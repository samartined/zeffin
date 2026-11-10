# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""Notes per app: what an earlier conversation on this server found out about driving one app of the Flipper,
in the agent's own words, kept as state/notes/<origin>/<appid>.json, one file per note:

    {"v": 1, "appid": "flipper_elements", "origin": "momentum", "text": "...", "written_at": 1762790000.0,
     "build": "Momentum/mntm-dev/0a1b2c3d"}

A note is keyed by the firmware's origin, reduced to [a-z0-9-] (origin_key), and the app's appid; the build it was
written on is recorded, not keyed. Its text came from the agent after it read untrusted screens, so it is untrusted:
cleaned and bounded where it enters (clean_note, refused past the limits rather than cut), and cleaned again when a
file is read back, as device.json is. Each file is written atomically, mode 600, in folders of mode 700, so `zf notes
delete` can remove one while the server writes another. Without a root (tests), the notes live in memory only.
"""
from __future__ import annotations

import json
import logging
import math
import os
import re
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from . import catalog, profiles
from .catalog import APPID
from .devicecache import Snapshot
from .gate import write_atomic
from .text import clean_text

log = logging.getLogger(__name__)

VERSION = 1
MAX_CHARS = 1000      # a note's characters, after cleaning
MAX_LINES = 12
MAX_PER_ORIGIN = 200  # notes per firmware origin
MAX_TOTAL = 500       # notes in all, whatever the origin
ORIGIN_LIMIT = 32
BUILD_LIMIT = 3 * 64 + 2  # devicecache.build_of: three fields of at most 64 characters
UNKNOWN = "unknown"

_NOT_ORIGIN = re.compile(r"[^a-z0-9]+")
_ORIGIN = re.compile(r"[a-z0-9](?:[a-z0-9-]{0,31})")
# Tags that could open or close one of the blocks the tools hand to the agent: the untrusted <flipper_data> and the
# repository's <catalog>, <profile> and <app> (tools.sanitize and tools.no_repo_tags, which run again on the way out).
_TAG = re.compile(r"</?(flipper_data|catalog|profile|app)\b", re.IGNORECASE)


class NoteError(Exception):
    """A note that cannot be kept: its text, its appid, or a limit. The message says which, for the agent."""


def origin_key(origin: Any) -> str:
    """The firmware's origin as a folder name: lower case, runs of anything but [a-z0-9] as one "-", at most 32
    characters; "unknown" when nothing is left. It is device text, so it becomes a path only after this."""
    text = clean_text(origin, 256) if isinstance(origin, str) else None
    key = _NOT_ORIGIN.sub("-", (text or "").lower()).strip("-")[:ORIGIN_LIMIT].strip("-")
    return key or UNKNOWN


def _escape(ch: str) -> str:
    return f"\\u{ord(ch):04x}"


def clean_note(text: Any) -> str:
    """A note's text, cleaned: line ends as \\n, tabs as spaces, trailing spaces dropped, every other control, bidi or
    format character written as \\uXXXX, and the blocks' tags escaped. Refused (NoteError) when empty or past
    MAX_CHARS or MAX_LINES: a cut note could lose its exit."""
    if not isinstance(text, str):
        raise NoteError("text must be a string")
    text = text.replace("\r\n", "\n").replace("\r", "\n").replace("\t", " ")
    text = "".join(ch if ch == "\n" or ch.isprintable() else _escape(ch) for ch in text)
    text = _TAG.sub(lambda m: m.group().replace("<", "<\\", 1), text)
    text = "\n".join(line.rstrip() for line in text.strip().split("\n"))
    if not text:
        raise NoteError("The note is empty: write what you found out about driving the app.")
    if len(text) > MAX_CHARS:
        raise NoteError(f"The note has {len(text)} characters, above {MAX_CHARS}: write it shorter. Nothing was saved.")
    if text.count("\n") + 1 > MAX_LINES:
        raise NoteError(f"The note has {text.count(chr(10)) + 1} lines, above {MAX_LINES}: write it shorter. Nothing "
                        "was saved.")
    return text


def check_appid(appid: Any) -> str:
    if not isinstance(appid, str) or not APPID.fullmatch(appid):
        raise NoteError("appid must be 1-64 characters of a-z, 0-9 and _: the .fap file's name without .fap")
    return appid


@dataclass(frozen=True)
class Note:
    origin: str
    appid: str
    text: str
    written_at: float
    build: str | None = None

    def to_json(self) -> dict[str, Any]:
        return {"v": VERSION, "appid": self.appid, "origin": self.origin, "text": self.text,
                "written_at": self.written_at, "build": self.build}


def _time(value: Any) -> float | None:
    if isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and value >= 0:
        return float(value)
    return None


def parse(data: Any, origin: str, appid: str) -> Note | None:
    """A note from its file's content, cleaned again; None when it is not one, or names another origin or appid than
    its path, or its text is past the limits (a file edited by hand)."""
    if not isinstance(data, dict) or data.get("v") != VERSION:
        return None
    if data.get("origin") != origin or data.get("appid") != appid:
        return None
    written_at = _time(data.get("written_at"))
    if written_at is None:
        return None
    try:
        text = clean_note(data.get("text"))
    except NoteError:
        return None
    build = clean_text(data.get("build"), BUILD_LIMIT)
    return Note(origin, appid, text, written_at, build)


class NoteStore:
    """The notes on disk under `root` (state/notes), or in memory without one. Every read goes to disk, so a note
    `zf notes` deleted is gone at once for the server too."""

    def __init__(self, root: Path | None = None) -> None:
        self.root = root
        self._memory: dict[tuple[str, str], Note] = {}

    def _path(self, origin: str, appid: str) -> Path:
        assert self.root is not None and _ORIGIN.fullmatch(origin) and APPID.fullmatch(appid)
        return self.root / origin / f"{appid}.json"

    def get(self, origin: str, appid: str) -> Note | None:
        if not _ORIGIN.fullmatch(origin) or not APPID.fullmatch(appid):
            return None
        if self.root is None:
            return self._memory.get((origin, appid))
        try:
            return parse(json.loads(self._path(origin, appid).read_text(encoding="utf-8")), origin, appid)
        except (OSError, ValueError):
            return None

    def _keys(self) -> list[tuple[str, str]]:
        """(origin, appid) of every file that may hold a note, sorted; parsing is left to get()."""
        if self.root is None:
            return sorted(self._memory)
        keys: list[tuple[str, str]] = []
        try:
            folders = sorted(p for p in self.root.iterdir() if p.is_dir() and _ORIGIN.fullmatch(p.name))
        except OSError:
            return []
        for folder in folders:
            try:
                files = sorted(folder.iterdir())
            except OSError:
                continue
            keys += [(folder.name, f.stem) for f in files
                     if f.suffix == ".json" and APPID.fullmatch(f.stem) and f.is_file()]
        return keys

    def list(self, origin: str | None = None) -> list[Note]:
        """Every readable note, by origin and appid; only one origin's when given."""
        found = (self.get(o, a) for o, a in self._keys() if origin is None or o == origin)
        return [n for n in found if n is not None]

    def write(self, origin: str, appid: str, text: str, build: str | None) -> tuple[Note, Note | None]:
        """Keep a note, replacing the app's note whole. Returns (the new note, the one it replaced). Raises NoteError
        for a bad text or appid, or when a new app's note would pass MAX_PER_ORIGIN or MAX_TOTAL; replacing one
        always works."""
        check_appid(appid)
        if not _ORIGIN.fullmatch(origin):
            raise NoteError("unknown firmware origin")
        clean = clean_note(text)
        previous = self.get(origin, appid)
        # A file that no longer parses (edited by hand) still holds the app's place: replacing it is no new note.
        exists = previous is not None or (self.root is not None and self._path(origin, appid).exists())
        if not exists:
            keys = self._keys()
            if sum(1 for o, _ in keys if o == origin) >= MAX_PER_ORIGIN:
                raise NoteError(f"This firmware already has {MAX_PER_ORIGIN} notes, the most it keeps; a new app's note "
                                "is refused, and replacing one still works. The owner can delete notes with "
                                "`zf notes delete`.")
            if len(keys) >= MAX_TOTAL:
                raise NoteError(f"This server already keeps {MAX_TOTAL} notes, the most it keeps; a new app's note is "
                                "refused, and replacing one still works. The owner can delete notes with "
                                "`zf notes delete`.")
        note = Note(origin, appid, clean, round(time.time(), 3), clean_text(build, BUILD_LIMIT))
        if self.root is None:
            self._memory[(origin, appid)] = note
            return note, previous
        path = self._path(origin, appid)
        for folder in (self.root, path.parent):
            folder.mkdir(mode=0o700, parents=True, exist_ok=True)
            os.chmod(folder, 0o700)
        write_atomic(path, json.dumps(note.to_json(), ensure_ascii=False).encode())
        return note, previous

    def delete(self, origin: str, appid: str) -> bool:
        """Remove one note; False when there was none."""
        if not _ORIGIN.fullmatch(origin) or not APPID.fullmatch(appid):
            return False
        if self.root is None:
            return self._memory.pop((origin, appid), None) is not None
        try:
            self._path(origin, appid).unlink()
        except FileNotFoundError:
            return False
        return True

    def delete_all(self) -> int:
        """Remove every note; returns how many files went."""
        return sum(self.delete(o, a) for o, a in self._keys())


def current(snapshot: Snapshot | None) -> tuple[str, str] | None:
    """(origin key, build) of the firmware the Flipper ran last (device.json), or None when none is known."""
    if snapshot is None:
        return None
    return origin_key(snapshot.firmware.get("origin")), snapshot.build()


def installed(snapshot: Snapshot | None) -> set[str]:
    """The appids on the Flipper as last learned: its .fap files by name, and the built-in apps of its firmware's
    catalog."""
    if snapshot is None:
        return set()
    found = {a for names in (snapshot.apps.categories.values() if snapshot.apps else ())
             for a in (catalog.appid_of(n) for n in names) if a is not None}
    profile = profiles.choose(snapshot.firmware.get("origin")).profile
    if profile.catalog is not None:
        found |= set(catalog.load(profile.catalog).builtin)
    return found
