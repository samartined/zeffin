# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The firmware profiles: one Markdown file per firmware for the agent, shipped as package data in
bridge/profile_data/, and index.json, which maps the detected firmware origin to a file.

    {"default": "official", "base": "base.md",
     "profiles": {"official": {"file": "official.md", "origins": ["official"], "app_categories": ["Games", ...],
                               "catalog": "official.json"}}}

The origin the Flipper reports (firmware.origin.fork) is stripped and lower-cased and must equal one of a profile's
origins; a missing or unknown origin gets the default. The profiles are repository data, not text from the device;
app_categories are the /ext/apps/<Category> folders whose apps the server learns (and their subfolders), and catalog
is the profile's app catalog in profile_data/apps/ (catalog.py). base is what every Flipper shares: flipper_firmware
gives it before the firmware's own profile.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from functools import lru_cache
from importlib import resources
from importlib.resources.abc import Traversable
from typing import Any

NAME = re.compile(r"[a-z0-9][a-z0-9_-]{0,31}")
FILE = re.compile(r"[a-z0-9][a-z0-9_-]{0,31}\.md")
CATALOG = re.compile(r"[a-z0-9][a-z0-9_-]{0,31}\.json")
CATEGORY = re.compile(r"[A-Za-z0-9][A-Za-z0-9 _-]{0,31}")

MATCHED, UNKNOWN, MISSING = "matched", "unknown", "missing"  # how the profile was chosen


class ProfileIndexError(ValueError):
    """index.json does not have the expected form (a bug in the repository, caught by the tests)."""


@dataclass(frozen=True)
class Profile:
    name: str
    file: str
    origins: tuple[str, ...]
    app_categories: tuple[str, ...]
    catalog: str | None = None  # the file in profile_data/apps/


@dataclass(frozen=True)
class Index:
    default: str
    profiles: dict[str, Profile]
    base: str = "base.md"


@dataclass(frozen=True)
class Choice:
    profile: Profile
    how: str  # MATCHED, UNKNOWN (an origin the index does not know) or MISSING (no origin reported)

    @property
    def default(self) -> bool:
        return self.how != MATCHED


def _profile(name: str, data: Any) -> Profile:
    if not NAME.fullmatch(name) or not isinstance(data, dict):
        raise ProfileIndexError(f"profile {name!r}")
    file, origins, categories = data.get("file"), data.get("origins"), data.get("app_categories")
    if not isinstance(file, str) or not FILE.fullmatch(file):
        raise ProfileIndexError(f"profile {name!r}: file")
    if not isinstance(origins, list) or not all(isinstance(o, str) and o == o.strip().lower() and o for o in origins):
        raise ProfileIndexError(f"profile {name!r}: origins must be lower-case strings")
    if not isinstance(categories, list) or not all(isinstance(c, str) and CATEGORY.fullmatch(c) for c in categories):
        raise ProfileIndexError(f"profile {name!r}: app_categories")
    catalog = data.get("catalog")
    if catalog is not None and (not isinstance(catalog, str) or not CATALOG.fullmatch(catalog)):
        raise ProfileIndexError(f"profile {name!r}: catalog")
    return Profile(name, file, tuple(origins), tuple(categories), catalog)


def parse_index(data: Any) -> Index:
    if not isinstance(data, dict) or not isinstance(data.get("profiles"), dict):
        raise ProfileIndexError("index.json must hold profiles")
    profiles = {name: _profile(name, p) for name, p in data["profiles"].items()}
    default = data.get("default")
    if default not in profiles:
        raise ProfileIndexError("the default must be one of the profiles")
    base = data.get("base", "base.md")
    if not isinstance(base, str) or not FILE.fullmatch(base) or base in {p.file for p in profiles.values()}:
        raise ProfileIndexError("base must be a profile file of its own")
    seen: set[str] = set()
    for p in profiles.values():
        if seen & set(p.origins):
            raise ProfileIndexError(f"an origin of {p.name!r} is listed twice")
        seen |= set(p.origins)
    return Index(default, profiles, base)


def _data() -> Traversable:
    """bridge/profile_data/, a folder of package data (not a package: the module is bridge.profiles)."""
    return resources.files("bridge").joinpath("profile_data")


@lru_cache(maxsize=1)
def load_index() -> Index:
    return parse_index(json.loads(_data().joinpath("index.json").read_text(encoding="utf-8")))


def choose(origin: str | None, index: Index | None = None) -> Choice:
    """The profile for a detected origin (untrusted text: only compared, never used as a path)."""
    index = index or load_index()
    key = origin.strip().lower() if isinstance(origin, str) else ""
    if key:
        for p in index.profiles.values():
            if key in p.origins:
                return Choice(p, MATCHED)
    return Choice(index.profiles[index.default], UNKNOWN if key else MISSING)


def text(profile: Profile) -> str:
    return _data().joinpath(profile.file).read_text(encoding="utf-8")


def base_text(index: Index | None = None) -> str:
    """The base profile: what every Flipper shares, given before the firmware's own."""
    return _data().joinpath((index or load_index()).base).read_text(encoding="utf-8")
