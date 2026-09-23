#!/usr/bin/env python3
"""The stand-in bank (PLAN.md §6.1, §6.8, §3.19): assets/instruments/stub/{map.json, env.bin, u/0.opus}
plus the 60 s decode-bench stream u/bench.opus, all with the kit encoding.

30 roots (keys 21 + 3i, i = 0..29) × 1 layer × 1.2 s of additive partials (12 partials,
f_n = n·f0·√(1 + B·n²) with B = 0.0004, a two-stage decay, a 0.5 ms attack at frame 96 and the
300 ms end fade). Deterministic: two runs give byte-identical files (T11.7). The stub instrument
uses the grand's §3.7 damper and free T60 formulas (T11.10 checks them against InstrumentProfile.kt).

    python3 tools/pipeline/make_stub_bank.py [--out DIR]
"""
import argparse
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import audio  # noqa: E402
import common  # noqa: E402
import kit_build  # noqa: E402
import kitmap  # noqa: E402

SR = common.SR
ROOTS = [21 + 3 * i for i in range(30)]
REGION_SECONDS = 1.2
PARTIALS = 12
B = 0.0004
NATURAL_PEAK_DB = -9.0          # the synthesised level the bank's gainDb restores
VERSION = "2026.09.1"           # fixed, so a rebuild is byte-identical
BENCH_SECONDS = 60


def synth_tone(key, seconds, a4=440.0, b=B, partials=PARTIALS, attack_frames=24, pre_roll=kit_build.PRE_ROLL,
               t60_free=None, fade=True):
    """Deterministic additive tone: float64 (frames, 2), onset at `pre_roll`, peak −3 dBFS."""
    n = int(round(seconds * SR))
    f0 = a4 * 2.0 ** ((key - 69) / 12.0)
    t = np.arange(n - pre_roll, dtype=np.float64) / SR
    t60 = t60_free if t60_free is not None else kitmap.grand_free_t60(key)
    tau2 = t60 / 6.91
    tau1 = 0.12
    x = np.zeros(n - pre_roll)
    for p in range(1, partials + 1):
        fp = p * f0 * math.sqrt(1.0 + b * p * p)
        if fp >= 20000.0:
            break
        amp = 1.0 / p ** 1.1
        phase = 2 * math.pi * ((p * 0.61803398875 + key * 0.1234567) % 1.0)
        tp = tau2 / (1.0 + 0.08 * (p - 1))
        env = 0.55 * np.exp(-t / tau1) + 0.45 * np.exp(-t / tp)
        x += amp * env * np.sin(2 * math.pi * fp * t + phase)
    att = np.ones_like(x)
    att[:attack_frames] = 0.5 - 0.5 * np.cos(np.pi * (np.arange(attack_frames) + 1) / attack_frames)
    x *= att
    pan = math.pi / 4 + 0.3 * (key - 64) / 88.0
    st = np.zeros((n, 2))
    st[pre_roll:, 0] = x * math.cos(pan)
    st[pre_roll:, 1] = x * math.sin(pan)
    if fade:
        audio.raised_cosine_fade(st, min(kit_build.FADE, n // 3))
    st *= audio.db_to_lin(kit_build.TARGET_PEAK_DB) / float(np.max(np.abs(st)))
    return st


def stub_regions():
    regions = []
    for i, k in enumerate(ROOTS):
        pcm = synth_tone(k, REGION_SECONDS)
        fit = audio.fit_partials(audio.to_mono(pcm)[kit_build.PRE_ROLL:], audio.key_hz(k), t0=0.05, t1=1.1,
                                 n_max=PARTIALS)
        regions.append({
            "id": i, "kind": "sustain", "unit": 0, "stop": 0, "layer": 0, "root": k,
            "lo": 0 if i == 0 else k - 1, "hi": 127 if i == len(ROOTS) - 1 else k + 1, "rr": 0,
            "onsetFrame": kit_build.PRE_ROLL, "thrFrame": audio.thr_frame(pcm),
            "pitchCents": audio.pitch_cents(fit["f0"], k), "B": fit["B"],
            "gainDb": NATURAL_PEAK_DB - kit_build.TARGET_PEAK_DB, "pcm": pcm.astype(np.float32)})
    return regions


def bench_stream(regions):
    """60 s of stub tones back to back (the kit encoding), for the M1 decode bench."""
    total = BENCH_SECONDS * SR
    parts, have, i = [], 0, 0
    while have < total:
        r = regions[i % len(regions)]["pcm"]
        parts.append(np.zeros((kit_build.GAP, 2), dtype=np.float32))
        parts.append(r)
        have += kit_build.GAP + len(r)
        i += 1
    return np.concatenate(parts)[:total]


def build(out_dir):
    report = ["kit stub: %d roots x 1 layer x %.1f s, B = %g" % (len(ROOTS), REGION_SECONDS, B)]
    regions = stub_regions()
    pts = [(r["root"], r["pitchCents"]) for r in regions]
    a_off, stretch, resid, _f = kit_build.tuning_shape(pts, piano=False)
    loud = [audio.aweighted_rms_db(r["pcm"], kit_build.PRE_ROLL) + r["gainDb"] for r in regions]
    layers = [{"index": 0, "velLo": 1, "velHi": 127, "velRef": 64, "unit": 0}]
    meta = {
        "instrument": "stub", "kit": "stub", "version": VERSION, "mode": "HARD", "xfadeSteps": 0, "xfadeLaw": [],
        "lastDamper": 88, "aOffsetCents": a_off, "recordedAHz": 440.0 * 2 ** (a_off / 1200.0),
        "pedalGainDb": -20.0, "releaseCarriesTail": False, "embeddedRoomDb": 30.0, "embeddedEdtS": 0.0,
        "layers": layers, "stops": [{"index": 0, "name": "main"}],
        "levelCurve": [[{"vel": 64, "db": float(np.median(loud))}]],
        "stretchCents": stretch,
        "inharmB": kit_build.per_key({r["root"]: r["B"] for r in regions}, lambda k: B),
        "damperT60": [kitmap.default_damper_t60("stub", k) for k in range(128)],
        "freeT60": [[kitmap.default_free_t60("stub", "main", k) for k in range(128)]],
        "releaseRule": {"relGainDb": 0.0, "velExp": 0.7, "ageTauS": 3.0, "floor": 0.25, "heldDb": -9.0},
        "credit": kit_build.CREDITS["stub"][0], "source": kit_build.CREDITS["stub"][1],
    }
    units = [{"id": 0, "label": "stub", "order": 0}]
    m = kit_build.assemble_kit(out_dir, meta, regions, units, report)
    bench = os.path.join(out_dir, "u", "bench.opus")
    audio.encode_opus(bench_stream(regions), bench)
    report.append("aOffsetCents %.4f, max |residual| %.4f c" % (a_off, max(abs(v) for v in resid.values())))
    report.append("bench.opus: %d s, %d bytes" % (BENCH_SECONDS, os.path.getsize(bench)))
    report.append("u/0.opus: %d bytes; kit sha1 %s" % (os.path.getsize(os.path.join(out_dir, "u", "0.opus")), m["sha1"]))
    return m, report


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=os.path.join(common.INSTRUMENTS, "stub"))
    a = ap.parse_args(argv)
    m, report = build(a.out)
    rep = os.path.join(common.BUILD, "kit-stub-report.txt")
    common.write_text(rep, "\n".join(report) + "\n")
    print("stub bank: %d regions, sha1 %s -> %s" % (len(m["regions"]), m["sha1"], a.out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
