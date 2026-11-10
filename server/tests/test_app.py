# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The /app channel, the extended relay ready message, the app token migration and pairing."""
import base64
import json
import os

import pytest
from starlette.testclient import TestClient, WebSocketDenialResponse
from starlette.websockets import WebSocketDisconnect

from bridge import __version__, config as cfg
from bridge.app import public_app
from bridge.appws import MAX_CLIENTS, AppClient
from bridge.cli import main
from bridge.config import Config
from bridge.hub import Hub
from bridge.phone import relay_info
from bridge.paircodes import PairCodes

CFG = Config(host="127.0.0.1", phone_port=19434, mcp_port=19435, phone_token="p" * 40,
             mcp_token="m" * 40, view_token="v" * 40, app_token="a" * 40, state_dir="/tmp", keepalive_s=0)
APP = {"Authorization": "Bearer " + "a" * 40}
PHONE = {"Authorization": "Bearer " + "p" * 40}
PEER = ("100.98.12.7", 50000)  # a device on the private network: the public listener serves it


@pytest.fixture
def hub(tmp_path):
    return Hub(tmp_path, keepalive_s=0)


def receive_until(ws, kind, limit=20):
    for _ in range(limit):
        message = json.loads(ws.receive_text())
        if message["type"] == kind:
            return message
    raise AssertionError(f"no {kind!r} message")


def test_app_rejects_other_tokens_with_401(hub):
    with TestClient(public_app(hub, CFG, codes=PairCodes()), client=PEER) as client:
        for headers in ({}, PHONE, {"Authorization": "Bearer " + "v" * 40}):
            with pytest.raises(WebSocketDenialResponse) as denied:
                with client.websocket_connect("/app", headers=headers):
                    pass
            assert denied.value.status_code == 401
    rejected = [e for e in hub.metrics.recent if e["ev"] == "app_rejected"]
    assert len(rejected) == 3 and all("a" * 40 not in json.dumps(e) for e in rejected)


def test_app_greeting_and_frames(hub):
    with TestClient(public_app(hub, CFG, codes=PairCodes()), client=PEER) as client:
        with client.websocket_connect("/app", headers=APP) as ws:
            hello = json.loads(ws.receive_text())
            assert hello["type"] == "hello" and hello["protocol"] == 1 and hello["server"]["version"]
            status = json.loads(ws.receive_text())
            assert status["type"] == "status" and status["connected"] is False and status["gate"] == "active"
            assert json.loads(ws.receive_text())["type"] == "audit.history"
            assert json.loads(ws.receive_text())["type"] == "phone.history"
            client.portal.call(hub.screen.update, b"\xaa" * 1024, 1)
            frame = receive_until(ws, "frame")
            assert frame["seq"] == 1 and frame["orientation"] == 1 and frame["stale"] is False
            assert base64.b64decode(frame["data"]) == b"\xaa" * 1024


def test_app_sends_current_frame_on_connect(hub):
    with TestClient(public_app(hub, CFG, codes=PairCodes()), client=PEER) as client:
        client.portal.call(hub.screen.update, b"\x01" * 1024, 0)
        with client.websocket_connect("/app", headers=APP) as ws:
            assert receive_until(ws, "frame")["seq"] == 1


class _BrokenChat:
    """A chat whose chat.open fails in a way nobody planned for."""

    def capabilities(self):
        return {}

    def page(self, *args):
        raise AttributeError("'int' object has no attribute 'get'")


class _Socket:
    def __init__(self):
        self.sent: list[dict] = []

    async def send_text(self, text):
        self.sent.append(json.loads(text))


async def test_an_unexpected_chat_error_is_answered_and_keeps_the_socket(hub):
    ws = _Socket()
    client = AppClient(hub, ws, "100.98.12.7:5000", _BrokenChat())
    await client.handle(json.dumps({"type": "chat.open", "conversation": "0f8fad5b-d9cb-469f-a165-70867728950e"}))
    assert ws.sent[-1]["type"] == "error" and ws.sent[-1]["error"] == "bad_request"
    assert ws.sent[-1]["request"] == "chat.open" and "int" not in ws.sent[-1]["message"]
    await client.handle(json.dumps({"type": "ping"}))
    assert ws.sent[-1]["type"] == "pong"


async def test_the_log_gets_the_kind_cleaned_and_short(hub, caplog):
    client = AppClient(hub, _Socket(), "100.98.12.7:5000", _BrokenChat())

    async def broken(kind, m):
        raise KeyError(kind)

    client.handle_chat = broken
    kind = "chat.\u202eopen\x1b[2J\n" + "x" * 80
    with caplog.at_level("WARNING", logger="zeffin.app"):
        await client.handle(json.dumps({"type": kind}))
    line = caplog.records[-1].getMessage()
    assert "\u202e" not in line and "\x1b" not in line and "\n" not in line and "x" * 40 not in line
    assert "chat. open [2J" in line and "KeyError" in line


async def test_a_client_that_left_still_ends_the_handler(hub):
    class Gone(_BrokenChat):
        def page(self, *args):
            raise WebSocketDisconnect(1001)

    client = AppClient(hub, _Socket(), "100.98.12.7:5000", Gone())
    with pytest.raises(WebSocketDisconnect):
        await client.handle(json.dumps({"type": "chat.open", "conversation": "0f8fad5b-d9cb-469f-a165-70867728950e"}))


def test_app_gate_commands(hub):
    with TestClient(public_app(hub, CFG, codes=PairCodes()), client=PEER) as client:
        with client.websocket_connect("/app", headers=APP) as ws:
            receive_until(ws, "phone.history")
            ws.send_text(json.dumps({"type": "gate", "action": "pause"}))
            assert receive_until(ws, "status")["gate"] == "paused"
            assert hub.gate.state == "paused" and hub.gate.by.startswith("app ")
            audit = receive_until(ws, "audit")["item"]
            assert audit["tool"] == "operator" and audit["action"] == "pause" and audit["by"] == "app"
            ws.send_text(json.dumps({"type": "gate", "action": "explode"}))
            assert receive_until(ws, "error")["error"] == "bad_action"
            ws.send_text(json.dumps({"type": "gate", "action": "stop"}))
            assert receive_until(ws, "status")["gate"] == "stopped"
        # stopped from the app: the relay is refused until resume, as with the live view
        with pytest.raises(WebSocketDenialResponse) as denied:
            with client.websocket_connect("/phone", headers=PHONE):
                pass
        assert denied.value.status_code == 423


def test_the_remote_s_pause_reads_remote_in_status_and_audit(hub):
    with TestClient(public_app(hub, CFG, codes=PairCodes()), client=PEER) as client:
        with client.websocket_connect("/app", headers=APP) as ws:
            receive_until(ws, "phone.history")
            ws.send_text(json.dumps({"type": "gate", "action": "pause", "via": "remote"}))
            status = receive_until(ws, "status")
            assert status["gate"] == "paused" and status["gate_by"] == "remote"
            assert hub.gate.by.startswith("remote ")
            audit = receive_until(ws, "audit")["item"]
            assert audit["action"] == "pause" and audit["by"] == "remote"
            ws.send_text(json.dumps({"type": "gate", "action": "resume", "via": "remote"}))
            assert receive_until(ws, "status")["gate_by"] == "remote"
            ws.send_text(json.dumps({"type": "gate", "action": "pause"}))
            assert receive_until(ws, "status")["gate_by"] == "app"
            for via in ("pad", 1, None, ["remote"]):
                ws.send_text(json.dumps({"type": "gate", "action": "resume", "via": via}))
                assert receive_until(ws, "error")["error"] == "bad_action"
            assert hub.gate.state == "paused"


def test_status_says_when_a_call_holds_the_flipper(hub):
    with TestClient(public_app(hub, CFG, codes=PairCodes()), client=PEER) as client:
        with client.websocket_connect("/app", headers=APP) as ws:
            assert receive_until(ws, "status")["busy"] is False
            receive_until(ws, "phone.history")

            async def take():
                await hub.ops_lock.acquire()
                hub.ops_changed()

            def give():
                hub.ops_lock.release()
                hub.ops_changed()

            client.portal.call(take)
            assert receive_until(ws, "status")["busy"] is True
            client.portal.call(give)
            assert receive_until(ws, "status")["busy"] is False


def test_app_stop_closes_the_phone_session(hub):
    with TestClient(public_app(hub, CFG, codes=PairCodes()), client=PEER) as client:
        with client.websocket_connect("/phone", headers=PHONE) as phone:
            phone.send_text(json.dumps({"type": "ready", "protocol": 1, "transport": "BLE"}))
            phone.receive_bytes()  # stream start request
            with client.websocket_connect("/app", headers=APP) as app:
                receive_until(app, "phone.history")
                app.send_text(json.dumps({"type": "gate", "action": "stop"}))
                receive_until(app, "status")
            with pytest.raises(Exception):
                phone.receive_bytes()
    assert any(e.get("ev") == "close" and e.get("reason") == "Stopped by the operator" for e in hub.metrics.recent)


def test_app_errors_and_ping(hub):
    with TestClient(public_app(hub, CFG, codes=PairCodes()), client=PEER) as client:
        with client.websocket_connect("/app", headers=APP) as ws:
            receive_until(ws, "phone.history")
            ws.send_text("not json")
            assert receive_until(ws, "error")["error"] == "bad_json"
            ws.send_text(json.dumps({"type": "screen.fresh"}))
            assert receive_until(ws, "error")["error"] == "not_connected"
            ws.send_text(json.dumps({"type": "launch"}))
            assert receive_until(ws, "error")["error"] == "unknown_type"
            ws.send_text(json.dumps({"type": "ping"}))
            assert receive_until(ws, "pong")["type"] == "pong"


def test_app_closes_on_oversized_or_binary(hub):
    with TestClient(public_app(hub, CFG, codes=PairCodes()), client=PEER) as client:
        with client.websocket_connect("/app", headers=APP) as ws:
            receive_until(ws, "phone.history")
            ws.send_text(json.dumps({"type": "ping", "pad": "é" * 33000}))  # over 64 KiB in bytes, not in characters
            with pytest.raises(Exception):
                for _ in range(5):
                    ws.receive_text()
        with client.websocket_connect("/app", headers=APP) as ws:
            receive_until(ws, "phone.history")
            ws.send_bytes(b"\x00")
            with pytest.raises(Exception):
                for _ in range(5):
                    ws.receive_text()
    assert hub.app_clients == 0
    closes = [e for e in hub.metrics.recent if e["ev"] == "app_close"]
    assert len(closes) == 2 and all("protocol error" in e["reason"] for e in closes)


def test_app_connection_limit(hub):
    with TestClient(public_app(hub, CFG, codes=PairCodes()), client=PEER) as client:
        hub.app_clients = MAX_CLIENTS
        with pytest.raises(WebSocketDenialResponse) as denied:
            with client.websocket_connect("/app", headers=APP):
                pass
        assert denied.value.status_code == 429
        hub.app_clients = 0


def test_app_sees_relay_connect_with_extended_ready(hub):
    with TestClient(public_app(hub, CFG, codes=PairCodes()), client=PEER) as client:
        with client.websocket_connect("/app", headers=APP) as app:
            receive_until(app, "phone.history")
            with client.websocket_connect("/phone", headers=PHONE) as phone:
                phone.send_text(json.dumps({"type": "ready", "protocol": 1, "transport": "BLE",
                                            "app": "0.1.0", "device": "Demo\x1b[31m", "mtu": 247}))
                phone.receive_bytes()
                event = receive_until(app, "phone")["item"]
                while event["ev"] != "ready":
                    event = receive_until(app, "phone")["item"]
                assert event["device"] == "Demo[31m" and event["mtu"] == 247
                status = receive_until(app, "status")
                assert status["connected"] is True
                assert status["relay"] == {"app": "0.1.0", "device": "Demo[31m", "mtu": 247}


def test_relay_info_is_optional_and_sanitized():
    assert relay_info({}) == {"app": None, "device": None, "mtu": None}
    assert relay_info({"app": 3, "device": "x" * 200, "mtu": True}) == {"app": None, "device": "x" * 64, "mtu": None}
    assert relay_info({"device": "\n\t ", "mtu": 9999})["mtu"] is None


def test_old_relay_ready_still_accepted(hub):
    with TestClient(public_app(hub, CFG, codes=PairCodes()), client=PEER) as client:
        with client.websocket_connect("/phone", headers=PHONE) as phone:
            phone.send_text(json.dumps({"type": "ready", "protocol": 1, "transport": "USB"}))
            phone.receive_bytes()
            assert hub.status()["relay"] == {"app": None, "device": None, "mtu": None}


def test_config_migration_adds_app_token(tmp_path):
    path = tmp_path / "config.json"
    c = cfg.create(path, "127.0.0.1", 9434, 9435, tmp_path)
    data = json.loads(path.read_text())
    del data["app_token"]
    path.write_text(json.dumps(data))
    path.chmod(0o600)
    loaded = cfg.load(path)
    assert len(loaded.app_token) >= 32 and loaded.app_token not in (c.phone_token, c.mcp_token, c.view_token)
    assert json.loads(path.read_text())["app_token"] == loaded.app_token
    assert oct(path.stat().st_mode & 0o777) == "0o600"
    assert cfg.load(path).app_token == loaded.app_token  # stable once written


def test_rotate_app_token(tmp_path, capsys):
    path = tmp_path / "config.json"
    c = cfg.create(path, "127.0.0.1", 9434, 9435, tmp_path)
    assert main(["--config", str(path), "rotate-tokens", "--only", "app"]) == 0
    new = cfg.load(path)
    assert new.app_token != c.app_token and new.phone_token == c.phone_token
    assert "pair" in capsys.readouterr().out


# ---- what the Zeffin app and its relay need ----

def test_phone_check_reports_without_taking_the_slot(hub):
    with TestClient(public_app(hub, CFG, codes=PairCodes()), client=PEER) as client:
        r = client.get("/phone/check", headers={"Authorization": "Bearer " + "x" * 40})
        assert r.status_code == 401 and "x" * 40 not in r.text
        rejected = [e for e in hub.metrics.recent if e["ev"] == "ws_rejected"]
        assert rejected[-1]["via"] == "check" and rejected[-1]["token_length"] == 40
        r = client.get("/phone/check", headers=PHONE)
        body = r.json()
        assert r.status_code == 200 and r.headers["cache-control"] == "no-store"
        assert body["gate"] == "active" and body["relay"] is None
        assert body["server"]["version"] == __version__ and body["server"]["name"]
        assert "p" * 40 not in r.text
        with client.websocket_connect("/phone", headers=PHONE) as phone:  # the check took no slot
            phone.send_text(json.dumps({"type": "ready", "protocol": 1, "transport": "BLE",
                                        "app": "0.1.0", "device": "Demo", "mtu": 414}))
            phone.receive_bytes()
            relay = client.get("/phone/check", headers=PHONE).json()["relay"]
            assert relay["app"] == "0.1.0" and relay["device"] == "Demo" and relay["ready"] is True
            assert relay["age_s"] >= 0
        client.portal.call(hub.operator, "stop", "view 1.2.3.4:5")
        assert client.get("/phone/check", headers=PHONE).json()["gate"] == "stopped"
    assert len([e for e in hub.metrics.recent if e["ev"] == "ws_rejected"]) == 1  # good checks are not logged


def test_stopped_relay_is_refused_with_operator_text(hub):
    with TestClient(public_app(hub, CFG, codes=PairCodes()), client=PEER) as client:
        client.portal.call(hub.operator, "stop", "app 1.2.3.4:5")
        with pytest.raises(WebSocketDenialResponse) as denied:
            with client.websocket_connect("/phone", headers=PHONE):
                pass
        assert denied.value.status_code == 423 and b"by the operator" in denied.value.content


def test_status_has_gate_origin_and_session_counters(hub):
    with TestClient(public_app(hub, CFG, codes=PairCodes()), client=PEER) as client:
        with client.websocket_connect("/app", headers=APP) as app:
            hello = json.loads(app.receive_text())
            assert hello["server"]["name"] and hello["server"]["version"] == __version__
            # the home folder, which the app shows as ~ in the permission cards (display only)
            assert hello["server"]["home"] == os.path.expanduser("~")
            status = receive_until(app, "status")
            assert status["gate_by"] == "startup" and status["gate_changed_at"] > 0
            assert status["session_frames"] is None
            receive_until(app, "phone.history")
            app.send_text(json.dumps({"type": "gate", "action": "pause"}))
            assert receive_until(app, "status")["gate_by"] == "app"
            with client.websocket_connect("/phone", headers=PHONE) as phone:
                phone.send_text(json.dumps({"type": "ready", "protocol": 1, "transport": "BLE"}))
                sent = phone.receive_bytes()  # stream start request
                phone.send_text(json.dumps({"type": "written"}))
                status = hub.status()
                assert status["session_frames"] == 0 and status["session_bytes_out"] == len(sent)
                assert status["session_bytes_in"] == 0


def test_close_keeps_the_phone_code_and_reason(hub):
    with TestClient(public_app(hub, CFG, codes=PairCodes()), client=PEER) as client:
        with client.websocket_connect("/phone", headers=PHONE) as phone:
            phone.send_text(json.dumps({"type": "ready", "protocol": 1, "transport": "BLE"}))
            phone.receive_bytes()
            phone.close(code=4000, reason="Flipper: disconnected: the Flipper stopped answering: out of range or off (status 8)\x07")
        for _ in range(50):
            closes = [e for e in hub.metrics.recent if e["ev"] == "close"]
            if closes:
                break
            client.portal.call(__import__("anyio").sleep, 0.02)
    assert closes[-1]["phone_code"] == 4000
    assert closes[-1]["phone_reason"] == "Flipper: disconnected: the Flipper stopped answering: out of range or off (status 8)"


def test_diag_figures(tmp_path):
    hub = Hub(tmp_path, keepalive_s=0)
    with TestClient(public_app(hub, CFG, codes=PairCodes()), client=PEER) as client:
        client.get("/phone/check", headers={"Authorization": "Bearer nope"})
        with client.websocket_connect("/phone", headers=PHONE) as phone:
            phone.send_text(json.dumps({"type": "ready", "protocol": 1, "transport": "BLE"}))
            phone.receive_bytes()
            hub.audit.write({"tool": "flipper_press", "args": {"key": "OK"}, "result": "ok"})
            with client.websocket_connect("/app", headers=APP) as app:
                receive_until(app, "phone.history")
                app.send_text(json.dumps({"type": "diag"}))
                diag = receive_until(app, "diag")
    assert diag["hours"] == 24 and diag["connections"] == 1 and diag["reached_ready"] == 1
    assert diag["rejected"] == {"bad_token": 1} and diag["last_rejected"]["reason"] == "bad_token"
    assert diag["agent_actions"] == 1
    assert diag["session"]["frames"] == 0 and diag["session"]["bytes_out"] > 0
    assert "p" * 40 not in json.dumps(diag) and "a" * 40 not in json.dumps(diag)


def test_read_events_reads_only_the_tail(tmp_path):
    from bridge.stats import read_events

    path = tmp_path / "metrics.jsonl"
    lines = [json.dumps({"ts": 100 + i, "ev": "x", "pad": "y" * 50}) for i in range(1000)]
    path.write_text("\n".join(lines) + "\nnot json\n")
    events = read_events(path, since=0, max_bytes=2000)
    assert 0 < len(events) < 40 and events[-1]["ts"] == 1099
    assert all(e["ts"] >= 1050 for e in read_events(path, since=1050))


def test_sim_flipper_over_tcp():
    import asyncio

    from bridge import pb
    from bridge.framing import Decoder, frame
    from bridge.phone import build
    from bridge.sim import serve_tcp

    async def scenario():
        ready = asyncio.get_running_loop().create_future()
        server = asyncio.create_task(serve_tcp(0, ready=ready))
        port = await ready
        reader, writer = await asyncio.open_connection("127.0.0.1", port)
        ping = build("system_ping_request", {"data": "Ymc="})
        ping.command_id = 7
        writer.write(frame(ping.SerializeToString()))
        await writer.drain()
        decoder = Decoder()
        while True:
            messages = decoder.feed(await reader.read(4096))
            if messages:
                break
        reply = pb.Main.FromString(messages[0])
        writer.close()
        server.cancel()
        return reply

    reply = asyncio.run(scenario())
    assert reply.command_id == 7 and reply.system_ping_response.data == b"bg"


def _received(ws, until: str) -> list[dict]:
    """Every message up to and including the first of kind `until`."""
    seen = []
    while not seen or seen[-1]["type"] != until:
        seen.append(json.loads(ws.receive_text()))
    return seen


def _kinds(messages, kind):
    return [m for m in messages if m["type"] == kind]


def test_frames_send_no_status_while_the_rest_of_it_holds(hub):
    with TestClient(public_app(hub, CFG, codes=PairCodes()), client=PEER) as client:
        with client.websocket_connect("/app", headers=APP) as ws:
            _received(ws, "phone.history")
            client.portal.call(hub.screen.update, b"\x01" * 1024, 0)
            _received(ws, "frame")  # the first frame may change "stale": a status may follow it
            ws.send_text(json.dumps({"type": "ping"}))
            _received(ws, "pong")
            seen = []
            for i in range(2, 6):
                client.portal.call(hub.screen.update, bytes([i]) * 1024, 0)
                seen += _received(ws, "frame")
            client.portal.call(hub.metrics.write, {"ev": "ws_rejected", "reason": "busy", "peer": "x"})
            seen += _received(ws, "phone")
            ws.send_text(json.dumps({"type": "ping"}))
            seen += _received(ws, "pong")
            assert _kinds(seen, "status") == []  # the frame figures alone are no news


def test_a_change_sends_one_status(hub):
    with TestClient(public_app(hub, CFG, codes=PairCodes()), client=PEER) as client:
        with client.websocket_connect("/app", headers=APP) as ws:
            _received(ws, "phone.history")
            client.portal.call(hub.gate.set, "paused", "view")
            status = _received(ws, "status")[-1]
            assert status["gate"] == "paused" and status["gate_by"] == "view"
            client.portal.call(hub.screen.update, b"\x01" * 1024, 0)
            seen = _received(ws, "frame")
            ws.send_text(json.dumps({"type": "ping"}))
            seen += _received(ws, "pong")
            assert all(m["gate"] == "paused" for m in _kinds(seen, "status"))


def test_the_whole_status_still_goes_out_every_interval(hub, monkeypatch):
    from bridge import appws

    monkeypatch.setattr(appws, "STATUS_EVERY_S", 0.2)
    with TestClient(public_app(hub, CFG, codes=PairCodes()), client=PEER) as client:
        with client.websocket_connect("/app", headers=APP) as ws:
            first = _received(ws, "status")[-1]
            _received(ws, "phone.history")
            again = _received(ws, "status")[-1]  # nothing changed: the timer sends it anyway
            assert appws.stable_status(again) == appws.stable_status(first)


def test_stable_status_leaves_out_only_the_figures(hub):
    from bridge.appws import stable_status

    status = hub.status()
    stable = stable_status(status)
    assert set(status) - set(stable) == {"session_uptime_s", "last_ping_ms", "session_frames", "session_bytes_in",
                                         "session_bytes_out"}
    assert stable["screen"] == {"stale": status["screen"]["stale"]} and stable["gate"] == status["gate"]
