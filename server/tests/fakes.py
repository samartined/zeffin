# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""A simulated phone + Flipper speaking the /phone relay contract, for tests without hardware."""
from __future__ import annotations

import asyncio
import hashlib
import json

from bridge import pb
from bridge.framing import Decoder, frame
from bridge.hub import Hub
from bridge.phone import PhoneSession


class FakeFlipper:
    def __init__(self, hub: Hub):
        self.hub = hub
        self.session = PhoneSession(hub, self._send_bytes, self._close, "fake")
        self.decoder = Decoder()
        self.requests: list = []
        self.frames_sent: list[int] = []  # sizes of WebSocket frames from the bridge
        self.raw: list[bytes] = []  # the frames themselves
        self.inputs: list[tuple[str, str]] = []
        self.files: dict[str, bytes] = {"/ext/hello.txt": b"hello"}
        self.dirs: set[str] = {"/ext", "/int"}
        self.respond = True
        self.ack = True
        self.screen_fill = 0x00
        self.closed: tuple[int, str] | None = None
        self.delay = 0.0  # seconds before each request is answered: a slow link's round trip
        self.part_gap = 0.0  # seconds between the parts of a multi-part reply
        self.device_info_parts = 2
        # What property_get_request answers, by key (fake values). property_get=False answers it as an old firmware.
        # The keys have the property request's form: the commit and the branch are "firmware.commit.hash" and
        # "firmware.branch.name" there ("firmware_commit" and "firmware_branch" in the device info request's form).
        self.devinfo: dict[str, str] = {
            "hardware.model": "Flipper Zero", "hardware.name": "Demo", "firmware.commit.hash": "0a1b2c3d",
            "firmware.commit.dirty": "false", "firmware.branch.name": "dev", "firmware.branch.num": "1",
            "firmware.version": "mntm-dev", "firmware.build.date": "01-11-2026", "firmware.target": "7",
            "firmware.api.major": "87", "firmware.api.minor": "1", "firmware.origin.fork": "Momentum",
            "firmware.origin.git": "https://example.invalid/firmware.git"}
        self.property_get = True
        self.in_order = False  # with delay: answer one request at a time, in order, as the Flipper does
        self._free_at = 0.0
        self.fail: set[tuple[str, str]] = set()  # (key, type) input events answered with an error
        self._write_buf: dict[int, bytearray] = {}

    async def connect(self, transport: str = "BLE", detected: bool = True) -> None:
        """detected=True waits until the background firmware detection has ended, so a test's own requests are the
        only ones on the link; detected=False returns while it may still run."""
        assert self.hub.reserve(self.session) is None
        await self.session.on_text(json.dumps({"type": "ready", "protocol": 1, "transport": transport}))
        await asyncio.sleep(0)
        await asyncio.wait_for(self.session.settled.wait(), 2)
        for _ in range(20):
            await asyncio.sleep(0)
        if detected:
            await self.detection_ended()

    async def detection_ended(self, timeout: float = 5.0) -> None:
        from bridge.firmware import DETECTING, NONE

        end = asyncio.get_running_loop().time() + timeout
        firmware = self.hub.firmware
        while firmware.state in (NONE, DETECTING) or firmware.learning:
            if asyncio.get_running_loop().time() > end:
                raise AssertionError(f"the firmware detection did not end: {firmware.summary()}")
            await asyncio.sleep(0.001)

    # ---- bridge -> phone ----
    async def _send_bytes(self, data: bytes) -> None:
        assert len(data) <= 65536, "frame larger than the app accepts"
        self.frames_sent.append(len(data))
        self.raw.append(bytes(data))
        asyncio.get_running_loop().call_soon(self._process, bytes(data))

    async def _close(self, code: int, reason: str) -> None:
        self.closed = (code, reason)

    def _process(self, data: bytes) -> None:
        if self.ack:
            asyncio.ensure_future(self.session.on_text('{"type":"written"}'))
        for raw in self.decoder.feed(data):
            request = pb.Main.FromString(raw)
            self.requests.append(request)
            if self.respond:
                loop = asyncio.get_running_loop()
                if self.delay and self.in_order:
                    self._free_at = max(loop.time(), self._free_at) + self.delay
                    loop.call_at(self._free_at, self.handle, request)
                elif self.delay:
                    loop.call_later(self.delay, self.handle, request)
                else:
                    self.handle(request)

    # ---- phone -> bridge ----
    def send(self, message, split: int | None = None) -> None:
        data = frame(message.SerializeToString())
        if split:
            for i in range(0, len(data), split):
                self.session.on_bytes(data[i:i + split])
        else:
            self.session.on_bytes(data)

    def ok(self, request, **content) -> None:
        m = pb.Main(command_id=request.command_id)
        if not content:
            m.empty.SetInParent()
        for field, value in content.items():
            getattr(m, field).CopyFrom(value)
        self.send(m, split=20)

    def error(self, request, status: str) -> None:
        m = pb.Main(command_id=request.command_id, command_status=pb.CommandStatus.Value(status))
        m.empty.SetInParent()
        self.send(m)

    def emit_frame(self, fill: int | None = None, orientation: int = 0, data: bytes | None = None) -> None:
        if fill is not None:
            self.screen_fill = fill
        m = pb.Main()
        m.gui_screen_frame.data = data if data is not None else bytes([self.screen_fill]) * 1024
        m.gui_screen_frame.orientation = orientation
        self.send(m, split=244)

    # ---- simulated firmware ----
    def handle(self, r) -> None:
        kind = r.WhichOneof("content")
        if kind == "gui_start_screen_stream_request":
            self.ok(r)
            self.emit_frame()
        elif kind == "gui_stop_screen_stream_request":
            self.ok(r)
        elif kind == "gui_send_input_event_request":
            e = r.gui_send_input_event_request
            key = pb.gui.InputKey.Name(e.key)
            typ = pb.gui.InputType.Name(e.type)
            self.inputs.append((key, typ))
            if (key, typ) in self.fail:
                return self.error(r, "ERROR_INVALID_PARAMETERS")
            self.ok(r)
            if typ in ("SHORT", "LONG"):
                self.emit_frame((self.screen_fill + 1) % 256)
        elif kind == "system_ping_request":
            resp = pb.system.PingResponse(data=r.system_ping_request.data)
            self.ok(r, system_ping_response=resp)
        elif kind == "system_device_info_request":
            pairs = [("hardware_name", "Flipper"), ("firmware_version", "1.4.3")]
            pairs += [(f"key_{i}", str(i)) for i in range(self.device_info_parts - 2)]
            for i, (k, v) in enumerate(pairs):
                m = pb.Main(command_id=r.command_id, has_next=i < len(pairs) - 1)
                m.system_device_info_response.key = k
                m.system_device_info_response.value = v
                if self.part_gap:
                    asyncio.get_running_loop().call_later(self.part_gap * i, self.send, m, 7)
                else:
                    self.send(m, split=7)
        elif kind == "property_get_request":
            if not self.property_get:
                return self.error(r, "ERROR_NOT_IMPLEMENTED")
            top, _, sub = r.property_get_request.key.partition(".")
            if top != "devinfo":
                return self.error(r, "ERROR_INVALID_PARAMETERS")
            pairs = [(k, v) for k, v in self.devinfo.items() if k.startswith(sub)]
            if not pairs:
                return self.ok(r)
            for i, (k, v) in enumerate(pairs):
                m = pb.Main(command_id=r.command_id, has_next=i < len(pairs) - 1)
                m.property_get_response.key = k
                m.property_get_response.value = v
                self.send(m, split=7)
        elif kind == "storage_list_request":
            path = r.storage_list_request.path.rstrip("/")
            if path not in self.dirs:
                return self.error(r, "ERROR_STORAGE_NOT_EXIST")
            resp = pb.storage.ListResponse()
            for name, data in self.files.items():
                if name.rsplit("/", 1)[0] == path:
                    resp.file.add(name=name.rsplit("/", 1)[1], size=len(data), type=pb.storage.File.FILE)
            for d in sorted(self.dirs):
                if d.rsplit("/", 1)[0] == path and d != path:
                    resp.file.add(name=d.rsplit("/", 1)[1], type=pb.storage.File.DIR)
            self.ok(r, storage_list_response=resp)
        elif kind == "storage_stat_request":
            path = r.storage_stat_request.path
            if path in self.dirs:
                self.ok(r, storage_stat_response=pb.storage.StatResponse(file=pb.storage.File(type=pb.storage.File.DIR)))
            elif path in self.files:
                f = pb.storage.File(type=pb.storage.File.FILE, size=len(self.files[path]))
                self.ok(r, storage_stat_response=pb.storage.StatResponse(file=f))
            else:
                self.error(r, "ERROR_STORAGE_NOT_EXIST")
        elif kind == "storage_md5sum_request":
            data = self.files.get(r.storage_md5sum_request.path)
            if data is None:
                return self.error(r, "ERROR_STORAGE_NOT_EXIST")
            self.ok(r, storage_md5sum_response=pb.storage.Md5sumResponse(md5sum=hashlib.md5(data).hexdigest()))
        elif kind == "storage_read_request":
            data = self.files.get(r.storage_read_request.path)
            if data is None:
                return self.error(r, "ERROR_STORAGE_NOT_EXIST")
            chunks = [data[i:i + 512] for i in range(0, len(data), 512)] or [b""]
            for i, chunk in enumerate(chunks):
                m = pb.Main(command_id=r.command_id, has_next=i < len(chunks) - 1)
                m.storage_read_response.file.data = chunk
                self.send(m)
        elif kind == "storage_write_request":
            buf = self._write_buf.setdefault(r.command_id, bytearray())
            buf += r.storage_write_request.file.data
            if not r.has_next:
                self.files[r.storage_write_request.path] = bytes(self._write_buf.pop(r.command_id))
                self.ok(r)
        elif kind == "storage_delete_request":
            if self.files.pop(r.storage_delete_request.path, None) is None:
                return self.error(r, "ERROR_STORAGE_NOT_EXIST")
            self.ok(r)
        elif kind in ("storage_mkdir_request", "app_start_request", "app_exit_request",
                      "app_load_file_request", "app_button_press_release_request", "system_play_audiovisual_alert_request"):
            if kind == "storage_mkdir_request":
                self.dirs.add(r.storage_mkdir_request.path)
            self.ok(r)
        else:
            self.error(r, "ERROR_NOT_IMPLEMENTED")
