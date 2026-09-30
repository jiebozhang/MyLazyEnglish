"""Draw LazyEng's moon owl with Pillow; run from any directory.

Requires Pillow. All coordinates use Android's 108dp adaptive canvas.
Colors are read from the light design-system palette, never a second palette.
"""
from pathlib import Path
import math
import re

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "apps/android/app/src/main/res"
EVIDENCE = ROOT / "docs/adr/evidence/app-icon-design"
SOURCE = ROOT / "apps/android/core/designsystem/src/main/java/com/lazyeng/family/core/designsystem/Color.kt"
PALETTE = SOURCE.read_text(encoding="utf-8").split("internal val DarkLazyEngPalette")[0]
SCALE = 12  # 1296px master, then Lanczos downsample
SIZE = 108 * SCALE


def token(name):
    return "#" + re.search(rf"{name} = Color\(0xFF([0-9A-F]{{6}})\)", PALETTE)[1]


MINT, DEEP, PAPER, WHITE, GOLD = map(token, ("primaryMint", "primaryDeep", "background", "surface", "warning"))


def box(values):
    return tuple(round(v * SCALE) for v in values)


def ellipse(draw, bounds, fill):
    draw.ellipse(box(bounds), fill=fill)


def polygon(draw, points, fill):
    draw.polygon([box(p) for p in points], fill=fill)


def mask(kind, size=SIZE):
    result = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(result)
    if kind == "circle":
        draw.ellipse((0, 0, size - 1, size - 1), fill=255)
    elif kind == "rounded":
        draw.rounded_rectangle((0, 0, size - 1, size - 1), radius=size * 20 / 108, fill=255)
    else:
        draw.rectangle((0, 0, size - 1, size - 1), fill=255)
    return result


def save(image, path, size):
    path.parent.mkdir(parents=True, exist_ok=True)
    image.resize((size, size), Image.Resampling.LANCZOS).save(path)


def generate():
    background = Image.new("RGBA", (SIZE, SIZE), MINT)
    foreground = Image.new("RGBA", (SIZE, SIZE))
    d = ImageDraw.Draw(foreground)
    # Soft ear tufts, broad rounded body and two wings form one simple silhouette.
    polygon(d, [(32, 43), (33, 29), (48, 37)], DEEP)
    polygon(d, [(60, 37), (75, 29), (76, 43)], DEEP)
    ellipse(d, (29, 34, 79, 82), DEEP)
    ellipse(d, (32, 38, 76, 77), PAPER)
    ellipse(d, (29, 54, 40, 73), DEEP)
    ellipse(d, (68, 54, 79, 73), DEEP)
    # Paired face disks and large pupils remain readable at 48px.
    ellipse(d, (34, 41, 55, 63), WHITE)
    ellipse(d, (53, 41, 74, 63), WHITE)
    ellipse(d, (41, 47, 49, 57), DEEP)
    ellipse(d, (59, 47, 67, 57), DEEP)
    ellipse(d, (42, 48, 44.5, 50.5), WHITE)
    ellipse(d, (60, 48, 62.5, 50.5), WHITE)
    polygon(d, [(49.5, 59), (58.5, 59), (54, 65)], GOLD)
    # Crescent badge echoes the existing ProfileAvatar("moon") visual vocabulary.
    moon = Image.new("RGBA", (SIZE, SIZE))
    md = ImageDraw.Draw(moon)
    ellipse(md, (48, 66, 61, 79), DEEP)
    ellipse(md, (53, 63, 64, 75), (0, 0, 0, 0))
    foreground = Image.alpha_composite(foreground, moon)
    # Validate every nontransparent master pixel, including antialiasing source edges.
    alpha = foreground.getchannel("A")
    pixels = alpha.load()
    max_radius = max(math.hypot((x + .5) / SCALE - 54, (y + .5) / SCALE - 54)
                     for y in range(SIZE) for x in range(SIZE) if pixels[x, y])
    assert max_radius < 33, f"Foreground outside 66dp safe circle: {max_radius}"
    save(background, RES / "drawable/ic_launcher_background.png", 432)
    save(foreground, RES / "drawable/ic_launcher_foreground.png", 432)
    exported = Image.open(RES / "drawable/ic_launcher_foreground.png").getchannel("A")
    exported_radius = max(math.hypot((x + .5) / 4 - 54, (y + .5) / 4 - 54)
                          for y in range(432) for x in range(432) if exported.getpixel((x, y)))
    assert exported_radius < 33, f"Downsampling halo outside safe circle: {exported_radius}"
    adaptive = '<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n    <background android:drawable="@drawable/ic_launcher_background" />\n    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n</adaptive-icon>\n'
    for name in ("ic_launcher", "ic_launcher_round"):
        path = RES / f"mipmap-anydpi-v26/{name}.xml"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(adaptive, encoding="utf-8")
    composite = Image.alpha_composite(background, foreground)
    # Legacy launchers have no adaptive viewport zoom; use the visible 72dp region.
    legacy = composite.crop(box((18, 18, 90, 90))).resize((SIZE, SIZE), Image.Resampling.LANCZOS)
    for density, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        for name, kind in (("ic_launcher", "rounded"), ("ic_launcher_round", "circle")):
            image = legacy.copy()
            image.putalpha(mask(kind))
            save(image, RES / f"mipmap-{density}/{name}.png", size)
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    preview = Image.new("RGB", (1056, 460), PAPER)
    pd = ImageDraw.Draw(preview)
    font = ImageFont.load_default(size=20)
    for index, kind in enumerate(("square", "rounded", "circle")):
        image = legacy.copy()
        image.putalpha(mask(kind))
        tile = image.resize((272, 272), Image.Resampling.LANCZOS)
        x = 40 + index * 352
        preview.paste(tile, (x, 28), tile)
        pd.text((x, 316), kind.upper(), font=font, fill=DEEP)
        small = image.resize((48, 48), Image.Resampling.LANCZOS)
        preview.paste(small, (x, 360), small)
        pd.text((x + 64, 371), "48px / mdpi", font=font, fill=DEEP)
    preview.save(EVIDENCE / "icon-mask-preview.png")
    # Raw adaptive layers at full 108dp canvas, with safe circle for review.
    guide = composite.copy()
    ImageDraw.Draw(guide).ellipse(box((21, 21, 87, 87)), outline=DEEP, width=SCALE // 2)
    save(guide, EVIDENCE / "adaptive-safe-zone.png", 432)
    print(f"Generated launcher assets; master radius {max_radius:.2f}dp; exported radius {exported_radius:.2f}dp < 33dp.")


if __name__ == "__main__":
    generate()
