# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""Notification mockups: the phone's notifications when the agent works, ends a turn or asks for
permission while the app is not on screen. They stay as mockups: Android draws them, so the app has no screenshot
of them. Settings, Agent with its Notifications card is built and captured from the app.

Usage: python3 -I notifications.py <out-folder>   (writes one standalone HTML page per artboard)
The notifications are drawn the way Android 14 shows them: the shade and the lock screen are Android's,
only the texts, the icon and the buttons are the app's.
"""
import sys
from pathlib import Path

sys.dont_write_bytecode = True  # keep the repo free of __pycache__
sys.path.insert(0, str(Path(__file__).resolve().parent))
from parts import page, write_all  # noqa: E402

CSS = '''
.body { flex: 1; min-height: 0; overflow: hidden; display: flex; flex-direction: column; gap: 14px; padding: 14px; }
.body > * { flex-shrink: 0; }
.note { margin: 0; padding: 0 2px; font: 400 12px/16px var(--font-sans); color: var(--ink-muted); }
.warn .fb-card__title { color: var(--warning); }
.warn__text { margin: 0; padding: 0 var(--space-3) var(--space-3); font: 400 14px/20px var(--font-sans); color: var(--ink-muted); }
.dim .fb-setting { opacity: .45; }

/* Android's shade and lock screen: system surfaces, not the app's */
.shade { flex: 1; display: flex; flex-direction: column; gap: 8px; padding: 0 10px; background: #0b0b0c; }
.sys { display: flex; justify-content: space-between; align-items: center; height: 32px; padding: 0 12px; font: 500 13px/16px var(--font-sans); color: #e6e6e6; }
.sys__icons { display: flex; gap: 6px; align-items: center; }
.sys__icons i { display: block; width: 14px; height: 10px; border-radius: 2px; background: #e6e6e6; }
.sys__icons i.bat { width: 20px; }
.clock { padding: 6px 14px 0; font: 400 44px/52px var(--font-sans); color: #f2f2f2; }
.date { padding: 0 14px 6px; font: 400 14px/20px var(--font-sans); color: #c8c8c8; }
.tiles { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; padding: 4px 4px 10px; }
.tiles i { display: block; height: 52px; border-radius: 26px; background: #262628; }
.tiles i.on { background: #3a3a3d; }
.section { display: flex; justify-content: space-between; padding: 6px 14px 0; font: 500 13px/18px var(--font-sans); color: #c8c8c8; }
.nt { display: flex; flex-direction: column; gap: 4px; padding: 14px 16px; border-radius: 24px; background: #202022; color: #f2f2f2; }
.nt--heads { box-shadow: 0 0 0 2px #3a3a3d; }
.nt__head { display: flex; align-items: center; gap: 8px; font: 400 12px/16px var(--font-sans); color: #bdbdbd; }
.nt__icon { display: inline-flex; align-items: center; justify-content: center; width: 24px; height: 24px; border-radius: 50%; background: var(--accent); color: #000; flex-shrink: 0; }
.nt__icon svg { width: 17px; height: 17px; }
.nt__head b { font-weight: 500; color: #e6e6e6; }
.nt__chev { margin-left: auto; width: 26px; height: 18px; border-radius: 9px; background: #2e2e31; }
.nt__title { margin-top: 2px; padding-left: 32px; font: 500 15px/20px var(--font-sans); }
.nt__text { padding-left: 32px; font: 400 14px/20px var(--font-sans); color: #c8c8c8; }
.nt__text + .nt__text { margin-top: 2px; }
.nt__acts { display: flex; gap: 8px; padding: 8px 0 0 24px; }
.nt__acts span { display: inline-flex; align-items: center; gap: 6px; white-space: nowrap; padding: 8px 14px; border-radius: 18px; background: #2e2e31; font: 500 14px/20px var(--font-sans); color: var(--action); }
.nt__acts svg { width: 14px; height: 14px; color: #bdbdbd; }
.nt--silent { padding: 10px 16px; }
.dimapp { position: absolute; inset: 0; background: var(--surface); }
.lock { flex: 1; display: flex; flex-direction: column; gap: 8px; padding: 0 10px; background: radial-gradient(120% 70% at 50% 0%, #1d1d22 0%, #050506 70%); }
.lock .clock { padding: 40px 0 0; text-align: center; font-size: 72px; line-height: 80px; }
.lock .date { padding: 0 0 28px; text-align: center; }
.other { flex: 1; position: relative; display: flex; flex-direction: column; background: #121214; }
.other__bar { height: 56px; margin: 0 0 12px; background: #1c1c1f; }
.other__row { height: 64px; margin: 0 16px 10px; border-radius: 12px; background: #1c1c1f; }
.popup { position: absolute; top: 36px; left: 8px; right: 8px; }
.popup .nt { box-shadow: 0 8px 24px rgba(0, 0, 0, .6); background: #2a2a2d; }
.sys__left { display: flex; gap: 6px; align-items: center; }
.sys__left .nt__icon { width: 16px; height: 16px; background: transparent; color: #e6e6e6; }
.sys__left .nt__icon svg { width: 16px; height: 16px; }
.lock__foot { margin-top: auto; padding: 0 0 28px; text-align: center; font: 400 13px/18px var(--font-sans); color: #9a9a9a; }
'''

ICON = ('<span class="nt__icon"><svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path fill-rule="evenodd" '
        'd="M7 6h10a5 5 0 0 1 0 10H7A5 5 0 0 1 7 6zM5.5 9v4h7V9zM16.5 9.4a1.6 1.6 0 1 0 0 3.2 1.6 1.6 0 0 0 0-3.2z"/></svg></span>')

LOCK = ('<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" aria-hidden="true">'
        '<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/></svg>')
TITLE = 'TV remote'  # the conversation's title


def nt(sub, title, texts=(), acts=(), when='now', heads=False, silent=False):
    """An Android notification: the app's icon, "Zeffin · <sub> · <when>", title, text and buttons."""
    cls = 'nt' + (' nt--heads' if heads else '') + (' nt--silent' if silent else '')
    head = f'<div class="nt__head">{ICON}<b>Zeffin</b>' + ''.join(f' · {x}' for x in (sub, when) if x) + '<span class="nt__chev"></span></div>'
    body = f'<div class="nt__title">{title}</div>' + ''.join(f'<div class="nt__text">{t}</div>' for t in texts)
    a = ''
    if acts:
        a = '<div class="nt__acts">' + ''.join(f'<span>{LOCK}{x[1:]}</span>' if x.startswith('!') else f'<span>{x}</span>' for x in acts) + '</div>'
    return f'    <div class="{cls}">{head}{body}{a}</div>\n'


SYS = ('  <div class="sys"><span>18:42</span><span class="sys__icons"><i></i><i></i><i class="bat"></i></span></div>\n')
HEAD = SYS + '  <div class="clock">18:42</div>\n  <div class="date">Saturday, 24 October</div>\n' \
    '  <div class="tiles"><i class="on"></i><i class="on"></i><i></i><i></i></div>\n'
RELAY = nt('', 'Zeffin relay', ('Running · Demo · 11 min',), acts=('Stop',), when='', silent=True)


def shade(name, inner):
    return page(name, CSS, '  <div class="shade">\n' + HEAD + inner + '  </div>\n')


# ---- The notifications

WORKING = nt('2:04', 'The agent is working', (TITLE,), when='', silent=True)
FINISHED = nt('Turn finished', TITLE, ('Saved remotes has 3 remotes: TV, Fan and Air conditioner. The TV one has 12 buttons; '
                                       'Power worked on the first try.',))
STOPPED = nt('Stopped', 'Fan schedule', ('You stopped the turn.',), when='5 min')
ERROR = nt('Turn failed', 'Read the NFC card', ('The agent reached its step limit.',), when='12 min')
PERMISSION = nt('Permission · 1:52', 'The agent asks for permission',
                ('Bash: touch fb-test.txt', f'{TITLE} · denied if no one answers in 2 min'),
                acts=('!Allow once', 'Deny'), when='', heads=True)
PLAN = nt('Plan · 1:52', 'The agent has a plan', ('Open the conversation to read it and approve it.', f'{TITLE}'),
          when='', heads=True)
LOCK_PERM = nt('Permission · 1:52', 'The agent asks for permission', ('Unlock to see what it wants to do.',),
               acts=('Deny',), when='')
LOCK_DONE = nt('Turn finished', 'The agent finished a turn', ('Unlock to read it.',), when='3 min')


def lock(inner):
    return page('Lock screen', CSS, '  <div class="lock">\n' + SYS + '  <div class="clock">18:42</div>\n'
                '  <div class="date">Saturday, 24 October</div>\n' + inner
                + '  <div class="lock__foot">Swipe up to unlock</div>\n  </div>\n')


def popup(name, inner, icon=False):
    """The phone unlocked, another app on screen, the notification popping up at the top (heads-up)."""
    left = '<span class="sys__left"><span>18:42</span>' + (ICON if icon else '') + '</span>'
    bar = f'  <div class="sys">{left}<span class="sys__icons"><i></i><i></i><i class="bat"></i></span></div>\n'
    app = '<div class="other__bar"></div>' + '<div class="other__row"></div>' * 9
    pop = f'<div class="popup">\n{inner}</div>' if inner else ''
    return page(name, CSS, '  <div class="other" style="background:#121214">\n' + bar + app + pop + '  </div>\n')


def boards():
    sil = '  <div class="section"><span>Silent</span></div>\n'
    return {
        'notify-01-working': shade('Agent working', sil + WORKING + RELAY),
        'notify-02-finished': shade('Turn finished', FINISHED + sil + RELAY),
        'notify-03-stopped-error': shade('Stopped and failed', STOPPED + ERROR + sil + RELAY),
        'notify-04-permission': shade('Permission', PERMISSION + sil + WORKING + RELAY),
        'notify-04b-plan': shade('Plan', PLAN + sil + WORKING + RELAY),
        'notify-05-lock': lock(LOCK_PERM + LOCK_DONE),
        'notify-06-popup-permission': popup('Permission pops up', PERMISSION.replace(' nt--heads', '')),
        'notify-06c-icon-finished': popup('Turn finished, no pop-up', '', icon=True),
    }


if __name__ == '__main__':
    for p in write_all(boards(), sys.argv[1]):
        print(p)
