#!/usr/bin/env python3
"""Hammerklavier launcher icon: one source, emitted as Android vector drawables and preview SVGs.
   python3 tools/icon/make_icon.py        -> res/drawable/ic_launcher_{background,foreground,monochrome}.xml
                                             + build/icon/{icon_round,icon_square}.svg (for tools/icon/render.sh)
A gilt rocaille cartouche on candlelit walnut frames black lacquer; a felt hammer rises into three
ringing strings; its shank rests on a sliver of ivory and ebony keys. 108 viewport, 66dp safe zone."""
import os
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RES = os.path.join(ROOT, "app/src/main/res/drawable")
OUT = os.path.join(ROOT, "build/icon")

def lin(x1, y1, x2, y2, *stops): return ("linear", (x1, y1, x2, y2), stops)
def rad(cx, cy, r, *stops): return ("radial", (cx, cy, r), stops)
def P(d, fill=None, stroke=None, w=0, alpha=1.0, cap="round"): return dict(d=d, fill=fill, stroke=stroke, w=w, a=alpha, cap=cap)

GILT = lin(30, 22, 78, 86, (0, "#FFFFDE96"), (0.35, "#FFE2A84E"), (0.6, "#FF9A6A2A"), (0.8, "#FFE2A84E"), (1, "#FFFFDE96"))
GILT_V = lin(0, 18, 0, 34, (0, "#FFFFE9B4"), (0.5, "#FFE2A84E"), (1, "#FF8A5C22"))
SHIELD = "M54,27 C66,27 76,35 76,50 L76,60 C76,73 66,80 54,83 C42,80 32,73 32,60 L32,50 C32,35 42,27 54,27Z"
INNER = "M54,30.2 C64,30.2 72.8,37 72.8,50.5 L72.8,59.5 C72.8,71 64,77 54,79.6 C44,77 35.2,71 35.2,59.5 L35.2,50.5 C35.2,37 44,30.2 54,30.2Z"

background = [
    P("M0,0h108v108h-108z", fill=rad(54, 38, 78, (0, "#FF5A3820"), (0.45, "#FF2B1A10"), (1, "#FF0E0804"))),
    # panelling: two faint vertical stiles and a rail, lit from the candle above
    P("M20,0v108M88,0v108", stroke="#FF4A2E1A", w=1.2, alpha=0.55),
    P("M0,92h108", stroke="#FF4A2E1A", w=1.2, alpha=0.55),
]

def keys():
    out = []
    x0, y0, kw, kh = 39.0, 66.0, 5.0, 8.0
    for i in range(6):
        x = x0 + i * kw
        out.append(P(f"M{x+0.35:.2f},{y0}h{kw-0.7:.2f}v{kh-0.8:.2f}q0,0.8 -0.8,0.8h{-(kw-2.3):.2f}q-0.8,0 -0.8,-0.8z",
                     fill=lin(0, y0, 0, y0 + kh, (0, "#FFFFF8E6"), (1, "#FFD9C9A4"))))
    for b in (1, 2, 4, 5):
        x = x0 + b * kw - 1.6
        out.append(P(f"M{x:.2f},{y0}h3.2v4.8h-3.2z", fill=lin(x, 0, x + 3.2, 0, (0, "#FF222226"), (0.5, "#FF70767F"), (1, "#FF16161A"))))
    return out

foreground = [
    # lacquer field inside the cartouche
    P(SHIELD, fill=rad(54, 42, 40, (0, "#FF3A3A40"), (0.6, "#FF222226"), (1, "#FF0C0C0E"))),
    # candle glow where felt meets string
    P("M38,45a16,8 0,1 1,32 0a16,8 0,1 1,-32 0z", fill=rad(54, 45, 16, (0, "#AAFFDE96"), (0.5, "#33E2A84E"), (1, "#00E2A84E"))),
    # three strings, brightest at the blow
    *[P(f"M{a},{y}H{b}", stroke=lin(34, 0, 74, 0, (0, "#FF5C6068"), (0.5, "#FFFFF4D6"), (1, "#FF5C6068")), w=1.3, cap="butt") for a, y, b in ((39.6, 38, 68.4), (37.2, 41.5, 70.8), (35.9, 45, 72.1))],
    # felt head (ivory felt, lit from above-left), walnut moulding, shank
    P("M47,50.5 C47,45.8 50,44.9 54,44.9 C58,44.9 61,45.8 61,50.5 L60.2,55.5 L47.8,55.5 Z",
      fill=lin(46, 44, 62, 56, (0, "#FFFFF8E6"), (0.55, "#FFE8DCC0"), (1, "#FFA89878"))),
    P("M47.8,55.5 L60.2,55.5 L58.8,59.5 L49.2,59.5 Z", fill=lin(0, 55.5, 0, 59.5, (0, "#FF7A5230"), (1, "#FF2B1A10"))),
    P("M52.8,59.5 L55.2,59.5 L55.2,66 L52.8,66 Z", fill=lin(52.8, 0, 55.2, 0, (0, "#FFB8894C"), (1, "#FF4A2E1A"))),
    *keys(),
    # the gilt cartouche
    P(SHIELD, stroke=GILT, w=2.6),
    # lustre: a candle highlight running down the upper-left of the gilt
    P("M50,27.3 C42,28.3 34.2,34 33.3,46", stroke="#CCFFF4D6", w=0.8),
    P(INNER, stroke="#FFE2A84E", w=0.5, alpha=0.6),
    # rocaille shell crest, flanked by two curling leaves
    P("M47,29 C47,23 50.5,20.5 54,20.5 C57.5,20.5 61,23 61,29 C58.5,27.6 56.3,27.2 54,27.2 C51.7,27.2 49.5,27.6 47,29Z", fill=GILT_V),
    P("M54,27V21.6M51,27.5L49.6,23.2M57,27.5L58.4,23.2", stroke="#FF6A4418", w=0.6),
    P("M46.5,29.5 C42,28 38.5,30 38,33.5 C37.7,35.6 40,36.2 40.6,34.6", stroke=GILT, w=1.4),
    P("M61.5,29.5 C66,28 69.5,30 70,33.5 C70.3,35.6 68,36.2 67.4,34.6", stroke=GILT, w=1.4),
    # side cartouche scrolls, curling in
    P("M32,52 C28.5,53 28.5,58 32,58.5", stroke=GILT, w=1.4),
    P("M76,52 C79.5,53 79.5,58 76,58.5", stroke=GILT, w=1.4),
    # foot: a small acanthus drop
    P("M49,82 C51.5,82.6 52.5,85 54,86.4 C55.5,85 56.5,82.6 59,82 C56.5,84 55.3,83.8 54,83.2 C52.7,83.8 51.5,84 49,82Z", fill=GILT_V),
]

MONO_SKIP = set()
def mono_layer():
    # a silhouette: frame, crest, strings, hammer, keys block
    W = "#FFFFFFFF"
    return [P(SHIELD, stroke=W, w=3),
            P("M47,29 C47,23 50.5,20.5 54,20.5 C57.5,20.5 61,23 61,29 C58.5,27.6 56.3,27.2 54,27.2 C51.7,27.2 49.5,27.6 47,29Z", fill=W),
            *[P(f"M{a},{y}H{b}", stroke=W, w=1.5, cap="butt") for a, y, b in ((39.6, 38, 68.4), (37.2, 41.5, 70.8), (35.9, 45, 72.1))],
            P("M47,50.5 C47,45.8 50,44.9 54,44.9 C58,44.9 61,45.8 61,50.5 L60.2,55.5 L58.8,59.5 L55.2,59.5 L55.2,65 L52.8,65 L52.8,59.5 L49.2,59.5 L47.8,55.5 Z", fill=W),
            P("M39,66h30v7.5h-30z", fill=W, alpha=0.9)]

# ---- Android vector ----
def a_grad(attr, g):
    kind, geo, stops = g
    if kind == "linear":
        x1, y1, x2, y2 = geo; hdr = f'android:type="linear" android:startX="{x1}" android:startY="{y1}" android:endX="{x2}" android:endY="{y2}"'
    else:
        cx, cy, r = geo; hdr = f'android:type="radial" android:centerX="{cx}" android:centerY="{cy}" android:gradientRadius="{r}"'
    items = "".join(f'\n                <item android:offset="{o}" android:color="{c}" />' for o, c in stops)
    return f'\n        <aapt:attr name="android:{attr}">\n            <gradient {hdr}>{items}\n            </gradient>\n        </aapt:attr>'

def vector(paths, comment):
    body = []
    for p in paths:
        attrs = [f'android:pathData="{p["d"]}"']; kids = ""
        if p["fill"]:
            if isinstance(p["fill"], str): attrs.append(f'android:fillColor="{p["fill"]}"')
            else: kids += a_grad("fillColor", p["fill"])
            if p["a"] != 1: attrs.append(f'android:fillAlpha="{p["a"]}"')
        if p["stroke"]:
            attrs.append(f'android:strokeWidth="{p["w"]}"'); attrs.append(f'android:strokeLineCap="{p["cap"]}"'); attrs.append('android:strokeLineJoin="round"')
            if isinstance(p["stroke"], str): attrs.append(f'android:strokeColor="{p["stroke"]}"')
            else: kids += a_grad("strokeColor", p["stroke"])
            if p["a"] != 1: attrs.append(f'android:strokeAlpha="{p["a"]}"')
        a = "\n        ".join(attrs)
        body.append(f'    <path\n        {a}>{kids}\n    </path>' if kids else f'    <path\n        {a} />')
    return ('<?xml version="1.0" encoding="utf-8"?>\n<!-- GENERATED by tools/icon/make_icon.py; edit there. ' + comment + ' -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    xmlns:aapt="http://schemas.android.com/aapt"\n'
            '    android:width="108dp"\n    android:height="108dp"\n    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
            + "\n".join(body) + "\n</vector>\n")

# ---- SVG preview ----
def argb(c):  # #AARRGGBB -> (#RRGGBB, alpha)
    return "#" + c[3:], int(c[1:3], 16) / 255
def svg(paths, defs, n):
    out = []
    for p in paths:
        s = []
        for key, attr in (("fill", "fill"), ("stroke", "stroke")):
            v = p[key]
            if v is None: s.append(f'{attr}="none"'); continue
            if isinstance(v, str):
                col, al = argb(v); s.append(f'{attr}="{col}" {attr}-opacity="{al*p["a"]:.3f}"')
            else:
                gid = f"g{len(defs)}"; kind, geo, stops = v
                st = "".join(f'<stop offset="{o}" stop-color="{argb(c)[0]}" stop-opacity="{argb(c)[1]:.3f}"/>' for o, c in stops)
                if kind == "linear":
                    x1, y1, x2, y2 = geo; defs.append(f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}">{st}</linearGradient>')
                else:
                    cx, cy, r = geo; defs.append(f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" cx="{cx}" cy="{cy}" r="{r}">{st}</radialGradient>')
                s.append(f'{attr}="url(#{gid})"' + (f' {attr}-opacity="{p["a"]}"' if p["a"] != 1 else ""))
        if p["stroke"]: s.append(f'stroke-width="{p["w"]}" stroke-linecap="{p["cap"]}" stroke-linejoin="round"')
        out.append(f'<path d="{p["d"]}" {" ".join(s)}/>')
    return "\n".join(out)

def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True); open(path, "w").write(text)

write(os.path.join(RES, "ic_launcher_background.xml"), vector(background, "Candlelit walnut panelling."))
write(os.path.join(RES, "ic_launcher_foreground.xml"), vector(foreground, "Gilt cartouche, hammer mid-strike on three strings, ivory/ebony keys."))
write(os.path.join(RES, "ic_launcher_monochrome.xml"), vector(mono_layer(), "Themed-icon silhouette."))
for name, clip in (("icon_round", True), ("icon_square", False), ("icon_mono", True)):
    defs = []
    layers = (svg(mono_layer(), defs, 0) if name == "icon_mono" else svg(background, defs, 0) + svg(foreground, defs, 1))
    bg = '<rect x="0" y="0" width="108" height="108" fill="#3C4A5A"/>' if name == "icon_mono" else ""
    cp = '<clipPath id="m"><circle cx="54" cy="54" r="36"/></clipPath>' if clip else ""
    g = f'<g clip-path="url(#m)">{bg}{layers}</g>' if clip else layers
    write(os.path.join(OUT, name + ".svg"),
          f'<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="18 18 72 72"><defs>{"".join(defs)}{cp}</defs>{g}</svg>\n')
print("ok")
