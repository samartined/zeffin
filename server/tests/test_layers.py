# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The server's layers (docs/architecture.md): each module of bridge/ belongs to one layer, and a layer imports only
from the layers below it. One table, one test per rule. Imports inside
functions count too. A rule the code does not hold yet is listed in PENDING with the step that makes it hold; a test
fails when an entry there is no longer needed, so the list only shrinks."""
import ast
from pathlib import Path

BRIDGE = Path(__file__).resolve().parents[1] / "bridge"

CONTRACTS, PERSISTENCE, DEVICE, AGENT, CHAT, ADAPTERS = "contracts", "persistence", "device", "agent", "chat", "adapters"

LAYERS = {
    CONTRACTS: {"bridge", "bridge.chat", "bridge.wire", "bridge.events", "bridge.text", "bridge.png", "bridge.localpeer", "bridge.privnet",
                "bridge.fanout", "bridge.agent"},
    PERSISTENCE: {"bridge.config", "bridge.gate", "bridge.stats", "bridge.paircodes", "bridge.peers", "bridge.tlskey",
                  "bridge.devicecache", "bridge.notes",
                  "bridge.profiles", "bridge.catalog", "bridge.chat.index",
                  "bridge.chat.history", "bridge.chat.attachments"},
    DEVICE: {"bridge.framing", "bridge.phone", "bridge.hub", "bridge.screen", "bridge.pb", "bridge.watch",
               "bridge.firmware"},
    AGENT: {"bridge.chat.command", "bridge.chat.stream", "bridge.chat.remote", "bridge.chat.catalog",
            "bridge.chat.permissions", "bridge.chat.guarded", "bridge.chat.probe", "bridge.chat.probe_remote",
            "bridge.chat.redact",
            "bridge.chat.terminal", "bridge.chat.claude_code", "bridge.chat.acp", "bridge.chat.opencode",
            "bridge.chat.probe_opencode"},
    CHAT: {"bridge.chat.manager", "bridge.chat.service", "bridge.chat.turn", "bridge.chat.remote_runner",
           "bridge.chat.broker", "bridge.chat.slot", "bridge.chat.bus", "bridge.chat.ports", "bridge.chat.tasks",
           "bridge.chat.errors"},
    ADAPTERS: {"bridge.app", "bridge.appws", "bridge.phonews", "bridge.view", "bridge.tools", "bridge.cli",
               "bridge.report", "bridge.systemd", "bridge.pairing", "bridge.web", "bridge.sim", "bridge.chat.client"},
}
ROOTS = {"bridge.app", "bridge.cli"}  # the composition roots: the only adapters that import other adapters
SHARED = {"bridge.web"}  # Starlette helpers every adapter may import: the one exception to that rule
WEB = {"starlette", "mcp", "uvicorn"}  # only adapters know them

# (module, what it imports: a bridge module or a framework) pairs that break a rule today, and the step that fixes them.
PENDING: dict[tuple[str, str], str] = {}


def layer_of(module: str) -> str:
    for layer, modules in LAYERS.items():
        if module in modules:
            return layer
    raise AssertionError(f"{module} is in no layer: add it to LAYERS")


def module_name(path: Path) -> str:
    parts = path.relative_to(BRIDGE.parent).with_suffix("").parts
    return ".".join(parts[:-1] if parts[-1] == "__init__" else parts)


def sources() -> dict[str, Path]:
    """Every module of bridge/, without the generated protobuf code."""
    return {module_name(p): p for p in sorted(BRIDGE.rglob("*.py")) if not p.name.endswith("_pb2.py")}


def imports(path: Path) -> tuple[set[str], set[str]]:
    """(bridge modules, top-level third-party or stdlib names) that a file imports, anywhere in it."""
    module = module_name(path)
    package = module if path.name == "__init__.py" else module.rpartition(".")[0]
    known = set(sources())
    inside, outside = set(), set()
    for node in ast.walk(ast.parse(path.read_text())):
        if isinstance(node, ast.Import):
            for alias in node.names:
                top = alias.name.split(".")[0]
                if top == "bridge":
                    inside.add(alias.name)
                else:
                    outside.add(top)
        elif isinstance(node, ast.ImportFrom):
            if node.level == 0:
                if node.module.split(".")[0] != "bridge":
                    outside.add(node.module.split(".")[0])
                    continue
                base = node.module
            else:
                base = ".".join(package.split(".")[:len(package.split(".")) - node.level + 1])
                if node.module:
                    base = f"{base}.{node.module}"
            for alias in node.names:
                inside.add(f"{base}.{alias.name}" if f"{base}.{alias.name}" in known else base)
    inside.discard(module)
    return inside, outside


def breaks(layer: str, allowed: set[str]) -> list[str]:
    """Imports of the layer's modules that land outside the allowed layers, PENDING ones aside."""
    found = []
    for module, path in sources().items():
        if layer_of(module) != layer:
            continue
        for target in sorted(imports(path)[0]):
            if layer_of(target) not in allowed and (module, target) not in PENDING:
                found.append(f"{module} imports {target} ({layer_of(target)})")
    return found


def test_every_module_has_a_layer():
    for module in sources():
        layer_of(module)


def test_contracts_import_nothing_from_bridge():
    found = [f"{m} imports {t}" for m, p in sources().items() if layer_of(m) == CONTRACTS for t in imports(p)[0]]
    assert found == []


def test_persistence_imports_only_contracts():
    assert breaks(PERSISTENCE, {CONTRACTS, PERSISTENCE}) == []


def test_the_device_imports_only_contracts_and_persistence():
    assert breaks(DEVICE, {CONTRACTS, PERSISTENCE, DEVICE}) == []


def test_agent_integration_imports_only_contracts():
    assert breaks(AGENT, {CONTRACTS, AGENT}) == []


def test_chat_services_reach_the_agent_only_through_its_port():
    """bridge/agent.py, a contract; the composition root (app.py) builds the agent and hands it in."""
    assert breaks(CHAT, {CONTRACTS, PERSISTENCE, CHAT}) == []


# What only the agent may know: Claude Code's hook and field names, its tool prefix, its models and its name. Matched
# in every string of the chat services' code (docstrings aside, comments are not code): SPOKEN as a part of any string,
# whatever its case, HOOKS as a whole string.
SPOKEN = ("claude", "fable", "[1m]", "mcp__", "hookspecificoutput", "pretooluse", "posttooluse", "userpromptsubmit",
          "permissionrequest", "permissiondenied", "decision", "error_max_turns", "apikeysource", "permissionmode",
          "permission_suggestions", "last_assistant_message", "tool_response", "notification_type", "stream-json",
          "--resume", "--session-id", "hastrustdialogaccepted")
HOOKS = {"stop", "Stop", "stop-failure", "notification", "pre-tool-use", "post-tool-use", "user-prompt-submit",
         "session-start", "session-end", "permission-request", "permission-denied"}


def strings(path: Path) -> list[tuple[int, str]]:
    """(line, text) of every string constant in the code, docstrings aside."""
    tree = ast.parse(path.read_text())
    docs = {id(node.body[0].value) for node in ast.walk(tree)
            if isinstance(node, (ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)) and node.body
            and isinstance(node.body[0], ast.Expr) and isinstance(node.body[0].value, ast.Constant)}
    return [(node.lineno, node.value) for node in ast.walk(tree)
            if isinstance(node, ast.Constant) and isinstance(node.value, str) and id(node) not in docs]


def test_chat_services_write_nothing_only_the_agent_knows():
    found = [f"{m}:{line} {text!r}" for m, p in sources().items() if layer_of(m) == CHAT for line, text in strings(p)
             if text in HOOKS or any(word in text.lower() for word in SPOKEN)]
    assert found == []


def test_the_literal_check_finds_what_it_looks_for(tmp_path):
    path = tmp_path / "x.py"
    path.write_text('"""Claude Code, the Stop hook: docstrings are free."""\n'
                    'def f():\n    """mcp__flipper__ too."""\n    return {"decision": "block"}, "stop", f"{1}mcp__x"\n')
    assert sorted(text for _, text in strings(path)) == ["block", "decision", "mcp__x", "stop"]


def test_chat_services_never_import_the_device_or_adapters():
    """They reach the hub only through the object they are given."""
    assert breaks(CHAT, {CONTRACTS, PERSISTENCE, AGENT, CHAT}) == []
    web = [f"{m} imports {name}" for m, p in sources().items() if layer_of(m) == CHAT
           for name in imports(p)[1] if name in ("starlette", "mcp", "uvicorn")]
    assert web == []


def test_only_adapters_know_the_web_and_mcp_frameworks():
    found = [f"{m} imports {name}" for m, p in sources().items() if layer_of(m) != ADAPTERS
             for name in sorted(imports(p)[1] & WEB) if (m, name) not in PENDING]
    assert found == []


def test_adapters_import_adapters_only_from_the_composition_roots():
    found = [f"{m} imports {t}" for m, p in sources().items() if layer_of(m) == ADAPTERS and m not in ROOTS
             for t in sorted(imports(p)[0] - SHARED) if layer_of(t) == ADAPTERS and (m, t) not in PENDING]
    assert found == []


def test_pending_entries_are_still_needed():
    """When the code holds a rule, its PENDING entry goes."""
    stale = [f"{m} -> {t}: {step}" for (m, t), step in PENDING.items() if t not in set.union(*imports(sources()[m]))]
    assert stale == []
