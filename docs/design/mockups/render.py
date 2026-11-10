# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""Renders the mockups to docs/design/captures/ with headless Chromium.

Usage: python3 -I docs/design/mockups/render.py            (the mockups, into docs/design/captures/)
       python3 -I docs/design/mockups/render.py --images   (brand.py's images, into docs/design/images/)
Every other script in this folder with a boards() function is a set of mockups; parts.py has the
shared pieces; brand.py draws the README's images and the social preview with images(). Chromium comes from the CHROMIUM environment variable, or the first of the
known paths below that exists. Every artboard is captured at its own size (390x844, or 844x390 in landscape).
"""
import importlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.dont_write_bytecode = True  # keep the repo free of __pycache__
sys.path.insert(0, str(HERE))
from parts import H, W, write_all  # noqa: E402

CAPTURES = HERE.parent / 'captures'
IMAGES = HERE.parent / 'images'
# The headless shell first: full Chromium in headless mode keeps room for its window chrome,
# so the page viewport comes out shorter than --window-size.
CANDIDATES = ['/opt/pw-browsers/chromium_headless_shell-1194/chrome-linux/headless_shell',
              'chromium', 'chromium-browser', '/opt/pw-browsers/chromium-1194/chrome-linux/chrome']


def chromium():
    if os.environ.get('CHROMIUM'):
        return os.environ['CHROMIUM']
    for c in CANDIDATES:
        found = shutil.which(c) or (c if Path(c).is_file() else None)
        if found:
            return found
    sys.exit('Chromium not found: set CHROMIUM to its binary.')


def boards():
    """The artboards of every mockup script here: {capture name: html}."""
    found = {}
    for script in sorted(HERE.glob('*.py')):
        if script.stem not in ('parts', 'render', 'brand', 'demo'):  # brand.py and demo.py draw the README's images
            found.update(importlib.import_module(script.stem).boards())
    return found


def main():
    if '--images' in sys.argv[1:]:
        pending, target = importlib.import_module('brand').images(), IMAGES
    else:
        pending, target = boards(), CAPTURES
    if not pending:
        print('No mockups: every screen is built. The captures come from the app (android/scripts/update-design-captures.sh).')
        return
    browser = chromium()
    target.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        pages = write_all(pending, Path(tmp) / 'html')
        for p in pages:
            png = target / (p.stem + '.png')
            size = re.search(r'class="app fb" style="width: (\d+)px; height: (\d+)px;', p.read_text(encoding='utf-8'))
            w, h = (int(size[1]), int(size[2])) if size else (W, H)
            subprocess.run([browser, '--headless', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
                            f'--user-data-dir={tmp}/profile', f'--window-size={w},{h}',
                            '--force-device-scale-factor=1', f'--screenshot={png}', p.as_uri()],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120)
            print(png.relative_to(HERE.parents[2]))


if __name__ == '__main__':
    main()
