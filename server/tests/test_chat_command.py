# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The command line, environment and settings of one claude -p turn."""
import json

from bridge import wire
from bridge.chat import command
from bridge.chat.command import TurnSpec, build_command, turn_env
from bridge.wire import valid_rule

SPEC = TurnSpec(claude_bin="/opt/claude", session_id="7f1d3c3e-1111-4222-8333-944455556666", resume=False,
                model="opus", mode="default", mcp_url="http://127.0.0.1:9435/mcp", hooks_url="http://127.0.0.1:9435/hooks")


def test_first_turn_and_resume():
    argv = build_command(SPEC)
    assert argv[:2] == ["/opt/claude", "-p"] and "--bare" not in argv
    assert argv[argv.index("--session-id") + 1] == SPEC.session_id and "--resume" not in argv
    assert argv[argv.index("--permission-mode") + 1] == "default"
    assert argv[argv.index("--setting-sources") + 1] == "project,local"
    assert "--strict-mcp-config" in argv and argv[-1] == "mcp__flipper__*"
    assert "--effort" not in argv
    resumed = build_command(TurnSpec(**{**SPEC.__dict__, "resume": True, "mode": "plan", "effort": "high",
                                        "rules": ["Bash(ls)"]}))
    assert resumed[resumed.index("--resume") + 1] == SPEC.session_id
    assert resumed[resumed.index("--permission-mode") + 1] == "plan" and resumed[-2:] == ["--allowedTools", "mcp__flipper__*"]
    assert json.loads(resumed[resumed.index("--settings") + 1])["permissions"]["allow"] == ["Bash(ls)"]
    assert resumed[resumed.index("--effort") + 1] == "high"


def test_no_secret_on_the_command_line():
    argv = " ".join(build_command(SPEC))
    settings = json.loads(build_command(SPEC)[build_command(SPEC).index("--settings") + 1])
    hook = settings["hooks"]["PermissionRequest"][0]["hooks"][0]
    assert hook["headers"]["Authorization"] == "Bearer $FB_HOOK_TOKEN" and hook["allowedEnvVars"] == ["FB_HOOK_TOKEN"]
    assert hook["timeout"] == 150 and hook["url"].endswith("/hooks/permission-request")
    assert "${ZEFFIN_MCP_TOKEN}" in argv
    env = turn_env({"PATH": "/usr/bin", "ANTHROPIC_API_KEY": "k", "ANTHROPIC_BASE_URL": "u", "CLAUDE_CODE_USE_BEDROCK": "1",
                    "AWS_BEARER_TOKEN_BEDROCK": "b", "CLAUDE_CODE_OAUTH_TOKEN": "o"}, hook_token="h" * 30, mcp_token="m" * 40)
    assert set(env) == {"PATH", "CLAUDE_CODE_OAUTH_TOKEN", "DISABLE_AUTOUPDATER", "FB_HOOK_TOKEN", "ZEFFIN_MCP_TOKEN",
                        "CLAUDE_CODE_DISABLE_AUTO_MEMORY"}
    assert "h" * 30 not in argv and "m" * 40 not in argv


def test_rules_cannot_become_options():
    assert valid_rule("Bash(ls ~/zeffin-agent/ir)") and valid_rule("WebFetch") and valid_rule("mcp__x__y")
    for bad in ("--dangerously-skip-permissions", "-p", "Bash(ls)\nRead", "", "Bash()", 5, "x" * 700):
        assert not valid_rule(bad)
    argv = build_command(TurnSpec(**{**SPEC.__dict__, "rules": ["--bare", "Read"]}))
    assert "--bare" not in argv and argv[-1] == "mcp__flipper__*"
    assert json.loads(argv[argv.index("--settings") + 1])["permissions"]["allow"] == ["Read"]


def test_a_rule_can_never_be_read_as_several():
    """Security scan before 1.0.0, F1: Claude Code's --allowedTools parser split Read(<a path holding ") Bash ">)
    into a bare Bash rule, so every later turn ran any command with no card. Kept rules now go one JSON string each in
    the inline settings' permissions.allow, never on the command line, and a rule whose content could be read as
    several (a quote, a parenthesis that closes early or never) is not valid at all."""
    from bridge.chat import permissions

    evil = 'Read(/home/demo/x") Bash ")'
    for bad in (evil, 'Read(a) Bash(b)', 'Bash(echo "hi")', "Bash(echo 'hi')", "Bash(a`b`)", "Read(a(b)",
                "Read(a)b)", "Bash(x), Bash"):
        assert not valid_rule(bad), bad
    for good in ("Bash(ls -la)", "Bash(git log:*)", "Read(//home/demo/a (1).txt)", "Edit(~/a,b.txt)", "WebFetch(domain:x.y)"):
        assert valid_rule(good), good
    suggested = [{"type": "addRules", "behavior": "allow", "rules": [
        {"toolName": "Read", "ruleContent": '/home/demo/x") Bash "'}, {"toolName": "Bash", "ruleContent": "ls -la"}]}]
    assert permissions.suggested_rules(suggested) == ["Bash(ls -la)"]  # the bad one is never offered
    for argv in (build_command(TurnSpec(**{**SPEC.__dict__, "rules": [evil, "Bash(ls -la)"]})),
                 command.build_remote_command(TurnSpec(**{**SPEC.__dict__, "rules": [evil, "Bash(ls -la)"]}), "r")):
        settings = json.loads(argv[argv.index("--settings") + 1])
        assert settings["permissions"]["allow"] == ["Bash(ls -la)"]
        assert "Bash" not in argv and not any("Read(" in a for a in argv if not a.startswith("{"))
        assert argv[argv.index("--allowedTools") + 1:] == [command.FLIPPER_TOOLS]
    # with no rule kept, no allow list at all
    assert "allow" not in json.loads(command.settings_json("http://x/hooks", 120))["permissions"]


def test_hook_timeout_is_capped():
    assert command.hook_timeout(120) == 150 and command.hook_timeout(9999) == 600


def test_cleanup_days_copied_from_user_settings(tmp_path):
    assert command.user_cleanup_days(tmp_path) is None
    (tmp_path / ".claude").mkdir()
    (tmp_path / ".claude" / "settings.json").write_text('{"cleanupPeriodDays": 90}')
    assert command.user_cleanup_days(tmp_path) == 90
    settings = json.loads(command.settings_json("http://x/hooks", 120, 90))
    assert settings["cleanupPeriodDays"] == 90


def test_agent_folder_settings_that_bill_are_refused(tmp_path):
    assert command.agent_settings_problem(tmp_path) is None
    (tmp_path / ".claude").mkdir()
    path = tmp_path / ".claude" / "settings.json"
    path.write_text('{"env": {"FOO": "1"}}')
    assert command.agent_settings_problem(tmp_path) is None
    path.write_text('{"env": {"ANTHROPIC_API_KEY": "x"}}')
    assert "ANTHROPIC_API_KEY" in command.agent_settings_problem(tmp_path)
    path.write_text('{"apiKeyHelper": "/bin/key"}')
    assert "apiKeyHelper" in command.agent_settings_problem(tmp_path)


def test_version_and_auth_with_the_fake(fake_claude, monkeypatch):
    assert command.claude_version(str(fake_claude)) == "2.1.999"
    assert command.auth_status(str(fake_claude)) == (True, "claude.ai")
    monkeypatch.setenv("FAKE_CLAUDE_AUTH", "none")
    assert command.auth_status(str(fake_claude))[0] is False


def test_modes_are_claude_codes_own():
    assert wire.MODES == ("default", "acceptEdits", "plan", "auto")
    assert wire.normal_mode("manual") == "default" and wire.normal_mode("flipper") == "default"
    assert wire.normal_mode("bypassPermissions") is None and wire.normal_mode("dontAsk") is None
    import pytest
    with pytest.raises(ValueError):
        build_command(TurnSpec(**{**SPEC.__dict__, "mode": "bypassPermissions"}))
    with pytest.raises(ValueError):
        build_command(TurnSpec(**{**SPEC.__dict__, "effort": "ultracode"}))


def test_model_list_with_the_fake(fake_claude, monkeypatch):
    rows = command.list_models(str(fake_claude))
    assert [r["value"] for r in rows] == ["default", "opus", "sonnet", "haiku"]
    monkeypatch.setenv("FAKE_CLAUDE_FABLE", "included")
    assert rows != command.list_models(str(fake_claude))


def test_claude_code_files_of_one_session(tmp_path):
    from bridge.chat.command import delete_session, session_paths

    sid, other = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", "11111111-2222-4333-8444-555555555555"
    project = tmp_path / "projects" / "-srv-agent"
    for name in (f"{sid}.jsonl", f"{sid}.orphaned-9-x.jsonl", f"{sid}.jsonl.superseded-1", f"{other}.jsonl"):
        (project / name).parent.mkdir(parents=True, exist_ok=True)
        (project / name).write_text("{}")
    (project / sid / "tool-results").mkdir(parents=True)
    (project / sid / "tool-results" / "r.txt").write_text("x")
    (tmp_path / "file-history" / sid).mkdir(parents=True)
    outside = tmp_path / "outside"
    outside.mkdir()
    (outside / "keep.txt").write_text("keep")
    (tmp_path / "projects" / "linked").symlink_to(outside)  # never followed
    assert len(session_paths(sid, tmp_path)) == 5
    assert session_paths("../../etc", tmp_path) == [] and session_paths(f"{sid}/..", tmp_path) == []
    assert delete_session(sid, tmp_path) == 5
    assert sorted(p.name for p in project.iterdir()) == [f"{other}.jsonl"]
    assert not (tmp_path / "file-history" / sid).exists() and (outside / "keep.txt").exists()
    assert delete_session(sid, tmp_path) == 0


def test_remote_command_is_interactive_with_the_turns_settings():
    spec = command.TurnSpec(claude_bin="/bin/claude", session_id="s-1", resume=True, model="opus", mode="acceptEdits",
                            mcp_url="http://127.0.0.1:9435/mcp", hooks_url="http://127.0.0.1:9435/hooks",
                            rules=["Bash(touch a.txt)", "--evil"], effort="high")
    argv = command.build_remote_command(spec, "Fix the remote")
    assert argv[:5] == ["/bin/claude", "--remote-control", "Fix the remote", "--resume", "s-1"]
    for flag in ("-p", "--output-format", "--include-partial-messages", "--input-format", "--bare"):
        assert flag not in argv
    assert argv[argv.index("--permission-mode") + 1] == "acceptEdits" and argv[argv.index("--effort") + 1] == "high"
    assert argv[-2:] == ["--allowedTools", command.FLIPPER_TOOLS]
    assert json.loads(argv[argv.index("--settings") + 1])["permissions"]["allow"] == ["Bash(touch a.txt)"]
    hooks = json.loads(argv[argv.index("--settings") + 1])["hooks"]
    assert sorted(hooks) == sorted(command.REMOTE_HOOKS) and "PermissionRequest" not in hooks
    stop = hooks["Stop"][0]["hooks"][0]
    assert stop["url"] == "http://127.0.0.1:9435/hooks/remote/stop"
    assert stop["headers"] == {"Authorization": "Bearer $FB_HOOK_TOKEN",
                               command.BRIDGE_HEADER: "$CLAUDE_CODE_BRIDGE_SESSION_ID"}
    assert stop["allowedEnvVars"] == ["FB_HOOK_TOKEN", "CLAUDE_CODE_BRIDGE_SESSION_ID"]
    probe = json.loads(command.remote_settings_json("http://h/hooks", 30, "true"))
    assert probe["hooks"]["SessionStart"][0]["hooks"][0] == {"type": "command", "command": "true", "timeout": 10}
    assert probe["cleanupPeriodDays"] == 30


def test_turns_remote_control_and_the_model_list_share_their_isolation():
    """The model list runs with the same setting sources, MCP isolation and environment as a turn."""
    spec = TurnSpec(**{**SPEC.__dict__, "mode": "default"})

    def isolated(argv):
        n = len(command.ISOLATION)
        return any(tuple(argv[i:i + n]) == command.ISOLATION for i in range(len(argv)))

    assert isolated(build_command(spec)) and isolated(command.build_remote_command(spec, "x"))
    assert isolated(command.models_argv("/bin/claude"))
    env = command.base_env({"PATH": "/bin", "ANTHROPIC_API_KEY": "k", "CLAUDE_CODE_USE_BEDROCK": "1"})
    assert env == {"PATH": "/bin", "DISABLE_AUTOUPDATER": "1", "CLAUDE_CODE_DISABLE_AUTO_MEMORY": "1"}
    turn = turn_env({"PATH": "/bin", "ANTHROPIC_API_KEY": "k"}, hook_token="h" * 32, mcp_token="m" * 32)
    assert {k: v for k, v in turn.items() if k in env} == env


def test_auto_memory_is_off_in_every_turn_and_remote_control_session():
    """Claude Code's auto memory is loaded as instructions; Zeffin's notes per app replace it. The variable is
    set over whatever the service's environment holds (0 would force it on)."""
    from bridge.chat import remote

    for base in ({"PATH": "/bin"}, {"PATH": "/bin", "CLAUDE_CODE_DISABLE_AUTO_MEMORY": "0"}):
        for free_only in (True, False):
            turn = turn_env(base, hook_token="h" * 32, mcp_token="m" * 32, free_only=free_only)
            assert turn["CLAUDE_CODE_DISABLE_AUTO_MEMORY"] == "1"
            assert remote.remote_env(turn)["CLAUDE_CODE_DISABLE_AUTO_MEMORY"] == "1"
            assert command.base_env(base, free_only)["CLAUDE_CODE_DISABLE_AUTO_MEMORY"] == "1"  # the model list too
