# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The firmware detection: which firmware the attached Flipper runs, read in the background after the screen stream
starts, and the apps installed on it.

Why small commands and no lock: the Flipper runs RPC commands one at a time and answers in order, so a tool call sent
behind a long reply waits for all of it on the wire, whoever holds ops_lock. Detection therefore sends one small
command at a time (a property_get_request per key prefix, 1-2 parts each), and before each one waits until ops_lock is
free, without taking a place in its queue: an agent call waits at most for one small reply. A firmware that refuses the
property request gets system_device_info_request (~60 parts, ~25 s over Bluetooth) under ops_lock, so a call waits for
it in the open instead of timing out on the wire. A later key the Flipper refuses is skipped: the run goes on with the
others, and the `detect` metrics line and flipper_firmware name it; a run whose every key after the first is refused
fails.

A run belongs to its session: a result from a session that is no longer the attached one, or from an older run, is
dropped. When the session closes during a run, Hub.on_closed calls closed(), which marks it failed with the reason.

Everything read here is untrusted text from the device, cleaned in devicecache.
"""
from __future__ import annotations

import asyncio
import contextlib
import re
import time
from typing import TYPE_CHECKING, Any, Callable

from . import pb, profiles
from .devicecache import Apps, DeviceCache, Snapshot, build_of, clean_names, clean_pairs, firmware_of
from .events import Ev
from .phone import LinkError, RpcError, RpcTimeout, build

if TYPE_CHECKING:
    from .gate import JsonlLog
    from .phone import PhoneSession

NONE, DETECTING, DONE, FAILED = "none", "detecting", "done", "failed"

# One command per prefix: what flipper_firmware shows (devicecache.FIELDS) and little else.
KEYS = ("devinfo.firmware.origin", "devinfo.firmware.version", "devinfo.firmware.build.date", "devinfo.firmware.api",
        "devinfo.firmware.branch", "devinfo.firmware.commit", "devinfo.hardware.name", "devinfo.hardware.model")
# A firmware without the property request (needs checking on the Flipper which status it answers).
UNSUPPORTED = {pb.CommandStatus.Value("ERROR_INVALID_PARAMETERS"), pb.CommandStatus.Value("ERROR_NOT_IMPLEMENTED")}
STEP_TIMEOUT = 20.0  # seconds for each part of a reply
APPS_ROOT = "/ext/apps"
MAX_FOLDERS = 64  # listings per run: the categories and their subfolders together
SUBFOLDER = re.compile(r"[A-Za-z0-9][A-Za-z0-9 _.-]{0,31}")  # a subfolder name from the device that is listed
WHY_LIMIT = 200


class RefusedAll(Exception):
    """Every property key after the first was refused: nothing of this firmware is known."""


def short(key: str) -> str:
    """A property key without its "devinfo." prefix, as the metrics line and flipper_firmware name it."""
    return key.removeprefix("devinfo.")


class Firmware:
    """The detection's state (hub.firmware) and the runs that change it."""

    def __init__(self, cache: DeviceCache, lock: asyncio.Lock, metrics: "JsonlLog",
                 attached: Callable[[], "PhoneSession | None"], ops_changed: Callable[[], None] = lambda: None):
        self.cache = cache
        self.lock = lock  # Hub.ops_lock
        self.metrics = metrics
        self.attached = attached
        self.ops_changed = ops_changed  # Hub.ops_changed: /app's "busy" follows ops_lock when detection holds it
        self.state = NONE
        self.why: str | None = None
        self.since: float | None = None  # wall clock: when the run started
        self.at: float | None = None  # wall clock: when the last run ended
        self.took_s: float | None = None
        self.learning = False
        self.apps_why: str | None = None  # why the apps were not learned in the last run
        self.skipped: list[str] = []  # short names of the keys the Flipper refused in the last run
        self._run = 0
        self._session: "PhoneSession | None" = None
        self._task: asyncio.Task | None = None
        self._started = 0.0

    # ---- what the tool and /status read ----
    def summary(self) -> dict[str, Any]:
        snap = self.cache.snapshot
        fw = snap.firmware if snap else {}
        return {"state": self.state, "why": self.why, "learning": self.learning,
                "origin": fw.get("origin"), "version": fw.get("version"), "hardware_name": fw.get("hardware_name"),
                "detected_at": round(snap.detected_at, 3) if snap else None}

    # ---- runs ----
    def start(self, session: "PhoneSession") -> None:
        """The background run for a new session (after its stream started, or failed to)."""
        self._task = session.spawn(self.detect(session))

    async def refresh(self, session: "PhoneSession") -> None:
        """Detect and learn again now; the caller holds ops_lock (flipper_firmware(refresh=true))."""
        task, self._task = self._task, None
        if task is not None and not task.done():
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await task
        await self.detect(session, locked=True, relearn=True)

    async def detect(self, session: "PhoneSession", *, locked: bool = False, relearn: bool = False) -> None:
        if self.attached() is not session:
            return  # a run for a session already replaced: the attached one has its own
        self._run += 1
        run = self._run
        self._session, self.state, self.why = session, DETECTING, None
        self.since, self._started = time.time(), time.monotonic()
        self.learning, self.apps_why, self.skipped = False, None, []
        try:
            pairs = await self._read(session, locked)
        except (LinkError, RpcTimeout, RpcError, RefusedAll) as e:
            self._fail(run, session, str(e))
            return
        if not self._mine(run, session):
            return
        raw = clean_pairs(pairs)
        firmware = firmware_of(raw)
        old = self.cache.snapshot
        apps = old.apps if old is not None and old.apps is not None and old.apps.build == build_of(firmware) else None
        self.cache.save(Snapshot(firmware, raw, time.time(), apps))
        self.state, self.at, self.took_s = DONE, time.time(), round(time.monotonic() - self._started, 2)
        if apps is None or relearn:
            await self._learn(run, session, firmware, locked)
        self._write_metric()

    def closed(self, session: "PhoneSession", reason: str | None) -> None:
        """The session closed (Hub.on_closed): a run of it still detecting has failed; one learning stops there."""
        if self._session is not session:
            return
        why = f"the session closed: {reason or 'disconnected'}"
        if self.state == DETECTING:
            self._fail(self._run, session, why, attached=False)
        elif self.learning:
            self.learning, self.apps_why = False, why
            self._write_metric()
        self._session = None

    # ---- inside a run ----
    def _mine(self, run: int, session: "PhoneSession") -> bool:
        return run == self._run and self._session is session and self.attached() is session

    def _fail(self, run: int, session: "PhoneSession", why: str, attached: bool = True) -> None:
        if run != self._run or self._session is not session or self.state != DETECTING:
            return
        if attached and self.attached() is not session:
            return
        self.state, self.why, self.at = FAILED, why[:WHY_LIMIT], time.time()
        self.took_s = round(time.monotonic() - self._started, 2)
        self._write_metric()

    async def _free(self, locked: bool) -> None:
        """Wait until no tool call holds ops_lock, without holding it across an await or taking a waiting place."""
        if not locked and self.lock.locked():
            async with self.lock:
                pass

    async def _read(self, session: "PhoneSession", locked: bool) -> list[tuple[str, str]]:
        pairs: list[tuple[str, str]] = []
        for i, key in enumerate(KEYS):
            await self._free(locked)
            try:
                replies = await session.request([build("property_get_request", {"key": key})], STEP_TIMEOUT)
            except RpcError as e:
                if i == 0:
                    if e.status in UNSUPPORTED:
                        return await self._device_info(session, locked)
                    raise
                self.skipped.append(short(key))  # a later key refused: the others still count
                if len(self.skipped) == len(KEYS) - 1:
                    raise RefusedAll(f"the Flipper refused every property key after the first (last: {e})") from e
                continue
            pairs += [(r.property_get_response.key, r.property_get_response.value)
                      for r in replies if r.HasField("property_get_response")]
        return pairs

    async def _device_info(self, session: "PhoneSession", locked: bool) -> list[tuple[str, str]]:
        """Every pair at once: the agent's calls wait for it on ops_lock rather than behind it on the wire."""
        if locked:
            replies = await session.request([build("system_device_info_request")], STEP_TIMEOUT)
        else:
            await self.lock.acquire()
            self.ops_changed()  # as tools.run does: /app's status reads busy while it holds the lock
            try:
                replies = await session.request([build("system_device_info_request")], STEP_TIMEOUT)
            finally:
                self.lock.release()
                self.ops_changed()
        return [(r.system_device_info_response.key, r.system_device_info_response.value)
                for r in replies if r.HasField("system_device_info_response")]

    async def _learn(self, run: int, session: "PhoneSession", firmware: dict[str, str], locked: bool) -> None:
        """The .fap files in each of the profile's /ext/apps/<Category> and, one level down, in their subfolders
        (GPIO/ESP and the like), one listing at a time and at most MAX_FOLDERS listings. Folder names from the device
        are untrusted: only a plain name (SUBFOLDER) is listed, and it becomes a cache key such as "GPIO/ESP"."""
        queue = list(profiles.choose(firmware.get("origin")).profile.app_categories)
        found: dict[str, list[str]] = {}
        self.learning = True
        listed = 0
        try:
            while queue and listed < MAX_FOLDERS:
                folder = queue.pop(0)
                listed += 1
                await self._free(locked)
                try:
                    replies = await session.request(
                        [build("storage_list_request", {"path": f"{APPS_ROOT}/{folder}"})], STEP_TIMEOUT)
                except RpcError:
                    continue  # no such folder on this card
                files = [f for r in replies for f in r.storage_list_response.file]
                names = [f.name for f in files if f.type != pb.storage.File.DIR and f.name.lower().endswith(".fap")]
                if names:
                    found[folder] = clean_names(names)
                if "/" not in folder:  # one level down only
                    subs = sorted({f.name for f in files if f.type == pb.storage.File.DIR
                                   and isinstance(f.name, str) and SUBFOLDER.fullmatch(f.name)})
                    queue += [f"{folder}/{sub}" for sub in subs]
        except (LinkError, RpcTimeout) as e:
            if self._mine(run, session):
                self.apps_why = str(e)[:WHY_LIMIT]
            return
        finally:
            if run == self._run:
                self.learning = False
        snap = self.cache.snapshot
        if self._mine(run, session) and snap is not None:
            self.cache.save(Snapshot(snap.firmware, snap.raw, snap.detected_at,
                                     Apps(build_of(snap.firmware), time.time(), found)))

    def _write_metric(self) -> None:
        snap = self.cache.snapshot
        record: dict[str, Any] = {"ev": Ev.DETECT, "state": self.state, "took_s": self.took_s}
        if self.state == FAILED:
            record["why"] = self.why
        else:
            record["apps"] = snap.apps.count() if snap is not None and snap.apps is not None else None
            if self.apps_why:
                record["apps_why"] = self.apps_why
            if self.skipped:
                record["skipped"] = list(self.skipped)
        self.metrics.write(record)
