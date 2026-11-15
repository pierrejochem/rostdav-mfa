#!/usr/bin/env python3
"""Rewrite brand/logo/wordmark.svg, lockup-light.svg and lockup-dark.svg with the text outlined.

Needs fontTools (a development tool, not a CI dependency):
    python3 brand/logo/make_wordmark.py --font android/app/src/main/res/font/space_grotesk_bold.ttf
"""
import argparse
import re
from pathlib import Path

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.ttLib import TTFont

HERE = Path(__file__).resolve().parent
SIZE = 64.0
SPACING = -1.0


def outline(font_path, text, accent_from):
    font = TTFont(font_path)
    glyphs, cmap, hmtx = font.getGlyphSet(), font.getBestCmap(), font["hmtx"]
    scale = SIZE / font["head"].unitsPerEm
    x, parts = 0.0, []
    for i, ch in enumerate(text):
        name = cmap[ord(ch)]
        pen = SVGPathPen(glyphs)
        glyphs[name].draw(pen)
        parts.append((accent_from <= i < accent_from + 3, x, pen.getCommands()))
        x += hmtx[name][0] * scale + SPACING
    return parts, x - SPACING, scale


def group(parts, scale, ink, accent, baseline):
    out = []
    for is_accent, x, d in parts:
        if not d:
            continue
        out.append(
            '<path fill="%s" transform="translate(%.2f %.2f) scale(%.5f %.5f)" d="%s"/>'
            % (accent if is_accent else ink, x, baseline, scale, -scale, d)
        )
    return "\n".join(out)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--font", required=True)
    args = p.parse_args()
    parts, width, scale = outline(args.font, "rostdav-mfa", accent_from=4)
    baseline = 50.0
    mark = (HERE / "icon.svg").read_text()
    defs = re.search(r"<defs>.*?</defs>\n", mark, re.S).group(0)
    art = re.search(r'(<rect width="512".*?)\n</svg>', mark, re.S).group(1) + "\n"

    def svg(title, w, h, body, with_defs=False):
        return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" width="%d" height="%d" role="img">\n'
                "<title>%s</title>\n%s%s</svg>\n") % (w, h, w, h, title, defs if with_defs else "", body)

    wm_w = int(width) + 4
    (HERE / "wordmark.svg").write_text(
        svg("rostdav-mfa", wm_w, 72, group(parts, scale, "#15171C", "#C2491D", baseline) + "\n"))
    for name, ink, accent, title in (("light", "#15171C", "#C2491D", "rostdav-mfa lockup, light"),
                                     ("dark", "#F4ECE6", "#E8783A", "rostdav-mfa lockup, dark")):
        body = ('<g transform="scale(0.1875)">\n%s</g>\n<g transform="translate(112 12)">\n%s\n</g>\n'
                % (art, group(parts, scale, ink, accent, baseline)))
        (HERE / ("lockup-%s.svg" % name)).write_text(svg(title, 112 + wm_w + 12, 96, body, with_defs=True))


if __name__ == "__main__":
    main()
