# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""OpenCode behind the Agent port: the ACP client's mapping of the shapes the trial recorded, the
"never pay" guards, the catalog of free models, and the permission request handed to the broker."""
import asyncio
import json
import shlex
import stat
import sys
from pathlib import Path

import pytest

from bridge import __version__
from bridge.agent import Delta, Output, PermissionAnswer, PermissionAsk, TurnRequest
from bridge.chat import acp, opencode
from bridge.config import Config
from bridge.wire import AgentId, Guard, Mode, ModelReason, Outcome, Problem, TurnError

FAKE = Path(__file__).resolve().parent / "fake_acp.py"
TOKEN = "m" * 40
CFG = dict(host="127.0.0.1", phone_port=19434, mcp_port=19435, phone_token="p" * 40, mcp_token=TOKEN,
           view_token="v" * 40, app_token="a" * 40, state_dir="/tmp", keepalive_s=0, free_only=True)
SID = "ses_0123456789ab"

# The trial's shapes, redacted as it posted them.
TOOL = {"sessionUpdate": "tool_call", "toolCallId": "call-1", "title": "flipper_flipper_screen", "kind": "other",
        "status": "pending", "locations": [], "rawInput": {}}
RESULT = {"sessionUpdate": "tool_call_update", "toolCallId": "call-1", "status": "completed", "kind": "other",
          "title": "flipper_flipper_screen",
          "content": [{"type": "content", "content": {"type": "text", "text": "{\"seq\": 1, \"stale\": false, "
                                                                           "\"note\": \"untrusted\"}"}},
                      {"type": "content", "content": {"type": "image", "mimeType": "image/png", "data": "AAAA"}}],
          "rawOutput": {"output": "{…}", "metadata": {"truncated": False}}}
ASK = {"sessionId": SID, "toolCall": {"toolCallId": "call-2", "title": "echo trial-ok", "kind": "execute",
                                      "status": "pending", "locations": [], "rawInput": {"command": "echo trial-ok"}},
       "options": [{"optionId": "once", "kind": "allow_once", "name": "Allow once"},
                   {"optionId": "always", "kind": "allow_always", "name": "Always allow"},
                   {"optionId": "reject", "kind": "reject_once", "name": "Reject"}]}


def chunk(text: str, mid: str = "msg_1", kind: str = "agent_message_chunk") -> dict:
    return {"sessionUpdate": kind, "messageId": mid, "content": {"type": "text", "text": text}}


# ------------------------------------------------------------------ the ACP client's mapping
def test_updates_become_the_ports_events_in_the_bridges_vocabulary():
    updates = acp.Updates(opencode.tool_name)
    events = [e for u in (chunk("Hel"), chunk("lo"), chunk("hidden", kind="agent_thought_chunk"), TOOL,
                          {**TOOL, "sessionUpdate": "tool_call_update", "status": "in_progress"}, RESULT,
                          {"sessionUpdate": "usage_update", "used": 1}, chunk("Done", "msg_2"),
                          {"sessionUpdate": "something_new"}) for e in updates.feed(u)] + updates.flush()
    deltas = [e for e in events if isinstance(e, Delta)]
    assert [(d.block, d.offset, d.text) for d in deltas] == [("msg_1", 0, "Hel"), ("msg_1", 3, "lo"),
                                                             ("msg_2", 0, "Done")]
    shown = [e.event for e in events if isinstance(e, Output)]
    assert [e["kind"] for e in shown] == ["text", "tool", "tool.result", "text"]
    assert shown[0] == {"kind": "text", "id": "msg_1", "text": "Hello"}  # the thought never shows
    tool = next(e for e in events if isinstance(e, Output) and e.event["kind"] == "tool")
    assert tool.event == {"kind": "tool", "id": "call-1", "name": "mcp__flipper__flipper_screen", "input": {}}
    assert tool.flipper
    result = next(e for e in events if isinstance(e, Output) and e.event["kind"] == "tool.result")
    assert result.seq == 1 and result.event["images"] == 1 and result.event["error"] is False
    assert "seq" not in result.event and isinstance(result.event["ms"], int)


def test_a_rejected_tool_is_a_failed_result_and_text_is_cut():
    updates = acp.Updates(opencode.tool_name)
    tool = {**TOOL, "toolCallId": "c", "title": "touch x", "kind": "execute", "rawInput": {"command": "touch x"}}
    failed = {"sessionUpdate": "tool_call_update", "toolCallId": "c", "status": "failed",
              "rawOutput": {"error": "The user rejected permission to use this specific tool call."}}
    shown = [e.event for u in (tool, failed, chunk("x" * 70_000, "m")) for e in updates.feed(u)
             if isinstance(e, Output)] + [e.event for e in updates.flush() if isinstance(e, Output)]
    assert shown[0] == {"kind": "tool", "id": "c", "name": "Bash", "input": {"command": "touch x"}}
    assert shown[1]["error"] is True and "rejected" in shown[1]["text"]
    assert len(shown[2]["text"]) < 66_000


def test_tool_names_and_the_rules_always_grants():
    assert opencode.tool_name({"title": "flipper_flipper_press", "kind": "other"}) == ("mcp__flipper__flipper_press",
                                                                                     True)
    assert opencode.tool_name({"title": "ls -la", "kind": "execute"}) == ("Bash", False)
    assert opencode.tool_name({"title": "src/a.py", "kind": "edit"}) == ("Edit", False)
    assert opencode.tool_name({"title": "my_server_tool", "kind": "other"}) == ("my_server_tool", False)
    # Only a title that is exactly one of the bridge's tools (security review before 1.0.0, M1): an external_directory
    # request carries the shell command as its title, with kind other
    for title in ("flipper_flipper_x=1 cat ../zeffin-data/config.json", "flipper_flipper_press; cat x",
                  "flipper_flipper_nope", "flipper_flipper_press ", "xflipper_flipper_press"):
        assert opencode.tool_name({"title": title, "kind": "other"})[1] is False, title
    assert opencode.tool_name({"title": "flipper_flipper_press", "kind": "execute"}) == ("Bash", False)
    assert opencode.rule_for(ASK["toolCall"]) == "Bash(echo trial-ok)"
    assert opencode.rule_for({"kind": "edit", "title": "a", "locations": [{"path": "/x/a.py"}]}) == "Edit(/x/a.py)"
    assert opencode.rule_for({"kind": "execute", "rawInput": {"command": "a\x01b"}}) is None  # never a bad rule


def test_the_bridges_tools_are_the_mcp_servers():
    tools = json.loads((Path(__file__).parent / "fixtures" / "mcp_tools.json").read_text())
    assert opencode.MCP_TOOLS == {t["name"] for t in tools}


# ------------------------------------------------------------------ the "never pay" guards
def test_the_agents_environment_is_an_allow_list():
    environ = {"PATH": "/usr/bin", "HOME": "/home/u", "LANG": "C.UTF-8", "ANTHROPIC_API_KEY": "x",
               "ANTHROPIC_BASE_URL": "x", "OPENAI_API_KEY": "x", "GEMINI_API_KEY": "x", "NVIDIA_API_KEY": "x",
               "OPENROUTER_API_KEY": "x", "AWS_SECRET_ACCESS_KEY": "x", "ZEFFIN_MCP_TOKEN": TOKEN,
               "OPENCODE_AUTO_SHARE": "1", "OPENCODE_CONFIG_CONTENT": "{}", "HTTPS_PROXY": "http://proxy:1"}
    env = opencode.agent_env(environ)
    assert {k for k in env if not k.startswith("OPENCODE_")} == {"PATH", "HOME", "LANG", "HTTPS_PROXY"}
    assert env["OPENCODE_DISABLE_AUTOUPDATE"] == "1" and "OPENCODE_AUTO_SHARE" not in env
    assert "OPENCODE_CONFIG_CONTENT" not in env and TOKEN not in json.dumps(env)


def test_the_configuration_holds_the_guards_and_a_changed_one_is_unsafe(tmp_path):
    opencode.write_config(tmp_path)
    written = json.loads((tmp_path / "opencode.json").read_text())
    assert written["share"] == "disabled" and written["autoupdate"] is False
    assert written["enabled_providers"] == ["opencode", "nvidia"] and written["permission"]["flipper_*"] == "allow"
    assert "mcp" not in written and TOKEN not in json.dumps(written)  # the MCP server goes through the pipe
    assert written["compaction"] == {"tail_turns": 0}  # a summary drops every turn's images
    assert opencode.config_problem(written) is None
    for change in ({"share": "auto"}, {"autoupdate": True}, {"enabled_providers": ["opencode", "anthropic"]},
                   {"enabled_providers": None}, {"provider": {"nvidia": {"options": {"baseURL": "x"}}}},
                   {"model": "anthropic/claude-x"}, {"small_model": "openai/gpt-x"},
                   {"agent": {"build": {"model": "opencode/big-paid-model"}}}, {"agent": {"x": {"model": None}}},
                   {"mode": {"plan": {"model": "nvidia/nvidia/nemotron-3.5-super-vl-120b-a12b"}}}, {"agent": []}):
        assert opencode.config_problem({**written, **change}), change
    assert opencode.config_problem({k: v for k, v in written.items() if k != "share"})
    assert opencode.config_problem({**written, "agent": {"build": {"prompt": "x"}}, "mode": {}}) is None


def test_the_version_is_pinned_to_the_checked_range():
    assert opencode.parse_version("1.19.5\n") == (1, 19, 5) and opencode.parse_version("opencode v1.19.7") == (1, 19, 7)
    assert opencode.parse_version("nope") is None
    assert opencode.version_ok((1, 19, 5)) and not opencode.version_ok((1, 19, 4))
    assert not opencode.version_ok((1, 20, 0)) and not opencode.version_ok((2, 2, 5))


MODELS_TEXT = "\n".join(f"{key}\n{json.dumps(body, indent=2)}" for key, body in [
    ("opencode/nemotron-3.5-super-vl-free", {"name": "Nemotron 3.5 Super VL Free", "cost": {"input": 0, "output": 0},
                                             "capabilities": {"input": {"image": True}}, "variants": {}}),
    ("opencode/glm-5.3-flash-free", {"name": "GLM 5.3 Flash Free", "cost": {"input": 0, "output": 0},
                                     "capabilities": {"input": {"image": True}}}),
    ("opencode/big-paid-model", {"name": "Big", "cost": {"input": 3, "output": 15},
                                 "capabilities": {"input": {"image": True}}}),
    ("opencode/odd-cost", {"name": "Odd", "cost": {"input": 0}, "capabilities": {"input": {"image": False}}}),
    ("opencode/text-free", {"name": "Text Free", "cost": {"input": 0, "output": 0, "cache": {"read": 0, "write": 0}},
                            "capabilities": {"input": {"image": False}},
                            "variants": {"low": {}, "high": {}, "turbo": {}}}),
    ("nvidia/some/other-model", {"name": "Other", "cost": {"input": 0, "output": 0},
                                 "capabilities": {"input": {"image": True}}}),
    ("anthropic/claude-x", {"name": "Paid", "cost": {"input": 0, "output": 0}}),
]) + "\n"


def test_only_a_flipper_tool_repeated_is_allowed_without_the_card():
    """OpenCode's doom_loop for a tool of the bridge's own, in exactly the shape 1.19.5 sends."""
    def call(raw, title="doom_loop", kind="other"):
        return {"toolCallId": "c1", "title": title, "kind": kind, "status": "pending", "locations": [], "rawInput": raw}

    for tool in ("flipper_flipper_screen", "flipper_flipper_press"):
        assert opencode.flipper_doom_loop(call({"tool": tool, "input": {"keys": ["OK"]}}))
    for raw in ({"tool": "bash", "input": {"command": "ls"}}, {"tool": "flipper_screen", "input": {}},
                {"tool": "flipper_flipper_screenx", "input": {}}, {"tool": "flipper_flipper_screen ", "input": {}},
                {"tool": "mcp__flipper__flipper_screen", "input": {}}, {"tool": "flipper_flipper_screen", "input": "x"},
                {"tool": "flipper_flipper_screen"}, {"input": {}}, {"tool": ["flipper_flipper_screen"], "input": {}},
                {"tool": "flipper_flipper_screen", "input": {}, "command": "rm -rf ."}, "flipper_flipper_screen", None):
        assert not opencode.flipper_doom_loop(call(raw)), raw
    assert not opencode.flipper_doom_loop(call({"tool": "flipper_flipper_screen", "input": {}}, title="flipper_doom"))
    assert not opencode.flipper_doom_loop(call({"tool": "flipper_flipper_screen", "input": {}}, kind="execute"))


def test_the_models_whose_provider_caps_their_images(agent):
    """NVIDIA's hosted API refuses a request with 14 images or more, for its free model and Zen's route to it."""
    assert agent.image_limit("nvidia/nvidia/nemotron-3.5-super-vl-120b-a12b") == 13
    assert agent.image_limit(opencode.FREE["fallback"]) == 13
    assert agent.image_limit("opencode/text-free") is None and agent.image_limit("about") is None


def test_only_free_models_are_offered():
    rows = opencode.parse_models(MODELS_TEXT)
    assert len(rows) == 7 and rows[0]["key"] == "opencode/nemotron-3.5-super-vl-free"
    models = opencode.catalog(rows)
    by = {m["value"]: m for m in models}
    # NIM's from the curated list, the default first; Zen's free ones with cost 0; nothing else
    assert list(by) == ["nvidia/nvidia/nemotron-3.5-super-vl-120b-a12b", "opencode/nemotron-3.5-super-vl-free",
                        "opencode/text-free"]
    nim = by["nvidia/nvidia/nemotron-3.5-super-vl-120b-a12b"]
    assert (nim["available"], nim["reason"], nim["images"]) == (False, ModelReason.NOT_SIGNED_IN, True)
    zen = by["opencode/nemotron-3.5-super-vl-free"]
    assert (zen["available"], zen["reason"], zen["images"], zen["efforts"]) == (True, None, True, [])
    assert by["opencode/text-free"]["images"] is False and by["opencode/text-free"]["efforts"] == ["low", "high"]
    signed = rows + [{"key": "nvidia/nvidia/nemotron-3.5-super-vl-120b-a12b",
                      "model": {"name": "Nemotron 3.5 Super VL", "cost": {"input": 0, "output": 0},
                                "capabilities": {"input": {"image": True}}}}]
    nim = next(m for m in opencode.catalog(signed) if m["value"].startswith("nvidia/"))
    assert nim["available"] and opencode.providers(signed) == ["nvidia", "opencode"]  # signed in first, then Zen
    assert opencode.catalog([]) == [m for m in opencode.catalog([]) if not m["available"]]  # nothing listed: none taken


# ------------------------------------------------------------------ the agent behind the port
def fake_bin(folder: Path) -> Path:
    """An executable `opencode` that runs tests/fake_acp.py. The bridge gives it only an allow-listed environment, so
    its own switches come from a file next to it (fake_set), read by its wrapper."""
    home = folder / "fake-opencode"
    home.mkdir()
    script = home / "opencode"
    script.write_text(f'#!/bin/sh\nset -a\n. "{home}/env"\nset +a\nexec "{sys.executable}" -I "{FAKE}" "$@"\n')
    script.chmod(script.stat().st_mode | stat.S_IXUSR)
    fake_set(script, FAKE_OPENCODE_HOME=str(home), FAKE_OPENCODE_LOG=str(home / "runs.jsonl"))
    return script


def fake_set(script: Path, **values: str | None) -> None:
    """Set (or, with None, unset) the fake's switches: FAKE_OPENCODE_NVIDIA, _OPENROUTER, _VERSION, _GLOBAL."""
    path = script.parent / "env"
    kept = json.loads((script.parent / "env.json").read_text()) if (script.parent / "env.json").exists() else {}
    kept = {k: v for k, v in {**kept, **values}.items() if v is not None}
    (script.parent / "env.json").write_text(json.dumps(kept))
    path.write_text("".join(f"{k}={shlex.quote(v)}\n" for k, v in kept.items()))


def runs(script: Path) -> list[dict]:
    log = script.parent / "runs.jsonl"
    return [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []


@pytest.fixture
def agent(tmp_path, monkeypatch):
    folder = tmp_path / "agent"
    folder.mkdir()
    opencode.write_config(folder)
    config = Config(**CFG, agent_dir=str(folder), opencode_bin=str(fake_bin(tmp_path)))
    return opencode.OpenCode(config)


async def test_check_reads_version_catalog_and_login(agent, monkeypatch):
    assert agent.id == AgentId.OPENCODE and agent.remote is None and agent.hook_routes() is None
    assert agent.problem() == Problem.CHECKING
    await agent.check()
    assert agent.problem() is None and agent.identity() == {"name": "OpenCode", "version": "1.19.5"}
    assert agent.login() == {"method": "OpenCode Zen", "subscription": False}  # each provider by its name
    assert [o["value"] for o in agent.mode_options()] == [Mode.DEFAULT, Mode.ACCEPT_EDITS, Mode.PLAN]
    assert agent.capabilities() == {"remote": None, "attachments": True}
    script = Path(agent.config.opencode_bin)
    monkeypatch.setenv("NVIDIA_API_KEY", "nvapi-fake")  # never passed on: OpenCode keeps its own login
    fake_set(script, FAKE_OPENCODE_NVIDIA="1")
    await agent.check()
    assert agent.login()["method"] == "NVIDIA NIM, OpenCode Zen" and all(m["available"] for m in agent.models())
    fake_set(script, FAKE_OPENCODE_VERSION="2.2.5")
    await agent.check()
    assert agent.problem() == Problem.AGENT_VERSION
    fake_set(script, FAKE_OPENCODE_VERSION=None, FAKE_OPENCODE_GLOBAL=json.dumps({"provider": {"openai": {}}}))
    await agent.check()
    assert agent.problem() == Problem.UNSAFE_AGENT_SETTINGS  # the merged configuration is what counts


def request(**fields) -> TurnRequest:
    base = dict(session="3f2b8c1e-5d4a-4e6f-9b7c-2a1d0e9f8c7b", resume=False,
                model="opencode/nemotron-3.5-super-vl-free", effort=None, mode="default", rules=[],
                permission_timeout_s=60, text="hello")
    return TurnRequest(**{**base, **fields})


async def collect(turn) -> list:
    out = []
    async for events in turn.lines():
        out += events
    return out


async def test_a_turn_passes_the_token_through_the_pipe_only(agent):
    await agent.check()
    turn = await agent.start_turn(request(text="scenario:screen"))
    events = await collect(turn)
    await turn.wait(5)
    assert turn.outcome().outcome == Outcome.DONE
    started = events[0]
    assert started.session.startswith("ses_") and started.model == "opencode/nemotron-3.5-super-vl-free"
    assert started.guard is None and started.version == "1.19.5"
    result = next(e for e in events if isinstance(e, Output) and e.event["kind"] == "tool.result")
    assert result.flipper and result.seq == 1
    run = [r for r in runs(Path(agent.config.opencode_bin)) if r["argv"] == ["acp"]][-1]
    assert TOKEN not in json.dumps(run["argv"]) and "ZEFFIN_MCP_TOKEN" not in run["env"]
    assert not [n for n in run["env"] if n.startswith(("ANTHROPIC_", "OPENAI_"))]
    init = next(m for m in run["received"] if m.get("method") == "initialize")["params"]
    assert init["protocolVersion"] == 1 and init["clientInfo"] == {"name": "zeffin", "version": __version__}
    new = next(m for m in run["received"] if m.get("method") == "session/new")["params"]
    assert new["mcpServers"] == [{"type": "http", "name": "flipper", "url": "http://127.0.0.1:19435/mcp",
                                  "headers": [{"name": "Authorization", "value": f"Bearer {TOKEN}"}]}]


async def test_a_model_outside_the_list_is_killed_at_once(agent):
    await agent.check()
    turn = await agent.start_turn(request(model="opencode/big-paid-model"))
    events = await collect(turn)
    assert events[0].guard == Guard.MODEL


async def test_permission_requests_reach_the_broker_and_its_answer_comes_back(agent, tmp_path):
    await agent.check()
    asked: list[PermissionAsk] = []

    async def ask(a: PermissionAsk) -> PermissionAnswer:
        asked.append(a)
        return PermissionAnswer("deny")

    turn = await agent.start_turn(request(text="Use bash to touch denied.txt", ask=ask))
    await collect(turn)
    assert [(a.tool, a.input, a.rules, a.flipper) for a in asked] == [
        ("Bash", {"command": "touch denied.txt"}, ["Bash(touch denied.txt)"], False)]
    assert not (Path(agent.config.agent_dir) / "denied.txt").exists()
    reply = [m for r in runs(Path(agent.config.opencode_bin)) for m in r["received"] if "result" in m][-1]
    assert reply["result"] == {"outcome": {"outcome": "selected", "optionId": "reject"}}

    async def once(a: PermissionAsk) -> PermissionAnswer:
        return PermissionAnswer("once")

    turn = await agent.start_turn(request(text="Use bash to touch allowed.txt", ask=once))
    await collect(turn)
    assert (Path(agent.config.agent_dir) / "allowed.txt").exists()
    # A rule kept with "always" answers it without asking; Accept edits answers edits.
    turn = await agent.start_turn(request(text="Use bash to touch kept.txt", ask=ask, rules=["Bash(touch kept.txt)"]))
    await collect(turn)
    turn = await agent.start_turn(request(text="Edit notes.md", ask=ask, mode=Mode.ACCEPT_EDITS))
    await collect(turn)
    assert len(asked) == 1 and (Path(agent.config.agent_dir) / "notes.md").exists()


async def test_resume_loads_the_session_and_a_lost_one_has_expired(agent):
    await agent.check()
    turn = await agent.start_turn(request(text="remember the number 42"))
    sid = (await collect(turn))[0].session
    turn = await agent.start_turn(request(session=sid, resume=True, text="What number did I say?"))
    events = await collect(turn)
    texts = [e.event["text"] for e in events if isinstance(e, Output) and e.event["kind"] == "text"]
    assert texts == ["The number is 42."]  # the replayed history is not shown again
    assert agent.forget(sid) == 1
    await asyncio.sleep(0)
    turn = await agent.start_turn(request(session=sid, resume=True, text="again"))
    await collect(turn)
    assert turn.outcome().error == TurnError.EXPIRED


async def test_images_go_as_image_blocks(agent, tmp_path):
    await agent.check()
    png = tmp_path / "a.png"
    png.write_bytes(b"\x89PNG\r\n\x1a\n")
    turn = await agent.start_turn(request(text="look", images=[("image/png", png)]))
    events = await collect(turn)
    assert [e.event["text"] for e in events if isinstance(e, Output) and e.event["kind"] == "text"] == [
        "You sent 1 images."]


async def test_the_fake_crashing_is_agent_failed_and_its_stderr_stays_in_the_log(agent):
    await agent.check()
    turn = await agent.start_turn(request(text="scenario:crash"))
    await collect(turn)
    await turn.wait(5)
    end = turn.outcome()
    assert (end.outcome, end.error) == (Outcome.ERROR, TurnError.AGENT_FAILED) and end.note


def test_a_lost_session_reads_as_expired_in_both_shapes():
    # OpenCode 1.19.5 on a deleted or unknown session (the probe on a real server), and a plain message
    assert opencode._not_found(acp.AcpError(-32603, "Internal error: OpenCode service failure", {"service": "session"}))
    assert opencode._not_found(acp.AcpError(-32603, "Internal error", {"message": "Session not found: ses_1"}))
    assert not opencode._not_found(acp.AcpError(-32603, "Internal error: OpenCode service failure",
                                                {"service": "provider"}))
    assert not opencode._not_found(acp.AcpError(-32602, "Invalid params", {"service": "session"}))


def test_setup_writes_the_folder_and_never_reads_credentials(tmp_path):
    folder = tmp_path / "agent"
    folder.mkdir()
    assert opencode.write_agents_md(folder, "# Flipper Zero\n\n- Read flipper_firmware first.\n") is True
    assert (folder / "AGENTS.md").read_text() == "# Flipper Zero\n\n- Read flipper_firmware first.\n"
    (folder / "AGENTS.md").write_text("mine")
    assert opencode.write_agents_md(folder, "# other\n") is False and (folder / "AGENTS.md").read_text() == "mine"
    # OpenCode's credentials file is never named, so never read
    assert "auth.json" not in Path(opencode.__file__).read_text() + Path(acp.__file__).read_text()


# ------------------------------------------------------------------ the security review before 1.0.0
async def test_a_command_titled_like_a_flipper_tool_asks_and_is_never_allowed_by_the_bridge(agent):
    """M1: OpenCode 1.19.5 raises external_directory as kind other with the command as its title (the probe on a
    real server). The bridge never answers it by itself: it reaches the card, and a denial keeps the file unread."""
    await agent.check()
    asked: list[PermissionAsk] = []

    async def deny(a: PermissionAsk) -> PermissionAnswer:
        asked.append(a)
        return PermissionAnswer("deny")

    command = "flipper_flipper_x=1 cat ../zeffin-data/config.json"
    turn = await agent.start_turn(request(text=f"Use bash to run: {command}", ask=deny))
    events = await collect(turn)
    assert [(a.tool, a.flipper) for a in asked] == [(command, False)]
    run = [r for r in runs(Path(agent.config.opencode_bin)) if r["argv"] == ["acp"]][-1]
    assert run["ran"] == []
    replies = [m["result"]["outcome"] for m in run["received"] if isinstance(m.get("result"), dict)
               and "outcome" in m["result"]]
    assert replies == [{"outcome": "selected", "optionId": "reject"}]
    shown = [e.event for e in events if isinstance(e, Output) and e.event["kind"] == "tool"]
    assert all(not e["name"].startswith("mcp__flipper__") for e in shown)


async def test_always_is_answered_once_and_the_bridge_keeps_the_rule(agent):
    """M2: "Always" on the card grants its rule only. OpenCode's own allow_always would let any `echo …` run for the
    rest of the process, so the bridge answers allow_once and matches the granted rule itself during the turn."""
    await agent.check()
    asked: list[PermissionAsk] = []

    async def always(a: PermissionAsk) -> PermissionAnswer:
        asked.append(a)
        return PermissionAnswer("always", a.rules)

    text = "Use bash to run: echo alpha-one\nUse bash to run: echo beta-two\nUse bash to run: echo alpha-one"
    turn = await agent.start_turn(request(text=text, ask=always))
    await collect(turn)
    await turn.wait(5)
    assert [a.rules for a in asked] == [["Bash(echo alpha-one)"], ["Bash(echo beta-two)"]]
    run = [r for r in runs(Path(agent.config.opencode_bin)) if r["argv"] == ["acp"]][-1]
    assert run["ran"] == ["echo alpha-one", "echo beta-two", "echo alpha-one"]
    options = [m["result"]["outcome"].get("optionId") for m in run["received"] if isinstance(m.get("result"), dict)
               and "outcome" in m["result"]]
    assert options == ["once", "once", "once"] and "always" not in options


async def test_the_configuration_is_checked_again_when_a_turn_starts(agent):
    """L1, with free only on: a global file changed since the last check is refused before any turn runs, and the
    agent's problem says so until the next check."""
    await agent.check()
    script = Path(agent.config.opencode_bin)
    before = len(runs(script))
    fake_set(script, FAKE_OPENCODE_GLOBAL=json.dumps({"agent": {"general": {"model": "opencode/big-paid-model"}}}))
    turn = await agent.start_turn(request())
    assert await collect(turn) == []
    end = turn.outcome()
    assert (end.outcome, end.error, end.data) == (Outcome.ERROR, TurnError.BILLING_GUARD, {"why": Guard.MODEL})
    assert not [r for r in runs(script)[before:] if r["argv"] == ["acp"]]  # no agent process started
    assert agent.problem() == Problem.UNSAFE_AGENT_SETTINGS
    fake_set(script, FAKE_OPENCODE_GLOBAL=None, FAKE_OPENCODE_VERSION="1.20.1")
    turn = await agent.start_turn(request())
    await collect(turn)
    assert turn.outcome().error == TurnError.AGENT_FAILED and agent.problem() == Problem.AGENT_VERSION
    assert turn.outcome().data["problem"] == Problem.AGENT_VERSION  # turn.end says why, as the log does


async def test_a_session_moved_to_another_model_during_the_turn_is_killed(agent):
    """L1: a config_option_update to another model ends the turn at once."""
    await agent.check()
    turn = await agent.start_turn(request(text="scenario:switch-model"))
    await collect(turn)
    assert await turn.wait(5)
    end = turn.outcome()
    assert (end.outcome, end.error, end.data) == (Outcome.ERROR, TurnError.BILLING_GUARD, {"why": Guard.MODEL})


# ------------------------------------------------------------------ the security scan before 1.0.0
def test_a_permission_that_skips_the_card_is_refused_whatever_the_switch(tmp_path):
    """The bridge owns the permission flow: no configuration may allow what its opencode.json asks for, at the top
    level, per agent or per mode, itself or through a wildcard (the last matching rule wins). The user's other rules
    (reads, their own MCP servers' tools, denials) stay theirs."""
    for free_only in (True, False):
        opencode.write_config(tmp_path, free_only)
        written = json.loads((tmp_path / "opencode.json").read_text())
        ask = written["permission"]
        assert opencode.config_problem(written, free_only) is None
        for change in ({"permission": "allow"}, {"permission": {**ask, "bash": "allow"}},
                       {"permission": {**ask, "*": "allow"}}, {"permission": {**ask, "e?it": "allow"}},
                       {"permission": {**ask, "web*": "allow"}},
                       {"permission": {**ask, "bash": {"git status": "allow", "*": "ask"}}},
                       {"permission": {**ask, "external_directory": "allow"}},
                       {"permission": {k: v for k, v in ask.items() if k != "edit"}}, {"permission": None},
                       {"permission": {**ask, "bash": "sometimes"}}, {"permission": ["bash"]},
                       {"agent": {"build": {"permission": {"edit": "allow"}}}},
                       {"agent": {"general": {"permission": "allow"}}},
                       {"mode": {"build": {"permission": {"bash": {"*": "allow"}}}}},
                       {"tools": {"bash": True}}, {"agent": {"build": {"tools": {"webfetch": True}}}},
                       {"agent": {"build": "x"}}, {"mode": []}):
            problem = opencode.config_problem({**written, **change}, free_only)
            assert problem, (free_only, change)
            assert "zf" in problem or "remove" in problem or "not" in problem
        for fine in ({"permission": {**ask, "read": "allow", "github_*": "allow", "doom_loop": "allow"}},
                     {"permission": {**ask, "bash": {"rm *": "deny", "*": "ask"}, "edit": "deny"}},
                     {"agent": {"build": {"permission": {"edit": "deny", "read": "allow"}, "prompt": "x"}}},
                     {"tools": {"bash": False, "github_search": True}}):
            assert opencode.config_problem({**written, **fine}, free_only) is None, (free_only, fine)


def test_an_edit_states_its_paths_as_opencode_sends_them(tmp_path):
    """OpenCode 1.19.5's "edit" permission (acp/permission.ts): the path in locations and in rawInput's filepath;
    apply_patch joins the relative paths in filepath and lists each file in files."""
    edit = {"kind": "edit", "title": "/a/x.py", "locations": [{"path": "/a/x.py"}],
            "rawInput": {"filepath": "/a/x.py", "diff": "+x"}}
    assert opencode.request_paths(edit) == ["/a/x.py", "/a/x.py"]
    patch = {"kind": "edit", "title": "2 files", "locations": [{"path": "/a/x.py"}, {"path": "/a/y.py"}],
             "rawInput": {"filepath": "x.py, y.py", "diff": "+x", "files": [
                 {"filePath": "/a/x.py", "relativePath": "x.py"},
                 {"filePath": "/a/y.py", "relativePath": "z.py", "movePath": "/a/z.py"}]}}
    assert opencode.request_paths(patch) == ["/a/x.py", "/a/y.py", "/a/x.py", "/a/y.py", "/a/z.py"]
    assert opencode.rule_for(patch) is None  # "always" would cover more than the one path on the card
    assert opencode.rule_for(edit) == "Edit(/a/x.py)"
    for unclear in ({"kind": "edit", "title": "x", "locations": [], "rawInput": {}},
                    {"kind": "edit", "locations": [{"path": ""}], "rawInput": {"filepath": "/a/x.py"}},
                    {"kind": "edit", "locations": [{"uri": "/a"}], "rawInput": {}},
                    {"kind": "edit", "locations": [], "rawInput": {"filepath": "/a/x.py "}},
                    {"kind": "edit", "locations": [], "rawInput": {"files": [{"relativePath": "x.py"}]}},
                    {"kind": "edit", "locations": [], "rawInput": {"files": "x.py"}}):
        assert opencode.request_paths(unclear) is None, unclear
        assert opencode.guards(unclear, str(tmp_path)), unclear
    assert not opencode.guards({**edit, "kind": "read", "locations": []}, str(tmp_path))  # reading is not guarded
    assert opencode.rule_for({"kind": "execute", "rawInput": {"command": "cat AGENTS.md"}}) is None
    assert opencode.rule_for({"kind": "execute", "rawInput": {"command": "echo x > Opencode.json"}}) is None


async def test_the_agents_own_files_always_go_through_the_card(agent):
    """In every mode, and whatever "always" granted before or during the turn, an edit of opencode.json, .opencode/,
    AGENTS.md, CLAUDE.md or .claude/ reaches the card, without "always"; Accept edits still answers other edits."""
    await agent.check()
    folder = Path(agent.config.agent_dir)
    asked: list[PermissionAsk] = []

    async def deny(a: PermissionAsk) -> PermissionAnswer:
        asked.append(a)
        return PermissionAnswer("deny")

    before = (folder / "opencode.json").read_text()
    for name in ("opencode.json", "OPENCODE.JSON", ".opencode/plugin/x.js", "AGENTS.md", "CLAUDE.md",
                 ".claude/settings.json", "sub/../opencode.jsonc"):
        rule = f"Edit({folder / name})"
        turn = await agent.start_turn(request(text=f"Edit {name}", ask=deny, mode=Mode.ACCEPT_EDITS, rules=[rule]))
        await collect(turn)
        assert asked and asked[-1].tool == "Edit" and asked[-1].rules == [], name
        assert asked[-1].input == {"filepath": str(folder / name), "diff": f"--- {folder / name}\n+++ "
                                   f"{folder / name}\n+x\n"}  # the request whole, as OpenCode sends it
    assert len(asked) == 7 and (folder / "opencode.json").read_text() == before
    assert not (folder / ".opencode").exists() and not (folder / "AGENTS.md").exists()
    # A patch that touches one of them among others asks too; one that does not is Accept edits'
    turn = await agent.start_turn(request(text="Patch a.py opencode.json", ask=deny, mode=Mode.ACCEPT_EDITS))
    await collect(turn)
    assert len(asked) == 8 and asked[-1].rules == [] and not (folder / "a.py").exists()
    turn = await agent.start_turn(request(text="Patch a.py b.py", ask=deny, mode=Mode.ACCEPT_EDITS))
    await collect(turn)
    assert len(asked) == 8 and (folder / "a.py").exists() and (folder / "b.py").exists()
    # A rule for one file never answers a patch of several
    turn = await agent.start_turn(request(text="Patch c.py d.py", ask=deny, rules=[f"Edit({folder / 'c.py'})"]))
    await collect(turn)
    assert len(asked) == 9 and asked[-1].rules == [] and not (folder / "c.py").exists()


async def test_always_granted_during_the_turn_never_covers_a_guarded_file(agent):
    await agent.check()
    folder = Path(agent.config.agent_dir)
    asked: list[PermissionAsk] = []

    async def always(a: PermissionAsk) -> PermissionAnswer:
        asked.append(a)
        return PermissionAnswer("always", [f"Edit({folder / 'AGENTS.md'})"])  # even a rule the card never offered

    turn = await agent.start_turn(request(text="Edit AGENTS.md", ask=always))
    await collect(turn)
    assert [a.rules for a in asked] == [[]] and turn.granted == [f"Edit({folder / 'AGENTS.md'})"]
    # the rule is kept, but a guarded path never matches it: the next turn asks again
    turn = await agent.start_turn(request(text="Edit AGENTS.md", ask=always, rules=turn.granted))
    await collect(turn)
    assert len(asked) == 2


async def test_a_permission_in_the_merged_configuration_is_refused_when_a_turn_starts(tmp_path):
    """With free only off too: a global file that allows edits (OpenCode merges it deep with the folder's) refuses the
    turn before any agent process starts, and the agent's problem says so."""
    folder = tmp_path / "agent"
    folder.mkdir()
    opencode.write_config(folder, free_only=False)
    agent = opencode.OpenCode(Config(**{**CFG, "free_only": False}, agent_dir=str(folder),
                                     opencode_bin=str(fake_bin(tmp_path))))
    await agent.check()
    assert agent.problem() is None
    script = Path(agent.config.opencode_bin)
    for change in ({"permission": {"*": "allow"}}, {"agent": {"build": {"permission": {"edit": "allow"}}}}):
        fake_set(script, FAKE_OPENCODE_GLOBAL=json.dumps(change))
        before = len(runs(script))
        turn = await agent.start_turn(request())
        assert await collect(turn) == []
        end = turn.outcome()
        assert (end.outcome, end.error) == (Outcome.ERROR, TurnError.AGENT_FAILED), change
        assert "opencode refused: its configuration the permission of" in end.note
        assert not [r for r in runs(script)[before:] if r["argv"] == ["acp"]]
        assert agent.problem() == Problem.UNSAFE_AGENT_SETTINGS
    fake_set(script, FAKE_OPENCODE_GLOBAL=None)
    await agent.check()
    turn = await agent.start_turn(request())
    await collect(turn)
    assert turn.outcome().outcome == Outcome.DONE


async def test_a_long_request_goes_whole_and_one_past_the_bound_can_only_be_denied(agent):
    """S1b: OpenCode's request reaches the broker whole up to the bound (the diff far past 2,000 characters); past it,
    cut and not whole, with no rule to grant. Accept edits still answers edits itself, whatever their size: that mode
    is the owner's choice not to be asked."""
    from bridge.chat.stream import PERMISSION_INPUT_LIMIT

    await agent.check()
    folder = Path(agent.config.agent_dir)
    asked: list[PermissionAsk] = []

    async def deny(a: PermissionAsk) -> PermissionAnswer:
        asked.append(a)
        return PermissionAnswer("deny")

    turn = await agent.start_turn(request(text="Write a.txt with 20000 characters", ask=deny))
    await collect(turn)
    assert asked[-1].whole and len(asked[-1].input["diff"]) > 20_000 and asked[-1].rules == [f"Edit({folder / 'a.txt'})"]
    n = PERMISSION_INPUT_LIMIT + 10
    turn = await agent.start_turn(request(text=f"Write b.txt with {n} characters", ask=deny))
    await collect(turn)
    assert len(asked) == 2 and not asked[-1].whole and asked[-1].rules == []
    assert len(asked[-1].input["diff"]) < 2100 and not (folder / "b.txt").exists()
    turn = await agent.start_turn(request(text=f"Write c.txt with {n} characters", ask=deny, mode=Mode.ACCEPT_EDITS))
    await collect(turn)
    assert len(asked) == 2 and (folder / "c.txt").exists()


async def test_a_refused_turn_says_why_and_a_fixed_setting_works_at_the_next_message(tmp_path):
    """A real probe: a turn refused at its start for the agent's settings carries the problem and the log's
    reason in turn.end (the app words it); once the owner fixes the setting, the next message checks again and goes
    through, without waiting for the 10-minute check or a restart."""
    from bridge.chat.attachments import Store
    from bridge.chat.bus import ChatBus
    from bridge.chat.history import History
    from bridge.chat.service import ChatService
    from bridge.chat.tasks import Tasks

    folder = tmp_path / "agent"
    folder.mkdir()
    opencode.write_config(folder, free_only=False)
    config = Config(**{**CFG, "free_only": False}, agent_dir=str(folder), opencode_bin=str(fake_bin(tmp_path)))
    agent = opencode.OpenCode(config)
    await agent.check()
    script = Path(config.opencode_bin)
    fake_set(script, FAKE_OPENCODE_GLOBAL=json.dumps({"permission": {"*": "allow"}}))
    turn = await agent.start_turn(request())
    end = turn.outcome()
    assert end.error == TurnError.AGENT_FAILED and end.data["problem"] == Problem.UNSAFE_AGENT_SETTINGS
    assert end.data["reason"].startswith("its configuration the permission of the configuration allows *")
    assert agent.problem() == Problem.UNSAFE_AGENT_SETTINGS

    statuses: list = []
    service = ChatService(config, [agent], tmp_path / "state", ChatBus(History(None)), Store(tmp_path / "state"), Tasks(),
                          status_changed=lambda: statuses.append(1))
    c = service.index.new(agent=AgentId.OPENCODE)
    await service.recheck(c.id)  # still unsafe: checked again, still refused
    assert agent.problem() == Problem.UNSAFE_AGENT_SETTINGS and len(statuses) == 1
    fake_set(script, FAKE_OPENCODE_GLOBAL=None)  # the owner removes the rule
    await service.recheck(c.id)
    assert agent.problem() is None and service.problem(AgentId.OPENCODE) is None and len(statuses) == 2
    before = len(runs(script))
    await service.recheck(c.id)  # ready: nothing to look at again
    await service.recheck("no-such-conversation")
    assert len(runs(script)) == before and len(statuses) == 2


def test_a_patch_joined_in_one_location_is_several_paths(tmp_path):
    """A real probe: OpenCode 1.19.5 sent an apply_patch over two files as one location, "a.py, b.py". It
    grants no rule (one rule would cover both), and a guarded file joined with another is still guarded."""
    joined = {"kind": "edit", "title": "a.py, b.py", "locations": [{"path": "a.py, b.py"}],
              "rawInput": {"filepath": "a.py, b.py", "diff": "+x"}}
    assert opencode.rule_for(joined) is None
    assert set(opencode.request_paths(joined) or []) >= {"a.py", "b.py"}
    assert not opencode.guards(joined, str(tmp_path))
    guarded = {**joined, "locations": [{"path": "a.py, opencode.json"}], "rawInput": {"filepath": "a.py, opencode.json"}}
    assert opencode.guards(guarded, str(tmp_path)) and opencode.rule_for(guarded) is None
    assert opencode.rule_for({"kind": "edit", "title": "2 files", "locations": [], "rawInput": {}}) is None


def test_always_covers_one_path_and_never_a_move():
    """Security scan before 1.0.0, F2: the rule came from the locations alone, so an apply_patch that moves x.py
    (rawInput.files' movePath) matched a rule granted for x.py and ran with no card. The rule now comes from every path
    the request names: one, and not a move, or none."""
    one = {"kind": "edit", "title": "x.py", "locations": [{"path": "/a/x.py"}],
           "rawInput": {"filepath": "/a/x.py", "diff": "+x"}}
    assert opencode.rule_for(one) == "Edit(/a/x.py)"
    move = {"kind": "edit", "title": "x.py", "locations": [{"path": "/a/x.py"}],
            "rawInput": {"filepath": "x.py", "diff": "+x", "files": [{"filePath": "/a/x.py", "relativePath": "y.py",
                                                                      "movePath": "/a/y.py"}]}}
    assert opencode.rule_for(move) is None
    in_place = {**move, "rawInput": {"filepath": "x.py", "files": [{"filePath": "/a/x.py", "relativePath": "x.py"}]}}
    assert opencode.rule_for(in_place) == "Edit(/a/x.py)"
    two = {**one, "rawInput": {"filepath": "x.py, z.py", "files": [{"filePath": "/a/x.py"}, {"filePath": "/a/z.py"}]}}
    assert opencode.rule_for(two) is None
    assert opencode.rule_for({**one, "rawInput": {"filepath": "/a/other.py"}}) is None  # the location and the input differ
    assert opencode.rule_for({**one, "locations": [{"path": ""}]}) is None  # a path not stated clearly
    # a request with no path keeps its title (kind other), and a command its command
    assert opencode.rule_for({"kind": "other", "title": "my_tool", "locations": [], "rawInput": {}}) == "my_tool(my_tool)"
    assert opencode.rule_for({"kind": "execute", "locations": [{"path": "/a"}], "rawInput": {"command": "ls"}}) == "Bash(ls)"
