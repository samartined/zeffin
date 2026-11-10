# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The watch on its own, without MCP or a Flipper: frames come from the screen store, keys go to a fake press.
The last tests go through flipper_watch, for what tools.py adds: the failed press's text and the audit line."""
import asyncio
import time

import pytest
from mcp.server.mcpserver.exceptions import ToolError

from bridge.hub import Hub
from bridge.tools import build_mcp
from bridge.watch import PressFailed, Watch, WatchError, check_rule, parse_region
from bridge.wire import PAUSED
from fakes import FakeFlipper


def dots(*points):
    data = bytearray(1024)
    for x, y in points:
        data[(y // 8) * 128 + x] |= 1 << (y % 8)
    return bytes(data)


@pytest.fixture
async def hub(tmp_path):
    hub = Hub(tmp_path, keepalive_s=0)
    hub.screen.update(dots(), 0)
    return hub


def make(hub, *, until="change", keys=(), pressed=None, **kw):
    async def press(parsed):
        if pressed is not None:
            pressed.append(parsed)
        return [f"{k}:{t.lower()}" for k, t in parsed]

    opts = dict(until=until, ref_seq=None, region=None, still_ms=300, keys=list(keys), repeat=1, timeout_s=3)
    opts.update(kw)
    return Watch(hub, press, **opts)


def later(delay, fn, *args):
    asyncio.get_running_loop().call_later(delay, fn, *args)


def audit(hub, tool):
    return [r for r in hub.audit.recent if r["tool"] == tool]


def test_the_rule_and_the_region_are_checked():
    assert check_rule(" Change ", None) == "change" and check_rule("match", 4) == "match"
    for bad in (("later", None), ("match", None), ("change", 3)):
        with pytest.raises(WatchError):
            check_rule(*bad)
    assert parse_region(None) is None and parse_region([]) is None and parse_region([1, 2, 3, 4]) == (1, 2, 3, 4)
    for bad in ([1, 2], [0, 0, 0, 5], [-1, 0, 4, 4], ["a", 0, 1, 1]):
        with pytest.raises(WatchError):
            parse_region(bad)


async def test_a_change_inside_the_region_triggers_once_and_one_outside_does_not(hub):
    later(0.1, lambda: hub.screen.update(dots((100, 40)), 0))
    later(0.3, lambda: hub.screen.update(dots((100, 40), (2, 3)), 0))
    result = await make(hub, region=[0, 0, 8, 8]).run()
    assert not result.timed_out and result.stopped is None and len(result.triggers) == 1
    assert result.triggers[0]["seq"] == hub.screen.seq and "keys" not in result.triggers[0]
    assert result.audit() == {"triggers": 1, "timed_out": False, "stopped": None}
    assert result.meta()["until"] == "change"
    line = audit(hub, "flipper_watch.trigger")[-1]
    assert line["until"] == "change" and line["writes"] is False and line["seq"] == result.triggers[0]["seq"]


async def test_a_still_screen_triggers_after_still_ms_and_a_moving_one_times_out(hub):
    result = await make(hub, until="still", still_ms=300).run()
    assert len(result.triggers) == 1 and result.triggers[0]["t_ms"] >= 250
    for i in range(1, 30):
        later(i * 0.05, lambda i=i: hub.screen.update(dots((i, 1)), 0))
    result = await make(hub, until="still", still_ms=500, timeout_s=0.6).run()
    assert result.timed_out and result.triggers == []


async def test_match_compares_with_a_kept_frame_and_refuses_one_that_is_gone(hub):
    ref = hub.screen.update(dots((5, 5)), 0)
    hub.screen.keep(ref)
    hub.screen.update(dots(), 0)
    later(0.1, lambda: hub.screen.update(dots((5, 5)), 0))
    result = await make(hub, until="match", ref_seq=ref.seq, region=[0, 0, 16, 16]).run()
    assert len(result.triggers) == 1
    with pytest.raises(WatchError, match="no longer kept"):
        await make(hub, until="match", ref_seq=999).run()


async def test_keys_are_pressed_at_each_trigger_and_the_watch_re_arms(hub):
    pressed = []
    later(0.1, lambda: hub.screen.update(dots((1, 1)), 0))
    later(2.2, lambda: hub.screen.update(dots((2, 2)), 0))  # after the settle wait of the first
    result = await make(hub, keys=[("RIGHT", "SHORT")], pressed=pressed, repeat=2, timeout_s=5,
                        region=[0, 0, 8, 8]).run()
    assert len(result.triggers) == 2 and pressed == [[("RIGHT", "SHORT")]] * 2
    assert result.triggers[0]["keys"] == ["RIGHT:short"]
    assert [r["writes"] for r in audit(hub, "flipper_watch.trigger")] == [True, True]


async def test_a_paused_gate_stops_a_watch_with_keys_but_not_one_without(hub):
    hub.gate.set(PAUSED, "test")
    later(0.1, lambda: hub.screen.update(dots((1, 1)), 0))
    pressed = []
    result = await make(hub, keys=[("OK", "SHORT")], pressed=pressed).run()
    assert result.stopped == "control paused by the operator" and result.triggers == [] and pressed == []
    result = await make(hub, timeout_s=0.5).run()  # a watch that presses nothing does not care about the gate
    assert result.stopped is None and len(result.triggers) == 1


async def test_a_pause_that_comes_with_the_change_stops_the_trigger(hub):
    def pause_and_change():
        hub.gate.set(PAUSED, "test")
        hub.screen.update(dots((1, 1)), 0)

    later(0.15, pause_and_change)  # between two polls, so the watch sees both at once, at the trigger itself
    pressed = []
    result = await make(hub, keys=[("OK", "SHORT")], pressed=pressed).run()
    assert result.stopped == "control paused by the operator" and result.triggers == [] and pressed == []
    assert audit(hub, "flipper_watch.trigger") == []


@pytest.mark.parametrize("until", ["still", "match"])
async def test_still_and_match_wait_for_the_condition_to_end_before_triggering_again(hub, until):
    ref = hub.screen.frame
    hub.screen.keep(ref)
    later(0.6, lambda: hub.screen.update(dots((1, 1)), 0))  # the condition ends
    later(0.9, lambda: hub.screen.update(dots(), 0))  # and holds again
    pressed = []
    result = await make(hub, until=until, ref_seq=ref.seq if until == "match" else None, still_ms=300,
                        keys=[("OK", "SHORT")], pressed=pressed, repeat=2, timeout_s=3).run()
    assert len(result.triggers) == 2 and len(pressed) == 2 and not result.timed_out
    first, second = (t["t_ms"] for t in result.triggers)
    assert second - first >= 500  # not right after the first, while the condition still held


async def test_still_counts_from_the_frame_not_from_when_the_watch_looks_at_it(hub):
    changed = []

    def change_then_block():
        changed.append(hub.screen.update(dots((1, 1)), 0).seq)
        time.sleep(0.3)  # the watch only sees the frame 0.3 s after it came

    later(0.15, change_then_block)
    result = await make(hub, until="still", still_ms=500).run()
    assert len(result.triggers) == 1 and result.triggers[0]["seq"] == changed[0]
    assert 600 <= result.triggers[0]["t_ms"] < 850  # 0.5 s after the frame came, not after the watch saw it


async def test_a_dropped_link_stops_the_watch_and_no_screen_is_refused(hub, tmp_path):
    later(0.1, hub.screen.mark_stale)
    result = await make(hub, timeout_s=3).run()
    assert result.stopped == "the link to the phone or the Flipper dropped" and not result.timed_out
    with pytest.raises(WatchError, match="No current screen frame"):
        await make(hub).run()
    with pytest.raises(WatchError, match="No current screen frame"):
        await make(Hub(tmp_path / "empty", keepalive_s=0)).run()


async def test_a_failed_press_names_the_trigger_and_the_earlier_ones(hub):
    async def press(parsed):
        raise PressFailed("Key 1/1 failed.")

    later(0.1, lambda: hub.screen.update(dots((1, 1)), 0))
    watch = Watch(hub, press, until="change", ref_seq=None, region=None, still_ms=300,
                  keys=[("OK", "SHORT")], repeat=1, timeout_s=3)
    with pytest.raises(WatchError, match=r"Trigger 1: Key 1/1 failed\. Earlier triggers: \[\]"):
        await watch.run()


async def test_the_arguments_are_bounded(hub):
    assert make(hub, timeout_s=999).timeout == 120.0 and make(hub, timeout_s=0).timeout == 0.5
    assert make(hub, still_ms=1).still == 0.1 and make(hub, still_ms=10**6).still == 10.0
    assert make(hub, keys=[("OK", "SHORT")], repeat=99).repeat == 20 and make(hub, repeat=0).repeat == 1
    with pytest.raises(WatchError, match="At most 5 keys"):
        make(hub, keys=[("OK", "SHORT")] * 6)
    with pytest.raises(WatchError, match="repeat needs keys"):
        make(hub, repeat=2)
    with pytest.raises(WatchError, match="outside the screen"):
        await make(hub, region=[120, 0, 16, 8]).run()


async def error_of(server, name, **args):
    """As in test_tools.py: a ToolError is the text the model reads."""
    try:
        await server.call_tool(name, args)
    except ToolError as e:
        return str(e)
    raise AssertionError("the call did not fail")


@pytest.fixture
async def mcp(tmp_path):
    hub = Hub(tmp_path, keepalive_s=0)
    flipper = FakeFlipper(hub)
    await flipper.connect()
    flipper.emit_frame(data=dots())
    await asyncio.sleep(0.05)
    return hub, flipper, build_mcp(hub)


async def test_flipper_watch_reports_a_failed_press_as_before_and_audits_the_bounded_repeat(mcp):
    hub, flipper, server = mcp
    flipper.fail = {("OK", "PRESS")}
    later(0.1, lambda: flipper.emit_frame(data=dots((1, 1))))
    error = await error_of(server, "flipper_watch", until="change", keys=["OK"], repeat=99, timeout_s=3)
    said = ("Trigger 1: Key 1/1 (OK:short) failed: ERROR_INVALID_PARAMETERS. Sent before it: []. "
            "Not retried; look at the screen before continuing. Earlier triggers: []")
    assert error == f"Error executing tool flipper_watch: {said}"
    line = [r for r in hub.audit.recent if r["tool"] == "flipper_watch"][-1]
    assert line["args"]["repeat"] == 20 and line["result"] == f"error: {said}"
