# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The command_id ranges of /phone (docs/protocol/rpc/README.md): the relay's own messages never reach the server's
replies or flipper_events, and the settle stop is still found among them."""
import asyncio

import pytest

from bridge import pb
from bridge import phone as phone_module
from bridge.framing import frame
from bridge.hub import Hub
from bridge.phone import (RELAY_ID_MAX, RELAY_ID_MIN, SERVER_ID_MAX, SETTLE_ID_MAX, SETTLE_ID_MIN, RpcTimeout,
                          build)
from fakes import FakeFlipper


@pytest.fixture
async def hub_flipper(tmp_path):
    hub = Hub(tmp_path, keepalive_s=0)
    flipper = FakeFlipper(hub)
    await flipper.connect()
    return hub, flipper


def relay_reply(cid: int, status: str = "OK", has_next: bool = False) -> bytes:
    """The Flipper's reply to a message the relay sent: empty content, with its id."""
    message = pb.Main(command_id=cid, command_status=pb.CommandStatus.Value(status), has_next=has_next)
    message.empty.SetInParent()
    return frame(message.SerializeToString())


def test_the_ranges_are_ordered_and_disjoint():
    assert 0 < 1 <= SERVER_ID_MAX < SETTLE_ID_MIN <= SETTLE_ID_MAX < RELAY_ID_MIN <= RELAY_ID_MAX < 1 << 30
    assert (SERVER_ID_MAX, SETTLE_ID_MIN, SETTLE_ID_MAX) == ((1 << 28) - 1, 1 << 28, (1 << 29) - 1)
    assert (RELAY_ID_MIN, RELAY_ID_MAX) == (1 << 29, (1 << 30) - 1)


async def test_the_servers_ids_wrap_inside_its_range(hub_flipper):
    _, flipper = hub_flipper
    session = flipper.session
    session._next_id = SERVER_ID_MAX - 1
    assert [session._allocate_id() for _ in range(3)] == [SERVER_ID_MAX - 1, SERVER_ID_MAX, 1]


@pytest.mark.parametrize("draw", [0, SETTLE_ID_MAX - SETTLE_ID_MIN])
async def test_the_settle_stop_takes_an_id_in_its_range(tmp_path, monkeypatch, draw):
    bounds = []

    def randbelow(n):
        bounds.append(n)
        return min(draw, n - 1)

    monkeypatch.setattr(phone_module.secrets, "randbelow", randbelow)
    hub = Hub(tmp_path, keepalive_s=0)
    flipper = FakeFlipper(hub)
    await flipper.connect()
    stop = flipper.requests[0]
    assert stop.WhichOneof("content") == "gui_stop_screen_stream_request"
    assert SETTLE_ID_MIN <= stop.command_id <= SETTLE_ID_MAX
    assert bounds == [SETTLE_ID_MAX - SETTLE_ID_MIN + 1]


@pytest.mark.parametrize("cid", [RELAY_ID_MIN, RELAY_ID_MIN + 7, RELAY_ID_MAX])
async def test_a_relay_reply_is_counted_and_never_reaches_flipper_events(hub_flipper, cid):
    hub, flipper = hub_flipper
    session = flipper.session
    session.on_bytes(relay_reply(cid) + relay_reply(cid, "ERROR_INVALID_PARAMETERS")
                     + relay_reply(cid, has_next=True))
    assert session.relay_replies == 3
    assert list(hub.unsolicited) == []
    assert not session.closed


async def test_messages_outside_the_relays_range_still_reach_flipper_events(hub_flipper):
    hub, flipper = hub_flipper
    for cid in (RELAY_ID_MIN - 1, RELAY_ID_MAX + 1):
        flipper.session.on_bytes(relay_reply(cid))
    assert [e["command_id"] for e in hub.unsolicited] == [RELAY_ID_MIN - 1, RELAY_ID_MAX + 1]
    assert flipper.session.relay_replies == 0


async def test_a_relay_reply_never_satisfies_a_pending_request(hub_flipper, monkeypatch):
    # Were a request to hold a relay id, the relay's reply would answer it: the drop comes before pending.
    hub, flipper = hub_flipper
    session = flipper.session
    flipper.respond = False
    monkeypatch.setattr(session, "_allocate_id", lambda: RELAY_ID_MIN + 3)
    task = asyncio.create_task(session.request([build("system_ping_request")], timeout=0.3))
    for _ in range(10):
        await asyncio.sleep(0)
    session.on_bytes(relay_reply(RELAY_ID_MIN + 3))
    with pytest.raises(RpcTimeout):
        await task
    assert session.relay_replies == 1 and list(hub.unsolicited) == []


async def test_the_close_metric_counts_the_relay_replies(hub_flipper):
    hub, flipper = hub_flipper
    flipper.session.on_bytes(relay_reply(RELAY_ID_MIN) * 2)
    await flipper.session.abort("test")
    close = [e for e in hub.metrics.recent if e["ev"] == "close"][-1]
    assert close["relay_replies"] == 2


async def test_the_settle_stop_is_found_among_relay_replies(tmp_path, monkeypatch):
    settle_id = SETTLE_ID_MIN + 4242
    monkeypatch.setattr(phone_module.secrets, "randbelow", lambda n: settle_id - SETTLE_ID_MIN)
    hub = Hub(tmp_path, keepalive_s=0)
    flipper = FakeFlipper(hub)
    flipper.respond = False
    assert hub.reserve(flipper.session) is None
    await flipper.session.on_text('{"type": "ready", "protocol": 1, "transport": "BLE"}')
    for _ in range(5):
        await asyncio.sleep(0)
    session = flipper.session
    stop = flipper.requests[0]
    assert stop.command_id == settle_id
    stop_reply = pb.Main(command_id=settle_id)
    stop_reply.empty.SetInParent()
    before = relay_reply(RELAY_ID_MIN) + relay_reply(RELAY_ID_MIN + 1, "ERROR_INVALID_PARAMETERS")
    after = relay_reply(RELAY_ID_MIN + 2) * 2
    session.on_bytes(before + frame(stop_reply.SerializeToString()) + after)
    flipper.respond = True
    await asyncio.wait_for(session.settled.wait(), 2)
    for _ in range(20):
        await asyncio.sleep(0)
    assert not session.closed, session.close_reason
    settle = [e for e in hub.metrics.recent if e["ev"] == "settle"][-1]
    assert settle["synced"] is True
    assert settle["dropped_bytes"] == len(before) + len(frame(stop_reply.SerializeToString()))
    assert session.relay_replies == 2  # the ones after the stop's reply are read, and dropped by id
    assert list(hub.unsolicited) == []
    assert await session.request([build("system_ping_request")], timeout=1)
