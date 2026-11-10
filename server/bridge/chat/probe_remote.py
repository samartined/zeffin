# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""`zf agent probe --remote`: Claude Code's interactive mode with Remote Control, steps 22 to 33.

What the server's Remote Control would rely on, checked on a real server before any design:
an interactive `claude --resume <id> --remote-control <name>` under a pseudo-terminal, followed only
through its documented http hooks (code.claude.com/docs/en/hooks, remote-control; read 2026-10-24).

It creates a session that shows in the owner's Claude app and at claude.ai/code while it runs; its
transcript is stored by Anthropic while connected. The prompts never ask for anything private, and the
report keeps the same rule as the rest of the probe: yes/no, times, field names and enum values, never
texts, paths, ids or tokens. The terminal's text is only searched for a few known words.

Steps 30-33 resume the same conversation a second time, with `--add-dir` on a temporary folder: what
attachments with Remote Control would rely on. Claude Code passes files sent from the Claude app to the model
as `@` file references (code.claude.com/docs/en/remote-control); whether a `@path` inside the bracketed paste
the server types does the same is not documented: these steps answer it.
"""
from __future__ import annotations

import json
import os
import shlex
import shutil
import tempfile
import time
import uuid
from pathlib import Path

from ..png import solid_png
from . import command
from .terminal import CTRL_C, ESC, Terminal

NAME = "zeffin probe"
MODEL = "haiku"   # as the rest of the probe
START_S = 60      # to the SessionStart hook (or a question on the screen)
TURN_S = 150      # to the Stop hook of a short Haiku turn
PASTE_START, PASTE_END = "\x1b[200~", "\x1b[201~"   # bracketed paste, as remote.py types a message
WORDS = {"a.txt": "romeo-17", "b.txt": "sierra-58"}   # the extra folder's files and what they hold
IMAGE = ("c.png", (20, 40, 220), "blue")


class RemoteSteps:
    SETTLE_S = 5.0        # after the start, before checking the process is still up
    AFTER_KEY_S = 10.0    # what fires after Esc
    BLOCKED_S = 15.0      # how long a blocked prompt is watched for a model reply
    BEFORE_ESC_S = 4.0    # into the counting turn before Esc
    QUIET_S = 2.0         # no output for this long: the prompt is back
    POLL_S = 0.2          # how often wait_for looks again

    def __init__(self, probe):
        self.p = probe
        self.hooks = probe.hooks
        self.term: Terminal | None = None
        self.session = str(uuid.uuid4())
        self.marker = probe.workdir / ".session-start"

    # ------------------------------------------------------------------ helpers
    def calls(self, event: str | None = None, since: float = 0.0) -> list[dict]:
        return [c for c in self.hooks.remote_calls if c["at"] >= since and (event is None or c["event"] == event)]

    def wait_for(self, test, timeout: float) -> bool:
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            if test():
                return True
            if self.term is not None and not self.term.alive():
                return bool(test())
            time.sleep(self.POLL_S)
        return bool(test())

    def wait_turn_end(self, since: float, timeout: float = TURN_S) -> dict | None:
        self.wait_for(lambda: self.calls("stop", since) or self.calls("stop-failure", since), timeout)
        found = self.calls("stop", since) or self.calls("stop-failure", since)
        return found[0] if found else None

    def screen(self) -> dict:
        t = self.term
        if t is None:
            return {}
        return {"consent_question": t.seen("enable remote control"),
                "trust_question": t.seen("do you trust") or t.seen("trust this folder"),
                "rc_active": t.seen("/rc active") or t.seen("rc active"),
                "rc_failed": t.seen("remote control failed") or t.seen("remote control requires")
                or t.seen("requires a claude.ai subscription") or t.seen("remote control disconnected"),
                "not_started_here": t.seen("remote control not started"),
                "reconnect_failed": t.seen("couldn't reconnect") or t.seen("previous session is unavailable")}

    def sessions_started(self) -> list[dict]:
        if not self.marker.exists():
            return []
        found = []
        for line in self.marker.read_text().splitlines():
            try:
                found.append(json.loads(line))
            except ValueError:
                pass
        return found

    @staticmethod
    def order(calls: list[dict]) -> list[str]:
        return [c["event"] for c in calls]

    def project(self) -> Path:
        from .redact import encoded

        return command.claude_dir() / "projects" / encoded(str(self.p.workdir.resolve()))

    def transcripts(self) -> int | None:
        """How many transcripts the probe's folder has in ~/.claude/projects (counted, never read): a new one
        after the interactive session would mean its turns went to another session."""
        folder = self.project()
        return len(list(folder.glob("*.jsonl"))) if folder.is_dir() else None

    def transcript_size(self) -> int | None:
        """Our transcript's size in bytes (stat only, never read): whether the interactive turns were written."""
        try:
            return (self.project() / f"{self.session}.jsonl").stat().st_size
        except OSError:
            return None

    def sessions(self) -> dict:
        """Whether the hooks report our session id and its transcript's name (yes/no and counts only)."""
        bodies = [c["body"] for c in self.hooks.remote_calls]
        ids = {str(b.get("session_id")) for b in bodies if b.get("session_id")}
        names = {Path(str(b["transcript_path"])).stem for b in bodies if b.get("transcript_path")}
        return {"hook_session_ids": len(ids), "hook_session_ours": ids == {self.session} if ids else None,
                "transcript_ours": names == {self.session} if names else None}

    # ------------------------------------------------------------------ the steps
    def run(self) -> None:
        self.before: int | None = None
        self.size_before: int | None = None
        self.size_after: int | None = None
        try:
            if not self.step_start():
                return
            for step in (self.step_prompt_hooks, self.step_permission_notice, self.step_block,
                         self.step_interrupt, self.step_bridge_header, self.step_exit):
                if self.term is None or not self.term.alive():
                    self.p.check(0, "rc_" + step.__name__.removeprefix("step_"), False,
                                 "skipped: the interactive process is gone")
                    continue
                try:
                    step()
                except Exception as e:  # keep going, as the main probe does
                    self.p.check(0, "rc_" + step.__name__.removeprefix("step_"), False, f"probe error {type(e).__name__}")
        finally:
            if self.term is not None:
                self.term.close()
        self.step_resume_print()
        self.run_add_dir()

    def launch(self, add_dirs: list[str] | None = None) -> tuple[bool, float, dict, list]:
        """Resume the conversation interactively with Remote Control on, and wait until its prompt is up:
        (still up, seconds to SessionStart, what the screen showed, SessionStart sources)."""
        spec = command.TurnSpec(claude_bin=self.p.claude_bin, session_id=self.session, resume=True,
                                model=MODEL, mode="default", mcp_url=self.p.c.mcp_url(),
                                hooks_url=self.hooks.url, cleanup_days=command.user_cleanup_days())
        q = shlex.quote(str(self.marker))
        argv = command.build_remote_command(spec, NAME, session_start_command=f"cat >> {q}; echo >> {q}")
        if add_dirs:
            argv[3:3] = ["--add-dir", *add_dirs]  # after the name, before --resume (as build_command places it)
        env = command.turn_env(os.environ, hook_token=self.p.hook_token,
                               mcp_token=self.p.c.mcp_token if self.p.mcp_running else "")
        self.hooks.remote_calls = []
        self.marker.unlink(missing_ok=True)
        self.term = Terminal(argv, cwd=str(self.p.workdir), env=env)

        def question() -> bool:
            screen = self.screen()
            return screen["consent_question"] or screen["trust_question"]

        self.wait_for(lambda: bool(self.sessions_started()) or question(), START_S)
        start_s = round(time.monotonic() - self.term.started, 1)
        self.term.quiet_for(self.QUIET_S, 20)
        if not question():
            self.wait_for(lambda: self.screen().get("rc_active") or self.screen().get("rc_failed"), 20)
        alive_then = self.term.alive()
        time.sleep(self.SETTLE_S)
        up = self.term.alive() and alive_then
        return up, start_s, self.screen(), [s.get("source") for s in self.sessions_started()]

    def step_start(self) -> bool:
        """22: a conversation made by -p turns, resumed interactively with Remote Control on."""
        self.p.step_name = "rc_start"
        first = self.p.turn("Please remember the number 5023 for later. Reply with only the word ok.", session=self.session)
        if first.exit_code != 0:
            self.p.check(22, "rc_start", False, f"the first -p turn failed (exit {first.exit_code})")
            return False
        self.before, self.size_before = self.transcripts(), self.transcript_size()  # ours exists now
        up, start_s, screen, sources = self.launch()
        ok = up and bool(sources) and not screen["consent_question"] and not screen["trust_question"]
        self.p.report["remote"] = {"start_s": start_s, "screen_at_start": screen}
        self.p.check(22, "rc_start", ok,
                     f"stays up {up}, SessionStart {sources or 'not fired'} after {start_s} s, "
                     f"'Enable Remote Control?' question {screen['consent_question']}, trust question "
                     f"{screen['trust_question']}, /rc active {screen['rc_active']}, Remote Control failure shown "
                     f"{screen['rc_failed']}, exit {self.term.proc.returncode}",
                     up=up, session_start=sources, start_s=start_s, **screen)
        if screen["trust_question"]:
            self.p.out("   Claude Code asked whether to trust the probe's folder, which the probe trusts in its config\n"
                       "   first. Paste the report: the server's Remote Control would need the same.")
        if screen["consent_question"]:
            self.p.out("   Claude Code asks once whether to enable Remote Control. The probe never answers it for you.\n"
                       "   To accept it: in the agent folder (zf status shows it) run claude --remote-control,\n"
                       "   answer the question, type /exit, then run zf agent probe --remote again.")
        return ok or (up and not screen["consent_question"] and not screen["trust_question"])

    def step_prompt_hooks(self):
        """23: a prompt typed into the process fires UserPromptSubmit, PostToolUse and Stop."""
        since = time.monotonic()
        self.term.type("Use the Read tool to read CLAUDE.md. Then reply with the probe word and the number I asked "
                       "you to remember, and remember the number 6131 for later.")
        end = self.wait_turn_end(since)
        ups = self.calls("user-prompt-submit", since)
        post = self.calls("post-tool-use", since)
        message = (end or {}).get("body", {}).get("last_assistant_message") or ""
        prompt_ok = bool(ups) and "6131" in str(ups[0]["body"].get("prompt", ""))
        read = [c for c in post if c["body"].get("tool_name") == "Read"]
        fields = sorted(set(read[0]["body"]) & {"tool_name", "tool_input", "tool_response", "tool_use_id"}) if read else []
        remembers = "5023" in str(message)
        ok = prompt_ok and bool(read) and end is not None and end["event"] == "stop" and remembers
        self.p.check(23, "rc_prompt_hooks", ok,
                     f"hooks {self.order(self.calls(since=since))}, prompt field ok {prompt_ok}, PostToolUse fields "
                     f"{fields}, Stop has last_assistant_message {bool(message)}, CLAUDE.md read "
                     f"{'tangerine' in str(message).lower()}, remembers the -p turn {remembers}, token header ok "
                     f"{all(c['header_ok'] for c in self.calls(since=since))}, "
                     f"turn {round(end['at'] - since, 1) if end else None} s",
                     order=self.order(self.calls(since=since)), prompt_ok=prompt_ok, post_fields=fields,
                     stop_keys=sorted((end or {}).get("body", {})), remembers=remembers,
                     prompt_keys=sorted(ups[0]["body"]) if ups else None)

    def step_permission_notice(self):
        """24: with no PermissionRequest hook, a write that asks fires Notification(permission_prompt);
        Esc answers it locally (the Claude app would answer it remotely)."""
        since = time.monotonic()
        self.term.type("Use the Bash tool to run exactly: touch rc-b.txt")
        self.wait_for(lambda: any(c["body"].get("notification_type") == "permission_prompt"
                                  for c in self.calls("notification", since)), 90)
        notices = self.calls("notification", since)
        prompt = [c for c in notices if c["body"].get("notification_type") == "permission_prompt"]
        notice_s = round(prompt[0]["at"] - since, 1) if prompt else None
        esc_at = time.monotonic()
        self.term.key(ESC)
        time.sleep(self.AFTER_KEY_S)
        after = self.order(self.calls(since=esc_at))
        written = (self.p.workdir / "rc-b.txt").exists()
        self.term.quiet_for(self.QUIET_S, 15)
        self.p.check(24, "rc_permission_notice", bool(prompt) and not written,
                     f"Notification types {[c['body'].get('notification_type') for c in notices]}, permission_prompt "
                     f"after {notice_s} s, hooks after Esc {after}, file written {written}",
                     types=[c["body"].get("notification_type") for c in notices], notice_s=notice_s,
                     after_esc=after, written=written,
                     notice_keys=sorted(prompt[0]["body"]) if prompt else None)

    def step_block(self):
        """25: UserPromptSubmit answers decision block: the prompt never reaches the model."""
        self.hooks.remote_answers["user-prompt-submit"] = {"decision": "block", "reason": "The probe blocked this prompt."}
        since = time.monotonic()
        try:
            self.term.type("Reply with only the word unblocked.")
            self.wait_for(lambda: self.calls("user-prompt-submit", since), 30)
            time.sleep(self.BLOCKED_S)
        finally:
            self.hooks.remote_answers.pop("user-prompt-submit", None)
        seen = self.calls(since=since)
        stops = [c for c in seen if c["event"] == "stop"]
        answered = any("unblocked" in str(c["body"].get("last_assistant_message", "")).lower() for c in stops)
        ups = bool(self.calls("user-prompt-submit", since))
        self.p.check(25, "rc_block", ups and not answered and self.term.alive(),
                     f"hooks {self.order(seen)}, the model answered {answered}, still up {self.term.alive()}",
                     order=self.order(seen), answered=answered)
        self.term.quiet_for(self.QUIET_S, 10)

    def step_interrupt(self):
        """26: what fires when Esc interrupts a turn, and whether the next prompt works."""
        since = time.monotonic()
        self.term.type("Count from 1 to 400, one number per line, with no other text.")
        self.wait_for(lambda: self.calls("user-prompt-submit", since), 30)
        time.sleep(self.BEFORE_ESC_S)
        esc_at = time.monotonic()
        self.term.key(ESC)
        time.sleep(self.AFTER_KEY_S)
        after = self.order(self.calls(since=esc_at))
        self.term.quiet_for(self.QUIET_S, 10)
        again = time.monotonic()
        self.term.type("Reply with only the word back.")
        end = self.wait_turn_end(again)
        back = end is not None and "back" in str(end["body"].get("last_assistant_message", "")).lower()
        self.p.check(26, "rc_interrupt", back,
                     f"hooks in the {self.AFTER_KEY_S:.0f} s after Esc {after or 'none'}, next prompt answered {back}",
                     after_esc=after, next_ok=back)

    def step_bridge_header(self):
        """27: CLAUDE_CODE_BRIDGE_SESSION_ID in an http hook header (hooks page: set while connected)."""
        calls = self.hooks.remote_calls
        with_id = [c for c in calls if c["bridge"]]
        same = len({c["bridge"] for c in with_id}) <= 1
        screen = self.screen()
        self.p.check(27, "rc_bridge_header", True if with_id else None,
                     f"header filled in {len(with_id)} of {len(calls)} hook calls, one value {same}, "
                     f"/rc active {screen['rc_active']}, failure shown {screen['rc_failed']}",
                     filled=len(with_id), calls=len(calls), one_value=same, **screen)

    def step_exit(self):
        """28: /exit ends the process and fires SessionEnd."""
        since = time.monotonic()
        self.term.type("/exit")
        code = self.term.wait(20)
        if code is None:  # a menu took the Enter: press it again
            self.term.key("\r")
            code = self.term.wait(10)
        self.wait_for(lambda: self.calls("session-end", since), 3)
        self.first_sessions = self.sessions()
        self.size_after = self.transcript_size()
        ends = self.calls("session-end", since)
        reason = ends[0]["body"].get("reason") if ends else None
        self.p.check(28, "rc_exit", code is not None and bool(ends),
                     f"exit {code}, SessionEnd {bool(ends)} reason {reason}", exit=code, session_end=bool(ends),
                     reason=reason)
        if code is None:
            self.term.key(CTRL_C)
            self.term.key(CTRL_C)

    def step_resume_print(self):
        """29: a later -p turn on the same conversation remembers what was said in the remote session."""
        self.p.step_name = "rc_resume_print"
        record = self.p.turn("What number did I ask you to remember last? Reply with only the number.",
                             session=self.session, resume=True)
        got = "6131" in record.reply()
        after = self.transcripts()
        found = {**getattr(self, "first_sessions", {}),
                 "new_transcripts": after - self.before if after is not None and self.before is not None else None,
                 "transcript_grew": self.size_after > self.size_before
                 if self.size_after is not None and self.size_before is not None else None,
                 "resumed_id_kept": record.init.get("session_id") == self.session}
        self.p.check(29, "rc_resume_print", record.exit_code == 0 and got,
                     f"exit {record.exit_code}, remembers the remote turn {got}; the remote hooks reported "
                     f"{found.get('hook_session_ids')} session id(s), ours {found.get('hook_session_ours')}, transcript "
                     f"named after ours {found.get('transcript_ours')}; new transcripts {found['new_transcripts']}, ours grew "
                     f"during the remote session {found['transcript_grew']}; "
                     f"-p kept the id {found['resumed_id_kept']}",
                     exit=record.exit_code, remembers=got, **found)

    # ------------------------------------------------------------------ 30-33: --add-dir, @ mentions in a paste
    def run_add_dir(self) -> None:
        folder = Path(tempfile.mkdtemp(prefix="probe-files-", dir=self.p.c.state))
        try:
            for name, word in WORDS.items():
                (folder / name).write_text(word + "\n")
            (folder / IMAGE[0]).write_bytes(solid_png(64, 64, IMAGE[1]))
            if not self.step_add_dir_resume(folder):
                return
            for step in (self.step_add_dir_read, self.step_paste_mention, self.step_paste_image):
                if self.term is None or not self.term.alive():
                    self.p.check(0, "rc_" + step.__name__.removeprefix("step_"), False,
                                 "skipped: the interactive process is gone")
                    continue
                try:
                    step(folder)
                except Exception as e:  # keep going, as the main probe does
                    self.p.check(0, "rc_" + step.__name__.removeprefix("step_"), False, f"probe error {type(e).__name__}")
            if self.term is not None and self.term.alive():
                self.term.type("/exit")
                if self.term.wait(20) is None:
                    self.term.key("\r")
                    self.term.wait(10)
        finally:
            if self.term is not None:
                self.term.close()
            shutil.rmtree(folder, ignore_errors=True)

    def paste(self, text: str) -> None:
        """One message as remote.py's type_text sends it: a bracketed paste, then Enter as a key of its own."""
        self.term.key(PASTE_START + text + PASTE_END)
        time.sleep(self.term.KEY_GAP_S)
        self.term.key("\r")

    def ask(self, send) -> dict:
        """Send one message and wait for the end of its turn, or for a permission prompt (rejected with Esc)."""
        since = time.monotonic()
        send()

        def prompted() -> bool:
            return any(c["body"].get("notification_type") == "permission_prompt" for c in self.calls("notification", since))

        self.wait_for(lambda: prompted() or self.calls("stop", since) or self.calls("stop-failure", since), TURN_S)
        asked = prompted()
        if asked:
            self.term.key(ESC)
            self.wait_turn_end(since, self.AFTER_KEY_S)
        self.term.quiet_for(self.QUIET_S, 15)
        ends = self.calls("stop", since) or self.calls("stop-failure", since)
        end = ends[0] if ends else None
        ups = self.calls("user-prompt-submit", since)
        tools = [c["body"].get("tool_name") for c in self.calls("post-tool-use", since)]
        return {"end": end, "asked": asked, "tools": tools,
                "message": str((end or {}).get("body", {}).get("last_assistant_message") or ""),
                "prompt_mention": bool(ups) and "@" in str(ups[0]["body"].get("prompt", "")),
                "turn_s": round(end["at"] - since, 1) if end else None}

    def answer(self, number: int, name: str, got: dict, found: bool) -> None:
        """ok: the content came back without a permission prompt; the summary says how (Read or the mention)."""
        how = "Read" if "Read" in got["tools"] else ("the mention" if found else "neither")
        told = got["end"] is not None or got["asked"]
        self.p.check(number, name, (found and not got["asked"]) if told else None,
                     f"content came back {found}, through {how}, permission prompt {got['asked']}, tools {got['tools']}, "
                     f"turn {got['turn_s']} s", found=found, how=how, asked=got["asked"], tools=got["tools"],
                     ended=got["end"] is not None, prompt_has_mention=got["prompt_mention"])

    def step_add_dir_resume(self, folder: Path) -> bool:
        """30: --add-dir together with --resume --remote-control: it starts and the conversation goes on.
        It says where a failure is: the prompt never submitted (then Enter is pressed once more), the turn
        never ended, or the answer was another number."""
        up, start_s, screen, sources = self.launch(add_dirs=[str(folder)])
        blocked = screen["consent_question"] or screen["trust_question"]
        found: dict = {"prompt_seen": False, "enter_again": False, "ended": False, "answer": None, "order": []}
        if up and not blocked:
            since = time.monotonic()
            self.term.type("What number did I ask you to remember last? Reply with only the number.")
            if not self.wait_for(lambda: self.calls("user-prompt-submit", since), 20):
                found["enter_again"] = True  # the text may sit in the input box: submit it
                self.term.key("\r")
                self.wait_for(lambda: self.calls("user-prompt-submit", since), 20)
            found["prompt_seen"] = bool(self.calls("user-prompt-submit", since))
            end = self.wait_turn_end(since) if found["prompt_seen"] else None
            message = str((end or {}).get("body", {}).get("last_assistant_message") or "")
            found["ended"] = end is not None and end["event"] == "stop"
            labels = {"6131": "last", "5023": "earlier", "4817": "unrelated"}  # never the text itself
            found["answer"] = next((label for n, label in labels.items() if n in message), "other" if message else None)
            found["order"] = self.order(self.calls(since=since))
            self.term.quiet_for(self.QUIET_S, 15)
        remembers = found["answer"] == "last"
        ok = up and bool(sources) and remembers
        self.p.check(30, "rc_add_dir_resume", ok,
                     f"stays up {up}, SessionStart {sources or 'not fired'} after {start_s} s, prompt submitted "
                     f"{found['prompt_seen']}{' (after a second Enter)' if found['enter_again'] else ''}, turn ended "
                     f"{found['ended']}, answer {found['answer']}, hooks {found['order']}, question on the screen "
                     f"{blocked}, /rc active {screen['rc_active']}",
                     up=up, session_start=sources, start_s=start_s, remembers=remembers, **found, **screen)
        return up and not blocked

    def step_add_dir_read(self, folder: Path):
        """31: a file in the --add-dir folder is read without a permission prompt."""
        got = self.ask(lambda: self.term.type(f"Use the Read tool to read {folder / 'a.txt'} and reply with its content only."))
        self.answer(31, "rc_add_dir_read", got, WORDS["a.txt"] in got["message"])

    def step_paste_mention(self, folder: Path):
        """32: `@path` inside a bracketed paste reaches the model as the file."""
        got = self.ask(lambda: self.paste(f"@{folder / 'b.txt'} Reply with only the word written in that file."))
        self.answer(32, "rc_paste_mention", got, WORDS["b.txt"] in got["message"])

    def step_paste_image(self, folder: Path):
        """33: `@path` of a photo inside a bracketed paste: the model sees the image."""
        got = self.ask(lambda: self.paste(f"@{folder / IMAGE[0]} What colour is this image? Answer with one word."))
        self.answer(33, "rc_paste_image", got, IMAGE[2] in got["message"].lower())
