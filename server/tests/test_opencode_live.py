# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""OpenCode end to end: the real server in a subprocess, the fake `opencode` (tests/fake_acp.py)
beside the fake claude, and /app over a real socket. A conversation with OpenCode runs its turns over ACP, asks its
permissions through the app's card, goes on with its session and is deleted with OpenCode's own command."""
import json
import os
from pathlib import Path

import pytest

from bridge import config as cfg
from bridge.chat import opencode
from conftest import free_port
from test_chat_live import Live, events
from test_opencode import fake_bin, fake_set, runs


@pytest.fixture
def server(tmp_path, fake_claude):
    path = tmp_path / "config.json"
    cfg.create(path, "127.0.0.1", free_port(), free_port(), tmp_path / "state")
    agent = tmp_path / "agent"
    agent.mkdir()
    opencode.write_config(agent)
    c = cfg.load(path)
    script = fake_bin(tmp_path)
    c.agent_dir, c.claude_bin, c.opencode_bin = str(agent), str(fake_claude), str(script)
    c.free_only = True  # the guards' tests run with the switch on
    cfg.save(path, c)
    live = Live(tmp_path, fake_claude, dict(os.environ))
    live.script = script
    yield live
    if live.proc.poll() is None:
        live.close()


def ready(server: Live) -> dict:
    """chat.status once OpenCode is checked and ready."""
    for _ in range(50):
        server.send("chat.list")
        status = server.until(lambda m: m["type"] == "chat.status")
        entry = next(a for a in status["agents"] if a["id"] == "opencode")
        if entry["ready"]:
            return status
    raise AssertionError(f"OpenCode never became ready: {entry['problem']}")


def texts(messages: list[dict]) -> list[str]:
    return [m["text"] for m in events(messages, "text")]


def test_a_conversation_with_opencode(server):
    status = ready(server)
    entry = next(a for a in status["agents"] if a["id"] == "opencode")
    assert entry["version"] == "1.19.5" and entry["login"] == {"method": "OpenCode Zen", "subscription": False}
    assert entry["remote"] is None and [m["value"] for m in entry["models"] if m["available"]] == [
        "opencode/nemotron-3.5-super-vl-free", "opencode/text-free"]
    cid = server.new(agent="opencode")
    server.send("chat.list")
    listed = server.until(lambda m: m["type"] == "chat.conversations")["items"]
    item = next(c for c in listed if c["id"] == cid)
    assert (item["agent"], item["model"], item["mode"]) == ("opencode", "opencode/nemotron-3.5-super-vl-free",
                                                            "default")

    # The Flipper's screen as a tool result: the bridge's own tool, with its image
    server.send("chat.send", conversation=cid, text="scenario:screen")
    messages = server.collect(cid)
    info = events(messages, "turn.info")[0]
    assert info["model"] == "opencode/nemotron-3.5-super-vl-free" and info["version"] == "1.19.5"
    tool, result = events(messages, "tool")[0], events(messages, "tool.result")[0]
    assert tool["name"] == "mcp__flipper__flipper_screen" and result["images"] == 1 and not result["error"]
    assert texts(messages) == ["I see the main menu."] and events(messages, "turn.end")[0]["outcome"] == "done"

    # A permission asked inside ACP is the app's card; "always" keeps a rule that answers it next time
    server.send("chat.send", conversation=cid, text="Use bash to touch asked.txt")
    ask = server.until(lambda m: m["type"] == "chat.event" and m["kind"] == "permission")
    assert (ask["tool"], ask["input"], ask["rules"]) == ("Bash", {"command": "touch asked.txt"},
                                                         ["Bash(touch asked.txt)"])
    server.send("chat.permission.answer", id=ask["id"], answer="always", rules=ask["rules"])
    messages = server.collect(cid)
    assert events(messages, "permission.end")[0]["answer"] == "always"
    assert (server.agent / "asked.txt").exists() and texts(messages) == ["Done: asked.txt."]
    (server.agent / "asked.txt").unlink()
    server.send("chat.send", conversation=cid, text="Use bash to touch asked.txt")
    messages = server.collect(cid)
    assert not events(messages, "permission") and (server.agent / "asked.txt").exists()

    # Denied: the tool fails, the turn ends
    server.send("chat.send", conversation=cid, text="Use bash to touch denied.txt")
    ask = server.until(lambda m: m["type"] == "chat.event" and m["kind"] == "permission")
    server.send("chat.permission.answer", id=ask["id"], answer="deny")
    messages = server.collect(cid)
    assert events(messages, "tool.result")[0]["error"] and not (server.agent / "denied.txt").exists()

    # The next turn goes on with the same session (session/load)
    server.send("chat.send", conversation=cid, text="remember the number 7")
    server.collect(cid)
    server.send("chat.send", conversation=cid, text="What number did I say?")
    assert texts(server.collect(cid)) == ["The number is 7."]
    acp_runs = [r for r in runs(server.script) if r["argv"] == ["acp"]]
    loaded = [m["params"]["sessionId"] for r in acp_runs for m in r["received"] if m.get("method") == "session/load"]
    assert len(acp_runs) == 6 and len(set(loaded)) == 1 and loaded[0].startswith("ses_")
    for run in acp_runs:  # the token only through the pipe; no paid provider's variable
        assert "ZEFFIN_MCP_TOKEN" not in run["env"] and not [n for n in run["env"] if n.startswith(
            ("ANTHROPIC_", "OPENAI_", "CLAUDE_"))]
        opened = next(m["params"] for m in run["received"] if m.get("method") in ("session/new", "session/load"))
        assert opened["mcpServers"][0]["headers"] == [{"name": "Authorization",
                                                       "value": f"Bearer {server.c.mcp_token}"}]

    # Only its agent's choices; no Remote Control
    for change, code in (({"model": "opus"}, "bad_model"), ({"model": "opencode/big-paid-model"}, "bad_model"),
                         ({"mode": "auto"}, "bad_mode")):
        server.send("chat.set", conversation=cid, **change)
        assert server.until(lambda m: m["type"] == "error")["error"] == code, change
    server.send("chat.remote", conversation=cid, on=True)
    assert server.until(lambda m: m["type"] == "error")["error"] == "remote_unsupported"

    # Deleted with OpenCode's own command
    server.send("chat.delete", conversation=cid)
    server.until(lambda m: m["type"] == "chat.deleted" and m["conversation"] == cid)
    deleted = [r for r in runs(server.script) if r["argv"][:2] == ["session", "delete"]]
    assert [r["argv"][2] for r in deleted] == [loaded[0]]
    assert not (server.script.parent / "sessions" / f"{loaded[0]}.json").exists()


def test_nim_signed_in_is_the_default_and_a_lost_session_has_expired(server):
    fake_set(server.script, FAKE_OPENCODE_NVIDIA="1")
    server.send("chat.list")
    server.until(lambda m: m["type"] == "chat.status")
    server.close()
    server = Live(server.folder, Path(server.c.claude_bin), dict(os.environ))  # checked again at start
    server.script = Path(server.c.opencode_bin)
    status = ready(server)
    entry = next(a for a in status["agents"] if a["id"] == "opencode")
    assert entry["login"]["method"] == "NVIDIA NIM, OpenCode Zen"
    assert status["settings"]["agents"]["opencode"]["default_model"] == "nvidia/nvidia/nemotron-3.5-super-vl-120b-a12b"
    cid = server.new(agent="opencode")
    server.send("chat.send", conversation=cid, text="hello")
    assert texts(server.collect(cid)) == ["You said: hello"]
    for path in (server.script.parent / "sessions").glob("*.json"):
        path.unlink()  # OpenCode lost it
    server.send("chat.send", conversation=cid, text="again")
    end = events(server.collect(cid), "turn.end")[0]
    assert (end["outcome"], end["error"]) == ("error", "expired")
    server.close()
    data = json.loads((server.folder / "state" / "conversations.json").read_text())
    saved = next(c for c in data["conversations"] if c["id"] == cid)
    assert saved["agent"] == "opencode" and saved["agent_session"].startswith("ses_") and saved["state"] == "expired"


def test_interrupting_cancels_the_prompt(server):
    ready(server)
    cid = server.new(agent="opencode")
    server.send("chat.send", conversation=cid, text="Count from 1 to 50")
    server.until(lambda m: m["type"] == "chat.delta" and m["conversation"] == cid)
    server.send("chat.interrupt", conversation=cid)
    end = events(server.collect(cid), "turn.end")[0]
    assert end["outcome"] == "interrupted" and end["duration_ms"] < 8000
    run = [r for r in runs(server.script) if r["argv"] == ["acp"]][-1]
    assert any(m.get("method") == "session/cancel" for m in run["received"])


def test_zf_agent_setup_opencode(tmp_path, monkeypatch, capsys):
    from bridge import cli

    path = tmp_path / "config.json"
    cfg.create(path, "127.0.0.1", free_port(), free_port(), tmp_path / "state")
    c = cfg.load(path)
    c.free_only = True
    cfg.save(path, c)
    script = fake_bin(tmp_path)
    monkeypatch.setenv("PATH", f"{script.parent}:/usr/bin:/bin")
    monkeypatch.setenv("HOME", str(tmp_path / "home"))
    monkeypatch.setattr(cli, "_service", lambda: None)
    folder = tmp_path / "zeffin-agent"
    assert cli.main(["--config", str(path), "agent", "setup", "--agent", "opencode", "--dir", str(folder)]) == 0
    c = cfg.load(path)
    assert c.opencode_bin == str(script) and c.agent_dir == str(folder.resolve())
    agents_md = (folder / "AGENTS.md").read_text()
    assert agents_md.startswith("# Flipper Zero") and "Copy this file" not in agents_md
    assert opencode.config_problem(json.loads((folder / "opencode.json").read_text())) is None
    out = capsys.readouterr().out
    assert "NVIDIA NIM is not signed in" in out and "opencode/nemotron-3.5-super-vl-free" in out
    # A global configuration that would undo a guard is refused; a version outside the range too
    fake_set(script, FAKE_OPENCODE_GLOBAL=json.dumps({"small_model": "openai/gpt-x"}))
    assert cli.main(["--config", str(path), "agent", "setup", "--agent", "opencode"]) == 1
    assert "Refused" in capsys.readouterr().out
    fake_set(script, FAKE_OPENCODE_GLOBAL=None, FAKE_OPENCODE_VERSION="2.2.5")
    assert cli.main(["--config", str(path), "agent", "setup", "--agent", "opencode"]) == 1
    assert "1.19.5" in capsys.readouterr().out


def test_zf_status_shows_both_agents(server):
    from bridge.report import describe_chat
    from bridge.cli import fetch_status

    ready(server)
    data, _ = fetch_status(server.c)
    agents = data["chat"]["agents"]
    assert [a["id"] for a in agents] == ["claude-code", "opencode"]
    assert agents[1] == {"id": "opencode", "name": "OpenCode", "version": "1.19.5", "configured": True,
                         "problem": None, "login": "OpenCode Zen", "model": "opencode/nemotron-3.5-super-vl-free"}
    lines = describe_chat(data["chat"])
    assert "OpenCode: 1.19.5 · OpenCode Zen · model opencode/nemotron-3.5-super-vl-free" in lines


def test_zf_status_off_shows_the_signed_in_providers_default(tmp_path, fake_claude):
    """Free only off, OpenRouter signed in and NIM not: the default is OpenRouter's first model, its login names it
    first, and no NIM row is sent."""
    from bridge.cli import fetch_status

    path = tmp_path / "config.json"
    cfg.create(path, "127.0.0.1", free_port(), free_port(), tmp_path / "state")
    agent = tmp_path / "agent"
    agent.mkdir()
    opencode.write_config(agent, False)
    c = cfg.load(path)
    script = fake_bin(tmp_path)
    fake_set(script, FAKE_OPENCODE_OPENROUTER="1")
    c.agent_dir, c.claude_bin, c.opencode_bin = str(agent), str(fake_claude), str(script)
    cfg.save(path, c)
    live = Live(tmp_path, fake_claude, dict(os.environ), free_only=False)
    try:
        status = ready(live)
        entry = next(a for a in status["agents"] if a["id"] == "opencode")
        assert status["free_only"] is False and entry["login"]["method"] == "OpenRouter, OpenCode Zen"
        assert [m["value"] for m in entry["models"]][0] == "openrouter/example/coder-large"
        assert not [m for m in entry["models"] if m["value"].startswith("nvidia/")]
        assert status["settings"]["agents"]["opencode"]["default_model"] == "openrouter/example/coder-large"
        data, _ = fetch_status(live.c)
        assert data["chat"]["agents"][1]["model"] == "openrouter/example/coder-large"
    finally:
        live.close()


def test_zf_agent_probe_opencode_uses_a_throwaway_bridge(tmp_path, monkeypatch, capsys):
    from bridge import cli

    path = tmp_path / "config.json"
    cfg.create(path, "127.0.0.1", free_port(), free_port(), tmp_path / "state")
    script = fake_bin(tmp_path)
    c = cfg.load(path)
    c.opencode_bin = str(script)
    cfg.save(path, c)
    assert cli.main(["--config", str(path), "agent", "probe", "--agent", "opencode", "--json"]) in (0, 1)
    report = json.loads(capsys.readouterr().out)
    assert report["scrubbed"] is True and report["version"]["supported"] and report["config"]["guards_hold"]
    assert report["permission_rejected"]["asked"] == 1 and not report["permission_rejected"]["file_created"]
    assert report["permission_allowed"]["file_created"] and report["delete"]["reads_as_expired"]
    assert c.mcp_token not in json.dumps(report) and str(tmp_path) not in json.dumps(report)
    assert not list((tmp_path / "state").glob("probe-opencode-*"))  # nothing left behind
    assert report["sessions_left"] == 0 and not list((script.parent / "sessions").glob("*.json"))  # every one it opened


def test_zf_agent_probe_opencode_notes_steps_report_counts_and_flags_only(tmp_path, capsys):
    """--notes adds steps 7 and 8 on the simulator's Dial. The fake agent calls no tool, so nothing is learned or
    saved and the notes are not ok; the report holds no text of the model's or the note's."""
    from bridge import cli

    path = tmp_path / "config.json"
    cfg.create(path, "127.0.0.1", free_port(), free_port(), tmp_path / "state")
    script = fake_bin(tmp_path)
    c = cfg.load(path)
    c.opencode_bin = str(script)
    cfg.save(path, c)
    assert cli.main(["--config", str(path), "agent", "probe", "--agent", "opencode", "--notes", "--json"]) == 0
    out = capsys.readouterr()
    report = json.loads(out.out)
    assert "7/8 notes" in out.err and "8/8 notes" in out.err
    notes = report["notes"]
    assert set(notes) == {"learn", "saved_when_asked", "use", "ok"} and notes["ok"] is False and report["ok"] is False
    assert notes["learn"]["note_saved"] is False and notes["saved_when_asked"]["note_saved"] is False
    assert notes["use"]["told_code"] is False and notes["use"]["keys"] == 0
    assert all(isinstance(v, (bool, int, float, type(None))) or v in ("done", "failed", "cancelled")
               for step in (notes["learn"], notes["use"]) for k, v in step.items() if k not in ("outcome", "error"))
    assert "sim_dial" not in json.dumps(report) and "AMBER" not in json.dumps(report) and report["scrubbed"] is True
    assert report["sessions_left"] == 0


def compacted(messages: list[dict]) -> int:
    return sum(1 for n in events(messages, "notice") if n["code"] == "compacted")


def test_a_conversation_past_the_models_images_is_compacted(server):
    """NVIDIA's free model refuses a request with 14 images or more, and OpenCode sends the whole session each
    time. The bridge has OpenCode summarize the session (/compact, with compaction.tail_turns 0) before a turn that
    starts with half the limit, and after a turn that failed past it, which ends with image_limit."""
    ready(server)
    cid = server.new(agent="opencode")
    server.send("chat.send", conversation=cid, text="scenario:screens 7")
    messages = server.collect(cid)
    assert sum(r["images"] for r in events(messages, "tool.result")) == 7 and not compacted(messages)
    assert events(messages, "turn.end")[0]["outcome"] == "done"

    # 7 more would make 14: the session holds half the limit, so it is summarized first; its text is not shown
    server.send("chat.send", conversation=cid, text="scenario:screens 7")
    messages = server.collect(cid)
    kinds = [m["kind"] for m in events(messages)]
    assert compacted(messages) == 1 and kinds.index("notice") < kinds.index("tool")
    assert events(messages, "turn.end")[0]["outcome"] == "done" and texts(messages) == ["done"]

    # 14 in one turn: summarized first again, then past the limit; summarized after it, and the turn says so
    server.send("chat.send", conversation=cid, text="scenario:screens 14")
    messages = server.collect(cid, timeout=30)
    end = events(messages, "turn.end")[0]
    assert (end["outcome"], end["error"]) == ("error", "image_limit") and compacted(messages) == 2
    assert end["data"] == {"images": 14, "limit": 13, "compacted": True}
    server.send("chat.send", conversation=cid, text="remember the number 7")
    messages = server.collect(cid)
    assert events(messages, "turn.end")[0]["outcome"] == "done" and not compacted(messages)

    # A summary that fails: the turn says to start a new conversation, and the next one tries again first
    fake_set(server.script, FAKE_OPENCODE_NO_COMPACT="1")
    server.send("chat.send", conversation=cid, text="scenario:screens 14")
    end = events(server.collect(cid, timeout=30), "turn.end")[0]
    assert (end["error"], end["data"]) == ("image_limit", {"images": 14, "limit": 13, "compacted": False})
    server.send("chat.send", conversation=cid, text="What number")
    end = events(server.collect(cid, timeout=30), "turn.end")[0]
    assert (end["error"], end["data"]["compacted"]) == ("image_limit", False)  # the session stays past the limit
    fake_set(server.script, FAKE_OPENCODE_NO_COMPACT=None)
    server.send("chat.send", conversation=cid, text="What number")
    messages = server.collect(cid)
    assert compacted(messages) == 1 and events(messages, "turn.end")[0]["outcome"] == "done"
    saved = json.loads((server.folder / "state" / "conversations.json").read_text())
    assert next(c for c in saved["conversations"] if c["id"] == cid)["images"] == 0


def test_a_flipper_tool_repeated_goes_on_and_any_other_asks(server):
    """OpenCode asks doom_loop when a tool runs 3 times in a row with the same input: for a Flipper tool the bridge
    answers it itself, without a card or a rule; for anything else, or a request it does not recognize, the card."""
    ready(server)
    cid = server.new(agent="opencode")
    for tool in ("flipper_flipper_screen", "flipper_flipper_press"):
        server.send("chat.send", conversation=cid, text=f"scenario:doom {tool}")
        messages = server.collect(cid)
        assert not events(messages, "permission") and texts(messages) == ["doom_loop: once"]
    for text in ("scenario:doom bash", "scenario:doom flipper_flipper_screen malformed",
                 "scenario:doom flipper_flipper_screenx"):
        server.send("chat.send", conversation=cid, text=text)
        ask = server.until(lambda m: m["type"] == "chat.event" and m["kind"] == "permission")
        assert ask["tool"] == "doom_loop" and ask["rules"] == ["doom_loop(doom_loop)"], text
        server.send("chat.permission.answer", id=ask["id"], answer="deny")
        assert texts(server.collect(cid)) == ["doom_loop: reject"], text
    item = json.loads((server.folder / "state" / "conversations.json").read_text())["conversations"]
    assert next(c for c in item if c["id"] == cid)["rules"] == []
