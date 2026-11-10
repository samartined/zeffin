# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
import asyncio
import time

import pytest
from google.protobuf.message import DecodeError

from bridge import pb
from bridge import hub as hub_module
from bridge import phone as phone_module
from bridge.framing import frame, varint
from bridge.gate import GateClosed
from bridge.hub import Hub
from bridge.phone import SETTLE_ID_MAX, SETTLE_ID_MIN, LinkError, RpcError, RpcTimeout, build
from fakes import FakeFlipper


@pytest.fixture
async def hub_flipper(tmp_path):
    hub = Hub(tmp_path, keepalive_s=0)
    flipper = FakeFlipper(hub)
    await flipper.connect()
    return hub, flipper


async def test_ready_starts_stream_and_receives_frame(hub_flipper):
    hub, flipper = hub_flipper
    kinds = [r.WhichOneof("content") for r in flipper.requests[:2]]
    assert kinds == ["gui_stop_screen_stream_request", "gui_start_screen_stream_request"]
    assert hub.screen.seq == 1 and not hub.screen.stale
    events = [e["ev"] for e in hub.metrics.recent]
    assert events[:3] == ["ws_open", "ready", "stream"] or "first_frame" in events


async def test_frames_are_uncorrelated_and_keep_latest(hub_flipper):
    hub, flipper = hub_flipper
    for fill in (1, 2, 3):
        flipper.emit_frame(fill)
    assert hub.screen.seq == 4
    assert hub.screen.frame.data[0] == 3


async def test_multipart_reply(hub_flipper):
    hub, _ = hub_flipper
    replies = await hub.request([build("system_device_info_request")])
    assert [r.system_device_info_response.key for r in replies] == ["hardware_name", "firmware_version"]


async def test_timeout_fails_only_that_command(hub_flipper):
    hub, flipper = hub_flipper
    flipper.respond = False
    with pytest.raises(RpcTimeout):
        await hub.request([build("system_ping_request")], timeout=0.05)
    assert hub.session is flipper.session and flipper.session.ready
    sent = len(flipper.requests)
    flipper.respond = True
    await hub.request([build("system_ping_request")])
    assert len(flipper.requests) == sent + 1  # nothing replayed


async def test_rpc_error_status(hub_flipper):
    hub, _ = hub_flipper
    with pytest.raises(RpcError) as e:
        await hub.request([build("storage_stat_request", {"path": "/ext/missing"})])
    assert e.value.status_name == "ERROR_STORAGE_NOT_EXIST"


async def test_missing_ack_closes_session(hub_flipper, monkeypatch):
    hub, flipper = hub_flipper
    monkeypatch.setattr("bridge.phone.ACK_TIMEOUT", 0.05)
    flipper.ack = False
    with pytest.raises(LinkError):
        await hub.request([build("system_ping_request")])
    assert flipper.session.closed and hub.session is None and hub.screen.stale


async def test_unexpected_written_is_protocol_error(hub_flipper):
    _, flipper = hub_flipper
    with pytest.raises(ValueError):
        await flipper.session.on_text('{"type":"written"}')


async def test_decode_error_from_flipper_is_fatal(hub_flipper):
    _, flipper = hub_flipper
    m = pb.Main(command_id=0, command_status=pb.CommandStatus.Value("ERROR_DECODE"))
    m.empty.SetInParent()
    with pytest.raises(ValueError):
        flipper.send(m)


async def test_binary_before_ready_rejected(tmp_path):
    hub = Hub(tmp_path, keepalive_s=0)
    flipper = FakeFlipper(hub)
    assert hub.reserve(flipper.session) is None
    with pytest.raises(ValueError):
        flipper.session.on_bytes(b"\x01\x00")


async def test_second_phone_refused(hub_flipper):
    hub, _ = hub_flipper
    other = FakeFlipper(hub)
    assert hub.reserve(other.session) == "busy"


async def test_disconnect_fails_pending_and_allows_reconnect(hub_flipper):
    hub, flipper = hub_flipper
    flipper.respond = False
    task = asyncio.create_task(hub.request([build("system_ping_request")], timeout=5))
    await asyncio.sleep(0.01)
    await flipper.session.abort("test drop")
    with pytest.raises(LinkError):
        await task
    again = FakeFlipper(hub)
    await again.connect("USB")
    assert hub.status()["transport"] == "USB" and hub.screen.seq == 2


async def test_large_write_is_packed_under_limits(hub_flipper):
    hub, flipper = hub_flipper
    data = bytes(range(256)) * 4096  # 1 MiB
    messages = []
    for i in range(0, len(data), 512):
        m = pb.Main()
        m.storage_write_request.path = "/ext/big.bin"
        m.storage_write_request.file.data = data[i:i + 512]
        messages.append(m)
    before = len(flipper.frames_sent)
    await hub.request(messages, timeout=30)
    sizes = flipper.frames_sent[before:]
    assert flipper.files["/ext/big.bin"] == data
    assert max(sizes) <= 8192 + 600 and len(sizes) < 200


async def test_wait_settled_quiet_and_animated(hub_flipper):
    hub, flipper = hub_flipper
    seq = hub.screen.seq

    async def burst(n, gap):
        for i in range(n):
            await asyncio.sleep(gap)
            flipper.emit_frame(i)

    asyncio.create_task(burst(3, 0.02))
    frame, settled = await hub.screen.wait_settled(seq, idle=0.1, max_wait=1.0)
    assert settled and frame.seq == seq + 3

    seq = hub.screen.seq
    # Frames every 20 ms against a 300 ms idle window: a busy CI runner cannot fake a quiet screen.
    anim = asyncio.create_task(burst(200, 0.02))
    frame, settled = await hub.screen.wait_settled(seq, idle=0.3, max_wait=0.8)
    anim.cancel()
    assert not settled

    seq = hub.screen.seq
    frame, settled = await hub.screen.wait_settled(seq, idle=0.05, max_wait=0.1)
    assert settled and frame.seq == seq  # nothing happened


async def test_view_event_stream_yields_status_and_frames(hub_flipper):
    from bridge.view import _event_stream

    hub, flipper = hub_flipper
    stream = _event_stream(hub)
    first = await anext(stream)
    assert first.startswith(b"event: status")
    assert (await anext(stream)).startswith(b"event: history")
    assert (await anext(stream)).startswith(b"event: phone_history")
    assert (await anext(stream)).startswith(b"event: frame")  # current frame is pushed on subscribe
    flipper.emit_frame(9)
    chunks = [await anext(stream) for _ in range(2)]
    assert any(c.startswith(b"event: frame") for c in chunks)
    await stream.aclose()
    assert not hub.screen._fanout


async def test_cancelled_call_does_not_break_ack_accounting(hub_flipper):
    hub, flipper = hub_flipper
    flipper.ack = False
    task = asyncio.create_task(hub.request([build("system_ping_request")], timeout=5))
    await asyncio.sleep(0.01)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    await flipper.session.on_text('{"type":"written"}')  # late ack for the cancelled frame is accepted
    flipper.ack = True
    await hub.request([build("system_ping_request")])
    assert flipper.session.ready and not flipper.session.closed


async def test_keepalive_skips_while_busy_and_closes_dead_link(tmp_path):
    hub = Hub(tmp_path, keepalive_s=0.05)
    flipper = FakeFlipper(hub)
    await flipper.connect()
    await asyncio.sleep(0.2)
    assert any(e["ev"] == "ping" and e["ok"] for e in hub.metrics.recent)
    flipper.respond = False
    async with flipper.session.send_lock:  # simulate a long write holding the transport
        await asyncio.sleep(0.2)
    assert not flipper.session.closed
    await asyncio.sleep(0.6)
    assert flipper.session.closed and "keepalive" in flipper.session.close_reason


async def test_a_reused_link_mid_frame_settles_before_reading(tmp_path):
    # Android kept the Bluetooth link of an earlier session and the Flipper is still streaming:
    # the new session first sees the tail of a frame. It must not fail with "Invalid varint length".
    hub = Hub(tmp_path, keepalive_s=0)
    flipper = FakeFlipper(hub)
    assert hub.reserve(flipper.session) is None
    await flipper.session.on_text('{"type": "ready", "protocol": 1, "transport": "BLE"}')
    flipper.session.on_bytes(b"\xff" * 7 + bytes(300))  # the middle of an old screen frame
    await asyncio.wait_for(flipper.session.settled.wait(), 2)
    for _ in range(20):
        await asyncio.sleep(0)
    assert not flipper.session.closed and hub.screen.seq >= 1
    assert any(e["ev"] == "settle" and e["dropped_bytes"] >= 307 for e in hub.metrics.recent)


def feed(session, data: bytes) -> None:
    """Bytes from the phone as phone_endpoint delivers them: a protocol error closes the session."""
    try:
        session.on_bytes(data)
    except (ValueError, DecodeError) as e:
        session.spawn(session.abort(f"Protocol error: {e}"))


def old_frame() -> bytes:
    """A screen frame of the earlier RPC session; its second half read as new messages is "Wire format was corrupt"."""
    old = pb.Main()
    old.gui_screen_frame.data = bytes([0x07, 0xFF, 0x00, 0x3C]) * 256
    return frame(old.SerializeToString())


async def reused_link(tmp_path, monkeypatch, settle_id: int = SETTLE_ID_MIN + 12345):
    """A session opened on a link whose Flipper is still sending; the fake answers nothing until the test says so."""
    monkeypatch.setattr(phone_module.secrets, "randbelow", lambda n: settle_id - SETTLE_ID_MIN)
    hub = Hub(tmp_path, keepalive_s=0)
    flipper = FakeFlipper(hub)
    flipper.respond = False
    assert hub.reserve(flipper.session) is None
    await flipper.session.on_text('{"type": "ready", "protocol": 1, "transport": "BLE"}')
    for _ in range(5):  # the stop reaches the fake
        await asyncio.sleep(0)
    return hub, flipper


async def trickle(session, data: bytes, pause_at: int | None = None) -> None:
    """MTU 23: 20-byte chunks, with a pause longer than the old 0.4 s quiet window at pause_at."""
    for i in range(0, len(data), 20):
        if i == pause_at:
            await asyncio.sleep(0.5)
        feed(session, data[i:i + 20])
        await asyncio.sleep(0.002)


async def answer_the_stop(hub, flipper, status: str | None = None) -> None:
    flipper.respond = True
    stop = flipper.requests[0]
    assert stop.WhichOneof("content") == "gui_stop_screen_stream_request" and stop.command_id >= SETTLE_ID_MIN
    if status:
        flipper.error(stop, status)
    else:
        flipper.handle(stop)
    await asyncio.wait_for(flipper.session.settled.wait(), 2)
    for _ in range(20):
        await asyncio.sleep(0)


async def test_a_slow_reused_link_pausing_inside_a_frame_settles_at_the_stop_reply(tmp_path, monkeypatch):
    # MTU 23 on a reused link: the old session's frame comes in 20-byte chunks, with a pause inside it longer than
    # the quiet window settle used to wait for. Reading the rest of the frame as new messages closed the session
    # ("Wire format was corrupt" on a real phone). The Flipper answers the stop after the frame it is sending.
    hub, flipper = await reused_link(tmp_path, monkeypatch)
    data = old_frame()
    await trickle(flipper.session, data, pause_at=520)
    assert not flipper.session.closed, flipper.session.close_reason
    await answer_the_stop(hub, flipper)
    assert not flipper.session.closed, flipper.session.close_reason
    assert hub.screen.seq == 1 and hub.screen.frame.data[0] == 0x00  # the new stream's frame, not the old one
    settle = [e for e in hub.metrics.recent if e["ev"] == "settle"]
    assert settle[-1]["synced"] is True and settle[-1]["dropped_bytes"] == len(data) + 9  # the frame and the reply
    assert (await hub.request([build("system_ping_request", {"data": "Ymc="})], timeout=1))


async def test_the_stop_reply_is_found_with_an_error_status(tmp_path, monkeypatch):
    # The stream was not running: the reply carries a command_status between its id and its content.
    hub, flipper = await reused_link(tmp_path, monkeypatch, settle_id=SETTLE_ID_MAX)
    await answer_the_stop(hub, flipper, status="ERROR_BUSY")
    assert not flipper.session.closed and hub.screen.seq == 1
    assert [e for e in hub.metrics.recent if e["ev"] == "settle"][-1]["synced"] is True


async def test_the_stop_reply_s_bytes_inside_a_bitmap_are_not_taken_for_it(tmp_path, monkeypatch):
    settle_id = SETTLE_ID_MIN + 777
    hub, flipper = await reused_link(tmp_path, monkeypatch, settle_id)
    # a bitmap holding a one-byte length, 0x08 and the stop's id, followed by bytes that do not parse
    lookalike = bytes([8]) + b"\x08" + varint(settle_id) + b"\xff\xff"
    old = pb.Main()
    old.gui_screen_frame.data = bytes([0x07, 0xFF, 0x00, 0x3C]) * 100 + lookalike + bytes([0x07, 0xFF, 0x00, 0x3C]) * 150
    data = frame(old.SerializeToString())
    await trickle(flipper.session, data)
    await answer_the_stop(hub, flipper)
    assert not flipper.session.closed, flipper.session.close_reason
    settle = [e for e in hub.metrics.recent if e["ev"] == "settle"][-1]
    assert settle["synced"] is True and settle["dropped_bytes"] == len(data) + 9


async def test_without_the_stop_reply_reading_starts_at_the_limit(tmp_path, monkeypatch):
    monkeypatch.setattr(hub_module, "SETTLE_LIMIT_S", 0.2)
    hub, flipper = await reused_link(tmp_path, monkeypatch)
    await asyncio.wait_for(flipper.session.settled.wait(), 2)
    assert [e for e in hub.metrics.recent if e["ev"] == "settle"][-1] == {
        "ts": pytest.approx(time.time(), abs=5), "ev": "settle", "dropped_bytes": 0, "synced": False}
    flipper.respond = True
    flipper.emit_frame(0x42)
    assert hub.screen.seq == 1 and hub.screen.frame.data[0] == 0x42


async def test_a_long_multipart_reply_lives_while_parts_keep_coming(hub_flipper):
    hub, flipper = hub_flipper
    flipper.device_info_parts, flipper.part_gap = 8, 0.05  # 0.35 s in total, never 0.2 s without a part
    replies = await hub.request([build("system_device_info_request")], timeout=0.2)
    assert len(replies) == 8


async def test_parts_that_stop_time_out_naming_how_many_came(hub_flipper):
    hub, flipper = hub_flipper
    flipper.device_info_parts, flipper.part_gap = 4, 0.3
    with pytest.raises(RpcTimeout, match="after 1 parts"):
        await hub.request([build("system_device_info_request")], timeout=0.2)


async def test_a_batch_goes_in_one_frame_and_reports_each_command(hub_flipper):
    hub, flipper = hub_flipper
    flipper.fail = {("OK", "SHORT")}
    frames = len(flipper.frames_sent)
    events = [[build("gui_send_input_event_request", {"key": "OK", "type": t})] for t in ("PRESS", "SHORT", "RELEASE")]
    results = await hub.request_batch(events, 2)
    assert len(flipper.frames_sent) == frames + 1
    assert not isinstance(results[0], Exception) and isinstance(results[1], RpcError)
    assert not isinstance(results[2], Exception)
    assert flipper.inputs == [("OK", "PRESS"), ("OK", "SHORT"), ("OK", "RELEASE")]


async def test_file_writes_go_in_small_frames(hub_flipper):
    hub, flipper = hub_flipper
    from bridge.tools import WRITE_PACK
    messages = []
    for _ in range(16):
        m = pb.Main()
        m.storage_write_request.path = "/ext/big.bin"
        m.storage_write_request.file.data = bytes(512)
        messages.append(m)
    before = len(flipper.frames_sent)
    await hub.request(messages, pack_limit=WRITE_PACK)
    sizes = flipper.frames_sent[before:]
    assert len(sizes) >= 4 and max(sizes) <= WRITE_PACK


async def test_settle_waits_through_the_loaders_transition_frame(hub_flipper):
    """An app closing: its portrait frame, then the loader's still landscape frame, then the destination 1 s later."""
    hub, flipper = hub_flipper
    flipper.emit_frame(0x11, orientation=2)  # the app, portrait
    seq = hub.screen.seq

    async def flipper_side():
        await asyncio.sleep(0.05)
        flipper.emit_frame(0x22, orientation=0)  # the loader's transition frame, still for a while
        await asyncio.sleep(0.6)
        flipper.emit_frame(0x33, orientation=0)  # the destination

    asyncio.ensure_future(flipper_side())
    frame, settled = await hub.screen.wait_settled(seq, idle=0.1, max_wait=2.0)
    assert frame.data[0] == 0x33 and settled is True

    # the same orientation throughout: a still frame after one change is settled, as before
    seq = hub.screen.seq
    asyncio.get_running_loop().call_later(0.05, flipper.emit_frame, 0x44, 0)
    frame, settled = await hub.screen.wait_settled(seq, idle=0.1, max_wait=1.0)
    assert frame.data[0] == 0x44 and settled is True

    # a transition that never resolves within max_wait is reported as not settled
    seq = hub.screen.seq
    asyncio.get_running_loop().call_later(0.05, flipper.emit_frame, 0x55, 2)
    frame, settled = await hub.screen.wait_settled(seq, idle=0.1, max_wait=0.5)
    assert frame.data[0] == 0x55 and settled is False


@pytest.mark.parametrize("action, state", [("stop", "stopped"), ("pause", "paused")])
async def test_the_operators_pause_or_stop_survives_a_restart(tmp_path, action, state):
    hub = Hub(tmp_path, keepalive_s=0)
    await hub.operator(action, by="app 100.98.12.7")
    assert oct((tmp_path / "gate.json").stat().st_mode & 0o777) == "0o600"
    again = Hub(tmp_path, keepalive_s=0)
    assert again.gate.state == state and again.gate.by == "app 100.98.12.7"
    assert again.gate.changed_at == hub.gate.changed_at
    flipper = FakeFlipper(again)
    if state == "stopped":
        assert again.reserve(flipper.session) == "stopped"
    else:
        await flipper.connect()
        with pytest.raises(GateClosed):
            again.gate.check()
    await again.operator("resume", by="view")
    assert Hub(tmp_path, keepalive_s=0).gate.state == "active"


@pytest.mark.parametrize("action, state", [("stop", "stopped"), ("pause", "paused")])
async def test_a_restored_pause_or_stop_is_logged(tmp_path, action, state):
    await Hub(tmp_path, keepalive_s=0).operator(action, by="app 100.98.12.7")
    again = Hub(tmp_path, keepalive_s=0)
    entry = list(again.audit.recent)[-1]
    assert entry["tool"] == "operator" and entry["action"] == action and entry["by"] == "app"
    assert entry["restored"] is True and entry["result"] == "ok"
    assert again.restored_gate() == {"gate": state, "gate_by": "app", "gate_changed_at": round(again.gate.changed_at, 3)}
    await again.operator("resume", by="view")
    fresh = Hub(tmp_path, keepalive_s=0)
    assert fresh.restored_gate() == {} and not fresh.audit.recent


@pytest.mark.parametrize("content", ["{not json", '{"state": "nonsense", "changed_at": 1, "by": "x"}', "[]", ""])
def test_an_unreadable_gate_file_means_active(tmp_path, content):
    (tmp_path / "gate.json").write_text(content)
    hub = Hub(tmp_path, keepalive_s=0)
    assert hub.gate.state == "active" and hub.gate.by == "startup"
