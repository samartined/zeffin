# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""`zf agent probe`: answer, on a real server, what the Claude Code docs leave open.

About 15 tiny turns with `--model haiku`, in a temporary folder under the state dir that is removed
at the end. Every turn uses the same command line as the server (command.py), with a small hook
server of its own on 127.0.0.1. The report holds versions, yes/no, codes, times and field names:
never Claude's text, paths, ids or tokens, so it can be pasted into a chat.
With --remote, steps 22-33 also try Claude Code's interactive mode with Remote Control (probe_remote.py).
With a fixtures folder, each -p turn's stream-json is also saved there, redacted (redact.py), for the parser's
tests; nothing is written unless the self-check finds none of this machine's personal values in it.
"""
from __future__ import annotations

import json
import os
import secrets
import shutil
import signal
import subprocess
import tempfile
import threading
import time
import uuid
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Protocol

from ..png import solid_png
from ..text import one_line
from . import command
from .redact import Redactor, joined_blocks, local_secrets

class ProbeConfig(Protocol):
    """What the probe reads from the server's configuration (cli.py passes the Config): the MCP listener and its token,
    and the folder for its temporary files; the host and the other tokens only to keep them out of the fixtures."""
    host: str
    phone_token: str
    mcp_token: str
    view_token: str
    app_token: str
    state: Path

    def mcp_url(self) -> str: ...


PROBE_MODEL = "haiku"
NO_FIXTURES = ("usage", "models")  # no model turn: what they print is the owner's (usage, commands, agents)
REPORT_VERSION = 2
CLAUDE_MD = "# Probe\n\nThe probe word is tangerine. When asked for the probe word, reply with it and nothing else.\n"


def family(model: object) -> str:
    text = str(model or "").lower()
    for name in ("fable", "opus", "sonnet", "haiku"):
        if name in text:
            return name + ("[1m]" if "[1m]" in text else "")
    return "other"


@dataclass
class TurnRecord:
    events: list = field(default_factory=list)
    exit_code: int | None = None
    stderr: str = ""
    first_event_s: float | None = None
    first_text_s: float | None = None
    duration_s: float = 0.0
    signalled_s: float | None = None

    def of(self, kind: str, subtype: str | None = None) -> list:
        return [e for e in self.events if e.get("type") == kind and (subtype is None or e.get("subtype") == subtype)]

    @property
    def init(self) -> dict:
        found = self.of("system", "init")
        return found[0] if found else {}

    @property
    def result(self) -> dict | None:
        found = self.of("result")
        return found[-1] if found else None

    def reply(self) -> str:
        r = self.result
        if r and isinstance(r.get("result"), str):
            return r["result"]
        texts = [b.get("text", "") for e in self.of("assistant") for b in (e.get("message") or {}).get("content") or []
                 if isinstance(b, dict) and b.get("type") == "text"]
        return "\n".join(texts)

    def tool_uses(self) -> list[str]:
        return [b.get("name") for e in self.of("assistant") for b in (e.get("message") or {}).get("content") or []
                if isinstance(b, dict) and b.get("type") == "tool_use"]

    def tool_errors(self) -> list[bool]:
        return [bool(b.get("is_error")) for e in self.of("user") for b in (e.get("message") or {}).get("content") or []
                if isinstance(b, dict) and b.get("type") == "tool_result"]


class HookServer:
    """Answers the http hooks the way each step asks, and records what it saw (no values)."""

    def __init__(self, token: str):
        self.token = token
        self.behavior = {"answer": "allow", "delay": 0.0}
        self.calls: list[dict] = []
        self.remote_calls: list[dict] = []   # kept in memory only: bodies hold texts
        self.remote_answers: dict = {}
        server = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):  # quiet
                pass

            def do_POST(self):
                length = int(self.headers.get("content-length") or 0)
                try:
                    body = json.loads(self.rfile.read(length) or b"{}")
                except ValueError:
                    body = {}
                header_ok = self.headers.get("authorization") == f"Bearer {server.token}"
                if "/remote/" in self.path:  # the Remote Control steps (probe_remote.py)
                    event = self.path.rsplit("/", 1)[-1]
                    server.remote_calls.append({"event": event, "at": time.monotonic(), "header_ok": header_ok,
                                                "bridge": self.headers.get(command.BRIDGE_HEADER) or "",
                                                "body": body if isinstance(body, dict) else {}})
                    return self.reply(server.remote_answers.get(event, {}))
                suggestions = body.get("permission_suggestions") or []
                server.calls.append({"path": self.path, "at": time.monotonic(), "header_ok": header_ok,
                                     "keys": sorted(body), "tool": body.get("tool_name"),
                                     "suggestions": sorted({s.get("type", "?") for s in suggestions if isinstance(s, dict)}),
                                     "set_modes": sorted({str(s.get("mode")) for s in suggestions
                                                          if isinstance(s, dict) and s.get("type") == "setMode"}),
                                     "input_keys": sorted(body.get("tool_input") or {}) if isinstance(body.get("tool_input"), dict) else None,
                                     "has_reason": bool(body.get("reason"))})
                if self.path.endswith("permission-denied"):
                    return self.reply({})
                if str(body.get("tool_name") or "").startswith("mcp__flipper__"):
                    # What the bridge does: Flipper tools never wait for an answer, in any mode.
                    return self.reply({"hookSpecificOutput": {"hookEventName": "PermissionRequest",
                                                              "decision": {"behavior": "allow"}}})
                behavior = dict(server.behavior)
                if behavior["delay"]:
                    time.sleep(behavior["delay"])
                if behavior["answer"] == "none":
                    return self.reply({})
                decision: dict = {"behavior": behavior["answer"]}
                if behavior["answer"] == "allow":
                    rules = [s for s in suggestions if isinstance(s, dict) and s.get("type") == "addRules"
                             and s.get("behavior") == "allow"]
                    if rules:
                        decision["updatedPermissions"] = [{**rules[0], "destination": "session"}]
                else:
                    decision["message"] = "The probe denied this action."
                self.reply({"hookSpecificOutput": {"hookEventName": "PermissionRequest", "decision": decision}})

            def reply(self, data: dict):
                payload = json.dumps(data).encode()
                try:
                    self.send_response(200)
                    self.send_header("content-type", "application/json")
                    self.send_header("content-length", str(len(payload)))
                    self.end_headers()
                    self.wfile.write(payload)
                except OSError:
                    pass  # the hook already gave up (timeout step)

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.httpd.daemon_threads = True
        self.url = f"http://127.0.0.1:{self.httpd.server_address[1]}/hooks"
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def set(self, answer: str = "allow", delay: float = 0.0) -> None:
        self.behavior = {"answer": answer, "delay": delay}
        self.calls = []

    def permission_calls(self) -> list[dict]:
        return [c for c in self.calls if c["path"].endswith("permission-request")]

    def close(self) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()


class Probe:
    HOLD_S = 20.0           # how long the hook waits before allowing (step 7)
    TIMEOUT_HOOK_S = 5      # hook timeout in the timeout step (step 8) ...
    TIMEOUT_DELAY_S = 15.0  # ... and how long the hook takes to answer there

    def __init__(self, c: ProbeConfig, claude_bin: str, workdir: Path, mcp_running: bool, out=print,
                 remote: bool = False, keep_turns: bool = False):
        self.c, self.claude_bin, self.workdir, self.mcp_running, self.out = c, claude_bin, workdir, mcp_running, out
        self.remote = remote
        self.hook_token = secrets.token_urlsafe(24)
        self.hooks = HookServer(self.hook_token)
        self.report: dict = {"v": REPORT_VERSION, "checks": {}, "events": {}}
        self.lines: list[str] = []
        self.keep_turns = keep_turns      # keep each turn's events in memory, for the fixtures
        self.kept: list[dict] = []
        self.step_name = ""

    # -------------------------------------------------------------- one turn
    def turn(self, prompt: str, *, session: str | None = None, resume: bool = False, mode: str = "default",
             timeout_s: float = 60, interrupt_on_text: bool = False, limit_s: float = 240, effort: str | None = None,
             extra: list[str] | None = None, until: str | None = None, images: list[tuple[str, bytes]] | None = None,
             add_dirs: list[str] | None = None) -> TurnRecord:
        """One turn. With `until`, stdin stays open until an event of that type arrives (control requests).
        With `images`, the message goes as one stream-json user message, as the chat sends it."""
        spec = command.TurnSpec(claude_bin=self.claude_bin, session_id=session or str(uuid.uuid4()), resume=resume,
                                model=PROBE_MODEL, mode=mode, mcp_url=self.c.mcp_url(), hooks_url=self.hooks.url,
                                permission_timeout_s=timeout_s, cleanup_days=command.user_cleanup_days(), effort=effort,
                                input_json=bool(images), add_dirs=add_dirs or [])
        if images:
            prompt = command.user_message(prompt, images)
        # without a running bridge of ours, whatever listens on the MCP port gets no token
        env = command.turn_env(os.environ, hook_token=self.hook_token,
                               mcp_token=self.c.mcp_token if self.mcp_running else "")
        record = TurnRecord()
        start = time.monotonic()
        argv = command.build_command(spec)
        if extra:
            argv[2:2] = extra  # right after -p, before the variadic --allowedTools
        proc = subprocess.Popen(argv, cwd=self.workdir, env=env, stdin=subprocess.PIPE,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, start_new_session=True)
        killer = threading.Timer(limit_s, lambda: _kill(proc))
        killer.start()
        stderr: list[str] = []
        reader = threading.Thread(target=lambda: stderr.append(proc.stderr.read()), daemon=True)
        reader.start()
        try:
            proc.stdin.write(prompt)
            if until is None:
                proc.stdin.close()
            else:
                proc.stdin.flush()
            for line in proc.stdout:
                try:
                    event = json.loads(line)
                except ValueError:
                    continue
                if not isinstance(event, dict):
                    continue
                now = time.monotonic() - start
                record.events.append(event)
                if until is not None and event.get("type") == until and not proc.stdin.closed:
                    proc.stdin.close()  # got the answer: let the process end
                self._keys(event)
                for code in (event.get("error"), event.get("api_error_status")):
                    if isinstance(code, (str, int)) and str(code) not in self.report.setdefault("api_errors", []):
                        self.report["api_errors"].append(str(code))
                if record.first_event_s is None:
                    record.first_event_s = round(now, 2)
                delta = (event.get("event") or {}).get("delta") or {}
                if event.get("type") == "stream_event" and delta.get("type") == "text_delta":
                    if record.first_text_s is None:
                        record.first_text_s = round(now, 2)
                    if interrupt_on_text and record.signalled_s is None:
                        record.signalled_s = round(now, 2)
                        proc.send_signal(signal.SIGINT)
            record.exit_code = proc.wait(timeout=60)
        finally:
            killer.cancel()
            if proc.poll() is None:
                _kill(proc)
            reader.join(timeout=5)
        record.stderr = "".join(stderr)
        record.duration_s = round(time.monotonic() - start, 2)
        if self.keep_turns and self.step_name not in NO_FIXTURES:
            self.kept.append({"step": self.step_name, "events": record.events, "exit": record.exit_code,
                              "resume": resume, "mode": mode, "prompt": None if images else prompt,
                              "images": len(images or [])})
        return record

    def _keys(self, event: dict) -> None:
        name = event.get("type", "?") + (f"/{event['subtype']}" if event.get("subtype") else "")
        if event.get("type") == "stream_event":
            name += "/" + str((event.get("event") or {}).get("type"))
        keys = self.report["events"].setdefault(name, [])
        for key in event:
            if key not in keys:
                keys.append(key)

    def check(self, number: int, name: str, ok: bool | None, summary: str, **data) -> None:
        mark = "✓" if ok else ("?" if ok is None else "✗")
        line = f"{number:2} {mark} {name}: {summary}"
        self.lines.append(line)
        self.out(line)
        self.report["checks"][name] = {"ok": ok, "step": number, "summary": summary, **data}

    # -------------------------------------------------------------- the steps
    def run(self) -> dict:
        (self.workdir / "CLAUDE.md").write_text(CLAUDE_MD)
        steps = [self.step_version, self.step_new_and_resume, self.step_allow, self.step_deny, self.step_hold,
                 self.step_hook_timeout, self.step_sigint, self.step_auto, self.step_mcp,
                 self.step_unknown_resume, self.step_usage, self.step_models, self.step_effort, self.step_plan,
                 self.step_accept_edits, self.step_bash_suggestions, self.step_image, self.step_add_dir]
        if self.remote:
            steps.append(self.step_remote)
        for step in steps:
            self.step_name = step.__name__.removeprefix("step_")
            if step != self.step_version and not self.report.get("logged_in"):
                self.out("Stopped: claude is not signed in with a subscription for the service. "
                         "Run claude in a terminal, type /login, then run zf agent probe again.")
                break
            try:
                step()
            except Exception as e:  # keep going: one broken step must not hide the others
                self.check(0, step.__name__.removeprefix("step_"), False, f"probe error {type(e).__name__}")
        return self.report

    def step_version(self):
        version = command.claude_version(self.claude_bin)
        logged_in, method = command.auth_status(self.claude_bin)
        self.report["claude"] = version
        self.report["auth"] = method
        self.report["logged_in"] = logged_in and method in command.SUBSCRIPTION_AUTH
        self.check(1, "version_and_auth", bool(version) and logged_in and method in command.SUBSCRIPTION_AUTH,
                   f"claude {version or 'not found'}, auth {method or 'none'}", logged_in=logged_in)

    def step_new_and_resume(self):
        session = str(uuid.uuid4())
        first = self.turn("Reply with only the probe word from your instructions, "
                          "and remember the number 4817 for later.", session=session)
        init = first.init
        partial = any(e.get("type") == "stream_event" for e in first.events)
        self.report["init"] = {"permissionMode": init.get("permissionMode"), "apiKeySource": init.get("apiKeySource"),
                               "model": family(init.get("model")), "init_first": bool(first.events) and first.events[0] is init}
        ok = (first.exit_code == 0 and init.get("session_id") == session and init.get("apiKeySource") == "none")
        self.check(2, "new_turn", ok,
                   f"exit {first.exit_code}, session id kept {init.get('session_id') == session}, "
                   f"mode {init.get('permissionMode')}, apiKeySource {init.get('apiKeySource')}, "
                   f"model {family(init.get('model'))}, partial messages {partial}, "
                   f"first event {first.first_event_s} s, first text {first.first_text_s} s, total {first.duration_s} s",
                   exit=first.exit_code, session_kept=init.get("session_id") == session, partial=partial,
                   first_event_s=first.first_event_s, first_text_s=first.first_text_s, total_s=first.duration_s,
                   result_subtype=(first.result or {}).get("subtype"))
        self.check(3, "claude_md_read", "tangerine" in first.reply().lower(), "the folder's CLAUDE.md is used in -p")
        second = self.turn("What number did I ask you to remember? Reply with only the number.",
                           session=session, resume=True)
        self.check(4, "resume", second.exit_code == 0 and "4817" in second.reply(),
                   f"exit {second.exit_code}, remembers {'4817' in second.reply()}", exit=second.exit_code)

    def _file(self, name: str) -> str | None:
        path = self.workdir / name
        return path.read_text().strip() if path.exists() else None

    def step_allow(self):
        self.hooks.set("allow")
        record = self.turn("Use the Write tool to create probe-a.txt containing the word alpha. "
                           "Then use the Write tool again to overwrite probe-a.txt with the word beta. Use no other tool.")
        calls = self.hooks.permission_calls()
        first = calls[0] if calls else {}
        self.check(5, "permission_hook_allow", bool(calls) and first.get("header_ok") and self._file("probe-a.txt") == "beta",
                   f"hook calls {len(calls)}, token header ok {first.get('header_ok')}, "
                   f"suggestions {first.get('suggestions')}, file written {self._file('probe-a.txt') is not None}",
                   calls=len(calls), header_ok=first.get("header_ok"), input_keys=first.get("keys"),
                   suggestions=first.get("suggestions"), tools=record.tool_uses())

    def step_deny(self):
        self.hooks.set("deny")
        record = self.turn("Use the Write tool to create probe-b.txt containing the word gamma. "
                           "If it is denied, reply with only the word DENIED.")
        denials = (record.result or {}).get("permission_denials") or []
        stream = record.of("system", "permission_denied")
        self.check(6, "permission_hook_deny", self._file("probe-b.txt") is None and bool(self.hooks.permission_calls()),
                   f"not written {self._file('probe-b.txt') is None}, result denials {len(denials)}, "
                   f"system/permission_denied events {len(stream)}",
                   denials=len(denials), denial_keys=sorted(denials[0]) if denials and isinstance(denials[0], dict) else None,
                   stream_events=len(stream))

    def step_hold(self):
        self.hooks.set("allow", delay=self.HOLD_S)
        start = time.monotonic()
        self.turn("Use the Write tool to create probe-c.txt containing the word delta. Use no other tool.")
        written = self._file("probe-c.txt") is not None
        self.check(7, "hook_hold", written, f"held {self.HOLD_S:.0f} s, then wrote {written}, turn {round(time.monotonic() - start)} s")

    def step_hook_timeout(self):
        self.hooks.set("allow", delay=self.TIMEOUT_DELAY_S)
        record = self.turn("Use the Write tool to create probe-d.txt containing the word echo. "
                           "If it is denied, reply with only the word DENIED.",
                           timeout_s=self.TIMEOUT_HOOK_S - 30)  # the hook's timeout is this plus 30 s
        written = self._file("probe-d.txt") is not None
        denials = (record.result or {}).get("permission_denials") or []
        self.check(8, "hook_timeout", None, f"written {written}, denials {len(denials)}, exit {record.exit_code}, "
                   f"turn {record.duration_s} s", written=written, denials=len(denials), exit=record.exit_code)

    def step_sigint(self):
        session = str(uuid.uuid4())
        record = self.turn("Count from 1 to 400, one number per line, with no other text.", session=session,
                           interrupt_on_text=True)
        exited_after = round(record.duration_s - record.signalled_s, 2) if record.signalled_s is not None else None
        after = self.turn("Reply with only the word back.", session=session, resume=True)
        self.check(9, "sigint", record.signalled_s is not None and after.exit_code == 0,
                   f"exit {record.exit_code} {exited_after} s after SIGINT, result {(record.result or {}).get('subtype')}, "
                   f"resume afterwards exit {after.exit_code}",
                   exit=record.exit_code, exit_after_s=exited_after, result_subtype=(record.result or {}).get("subtype"),
                   resume_exit=after.exit_code)

    def step_auto(self):
        record = self.turn("Reply with only the word ok.", mode="auto")
        mode = record.init.get("permissionMode")
        self.auto = mode == "auto"
        self.check(10, "auto_mode", self.auto, f"requested auto, init says {mode}", mode=mode)

    def step_mcp(self):
        if not self.mcp_running:
            self.check(12, "flipper_mcp", None, "skipped: the bridge is not running")
            return
        for mode in ("default", "auto", "plan") if getattr(self, "auto", False) else ("default", "plan"):
            self.hooks.set("deny")
            record = self.turn("Call the flipper_status tool once, then reply with only the word done.", mode=mode)
            servers = {s.get("name"): s.get("status") for s in record.init.get("mcp_servers") or [] if isinstance(s, dict)}
            used = "mcp__flipper__flipper_status" in record.tool_uses()
            errors = record.tool_errors()
            calls = len(self.hooks.permission_calls())
            self.check(12, f"flipper_mcp_{'manual' if mode == 'default' else mode}",
                       servers.get("flipper") == "connected" and used and not any(errors),
                       f"flipper {servers.get('flipper')}, other servers {len(servers) - ('flipper' in servers)}, "
                       f"tool used {used}, tool errors {sum(errors)}, permission asked {calls}"
                       + (" (allowed by the hook, as the bridge does)" if calls else ""),
                       status=servers.get("flipper"), others=len(servers) - ("flipper" in servers), used=used, asked=calls)

    def step_unknown_resume(self):
        record = self.turn("Reply with only the word ok.", session=str(uuid.uuid4()), resume=True, limit_s=60)
        message = "No conversation found" in record.stderr or any(
            "No conversation found" in json.dumps(e) for e in record.events)
        self.check(13, "unknown_resume", record.exit_code != 0 and message,
                   f"exit {record.exit_code}, message {message}, result event {record.result is not None}, "
                   f"stream events {len(record.events)}", exit=record.exit_code, message=message,
                   result=(record.result or {}).get("subtype"), events=len(record.events))

    # ---- 2026-10-23: what the app's model, effort and mode pickers need
    def step_usage(self):
        """Does /usage answer in -p without a model turn, and does it say whether usage credits can pay?"""
        record = self.turn("/usage", limit_s=90)
        reports = [e.get("usage_report") for e in record.events if isinstance(e.get("usage_report"), dict)]
        report = reports[0] if reports else {}
        extra = report.get("extra_usage") if isinstance(report.get("extra_usage"), dict) else None
        result = record.result or {}
        model_used = bool(result.get("modelUsage")) or bool((result.get("usage") or {}).get("output_tokens"))
        self.report["usage"] = {"report": bool(reports), "keys": sorted(report), "extra_usage": extra is not None,
                                "extra_is_enabled": extra.get("is_enabled") if extra else None,
                                "model_used": model_used, "exit": record.exit_code}
        self.check(14, "usage_report", bool(reports) and not model_used,
                   f"usage_report {bool(reports)}, extra_usage {extra is not None}"
                   + (f" (is_enabled {extra.get('is_enabled')})" if extra else "") + f", model used {model_used}")

    def step_models(self):
        """The models Claude Code offers (control request initialize over stream-json input), each row as the catalog
        reads it (catalog.py): its value and the model it resolves to, its own name and description (Claude Code's
        words, which say when a model requires usage credits), its efforts and auto mode."""
        request = {"type": "control_request", "request_id": "probe-1", "request": {"subtype": "initialize"}}
        record = self.turn(json.dumps(request) + "\n", extra=["--input-format", "stream-json"],
                           until="control_response", limit_s=60)
        models = _find_list(record.events, "models")
        rows = []
        for m in models or []:
            if not isinstance(m, dict):
                continue
            text = f"{m.get('displayName', '')} {m.get('description', '')}".lower()
            rows.append({"value": m.get("value"), "resolved": m.get("resolvedModel"),
                         "display": _row_text(m.get("displayName"), 60),
                         "description": _row_text(m.get("description"), 200),
                         "supports_effort": m.get("supportsEffort"),
                         "efforts": m.get("supportedEffortLevels"), "auto": m.get("supportsAutoMode"),
                         "mentions_credits": "credit" in text})
        account = _find_dict(record.events, "account")
        self.report["models"] = {"rows": rows, "account_keys": sorted(account) if account else None,
                                 "exit": record.exit_code,
                                 "response": any(e.get("type") == "control_response" for e in record.events)}
        fable = [r for r in rows if "fable" in str(r["value"]).lower() or "fable" in str(r["resolved"]).lower()]
        self.check(15, "model_list", bool(rows),
                   f"{len(rows)} models: {', '.join(str(r['value']) for r in rows)}; "
                   f"fable {'offered' if fable else 'not offered'}"
                   + (f", row mentions credits {fable[0]['mentions_credits']}" if fable else ""))

    def step_effort(self):
        found = {}
        for level in ("low", "max"):
            record = self.turn("Reply with only the word ok.", effort=level, limit_s=90)
            found[level] = {"init_effort": record.init.get("effort"), "exit": record.exit_code,
                            "result": (record.result or {}).get("subtype")}
        self.check(16, "effort", True if found["low"]["init_effort"] == "low" else (None if found["low"]["exit"] == 0 else False),
                   f"--effort low: init {found['low']['init_effort']}; --effort max on Haiku: "
                   f"init {found['max']['init_effort']}, exit {found['max']['exit']}", **found)

    def step_plan(self):
        """Plan mode in -p: how a plan is approved, and whether Flipper tools work there."""
        found = {}
        for answer in ("allow", "deny"):
            self.hooks.set(answer)
            name = f"plan-{answer}.txt"
            record = self.turn(f"Use the Write tool to create {name} containing the word november. "
                               "Plan it first if your mode asks for a plan.", mode="plan", limit_s=180)
            calls = self.hooks.permission_calls()
            found[answer] = {"hook_tools": [c["tool"] for c in calls], "set_modes": [c["set_modes"] for c in calls],
                             "input_keys": [c["input_keys"] for c in calls], "written": self._file(name) is not None,
                             "init_mode": record.init.get("permissionMode"), "tools": record.tool_uses(),
                             "exit": record.exit_code}
        approve = found["allow"]
        self.check(17, "plan_mode", True if "ExitPlanMode" in approve["hook_tools"]
                   else (None if not approve["written"] and not found["deny"]["written"] else False),
                   f"approve: hook saw {approve['hook_tools']}, written {approve['written']}; "
                   f"reject: hook saw {found['deny']['hook_tools']}, written {found['deny']['written']}", **found)

    def step_accept_edits(self):
        self.hooks.set("deny")
        record = self.turn("Use the Write tool to create ae-1.txt containing the word oscar. Then use the Bash tool "
                           "to run exactly: echo papa > ae-2.txt", mode="acceptEdits", limit_s=120)
        calls = [c["tool"] for c in self.hooks.permission_calls()]
        self.check(18, "accept_edits", self._file("ae-1.txt") is not None and "Write" not in calls,
                   f"Write without asking {self._file('ae-1.txt') is not None and 'Write' not in calls}, "
                   f"hook saw {calls}, bash file written {self._file('ae-2.txt') is not None}",
                   hook_tools=calls, tools=record.tool_uses(), bash_written=self._file("ae-2.txt") is not None)

    def step_bash_suggestions(self):
        """Which Bash commands Claude Code suggests an allow rule for: the chat offers "always" only then,
        like Claude Code's own prompt (zf chat --test, 2026-10-23: a redirect got no rule)."""
        found = {}
        for name, cmd in (("plain", "touch probe-f.txt"), ("redirect", "echo golf > probe-g.txt")):
            self.hooks.set("allow")
            self.turn(f"Use the Bash tool to run exactly: {cmd}", limit_s=120)
            calls = self.hooks.permission_calls()
            found[name] = {"asked": [c["tool"] for c in calls], "suggestions": [c["suggestions"] for c in calls],
                           "set_modes": [c["set_modes"] for c in calls],
                           "ran": self._file(cmd.split()[-1]) is not None}
        plain = any("addRules" in s for s in found["plain"]["suggestions"])
        self.check(19, "bash_rule_suggestions", True if plain else (None if found["plain"]["ran"] else False),
                   f"folder trusted: {self.report.get('trusted')}; "
                   f"plain command: asked {found['plain']['asked']}, suggestions {found['plain']['suggestions']}; "
                   f"with a redirect: asked {found['redirect']['asked']}, suggestions {found['redirect']['suggestions']}",
                   **found)

    def step_image(self):
        """An attached image: one stream-json user message with an image block, stdin closed after it."""
        self.hooks.set("deny")
        png = solid_png(64, 64, (220, 20, 20))
        record = self.turn("Answer with one word: what colour is this image?", images=[("image/png", png)],
                           limit_s=120)
        reply = record.reply().lower()
        ended = record.result is not None and record.exit_code is not None
        self.check(20, "image_attachment", ended and "red" in reply,
                   f"answered {reply[:40]!r}, result {record.result is not None}, exit {record.exit_code}, "
                   f"{record.duration_s} s", exit=record.exit_code, ended=ended, saw_red="red" in reply,
                   stderr=record.stderr.strip()[-200:] or None)

    def step_add_dir(self):
        """Other attached files are named by path in a folder added with --add-dir: Read must not ask."""
        self.hooks.set("deny")
        folder = Path(tempfile.mkdtemp(prefix="probe-files-", dir=self.c.state))
        try:
            (folder / "note.txt").write_text("quebec-42\n")
            record = self.turn(f"Use the Read tool to read {folder / 'note.txt'} and reply with its content only.",
                               add_dirs=[str(folder)], limit_s=120)
            asked = [c["tool"] for c in self.hooks.permission_calls()]
            got = "quebec-42" in record.reply()
            self.check(21, "add_dir_read", got and not asked,
                       f"content read {got}, hook asked for {asked or 'nothing'}", read=got, asked=asked,
                       tools=record.tool_uses())
        finally:
            shutil.rmtree(folder, ignore_errors=True)

    def step_remote(self):
        """22-33, only with --remote: Claude Code's interactive mode with Remote Control (probe_remote.py)."""
        from .probe_remote import RemoteSteps

        RemoteSteps(self).run()


def _row_text(value: object, limit: int) -> str | None:
    """A model row's own text (Claude Code's words, never the model's), on one line and short."""
    return one_line(value, limit) if isinstance(value, str) else None


def _find_list(events: list, key: str) -> list | None:
    for e in events:
        found = _search(e, key, list)
        if found is not None:
            return found
    return None


def _find_dict(events: list, key: str) -> dict | None:
    for e in events:
        found = _search(e, key, dict)
        if found is not None:
            return found
    return None


def _search(value, key: str, kind):
    if isinstance(value, dict):
        if isinstance(value.get(key), kind):
            return value[key]
        for v in value.values():
            found = _search(v, key, kind)
            if found is not None:
                return found
    elif isinstance(value, list):
        for v in value:
            found = _search(v, key, kind)
            if found is not None:
                return found
    return None


def _kill(proc: subprocess.Popen) -> None:
    try:
        os.killpg(proc.pid, signal.SIGKILL)
    except (ProcessLookupError, PermissionError):
        pass


def run_probe(c: ProbeConfig, claude_bin: str, mcp_running: bool, out=print, remote: bool = False,
              fixtures: Path | None = None) -> dict:
    c.state.mkdir(parents=True, exist_ok=True, mode=0o700)
    workdir = Path(tempfile.mkdtemp(prefix="probe-", dir=c.state))
    probe = Probe(c, claude_bin, workdir, mcp_running, out, remote=remote, keep_turns=fixtures is not None)
    trust = command.trust_folder(workdir)  # as zf agent setup does for the agent folder
    probe.report["trusted"] = trust
    try:
        report = probe.run()
        if fixtures is not None:
            report["fixtures"] = save_fixtures(probe, fixtures)
            if report["fixtures"]["refused"]:
                out(f"Fixtures not written: the self-check found {', '.join(report['fixtures']['refused'])} in them.")
        return report
    finally:
        probe.hooks.close()
        if trust == "trusted":
            command.untrust_folder(workdir)
        shutil.rmtree(workdir, ignore_errors=True)


FIXTURE_NAME = "[0-9][0-9]-*.jsonl"


def secrets_of(probe: Probe):
    """This machine's personal values, read in process for the redaction and its self-check."""
    c = probe.c
    folders = {probe.workdir: "<workdir>", c.state: "<state>"}
    base = c.state.parent.parent  # the folder the clone and its data sit in (config.py)
    if base != Path.home() and base.is_relative_to(Path.home()):
        folders[base] = "<base>"
    tokens = [c.phone_token, c.mcp_token, c.view_token, c.app_token, probe.hook_token]
    return local_secrets(tokens=tokens, paths=folders, ips=[c.host], claude_json=command.claude_config_file())


def save_fixtures(probe: Probe, folder: Path) -> dict:
    """Each kept turn as `NN-<step>[-k].jsonl` (one redacted event per line) and a manifest.json.
    All or nothing: if the self-check finds a personal value in any of them, nothing is written."""
    known = [CLAUDE_MD, *(t["prompt"] for t in probe.kept if t["prompt"])]
    redactor = Redactor(secrets_of(probe), known)
    files: dict[str, str] = {}
    manifest: dict = {"v": 1, "claude": probe.report.get("claude"), "files": []}
    per_step: dict[str, int] = {}
    joined: list[str] = []  # each streamed block whole: a value cut in pieces is found there
    for n, turn in enumerate(probe.kept, 1):
        k = per_step[turn["step"]] = per_step.get(turn["step"], 0) + 1
        name = f"{n:02d}-{turn['step']}" + (f"-{k}" if k > 1 else "") + ".jsonl"
        events = redactor.events(turn["events"])
        joined += joined_blocks(events)
        files[name] = "".join(json.dumps(e, ensure_ascii=False, sort_keys=True) + "\n" for e in events)
        manifest["files"].append({"file": name, "step": turn["step"], "exit": turn["exit"], "resume": turn["resume"],
                                  "mode": turn["mode"], "images": turn["images"],
                                  "prompt": redactor.value(turn["prompt"]) if turn["prompt"] is not None else None})
    files["manifest.json"] = json.dumps(redactor.walk(manifest), ensure_ascii=False, indent=1, sort_keys=True) + "\n"
    refused = redactor.leaks("\n".join([*files.values(), *joined]))
    if refused:
        return {"written": False, "files": 0, "refused": refused}
    folder.mkdir(parents=True, exist_ok=True)
    for old in folder.glob(FIXTURE_NAME):
        old.unlink()
    for name, text in files.items():
        (folder / name).write_text(text)
    return {"written": True, "files": len(files) - 1, "refused": []}


# The notes per app with Claude Code: two tiny Haiku turns against the throwaway bridge and its simulator, never
# the running service. The first saves a given note on the simulator's Dial; the second, in a new session, reads it
# back and must answer from it. Both run with the server's own command line and environment (command.base_env sets
# CLAUDE_CODE_DISABLE_AUTO_MEMORY), so the probe also checks that no auto memory file is written while they run.
NOTE_APPID = "sim_dial"  # sim.py's Dial (DIAL_PATH): the agent integration imports no adapter
NOTE_TEXT = "Opens on the dial. Long OK opens About. Exit: long BACK, then RIGHT."
NOTE_SAVE = (f"Call the flipper_note tool once, with appid {NOTE_APPID} and exactly this text: {NOTE_TEXT} "
             "Then reply with only the word done.")
NOTE_READ = (f"Call the flipper_app tool once, with appid {NOTE_APPID}. Its answer holds a note on the app. Reply with "
             "only the key that confirms the app's exit, as the note names it, nothing else.")


def memory_files(workdir: Path) -> dict[str, float]:
    """The files in Claude Code's auto memory folder for a working folder outside any git repository,
    ~/.claude/projects/<project>/memory/ (code.claude.com/docs/en/memory; under CLAUDE_CONFIG_DIR when set), with their
    modification times, compared before and after the turns; the files are never read. <project> is the folder's path
    with each character outside [A-Za-z0-9] as "-", so a folder of its own, with a unique name, never matches another
    session's memory on this machine."""
    import re

    base = os.environ.get("CLAUDE_CONFIG_DIR")
    projects = (Path(base).expanduser() if base else Path.home() / ".claude") / "projects"
    tail = re.sub(r"[^A-Za-z0-9]", "-", workdir.name)
    found: dict[str, float] = {}
    try:
        for memory in (p / "memory" for p in projects.glob("*") if p.name.endswith(tail)):
            for f in memory.rglob("*"):
                if f.is_file():
                    found[str(f)] = f.stat().st_mtime
    except OSError:
        pass
    return found


def audit_lines(path: Path) -> list[dict]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return []
    out = []
    for line in lines:
        try:
            record = json.loads(line)
        except ValueError:
            continue
        if isinstance(record, dict):
            out.append(record)
    return out


def run_notes_probe(c: ProbeConfig, claude_bin: str, out=print) -> dict:
    """`zf agent probe --notes`: c is the throwaway bridge's Config (cli._throwaway_bridge), whose simulator runs. The
    turns run in a temporary folder of their own outside any git repository (the system's temporary folder, removed at
    the end), so no parent CLAUDE.md reaches them and their auto memory folder is theirs alone."""
    workdir = Path(tempfile.mkdtemp(prefix="zeffin-probe-notes-"))
    probe = Probe(c, claude_bin, workdir, mcp_running=True, out=out)
    trust = command.trust_folder(workdir)
    audit = c.state / "audit.jsonl"
    report: dict = {"v": REPORT_VERSION, "trusted": trust, "model": PROBE_MODEL}
    before = memory_files(workdir)
    try:
        (workdir / "CLAUDE.md").write_text(CLAUDE_MD)
        out("1/2 notes: save a given note on the simulator's Dial")
        saved = probe.turn(NOTE_SAVE)
        records = audit_lines(audit)
        notes = [r for r in records if r.get("tool") == "flipper_note"]
        report["save"] = {"exit": saved.exit_code, "tool_used": "mcp__flipper__flipper_note" in saved.tool_uses(),
                          "note_saved": any(r.get("result") == "ok" for r in notes),
                          "note_chars": next((r.get("chars") for r in notes if r.get("result") == "ok"), None),
                          "seconds": saved.duration_s}
        out("2/2 notes: a new session reads it back")
        read = probe.turn(NOTE_READ)
        apps = [r for r in audit_lines(audit)[len(records):] if r.get("tool") == "flipper_app"]
        report["read"] = {"exit": read.exit_code, "tool_used": "mcp__flipper__flipper_app" in read.tool_uses(),
                          "note_returned": any(r.get("note") is True for r in apps),
                          "read_note": "right" in read.reply().lower(), "seconds": read.duration_s}
        after = memory_files(workdir)
        report["auto_memory"] = {"files_written": sum(1 for f, t in after.items() if before.get(f) != t)}
        report["api_errors"] = probe.report.get("api_errors", [])
        report["ok"] = bool(report["save"]["note_saved"] and report["read"]["note_returned"]
                            and report["read"]["read_note"] and not report["auto_memory"]["files_written"])
        return report
    except Exception as e:  # the report says where it stopped, never the error's text
        report["stopped"] = one_line(type(e).__name__, 60)
        return report
    finally:
        probe.hooks.close()
        if trust == "trusted":
            command.untrust_folder(workdir)
        shutil.rmtree(workdir, ignore_errors=True)
