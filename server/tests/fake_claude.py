# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""A fake `claude` CLI for tests: speaks the documented stream-json shapes and calls the http hooks.

Environment:
- FAKE_CLAUDE_HOME   folder for its sessions (required)
- FAKE_CLAUDE_LOG    jsonl file where each run records argv, env names and stdin
- FAKE_CLAUDE_AUTH   authMethod for `auth status` (default claude.ai; "none" = logged out)
- FAKE_CLAUDE_KEY_SOURCE  apiKeySource in init (default: ANTHROPIC_API_KEY when that variable reaches it, else none)
--bare is refused (exit 2): the bridge never uses it, since it skips the subscription login (code.claude.com, headless).
- FAKE_CLAUDE_NO_AUTO     set: auto mode is unavailable and the session starts in default
- FAKE_CLAUDE_FABLE       "included" or "credits": Fable is in the model list (its row mentions usage
                          credits with "credits"); unset: no Fable
- FAKE_CLAUDE_EXTRA_USAGE "on" or "off": /usage reports usage credits enabled or not; unset: no extra_usage

What the prompt asks decides what it does:
- "Use the Write tool to create <file> containing <word>": a permission-checked write; with
  "overwrite it with <word>" a second write follows
- "Count from 1 to N": streams N lines slowly (to be interrupted)
- "flipper_status" / "scenario:tool": a Flipper tool call
- "scenario:crash": exits 1 without a result; "scenario:slow": waits 30 s
- "remember the number N" / "What number": a memory kept per session
- "probe word": the word from CLAUDE.md in the folder
- "@/abs/path" of a file in the working folder or an --add-dir folder: the file reaches the model without a
  tool (a PNG's colour, else the file's first line); FAKE_CLAUDE_NO_MENTIONS set: mentions stay plain text
- anything else: "You said: <prompt>"

Without -p it runs interactively, as `claude --remote-control <name>` under a terminal: it reads keys
from the terminal (Enter submits, Esc interrupts a turn or rejects a permission prompt, "/exit" quits),
calls the SessionStart command hook and the http hooks UserPromptSubmit (a "block" decision stops the
prompt), PostToolUse, Stop, Notification (permission_prompt, since a write that asks waits for an answer
from the Claude app) and SessionEnd. With --remote-control it sets CLAUDE_CODE_BRIDGE_SESSION_ID.
- FAKE_CLAUDE_RC_CONSENT  set: asks "Enable Remote Control? (y/n)" first and waits for y
- FAKE_CLAUDE_INBOX       a file: each line added to it is a prompt from another device (the Claude app)
- FAKE_CLAUDE_TRANSCRIPTS set: one line per turn in $HOME/.claude/projects/<folder>/<session>.jsonl (the probe
                          counts and stats them; HOME must be a test folder)
- FAKE_CLAUDE_RC_FORK     set: a resumed interactive session loads the conversation but writes its turns to a
                          new session id (what the probe's steps 29 and 30 look for)
It turns bracketed paste on (ESC[?2004h) and reads a paste (ESC[200~ … ESC[201~) as text, newlines included; an Esc
alone in one read is the Esc key. A permission dialog takes Esc as no and Enter as yes (logged in allowed.log); a line
starting with "!" runs as a shell command (logged in shell.log), as in the real interactive mode. Every read from the
terminal is appended, raw, to keys.log.
In a folder not trusted in $HOME/.claude.json it first asks "Do you trust the files in this folder?".
"""
import json
import os
import re
import signal
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

VERSION = "2.1.999"


def out(event: dict) -> None:
    sys.stdout.write(json.dumps(event) + "\n")
    sys.stdout.flush()


def transcript(session: str) -> None:
    """One line per turn in ~/.claude/projects/<folder>/<session>.jsonl, so the probe can count and stat it."""
    if not os.environ.get("FAKE_CLAUDE_TRANSCRIPTS"):
        return
    folder = Path.home() / ".claude" / "projects" / re.sub(r"[^A-Za-z0-9]", "-", str(Path.cwd().resolve()))
    folder.mkdir(parents=True, exist_ok=True)
    with (folder / f"{session}.jsonl").open("a") as f:
        f.write(json.dumps({"type": "turn"}) + "\n")


def option(args: list[str], name: str) -> str | None:
    return args[args.index(name) + 1] if name in args else None


def main() -> int:
    args = sys.argv[1:]
    if "--bare" in args:
        sys.stderr.write("fake claude: --bare skips the subscription login; the bridge must never use it\n")
        return 2
    if args == ["--version"]:
        print(f"{VERSION} (Claude Code)")
        return 0
    if args[:2] == ["auth", "status"]:
        method = os.environ.get("FAKE_CLAUDE_AUTH", "claude.ai")
        print(json.dumps({"loggedIn": method != "none", "authMethod": method}))
        return 0 if method != "none" else 1
    home = Path(os.environ["FAKE_CLAUDE_HOME"])
    sessions = home / "sessions"
    sessions.mkdir(parents=True, exist_ok=True)
    if "-p" not in args:
        return interactive(args, sessions)
    images: list[str] = []
    if option(args, "--input-format") == "stream-json":
        message = control(sys.stdin)
        if message is None:
            sys.stderr.write("Error: Input must be provided either through stdin or as a prompt argument when using --print\n")
            return 1
        prompt, images = message
    else:
        prompt = sys.stdin.read()
    log = os.environ.get("FAKE_CLAUDE_LOG")
    if log:
        with open(log, "a") as f:
            f.write(json.dumps({"argv": args, "env": sorted(os.environ), "stdin": prompt, "images": images,
                                "hook_token": os.environ.get("FB_HOOK_TOKEN", "")[:4]}) + "\n")
    resume = option(args, "--resume")
    session = resume or option(args, "--session-id") or str(uuid.uuid4())
    state_file = sessions / f"{session}.json"
    if resume and not state_file.exists():
        sys.stderr.write(f"No conversation found with session ID: {resume}\n")
        return 1
    state = json.loads(state_file.read_text()) if state_file.exists() else {"turns": 0}
    state["turns"] += 1
    state_file.write_text(json.dumps(state))
    transcript(session)

    mode = option(args, "--permission-mode") or "default"
    if mode == "auto" and os.environ.get("FAKE_CLAUDE_NO_AUTO"):
        mode = "default"
    model = {"opus": "claude-opus-5-5", "sonnet": "claude-sonnet-5-5", "haiku": "claude-haiku-5-5",
             "fable": "claude-fable-5-1"}.get(option(args, "--model") or "", "claude-opus-5-5")
    mcp = []
    if option(args, "--mcp-config"):
        mcp = [{"name": name, "status": "connected"} for name in json.loads(option(args, "--mcp-config"))["mcpServers"]]
    out({"type": "system", "subtype": "init", "session_id": session, "cwd": os.getcwd(), "model": model,
         "permissionMode": mode, "apiKeySource": os.environ.get(
             "FAKE_CLAUDE_KEY_SOURCE", "ANTHROPIC_API_KEY" if os.environ.get("ANTHROPIC_API_KEY") else "none"),
         "claude_code_version": VERSION, "tools": ["Bash", "Read", "Write"], "mcp_servers": mcp,
         "effort": option(args, "--effort")})
    if prompt.strip() == "/usage":
        extra = os.environ.get("FAKE_CLAUDE_EXTRA_USAGE")
        report = {"session": {"total_cost_usd": 0}}
        if extra:
            report["extra_usage"] = {"is_enabled": extra == "on", "monthly_limit": None, "used_credits": 0,
                                     "utilization": 0, "currency": "USD"}
        out({"type": "assistant", "session_id": session, "parent_tool_use_id": None, "usage_report": report,
             "message": {"id": "msg_usage", "role": "assistant", "content": [{"type": "text", "text": "Usage"}]}})
        out({"type": "result", "subtype": "success", "is_error": False, "session_id": session, "num_turns": 0,
             "duration_ms": 1, "result": "Usage", "usage": {"output_tokens": 0}, "modelUsage": {},
             "permission_denials": []})
        return 0
    settings = json.loads(option(args, "--settings") or "{}")
    allowed = allowed_rules(args, settings)
    turn = Turn(session, mode, settings, allowed)
    turn.images = images
    turn.add_dirs = add_dirs(args)
    signal.signal(signal.SIGINT, turn.interrupted)
    if "scenario:api_key" in prompt:
        time.sleep(5)
    try:
        turn.run(prompt, state, state_file)
    except Crash:
        return 1
    out({"type": "result", "subtype": "success", "is_error": False, "session_id": session, "num_turns": turn.tools + 1,
         "duration_ms": 5, "result": turn.reply, "stop_reason": "end_turn", "permission_denials": turn.denials})
    return 0


def models() -> list:
    rows = [{"value": "default", "displayName": "Default (recommended)", "description": "Opus 5.5",
             "supportedEffortLevels": ["low", "medium", "high", "xhigh", "max"], "supportsAutoMode": True},
            {"value": "opus", "resolvedModel": "claude-opus-5-5", "displayName": "Opus", "description": "Opus 5.5",
             "supportedEffortLevels": ["low", "medium", "high", "xhigh", "max"], "supportsAutoMode": True},
            {"value": "sonnet", "resolvedModel": "claude-sonnet-5-5", "displayName": "Sonnet", "description": "Sonnet 5.5",
             "supportedEffortLevels": ["low", "medium", "high"], "supportsAutoMode": True},
            {"value": "haiku", "resolvedModel": "claude-haiku-5-5", "displayName": "Haiku", "description": "Haiku 5.5",
             "supportedEffortLevels": ["low", "medium", "high"], "supportsAutoMode": True}]
    fable = os.environ.get("FAKE_CLAUDE_FABLE")
    if fable:
        rows.append({"value": "fable", "resolvedModel": "claude-fable-5-1", "displayName": "Fable",
                     "description": "Fable 5.1" + (" · Requires usage credits" if fable == "credits" else ""),
                     "supportedEffortLevels": ["low", "medium", "high", "xhigh", "max"], "supportsAutoMode": True})
    return rows


def colour(data: str) -> str | None:
    """The first pixel's colour name of a base64 RGB PNG (what the probe sends), so the fake can "see" it."""
    import base64
    import struct
    import zlib

    try:
        raw = base64.b64decode(data)
        i, idat = 8, b""
        while i < len(raw):
            length = struct.unpack(">I", raw[i:i + 4])[0]
            if raw[i + 4:i + 8] == b"IDAT":
                idat += raw[i + 8:i + 8 + length]
            i += 12 + length
        r, g, b = zlib.decompress(idat)[1:4]
    except Exception:
        return None
    return max((("red", r), ("green", g), ("blue", b)), key=lambda kv: kv[1])[0]


def add_dirs(args: list[str]) -> list[str]:
    if "--add-dir" not in args:
        return []
    found = []
    for value in args[args.index("--add-dir") + 1:]:
        if value.startswith("--"):
            break
        found.append(value)
    return found


def control(stdin) -> tuple[str, list[str]] | None:
    """--input-format stream-json: answer the initialize control request; return the first user message as
    (its text, the media types of its image blocks), or None at EOF."""
    for line in stdin:
        try:
            message = json.loads(line)
        except ValueError:
            continue
        if message.get("type") == "control_request" and (message.get("request") or {}).get("subtype") == "initialize":
            out({"type": "control_response", "response": {"subtype": "success", "request_id": message.get("request_id"),
                 "response": {"commands": [], "agents": [], "output_style": "default", "available_output_styles": [],
                              "models": models(), "account": {"subscriptionType": "max"}}}})
        elif message.get("type") == "user":
            content = (message.get("message") or {}).get("content")
            if isinstance(content, str):
                return content, []
            blocks = [b for b in content or [] if isinstance(b, dict)]
            text = "\n".join(b.get("text", "") for b in blocks if b.get("type") == "text")
            images = [(b.get("source") or {}).get("media_type", "?") for b in blocks if b.get("type") == "image"]
            colours = [colour((b.get("source") or {}).get("data", "")) for b in blocks if b.get("type") == "image"]
            return text + "".join(f"\n[colour:{c}]" for c in colours if c), images
    return None


def suggestions(tool: str, rule: str) -> list:
    """Claude Code 2.1.308 (zf agent probe) suggests a mode change for file writes, rules for commands."""
    content = rule[len(tool) + 1:-1]
    if tool in ("Write", "Edit") or (tool == "Bash" and (">" in content or "<" in content)):
        # Also for a command with a redirect (zf chat --test, 2026-10-23): no rule, only a mode change.
        return [{"type": "setMode", "mode": "acceptEdits", "destination": "session"}]
    return [{"type": "addRules", "rules": [{"toolName": tool, "ruleContent": rule[len(tool) + 1:-1]}],
             "behavior": "allow", "destination": "localSettings"}]


class Crash(Exception):
    pass



def allowed_rules(args: list, settings: dict) -> list:
    """What Claude Code allows without asking: --allowedTools's list, and the inline settings' permissions.allow
    (code.claude.com/docs/en/settings: --settings' lists combine with the files')."""
    flag = args[args.index("--allowedTools") + 1:] if "--allowedTools" in args else []
    allow = (settings.get("permissions") or {}).get("allow") or []
    return [*flag, *[r for r in allow if isinstance(r, str)]]


class Turn:
    def __init__(self, session, mode, settings, allowed):
        self.session, self.mode, self.settings, self.allowed = session, mode, settings, list(allowed)
        self.denials, self.tools, self.reply, self.msg = [], 0, "", 0
        self.images: list[str] = []
        self.add_dirs: list[str] = []

    def interrupted(self, *_):
        sys.stderr.write("interrupted\n")
        os._exit(130)

    def text(self, text: str, chunks: int = 3, pause: float = 0.0) -> None:
        self.msg += 1
        mid = f"msg_{self.msg}"
        out({"type": "stream_event", "session_id": self.session, "parent_tool_use_id": None,
             "event": {"type": "message_start", "message": {"id": mid}}})
        out({"type": "stream_event", "session_id": self.session, "parent_tool_use_id": None,
             "event": {"type": "content_block_start", "index": 0, "content_block": {"type": "text", "text": ""}}})
        size = max(1, len(text) // chunks + 1)
        for i in range(0, len(text), size):
            out({"type": "stream_event", "session_id": self.session, "parent_tool_use_id": None,
                 "event": {"type": "content_block_delta", "index": 0,
                           "delta": {"type": "text_delta", "text": text[i:i + size]}}})
            if pause:
                time.sleep(pause)
        out({"type": "stream_event", "session_id": self.session, "parent_tool_use_id": None,
             "event": {"type": "content_block_stop", "index": 0}})
        out({"type": "assistant", "session_id": self.session, "parent_tool_use_id": None,
             "message": {"id": mid, "role": "assistant", "content": [{"type": "text", "text": text}]}})
        self.reply = text

    def inside(self, path: str) -> bool:
        return any(Path(path).resolve().is_relative_to(Path(d).resolve()) for d in [os.getcwd(), *self.add_dirs])

    def mentioned(self, prompt: str) -> str | None:
        """What an @ mention of an allowed file gives the model: a PNG's colour, else the file's first line."""
        if os.environ.get("FAKE_CLAUDE_NO_MENTIONS"):
            return None
        for path in re.findall(r"@(/\S+)", prompt):
            if not self.inside(path) or not Path(path).is_file():
                continue
            if path.endswith(".png"):
                import base64

                return (colour(base64.b64encode(Path(path).read_bytes()).decode()) or "unknown").capitalize() + "."
            return Path(path).read_text(errors="replace").strip().split("\n")[0]
        return None

    def read(self, path: str) -> str:
        """Read without asking inside the working folder or an --add-dir folder; otherwise ask the hook."""
        inside = self.inside(path)
        if not inside and not self.ask("Read", {"file_path": path}, []):
            return "denied"
        content = Path(path).read_text(errors="replace")
        self.tool("Read", {"file_path": path}, content)
        return content.strip().splitlines()[0] if content.strip() else ""

    def tool(self, name: str, tool_input: dict, result: list | str, is_error: bool = False) -> str:
        self.tools += 1
        self.msg += 1
        tid = f"toolu_{self.tools}"
        out({"type": "assistant", "session_id": self.session, "parent_tool_use_id": None,
             "message": {"id": f"msg_{self.msg}", "role": "assistant",
                         "content": [{"type": "tool_use", "id": tid, "name": name, "input": tool_input}]}})
        out({"type": "user", "session_id": self.session, "parent_tool_use_id": None,
             "message": {"role": "user", "content": [{"type": "tool_result", "tool_use_id": tid,
                                                      "content": result, "is_error": is_error}]}})
        return tid

    def hook(self, event: str, body: dict) -> dict | None:
        for group in self.settings.get("hooks", {}).get(event, []):
            for h in group.get("hooks", []):
                headers = {}
                for key, value in h.get("headers", {}).items():
                    for var in h.get("allowedEnvVars", []):
                        value = value.replace(f"${{{var}}}", os.environ.get(var, "")).replace(f"${var}", os.environ.get(var, ""))
                    headers[key] = value
                request = urllib.request.Request(h["url"], data=json.dumps(body).encode(), method="POST",
                                                 headers={"content-type": "application/json", **headers})
                try:
                    with urllib.request.urlopen(request, timeout=h.get("timeout", 600)) as r:
                        return json.loads(r.read() or b"{}")
                except (urllib.error.URLError, OSError, ValueError):
                    return None
        return None

    def permitted(self, tool: str, tool_input: dict, rule: str) -> bool:
        if any(a in (tool, rule) for a in self.allowed):
            return True
        if self.mode in ("auto", "bypassPermissions") or (self.mode == "acceptEdits" and tool in ("Write", "Edit")):
            return True
        if self.mode == "plan":
            # What Claude Code 2.1.308 is expected to do (to be confirmed by zf agent probe): nothing runs
            # until the plan is approved through ExitPlanMode; then the turn goes on in Manual.
            plan = {"plan": f"1. Use {tool}: {json.dumps(tool_input)[:200]}"}
            if not self.ask("ExitPlanMode", plan, [{"type": "setMode", "mode": "acceptEdits", "destination": "session"}]):
                self.tool("ExitPlanMode", plan, "The user rejected the plan", is_error=True)
                return False
            self.tool("ExitPlanMode", plan, "Plan approved")
            self.mode = "default"
            return self.permitted(tool, tool_input, rule)
        if self.mode == "dontAsk":
            self.denials.append({"tool_name": tool, "tool_use_id": f"toolu_{self.tools + 1}", "tool_input": tool_input})
            return False
        return self.ask(tool, tool_input, suggestions(tool, rule))

    def ask(self, tool: str, tool_input: dict, suggested: list) -> bool:
        tid = f"toolu_{self.tools + 1}"
        answer = self.hook("PermissionRequest", {
            "session_id": self.session, "transcript_path": "/tmp/t.jsonl", "cwd": os.getcwd(),
            "permission_mode": self.mode, "hook_event_name": "PermissionRequest", "tool_name": tool,
            "tool_input": tool_input, "permission_suggestions": suggested})
        decision = ((answer or {}).get("hookSpecificOutput") or {}).get("decision") or {}
        if decision.get("behavior") == "allow":
            for update in decision.get("updatedPermissions") or []:
                for r in update.get("rules", []):
                    self.allowed.append(f"{r['toolName']}({r['ruleContent']})" if r.get("ruleContent") else r["toolName"])
            return True
        self.denials.append({"tool_name": tool, "tool_use_id": tid, "tool_input": tool_input})
        out({"type": "system", "subtype": "permission_denied", "session_id": self.session,
             "tool_name": tool, "tool_use_id": tid, "decision_reason_type": "hook",
             "decision_reason": "The user denied this action.", "message": "Permission denied"})
        if decision.get("interrupt"):
            sys.exit(130)
        return False

    def bash(self, command: str) -> bool:
        tool_input = {"command": command}
        if not self.permitted("Bash", tool_input, f"Bash({command})"):
            self.tool("Bash", tool_input, "Permission denied", is_error=True)
            return False
        echo = re.fullmatch(r"echo (\w+) > (\S+)", command)
        if echo:
            Path(echo.group(2)).write_text(echo.group(1))
        touch = re.fullmatch(r"touch (\S+)", command)
        if touch:
            Path(touch.group(1)).touch()
        self.tool("Bash", tool_input, "ok")
        return True

    def write(self, name: str, word: str) -> bool:
        tool_input = {"file_path": str(Path.cwd() / name), "content": word}
        if not self.permitted("Write", tool_input, f"Write({name})"):
            self.tool("Write", tool_input, "Permission denied", is_error=True)
            return False
        Path(name).write_text(word)
        self.tool("Write", tool_input, f"Wrote {name}")
        return True

    def run(self, prompt: str, state: dict, state_file: Path) -> None:
        if self.images:
            seen = re.findall(r"\[colour:(\w+)\]", prompt)
            if "what colour" in prompt and seen:
                return self.text(seen[0].capitalize() + ".")
            return self.text(f"I see {len(self.images)} image(s): {', '.join(self.images)}.")
        seen_file = self.mentioned(prompt)
        if seen_file is not None:
            return self.text(seen_file)
        m = re.search(r"Read tool to read (\S+?)\.? Then reply with the probe word and the number I asked you to remember",
                      prompt)
        if m:
            content = self.read(m.group(1))
            word = re.search(r"probe word is (\w+)", Path(m.group(1)).read_text()) if content else None
            reply = f"{word.group(1) if word else 'unknown'} {state.get('number', 'none')}"
            again = re.search(r"remember the number (\d+)", prompt)
            if again:
                state["number"] = again.group(1)
                state_file.write_text(json.dumps(state))
            return self.text(reply)
        m = re.search(r"Read tool to read (\S+) and reply", prompt)
        if m:
            return self.text(self.read(m.group(1)))
        files = re.findall(r"^- (/\S+) \(sent as ", prompt, re.M)
        if files:
            return self.text("Read: " + " | ".join(self.read(path) for path in files))
        if "scenario:crash" in prompt:
            sys.stderr.write("something broke\n")
            raise Crash
        if "scenario:slow" in prompt:
            self.text("Working on it")
            time.sleep(30)
        m = re.search(r"Write tool to create (\S+) containing (?:the word )?(\w+)", prompt)
        if m:
            ok = self.write(m.group(1), m.group(2))
            again = re.search(r"overwrite \S+ with (?:the word )?(\w+)", prompt)
            if ok and again:
                ok = self.write(m.group(1), again.group(1))
            bash = re.search(r"Bash tool to run exactly: (.+)", prompt)
            if ok and bash:
                ok = self.bash(bash.group(1).strip())
            return self.text("Done." if ok else "DENIED")
        m = re.search(r"^Use the Bash tool to run exactly: (.+)", prompt.strip())
        if m:
            return self.text("Ran it." if self.bash(m.group(1).strip()) else "It was denied.")
        m = re.search(r"Count from 1 to (\d+)", prompt)
        if m:
            return self.text("\n".join(str(i) for i in range(1, int(m.group(1)) + 1)), chunks=200, pause=0.05)
        m = re.search(r"Bash command: (.+)", prompt)
        if m:
            return self.text("Ran it." if self.bash(m.group(1).strip()) else "It was denied.")
        if "flipper_status" in prompt:
            self.tool("mcp__flipper__flipper_status", {}, [{"type": "text", "text": '{"connected": true}'}])
            return self.text("done")
        if "scenario:tool" in prompt:
            meta = json.dumps({"seq": int(os.environ.get("FAKE_CLAUDE_SEQ", "7")), "stale": False})
            self.text("Pressing.")
            self.tool("mcp__flipper__flipper_press", {"keys": ["DOWN", "OK"]},
                      [{"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": "iVBORw0KGgo="}},
                       {"type": "text", "text": meta}])
            return self.text("Pressed.")
        m = re.search(r"remember the number (\d+)", prompt)
        if m:
            state["number"] = m.group(1)
            state_file.write_text(json.dumps(state))
        if "probe word" in prompt:
            word = re.search(r"probe word is (\w+)", Path("CLAUDE.md").read_text() if Path("CLAUDE.md").exists() else "")
            return self.text(word.group(1) if word else "unknown")
        if "What number" in prompt:
            return self.text(state.get("number", "none"))
        if "Reply with only the word" in prompt:
            return self.text(prompt.split("Reply with only the word")[1].strip(" .\n").split()[0])
        self.text(f"You said: {prompt.strip()}")


# ---------------------------------------------------------------- interactive mode (Remote Control)

class Interrupted(Exception):
    pass


class InteractiveTurn(Turn):
    """A turn in the interactive process: tools fire PostToolUse, a permission prompt waits for the
    terminal (Esc rejects it), and Esc interrupts the text."""

    def __init__(self, session, mode, settings, allowed, keys):
        super().__init__(session, mode, settings, allowed)
        self.keys = keys

    def screen(self, text: str) -> None:
        sys.stdout.write(text.replace("\n", "\r\n") + "\r\n")
        sys.stdout.flush()

    def text(self, text: str, chunks: int = 3, pause: float = 0.0) -> None:
        size = max(1, len(text) // chunks + 1)
        for i in range(0, len(text), size):
            if self.keys.escaped():
                raise Interrupted
            sys.stdout.write(text[i:i + size].replace("\n", "\r\n"))
            sys.stdout.flush()
            if pause:
                time.sleep(pause)
        self.screen("")
        self.reply = text

    def tool(self, name: str, tool_input: dict, result, is_error: bool = False) -> str:
        self.tools += 1
        tid = f"toolu_{self.tools}"
        self.hook("PreToolUse", {**self.common("PreToolUse"), "tool_name": name, "tool_input": tool_input,
                                 "tool_use_id": tid})
        self.screen(f"● {name}")
        if not is_error:
            self.hook("PostToolUse", {**self.common("PostToolUse"), "tool_name": name, "tool_input": tool_input,
                                      "tool_response": result, "tool_use_id": tid, "duration_ms": 3})
        return tid

    def ask(self, tool: str, tool_input: dict, suggested: list) -> bool:
        self.screen(f"Do you want to proceed? {tool} (esc to reject)")
        self.keys.escaped()  # forget an earlier Esc
        self.hook("Notification", {**self.common("Notification"), "message": "Claude needs your permission to use "
                                   + tool, "title": "Permission needed", "notification_type": "permission_prompt"})
        import queue

        while True:  # like the real dialog: Esc rejects, Enter takes the focused option, "Yes"
            if self.keys.escaped():
                raise Interrupted
            try:
                self.keys.lines.get(timeout=0.05)
            except queue.Empty:
                continue
            Path(os.environ["FAKE_CLAUDE_HOME"], "allowed.log").open("a").write(tool + "\n")
            return True

    def common(self, event: str) -> dict:
        transcript = str(Path(os.environ["FAKE_CLAUDE_HOME"], f"{self.session}.jsonl"))
        return {"session_id": self.session, "transcript_path": transcript, "cwd": os.getcwd(),
                "permission_mode": self.mode, "hook_event_name": event}


class Keys:
    """Reads the terminal in raw mode on a thread: lines on Enter, a flag on Esc."""

    def __init__(self):
        import queue
        import termios
        import threading
        import tty

        self.fd = sys.stdin.fileno()
        self.saved = termios.tcgetattr(self.fd)
        tty.setraw(self.fd)
        self.lines, self.esc, self.buffer = queue.Queue(), threading.Event(), ""
        threading.Thread(target=self.read, daemon=True).start()

    def read(self):
        paste = None  # the text of a bracketed paste being read
        while True:
            try:
                data = os.read(self.fd, 65536).decode(errors="replace")
            except OSError:
                self.lines.put(None)
                return
            if not data:
                self.lines.put(None)
                return
            with Path(os.environ["FAKE_CLAUDE_HOME"], "keys.log").open("a", newline="") as f:
                f.write(data)
            if data == "\x1b":  # the Esc key: alone in its read
                self.esc.set()
                continue
            i = 0
            while i < len(data):
                if paste is not None:
                    end = data.find("\x1b[201~", i)
                    if end < 0:
                        paste += data[i:]
                        break
                    self.buffer += paste + data[i:end]
                    paste, i = None, end + len("\x1b[201~")
                    continue
                if data.startswith("\x1b[200~", i):
                    paste, i = "", i + len("\x1b[200~")
                    continue
                ch = data[i]
                i += 1
                if ch == "\x1b":
                    self.esc.set()
                elif ch == "\x03":
                    self.lines.put(None)
                elif ch in "\r\n":
                    self.lines.put(self.buffer)
                    self.buffer = ""
                else:
                    self.buffer += ch

    def inbox(self, path: str):
        """Lines added to the file are prompts from another device."""
        import threading

        def watch():
            seen = 0
            while True:
                time.sleep(0.1)
                try:
                    lines = Path(path).read_text().splitlines()
                except OSError:
                    continue
                for line in lines[seen:]:
                    self.lines.put(line)
                seen = len(lines)
        threading.Thread(target=watch, daemon=True).start()

    def escaped(self) -> bool:
        if self.esc.is_set():
            self.esc.clear()
            return True
        return False

    def restore(self):
        import termios

        termios.tcsetattr(self.fd, termios.TCSADRAIN, self.saved)


def command_hooks(settings: dict, event: str, body: dict) -> None:
    import subprocess

    for group in settings.get("hooks", {}).get(event, []):
        for h in group.get("hooks", []):
            if h.get("type") == "command":
                subprocess.run(h["command"], shell=True, input=json.dumps(body), text=True, timeout=10)


def trusted(folder: Path) -> bool:
    try:
        config = json.loads((Path(os.environ["HOME"]) / ".claude.json").read_text())
    except (OSError, ValueError, KeyError):
        return False
    return ((config.get("projects") or {}).get(str(folder)) or {}).get("hasTrustDialogAccepted") is True


def interactive(args: list[str], sessions: Path) -> int:
    resume = option(args, "--resume")
    session = resume or option(args, "--session-id") or str(uuid.uuid4())
    state_file = sessions / f"{session}.json"
    if resume and not state_file.exists():
        sys.stderr.write(f"No conversation found with session ID: {resume}\n")
        return 1
    state = json.loads(state_file.read_text()) if state_file.exists() else {"turns": 0}
    if resume and os.environ.get("FAKE_CLAUDE_RC_FORK"):
        session = str(uuid.uuid4())
        state_file = sessions / f"{session}.json"
    log = os.environ.get("FAKE_CLAUDE_LOG")
    if log:
        with open(log, "a") as f:
            f.write(json.dumps({"argv": args, "env": sorted(os.environ), "stdin": None, "interactive": True}) + "\n")
    keys = Keys()
    settings = json.loads(option(args, "--settings") or "{}")
    allowed = allowed_rules(args, settings)
    turn = InteractiveTurn(session, option(args, "--permission-mode") or "default", settings, allowed, keys)
    turn.add_dirs = add_dirs(args)
    try:
        turn.screen(f"Claude Code v{VERSION}")
        if not trusted(Path.cwd()):
            turn.screen("Do you trust the files in this folder? 1. Yes, proceed 2. No, exit")
            if (keys.lines.get() or "1").strip() != "1":
                return 1
        if "--remote-control" in args and os.environ.get("FAKE_CLAUDE_RC_CONSENT"):
            turn.screen("Enable Remote Control? (y/n)")
            if (keys.lines.get() or "").strip().lower() != "y":
                return 0
        sys.stdout.write("\x1b[?2004h")
        command_hooks(settings, "SessionStart", {**turn.common("SessionStart"), "source": "resume" if resume else "startup"})
        if "--remote-control" in args:
            os.environ["CLAUDE_CODE_BRIDGE_SESSION_ID"] = "cse_fake" + session[:8]
            turn.screen("/rc active")
        if os.environ.get("FAKE_CLAUDE_INBOX"):
            keys.inbox(os.environ["FAKE_CLAUDE_INBOX"])
        turn.screen("> ")
        while True:
            line = keys.lines.get()
            if line is None or line.strip() == "/exit":
                turn.hook("SessionEnd", {**turn.common("SessionEnd"),
                                         "reason": "prompt_input_exit" if line is not None else "other"})
                return 0
            if not line.strip():
                continue
            if line.startswith("!"):  # shell mode: runs at once, no prompt and no permission (interactive-mode page)
                Path(os.environ["FAKE_CLAUDE_HOME"], "shell.log").open("a").write(line + "\n")
                continue
            answer = turn.hook("UserPromptSubmit", {**turn.common("UserPromptSubmit"), "prompt": line}) or {}
            if answer.get("decision") == "block":
                turn.screen(f"Operation stopped by hook: {answer.get('reason', '')}")
                continue
            keys.escaped()
            state["turns"] += 1
            state_file.write_text(json.dumps(state))
            transcript(session)
            try:
                turn.run(line, state, state_file)
            except Interrupted:
                turn.screen("Interrupted · What should Claude do instead?")
                continue
            turn.hook("Stop", {**turn.common("Stop"), "stop_hook_active": False,
                               "last_assistant_message": turn.reply, "background_tasks": [], "session_crons": []})
            turn.screen("> ")
    finally:
        keys.restore()


if __name__ == "__main__":
    sys.exit(main())
