# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""StreamParser over what the real Claude Code wrote: the redacted fixtures of `zf agent probe --fixtures`.

They come from one probe run on a real server (manifest.json names the Claude Code version and, for each
file, the probe step, its prompt, whether it resumed and how the process exited). To refresh them, run the
probe with `--json --fixtures server/tests/fixtures/stream` there (docs/guide/zf-command.md, `zf agent probe`).
"""
import json
from pathlib import Path

import pytest

from bridge.chat.stream import StreamParser
from bridge.wire import Kind

FOLDER = Path(__file__).parent / "fixtures" / "stream"
if not (FOLDER / "manifest.json").exists():
    pytest.skip("no probe fixtures yet", allow_module_level=True)
MANIFEST = json.loads((FOLDER / "manifest.json").read_text())
FILES = {f["file"]: f for f in MANIFEST["files"]}


def events(name: str) -> list[dict]:
    return [json.loads(line) for line in (FOLDER / name).read_text().splitlines()]


def parsed(name: str):
    parser = StreamParser()
    outs = [out for event in events(name) for out in parser.feed(event)]
    return parser, outs


def of_step(step: str) -> list[str]:
    return [f["file"] for f in MANIFEST["files"] if f["step"] == step]


def kinds(outs, kind: str) -> list[dict]:
    return [o.data for o in outs if o.kind == "event" and o.data.get("kind") == kind]


def test_the_manifest_matches_the_folder():
    assert MANIFEST["claude"] and sorted(FILES) == sorted(p.name for p in FOLDER.glob("*.jsonl"))


@pytest.mark.parametrize("name", sorted(FILES))
def test_every_turn_parses(name):
    """Never raises; a turn that ran opens with init and ends with its result; streamed text adds up to the
    final text of its block."""
    parser, outs = parsed(name)
    meta = FILES[name]
    if meta["step"] == "unknown_resume":
        return  # no model turn: below
    assert outs[0].kind == "init" and outs[-1].kind == "result", [o.kind for o in outs]
    assert outs[0].data["session_id"] == outs[-1].data["session_id"]
    streamed: dict[str, str] = {}
    for o in outs:
        if o.kind == "delta":
            assert o.data["offset"] == len(streamed.get(o.data["id"], ""))
            streamed[o.data["id"]] = streamed.get(o.data["id"], "") + o.data["text"]
    texts = {t["id"]: t["text"] for t in kinds(outs, Kind.TEXT)}
    for block, text in streamed.items():
        assert texts.get(block) == text, block
    started = {t["id"] for t in kinds(outs, Kind.TOOL)}
    assert {r["id"] for r in kinds(outs, Kind.TOOL_RESULT)} <= started
    if meta["exit"] == 0 and outs[-1].data.get("subtype") == "success":
        assert texts, "a finished turn says something"


@pytest.mark.parametrize("step", ["new_and_resume", "sigint"])
def test_a_resumed_turn_keeps_its_session(step):
    first, second = of_step(step)[:2]
    assert FILES[second]["resume"] is True
    assert parsed(first)[1][0].data["session_id"] == parsed(second)[1][0].data["session_id"]


def test_an_allowed_write():
    _, outs = parsed(of_step("allow")[0])
    tools = kinds(outs, Kind.TOOL)
    assert [t["name"] for t in tools] == ["Write", "Write"]
    assert all(set(t["input"]) >= {"file_path", "content"} for t in tools)
    results = kinds(outs, Kind.TOOL_RESULT)
    assert [r["error"] for r in results] == [False, False] and all(r["ms"] is not None for r in results)


@pytest.mark.parametrize("step", ["deny", "hook_timeout"])
def test_a_denied_write(step):
    _, outs = parsed(of_step(step)[0])
    denied = [o.data for o in outs if o.kind == "denied"]
    assert len(denied) == 1 and denied[0]["tool_name"] == "Write"
    assert [r["error"] for r in kinds(outs, Kind.TOOL_RESULT)] == [True]
    assert outs[-1].data["permission_denials"][0]["tool_name"] == "Write"


def test_an_interrupted_turn():
    _, outs = parsed(of_step("sigint")[0])
    assert outs[-1].data["subtype"] == "error_during_execution"


def test_an_unknown_resume_gives_only_a_result():
    _, outs = parsed(of_step("unknown_resume")[0])
    assert [o.kind for o in outs] == ["result"] and outs[0].data["subtype"] == "error_during_execution"


def test_the_flipper_tool():
    for name in of_step("mcp"):
        _, outs = parsed(name)
        tools = {t["id"]: t["name"] for t in kinds(outs, Kind.TOOL)}
        flipper = [r for r in kinds(outs, Kind.TOOL_RESULT) if tools.get(r["id"]) == "mcp__flipper__flipper_status"]
        assert len(flipper) == 1 and flipper[0]["error"] is False and "connected" in flipper[0]["text"]


def test_tools_without_asking():
    _, outs = parsed(of_step("accept_edits")[0])
    assert [t["name"] for t in kinds(outs, Kind.TOOL)] == ["Write", "Bash"]
    _, outs = parsed(of_step("add_dir")[0])
    assert [t["name"] for t in kinds(outs, Kind.TOOL)] == ["Read"]
    assert [r["error"] for r in kinds(outs, Kind.TOOL_RESULT)] == [False]


def test_an_image_turn_and_effort():
    _, outs = parsed(of_step("image")[0])
    assert outs[-1].data["subtype"] == "success" and kinds(outs, Kind.TEXT)
    low = parsed(of_step("effort")[0])[1]
    assert low[0].data["permissionMode"] == "default"


def test_no_fixture_of_what_is_the_owners():
    """/usage prints the owner's usage, and the model list their commands and agents: neither is saved."""
    assert not {"usage", "models"} & {f["step"] for f in MANIFEST["files"]}


def test_what_the_parser_ignores():
    """Every event type in the fixtures is either parsed or knowingly ignored."""
    seen = {(e.get("type"), e.get("subtype")) for name in FILES for e in events(name)}
    handled = {("system", "init"), ("stream_event", None), ("assistant", None), ("user", None),
               ("result", "success"), ("result", "error_during_execution"), ("system", "permission_denied"),
               ("rate_limit_event", None)}
    ignored = {("system", "status"), ("system", "thinking_tokens")}
    assert seen <= handled | ignored, seen - handled - ignored
    for name in FILES:
        parser, outs = parsed(name)
        for e in events(name):
            if e.get("type") == "rate_limit_event" and (e.get("rate_limit_info") or {}).get("status") == "allowed":
                assert not any(o.kind == "limit" for o in outs)
