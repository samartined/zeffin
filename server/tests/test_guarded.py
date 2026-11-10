# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The agents' own configuration and instructions (chat/guarded.py; security scan before 1.0.0), and how each agent
keeps them behind the card: Claude Code's ask rules and its hook's requests."""
import json
import os

from bridge.chat import claude_code, command, guarded, permissions
from bridge.config import Config


def test_the_guarded_paths(tmp_path):
    home, folder = tmp_path / "home", tmp_path / "home" / "projects" / "zeffin-agent"
    folder.mkdir(parents=True)
    env = {"XDG_CONFIG_HOME": str(tmp_path / "xdg")}

    def g(path: str) -> bool:
        return guarded.is_guarded(path, folder, env, home)

    for path in ("opencode.json", "opencode.jsonc", "AGENTS.md", "agents.md", "CLAUDE.md", "CLAUDE.local.md",
                 ".mcp.json", ".opencode", ".opencode/plugin/x.js", ".claude/settings.json", ".claude",
                 "./sub/../AGENTS.md", str(folder / "opencode.json"), str(tmp_path / "xdg" / "opencode" / "x.json"),
                 str(home / ".claude" / "settings.json"), str(home / ".claude.json"), str(folder), str(home),
                 str(tmp_path / "xdg")):
        assert g(path), path
    for path in ("notes.md", "sub/AGENTS.md", "opencode.json.bak", ".opencodex/x", str(home / ".config" / "opencode"),
                 str(tmp_path / "elsewhere" / "CLAUDE.md")):
        assert not g(path), path
    assert guarded.is_guarded(str(home / ".config" / "opencode" / "x"), folder, {}, home)  # without XDG_CONFIG_HOME
    # A symlink in the agent folder that points at a guarded file is that file
    os.symlink(folder / "AGENTS.md", folder / "notes-link.md")
    assert g("notes-link.md")
    os.symlink(folder / ".opencode", folder / "plain")
    assert g("plain/plugin.js")


def test_a_command_that_names_a_guarded_file():
    for c in ("cp x opencode.json", "sed -i s/a/b/ AGENTS.md", "rm -rf .opencode", "echo x >> claude.md",
              "cat ~/.config/opencode/opencode.json", "mv .claude/settings.json x"):
        assert guarded.is_named(c), c
    assert not guarded.is_named("ls -la") and not guarded.is_named("cat notes.md")


def test_claude_codes_turns_ask_for_the_files_its_protected_paths_leave_out():
    """code.claude.com/docs/en/permission-modes ("Protected paths", checked 2026-11-03): Claude Code asks for .claude/
    and .mcp.json in Accept edits, but not for CLAUDE.md, AGENTS.md or OpenCode's files, so every turn carries ask
    rules for those, which an allow rule (a saved "always") never overrides (permissions, "Manage permissions")."""
    for settings in (json.loads(command.settings_json("http://h/hooks", 120)),
                     json.loads(command.remote_settings_json("http://h/hooks"))):
        ask = settings["permissions"]["ask"]
        for rule in ("Edit(CLAUDE.md)", "Edit(CLAUDE.local.md)", "Edit(AGENTS.md)", "Edit(opencode.json)",
                     "Edit(opencode.jsonc)", "Edit(.opencode/**)", "Edit(~/.config/opencode/**)",
                     "Bash(*AGENTS.md*)", "Bash(*opencode.json*)", "Bash(*.opencode*)"):
            assert rule in ask, rule
        assert "allow" not in settings["permissions"] and "defaultMode" not in settings["permissions"]


def test_claude_codes_requests_for_guarded_files_offer_no_always(tmp_path):
    """The hook's request for a guarded file reaches the card like any other (the bridge never answers one by itself),
    without "always", whatever Claude Code suggests."""
    agent = claude_code.ClaudeCode(Config(host="127.0.0.1", phone_port=1, mcp_port=2, phone_token="p" * 40,
                                          mcp_token="m" * 40, view_token="v" * 40, app_token="a" * 40,
                                          state_dir=str(tmp_path), agent_dir=str(tmp_path / "agent"), keepalive_s=0))
    suggestion = [{"type": "addRules", "behavior": "allow", "destination": "session",
                   "rules": [{"toolName": "Edit", "ruleContent": "AGENTS.md"}]}]
    for tool, data in (("Write", {"file_path": str(tmp_path / "agent" / "AGENTS.md"), "content": "x"}),
                       ("Edit", {"file_path": "CLAUDE.md", "old_string": "a", "new_string": "b"}),
                       ("MultiEdit", {"file_path": str(tmp_path / "agent" / ".opencode" / "a.js"), "edits": []}),
                       ("NotebookEdit", {"notebook_path": None}), ("Bash", {"command": "cp x opencode.json"})):
        ask = agent.read_permission({"session_id": "s", "tool_name": tool, "tool_input": data,
                                     "permission_suggestions": suggestion})
        assert ask.rules == [] and ask.input == data and not ask.flipper, tool
    plain = agent.read_permission({"session_id": "s", "tool_name": "Edit", "tool_input": {"file_path": "notes.md"},
                                   "permission_suggestions": suggestion})
    assert plain.rules == ["Edit(AGENTS.md)"]  # Claude Code's own suggestion, for a file that is not guarded
    assert permissions.guarded_request("Bash", {"command": "ls"}, str(tmp_path)) is False
