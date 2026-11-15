#!/usr/bin/env python3
"""WCAG 2.1 contrast check over the colour pairs in brand/tokens.json."""
import argparse
import json
import sys
from pathlib import Path

DEFAULT_TOKENS = Path(__file__).resolve().parent / "tokens.json"

# (foreground, background, minimum ratio). 4.5 body text, 3 large text and icons.
PAIRS = [
    ("ink", "paper", 4.5),
    ("ink", "surface", 4.5),
    ("slate", "paper", 4.5),
    ("slate", "surface", 4.5),
    ("onRost", "rost600", 4.5),
    ("rost600", "paper", 4.5),
    ("rost600", "surface", 4.5),
    ("ok", "surface", 4.5),
    ("danger", "surface", 4.5),
    ("rost500", "paper", 3.0),
]


def _channel(c):
    c = c / 255
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4


def luminance(hex_color):
    h = hex_color.lstrip("#")
    r, g, b = (int(h[i:i + 2], 16) for i in (0, 2, 4))
    return 0.2126 * _channel(r) + 0.7152 * _channel(g) + 0.0722 * _channel(b)


def ratio(a, b):
    la, lb = luminance(a), luminance(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def check(tokens):
    """Return one message per failing pair, naming the theme and the pair."""
    failures = []
    for theme in ("light", "dark"):
        colors = tokens["colors"][theme]
        for fg, bg, minimum in PAIRS:
            r = ratio(colors[fg], colors[bg])
            if r < minimum:
                failures.append("%s %s/%s: %.2f:1, needs %.1f:1" % (theme, fg, bg, r, minimum))
    return failures


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--tokens", type=Path, default=DEFAULT_TOKENS)
    args = p.parse_args(argv)
    failures = check(json.loads(args.tokens.read_text()))
    for f in failures:
        print("contrast failure: " + f, file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
