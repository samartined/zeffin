# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""zf agent probe --json and --fixtures: the redaction rule, its self-check, and the report on stdout."""
import json

import pytest

from bridge import cli
from bridge import config as cfg
from bridge.chat import probe as probe_module
from bridge.chat.probe import Probe, run_probe
from bridge.chat.redact import Redactor, Secrets, claude_account

# Fake values only.
HOME = "/home/jdoe"
TOKENS = ["phone-tok-AAAAAAAAAAAAAAAAAAAAAAAA", "mcp-tok-BBBBBBBBBBBBBBBBBBBBBBBB",
          "view-tok-CCCCCCCCCCCCCCCCCCCCCCCC", "app-tok-DDDDDDDDDDDDDDDDDDDDDDDD", "hook-tok-EEEEEEEEEEEEEEEEEEEE"]
SESSION = "3F2504E0-4F89-11D3-9A0C-0305E82C3301"
OTHER = "6fa459ea-ee8a-3ca4-894e-db77e160355e"


def _secrets() -> Secrets:
    return Secrets(paths={f"{HOME}/projects/zeffin-data/state/probe-x1": "<workdir>",
                          f"{HOME}/projects/zeffin-data/state": "<state>", HOME: "<home>", "/tmp": "<tmp>"},
                   user="jdoe", host="my-server.example", home=HOME, tokens=TOKENS, ips=["100.98.12.7", "fd7a:115c::5"],
                   account=["Jane Doe", "Doe Labs", "jane@example.org"])


def test_each_kind_is_replaced():
    r = Redactor(_secrets())
    event = {"type": "assistant", "session_id": SESSION, "cwd": f"{HOME}/projects/zeffin-data/state/probe-x1",
             "message": {"content": [{"type": "text", "text":
                         f"jdoe on my-server ran cat {HOME}/.bashrc and /tmp/x with Authorization: Bearer {TOKENS[1]} "
                         f"and Bearer zzzzzzzzzzzzzzzzzz, key sk-ant-api03-abc_DEF, from 100.98.12.7:9434 and "
                         f"[fd7a:115c::5]; Jane Doe of Doe Labs, jane@example.org, cc someone@else.net; "
                         f"{TOKENS[4]}"}]},
             f"{HOME}/key": TOKENS[0]}
    out = r.text(json.dumps(event))  # the deny-list alone: the allow-list would take the whole sentence
    for value in [HOME, "jdoe", "my-server", "100.98.12.7", "fd7a:115c::5", "Jane Doe", "Doe Labs", "@example.org",
                  "someone@else.net", "zzzzzzzz", "sk-ant-api03", SESSION, SESSION.lower(), *TOKENS]:
        assert value not in out, value
    for placeholder in ["<workdir>", "<home>/.bashrc", "<tmp>/x", "<user>", "<host>", "<ip>:9434", "[<ip>]",
                        "<account>", "<email>", "Bearer <token>", "<uuid-1>", "<home>/key"]:
        assert placeholder in out, placeholder
    assert r.leaks(out) == []
    # with the allow-list, the sentence is gone whole and the key made of a path too
    event = r.event(event)
    assert event["message"]["content"][0]["text"] == "<text-1>" and event["cwd"] == "<workdir>"
    assert event["<text-2>"] == "<token>" and r.leaks(json.dumps(event)) == []


def test_the_allow_list_keeps_structure_and_the_probes_words():
    r = Redactor(_secrets(), known=["Use the Write tool to create probe-a.txt containing the word alpha."])
    event = {"type": "assistant", "message": {"id": "msg_01AbC", "model": "claude-haiku-5-5", "content": [
        {"type": "tool_use", "id": "toolu_01X", "name": "Write",
         "input": {"file_path": f"{HOME}/projects/zeffin-data/state/probe-x1/probe-a.txt", "content": "alpha"}},
        {"type": "text", "text": "Use the Write tool to create probe-a.txt containing the word alpha."},
        {"type": "text", "text": "Sure, I wrote it next to your tax files."},
        {"type": "tool_result", "content": '{"connected": true, "name": "Demo", "seq": 7, "where": "a b"}'}]},
        "timestamp": "2026-10-27T15:08:05.649Z", "usage": {"input_tokens": 2}, "is_error": False, "n": None}
    out = r.event(event)
    tool, known, other, result = out["message"]["content"]
    assert tool == {"type": "tool_use", "id": "toolu_01X", "name": "Write",
                    "input": {"file_path": "<workdir>/probe-a.txt", "content": "alpha"}}
    assert known["text"] == event["message"]["content"][1]["text"] and other["text"] == "<text-1>"
    assert json.loads(result["content"]) == {"connected": True, "name": "Demo", "seq": 7, "where": "<text-2>"}
    assert out["timestamp"] == event["timestamp"] and out["usage"] == {"input_tokens": 2}
    assert out["is_error"] is False and out["n"] is None and out["message"]["model"] == "claude-haiku-5-5"
    assert r.event({"text": "Sure, I wrote it next to your tax files."})["text"] == "<text-1>"  # stable in the run_stay_the_same_across_events():
    r = Redactor(_secrets())
    first = r.event({"type": "system", "subtype": "init", "session_id": SESSION.lower(), "uuid": OTHER})
    second = r.event({"type": "result", "session_id": SESSION, "text": f"resumed {SESSION.lower()}"})
    assert first["session_id"] == second["session_id"] == "<uuid-1>" and first["uuid"] == "<uuid-2>"
    assert r.text(f"resumed {SESSION.lower()}") == "resumed <uuid-1>"


def test_the_owners_setup_in_init_is_replaced_by_rule():
    """Names nobody can list in advance: everything but the bridge's own MCP server becomes <name-N>."""
    r = Redactor(_secrets())
    init = {"type": "system", "subtype": "init", "session_id": SESSION,
            "tools": ["Bash", "Read", "mcp__flipper__flipper_status", "mcp__notes-of-jdoe__search", "mcp__mail__send"],
            "mcp_servers": [{"name": "flipper", "status": "connected"}, {"name": "notes-of-jdoe", "status": "failed"}],
            "slash_commands": ["compact", "deploy-shop"], "skills": ["tax-helper"], "agents": ["reviewer"],
            "plugins": [{"name": "acme-tools", "path": f"{HOME}/.claude/plugins/acme-tools"}],
            "output_style": "pirate", "model": "claude-haiku-5-5", "permissionMode": "default"}
    out = r.event(init)
    text = json.dumps(out)
    for value in ["notes-of-jdoe", "mcp__mail__send", "deploy-shop", "tax-helper", "reviewer", "acme-tools", "pirate"]:
        assert value not in text, value
    assert out["tools"][:3] == ["Bash", "Read", "mcp__flipper__flipper_status"] and len(out["tools"]) == 5
    assert all(t.startswith("<name-") for t in out["tools"][3:])
    assert out["mcp_servers"][0] == {"name": "flipper", "status": "connected"}
    assert out["mcp_servers"][1]["status"] == "failed" and out["mcp_servers"][1]["name"].startswith("<name-")
    assert len(out["slash_commands"]) == 2 and len(out["skills"]) == len(out["agents"]) == len(out["plugins"]) == 1
    assert out["model"] == "claude-haiku-5-5" and out["output_style"].startswith("<name-")
    again = r.event({**init, "session_id": OTHER})
    assert again["skills"] == out["skills"] and again["mcp_servers"] == out["mcp_servers"]
    assert r.event({**init, "output_style": "default"})["output_style"] == "default"


def test_the_self_check_names_kinds_only():
    r = Redactor(_secrets())
    assert r.leaks("nothing here, <user> on <host> at <home>") == []
    found = r.leaks(f"{HOME}/x jdoe MY-SERVER 100.98.12.7 Jane Doe {TOKENS[2]}")
    assert found == ["token", "home", "user", "host", "ip", "account"]
    assert r.leaks("100.98.12.70 and 1100.98.12.7 are other addresses") == []
    assert r.leaks("a@b.co") == ["account"]


def test_the_claude_account_is_read_from_claude_json(tmp_path):
    path = tmp_path / ".claude.json"
    path.write_text(json.dumps({"oauthAccount": {"emailAddress": "jane@example.org", "displayName": "Jane Doe",
                                                 "organizationName": "Doe Labs", "billingType": "x"}}))
    assert sorted(claude_account(path)) == ["Doe Labs", "Jane Doe", "jane@example.org"]
    assert claude_account(tmp_path / "missing.json") == []


@pytest.fixture
def home(tmp_path, monkeypatch):
    home = tmp_path / "home"
    home.mkdir()
    monkeypatch.setenv("HOME", str(home))
    monkeypatch.delenv("CLAUDE_CONFIG_DIR", raising=False)
    (home / ".claude.json").write_text(json.dumps({"oauthAccount": {"emailAddress": "jane@example.org",
                                                                    "displayName": "Jane Doe"}}))
    return home


def _config(tmp_path):
    path = tmp_path / "config.json"
    cfg.create(path, "127.0.0.1", 19434, 1, tmp_path / "state")  # MCP port 1: nothing listens
    return path


def _quick(monkeypatch):
    monkeypatch.setattr(Probe, "HOLD_S", 0.3)
    monkeypatch.setattr(Probe, "TIMEOUT_HOOK_S", 1)
    monkeypatch.setattr(Probe, "TIMEOUT_DELAY_S", 2.0)


def test_json_prints_only_the_report_and_writes_clean_fixtures(tmp_path, fake_claude, monkeypatch, capsys, home):
    _quick(monkeypatch)
    path = _config(tmp_path)
    monkeypatch.setattr(cli, "fetch_status", lambda c, **_: (None, None))
    monkeypatch.setenv("PATH", f"{fake_claude.parent}:/usr/bin:/bin")
    folder = tmp_path / "fixtures"
    folder.mkdir()
    (folder / "99-stale.jsonl").write_text("{}\n")
    (folder / "README.md").write_text("kept")
    assert cli.main(["--config", str(path), "agent", "probe", "--json", "--fixtures", str(folder)]) == 0
    out, err = capsys.readouterr()
    report = json.loads(out)  # stdout is the report and nothing else
    assert "Probing Claude Code" in err and " new_turn: " in err
    assert report["v"] == 2 and report["checks"]["new_turn"]["step"] == 2
    assert report["checks"]["resume"]["summary"].startswith("exit 0")
    assert report["fixtures"] == {"written": True, "files": report["fixtures"]["files"], "refused": []}
    files = sorted(p.name for p in folder.glob("*.jsonl"))
    assert len(files) == report["fixtures"]["files"] and "99-stale.jsonl" not in files
    assert (folder / "README.md").read_text() == "kept"
    assert files[:3] == ["01-new_and_resume.jsonl", "02-new_and_resume-2.jsonl", "03-allow.jsonl"]
    c = cfg.load(path)
    everything = "\n".join(p.read_text() for p in folder.iterdir())
    for value in [str(tmp_path), str(home), c.phone_token, c.mcp_token, c.view_token, c.app_token, "jane@example.org",
                  "Jane Doe"]:
        assert value not in everything
    first = [json.loads(line) for line in (folder / files[0]).read_text().splitlines()]
    second = [json.loads(line) for line in (folder / files[1]).read_text().splitlines()]
    assert first[0]["session_id"] == second[0]["session_id"] == "<uuid-1>" and first[0]["cwd"] == "<workdir>"
    manifest = json.loads((folder / "manifest.json").read_text())
    assert manifest["claude"] == "2.1.999" and [f["file"] for f in manifest["files"]] == files
    assert manifest["files"][1]["resume"] is True and "4817" in manifest["files"][1]["prompt"] + manifest["files"][0]["prompt"]
    image = next(f for f in manifest["files"] if f["step"] == "image")
    assert image["images"] == 1 and image["prompt"] is None
    assert "<workdir>" in next(f for f in manifest["files"] if f["step"] == "add_dir")["prompt"] or \
        "<state>" in next(f for f in manifest["files"] if f["step"] == "add_dir")["prompt"]


def test_the_self_check_refuses_to_write(tmp_path, fake_claude, monkeypatch, home):
    """If the redaction missed a known value, nothing is written and the report names only its kind."""
    _quick(monkeypatch)
    monkeypatch.setattr(probe_module.Redactor, "text", lambda self, value: value)
    path = tmp_path / "config.json"
    cfg.create(path, "127.0.0.1", 19434, 1, home / "state")  # the turns' cwd is under home
    c = cfg.load(path)
    folder = tmp_path / "fixtures"
    lines = []
    report = run_probe(c, str(fake_claude), mcp_running=False, out=lines.append, fixtures=folder)
    assert report["fixtures"]["written"] is False and not folder.exists()
    assert "home" in report["fixtures"]["refused"]
    assert any("Fixtures not written" in line for line in lines)
    assert str(tmp_path) not in json.dumps(report) + "\n".join(lines)


def _base_secrets() -> Secrets:
    from bridge.chat.redact import path_forms

    paths = {f"{HOME}/projects": "<base>", f"{HOME}/projects/zeffin-data/state": "<state>",
             f"{HOME}/projects/zeffin-data/state/probe-x1": "<workdir>", HOME: "<home>", "/tmp": "<tmp>"}
    return Secrets(paths=path_forms(paths, HOME), user="jdoe", host="my-server", home=HOME, tokens=TOKENS)


def _streamed(pieces: list[str], kind: str = "text_delta", field: str = "text", message: str = "msg_1") -> list[dict]:
    start = {"type": "stream_event", "event": {"type": "message_start", "message": {"id": message}}}
    return [start] + [{"type": "stream_event", "event": {"type": "content_block_delta", "index": 1,
                                                          "delta": {"type": kind, field: p}}} for p in pieces]


def test_a_value_cut_across_streamed_pieces_is_redacted():
    """2026-10-27, the first run on a real server: a path came cut in pieces ("/home/jd" + "oe/proj" …)
    and each piece passed alone."""
    from bridge.chat.redact import joined_blocks

    r = Redactor(_base_secrets())
    text = f"Created `{HOME}/projects/zeffin-data/state/probe-x1/a.txt` and `{HOME}/projects/other/b`."
    pieces = [text[:14], text[14:21], text[21:40], text[40:]]
    json_pieces = ['{"file_path": "/home/jd', 'oe/projects/ze', 'ffin-data/state/probe-x1/a.txt"}']
    out = r.events(_streamed(pieces) + _streamed(json_pieces, "input_json_delta", "partial_json", "msg_2"))
    assert len(out) == 1 + 4 + 1 + 3 and r.leaks("\n".join(joined_blocks(out)) + json.dumps(out)) == []
    first, second = joined_blocks(out)
    assert first == "<text-1>" and r.text(text) == "Created `<workdir>/a.txt` and `<base>/other/b`."
    assert json.loads(second) == {"file_path": "<workdir>/a.txt"}
    assert "".join(e["event"]["delta"]["text"] for e in out[1:5]) == "<text-1>"  # as many pieces
    # the self-check joins the pieces too: a value cut where no piece shows it is still found
    left = _streamed(json_pieces)
    assert r.leaks(json.dumps(left)) == [] and {"home", "user"} <= set(r.leaks("\n".join(joined_blocks(left))))


def test_local_command_output_paths_forms_and_time_zones():
    r = Redactor(_base_secrets())
    usage = {"type": "assistant", "local_command_run": {"command": "usage"},
             "local_command_source": "<local-command-stdout>resets 8pm (Asia/Tokyo) · mostly in ~/projects/x",
             "message": {"content": [{"type": "text", "text": "resets 8pm (Asia/Tokyo)"}]},
             "usage_report": {"rate_limits": {"limits": [{"percent": 15}]}}}
    result = {"type": "result", "local_command": "usage", "result": "Top plugins: acme 98%"}
    out = json.dumps(r.events([usage, result]))
    assert "Tokyo" not in out and "acme" not in out and "~/projects" not in out
    assert out.count("<local command output>") == 3 and '"percent": 15' in out
    assert r.text("resets 8pm (Asia/Tokyo) · mostly in ~/projects/x") == "resets 8pm (<tz>) · mostly in <base>/x"
    init = {"type": "system", "subtype": "init",
            "memory_paths": {"auto": f"{HOME}/.claude/projects/-home-jdoe-projects-zeffin-data-state-probe-x1/m/"},
            "cwd": "~/projects/zeffin-data/state/probe-x1"}
    out = r.event(init)
    assert r.text(init["memory_paths"]["auto"]) == "<home>/.claude/projects/<project>/m/" and out["cwd"] == "<workdir>"
    assert out["memory_paths"]["auto"].startswith("<text-") and r.leaks(json.dumps(out)) == []
    assert r.text("in -home-jdoe-projects-zeffin, at 9pm (America/Argentina/Buenos_Aires)") == \
        "in <base>-zeffin, at 9pm (<tz>)"
    assert r.leaks("-home-<user>-projects-zeffin-data-state-probe-x1") == ["path"]
    assert r.leaks("<home>/projects/zeffin-data/state/x") == ["path"] and r.leaks("Asia/Tokyo") == ["time zone"]
    assert r.leaks("<home>/.claude/projects/<project>/memory and other projects") == []


def test_the_setup_in_the_initialize_answer_is_replaced_too():
    r = Redactor(_base_secrets())
    answer = {"type": "control_response", "response": {"subtype": "success", "response": {
        "commands": [{"name": "compact", "description": "Clear history", "builtin": True},
                     {"name": "deploy-shop", "description": "Ship the jdoe shop", "argumentHint": ""}],
        "agents": [{"name": "tax-helper", "description": "Does my taxes"}],
        "available_output_styles": ["default", "pirate"], "output_style": "default",
        "models": [{"value": "haiku"}]}}}
    out = r.event(answer)["response"]["response"]
    text = json.dumps(out)
    assert "deploy-shop" not in text and "taxes" not in text and "pirate" not in text and "jdoe" not in text
    assert out["commands"][0]["name"] == "compact" and out["commands"][0]["builtin"] is True
    assert out["commands"][1]["name"].startswith("<name-") and out["agents"][0]["name"].startswith("<name-")
    # a setup name written elsewhere is replaced there too, and the self-check knows it
    assert r.text("ran /deploy-shop twice, tax-helper once") == f"ran /{out['commands'][1]['name']} twice, " \
        f"{out['agents'][0]['name']} once"
    assert r.leaks("tax-helper") == ["setup"]
    assert out["available_output_styles"][0] == "default" and out["models"] == [{"value": "haiku"}]
