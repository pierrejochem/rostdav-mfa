#!/usr/bin/env python3
"""Write the mark SVGs and the Android icon drawables from one set of shapes.

The mark is the Rostdav folder (Oxid back plate, Rost front plate, Kupfer highlight, three rust
flakes) with a lock on the front plate. Standard library only. Run from the repository root:
    python3 brand/logo/make_mark.py
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
RES = ROOT / "android/app/src/main/res/drawable"
LOGO = ROOT / "brand/logo"

BACK = "M18 34a8 8 0 0 1 8-8h22l8 9h44a8 8 0 0 1 8 8v51a8 8 0 0 1-8 8H26a8 8 0 0 1-8-8Z"
FRONT = "M33 50h75a4 4 0 0 1 3.9 4.8l-9 44A4 4 0 0 1 99 102H24a4 4 0 0 1-3.9-4.8l9-44A4 4 0 0 1 33 50Z"
HIGHLIGHT = "M33 50h75a4 4 0 0 1 3.9 4.8l-0.6 3.2H28.4l0.7-3.2A4 4 0 0 1 33 50Z"
# lock on the front plate, centred at x=64: body, shackle (as a closed ring) and keyhole
LOCK_BODY = "M56 76H72A3 3 0 0 1 75 79V90A3 3 0 0 1 72 93H56A3 3 0 0 1 53 90V79A3 3 0 0 1 56 76Z"
LOCK_SHACKLE = "M57 76V70A7 7 0 0 1 71 70V76H68.6V70A4.6 4.6 0 0 0 59.4 70V76Z"
KEYHOLE = "M61.8 83.5A2.2 2.2 0 1 0 66.2 83.5A2.2 2.2 0 1 0 61.8 83.5Z"
FLAKES = [  # x, y, size, radius, rotation, pivot, colour
    (110, 84, 9, 2, 12, (114.5, 88.5), "#E8783A"),
    (122, 70, 6, 1.5, 24, (125, 73), "#F2A65A"),
    (130, 58, 4, 1, 36, (132, 60), "#F7C589"),
]
# mark bounds (viewBox units): x 18..134, y 26..110
CX, CY = 76.0, 68.0


def flake_rect(x, y, s, r, rot, pivot, fill, opacity=None):
    op = ' opacity="%s"' % opacity if opacity else ""
    return '<rect x="%s" y="%s" width="%s" height="%s" rx="%s" fill="%s"%s transform="rotate(%s %s %s)"/>' % (
        x, y, s, s, r, fill, op, rot, pivot[0], pivot[1])


def mark_color():
    f = [flake_rect(*fl) for fl in FLAKES]
    return "\n".join([
        '<path d="%s" fill="#7A2A12"/>' % BACK,
        '<path d="%s" fill="#C2491D"/>' % FRONT,
        '<path d="%s" fill="#E8783A"/>' % HIGHLIGHT,
        '<path d="%s" fill="#F4EFE8"/>' % LOCK_BODY,
        '<path d="%s" fill="#F4EFE8"/>' % LOCK_SHACKLE,
        '<path d="%s" fill="#7A2A12"/>' % KEYHOLE,
    ] + f)


def mark_mono(ink):
    f = [flake_rect(x, y, s, r, rot, p, ink) for (x, y, s, r, rot, p, _c) in FLAKES]
    return "\n".join([
        '<path d="%s" fill="%s" opacity="0.35"/>' % (BACK, ink),
        '<path fill-rule="evenodd" d="%s%s%s%s" fill="%s"/>' % (FRONT, LOCK_BODY, LOCK_SHACKLE, KEYHOLE, ink),
    ] + f)


def mark_negative():
    f = [flake_rect(x, y, s, r, rot, p, "#F4EFE8", op) for (x, y, s, r, rot, p, _c), op in zip(FLAKES, (None, "0.8", "0.6"))]
    return "\n".join([
        '<path d="%s" fill="#7A2A12"/>' % BACK,
        '<path fill-rule="evenodd" d="%s%s%s%s" fill="#F4EFE8"/>' % (FRONT, LOCK_BODY, LOCK_SHACKLE, KEYHOLE),
    ] + f)


def svg(title, view, w, h, body, defs=""):
    return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="%s" width="%s" height="%s" role="img">\n<title>%s</title>\n%s%s\n</svg>\n' % (view, w, h, title, defs, body)


ICON_DEFS = ('<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#2A2D35"/>'
             '<stop offset="1" stop-color="#15171C"/></linearGradient></defs>\n')
ICON_SCALE = 3.1


def icon_body():
    tx, ty = 256 - CX * ICON_SCALE, 256 - CY * ICON_SCALE
    return ('<rect width="512" height="512" rx="115" fill="url(#g)"/>\n<g transform="translate(%.1f %.1f) scale(%s)">\n%s\n</g>'
            % (tx, ty, ICON_SCALE, mark_color()))


# ---------- Android vector drawables ----------
HEAD = '<?xml version="1.0" encoding="utf-8"?>\n'


def vec_open(size, vp, aapt=False):
    ns = '\n    xmlns:aapt="http://schemas.android.com/aapt"' if aapt else ""
    return ('<vector xmlns:android="http://schemas.android.com/apk/res/android"%s\n    android:width="%sdp" android:height="%sdp" '
            'android:viewportWidth="%s" android:viewportHeight="%s">\n' % (ns, size, size, vp, vp))


def vpath(d, fill, extra=""):
    return '        <path android:fillColor="%s"%s android:pathData="%s"/>\n' % (fill, extra, d)


def flake_path(x, y, s):
    return "M%s %sh%sv%sh-%sz" % (x, y, s, s, s)


def vmark(colors, mono=None):
    out = ""
    if mono:
        out += vpath(BACK, mono, ' android:fillAlpha="0.4"')
        out += vpath(FRONT + LOCK_BODY + LOCK_SHACKLE + KEYHOLE, mono, ' android:fillType="evenOdd"')
    else:
        out += vpath(BACK, "#7A2A12") + vpath(FRONT, "#C2491D") + vpath(HIGHLIGHT, "#E8783A")
        out += vpath(LOCK_BODY, "#F4EFE8") + vpath(LOCK_SHACKLE, "#F4EFE8") + vpath(KEYHOLE, "#7A2A12")
    for (x, y, s, r, rot, pv, c) in FLAKES:
        out += ('        <group android:rotation="%s" android:pivotX="%s" android:pivotY="%s">\n    %s        </group>\n'
                % (rot, pv[0], pv[1], vpath(flake_path(x, y, s), mono or c)))
    return out


def group(scale, tx, ty, inner):
    return '    <group android:scaleX="%s" android:scaleY="%s" android:translateX="%.2f" android:translateY="%.2f">\n%s    </group>\n' % (scale, scale, tx, ty, inner)


def gradient_path(d, x1, y1, x2, y2, c1, c2):
    return ('    <path android:pathData="%s">\n        <aapt:attr name="android:fillColor">\n            <gradient android:type="linear" '
            'android:startX="%s" android:startY="%s" android:endX="%s" android:endY="%s" android:startColor="%s" android:endColor="%s"/>\n'
            '        </aapt:attr>\n    </path>\n' % (d, x1, y1, x2, y2, c1, c2))


def squircle(x, y, w, r):
    return "M%s %sH%sA%s %s 0 0 1 %s %sV%sA%s %s 0 0 1 %s %sH%sA%s %s 0 0 1 %s %sV%sA%s %s 0 0 1 %s %sZ" % (
        x + r, y, x + w - r, r, r, x + w, y + r, y + w - r, r, r, x + w - r, y + w, x + r, r, r, x, y + w - r, y + r, r, r, x + r, y)


def main():
    LOGO.mkdir(parents=True, exist_ok=True)
    view = "0 0 140 128"
    (LOGO / "mark.svg").write_text(svg("rostdav-mfa", view, 280, 256, mark_color()))
    (LOGO / "mark-mono.svg").write_text(svg("rostdav-mfa, single colour", view, 280, 256, mark_mono("#15171C")))
    (LOGO / "mark-negative.svg").write_text(svg("rostdav-mfa, negative on Rost", view, 280, 256, mark_negative()))
    (LOGO / "icon.svg").write_text(svg("rostdav-mfa app icon", "0 0 512 512", 512, 512, icon_body(), ICON_DEFS))

    s = 0.5
    tx, ty = 54 - CX * s, 54 - CY * s
    (RES / "ic_launcher_foreground.xml").write_text(HEAD + vec_open(108, 108) + group(s, tx, ty, vmark(None)) + "</vector>\n")
    (RES / "ic_launcher_monochrome.xml").write_text(HEAD + vec_open(108, 108) + group(s, tx, ty, vmark(None, mono="#000000")) + "</vector>\n")
    (RES / "ic_launcher_background.xml").write_text(
        HEAD + vec_open(108, 108, aapt=True) + gradient_path("M0,0h108v108h-108z", 0, 0, 108, 108, "#2A2D35", "#15171C") + "</vector>\n")
    s2 = 0.36
    (RES / "ic_splash.xml").write_text(
        HEAD + vec_open(108, 108, aapt=True) + gradient_path(squircle(18, 18, 72, 16), 18, 18, 90, 90, "#2A2D35", "#15171C")
        + group(s2, 54 - CX * s2, 54 - CY * s2, vmark(None)) + "</vector>\n")
    s3 = ICON_SCALE
    (RES / "ic_brand_mark.xml").write_text(
        HEAD + vec_open(48, 512, aapt=True) + gradient_path(squircle(0, 0, 512, 115), 0, 0, 512, 512, "#2A2D35", "#15171C")
        + group(s3, 256 - CX * s3, 256 - CY * s3, vmark(None)) + "</vector>\n")


if __name__ == "__main__":
    main()
