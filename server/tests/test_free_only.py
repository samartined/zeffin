# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The "free only" switch, so paid usage stays the user's choice: kept in config.json, set with zf set-free-only, off by default. Off, each agent runs on the login or
providers the user set up and the app is told which models are paid; on, the "never pay" guards hold (their own
tests run with it on: test_chat_live, test_opencode, test_agent). Whatever the switch: never --bare, no token on a
command line, OpenCode's sharing and auto-update off and its environment an allow-list."""
import json
from dataclasses import replace
from pathlib import Path

import pytest

from bridge import cli
from bridge import config as cfg
from bridge.agent import Output, TurnRequest
from bridge.chat import catalog, command, opencode
from bridge.chat.claude_code import ClaudeCode, ClaudeTurn
from bridge.chat.stream import Out
from bridge.config import Config
from bridge.report import describe_chat
from bridge.wire import Guard, Outcome, Problem, TurnError

from test_opencode import CFG as OPENCODE_CFG
from test_opencode import MODELS_TEXT, collect, fake_bin, fake_set, request, runs

MODEL_ROWS = json.loads((Path(__file__).parent / "fixtures/models.json").read_text())["rows"]
CREDITS = {"value": "fable", "resolvedModel": "claude-fable-5-1", "displayName": "Fable 5.1",
           "description": "For your toughest challenges · Requires usage credits"}


def claude_config(tmp_path: Path, free_only: bool) -> Config:
    claude = tmp_path / "claude"
    claude.write_text("#!/bin/sh\n")
    claude.chmod(0o755)
    return Config(host="127.0.0.1", phone_port=19434, mcp_port=19435, phone_token="p" * 40, mcp_token="m" * 40,
                  view_token="v" * 40, app_token="a" * 40, state_dir=str(tmp_path), keepalive_s=0,
                  agent_dir=str(tmp_path), claude_bin=str(claude), free_only=free_only)


# ------------------------------------------------------------------ the setting
def test_it_is_off_by_default_kept_by_load_and_save_and_checked(tmp_path):
    path = tmp_path / "config.json"
    c = cfg.create(path, "127.0.0.1", 9434, 9435, tmp_path / "state")
    assert c.free_only is False and json.loads(path.read_text())["free_only"] is False
    cfg.save(path, replace(c, free_only=True))
    assert cfg.load(path).free_only is True
    data = json.loads(path.read_text())
    del data["free_only"]  # a file without the field: off
    path.write_text(json.dumps(data))
    assert cfg.load(path).free_only is False
    with pytest.raises(cfg.ConfigError):
        replace(c, free_only="yes").validate()


def test_zf_set_free_only_prints_sets_and_rewrites_opencode_json(tmp_path, capsys, monkeypatch):
    path = tmp_path / "config.json"
    cfg.create(path, "127.0.0.1", 9434, 9435, tmp_path / "state")
    monkeypatch.setattr(cli, "_service", lambda: None)
    assert cli.main(["--config", str(path), "set-free-only"]) == 0
    assert "Free only: off." in capsys.readouterr().out
    folder = tmp_path / "agent"
    folder.mkdir()
    opencode.write_config(folder, False)
    c = cfg.load(path)
    cfg.save(path, replace(c, agent_dir=str(folder), opencode_bin="/opt/opencode"))
    assert cli.main(["--config", str(path), "set-free-only", "on"]) == 0
    out = capsys.readouterr().out
    assert "Free only: on." in out and "Wrote" in out
    assert cfg.load(path).free_only is True
    assert json.loads((folder / "opencode.json").read_text())["enabled_providers"] == ["opencode", "nvidia"]
    assert cli.main(["--config", str(path), "set-free-only", "off"]) == 0
    assert cfg.load(path).free_only is False and "enabled_providers" not in json.loads(
        (folder / "opencode.json").read_text())
    with pytest.raises(SystemExit):
        cli.main(["--config", str(path), "set-free-only", "maybe"])


def test_zf_set_free_only_restarts_a_running_service(tmp_path, capsys, monkeypatch):
    """Only the service that runs this config: one of another config is left alone."""
    path = tmp_path / "config.json"
    cfg.create(path, "127.0.0.1", 9434, 9435, tmp_path / "state")
    calls = []
    monkeypatch.setenv("HOME", str(tmp_path / "home"))
    monkeypatch.setattr(cli, "_service", lambda: {"ActiveState": "active"})
    monkeypatch.setattr(cli, "_systemctl", lambda *a: type("R", (), {"stdout": "active\n", "returncode": 0})())
    monkeypatch.setattr(cli, "_control", lambda action: calls.append(action) or 0)
    unit = cli.unit_path()
    unit.parent.mkdir(parents=True)
    unit.write_text(cli.render_unit(tmp_path / "other.json", tmp_path / "state", minimal=False))
    assert cli.main(["--config", str(path), "set-free-only", "on"]) == 0
    assert calls == []
    unit.write_text(cli.render_unit(path, tmp_path / "state", minimal=False))
    assert cli.main(["--config", str(path), "set-free-only", "off"]) == 0
    assert calls == ["restart"]


def test_zf_status_shows_one_line_only_when_it_is_on():
    chat = {"configured": False, "problem": None, "claude": {"version": "2.1.316", "auth": "claude.ai"},
            "model": "opus", "agent_dir": None, "agents": []}
    assert not [line for line in describe_chat(chat) if "free only" in line.lower()]
    lines = [line for line in describe_chat({**chat, "free_only": True}) if "free only" in line.lower()]
    assert len(lines) == 1 and lines[0].startswith("Free:") and "set-free-only off" in lines[0]


def test_install_script_never_asks_for_it():
    script = (Path(__file__).resolve().parents[2] / "install.sh").read_text()
    assert "free_only" not in script and "set-free-only" not in script


# ------------------------------------------------------------------ Claude Code
def test_the_catalog_off_offers_every_model_and_marks_the_paid_ones():
    rows = [r for r in MODEL_ROWS if r["value"] != "fable"] + [
        CREDITS, {"value": "claude-opus-4-6[1m]", "displayName": "Opus 4.6 (1M context)"}]
    on = {m["value"]: m for m in catalog.build(rows).models}
    assert (on["fable"]["available"], on["fable"]["paid"]) == (False, True)
    assert on["opus"]["paid"] is False and not any(m["paid"] for m in on.values() if m["available"])
    off = catalog.build(rows, free_only=False)
    by = {m["value"]: m for m in off.models}
    assert all(m["available"] and m["reason"] is None for m in by.values()) and not off.blocked
    assert by["fable"]["paid"] and by["claude-opus-4-6[1m]"]["paid"] and not by["opus"]["paid"]
    # An API key: every model bills per use
    assert all(m["paid"] for m in catalog.build(rows, free_only=False, subscription=False).models)
    assert all(m["paid"] for m in catalog.build(None, free_only=False, subscription=False).models)


def test_claude_code_off_takes_any_login_and_never_kills_for_billing(tmp_path):
    agent = ClaudeCode(claude_config(tmp_path, False))
    agent.health = {"version": "2.1.316", "auth": "console", "logged_in": True, "checked_at": 1.0}
    assert agent.problem() is None and agent.login() == {"method": "console", "subscription": False}
    assert agent.guard("claude-fable-5-1", {"apiKeySource": "ANTHROPIC_API_KEY"}) is None
    assert agent.guard("claude-opus-4-6[1m]", {"apiKeySource": "none"}) is None
    on = ClaudeCode(claude_config(tmp_path, True))
    on.health = dict(agent.health)
    assert on.problem() == Problem.NOT_SUBSCRIPTION
    assert on.guard("claude-opus-5-5", {"apiKeySource": "ANTHROPIC_API_KEY"}) == Guard.API_KEY_SOURCE


def test_usage_credits_end_a_turn_only_with_the_switch_on(tmp_path):
    limit = Out("limit", {"type": "rate_limit_event", "credits_required": True})
    for free_only, killed in ((True, True), (False, False)):
        turn = ClaudeTurn(ClaudeCode(claude_config(tmp_path, free_only)), TurnRequest(
            session="s", resume=False, model="opus", effort=None, mode="default", rules=[], permission_timeout_s=60,
            text="hi"))
        assert (turn._event(limit) is not None) is killed


def test_the_settings_check_off_keeps_only_what_it_cannot_read(tmp_path):
    settings = tmp_path / ".claude" / "settings.json"
    settings.parent.mkdir()
    settings.write_text(json.dumps({"apiKeyHelper": "/bin/key", "env": {"ANTHROPIC_API_KEY": "k"}}))
    assert command.agent_settings_problem(tmp_path) and command.agent_settings_problem(tmp_path, True)
    assert command.agent_settings_problem(tmp_path, False) is None
    settings.write_text("{not json")
    assert command.agent_settings_problem(tmp_path, False)


def test_the_environment_off_passes_the_users_key_and_never_a_bare_flag():
    base = {"PATH": "/usr/bin", "ANTHROPIC_API_KEY": "k", "CLAUDE_CODE_USE_BEDROCK": "1"}
    on = command.turn_env(base, hook_token="h" * 8, mcp_token="m" * 8)
    off = command.turn_env(base, hook_token="h" * 8, mcp_token="m" * 8, free_only=False)
    assert "ANTHROPIC_API_KEY" not in on and "CLAUDE_CODE_USE_BEDROCK" not in on
    assert off["ANTHROPIC_API_KEY"] == "k" and off["DISABLE_AUTOUPDATER"] == "1"
    spec = command.TurnSpec(claude_bin="/c", session_id="s", resume=False, model="fable", mode="default",
                            mcp_url="http://127.0.0.1:1/mcp", hooks_url="http://127.0.0.1:1/hooks")
    argv = command.build_command(spec)
    assert "--bare" not in argv and "m" * 8 not in json.dumps(argv)


# ------------------------------------------------------------------ OpenCode
@pytest.fixture
def open_agent(tmp_path):
    folder = tmp_path / "agent"
    folder.mkdir()
    opencode.write_config(folder, False)
    return opencode.OpenCode(Config(**{**OPENCODE_CFG, "free_only": False}, agent_dir=str(folder),
                                    opencode_bin=str(fake_bin(tmp_path))))


def test_opencode_json_off_has_no_provider_restriction_and_still_no_sharing(tmp_path):
    opencode.write_config(tmp_path, False)
    written = json.loads((tmp_path / "opencode.json").read_text())
    assert "enabled_providers" not in written and written["share"] == "disabled" and written["autoupdate"] is False
    assert written["permission"]["flipper_*"] == "allow"
    assert opencode.config_problem(written, False) is None and opencode.config_problem(written, True)
    for change in ({"provider": {"openrouter": {}}}, {"model": "anthropic/x"},
                   {"agent": {"build": {"model": "anthropic/x"}}}):
        assert opencode.config_problem({**written, **change}, False) is None, change
    for change in ({"share": "auto"}, {"autoupdate": True}):
        assert opencode.config_problem({**written, **change}, False), change


NIM = {"key": "nvidia/nvidia/nemotron-3.5-super-vl-120b-a12b",
       "model": {"name": "Nemotron 3.5 Super VL", "cost": {"input": 0, "output": 0},
                 "capabilities": {"input": {"image": True}}}}


def test_the_catalog_off_favours_no_provider():
    """Off: the providers signed in first, in OpenCode's order, then Zen's free models (no account). No row for a
    provider nobody signed in; Zen's priced models are left out, since nothing says Zen is signed in."""
    rows = opencode.parse_models(MODELS_TEXT)  # NIM's other model and an anthropic one: both signed in; NIM's curated not
    models = opencode.catalog(rows, False)
    assert [m["value"] for m in models] == ["nvidia/some/other-model", "anthropic/claude-x",
                                            "opencode/nemotron-3.5-super-vl-free", "opencode/text-free"]
    assert all(m["available"] and m["reason"] is None for m in models)
    assert not any(m["paid"] for m in models)  # anthropic/claude-x is priced at 0 in this list
    assert opencode.providers(rows, False) == ["nvidia", "anthropic", "opencode"]
    # NIM signed in: its curated model first among NIM's
    signed = [NIM] + rows
    assert [m["value"] for m in opencode.catalog(signed, False)][:2] == [NIM["key"], "nvidia/some/other-model"]
    # Nobody signed in: Zen's free models only, its fallback first (the default)
    zen = [r for r in rows if r["key"].startswith("opencode/")]
    assert [m["value"] for m in opencode.catalog(zen, False)] == ["opencode/nemotron-3.5-super-vl-free",
                                                                  "opencode/text-free"]
    paid = [{"key": "openrouter/x/big", "model": {"cost": {"input": 3, "output": 9}}},
            {"key": "openrouter/x/odd", "model": {"cost": {"input": 0}}}] + zen
    by = {m["value"]: m for m in opencode.catalog(paid, False)}
    assert list(by)[0] == "openrouter/x/big" and by["openrouter/x/big"]["paid"] and by["openrouter/x/odd"]["paid"]
    many = [{"key": f"p/m{n:03}", "model": {"cost": {"input": 1, "output": 1}}} for n in range(300)]
    assert len(opencode.catalog(many, False)) == opencode.MAX_MODELS


def test_the_catalog_on_is_rc1s_exactly():
    rows = opencode.parse_models(MODELS_TEXT)
    models = opencode.catalog(rows, True)
    assert [(m["value"], m["available"], m["reason"]) for m in models] == [
        ("nvidia/nvidia/nemotron-3.5-super-vl-120b-a12b", False, "not_signed_in"),
        ("opencode/nemotron-3.5-super-vl-free", True, None), ("opencode/text-free", True, None)]
    assert opencode.providers(rows, True) == ["nvidia", "opencode"]


def test_providers_have_readable_names():
    assert [opencode.provider_name(p) for p in ("opencode", "nvidia", "openai", "anthropic", "google", "openrouter",
                                                "someone-else")] == [
        "OpenCode Zen", "NVIDIA NIM", "OpenAI", "Anthropic", "Google", "OpenRouter", "someone-else"]


async def test_opencode_off_with_a_paid_provider_and_no_nim(open_agent):
    """The fake with OpenRouter signed in and NIM not: OpenRouter first and the default, Zen's free ones after, no NIM
    row; its models are paid, and one runs when the conversation chose it."""
    fake_set(Path(open_agent.config.opencode_bin), FAKE_OPENCODE_OPENROUTER="1")
    await open_agent.check()
    models = open_agent.models()
    assert [(m["value"], m["paid"]) for m in models] == [
        ("openrouter/example/coder-large", True), ("openrouter/example/vision-pro", True),
        ("opencode/nemotron-3.5-super-vl-free", False), ("opencode/text-free", False)]
    assert open_agent.login() == {"method": "OpenRouter, OpenCode Zen", "subscription": False}
    assert open_agent.problem() is None and "opencode/big-paid-model" not in open_agent.offered()
    turn = await open_agent.start_turn(request(model="openrouter/example/coder-large"))
    events_ = await collect(turn)
    await turn.wait(5)
    assert events_[0].guard is None and turn.outcome().outcome == Outcome.DONE
    run = [r for r in runs(Path(open_agent.config.opencode_bin)) if r["argv"] == ["acp"]][-1]
    assert not [n for n in run["env"] if n.endswith("_API_KEY") or n.startswith("ANTHROPIC_")]
    # No turn-start check of the configuration with the switch off: only the acp process ran
    assert [r["argv"] for r in runs(Path(open_agent.config.opencode_bin))][-1:] == [["acp"]]


async def test_opencode_off_still_kills_a_session_moved_to_another_model(open_agent):
    await open_agent.check()
    turn = await open_agent.start_turn(request(text="scenario:switch-model"))
    events_ = await collect(turn)
    await turn.wait(5)
    end = turn.outcome()
    assert (end.outcome, end.error, end.data) == (Outcome.ERROR, TurnError.BILLING_GUARD, {"why": Guard.MODEL})
    assert sum(1 for e in events_ if isinstance(e, Output) and e.event["kind"] == "text") <= 1


async def test_opencode_off_still_refuses_sharing(open_agent):
    await open_agent.check()
    assert open_agent.problem() is None
    path = Path(open_agent.config.agent_dir) / "opencode.json"
    path.write_text(json.dumps({**json.loads(path.read_text()), "share": "auto"}))
    assert open_agent.problem() == Problem.UNSAFE_AGENT_SETTINGS
    await open_agent.check()  # the merged configuration says it too
    assert open_agent.health["config"] == "sharing is not disabled"


def test_zf_agent_setup_opencode_off_recommends_no_provider(tmp_path, monkeypatch, capsys):
    path = tmp_path / "config.json"
    cfg.create(path, "127.0.0.1", 9434, 9435, tmp_path / "state")
    script = fake_bin(tmp_path)
    monkeypatch.setenv("PATH", f"{script.parent}:/usr/bin:/bin")
    monkeypatch.setenv("HOME", str(tmp_path / "home"))
    monkeypatch.setattr(cli, "_service", lambda: None)
    folder = tmp_path / "zeffin-agent"
    assert cli.main(["--config", str(path), "agent", "setup", "--agent", "opencode", "--dir", str(folder)]) == 0
    out = capsys.readouterr().out
    assert "No provider is signed in, so OpenCode uses OpenCode Zen's free models (no account)" in out
    assert "build.nvidia.com" not in out
    assert "Default model for new conversations: opencode/nemotron-3.5-super-vl-free" in out
    fake_set(script, FAKE_OPENCODE_OPENROUTER="1")
    assert cli.main(["--config", str(path), "agent", "setup", "--agent", "opencode"]) == 0
    out = capsys.readouterr().out
    assert "Signed in: OpenRouter." in out and "NVIDIA" not in out
    assert "Default model for new conversations: openrouter/example/coder-large" in out and "Paid models: 2." in out
    # On: the free-only words, NIM recommended
    cfg.save(path, replace(cfg.load(path), free_only=True))
    fake_set(script, FAKE_OPENCODE_OPENROUTER=None)
    assert cli.main(["--config", str(path), "agent", "setup", "--agent", "opencode"]) == 0
    out = capsys.readouterr().out
    assert "NVIDIA NIM is not signed in" in out and "build.nvidia.com" in out
