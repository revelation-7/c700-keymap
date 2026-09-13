"""C700 KeyMap icon — Legion 'O' rebuilt per the logo's own construction:
a ring cut into THREE arcs (broken at 3 / 6 / 9 o'clock) and the pieces pulled
apart radially. Flat cut ends, tile + colors unchanged from the approved draft."""
import math
from PIL import Image, ImageDraw, ImageFont
import os

S = 2048                      # master; 2x supersample -> smooth edges
BG_TOP = (0x36, 0x42, 0x54)
BG_BOT = (0x1C, 0x23, 0x2E)
CY = (0x29, 0xD6, 0xFF)
PAD = 0.040
TILE_R = 0.225
R_OUT = 0.285                 # ring outer radius / canvas
STROKE = 0.085                # ring band thickness
GAP = 24.0                    # angular width (deg) of each of the three cuts

# THREE EQUAL arcs: cuts 120 deg apart, centred on bottom / upper-left / upper-right
# (PIL angles: 0 = 3 o'clock, growing clockwise, y down)
_ARC = 120.0 - GAP            # 96 deg of arc per piece
_CUTS = (90.0, 210.0, 330.0)
ARCS = [((c + GAP / 2), (c + GAP / 2 + _ARC)) for c in _CUTS]


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(len(a)))


def sector_mask(a0, a1, pull_px, cx, cy, steps=1080):
    """One ring piece as a filled polygon: no PIL arc quadrant seams,
    and the two cut ends are straight radial edges (like the logo)."""
    r_out = R_OUT * S
    r_in = (R_OUT - STROKE) * S
    pts = []
    for i in range(steps + 1):
        a = math.radians(a0 + (a1 - a0) * i / steps)
        pts.append((cx + r_out * math.cos(a), cy + r_out * math.sin(a)))
    for i in range(steps, -1, -1):
        a = math.radians(a0 + (a1 - a0) * i / steps)
        pts.append((cx + r_in * math.cos(a), cy + r_in * math.sin(a)))
    m = Image.new("L", (S, S), 0)
    mid = math.radians((a0 + a1) / 2)
    dx, dy = pull_px * math.cos(mid), pull_px * math.sin(mid)
    ImageDraw.Draw(m).polygon([(x + dx, y + dy) for x, y in pts], fill=255)
    return m


def build(pull):
    """pull: radial separation of the three pieces, as a fraction of the canvas."""
    # ---- tile ----
    grad = Image.new("RGBA", (S, S))
    g = ImageDraw.Draw(grad)
    for y in range(S):
        g.line([(0, y), (S, y)], fill=lerp(BG_TOP, BG_BOT, y / S) + (255,))
    tile_m = Image.new("L", (S, S), 0)
    p = int(S * PAD)
    ImageDraw.Draw(tile_m).rounded_rectangle([p, p, S - p, S - p],
                                             radius=int(S * TILE_R), fill=255)
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    img.paste(grad, (0, 0), tile_m)

    # ---- three pieces, each pushed outward along its own mid-angle ----
    cx = cy = S / 2
    d_px = pull * S
    cy_layer = Image.new("RGBA", (S, S), CY + (255,))
    for a0, a1 in ARCS:
        img.paste(cy_layer, (0, 0), sector_mask(a0, a1, d_px, cx, cy))
    return img


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    pulls = [0.000, 0.009, 0.018]
    font = ImageFont.load_default(size=30)
    big, small, pad = 190, 56, 24
    row_h = big + 12 + small + 40
    sheet = Image.new("RGB", (pad + (big + pad) * 3, pad + row_h + pad), (13, 18, 26))
    d = ImageDraw.Draw(sheet)
    labels = ["不拉开 0.000", "轻拉 0.009", "现在 0.018"]
    for i, pull in enumerate(pulls):
        im = build(pull)
        x = pad + i * (big + pad)
        y = pad
        sheet.paste(im.resize((big, big), Image.LANCZOS), (x, y),
                    im.resize((big, big), Image.LANCZOS))
        sm = im.resize((small, small), Image.LANCZOS)
        sheet.paste(sm, (x + big - small, y + big + 12), sm)
        d.text((x + 2, y + big + 16), labels[i], fill=(150, 200, 225), font=font)
        im.resize((256, 256), Image.LANCZOS).save(
            os.path.join(here, "logo_pull_%d.png" % i))
    # ---- ship the middle option into res/ ----
    ship = build(0.000)
    for folder, px in {"mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96,
                       "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192}.items():
        out = os.path.join(here, "res", folder)
        os.makedirs(out, exist_ok=True)
        ship.resize((px, px), Image.LANCZOS).save(os.path.join(out, "ic_launcher.png"))
    ship.resize((256, 256), Image.LANCZOS).save(os.path.join(here, "icon_preview.png"))
    strip = Image.new("RGBA", (320, 120), (13, 18, 26, 255))
    for i, px in enumerate((96, 72, 48)):
        sm = ship.resize((px, px), Image.LANCZOS)
        strip.paste(sm, (24 + i * 104, (120 - px) // 2), sm)
    strip.save(os.path.join(here, "icon_preview_small.png"))
    sheet.save(os.path.join(here, "logo_pulls_v3.png"))
    print("legion-O pieces rendered + res/ updated")


if __name__ == "__main__":
    main()
