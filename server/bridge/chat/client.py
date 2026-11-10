# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""`zf chat`: chat with the agent from a terminal, through the same /app channel as the app.

Enter sends a message and the answer is written as it arrives. Each tool call is one line;
permission requests ask once, always or deny, and a plan asks to be approved. Ctrl+C stops the turn
(twice in a row quits). Lines pasted together are sent as one message.
Commands: /model, /effort, /mode, /attach, /remote, /status, /new, /list, /help, /quit.
"""
from __future__ import annotations

import json
import mimetypes
import os
import queue
import select
import sys
import threading
import time
import urllib.parse
from pathlib import Path
from typing import Callable

from .. import localpeer, tlskey
from ..config import Config
from ..text import terminal_safe
from ..wire import VIA_REMOTE, Answer, DeniedBy, Err, In, Kind, Notice, Out, Outcome, Problem, RemoteOff, TurnError

HELP = ("Commands: /model NAME, /effort low|medium|high|xhigh|max, /mode manual|acceptEdits|plan|auto, "
        "/attach FILE (sent with your next message; /attach alone lists them, /attach clear drops them), "
        "/remote on|off (Remote Control: also from the Claude app), "
        "/status, /new, /list, /delete (this conversation, for good), /quit. Ctrl+C stops the agent's turn. zf commands (zf restart, zf status…) go in another terminal.")
MODE_NAMES = {"default": "Manual", "acceptEdits": "Accept edits", "plan": "Plan", "auto": "Auto"}
MODE_ALIASES = {"manual": "default", "default": "default", "acceptedits": "acceptEdits", "accept": "acceptEdits",
                "plan": "plan", "auto": "auto"}
MODEL_REASONS = {"usage_credits": "your plan bills it from usage credits",
                 "unchecked": "the server cannot check that your subscription covers it",
                 "not_signed_in": "the agent is not signed in to its provider"}
PROBLEMS = {
    Problem.NOT_CONFIGURED: "the agent is not set up: run zf agent setup",
    Problem.AGENT_MISSING: "claude was not found: run zf agent setup again",
    Problem.CHECKING: "the server is still checking claude; try again in a moment",
    Problem.NOT_LOGGED_IN: "claude is not signed in: run claude, type /login, then zf restart",
    Problem.NOT_SUBSCRIPTION: "claude does not use a subscription login and free only is on, so chats are refused",
    Problem.UNSAFE_AGENT_SETTINGS: "the agent's settings could make it leak, act without asking you, or pay while "
                                   "free only is on (an API key, a permission that allows, or a setting the server "
                                   "did not write): run zf agent setup",
    Problem.AGENT_VERSION: "the agent's version is not one this server supports: run zf agent setup",
    Problem.STOPPING: "the server is stopping",
    Problem.REMOTE_STARTING: "Remote Control is still starting; try again in a moment",
    Problem.REMOTE_ENDING: "Remote Control is turning off",
    Problem.AGENT_FOLDER_UNTRUSTED: "Claude Code does not trust the agent folder yet: run zf agent setup",
}
REMOTE_ENDS = {RemoteOff.OFF: "turned off", RemoteOff.IDLE: "8 hours without activity",
               RemoteOff.SERVER_RESTARTED: "the server restarted", RemoteOff.SERVER_STOPPED: "the server stopped",
               RemoteOff.EXITED: "the process ended", RemoteOff.DELETED: "the conversation was deleted",
               RemoteOff.COULD_NOT_START: "it could not start"}


def short(value: object, limit: int = 80) -> str:
    text = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False)
    text = " ".join(text.split())
    return text if len(text) <= limit else text[:limit - 1] + "…"


def describe_tool(name: str, tool_input: object) -> str:
    name = name.removeprefix("mcp__flipper__")
    if isinstance(tool_input, dict):
        if isinstance(tool_input.get("keys"), list):
            return f"{name} {' '.join(map(str, tool_input['keys']))}"
        for key in ("command", "file_path", "path", "url", "pattern"):
            if isinstance(tool_input.get(key), str):
                return f"{name} {short(tool_input[key], 70)}"
        if not tool_input:
            return name
    return f"{name} {short(tool_input, 70)}"


def size(n: int) -> str:
    return f"{n / (1024 * 1024):.1f} MB" if n >= 1024 * 1024 else f"{max(1, round(n / 1024))} KB"


def describe_attachment(meta: dict) -> str:
    dims = f" {meta['width']}×{meta['height']}" if meta.get("width") else ""
    return f"[{meta['kind']} {meta['name']} {size(meta['bytes'])}{dims}]"


def describe_notice(code: str, data: dict) -> str:
    if code == Notice.RATE_LIMIT:
        resets = data.get("resetsAt")
        when = f", resets at {time.strftime('%H:%M', time.localtime(resets))}" if isinstance(resets, (int, float)) else ""
        return f"subscription usage limit: {data.get('status', '?')}{when}"
    if code == Notice.REMOTE_OFF:
        return f"Remote Control is off ({REMOTE_ENDS.get(data.get('reason'), data.get('reason'))})"
    if code == Notice.REMOTE_PERMISSION:
        tool = f" ({data['tool']})" if data.get("tool") else ""
        return f"a permission waits for its answer on the other device{tool}"
    texts = {Notice.REMOTE_ON: "Remote Control is on: the conversation can also be written from the Claude app",
             Notice.AUTO_UNAVAILABLE: "automatic mode is not available: this turn ran in Manual",
             Notice.FLIPPER_MCP_FAILED: "the Flipper tools did not connect for this turn",
             Notice.API_RETRY: "Claude's service did not answer; retrying",
             Notice.COMPACTED: "the conversation was compacted to fit"}
    return texts.get(code, f"{code} {json.dumps(data) if data else ''}".rstrip())


def image_limit_text(data: dict) -> str:
    """turn.end's image_limit: the model's limit, and whether the server freed the conversation."""
    limit = data.get("limit")
    head = (f"this model takes at most {limit} screen images per conversation, and this one reached it."
            if isinstance(limit, int) else "this conversation holds more screen images than this model takes.")
    if data.get("compacted"):
        return head + ' The conversation was summarized to make room: send your message again, or "continue".'
    return head + " Start a new conversation with /new."


class ChatClient:
    def __init__(self, ws, out: Callable[[str], None] | None = None, ask: Callable[[str], str] | None = None,
                 upload: Callable[[str, Path], dict] | None = None, agents: list | None = None):
        self.ws = ws
        self.agents = agents or []  # hello's agents, until chat.status brings them
        self.upload = upload
        self.pending: list[dict] = []  # attachments for the next message
        raw = out or (lambda text: (sys.stdout.write(text), sys.stdout.flush()))
        self.write = lambda text: raw(terminal_safe(text))  # everything printed: the agent's text is untrusted
        self.ask = ask or (lambda prompt: input(prompt))  # looked up per call, so tests can patch input
        self.terminal = ask is None and sys.stdin.isatty()
        self.inbox: queue.Queue = queue.Queue()
        self.conversation: dict | None = None
        self.conversations: list[dict] = []
        self.status: dict = {}
        self.streamed: dict[str, int] = {}
        self.tools: dict[str, str] = {}
        self.last_info: tuple | None = None
        threading.Thread(target=self._read, daemon=True).start()

    def say(self, line: str = "") -> None:
        self.write(line + "\n")

    def _read(self) -> None:
        try:
            for raw in self.ws:
                self.inbox.put(json.loads(raw))
        except Exception:
            pass
        self.inbox.put(None)

    def send(self, kind: str, **fields) -> None:
        self.ws.send(json.dumps({"type": kind, **fields}))

    def next(self, timeout: float | None = None) -> dict | None:
        message = self.inbox.get(timeout=timeout)
        if message is None:
            raise ConnectionError("The server closed the connection.")
        if message["type"] == Out.CHAT_STATUS:
            self.status = message
        elif message["type"] == Out.CHAT_CONVERSATIONS:
            self.conversations = message["items"]
        elif message["type"] == Out.CHAT_CONVERSATION and self.conversation and message["item"]["id"] == self.conversation["id"]:
            self.conversation = message["item"]
        return message

    def wait_for(self, kind: str, timeout: float = 15, match: Callable[[dict], bool] = lambda m: True) -> dict:
        deadline = time.monotonic() + timeout
        while True:
            message = self.next(max(0.01, deadline - time.monotonic()))
            if message["type"] == kind and match(message):
                return message
            if message["type"] == Out.ERROR:
                raise RuntimeError(self.error_text(message))

    def agent(self, agent: str | None = None) -> dict:
        """An agent's entry, as chat.status last had it: the open conversation's (or the default one) when None."""
        agents = self.status.get("agents") or self.agents
        if agent is None:
            agent = (self.conversation or {}).get("agent") or (self.status.get("settings") or {}).get("default_agent")
        return next((a for a in agents if a.get("id") == agent), agents[0] if agents else {})

    @staticmethod
    def error_text(message: dict) -> str:
        code, data = message.get("error"), message.get("data") or {}
        if code == Err.NOT_READY:
            return PROBLEMS.get(data.get("problem"), f"not ready ({data.get('problem')})")
        if code == Err.BUSY:
            return "Claude is busy in another conversation; stop it there or wait."
        if code == Err.BAD_MODEL:
            return f"unknown model; choose one of {', '.join(data.get('models') or [])}"
        if code == Err.BAD_EFFORT:
            return f"this model accepts {', '.join(data.get('efforts') or []) or 'no effort level'}"
        if code == Err.MODEL_UNAVAILABLE:
            return f"{data.get('model')} is not available now; choose another with /model"
        if code == Err.BAD_ATTACHMENT:
            return "an attachment is no longer on the server (unsent uploads go after an hour); attach it again"
        if code == Err.TOO_LARGE:
            return "the images of this message are too large together; send fewer at a time"
        if code == Err.REMOTE_COMMAND:
            return "with Remote Control on, a message can't start with / ! or #"
        if code == Err.REMOTE_SETTINGS:
            return "with Remote Control on, the model, effort and mode stay as they are; turn it off to change them"
        if code == Err.REMOTE_ATTACHMENTS:
            return "with Remote Control on, this server can't send these attachments; turn it off to send them"
        if code == Err.REMOTE_BUSY:
            return f"Remote Control is on in another conversation ({str(data.get('conversation'))[:8]}); turn it off there first"
        if code == Err.BAD_AGENT:
            return f"unknown agent; choose one of {', '.join(data.get('agents') or [])}"
        if code == Err.REMOTE_UNSUPPORTED:
            return "this conversation's agent has no Remote Control"
        if code == Err.EXPIRED:
            return "Claude Code no longer has this conversation (deleted after its retention); start a new one with /new."
        return f"{code} {json.dumps(data) if data else ''}".strip()

    # ------------------------------------------------------------------ choosing a conversation
    def start(self, new: bool = False, mode: str | None = None, prefix: str | None = None) -> None:
        self.send(In.CHAT_LIST)
        self.wait_for(Out.CHAT_CONVERSATIONS)
        problem = self.agent().get("problem")
        if problem:
            self.say(f"Note: {PROBLEMS.get(problem, problem)}.")
        if prefix:
            matches = [c for c in self.conversations if c["id"].startswith(prefix)]
            if len(matches) != 1:
                raise RuntimeError(f"{len(matches)} conversations start with {prefix!r}; see zf chat --list.")
            self.open(matches[0])
        elif new or not self.conversations:
            self.new(mode)
        else:
            self.open(self.conversations[0])

    def new(self, mode: str | None = None) -> None:
        known = {c["id"] for c in self.conversations} | ({self.conversation["id"]} if self.conversation else set())
        self.send(In.CHAT_NEW, **({"mode": mode} if mode else {}))
        self.conversation = self.wait_for(Out.CHAT_CONVERSATION, match=lambda m: m["item"]["id"] not in known)["item"]
        self.say(f"New conversation ({self.settings_line()}). {HELP}")

    def delete(self) -> None:
        """Delete the open conversation for good, after a yes; then start a new one."""
        if not self.conversation:
            return self.say("No conversation open.")
        title = self.conversation["title"] or "(untitled)"
        answer = self.ask(terminal_safe(f"Delete \"{title}\" for good: its messages, attachments and the agent's own copy? [y/N] "))
        if answer.strip().lower() not in ("y", "yes", "s", "si", "sí"):
            return self.say("Kept.")
        cid = self.conversation["id"]
        self.send(In.CHAT_DELETE, conversation=cid)
        self.wait_for(Out.CHAT_DELETED, match=lambda m: m["conversation"] == cid, timeout=60)
        self.conversations = [c for c in self.conversations if c["id"] != cid]
        self.conversation = None
        self.say("Deleted.")
        self.new()

    def open(self, conversation: dict) -> None:
        self.conversation = conversation
        self.say(f"Conversation: {conversation['title'] or '(untitled)'} · {self.settings_line()} · {conversation['id'][:8]}")
        self.send(In.CHAT_OPEN, conversation=conversation["id"], limit=30)
        history = self.wait_for(Out.CHAT_HISTORY)
        if history["more"]:
            self.say("…")
        for event in history["events"]:
            self.render(event, live=False)
        self.say(HELP)

    def settings_line(self, c: dict | None = None) -> str:
        c = c or self.conversation
        return f"{c.get('model')} · effort {c.get('effort') or 'none'} · {MODE_NAMES.get(c.get('mode'), c.get('mode'))}"

    def show_status(self) -> None:
        agent = self.agent()
        if agent.get("name"):
            self.say(f"Agent: {agent['name']} {agent.get('version') or '(version not checked yet)'}")
        self.say(f"This conversation: {self.settings_line()}.")
        remote = self.status.get("remote")
        if remote:
            here = remote["conversation"] == self.conversation["id"]
            self.say(f"Remote Control: {remote['state']} " + ("here" if here else f"in {remote['conversation'][:8]}")
                     + (", connected" if remote.get("connected") else ""))
        models = agent.get("models") or []
        offered = [m for m in models if m.get("available", True)]
        self.say("Models: " + "; ".join(f"{m['value']} ({', '.join(m['efforts']) or 'no effort levels'}"
                                        + (", paid" if m.get("paid") is True else "") + ")" for m in offered))
        others = [m for m in models if not m.get("available", True)]
        if others:
            self.say("Not offered: " + "; ".join(
                f"{m['value']} ({MODEL_REASONS.get(m.get('reason'), m.get('reason'))})" for m in others))

    def configure(self, **change) -> None:
        cid = self.conversation["id"]
        self.send(In.CHAT_SET, conversation=cid, **change)
        try:
            self.conversation = self.wait_for(Out.CHAT_CONVERSATION, match=lambda m: m["item"]["id"] == cid and all(
                m["item"].get(k) == v for k, v in change.items()))["item"]
        except RuntimeError as e:
            self.say(str(e))
            return
        self.say(f"Now: {self.settings_line()} (from the next message).")

    def remote(self, value: str) -> None:
        """/remote on|off: Remote Control for this conversation."""
        if value not in ("on", "off"):
            remote = self.status.get("remote")
            self.say("Use /remote on or /remote off." + (f" Now: on in {remote['conversation'][:8]}." if remote else ""))
            return
        cid = self.conversation["id"]
        self.send(In.CHAT_REMOTE, conversation=cid, on=value == "on")
        want = (lambda m: (m.get("remote") or {}).get("conversation") == cid and m["remote"]["state"] == "on") \
            if value == "on" else (
            lambda m: (m.get("remote") or {}).get("conversation") != cid)
        try:
            self.wait_for(Out.CHAT_STATUS, timeout=30, match=want)
        except (RuntimeError, queue.Empty) as e:
            self.say(str(e) or "No answer from the server.")
            return
        self.say("Remote Control is on: this conversation can also be written from the Claude app." if value == "on"
                 else "Remote Control is off.")

    def list(self) -> None:
        for c in self.conversations:
            when = time.strftime("%Y-%m-%d %H:%M", time.localtime(c["updated"]))
            flags = "".join(f for f, on in (("*", c["pinned"]), ("♥", c["favorite"]),
                                            ("R", c.get("remote"))) if on)
            self.say(f"{c['id'][:8]}  {when}  {c['mode']:<7} {flags:<2} {c['title'] or '(untitled)'}")

    # ------------------------------------------------------------------ events
    def render(self, e: dict, live: bool = True) -> None:
        kind = e.get("kind")
        if kind == Kind.USER:
            if not live or e.get("via") == VIA_REMOTE:
                self.say(f"> {e['text']}" + ("  (from another device)" if e.get("via") == VIA_REMOTE else ""))
            if e.get("attachments"):
                self.say("  " + " ".join(describe_attachment(a) for a in e["attachments"]))
        elif kind == Kind.TEXT:
            done = self.streamed.pop(e["id"], None)
            if done is None or done < len(e["text"]):
                self.say(e["text"] if done is None else e["text"][done:])
            else:
                self.say()
        elif kind == Kind.TOOL:
            self.tools[e["id"]] = e["name"]
            self.say(f"  · {describe_tool(e['name'], e.get('input'))}")
        elif kind == Kind.TOOL_RESULT:
            parts = []
            if e.get("error"):
                parts.append(f"error: {short(e.get('text') or '', 100)}")
            if e.get("ms") is not None:
                parts.append(f"{e['ms'] / 1000:.1f} s")
            if e.get("screen"):
                parts.append(f"frame {e['screen']['seq']}")
            if parts:
                self.say("    " + " · ".join(parts))
        elif kind == Kind.DENIED:
            reason = f": {e['reason']}" if e.get("reason") else ""
            by = "automatic mode" if e.get("by") == DeniedBy.AUTO else "this conversation's mode"
            self.say(f"  ✗ {describe_tool(e['tool'], e.get('input'))} blocked by {by}{reason}")
        elif kind == Kind.PERMISSION and not live:
            self.say(f"  ? permission asked for {describe_tool(e['tool'], e.get('input'))}")
        elif kind == Kind.PERMISSION_END and not live:
            self.say(f"    answer: {e['answer']}")
        elif kind == Kind.TURN_INFO and live:
            info = (e.get("model"), e.get("effort"), e.get("mode"))
            if self.last_info is not None and info != self.last_info:
                self.say(f"  ({e.get('model')} · effort {e.get('effort') or 'none'} · "
                         f"{MODE_NAMES.get(e.get('mode'), e.get('mode'))})")
            self.last_info = info
        elif kind == Kind.NOTICE:
            self.say(f"  ! {describe_notice(e['code'], e.get('data') or {})}")
        elif kind == Kind.TURN_END:
            if live and e["outcome"] == Outcome.DONE and self.last_info and self.last_info[2] == "plan":
                self.say("  (Plan mode changes nothing. To carry the plan out: /mode manual, accept or auto, "
                         "then tell Claude to go ahead.)")
            if e["outcome"] == Outcome.INTERRUPTED and e.get("error") == TurnError.PERMISSION_STALE:
                self.say("  (stopped: a permission on another device got no answer for too long, so the server "
                         "declined it)")
            elif e["outcome"] == Outcome.INTERRUPTED:
                self.say("  (stopped)")
            elif e["outcome"] == Outcome.ERROR and e.get("error") == TurnError.IMAGE_LIMIT:
                self.say("  turn failed: " + image_limit_text(e.get("data") or {}))
            elif e["outcome"] == Outcome.ERROR:
                self.say(f"  turn failed: {e['error']} {json.dumps(e['data']) if e.get('data') else ''}".rstrip()
                         + (" (details: zf logs)" if e.get("error") == TurnError.AGENT_FAILED else ""))

    def delta(self, message: dict) -> None:
        so_far = self.streamed.get(message["id"], 0)
        if message["offset"] != so_far:
            return  # one was lost: the full text comes with the text event
        self.streamed[message["id"]] = so_far + len(message["text"])
        self.write(message["text"])

    def permission(self, e: dict) -> None:
        rules: list[str] = []
        if e["tool"] == "ExitPlanMode":
            plan = (e.get("input") or {}).get("plan") if isinstance(e.get("input"), dict) else None
            self.say("\n  Claude proposes this plan:\n" + "\n".join("    " + line for line in str(plan or "").splitlines()))
            options = "[o] approve, [d] reject"
        else:
            self.say(f"\n  Claude asks to run {describe_tool(e['tool'], e.get('input'))}")
            tool_input = e.get("input")
            if e["tool"] == "Bash" and isinstance(tool_input, dict) and isinstance(tool_input.get("command"), str):
                self.say("  command: " + json.dumps(tool_input["command"], ensure_ascii=False))  # exact, never cut
            else:  # whole, as the app's cards show it (S1b): the owner approves what they see whole
                self.say("  input: " + json.dumps(tool_input, ensure_ascii=False, indent=2).replace("\n", "\n  "))
            if e.get("whole") is False:
                self.say("  Too long to show whole (cut above), so it can only be denied.")
            if e.get("always"):
                rules = [r for r in e.get("rules") or [] if isinstance(r, str)]
                self.say("  [a]lways would allow, in this conversation:"
                         + "".join(f"\n    {json.dumps(r, ensure_ascii=False)}" for r in rules))
            options = ("[d]eny" if e.get("whole") is False else
                       "[o]nce, [a]lways, [d]eny" if e.get("always") else "[o]nce, [d]eny")
        left = max(0, int(e["deadline"] - time.time()))
        try:
            choice = self.ask(f"  Allow? {options} ({left} s): ").strip().lower()
        except EOFError:
            choice = "d"
        if time.time() > e["deadline"]:
            self.say("  Too late: the server already denied it.")
            return
        answer = {"o": Answer.ONCE, "a": Answer.ALWAYS, "d": Answer.DENY}.get(choice[:1], Answer.DENY)
        if answer == Answer.ALWAYS and not e.get("always"):
            answer = Answer.ONCE
        if e.get("whole") is False:
            answer = Answer.DENY  # the server takes nothing else for it
        if answer == Answer.ALWAYS:  # exactly the rules shown above: the server keeps no others
            self.send(In.CHAT_PERMISSION_ANSWER, id=e["id"], answer=answer, rules=rules)
        else:
            self.send(In.CHAT_PERMISSION_ANSWER, id=e["id"], answer=answer)

    def wait_turn(self) -> None:
        cid = self.conversation["id"]
        interrupts = 0
        while True:
            try:
                try:
                    message = self.next(0.2)
                except queue.Empty:
                    continue
                if message["type"] == Out.ERROR:
                    self.say(f"  {self.error_text(message)}")
                    if message.get("request") in (None, In.CHAT_SEND):
                        return
                    continue
                if message.get("conversation") != cid:
                    continue
                if message["type"] == Out.CHAT_DELTA:
                    self.delta(message)
                elif message["type"] == Out.CHAT_EVENT:
                    if message["kind"] == Kind.PERMISSION:
                        self.permission(message)
                    else:
                        self.render(message)
                    if message["kind"] == Kind.TURN_END:
                        return
            except KeyboardInterrupt:
                interrupts += 1
                if interrupts > 1:
                    raise
                self.say("\n  stopping the turn… (Ctrl+C again to quit)")
                self.send(In.CHAT_INTERRUPT, conversation=cid)

    # ------------------------------------------------------------------ the loop
    def read_message(self) -> str:
        line = self.ask("> ")
        if self.terminal:  # lines pasted together arrive at once: send them as one message
            more = []
            while select.select([sys.stdin], [], [], 0.05)[0]:
                extra = sys.stdin.readline()
                if not extra:
                    break
                more.append(extra.rstrip("\n"))
            if more:
                line = "\n".join([line, *more])
        return line

    def repl(self) -> None:
        while True:
            try:
                line = self.read_message()
            except (EOFError, KeyboardInterrupt):
                self.say()
                return
            text = line.strip()
            if not text:
                continue
            if text in ("/quit", "/exit"):
                return
            if text == "/help":
                self.say(HELP)
            elif text == "/new":
                self.new()
            elif text == "/delete":
                self.delete()
            elif text == "/list":
                self.send(In.CHAT_LIST)
                self.wait_for(Out.CHAT_CONVERSATIONS)
                self.list()
            elif text == "/status":
                self.send(In.CHAT_LIST)
                self.wait_for(Out.CHAT_CONVERSATIONS)
                self.show_status()
            elif text.split()[0] == "/remote" and "\n" not in text:
                self.remote(text[len("/remote"):].strip().lower())
            elif text.split()[0] == "/attach" and "\n" not in text:
                self.attach(text[len("/attach"):].strip())
            elif text.split()[0] in ("/mode", "/model", "/effort") and "\n" not in text:
                command, _, value = text.partition(" ")
                value = value.strip()
                if command == "/mode":
                    if value.lower() not in MODE_ALIASES:
                        self.say("Use /mode manual, /mode acceptEdits, /mode plan or /mode auto.")
                        continue
                    self.configure(mode=MODE_ALIASES[value.lower()])
                elif command == "/model":
                    if not value:
                        self.show_status()
                        continue
                    self.configure(model=value.lower())
                else:
                    if not value:
                        self.say("Use /effort low, medium, high, xhigh or max.")
                        continue
                    self.configure(effort=value.lower())
            elif text.startswith("/") and "\n" not in text:
                self.say(f"Unknown command {text.split()[0]}. {HELP}")
            else:
                ids = [a["id"] for a in self.pending]
                self.pending = []
                self.send(In.CHAT_SEND, conversation=self.conversation["id"], text=line,
                          **({"attachments": ids} if ids else {}))
                self.wait_turn()

    def attach(self, value: str) -> None:
        if not value:
            self.say("Attached for your next message: " + (" ".join(describe_attachment(a) for a in self.pending)
                                                            if self.pending else "nothing. Use /attach FILE."))
        elif value == "clear":
            self.pending = []
            self.say("No attachments for your next message.")
        elif self.upload is None:
            self.say("This client cannot upload files.")
        else:
            path = Path(value).expanduser()
            if not path.is_file():
                self.say(f"No file at {path}.")
                return
            try:
                meta = self.upload(self.conversation["id"], path)
            except RuntimeError as e:
                self.say(f"Not attached: {e}")
                return
            self.pending.append(meta)
            self.say(f"Attached {describe_attachment(meta)}: it goes with your next message.")


def upload(c: Config, conversation: str, path: Path, pin: str) -> dict:
    """POST the file to /app/upload with the app token, once localpeer has checked that the listener is ours;
    the server checks the file and keeps it."""
    query = urllib.parse.urlencode({"conversation": conversation, "name": path.name})
    media_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    try:
        status, body = localpeer.request(c.host, c.phone_port, "POST", f"/app/upload?{query}", c.app_token,
                                         body=path.read_bytes(), content_type=media_type, timeout=60,
                                         tls=tlskey.client_wrap(pin))
    except (localpeer.NotOurs, tlskey.Mismatch) as e:
        raise RuntimeError(str(e)) from None
    except OSError as e:
        raise RuntimeError(f"could not reach the server ({type(e).__name__})") from None
    try:
        data = json.loads(body)
    except ValueError:
        data = None
    if not 200 <= status < 300:
        detail = data.get("message") if isinstance(data, dict) else None
        raise RuntimeError(detail or f"HTTP {status}")
    if not isinstance(data, dict):
        raise RuntimeError("the server's answer is not JSON")
    return data


def run(c: Config, pin: str, new: bool = False, mode: str | None = None, prefix: str | None = None,
        only_list: bool = False, test: bool = False, lang: str | None = None) -> int:
    """The chat over /app. The socket is checked to be our own user's listener, then its TLS key against pin (the key
    in the data folder), before the token is sent."""
    from websockets.sync.client import connect

    try:
        sock = localpeer.connect(c.host, c.phone_port, timeout=5)  # checked before the token is sent
        try:
            sock = tlskey.client_wrap(pin)(sock)
        except BaseException:
            sock.close()
            raise
        # The socket already speaks TLS, checked against the pin: websockets gets the ws:// form of the address,
        # so it does not wrap it again with a context that would check names instead of the key.
        with connect(c.app_url().replace("wss://", "ws://", 1), sock=sock,
                     additional_headers={"Authorization": f"Bearer {c.app_token}"},
                     max_size=16 * 1024 * 1024, open_timeout=5) as ws:
            hello = json.loads(ws.recv(timeout=5))
            if "chat" not in (hello.get("features") or []):
                print("This server has no chat: update it (zf update).")
                return 1
            client = ChatClient(ws, upload=lambda cid, path: upload(c, cid, path, pin), agents=hello.get("agents"))
            if only_list:
                client.send(In.CHAT_LIST)
                client.wait_for(Out.CHAT_CONVERSATIONS)
                client.list()
                return 0
            client.start(new=new or test, mode="default" if test else mode, prefix=None if test else prefix)
            if test:
                client.say("Starting the guided test: Claude explains each step and waits for you.")
                client.send(In.CHAT_SEND, conversation=client.conversation["id"], text=guided_test(lang))
                client.wait_turn()
            client.repl()
    except (localpeer.NotOurs, tlskey.Mismatch) as e:
        print(f"Not connecting: {e}.")
        return 1
    except (OSError, TimeoutError) as e:
        print(f"Could not reach the server at {c.app_url()} ({type(e).__name__}). Is it running? zf status")
        return 1
    except (RuntimeError, ConnectionError) as e:
        print(terminal_safe(str(e)))
        return 1
    except KeyboardInterrupt:
        print()
    return 0


# ---------------------------------------------------------------------------- zf chat --test
# The agent guides the user through the chat's manual test, one step at a time. The checks are the list in
# STEPS below. The first message of the conversation carries them; Claude explains
# each step, waits for the user's answer and keeps the tally. It never tries to provoke a block from automatic mode;
# what it runs only touches fb-test*.txt files.

STEPS = """\
All the commands below work on test files named fb-test*.txt in this folder; nothing else is touched.
In Manual mode Claude Code runs reads and read-only commands (ls, pwd, date…) without asking, so the
permission steps use commands that write.

1. Screen. Call flipper_screen and describe what the Flipper shows in one sentence. Ask the owner whether
   zf chat showed the tool line with a frame number, without asking for permission.
2. Memory. Choose a short code word, tell it to the owner, and ask them to reply with anything. In your
   next reply, repeat the code word without looking at earlier messages: that proves the turn resumed.
3. Restart. Ask the owner to leave zf chat with /quit, run `zf restart`, run `zf chat` again (it opens
   this same conversation) and write "back". Then say the code word again.
4. Manual: once and always. Run `touch fb-test.txt` with Bash; it must ask and the owner answers "o".
   Run exactly the same command in your next turn: it must ask again; the owner answers "a" (always).
   Run it once more in the turn after: it must not ask. Claude Code offers "always" only for commands it
   suggests a rule for (not for one with a redirect such as `echo x > file`): if the owner says only once
   and deny were offered, note which options appeared and go on.
5. Manual: deny. Run `rm fb-test.txt`; the owner answers "d". Check with `ls` that the file is still there.
6. Manual: no answer. Run `mv fb-test.txt fb-test-2.txt`; the owner must not answer for two minutes, then
   press Enter. It must be refused because nobody answered in time, and you must go on.
7. Accept edits. Ask the owner to type `/mode accept`. In your next turn overwrite fb-test.txt with the
   word "editado" using the Write tool: it must not ask. Then run `echo x > fb-test-3.txt` with Bash and
   tell the owner whether it asked.
8. Plan. Ask the owner to type `/mode plan`. In your next turn call flipper_screen (Flipper tools should
   work in every mode) and then plan how to delete fb-test.txt: in Plan mode nothing may change, so present
   the plan and stop. If zf chat asks to approve a plan, the owner approves it. Then ask the owner to type
   `/mode accept` and say "go ahead"; carry the plan out in that turn.
9. Automatic. Ask the owner to type `/mode auto`. In your next turn run `touch fb-test-4.txt`: it should
   run without asking. Do not try anything risky on purpose.
10. Model and effort. Ask the owner to type `/model sonnet` and `/effort low`, then send any message:
    zf chat must show a line with the new model and effort on that turn. Then ask them to go back with
    `/model opus` and `/effort medium`.
11. Models. Ask the owner to type `/status`: it lists the models offered, the ones that are not and why, and
    whether Fable is offered.
12. Interrupt. Tell the owner you will write the numbers from 1 to 300, one per line, and that they must
    press Ctrl+C once while you write. Then write them. In the next turn, ask whether the writing stopped.
13. Busy. Tell the owner to open a second terminal and run `zf chat --new`. When they are ready, write the
    numbers from 1 to 600, one per line; while you write, they send any message from the second terminal:
    it must answer that Claude is busy. Then they quit that terminal with /quit.
14. Operator pause. Ask the owner to type `/mode manual`, open the web view in a browser and press Pause.
    Then call flipper_press with the single key BACK: it must be refused because the agent is paused.
    Ask the owner to press Resume afterwards.
15. Clean up. Delete the fb-test*.txt files with Bash (the owner answers "o" if it asks).
16. Status and logs. Ask the owner to run, in another terminal, `zf status` (it must show the Claude and
    Chat lines) and `zf logs -n 100` (it must contain no tokens and none of the texts of this chat).
"""


def guided_test(lang: str | None = None) -> str:
    lang = (lang or os.environ.get("LC_ALL") or os.environ.get("LANG") or "").lower()
    language = "Spanish" if lang.startswith("es") else "English"
    return f"""This is the guided manual test of the Zeffin chat. I am the owner, chatting with you from \
`zf chat` in a terminal on the server; the app's chat does not exist yet. Write to me in {language}, in short \
sentences, and switch to my language if I answer in another one. Say where each action happens (in this zf chat, in another terminal, in the browser).

Go one step at a time: explain the step, do your part, then stop and wait for my answer. Keep a tally: \
each step passed, failed (with a one-line note of what I saw) or skipped. Only touch the fb-test*.txt files \
the steps name, and do not transmit anything with the Flipper. When all steps are done, print the tally as a short list I can \
paste into an issue or a pull request.

The steps:

{STEPS}
Start with step 1 now."""
