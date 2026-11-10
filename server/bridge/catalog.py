# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The app catalogs: profile_data/apps/<origin>.json, built by scripts/build_app_catalog.py from a firmware's public app
sources and shipped as package data. Each profile in index.json names its catalog.

    {"format": 1, "origin": "momentum", "built_at": "2026-11-02",
     "source": {"repository": "...", "branch": "dev", "version": "mntm-dev", "commit": "d3f89df",
                "date": "2026-08-18"},
     "apps_source": {"repository": "...", "commit": "55a446b", "date": "2026-08-18"},
     "apps": {"esp_flasher": {"name": "[ESP] ESP Flasher", "category": "GPIO/ESP",
                              "path": "/ext/apps/GPIO/ESP/esp_flasher.fap", "summary": "...",
                              "summary_source": "manifest", "capabilities": ["ext_hw"], ...}},
     "builtin": {"archive": {"name": "Archive", "summary": "...", "builtin": true, ...},
                 "about": {"name": "About", "path": "/ext/apps/assets/about.fap", "builtin": true, ...}}}

A built-in app is the firmware's own: compiled in (no path), or a .fap file the firmware ships outside the app
catalog's sources, such as Momentum's settings apps in /ext/apps/assets/ (a path, matched by appid like the others).

The catalog is repository data, but its names and the summaries whose summary_source is "manifest" were written by
each app's author: the script cleans and caps them, this module checks them again on load, and the tools label them
as descriptions, never instructions. An installed app is matched by its appid: the .fap file's name without ".fap".

An app's README is read only from a local source tree the configuration names (firmware_sources), at the catalog's
own source_dir, never from a path that came from the device or the agent, and only if it stays inside the tree.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from functools import lru_cache
from importlib import resources
from importlib.resources.abc import Traversable
from pathlib import Path
from typing import Any

FORMAT = 1
APPS_ROOT = "/ext/apps"
APPID = re.compile(r"[a-z0-9_]{1,64}")
FILE = re.compile(r"[a-z0-9][a-z0-9_-]{0,31}\.json")
BUILTIN_PATH = re.compile(r"/ext/apps/[A-Za-z0-9][A-Za-z0-9 _-]{0,31}/([a-z0-9_]{1,64})\.fap")
SOURCE_DIR = re.compile(r"[A-Za-z0-9_.+ -]{1,128}(/[A-Za-z0-9_.+ -]{1,128}){0,7}")
CAPABILITIES = ("subghz_tx", "ir_tx", "nfc_emulate_write", "rfid_emulate_write", "hid", "ble_adv", "ext_hw",
                "writes_files")
SUMMARY_SOURCES = ("manifest", "ours", "none")
NAME_LIMIT, SUMMARY_LIMIT, FIELD_LIMIT, URL_LIMIT = 64, 200, 64, 200
README_LIMIT = 16 * 1024


class CatalogError(ValueError):
    """A catalog file does not have the expected form (a bug in the repository, caught by the tests)."""


class NoReadme(Exception):
    """Why an app's README cannot be read: the message says it in one line."""


@dataclass(frozen=True)
class Catalog:
    file: str
    origin: str
    built_at: str
    source: dict[str, str]
    apps_source: dict[str, str]
    apps: dict[str, dict[str, Any]]
    builtin: dict[str, dict[str, Any]]
    capabilities_note: str
    text_note: str

    def entry(self, appid: str) -> dict[str, Any] | None:
        return self.apps.get(appid) or self.builtin.get(appid)

    def version(self) -> str | None:
        """The firmware.version a build of the catalog's source reports: "version" when the source names it (a
        branch, such as Momentum's dev, whose builds report mntm-dev), else the release tag."""
        return self.source.get("version") or self.source.get("tag")

    def built_from(self) -> str:
        """For example "Momentum-Firmware branch dev (d3f89df, 2026-08-18); apps Momentum-Apps 55a446b (2026-08-18)"."""
        def one(s: dict[str, str]) -> str:
            name = s.get("repository", "?").rstrip("/").rsplit("/", 1)[-1]
            ref = s.get("tag") or (f"branch {s['branch']}" if s.get("branch") else None) or s.get("commit") or "?"
            named = s.get("tag") or s.get("branch")
            extra = ", ".join(v for v in (s.get("commit") if named else None, s.get("date")) if v)
            return f"{name} {ref}" + (f" ({extra})" if extra else "")
        text = one(self.source)
        if self.apps_source:
            text += f"; apps {one(self.apps_source)}"
        return text


def _clean(value: Any, limit: int) -> bool:
    return isinstance(value, str) and 0 < len(value) <= limit and value.isprintable() and value == value.strip()


def _optional(value: Any, limit: int) -> bool:
    return value is None or _clean(value, limit)


def _check_app(appid: str, e: Any, builtin: bool) -> dict[str, Any]:
    where = f"{'built-in ' if builtin else ''}app {appid!r}"
    if not APPID.fullmatch(appid) or not isinstance(e, dict):
        raise CatalogError(where)
    if not _clean(e.get("name"), NAME_LIMIT) or not _clean(e.get("summary"), SUMMARY_LIMIT):
        raise CatalogError(f"{where}: name and summary must be clean single lines within their caps")
    if e.get("summary_source") not in SUMMARY_SOURCES:
        raise CatalogError(f"{where}: summary_source")
    caps = e.get("capabilities")
    if not isinstance(caps, list) or any(c not in CAPABILITIES for c in caps):
        raise CatalogError(f"{where}: capabilities")
    if builtin:
        if e.get("builtin") is not True:
            raise CatalogError(f"{where}: a built-in app has builtin: true")
        if "path" in e and not builtin_path(appid, e["path"]):
            raise CatalogError(f"{where}: a built-in app's path must be /ext/apps/<folder>/<appid>.fap")
        return e
    category = e.get("category")
    if not isinstance(category, str) or e.get("path") != f"/ext/apps/{category}/{appid}.fap":
        raise CatalogError(f"{where}: path must be /ext/apps/<category>/<appid>.fap")
    for field, limit in (("author", FIELD_LIMIT), ("version", FIELD_LIMIT), ("source_url", URL_LIMIT)):
        if not _optional(e.get(field), limit):
            raise CatalogError(f"{where}: {field}")
    source_dir = e.get("source_dir")
    if source_dir is not None and (not isinstance(source_dir, str) or not SOURCE_DIR.fullmatch(source_dir)
                                   or any(part in (".", "..") for part in source_dir.split("/"))):
        raise CatalogError(f"{where}: source_dir must be a relative path inside the source tree")
    if not isinstance(e.get("capabilities_searched"), bool):
        raise CatalogError(f"{where}: capabilities_searched")
    return e


def builtin_path(appid: str, path: Any) -> bool:
    """A built-in app's path: its own .fap file in one folder of /ext/apps."""
    match = BUILTIN_PATH.fullmatch(path) if isinstance(path, str) else None
    return match is not None and match.group(1) == appid


def _source(value: Any, name: str) -> dict[str, str]:
    if not isinstance(value, dict) or not all(isinstance(v, str) and _clean(v, URL_LIMIT) for v in value.values()):
        raise CatalogError(name)
    return dict(value)


def parse(file: str, data: Any) -> Catalog:
    if not isinstance(data, dict) or data.get("format") != FORMAT:
        raise CatalogError(f"{file}: format {FORMAT} expected")
    apps, builtin = data.get("apps"), data.get("builtin", {})
    if not isinstance(apps, dict) or not isinstance(builtin, dict):
        raise CatalogError(f"{file}: apps and builtin must be objects")
    source = _source(data.get("source"), f"{file}: source")
    if not source.get("repository"):
        raise CatalogError(f"{file}: source needs a repository")
    return Catalog(
        file=file,
        origin=str(data.get("origin")),
        built_at=str(data.get("built_at")),
        source=source,
        apps_source=_source(data.get("apps_source", {}), f"{file}: apps_source"),
        apps={k: _check_app(k, v, False) for k, v in apps.items()},
        builtin={k: _check_app(k, v, True) for k, v in builtin.items()},
        capabilities_note=str(data.get("capabilities_note", "")),
        text_note=str(data.get("text_note", "")),
    )


def _folder() -> Traversable:
    return resources.files("bridge").joinpath("profile_data").joinpath("apps")


@lru_cache(maxsize=8)
def load(file: str) -> Catalog:
    if not FILE.fullmatch(file):
        raise CatalogError(f"catalog file {file!r}")
    return parse(file, json.loads(_folder().joinpath(file).read_text(encoding="utf-8")))


def appid_of(name: str) -> str | None:
    """The appid of an installed file name from the device (untrusted): "esp_flasher.fap" -> "esp_flasher"."""
    if not isinstance(name, str) or not name.lower().endswith(".fap"):
        return None
    appid = name[:-4]
    return appid if APPID.fullmatch(appid) else None


def ext_hw(entry: dict[str, Any]) -> bool:
    """Needs external hardware: its code drives the GPIO header, or it sits in a GPIO subfolder (ESP, NRF24...)."""
    return "ext_hw" in entry.get("capabilities", []) or str(entry.get("category", "")).startswith("GPIO/")


def readme(tree: str | None, origin: str, entry: dict[str, Any]) -> tuple[str, bool]:
    """(the README's text, whether it was cut) from <tree>/<source_dir>/README.md; NoReadme says why not.
    The text is untrusted: the caller cleans and wraps it."""
    source_dir = entry.get("source_dir")
    if entry.get("builtin") or not source_dir:
        raise NoReadme("No README: this app's sources are not in a source tree the catalog knows.")
    if not tree:
        raise NoReadme(f"No README: no local source tree for {origin} (zf set-firmware-sources {origin} <path>).")
    root = Path(tree).resolve()
    if not root.is_dir():
        raise NoReadme(f"No README: the source tree for {origin} is not a folder on this server.")
    folder = (root / source_dir).resolve()
    if not folder.is_relative_to(root) or not folder.is_dir():
        raise NoReadme(f"No README: {source_dir} is not inside the source tree for {origin}.")
    candidates = sorted(p for p in folder.iterdir() if p.name.lower() == "readme.md")
    for path in candidates:
        real = path.resolve()
        if not real.is_relative_to(root) or not real.is_file():
            continue
        with real.open("rb") as f:
            data = f.read(README_LIMIT + 1)
        cut = len(data) > README_LIMIT
        return data[:README_LIMIT].decode("utf-8", errors="replace"), cut
    raise NoReadme(f"No README in {source_dir}.")
