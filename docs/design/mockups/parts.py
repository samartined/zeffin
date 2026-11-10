# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""Shared markup for the Zeffin mockups.

Each artboard is one standalone HTML page: the design system CSS
(system/tokens.css and system/components/bundle.css), the Roboto fonts the app
bundles and the Flipper screen image are all inlined, so a page needs no other
file and no network.
"""
import base64
import html
from pathlib import Path

HERE = Path(__file__).resolve().parent
DESIGN = HERE.parent
ROOT = DESIGN.parent.parent
FONTS = ROOT / 'android/app/src/main/res/font'
# A frame of the server's own simulator: the Infrared menu with "Saved remotes" selected.
SCREEN_PNG = ROOT / 'android/app/src/test/resources/screen/orientation-0.png'

W, H = 390, 844


def data_uri(path, mime):
    return f'data:{mime};base64,' + base64.b64encode(Path(path).read_bytes()).decode('ascii')


SCREEN = data_uri(SCREEN_PNG, 'image/png')


def font_faces():
    faces = [('Roboto', 400, 'roboto_regular.ttf'), ('Roboto', 500, 'roboto_medium.ttf'),
             ('Roboto', 700, 'roboto_bold.ttf'), ('Roboto Mono', 500, 'roboto_mono_medium.ttf')]
    return '\n'.join(
        f'@font-face {{ font-family: "{fam}"; font-weight: {wt}; font-style: normal; '
        f'src: url("{data_uri(FONTS / f, "font/ttf")}") format("truetype"); }}'
        for fam, wt, f in faces)


def design_css():
    return ((DESIGN / 'system/tokens.css').read_text(encoding='utf-8') + '\n'
            + (DESIGN / 'system/components/bundle.css').read_text(encoding='utf-8'))


def page(title, css, body, w=W, h=H):
    return f'''<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width={w}">
<title>{html.escape(title)}</title>
<style>
{font_faces()}
{design_css()}
{COMMON_CSS}
{css}
</style>
</head>
<body>
<div class="app fb" style="width: {w}px; height: {h}px;">
{body}
</div>
</body>
</html>
'''


def write_all(boards, out):
    """Writes {name: html} as <out>/<name>.html and returns the paths."""
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    paths = []
    for name, doc in boards.items():
        p = out / f'{name}.html'
        p.write_text(doc, encoding='utf-8')
        paths.append(p)
    return paths


def svg(d, w=2, fill=False, extra=''):
    if fill:
        return f'<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">{d}{extra}</svg>'
    return f'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="{w}" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">{d}{extra}</svg>'

P = lambda d: f'<path d="{d}"/>'
I = {
    'back': svg(P('M20 12H5M11 6l-6 6 6 6')),
    'search': svg('<circle cx="11" cy="11" r="7"/>' + P('M20 20l-4-4')),
    'plus': svg(P('M12 5v14M5 12h14')),
    'trash': svg(P('M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3')),
    'refresh': svg(P('M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5')),
    'eye': svg(P('M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6S2 12 2 12z') + '<circle cx="12" cy="12" r="3"/>'),
    'sync': svg(P('M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5')),
    'stop': svg('<rect x="6" y="6" width="12" height="12" rx="2"/>'),
    'close': svg(P('M6 6l12 12M18 6L6 18')),
    'open': svg(P('M14 4h6v6M20 4l-9 9M18 14v6H4V6h6')),
    'chart': svg(P('M4 19h16M4 15l4-4 4 3 4-6 4 4')),
    'chev': '<svg class="fb-setting__chev" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M9 6l6 6-6 6"/></svg>',
    'flipper': svg(P('M6.5 6.5h11a4.5 4.5 0 0 1 0 9h-11a4.5 4.5 0 0 1 0-9z') + '<rect x="5.5" y="9" width="7" height="4" rx="1"/><circle cx="16.5" cy="11" r="1.6"/>'),
    'bt': svg(P('M7 7l10 10-5 5V2l5 5L7 17')),
    'send': svg(P('M5 12h14M13 6l6 6-6 6')),
    'tune': svg(P('M4 6h9M17 6h3M4 12h3M11 12h9M4 18h11M19 18h1') + '<circle cx="15" cy="6" r="2"/><circle cx="9" cy="12" r="2"/><circle cx="17" cy="18" r="2"/>'),
    'pin': svg(P('M9 4h6l-1 5 3 3v2H7v-2l3-3-1-5zM12 14v6')),
    'star': svg(P('M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z')),
    'starfill': '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z"/></svg>',
    'pencil': svg(P('M4 20h4L19 9l-4-4L4 16v4zM14 6l4 4')),
    'copy': svg('<rect x="9" y="9" width="11" height="11" rx="2"/>' + P('M5 15V5a1 1 0 0 1 1-1h10')),
    'list': svg(P('M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01')),
    'settings': svg('<circle cx="12" cy="12" r="3"/>' + P('M12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M5 19l2-2M17 7l2-2')),
    'check': svg(P('M5 12l5 5 9-10')),
    'stopfill': '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><rect x="7" y="7" width="10" height="10" rx="2"/></svg>',
}
NAV_ICONS = {
    'flip_on': '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path fill-rule="evenodd" d="M7 6h10a5 5 0 0 1 0 10H7A5 5 0 0 1 7 6zM5.5 9v4h7V9zM16.5 9.4a1.6 1.6 0 1 0 0 3.2 1.6 1.6 0 0 0 0-3.2z"/></svg>',
    'flip_off': svg(P('M7 6.5h10a4.5 4.5 0 0 1 0 9H7a4.5 4.5 0 0 1 0-9z') + '<rect x="5.5" y="9" width="7" height="4" rx="1"/><circle cx="16.5" cy="11" r="1.6"/>', w=1.8),
    'chat_on': '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M20 2H4c-1.1 0-1.99.9-1.99 2L2 22l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zM6 9h12v2H6V9zm8 5H6v-2h8v2zm4-6H6V6h12v2z"/></svg>',
    'chat_off': '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm0 14H6l-2 2V4h16v12z"/></svg>',
    'gear_on': '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.49.49 0 0 0-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.48.48 0 0 0-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96a.48.48 0 0 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32a.49.49 0 0 0-.12-.61l-2.01-1.58zM12 15.6A3.6 3.6 0 1 1 12 8.4a3.6 3.6 0 0 1 0 7.2z"/></svg>',
    'gear_off': '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round" aria-hidden="true"><path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.49.49 0 0 0-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.48.48 0 0 0-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96a.48.48 0 0 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32a.49.49 0 0 0-.12-.61l-2.01-1.58z"/><circle cx="12" cy="12" r="3.2"/></svg>',
}

def nav(active, status='Connected', off=False):
    def b(i, label, on, offi, extra_cls='', aria=None):
        cls = ' '.join(x for x in [extra_cls, 'is-active' if i == active else ''] if x)
        a = (f' class="{cls}"' if cls else '') + (' aria-current="page"' if i == active else '') + (f' aria-label="{aria}"' if aria else '')
        ic = NAV_ICONS[on if i == active else offi]
        return f'    <button type="button"{a}><span class="nav__icon">{ic}</span>{label}</button>\n'
    st = 'nav__status' + (' nav__status--off' if off else '')
    return ('  <nav class="nav" aria-label="Sections">\n'
            + b(0, status, 'flip_on', 'flip_off', st, f'{status}, go to Home')
            + b(1, 'Conversations', 'chat_on', 'chat_off')
            + b(2, 'Settings', 'gear_on', 'gear_off')
            + '  </nav>')


def iconbtn(icon, label):
    return f'    <button class="iconbtn" type="button" aria-label="{label}">{I[icon]}</button>\n'


def topbar(title, sub=None, back=False, actions=()):
    s = '  <header class="topbar">\n'
    if back:
        s += iconbtn('back', 'Back')
    s += '    <div class="topbar__title">\n'
    s += f'      <span class="topbar__name">{title}</span>\n'
    if sub:
        s += f'      <span class="topbar__sub">{sub}</span>\n'
    s += '    </div>\n'
    for a in actions:
        s += iconbtn(*a)
    return s + '  </header>'


def sheet(title, inner, sub=None):
    t = f'<span class="fb-sheet__title">{title}</span>' + (f'<span class="fb-sheet__sub">{sub}</span>' if sub else '')
    return (f'  <div class="fb-scrim" aria-hidden="true"></div>\n  <section class="fb-sheet" aria-label="{title}">\n    <span class="fb-sheet__handle"></span>\n'
            f'    <div class="fb-sheet__head">{t}</div>\n{inner}  </section>\n')


def state(kind, text):
    return f'<span class="fb-state{" fb-state--" + kind if kind else ""}">{text}</span>'


def card(title, inner, st=None):
    head = ''
    if title:
        head = f'      <div class="fb-card__head"><span class="fb-card__title">{title}</span>{state(*st) if st else ""}</div>\n'
    return f'    <section class="fb-card">\n{head}{inner}    </section>\n'


def info(label, value, kind=None, mono=False):
    cls = 'fb-info__value' + (' fb-info__value--mono' if mono else '') + (f' fb-state--{kind}' if kind else '')
    return f'      <div class="fb-info"><span class="fb-info__label">{label}</span><span class="{cls}">{value}</span></div>\n'


def action(label, icon='sync', danger=False):
    cls = 'fb-action' + (' fb-action--danger' if danger else '')
    return f'      <button type="button" class="{cls}">{I[icon]}{label}</button>\n'


def setting(title, desc=None, value=None, toggle=None):
    tag = 'div' if toggle is not None else 'button'
    t = '' if tag == 'div' else ' type="button"'
    d = f'<span class="fb-setting__desc">{desc}</span>' if desc else ''
    end = ''
    if value:
        end += f'<span class="fb-setting__value">{value}</span>'
    if toggle is None:
        end += I['chev']
    else:
        on = ' is-on' if toggle else ''
        end += f'<button type="button" class="fb-switch{on}" role="switch" aria-checked="{"true" if toggle else "false"}" aria-label="{title}"></button>'
    return f'      <{tag} class="fb-setting"{t}><span class="fb-setting__text"><span class="fb-setting__title">{title}</span>{d}</span>{end}</{tag}>\n'


COMMON_CSS = '''body { margin: 0; background: var(--surface); }
.app { position: relative; box-sizing: border-box; display: flex; flex-direction: column; background: var(--surface); color: var(--ink); font-family: var(--font-sans); overflow: hidden; }
.app * { box-sizing: border-box; }
.app button, .app input { font-family: inherit; }
.app :focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }

/* Orange app bar */
.topbar { display: flex; align-items: center; gap: var(--space-1); min-height: 56px; padding: 0 var(--space-1) 0 14px; background: var(--accent); color: var(--on-accent); flex-shrink: 0; }
.topbar .iconbtn:first-child { margin-left: -10px; }
.topbar__title { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.topbar__name { font: 700 20px/26px var(--font-sans); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.topbar__sub { font: 400 12px/16px var(--font-sans); color: var(--on-accent); }
.iconbtn { display: inline-flex; align-items: center; justify-content: center; width: 44px; height: 44px; border: 0; border-radius: var(--radius-pill); background: transparent; color: inherit; cursor: pointer; flex-shrink: 0; }
.iconbtn svg { width: 24px; height: 24px; }

/* Bottom bar: pill behind the active icon, 10px bold label */
.nav { display: flex; height: 64px; flex-shrink: 0; background: var(--surface-raised); }
.nav button { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 4px; border: 0; background: transparent; font: 700 10px/14px var(--font-sans); color: var(--ink-muted); cursor: pointer; }
.nav__icon { display: inline-flex; align-items: center; justify-content: center; width: 48px; height: 28px; border-radius: 14px; }
.nav svg { width: 24px; height: 24px; }
.nav .is-active { color: var(--ink); }
.nav .is-active .nav__icon { background: var(--line); }
.nav .nav__status.is-active { color: var(--action); }
.nav .nav__status.is-active .nav__icon { background: var(--action); color: var(--on-action); }
.nav .nav__status--off.is-active { color: var(--ink); }
.nav .nav__status--off.is-active .nav__icon { background: var(--line); color: var(--ink); }
'''
