# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The device's cache, state/device.json: the firmware detected last, its raw pairs and when, and the apps learned on it
with the build they were learned on. Written atomically like gate.json; read at start, so flipper_firmware answers at
once after a restart while a new detection runs.

Everything in it came from the Flipper: keys, values and app names are untrusted text, cleaned and bounded where they
enter (here) and again when the file is read back.

    {"v": 1,
     "firmware": {"origin": "Momentum", "version": "mntm-dev", "build_date": "01-11-2026", "api": "87.1",
                  "branch": "dev", "commit": "0a1b2c3d", "hardware_name": "Demo", "hardware_model": "Flipper Zero"},
     "raw": {"firmware_origin_fork": "Momentum", ...},
     "detected_at": 1761990000.0,
     "apps": {"build": "Momentum/mntm-dev/0a1b2c3d", "learned_at": 1761990010.0,
              "categories": {"Games": ["snake_game.fap"]}}}
"""
from __future__ import annotations

import json
import logging
import math
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable

from .gate import write_atomic
from .text import clean_text

log = logging.getLogger(__name__)

VERSION = 1
KEY_LIMIT = 64
VALUE_LIMIT = 64
MAX_PAIRS = 100
NAME_LIMIT = 64
MAX_APPS = 100  # per category
MAX_CATEGORIES = 64  # folders: the categories and their subfolders ("GPIO/ESP"), as firmware.MAX_FOLDERS

# The fields kept from the pairs, by their key with "_" as the separator, the first one found. Most keys read the same
# in both requests' forms ("firmware.origin.fork" and "firmware_origin_fork"), but the property request names the
# commit and the branch "firmware.commit.hash" and "firmware.branch.name", where the device info request has
# "firmware_commit" and "firmware_branch". "api" is made of two keys.
FIELDS = {
    "origin": ("firmware_origin_fork",),
    "version": ("firmware_version",),
    "build_date": ("firmware_build_date",),
    "branch": ("firmware_branch", "firmware_branch_name"),
    "commit": ("firmware_commit", "firmware_commit_hash"),
    "hardware_name": ("hardware_name",),
    "hardware_model": ("hardware_model",),
}
FIRMWARE_FIELDS = ("origin", "version", "build_date", "api", "branch", "commit", "hardware_name", "hardware_model")


def clean_key(key: Any) -> str | None:
    """A pair's key from the Flipper: printable, bounded, lower case, "_" as the separator, without "devinfo"."""
    text = clean_text(key, KEY_LIMIT)
    if text is None:
        return None
    text = text.lower().replace(".", "_")
    for prefix in ("devinfo_", "device_info_"):
        if text.startswith(prefix) and len(text) > len(prefix):
            text = text[len(prefix):]
    return text


def clean_pairs(pairs: Iterable[tuple[Any, Any]]) -> dict[str, str]:
    """Keys and values cleaned and bounded, at most MAX_PAIRS; a pair without a key or a value is dropped."""
    out: dict[str, str] = {}
    for key, value in pairs:
        k, v = clean_key(key), clean_text(value, VALUE_LIMIT)
        if k is None or v is None or (k not in out and len(out) >= MAX_PAIRS):
            continue
        out[k] = v
    return out


def firmware_of(raw: dict[str, str]) -> dict[str, str]:
    """The kept fields of clean pairs; a field the Flipper did not send is left out."""
    fw = {name: next(raw[k] for k in keys if k in raw) for name, keys in FIELDS.items() if any(k in raw for k in keys)}
    major, minor = raw.get("firmware_api_major"), raw.get("firmware_api_minor")
    if major is not None:
        fw["api"] = f"{major}.{minor}" if minor is not None else major
    return {name: fw[name] for name in FIRMWARE_FIELDS if name in fw}


def build_of(firmware: dict[str, str]) -> str:
    """What tells one build from another: the learned apps are kept per build."""
    return "/".join(firmware.get(k) or "?" for k in ("origin", "version", "commit"))


def clean_names(names: Iterable[Any]) -> list[str]:
    """App file names from the Flipper: cleaned, cut, without duplicates, sorted, at most MAX_APPS."""
    out = sorted({n for n in (clean_text(name, NAME_LIMIT) for name in names) if n is not None})
    return out[:MAX_APPS]


@dataclass
class Apps:
    build: str
    learned_at: float
    categories: dict[str, list[str]] = field(default_factory=dict)

    def count(self) -> int:
        return sum(len(v) for v in self.categories.values())


@dataclass
class Snapshot:
    firmware: dict[str, str]
    raw: dict[str, str]
    detected_at: float
    apps: Apps | None = None

    def build(self) -> str:
        return build_of(self.firmware)

    def to_json(self) -> dict[str, Any]:
        data: dict[str, Any] = {"v": VERSION, "firmware": self.firmware, "raw": self.raw,
                                "detected_at": self.detected_at}
        if self.apps is not None:
            data["apps"] = {"build": self.apps.build, "learned_at": self.apps.learned_at,
                            "categories": self.apps.categories}
        return data


def _time(value: Any) -> float | None:
    if isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and value >= 0:
        return float(value)
    return None


def _strings(value: Any) -> dict[str, str]:
    return clean_pairs(value.items()) if isinstance(value, dict) else {}


def parse(data: Any) -> Snapshot | None:
    """A snapshot from device.json's content, cleaned again; None when it is not one."""
    if not isinstance(data, dict) or data.get("v") != VERSION:
        return None
    detected_at = _time(data.get("detected_at"))
    if detected_at is None or not isinstance(data.get("firmware"), dict):
        return None
    firmware = {k: v for k, v in _strings(data["firmware"]).items() if k in FIRMWARE_FIELDS}
    apps = None
    raw_apps = data.get("apps")
    if isinstance(raw_apps, dict):
        build, learned_at, categories = (clean_text(raw_apps.get("build"), 3 * VALUE_LIMIT + 2),
                                         _time(raw_apps.get("learned_at")), raw_apps.get("categories"))
        if build is not None and learned_at is not None and isinstance(categories, dict):
            apps = Apps(build, learned_at, {
                c: clean_names(names) for c, names in list(categories.items())[:MAX_CATEGORIES]
                if isinstance(c, str) and clean_text(c, NAME_LIMIT) == c and isinstance(names, list)})
    return Snapshot(firmware, _strings(data.get("raw")), detected_at, apps)


class DeviceCache:
    """device.json in memory and on disk. Without a path (tests), it lives in memory only."""

    def __init__(self, path: Path | None = None) -> None:
        self.path = path
        self.snapshot: Snapshot | None = self._load()

    def _load(self) -> Snapshot | None:
        if self.path is None:
            return None
        try:
            return parse(json.loads(self.path.read_text()))
        except (OSError, ValueError):
            return None

    def save(self, snapshot: Snapshot) -> None:
        self.snapshot = snapshot
        if self.path is None:
            return
        try:
            write_atomic(self.path, json.dumps(snapshot.to_json(), ensure_ascii=False).encode())
        except OSError as e:  # the cache still holds in memory
            log.warning("Could not save the device cache to %s: %s", self.path, e)
