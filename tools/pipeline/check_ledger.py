#!/usr/bin/env python3
"""The licence ledger (PLAN.md §6.9): tools/pipeline/ledger.csv, one row per shipped asset.

    python3 tools/pipeline/check_ledger.py            # check (T11.4, part of tools/ci.sh)
    python3 tools/pipeline/check_ledger.py --update   # add rows for new pipeline outputs, refresh bytes/sha1

Checks: every file under assets/instruments, assets/midi and assets/licenses has a row; every row's
file exists with the recorded bytes and SHA-1; every modified=no row whose notes name a cache
source (`cache=<path>`) is byte-identical to it when that cache file is present; licence ids are
known. `--update` derives rows from rules (our generated files; the approved MIDI and licence
manifest rows; Sankey extractions; the sample kits) and never edits a row's source fields by hand.
"""
import argparse
import csv
import io
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common  # noqa: E402

LEDGER = os.path.join(common.PIPELINE, "ledger.csv")
FIELDS = ["asset_path", "bytes", "sha1", "source_name", "source_url", "licence_id", "licence_url", "credit",
          "modified", "how_modified", "notes"]
SCANNED = ("instruments", "midi", "licenses")
UNLEDGERED = {"licenses/SOURCES.csv"}      # the published copy of the ledger itself
LICENCE_URLS = {
    "CC0-1.0": "https://creativecommons.org/publicdomain/zero/1.0/",
    "CC-BY-3.0": "https://creativecommons.org/licenses/by/3.0/",
    "CC-BY-SA-3.0": "https://creativecommons.org/licenses/by-sa/3.0/",
    "CC-BY-SA-3.0-DE": "https://creativecommons.org/licenses/by-sa/3.0/de/deed.en",
    "CC-BY-SA-4.0": "https://creativecommons.org/licenses/by-sa/4.0/",
    "SANKEY": "https://www.johnsankey.ca/copyright.html",
    "PD": "https://creativecommons.org/publicdomain/mark/1.0/",
    "PD-self": "https://creativecommons.org/publicdomain/mark/1.0/",
    "NONE-STATED": "",
}
# the manifest's free-text licence column → a ledger licence id
MANIFEST_LICENCE = {"CC0": "CC0-1.0", "CC BY-SA 3.0 / GFDL": "CC-BY-SA-3.0"}
SAMPLE_MOD = "trimmed, resampled where needed, level-matched, normalised, Opus-encoded"
KIT_SOURCES = {
    "grand": ("Salamander Grand Piano V3 (Alexander Holm; Markus Fiedler; kinwie)",
              "https://github.com/sfzinstruments/SalamanderGrandPiano", "CC-BY-3.0",
              "Alexander Holm (Salamander Grand Piano V3), retuning Markus Fiedler, SFZ data kinwie"),
    "upright": ("VCSL Upright Piano, Knight + VSCO-2 CE upright pp (Versilian Studios / Sam Gossner; Simon Dalzell)",
                "https://github.com/sgossner/VCSL", "CC0-1.0", "Versilian Studios LLC / Sam Gossner; Simon Dalzell"),
    "harpsichord": ("VCSL Harpsichord, Flemish (Versilian Studios / Sam Gossner)",
                    "https://github.com/sgossner/VCSL", "CC0-1.0", "Versilian Studios LLC / Sam Gossner"),
}
OWN = ("Hammerklavier pipeline (generated)", "CC0-1.0", "tropicalstream")


def read_ledger(path=LEDGER):
    if not os.path.isfile(path):
        return []
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def write_ledger(rows, path=LEDGER):
    buf = io.StringIO()
    w = csv.DictWriter(buf, fieldnames=FIELDS, lineterminator="\n")
    w.writeheader()
    for r in sorted(rows, key=lambda r: r["asset_path"]):
        w.writerow({k: r.get(k, "") for k in FIELDS})
    common.write_text(path, buf.getvalue())


def scan(assets=common.ASSETS):
    out = []
    for top in SCANNED:
        base = os.path.join(assets, top)
        for d, _s, files in os.walk(base):
            for f in files:
                if f.startswith("."):
                    continue
                rel = os.path.relpath(os.path.join(d, f), assets).replace(os.sep, "/")
                if rel in UNLEDGERED:
                    continue
                out.append(os.path.relpath(os.path.join(d, f), assets).replace(os.sep, "/"))
    return sorted(out)


def rule_row(rel, assets=common.ASSETS):
    """A ledger row for `rel` (relative to assets/) derived from the pipeline's rules, or None."""
    p = os.path.join(assets, rel)
    base = {"asset_path": rel, "bytes": str(os.path.getsize(p)), "sha1": common.sha1_file(p)}
    parts = rel.split("/")
    if rel == "instruments/probe.opus" or rel.startswith("instruments/stub/"):
        tool = "tools/pipeline/make_probe.py" if rel.endswith("probe.opus") else "tools/pipeline/make_stub_bank.py"
        return dict(base, source_name=OWN[0], source_url=tool, licence_id=OWN[1], licence_url=LICENCE_URLS[OWN[1]],
                    credit=OWN[2], modified="no", how_modified="", notes="synthesised by " + tool)
    if rel.startswith("midi/test/"):
        return dict(base, source_name=OWN[0], source_url="tools/pipeline/make_test_midis.py", licence_id=OWN[1],
                    licence_url=LICENCE_URLS[OWN[1]], credit=OWN[2], modified="no", how_modified="",
                    notes="written by tools/pipeline/make_test_midis.py")
    if parts[0] == "instruments" and len(parts) > 2 and parts[1] in KIT_SOURCES:
        name, url, lic, credit = KIT_SOURCES[parts[1]]
        return dict(base, source_name=name, source_url=url, licence_id=lic, licence_url=LICENCE_URLS[lic],
                    credit=credit, modified="yes", how_modified=SAMPLE_MOD, notes="built by tools/pipeline/kit_build.py")
    man = manifest_rows()
    if rel in man:
        m = man[rel]
        lic = MANIFEST_LICENCE.get(m["licence"], m["licence"])
        return dict(base, source_name=m["group"], source_url=m["url"], licence_id=lic,
                    licence_url=LICENCE_URLS.get(lic, ""), credit=credit_for(m["group"]), modified="no",
                    how_modified="", notes="cache=" + m["cache_path"])
    sk = sankey_rows()
    if rel in sk:
        s = sk[rel]
        return dict(base, source_name="sankey", source_url="http://www.jsbach.net/midi/sankey/" + s["zip"],
                    licence_id="SANKEY", licence_url=LICENCE_URLS["SANKEY"], credit=credit_for("sankey"),
                    modified="no", how_modified="", notes="zip=%s entry=%s" % (s["zip"], s["entry"]))
    return None


def credit_for(group):
    return {"krueger": "Bernd Krueger, piano-midi.de", "sankey": "John Sankey, johnsankey.ca",
            "commons": "Wikimedia Commons contributors (see notes)", "mutopia": "The Mutopia Project",
            "imslp": "Pierre Gouin, Les Éditions Outremontaises", "madore": "David Madore",
            "licence": "licence text"}.get(group, group)


_MAN = None


def manifest_rows():
    global _MAN
    if _MAN is None:
        _MAN = {}
        p = os.path.join(common.MANIFESTS, "midi.tsv")
        if os.path.isfile(p):
            for r in common.read_tsv(p):
                ap = r.get("asset_path") or ""
                if ap.startswith("app/src/main/assets/"):
                    _MAN[ap[len("app/src/main/assets/"):]] = r
    return _MAN


def sankey_rows():
    out = {}
    p = os.path.join(common.BUILD, "sankey_extracted.tsv")
    if os.path.isfile(p):
        for r in common.read_tsv(p):
            a = r["asset"]
            if a.startswith("app/src/main/assets/"):
                out[a[len("app/src/main/assets/"):]] = r
    return out


def check(rows, assets=common.ASSETS, root=common.ROOT):
    errs = []
    by = {}
    for r in rows:
        if r["asset_path"] in by:
            errs.append("duplicate row " + r["asset_path"])
        by[r["asset_path"]] = r
        missing = [f for f in ("asset_path", "bytes", "sha1", "source_name", "licence_id", "modified")
                   if not r.get(f)]
        if missing:
            errs.append("%s: empty %s" % (r["asset_path"], ", ".join(missing)))
        if r.get("licence_id") and r["licence_id"] not in LICENCE_URLS:
            errs.append("%s: unknown licence %s" % (r["asset_path"], r["licence_id"]))
        if r.get("modified") not in ("yes", "no"):
            errs.append("%s: modified must be yes/no" % r["asset_path"])
        if r.get("modified") == "yes" and not r.get("how_modified"):
            errs.append("%s: modified=yes needs how_modified" % r["asset_path"])
        p = os.path.join(assets, r["asset_path"])
        if not os.path.isfile(p):
            errs.append("%s: file missing" % r["asset_path"])
            continue
        if str(os.path.getsize(p)) != r["bytes"]:
            errs.append("%s: %d bytes, ledger says %s" % (r["asset_path"], os.path.getsize(p), r["bytes"]))
        sha = common.sha1_file(p)
        if sha != r["sha1"]:
            errs.append("%s: sha1 differs from the ledger" % r["asset_path"])
        if r.get("modified") == "no":
            for tok in (r.get("notes") or "").split():
                if tok.startswith("cache="):
                    src = os.path.join(root, tok[6:])
                    if os.path.isfile(src) and common.sha1_file(src) != sha:
                        errs.append("%s: modified=no but differs from its source %s" % (r["asset_path"], tok[6:]))
    for rel in scan(assets):
        if rel not in by:
            errs.append("%s: no ledger row" % rel)
    return errs


def update(rows, assets=common.ASSETS):
    by = {r["asset_path"]: r for r in rows}
    present = set(scan(assets))
    out, unknown = [], []
    for rel in sorted(present):
        new = rule_row(rel, assets)
        if new is None:
            if rel in by:                        # hand-added row: refresh the measured fields only
                p = os.path.join(assets, rel)
                old = dict(by[rel])
                old["bytes"], old["sha1"] = str(os.path.getsize(p)), common.sha1_file(p)
                out.append(old)
            else:
                unknown.append(rel)
            continue
        out.append(new)
    return out, unknown


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--update", action="store_true")
    a = ap.parse_args(argv)
    rows = read_ledger()
    if a.update:
        rows, unknown = update(rows)
        write_ledger(rows)
        print("ledger: %d rows written" % len(rows))
        for u in unknown:
            print("ledger: no rule for %s (add its row by hand)" % u, file=sys.stderr)
        if unknown:
            return 1
    errs = check(read_ledger())
    for e in errs:
        print("ledger: " + e, file=sys.stderr)
    print("ledger: %d rows, %s" % (len(read_ledger()), "ok" if not errs else "%d problems" % len(errs)))
    return 1 if errs else 0


if __name__ == "__main__":
    sys.exit(main())
