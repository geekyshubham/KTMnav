#!/usr/bin/env python3
"""WCAG 2.x contrast for every KTMnav 1.4 token pair; asserts each hex is still defined in the app sources."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs' / 'verification-1.4' / 'design-token-contrast.json'
SOURCES = [ROOT / 'app/src/main/java/app/ktmnav/MainActivity.java', ROOT / 'app/src/main/res/values/styles.xml',
           ROOT / 'app/src/main/res/values/colors.xml']
THEMES = {
    'KTM dark': {'bg': '#101214', 'surface': '#191D20', 'raised': '#22272B', 'tile': '#22272B', 'text': '#F2F4F5', 'muted': '#AAB5BC',
                 'line': '#71808A', 'accent': '#FF6600', 'onAccent': '#101214', 'accentText': '#FF6600', 'accentIcon': '#FF6600',
                 'danger': '#FFB4AB', 'success': '#7DD99B'},
    'Daylight': {'bg': '#F4F5F6', 'surface': '#FFFFFF', 'raised': '#E8ECEF', 'tile': '#FFF1E8', 'text': '#171B1E', 'muted': '#4D5B65',
                 'line': '#75848E', 'accent': '#FF6600', 'onAccent': '#101214', 'accentText': '#C24E00', 'accentIcon': '#E65C00',
                 'danger': '#B3261E', 'success': '#1E7B3A'},
    'Husqvarna dark': {'bg': '#101214', 'surface': '#191D20', 'raised': '#22272B', 'tile': '#22272B', 'text': '#F2F4F5', 'muted': '#AAB5BC',
                       'line': '#71808A', 'accent': '#8AB4F8', 'onAccent': '#101214', 'accentText': '#8AB4F8', 'accentIcon': '#8AB4F8',
                       'danger': '#FFB4AB', 'success': '#7DD99B'},
}
PAIRS = [
    ('text', 'bg', 'text', 4.5), ('text', 'surface', 'text', 4.5), ('text', 'raised', 'text', 4.5),
    ('muted', 'bg', 'text', 4.5), ('muted', 'surface', 'text', 4.5), ('muted', 'raised', 'text', 4.5),
    ('onAccent', 'accent', 'text', 4.5), ('accentText', 'surface', 'text', 4.5),
    ('accentIcon', 'tile', 'non-text', 3.0), ('accentIcon', 'surface', 'non-text', 3.0),
    ('line', 'surface', 'non-text', 3.0), ('danger', 'surface', 'text', 4.5), ('success', 'raised', 'non-text', 3.0),
    ('muted', 'surface', 'non-text', 3.0),
]


def luminance(hex_):
    c = [int(hex_[i:i + 2], 16) / 255 for i in (1, 3, 5)]
    c = [v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4 for v in c]
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]


def ratio(a, b):
    hi, lo = sorted((luminance(a), luminance(b)), reverse=True)
    return round((hi + 0.05) / (lo + 0.05), 2)


source = '\n'.join(p.read_text(encoding='utf-8') for p in SOURCES).upper()
rows, failures = [], []
for theme, t in THEMES.items():
    for hex_ in t.values():
        assert hex_.upper() in source, f'{theme}: {hex_} is not defined in the app sources'
    for fg, bg, kind, need in PAIRS:
        r = ratio(t[fg], t[bg])
        rows.append({'theme': theme, 'foreground': fg, 'fg_hex': t[fg], 'background': bg, 'bg_hex': t[bg], 'use': kind, 'ratio': r, 'required': need, 'pass': r >= need})
        if r < need:
            failures.append(rows[-1])
notes = {
    'daylight_accent_text_on_bg': ratio('#C24E00', '#F4F5F6'),
    'note': 'Daylight #C24E00 is used for text on white surfaces and dialogs only; text links on the #F4F5F6 page background use the text token.',
    'brand_fill_on_white_boundary': ratio('#FF6600', '#FFFFFF'),
    'brand_fill_note': 'Orange fills always carry #101214 labels (6.39:1); fill-to-surface boundary is not the sole identifier of the control.',
}
OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps({'passed': not failures, 'pairs': rows, 'failures': failures, 'notes': notes}, indent=2))
assert not failures, failures
print(f'PASS: {len(rows)} token pairs meet WCAG thresholds -> {OUT}')
