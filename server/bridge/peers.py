# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The devices that use the server: the public listener's peers, one per address, in memory only (docs/security.md).

Every paired device holds the same tokens, so a device is told apart by its address on the private network, which the
tunnel ties to that device. A device enters only once a connection from it passed a token, the view cookie or a
pairing: the adapters say so (phonews, appws, view, app), never for a peer that has not.
- Connected now: its open /phone (relay), /app (app) and /view/events (web view). The short requests (/phone/check,
  attachments, the web view's sign-in and pages, collecting the tokens after a pairing) only count as used and seen.
- Since the server started, at most MAX devices: when a new one comes, the one seen longest ago with nothing open goes.
- The server's own connections (loopback, or its own address: zf chat) are one row, "this server", never looked up.
- A device's name and OS come from the private network (privnet.lookup), in a worker thread after a sighting, at most
  LOOKUPS at once and one per device, kept FRESH_S (a failure RETRY_S). summary() never waits for one. A device
  chooses its own name: it is cleaned here, and printed terminal-safe.
- The first time a device is seen after a start, the server's log says so, once, with its name: the record that
  outlives a restart. Nothing is written anywhere else.
"""
from __future__ import annotations

import asyncio
import logging
import time
from collections.abc import Callable
from dataclasses import dataclass, field

from . import privnet
from .text import clean_text

log = logging.getLogger("zeffin.devices")

RELAY, APP, VIEW, PAIRING = "relay", "app", "view", "pairing"
KINDS = (RELAY, APP, VIEW, PAIRING)
NAME_LIMIT = 63  # a DNS label
OS_LIMIT = 16
THIS_SERVER = "this server"  # the key of the server's own row
PENDING, DONE, FAILED, NONE = "pending", "done", "failed", "none"  # where a device's lookup stands


@dataclass
class Device:
    address: str
    this_server: bool
    first_seen: float
    last_seen: float
    open: dict[str, int] = field(default_factory=dict)
    used: set[str] = field(default_factory=set)
    name: str | None = None
    os: str | None = None
    lookup: str = PENDING
    next_lookup: float = 0.0  # monotonic: no new lookup before it
    logged: bool = False

    def item(self) -> dict:
        return {"address": self.address, "this_server": self.this_server, "name": self.name, "os": self.os,
                "lookup": self.lookup, "connected": {k: n for k, n in sorted(self.open.items()) if n > 0},
                "used": sorted(self.used), "first_seen": round(self.first_seen, 1), "last_seen": round(self.last_seen, 1)}


class Peers:
    """The devices seen since the server started. seen, open and close take the kernel's peer address as text."""

    MAX = 32
    LOOKUPS = 2
    FRESH_S = 600.0
    RETRY_S = 60.0

    def __init__(self, own_address: str | None = None, lookup: Callable[..., dict | None] | None = None,
                 clock: Callable[[], float] = time.time, monotonic: Callable[[], float] = time.monotonic):
        self.own = privnet.peer_ip(own_address)
        self.lookup, self.clock, self.monotonic = lookup, clock, monotonic  # lookup None: privnet.lookup
        self.since = clock()
        self.devices: dict[str, Device] = {}
        self.tasks: set[asyncio.Task] = set()
        self._slots: asyncio.Semaphore | None = None

    def seen(self, host: str | None, kind: str) -> None:
        """A request from host passed its credential: it used kind."""
        self._sighting(host, kind)

    def open(self, host: str | None, kind: str) -> None:
        """A connection of kind from host passed its credential and stays open until close."""
        device = self._sighting(host, kind)
        if device is not None:
            device.open[kind] = device.open.get(kind, 0) + 1

    def close(self, host: str | None, kind: str) -> None:
        key = self._key(host)
        device = self.devices.get(key[0]) if key else None
        if device is None or device.open.get(kind, 0) <= 0:
            return
        device.open[kind] -= 1
        device.last_seen = self.clock()

    def summary(self) -> dict:
        """For /status: connected devices first (oldest first), then the others, last seen first."""
        devices = list(self.devices.values())
        now = sorted((d for d in devices if any(d.open.values())), key=lambda d: d.first_seen)
        rest = sorted((d for d in devices if not any(d.open.values())), key=lambda d: -d.last_seen)
        return {"since": round(self.since, 1), "items": [d.item() for d in now + rest]}

    def _key(self, host: str | None) -> tuple[str, privnet.IPAddress, bool] | None:
        ip = privnet.peer_ip(host)
        if ip is None:
            return None
        this = ip.is_loopback or (self.own is not None and ip == self.own)
        return (THIS_SERVER if this else str(ip)), ip, this

    def _sighting(self, host: str | None, kind: str) -> Device | None:
        key = self._key(host)
        if key is None or kind not in KINDS:
            return None
        name, ip, this = key
        now = self.clock()
        device = self.devices.get(name)
        if device is None:
            self._make_room()
            device = Device(str(ip), this, now, now, lookup=NONE if this else PENDING)
            self.devices[name] = device
        device.used.add(kind)
        device.last_seen = now
        if not this:
            self._look_up(name, device, ip)
        return device

    def _make_room(self) -> None:
        if len(self.devices) < self.MAX:
            return
        idle = [k for k, d in self.devices.items() if not any(d.open.values())]
        pool = idle or list(self.devices)
        del self.devices[min(pool, key=lambda k: self.devices[k].last_seen)]

    def _look_up(self, key: str, device: Device, ip: privnet.IPAddress) -> None:
        if self.monotonic() < device.next_lookup:
            return
        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:
            return
        device.next_lookup = float("inf")  # one in flight per device
        task = loop.create_task(self._lookup_task(key, device, ip))
        self.tasks.add(task)
        task.add_done_callback(self.tasks.discard)

    async def _lookup_task(self, key: str, device: Device, ip: privnet.IPAddress) -> None:
        if self._slots is None:
            self._slots = asyncio.Semaphore(self.LOOKUPS)
        found: dict | None = None
        try:
            async with self._slots:
                found = await asyncio.to_thread(self.lookup or privnet.lookup, ip)
        except Exception:  # a lookup never fails a request: the address alone stays
            found = None
        name = clean_text(found.get("name"), NAME_LIMIT) if isinstance(found, dict) else None
        os_name = clean_text(found.get("os"), OS_LIMIT) if isinstance(found, dict) else None
        if name or os_name:
            device.name, device.os, device.lookup = name, os_name, DONE
            device.next_lookup = self.monotonic() + self.FRESH_S
        else:
            device.lookup = DONE if device.name else FAILED  # a refresh that fails keeps the name it had
            device.next_lookup = self.monotonic() + self.RETRY_S
        if not device.logged and self.devices.get(key) is device:
            device.logged = True
            who = (f"{device.name} ({device.os + ', ' if device.os else ''}{device.address})" if device.name
                   else f"{device.address} ({device.os + ', ' if device.os else ''}{privnet.NO_NAME})")
            log.info("first connection since the server started from %s: %s", who, ", ".join(sorted(device.used)))
