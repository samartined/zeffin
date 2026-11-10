# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""One bounded queue per listener: each owner keeps its queue size, and a full queue loses its oldest item."""
import pytest

from bridge.fanout import FanOut
from bridge.gate import ControlGate, JsonlLog
from bridge.screen import FRAME_BYTES, ScreenStore
from bridge.wire import ACTIVE, PAUSED


def drain(q):
    items = []
    while not q.empty():
        items.append(q.get_nowait())
    return items


def test_every_listener_gets_every_item_and_a_listener_that_left_gets_none():
    fan = FanOut(4)
    a, b = fan.listen(), fan.listen()
    fan.publish(1)
    fan.unlisten(b)
    fan.publish(2)
    fan.unlisten(b)  # twice is fine
    assert drain(a) == [1, 2] and drain(b) == [1] and len(fan) == 1


def test_a_full_queue_loses_its_oldest_item_and_the_publisher_never_blocks():
    fan = FanOut(3)
    slow, quick = fan.listen(), fan.listen()
    for i in range(5):
        fan.publish(i)
        if i == 4:
            assert drain(quick) == [2, 3, 4]
    assert drain(slow) == [2, 3, 4] and slow.maxsize == 3


def test_the_screen_keeps_only_the_latest_frame_number_per_subscriber():
    screen = ScreenStore()
    assert screen.subscribe().empty()  # no frame yet: nothing to push
    for _ in range(3):
        screen.update(bytes(FRAME_BYTES), 0)
    q = screen.subscribe()
    assert drain(q) == [3]  # the current frame is pushed on subscribe
    for _ in range(4):
        screen.update(bytes(FRAME_BYTES), 0)
    assert q.maxsize == 1 and drain(q) == [7]
    screen.mark_stale()
    assert drain(q) == [7]  # a stale mark wakes the listener with the same number
    screen.unsubscribe(q)
    screen.update(bytes(FRAME_BYTES), 0)
    assert q.empty()


def test_the_gate_keeps_the_last_8_changes_per_listener():
    gate = ControlGate()
    q = gate.listen()
    assert q.maxsize == 8
    for i in range(12):
        gate.set(PAUSED if i % 2 == 0 else ACTIVE, f"op{i}")
    assert [s["by"] for s in drain(q)] == [f"op{i}" for i in range(4, 12)]
    gate.unlisten(q)
    gate.set(PAUSED, "later")
    assert q.empty()


@pytest.mark.parametrize("keep", [0, 5])
def test_a_log_keeps_the_last_64_events_per_listener(keep):
    log = JsonlLog(None, keep)
    q = log.listen()
    assert q.maxsize == 64
    for i in range(70):
        log.write({"n": i})
    assert [r["n"] for r in drain(q)] == list(range(6, 70))
    log.unlisten(q)
    log.write({"n": 70})
    assert q.empty()
