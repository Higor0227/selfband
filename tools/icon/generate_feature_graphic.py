# SPDX-License-Identifier: GPL-3.0-or-later
"""
Generates the store feature graphic (1024x500) in the style of the icon: a wall of coral video
frames with the sync waveform across it. No text, so one image serves every language.

Writes fastlane/metadata/android/{en-US,pt-BR}/images/featureGraphic.png (needs Pillow).
Run from the repository root:  python tools/icon/generate_feature_graphic.py
"""
import math
import os

from PIL import Image, ImageDraw

from generate_icon import BG, CELL_COLORS, ROOT

W, H = 1024, 500
K = 4  # supersampling
COLS, ROWS = 7, 3
CELL = 112
GAP = 22
RADIUS = 20
WAVE_WIDTH = 14
OUTLINE_WIDTH = 30
LOCALES = ["en-US", "pt-BR"]


def rgb(h):
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def wave_points():
    # Flat at both ends, a burst in the middle: the same gesture as the icon's line.
    left, right, mid = 40, W - 40, H / 2
    pts = []
    for i in range(0, 241):
        t = i / 240
        x = left + t * (right - left)
        envelope = math.sin(math.pi * min(max((t - 0.12) / 0.76, 0), 1)) ** 1.5
        y = mid - envelope * 120 * math.sin(t * 2 * math.pi * 9.5) * (0.7 + 0.3 * math.cos(t * 17))
        pts.append((x, y))
    return pts


def render():
    img = Image.new("RGB", (W * K, H * K), rgb(BG))
    d = ImageDraw.Draw(img)
    grid_w = COLS * CELL + (COLS - 1) * GAP
    grid_h = ROWS * CELL + (ROWS - 1) * GAP
    x0, y0 = (W - grid_w) / 2, (H - grid_h) / 2
    for r in range(ROWS):
        for c in range(COLS):
            x, y = x0 + c * (CELL + GAP), y0 + r * (CELL + GAP)
            color = CELL_COLORS[(r + c) % len(CELL_COLORS)]
            d.rounded_rectangle((x * K, y * K, (x + CELL) * K, (y + CELL) * K), radius=RADIUS * K, fill=rgb(color))
    pts = [(x * K, y * K) for x, y in wave_points()]
    for color, width in ((rgb(BG), OUTLINE_WIDTH), ((255, 255, 255), WAVE_WIDTH)):
        d.line(pts, fill=color, width=width * K, joint="curve")
        r = width * K / 2
        for x, y in (pts[0], pts[-1]):
            d.ellipse((x - r, y - r, x + r, y + r), fill=color)
    return img.resize((W, H), Image.LANCZOS)


if __name__ == "__main__":
    image = render()
    for locale in LOCALES:
        path = os.path.join(ROOT, "fastlane", "metadata", "android", locale, "images", "featureGraphic.png")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        image.save(path)
    print("feature graphic written")
