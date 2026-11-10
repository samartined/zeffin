# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""OpenCode behind the Agent port (bridge/agent.py), over ACP (acp.py). It runs on whatever providers the user set up,
or only on free ones with the "free only" switch on.

- A turn is one `opencode acp` process: initialize, session/new (or session/load to go on with one), the model,
  effort and mode as session config options, then session/prompt; its session/update notifications become the port's
  events. The bridge's MCP server and its token go in session/new and session/load (`mcpServers`, HTTP with an
  Authorization header): through the pipe, never in the environment or on a command line, where the agent's shell
  tool could read them.
- Its permission requests (session/request_permission) are handed to the bridge's broker (TurnRequest.ask), so the
  app's card answers them: once, always, or reject.
- Deleting a conversation runs `opencode session delete <id>`: ACP 1 has no session/delete (the trial).
- Compacting a session sends the prompt "/compact" in a turn of its own: OpenCode 1.19.5 answers it with its summary
  (its acp/service.ts, session.summarize). The bridge's opencode.json sets compaction.tail_turns to 0, so the summary
  replaces every turn and the images in them: a model whose provider caps the images per request (free_models.json,
  max_images) gets a conversation without them (chat/turn.py decides when).

Always, whatever the "free only" switch (config.free_only), each held by a test (tests/test_opencode.py):
- the process's environment is an allow-list: no provider's key, no ANTHROPIC_* or OPENAI_*, no token (a provider is
  signed in with `opencode auth login`, which OpenCode keeps in its own files);
- the configuration the bridge writes in the agent folder (opencode.json) turns sharing and auto-update off and lets
  the bridge's flipper_* tools run; OpenCode's merged configuration (`opencode debug config`) is checked too, so a
  global file cannot undo it;
- a turn whose session runs another model than the conversation chose is killed at once, before its prompt or when
  the agent changes it during the turn;
- the version is pinned to the range the trial checked;
- the configuration's permissions (top level, and each agent's and mode's) may not allow what the bridge's own
  opencode.json asks for (bash, edit, webfetch, external_directory), checked again when each turn starts;
- an edit, delete or move of the agents' own configuration or instructions (guarded.py), or one whose paths the request
  does not state clearly, always goes through the card: neither Accept edits nor an "always" rule answers it.

With free only on, the "never pay" guards too:
- opencode.json allows only OpenCode Zen and NVIDIA NIM, and the merged configuration may not name another provider,
  a model of another one, or a model for one of its agents or modes; both are checked again when each turn starts;
- only free models are offered: Zen's priced at 0 in its catalog, NIM's from a curated list (free_models.json),
  since NIM's catalog prices every model at 0; a session on a model the bridge does not offer is killed at once.

With it off, every provider OpenCode lists models of is used, and its models are offered with whether they are paid.

OpenCode's credentials file is never read: whether NVIDIA is signed in is whether `opencode models` lists its models.
"""
from __future__ import annotations

import asyncio
import contextlib
import json
import logging
import os
import re
import shutil
import signal
import subprocess
import time
from dataclasses import replace
from importlib import resources
from pathlib import Path
from typing import Any, AsyncIterator, Mapping, Protocol

from .. import __version__
from ..agent import (AgentTurn, Denied, HookRoutes, PermissionAnswer, PermissionAsk, Started, TurnEnd, TurnEvent,
                     TurnRequest)
from ..text import one_line
from ..wire import (EFFORTS, AgentId, Answer, Guard, Mode, ModelReason, Outcome, Problem, TurnError,
                    valid_rule)
from . import acp, guarded
from .stream import FLIPPER_PREFIX, permission_input

log = logging.getLogger("zeffin.chat")

NAME = "OpenCode"
# The versions the bridge runs: from the one the trial checked (1.19.5) up to the next minor, which may change what
# the trial saw. Its schema marks share, autoupdate, enabled_providers and permission deprecated (in favour of
# update, policies and permissions), though 1.19.5 honors them: a newer range follows the migration, after a probe.
MIN_VERSION = (1, 19, 5)
BELOW_VERSION = (1, 20, 0)
CONFIG_FILE = "opencode.json"
MCP_NAME = "flipper"  # OpenCode names an MCP server's tools <server>_<tool>: flipper_flipper_press
# The bridge's MCP tools (bridge/tools.py; tests/fixtures/mcp_tools.json, which a test compares with this list). A tool
# call is the bridge's only when its title is exactly one of these with OpenCode's prefix: an ACP title can be any
# text, such as a shell command that starts with the same words (security review before 1.0.0, M1).
MCP_TOOLS = frozenset({
    "flipper_app", "flipper_app_button", "flipper_app_exit", "flipper_app_load_file", "flipper_app_start",
    "flipper_delete", "flipper_device_info", "flipper_events", "flipper_firmware", "flipper_list", "flipper_mkdir",
    "flipper_note", "flipper_power_info", "flipper_press", "flipper_read", "flipper_rename", "flipper_rpc", "flipper_screen",
    "flipper_stat", "flipper_status", "flipper_wait_screen", "flipper_watch", "flipper_write"})
LINE_LIMIT = acp.LINE_LIMIT
STDERR_TAIL = 4096
STDERR_LOGGED = 300
START_S = 60.0     # initialize, and opening or loading the session
COMMAND_S = 30.0   # each `opencode` command the bridge runs (version, models, config, delete)
LABEL_CHARS = 40
DOOM_LOOP = "doom_loop"  # OpenCode's permission for a tool repeated 3 times with the same input
COMPACT_PROMPT = "/compact"  # OpenCode's own command over ACP: its summary of the session
MAX_MODELS = 100   # the app keeps at most this many models per agent

FREE = json.loads(resources.files(__package__).joinpath("free_models.json").read_text())
FREE_PROVIDERS: tuple[str, ...] = tuple(FREE["providers"])

# The kinds Accept edits answers, and that may never touch a guarded path without the card. OpenCode 1.19.5 asks every
# edit, write and apply_patch as its "edit" permission, kind edit (its acp/permission.ts); delete and move stay for
# another agent or version.
EDIT_KINDS = ("edit", "delete", "move")
# What the bridge's opencode.json asks before (CONFIG's "ask"): a configuration may not allow any of them.
ASKED = ("bash", "edit", "webfetch", "external_directory")
ACTIONS = ("ask", "allow", "deny")
# Its modes, as data: Manual and Accept edits are OpenCode's build agent (Accept edits answers edits itself), Plan is
# its plan agent. Auto is not offered: OpenCode has no classifier that denies on its own.
MODES = {
    Mode.DEFAULT: {"label": "Manual", "agent": "build", "allow": ()},
    Mode.ACCEPT_EDITS: {"label": "Accept edits", "agent": "build", "allow": EDIT_KINDS},
    Mode.PLAN: {"label": "Plan", "agent": "plan", "allow": ()},
}
# ACP's tool kinds, named as the app shows tools (agentclientprotocol.com, "Tool Calls"); "other" keeps the title.
KIND_NAMES = {"execute": "Bash", "edit": "Edit", "read": "Read", "search": "Search", "fetch": "WebFetch",
              "delete": "Delete", "move": "Move", "think": "Think"}

# The configuration the bridge writes in the agent folder (the keys the trial checked with 1.19.5), with free only on;
# OPEN_CONFIG, with it off, is the same without the providers' restriction.
CONFIG = {
    "$schema": "https://opencode.ai/config.json",
    "share": "disabled",
    "autoupdate": False,
    "enabled_providers": list(FREE_PROVIDERS),
    "permission": {"flipper_*": "allow", "bash": "ask", "edit": "ask", "webfetch": "ask", "external_directory": "ask"},
    # A summary keeps no recent turn as it is, so none of their images either (its schema's compaction.tail_turns
    # in 1.19.5): the probe on a real server saw images kept by the default fail again 3 images later.
    "compaction": {"tail_turns": 0},
}
OPEN_CONFIG = {k: v for k, v in CONFIG.items() if k != "enabled_providers"}
# Never in the agent's environment: only these names, and OpenCode's own switches set here.
ENV_ALLOW = ("PATH", "HOME", "USER", "LOGNAME", "SHELL", "LANG", "LANGUAGE", "LC_ALL", "LC_CTYPE", "TERM", "TZ",
             "TMPDIR", "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_CACHE_HOME", "XDG_STATE_HOME", "XDG_RUNTIME_DIR",
             "HTTPS_PROXY", "HTTP_PROXY", "NO_PROXY", "https_proxy", "http_proxy", "no_proxy",
             "SSL_CERT_FILE", "SSL_CERT_DIR", "NODE_EXTRA_CA_CERTS")
ENV_SET = {"OPENCODE_DISABLE_AUTOUPDATE": "1",  # belt and braces with "autoupdate": false
           "OPENCODE_DISABLE_CLAUDE_CODE": "1"}  # read AGENTS.md, not the folder's CLAUDE.md or ~/.claude
_VERSION = re.compile(r"(\d+)\.(\d+)\.(\d+)")
_SESSION = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,127}")


class Settings(Protocol):
    """What it reads from the server's Config, live."""
    opencode_bin: str
    agent_dir: str
    mcp_token: str
    free_only: bool

    def mcp_url(self) -> str: ...


# ------------------------------------------------------------------ the guards
def agent_env(environ: Mapping[str, str]) -> dict[str, str]:
    """The environment of every `opencode` process: an allow-list, never a provider's key or a token."""
    return {**{k: v for k, v in environ.items() if k in ENV_ALLOW}, **ENV_SET}


def write_config(folder: Path, free_only: bool = True) -> Path:
    path = folder / CONFIG_FILE
    path.write_text(json.dumps(CONFIG if free_only else OPEN_CONFIG, indent=2) + "\n")
    return path


def _covers(key: str) -> bool:
    """Whether a permission key names one of what the bridge asks before, itself or through OpenCode's wildcards (*
    any text, ? one character): with the last matching rule winning, "*": "allow" after "bash": "ask" allows bash."""
    pattern = re.compile("".join(".*" if c == "*" else "." if c == "?" else re.escape(c) for c in key))
    return any(pattern.fullmatch(name) for name in ASKED)


def _permission_problem(permission: object, where: str, top: bool) -> str | None:
    """Why a permission block allows more than the bridge's own (CONFIG's): an "allow" for bash, edit, webfetch or
    external_directory, itself or through a wildcard, or, at the top level, one of them not asked. The user's other
    rules (their own MCP servers' tools, reads) are theirs."""
    if permission is None:
        return f"{where} has no permission rules (run zf agent setup to write them)" if top else None
    if isinstance(permission, str):
        permission = {"*": permission}  # OpenCode reads a bare action as every permission's
    if not isinstance(permission, dict):
        return f"the permission of {where} is not an object"
    for key, rule in permission.items():
        actions = [rule] if isinstance(rule, str) else list(rule.values()) if isinstance(rule, dict) else None
        if actions is None or any(a not in ACTIONS for a in actions):
            return f"the permission of {where} for {key} is not ask, allow or deny"
        if "allow" in actions and _covers(key):
            return (f"the permission of {where} allows {key} without asking: remove that rule, the app's card asks "
                    "for it")
    missing = [k for k in ASKED if k not in permission] if top else []
    if missing:
        return f"the permission of {where} does not ask before {', '.join(missing)} (run zf agent setup to write it)"
    return None


def _tools_problem(tools: object, where: str) -> str | None:
    """The older "tools" switch: true turns a tool's permission into allow."""
    if tools is None:
        return None
    if not isinstance(tools, dict):
        return f"the tools of {where} is not an object"
    on = [k for k, v in tools.items() if v is not False and _covers(str(k))]
    return f"the tools of {where} turn on {', '.join(on)} without asking: remove them" if on else None


def guard_problem(config: dict) -> str | None:
    """What stands whatever the free only switch: sharing and auto-update off, and no permission that lets the agent
    run a command, edit a file or fetch a page without the owner's card (the flow the bridge owns)."""
    if config.get("share") != "disabled":
        return "sharing is not disabled"
    if config.get("autoupdate") is not False:
        return "auto-update is not off"
    problem = _permission_problem(config.get("permission"), "the configuration", True) or \
        _tools_problem(config.get("tools"), "the configuration")
    if problem:
        return problem
    for key in ("agent", "mode"):
        entries = config.get(key)
        if entries is None:
            continue
        if not isinstance(entries, dict):
            return f"{key} is not an object"
        for name, entry in entries.items():
            if not isinstance(entry, dict):
                return f"the {key} {name} is not an object"
            where = f"the {key} {name}"
            problem = _permission_problem(entry.get("permission"), where, False) or \
                _tools_problem(entry.get("tools"), where)
            if problem:
                return problem
    return None


def config_problem(config: object, free_only: bool = True) -> str | None:
    """Why a configuration (the folder's, or OpenCode's merged one) could make OpenCode leak, act without the owner's
    card, or, with free only on, pay; None when it could not."""
    if not isinstance(config, dict):
        return "not a JSON object"
    problem = guard_problem(config)
    if problem or not free_only:
        return problem
    providers = config.get("enabled_providers")
    if not isinstance(providers, list) or not providers or any(p not in FREE_PROVIDERS for p in providers):
        return "enabled_providers is not a list of the free providers"
    if "provider" in config:
        return "a provider is configured (a key or another endpoint)"
    for key in ("model", "small_model"):
        value = config.get(key)
        if value is not None and (not isinstance(value, str) or value.split("/", 1)[0] not in FREE_PROVIDERS):
            return f"{key} is not one of the free providers'"
    # An agent or a mode with a model of its own (a subagent's) would run it whatever the conversation chose.
    for key in ("agent", "mode"):
        entries = config.get(key)
        if entries is None:
            continue
        if not isinstance(entries, dict) or any(not isinstance(e, dict) or "model" in e for e in entries.values()):
            return f"an entry of {key} sets a model of its own"
    return None


def parse_version(text: str) -> tuple[int, int, int] | None:
    found = _VERSION.search(text or "")
    return (int(found[1]), int(found[2]), int(found[3])) if found else None


def version_ok(version: tuple[int, int, int] | None) -> bool:
    return version is not None and MIN_VERSION <= version < BELOW_VERSION


# ------------------------------------------------------------------ the catalog of free models
def parse_models(text: str) -> list[dict]:
    """`opencode models --verbose`: "provider/model" then the model as JSON, for each model."""
    rows: list[dict] = []
    decoder = json.JSONDecoder()
    position = 0
    while position < len(text):
        newline = text.find("\n", position)
        line = text[position:newline if newline >= 0 else len(text)].strip()
        position = newline + 1 if newline >= 0 else len(text)
        if not line or "/" not in line or line.startswith("{"):
            continue
        rest = text[position:].lstrip()
        try:
            model, used = decoder.raw_decode(rest)
        except ValueError:
            model, used = None, 0
        rows.append({"key": line, "model": model if isinstance(model, dict) else {}})
        position += len(text[position:]) - len(rest) + used
    return rows


def _free(model: dict) -> bool:
    cost = model.get("cost")
    if not isinstance(cost, dict) or not cost:
        return False
    values = [cost.get("input"), cost.get("output")]
    cache = cost.get("cache")
    if isinstance(cache, dict):
        values += [cache.get("read"), cache.get("write")]
    return all(v is not None for v in values[:2]) and all(v == 0 for v in values if v is not None)


def _images(model: dict) -> bool:
    caps = model.get("capabilities")
    inputs = caps.get("input") if isinstance(caps, dict) else None
    return isinstance(inputs, dict) and inputs.get("image") is True


def _efforts(model: dict) -> list[str]:
    variants = model.get("variants")
    return [e for e in EFFORTS if isinstance(variants, dict) and e in variants]


def _label(model: dict, key: str) -> str:
    name = model.get("name")
    return (name.strip() if isinstance(name, str) and name.strip() else key)[:LABEL_CHARS]


# Readable names for the providers' ids: OpenCode 1.19.5's `models --verbose` gives each model's providerID but no
# provider name (the trial's shapes), so the common ones are named here; any other shows its id.
PROVIDER_NAMES = {"opencode": "OpenCode Zen", "nvidia": "NVIDIA NIM", "openai": "OpenAI", "anthropic": "Anthropic",
                  "google": "Google", "openrouter": "OpenRouter"}
ZEN = "opencode"  # OpenCode Zen: its free models need no account, so it is listed whether or not anyone signed in


def provider_name(provider: str) -> str:
    return PROVIDER_NAMES.get(provider, provider)


def providers(rows: list[dict], free_only: bool = True) -> list[str]:
    """The providers OpenCode lists models of, by id: those signed in (every one but Zen), in OpenCode's order, then
    Zen, open without an account. With free only on, only the free providers."""
    seen = list(dict.fromkeys(row["key"].split("/", 1)[0] for row in rows))
    found = [p for p in seen if p != ZEN] + [p for p in seen if p == ZEN]
    return [p for p in found if p in FREE_PROVIDERS] if free_only else found


def _entry(key: str, model: dict, paid: bool) -> dict:
    return {"value": key, "label": _label(model, key), "efforts": _efforts(model), "auto": False,
            "images": _images(model), "available": True, "reason": None, "paid": paid}


def _zen_free(listed: dict[str, dict]) -> list[tuple[str, dict]]:
    """Zen's models priced at 0, but those excluded, its fallback first."""
    zen = [(key, model) for key, model in listed.items() if key.startswith(ZEN + "/")
           and key not in FREE["zen_excluded"] and _free(model)]
    zen.sort(key=lambda pair: pair[0] != FREE["fallback"])
    return zen


def catalog(rows: list[dict], free_only: bool = True) -> list[dict]:
    """The models offered, in their order; the first available one is a new conversation's default.

    With free only on: NIM's curated ones first (not signed in: listed, not available), then Zen's
    priced at 0 but those excluded. Nothing else is listed, so nothing else can be chosen.

    Off, no provider is favoured: the models of the providers the user signed in come first, each provider in
    OpenCode's order and its models in OpenCode's order (NIM's curated ones first among NIM's), each `paid` unless its
    price is 0; then Zen's free models, which need no account. A provider nobody signed in has no rows. Zen's priced
    models are left out: whether Zen is signed in cannot be told without reading OpenCode's credentials."""
    listed = {row["key"]: row["model"] for row in rows}
    if not free_only:
        return _open_catalog(listed)
    models: list[dict] = []
    for item in FREE["nvidia"]:
        model = listed.get(item["value"])
        signed = model is not None
        models.append({"value": item["value"], "label": _label(model or {}, item["label"]) if signed else item["label"],
                       "efforts": _efforts(model or {}), "auto": False, "images": item["images"],
                       "available": signed, "reason": None if signed else ModelReason.NOT_SIGNED_IN, "paid": False})
    models += [_entry(key, model, False) for key, model in _zen_free(listed)]
    return models[:MAX_MODELS]


def _open_catalog(listed: dict[str, dict]) -> list[dict]:
    curated = [item["value"] for item in FREE["nvidia"]]
    models: list[dict] = []
    for provider in providers([{"key": k} for k in listed], free_only=False):
        if provider == ZEN:
            continue
        keys = [k for k in listed if k.split("/", 1)[0] == provider and k not in FREE["zen_excluded"]]
        keys.sort(key=lambda k: k not in curated)  # stable: NIM's curated first, the rest in OpenCode's order
        models += [_entry(k, listed[k], k not in curated and not _free(listed[k])) for k in keys]
    models += [_entry(key, model, False) for key, model in _zen_free(listed)]
    return models[:MAX_MODELS]


# ------------------------------------------------------------------ tools and permissions
def tool_name(call: dict) -> tuple[str, bool]:
    """A tool call's name on the wire, and whether it is the bridge's own: OpenCode names an MCP tool
    <server>_<tool>, which becomes mcp__flipper__<tool> as Claude Code's are, so the app shows it as one. Only a title
    that is exactly one of the bridge's tools counts: a permission request of kind other can carry a shell command as
    its title (external_directory, with OpenCode 1.19.5)."""
    title = call.get("title") if isinstance(call.get("title"), str) else ""
    kind = call.get("kind") if isinstance(call.get("kind"), str) else "other"
    prefix = MCP_NAME + "_"
    if kind == "other" and title.startswith(prefix) and title[len(prefix):] in MCP_TOOLS:
        return FLIPPER_PREFIX + title[len(prefix):], True
    if kind in KIND_NAMES:
        return KIND_NAMES[kind], False
    return one_line(title, 120) or "?", False


def flipper_doom_loop(call: dict) -> bool:
    """OpenCode's doom_loop request (one tool run 3 times in a row with the same input: its session/processor.ts) for a
    tool of the bridge's own. The Flipper's tools run without asking, and looking at the screen again is how the agent
    waits, so the bridge allows it once itself; the owner's Pause still stops the tool (the maintainer's decision). Only
    exactly the shape OpenCode 1.19.5 sends (its acp/permission.ts): title doom_loop, kind other, and rawInput holding
    just the repeated tool's name, which must be one of the bridge's tools as tool_name reads it, and its input object.
    Anything else goes to the card."""
    raw = call.get("rawInput")
    if call.get("title") != DOOM_LOOP or call.get("kind") != "other" or not isinstance(raw, dict):
        return False
    if set(raw) != {"tool", "input"} or not isinstance(raw["tool"], str) or not isinstance(raw["input"], dict):
        return False
    return tool_name({"title": raw["tool"], "kind": "other"})[1]


def _clear(path: object) -> bool:
    return isinstance(path, str) and bool(path) and path.strip() == path and "\x00" not in path


JOINED = ", "  # how apply_patch joins its relative paths into one filepath (and, without files, one location)


def request_paths(call: dict) -> list[str] | None:
    """Every path a request names, as OpenCode 1.19.5 sends an edit's (acp/permission.ts): the locations, and its
    rawInput, which is the permission's metadata: filepath (with apply_patch, the relative paths joined by ", ",
    so its files' filePath and movePath instead), or filePath or path. A path with ", " in it may be several joined
    (the real probe saw one location "a.py, b.py"): each part counts too. None when a path is not stated clearly."""
    raw = call.get("rawInput") if isinstance(call.get("rawInput"), dict) else {}
    found: list[str] = []
    for loc in call.get("locations") or []:
        path = loc.get("path") if isinstance(loc, dict) else None
        if not _clear(path):
            return None
        found.append(path)
    files = raw.get("files")
    if files is not None:
        if not isinstance(files, list):
            return None
        for item in files:
            if not isinstance(item, dict) or not _clear(item.get("filePath")):
                return None
            found.append(item["filePath"])
            if "movePath" in item:
                if not _clear(item["movePath"]):
                    return None
                found.append(item["movePath"])
    else:
        for key in ("filepath", "filePath", "path"):
            if key in raw:
                if not _clear(raw[key]):
                    return None
                found.append(raw[key])
    found += [part for path in found if JOINED in path for part in path.split(JOINED) if part]
    return found or None


def guards(call: dict, folder: str) -> bool:
    """Whether a request must go through the card whatever the mode and the rules: an edit, delete or move of a
    guarded path (guarded.py), or one whose paths are not stated clearly."""
    if call.get("kind") not in EDIT_KINDS:
        return False
    paths = request_paths(call)
    return paths is None or any(guarded.is_guarded(p, folder) for p in paths)


def rule_for(call: dict) -> str | None:
    """What "always" grants for a request, as a rule the bridge keeps with the conversation and answers next time:
    the tool and its command, path or title. The path is every path the request names (request_paths: locations,
    rawInput's filepath and its files' filePath and movePath), so a rule covers exactly one file: None when the request
    names more than one path, a move (a movePath), or a path not stated clearly; None too when it would not be a valid
    rule, and for a command that names a guarded file."""
    name, _ = tool_name(call)
    raw = call.get("rawInput") if isinstance(call.get("rawInput"), dict) else {}
    locations = call.get("locations") if isinstance(call.get("locations"), list) else []
    command = raw.get("command") if isinstance(raw.get("command"), str) else None
    files = raw.get("files")
    if command is not None:
        if guarded.is_named(command):
            return None
        content: object = command
    elif locations or files is not None or any(k in raw for k in ("filepath", "filePath", "path")):
        paths = request_paths(call)
        distinct = list(dict.fromkeys(paths or []))
        moves = isinstance(files, list) and any(isinstance(f, dict) and "movePath" in f for f in files)
        if paths is None or len(distinct) != 1 or moves:
            return None
        content = distinct[0]
    elif call.get("kind") in EDIT_KINDS:
        return None  # an edit that states no path: its title ("2 files") names no file a rule could cover
    else:
        content = call.get("title")
    rule = f"{name}({content})" if isinstance(content, str) and content else None
    return rule if rule is not None and valid_rule(rule) else None


def write_agents_md(folder: Path, body: str) -> bool:
    """The agent's instructions, AGENTS.md, as CLAUDE.md has them (CLAUDE.md.example). An existing one is kept."""
    path = folder / "AGENTS.md"
    if path.exists():
        return False
    path.write_text(body.rstrip() + "\n")
    return True


def find_opencode(home: Path | None = None) -> str | None:
    """On the PATH, or where OpenCode's install script puts it (~/.opencode/bin)."""
    found = shutil.which("opencode")
    if found:
        return str(Path(found).absolute())
    local = (home or Path.home()) / ".opencode" / "bin" / "opencode"
    return str(local) if os.access(local, os.X_OK) else None


def _run(argv: list[str], cwd: str | None) -> subprocess.CompletedProcess | None:
    try:
        return subprocess.run(argv, cwd=cwd or None, env=agent_env(os.environ), capture_output=True, text=True,
                              timeout=COMMAND_S, stdin=subprocess.DEVNULL)
    except (OSError, subprocess.TimeoutExpired):
        return None


# ------------------------------------------------------------------ the agent
class OpenCode:
    id = AgentId.OPENCODE
    remote = None  # no interactive session: no Remote Control

    def __init__(self, config: Settings) -> None:
        self.config = config
        self.health: dict[str, Any] = {"version": None, "providers": [], "config": None, "checked_at": None}
        self.catalog: list[dict] = catalog([], self.free_only)

    @property
    def configured(self) -> bool:
        return bool(self.config.opencode_bin)

    @property
    def free_only(self) -> bool:
        return self.config.free_only is True

    async def settings(self) -> tuple[tuple[int, int, int] | None, str | None, bool]:
        """Its version, why its merged configuration is refused (None when it is not), and whether that stands with
        free only off too (a leak, or a permission that skips the card), from OpenCode itself."""
        binary, folder = self.config.opencode_bin, self.config.agent_dir
        version = await asyncio.to_thread(_run, [binary, "--version"], folder)
        merged = await asyncio.to_thread(_run, [binary, "debug", "config"], folder)
        parsed = parse_version(version.stdout) if version is not None and version.returncode == 0 else None
        if merged is None or merged.returncode != 0:
            return parsed, "OpenCode did not show its configuration", True
        try:
            config = json.loads(merged.stdout)
        except ValueError:
            return parsed, "OpenCode's configuration is not JSON", True
        always = config_problem(config, free_only=False)
        problem = always or (config_problem(config) if self.free_only else None)
        return parsed, problem, always is not None

    async def check(self) -> None:
        binary, folder = self.config.opencode_bin, self.config.agent_dir
        version, problem, _ = await self.settings()
        models = await asyncio.to_thread(_run, [binary, "models", "--verbose"], folder)
        rows = parse_models(models.stdout) if models is not None and models.returncode == 0 else []
        self.catalog = catalog(rows, self.free_only)
        self.health = {"version": version, "providers": providers(rows, self.free_only), "config": problem,
                       "checked_at": time.time()}

    def models(self) -> list[dict]:
        return self.catalog

    def offered(self) -> set[str]:
        return {m["value"] for m in self.catalog if m["available"]}

    def identity(self) -> dict:
        version = self.health["version"]
        return {"name": NAME, "version": ".".join(map(str, version)) if version else None}

    def mode_options(self) -> list[dict]:
        return [{"value": str(mode), "label": spec["label"]} for mode, spec in MODES.items()]

    def login(self) -> dict:
        """The providers OpenCode can use, by name, those signed in first; never the owner's subscription (it decides
        nothing: the guards do)."""
        names = [provider_name(p) for p in self.health["providers"]]
        return {"method": ", ".join(names) if names else None, "subscription": False}

    def problem(self) -> str | None:
        c = self.config
        if not c.agent_dir or not c.opencode_bin or not Path(c.agent_dir).is_dir():
            return Problem.NOT_CONFIGURED
        if not os.access(c.opencode_bin, os.X_OK):
            return Problem.AGENT_MISSING
        try:
            local = json.loads((Path(c.agent_dir) / CONFIG_FILE).read_text())
        except (OSError, ValueError):
            local = None
        if config_problem(local, self.free_only):
            return Problem.UNSAFE_AGENT_SETTINGS
        if self.health["checked_at"] is None:
            return Problem.CHECKING
        if self.health["version"] is None:
            return Problem.AGENT_MISSING
        if not version_ok(self.health["version"]):
            return Problem.AGENT_VERSION
        if self.health["config"]:
            return Problem.UNSAFE_AGENT_SETTINGS
        if not self.offered():
            return Problem.NOT_LOGGED_IN
        return None

    def remote_problem(self) -> str | None:
        return None

    def summary_fields(self) -> dict:
        return {}

    def capabilities(self) -> dict:
        # Images go as image blocks to a model that takes them; other files are named by path (turn.py), and OpenCode
        # may ask before it reads outside the agent folder (external_directory: ask).
        return {"remote": None, "attachments": True}

    def hook_routes(self) -> HookRoutes | None:
        return None

    def forget(self, session: str) -> int:
        """`opencode session delete <id>`: 1 when it went."""
        if not self.configured or not _SESSION.fullmatch(session):
            return 0
        done = _run([self.config.opencode_bin, "session", "delete", session], self.config.agent_dir)
        return 1 if done is not None and done.returncode == 0 else 0

    async def start_turn(self, request: TurnRequest) -> OpenCodeTurn:
        turn = OpenCodeTurn(self, request)
        await turn.start()
        return turn

    def image_limit(self, model: str) -> int | None:
        """The images the model takes in one request, where its provider caps them (free_models.json)."""
        limit = FREE.get("max_images", {}).get(model)
        return limit if isinstance(limit, int) and not isinstance(limit, bool) and limit > 0 else None

    async def compact(self, request: TurnRequest) -> AgentTurn | None:
        """A turn of its own that sends "/compact" in the request's session: OpenCode replaces the session's turns
        with its summary. Its events (the summary's text) are not the conversation's; the caller drains them."""
        return await self.start_turn(replace(request, text=COMPACT_PROMPT, images=[], folders=[]))

    # Its permission requests come inside ACP, never through hooks.
    def read_permission(self, body: dict) -> PermissionAsk:
        raise NotImplementedError

    def permission_reply(self, answer: str, rules: list[str]) -> dict:
        raise NotImplementedError

    def read_denied(self, body: dict) -> Denied:
        raise NotImplementedError


def _not_found(error: acp.AcpError) -> bool:
    """session/load's answer for a session the agent no longer has: OpenCode 1.19.5 sends -32603 "Internal error:
    OpenCode service failure" with data.service "session" for a deleted or unknown one (the probe); any agent's
    message that says "not found" reads the same."""
    if error.code == acp.INTERNAL_ERROR and isinstance(error.data, dict) and error.data.get("service") == "session":
        return True
    return "not found" in error.text(500).lower()


class OpenCodeTurn:
    """One `opencode acp` process and what it said."""

    def __init__(self, agent: OpenCode, request: TurnRequest) -> None:
        self.agent = agent
        self.request = request
        self.proc: asyncio.subprocess.Process | None = None
        self.conn: acp.Connection | None = None
        self.updates = acp.Updates(tool_name)
        self.queue: asyncio.Queue[list[TurnEvent]] = asyncio.Queue()
        self.session: str | None = None
        self.listening = False   # updates before the prompt (a loaded session's history) are not shown
        self.prompting = False
        self.result: dict | None = None
        self.error: acp.AcpError | None = None
        self.ended: TurnEnd | None = None
        self.stderr = bytearray()
        self.tasks: list[asyncio.Task] = []
        self.blocks: list[dict] = []
        self.granted: list[str] = []  # the rules "always" granted during this turn: the bridge answers them itself

    async def start(self) -> None:
        """Start the process; a turn that cannot start keeps the reason and ends at once. OpenCode's version and merged
        configuration are checked again first, whatever the switch: a global file changed since the last check cannot
        let this turn act without the card, nor, with free only on, pay (security review before 1.0.0, L1, and
        security scan before 1.0.0)."""
        r = self.request
        if not await self._still_safe():
            return
        try:
            images = await asyncio.to_thread(lambda: [(t, p.read_bytes()) for t, p in r.images])
        except OSError as e:
            self.stderr += f"attachment unreadable: {type(e).__name__}".encode()
            return
        self.blocks = [acp.text_block(r.text)] + [acp.image_block(t, data) for t, data in images]
        try:
            self.proc = await asyncio.create_subprocess_exec(
                self.agent.config.opencode_bin, "acp", cwd=self.agent.config.agent_dir, env=agent_env(os.environ),
                stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
                start_new_session=True, limit=LINE_LIMIT)
        except OSError as e:
            self.stderr += str(e).encode()

    async def _still_safe(self) -> bool:
        version, problem, always = await self.agent.settings()
        if version_ok(version) and not problem:
            return True
        self.agent.health.update(version=version, config=problem)  # the agent's problem says it until the next check
        if problem and not always:
            log.warning("opencode: its configuration %s: turn refused", problem)
            self.ended = TurnEnd(Outcome.ERROR, TurnError.BILLING_GUARD, {"why": Guard.MODEL})
        elif problem:
            # the app words the problem as it does for not_ready, with the log's reason after it
            self.ended = TurnEnd(Outcome.ERROR, TurnError.AGENT_FAILED,
                                 {"exit": None, "subtype": None, "problem": Problem.UNSAFE_AGENT_SETTINGS,
                                  "reason": f"its configuration {problem}"},
                                 f"opencode refused: its configuration {problem}")
        else:
            self.ended = TurnEnd(Outcome.ERROR, TurnError.AGENT_FAILED,
                                 {"exit": None, "subtype": None, "problem": Problem.AGENT_VERSION,
                                  "reason": "not a version this server runs"},
                                 "opencode failed: not a version this server runs")
        return False

    # ---- the wire
    def _on_update(self, params: dict) -> None:
        update = params.get("update")
        if self.listening and params.get("sessionId") == self.session and isinstance(update, dict):
            if self._model_changed(update):
                return
            events = self.updates.feed(update)
            if events:
                self.queue.put_nowait(events)

    def _model_changed(self, update: dict) -> bool:
        """The agent moved the session to another model during the turn (a config_option_update): the turn is killed
        at once, whatever the switch, as a session that starts on another model is (security review, L1)."""
        if update.get("sessionUpdate") not in ("config_option_update", "config_options_update"):
            return False
        model = (acp.option(update.get("configOptions"), "model") or {}).get("currentValue")
        if model is None or model == self.request.model or self.ended is not None:
            return False
        log.warning("opencode: the session moved to another model during the turn: turn killed")
        self.ended = TurnEnd(Outcome.ERROR, TurnError.BILLING_GUARD, {"why": Guard.MODEL})
        self.kill()
        return True

    async def _on_request(self, method: str, params: dict) -> dict:
        if method == "session/request_permission":
            return await self._permission(params)
        raise acp.AcpError(acp.METHOD_NOT_FOUND, "Method not found")

    async def _permission(self, params: dict) -> dict:
        call = params.get("toolCall") if isinstance(params.get("toolCall"), dict) else {}
        options = [o for o in params.get("options") or [] if isinstance(o, dict) and isinstance(o.get("optionId"), str)]

        def pick(kind: str) -> dict:
            option = next((o for o in options if o.get("kind") == kind), None)
            return {"outcome": {"outcome": "selected", "optionId": option["optionId"]}} if option else \
                {"outcome": {"outcome": "cancelled"}}

        r = self.request
        name, flipper = tool_name(call)
        # An edit of the agents' own configuration or instructions, or of a path the request does not state clearly,
        # always goes to the card, without "always" (security scan before 1.0.0).
        guard = guards(call, self.agent.config.agent_dir)
        rule = None if guard else rule_for(call)
        if params.get("sessionId") != self.session:
            return pick("reject_once")
        if flipper_doom_loop(call):
            return pick("allow_once")  # no card and no rule: a Flipper tool repeated (the maintainer's decision)
        # The bridge's own tools are allowed by opencode.json and never reach here; one that does goes through the
        # broker, which lets a Flipper tool run (ask.flipper). The mode's kinds, and the rules "always" granted before
        # or during this turn, are answered here, once each.
        if not guard and (call.get("kind") in MODES.get(r.mode, MODES[Mode.DEFAULT])["allow"] or
                          (rule and (rule in r.rules or rule in self.granted))):
            return pick("allow_once")
        shown, whole = permission_input(call.get("rawInput") or {})
        ask = PermissionAsk(session=params.get("sessionId"), tool=name, input=shown,
                            rules=[rule] if rule and whole else [], flipper=flipper, whole=whole)
        answer = await r.ask(ask) if r.ask is not None else PermissionAnswer(Answer.DENY)
        if answer.answer == Answer.ONCE:
            return pick("allow_once")
        if answer.answer == Answer.ALWAYS:
            # Never OpenCode's allow_always: its own "always" patterns are broader than the rule on the card (a
            # command's prefix and *), which the bridge keeps instead and matches itself (security review, M2).
            self.granted += [g for g in answer.rules if g not in self.granted]
            return pick("allow_once")
        if answer.answer in (Answer.INTERRUPTED, Answer.STOPPING):
            return {"outcome": {"outcome": "cancelled"}}
        return pick("reject_once")  # deny, or no answer in time

    # ---- the turn
    async def _set(self, options: Any, category: str, value: str | None) -> Any:
        item = acp.option(options, category)
        if value is None or item is None or item.get("currentValue") == value or value not in acp.option_values(item):
            return options
        result = await self.conn.request("session/set_config_option",  # type: ignore[union-attr]
                                         {"sessionId": self.session, "configId": item.get("id"), "value": value},
                                         timeout=START_S)
        return result.get("configOptions") or options

    async def _open(self) -> Started | None:
        r, config, conn = self.request, self.agent.config, self.conn
        assert conn is not None
        init = await conn.request("initialize", {
            "protocolVersion": acp.PROTOCOL_VERSION,
            "clientCapabilities": {"fs": {"readTextFile": False, "writeTextFile": False}, "terminal": False},
            # ACP's schema requires clientInfo's name and version; OpenCode 1.19.5 refuses initialize without them.
            "clientInfo": {"name": "zeffin", "version": __version__}}, timeout=START_S)
        info = init.get("agentInfo") if isinstance(init.get("agentInfo"), dict) else {}
        servers = [acp.mcp_http(MCP_NAME, config.mcp_url(), {"Authorization": f"Bearer {config.mcp_token}"})]
        if r.resume:
            self.session = r.session
            try:
                opened = await conn.request("session/load", {"sessionId": r.session, "cwd": config.agent_dir,
                                                             "mcpServers": servers}, timeout=START_S)
            except acp.AcpError as e:
                if _not_found(e):
                    self.ended = TurnEnd(Outcome.ERROR, TurnError.EXPIRED)
                    return None
                raise
        else:
            opened = await conn.request("session/new", {"cwd": config.agent_dir, "mcpServers": servers},
                                        timeout=START_S)
            session = opened.get("sessionId")
            if not isinstance(session, str) or not _SESSION.fullmatch(session):
                raise acp.AcpError(None, "session/new gave no session id")
            self.session = session
        options = opened.get("configOptions")
        options = await self._set(options, "model", r.model)
        with contextlib.suppress(acp.AcpError):
            options = await self._set(options, "mode", MODES.get(r.mode, MODES[Mode.DEFAULT])["agent"])
        with contextlib.suppress(acp.AcpError):
            options = await self._set(options, "thought_level", r.effort)
        model = (acp.option(options, "model") or {}).get("currentValue")
        effort = (acp.option(options, "thought_level") or {}).get("currentValue")
        # The guard: a session on another model than the conversation chose is killed before its prompt is sent; with
        # free only on, also one on a model the bridge does not offer (a free one).
        offered = model in self.agent.offered() if self.agent.free_only else True
        guard = None if model == r.model and offered else Guard.MODEL
        version = info.get("version") if isinstance(info.get("version"), str) else None
        return Started(session=self.session, model=str(model or ""), mode=r.mode,
                       effort=effort if effort in EFFORTS else None, version=version, mcp=None, mcp_ok=True,
                       guard=guard)

    async def lines(self) -> AsyncIterator[list[TurnEvent]]:
        proc = self.proc
        if proc is None or proc.stdin is None or proc.stdout is None or proc.stderr is None:
            return
        stderr = proc.stderr

        async def read_stderr() -> None:
            while chunk := await stderr.read(4096):
                self.stderr.extend(chunk)
                del self.stderr[:-STDERR_TAIL]

        self.conn = conn = acp.Connection(proc.stdout, proc.stdin, on_update=self._on_update,
                                          on_request=self._on_request)
        self.tasks = [asyncio.create_task(conn.run()), asyncio.create_task(read_stderr())]
        try:
            started = await self._open()
            if started is None:
                return
            yield [started]
            if started.guard:
                return
            self.listening = self.prompting = True
            prompt = asyncio.create_task(conn.request("session/prompt", {"sessionId": self.session,
                                                                         "prompt": self.blocks}))
            while not prompt.done():
                get = asyncio.create_task(self.queue.get())
                await asyncio.wait({get, prompt}, return_when=asyncio.FIRST_COMPLETED)
                if get.done():
                    yield get.result()
                else:
                    get.cancel()
            while not self.queue.empty():
                yield self.queue.get_nowait()
            tail = self.updates.flush()
            if tail:
                yield tail
            self.result = prompt.result()
        except acp.AcpError as e:
            self.error = e
        except (ConnectionError, asyncio.TimeoutError, OSError):
            pass  # the process ended or never answered: its exit and stderr say why
        finally:
            self.prompting = False
            conn.close()

    async def wait(self, timeout: float) -> bool:
        if self.proc is None:
            return True
        try:
            await asyncio.wait_for(self.proc.wait(), timeout)
            return True
        except asyncio.TimeoutError:
            return False

    async def end(self, waits: tuple[float, float]) -> None:
        """session/cancel while the prompt runs (the agent answers it with stopReason cancelled), then the signals."""
        proc = self.proc
        if proc is None or proc.returncode is not None:
            return
        if self.conn is not None and self.prompting and self.session:
            self.conn.notify("session/cancel", {"sessionId": self.session})
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
        """A rejected tool ends failed and the owner's answer is already shown: nothing more to report."""
        for task in self.tasks:
            with contextlib.suppress(asyncio.TimeoutError, asyncio.CancelledError, Exception):
                await asyncio.wait_for(task, 2)
        return []

    def outcome(self) -> TurnEnd:
        if self.ended is not None:
            return self.ended
        if self.result is not None:
            stop = self.result.get("stopReason")
            if stop == "end_turn":
                return TurnEnd(Outcome.DONE)
            if stop == "cancelled":
                return TurnEnd(Outcome.INTERRUPTED)
            if stop in ("max_tokens", "max_turn_requests"):
                return TurnEnd(Outcome.ERROR, TurnError.MAX_TURNS)
            return TurnEnd(Outcome.ERROR, TurnError.API_ERROR, {"reason": one_line(str(stop), 100)})
        if self.error is not None:
            # An error response, from the agent or its provider (a quota, a model that is gone): its text, cut.
            return TurnEnd(Outcome.ERROR, TurnError.API_ERROR, {"reason": self.error.text(500)})
        exit_code = self.proc.returncode if self.proc is not None else None
        stderr = self.stderr.decode(errors="replace")
        # What opencode wrote to stderr stays here, in the server log: never on the phone.
        note = "opencode failed (exit %s): %s" % (exit_code, one_line(stderr.strip()[-STDERR_LOGGED:], STDERR_LOGGED)
                                                  or "no stderr")
        return TurnEnd(Outcome.ERROR, TurnError.AGENT_FAILED, {"exit": exit_code, "subtype": None}, note)
