# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The process-wide device state: the one phone slot, the screen, the operator gate, the logs and the admission
lock of the Flipper (ops_lock), shared by the phone endpoint, the MCP tools, the live view and /app."""
from __future__ import annotations

import asyncio
import time
from collections import deque
from pathlib import Path
from typing import Any, Callable

from . import pb
from .devicecache import DeviceCache
from .fanout import FanOut
from .firmware import Firmware
from .gate import ControlGate, JsonlLog
from .notes import NoteStore
from .peers import Peers
from .phone import PACK_LIMIT, LinkError, PhoneSession, RpcError, RpcTimeout, build
from .screen import ScreenStore
from .wire import ACTIVE, GATE_ACTIONS, PAUSED, STOPPED

SETTLE_LIMIT_S = 5.0  # the longest wait for the settle stop's reply before reading anyway
ALREADY_STARTED = pb.CommandStatus.Value("ERROR_VIRTUAL_DISPLAY_ALREADY_STARTED")
RESTORED_ACTIONS = {PAUSED: "pause", STOPPED: "stop"}  # the action a restored state came from


class FrameWindow:
    """Per-minute frame statistics for the stability probe."""

    def __init__(self, hub: "Hub", seconds: float = 60.0):
        self.hub = hub
        self.seconds = seconds
        self.reset()

    def reset(self) -> None:
        self.start = time.monotonic()
        self.count = 0
        self.last = None
        self.max_gap = 0.0

    def frame(self) -> None:
        now = time.monotonic()
        if self.last is not None:
            self.max_gap = max(self.max_gap, now - self.last)
        self.last = now
        self.count += 1

    def flush(self, session: PhoneSession | None) -> None:
        now = time.monotonic()
        since_last = now - self.last if self.last is not None else None
        self.hub.metrics.write({
            "ev": "window",
            "seconds": round(now - self.start, 1),
            "frames": self.count,
            "fps": round(self.count / max(now - self.start, 0.001), 2),
            "max_gap_s": round(self.max_gap, 2),
            "since_last_frame_s": round(since_last, 1) if since_last is not None else None,
            "connected": bool(session and session.ready and not session.closed),
            "transport": session.transport if session else None,
        })
        last = self.last
        self.reset()
        self.last = last


class Hub:
    """Process-wide state shared by the phone endpoint, the MCP tools and the live view."""

    def __init__(self, state_dir: Path | None = None, keepalive_s: float = 30.0, metrics_window_s: float = 60.0,
                 own_address: str | None = None):
        if state_dir is not None:
            state_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.state_dir = state_dir
        self.latest_png = state_dir / "latest.png" if state_dir else None
        self.screen = ScreenStore(self.latest_png)
        self.gate = ControlGate(state_dir / "gate.json" if state_dir else None)  # survives a restart
        self.audit = JsonlLog(state_dir / "audit.jsonl" if state_dir else None, keep=200)
        self.metrics = JsonlLog(state_dir / "metrics.jsonl" if state_dir else None, keep=200)
        self.session: PhoneSession | None = None
        self.ops_lock = asyncio.Lock()
        self.ops_waiting = 0  # calls waiting for ops_lock; at most one may wait (tools.run)
        self.busy_changes = FanOut(8)  # True when a call takes ops_lock, False when it lets go (status "busy")
        self.device = DeviceCache(state_dir / "device.json" if state_dir else None)  # the last firmware, kept
        self.notes = NoteStore(state_dir / "notes" if state_dir else None)  # the agent's notes per app
        self.firmware = Firmware(self.device, self.ops_lock, self.metrics, lambda: self.session, self.ops_changed)
        self.unsolicited: deque[dict[str, Any]] = deque(maxlen=50)
        self.keepalive_s = keepalive_s
        self.window = FrameWindow(self, metrics_window_s)
        self._bg: set[asyncio.Task] = set()
        self.last_ping_ms: float | None = None
        self.app_clients = 0
        self.peers = Peers(own_address)  # the devices that use the public listener; own_address is the bound one
        if self.restored_gate():
            self.audit.write({"tool": "operator", "action": RESTORED_ACTIONS[self.gate.state],
                              "by": self.gate.by.split(" ")[0], "restored": True, "result": "ok"})

    def restored_gate(self) -> dict[str, Any]:
        """A Pause or Stop kept in gate.json from before a restart, for the start metrics; {} when active."""
        if self.gate.state == ACTIVE:
            return {}
        return {"gate": self.gate.state, "gate_by": self.gate.by.split(" ")[0],
                "gate_changed_at": round(self.gate.changed_at, 3)}

    # ---- session lifecycle ----
    def reserve(self, session: PhoneSession) -> str | None:
        """Claim the single phone slot. Returns None on success, else the refusal reason."""
        if self.gate.state == STOPPED:
            return "stopped"
        if self.session is not None and not self.session.closed:
            return "busy"
        self.session = session
        self.metrics.write({"ev": "ws_open", "peer": session.peer})
        return None

    def on_ready(self, session: PhoneSession) -> None:
        self.metrics.write({"ev": "ready", "transport": session.transport,
                            "after_open_s": round(time.monotonic() - session.opened_at, 2),
                            **{k: v for k, v in session.info.items() if v is not None}})
        session.spawn(self._start_stream(session))
        if self.keepalive_s > 0:
            session.spawn(self._keepalive(session))

    def on_closed(self, session: PhoneSession) -> None:
        self.screen.mark_stale()
        self.firmware.closed(session, session.close_reason)
        duration = time.monotonic() - session.ready_at if session.ready_at else None
        self.metrics.write({
            "ev": "close", "reason": session.close_reason, "transport": session.transport,
            "ready_duration_s": round(duration, 1) if duration is not None else None,
            "frames": session.frames, "relay_replies": session.relay_replies,
            "bytes_in": session.bytes_in, "bytes_out": session.bytes_out,
            **({"phone_code": session.phone_close["code"], "phone_reason": session.phone_close["reason"]}
               if session.phone_close else {}),
        })
        if self.session is session:
            self.session = None

    def on_unsolicited(self, message) -> None:
        from google.protobuf.json_format import MessageToDict

        record = {"ts": round(time.time(), 3), "command_id": message.command_id,
                  "status": pb.CommandStatus.Name(message.command_status) if message.command_status in pb.CommandStatus.values() else message.command_status,
                  "message": MessageToDict(message, preserving_proto_field_name=True)}
        self.unsolicited.append(record)

    async def _start_stream(self, session: PhoneSession) -> None:
        try:
            dropped, synced = await session.settle(SETTLE_LIMIT_S)
        except LinkError:
            return
        self.metrics.write({"ev": "settle", "dropped_bytes": dropped, "synced": synced})
        await self._request_stream(session)
        if not session.closed:  # whether the stream started or not, so a live session never reads "none"
            self.firmware.start(session)

    async def _request_stream(self, session: PhoneSession) -> None:
        # Starting the stream makes the firmware redraw (gui_add_framebuffer_callback -> gui_update),
        # so a fresh frame follows right away.
        try:
            await session.request([build("gui_start_screen_stream_request")], timeout=15)
            status = "OK"
        except RpcError as e:
            status = e.status_name
            if e.status != ALREADY_STARTED:
                self.metrics.write({"ev": "stream", "status": status})
                return
        except (RpcTimeout, LinkError) as e:
            self.metrics.write({"ev": "stream", "status": f"failed: {e}"})
            return
        self.metrics.write({"ev": "stream", "status": status})

    async def restart_stream(self) -> None:
        """Stop and start the stream to force a redraw, without settle(): it would drop the replies of requests
        in flight (a press or a write running beside flipper_screen). The stop's reply, read in order, shows the
        stream is at a message boundary; a session whose stream lost its boundary has already been closed."""
        session = self.require_session()
        try:
            await session.request([build("gui_stop_screen_stream_request")], timeout=10)
        except RpcError:
            pass
        await self._request_stream(session)

    async def _keepalive(self, session: PhoneSession) -> None:
        failures = 0
        while not session.closed:
            await asyncio.sleep(self.keepalive_s)
            if session.closed:
                return
            if session.pending or session.send_lock.locked():
                continue  # other traffic is in flight; its own timeout covers liveness
            start = time.monotonic()
            try:
                await session.request([build("system_ping_request", {"data": "Ymc="})], timeout=min(15.0, self.keepalive_s * 2))
            except (RpcTimeout, RpcError) as e:
                failures += 1
                self.metrics.write({"ev": "ping", "ok": False, "error": str(e), "failures": failures})
                if failures >= 2:
                    await session.abort("Flipper stopped answering keepalive pings")
                    return
                continue
            except LinkError:
                return
            failures = 0
            self.last_ping_ms = round((time.monotonic() - start) * 1000, 1)
            self.metrics.write({"ev": "ping", "ok": True, "rtt_ms": self.last_ping_ms})

    async def metrics_loop(self) -> None:
        while True:
            await asyncio.sleep(self.window.seconds)
            self.window.flush(self.session)

    # ---- operator ----
    async def operator(self, action: str, by: str) -> dict[str, Any]:
        """Pause, resume or stop the agent from the live view or the app. Returns the new status."""
        state = GATE_ACTIONS[action]
        self.gate.set(state, by=by)
        self.audit.write({"tool": "operator", "action": action, "by": by.split(" ")[0], "result": "ok"})
        if state == STOPPED and self.session is not None:
            await self.session.abort("Stopped by the operator")
        return self.status()

    def ops_changed(self) -> None:
        """A call took ops_lock or let it go: /app's status says so at once ("busy")."""
        self.busy_changes.publish(self.ops_lock.locked())

    # ---- accessors ----
    def require_session(self) -> PhoneSession:
        s = self.session
        if s is None or s.closed or not s.ready:
            raise LinkError("Phone/Flipper is not connected")
        return s

    async def request(self, messages: list, timeout: float = 20.0, pack_limit: int = PACK_LIMIT,
                      between: Callable[[], None] | None = None) -> list:
        return await self.require_session().request(messages, timeout, pack_limit, between)

    async def request_batch(self, commands: list[list], timeout: float = 20.0) -> list[list | Exception]:
        return await self.require_session().request_batch(commands, timeout)

    def relay_summary(self) -> dict[str, Any] | None:
        """The attached relay as /phone/check reports it (None when no phone is attached)."""
        s = self.session
        if s is None or s.closed:
            return None
        return {**s.info, "ready": s.ready, "age_s": round(time.monotonic() - s.opened_at, 1)}

    def status(self) -> dict[str, Any]:
        s = self.session
        frame = self.screen.frame
        return {
            "connected": bool(s and s.ready and not s.closed),
            "phone_attached": bool(s and not s.closed),
            "transport": s.transport if s else None,
            "relay": dict(s.info) if s else None,
            "gate": self.gate.state,
            "gate_changed_at": round(self.gate.changed_at, 3),
            "gate_by": self.gate.by.split(" ")[0],
            "busy": self.ops_lock.locked(),
            "screen": {
                "seq": self.screen.seq,
                "stale": self.screen.stale,
                "age_s": round(frame.age(), 2) if frame else None,
            },
            "session_uptime_s": round(time.monotonic() - s.ready_at, 1) if s and s.ready_at else None,
            "last_ping_ms": self.last_ping_ms,
            "session_frames": s.frames if s else None,
            "session_bytes_in": s.bytes_in if s else None,
            "session_bytes_out": s.bytes_out if s else None,
        }
