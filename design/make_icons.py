"""Builds the app icons from design/icon-source.jpg.

The source is a flattened JPG (its checkerboard "transparency" is baked in), so
we cut out the round emblem with an anti-aliased circular mask and use it as
the adaptive-icon foreground; the background is a gradient drawable (XML).

Run from the repo root: python3 design/make_icons.py  (needs Pillow)
"""
import json
import os

from PIL import Image, ImageDraw

SRC = "design/icon-source.jpg"
CENTER = (512, 512)  # red ring center in the source
RADIUS = 276         # just outside the red ring
TOP, MID, BOTTOM = (42, 127, 192), (31, 58, 114), (25, 25, 59)  # sampled blues

RES = "android/app/src/main/res"
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def emblem(size):
    """The round emblem on transparency, size x size."""
    src = Image.open(SRC).convert("RGB")
    x, y = CENTER
    crop = src.crop((x - RADIUS, y - RADIUS, x + RADIUS, y + RADIUS))
    big = RADIUS * 2 * 4
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, big - 1, big - 1), fill=255)
    mask = mask.resize(crop.size, Image.LANCZOS)
    crop.putalpha(mask)
    return crop.resize((size, size), Image.LANCZOS)


def gradient(size):
    img = Image.new("RGB", (size, size))
    px = img.load()
    for yy in range(size):
        t = yy / (size - 1)
        a, b, u = (TOP, MID, t * 2) if t < 0.5 else (MID, BOTTOM, (t - 0.5) * 2)
        c = tuple(round(a[i] + (b[i] - a[i]) * u) for i in range(3))
        for xx in range(size):
            px[xx, yy] = c
    return img


def composite(size, emblem_fraction, corner_fraction=0.0):
    """Full icon (gradient + emblem), optionally with rounded corners."""
    img = gradient(size).convert("RGBA")
    e = round(size * emblem_fraction)
    img.alpha_composite(emblem(e), ((size - e) // 2, (size - e) // 2))
    if corner_fraction:
        big = size * 4
        mask = Image.new("L", (big, big), 0)
        ImageDraw.Draw(mask).rounded_rectangle(
            (0, 0, big - 1, big - 1), radius=round(big * corner_fraction), fill=255)
        img.putalpha(mask.resize((size, size), Image.LANCZOS))
    return img


def android():
    for name, scale in DENSITIES.items():
        d = f"{RES}/mipmap-{name}"
        os.makedirs(d, exist_ok=True)
        # Adaptive foreground: 108dp canvas; emblem sized to the 66dp safe zone.
        canvas = round(108 * scale)
        fg = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        e = round(66 * scale)
        fg.alpha_composite(emblem(e), ((canvas - e) // 2, (canvas - e) // 2))
        fg.save(f"{d}/ic_launcher_foreground.png")
        # Legacy icon for launchers that ignore adaptive icons.
        composite(round(48 * scale), 0.86, 0.18).save(f"{d}/ic_launcher.png")


def ios():
    d = "ios/Runner/Assets.xcassets/AppIcon.appiconset"
    contents = json.load(open(f"{d}/Contents.json"))
    full = composite(1024, 0.86).convert("RGB")  # iOS: no alpha, system rounds corners
    for image in contents["images"]:
        if "filename" not in image:
            continue
        points = float(image["size"].split("x")[0])
        px = round(points * int(image["scale"].rstrip("x")))
        full.resize((px, px), Image.LANCZOS).save(f"{d}/{image['filename']}")


def preview():
    """Side-by-side preview (circle and rounded-square masks) for review."""
    out = Image.new("RGBA", (560, 280), (255, 255, 255, 255))
    full = composite(256, 66 / 108 * 1.0 / (72 / 108))  # what a 72dp mask shows
    circle = Image.new("L", (256, 256), 0)
    ImageDraw.Draw(circle).ellipse((0, 0, 255, 255), fill=255)
    a = full.copy(); a.putalpha(circle)
    out.alpha_composite(a, (12, 12))
    out.alpha_composite(composite(256, 66 / 72, 0.22), (292, 12))
    out.save("design/icon-preview.png")


if __name__ == "__main__":
    android()
    ios()
    preview()
    print("icons written")
