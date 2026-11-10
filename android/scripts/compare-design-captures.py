# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""Compares the app's English screenshots with the approved design captures; fails on any visual change.

Run the screenshot tests first, then from the repository root, with a Python that has Pillow (the server's venv):

    cd android && ./gradlew :app:testDebugUnitTest
    .venv/bin/python -I android/scripts/compare-design-captures.py

The screens checked are listed in android/scripts/design-captures.txt (the notification mockups are not app
screenshots and are not checked). For each screen that changed, an image with the changed pixels in red is written to
android/app/build/outputs/design-compare/. When the owner approves a change, android/scripts/update-design-captures.sh
copies the new screenshots over the captures, and they are committed with the change.
"""
import sys
from pathlib import Path

from PIL import Image, ImageChops

ROOT = Path(__file__).resolve().parents[2]
SHOTS = ROOT / "android/app/build/outputs/roborazzi/en"
CAPTURES = ROOT / "docs/design/captures"
DIFFS = ROOT / "android/app/build/outputs/design-compare"
LIST = ROOT / "android/scripts/design-captures.txt"


def names() -> list[str]:
    return [line.strip() for line in LIST.read_text().splitlines() if line.strip() and not line.startswith("#")]


def compare(name: str) -> str | None:
    """None when the screenshot matches its capture, else what differs."""
    shot, capture = SHOTS / f"{name}.png", CAPTURES / f"{name}.png"
    if not shot.exists():
        return "no screenshot (did the screenshot tests run?)"
    if not capture.exists():
        return "no capture in docs/design/captures/"
    a, b = Image.open(shot).convert("RGBA"), Image.open(capture).convert("RGBA")
    if a.size != b.size:
        return f"size {a.size[0]}x{a.size[1]}, capture {b.size[0]}x{b.size[1]}"
    r, g, b_, alpha = ImageChops.difference(a, b).split()  # any channel, by any amount
    mask = ImageChops.lighter(ImageChops.lighter(r, g), ImageChops.lighter(b_, alpha)).point(lambda v: 255 if v else 0)
    changed = a.size[0] * a.size[1] - mask.histogram()[0]
    if not changed:
        return None
    DIFFS.mkdir(parents=True, exist_ok=True)
    marked = Image.blend(a.convert("RGB"), Image.new("RGB", a.size, "white"), 0.7)
    marked.paste(Image.new("RGB", a.size, (255, 0, 0)), mask=mask)
    marked.save(DIFFS / f"{name}.png")
    return f"{changed} pixels changed ({100 * changed / (a.size[0] * a.size[1]):.2f} %)"


def main() -> int:
    checked = names()
    problems = {name: why for name in checked if (why := compare(name)) is not None}
    for name, why in problems.items():
        print(f"{name}: {why}")
    if problems:
        print(f"{len(problems)} of {len(checked)} screens differ from docs/design/captures/. If the change is approved, "
              "run android/scripts/update-design-captures.sh and commit the captures; changed pixels are marked in "
              f"{DIFFS.relative_to(ROOT)}/.")
        return 1
    print(f"All {len(checked)} screens match docs/design/captures/.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
