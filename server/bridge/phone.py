# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""Phone relay session: the relay's WebSocket contract plus Flipper RPC.

The /phone contract:
- The phone connects to /phone with "Authorization: Bearer <phone token>".
- It opens BLE/USB and then sends {"type":"ready","protocol":1,"transport":"BLE"|"USB"}.
  Any binary frame received before that makes the phone drop the socket.
  The relay may add "app" (its version), "device" (the Flipper's name) and "mtu";
  they are optional and only shown to the operator.
- Server -> phone: binary frames only (text closes with 1003), each <= 65536 bytes,
  containing varint-delimited PB.Main messages. At most 64 frames queued on the phone.
- Phone -> server: {"type":"written"} after each frame is written to the transport,
  and binary frames with raw transport bytes (arbitrary boundaries).
- Every reconnect is a fresh Flipper RPC session; nothing is replayed.
- The phone may ask GET /phone/check (same token) before connecting: it reports the gate and
  the attached relay without taking the phone slot.
- When the phone closes the socket, its close code and reason are kept in the "close" metric.
- Each server frame holds whole PB.Main messages, never part of one (_send_messages); the relay relies on it.

command_id ranges (docs/protocol/rpc/README.md, with vectors.json): the Flipper echoes a request's id in its replies,
and the server and the relay share its RPC session, so each side has its own ids:
- 0: unsolicited messages from the Flipper (screen frames). Nobody sends a request with id 0.
- 1 … 2^28 − 1: the server's requests (_allocate_id wraps inside it).
- 2^28 … 2^29 − 1: the server's settle stop, at random. The relay syncs its own reader only on a stop with an id in
  this range; restart_stream's stop carries an ordinary server id.
- 2^29 … 2^30 − 1: the relay's own messages (keys, its screen stream). Their replies reach the server with the
  Flipper's other bytes: _dispatch counts them ("relay_replies" in the "close" metric) and drops them, so they never
  answer a request or reach flipper_events.
- 2^30 … 2^32 − 1: reserved.
"""
from __future__ import annotations

import asyncio
import contextlib
import json
import secrets
import time
from collections import deque
from typing import TYPE_CHECKING, Callable

from google.protobuf.message import DecodeError

from . import pb
from .framing import Decoder, varint, frame
from .text import clean_text

if TYPE_CHECKING:
    from .hub import Hub

PACK_LIMIT = 8192  # bytes per WebSocket frame; the phone must write each frame over BLE within 20 s
ACK_TIMEOUT = 30.0
MAX_REPLY_BYTES = 4 * 1024 * 1024
MAX_REPLY_S = 600.0  # a multi-part reply may take this long in total; the timeout restarts on each part
# command_id ranges (docs/protocol/rpc/README.md): one per side of the link, so no reply goes to the wrong one.
SERVER_ID_MAX = (1 << 28) - 1  # the server's requests: 1 … 2^28 − 1 (_allocate_id wraps here)
SETTLE_ID_MIN = 1 << 28  # settle's stop: random in 2^28 … 2^29 − 1
SETTLE_ID_MAX = (1 << 29) - 1
RELAY_ID_MIN = 1 << 29  # the relay's own messages (keys, its screen stream): 2^29 … 2^30 − 1; dropped by _dispatch
RELAY_ID_MAX = (1 << 30) - 1
OK = pb.CommandStatus.Value("OK")
ERROR_DECODE = pb.CommandStatus.Value("ERROR_DECODE")


def relay_info(info: dict) -> dict:
    mtu = info.get("mtu")
    return {
        "app": clean_text(info.get("app")),
        "device": clean_text(info.get("device")),
        "mtu": mtu if isinstance(mtu, int) and not isinstance(mtu, bool) and 23 <= mtu <= 517 else None,
    }


class LinkError(Exception):
    """No ready phone/Flipper session, or it dropped. Outcome of an in-flight command is unknown."""


class RpcTimeout(Exception):
    """No reply in time. The command may or may not have run; it is never retried automatically."""


class RpcError(Exception):
    def __init__(self, status: int, replies: list | None = None):
        self.status = status
        self.status_name = pb.CommandStatus.Name(status) if status in pb.CommandStatus.values() else str(status)
        self.replies = replies or []
        super().__init__(self.status_name)


class PhoneSession:
    def __init__(self, hub: "Hub", send_bytes, close, peer: str):
        self.hub = hub
        self._send_bytes = send_bytes
        self._close = close
        self.peer = peer
        self.ready = False
        self.closed = False
        self.transport: str | None = None
        self.info: dict = {"app": None, "device": None, "mtu": None}
        self.decoder = Decoder()
        self.pending: dict[int, asyncio.Queue] = {}
        self._next_id = 1
        self.send_lock = asyncio.Lock()
        self._acks: deque[asyncio.Future] = deque()  # one per frame sent, in order
        self.opened_at = time.monotonic()
        self.ready_at: float | None = None
        self.frames = 0
        self.relay_replies = 0  # replies to the relay's own messages, dropped (RELAY_ID_MIN … RELAY_ID_MAX)
        self.bytes_in = 0
        self.bytes_out = 0
        self.phone_close: dict | None = None  # code and reason sent by the phone when it closed
        self.first_frame_s: float | None = None
        self.close_reason: str | None = None
        self.tasks: set[asyncio.Task] = set()
        # While settling, bytes are dropped until the reply of settle()'s stop: see settle().
        self.discarding = False
        self.discarded = 0
        self.settled = asyncio.Event()
        self.settled.set()
        self._settle_id: int | None = None
        self._settle_tail = bytearray()  # dropped bytes that may still begin the stop's reply
        self._synced = asyncio.Event()

    # ---------------- inbound ----------------
    async def on_text(self, text: str) -> None:
        if len(text) > 1024:
            raise ValueError("Status message too large")
        info = json.loads(text)
        if not isinstance(info, dict):
            raise ValueError("Status must be a JSON object")
        kind = info.get("type")
        if kind == "written":
            if not self.ready or not self._acks:
                raise ValueError("Unexpected write acknowledgement")
            ack = self._acks.popleft()
            if not ack.done():
                ack.set_result(True)
            return
        if kind == "ready":
            if self.ready or info.get("protocol") != 1 or info.get("transport") not in ("BLE", "USB"):
                raise ValueError("Invalid ready message")
            self.transport = info["transport"]
            self.info = relay_info(info)
            self.ready = True
            self.ready_at = time.monotonic()
            self.discarding = True  # until settle() has read its stop's reply
            self.settled.clear()
            self.hub.on_ready(self)
            return
        raise ValueError(f"Unknown status type {kind!r}")

    def on_bytes(self, data: bytes) -> None:
        if not self.ready:
            raise ValueError("Binary data before ready")
        self.bytes_in += len(data)
        if self.discarding:
            self.discarded += len(data)
            rest = self._after_settle_reply(data)
            if rest is None:
                return
            self.discarded -= len(rest)
            self.decoder = Decoder()
            self.discarding = False
            self._synced.set()
            data = rest
        for raw in self.decoder.feed(data):
            self._dispatch(pb.Main.FromString(raw))

    def _dispatch(self, message) -> None:
        content = message.WhichOneof("content")
        if content == "gui_screen_frame":
            f = message.gui_screen_frame
            self.hub.screen.update(f.data, f.orientation)
            self.frames += 1
            if self.first_frame_s is None and self.ready_at is not None:
                self.first_frame_s = time.monotonic() - self.ready_at
                self.hub.metrics.write({"ev": "first_frame", "after_ready_s": round(self.first_frame_s, 3)})
            self.hub.window.frame()
            return
        if RELAY_ID_MIN <= message.command_id <= RELAY_ID_MAX:  # the relay reads its own replies
            self.relay_replies += 1
            return
        queue = self.pending.get(message.command_id)
        if queue is not None:
            try:
                queue.put_nowait(message)
            except asyncio.QueueFull:
                self._fail_pending(message.command_id, LinkError("Too many reply fragments"))
            return
        if message.command_id == 0 and message.command_status == ERROR_DECODE:
            # rpc.c closes the RPC session (and drops BLE) after a decode error.
            raise ValueError("Flipper reported ERROR_DECODE and closed its RPC session")
        self.hub.on_unsolicited(message)

    # ---------------- outbound ----------------
    async def _send_frame(self, payload: bytes) -> None:
        """Send one binary frame and wait for the phone's transport-write acknowledgement."""
        ack = asyncio.get_running_loop().create_future()
        # The ack stays queued even if this call is cancelled, so a late "written" is still expected.
        self._acks.append(ack)
        await self._send_bytes(payload)
        self.bytes_out += len(payload)
        try:
            await asyncio.wait_for(asyncio.shield(ack), ACK_TIMEOUT)
        except TimeoutError:
            # Acks are not numbered; after a missing one the stream cannot be trusted.
            await self.abort("Phone did not acknowledge a write in time")
            raise LinkError("Phone stopped acknowledging writes; outcome unknown, not retried") from None
        if self.closed:
            raise LinkError(self.close_reason or "Phone disconnected")

    async def _send_messages(self, messages: list, pack_limit: int = PACK_LIMIT,
                             between: Callable[[], None] | None = None) -> None:
        """`between` runs before each frame after the first; if it raises, the command in progress ends with
        its next part sent as the last one (so the Flipper's stream stays whole; a write's with no data) and the
        error goes up."""
        packed = bytearray()
        for i, message in enumerate(messages):
            chunk = frame(message.SerializeToString())
            if packed and len(packed) + len(chunk) > pack_limit:
                await self._send_frame(bytes(packed))
                packed.clear()
                if between is not None:
                    try:
                        between()
                    except Exception:
                        if messages[i - 1].has_next:
                            message.has_next = False
                            if message.WhichOneof("content") == "storage_write_request":
                                message.storage_write_request.file.data = b""  # it only closes: nothing lands after the error
                            await self._send_frame(frame(message.SerializeToString()))
                        raise
            packed += chunk
        if packed:
            await self._send_frame(bytes(packed))

    async def settle(self, limit_s: float) -> tuple[int, bool]:
        """Start reading the Flipper's stream at a message boundary.

        When Android keeps the Bluetooth link of an earlier session, the Flipper's RPC session lives on
        and may still be streaming screen frames, so the new session would start reading in the middle
        of one ("Invalid varint length", or "Wire format was corrupt" at MTU 23). Ask for the stream to
        stop and drop everything until the stop's own reply. The Flipper answers in order, after the
        frame it was sending, and sends no frame after it, so the next byte starts a message, however
        slowly the link trickles. A pause inside a frame no longer looks like the end of the stream.

        The stop carries an id from the settle range, which no request uses, and its reply is found in the dropped
        bytes by that id.
        Without a reply within limit_s, reading starts there with an empty decoder.
        Returns the bytes dropped and whether the reply was found.
        """
        self.settled.clear()
        self.discarding = True
        self._synced.clear()
        self._settle_tail.clear()
        self._settle_id = SETTLE_ID_MIN + secrets.randbelow(SETTLE_ID_MAX - SETTLE_ID_MIN + 1)
        try:
            stop = build("gui_stop_screen_stream_request")
            stop.command_id = self._settle_id
            async with self.send_lock:
                await self._send_messages([stop])
            if self.discarding:
                try:
                    await asyncio.wait_for(self._synced.wait(), limit_s)
                except TimeoutError:
                    pass
        finally:
            synced = not self.discarding and not self.closed
            if self.discarding:
                self.decoder = Decoder()
                self.discarding = False
            self._settle_id = None
            self._settle_tail.clear()
            self.settled.set()
        return self.discarded, synced

    def _after_settle_reply(self, data: bytes) -> bytes | None:
        """While settling: the bytes after the stop's reply when this chunk completes it, else None.

        The reply is a PB.Main whose first field is command_id (nanopb writes fields in order), so it starts with its
        one-byte length, 0x08 and the id's varint. A match must also parse as a whole PB.Main with that id, so the
        same bytes inside a bitmap are not taken for it. Its command_status, if not OK, and its content may follow.
        """
        cid = self._settle_id
        if cid is None:
            return None
        marker = b"\x08" + varint(cid)
        buf = self._settle_tail
        buf += data
        at = buf.find(marker, 1)
        while at != -1:
            size = buf[at - 1]
            end = at + size
            if size < len(marker) or size >= 0x80:
                pass  # not the reply's one-byte length
            elif len(buf) < end:
                del buf[:at - 1]  # the candidate is not complete yet: keep it for the next chunk
                return None
            else:
                try:
                    reply = pb.Main.FromString(bytes(buf[at:end]))
                except DecodeError:
                    reply = None
                if reply is not None and reply.command_id == cid and not reply.has_next:
                    return bytes(buf[end:])
            at = buf.find(marker, at + 1)
        del buf[:max(0, len(buf) - len(marker))]  # a marker may begin in the last bytes, after its length byte
        return None

    def _allocate_id(self) -> int:
        cid = self._next_id
        self._next_id = self._next_id % SERVER_ID_MAX + 1
        return cid

    async def request(self, messages: list, timeout: float = 20.0, pack_limit: int = PACK_LIMIT,
                      between: Callable[[], None] | None = None) -> list:
        """Send one RPC command (possibly multipart) and collect its full reply.

        `timeout` is the wait for each part of the reply: it restarts on every part, up to MAX_REPLY_S in total.
        A timeout fails only this command; the session stays up. Nothing is retried.
        `between` runs before each frame after the first (_send_messages); when it raises, the command ends early,
        its reply is awaited and that error is raised.
        """
        result = (await self.request_batch([messages], timeout, pack_limit, between))[0]
        if isinstance(result, Exception):
            raise result
        return result

    async def request_batch(self, commands: list[list], timeout: float = 20.0,
                            pack_limit: int = PACK_LIMIT,
                            between: Callable[[], None] | None = None) -> list[list | Exception]:
        """Send several commands together (one frame when they fit) and collect each reply in order.

        The Flipper reads its RPC stream in order, so the commands run as if sent one by one, without waiting a
        round trip between them. Returns, per command, its replies or the exception that ended it.
        """
        if self.closed or not self.ready:
            raise LinkError("Phone/Flipper is not connected")
        if not commands or not all(commands):
            raise ValueError("Empty request")
        if between is not None and len(commands) != 1:
            raise ValueError("between is for one command")
        if not self.settled.is_set():
            try:
                await asyncio.wait_for(self.settled.wait(), 10)
            except TimeoutError:
                raise LinkError("The Flipper's stream did not settle") from None
        cids = []
        try:
            for messages in commands:
                cid = self._allocate_id()
                cids.append(cid)
                self.pending[cid] = asyncio.Queue(maxsize=8192)
                for i, message in enumerate(messages):
                    message.command_id = cid
                    message.has_next = i < len(messages) - 1
            stopped: Exception | None = None
            async with self.send_lock:
                if between is None:
                    await self._send_messages([m for messages in commands for m in messages], pack_limit)
                else:
                    try:
                        await self._send_messages(commands[0], pack_limit, between)
                    except LinkError:
                        raise
                    except Exception as e:
                        stopped = e
            if stopped is not None:  # the command was ended early: wait for its reply, then say why
                with contextlib.suppress(RpcError, RpcTimeout, LinkError):
                    await self._collect(self.pending[cids[0]], timeout)
                raise stopped
            # The reply timeout starts once the request is on the transport (sending is bounded by acks).
            results: list[list | Exception] = []
            for cid in cids:
                try:
                    results.append(await self._collect(self.pending[cid], timeout))
                except (RpcError, RpcTimeout, LinkError) as e:
                    results.append(e)
            return results
        finally:
            for cid in cids:
                self.pending.pop(cid, None)

    async def _collect(self, queue: asyncio.Queue, timeout: float) -> list:
        """All parts of one reply. The wait restarts on each part, up to MAX_REPLY_S in total."""
        end = time.monotonic() + max(timeout, MAX_REPLY_S)
        deadline = time.monotonic() + timeout
        replies, size = [], 0
        while True:
            remaining = min(deadline, end) - time.monotonic()
            try:
                if remaining <= 0:
                    raise TimeoutError
                item = await asyncio.wait_for(queue.get(), remaining)
            except TimeoutError:
                after = f" after {len(replies)} parts" if replies else ""
                raise RpcTimeout(f"No reply within {timeout:.0f} s{after}; outcome unknown, not retried") from None
            if isinstance(item, Exception):
                raise item
            if item.command_status != OK:
                raise RpcError(item.command_status, replies)
            size += item.ByteSize()
            if size > MAX_REPLY_BYTES:
                raise LinkError("Reply exceeds 4 MiB")
            replies.append(item)
            if not item.has_next:
                return replies
            deadline = time.monotonic() + timeout

    def _fail_pending(self, cid: int, error: Exception) -> None:
        queue = self.pending.get(cid)
        if queue is None:
            return
        while not queue.empty():
            queue.get_nowait()
        queue.put_nowait(error)

    async def abort(self, reason: str) -> None:
        if self.closed:
            return
        self.closed = True
        self.ready = False
        self.close_reason = reason
        for cid in list(self.pending):
            self._fail_pending(cid, LinkError(f"{reason}; outcome unknown, not retried"))
        while self._acks:
            ack = self._acks.popleft()
            if not ack.done():
                ack.set_result(False)
        for task in list(self.tasks):
            if task is not asyncio.current_task():
                task.cancel()
        self.hub.on_closed(self)
        try:
            await self._close(1011, reason[:100])
        except Exception:
            pass

    def spawn(self, coro) -> asyncio.Task:
        task = asyncio.create_task(coro)
        self.tasks.add(task)
        task.add_done_callback(self.tasks.discard)
        return task


def build(name: str, payload: dict | None = None):
    """PB.Main with a single content field set (payload may be empty)."""
    from google.protobuf.json_format import ParseDict

    message = pb.Main()
    ParseDict(payload or {}, getattr(message, name))
    getattr(message, name).SetInParent()
    return message
