# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
import json

import pytest
from starlette.testclient import TestClient, WebSocketDenialResponse

from bridge.app import mcp_app, public_app
from bridge.config import Config
from bridge.framing import Decoder, frame
from bridge import pb
from bridge.hub import Hub
from bridge.paircodes import PairCodes

CFG = Config(host="127.0.0.1", phone_port=19434, mcp_port=19435, phone_token="p" * 40,
             mcp_token="m" * 40, view_token="v" * 40, app_token="a" * 40, state_dir="/tmp", keepalive_s=0)
PEER = ("100.98.12.7", 50000)  # a device on the private network: the public listener serves it


@pytest.fixture
def hub(tmp_path):
    return Hub(tmp_path, keepalive_s=0)


def test_phone_rejects_bad_token_with_401_and_logs_it(hub):
    with TestClient(public_app(hub, CFG, codes=PairCodes()), client=PEER) as client:
        for headers in ({}, {"Authorization": "Bearer " + "m" * 40}, {"Authorization": "p" * 40}):
            with pytest.raises(WebSocketDenialResponse) as denied:
                with client.websocket_connect("/phone", headers=headers):
                    pass
            assert denied.value.status_code == 401
        assert hub.session is None
    rejected = [e for e in hub.metrics.recent if e["ev"] == "ws_rejected"]
    assert [e["reason"] for e in rejected] == ["bad_token"] * 3
    assert [(e["has_header"], e["bearer_prefix"], e["token_length"]) for e in rejected] == [
        (False, False, 0), (True, True, 40), (True, False, 0)]
    assert all("p" * 40 not in json.dumps(e) and "m" * 40 not in json.dumps(e) for e in rejected)


def test_phone_busy_is_409(hub):
    auth = {"Authorization": "Bearer " + "p" * 40}
    with TestClient(public_app(hub, CFG, codes=PairCodes()), client=PEER) as client:
        with client.websocket_connect("/phone", headers=auth):
            with pytest.raises(WebSocketDenialResponse) as denied:
                with client.websocket_connect("/phone", headers=auth):
                    pass
            assert denied.value.status_code == 409
    assert any(e["ev"] == "ws_rejected" and e["reason"] == "busy" for e in hub.metrics.recent)


def test_phone_contract_end_to_end(hub, monkeypatch):
    # No reply to the stop: the settle waits out its limit, shortened from 5 s (the junk below arrives in ms).
    monkeypatch.setattr("bridge.hub.SETTLE_LIMIT_S", 1.0)
    with TestClient(public_app(hub, CFG, codes=PairCodes()), client=PEER) as client:
        with client.websocket_connect("/phone", headers={"Authorization": "Bearer " + "p" * 40}) as ws:
            ws.send_text(json.dumps({"type": "ready", "protocol": 1, "transport": "BLE"}))
            data = ws.receive_bytes()  # nothing binary is sent before ready
            # First the server stops any stream an earlier session left on the link, and drops
            # what arrives until the link is quiet...
            stop = pb.Main.FromString(Decoder().feed(data)[0])
            assert stop.WhichOneof("content") == "gui_stop_screen_stream_request"
            ws.send_text('{"type":"written"}')
            ws.send_bytes(b"\xff" * 9)  # the tail of an old frame: dropped, not a protocol error
            # ...then starts its own stream.
            request = pb.Main.FromString(Decoder().feed(ws.receive_bytes())[0])
            assert request.WhichOneof("content") == "gui_start_screen_stream_request"
            ws.send_text('{"type":"written"}')
            reply = pb.Main(command_id=request.command_id)
            reply.empty.SetInParent()
            ws.send_bytes(frame(reply.SerializeToString()))
            shot = pb.Main()
            shot.gui_screen_frame.data = b"\x01" * 1024
            packed = frame(shot.SerializeToString())
            ws.send_bytes(packed[:300])
            ws.send_bytes(packed[300:])
            # ...then reads the firmware, one small property request at a time (firmware.py).
            detect = pb.Main.FromString(Decoder().feed(ws.receive_bytes())[0])
            assert detect.property_get_request.key == "devinfo.firmware.origin"
            ws.send_text('{"type":"ping"}')  # unknown status -> protocol error -> close
            with pytest.raises(Exception):
                ws.receive_bytes()
        assert hub.screen.seq == 1
        assert any(e.get("ev") == "close" and "Protocol error" in (e.get("reason") or "") for e in hub.metrics.recent)


def test_phone_binary_before_ready_closes(hub):
    with TestClient(public_app(hub, CFG, codes=PairCodes()), client=PEER) as client:
        with client.websocket_connect("/phone", headers={"Authorization": "Bearer " + "p" * 40}) as ws:
            ws.send_bytes(b"\x01\x00")
            with pytest.raises(Exception):
                ws.receive_bytes()
    assert hub.session is None


def test_view_login_and_gate(hub):
    # The public listener speaks TLS, and the view's cookie is Secure: a browser sends it back only over https.
    with TestClient(public_app(hub, CFG, codes=PairCodes()), client=PEER, base_url="https://testserver") as client:
        assert "View token" in client.get("/view").text
        assert client.get("/view/events").status_code == 401
        assert client.post("/view/pause", headers={"x-flipper-bridge": "1"}).status_code == 401
        assert client.post("/view/login", data={"token": "wrong"}).status_code == 401
        r = client.post("/view/login", data={"token": "v" * 40}, follow_redirects=False)
        assert r.status_code == 303 and "httponly" in r.headers["set-cookie"].lower()
        assert "secure" in r.headers["set-cookie"].lower()
        assert "v" * 40 not in r.headers["set-cookie"]
        assert "EventSource" in client.get("/view").text
        assert client.post("/view/pause").status_code == 403  # missing anti-CSRF header
        r = client.post("/view/pause", headers={"x-flipper-bridge": "1"})
        assert r.status_code == 200 and r.json()["gate"] == "paused" and hub.gate.state == "paused"
        client.post("/view/resume", headers={"x-flipper-bridge": "1"})
        assert hub.gate.state == "active"
        client.post("/view/stop", headers={"x-flipper-bridge": "1"})
        assert hub.gate.state == "stopped"
        # stopped: the phone is refused until resume
        with pytest.raises(WebSocketDenialResponse) as denied:
            with client.websocket_connect("/phone", headers={"Authorization": "Bearer " + "p" * 40}):
                pass
        assert denied.value.status_code == 423


def test_public_body_limit(hub):
    with TestClient(public_app(hub, CFG, codes=PairCodes()), client=PEER) as client:
        r = client.post("/view/login", content=b"x" * (64 * 1024))
        assert r.status_code == 413


def test_mcp_requires_token_before_body(hub):
    init = {"jsonrpc": "2.0", "id": 1, "method": "initialize",
            "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                       "clientInfo": {"name": "test", "version": "0"}}}
    headers = {"Accept": "application/json, text/event-stream", "Content-Type": "application/json"}
    with TestClient(mcp_app(hub, CFG, codes=PairCodes()), base_url="http://127.0.0.1:19435") as client:
        assert client.post("/mcp", json=init, headers=headers).status_code == 401
        bad = {**headers, "Authorization": "Bearer " + "p" * 40}
        assert client.post("/mcp", json=init, headers=bad).status_code == 401
        r = client.post("/mcp", content=b"x" * (5 * 1024 * 1024), headers=headers)
        assert r.status_code in (401, 413)
        good = {**headers, "Authorization": "Bearer " + "m" * 40}
        r = client.post("/mcp", json=init, headers=good)
        assert r.status_code == 200, r.text
        assert "zeffin" in r.text
