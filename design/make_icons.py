"""Draws the Message Killer app icon and writes every Android/iOS size.

Design: a bold US Capitol silhouette on a cream disc, behind a red prohibition
sign, on a blue gradient. Everything is drawn from simple shapes (supersampled,
then downscaled) so small sizes stay crisp.

Run from the repo root: python3 design/make_icons.py  (needs Pillow)
"""
import json
import math
import os

from PIL import Image, ImageDraw, ImageFilter

S = 4  # supersampling factor
U = 1000  # emblem design units: a 1000x1000 square holding the round emblem

CREAM = (245, 239, 224)
NAVY = (36, 52, 92)
RED = (206, 32, 41)
TOP, MID, BOTTOM = (42, 127, 192), (31, 58, 114), (25, 25, 59)

RES = "android/app/src/main/res"
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

RING_OUT, RING_IN = 490, 405
SLASH_HALF_WIDTH = 42


CAPITOL_SCALE, CAPITOL_CENTER_Y = 1.1, 530  # enlarge and center the building in the disc


def capitol(d, k, color, gap):
    """US Capitol silhouette; `gap` is the color used for column slits."""
    def p(x, y):  # design units (building drawn around y=470) -> pixels
        return ((500 + (x - 500) * CAPITOL_SCALE) * k, (CAPITOL_CENTER_Y + (y - 470) * CAPITOL_SCALE) * k)

    def box(x0, y0, x1, y1):
        (a, b), (c, e) = p(x0, y0), p(x1, y1)
        return [a, b, c, e]

    def r(x0, y0, x1, y1, c=color):
        d.rectangle(box(x0, y0, x1, y1), fill=c)

    def poly(pts, c=color):
        d.polygon([p(x, y) for x, y in pts], fill=c)

    # Steps / base and the two long wings.
    r(150, 705, 850, 745)
    r(175, 600, 825, 705)
    # Wing pediments.
    poly([(175, 600), (265, 600), (220, 575)])
    poly([(735, 600), (825, 600), (780, 575)])
    # Central block with its triangular portico pediment.
    r(345, 545, 655, 705)
    poly([(330, 548), (670, 548), (500, 488)])
    # Drum (two tiers) and the dome.
    r(375, 470, 625, 500)
    r(395, 400, 605, 470)
    d.pieslice(box(395, 255, 605, 545), 180, 360, fill=color)
    # Lantern and statue.
    r(482, 250, 518, 300)
    d.ellipse(box(474, 238, 526, 262), fill=color)
    r(494, 205, 506, 240)
    d.ellipse(box(488, 195, 512, 213), fill=color)
    # Column slits: drum, portico, wings.
    if gap is not None:
        for x in range(412, 600, 30):
            r(x, 410, x + 10, 462, gap)
        for x in range(368, 640, 34):
            r(x, 590, x + 12, 690, gap)
        for x0 in (195, 640):
            for x in range(x0, x0 + 150, 32):
                r(x, 625, x + 10, 690, gap)


def emblem(size, mono=False):
    """Round emblem on transparency. mono=True: one-color silhouette for themed icons."""
    k = size * S / U
    big = round(U * k)
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    c = U / 2
    ink = (255, 255, 255, 255) if mono else None

    if not mono:
        d.ellipse([(c - RING_IN) * k, (c - RING_IN) * k, (c + RING_IN) * k, (c + RING_IN) * k], fill=CREAM)
    capitol(d, k, ink or NAVY, None if mono else CREAM)

    # Prohibition slash (top-left to bottom-right), clipped to the inner disc.
    slash = Image.new("L", (big, big), 0)
    sd = ImageDraw.Draw(slash)
    a = math.radians(45)
    dx, dy = math.cos(a), math.sin(a)
    nx, ny = -dy, dx
    L, w = RING_IN + 20, SLASH_HALF_WIDTH
    pts = [(c - dx * L + nx * w, c - dy * L + ny * w), (c + dx * L + nx * w, c + dy * L + ny * w),
           (c + dx * L - nx * w, c + dy * L - ny * w), (c - dx * L - nx * w, c - dy * L - ny * w)]
    sd.polygon([(x * k, y * k) for x, y in pts], fill=255)
    disc = Image.new("L", (big, big), 0)
    ImageDraw.Draw(disc).ellipse([(c - RING_IN) * k, (c - RING_IN) * k, (c + RING_IN) * k, (c + RING_IN) * k], fill=255)
    slash = Image.composite(slash, Image.new("L", (big, big), 0), disc)
    if mono:
        # Cut a thin gap around the slash so it reads apart from the building.
        halo = slash.resize((big, big)).point(lambda v: 255 if v else 0)
        img.putalpha(Image.composite(Image.new("L", (big, big), 0), img.getchannel("A"),
                                     halo.filter(ImageFilter.MaxFilter(round(9 * k) | 1))))
    img.paste(Image.new("RGBA", (big, big), ink or RED + (255,)), (0, 0), slash)

    # Ring.
    ring = Image.new("L", (big, big), 0)
    rd = ImageDraw.Draw(ring)
    rd.ellipse([(c - RING_OUT) * k, (c - RING_OUT) * k, (c + RING_OUT) * k, (c + RING_OUT) * k], fill=255)
    rd.ellipse([(c - RING_IN) * k, (c - RING_IN) * k, (c + RING_IN) * k, (c + RING_IN) * k], fill=0)
    img.paste(Image.new("RGBA", (big, big), ink or RED + (255,)), (0, 0), ring)

    return img.resize((size, size), Image.LANCZOS)


def gradient(size):
    img = Image.new("RGB", (size, size))
    px = img.load()
    for yy in range(size):
        t = yy / max(1, size - 1)
        a, b, u = (TOP, MID, t * 2) if t < 0.5 else (MID, BOTTOM, (t - 0.5) * 2)
        col = tuple(round(a[i] + (b[i] - a[i]) * u) for i in range(3))
        for xx in range(size):
            px[xx, yy] = col
    return img


def composite(size, emblem_fraction, corner_fraction=0.0):
    img = gradient(size).convert("RGBA")
    e = round(size * emblem_fraction)
    img.alpha_composite(emblem(e), ((size - e) // 2, (size - e) // 2))
    if corner_fraction:
        big = size * 4
        mask = Image.new("L", (big, big), 0)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, big - 1, big - 1), radius=round(big * corner_fraction), fill=255)
        img.putalpha(mask.resize((size, size), Image.LANCZOS))
    return img


def layer(scale, mono=False):
    """Adaptive icon layer: 108dp canvas, emblem sized to the 66dp safe zone."""
    canvas = round(108 * scale)
    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    e = round(66 * scale)
    out.alpha_composite(emblem(e, mono), ((canvas - e) // 2, (canvas - e) // 2))
    return out


def android():
    for name, scale in DENSITIES.items():
        d = f"{RES}/mipmap-{name}"
        os.makedirs(d, exist_ok=True)
        layer(scale).save(f"{d}/ic_launcher_foreground.png")
        layer(scale, mono=True).save(f"{d}/ic_launcher_monochrome.png")
        composite(round(48 * scale), 0.86, 0.18).save(f"{d}/ic_launcher.png")


def ios():
    d = "ios/Runner/Assets.xcassets/AppIcon.appiconset"
    contents = json.load(open(f"{d}/Contents.json"))
    full = composite(1024, 0.86).convert("RGB")  # iOS: no alpha; system rounds corners
    for image in contents["images"]:
        if "filename" in image:
            px = round(float(image["size"].split("x")[0]) * int(image["scale"].rstrip("x")))
            full.resize((px, px), Image.LANCZOS).save(f"{d}/{image['filename']}")


def preview():
    """Review sheet: circle and rounded-square masks, a themed (monochrome) icon,
    and real launcher sizes (48 and 72 px)."""
    out = Image.new("RGBA", (900, 290), (255, 255, 255, 255))
    full = composite(256, 66 / 72)
    circle = Image.new("L", (256, 256), 0)
    ImageDraw.Draw(circle).ellipse((0, 0, 255, 255), fill=255)
    a = full.copy()
    a.putalpha(circle)
    out.alpha_composite(a, (12, 16))
    out.alpha_composite(composite(256, 66 / 72, 0.22), (290, 16))
    themed = Image.new("RGBA", (256, 256), (214, 227, 255, 255))
    mono = emblem(round(256 * 66 / 72), mono=True)
    tint = Image.new("RGBA", mono.size, (40, 60, 110, 255))
    tint.putalpha(mono.getchannel("A"))
    themed.alpha_composite(tint, ((256 - mono.width) // 2, (256 - mono.height) // 2))
    themed.putalpha(circle)
    out.alpha_composite(themed, (568, 16))
    out.alpha_composite(composite(72, 66 / 72, 0.22), (836, 30))
    out.alpha_composite(composite(48, 66 / 72, 0.22), (848, 120))
    out.save("design/icon-preview.png")


if __name__ == "__main__":
    android()
    ios()
    preview()
    print("icons written")
