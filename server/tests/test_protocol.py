# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The /app contract: wire.py, the schemas and examples in docs/protocol/app, and the app's AppProtocol.kt say the
same. The live tests check every message the server sends against the schemas (conftest.py)."""
import ast
import json
import re
import tomllib
from pathlib import Path

import pytest

import bridge
from bridge import events, wire
from bridge.chat import client, permissions
from bridge.events import PHONE_EVENTS, Ev
from bridge.wire import GATE_ACTIONS
from protocol import EXAMPLES, ROOT, event_validator, problems, schema_files, validator

SERVER = Path(__file__).resolve().parents[1]
APP_PROTOCOL = SERVER.parent / "android/app/src/main/kotlin/dev/zeffin/link/AppProtocol.kt"
PRESENT_CHAT = SERVER.parent / "android/app/src/main/kotlin/dev/zeffin/ui/present/PresentChat.kt"
# The codes servers before 0.14.0 sent, named after Claude Code: no longer in the schemas, still read by the app.
OLD_PROBLEMS = {"claude_missing"}
OLD_TURN_ERRORS = {"claude_failed"}


def names(enum) -> set[str]:
    return {m.value for m in enum}


def stems(folder: str) -> set[str]:
    return {p.name.removesuffix(".json") for p in (ROOT / folder).glob("*.json")}


def examples(folder: str) -> list[Path]:
    return sorted((EXAMPLES / folder).glob("*.json"))


def schema(rel: str) -> dict:
    return json.loads((ROOT / rel).read_text())


def enum_of(node: dict) -> list:
    """The enum of a property, also inside anyOf [x, null]."""
    if "enum" in node:
        return node["enum"]
    for option in node.get("anyOf", []):
        if "enum" in option:
            return option["enum"]
    raise AssertionError(f"no enum in {node}")


def test_version_matches_pyproject():
    pyproject = tomllib.loads((SERVER / "pyproject.toml").read_text())
    assert bridge.__version__ == pyproject["project"]["version"]


@pytest.mark.parametrize("path", schema_files(), ids=lambda p: str(p.relative_to(ROOT)))
def test_schemas_are_valid(path):
    assert validator(str(path.relative_to(ROOT))) is not None  # check_schema raises when it is not


def test_every_name_has_a_schema_and_the_other_way_round():
    assert stems("server") == names(wire.Out)
    assert stems("app") == names(wire.In)
    assert stems("events") == names(wire.Kind)


def test_every_name_has_an_example():
    for folder, enum in (("server", wire.Out), ("app", wire.In)):
        typed = {json.loads(p.read_text())["type"] for p in examples(folder)}
        assert typed == names(enum), folder
    kinds = [json.loads(p.read_text()) for p in examples("events")]
    assert {e["kind"] for e in kinds} == names(wire.Kind)
    assert {e["code"] for e in kinds if e["kind"] == wire.Kind.NOTICE} == names(wire.Notice)


@pytest.mark.parametrize("path", examples("server") + examples("app"), ids=lambda p: str(p.relative_to(EXAMPLES)))
def test_message_examples_match_their_schema(path):
    message = json.loads(path.read_text())
    assert path.name.startswith(message["type"] + "."), "an example is named after its type"
    assert problems(path.parent.name, message) == []


@pytest.mark.parametrize("path", examples("events"), ids=lambda p: p.name)
def test_event_examples_match_their_schema(path):
    event = json.loads(path.read_text())
    assert path.name.startswith(event["kind"] + "."), "an example is named after its kind"
    assert [e.message for e in validator(f"events/{event['kind']}.json").iter_errors(event)] == []
    assert [e.message for e in event_validator().iter_errors(event)] == [], "chat.history's events"
    sent = {"type": "chat.event", "conversation": "3f2b8c1e-5d4a-4e6f-9b7c-2a1d0e9f8c7b", **event}
    assert problems("server", sent) == []


def test_schemas_refuse_what_is_not_in_them():
    hello = json.loads((EXAMPLES / "server/hello.json").read_text())
    assert problems("server", {**hello, "extra": 1})
    assert problems("server", {**hello, "protocol": 2})
    text = json.loads((EXAMPLES / "events/text.json").read_text())
    assert problems("server", {"type": "chat.event", "conversation": "c", **text, "kind": "texts"})
    assert problems("server", {"type": "chat.event", "conversation": "c", **{k: v for k, v in text.items() if k != "id"}})
    notice = json.loads((EXAMPLES / "events/notice.remote_off.json").read_text())
    assert problems("server", {"type": "chat.event", "conversation": "c", **notice, "data": {"reason": "bored"}})
    assert problems("app", {"type": "chat.send", "conversation": "c", "text": "x" * 16001})


def test_a_model_has_a_reason_exactly_when_it_is_not_available():
    status = json.loads((EXAMPLES / "server/chat.status.json").read_text())
    models = [m for agent in status["agents"] for m in agent["models"]]
    assert {(m["available"], m["reason"]) for m in models} == {(True, None)} | {(False, r) for r in wire.ModelReason}
    agent = status["agents"][0]
    model = agent["models"][0]
    for wrong in ({"available": True, "reason": "unchecked"}, {"available": False, "reason": None},
                  {"available": False, "reason": "not_offered"}, {"reason": None}):
        changed = {k: v for k, v in {**model, **wrong}.items() if k in wrong or k not in ("available", "reason")}
        assert problems("server", {**status, "agents": [{**agent, "models": [changed]}]}), wrong
    without_images = {k: v for k, v in model.items() if k != "images"}
    assert problems("server", {**status, "agents": [{**agent, "models": [without_images]}]})
    assert problems("app", {"type": "launch"})


def test_schema_enums_are_the_wire_names():
    assert set(enum_of(schema("server/error.json")["properties"]["error"])) == names(wire.Err)
    assert set(schema("server/hello.json")["properties"]["features"]["items"]["enum"]) == names(wire.Feature)
    defs = schema("defs.json")["$defs"]
    assert set(defs["problem"]["enum"]) == names(wire.Problem)
    assert set(enum_of(defs["model"]["properties"]["reason"])) == names(wire.ModelReason)
    notice = schema("events/notice.json")
    assert set(notice["properties"]["code"]["enum"]) == names(wire.Notice)
    assert {rule["if"]["properties"]["code"]["const"] for rule in notice["allOf"]} == names(wire.Notice)
    remote_off = next(r for r in notice["allOf"] if r["if"]["properties"]["code"]["const"] == wire.Notice.REMOTE_OFF)
    assert set(remote_off["then"]["properties"]["data"]["properties"]["reason"]["enum"]) == names(wire.RemoteOff)
    end = schema("events/turn.end.json")
    assert set(end["properties"]["outcome"]["enum"]) == names(wire.Outcome)
    assert set(enum_of(end["properties"]["error"])) == names(wire.TurnError)
    guard = next(r for r in end["allOf"] if r["if"]["properties"]["error"].get("const") == wire.TurnError.BILLING_GUARD)
    assert set(guard["then"]["properties"]["data"]["properties"]["why"]["enum"]) == names(wire.Guard)
    assert set(schema("events/permission.end.json")["properties"]["answer"]["enum"]) == names(wire.Answer)
    assert set(schema("events/denied.json")["properties"]["by"]["enum"]) == names(wire.DeniedBy)
    assert set(schema("app/chat.permission.answer.json")["properties"]["answer"]["enum"]) == set(permissions.ANSWERS)
    assert set(schema("app/gate.json")["properties"]["action"]["enum"]) == set(GATE_ACTIONS)
    assert set(schema("app/gate.json")["properties"]["via"]["enum"]) == names(wire.GateVia)
    assert defs["mode"] == {"type": "string", "description": defs["mode"]["description"]}, "a future agent's modes"
    assert defs["effort"]["enum"] == list(wire.EFFORTS)
    phone = schema("server/phone.json")["properties"]["item"]["properties"]["ev"]["enum"]
    assert set(phone) == set(PHONE_EVENTS)


def test_zf_chat_words_every_code():
    assert set(client.PROBLEMS) == names(wire.Problem)
    assert set(client.REMOTE_ENDS) == names(wire.RemoteOff)
    for code in wire.Notice:
        assert not client.describe_notice(code, {}).startswith(code.value), code


def test_phone_events_are_metrics_events():
    assert PHONE_EVENTS <= set(Ev)
    assert Ev.PAIRED in PHONE_EVENTS  # the app's phone history shows it, as the web view's does


def _literals() -> list[tuple[str, str, str, int]]:
    """(what, value, file, line) for every literal protocol or metrics name still written as a string in bridge/."""
    found = []
    for path in sorted((SERVER / "bridge").rglob("*.py")):
        if path.name in ("wire.py", "events.py"):
            continue
        tree = ast.parse(path.read_text())
        for node in ast.walk(tree):
            if isinstance(node, ast.Dict):
                for key, value in zip(node.keys, node.values):
                    if isinstance(key, ast.Constant) and key.value in ("kind", "code", "ev") \
                            and isinstance(value, ast.Constant) and isinstance(value.value, str):
                        found.append((key.value, value.value, path.name, node.lineno))
            if isinstance(node, ast.Call) and getattr(node.func, "id", None) == "ChatError" and node.args \
                    and isinstance(node.args[0], ast.Constant):
                found.append(("error", node.args[0].value, path.name, node.lineno))
    return found


def test_names_written_as_strings_are_known():
    """Writers outside wire.py's users (phone.py, hub.py, app.py, tools.py…) keep literals: they must still be known names.
    "kind" and "code" also name other things (attachments, Flipper RPC codes): only chat event kinds are checked."""
    known = {"ev": names(Ev), "error": names(wire.Err), "code": names(wire.Notice)}
    unknown = [f"{file}:{line} {what}={value!r}" for what, value, file, line in _literals()
               if what in known and value not in known[what] and file not in ("attachments.py", "tools.py")]
    assert unknown == []


def test_chat_modules_use_the_wire_names():
    """The chat's own modules write no protocol literal at all."""
    own = {"manager.py", "client.py", "stream.py", "remote.py", "appws.py", "stats.py", "view.py"}
    left = [f"{file}:{line} {what}={value!r}" for what, value, file, line in _literals() if file in own]
    assert left == []


def _kotlin_constants() -> dict[str, set[str]]:
    """const vals of AppProtocol.kt, by the object they sit in."""
    out: dict[str, set[str]] = {}
    current = None
    for line in APP_PROTOCOL.read_text().splitlines():
        if m := re.match(r"\s*object (\w+)", line):
            current = m.group(1)
        if (m := re.match(r'\s*const val \w+ = "([^"]+)"', line)) and current:
            out.setdefault(current, set()).add(m.group(1))
    return out


def test_app_protocol_kt_has_the_same_names():
    kt = _kotlin_constants()
    assert kt.get("Out") == names(wire.Out)
    assert kt.get("In") == names(wire.In)
    assert kt.get("Kind") == names(wire.Kind)
    assert kt.get("Feature") == names(wire.Feature)
    assert kt.get("Err") == names(wire.Err)
    # The app still reads the codes older servers sent, before they were neutral.
    assert names(wire.Problem) <= kt.get("Problem", set()) <= names(wire.Problem) | OLD_PROBLEMS
    assert kt.get("Notice") == names(wire.Notice)
    assert kt.get("RemoteOff") == names(wire.RemoteOff)
    assert kt.get("Outcome") == names(wire.Outcome)
    assert names(wire.TurnError) <= kt.get("TurnError", set()) <= names(wire.TurnError) | OLD_TURN_ERRORS
    assert kt.get("Answer") == names(wire.Answer)
    assert kt.get("GateVia") == names(wire.GateVia)
    assert kt.get("AgentId") == names(wire.AgentId)
    assert kt.get("ModelReason") == names(wire.ModelReason)
    assert kt.get("PhoneEv") == {e.value for e in PHONE_EVENTS}
    assert f"const val PROTOCOL = {wire.PROTOCOL}" in APP_PROTOCOL.read_text()


def test_the_app_knows_the_message_limit():
    """The app turns a longer paste into a file at the server's limit, so the two must not drift apart."""
    from bridge.chat.service import MAX_TEXT
    kt = (APP_PROTOCOL.parent / "ChatProtocol.kt").read_text()
    found = re.search(r"const val MAX_TEXT = ([\d_]+)", kt)
    assert found and int(found.group(1).replace("_", "")) == MAX_TEXT


def test_events_module_lists_each_name_once():
    assert len(set(Ev)) == len(list(Ev)) and events.PHONE_EVENTS == frozenset(PHONE_EVENTS)


def test_mode_options_are_the_modes_with_the_apps_english_words():
    """chat.status's mode_options name every mode chat.set takes, in its order, with the words the app shows today."""
    from bridge.chat.claude_code import MODE_LABELS

    options = [{"value": str(mode), "label": label} for mode, label in MODE_LABELS.items()]
    assert [o["value"] for o in options] == list(wire.MODES)
    app = dict(re.findall(r'Mode\.(\w+) -> (?:t\()?"([^"]+)"', PRESENT_CHAT.read_text().split("fun modeName")[1]
                          .split("fun ")[0]))
    kotlin = {"MANUAL": "default", "ACCEPT_EDITS": "acceptEdits", "PLAN": "plan", "AUTO": "auto"}
    assert {kotlin[k]: v for k, v in app.items()} == {o["value"]: o["label"] for o in options}
    item = schema("defs.json")["$defs"]["modeOption"]["properties"]["value"]
    assert item == {"type": "string", "description": item["description"]}, "a plain string: a future agent's modes"


def test_chat_status_has_no_fields_tied_to_claude_code():
    """hello and chat.status carry each agent's identity, login, models, modes and capabilities as neutral data
    (agents): Claude Code's own block (claude), the fixed mode list (modes) and the fields of one agent (hello's agent
    and remote, chat.status's login, models and mode_options) are gone from the schemas and every example."""
    defs = schema("defs.json")["$defs"]
    assert set(defs["login"]["properties"]) == {"method", "subscription"}
    gone = {"claude", "modes", "fable", "login", "models", "mode_options", "ready", "problem"}
    status, hello = schema("server/chat.status.json"), schema("server/hello.json")
    assert not gone & (set(status["properties"]) | set(status["required"]))
    assert not {"agent", "remote"} & set(hello["properties"])
    assert not {"claude", "modes", "fable"} & set(defs["agent"]["properties"])
    for path in examples("server"):
        message = json.loads(path.read_text())
        if message["type"] == "chat.status":
            assert not gone & set(message), path.name
        if message["type"] == "hello":
            assert not {"agent", "remote"} & set(message), path.name


def test_app_carries_no_code_named_after_claude_code():
    """The codes named after Claude Code left /app in 0.14.0: no schema enum and no example holds one."""
    def enums(node) -> set:
        if isinstance(node, dict):
            return set(node.get("enum", [])) | {v for child in node.values() for v in enums(child)}
        return {v for child in node for v in enums(child)} if isinstance(node, list) else set()

    held = {v for path in schema_files() for v in enums(json.loads(path.read_text()))}
    assert {"agent_missing", "agent_failed"} <= held and not (OLD_PROBLEMS | OLD_TURN_ERRORS) & held
    for path in EXAMPLES.rglob("*.json"):
        assert not {f'"{code}"' for code in OLD_PROBLEMS | OLD_TURN_ERRORS} & set(
            re.findall(r'"[a-z_]+"', path.read_text())), path.name
