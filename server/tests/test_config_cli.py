# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
import json
import os
import time

import pytest

from bridge import config as cfg
from bridge.cli import report


def test_create_and_load(tmp_path):
    path = tmp_path / "c" / "config.json"
    c = cfg.create(path, "100.100.1.2", 9434, 9435, tmp_path / "state")
    assert oct(path.stat().st_mode & 0o777) == "0o600"
    assert len({c.phone_token, c.mcp_token, c.view_token}) == 3
    assert cfg.load(path).phone_url() == "wss://100.100.1.2:9434/phone"
    with pytest.raises(cfg.ConfigError):
        cfg.create(path, "100.100.1.2", 9434, 9435, None)


@pytest.mark.parametrize("host", ["0.0.0.0", "192.168.1.2", "8.8.8.8", "100.128.0.1", "example.com"])
def test_public_host_must_be_tailnet_or_loopback(host):
    with pytest.raises(cfg.ConfigError):
        cfg.check_public_host(host)


def test_load_refuses_world_readable(tmp_path):
    path = tmp_path / "config.json"
    cfg.create(path, "127.0.0.1", 9434, 9435, tmp_path)
    os.chmod(path, 0o644)
    with pytest.raises(cfg.ConfigError):
        cfg.load(path)


def test_report_summarizes_sessions():
    t = time.time() - 600
    events = [
        {"ts": t, "ev": "ws_open"}, {"ts": t + 2, "ev": "ready", "transport": "BLE", "after_open_s": 2, "mtu": 23},
        {"ts": t + 2.5, "ev": "first_frame", "after_ready_s": 0.5},
        {"ts": t + 60, "ev": "window", "connected": True, "fps": 3.0, "max_gap_s": 4.0, "frames": 180, "seconds": 60},
        {"ts": t + 61, "ev": "ping", "ok": True, "rtt_ms": 80},
        {"ts": t + 100, "ev": "close", "reason": "Phone disconnected", "frames": 200, "ready_duration_s": 98,
         "phone_code": 1011, "phone_reason": "Flipper: disconnected: the Flipper stopped answering: out of range or off (status 8)"},
        {"ts": t + 105, "ev": "ws_open"}, {"ts": t + 107, "ev": "ready", "transport": "BLE"},
        {"ts": t + 110, "ev": "ws_rejected", "reason": "bad_token", "has_header": True, "bearer_prefix": True,
         "token_length": 42, "expected_length": 43},
    ]
    out = report(events, 1)
    assert "Phone connections: 2, reached ready: 2" in out
    assert "Reconnect time" in out and "Keepalive pings: 1/1 ok" in out
    assert "Rejected phone connections: bad_token x1" in out and "length 42 (expected 43)" in out
    assert "MTU 23" in out and "(phone: Flipper: disconnected: the Flipper stopped answering: out of range or off (status 8))" in out


def test_report_counts_settles_that_found_the_stop_reply():
    t = time.time() - 600
    events = [{"ts": t, "ev": "settle", "dropped_bytes": 9, "synced": True},
              {"ts": t + 1, "ev": "settle", "dropped_bytes": 0, "synced": False},
              {"ts": t + 2, "ev": "settle", "dropped_bytes": 5}]  # before the stop's reply was looked for
    assert "Session starts that found the stop's reply (the stream read from a message boundary): 1/2" in report(events, 1)
    assert "stop's reply" not in report(events[2:], 1)


def test_report_names_starts_with_a_restored_pause_or_stop():
    t = time.time() - 600
    events = [{"ts": t, "ev": "start"},
              {"ts": t + 50, "ev": "start", "gate": "paused", "gate_by": "app", "gate_changed_at": t + 20}]
    out = report(events, 1)
    assert "Starts with the agent paused or stopped from before the restart: 1 (last " in out
    assert ": paused by app since " in out
    assert "from before the restart" not in report(events[:1], 1)


def test_unit_quotes_paths_and_hardens(tmp_path):
    from bridge.cli import render_unit

    unit = render_unit(tmp_path / "my config%.json", tmp_path / "state", minimal=False)
    assert '"--config"' in unit and "my config%%.json" in unit
    assert "ProtectSystem=strict" in unit and "NoNewPrivileges=true" in unit and "ReadWritePaths=" in unit
    # These change the capability bounding set and fail in user services with 218/CAPABILITIES.
    for directive in ("PrivateDevices=", "ProtectKernelModules=", "CapabilityBoundingSet=", "ProtectClock=", "ProtectKernelLogs="):
        assert directive not in unit
    assert "ProtectSystem" not in render_unit(tmp_path / "c.json", tmp_path / "state", minimal=True)


def test_unit_working_directory_is_bare_path(tmp_path):
    from bridge.cli import render_unit

    line = next(l for l in render_unit(tmp_path / "c.json", tmp_path, False).splitlines() if l.startswith("WorkingDirectory="))
    assert '"' not in line and line.split("=", 1)[1].startswith("/")


def test_install_service_falls_back_to_minimal(tmp_path, monkeypatch):
    from bridge import cli

    path = tmp_path / "config.json"
    cfg.create(path, "127.0.0.1", 9434, 9435, tmp_path / "state")
    monkeypatch.setattr(cli.Path, "home", staticmethod(lambda: tmp_path))
    monkeypatch.setattr(cli.shutil, "which", lambda name: "/usr/bin/systemctl")
    written = []

    def fake_check(dest, unit, settle_s=4.0):
        written.append(unit)
        return ("ProtectSystem" not in unit, "active, restarts=0" if "ProtectSystem" not in unit else "activating, restarts=3")

    monkeypatch.setattr(cli, "_start_and_check", fake_check)
    assert cli.main(["--config", str(path), "install-service"]) == 0
    assert len(written) == 2 and "ProtectSystem" in written[0] and "ProtectSystem" not in written[1]


@pytest.fixture(autouse=True)
def claude_config_dir(tmp_path, monkeypatch):
    """zf mcp-config and zf rotate-tokens write Claude Code's ~/.claude.json: never the real one."""
    monkeypatch.setenv("CLAUDE_CONFIG_DIR", str(tmp_path / "claude-config"))


def test_rotate_tokens_all_and_only(tmp_path, capsys):
    from bridge import cli

    path = tmp_path / "config.json"
    old = cfg.create(path, "100.100.1.2", 9434, 9435, tmp_path / "state")
    assert cli.main(["--config", str(path), "rotate-tokens"]) == 0
    new = cfg.load(path)
    assert oct(path.stat().st_mode & 0o777) == "0o600"
    assert (new.host, new.phone_port, new.mcp_port, new.state_dir) == (old.host, old.phone_port, old.mcp_port, old.state_dir)
    assert all(getattr(new, f) != getattr(old, f) for f in ("phone_token", "mcp_token", "view_token"))
    out = capsys.readouterr().out
    assert "no `flipper` entry" in out and "mcp-config" in out and " restart" in out and "claude mcp add" not in out
    assert new.phone_token not in out and new.mcp_token not in out and new.view_token not in out

    assert cli.main(["--config", str(path), "rotate-tokens", "--only", "phone"]) == 0
    after = cfg.load(path)
    assert after.phone_token != new.phone_token
    assert (after.mcp_token, after.view_token) == (new.mcp_token, new.view_token)
    assert "claude mcp" not in capsys.readouterr().out


def test_a_config_moved_from_the_old_names_uses_the_new_folders(tmp_path):
    """0.15.x saved state_dir and agent_dir resolved, under flipper-bridge-data and flipper-agent. Once the folders are
    moved to their new names (docs/guide/updating.md), load() uses and saves the new paths; a path whose new folder is
    not there, or that never had the old name, is kept."""
    old_data, old_agent = tmp_path / "flipper-bridge-data", tmp_path / "flipper-agent"
    path = tmp_path / "zeffin-data" / "config.json"
    c = cfg.create(path, "127.0.0.1", 9434, 9435, old_data / "state")
    cfg.save(path, cfg.Config(**{**c.__dict__, "agent_dir": str(old_agent)}))
    assert cfg.load(path).state_dir == str(old_data / "state")  # the new folders are not both there yet: kept
    (tmp_path / "zeffin-agent").mkdir()
    loaded = cfg.load(path)
    assert loaded.agent_dir == str(tmp_path / "zeffin-agent") and loaded.state_dir == str(old_data / "state")
    (tmp_path / "zeffin-data" / "state").mkdir()
    loaded = cfg.load(path)
    assert loaded.state_dir == str(tmp_path / "zeffin-data" / "state")
    on_disk = json.loads(path.read_text())  # saved, so every later reader sees the new paths
    assert on_disk["state_dir"] == loaded.state_dir and on_disk["agent_dir"] == loaded.agent_dir
    assert cfg.renamed("/srv/other/state") is None and cfg.renamed("") is None


def test_config_json_carries_its_format_and_a_newer_one_is_refused(tmp_path):
    import json

    path = tmp_path / "config.json"
    cfg.create(path, "127.0.0.1", 9434, 9435, tmp_path)
    data = json.loads(path.read_text())
    assert data["version"] == cfg.CONFIG_VERSION == 2  # 2: opencode_bin
    cfg.save(path, cfg.load(path))
    assert json.loads(path.read_text())["version"] == 2
    del data["version"]  # written before the format existed: read as format 1
    path.write_text(json.dumps(data))
    assert cfg.load(path).phone_port == 9434
    path.write_text(json.dumps({**data, "version": 3}))
    with pytest.raises(cfg.ConfigError, match="newer zeffin"):
        cfg.load(path)
    assert json.loads(path.read_text())["version"] == 3  # left as it was


def _claude_json(tmp_path):
    import os
    from pathlib import Path

    return Path(os.environ["CLAUDE_CONFIG_DIR"]) / ".claude.json"


def test_mcp_config_writes_the_user_entry_without_printing_the_token(tmp_path, capsys):
    import json

    from bridge import cli

    path = tmp_path / "config.json"
    c = cfg.create(path, "100.100.1.2", 9434, 9435, tmp_path / "state")
    claude_json = _claude_json(tmp_path)
    claude_json.parent.mkdir()
    claude_json.write_text(json.dumps({"projects": {"/x": {"hasTrustDialogAccepted": True}},
                                       "mcpServers": {"other": {"type": "stdio", "command": "x"}}}))
    claude_json.chmod(0o644)
    assert cli.main(["--config", str(path), "mcp-config", "--yes"]) == 0
    out = capsys.readouterr().out
    assert c.mcp_token not in out and "Registered `flipper`" in out and "claude mcp list" in out
    assert "claude mcp add" not in out
    data = json.loads(claude_json.read_text())
    assert data["mcpServers"]["flipper"] == {"type": "http", "url": "http://127.0.0.1:9435/mcp",
                                             "headers": {"Authorization": f"Bearer {c.mcp_token}"}}
    assert data["mcpServers"]["other"] == {"type": "stdio", "command": "x"} and data["projects"]["/x"]
    assert oct(claude_json.stat().st_mode & 0o777) == "0o600"  # it holds a token: no longer readable by others
    assert cli.main(["--config", str(path), "mcp-config", "--yes"]) == 0
    assert "Already registered" in capsys.readouterr().out


def test_mcp_config_asks_first_and_writes_nothing_without_a_terminal(tmp_path, capsys, monkeypatch):
    import sys

    from bridge import cli

    path = tmp_path / "config.json"
    c = cfg.create(path, "100.100.1.2", 9434, 9435, tmp_path / "state")
    monkeypatch.setattr(sys.stdin, "isatty", lambda: False)
    assert cli.main(["--config", str(path), "mcp-config"]) == 0
    out = capsys.readouterr().out
    assert "--yes" in out and "${ZEFFIN_MCP_TOKEN}" in out and c.mcp_token not in out
    assert not _claude_json(tmp_path).exists()
    monkeypatch.setattr(sys.stdin, "isatty", lambda: True)
    monkeypatch.setattr("builtins.input", lambda prompt: "n")
    assert cli.main(["--config", str(path), "mcp-config"]) == 0
    assert not _claude_json(tmp_path).exists()
    monkeypatch.setattr("builtins.input", lambda prompt: "y")
    assert cli.main(["--config", str(path), "mcp-config"]) == 0
    assert c.mcp_token in _claude_json(tmp_path).read_text() and c.mcp_token not in capsys.readouterr().out


def test_mcp_config_leaves_an_unexpected_file_alone(tmp_path, capsys):
    from bridge import cli

    path = tmp_path / "config.json"
    cfg.create(path, "100.100.1.2", 9434, 9435, tmp_path / "state")
    claude_json = _claude_json(tmp_path)
    claude_json.parent.mkdir()
    for text in ("not json", '{"mcpServers": []}', "[]"):
        claude_json.write_text(text)
        assert cli.main(["--config", str(path), "mcp-config", "--yes"]) == 1
        assert "left as is" in capsys.readouterr().out and claude_json.read_text() == text


def test_rotate_tokens_updates_the_registered_entry(tmp_path, capsys):
    import json

    from bridge import cli

    path = tmp_path / "config.json"
    cfg.create(path, "100.100.1.2", 9434, 9435, tmp_path / "state")
    assert cli.main(["--config", str(path), "mcp-config", "--yes"]) == 0
    assert cli.main(["--config", str(path), "rotate-tokens", "--only", "mcp"]) == 0
    new = cfg.load(path)
    out = capsys.readouterr().out
    assert "updated the `flipper` entry" in out and new.mcp_token not in out
    entry = json.loads(_claude_json(tmp_path).read_text())["mcpServers"]["flipper"]
    assert entry["headers"]["Authorization"] == f"Bearer {new.mcp_token}"


def test_firmware_sources_are_set_checked_and_removed(tmp_path, capsys):
    from bridge.cli import main

    path = tmp_path / "config.json"
    cfg.create(path, "127.0.0.1", 9434, 9435, tmp_path / "state")
    assert cfg.load(path).firmware_sources == {}
    tree = tmp_path / "momentum-src"
    tree.mkdir()
    assert main(["--config", str(path), "set-firmware-sources", "momentum", str(tree)]) == 0
    assert cfg.load(path).firmware_sources == {"momentum": str(tree.resolve())}
    assert "flipper_app reads the apps' READMEs from it" in capsys.readouterr().out
    assert main(["--config", str(path), "set-firmware-sources", "nosuch", str(tree)]) == 2
    assert main(["--config", str(path), "set-firmware-sources", "momentum", str(tmp_path / "missing")]) == 2
    assert main(["--config", str(path), "set-firmware-sources", "momentum", "--remove"]) == 0
    assert cfg.load(path).firmware_sources == {}
    with pytest.raises(cfg.ConfigError):
        cfg.Config(**{**cfg.load(path).__dict__, "firmware_sources": {"momentum": "relative/path"}}).validate()
    with pytest.raises(cfg.ConfigError):
        cfg.Config(**{**cfg.load(path).__dict__, "firmware_sources": {"../x": "/abs"}}).validate()
