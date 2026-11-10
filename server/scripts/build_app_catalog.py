# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""Build a firmware's app catalog for zeffin: profile_data/apps/<origin>.json.

Run with `python3 -I`; it uses the standard library only and imports nothing from bridge. It reads a local checkout
of a firmware's public app sources, never builds or runs anything from it, and treats every file as untrusted data:

- every `application.fam` is parsed with `ast` (never executed); the apps kept are EXTERNAL and MENUEXTERNAL ones
  with a `fap_category`, which the firmware installs as /ext/apps/<category>/<appid>.fap;
- a symbol search over each app's .c, .h, .cpp and .js files gives its capabilities (CAPABILITIES below): a hint
  with false positives, never a permission;
- the official firmware's catalog repository holds one manifest.yml per app that points at an outside repository:
  only the manifest's own fields are read (a small YAML subset), and no code is searched;
- the apps' names and descriptions were written by their authors: they are cleaned and capped like device text;
- summaries.json (our own lines, for short or missing descriptions) and builtin.json (the compiled-in apps, written
  by hand) sit next to the output.

The public projects it has been pointed at (the sources the shipped catalogs come from):
  Momentum:  github.com/Next-Flip/Momentum-Firmware (--firmware), its dev branch (a build of it reports
             mntm-dev), with its applications/external submodule, github.com/Next-Flip/Momentum-Apps, at the
             commit dev pins (--source);
  Unleashed: github.com/DarkFlippers/unleashed-firmware (--firmware) and its apps bundle
             github.com/xMasterX/all-the-plugins at the build tag the release names (--source, three folders);
  official:  github.com/flipperdevices/flipperzero-firmware (--firmware) and the catalog repository
             github.com/flipperdevices/flipper-application-catalog (--catalog-repo).

Example (Unleashed):
  python3 -I server/scripts/build_app_catalog.py --origin unleashed \\
      --firmware ~/src/unleashed-firmware --root ~/src/all-the-plugins \\
      --source ~/src/all-the-plugins/base_pack --source ~/src/all-the-plugins/apps_source_code \\
      --source ~/src/all-the-plugins/non_catalog_apps \\
      --repository https://github.com/DarkFlippers/unleashed-firmware --tag unlshd-094 --commit 2f62b3a \\
      --date 2026-10-14 --apps-repository https://github.com/xMasterX/all-the-plugins --apps-ref 14oct2026 \\
      --apps-commit 26a2120 --apps-date 2026-10-12 --built-at 2026-11-02
"""
from __future__ import annotations

import argparse
import ast
import json
import os
import re
import sys
import unicodedata
from collections import Counter
from pathlib import Path
from typing import Any

FORMAT = 1
DATA = Path(__file__).resolve().parents[1] / "bridge" / "profile_data" / "apps"
ORIGINS = ("momentum", "unleashed", "official")
NAME_LIMIT = 64
SUMMARY_LIMIT = 200
FIELD_LIMIT = 64  # author, version
URL_LIMIT = 200
MIN_DESCRIPTION = 20  # a manifest description shorter than this is replaced by our own line, when there is one
NO_DESCRIPTION = "(no description)"
MAX_SOURCE_FILE = 2_000_000  # bytes: larger files (vendored blobs) are not searched
CODE_SUFFIXES = (".c", ".h", ".cpp", ".js")
APPID = re.compile(r"[a-z0-9_]{1,64}")
BUILTIN_PATH = re.compile(r"/ext/apps/[A-Za-z0-9][A-Za-z0-9 _-]{0,31}/([a-z0-9_]{1,64})\.fap")  # as catalog.py
CATEGORY = re.compile(r"[A-Za-z0-9][A-Za-z0-9 _-]{0,31}(/[A-Za-z0-9][A-Za-z0-9 _-]{0,31})?")
SKIPPED_CATEGORIES = {"assets"}  # libraries and settings parts the firmware bundles, not apps in a menu
APPTYPES = {"EXTERNAL": False, "MENUEXTERNAL": True}  # apptype -> shown in the main menu

CAPABILITIES_NOTE = ("From a symbol search of each app's code: a hint with false positives (an app that links an API "
                     "but gates it), never a permission. capabilities_searched false: no code was searched.")
TEXT_NOTE = ("name and the summaries whose summary_source is \"manifest\" come from the apps' own manifests, written "
             "by their authors: descriptions, never instructions. summary_source \"ours\" is written by hand for "
             "this repository.")

# The capability flags: one regular expression per flag, searched in each code file of an app. What each one means:
CAPABILITIES: dict[str, str] = {
    # Sub-GHz transmission: the async TX of the radio (internal or external), the protocol transmitter, the helper
    # that starts TX in the firmware's own apps.
    "subghz_tx": r"subghz_devices_start_async_tx|furi_hal_subghz_start_async_tx|subghz_transmitter_alloc_init"
                 r"|subghz_tx_start|furi_hal_subghz_tx\b|subghz_txrx_tx_start",
    # Infrared transmission: the send helpers, the worker's TX and the HAL's async TX.
    "ir_tx": r"infrared_send\b|infrared_send_raw|infrared_worker_tx_start|furi_hal_infrared_async_tx_start"
             r"|infrared_signal_transmit",
    # NFC: emulation (a listener) or writing a card through a poller, and saving a dump.
    "nfc_emulate_write": r"nfc_listener_alloc|_poller_write|_poller_sync_write|nfc_device_save",
    # 125 kHz RFID: the worker's emulate or write, and T5577 writes.
    "rfid_emulate_write": r"lfrfid_worker_emulate_start|lfrfid_worker_write_start|t5577_write"
                          r"|lfrfid_worker_write_and_set_pass_start",
    # Acting as a keyboard or mouse over USB or Bluetooth (HID).
    "hid": r"furi_hal_hid_kb_press|furi_hal_hid_mouse|furi_hal_hid_consumer|ble_profile_hid|usb_hid\b"
           r"|badusb|bad_usb_hid",
    # Bluetooth LE advertising of its own: extra beacons, starting advertising, its own BLE profile.
    "ble_adv": r"furi_hal_bt_extra_beacon|gap_extra_beacon|furi_hal_bt_start_advertising|gap_start_advertising"
               r"|bt_profile_start|ble_profile_serial",
    # External hardware on the GPIO header: pins, serial, SPI, I2C, and the expansion port (C and JS).
    "ext_hw": r"furi_hal_gpio_init\(|furi_hal_serial_control_acquire|furi_hal_spi_acquire|furi_hal_i2c_acquire"
              r"|expansion_disable|require\(\s*[\"'](gpio|serial|spi|i2c)[\"']\s*\)",
    # Writes to the SD card: files opened for writing, Flipper Format files created, folders made, copies, renames.
    "writes_files": r"FSAM_WRITE|flipper_format_file_open_always|flipper_format_file_open_new|storage_simply_mkdir"
                    r"|storage_common_copy|storage_common_rename",
}
_CAPABILITIES = {name: re.compile(pattern) for name, pattern in CAPABILITIES.items()}

# Invisible and direction characters, besides the non-printable ones.
_INVISIBLE = re.compile("[\u061c\u180e\u200b-\u200f\u202a-\u202e\u2060-\u2069\ufeff]")


class Problem(Exception):
    """A manifest or a data file that cannot be read: reported, and the app skipped."""


def clean(value: Any, limit: int) -> str | None:
    """Third-party text, as device text is: one line, printable, no invisible or direction marks, capped."""
    if not isinstance(value, str):
        return None
    text = unicodedata.normalize("NFC", value)
    text = _INVISIBLE.sub("", text)
    text = "".join(ch if ch.isprintable() else " " for ch in text)
    text = " ".join(text.split())
    if len(text) > limit:
        text = text[:limit - 1].rstrip() + "…"
    return text or None


# ---------------------------------------------------------------- application.fam

def _value(node: ast.AST) -> Any:
    """A literal from a manifest; None for what is not one (a name, a call): nothing is evaluated."""
    if isinstance(node, ast.Attribute):
        return node.attr  # FlipperAppType.EXTERNAL -> "EXTERNAL"
    if isinstance(node, ast.BinOp) and isinstance(node.op, ast.Add):
        left, right = _value(node.left), _value(node.right)
        return left + right if isinstance(left, str) and isinstance(right, str) else None
    try:
        return ast.literal_eval(node)
    except (ValueError, TypeError, SyntaxError, MemoryError, RecursionError):
        return None


def read_fam(path: Path) -> list[dict[str, Any]]:
    """The App(...) calls of one manifest, as {field: literal}."""
    try:
        tree = ast.parse(path.read_text(encoding="utf-8", errors="replace"), filename=str(path))
    except (SyntaxError, ValueError, OSError) as e:
        raise Problem(f"{path}: {e}") from e
    apps = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "App":
            apps.append({k.arg: _value(k.value) for k in node.keywords if k.arg})
    return apps


def version_of(value: Any) -> str | None:
    if isinstance(value, (tuple, list)) and value and all(isinstance(v, int) for v in value):
        return ".".join(str(v) for v in value)
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return str(value)
    return clean(value, FIELD_LIMIT)


def code_files(folder: Path) -> list[Path]:
    out = []
    for dirpath, dirnames, filenames in os.walk(folder, followlinks=False):
        dirnames[:] = sorted(d for d in dirnames if not d.startswith("."))
        for name in sorted(filenames):
            p = Path(dirpath, name)
            if name.endswith(CODE_SUFFIXES) and not p.is_symlink():
                try:
                    if p.stat().st_size <= MAX_SOURCE_FILE:
                        out.append(p)
                except OSError:
                    continue
    return out


def capabilities_of(folder: Path) -> list[str]:
    found: set[str] = set()
    for p in code_files(folder):
        try:
            text = p.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for name, pattern in _CAPABILITIES.items():
            if name not in found and pattern.search(text):
                found.add(name)
        if len(found) == len(_CAPABILITIES):
            break
    return [name for name in CAPABILITIES if name in found]


def manifests(root: Path) -> list[Path]:
    out = []
    for dirpath, dirnames, filenames in os.walk(root, followlinks=False):
        dirnames[:] = sorted(d for d in dirnames if not d.startswith("."))
        if "application.fam" in filenames:
            out.append(Path(dirpath, "application.fam"))
    return out


# ---------------------------------------------------------------- the official catalog's manifest.yml

def _scalar(text: str) -> str:
    text = text.strip()
    if len(text) >= 2 and text[0] == text[-1] == '"':
        body = text[1:-1]
        return re.sub(r'\\(["\\nt/])', lambda m: {"n": "\n", "t": "\t"}.get(m.group(1), m.group(1)), body)
    if len(text) >= 2 and text[0] == text[-1] == "'":
        return text[1:-1].replace("''", "'")
    return re.sub(r"\s+#.*$", "", text)


def read_yaml(text: str) -> dict[str, Any]:
    """The subset manifest.yml uses: nested mappings by indentation, scalars (plain, quoted, | and > blocks), and
    lists of scalars. Anything else is skipped; nothing is evaluated."""
    lines = text.replace("\t", "    ").splitlines()
    root: dict[str, Any] = {}
    stack: list[tuple[int, Any]] = [(-1, root)]
    i = 0
    while i < len(lines):
        raw = lines[i]
        i += 1
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip())
        line = raw.strip()
        while stack and indent <= stack[-1][0]:
            stack.pop()
        if not stack:
            stack = [(-1, root)]
        parent = stack[-1][1]
        if line.startswith("- "):
            if isinstance(parent, list):
                parent.append(_scalar(line[2:]))
            continue
        key, sep, rest = line.partition(":")
        if not sep or not isinstance(parent, dict):
            continue
        key, rest = key.strip(), rest.strip()
        if rest in ("|", "|-", "|+", ">", ">-", ">+"):
            block = []
            while i < len(lines) and (not lines[i].strip() or len(lines[i]) - len(lines[i].lstrip()) > indent):
                block.append(lines[i].strip())
                i += 1
            joiner = "\n" if rest.startswith("|") else " "
            parent[key] = joiner.join(block).strip()
        elif rest:
            parent[key] = _scalar(rest)
        else:
            nxt = next((ln for ln in lines[i:] if ln.strip() and not ln.lstrip().startswith("#")), "")
            child: Any = [] if nxt.strip().startswith("- ") else {}
            parent[key] = child
            stack.append((indent, child))
    return root


# ---------------------------------------------------------------- the catalog

class Builder:
    def __init__(self, origin: str, root: Path | None, summaries: dict[str, str]) -> None:
        self.origin = origin
        self.root = root.resolve() if root else None
        self.summaries = summaries
        self.apps: dict[str, dict[str, Any]] = {}
        self.used_summaries: set[str] = set()
        self.problems: list[str] = []
        self.duplicates: list[str] = []
        self.skipped: Counter[str] = Counter()

    def _summary(self, appid: str, description: Any) -> tuple[str, str]:
        text = clean(description, SUMMARY_LIMIT)
        if text is not None and len(text) >= MIN_DESCRIPTION:
            return text, "manifest"
        if appid in self.summaries:
            self.used_summaries.add(appid)
            return self.summaries[appid], "ours"
        return NO_DESCRIPTION, "none"

    def _add(self, appid: str, entry: dict[str, Any], where: str) -> None:
        if appid in self.apps:
            self.duplicates.append(f"{appid} ({where})")
            return
        self.apps[appid] = entry

    def _source_dir(self, folder: Path) -> str | None:
        if self.root is None:
            return None
        try:
            return folder.resolve().relative_to(self.root).as_posix()
        except ValueError:
            return None

    def add_fam_tree(self, top: Path, *, exclude: tuple[str, ...] = ()) -> None:
        for fam in manifests(top):
            rel = fam.parent.relative_to(top).as_posix()
            if any(rel == e or rel.startswith(e + "/") for e in exclude):
                continue
            try:
                apps = read_fam(fam)
            except Problem as e:
                self.problems.append(str(e))
                continue
            for app in apps:
                self._add_fam_app(fam.parent, app)

    def _add_fam_app(self, folder: Path, app: dict[str, Any]) -> None:
        apptype = app.get("apptype")
        if apptype not in APPTYPES:
            return
        appid, category = app.get("appid"), app.get("fap_category")
        if not isinstance(category, str) or not category or category in SKIPPED_CATEGORIES:
            self.skipped["no app category"] += 1
            return
        if not isinstance(appid, str) or not APPID.fullmatch(appid):
            self.skipped["appid outside [a-z0-9_]{1,64}"] += 1
            return
        if not CATEGORY.fullmatch(category):
            self.skipped["category outside the path rule"] += 1
            return
        summary, summary_source = self._summary(appid, app.get("fap_description"))
        entry: dict[str, Any] = {
            "name": clean(app.get("name"), NAME_LIMIT) or appid,
            "category": category,
            "path": f"/ext/apps/{category}/{appid}.fap",
            "summary": summary,
            "summary_source": summary_source,
            "capabilities": capabilities_of(folder),
            "capabilities_searched": True,
            "author": clean(app.get("fap_author"), FIELD_LIMIT),
            "version": version_of(app.get("fap_version")),
            "source_url": clean(app.get("fap_weburl"), URL_LIMIT),
            "source_dir": self._source_dir(folder),
        }
        if APPTYPES[apptype]:
            entry["menu"] = True
        self._add(appid, entry, str(folder))

    def add_catalog_repo(self, top: Path) -> None:
        base = top / "applications"
        for category_dir in sorted(p for p in base.iterdir() if p.is_dir() and not p.name.startswith(".")):
            for app_dir in sorted(p for p in category_dir.iterdir() if p.is_dir() and not p.name.startswith(".")):
                path = app_dir / "manifest.yml"
                if not path.is_file() or path.is_symlink():
                    continue
                try:
                    data = read_yaml(path.read_text(encoding="utf-8", errors="replace"))
                except (OSError, ValueError, RecursionError) as e:
                    self.problems.append(f"{path}: {e}")
                    continue
                self._add_manifest(category_dir.name, app_dir.name, data)

    def _add_manifest(self, category: str, folder: str, data: dict[str, Any]) -> None:
        appid = data.get("id") if isinstance(data.get("id"), str) else folder
        if not APPID.fullmatch(appid):
            self.skipped["appid outside [a-z0-9_]{1,64}"] += 1
            return
        if not CATEGORY.fullmatch(category):
            self.skipped["category outside the path rule"] += 1
            return
        location = data.get("sourcecode", {})
        location = location.get("location", {}) if isinstance(location, dict) else {}
        origin = location.get("origin") if isinstance(location, dict) else None
        summary, summary_source = self._summary(appid, data.get("short_description"))
        self._add(appid, {
            "name": clean(data.get("name"), NAME_LIMIT) or appid,
            "category": category,
            "path": f"/ext/apps/{category}/{appid}.fap",
            "summary": summary,
            "summary_source": summary_source,
            "capabilities": [],
            "capabilities_searched": False,
            "author": clean(data.get("author"), FIELD_LIMIT),
            "version": clean(data.get("version"), FIELD_LIMIT),
            "source_url": clean(origin, URL_LIMIT),
            "source_dir": None,
        }, f"{category}/{folder}")


def load_summaries(path: Path) -> dict[str, str]:
    if not path.exists():
        return {}
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise Problem(f"{path}: must be an object of appid -> summary")
    out = {}
    for appid, text in data.items():
        line = clean(text, SUMMARY_LIMIT)
        if not APPID.fullmatch(appid) or line is None or line != text:
            raise Problem(f"{path}: {appid!r} must be an appid with one clean line of at most {SUMMARY_LIMIT} chars")
        out[appid] = line
    return out


def load_builtin(path: Path) -> dict[str, dict[str, Any]]:
    if not path.exists():
        return {}
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, list):
        raise Problem(f"{path}: must be a list of apps")
    out: dict[str, dict[str, Any]] = {}
    for item in data:
        appid = item.get("appid") if isinstance(item, dict) else None
        if not isinstance(appid, str) or not APPID.fullmatch(appid) or appid in out:
            raise Problem(f"{path}: an entry needs a unique appid matching [a-z0-9_]{{1,64}}")
        name, summary, caps = item.get("name"), item.get("summary"), item.get("capabilities", [])
        if clean(name, NAME_LIMIT) != name or clean(summary, SUMMARY_LIMIT) != summary:
            raise Problem(f"{path}: {appid}: name and summary must be clean single lines")
        if not isinstance(caps, list) or any(c not in CAPABILITIES for c in caps):
            raise Problem(f"{path}: {appid}: capabilities must come from {', '.join(CAPABILITIES)}")
        if item.get("builtin") is not True:
            raise Problem(f"{path}: {appid}: a built-in app has builtin: true")
        fap = item.get("path")
        match = BUILTIN_PATH.fullmatch(fap) if isinstance(fap, str) else None
        if fap is not None and (match is None or match.group(1) != appid):
            raise Problem(f"{path}: {appid}: a built-in app's path must be /ext/apps/<folder>/<appid>.fap")
        entry = {"name": name, "summary": summary, "summary_source": "ours",
                 "capabilities": [c for c in CAPABILITIES if c in caps], "builtin": True}
        if fap is not None:
            entry["path"] = fap
        if isinstance(item.get("where"), str):
            entry["where"] = item["where"]
        out[appid] = entry
    return out


def source_info(repository: str | None, tag: str | None, commit: str | None, date: str | None,
                branch: str | None = None, version: str | None = None) -> dict[str, str]:
    pairs = (("repository", repository), ("tag", tag), ("branch", branch), ("commit", commit), ("date", date),
             ("version", version))
    return {k: v for k, v in pairs if v}


def build(args: argparse.Namespace) -> tuple[dict[str, Any], dict[str, Any]]:
    data_dir = Path(args.data)
    summaries = load_summaries(data_dir / f"{args.origin}.summaries.json")
    builtin = load_builtin(data_dir / f"{args.origin}.builtin.json")
    b = Builder(args.origin, Path(args.root) if args.root else None, summaries)
    if args.firmware:
        b.add_fam_tree(Path(args.firmware) / "applications", exclude=("external",))
    for source in args.source or []:
        b.add_fam_tree(Path(source))
    if args.catalog_repo:
        b.add_catalog_repo(Path(args.catalog_repo))
    clash = sorted(set(builtin) & set(b.apps))
    if clash:
        b.problems.append(f"built-in appids also found as .fap apps (the .fap entry is kept): {', '.join(clash)}")
        builtin = {k: v for k, v in builtin.items() if k not in clash}
    catalog: dict[str, Any] = {
        "format": FORMAT,
        "origin": args.origin,
        "built_at": args.built_at,
        "source": source_info(args.repository, args.tag, args.commit, args.date, args.branch, args.reports_version),
        "capabilities_note": CAPABILITIES_NOTE,
        "text_note": TEXT_NOTE,
        "apps": dict(sorted(b.apps.items())),
        "builtin": dict(sorted(builtin.items())),
    }
    apps_source = source_info(args.apps_repository, args.apps_ref, args.apps_commit, args.apps_date)
    if apps_source:
        catalog["apps_source"] = apps_source
    sources = Counter(e["summary_source"] for e in b.apps.values())
    caps = Counter(c for e in b.apps.values() for c in e["capabilities"])
    report = {
        "apps": len(b.apps),
        "builtin": len(builtin),
        "summary_manifest": sources["manifest"],
        "summary_ours": sources["ours"],
        "no_description": sources["none"],
        "capabilities_not_searched": sum(1 for e in b.apps.values() if not e["capabilities_searched"]),
        "capabilities": {name: caps[name] for name in CAPABILITIES},
        "skipped": dict(sorted(b.skipped.items())),
        "duplicates": b.duplicates,
        "unused_summaries": sorted(set(summaries) - b.used_summaries),
        "problems": b.problems,
    }
    return catalog, report


def dumps(catalog: dict[str, Any]) -> str:
    return json.dumps(catalog, ensure_ascii=False, indent=1, sort_keys=True) + "\n"


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--origin", required=True, choices=ORIGINS, help="the firmware, as its index entry names it")
    p.add_argument("--firmware", help="the firmware checkout: its applications/ (without applications/external)")
    p.add_argument("--source", action="append", help="a folder of app sources (repeatable)")
    p.add_argument("--catalog-repo", help="a checkout of the official firmware's catalog repository")
    p.add_argument("--root", help="the checkout each app's source_dir is relative to (firmware_sources points there)")
    p.add_argument("--repository", required=True, help="the firmware's repository URL")
    p.add_argument("--tag", help="the firmware release tag the catalog is built from")
    p.add_argument("--branch", help="the firmware branch, when the catalog is built from one instead of a tag")
    p.add_argument("--reports-version", help="the firmware.version a build of this source reports (default: the "
                   "tag), which flipper_firmware compares with the detected one; for a branch, such as mntm-dev")
    p.add_argument("--commit", help="the firmware commit")
    p.add_argument("--date", help="the firmware commit's date (YYYY-MM-DD)")
    p.add_argument("--apps-repository", help="the apps' repository, when it is not the firmware's")
    p.add_argument("--apps-ref", help="the apps' tag or build tag")
    p.add_argument("--apps-commit", help="the apps' commit")
    p.add_argument("--apps-date", help="the apps' commit date (YYYY-MM-DD)")
    p.add_argument("--built-at", required=True, help="the date written in the catalog (YYYY-MM-DD)")
    p.add_argument("--data", default=str(DATA), help="the folder of the catalogs and their summaries and built-ins")
    p.add_argument("--out", help="where to write (default: <data>/<origin>.json); '-' prints it")
    args = p.parse_args(argv)
    if not (args.firmware or args.source or args.catalog_repo):
        p.error("give --firmware, --source or --catalog-repo")
    if not (args.tag or args.branch):
        p.error("give --tag or --branch")
    try:
        catalog, report = build(args)
    except (Problem, OSError, ValueError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    text = dumps(catalog)
    if args.out == "-":
        sys.stdout.write(text)
    else:
        out = Path(args.out) if args.out else Path(args.data) / f"{args.origin}.json"
        out.write_text(text, encoding="utf-8")
    print(json.dumps(report, indent=1), file=sys.stderr if args.out == "-" else sys.stdout)
    return 0


if __name__ == "__main__":
    sys.exit(main())
