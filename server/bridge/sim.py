# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""Phone + Flipper simulator for trying the bridge (and Claude Code) without hardware.

It connects to /phone exactly like the phone relay (Bearer token, ready message,
written acks, raw transport bytes in BLE-sized pieces) and emulates a tiny Flipper:
a scrollable menu, the screen stream, input events, ping, device info and its properties (an official
firmware, with fake values), an in-memory SD card, and one app with hidden keys, Dial (below).

    python -m bridge.sim --config ~/projects/zeffin-data/config.json

With --flipper-tcp PORT it is only the Flipper: it listens on 127.0.0.1:PORT and speaks raw RPC
bytes, one fresh RPC session per TCP connection (as the Flipper does per Bluetooth connection).
The app's end-to-end relay test connects to it in place of Bluetooth.
"""
from __future__ import annotations

import argparse
import asyncio
import json
from pathlib import Path

from PIL import Image, ImageDraw

from . import config as cfg
from . import pb, tlskey
from .framing import Decoder, frame

# What property_get_request answers (fake values; the "devinfo." prefix is the request's, not the reply's key). The
# property request names the commit and the branch "firmware.commit.hash" and "firmware.branch.name".
DEVINFO = {"hardware.model": "Flipper Zero", "hardware.name": "Simulator", "firmware.commit.hash": "00000000",
           "firmware.branch.name": "sim", "firmware.version": "sim", "firmware.build.date": "01-01-2026",
           "firmware.api.major": "0", "firmware.api.minor": "0", "firmware.origin.fork": "Official"}
MENU = ["Sub-GHz", "125 kHz RFID", "NFC", "Infrared", "GPIO", "iButton", "Bad USB", "U2F", "Apps", "Settings"]
BLE_CHUNK = 244

# Dial, a simulated .fap with what a note per app is for, modelled on a real app that hides its keys: UP and
# DOWN turn the dial and a short OK shows "Saved" (a decoy); a long OK opens About, with the code word; a short BACK
# does nothing on the dial; a long BACK asks "Exit Dial?", and RIGHT confirms (LEFT or BACK stays). Like most .fap
# apps it has no RPC: app_exit_request answers ERROR_APP_NOT_RUNNING. The probe's --notes steps use it.
DIAL_PATH = "/ext/apps/Tools/sim_dial.fap"
DIAL_CODE = "AMBER-7"


def render(title: str, items: list[str], selected: int) -> bytes:
    """Draw a menu and pack it like the Flipper framebuffer (vertical bytes, LSB on top, 1 = dark)."""
    image = Image.new("1", (128, 64), 0)
    draw = ImageDraw.Draw(image)
    draw.text((2, 0), title, fill=1)
    top = max(0, min(selected - 1, len(items) - 4))
    for row, idx in enumerate(range(top, min(top + 4, len(items)))):
        y = 14 + row * 12
        if idx == selected:
            draw.rectangle((0, y - 1, 127, y + 10), fill=1)
            draw.text((4, y), items[idx], fill=0)
        else:
            draw.text((4, y), items[idx], fill=1)
    px = image.load()
    out = bytearray(1024)
    for page in range(8):
        for x in range(128):
            byte = 0
            for bit in range(8):
                if px[x, page * 8 + bit]:
                    byte |= 1 << bit
            out[page * 128 + x] = byte
    return bytes(out)


class SimFlipper:
    def __init__(self):
        self.selected = 0
        self.app: str | None = None
        self.dial: str | None = None  # Dial's view while it runs: "dial", "saved", "about" or "exit"
        self.dial_value = 0
        self.streaming = False
        self.files: dict[str, bytes] = {"/ext/README.txt": b"Simulated SD card\n", DIAL_PATH: b"\x7fELF sim"}
        self.dirs = {"/ext", "/int", "/ext/apps", "/ext/apps/Tools"}
        self.write_buf: dict[int, bytearray] = {}

    def screen(self) -> bytes:
        if self.dial == "about":
            return render("About Dial", ["Dial 1.0", f"Code word: {DIAL_CODE}"], -1)
        if self.dial == "exit":
            return render("Exit Dial?", ["RIGHT: exit", "LEFT: stay"], -1)
        if self.dial is not None:
            return render("Dial", [f"Value: {self.dial_value}"] + (["Saved"] if self.dial == "saved" else []), -1)
        if self.app:
            return render(self.app, ["(simulated app)", "BACK to exit"], 0)
        return render("Main menu", MENU, self.selected)

    def dial_key(self, key: str, typ: str) -> None:
        """One key in Dial: a long OK or a long BACK, or a short key."""
        view = self.dial
        if view == "about":
            if typ == "SHORT" and key == "BACK":
                self.dial = "dial"
        elif view == "exit":
            if typ == "SHORT" and key == "RIGHT":
                self.dial = self.app = None
            elif typ == "SHORT" and key in ("LEFT", "BACK"):
                self.dial = "dial"
        elif typ == "LONG":
            if key == "OK":
                self.dial = "about"
            elif key == "BACK":
                self.dial = "exit"
        elif key in ("UP", "DOWN"):
            self.dial_value = (self.dial_value + (1 if key == "UP" else -1)) % 10
            self.dial = "dial"
        elif key == "OK":
            self.dial = "saved"

    def handle(self, r) -> list:
        """Return the reply messages for one request (and screen frames when the screen changes)."""
        kind = r.WhichOneof("content")
        out = []

        def ok(**content):
            m = pb.Main(command_id=r.command_id)
            if not content:
                m.empty.SetInParent()
            for k, v in content.items():
                getattr(m, k).CopyFrom(v)
            return m

        def err(status):
            m = pb.Main(command_id=r.command_id, command_status=pb.CommandStatus.Value(status))
            m.empty.SetInParent()
            return m

        if kind == "gui_start_screen_stream_request":
            if self.streaming:
                return [err("ERROR_VIRTUAL_DISPLAY_ALREADY_STARTED")]
            self.streaming = True
            return [ok(), self.frame()]
        if kind == "gui_stop_screen_stream_request":
            self.streaming = False
            return [ok()]
        if kind == "gui_send_input_event_request":
            e = r.gui_send_input_event_request
            key, typ = pb.gui.InputKey.Name(e.key), pb.gui.InputType.Name(e.type)
            out.append(ok())
            if self.dial is not None and typ in ("SHORT", "LONG"):
                self.dial_key(key, typ)
                if self.streaming:
                    out.append(self.frame())
            elif typ == "SHORT":
                if self.app:
                    if key == "BACK":
                        self.app = None
                elif key == "DOWN":
                    self.selected = (self.selected + 1) % len(MENU)
                elif key == "UP":
                    self.selected = (self.selected - 1) % len(MENU)
                elif key == "OK":
                    self.app = MENU[self.selected]
                if self.streaming:
                    out.append(self.frame())
            return out
        if kind == "system_ping_request":
            return [ok(system_ping_response=pb.system.PingResponse(data=r.system_ping_request.data))]
        if kind == "system_device_info_request":
            pairs = [("hardware_name", "Simulator"), ("firmware_version", "sim"), ("protobuf_version_major", "0")]
            msgs = []
            for i, (k, v) in enumerate(pairs):
                m = pb.Main(command_id=r.command_id, has_next=i < len(pairs) - 1)
                m.system_device_info_response.key, m.system_device_info_response.value = k, v
                msgs.append(m)
            return msgs
        if kind == "property_get_request":
            top, _, sub = r.property_get_request.key.partition(".")
            if top != "devinfo":
                return [err("ERROR_INVALID_PARAMETERS")]
            found = [(k, v) for k, v in DEVINFO.items() if k.startswith(sub)]
            if not found:
                return [ok()]
            msgs = []
            for i, (k, v) in enumerate(found):
                m = pb.Main(command_id=r.command_id, has_next=i < len(found) - 1)
                m.property_get_response.key, m.property_get_response.value = k, v
                msgs.append(m)
            return msgs
        if kind == "system_power_info_request":
            m = pb.Main(command_id=r.command_id)
            m.system_power_info_response.key, m.system_power_info_response.value = "charge_level", "77"
            return [m]
        if kind == "storage_list_request":
            path = r.storage_list_request.path.rstrip("/")
            if path not in self.dirs:
                return [err("ERROR_STORAGE_NOT_EXIST")]
            resp = pb.storage.ListResponse()
            for name, data in self.files.items():
                if name.rsplit("/", 1)[0] == path:
                    resp.file.add(name=name.rsplit("/", 1)[1], size=len(data))
            for d in self.dirs:
                if d.rsplit("/", 1)[0] == path and d != path:
                    resp.file.add(name=d.rsplit("/", 1)[1], type=pb.storage.File.DIR)
            return [ok(storage_list_response=resp)]
        if kind == "storage_stat_request":
            p = r.storage_stat_request.path
            if p in self.dirs:
                return [ok(storage_stat_response=pb.storage.StatResponse(file=pb.storage.File(type=pb.storage.File.DIR)))]
            if p in self.files:
                return [ok(storage_stat_response=pb.storage.StatResponse(file=pb.storage.File(size=len(self.files[p]))))]
            return [err("ERROR_STORAGE_NOT_EXIST")]
        if kind == "storage_read_request":
            data = self.files.get(r.storage_read_request.path)
            if data is None:
                return [err("ERROR_STORAGE_NOT_EXIST")]
            chunks = [data[i:i + 512] for i in range(0, len(data), 512)] or [b""]
            msgs = []
            for i, c in enumerate(chunks):
                m = pb.Main(command_id=r.command_id, has_next=i < len(chunks) - 1)
                m.storage_read_response.file.data = c
                msgs.append(m)
            return msgs
        if kind == "storage_write_request":
            buf = self.write_buf.setdefault(r.command_id, bytearray())
            buf += r.storage_write_request.file.data
            if r.has_next:
                return []
            self.files[r.storage_write_request.path] = bytes(self.write_buf.pop(r.command_id))
            return [ok()]
        if kind == "storage_mkdir_request":
            self.dirs.add(r.storage_mkdir_request.path.rstrip("/"))
            return [ok()]
        if kind == "storage_delete_request":
            if self.files.pop(r.storage_delete_request.path, None) is None:
                return [err("ERROR_STORAGE_NOT_EXIST")]
            return [ok()]
        if kind == "app_start_request":
            if self.app:
                return [err("ERROR_APP_SYSTEM_LOCKED")]
            self.app = r.app_start_request.name
            if self.app == DIAL_PATH:
                self.dial, self.dial_value = "dial", 0
            return [ok()] + ([self.frame()] if self.streaming else [])
        if kind == "app_exit_request":
            if not self.app or self.dial is not None:  # Dial has no RPC, as most .fap apps
                return [err("ERROR_APP_NOT_RUNNING")]
            self.app = None
            return [ok()] + ([self.frame()] if self.streaming else [])
        return [err("ERROR_NOT_IMPLEMENTED")]

    def frame(self):
        m = pb.Main()
        m.gui_screen_frame.data = self.screen()
        return m


async def run(url: str, token: str, transport: str = "BLE", cert: Path | None = None) -> None:
    """`cert`: the server's certificate in its data folder: the only one trusted, checked before the token is sent."""
    import websockets

    sim = SimFlipper()
    decoder = Decoder()
    tls = {"ssl": tlskey.client_context(cert)} if cert is not None else {}
    async with websockets.connect(url, additional_headers={"Authorization": f"Bearer {token}"}, max_size=65536,
                                  **tls) as ws:
        await asyncio.sleep(0.5)  # "pairing"
        await ws.send(json.dumps({"type": "ready", "protocol": 1, "transport": transport}))
        print(f"sim: connected to {url} as {transport}")
        async for message in ws:
            if isinstance(message, str):
                raise SystemExit(f"sim: server sent text ({message!r}); the real app would close with 1003")
            if len(message) > 65536:
                raise SystemExit("sim: frame larger than the app accepts")
            await asyncio.sleep(0.01)  # transport write time
            await ws.send('{"type":"written"}')
            for raw in decoder.feed(message):
                for reply in sim.handle(pb.Main.FromString(raw)):
                    data = frame(reply.SerializeToString())
                    for i in range(0, len(data), BLE_CHUNK):  # arbitrary transport boundaries
                        await ws.send(data[i:i + BLE_CHUNK])


async def serve_tcp(port: int, host: str = "127.0.0.1", ready=None) -> None:
    async def handle(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        sim, decoder = SimFlipper(), Decoder()
        try:
            while data := await reader.read(4096):
                for raw in decoder.feed(data):
                    for reply in sim.handle(pb.Main.FromString(raw)):
                        writer.write(frame(reply.SerializeToString()))
                await writer.drain()
        except (ConnectionError, ValueError):
            pass
        finally:
            writer.close()

    server = await asyncio.start_server(handle, host, port)
    print(f"sim: Flipper listening on tcp://{host}:{server.sockets[0].getsockname()[1]}", flush=True)
    if ready is not None:
        ready.set_result(server.sockets[0].getsockname()[1])
    async with server:
        await server.serve_forever()


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--config", type=Path, default=cfg.DEFAULT_CONFIG)
    p.add_argument("--transport", choices=["BLE", "USB"], default="BLE")
    p.add_argument("--flipper-tcp", type=int, metavar="PORT", help="only emulate the Flipper, over TCP")
    args = p.parse_args()
    if args.flipper_tcp is not None:
        asyncio.run(serve_tcp(args.flipper_tcp))
        return
    c = cfg.load(args.config)
    _, cert = tlskey.paths(args.config.expanduser().resolve().parent)  # the data folder, as zf finds it
    asyncio.run(run(c.phone_url(), c.phone_token, args.transport, cert if cert.exists() else None))


if __name__ == "__main__":
    main()
