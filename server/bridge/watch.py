# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The reaction loop behind flipper_watch: look at the kept frames and, when a condition holds, press keys at once."""
from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable

from .hub import Hub
from .screen import region_bits, to_image
from .wire import ACTIVE

WATCH_MAX_S = 120.0  # the longest flipper_watch: it holds the Flipper for others meanwhile
WATCH_KEYS = 5  # keys per trigger
WATCH_REPEAT = 20
WATCH_POLL_S = 0.1  # how often the watch looks at the gate, the link and a "still" deadline between frames
UNTIL = ("change", "still", "match")

Press = Callable[[list[tuple[str, str]]], Awaitable[list[str]]]


class WatchError(Exception):
    """The watch cannot start or go on; the message is for the agent."""


class PressFailed(WatchError):
    """A key of a trigger failed; `press` raises it, with the message for the agent."""


def check_rule(until: str, ref_seq: int | None) -> str:
    """The condition, normalised, once it agrees with ref_seq (needed with "match", and only there)."""
    until = str(until).strip().lower()
    if until not in UNTIL:
        raise WatchError('until is "change", "still" or "match"')
    if (until == "match") != (ref_seq is not None):
        raise WatchError("ref_seq is required with until=match, and only used there")
    return until


def parse_region(region: list[int] | None) -> tuple[int, int, int, int] | None:
    if region is None or region == []:
        return None
    try:
        x, y, w, h = (int(v) for v in region)
    except (TypeError, ValueError):
        raise WatchError("region is [x, y, width, height] in screen pixels, e.g. [0, 0, 128, 12]") from None
    if x < 0 or y < 0 or w < 1 or h < 1:
        raise WatchError("region: x and y from 0, width and height from 1")
    return x, y, w, h


@dataclass
class WatchResult:
    until: str
    triggers: list[dict[str, Any]] = field(default_factory=list)
    timed_out: bool = False
    stopped: str | None = None
    elapsed_ms: int = 0

    def audit(self) -> dict[str, Any]:
        """The fields the watch's own audit line gets."""
        return {"triggers": len(self.triggers), "timed_out": self.timed_out, "stopped": self.stopped}

    def meta(self) -> dict[str, Any]:
        """The fields the screen result carries."""
        return {"until": self.until, "triggers": self.triggers, "timed_out": self.timed_out, "stopped": self.stopped,
                "elapsed_ms": self.elapsed_ms}


class Watch:
    """One watch of a region of the screen.

    `keys` are already parsed ((key, kind) pairs); `press` sends them and raises PressFailed when one fails.
    Each trigger is one line of the audit log (flipper_watch.trigger). `region` is [x, y, width, height] in the
    pixels of the image the agent sees; None is the whole screen.
    """

    def __init__(self, hub: Hub, press: Press, *, until: str, ref_seq: int | None, region: list[int] | None,
                 still_ms: int, keys: list[tuple[str, str]], repeat: int, timeout_s: float) -> None:
        if len(keys) > WATCH_KEYS:
            raise WatchError(f"At most {WATCH_KEYS} keys per trigger")
        repeat = max(1, min(int(repeat), WATCH_REPEAT))
        if repeat > 1 and not keys:
            raise WatchError("repeat needs keys: without keys the watch ends at the first trigger")
        self.hub = hub
        self.press = press
        self.until = until
        self.ref_seq = ref_seq
        self.keys = keys
        self.repeat = repeat
        self.box = parse_region(region)
        self.still = max(100, min(int(still_ms), 10000)) / 1000
        self.timeout = max(0.5, min(float(timeout_s), WATCH_MAX_S))

    def _gate_stop(self) -> str | None:
        if self.keys and self.hub.gate.state != ACTIVE:
            return f"control {self.hub.gate.state} by the operator"
        return None

    async def run(self) -> WatchResult:
        hub, box, until = self.hub, self.box, self.until
        current = hub.screen.frame
        if current is None or hub.screen.stale:
            raise WatchError("No current screen frame; the phone or the Flipper may be disconnected (flipper_status)")
        if box is not None:
            width, height = to_image(current).size
            if box[0] + box[2] > width or box[1] + box[3] > height:
                raise WatchError(f"region {list(box)} is outside the screen, which is {width}x{height} now")
        target = None
        if until == "match":
            ref = hub.screen.kept.get(int(self.ref_seq))
            if ref is None:
                raise WatchError(f"Frame {self.ref_seq} is no longer kept (only the last 32 screens returned to you): "
                                 "look with flipper_screen and use its seq")
            target = region_bits(ref, box)
        start = time.monotonic()
        deadline = start + self.timeout
        reference = last_bits = region_bits(current, box)
        still_since = start
        armed = True  # "still" and "match": after a trigger the condition has to end before the next one
        result = WatchResult(until)
        bits_seq = current.seq
        bits = reference
        while True:
            if current.seq != bits_seq:  # decode each frame once, not on every poll
                bits, bits_seq = region_bits(current, box), current.seq
            if until == "change":
                met = bits != reference
            elif until == "match":
                met = bits == target
            else:
                if bits != last_bits:
                    last_bits, still_since = bits, current.mono
                met = time.monotonic() - still_since >= self.still
            if not armed:
                armed, met = not met, False
            if met:
                result.stopped = self._gate_stop()
                if result.stopped:
                    break
                entry: dict[str, Any] = {"t_ms": round((time.monotonic() - start) * 1000), "seq": current.seq}
                seq_before = hub.screen.seq
                if self.keys:
                    try:
                        entry["keys"] = await self.press(self.keys)
                    except PressFailed as e:
                        raise WatchError(
                            f"Trigger {len(result.triggers) + 1}: {e} Earlier triggers: {result.triggers}") from None
                result.triggers.append(entry)
                hub.audit.write({"tool": "flipper_watch.trigger", "writes": bool(self.keys), "result": "ok",
                                 "until": until, **entry})
                if len(result.triggers) >= self.repeat:
                    break
                if until == "change":
                    frame, _ = await hub.screen.wait_settled(seq_before, 0.25, 1.5)
                    current = frame or hub.screen.frame
                    reference = region_bits(current, box)
                else:
                    armed = False
                    current = hub.screen.frame
                continue
            now = time.monotonic()
            if now >= deadline:
                result.timed_out = True
                break
            result.stopped = self._gate_stop()
            if result.stopped:
                break
            if hub.screen.stale:
                result.stopped = "the link to the phone or the Flipper dropped"
                break
            newer = await hub.screen.wait_newer(current.seq, min(deadline - now, WATCH_POLL_S))
            if newer is not None:
                current = newer
        result.elapsed_ms = round((time.monotonic() - start) * 1000)
        return result
