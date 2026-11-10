# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The README's demo animation, made from the repository's own sources, with nothing recorded.

Usage: .venv/bin/python -I docs/design/mockups/demo.py   (writes docs/design/images/zeffin-demo.gif)
It needs the server's venv (`pip install -e ./server`: Pillow and the simulator) and ffmpeg; no browser.
- the phone shows the approved captures in docs/design/captures/, unchanged, in brand.py's frame (its geometry);
- the Flipper's screen beside it is drawn by the server's simulator (bridge.sim.render), the one that drew the
  mockups' frame: its main menu, the Infrared menu and the saved remotes the conversation answers with;
- the words are set in the Roboto the app bundles, in the design system's colors (system/tokens.css), and the logo
  is the one brand.py draws in images/zeffin-main.png;
- inside the captures, only the Flipper's screen changes, in its own slots: the header's live thumbnail shows the
  same frame as the big screen, and the flipper_press row's the screen after that press (the captures' thumbnails
  are this simulator's frame at 1:1, so the slots match them pixel for pixel);
- the only thing drawn over the app is a touch ring, like Android's "show taps".
The story: the user asks from the phone, the agent presses the Flipper's buttons in step
with its tool lines, asks for a permission the user denies, and answers. So the animation changes when its sources
change: make it again (after render.py --images, for the logo) and commit it.
"""
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

sys.dont_write_bytecode = True  # keep the repo free of __pycache__
sys.path.insert(0, str(Path(__file__).resolve().parent))
from brand import BORDER, FRAME_RADIUS, SCREEN_RADIUS  # noqa: E402
from parts import DESIGN, FONTS  # noqa: E402

OUT = DESIGN / 'images/zeffin-demo.gif'
CAPTURES = DESIGN / 'captures'
LOGO = (DESIGN / 'images/zeffin-main.png', (72, 208, 204, 340))  # where brand.py's hero draws the 132 px logo
W, H = 1000, 760
FPS = 12
FADE = 4  # frames of a crossfade
PHONE_H, PHONE_X = 720, 40
SIDE_X = 432  # the left edge of the caption and the Flipper's screen
# tokens.css: --surface, --ink, --ink-muted, --screen-bezel, --screen-paper, --screen-pixel; brand.py's phone frame
SURFACE, INK, MUTED = (0, 0, 0), (255, 255, 255), (0x99, 0x99, 0x99)
BEZEL, PAPER, PIXEL = (0x80, 0x41, 0x00), (0xff, 0x8c, 0x29), (0, 0, 0)
FRAME, FRAME_EDGE, GLOW = (0x1f, 0x1f, 0x1f), (0x3d, 0x3d, 0x3d), (255, 130, 0)
INFRARED = ['Learn new remote', 'Saved remotes', 'Universal remotes', 'Extra']  # the rows of orientation-0.png
REMOTES = ['TV', 'Air', 'Fan', 'Lamp']  # the answer in conversation-22-finished
DENY = (66, 702)  # the Deny action of conversation-04-permission, in capture pixels
# The Flipper's live screen inside the captures, 128x64 at 1:1, in capture pixels: the header's thumbnail, and the
# thumbnail of the one flipper_press row ("Pressed DOWN, DOWN, DOWN, OK"), which shows the screen after that press.
HEADER = (31, 81)
PRESS_ROW = {'conversation-01-chat': (29, 573), 'conversation-21-working': (29, 485),
             'conversation-04-permission': (29, 312), 'conversation-22-finished': (29, 573)}

ASK = 'Ask the agent from your phone'
DRIVE = "It looks at the Flipper's screen and presses its buttons"
ASKS = 'It asks before anything risky.\nYou decide.'  # one sentence per line
ANSWER = 'It answers with the result'


def flipper(title, items, selected):
    """One simulator frame (128x64, 1 bit, the Flipper's byte order), black on the screen's orange."""
    from bridge.sim import render
    from PIL import Image
    raw = render(title, items, selected)
    img = Image.new('RGB', (128, 64), PAPER)
    px = img.load()
    for y in range(64):
        for x in range(128):
            if raw[(y // 8) * 128 + x] >> (y % 8) & 1:
                px[x, y] = PIXEL
    return img


def timeline():
    """(capture, Flipper screen, caption, touch ring, press row's screen), seconds it holds. Another capture fades in; a key is a cut."""
    from bridge.sim import MENU
    main_menu = [flipper('Main menu', MENU, i) for i in range(4)]  # Sub-GHz, then DOWN three times to Infrared
    ir = [flipper('Infrared', INFRARED, i) for i in range(2)]
    saved = flipper('Saved remotes', REMOTES, 0)
    after_press = ir[0]  # the screen after "Pressed DOWN, DOWN, DOWN, OK": Infrared, its first row selected
    return [
        (('conversation-09-new', main_menu[0], ASK, False, after_press), 2.5),
        (('conversation-01-chat', main_menu[0], DRIVE, False, after_press), 1.2),
        (('conversation-01-chat', main_menu[1], DRIVE, False, after_press), 0.5),
        (('conversation-01-chat', main_menu[2], DRIVE, False, after_press), 0.5),
        (('conversation-01-chat', main_menu[3], DRIVE, False, after_press), 0.6),
        (('conversation-01-chat', ir[0], DRIVE, False, after_press), 2.2),
        (('conversation-21-working', ir[0], DRIVE, False, after_press), 1.0),
        (('conversation-21-working', ir[1], DRIVE, False, after_press), 0.6),
        (('conversation-21-working', saved, DRIVE, False, after_press), 1.6),
        (('conversation-04-permission', saved, ASKS, False, after_press), 2.0),
        (('conversation-04-permission', saved, ASKS, True, after_press), 1.6),
        (('conversation-22-finished', saved, ANSWER, False, after_press), 4.2),
    ]


class Painter:
    """Draws one scene: the backdrop with the phone's frame and the logo once, then each capture, caption and
    Flipper screen on a copy of it."""

    def __init__(self):
        from PIL import Image, ImageDraw, ImageFilter, ImageFont
        self.Image, self.ImageDraw = Image, ImageDraw
        self.caption_font = ImageFont.truetype(str(FONTS / 'roboto_medium.ttf'), 32)
        self.label_font = ImageFont.truetype(str(FONTS / 'roboto_medium.ttf'), 18)
        self.name_font = ImageFont.truetype(str(FONTS / 'roboto_bold.ttf'), 30)
        self.inner_h = PHONE_H - 2 * BORDER
        self.inner_w = round(self.inner_h * 390 / 844)
        self.phone_y = (H - PHONE_H) // 2
        self.screen_xy = (PHONE_X + BORDER, self.phone_y + BORDER)

        base = Image.new('RGB', (W, H), SURFACE)
        # brand.py's glow: an orange radial gradient behind the right side
        glow = Image.new('L', (W, H), 0)
        ImageDraw.Draw(glow).ellipse((W - 620, -380, W + 200, 440), fill=56)
        glow = glow.filter(ImageFilter.GaussianBlur(160))
        base.paste(Image.new('RGB', (W, H), GLOW), (0, 0), glow)
        # the phone: its shadow, its edge, its frame
        shadow = Image.new('L', (W, H), 0)
        x0, y0, x1, y1 = PHONE_X, self.phone_y, PHONE_X + self.inner_w + 2 * BORDER, self.phone_y + PHONE_H
        ImageDraw.Draw(shadow).rounded_rectangle((x0, y0 + 24, x1, y1 + 24), FRAME_RADIUS, fill=150)
        base.paste(Image.new('RGB', (W, H), (0, 0, 0)), (0, 0), shadow.filter(ImageFilter.GaussianBlur(30)))
        d = ImageDraw.Draw(base)
        d.rounded_rectangle((x0 - 2, y0 - 2, x1 + 2, y1 + 2), FRAME_RADIUS + 2, fill=FRAME_EDGE)
        d.rounded_rectangle((x0, y0, x1, y1), FRAME_RADIUS, fill=FRAME)
        self.screen_mask = self.rounded_mask((self.inner_w, self.inner_h), SCREEN_RADIUS)
        # the side: the label, the Flipper's bezel and the logo with the name
        self.caption_y = (H - 506) // 2
        label_y = self.caption_y + 84 + 36
        self.flipper_xy = (SIDE_X + 8, label_y + 34 + 8)
        d.text((SIDE_X, label_y), 'Flipper · Demo', font=self.label_font, fill=MUTED)
        d.rounded_rectangle((SIDE_X, label_y + 34, SIDE_X + 528, label_y + 34 + 272), 14, fill=BEZEL)
        self.flipper_mask = self.rounded_mask((512, 256), 6)
        mark_y = label_y + 34 + 272 + 36
        src, box = LOGO
        logo = Image.open(src).convert('RGBA').crop(box).resize((44, 44), Image.LANCZOS)
        base.paste(logo, (SIDE_X, mark_y), logo)
        d.text((SIDE_X + 58, mark_y + 22), 'Zeffin', font=self.name_font, fill=INK, anchor='lm')
        self.base = base

    def rounded_mask(self, size, radius):
        """An antialiased rounded rectangle: drawn at x4 and scaled down."""
        big = self.Image.new('L', (size[0] * 4, size[1] * 4), 0)
        self.ImageDraw.Draw(big).rounded_rectangle((0, 0, size[0] * 4 - 1, size[1] * 4 - 1), radius * 4, fill=255)
        return big.resize(size, self.Image.LANCZOS)

    def wrap(self, text):
        """The caption's lines: its own line breaks, then the words that fit in the side's width."""
        if '\n' in text:
            return [w for part in text.split('\n') for w in self.wrap(part)]
        lines, line = [], ''
        for word in text.split():
            trial = f'{line} {word}'.strip()
            if line and self.caption_font.getlength(trial) > 528:
                lines.append(line)
                line = word
            else:
                line = trial
        return lines + [line]

    def scene(self, capture, screen, caption, ring, after_press):
        img = self.base.copy()
        shot = self.Image.open(CAPTURES / f'{capture}.png').convert('RGB')
        # only the Flipper's screen changes inside its own slots: the live one in the header, and the press row's
        shot.paste(screen, HEADER)
        if capture in PRESS_ROW:
            shot.paste(after_press, PRESS_ROW[capture])
        shot = shot.resize((self.inner_w, self.inner_h), self.Image.LANCZOS)
        img.paste(shot, self.screen_xy, self.screen_mask)
        img.paste(screen.resize((512, 256), self.Image.NEAREST), self.flipper_xy, self.flipper_mask)
        d = self.ImageDraw.Draw(img)
        for n, line in enumerate(self.wrap(caption)):
            d.text((SIDE_X, self.caption_y + n * 42), line, font=self.caption_font, fill=INK)
        if ring:
            scale = self.inner_h / 844
            cx, cy = (self.screen_xy[0] + DENY[0] * scale, self.screen_xy[1] + DENY[1] * scale)
            over = self.Image.new('RGBA', (W * 4, H * 4), (0, 0, 0, 0))
            o = self.ImageDraw.Draw(over)
            o.ellipse(((cx - 28) * 4, (cy - 28) * 4, (cx + 28) * 4, (cy + 28) * 4),
                      fill=(255, 255, 255, 72), outline=(255, 255, 255, 190), width=12)
            img = self.Image.alpha_composite(img.convert('RGBA'), over.resize((W, H), self.Image.LANCZOS))
            img = img.convert('RGB')
        return img


def main():
    from PIL import Image
    ffmpeg = shutil.which('ffmpeg') or sys.exit('ffmpeg not found.')
    steps = timeline()
    painter = Painter()
    shots = [painter.scene(*s) for s, _ in steps]
    frames = []  # (image, seconds)
    for i, ((s, hold), shot) in enumerate(zip(steps, shots)):
        nxt = (i + 1) % len(steps)
        fades = steps[nxt][0][0] != s[0]  # another capture: a crossfade; the same one: a cut
        frames.append((shot, hold - (FADE / FPS if fades else 0)))
        if fades:
            frames += [(Image.blend(shot, shots[nxt], k / (FADE + 1)), 1 / FPS) for k in range(1, FADE + 1)]
    with tempfile.TemporaryDirectory() as tmp:
        lines = []
        for n, (img, secs) in enumerate(frames):
            p = Path(tmp) / f'frame-{n:03}.png'
            img.save(p)
            lines += [f"file '{p}'", f'duration {secs:.3f}']
        lines.append(f"file '{p}'")  # the concat demuxer needs the last file twice to keep its duration
        listing = Path(tmp) / 'frames.txt'
        listing.write_text('\n'.join(lines) + '\n', encoding='utf-8')
        # one palette per frame: the flat screens keep their exact colors, and the crossfades their own
        subprocess.run([ffmpeg, '-v', 'error', '-y', '-f', 'concat', '-safe', '0', '-i', str(listing),
                        '-vf', 'split[a][b];[a]palettegen=stats_mode=single[p];'
                               '[b][p]paletteuse=new=1:dither=sierra2_4a',
                        '-fps_mode', 'vfr', '-loop', '0', str(OUT)], check=True)
    total = sum(s for _, s in frames)
    print(f'{OUT.relative_to(DESIGN.parents[1])}: {W}x{H}, {total:.1f} s, {len(frames)} frames, '
          f'{OUT.stat().st_size / 1e6:.2f} MB')


if __name__ == '__main__':
    main()
