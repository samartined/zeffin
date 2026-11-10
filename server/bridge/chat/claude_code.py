# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""Claude Code behind the Agent port (bridge/agent.py): its command lines and environment (command.py), its stream-json
output (stream.py), its hooks (permissions.py, remote.py), its models (catalog.py) and its files.

- A turn is one `claude -p` process: its message on stdin, its output read line by line and turned into the port's
  events. The subscription guard is here, the "free only" switch's "on" state (config.free_only): a turn whose init
  reports an API key source, a 1M-context model or a model the catalog does not offer (one that may bill usage
  credits) is killed at once (Guard), and so is one that would go on with usage credits. Off, the user's own login
  runs, whatever it bills; never with --bare.
- The interactive session is `claude --remote-control` under a pseudo-terminal (remote.py), followed by its http hooks.

The composition root (app.py) builds it from the server's Config and hands it to ChatManager.
"""
from __future__ import annotations

import asyncio
import contextlib
import json
import logging
import os
import signal
import time
from pathlib import Path
from typing import Any, AsyncIterator, Protocol

from ..agent import (AgentTurn, Delta, Denied, HookKind, HookRoutes, Launch, Limit, Output, PermissionAsk, RemoteHook,
                     RemoteRequest, Started, TurnEnd, TurnEvent, TurnRequest)
from ..text import cut, one_line
from ..wire import SEQ, AgentId, Guard, Mode, Outcome, Problem, TurnError
from . import catalog, command, permissions, remote
from .stream import FLIPPER_PREFIX, TEXT_LIMIT, StreamParser, _items, permission_input, tool_input

log = logging.getLogger("zeffin.chat")

LINE_LIMIT = 16 * 1024 * 1024
STDERR_TAIL = 4096
STDERR_LOGGED = 300  # characters of claude's stderr in the server log when a turn fails
REMOTE_NAME = "Zeffin"  # the Remote Control session's name when the conversation has no title
NAME = "Claude Code"  # hello's agent name
# Its permission modes, in its order, with its own English names (code.claude.com/docs/en/permission-modes).
MODE_LABELS = {Mode.DEFAULT: "Manual", Mode.ACCEPT_EDITS: "Accept edits", Mode.PLAN: "Plan", Mode.AUTO: "Auto"}


class Settings(Protocol):
    """What it reads from the server's Config, live."""
    claude_bin: str
    agent_dir: str
    mcp_port: int
    mcp_token: str
    free_only: bool

    def mcp_url(self) -> str: ...


class ClaudeCode:
    id = AgentId.CLAUDE_CODE

    def __init__(self, config: Settings) -> None:
        self.config = config
        self.remote = RemoteControl(self)
        self.health: dict = {"version": None, "auth": None, "logged_in": False, "checked_at": None}
        self.catalog = catalog.build(None)

    @property
    def hooks_url(self) -> str:
        return f"http://127.0.0.1:{self.config.mcp_port}/hooks"

    # ---- health and the catalog
    @property
    def configured(self) -> bool:
        return bool(self.config.claude_bin)

    @property
    def free_only(self) -> bool:
        return self.config.free_only is True

    async def check(self) -> None:
        claude, free_only = self.config.claude_bin, self.free_only
        version = await asyncio.to_thread(command.claude_version, claude, 20, free_only)
        logged_in, method = await asyncio.to_thread(command.auth_status, claude, 20, free_only)
        subscription = method in command.SUBSCRIPTION_AUTH
        rows = None
        if logged_in and (subscription or not free_only):
            rows = await asyncio.to_thread(command.list_models, claude, self.config.agent_dir or None, 30, free_only)
        self.catalog = catalog.build(rows, free_only, subscription or not logged_in)
        self.health = {"version": version, "auth": method, "logged_in": logged_in, "checked_at": time.time()}

    def models(self) -> list[dict]:
        return self.catalog.models

    def identity(self) -> dict:
        return {"name": NAME, "version": self.health["version"]}

    def mode_options(self) -> list[dict]:
        return [{"value": str(mode), "label": label} for mode, label in MODE_LABELS.items()]

    def login(self) -> dict:
        method = self.health["auth"] if self.health["logged_in"] else None
        return {"method": method, "subscription": method in command.SUBSCRIPTION_AUTH}

    def problem(self) -> str | None:
        c = self.config
        if not c.agent_dir or not c.claude_bin or not Path(c.agent_dir).is_dir():
            return Problem.NOT_CONFIGURED
        if not os.access(c.claude_bin, os.X_OK):
            return Problem.AGENT_MISSING
        if command.agent_settings_problem(Path(c.agent_dir), self.free_only):
            return Problem.UNSAFE_AGENT_SETTINGS
        if self.health["checked_at"] is None:
            return Problem.CHECKING
        if self.health["version"] is None:
            return Problem.AGENT_MISSING
        if not self.health["logged_in"]:
            return Problem.NOT_LOGGED_IN
        if self.free_only and self.health["auth"] not in command.SUBSCRIPTION_AUTH:
            return Problem.NOT_SUBSCRIPTION
        return None

    def remote_problem(self) -> str | None:
        if command.is_trusted(Path(self.config.agent_dir)) is not True:
            # The interactive mode asks "Do you trust this folder?", which nobody would answer (zf agent setup trusts it).
            return Problem.AGENT_FOLDER_UNTRUSTED
        return None

    def summary_fields(self) -> dict:
        """zf status keeps Claude Code's version and login: the server's own CLI, not /app."""
        return {"claude": {"version": self.health["version"], "auth": self.health["auth"]},
                "service_env_billing_vars": command.billing_vars(os.environ) if self.free_only else []}

    def capabilities(self) -> dict:
        return {"remote": dict(remote.CAPABILITY), "attachments": True}

    def hook_routes(self) -> HookRoutes:
        return HookRoutes(header=command.BRIDGE_HEADER, request="permission-request", denied="permission-denied",
                          remote=(*command.REMOTE_HOOKS.values(), "session-start"))

    def forget(self, session: str) -> int:
        return command.delete_session(session)

    # ---- a -p turn
    def image_limit(self, model: str) -> int | None:
        return None  # Claude's API takes 100 images per request or more: Claude Code compacts on its own

    async def compact(self, request: TurnRequest) -> AgentTurn | None:
        return None

    async def start_turn(self, request: TurnRequest) -> ClaudeTurn:
        turn = ClaudeTurn(self, request)
        await turn.start()
        return turn

    def guard(self, model: str, init: dict) -> str | None:
        """Why a turn must be killed at once while free only is on; off, never (the user's login, the user's bill)."""
        if not self.free_only:
            return None
        if init.get("apiKeySource") != "none":
            return Guard.API_KEY_SOURCE
        # A 1M-context model, one the catalog does not offer, and any Fable model it does not list as available.
        if (catalog.LONG_CONTEXT in model.lower() or model in self.catalog.blocked
                or ("fable" in model.lower() and model not in self.catalog.allowed)):
            return Guard.MODEL
        return None

    # ---- the turn's hooks
    def read_permission(self, body: dict) -> PermissionAsk:
        rules = permissions.suggested_rules(body.get("permission_suggestions"))
        if not rules:
            rules = permissions.fallback_rules(body.get("tool_name"), body.get("tool_input"))
        if permissions.guarded_request(body.get("tool_name"), body.get("tool_input"), self.config.agent_dir):
            rules = []  # the agents' own configuration: no "always", every time through the card (ask_rules)
        shown, whole = permission_input(body.get("tool_input"))
        return PermissionAsk(session=body.get("session_id"), tool=str(body.get("tool_name") or "?"),
                             input=shown, rules=rules if whole else [], whole=whole,
                             flipper=str(body.get("tool_name") or "").startswith(FLIPPER_PREFIX))

    def permission_reply(self, answer: str, rules: list[str]) -> dict:
        return permissions.decision(answer, rules)

    def read_denied(self, body: dict) -> Denied:
        """The PermissionDenied hook: a tool the auto mode denied."""
        return _denied(body, body.get("reason"))


def _denied(data: dict, reason: object, by_hook: bool = False) -> Denied:
    tool_use = data.get("tool_use_id")
    return Denied(tool_use=tool_use if isinstance(tool_use, str) else None, tool=str(data.get("tool_name") or "?"),
                  input=tool_input(data.get("tool_input")), reason=reason[:500] if isinstance(reason, str) else None,
                  by_hook=by_hook)


def stream_denied(data: dict) -> Denied:
    """A system/permission_denied message, or an entry of the result's permission_denials (Claude Code 2.1.308).
    decision_reason_type "hook": the owner's own answer (or its timeout), which permission.end already shows."""
    return _denied(data, data.get("decision_reason"), by_hook=data.get("decision_reason_type") == "hook")


class ClaudeTurn:
    """One `claude -p` process and what it said."""

    def __init__(self, agent: ClaudeCode, request: TurnRequest) -> None:
        self.agent = agent
        self.request = request
        self.proc: asyncio.subprocess.Process | None = None
        self.parser = StreamParser()
        self.result: dict | None = None
        self.stderr = bytearray()
        self.stderr_task: asyncio.Task | None = None
        self.stdin = b""

    def _spec(self) -> command.TurnSpec:
        r, config = self.request, self.agent.config
        return command.TurnSpec(
            claude_bin=config.claude_bin, session_id=r.session, resume=r.resume, model=r.model, effort=r.effort,
            mode=r.mode, mcp_url=config.mcp_url(), hooks_url=self.agent.hooks_url,
            permission_timeout_s=r.permission_timeout_s, rules=list(r.rules),
            cleanup_days=command.user_cleanup_days(), input_json=bool(r.images), add_dirs=list(r.folders))

    def _stdin(self) -> bytes:
        """What the turn reads: the text, or one stream-json user message when it carries images."""
        r = self.request
        images = [(media_type, path.read_bytes()) for media_type, path in r.images]
        return (command.user_message(r.text, images) if images else r.text).encode()

    async def start(self) -> None:
        """Start the process (its stdin is written when its output is read): a turn that cannot start keeps the reason
        and ends at once."""
        env = command.turn_env(os.environ, hook_token=self.request.token, mcp_token=self.agent.config.mcp_token,
                               free_only=self.agent.free_only)
        try:
            self.stdin = await asyncio.to_thread(self._stdin)
        except OSError as e:
            self.stderr += f"attachment unreadable: {type(e).__name__}".encode()
            return
        try:
            self.proc = await asyncio.create_subprocess_exec(
                *command.build_command(self._spec()), cwd=self.agent.config.agent_dir, env=env,
                stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
                start_new_session=True, limit=LINE_LIMIT)
        except OSError as e:
            self.stderr += str(e).encode()

    async def lines(self) -> AsyncIterator[list[TurnEvent]]:
        proc = self.proc
        if proc is None:
            return

        async def read_stderr():
            while chunk := await proc.stderr.read(4096):
                self.stderr.extend(chunk)
                del self.stderr[:-STDERR_TAIL]

        self.stderr_task = asyncio.create_task(read_stderr())
        with contextlib.suppress(BrokenPipeError, ConnectionResetError):
            proc.stdin.write(self.stdin)
            await proc.stdin.drain()
            proc.stdin.close()
        while True:
            try:
                line = await proc.stdout.readline()
            except ValueError:  # a line over the limit: skip it
                continue
            if not line:
                break
            try:
                obj = json.loads(line)
            except ValueError:
                continue
            if not isinstance(obj, dict):
                continue
            yield [event for out in self.parser.feed(obj) if (event := self._event(out)) is not None]

    def _event(self, out) -> TurnEvent | None:
        d = out.data
        if out.kind == "delta":
            return Delta(block=d["id"], offset=d["offset"], text=d["text"])
        if out.kind == "init":
            return self._started(d)
        if out.kind == "event":
            flipper = d["kind"] == "tool" and d["name"].startswith(FLIPPER_PREFIX)
            seq = d.pop(SEQ, None) if d["kind"] == "tool.result" else None
            return Output(event=d, flipper=flipper, seq=seq)
        if out.kind == "denied":
            return stream_denied(d)
        if out.kind == "limit":
            # Going on would bill usage credits: killed while free only is on; off, the user's own choice.
            return Limit() if self.agent.free_only else None
        if out.kind == "result":
            self.result = d
        return None

    def _started(self, init: dict) -> Started:
        session = init.get("session_id") if isinstance(init.get("session_id"), str) else None
        model = str(init.get("model") or "")
        # An odd shape reads as missing: a server whose name is not text is not the bridge's.
        servers = {s["name"]: s.get("status") for s in _items(init.get("mcp_servers"))
                   if isinstance(s, dict) and isinstance(s.get("name"), str)}
        # Claude Code 2.1.308 does not report the effort in init (zf agent probe): the bridge shows the one asked for.
        return Started(session=session, model=model, mode=init.get("permissionMode"), effort=init.get("effort"),
                       version=init.get("claude_code_version"), mcp=servers.get("flipper"),
                       mcp_ok=servers.get("flipper") == "connected", guard=self.agent.guard(model, init))

    async def wait(self, timeout: float) -> bool:
        if self.proc is None:
            return True
        try:
            await asyncio.wait_for(self.proc.wait(), timeout)
            return True
        except asyncio.TimeoutError:
            return False

    async def end(self, waits: tuple[float, float]) -> None:
        proc = self.proc
        if proc is None or proc.returncode is not None:
            return
        with contextlib.suppress(ProcessLookupError):
            proc.send_signal(signal.SIGINT)
        for sig, wait in ((signal.SIGTERM, waits[0]), (signal.SIGKILL, waits[1])):
            try:
                await asyncio.wait_for(proc.wait(), wait)
                return
            except asyncio.TimeoutError:
                with contextlib.suppress(ProcessLookupError, PermissionError):
                    if sig == signal.SIGKILL:
                        os.killpg(proc.pid, signal.SIGKILL)
                    else:
                        proc.send_signal(sig)

    def kill(self) -> None:
        if self.proc is not None and self.proc.returncode is None:
            with contextlib.suppress(ProcessLookupError, PermissionError):
                os.killpg(self.proc.pid, signal.SIGKILL)

    async def drain(self) -> list[Denied]:
        if self.stderr_task is not None:
            with contextlib.suppress(asyncio.TimeoutError):
                await asyncio.wait_for(self.stderr_task, 2)
        if self.result is None:
            return []
        return [stream_denied(d) for d in _items(self.result.get("permission_denials")) if isinstance(d, dict)]

    def outcome(self) -> TurnEnd:
        result, stderr = self.result, self.stderr.decode(errors="replace")
        if result is not None and not result.get("is_error"):
            return TurnEnd(Outcome.DONE)
        if self.request.resume and "No conversation found" in stderr + json.dumps((result or {}).get("errors") or []):
            return TurnEnd(Outcome.ERROR, TurnError.EXPIRED)
        if self.parser.api_error:
            return TurnEnd(Outcome.ERROR, TurnError.API_ERROR, {"reason": self.parser.api_error})
        if result is not None and result.get("subtype") == "error_max_turns":
            return TurnEnd(Outcome.ERROR, TurnError.MAX_TURNS)
        exit_code = self.proc.returncode if self.proc is not None else None
        data = {"exit": exit_code, "subtype": (result or {}).get("subtype")}
        # What claude wrote to stderr stays here, in the server log: never on the phone.
        note = "claude failed (exit %s, subtype %s): %s" % (
            exit_code, data["subtype"], one_line(stderr.strip()[-STDERR_LOGGED:], STDERR_LOGGED) or "no stderr")
        return TurnEnd(Outcome.ERROR, TurnError.AGENT_FAILED, data, note)


class RemoteControl:
    """The interactive session (remote.py): `claude --remote-control` under a pseudo-terminal, followed by its hooks.
    The times are read from remote.py when used, so a test may change them there."""

    def __init__(self, agent: ClaudeCode) -> None:
        self.agent = agent

    @property
    def watch_every_s(self) -> float:
        return remote.WATCH_EVERY_S

    @property
    def start_limit_s(self) -> float:
        return remote.START_LIMIT_S

    @property
    def no_news_s(self) -> float:
        return remote.NO_NEWS_S

    @property
    def idle_end_s(self) -> float:
        return remote.IDLE_END_S

    def launch(self, request: RemoteRequest) -> Launch:
        config, hooks = self.agent.config, self.agent.hooks_url
        spec = command.TurnSpec(
            claude_bin=config.claude_bin, session_id=request.session, resume=request.resume, model=request.model,
            effort=request.effort, mode=request.mode, mcp_url=config.mcp_url(), hooks_url=hooks,
            rules=list(request.rules), cleanup_days=command.user_cleanup_days(), add_dirs=list(request.folders))
        name = request.title.lstrip("- ") or REMOTE_NAME  # the session's name in the Claude app; never a flag
        argv = command.build_remote_command(spec, name, remote.session_start_command(f"{hooks}/remote/session-start"))
        env = remote.remote_env(command.turn_env(os.environ, hook_token=request.token, mcp_token=config.mcp_token,
                                                 free_only=self.agent.free_only))
        return Launch(terminal=remote.Terminal(), argv=argv, env=env, stale_s=remote.permission_stale_s(env))

    async def start(self, launch: Launch) -> None:
        await launch.terminal.start(launch.argv, cwd=self.agent.config.agent_dir, env=launch.env)

    async def wait(self, launch: Launch) -> int | None:
        return await launch.terminal.proc.wait()

    def type(self, terminal: Any, text: str):
        return terminal.type_text(text)

    def interrupt(self, terminal: Any) -> None:
        terminal.key(remote.ESC)

    async def stop(self, terminal: Any, has_turn: bool, starting: bool,
                   waits: tuple[float, float] | None = None) -> None:
        if waits is None:
            await remote.stop_process(terminal, has_turn, starting=starting)
        else:
            await remote.stop_process(terminal, has_turn, starting=starting, waits=waits)

    def close(self, terminal: Any) -> None:
        terminal.close()

    def clean(self, text: str) -> str:
        return remote.clean(text)

    def refused(self, text: str) -> bool:
        return remote.refused_start(text)

    def message(self, text: str, files: list[str]) -> str | None:
        return remote.message(text, files)

    def matches(self, sent: str, sent_at: float, prompt: str) -> bool:
        return time.time() - sent_at <= remote.MATCH_WINDOW_S and remote.normal(prompt) in (
            remote.normal(sent), remote.normal(" ".join(sent.split("\n"))))

    def block(self) -> dict:
        return {"decision": "block", "reason": remote.BLOCK_REASON}

    def read_hook(self, event: str, body: dict) -> RemoteHook:
        """The hooks under /hooks/remote/<event> (command.REMOTE_HOOKS), as the port's RemoteHook."""
        if event == "user-prompt-submit":
            return RemoteHook(HookKind.PROMPT, prompt=body.get("prompt") if isinstance(body.get("prompt"), str) else "")
        if event == "pre-tool-use":
            return RemoteHook(HookKind.TOOL_START)
        if event == "post-tool-use":
            tool, result = remote.tool_events(body)
            flipper = tool["name"].startswith(FLIPPER_PREFIX)
            seq = remote.flipper_seq(body.get("tool_response")) if flipper else None
            return RemoteHook(HookKind.TOOL_DONE, tool=tool, result=result, flipper=flipper, seq=seq)
        if event == "stop":
            message = body.get("last_assistant_message")
            if not isinstance(message, str):
                return RemoteHook(HookKind.STOP)
            return RemoteHook(HookKind.STOP, message=message, text=cut(message, TEXT_LIMIT))
        if event == "stop-failure":
            error = body.get("error")
            return RemoteHook(HookKind.FAILED, error=error[:500] if isinstance(error, str) else None)
        if event == "notification":
            kind = body.get("notification_type")
            if kind == "permission_prompt":
                return RemoteHook(HookKind.PERMISSION, permission_tool=remote.permission_tool(body.get("message")))
            if kind == "idle_prompt":
                return RemoteHook(HookKind.IDLE)
        if event == "session-end":
            reason = body.get("reason")
            return RemoteHook(HookKind.SESSION_END, reason=reason if isinstance(reason, str) else None)
        return RemoteHook(HookKind.OTHER)
