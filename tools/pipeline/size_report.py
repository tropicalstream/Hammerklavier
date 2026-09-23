#!/usr/bin/env python3
"""Asset and APK size table (PLAN.md §6.10); fails above the cap of the approved sample option (T11.5).

    python3 tools/pipeline/size_report.py [--apk PATH]

Cap: 100 MB with `samples-hd` approved, 60 MB with `samples-standard` (MB = 10^6 bytes). With an
APK (the argument, or the newest app/build/outputs/apk/release/*.apk) its size is checked; without
one the assets plus the §6.10 code estimate (4 MB) are.
"""
import argparse
import glob
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common  # noqa: E402

MB = 1000 * 1000
CAPS = {"samples-hd": 100 * MB, "samples-standard": 60 * MB}
CODE_ESTIMATE = 4 * MB
BUDGET = {"hd": {"grand kit": 58.2, "upright kit": 12.9, "harpsichord kit": 4.7, "stub + probe + bench": 1.4,
                 "midi": 3.5, "catalogue, credits, licences, companion": 0.4},
          "standard": {"grand kit": 22.3, "upright kit": 12.9, "harpsichord kit": 4.7, "stub + probe + bench": 1.4,
                       "midi": 3.5, "catalogue, credits, licences, companion": 0.4}}


def approved_option(path=os.path.join(common.PIPELINE, "APPROVED")):
    try:
        with open(path) as f:
            ids = {ln.strip() for ln in f if ln.strip() and not ln.startswith("#")}
    except FileNotFoundError:
        ids = set()
    return "samples-hd" if "samples-hd" in ids else "samples-standard"


def dir_bytes(p):
    if os.path.isfile(p):
        return os.path.getsize(p)
    tot = 0
    for d, _s, fs in os.walk(p):
        tot += sum(os.path.getsize(os.path.join(d, f)) for f in fs)
    return tot


def categories(assets=common.ASSETS):
    ins = os.path.join(assets, "instruments")
    rows = [("grand kit", dir_bytes(os.path.join(ins, "grand"))),
            ("upright kit", dir_bytes(os.path.join(ins, "upright"))),
            ("harpsichord kit", dir_bytes(os.path.join(ins, "harpsichord"))),
            ("stub + probe + bench", dir_bytes(os.path.join(ins, "stub")) + dir_bytes(os.path.join(ins, "probe.opus"))),
            ("midi", dir_bytes(os.path.join(assets, "midi"))),
            ("catalogue, credits, licences, companion",
             sum(dir_bytes(os.path.join(assets, x)) for x in ("catalog.json", "credits.txt", "licenses", "companion.html")))]
    known = sum(b for _n, b in rows)
    rows.append(("other assets", max(0, dir_bytes(assets) - known)))
    return rows


def report(apk=None, assets=common.ASSETS, option=None):
    option = option or approved_option()
    cap = CAPS[option]
    budget = BUDGET["hd" if option == "samples-hd" else "standard"]
    rows = categories(assets)
    lines = ["%-42s %12s %10s" % ("content", "bytes", "budget MB")]
    for n, b in rows:
        lines.append("%-42s %12d %10s" % (n, b, ("%.1f" % budget[n]) if n in budget else "-"))
    assets_total = sum(b for _n, b in rows)
    lines.append("%-42s %12d" % ("assets total", assets_total))
    if apk:
        total, what = os.path.getsize(apk), "APK " + os.path.basename(apk)
    else:
        total, what = assets_total + CODE_ESTIMATE, "assets + 4 MB code estimate"
    ok = total <= cap
    lines.append("%-42s %12d  cap %d (%s): %s" % (what, total, cap, option, "OK" if ok else "OVER"))
    for n, b in rows:
        if n in budget and b > budget[n] * MB * 1.15:
            lines.append("warning: %s is %.1f MB, budget %.1f MB" % (n, b / MB, budget[n]))
    return ok, "\n".join(lines) + "\n"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--apk")
    a = ap.parse_args(argv)
    apk = a.apk
    if not apk:
        c = sorted(glob.glob(os.path.join(common.ROOT, "app", "build", "outputs", "apk", "release", "*.apk")),
                   key=os.path.getmtime)
        apk = c[-1] if c else None
    ok, text = report(apk)
    sys.stdout.write(text)
    common.write_text(os.path.join(common.BUILD, "size_report.txt"), text)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
