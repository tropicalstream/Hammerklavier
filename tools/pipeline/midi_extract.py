#!/usr/bin/env python3
"""Sankey zips → byte-identical bundled MIDI, and the other approved MIDI/licence files → assets
(PLAN.md §6.7 step 2, §6.9).

    python3 tools/pipeline/midi_extract.py --list        # build/sankey_listing.txt (every zip entry)
    python3 tools/pipeline/midi_extract.py --extract     # sankey_map.tsv rows → assets/midi/sankey/<zip-stem>/<entry>
    python3 tools/pipeline/midi_extract.py --copy        # manifest rows with an asset_path, copied byte-for-byte

`sankey_map.tsv` (zip, entry, movement_id, title) is filled by hand from the listing and the §4.7
table. Extraction never rewrites bytes; each entry's SHA-1 is recorded in
build/sankey_extracted.tsv and checked against the zip's own CRC. Rows whose cache file is
absent (IMSLP not clicked, Couperin not permitted) are skipped with a note.
"""
import argparse
import os
import shutil
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common  # noqa: E402

SANKEY_CACHE = os.path.join(common.CACHE, "midi", "sankey")
SANKEY_MAP = os.path.join(common.PIPELINE, "sankey_map.tsv")
SANKEY_OUT = os.path.join(common.MIDI_ASSETS, "sankey")


def listing(cache=SANKEY_CACHE):
    rows = []
    for z in sorted(f for f in os.listdir(cache) if f.lower().endswith(".zip")):
        with zipfile.ZipFile(os.path.join(cache, z)) as zf:
            for i, info in enumerate(zf.infolist()):
                if info.is_dir():
                    continue
                data = zf.read(info)
                rows.append((z, i, info.filename, info.file_size, common.sha1_bytes(data)))
    return rows


def extract(map_path=SANKEY_MAP, cache=SANKEY_CACHE, out_root=SANKEY_OUT):
    rows = [r for r in common.read_tsv(map_path) if r.get("zip")]
    done = []
    for r in rows:
        zp = os.path.join(cache, r["zip"])
        with zipfile.ZipFile(zp) as zf:
            data = zf.read(r["entry"])          # zipfile checks the CRC-32
        stem = os.path.splitext(r["zip"])[0]
        dest = os.path.join(out_root, stem, os.path.basename(r["entry"]))
        common.write_bytes(dest, data)
        done.append((r["zip"], r["entry"], r["movement_id"], common.rel_to_root(dest), common.sha1_bytes(data)))
    return done


def copy_manifest():
    copied, skipped = [], []
    for row in common.read_tsv(os.path.join(common.MANIFESTS, "midi.tsv")):
        ap = row.get("asset_path") or ""
        if not ap:
            continue
        src = os.path.join(common.ROOT, row["cache_path"])
        if not os.path.isfile(src):
            skipped.append((row["cache_path"], row.get("need")))
            continue
        dest = os.path.join(common.ROOT, ap)
        common.ensure_dir(os.path.dirname(dest))
        shutil.copyfile(src, dest)
        if common.sha1_file(src) != common.sha1_file(dest):
            raise RuntimeError("copy changed bytes: " + ap)
        copied.append(ap)
    return copied, skipped


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--extract", action="store_true")
    ap.add_argument("--copy", action="store_true")
    a = ap.parse_args(argv)
    if not (a.list or a.extract or a.copy):
        ap.error("choose --list, --extract and/or --copy")
    if a.list:
        rows = listing()
        text = "zip\tindex\tentry\tbytes\tsha1\n" + "".join("%s\t%d\t%s\t%d\t%s\n" % r for r in rows)
        common.write_text(os.path.join(common.BUILD, "sankey_listing.txt"), text)
        print("listing: %d entries -> build/sankey_listing.txt" % len(rows))
    if a.extract:
        done = extract()
        text = "zip\tentry\tmovement_id\tasset\tsha1\n" + "".join("%s\t%s\t%s\t%s\t%s\n" % d for d in done)
        common.write_text(os.path.join(common.BUILD, "sankey_extracted.tsv"), text)
        print("extracted %d Sankey entries" % len(done))
    if a.copy:
        copied, skipped = copy_manifest()
        print("copied %d files; skipped %d absent (%s)" % (len(copied), len(skipped),
                                                           ", ".join("%s: %s" % s for s in skipped)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
