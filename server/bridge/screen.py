# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""Latest-frame store for the Flipper screen stream.

The firmware (applications/services/rpc/rpc_gui.c) emits a ScreenFrame on every
GUI commit while the stream is active, whatever app is running. Frames are not
correlated with any request (command_id 0). Only the newest frame matters.

Frames stay in memory. The one written to disk, latest.png, is the last screen a tool handed the agent
(save_latest, called by tools.screen_result), never every frame that arrives.
"""
from __future__ import annotations

import asyncio
import hashlib
import io
import logging
import time
from collections import OrderedDict
from dataclasses import dataclass
from pathlib import Path

from PIL import Image

from .fanout import FanOut
from .gate import write_atomic

WIDTH, HEIGHT = 128, 64
FRAME_BYTES = WIDTH * HEIGHT // 8
KEPT_FRAMES = 32

log = logging.getLogger(__name__)

# pb.gui.ScreenOrientation -> counterclockwise PIL rotation (the Android app rotates clockwise).
_ROTATION = {0: 0, 1: 180, 2: 270, 3: 90}


@dataclass(frozen=True)
class Frame:
    seq: int
    mono: float  # time.monotonic() at arrival
    wall: float  # time.time() at arrival
    data: bytes
    orientation: int

    @property
    def digest(self) -> str:
        return hashlib.sha256(bytes([self.orientation]) + self.data).hexdigest()[:16]

    def age(self) -> float:
        return time.monotonic() - self.mono


def to_image(frame: Frame) -> Image.Image:
    """Decode the 1-bit, vertically packed framebuffer (8 rows per byte, LSB on top)."""
    if len(frame.data) != FRAME_BYTES:
        raise ValueError(f"Unexpected frame size {len(frame.data)}")
    raw = bytearray(WIDTH * HEIGHT)
    data = frame.data
    for page in range(HEIGHT // 8):
        base = page * WIDTH
        for x in range(WIDTH):
            byte = data[base + x]
            if byte:
                for bit in range(8):
                    if byte & (1 << bit):
                        raw[(page * 8 + bit) * WIDTH + x] = 1
    # Flipper LCD: set bit = dark pixel. Render dark-on-light like the device.
    image = Image.frombytes("L", (WIDTH, HEIGHT), bytes(0 if v else 255 for v in raw))
    rotation = _ROTATION.get(frame.orientation, 0)
    if rotation:
        image = image.rotate(rotation, expand=True)
    return image


def region_bits(frame: Frame, region: tuple[int, int, int, int] | None = None) -> bytes:
    """The pixels of a region as the agent sees the screen (orientation applied, unscaled), for comparing frames.

    region is (x, y, width, height); None is the whole screen. The size leads, so regions of frames in another
    orientation never compare equal by accident.
    """
    image = to_image(frame)
    if region is not None:
        x, y, w, h = region
        image = image.crop((x, y, x + w, y + h))
    return bytes([image.width, image.height]) + image.tobytes()


def png(frame: Frame, scale: int = 4) -> bytes:
    image = to_image(frame)
    if scale > 1:
        image = image.resize((image.width * scale, image.height * scale), Image.Resampling.NEAREST)
    out = io.BytesIO()
    image.convert("1").save(out, format="PNG", optimize=True)
    return out.getvalue()


class ScreenStore:
    def __init__(self, latest_path: Path | None = None, latest_scale: int = 4):
        self.frame: Frame | None = None
        self.seq = 0
        self.stale = True
        self._changed = asyncio.Event()
        self._fanout = FanOut(1)  # the latest frame number replaces an unread one
        self._latest_path = latest_path
        self._latest_scale = latest_scale
        self._latest_seq: int | None = None  # the frame latest.png holds, once written by this process
        self.kept: OrderedDict[int, Frame] = OrderedDict()  # frames handed to the agent, for chat tool cards

    # ---- updates (called from the session's receive path) ----
    def update(self, data: bytes, orientation: int) -> Frame:
        if len(data) != FRAME_BYTES:
            raise ValueError(f"Unexpected frame size {len(data)}")
        self.seq += 1
        self.frame = Frame(self.seq, time.monotonic(), time.time(), bytes(data), int(orientation))
        self.stale = False
        self._notify()
        return self.frame

    def mark_stale(self) -> None:
        """Phone/Flipper link lost: keep the last frame for display but flag it as not current."""
        if not self.stale:
            self.stale = True
            self._notify()

    def _notify(self) -> None:
        event, self._changed = self._changed, asyncio.Event()
        event.set()
        self._fanout.publish(self.seq)

    # ---- waiting ----
    async def wait_newer(self, seq: int, timeout: float) -> Frame | None:
        deadline = time.monotonic() + timeout
        while self.seq <= seq or self.stale:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return None
            event = self._changed
            try:
                await asyncio.wait_for(event.wait(), remaining)
            except TimeoutError:
                return None
        return self.frame

    async def wait_settled(self, after_seq: int, idle: float = 0.25, max_wait: float = 1.5,
                           min_wait: float = 0.0) -> tuple[Frame | None, bool]:
        """Wait for the screen to react to an action and then go quiet.

        Returns (latest frame, settled). settled is False when frames kept arriving
        until max_wait (animations such as the desktop dolphin never stop). min_wait is never counted as
        settled: an app that stops drawing while it closes would otherwise look settled on its old screen.
        A frame whose orientation differs from the one before the action is the loader's transition (an app
        opening or closing redraws in its own orientation): it needs one more change before counting as settled.
        """
        start = time.monotonic()
        deadline = start + max(max_wait, min_wait)
        earliest = start + min_wait
        before = self.frame
        first = await self.wait_newer(after_seq, max(max_wait, min_wait))
        if first is None:
            return self.frame, True  # nothing changed on screen
        last_seq = self.seq
        transition = before is not None and first.orientation != before.orientation
        while True:
            now = time.monotonic()
            if now >= deadline:
                return self.frame, False
            window = min(max(idle, earliest - now), deadline - now)
            newer = await self.wait_newer(last_seq, window)
            if newer is None:
                if transition:
                    continue  # the loader's frame stays still until the destination draws: keep waiting
                # Quiet for a full idle window (and past min_wait) means settled; a window cut short does not.
                return self.frame, window >= idle and time.monotonic() >= earliest
            if transition and newer.digest != first.digest:
                transition = False
            last_seq = self.seq

    def keep(self, frame: Frame) -> None:
        """Remember a frame a tool returned, so the chat can show it next to the tool call."""
        self.kept[frame.seq] = frame
        self.kept.move_to_end(frame.seq)
        while len(self.kept) > KEPT_FRAMES:
            self.kept.popitem(last=False)

    # ---- live view subscribers ----
    def subscribe(self) -> asyncio.Queue:
        q = self._fanout.listen()
        if self.frame is not None:
            q.put_nowait(self.seq)
        return q

    def unsubscribe(self, q: asyncio.Queue) -> None:
        self._fanout.unlisten(q)

    # ---- latest.png on disk: the last screen a tool gave the agent ----
    def save_latest(self, frame: Frame) -> Path | None:
        """Write `frame` to latest.png at the store's scale, unless the file already holds it.

        Called only for the frame a tool returns to the agent, so the file is always that exact frame (for the
        agent's own file reads, `/attach` in zf chat, and debugging). Returns the path, or None without a path
        or when the write fails.
        """
        path = self._latest_path
        if path is None:
            return None
        if self._latest_seq == frame.seq and path.exists():
            return path
        try:
            write_atomic(path, png(frame, self._latest_scale))
        except OSError as e:
            self._latest_seq = None
            log.warning("Could not write %s: %s", path.name, e)
            return None
        self._latest_seq = frame.seq
        return path
