# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""Operator control gate, audit log and metrics log (each kept to its newest bytes), and the atomic write of a state file."""
from __future__ import annotations

import asyncio
import contextlib
import errno
import json
import logging
import math
import os
import threading
import time
from collections import deque
from pathlib import Path
from typing import Any

from .fanout import FanOut
from .wire import ACTIVE, PAUSED, STOPPED

log = logging.getLogger(__name__)

LOG_KEEP_BYTES = 8 * 1024 * 1024  # each JSONL log keeps its newest 8 MiB


class GateClosed(Exception):
    pass


class ControlGate:
    """Whether the agent may send anything that changes device state.

    active  - the agent acts freely (the operator watches the live view)
    paused  - every write/press/app/rpc tool is refused; reads still work
    stopped - paused, and the phone session is closed and refused until resume

    With a path, the state is kept there so a Pause or Stop survives a restart of the server; a missing or
    unreadable file means active.
    """

    def __init__(self, path: Path | None = None) -> None:
        self.path = path
        self.state = ACTIVE
        self.changed_at = time.time()
        self.by = "startup"
        self._fanout = FanOut(8)
        self._load()

    def _load(self) -> None:
        if self.path is None:
            return
        try:
            data = json.loads(self.path.read_text())
            state, changed_at, by = data["state"], data["changed_at"], data["by"]
        except (OSError, ValueError, TypeError, KeyError):
            return
        if (state in (ACTIVE, PAUSED, STOPPED) and isinstance(changed_at, (int, float))
                and not isinstance(changed_at, bool) and math.isfinite(changed_at) and isinstance(by, str)):
            self.state, self.changed_at, self.by = state, float(changed_at), by[:100]

    def _save(self) -> None:
        if self.path is None:
            return
        try:
            write_atomic(self.path, json.dumps(self.snapshot()).encode())
        except OSError as e:  # the new state still holds in memory
            log.warning("Could not save the gate state to %s: %s", self.path, e)

    def set(self, state: str, by: str) -> None:
        if state not in (ACTIVE, PAUSED, STOPPED):
            raise ValueError(state)
        self.state, self.changed_at, self.by = state, time.time(), by
        self._save()
        self._fanout.publish(self.snapshot())

    def check(self) -> None:
        if self.state != ACTIVE:
            raise GateClosed(
                f"Control {self.state} by the operator at {time.strftime('%H:%M:%S', time.localtime(self.changed_at))}. "
                "Do not retry; wait for the operator to resume."
            )

    def snapshot(self) -> dict[str, Any]:
        return {"state": self.state, "changed_at": self.changed_at, "by": self.by}

    def listen(self) -> asyncio.Queue:
        return self._fanout.listen()

    def unlisten(self, q: asyncio.Queue) -> None:
        self._fanout.unlisten(q)


class JsonlLog:
    """An append-only JSONL file (mode 600), the last `keep` records in memory, and listeners.

    The file keeps its newest `keep_bytes`: when an append takes it past keep_bytes + keep_bytes // 4, it is
    replaced by its tail from the first line that starts within the last keep_bytes. Its name never changes, so
    every reader reads one file and its last line is always the newest record. A write that fails on disk (a full
    disk) never reaches the caller: the record still goes to `recent` and the listeners, and the server's log
    says once when writing starts to fail and once when it works again.
    """

    def __init__(self, path: Path | None, keep: int = 0, keep_bytes: int = LOG_KEEP_BYTES):
        self.path = path
        self.keep_bytes = keep_bytes
        self.recent: deque[dict[str, Any]] = deque(maxlen=keep or None) if keep else deque(maxlen=0)
        self._fanout = FanOut(64)
        self._lock = threading.Lock()  # an append and a trim from two threads never interleave
        self._failing = False
        if path is not None:
            path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)

    def write(self, event: dict[str, Any]) -> dict[str, Any]:
        record = {"ts": round(time.time(), 3), **event}
        if self.recent.maxlen:
            self.recent.append(record)
        if self.path is not None:
            line = json.dumps(record, ensure_ascii=False, default=str) + "\n"
            with self._lock:
                self._store(self.path, line.encode())
        self._fanout.publish(record)
        return record

    def _store(self, path: Path, data: bytes) -> None:
        try:
            size = self._append(path, data)
            if size > self.keep_bytes + self.keep_bytes // 4:
                self._trim(path, size)
        except OSError as e:
            if not self._failing:
                self._failing = True
                log.warning("Could not write to %s; its new records are kept in memory only: %s",
                            path.name, e)
            return
        if self._failing:
            self._failing = False
            log.info("Writing to %s works again", path.name)

    def _append(self, path: Path, data: bytes) -> int:
        """Append one line; returns the file's size after it."""
        fd = os.open(path, os.O_RDWR | os.O_CREAT | os.O_APPEND, 0o600)  # read: the last byte after a failure
        try:
            if self._failing:  # a write cut short by a full disk must not run into this line
                end = os.fstat(fd).st_size
                if end and os.pread(fd, 1, end - 1) != b"\n":
                    data = b"\n" + data
            if os.write(fd, data) != len(data):
                raise OSError(errno.ENOSPC, "short write")
            return os.fstat(fd).st_size
        finally:
            os.close(fd)

    def _trim(self, path: Path, size: int) -> None:
        """Replace the file with its newest keep_bytes, from a line's start. On failure the file stays as it was."""
        with path.open("rb") as f:
            f.seek(size - self.keep_bytes - 1)
            f.readline()  # to the next line's start (the byte before keep_bytes may end a line)
            tail = f.read()
        write_atomic(path, tail)
        log.info("Trimmed %s to its newest %d bytes", path.name, len(tail))

    def listen(self) -> asyncio.Queue:
        return self._fanout.listen()

    def unlisten(self, q: asyncio.Queue) -> None:
        self._fanout.unlisten(q)


def write_atomic(path: Path, data: bytes) -> None:
    """Replace a file with `data` (mode 600) by one rename; on failure the file stays as it was, with no temporary."""
    tmp = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    try:
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)  # screen contents may be sensitive
        with os.fdopen(fd, "wb") as f:
            f.write(data)
        os.replace(tmp, path)
    except BaseException:
        with contextlib.suppress(OSError):
            os.unlink(tmp)
        raise
