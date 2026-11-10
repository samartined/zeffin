# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The devices that use the server (bridge/peers.py): what counts as one, what is kept, the name lookups, and that only a
peer that passed a credential enters the list (the public listener's routes)."""
import asyncio
import logging
import threading

import pytest
from starlette.applications import Starlette
from starlette.testclient import TestClient, WebSocketDenialResponse

from bridge import view
from bridge.app import attachment_routes, mcp_app, pairing_metrics, public_app
from bridge.config import Config
from bridge.hub import Hub
from bridge.paircodes import PairCodes
from bridge.peers import APP, PAIRING, RELAY, VIEW, Peers

CFG = Config(host="127.0.0.1", phone_port=19434, mcp_port=19435, phone_token="p" * 40,
             mcp_token="m" * 40, view_token="v" * 40, app_token="a" * 40, state_dir="/tmp", keepalive_s=0)
PHONE = {"Authorization": "Bearer " + "p" * 40}
APP_TOKEN = {"Authorization": "Bearer " + "a" * 40}
PEER = ("100.98.12.7", 50000)  # demo-phone, on the private network
TOKENS = ("p" * 40, "m" * 40, "v" * 40, "a" * 40)


class Clock:
    def __init__(self, now: float = 1_800_000_000.0):
        self.now = now

    def __call__(self) -> float:
        return self.now


def table(**options) -> tuple[Peers, Clock, Clock]:
    wall, mono = Clock(), Clock(1000.0)
    return Peers(clock=wall, monotonic=mono, **options), wall, mono


def items(peers: Peers) -> dict[str, dict]:
    return {d["address"]: d for d in peers.summary()["items"]}


def test_a_device_is_its_address_with_what_it_used_and_when():
    peers, wall, _ = table()
    peers.seen("100.98.12.7", RELAY)  # /phone/check
    wall.now += 5
    peers.open("100.98.12.7", RELAY)
    peers.open("::ffff:100.98.12.7", APP)  # the same address, mapped
    wall.now += 5
    peers.open("100.98.12.7", APP)  # a second app on the same phone
    device = items(peers)["100.98.12.7"]
    assert device["connected"] == {"app": 2, "relay": 1} and device["used"] == ["app", "relay"]
    assert device["first_seen"] == 1_800_000_000.0 and device["last_seen"] == 1_800_000_010.0
    assert device["this_server"] is False and device["name"] is None and device["lookup"] == "pending"
    wall.now += 60
    for kind in (RELAY, APP, APP, APP):  # one close too many is ignored
        peers.close("100.98.12.7", kind)
    device = items(peers)["100.98.12.7"]
    assert device["connected"] == {} and device["used"] == ["app", "relay"]
    assert device["last_seen"] == 1_800_000_070.0
    assert peers.summary()["since"] == 1_800_000_000.0


def test_short_requests_are_used_but_never_connected():
    peers, _, _ = table()
    for kind in (RELAY, APP, VIEW, PAIRING):
        peers.seen("100.98.12.7", kind)
    assert items(peers)["100.98.12.7"]["connected"] == {}
    assert items(peers)["100.98.12.7"]["used"] == ["app", "pairing", "relay", "view"]


def test_what_is_not_a_device_never_enters():
    peers, _, _ = table()
    for host in (None, "", "testclient", "not an address"):
        peers.seen(host, APP)
        peers.open(host, APP)
        peers.close(host, APP)
    peers.seen("100.98.12.7", "mcp")  # not a kind of the public listener
    assert peers.summary()["items"] == []


def test_the_server_s_own_connections_are_one_row_never_looked_up():
    calls = []
    peers, _, _ = table(own_address="100.98.12.8", lookup=lambda ip: calls.append(ip))
    peers.open("100.98.12.8", APP)  # zf chat, from the server itself
    peers.seen("127.0.0.1", APP)
    peers.seen("::1", RELAY)
    [row] = peers.summary()["items"]
    assert row["this_server"] is True and row["address"] == "100.98.12.8" and row["lookup"] == "none"
    assert row["connected"] == {"app": 1} and row["used"] == ["app", "relay"]
    peers.close("127.0.0.1", APP)  # the same row
    assert peers.summary()["items"][0]["connected"] == {}
    assert calls == [] and peers.tasks == set()


def test_the_list_is_bounded_and_an_open_device_stays():
    peers, wall, _ = table()
    peers.open("100.64.0.1", RELAY)  # seen first, and still connected
    for i in range(2, Peers.MAX + 10):
        wall.now += 1
        peers.seen(f"100.64.0.{i}", APP)
    found = items(peers)
    assert len(found) == Peers.MAX
    assert "100.64.0.1" in found and found["100.64.0.1"]["connected"] == {"relay": 1}
    assert "100.64.0.2" not in found and f"100.64.0.{Peers.MAX + 9}" in found  # the idle ones seen longest ago went


def test_connected_devices_come_first_then_the_most_recent():
    peers, wall, _ = table()
    peers.seen("100.64.0.1", VIEW)
    wall.now += 1
    peers.open("100.64.0.2", APP)
    wall.now += 1
    peers.seen("100.64.0.3", VIEW)
    assert [d["address"] for d in peers.summary()["items"]] == ["100.64.0.2", "100.64.0.3", "100.64.0.1"]


async def settle(peers: Peers) -> None:
    while peers.tasks:
        await asyncio.gather(*list(peers.tasks))


async def test_a_name_comes_in_the_background_and_the_list_never_waits_for_it():
    release, calls = threading.Event(), []

    def slow(ip):
        calls.append(str(ip))
        release.wait(5)
        return {"name": "demo-phone", "os": "android"}

    peers, _, _ = table(lookup=slow)
    peers.open("100.98.12.7", RELAY)
    peers.seen("100.98.12.7", APP)  # one lookup in flight per device
    await asyncio.sleep(0.05)
    assert items(peers)["100.98.12.7"]["lookup"] == "pending"  # summary() answered while the lookup waits
    release.set()
    await settle(peers)
    device = items(peers)["100.98.12.7"]
    assert (device["name"], device["os"], device["lookup"]) == ("demo-phone", "android", "done")
    assert calls == ["100.98.12.7"]


async def test_at_most_two_lookups_at_once():
    running, most, lock, release = [0], [0], threading.Lock(), threading.Event()

    def counting(ip):
        with lock:
            running[0] += 1
            most[0] = max(most[0], running[0])
        release.wait(0.2)
        with lock:
            running[0] -= 1
        return None

    peers, _, _ = table(lookup=counting)
    for i in range(1, 7):
        peers.seen(f"100.64.0.{i}", APP)
    await settle(peers)
    assert most[0] == Peers.LOOKUPS == 2


async def test_names_are_kept_for_a_while_and_a_failure_is_tried_again_later():
    answers, calls = [{"name": "demo-phone", "os": None}], []

    def lookup(ip):
        calls.append(ip)
        return answers[0]

    peers, _, mono = table(lookup=lookup)
    peers.seen("100.98.12.7", APP)
    await settle(peers)
    mono.now += Peers.FRESH_S - 1
    peers.seen("100.98.12.7", APP)
    await settle(peers)
    assert len(calls) == 1  # still fresh
    mono.now += 2
    answers[0] = None
    peers.seen("100.98.12.7", APP)
    await settle(peers)
    assert len(calls) == 2
    assert items(peers)["100.98.12.7"]["name"] == "demo-phone"  # a refresh that fails keeps the name

    peers.seen("100.98.12.9", APP)
    await settle(peers)
    assert items(peers)["100.98.12.9"]["lookup"] == "failed" and items(peers)["100.98.12.9"]["name"] is None
    peers.seen("100.98.12.9", APP)
    await settle(peers)
    assert len(calls) == 3  # a failure waits RETRY_S
    mono.now += Peers.RETRY_S + 1
    peers.seen("100.98.12.9", APP)
    await settle(peers)
    assert len(calls) == 4


async def test_a_lookup_that_raises_leaves_the_address_alone():
    def broken(ip):
        raise RuntimeError("boom")

    peers, _, _ = table(lookup=broken)
    peers.seen("100.98.12.7", APP)
    await settle(peers)
    assert items(peers)["100.98.12.7"]["lookup"] == "failed"


async def test_a_device_chooses_its_name_so_it_is_cleaned_and_cut():
    peers, _, _ = table(lookup=lambda ip: {"name": "demo\x1b[2J-phone‮" + "x" * 100, "os": "android\n" * 5})
    peers.seen("100.98.12.7", APP)
    await settle(peers)
    device = items(peers)["100.98.12.7"]
    assert device["name"].startswith("demo[2J-phone") and len(device["name"]) == 63
    assert "\x1b" not in device["name"] and "‮" not in device["name"]
    assert "\n" not in device["os"] and len(device["os"]) <= 16


async def test_the_log_says_once_when_a_device_first_connects(caplog):
    names = {"100.98.12.7": {"name": "demo-phone", "os": "android"}}
    peers, _, mono = table(own_address="100.98.12.8", lookup=lambda ip: names.get(str(ip)))
    with caplog.at_level(logging.INFO, logger="zeffin.devices"):
        peers.open("100.98.12.7", RELAY)
        await settle(peers)
        mono.now += Peers.FRESH_S + 1  # a later lookup: no second line
        peers.open("100.98.12.7", APP)
        peers.seen("100.98.12.8", APP)  # the server itself: none
        peers.seen("100.98.12.9", VIEW)  # no name
        await settle(peers)
    lines = [r.getMessage() for r in caplog.records if r.name == "zeffin.devices"]
    assert lines == ["first connection since the server started from demo-phone (android, 100.98.12.7): relay",
                     "first connection since the server started from 100.98.12.9 (no name from Tailscale): view"]


# ---- the public listener: only a peer that passed its credential enters ----

@pytest.fixture
def hub(tmp_path):
    return Hub(tmp_path, keepalive_s=0)


def test_wrong_credentials_never_make_a_device(hub, monkeypatch):
    looked_up = []
    monkeypatch.setattr(hub.peers, "lookup", lambda ip: looked_up.append(ip))
    with TestClient(public_app(hub, CFG, codes=PairCodes()), client=PEER, base_url="https://testserver") as client:
        for path, headers in (("/phone", {"Authorization": "Bearer wrong"}), ("/app", {"Authorization": "Bearer wrong"}),
                              ("/app", {})):
            with pytest.raises(WebSocketDenialResponse):
                with client.websocket_connect(path, headers=headers):
                    pass
        assert client.get("/phone/check", headers={"Authorization": "Bearer wrong"}).status_code == 401
        assert client.get("/view/events").status_code == 401
        client.cookies.set("fb_view", "forged")
        assert client.get("/view/screen.png").status_code == 401
        client.cookies.clear()
        assert client.post("/view/login", data={"token": "wrong"}).status_code == 401
        assert client.post("/app/pair", json={"code": "wrong"}).status_code in (401, 410)
        assert client.get("/").status_code == 200 and "View token" in client.get("/view").text  # no credential needed
    assert hub.peers.summary()["items"] == [] and looked_up == []


def test_an_outside_peer_is_refused_before_it_could_be_a_device(hub):
    with TestClient(public_app(hub, CFG, codes=PairCodes()), client=("8.8.8.8", 1)) as client:
        assert client.get("/phone/check", headers=PHONE).status_code == 403
    assert hub.peers.summary()["items"] == []


def test_the_relay_the_app_and_the_web_view_each_count(hub):
    with TestClient(public_app(hub, CFG, codes=PairCodes()), client=PEER, base_url="https://testserver") as client:
        assert client.get("/phone/check", headers=PHONE).status_code == 200
        assert items(hub.peers)["100.98.12.7"]["used"] == ["relay"]
        with client.websocket_connect("/app", headers=APP_TOKEN) as ws:
            ws.receive_text()
            assert items(hub.peers)["100.98.12.7"]["connected"] == {"app": 1}
            with client.websocket_connect("/phone", headers=PHONE):
                assert items(hub.peers)["100.98.12.7"]["connected"] == {"app": 1, "relay": 1}
        assert client.post("/view/login", data={"token": "v" * 40}, follow_redirects=False).status_code == 303
    device = items(hub.peers)["100.98.12.7"]
    assert device["connected"] == {} and device["used"] == ["app", "relay", "view"]


async def test_the_web_view_is_connected_while_its_stream_is_open(hub):
    stream = view._event_stream(hub, "100.98.12.7")
    assert (await stream.__anext__()).startswith(b"event: status")
    assert items(hub.peers)["100.98.12.7"]["connected"] == {"view": 1}
    await stream.aclose()
    assert items(hub.peers)["100.98.12.7"]["connected"] == {}


def test_attachments_count_as_the_app_once_the_token_passed(hub):
    class NoAttachments:
        def attachment(self, conversation, ident):
            return None

    app = Starlette(routes=attachment_routes(NoAttachments(), "a" * 40, hub.peers))  # type: ignore[arg-type]
    with TestClient(app, client=PEER) as client:
        assert client.get("/app/attachments/c/x").status_code == 401
        assert hub.peers.summary()["items"] == []
        assert client.get("/app/attachments/c/x", headers=APP_TOKEN).status_code == 404
    assert items(hub.peers)["100.98.12.7"]["used"] == ["app"]


def test_a_pairing_counts_only_once_the_tokens_were_collected(hub):
    report = pairing_metrics(hub)
    report("refused", "100.98.12.9")
    report("expired", "100.98.12.9")
    assert hub.peers.summary()["items"] == []
    report("paired", "100.98.12.7")
    assert items(hub.peers)["100.98.12.7"]["used"] == ["pairing"]


def test_status_lists_the_devices_and_never_a_token(tmp_path):
    hub = Hub(tmp_path, keepalive_s=0, own_address="100.98.12.8")
    hub.peers.open("100.98.12.7", RELAY)
    hub.peers.seen("100.98.12.8", APP)
    with TestClient(mcp_app(hub, CFG, codes=PairCodes()), base_url="http://127.0.0.1:19435") as client:
        r = client.get("/status", headers={"Authorization": "Bearer " + "m" * 40})
    devices = r.json()["devices"]
    assert set(devices) == {"since", "items"}
    assert [(d["address"], d["this_server"], d["connected"]) for d in devices["items"]] == [
        ("100.98.12.7", False, {"relay": 1}), ("100.98.12.8", True, {})]
    assert set(devices["items"][0]) == {"address", "this_server", "name", "os", "lookup", "connected", "used",
                                        "first_seen", "last_seen"}
    assert not any(token in r.text for token in TOKENS)
