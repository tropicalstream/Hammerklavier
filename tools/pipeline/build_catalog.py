#!/usr/bin/env python3
"""catalog_src.json (hand-written) + measured MIDI facts → assets/catalog.json (PLAN.md §4.6, §4.7, §6.7).

    python3 tools/pipeline/build_catalog.py                      # the full catalogue
    python3 tools/pipeline/build_catalog.py --partial            # Start-here works only (M2)
    python3 tools/pipeline/build_catalog.py --fixture            # core/src/test/resources/wp11/catalog_fixture.json

Hand-written in the source: shelves, ids, titles, composers, years, eras, default and alternative
instruments, sources, tiers, tuning overrides, movement titles and assets (optionally
`expectDurationSec` from the site listing, with `durationException: true` for the known ones).
Measured here, never typed: sha1Hex, sha1b32 (Krueger: must match docs/manifests/midi.tsv),
bytes, durationSec, lowKey, highKey, notes, hasSustain/hasSoft/hasSostenuto, pedalMode, folds,
and velocityPolicy ("flat" when ≥ 95% of every movement's note-ons share one velocity, unless the
source forces it).

Fails (exit 1) on a missing asset, a missing licence file, folding > 2% for the default
instrument, a Krueger SHA-1 mismatch, or a duration more than 10% off the listing (outside the
known exceptions). Writes build/listen.txt (tier-C movements for L-6).
"""
import argparse
import base64
import datetime
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common  # noqa: E402
import smf_stats  # noqa: E402

SRC = os.path.join(common.PIPELINE, "catalog_src.json")
FIXTURE_SRC = os.path.join(common.PIPELINE, "fixtures", "catalog_fixture_src.json")
OUT = os.path.join(common.ASSETS, "catalog.json")
FIXTURE_OUT = os.path.join(common.TEST_RES, "catalog_fixture.json")
INSTRUMENTS = ("grand", "upright", "harpsichord")
ERAS = ("baroque", "galant", "classical")
TEMPERAMENTS = ("EQUAL", "WERCKMEISTER_III", "KELLNER", "VALLOTTI", "YOUNG_II", "KIRNBERGER_III", "LEHMAN",
                "MEANTONE_QUARTER")
FOLD_LIMIT = 0.02
DURATION_TOLERANCE = 0.10


class CatalogError(Exception):
    pass


def krueger_sha1s():
    """asset path (relative to assets/) → manifest sha1_b32, for rows that carry one."""
    out = {}
    p = os.path.join(common.MANIFESTS, "midi.tsv")
    if not os.path.isfile(p):
        return out
    for row in common.read_tsv(p):
        ap = row.get("asset_path") or ""
        if row.get("sha1_b32") and ap.startswith("app/src/main/assets/"):
            out[ap[len("app/src/main/assets/"):]] = row["sha1_b32"]
    return out


def build(src, assets_dir=common.ASSETS, partial=False, generated=None, check_licences=True):
    errors, listen = [], []
    manifest = krueger_sha1s()
    sources = src["sources"]
    for sid, s in sources.items():
        for f in ("credit", "licence", "licenceUrl", "sourceUrl", "licenceFile", "performanceType", "tier",
                  "exportAllowed"):
            if f not in s:
                errors.append("source %s: missing %s" % (sid, f))
        if check_licences and s.get("licenceFile") and not os.path.isfile(os.path.join(assets_dir, s["licenceFile"])):
            errors.append("source %s: licence file %s missing" % (sid, s["licenceFile"]))
    start = list(src.get("startHere", []))
    works_out = []
    movement_ids = set()
    for w in src["works"]:
        wid = w["id"]
        if w["defaultInstrument"] not in INSTRUMENTS or any(a not in INSTRUMENTS for a in w.get("altInstruments", [])):
            errors.append("%s: unknown instrument" % wid)
        if w.get("era") not in ERAS:
            errors.append("%s: era %r not in %s" % (wid, w.get("era"), ERAS))
        if w["source"] not in sources:
            errors.append("%s: unknown source %s" % (wid, w["source"]))
            continue
        tun = w.get("tuning")
        if tun is not None and (set(tun) != {"aHz", "temperament"} or tun["temperament"] not in TEMPERAMENTS):
            errors.append("%s: tuning must be null or {aHz, temperament}" % wid)
        mv_out, flats = [], []
        for i, m in enumerate(w["movements"], 1):
            mid = "%s.%d" % (wid, i)
            movement_ids.add(mid)
            p = os.path.join(assets_dir, m["asset"])
            if not os.path.isfile(p):
                errors.append("%s: asset %s missing" % (mid, m["asset"]))
                continue
            with open(p, "rb") as f:
                data = f.read()
            st = smf_stats.stats(data, m["asset"])
            row = {"id": mid, "title": m["title"], "asset": m["asset"], "sha1Hex": st["sha1Hex"]}
            if w["source"] == "krueger":
                b32 = base64.b32encode(bytes.fromhex(st["sha1Hex"])).decode()
                row["sha1b32"] = b32
                want = manifest.get(m["asset"])
                if want and want != b32:
                    errors.append("%s: SHA-1 %s does not match the manifest %s" % (mid, b32, want))
            row.update({"bytes": st["bytes"], "durationSec": st["durationSec"], "lowKey": st["lowKey"],
                        "highKey": st["highKey"], "notes": st["notes"], "hasSustain": st["hasSustain"],
                        "hasSoft": st["hasSoft"], "hasSostenuto": st["hasSostenuto"], "pedalMode": st["pedalMode"],
                        "folds": dict(st["folds"])})
            folded = st["folds"][w["defaultInstrument"]]
            if st["notes"] and folded > FOLD_LIMIT * st["notes"]:
                errors.append("%s: %d of %d notes fold on the default %s (> 2%%)" % (
                    mid, folded, st["notes"], w["defaultInstrument"]))
            exp = m.get("expectDurationSec")
            if exp and not m.get("durationException") and abs(st["durationSec"] - exp) > DURATION_TOLERANCE * exp:
                errors.append("%s: duration %.1f s vs listing %.1f s" % (mid, st["durationSec"], exp))
            flats.append(st["flatVelocity"])
            if w.get("tier", sources[w["source"]]["tier"]) == "C":
                listen.append("%s\t%s\t%s" % (mid, m["asset"], m["title"]))
            mv_out.append(row)
        policy = w.get("velocityPolicy") or ("flat" if flats and all(flats) else "as-is")
        works_out.append({
            "id": wid, "composer": w["composer"], "composerShort": w["composerShort"], "title": w["title"],
            "shortTitle": w["shortTitle"], "catalogue": w.get("catalogue"), "year": w.get("year"),
            "era": w["era"], "shelf": w["shelf"], "defaultInstrument": w["defaultInstrument"],
            "altInstruments": list(w.get("altInstruments", [])), "source": w["source"],
            "tier": w.get("tier", sources[w["source"]]["tier"]), "velocityPolicy": policy,
            "tuning": tun, "movements": mv_out})
    shelves = [{"id": s["id"], "title": s["title"], "works": list(s["works"])} for s in src["shelves"]]
    known = {w["id"] for w in works_out}
    for s in shelves:
        for wid in s["works"]:
            if wid not in known and not src.get("optionalWorks", {}).get(wid):
                errors.append("shelf %s: unknown work %s" % (s["id"], wid))
    for w in works_out:
        if not any(w["id"] in s["works"] for s in shelves if s["id"] == w["shelf"]):
            errors.append("%s: not listed on its shelf %s" % (w["id"], w["shelf"]))
    start = [m for m in start if m in movement_ids]
    if partial:
        keep = {m.rsplit(".", 1)[0] for m in start}
        works_out = [w for w in works_out if w["id"] in keep]
        shelves = [dict(s, works=[x for x in s["works"] if x in keep]) for s in shelves]
        shelves = [s for s in shelves if s["works"]]
        used = {w["source"] for w in works_out}
        sources = {k: v for k, v in sources.items() if k in used}
    else:
        shelves = [dict(s, works=[x for x in s["works"] if x in known]) for s in shelves]
        shelves = [s for s in shelves if s["works"]]          # e.g. Handel when HWV 430 is not installed
    gen = generated or datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    cat = {"schema": 1, "generated": gen, "startHere": start, "shelves": shelves,
           "sources": {k: dict(v) for k, v in sources.items()}, "works": works_out}
    return cat, errors, listen


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--src")
    ap.add_argument("--out")
    ap.add_argument("--partial", action="store_true")
    ap.add_argument("--fixture", action="store_true")
    ap.add_argument("--generated", help="fixed ISO timestamp (reproducible output)")
    a = ap.parse_args(argv)
    src_path = a.src or (FIXTURE_SRC if a.fixture else SRC)
    out = a.out or (FIXTURE_OUT if a.fixture else OUT)
    with open(src_path, encoding="utf-8") as f:
        src = json.load(f)
    gen = a.generated or (src.get("generated") if a.fixture else None)
    cat, errors, listen = build(src, partial=a.partial, generated=gen, check_licences=not a.fixture)
    if errors:
        for e in errors:
            print("catalog: " + e, file=sys.stderr)
        return 1
    common.write_text(out, common.json_dumps(cat, digits=3))
    if not a.fixture:
        common.write_text(os.path.join(common.BUILD, "listen.txt"), "\n".join(listen) + ("\n" if listen else ""))
    n = sum(len(w["movements"]) for w in cat["works"])
    print("catalog: %d works, %d movements -> %s" % (len(cat["works"]), n, common.rel_to_root(out)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
