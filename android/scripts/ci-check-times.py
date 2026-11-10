#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The time of each Android check in the last Gradle run, as a Markdown table (the Tests workflow's summary).

It reads Gradle's --profile report (android/build/reports/profile/) and the JUnit results. Gradle runs tasks in
parallel, so task times add up to more than the wall time; the table gives both. Screenshot tests run inside
:app:testDebugUnitTest and are counted from their own test classes (Screenshot*Test).
"""
import glob
import html
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID = Path(__file__).resolve().parent.parent
UNITS = {"h": 3600.0, "m": 60.0, "s": 1.0}


def seconds(text: str) -> float:
    return sum(float(v) * UNITS[u] for v, u in re.findall(r"([\d.]+)(h|m|s)", text))


def tasks(report: Path) -> dict[str, float]:
    text = report.read_text(encoding="utf-8", errors="replace")
    rows = re.findall(r'<td class="indentPath">([^<]+)</td>\s*<td class="numeric">([^<]+)</td>', text)
    return {html.unescape(path): seconds(t) for path, t in rows if path.startswith(":")}


def wall(report: Path) -> float | None:
    text = report.read_text(encoding="utf-8", errors="replace")
    m = re.search(r"<td>Total Build Time</td>\s*<td class=\"numeric\">([^<]+)</td>", text)
    return seconds(m.group(1)) if m else None


def screenshot_s() -> float:
    total = 0.0
    for f in glob.glob(str(ANDROID / "*/build/test-results/testDebugUnitTest/*Screenshot*Test.xml")):
        total += float(ET.parse(f).getroot().get("time") or 0)
    return total


def main() -> int:
    reports = sorted(glob.glob(str(ANDROID / "build/reports/profile/*.html")))
    if not reports:
        print("No Gradle profile report: the Android checks did not run.")
        return 0
    report = Path(reports[-1])
    t = tasks(report)

    def total(test) -> float:
        return sum(s for p, s in t.items() if test(p.rsplit(":", 1)[-1]))

    tests = total(lambda n: n == "testDebugUnitTest")
    # Results restored from the build cache keep the times of the run that made them: count them only when
    # :app:testDebugUnitTest ran here.
    shots = screenshot_s() if t.get(":app:testDebugUnitTest", 0.0) >= 5 else 0.0
    rows = [
        ("Unit tests (testDebugUnitTest, without the screenshots)", max(0.0, tests - shots)),
        ("Screenshot tests (Screenshot*Test classes; 0 when :app's tests came from the cache)", shots),
        ("Lint (lintDebug and its analysis)", total(lambda n: n.startswith("lint"))),
        ("detekt", total(lambda n: n == "detekt")),
        ("Release build (tasks with Release, R8)", total(lambda n: "Release" in n and not n.startswith("lint"))),
        ("Compiling and the rest", total(lambda n: True)),
    ]
    rows[-1] = (rows[-1][0], rows[-1][1] - sum(s for _, s in rows[:-1]))
    print("| Android check | task time (s) |\n|---|---:|")
    for name, s in rows:
        print(f"| {name} | {s:.0f} |")
    w = wall(report)
    if w is not None:
        print(f"| **Gradle wall time** (tasks run in parallel) | **{w:.0f}** |")
    return 0


if __name__ == "__main__":
    sys.exit(main())
