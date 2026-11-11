# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""zf agent setup and zf agent probe, with the fake claude."""
import json
from pathlib import Path

import pytest

from bridge import cli
from bridge import config as cfg
from bridge.chat.probe import Probe, run_probe
from conftest import fake_runs


@pytest.fixture(autouse=True)
def home(tmp_path, monkeypatch):
    """Claude Code's config lives under HOME: never the real one."""
    home = tmp_path / "home"
    home.mkdir()
    monkeypatch.setenv("HOME", str(home))
    monkeypatch.delenv("CLAUDE_CONFIG_DIR", raising=False)
    monkeypatch.setenv("FAKE_CLAUDE_TRANSCRIPTS", "1")
    return home


def _config(tmp_path, free_only: bool = True) -> Path:
    path = tmp_path / "config.json"
    cfg.create(path, "127.0.0.1", 19434, 1, tmp_path / "state")  # MCP port 1: nothing listens
    c = cfg.load(path)
    c.free_only = free_only  # the guards' tests run with the switch on
    cfg.save(path, c)
    return path


def test_setup_records_the_agent(tmp_path, fake_claude, monkeypatch, capsys, home):
    path = _config(tmp_path)
    monkeypatch.setenv("PATH", f"{fake_claude.parent}:/usr/bin:/bin")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "not-a-real-key")
    monkeypatch.setattr(cli, "_service", lambda: None)
    folder = tmp_path / "zeffin-agent"
    assert cli.main(["--config", str(path), "agent", "setup", "--dir", str(folder), "--model", "sonnet"]) == 0
    c = cfg.load(path)
    assert c.agent_dir == str(folder.resolve()) and c.claude_bin == str(fake_claude) and c.chat_model == "sonnet"
    text = (folder / "CLAUDE.md").read_text()
    assert text.startswith("# Flipper Zero") and "Copy this file" not in text and "flipper_screen" in text
    out = capsys.readouterr().out
    assert "ANTHROPIC_API_KEY" in out and "not-a-real-key" not in out
    config = json.loads((home / ".claude.json").read_text())
    assert config["projects"][str(folder.resolve())] == {"hasTrustDialogAccepted": True} and "trusted" in out
    (folder / "CLAUDE.md").write_text("mine")
    assert cli.main(["--config", str(path), "agent", "setup"]) == 0 and (folder / "CLAUDE.md").read_text() == "mine"


def test_setup_refuses_without_a_subscription_login(tmp_path, fake_claude, monkeypatch, capsys):
    path = _config(tmp_path)
    monkeypatch.setenv("PATH", f"{fake_claude.parent}:/usr/bin:/bin")
    monkeypatch.setattr(cli, "_service", lambda: None)
    monkeypatch.setenv("FAKE_CLAUDE_AUTH", "api_key")
    assert cli.main(["--config", str(path), "agent", "setup", "--dir", str(tmp_path / "a")]) == 1
    assert "not a subscription login" in capsys.readouterr().out
    assert cfg.load(path).agent_dir == ""


def test_unit_with_the_agent(tmp_path):
    unit = cli.render_unit(tmp_path / "c.json", tmp_path / "state", minimal=False, agent=True)
    assert "KillMode=mixed" in unit and "ProtectSystem=full" in unit
    for directive in ("ProtectHome=", "MemoryDenyWriteExecute=", "SystemCallFilter=", "RestrictNamespaces="):
        assert directive not in unit
    assert "MemoryDenyWriteExecute=true" in cli.render_unit(tmp_path / "c.json", tmp_path / "state", minimal=False)


def test_probe_against_the_fake(tmp_path, fake_claude, monkeypatch, home):
    (home / ".claude.json").write_text('{"projects": {"/elsewhere": {"hasTrustDialogAccepted": true}}, "theme": "dark"}')
    monkeypatch.setenv("FAKE_CLAUDE_FABLE", "credits")
    monkeypatch.setattr(Probe, "HOLD_S", 0.3)
    monkeypatch.setattr(Probe, "TIMEOUT_HOOK_S", 1)
    monkeypatch.setattr(Probe, "TIMEOUT_DELAY_S", 2.0)
    c = cfg.load(_config(tmp_path))
    lines = []
    report = run_probe(c, str(fake_claude), mcp_running=False, out=lines.append)
    checks = report["checks"]
    for name in ("version_and_auth", "new_turn", "claude_md_read", "resume", "permission_hook_allow",
                 "permission_hook_deny", "hook_hold", "sigint", "auto_mode", "unknown_resume", "usage_report",
                 "model_list", "effort", "plan_mode", "accept_edits", "bash_rule_suggestions", "image_attachment",
                 "add_dir_read"):
        assert checks[name]["ok"] is True, (name, checks[name], lines)
    assert checks["permission_hook_allow"]["calls"] == 2  # writes get a mode suggestion, not a rule
    assert checks["hook_timeout"]["written"] is False
    assert checks["flipper_mcp"]["ok"] is None
    assert "apiKeySource" in report["events"]["system/init"]
    assert report["checks"]["plan_mode"]["allow"]["hook_tools"] == ["ExitPlanMode", "Write"]
    assert report["checks"]["plan_mode"]["deny"]["written"] is False
    assert report["checks"]["accept_edits"]["hook_tools"] == ["Bash"]
    rules = report["checks"]["bash_rule_suggestions"]
    assert rules["plain"]["suggestions"] == [["addRules"]] and rules["redirect"]["suggestions"] == [["setMode"]]
    assert rules["plain"]["ran"] and rules["redirect"]["ran"]
    assert report["usage"]["extra_usage"] is False and [r["value"] for r in report["models"]["rows"]][:2] == ["default", "opus"]
    assert "account" not in json.dumps(report["models"]).replace("account_keys", "")
    rows = {r["value"]: r for r in report["models"]["rows"]}  # what the catalog's rule reads from each row (S2)
    assert rows["opus"] == {"value": "opus", "resolved": "claude-opus-5-5", "display": "Opus", "description": "Opus 5.5",
                            "supports_effort": None, "efforts": ["low", "medium", "high", "xhigh", "max"], "auto": True,
                            "mentions_credits": False}
    assert rows["fable"]["description"] == "Fable 5.1 · Requires usage credits" and rows["fable"]["mentions_credits"]
    text = json.dumps(report) + "\n".join(lines)
    assert c.mcp_token not in text and str(tmp_path) not in text and "tangerine" not in text
    assert not list(c.state.glob("probe-*"))
    assert "fixtures" not in report  # without --fixtures nothing is kept
    assert report["trusted"] == "trusted"  # and the temporary folder's entry is gone again
    assert json.loads((home / ".claude.json").read_text()) == {"projects": {"/elsewhere": {"hasTrustDialogAccepted": True}},
                                                                "theme": "dark"}


def test_probe_stops_without_a_login(tmp_path, fake_claude, monkeypatch):
    monkeypatch.setenv("FAKE_CLAUDE_AUTH", "none")
    lines = []
    report = run_probe(cfg.load(_config(tmp_path)), str(fake_claude), mcp_running=False, out=lines.append)
    assert list(report["checks"]) == ["version_and_auth"] and report["logged_in"] is False
    assert any("not signed in" in line for line in lines) and not (fake_claude.parent / "runs.jsonl").exists()


def test_everything_lives_next_to_the_clone(tmp_path, fake_claude, monkeypatch, capsys, home):
    """No new folders in home: data and the agent folder sit next to the repo clone, outside its git tree."""
    clone = Path(cli.__file__).resolve().parents[2]
    assert cfg.BASE_DIR == clone.parent
    assert cfg.DEFAULT_CONFIG == clone.parent / "zeffin-data" / "config.json"
    assert cfg.DEFAULT_STATE == clone.parent / "zeffin-data" / "state"
    assert cfg.DEFAULT_AGENT == clone.parent / "zeffin-agent"
    path = _config(tmp_path)
    monkeypatch.setattr(cfg, "DEFAULT_AGENT", tmp_path / "projects" / "zeffin-agent")
    monkeypatch.setenv("PATH", f"{fake_claude.parent}:/usr/bin:/bin")
    monkeypatch.setattr(cli, "_service", lambda: None)
    assert cli.main(["--config", str(path), "agent", "setup"]) == 0
    first = (tmp_path / "projects" / "zeffin-agent").resolve()
    assert cfg.load(path).agent_dir == str(first) and (first / "CLAUDE.md").exists()
    # Another folder with --dir: the earlier one is left as it is; only fb's trust entry goes.
    other = tmp_path / "other"
    assert cli.main(["--config", str(path), "agent", "setup", "--dir", str(other)]) == 0
    assert cfg.load(path).agent_dir == str(other.resolve()) and (first / "CLAUDE.md").exists()
    assert "no longer the agent folder" in capsys.readouterr().out
    projects = json.loads((home / ".claude.json").read_text())["projects"]
    assert projects == {str(other.resolve()): {"hasTrustDialogAccepted": True}}
    assert not any(p.name.startswith("flipper") for p in home.iterdir())


def _quick_remote_steps(monkeypatch):
    """The fake answers at once, so the probe's waits for a real agent shrink to a few ticks of polling."""
    from bridge.chat.probe_remote import RemoteSteps
    from bridge.chat.terminal import Terminal

    for name, value in (("SETTLE_S", 0.1), ("AFTER_KEY_S", 0.3), ("BLOCKED_S", 0.3), ("BEFORE_ESC_S", 0.3),
                        ("QUIET_S", 0.15), ("POLL_S", 0.02)):
        monkeypatch.setattr(RemoteSteps, name, value)
    monkeypatch.setattr(Terminal, "KEY_GAP_S", 0.05)
    monkeypatch.setattr(Terminal, "POLL_S", 0.02)


def _remote_probe(tmp_path, fake_claude, monkeypatch):
    """Only the version and Remote Control steps, in a folder that is not trusted (unlike run_probe's)."""
    _quick_remote_steps(monkeypatch)
    c = cfg.load(_config(tmp_path))
    lines = []
    workdir = c.state / "probe-remote"
    workdir.mkdir(parents=True)
    (workdir / "CLAUDE.md").write_text("# Probe\n\nThe probe word is tangerine.\n")
    probe = Probe(c, str(fake_claude), workdir, mcp_running=False, out=lines.append, remote=True)
    try:
        probe.step_version()
        probe.step_remote()
    finally:
        probe.hooks.close()
    return c, probe.report, lines


def test_remote_probe_against_the_fake(tmp_path, fake_claude, monkeypatch, home):
    """The whole probe with --remote: run_probe trusts its temporary folder, so the interactive
    process asks no trust question, and the trust entry is gone afterwards. The MCP token never leaves:
    whatever listens on the MCP port while the bridge is down gets it neither from a -p turn nor from a
    Remote Control launch."""
    _quick_remote_steps(monkeypatch)
    monkeypatch.setattr(Probe, "HOLD_S", 0.3)
    monkeypatch.setattr(Probe, "TIMEOUT_HOOK_S", 1)
    monkeypatch.setattr(Probe, "TIMEOUT_DELAY_S", 2.0)
    tokens = _record_mcp_tokens(monkeypatch)
    c = cfg.load(_config(tmp_path))
    lines = []
    report = run_probe(c, str(fake_claude), mcp_running=False, out=lines.append, remote=True)
    checks = report["checks"]
    assert checks["flipper_mcp"]["ok"] is None and "skipped" in checks["flipper_mcp"]["summary"]
    assert checks["rc_start"]["trust_question"] is False and report["trusted"] == "trusted"
    assert json.loads((home / ".claude.json").read_text()) == {"projects": {}}
    for name in ("rc_start", "rc_prompt_hooks", "rc_permission_notice", "rc_block", "rc_interrupt", "rc_bridge_header",
                 "rc_exit", "rc_resume_print", "rc_add_dir_resume", "rc_add_dir_read", "rc_paste_mention", "rc_paste_image"):
        assert checks[name]["ok"] is True, (name, checks[name], lines)
    assert [checks[n]["step"] for n in ("rc_add_dir_resume", "rc_add_dir_read", "rc_paste_mention", "rc_paste_image")] \
        == [30, 31, 32, 33]
    assert checks["rc_add_dir_resume"]["session_start"] == ["resume"]
    assert checks["rc_resume_print"]["hook_session_ours"] is True and checks["rc_resume_print"]["transcript_ours"] is True
    assert checks["rc_resume_print"]["hook_session_ids"] == 1 and checks["rc_resume_print"]["resumed_id_kept"] is True
    assert checks["rc_resume_print"]["new_transcripts"] == 0 and checks["rc_resume_print"]["transcript_grew"] is True
    resumed = checks["rc_add_dir_resume"]
    assert resumed["prompt_seen"] and not resumed["enter_again"] and resumed["ended"] and resumed["answer"] == "last"
    assert checks["rc_add_dir_read"]["how"] == "Read" and checks["rc_add_dir_read"]["asked"] is False
    assert checks["rc_paste_mention"]["how"] == "the mention" and checks["rc_paste_mention"]["prompt_has_mention"]
    assert checks["rc_paste_image"]["how"] == "the mention" and checks["rc_paste_image"]["tools"] == []
    assert checks["rc_start"]["session_start"] == ["resume"] and checks["rc_start"]["rc_active"]
    assert checks["rc_prompt_hooks"]["order"] == ["user-prompt-submit", "pre-tool-use", "post-tool-use", "stop"]
    assert checks["rc_permission_notice"]["types"] == ["permission_prompt"]
    assert checks["rc_block"]["order"] == ["user-prompt-submit"]
    assert checks["rc_interrupt"]["after_esc"] == []  # Stop does not run after an interrupt (hooks page)
    assert checks["rc_exit"]["reason"] == "prompt_input_exit"
    runs = [r for r in fake_runs(fake_claude) if r.get("interactive")]
    assert len(runs) == 2 and runs[0]["argv"][:3] == ["--remote-control", "zeffin probe", "--resume"]
    assert runs[1]["argv"][:4] == ["--remote-control", "zeffin probe", "--add-dir", runs[1]["argv"][3]]
    assert runs[1]["argv"][4] == "--resume" and runs[1]["argv"][5] == runs[0]["argv"][3]
    for run in runs:
        assert "-p" not in run["argv"] and not any(v.startswith("ANTHROPIC_") for v in run["env"])
    text = json.dumps(report) + "\n".join(lines)
    assert c.mcp_token not in text and str(tmp_path) not in text and "tangerine" not in text
    assert "6131" not in text and "cse_fake" not in text and "sierra" not in text
    assert not list(c.state.glob("probe-files-*"))
    all_runs = fake_runs(fake_claude)
    assert any(r.get("interactive") for r in all_runs) and any("-p" in r["argv"] for r in all_runs)
    leaked = sum(1 for token in tokens if token)  # a count: a failure never prints a token
    assert tokens and leaked == 0, lines


def test_remote_probe_when_the_interactive_session_forks(tmp_path, fake_claude, monkeypatch, home):
    """Steps 29 and 30 fail, and 29 says why: the hooks reported another session id than ours."""
    monkeypatch.setenv("FAKE_CLAUDE_RC_FORK", "1")
    from bridge.chat import command

    command.trust_folder(tmp_path / "state" / "probe-remote")
    _, report, lines = _remote_probe(tmp_path, fake_claude, monkeypatch)
    checks = report["checks"]
    assert checks["rc_prompt_hooks"]["ok"] is True  # the interactive session loaded the -p turn
    later = checks["rc_resume_print"]
    assert later["ok"] is False and later["hook_session_ours"] is False and later["transcript_ours"] is False
    assert later["new_transcripts"] == 1 and later["transcript_grew"] is False
    assert checks["rc_add_dir_resume"]["answer"] == "earlier"


def test_remote_probe_when_a_mention_stays_text(tmp_path, fake_claude, monkeypatch, home):
    """Steps 32 and 33 fail when the model gets the @path as plain text; 31 still reads through --add-dir."""
    monkeypatch.setenv("FAKE_CLAUDE_NO_MENTIONS", "1")
    from bridge.chat import command

    command.trust_folder(tmp_path / "state" / "probe-remote")
    _, report, lines = _remote_probe(tmp_path, fake_claude, monkeypatch)
    checks = report["checks"]
    assert checks["rc_add_dir_resume"]["ok"] is True and checks["rc_add_dir_read"]["ok"] is True
    for name in ("rc_paste_mention", "rc_paste_image"):
        assert checks[name]["ok"] is False and checks[name]["how"] == "neither" and checks[name]["ended"], checks[name]


def test_remote_probe_stops_at_a_trust_question(tmp_path, fake_claude, monkeypatch):
    _, report, lines = _remote_probe(tmp_path, fake_claude, monkeypatch)
    start = report["checks"]["rc_start"]
    assert start["ok"] is False and start["trust_question"] is True and start["session_start"] == []
    assert "rc_prompt_hooks" not in report["checks"]


def test_remote_probe_never_answers_the_consent_question(tmp_path, fake_claude, monkeypatch):
    monkeypatch.setenv("FAKE_CLAUDE_RC_CONSENT", "1")
    from bridge.chat import command

    command.trust_folder(tmp_path / "state" / "probe-remote")
    _, report, lines = _remote_probe(tmp_path, fake_claude, monkeypatch)
    start = report["checks"]["rc_start"]
    assert start["ok"] is False and start["consent_question"] is True and start["session_start"] == []
    assert "rc_prompt_hooks" not in report["checks"] and any("never answers it" in line for line in lines)
    assert "rc_resume_print" not in report["checks"]


def test_probe_remote_flag_asks_first(tmp_path, fake_claude, monkeypatch, capsys):
    path = _config(tmp_path)
    called = []
    monkeypatch.setattr("bridge.chat.probe.run_probe", lambda *a, **k: called.append(k) or {})
    monkeypatch.setattr(cli, "fetch_status", lambda c, **_: (None, None))
    monkeypatch.setenv("PATH", f"{fake_claude.parent}:/usr/bin:/bin")
    assert cli.main(["--config", str(path), "agent", "probe", "--remote"]) == 1  # stdin is not a terminal
    out = capsys.readouterr().out
    assert "Anthropic stores its transcript" in out and "--yes" in out and not called
    assert cli.main(["--config", str(path), "agent", "probe", "--remote", "--yes"]) == 0
    assert len(called) == 1 and called[0]["remote"] is True and called[0]["fixtures"] is None


def _record_mcp_tokens(monkeypatch) -> list[str]:
    from bridge.chat import command

    tokens: list[str] = []
    real = command.turn_env

    def turn_env(base, *, hook_token, mcp_token):
        tokens.append(mcp_token)
        return real(base, hook_token=hook_token, mcp_token=mcp_token)

    monkeypatch.setattr(command, "turn_env", turn_env)
    return tokens


def test_probe_stops_at_a_listener_of_another_user(tmp_path, fake_claude, monkeypatch, capsys):
    from bridge import localpeer

    path = _config(tmp_path)
    called = []
    monkeypatch.setattr("bridge.chat.probe.run_probe", lambda *a, **k: called.append(k) or {})
    tokens = _record_mcp_tokens(monkeypatch)
    monkeypatch.setenv("PATH", f"{fake_claude.parent}:/usr/bin:/bin")

    def refuse(*args, **kwargs):
        raise localpeer.NotOurs("the listener on 127.0.0.1:1 belongs to another user (uid 1001): not sending the token")

    monkeypatch.setattr(localpeer, "request", refuse)
    assert cli.main(["--config", str(path), "agent", "probe"]) == 1
    captured = capsys.readouterr()
    assert "Not probing: the listener on 127.0.0.1:1 belongs to another user (uid 1001)" in captured.err
    assert "Probing Claude Code" not in captured.out and "not running" not in captured.out
    assert not called and not tokens and not fake_runs(fake_claude)


def test_setup_touches_the_service_only_when_it_runs_this_config(tmp_path, fake_claude, monkeypatch, capsys, home):
    """`zf --config <another config> agent setup` (a probe's, a second install's) never reinstalls the machine's
    service onto that config: only a unit that runs this very config is reinstalled; another is left alone."""
    path = _config(tmp_path)
    other = tmp_path / "other" / "config.json"
    monkeypatch.setenv("PATH", f"{fake_claude.parent}:/usr/bin:/bin")
    monkeypatch.setattr(cli, "_service", lambda: {"ActiveState": "active"})
    installs: list = []
    monkeypatch.setattr(cli, "install_service", lambda p, minimal=False: installs.append(Path(p)) or 0)
    unit = cli.unit_path()
    assert unit == home / ".config/systemd/user/zeffin.service"
    unit.parent.mkdir(parents=True)
    unit.write_text(cli.render_unit(other, tmp_path / "state", minimal=False, agent=True))
    assert cli.main(["--config", str(path), "agent", "setup", "--dir", str(tmp_path / "a")]) == 0
    assert installs == [] and "runs another configuration, so it was left alone" in capsys.readouterr().out
    unit.write_text(cli.render_unit(path, tmp_path / "state", minimal=False, agent=True))
    assert cli.main(["--config", str(path), "agent", "setup", "--dir", str(tmp_path / "a"), "--model", "sonnet"]) == 0
    assert installs == [path] and "Reinstalling the service" in capsys.readouterr().out
    assert cli.runs_config(path, unit) and not cli.runs_config(other, unit)
    assert not cli.runs_config(path, tmp_path / "missing.service")


def test_probe_notes_uses_a_throwaway_bridge_and_reports_flags_only(tmp_path, fake_claude, capsys, monkeypatch):
    """zf agent probe --notes (Claude Code): two turns against a throwaway bridge with the simulator, never the given
    config's MCP listener. The fake claude calls no MCP tool, so nothing is saved and the report is not ok."""
    path = _config(tmp_path)
    c = cfg.load(path)
    c.claude_bin = str(fake_claude)
    cfg.save(path, c)
    monkeypatch.setattr(cli, "fetch_status", _only_throwaway(cli.fetch_status, c.mcp_port))
    assert cli.main(["--config", str(path), "agent", "probe", "--notes", "--json"]) == 0
    out = capsys.readouterr()
    report = json.loads(out.out)
    assert "1/2 notes" in out.err and "2/2 notes" in out.err
    assert set(report) >= {"save", "read", "auto_memory", "ok", "scrubbed"} and report["scrubbed"] is True
    assert report["save"]["note_saved"] is False and report["read"]["note_returned"] is False
    assert report["ok"] is False and report["auto_memory"] == {"files_written": 0}
    assert "Opens on the dial" not in out.out and "sim_dial" not in out.out
    runs = fake_runs(fake_claude)
    assert len(runs) == 2 and all("haiku" in r["argv"] for r in runs)
    assert all("CLAUDE_CODE_DISABLE_AUTO_MEMORY" in r["env"] for r in runs)  # names only: the server's environment
    assert not list((tmp_path / "state").glob("probe-notes-*"))  # nothing left behind


def _only_throwaway(real, given_port):
    def fetch(c, *a, **k):
        assert c.mcp_port != given_port, "the probe asked the given config's bridge"
        return real(c, *a, **k)
    return fetch


def test_memory_files_sees_only_the_probe_folders_own_memory(home, tmp_path):
    from bridge.chat.probe import memory_files

    workdir = tmp_path / "zeffin-probe-notes-ab_12"
    assert memory_files(workdir) == {}
    other = home / ".claude" / "projects" / "-some-repo" / "memory"
    other.mkdir(parents=True)
    (other / "MEMORY.md").write_text("x")  # another session's memory on the same machine
    assert memory_files(workdir) == {}
    own = home / ".claude" / "projects" / "-tmp-zeffin-probe-notes-ab-12" / "memory"
    own.mkdir(parents=True)
    (own / "MEMORY.md").write_text("x")
    assert list(memory_files(workdir)) == [str(own / "MEMORY.md")]
