# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""A process under a pseudo-terminal, for Claude Code's interactive mode (Remote Control).

The output is drained all the time (a full terminal buffer would stop the process) and only its last
part is kept, without escape sequences, to look for known words. It is never logged or reported.
Claude Code's terminal interface may ask the terminal where the cursor is or what it is; the reader
answers those two queries the way a plain xterm would (needs testing on the server).
"""
from __future__ import annotations

import fcntl
import os
import re
import signal
import struct
import subprocess
import termios
import threading
import time
from typing import Mapping

ESC = "\x1b"
CTRL_C = "\x03"
_SEQUENCE = re.compile(rb"\x1b(\[[0-?]*[ -/]*[@-~]|\][^\x07\x1b]*(\x07|\x1b\\)|[PX^_][^\x1b]*\x1b\\|.)")
_CURSOR_QUERY = b"\x1b[6n"
_DEVICE_QUERY = re.compile(rb"\x1b\[0?c")
KEEP = 64 * 1024


class Terminal:
    KEY_GAP_S = 0.4   # between typed text and its Enter, so the Enter is never read as part of a paste
    POLL_S = 0.2      # how often quiet_for looks at the output

    def __init__(self, argv: list[str], *, cwd: str, env: Mapping[str, str], rows: int = 50, cols: int = 200):
        master, slave = os.openpty()
        fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
        env = dict(env)
        env.setdefault("TERM", "xterm-256color")
        try:
            self.proc = subprocess.Popen(argv, cwd=cwd, env=env, stdin=slave, stdout=slave, stderr=slave,
                                         start_new_session=True, close_fds=True, preexec_fn=_controlling_tty)
        finally:
            os.close(slave)
        self.master = master
        self.started = time.monotonic()
        self._tail = bytearray()
        self._lock = threading.Lock()
        self.bytes_read = 0
        self._reader = threading.Thread(target=self._read, daemon=True)
        self._reader.start()

    def _read(self) -> None:
        while True:
            try:
                data = os.read(self.master, 4096)
            except OSError:
                break  # EIO: the process closed the terminal
            if not data:
                break
            self.bytes_read += len(data)
            if _CURSOR_QUERY in data:
                self._write(b"\x1b[1;1R")
            if _DEVICE_QUERY.search(data):
                self._write(b"\x1b[?62;22c")
            with self._lock:
                self._tail += data
                del self._tail[:-KEEP]

    def _write(self, data: bytes) -> None:
        try:
            os.write(self.master, data)
        except OSError:
            pass

    def type(self, text: str, enter: bool = True) -> None:
        """Type text as keys, then Enter (a separate key, so it is never read as part of a paste)."""
        self._write(text.encode())
        if enter:
            time.sleep(self.KEY_GAP_S)
            self._write(b"\r")

    def key(self, key: str) -> None:
        self._write(key.encode())

    def screen_text(self) -> str:
        """The last output without escape sequences, lower case, with whitespace collapsed."""
        with self._lock:
            raw = bytes(self._tail)
        text = _SEQUENCE.sub(b" ", raw).decode("utf-8", "replace").lower()
        return " ".join(text.split())

    def seen(self, phrase: str) -> bool:
        text = self.screen_text()
        return phrase.lower() in text or phrase.lower().replace(" ", "") in text.replace(" ", "")

    def quiet_for(self, seconds: float, limit: float) -> bool:
        """Wait until no output came for `seconds` (at most `limit`)."""
        end = time.monotonic() + limit
        last, since = self.bytes_read, time.monotonic()
        while time.monotonic() < end:
            time.sleep(self.POLL_S)
            if self.bytes_read != last:
                last, since = self.bytes_read, time.monotonic()
            elif time.monotonic() - since >= seconds:
                return True
        return False

    def alive(self) -> bool:
        return self.proc.poll() is None

    def wait(self, timeout: float) -> int | None:
        try:
            return self.proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            return None

    def close(self, grace: float = 5.0) -> int | None:
        """SIGTERM to the process group, SIGKILL after `grace` seconds."""
        if self.alive():
            _signal_group(self.proc, signal.SIGTERM)
            if self.wait(grace) is None:
                _signal_group(self.proc, signal.SIGKILL)
                self.wait(5)
        self._reader.join(timeout=2)
        try:
            os.close(self.master)
        except OSError:
            pass
        return self.proc.returncode


def _controlling_tty() -> None:
    # After setsid (start_new_session), make the pseudo-terminal the controlling terminal.
    try:
        fcntl.ioctl(0, termios.TIOCSCTTY, 0)
    except OSError:
        pass


def _signal_group(proc: subprocess.Popen, sig: int) -> None:
    try:
        os.killpg(proc.pid, sig)
    except (ProcessLookupError, PermissionError):
        pass
