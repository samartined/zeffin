# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import itertools as _itertools
import json
import os as _os
import socket as _socket
import stat as _stat

import pytest as _pytest

# Ports for the servers a test starts: below Linux's ephemeral range (32768-60999), where no client socket of
# another test takes one between free_port() and the server's bind, and a block of its own for each pytest-xdist
# worker (gw0, gw1, ...), so two workers never hand out the same port.
_WORKER = int(_os.environ.get("PYTEST_XDIST_WORKER", "gw0").removeprefix("gw") or 0)
_PORTS = _itertools.count(20000 + (_WORKER % 12) * 1000 + _os.getpid() % 500)


def free_port() -> int:
    """A port no other test of this run hands out, free now on 127.0.0.1."""
    for port in _PORTS:
        if port >= 20000 + (_WORKER % 12 + 1) * 1000:
            raise RuntimeError("no free port left in this worker's block")
        with _socket.socket() as s:
            try:
                s.bind(("127.0.0.1", port))
            except OSError:
                continue
            return port
    raise AssertionError("unreachable")


@_pytest.fixture(autouse=True)
def no_private_network_lookups(monkeypatch):
    """No test runs the private network's own command to name a device: bridge.peers asks privnet.lookup, which
    answers nothing here. tests/test_privnet.py keeps the real one (REAL_LOOKUP) and fakes the command instead."""
    from bridge import privnet

    monkeypatch.setattr(privnet, "lookup", lambda ip, timeout=None: None)


@_pytest.fixture
def fake_claude(tmp_path, monkeypatch):
    """An executable `claude` that runs tests/fake_claude.py; its sessions and log live in tmp_path."""
    home = tmp_path / "fake-claude"
    home.mkdir()
    script = home / "claude"
    script.write_text(f'#!/bin/sh\nexec "{sys.executable}" -I "{Path(__file__).resolve().parent / "fake_claude.py"}" "$@"\n')
    script.chmod(script.stat().st_mode | _stat.S_IXUSR)
    monkeypatch.setenv("FAKE_CLAUDE_HOME", str(home))
    monkeypatch.setenv("FAKE_CLAUDE_LOG", str(home / "runs.jsonl"))
    return script


def fake_runs(script) -> list[dict]:
    import json

    log = Path(script).parent / "runs.jsonl"
    return [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []


@_pytest.fixture(autouse=True)
def app_messages_match_schemas(monkeypatch):
    """Every message the server sends on /app in a test, and every one `zf chat` sends, matches its schema in
    docs/protocol/app. Messages a test writes by hand to the server are not checked: some are wrong on purpose."""
    from starlette.testclient import WebSocketTestSession
    from websockets.sync.client import ClientConnection

    from bridge.chat.client import ChatClient
    from protocol import problems

    found: list[str] = []

    def check(direction: str, text: object) -> None:
        if isinstance(text, str):
            try:
                message = json.loads(text)
            except ValueError:
                found.append(f"{direction}: not JSON: {text[:200]}")
                return
            found.extend(problems(direction, message))

    receive = WebSocketTestSession.receive

    def checked_receive(self):
        message = receive(self)
        if self.scope.get("path") == "/app" and message.get("type") == "websocket.send":
            check("server", message.get("text"))
        return message

    recv = ClientConnection.recv

    def checked_recv(self, *args, **kwargs):
        data = recv(self, *args, **kwargs)
        request = getattr(self, "request", None)
        if request is not None and request.path == "/app":
            check("server", data)
        return data

    send = ChatClient.send

    def checked_send(self, kind, **fields):
        found.extend(problems("app", json.loads(json.dumps({"type": kind, **fields}))))
        return send(self, kind, **fields)

    monkeypatch.setattr(WebSocketTestSession, "receive", checked_receive)
    monkeypatch.setattr(ClientConnection, "recv", checked_recv)
    monkeypatch.setattr(ChatClient, "send", checked_send)
    yield
    assert not found, "Messages that do not match docs/protocol/app:\n" + "\n".join(dict.fromkeys(found))
