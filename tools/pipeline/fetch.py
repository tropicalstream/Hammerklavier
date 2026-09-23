#!/usr/bin/env python3
"""Fetch the approved download manifests into tools/cache/ (PLAN.md §6.2-6.4).

    python3 tools/pipeline/fetch.py --manifest samples --tier hd      # core + hd rows
    python3 tools/pipeline/fetch.py --manifest midi
    python3 tools/pipeline/fetch.py --manifest samples --dry-run

Refuses to run until tools/pipeline/APPROVED names the manifest ("samples-hd" / "samples-standard",
"midi"). Standard library only. One file at a time per host (GitHub raw gets a small pool), resumable
.part files, sizes checked against the manifest for samples, SHA-1 (base32) checked for Krueger MIDIs.
Rows the user declined are never fetched: need=user-click (IMSLP) and need=conditional (Couperin).

Sample layout: tools/cache/samples/<group>/<filename> (per-group folders, because LICENSE, README.md
and Info.txt occur in several groups). MIDI layout: the manifest's own cache_path column.
"""
import argparse
import base64
import concurrent.futures as cf
import csv
import hashlib
import os
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
MANIFESTS = os.path.join(ROOT, "docs", "manifests")
CACHE = os.path.join(ROOT, "tools", "cache")
APPROVED = os.path.join(ROOT, "tools", "pipeline", "APPROVED")
UA = "Hammerklavier-pipeline/1.0 (+github tropicalstream)"


def approved_ids():
    try:
        with open(APPROVED) as f:
            return {ln.strip() for ln in f if ln.strip() and not ln.startswith("#")}
    except FileNotFoundError:
        return set()


def read_tsv(name):
    with open(os.path.join(MANIFESTS, name), newline="") as f:
        return list(csv.DictReader(f, delimiter="\t"))


def sha1_b32(path):
    h = hashlib.sha1()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return base64.b32encode(h.digest()).decode()


def download(url, dest, expect_bytes=None, tries=5):
    """Resumable GET into dest (via dest.part). Returns (ok, http_status_or_msg)."""
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    part = dest + ".part"
    last = "?"
    for attempt in range(tries):
        have = os.path.getsize(part) if os.path.exists(part) else 0
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        if have:
            req.add_header("Range", "bytes=%d-" % have)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                mode = "ab" if (have and r.status == 206) else "wb"
                with open(part, mode) as out:
                    while True:
                        buf = r.read(1 << 16)
                        if not buf:
                            break
                        out.write(buf)
            size = os.path.getsize(part)
            if expect_bytes and size != expect_bytes:
                last = "size %d != %d" % (size, expect_bytes)
                if size > expect_bytes:
                    os.remove(part)
                time.sleep(1.5 * (attempt + 1))
                continue
            os.replace(part, dest)
            return True, 200
        except urllib.error.HTTPError as e:
            last = e.code
            if e.code == 416 and os.path.exists(part):   # range past end: restart clean
                os.remove(part)
                continue
            if e.code in (404, 410, 418, 451):
                return False, e.code
            time.sleep(2.0 * (attempt + 1))
        except Exception as e:  # network hiccup: back off and resume
            last = repr(e)
            time.sleep(2.0 * (attempt + 1))
    return False, last


def fetch_samples(tier, dry, jobs):
    if ("samples-" + tier) not in approved_ids() and not (tier == "core" and "samples-hd" in approved_ids()):
        sys.exit("fetch.py: samples-%s is not in tools/pipeline/APPROVED — ask the user first (PLAN §6.2)" % tier)
    rows = [r for r in read_tsv("samples-v2.tsv") if r["tier"] == "core" or (tier == "hd" and r["tier"] == "hd")]
    todo = []
    for r in rows:
        dest = os.path.join(CACHE, "samples", r["group"], r["filename"])
        want = int(r["bytes"]) if r["bytes"].isdigit() else None
        if os.path.exists(dest) and (want is None or os.path.getsize(dest) == want):
            continue
        todo.append((r["url"], dest, want))
    total = sum(w or 0 for _, _, w in todo)
    print("samples: %d rows, %d to fetch, %.1f MiB" % (len(rows), len(todo), total / 1048576), flush=True)
    if dry:
        return 0
    fails = []
    done = [0, 0]

    def one(item):
        url, dest, want = item
        ok, st = download(url, dest, want)
        time.sleep(0.3)
        return item, ok, st

    with cf.ThreadPoolExecutor(max_workers=jobs) as ex:
        for item, ok, st in ex.map(one, todo):
            done[0] += 1
            done[1] += item[2] or 0
            if not ok:
                fails.append((item[0], st))
                print("FAIL %s -> %s" % (item[0], st), flush=True)
            if done[0] % 25 == 0 or done[0] == len(todo):
                print("  %d/%d files, %.1f/%.1f MiB" % (done[0], len(todo), done[1] / 1048576, total / 1048576), flush=True)
    print("samples: %d failures" % len(fails), flush=True)
    return 1 if fails else 0


def fetch_midi(dry):
    if "midi" not in approved_ids():
        sys.exit("fetch.py: midi is not in tools/pipeline/APPROVED — ask the user first (PLAN §6.2)")
    rows = [r for r in read_tsv("midi.tsv") if r["need"] == "required"]
    skipped = [r for r in read_tsv("midi.tsv") if r["need"] != "required"]
    print("midi: %d required rows; declined/not fetched: %s" % (len(rows), ", ".join(os.path.basename(r["cache_path"]) for r in skipped)), flush=True)
    if dry:
        return 0
    fails = []
    for r in rows:
        dest = os.path.join(ROOT, r["cache_path"])
        want_sha = r.get("sha1_b32", "").strip()
        if os.path.exists(dest) and (not want_sha or sha1_b32(dest) == want_sha):
            continue
        urls = [u for u in (r["url"], r.get("fallback_url", "")) if u.strip()]
        ok = False
        for u in urls:
            ok, st = download(u, dest)
            time.sleep(2.0 if "web.archive.org" in u else 0.5)
            if ok and want_sha and sha1_b32(dest) != want_sha:
                print("  sha1 mismatch from %s" % u, flush=True)
                os.remove(dest)
                ok = False
            if ok:
                break
        print(("ok   " if ok else "FAIL ") + r["cache_path"], flush=True)
        if not ok:
            fails.append(r["cache_path"])
    print("midi: %d failures %s" % (len(fails), fails), flush=True)
    return 1 if fails else 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", choices=["samples", "midi"], required=True)
    ap.add_argument("--tier", choices=["core", "hd"], default="hd")
    ap.add_argument("--jobs", type=int, default=4)
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    if a.manifest == "samples":
        sys.exit(fetch_samples(a.tier, a.dry_run, a.jobs))
    sys.exit(fetch_midi(a.dry_run))


if __name__ == "__main__":
    main()
