# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The private network (bridge/privnet.py) and the public listener's peer check (app.PrivatePeers)."""
import inspect
import ipaddress
import json
import re
import sys
import time
from pathlib import Path

import pytest
from starlette.routing import Route, WebSocketRoute
from starlette.testclient import TestClient, WebSocketDenialResponse
from uvicorn.middleware.proxy_headers import ProxyHeadersMiddleware

from bridge import app as app_module, privnet
from bridge.app import PrivatePeers, RefusalLog, attachment_routes, listener_config, public_app
from bridge.config import Config
from bridge.hub import Hub
from bridge.paircodes import PairCodes

BRIDGE = Path(__file__).resolve().parent.parent / "bridge"
REAL_LOOKUP = privnet.lookup  # conftest fakes privnet.lookup for every test; these fake the command instead
CFG = Config(host="127.0.0.1", phone_port=19434, mcp_port=19435, phone_token="p" * 40,
             mcp_token="m" * 40, view_token="v" * 40, app_token="a" * 40, state_dir="/tmp", keepalive_s=0)
PHONE = {"Authorization": "Bearer " + "p" * 40}
OUTSIDE = ("8.8.8.8", 40000)


@pytest.fixture
def hub(tmp_path):
    return Hub(tmp_path, keepalive_s=0)


@pytest.mark.parametrize("host", ["100.98.12.7", "100.64.0.1", "100.127.255.254", "fd7a:115c:a1e0::1",
                                  "::ffff:100.98.12.7", "127.0.0.1", "::1", "::ffff:127.0.0.1", "fd7a:115c:a1e0::1%utun0"])
def test_private_and_loopback_peers_are_allowed(host):
    assert privnet.allowed_peer(host)


@pytest.mark.parametrize("host", ["8.8.8.8", "::ffff:8.8.8.8", "100.128.0.1", "100.63.255.255", "192.168.1.2", "10.0.0.1",
                                  "fd7a:115c:a1e1::1", "2001:db8::1", "0.0.0.0", "::", "testclient", "", None])
def test_other_peers_are_refused(host):
    assert not privnet.allowed_peer(host)


def test_ipv4_mapped_addresses_are_their_ipv4_address():
    assert str(privnet.peer_ip("::ffff:100.98.12.7")) == "100.98.12.7"
    assert privnet.is_private(privnet.peer_ip("::ffff:100.98.12.7"))
    assert not privnet.is_private(privnet.peer_ip("::ffff:8.8.8.8"))


LITERALS = re.compile(r"100\.64|fd7a:|tailscale ip|tailscale|tailnet", re.IGNORECASE)


def test_only_privnet_names_the_private_network():
    """Its ranges, its command and its name (comments and docstrings included) live in privnet.py only."""
    found = [f"{p.relative_to(BRIDGE)}:{n}: {line.strip()}"
             for p in sorted(BRIDGE.rglob("*.py")) if p.name != "privnet.py" and not p.name.endswith("_pb2.py")
             for n, line in enumerate(p.read_text().splitlines(), 1) if LITERALS.search(line)]
    assert found == []


def _paths(hub) -> list[tuple[str, str]]:
    """(method or "ws", path) for every route of the public listener, with its parameters filled in."""
    peers = public_app(hub, CFG, codes=PairCodes())
    starlette = peers.app.app  # PrivatePeers(BodyLimit(Starlette))
    routes = [*starlette.routes, *attachment_routes(object(), CFG.app_token)]  # type: ignore[arg-type]
    out = []
    for route in routes:
        path = re.sub(r"\{[^}]+\}", "x", route.path)
        if isinstance(route, WebSocketRoute):
            out.append(("ws", path))
        elif isinstance(route, Route):
            out += [(m, path) for m in sorted(route.methods or ()) if m != "HEAD"]
    return out


def test_every_route_is_listed(hub):
    listed = {path for _, path in _paths(hub)}
    assert {"/phone", "/app", "/", "/phone/check", "/view", "/app/pair", "/app/upload", "/app/attachments/x/x"} <= listed


def test_an_outside_peer_gets_403_everywhere_before_routing(hub):
    """Nothing behind the peer check runs: no route, no body limit, no token check."""
    peers = public_app(hub, CFG, codes=PairCodes())
    reached, inner = [], peers.app

    async def spy(scope, receive, send):
        if scope["type"] == "lifespan":
            return await inner(scope, receive, send)
        reached.append(scope["path"])

    peers.app = spy
    with TestClient(peers, client=OUTSIDE) as client:
        for method, path in _paths(hub):
            if method == "ws":
                with pytest.raises(WebSocketDenialResponse) as denied:
                    with client.websocket_connect(path, headers=PHONE):
                        pass
                assert denied.value.status_code == 403, path
            else:
                assert client.request(method, path, headers=PHONE).status_code == 403, (method, path)
    assert reached == []


def test_an_outside_peer_never_reaches_a_token_check(hub, monkeypatch):
    calls = []
    monkeypatch.setattr(app_module, "phone_token_ok", lambda *a, **k: calls.append(a) or True)
    with TestClient(public_app(hub, CFG, codes=PairCodes()), client=OUTSIDE) as client:
        assert client.get("/phone/check", headers=PHONE).status_code == 403
        with pytest.raises(WebSocketDenialResponse) as denied:
            with client.websocket_connect("/app", headers={"Authorization": "Bearer " + "a" * 40}):
                pass
        assert denied.value.status_code == 403
        assert client.post("/app/pair", json={"code": "x"}).status_code == 403
    assert calls == []
    refused = [e for e in hub.metrics.recent if e["ev"] == "peer_refused"]
    assert refused[0] == {"ts": refused[0]["ts"], "ev": "peer_refused", "peer": "8.8.8.8:40000", "kind": "http",
                          "path": "/phone/check"}
    assert not [e for e in hub.metrics.recent if e["ev"] in ("ws_rejected", "app_rejected")]


@pytest.mark.parametrize("peer", [("100.98.12.7", 1), ("127.0.0.1", 1), ("::1", 1), ("::ffff:100.98.12.7", 1),
                                  ("fd7a:115c:a1e0::5", 1)])
def test_private_and_loopback_peers_are_served(hub, peer):
    with TestClient(public_app(hub, CFG, codes=PairCodes()), client=peer) as client:
        assert client.get("/").status_code == 200
        assert client.get("/phone/check").status_code == 401  # reached the token check
    assert not [e for e in hub.metrics.recent if e["ev"] == "peer_refused"]


def test_a_mapped_outside_peer_is_refused(hub):
    with TestClient(public_app(hub, CFG, codes=PairCodes()), client=("::ffff:8.8.8.8", 1)) as client:
        assert client.get("/").status_code == 403


def test_a_forwarded_header_does_not_make_a_peer_private(hub):
    with TestClient(public_app(hub, CFG, codes=PairCodes()), client=OUTSIDE) as client:
        headers = {"X-Forwarded-For": "100.98.12.7", "Forwarded": "for=100.98.12.7", "X-Real-IP": "127.0.0.1"}
        assert client.get("/", headers=headers).status_code == 403


def test_the_listeners_never_trust_proxy_headers():
    """uvicorn's default (proxy_headers=True) rewrites scope["client"] from X-Forwarded-For: pinned off."""
    config = listener_config(public_app(Hub(None, keepalive_s=0), CFG, codes=PairCodes()), "127.0.0.1", 0,
                             lifespan="off")
    assert config.proxy_headers is False
    config.load()
    assert not isinstance(config.loaded_app, ProxyHeadersMiddleware)
    with pytest.raises(TypeError):
        listener_config(None, "127.0.0.1", 0, proxy_headers=True)
    assert "uvicorn.Config(" not in inspect.getsource(app_module.serve)


async def test_a_peer_the_server_cannot_tell_is_refused():
    sent, reached, refused = [], [], []

    async def inner(scope, receive, send):
        reached.append(scope)

    async def send(message):
        sent.append(message)

    peers = PrivatePeers(inner, lambda *a: refused.append(a))
    await peers({"type": "http", "path": "/", "headers": []}, None, send)
    assert sent[0]["status"] == 403 and reached == [] and refused == [("?", None, "http", "/")]


class Clock:
    def __init__(self):
        self.now = 1000.0

    def __call__(self):
        return self.now


def test_refusals_are_rated():
    lines, clock = [], Clock()
    log = RefusalLog(lines.append, clock)
    for _ in range(5):
        log("8.8.8.8", 1, "http", "/")
    assert len(lines) == 1  # one line per peer a minute
    for i in range(30):
        log(f"203.0.113.{i}", 1, "ws", "/phone")
    assert len(lines) == RefusalLog.PER_MINUTE  # and a cap for all peers
    clock.now += 60
    log("8.8.8.8", 1, "http", "/view")
    assert lines[1]["suppressed"] == 4  # the next line written counts what was left out
    assert lines[-1]["suppressed"] == 30 - (RefusalLog.PER_MINUTE - 1)
    assert lines[-1]["path"] == "/view"
    log("198.51.100.1", 1, "http", "/" + "x" * 500)
    assert "suppressed" not in lines[-1] and len(lines[-1]["path"]) == 64


def test_the_peer_table_is_bounded():
    log = RefusalLog(lambda line: None, Clock())
    for i in range(RefusalLog.PEERS + 50):
        log(f"peer-{i}", 1, "http", "/")
        log.lines = 0
    assert len(log.last) == RefusalLog.PEERS


# A device's name: `tailscale whois --json`, faked by an executable of the same name first on PATH.
WHOIS = {"Node": {"ID": 1, "Name": "demo-phone.example-tailnet.ts.net.", "ComputedName": "demo-phone",
                  "ComputedNameWithHost": "demo-phone (Demo Model)", "Addresses": ["100.98.12.7/32"],
                  "Hostinfo": {"Hostname": "Demo Model", "OS": "android"}},
         "UserProfile": {"ID": 2, "LoginName": "owner@example.com", "DisplayName": "Demo Owner"}}


@pytest.fixture
def fake_tailscale(tmp_path, monkeypatch):
    """Write the fake command: it records its arguments, then runs body (Python with `sys` and `json` imported)."""
    folder = tmp_path / "bin"
    folder.mkdir()
    calls = tmp_path / "calls.json"

    def install(body: str) -> Path:
        script = folder / "tailscale"
        script.write_text(f"#!{sys.executable} -I\nimport json, sys, time\n"
                          f"open({str(calls)!r}, 'w').write(json.dumps(sys.argv[1:]))\n{body}\n")
        script.chmod(0o755)
        return calls

    monkeypatch.setenv("PATH", f"{folder}:/usr/bin:/bin")
    return install


def test_a_device_s_name_and_os_and_nothing_else(fake_tailscale):
    calls = fake_tailscale(f"print(json.dumps({WHOIS!r}))")
    assert REAL_LOOKUP(ipaddress.ip_address("100.98.12.7")) == {"name": "demo-phone", "os": "android"}
    assert json.loads(calls.read_text()) == ["whois", "--json", "100.98.12.7"]  # an argument, never a shell


def test_a_node_without_its_os_still_has_its_name(fake_tailscale):
    node = {"Node": {"ComputedName": "demo-server", "Hostinfo": {"Hostname": "x"}}}
    fake_tailscale(f"print(json.dumps({node!r}))")
    assert REAL_LOOKUP(ipaddress.ip_address("100.98.12.7")) == {"name": "demo-server", "os": None}


@pytest.mark.parametrize("body", [
    "sys.stderr.write('peer not found'); sys.exit(1)",  # an address the private network does not know
    "print('not json')",
    "print(json.dumps({'Node': {'Name': 'demo-phone.example-tailnet.ts.net.'}}))",  # nothing it reads
    "print(json.dumps([1, 2]))",
    "print(json.dumps({'Node': 'x'}))",
])
def test_anything_else_is_no_name(fake_tailscale, body):
    fake_tailscale(body)
    assert REAL_LOOKUP(ipaddress.ip_address("100.98.12.7")) is None


def test_a_long_answer_is_dropped_and_the_command_killed(fake_tailscale, monkeypatch):
    monkeypatch.setattr(privnet, "LOOKUP_MAX_BYTES", 4096)
    fake_tailscale(f"sys.stdout.write(json.dumps({WHOIS!r}) + ' ' * 5000); sys.stdout.flush(); time.sleep(30)")
    started = time.monotonic()
    assert REAL_LOOKUP(ipaddress.ip_address("100.98.12.7")) is None
    assert time.monotonic() - started < 5


def test_a_slow_answer_gives_up_at_the_timeout(fake_tailscale):
    fake_tailscale("time.sleep(30)")
    started = time.monotonic()
    assert REAL_LOOKUP(ipaddress.ip_address("100.98.12.7"), timeout=0.3) is None
    assert time.monotonic() - started < 5


def test_without_the_command_there_is_no_name(tmp_path, monkeypatch):
    monkeypatch.setenv("PATH", str(tmp_path))
    assert REAL_LOOKUP(ipaddress.ip_address("100.98.12.7")) is None


def test_only_an_address_is_looked_up(fake_tailscale):
    calls = fake_tailscale("print('{}')")
    assert REAL_LOOKUP("100.98.12.7; rm -rf ~") is None  # type: ignore[arg-type]
    assert not calls.exists()
