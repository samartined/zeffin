# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The command line, environment and inline settings of one `claude -p` turn.

Checked on code.claude.com (2026-10-22): headless, cli-reference, sessions, hooks, permissions,
authentication and model-config. Shared by the chat manager and `zf agent probe`, so the probe
tests exactly what the server runs.

- The message goes in on stdin; no token is ever on the command line (both travel in the
  environment, and the settings reference them by name). A message with images goes as one
  stream-json `user` message with image blocks (code.claude.com/docs/en/agent-sdk/streaming-vs-single-mode:
  text input takes no images); other attached files are named by path, in a folder added with `--add-dir`.
- `--setting-sources project,local` leaves the owner's user settings out: app conversations get
  only the Flipper MCP and the permissions chosen in the app.
- Never `--bare`: it ignores the subscription login.
- The inline settings ask before an edit of the agents' own instructions and OpenCode's configuration (`ask_rules`),
  which Claude Code's protected paths leave out; it already asks for `.claude/` and `.mcp.json` in every mode but
  bypassPermissions, and no allow rule pre-approves those (code.claude.com/docs/en/permission-modes, "Protected
  paths", checked 2026-11-03).
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import tempfile
import threading
from dataclasses import dataclass, field
from pathlib import Path
from typing import Mapping

from ..wire import EFFORTS, MODES, valid_rule

HOOK_TOKEN_VAR = "FB_HOOK_TOKEN"
MCP_TOKEN_VAR = "ZEFFIN_MCP_TOKEN"
FLIPPER_TOOLS = "mcp__flipper__*"
MAX_HOOK_TIMEOUT_S = 600  # the documented default for http hooks; the server answers before it

# Variables that make `claude -p` bill per use or use another provider: never passed to a turn while the "free only"
# switch is on (config.free_only); off, the user's own choice of login reaches claude as the service has it.
SCRUB_PREFIXES = ("ANTHROPIC_", "CLAUDE_CODE_USE_")
SCRUB_NAMES = {"AWS_BEARER_TOKEN_BEDROCK"}
SUBSCRIPTION_AUTH = ("claude.ai", "oauth_token")


def scrubbed(name: str) -> bool:
    return name.startswith(SCRUB_PREFIXES) or name in SCRUB_NAMES


def billing_vars(environ: Mapping[str, str]) -> list[str]:
    """Names (never values) of variables that would switch claude away from the subscription."""
    return sorted(k for k in environ if scrubbed(k))


# Every `claude` the server starts for the agent (a turn, Remote Control, the model list) shares these: the owner's user
# settings and MCP servers left out, and the environment below.
ISOLATION = ("--setting-sources", "project,local", "--strict-mcp-config")


# Claude Code's auto memory, on by default, is loaded at the start of each session as instructions, and only `--bare`
# skips it in `claude -p` (code.claude.com/docs/en/memory, "Enable or disable auto memory"; docs/en/headless). Zeffin
# never uses --bare, so every claude it starts (turns, Remote Control, the model list) turns it off with this variable,
# which takes precedence over the autoMemoryEnabled setting; a false value such as 0 would force it on. The notes per
# app are Zeffin's one memory, handed back as untrusted data, for every agent.
AUTO_MEMORY_OFF = ("CLAUDE_CODE_DISABLE_AUTO_MEMORY", "1")


def base_env(base: Mapping[str, str] | None = None, free_only: bool = True) -> dict[str, str]:
    """The environment without self-updates or auto memory, and without billing variables while free only is on."""
    env = clean_env(base, free_only)
    env["DISABLE_AUTOUPDATER"] = "1"
    env[AUTO_MEMORY_OFF[0]] = AUTO_MEMORY_OFF[1]
    return env


def turn_env(base: Mapping[str, str], *, hook_token: str, mcp_token: str, free_only: bool = True) -> dict[str, str]:
    """A turn's environment, which Remote Control's wraps: base_env (auto memory off) and the two tokens."""
    env = base_env(base, free_only)
    env[HOOK_TOKEN_VAR] = hook_token
    env[MCP_TOKEN_VAR] = mcp_token
    return env


def mcp_config(mcp_url: str) -> str:
    return json.dumps({"mcpServers": {"flipper": {
        "type": "http", "url": mcp_url, "headers": {"Authorization": f"Bearer ${{{MCP_TOKEN_VAR}}}"}}}})


def hook_timeout(permission_timeout_s: float) -> int:
    return int(min(MAX_HOOK_TIMEOUT_S, permission_timeout_s + 30))


# Ask rules (code.claude.com/docs/en/permissions, "Manage permissions": deny, then ask, then allow; a matching ask
# rule prompts in every mode but bypassPermissions, also when an allow rule such as a saved "always" matches;
# permission-modes, "Actions no mode auto-approves"). Bare file names and a one-segment folder match at any depth under
# the agent folder; ~/ is the home folder. The Bash ones cover the file commands Accept edits runs without asking
# (mkdir, touch, rm, rmdir, mv, cp, sed) when they name these files plainly: a check on the command's text, not a
# boundary (permissions, "Bash"). Not covered by Claude Code's own protected paths, which ask for .claude/ and .mcp.json.
GUARDED_FILES = ("CLAUDE.md", "CLAUDE.local.md", "AGENTS.md", "opencode.json", "opencode.jsonc")
GUARDED_BASH = ("CLAUDE.md", "CLAUDE.local.md", "AGENTS.md", "opencode.json", ".opencode", ".config/opencode")


def ask_rules() -> list[str]:
    """The ask rules every turn and Remote Control session carries: an edit of the agents' instructions or of
    OpenCode's configuration, in the agent folder or OpenCode's global folder, always reaches the owner's card. The same
    on every server, so what is launched stays fixed; a global folder elsewhere ($XDG_CONFIG_HOME) is outside the agent
    folder, where Accept edits asks anyway."""
    return ([f"Edit({name})" for name in GUARDED_FILES] + ["Edit(.opencode/**)", "Edit(~/.config/opencode/**)"]
            + [f"Bash(*{name}*)" for name in GUARDED_BASH])


def _allow(rules: list[str] | None) -> dict:
    """The conversation's "always" rules as permissions.allow in the inline settings: one JSON string per rule, so
    nothing splits or joins them, as a command line's --allowedTools list would (security scan before 1.0.0, F1;
    code.claude.com/docs/en/settings, "Settings precedence": --settings sits above the user, project and local files,
    and permissions.allow lists from every source are combined). Only valid rules (wire.valid_rule)."""
    kept = [r for r in rules or [] if valid_rule(r)]
    return {"allow": kept} if kept else {}


def settings_json(hooks_url: str, permission_timeout_s: float, cleanup_days: int | None = None,
                  allow: list[str] | None = None) -> str:
    def hook(path: str, timeout: int) -> list:
        return [{"hooks": [{"type": "http", "url": f"{hooks_url}/{path}", "timeout": timeout,
                            "headers": {"Authorization": f"Bearer ${HOOK_TOKEN_VAR}"},
                            "allowedEnvVars": [HOOK_TOKEN_VAR]}]}]

    settings: dict = {"hooks": {
        "PermissionRequest": hook("permission-request", hook_timeout(permission_timeout_s)),
        "PermissionDenied": hook("permission-denied", 10),
    }, "permissions": {"ask": ask_rules(), **_allow(allow)}}
    if cleanup_days is not None:
        settings["cleanupPeriodDays"] = cleanup_days
    return json.dumps(settings)


def user_cleanup_days(home: Path | None = None) -> int | None:
    """The owner's cleanupPeriodDays, copied so isolated turns never sweep transcripts sooner."""
    path = (home or Path.home()) / ".claude" / "settings.json"
    try:
        value = json.loads(path.read_text()).get("cleanupPeriodDays")
    except (OSError, ValueError, AttributeError):
        return None
    return value if isinstance(value, int) and not isinstance(value, bool) and value >= 1 else None


@dataclass
class TurnSpec:
    claude_bin: str
    session_id: str
    resume: bool
    model: str
    mode: str                     # one of MODES
    mcp_url: str
    hooks_url: str                # e.g. http://127.0.0.1:9435/hooks
    permission_timeout_s: float = 120
    rules: list[str] = field(default_factory=list)
    cleanup_days: int | None = None
    effort: str | None = None     # one of EFFORTS; None: the model's default
    add_dirs: list[str] = field(default_factory=list)  # folders Claude may read without asking (attachments)
    input_json: bool = False      # the message goes as one stream-json user message (it has images)


def build_command(spec: TurnSpec) -> list[str]:
    if spec.mode not in MODES and spec.mode != "dontAsk":  # dontAsk: only the probe's own checks
        raise ValueError(f"unknown mode {spec.mode!r}")
    if spec.effort is not None and spec.effort not in EFFORTS:
        raise ValueError(f"unknown effort {spec.effort!r}")
    return [
        spec.claude_bin, "-p", "--output-format", "stream-json", "--verbose", "--include-partial-messages",
        "--resume" if spec.resume else "--session-id", spec.session_id,
        "--model", spec.model,
        *(["--effort", spec.effort] if spec.effort else []),
        *(["--input-format", "stream-json"] if spec.input_json else []),
        *(["--add-dir", *spec.add_dirs] if spec.add_dirs else []),
        "--permission-mode", spec.mode,
        *ISOLATION, "--mcp-config", mcp_config(spec.mcp_url),
        "--settings", settings_json(spec.hooks_url, spec.permission_timeout_s, spec.cleanup_days, spec.rules),
        "--allowedTools", FLIPPER_TOOLS,  # a constant: the conversation's rules go in --settings, never here
    ]


# ------------------------------------------------------------------ Remote Control
# code.claude.com/docs/en/remote-control (2026-10-24): `claude --remote-control "<name>"` starts an
# interactive session that the Claude app and claude.ai/code can follow and drive; resuming a conversation
# reconnects the remote session recorded in it. Interactive only: the process needs a terminal.
# code.claude.com/docs/en/hooks: the documented way to follow such a session (never its transcript file).

BRIDGE_SESSION_VAR = "CLAUDE_CODE_BRIDGE_SESSION_ID"  # set by Claude Code while Remote Control is connected
BRIDGE_HEADER = "X-FB-Remote-Session"
REMOTE_HOOKS = {"UserPromptSubmit": "user-prompt-submit", "PreToolUse": "pre-tool-use", "PostToolUse": "post-tool-use",
                "Stop": "stop",
                "StopFailure": "stop-failure", "Notification": "notification", "SessionEnd": "session-end"}


def remote_settings_json(hooks_url: str, cleanup_days: int | None = None,
                         session_start_command: str | None = None, allow: list[str] | None = None) -> str:
    """Inline settings of a Remote Control process: http hooks that follow it, under hooks_url/remote/.
    No PermissionRequest hook: Claude Code forwards permission prompts to the connected device.
    SessionStart takes only command hooks (hooks page); the probe uses one to know the prompt is up."""
    def hook(path: str, timeout: int) -> list:
        return [{"hooks": [{"type": "http", "url": f"{hooks_url}/remote/{path}", "timeout": timeout,
                            "headers": {"Authorization": f"Bearer ${HOOK_TOKEN_VAR}",
                                        BRIDGE_HEADER: f"${BRIDGE_SESSION_VAR}"},
                            "allowedEnvVars": [HOOK_TOKEN_VAR, BRIDGE_SESSION_VAR]}]}]

    settings: dict = {"hooks": {event: hook(path, 10) for event, path in REMOTE_HOOKS.items()},
                      "permissions": {"ask": ask_rules(), **_allow(allow)}}
    if session_start_command:
        settings["hooks"]["SessionStart"] = [{"hooks": [{"type": "command", "command": session_start_command,
                                                         "timeout": 10}]}]
    if cleanup_days is not None:
        settings["cleanupPeriodDays"] = cleanup_days
    return json.dumps(settings)


def build_remote_command(spec: TurnSpec, name: str, session_start_command: str | None = None) -> list[str]:
    """The interactive `claude --resume <id> --remote-control <name>` for a conversation: the same model,
    effort, mode, MCP, rules and setting sources as its turns, without -p and stream-json. Its attachments
    folder goes in add_dirs on every launch: --resume does not restore it."""
    if spec.mode not in MODES:
        raise ValueError(f"unknown mode {spec.mode!r}")
    if spec.effort is not None and spec.effort not in EFFORTS:
        raise ValueError(f"unknown effort {spec.effort!r}")
    return [
        spec.claude_bin, "--remote-control", name,
        "--resume" if spec.resume else "--session-id", spec.session_id,
        "--model", spec.model,
        *(["--effort", spec.effort] if spec.effort else []),
        *(["--add-dir", *spec.add_dirs] if spec.add_dirs else []),
        "--permission-mode", spec.mode,
        *ISOLATION, "--mcp-config", mcp_config(spec.mcp_url),
        "--settings", remote_settings_json(spec.hooks_url, spec.cleanup_days, session_start_command, spec.rules),
        "--allowedTools", FLIPPER_TOOLS,  # a constant: the conversation's rules go in --settings, never here
    ]


def user_message(text: str, images: list[tuple[str, bytes]]) -> str:
    """One stream-json user message: the images first, then the text (platform.claude.com, Vision)."""
    import base64

    content: list[dict] = [{"type": "image", "source": {"type": "base64", "media_type": media_type,
                                                         "data": base64.b64encode(data).decode()}}
                           for media_type, data in images]
    if text:
        content.append({"type": "text", "text": text})
    return json.dumps({"type": "user", "message": {"role": "user", "content": content},
                       "parent_tool_use_id": None}) + "\n"


# ------------------------------------------------------------------ the CLI itself

def find_claude(home: Path | None = None) -> str | None:
    found = shutil.which("claude")
    if found:
        return str(Path(found).absolute())
    local = (home or Path.home()) / ".local" / "bin" / "claude"
    return str(local) if os.access(local, os.X_OK) else None


def clean_env(base: Mapping[str, str] | None = None, free_only: bool = True) -> dict[str, str]:
    """The environment for a `claude` the server starts: without billing variables while free only is on."""
    return {k: v for k, v in (os.environ if base is None else base).items() if not (free_only and scrubbed(k))}


def claude_version(claude_bin: str, timeout: float = 20, free_only: bool = True) -> str | None:
    try:
        out = subprocess.run([claude_bin, "--version"], capture_output=True, text=True, timeout=timeout,
                             env=clean_env(None, free_only), stdin=subprocess.DEVNULL)
    except (OSError, subprocess.SubprocessError):
        return None
    words = out.stdout.split()
    return words[0] if out.returncode == 0 and words else None


def auth_status(claude_bin: str, timeout: float = 20, free_only: bool = True) -> tuple[bool, str | None]:
    """(logged in, authMethod) from `claude auth status`, which prints JSON. In the turns' environment, so an API key
    the service passes on with free only off is the login it reports."""
    try:
        out = subprocess.run([claude_bin, "auth", "status"], capture_output=True, text=True, timeout=timeout,
                             env=clean_env(None, free_only), stdin=subprocess.DEVNULL)
        data = json.loads(out.stdout or "{}")
    except (OSError, subprocess.SubprocessError, ValueError):
        return False, None
    method = data.get("authMethod") if isinstance(data, dict) else None
    return out.returncode == 0 and method not in (None, "none"), method if isinstance(method, str) else None


def models_argv(claude_bin: str) -> list[str]:
    """The model list's command line: the turns' isolation, no session kept."""
    return [claude_bin, "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
            *ISOLATION, "--no-session-persistence"]


def list_models(claude_bin: str, cwd: str | None = None, timeout: float = 30, free_only: bool = True) -> list | None:
    """The `models` of Claude Code's answer to the initialize control request, or None. No model turn:
    the process ends when stdin closes without a message."""
    argv = models_argv(claude_bin)
    env = base_env(None, free_only)
    try:
        proc = subprocess.Popen(argv, cwd=cwd, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, text=True, start_new_session=True)
    except OSError:
        return None
    timer = threading.Timer(timeout, _kill_group, (proc,))
    timer.start()
    models = None
    try:
        proc.stdin.write(json.dumps({"type": "control_request", "request_id": "fb-models",
                                     "request": {"subtype": "initialize"}}) + "\n")
        proc.stdin.flush()
        for line in proc.stdout:
            try:
                event = json.loads(line)
            except ValueError:
                continue
            if isinstance(event, dict) and event.get("type") == "control_response":
                response = (event.get("response") or {}).get("response") or {}
                models = response.get("models") if isinstance(response.get("models"), list) else None
                break
    except (OSError, ValueError):
        pass
    finally:
        timer.cancel()
        try:
            proc.stdin.close()
            proc.wait(timeout=10)
        except (OSError, ValueError, subprocess.TimeoutExpired):
            _kill_group(proc)
    return models


def _kill_group(proc: subprocess.Popen) -> None:
    try:
        os.killpg(proc.pid, 9)
    except (ProcessLookupError, PermissionError):
        pass


def claude_dir() -> Path:
    """Claude Code's ~/.claude (CLAUDE_CONFIG_DIR when set: every ~/.claude path lives under it,
    code.claude.com/docs/en/claude-directory)."""
    base = os.environ.get("CLAUDE_CONFIG_DIR")
    return Path(base).expanduser() if base else Path.home() / ".claude"


_SESSION = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")


def session_paths(session_id: str, root: Path | None = None) -> list[Path]:
    """What Claude Code keeps for one session, as code.claude.com/docs/en/claude-directory lists it under
    "Application data": the transcript projects/<project>/<session>.jsonl, the copies it set aside
    (<session>.orphaned-*.jsonl, <session>.jsonl.superseded-*), the folder projects/<project>/<session>/
    (subagents, tool results) and file-history/<session>/. The page says these paths can be deleted by hand.
    The project folder is not computed: --resume finds a session in any project, so all are searched."""
    if not isinstance(session_id, str) or not _SESSION.fullmatch(session_id):
        return []
    root = root or claude_dir()
    found: list[Path] = []
    projects = root / "projects"
    if projects.is_dir():
        for folder in projects.iterdir():
            if not folder.is_dir() or folder.is_symlink():
                continue
            for pattern in (f"{session_id}.jsonl", f"{session_id}.orphaned-*.jsonl", f"{session_id}.jsonl.superseded-*",
                            session_id):
                found += [p for p in folder.glob(pattern) if not p.is_symlink()]
    history = root / "file-history" / session_id
    if history.is_dir() and not history.is_symlink():
        found.append(history)
    return found


def delete_session(session_id: str, root: Path | None = None) -> int:
    """Delete Claude Code's files for one session; returns how many paths went. Never follows a link."""
    gone = 0
    for path in session_paths(session_id, root):
        try:
            if path.is_dir():
                shutil.rmtree(path)
            else:
                path.unlink()
            gone += 1
        except OSError:
            pass
    return gone


def claude_config_file() -> Path:
    """Claude Code's ~/.claude.json (under CLAUDE_CONFIG_DIR when set: needs testing on the server)."""
    base = os.environ.get("CLAUDE_CONFIG_DIR")
    return (Path(base).expanduser() if base else Path.home()) / ".claude.json"


def trust_folder(folder: Path, config: Path | None = None) -> str:
    """Mark a folder as trusted for Claude Code without its dialog: projects[<path>].hasTrustDialogAccepted
    in ~/.claude.json (code.claude.com/docs/en/permissions, "trust it by hand"). `claude -p` never shows
    the dialog, and an untrusted folder's allow rules are not used. Returns trusted, already or kept
    (the file could not be read or has an unexpected shape: left alone)."""
    path = config or claude_config_file()
    try:
        data = json.loads(path.read_text()) if path.exists() else {}
    except (OSError, ValueError):
        return "kept"
    projects = data.setdefault("projects", {}) if isinstance(data, dict) else None
    entry = projects.setdefault(str(folder), {}) if isinstance(projects, dict) else None
    if not isinstance(entry, dict):
        return "kept"
    if entry.get("hasTrustDialogAccepted") is True:
        return "already"
    entry["hasTrustDialogAccepted"] = True
    _write_config(path, data)
    return "trusted"


def is_trusted(folder: Path, config: Path | None = None) -> bool | None:
    """Whether Claude Code's config marks the folder as trusted; None when the config cannot be read."""
    path = config or claude_config_file()
    try:
        data = json.loads(path.read_text())
    except (OSError, ValueError):
        return None
    projects = data.get("projects") if isinstance(data, dict) else None
    entry = projects.get(str(folder)) if isinstance(projects, dict) else None
    return isinstance(entry, dict) and entry.get("hasTrustDialogAccepted") is True


def untrust_folder(folder: Path, config: Path | None = None) -> None:
    """Drop a folder's entry again (the probe, for its temporary folder)."""
    path = config or claude_config_file()
    try:
        data = json.loads(path.read_text())
    except (OSError, ValueError):
        return
    projects = data.get("projects") if isinstance(data, dict) else None
    if isinstance(projects, dict) and projects.pop(str(folder), None) is not None:
        _write_config(path, data)


def set_user_mcp_server(name: str, server: dict, config: Path | None = None, only_if_present: bool = False) -> str:
    """Claude Code's user-scope MCP server `name`: mcpServers at the top level of ~/.claude.json
    (code.claude.com/docs/en/mcp, "user scope"), written here so the token is on no command line. The entry holds a token, so a file others could read becomes 0600.
    Returns written, same, missing (only_if_present and no such entry) or kept (the file could not be read or
    has an unexpected shape: left alone)."""
    path = config or claude_config_file()
    try:
        data = json.loads(path.read_text()) if path.exists() else {}
        loose = path.exists() and path.stat().st_mode & 0o077 != 0
    except (OSError, ValueError):
        return "kept"
    servers = data.get("mcpServers", {}) if isinstance(data, dict) else None
    if not isinstance(servers, dict):
        return "kept"
    if only_if_present and name not in servers:
        return "missing"
    if servers.get(name) == server and not loose:
        return "same"
    data["mcpServers"] = {**servers, name: server}
    _write_config(path, data, private=True)
    return "written"


def _write_config(path: Path, data: dict, private: bool = False) -> None:
    mode = path.stat().st_mode & 0o777 if path.exists() else 0o600
    if private:
        mode &= 0o700
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=".claude-", dir=path.parent)
    try:
        with os.fdopen(fd, "w") as f:
            json.dump(data, f, indent=2)
        os.chmod(tmp, mode)
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def agent_settings_problem(agent_dir: Path, free_only: bool = True) -> str | None:
    """The agent folder's own settings still apply in -p (env and apiKeyHelper even untrusted). A file that is not JSON
    is always refused (nothing in it can be checked); an API key, apiKeyHelper or billing variables only while free
    only is on: off, they are the user's own choice of login."""
    for name in ("settings.json", "settings.local.json"):
        path = agent_dir / ".claude" / name
        if not path.exists():
            continue
        try:
            data = json.loads(path.read_text())
        except (OSError, ValueError):
            return f"{path} is not valid JSON"
        if not isinstance(data, dict) or not free_only:
            continue
        if "apiKeyHelper" in data:
            return f"{path} sets apiKeyHelper, which bills per use"
        env = data.get("env")
        if isinstance(env, dict) and billing_vars(env):
            return f"{path} sets {', '.join(billing_vars(env))} in env"
    return None
