# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""Before probe fixtures are committed: does any of this machine's personal values remain in them?

Run on the server, from the zeffin clone the service runs from, with only the standard library:

    python3 -I server/scripts/check-private-fixtures.py server/tests/fixtures/stream /tmp/probe-report.json

The values: the user name, the host names, each part of the home folder and of the clone's path (minus
generic ones such as `home` or `projects`), and the time zone. Each file is searched as it is and as the
JSON strings it holds joined in order, so a value Claude Code streamed in pieces is found too. Values of
4 or more characters are searched as case-insensitive substrings, shorter ones as whole words.

It prints only "FOUND: value i of n", never a value, and exits 1 when something is found.
"""
from __future__ import annotations

import json
import os
import re
import socket
import subprocess
import sys
from pathlib import Path

GENERIC = {"home", "dev", "projects", "project", "src", "code", "repos", "repo", "git", "users", "root", "opt", "srv",
           "usr", "var", "tmp", "work", "server", "tests", "fixtures", "stream", "zeffin", "zeffin-data", "zeffin-agent", "flipper-bridge",
           "flipper-bridge-data", "flipper-agent", "africa", "america", "antarctica", "asia", "atlantic", "australia",
           "europe", "indian", "pacific", "etc", "utc"}


def time_zone() -> str:
    try:
        return Path("/etc/timezone").read_text().strip()
    except OSError:
        pass
    try:
        out = subprocess.run(["timedatectl", "show", "-p", "Timezone", "--value"], capture_output=True, text=True,
                             timeout=10).stdout.strip()
        if out:
            return out
    except (OSError, subprocess.SubprocessError):
        pass
    try:
        return str(Path("/etc/localtime").resolve()).split("zoneinfo/", 1)[1]
    except (OSError, IndexError):
        return ""


def personal_values(env: dict, hostname: str, clone: Path, tz: str) -> list[str]:
    found = [env.get("USER", ""), env.get("LOGNAME", ""), hostname, hostname.split(".")[0], tz,
             *str(env.get("HOME", "")).split("/"), *str(clone).split("/"), *tz.split("/")]
    return sorted({v for v in (x.strip() for x in found) if v and v.lower() not in GENERIC})


def strings(value) -> list[str]:
    """The string values in order (keys stay in the raw text: between pieces they would split a value again)."""
    if isinstance(value, dict):
        return [s for v in value.values() for s in strings(v)]
    if isinstance(value, list):
        return [s for v in value for s in strings(v)]
    return [value] if isinstance(value, str) else []


def views(path: Path) -> list[str]:
    """The file as it is, and its JSON strings joined in order (one JSON value per line, or one per file)."""
    raw = path.read_text(errors="replace")
    joined: list[str] = []
    for chunk in [raw] if path.suffix == ".json" else raw.splitlines():
        try:
            joined += strings(json.loads(chunk))
        except ValueError:
            continue
    return [raw, "".join(joined)]


def matches(value: str, text: str) -> bool:
    if len(value) >= 4:
        return value.lower() in text.lower()
    return re.search(r"(?<![A-Za-z0-9])" + re.escape(value) + r"(?![A-Za-z0-9])", text, re.IGNORECASE) is not None


def files(args: list[str]) -> list[Path]:
    found: list[Path] = []
    for arg in args:
        p = Path(arg)
        found += sorted(f for f in p.rglob("*") if f.is_file()) if p.is_dir() else [p]
    return found


def check(paths: list[Path], values: list[str]) -> list[int]:
    texts = [t for p in paths for t in views(p)]
    return [i for i, v in enumerate(values, 1) if any(matches(v, t) for t in texts)]


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__.strip().splitlines()[2].strip())
        return 2
    values = personal_values(dict(os.environ), socket.gethostname(), Path.cwd().resolve(), time_zone())
    paths = files(argv)
    found = check(paths, values)
    for i in found:
        print(f"FOUND: value {i} of {len(values)}")
    print(f"{'not clean' if found else 'clean'}: {len(values)} values, {len(paths)} files")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
