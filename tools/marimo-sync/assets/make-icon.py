#!/usr/bin/env python3
"""Build marimo-sync's app icon: the marimo mascot inside a sync ring.

Kept as a script rather than a one-off so the icon can be rebuilt if the mascot
ever changes. Writes assets/marimo-sync.png and installs a copy into the user's
icon theme so the .desktop entry can find it by name.

    python3 assets/make-icon.py
"""

from __future__ import annotations

import math
import shutil
from pathlib import Path

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
MASCOT = HERE.parent.parent / "marimo-desktop" / "assets" / "marimo.png"
ICON_DIR = Path.home() / ".local" / "share" / "icons" / "hicolor" / "256x256" / "apps"

SIZE = 256
RING = "#e8d9a0"        # pale amber: reads against a dark taskbar and against the ball
WIDTH = 13


def point(cx: float, cy: float, r: float, degrees: float) -> tuple[float, float]:
    a = math.radians(degrees)
    return cx + r * math.cos(a), cy + r * math.sin(a)


def arrowhead(draw: ImageDraw.ImageDraw, cx, cy, r, degrees, length=26, half=13) -> None:
    """A solid triangle at `degrees`, pointing the way the arc travels."""
    a = math.radians(degrees)
    tx, ty = -math.sin(a), math.cos(a)          # tangent, clockwise
    nx, ny = math.cos(a), math.sin(a)           # radial
    px, py = point(cx, cy, r, degrees)
    tip = (px + tx * length, py + ty * length)
    left = (px - nx * half, py - ny * half)
    right = (px + nx * half, py + ny * half)
    draw.polygon([tip, left, right], fill=RING)


def build() -> Image.Image:
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    ball = Image.open(MASCOT).convert("RGBA").resize((176, 176), Image.LANCZOS)
    img.alpha_composite(ball, (40, 40))

    box = (18, 18, SIZE - 18, SIZE - 18)
    # two thirds of a ring, with a gap where each arrowhead lands
    draw.arc(box, start=196, end=344, fill=RING, width=WIDTH)
    draw.arc(box, start=16, end=164, fill=RING, width=WIDTH)
    arrowhead(draw, SIZE / 2, SIZE / 2, (SIZE - 36) / 2, 344)
    arrowhead(draw, SIZE / 2, SIZE / 2, (SIZE - 36) / 2, 164)
    return img


def main() -> int:
    if not MASCOT.is_file():
        raise SystemExit(f"mascot not found: {MASCOT}")
    icon = build()
    out = HERE / "marimo-sync.png"
    icon.save(out)
    ICON_DIR.mkdir(parents=True, exist_ok=True)
    shutil.copy2(out, ICON_DIR / "marimo-sync.png")
    print(f"wrote {out} and {ICON_DIR / 'marimo-sync.png'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
