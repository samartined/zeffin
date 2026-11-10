# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The day-to-day commands of `zf`: status, update, menu, logs and the PATH link."""
import os
import subprocess
import sys
import time
from dataclasses import replace
from pathlib import Path

import pytest
from starlette.testclient import TestClient

from bridge import __version__, cli, report
from bridge.app import mcp_app
from bridge.config import Config
from bridge.hub import Hub
from bridge.paircodes import PairCodes

CFG = Config(host="127.0.0.1", phone_port=19434, mcp_port=19435, phone_token="p" * 40,
             mcp_token="m" * 40, view_token="v" * 40, app_token="a" * 40, state_dir="/tmp", keepalive_s=0)


@pytest.fixture
def hub(tmp_path):
    return Hub(tmp_path, keepalive_s=0)


def test_status_endpoint_needs_the_mcp_token(hub):
    with TestClient(mcp_app(hub, CFG, codes=PairCodes()), base_url="http://127.0.0.1:19435") as client:
        assert client.get("/status").status_code == 401
        assert client.get("/status", headers={"Authorization": "Bearer " + "p" * 40}).status_code == 401
        r = client.get("/status", headers={"Authorization": "Bearer " + "m" * 40})
        assert r.status_code == 200
        data = r.json()
        assert data["version"] == __version__ and data["gate"] == "active" and data["connected"] is False
        assert data["firmware"]["state"] == "none" and data["firmware"]["origin"] is None
        head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=Path(cli.__file__).parent, capture_output=True, text=True)
        assert data["commit"] == head.stdout.strip()  # the checkout these tests run from
        assert "m" * 40 not in r.text


def test_describe_status_lines():
    data = {"version": __version__, "connected": True, "phone_attached": True, "transport": "BLE",
            "relay": {"app": "0.1.0", "device": "Demo", "mtu": 414}, "gate": "paused", "gate_changed_at": None,
            "gate_by": "app", "screen": {"seq": 3032, "stale": False, "age_s": 0.4}, "session_uptime_s": 720,
            "last_ping_ms": 38.0}
    text = "\n".join(cli.describe_status(data, __version__))
    assert "Relay:   connected · BLE · app 0.1.0 · Demo · MTU 414 · up 12 min" in text
    assert "Agent:   paused" in text and "frame #3032" in text and "38 ms" in text
    assert "installed" not in text
    older = "\n".join(cli.describe_status({**data, "version": "0.0.9", "connected": False, "phone_attached": False}, __version__))
    assert "installed " + __version__ in older and "Relay:   not connected" in older


def test_status_when_nothing_answers():
    data, error = cli.fetch_status(replace(CFG, mcp_port=1), timeout=0.5)
    assert data is None and "not running" in error


def test_status_refuses_a_listener_of_another_user(monkeypatch):
    import socket

    from bridge import localpeer

    import threading

    server = socket.create_server(("127.0.0.1", 0))
    accepted: list = []
    # it accepts, as a real server does: before accept() the kernel shows uid 0, which tells nothing
    threading.Thread(target=lambda: accepted.append(server.accept()[0]), daemon=True).start()
    check = localpeer.check
    monkeypatch.setattr(localpeer, "check", lambda sock, proc: check(sock, proc, uid=os.getuid() + 1))
    try:
        data, error = cli.fetch_status(replace(CFG, mcp_port=server.getsockname()[1]), timeout=0.5)
    finally:
        for s in accepted:
            s.close()
        server.close()
    assert data is None and f"belongs to another user (uid {os.getuid()})" in error and "not sending the token" in error


def test_pair_refuses_a_listener_of_another_user(tmp_path, monkeypatch, capsys):
    from bridge import config as cfg
    from bridge import localpeer

    path = tmp_path / "config.json"
    cfg.create(path, "127.0.0.1", 19434, 19435, tmp_path / "state")

    def refuse(*args, **kwargs):
        raise localpeer.NotOurs("the listener on 127.0.0.1:19435 belongs to another user (uid 1001): not sending the token")

    monkeypatch.setattr(localpeer, "request", refuse)
    assert cli.main(["--config", str(path), "pair"]) == 1
    assert "Not pairing: the listener on 127.0.0.1:19435 belongs to another user" in capsys.readouterr().err


def test_chat_refuses_a_listener_of_another_user(tmp_path, monkeypatch, capsys):
    from bridge import localpeer
    from bridge.chat import client

    def refuse(host, port, timeout):
        raise localpeer.NotOurs(f"the listener on {host}:{port} belongs to another user (uid 1001): not sending the token")

    monkeypatch.setattr(localpeer, "connect", refuse)
    assert client.run(CFG, "J7pCvj_X0luG9SgZ7rrB0TGpD72G8BVi5G3NaLsCPS8") == 1
    assert "Not connecting: the listener on 127.0.0.1:19434 belongs to another user" in capsys.readouterr().out
    monkeypatch.setattr(localpeer, "request", lambda *a, **k: refuse("127.0.0.1", 19434, 1))
    upload = tmp_path / "a.txt"
    upload.write_text("x")
    with pytest.raises(RuntimeError, match="belongs to another user"):
        client.upload(CFG, "c1", upload, "J7pCvj_X0luG9SgZ7rrB0TGpD72G8BVi5G3NaLsCPS8")


def test_without_a_command_and_without_a_terminal_prints_help(capsys, monkeypatch):
    monkeypatch.setattr(sys.stdin, "isatty", lambda: False)
    assert cli.main([]) == 2
    assert "status" in capsys.readouterr().out


def test_menu_runs_the_chosen_command(monkeypatch, capsys):
    answers = iter(["99", "1", "q"])
    monkeypatch.setattr("builtins.input", lambda prompt="": next(answers))
    ran = []
    monkeypatch.setattr(cli, "main", lambda argv: ran.append(argv) or 0)
    assert cli.menu(Path("/tmp/c.json")) == 0
    assert ran == [["--config", "/tmp/c.json", "status"]]
    assert "Type a number" in capsys.readouterr().out


def test_logs_hands_over_to_journalctl(monkeypatch):
    monkeypatch.setattr(cli.shutil, "which", lambda name: "/usr/bin/" + name)
    seen = []
    monkeypatch.setattr(cli.os, "execvp", lambda file, args: seen.append(args))
    cli.main(["logs", "-f", "-n", "20"])
    assert seen == [["journalctl", "--user", "-u", "zeffin.service", "-n", "20", "-f"]]


def test_link_cli(tmp_path, monkeypatch):
    venv = tmp_path / "venv" / "bin"
    venv.mkdir(parents=True)
    (venv / "zf").write_text("#!/bin/sh\n")
    monkeypatch.setattr(cli.sys, "executable", str(venv / "python"))
    monkeypatch.setenv("PATH", "/usr/bin")
    message = cli.link_cli(tmp_path)
    link = tmp_path / ".local" / "bin" / "zf"
    assert os.readlink(link) == str(venv / "zf") and "not on your PATH" in message
    assert "ready" in cli.link_cli(tmp_path)
    link.unlink()
    link.write_text("mine")
    assert "left as is" in cli.link_cli(tmp_path) and link.read_text() == "mine"


def _git(cwd, *args):
    subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@example.invalid", "-c", "commit.gpgsign=false",
                    *args], cwd=cwd, check=True, capture_output=True)


@pytest.fixture
def repos(tmp_path, monkeypatch):
    """A remote and a clone, with update's pip and restart replaced."""
    remote, clone = tmp_path / "remote", tmp_path / "clone"
    remote.mkdir()
    _git(remote, "init", "-q", "-b", "main")
    (remote / "a").write_text("1")
    _git(remote, "add", "a")
    _git(remote, "commit", "-q", "-m", "one")
    _git(tmp_path, "clone", "-q", str(remote), str(clone))
    installs = []
    monkeypatch.setattr(cli, "repo_root", lambda: clone)
    monkeypatch.setattr(cli, "pip_install", lambda root: installs.append(root) or 0)
    monkeypatch.setattr(cli, "_service", lambda: None)
    return remote, clone, installs


def test_update_fast_forwards_and_reinstalls(repos, capsys):
    remote, clone, installs = repos
    # Without a service to ask, nothing to pull means nothing to do, and it says why it could not tell.
    assert cli.main(["update"]) == 0
    out = capsys.readouterr().out
    assert "Already up to date" in out and "no systemd service installed" in out and installs == []
    (remote / "a").write_text("2")
    _git(remote, "commit", "-q", "-am", "two")
    assert cli.main(["update"]) == 0
    assert (clone / "a").read_text() == "2" and installs == [clone]
    assert "1 new commit" in capsys.readouterr().out


@pytest.fixture
def service(repos, monkeypatch):
    """A running service whose /status reports `status["data"]`; restarts are recorded."""
    status = {"data": None, "error": None}
    restarts = []
    monkeypatch.setattr(cli, "_service", lambda: {"ActiveState": "active"})
    monkeypatch.setattr(cli.cfg, "load", lambda path: CFG)
    monkeypatch.setattr(cli, "fetch_status", lambda c: (status["data"], status["error"]))
    monkeypatch.setattr(cli, "_control", lambda action: restarts.append(action) or 0)
    monkeypatch.setattr(cli, "self_command", lambda config, *args: ["true"])
    return status, restarts


def _head(clone):
    return subprocess.run(["git", "rev-parse", "HEAD"], cwd=clone, capture_output=True, text=True).stdout.strip()


def test_update_leaves_a_service_that_runs_the_checked_out_code(repos, service, capsys):
    _, clone, installs = repos
    status, restarts = service
    status["data"] = {"version": __version__, "commit": _head(clone)}
    assert cli.main(["update"]) == 0
    assert "the service runs this code" in capsys.readouterr().out and installs == [] and restarts == []


def test_update_restarts_a_service_that_runs_other_code(repos, service, capsys):
    # A git checkout of another branch, or an update whose restart is still pending: nothing to pull.
    _, clone, installs = repos
    status, restarts = service
    status["data"] = {"version": __version__, "commit": "0" * 40}
    assert cli.main(["update"]) == 0
    out = capsys.readouterr().out
    assert f"runs 0000000, not the checked-out {_head(clone)[:7]}" in out
    assert installs == [clone] and restarts == ["restart"]
    # A server from before /status had a commit runs older code too.
    status["data"] = {"version": "0.9.0"}
    assert cli.main(["update"]) == 0 and "runs older code" in capsys.readouterr().out
    assert installs == [clone, clone] and restarts == ["restart", "restart"]


def test_update_says_when_it_cannot_tell_what_the_service_runs(repos, service, capsys):
    _, _, installs = repos
    status, restarts = service
    status["error"] = "not running (nothing answers on the local port)"
    assert cli.main(["update"]) == 0
    out = capsys.readouterr().out
    assert "Already up to date. Could not tell which code the service runs (server not running" in out
    assert installs == [] and restarts == []
    status["error"], status["data"] = None, {"version": __version__, "commit": None}
    assert cli.main(["update"]) == 0 and "does not know its git commit" in capsys.readouterr().out
    assert installs == [] and restarts == []


def test_update_refuses_local_changes_and_rewritten_history(repos, capsys):
    remote, clone, installs = repos
    (clone / "a").write_text("local")
    assert cli.main(["update"]) == 1 and "local changes" in capsys.readouterr().out
    _git(clone, "checkout", "-q", "a")
    _git(remote, "commit", "-q", "--amend", "-m", "rewritten")
    assert cli.main(["update"]) == 1
    out = capsys.readouterr().out
    assert "reset --hard @{u}" in out and installs == [] and (clone / "a").read_text() == "1"


def test_new_processes_ignore_a_stray_bridge_folder(tmp_path):
    # The update and menu run the CLI again; a leftover bridge/ folder where zf was typed must not shadow it.
    (tmp_path / "bridge" / "__pycache__").mkdir(parents=True)
    out = subprocess.run(cli.self_command(tmp_path / "missing.json", "status"), cwd=tmp_path, capture_output=True, text=True)
    assert "ImportError" not in out.stderr and "not found" in out.stderr


def test_describe_status_with_the_chat(monkeypatch):
    monkeypatch.setattr(cli, "_prog", lambda: "zf")
    base = {"version": __version__, "connected": False, "phone_attached": False, "gate": "active", "screen": {}}
    chat = {"configured": True, "problem": None, "claude": {"version": "2.1.308", "auth": "claude.ai"}, "model": "opus",
            "agent_dir": str(Path.home() / "projects" / "zeffin-agent"), "turn": None, "last_turn_at": None, "conversations": 3,
            "service_env_billing_vars": ["ANTHROPIC_API_KEY"]}
    text = "\n".join(cli.describe_status({**base, "chat": chat}, __version__))
    assert "Claude:  2.1.308 · claude.ai login · model opus · agent ~/projects/zeffin-agent" in text
    assert "Chat:    no turn running · 3 saved conversations (zf chat --list)" in text and "never passed to claude" in text
    text = "\n".join(cli.describe_status({**base, "chat": {**chat, "configured": False, "problem": "not_configured"}}, __version__))
    assert "run zf agent setup" in text and "Chat:" not in text


# ---------------------------------------------------------------------------------------------- the server's key

def _init(tmp_path, capsys):
    path = tmp_path / "data" / "config.json"
    assert cli.main(["--config", str(path), "init", "--host", "127.0.0.1", "--state-dir", str(tmp_path / "s")]) == 0
    return path, capsys.readouterr().out


def _no_key_material(text: str, folder: Path) -> bool:
    body = (folder / "tls-key.pem").read_text()
    return "PRIVATE" not in text and "BEGIN" not in text and not any(line in text for line in body.splitlines()[1:-1])


def test_init_makes_the_key_and_prints_only_its_fingerprint(tmp_path, capsys):
    from bridge import tlskey

    path, out = _init(tmp_path, capsys)
    key = tlskey.read(path.parent)
    assert key is not None and f"Server key:    {key.fingerprint}" in out and "wss://127.0.0.1:9434/phone" in out
    assert "https://127.0.0.1:9434/view" in out and _no_key_material(out, path.parent)
    assert (path.parent / "tls-key.pem").stat().st_mode & 0o777 == 0o600


def test_zf_key_prints_the_fingerprint_the_pin_and_the_files(tmp_path, capsys):
    from bridge import tlskey

    path, _ = _init(tmp_path, capsys)
    key = tlskey.read(path.parent)
    assert cli.main(["--config", str(path), "key"]) == 0
    out = capsys.readouterr().out
    assert key.fingerprint in out and key.pin in out and str(key.key_path) in out and str(key.cert_path) in out
    assert _no_key_material(out, path.parent)
    assert cli.main(["--config", str(path), "key", "--pin"]) == 0
    assert capsys.readouterr().out == key.pin + "\n"


def test_zf_key_new_asks_first_then_changes_the_key_and_the_phone_and_app_tokens(tmp_path, capsys, monkeypatch):
    from bridge import config as cfg
    from bridge import tlskey

    path, _ = _init(tmp_path, capsys)
    old, before = tlskey.read(path.parent), cfg.load(path)
    monkeypatch.setattr(sys.stdin, "isatty", lambda: True)
    monkeypatch.setattr("builtins.input", lambda prompt="": "n")
    assert cli.main(["--config", str(path), "key", "new"]) == 1
    assert tlskey.read(path.parent) == old and "Nothing changed" in capsys.readouterr().out
    monkeypatch.setattr("builtins.input", lambda prompt="": "y")
    assert cli.main(["--config", str(path), "key", "new"]) == 0
    out = capsys.readouterr().out
    new, after = tlskey.read(path.parent), cfg.load(path)
    assert new.pin != old.pin and new.fingerprint in out and "restart" in out and "pair" in out
    assert "rotate-tokens --only view --only mcp" in out and _no_key_material(out, path.parent)
    assert after.phone_token != before.phone_token and after.app_token != before.app_token
    assert (after.view_token, after.mcp_token) == (before.view_token, before.mcp_token)
    assert not any(t in out for t in (after.phone_token, after.app_token))
    assert cli.main(["--config", str(path), "key", "new", "--yes", "--keep-tokens"]) == 0
    assert cfg.load(path).app_token == after.app_token and tlskey.read(path.parent).pin != new.pin


def test_status_shows_the_running_key_and_warns_when_a_new_one_waits(hub, tmp_path):
    from bridge import tlskey

    running, _ = tlskey.ensure(tmp_path, "127.0.0.1")
    codes = PairCodes(CFG.base_url(), running.pin)
    with TestClient(mcp_app(hub, CFG, codes=codes, key=running), base_url="http://127.0.0.1:19435") as client:
        data = client.get("/status", headers={"Authorization": "Bearer " + "m" * 40}).json()
        assert data["key"] == {"pin": running.pin, "fingerprint": running.fingerprint}
        assert "PRIVATE" not in json_text(data)
        text = "\n".join(cli.describe_status(data, __version__, running.pin))
        assert f"Key:     {running.fingerprint}" in text and "waits" not in text
        new = tlskey.new(tmp_path, "127.0.0.1")  # zf key new without zf restart
        assert client.get("/status", headers={"Authorization": "Bearer " + "m" * 40}).json()["key"]["pin"] == running.pin
        text = "\n".join(cli.describe_status(data, __version__, new.pin))
        assert f"Key:     {running.fingerprint} (a new key waits for" in text
        payload = client.post("/pair", headers={"Authorization": "Bearer " + "a" * 40}).json()["payload"]
        assert running.pin in payload and new.pin not in payload  # the QR code names the key the listener serves


def json_text(data) -> str:
    import json

    return json.dumps(data)


def _at(hour: int, minute: int) -> float:
    return time.mktime((2026, 11, 3, hour, minute, 0, 0, 0, -1))


DEVICES = {"since": _at(14, 2), "items": [
    {"address": "100.98.12.7", "this_server": False, "name": "demo-phone", "os": "android", "lookup": "done",
     "connected": {"app": 2, "relay": 1}, "used": ["app", "pairing", "relay"], "first_seen": _at(14, 5),
     "last_seen": _at(16, 0)},
    {"address": "100.98.12.8", "this_server": True, "name": None, "os": None, "lookup": "none",
     "connected": {"app": 1}, "used": ["app"], "first_seen": _at(16, 1), "last_seen": _at(16, 1)},
    {"address": "100.98.12.9", "this_server": False, "name": None, "os": None, "lookup": "failed",
     "connected": {}, "used": ["view"], "first_seen": _at(15, 10), "last_seen": _at(15, 40)},
    {"address": "100.98.12.10", "this_server": False, "name": None, "os": None, "lookup": "pending",
     "connected": {}, "used": ["view"], "first_seen": time.mktime((2026, 11, 2, 9, 30, 0, 0, 0, -1)),
     "last_seen": time.mktime((2026, 11, 2, 9, 31, 0, 0, 0, -1))},
]}


def test_zf_status_lists_the_devices():
    lines = report.describe_devices(DEVICES, now=_at(16, 2))
    assert lines[0] == "Devices: 2 connected now · 4 seen since the server started (14:02)"
    assert lines[1].split() == ["demo-phone", "android", "100.98.12.7", "relay,", "app", "×2,", "pairing", "connected",
                                "now", "·", "first", "14:05"]
    assert lines[2].split() == ["this", "server", "100.98.12.8", "app", "connected", "now", "·", "first", "16:01"]
    assert lines[3].split() == ["(no", "name)", "100.98.12.9", "view", "last", "15:40", "·", "first", "15:10"]
    assert lines[4].split() == ["(looking", "up)", "100.98.12.10", "view", "last", "11-02", "09:31", "·", "first",
                                "11-02", "09:30"]
    assert len({line.index("100.98.12.") for line in lines[1:]}) == 1  # in columns


def test_zf_status_devices_are_terminal_safe_and_short():
    devices = {"since": _at(14, 2), "items": [{**DEVICES["items"][0], "name": "demo\x1b[2J" + "x" * 40,
                                               "os": "android\x1b]0;x\x07"}]}
    text = "\n".join(report.describe_devices(devices, now=_at(16, 2)))
    assert "\x1b" not in text and "\x07" not in text and "\\u001b" in text and "…" in text


def test_zf_status_without_devices_or_from_an_older_server():
    assert report.describe_devices({"since": _at(14, 2), "items": []}, now=_at(16, 2)) == [
        "Devices: none since the server started (14:02)"]
    assert report.describe_devices(None) == [] and report.describe_devices({"since": 1}) == []
    data = {"version": __version__, "connected": False, "phone_attached": False, "gate": "active",
            "screen": {"seq": 0}}
    assert not any(line.startswith("Devices:") for line in cli.describe_status(data, __version__))
    with_devices = cli.describe_status({**data, "devices": DEVICES}, __version__)
    assert with_devices[-5].startswith("Devices: 2 connected now")  # the last block
