# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The README's images and the repository's social preview, drawn from the app's own sources.

Usage: python3 -I docs/design/mockups/render.py --images   (writes docs/design/images/*.png)
- the logo is the launcher icon itself (android/app/src/main/res/drawable/ic_launcher_foreground.xml on the
  brand orange of values/colors.xml): the face with one round eye and one that winks as a Z, the brand's mark;
- the screens are the approved captures in docs/design/captures/, with the app's character (two round eyes);
- the words are set in the Roboto the app bundles, with the design system's tokens.
So each image changes when its sources change: render it again and commit it.
This file has images(), not boards(): render.py draws it into docs/design/images/, never into captures/.
"""
import html
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

sys.dont_write_bytecode = True  # keep the repo free of __pycache__
sys.path.insert(0, str(Path(__file__).resolve().parent))
from parts import DESIGN, ROOT, data_uri, page  # noqa: E402

RES = ROOT / 'android/app/src/main/res'
CAPTURES = DESIGN / 'captures'
A = '{http://schemas.android.com/apk/res/android}'


def brand_orange():
    for color in ET.parse(RES / 'values/colors.xml').getroot().iter('color'):
        if color.get('name') == 'ic_launcher_background':
            return color.text.strip()
    raise SystemExit('ic_launcher_background not found in values/colors.xml')


def logo(size, radius):
    """The launcher icon, as Android draws its adaptive icon: the foreground's 108 dp canvas on the brand orange,
    cut to a rounded square that shows the middle 72 dp."""
    paths = ''
    for p in ET.parse(RES / 'drawable/ic_launcher_foreground.xml').getroot().iter('path'):
        rule = ' fill-rule="evenodd"' if p.get(A + 'fillType') == 'evenOdd' else ''
        paths += f'<path fill="#000"{rule} d="{p.get(A + "pathData")}"/>'
    return (f'<svg class="logo" width="{size}" height="{size}" viewBox="18 18 72 72" role="img" aria-label="Zeffin">'
            f'<rect x="18" y="18" width="72" height="72" rx="{radius}" fill="{brand_orange()}"/>{paths}</svg>')


# One geometry for every phone: a dark border of BORDER px around the screen, the frame's corners rounded to
# FRAME_RADIUS, and the screen clipped to the frame's inner edge (FRAME_RADIUS - BORDER).
BORDER = 10
FRAME_RADIUS = 44
SCREEN_RADIUS = FRAME_RADIUS - BORDER


def phone(capture, height, label=None):
    """One approved capture (390x844) in a phone's frame `height` px tall: the screen fills the frame's inner
    rectangle (the frame less its border on each side), centered, and is clipped to its rounded corners."""
    inner_h = height - 2 * BORDER
    inner_w = round(inner_h * 390 / 844)
    shot = data_uri(CAPTURES / f'{capture}.png', 'image/png')
    caption = f'<figcaption>{html.escape(label)}</figcaption>' if label else ''
    return (f'<figure class="phone"><div class="phone__frame" style="width:{inner_w + 2 * BORDER}px;height:{height}px">'
            f'<div class="phone__screen" style="width:{inner_w}px;height:{inner_h}px">'
            f'<img src="{shot}" width="{inner_w}" height="{inner_h}" alt=""></div></div>{caption}</figure>')


CSS = '''
.app.fb { background: var(--surface); color: var(--ink); font-family: var(--font-sans); overflow: hidden; }
.logo { display: block; flex-shrink: 0; }
.phone { margin: 0; display: flex; flex-direction: column; align-items: center; gap: 14px; }
.phone__frame { box-sizing: border-box; padding: BORDERpx; border-radius: FRAMEpx; background: #1f1f1f; box-shadow: 0 0 0 2px #3d3d3d, 0 24px 60px rgba(0,0,0,.6); }
.phone__screen { overflow: hidden; border-radius: SCREENpx; clip-path: inset(0 round SCREENpx); }
.phone__screen img { display: block; width: 100%; height: 100%; }
.phone figcaption { font: 500 22px/28px var(--font-sans); color: var(--ink-muted); }
.glow { position: absolute; border-radius: 50%; background: radial-gradient(circle, rgba(255,130,0,.22), rgba(255,130,0,0) 70%); }
.name { margin: 0; font: 700 112px/1 var(--font-sans); letter-spacing: -2px; color: var(--ink); }
.tag { margin: 0; font: 400 34px/44px var(--font-sans); color: var(--ink); }
.tag b { color: var(--accent); font-weight: 500; }
.chips { display: flex; flex-wrap: wrap; gap: 12px; }
.chip { padding: 8px 18px; border-radius: var(--radius-pill); border: 2px solid var(--line); font: 500 22px/28px var(--font-sans); color: var(--ink-muted); }
'''

CSS = CSS.replace('BORDERpx', f'{BORDER}px').replace('FRAMEpx', f'{FRAME_RADIUS}px').replace('SCREENpx', f'{SCREEN_RADIUS}px')

HERO = '''
.hero { position: relative; display: flex; align-items: center; height: 100%; padding: 0 72px; gap: 56px; }
.hero__words { flex: 1; display: flex; flex-direction: column; gap: 28px; z-index: 1; }
.hero__brand { display: flex; align-items: center; gap: 28px; }
.hero__phones { display: flex; align-items: flex-end; gap: 28px; z-index: 1; }
.hero__phones .phone:first-child { margin-bottom: 36px; }
'''

PREVIEW = '''
.preview { position: relative; display: flex; align-items: center; height: 100%; padding: 0 96px; gap: 64px; }
.preview__words { flex: 1; display: flex; flex-direction: column; gap: 30px; z-index: 1; }
.preview .name { font-size: 132px; }
.preview .tag { font-size: 38px; line-height: 48px; }
.preview__phone { z-index: 1; margin-top: 150px; }
'''

STRIP = '''
.strip { display: flex; justify-content: center; align-items: flex-start; gap: 40px; padding: 40px 40px 0; height: 100%; box-sizing: border-box; }
'''

SCREENS = [('home-10-working', 'Home'),
           ('conversation-21-working', 'A conversation'),
           ('remote-01-portrait', 'The remote'),
           ('agents-01b-list-ready', 'The agents')]

TAG = 'Let an AI agent drive your <b>Flipper&nbsp;Zero</b>, from anywhere, with your <b>Android phone</b> as the bridge.'
CHIPS = ''.join(f'<span class="chip">{c}</span>' for c in
                ('Claude Code or OpenCode', 'Your own server', 'Tailscale only', 'Pause any time'))


def hero():
    body = f'''<div class="hero">
<div class="glow" style="width:900px;height:900px;right:-120px;top:-180px"></div>
<div class="hero__words">
  <div class="hero__brand">{logo(132, 16)}<h1 class="name">Zeffin</h1></div>
  <p class="tag">{TAG}</p>
  <div class="chips">{CHIPS}</div>
</div>
<div class="hero__phones">{phone('home-10-working', 520)}{phone('conversation-21-working', 520)}</div>
</div>'''
    return page('Zeffin', CSS + HERO, body, w=1600, h=800)


def social_preview():
    body = f'''<div class="preview">
<div class="glow" style="width:760px;height:760px;right:-160px;top:-120px"></div>
<div class="preview__words">
  {logo(120, 16)}
  <h1 class="name">Zeffin</h1>
  <p class="tag">{TAG}</p>
</div>
<div class="preview__phone">{phone('home-10-working', 620)}</div>
</div>'''
    return page('Zeffin', CSS + PREVIEW, body, w=1280, h=640)


def screens():
    body = '<div class="strip">' + ''.join(phone(name, 720, label) for name, label in SCREENS) + '</div>'
    return page('Zeffin screens', CSS + STRIP, body, w=1640, h=860)


def images():
    """{file name without .png: html}; each page carries its own size."""
    return {'zeffin-main': hero(), 'zeffin-social-preview': social_preview(), 'zeffin-screens': screens()}
