#!/usr/bin/env python3
"""Day-1 kit fixture for other WPs' tests (PLAN.md §6.6, §6.8) → core/src/test/resources/wp11/:
map_fixture.json + env_fixture.bin + u/{0,1,62,63}.opus.

A toy kit built by the real `kit_build.assemble_kit` (so it is packed, encoded, round-trip checked
and validated like every kit): 3 roots 58/60/62 × 2 layers (XFADE ±4, velRef 40 and 100, law
"gain"), ET (pitchCents exactly 0, aOffsetCents 0, zero stretch shape), a release per root
(unit 62) and one pedal-down and one pedal-up noise (unit 63), so every region kind and every
§6.6 field occurs. Deterministic.

    python3 tools/pipeline/make_fixtures.py [--out DIR]
"""
import argparse
import os
import shutil
import sys
import tempfile

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import audio  # noqa: E402
import common  # noqa: E402
import kit_build  # noqa: E402
import kitmap  # noqa: E402
import make_stub_bank  # noqa: E402

ROOTS = (58, 60, 62)
LAYERS = [{"index": 0, "velLo": 1, "velHi": 64, "velRef": 40, "unit": 0},
          {"index": 1, "velLo": 65, "velHi": 127, "velRef": 100, "unit": 1}]
LAYER_DB = (-12.0, 0.0)            # natural level of the soft layer relative to the loud one


def noise_burst(seconds, seed):
    rng = np.random.default_rng(seed)
    n = int(seconds * common.SR)
    x = np.zeros((n, 2))
    t = np.arange(n - kit_build.PRE_ROLL) / common.SR
    x[kit_build.PRE_ROLL:, :] = rng.standard_normal((n - kit_build.PRE_ROLL, 2)) * np.exp(-t / 0.05)[:, None]
    audio.raised_cosine_fade(x, n // 4)
    return x * (audio.db_to_lin(-3.0) / np.max(np.abs(x)))


def fixture_regions():
    regions = []

    def add(kind, unit, stop, layer, root, lo, hi, rr, pcm, gain_db, pitch=True):
        r = {"id": len(regions), "kind": kind, "unit": unit, "stop": stop, "layer": layer, "root": root,
             "lo": lo, "hi": hi, "rr": rr, "onsetFrame": kit_build.PRE_ROLL, "thrFrame": audio.thr_frame(pcm),
             "gainDb": gain_db, "pcm": pcm.astype(np.float32)}
        if pitch:
            r["pitchCents"] = 0.0
        regions.append(r)

    ranges = {58: (0, 59), 60: (60, 60), 62: (61, 127)}
    for li, L in enumerate(LAYERS):
        for k in ROOTS:
            add("sustain", L["unit"], 0, li, k, ranges[k][0], ranges[k][1], 0,
                make_stub_bank.synth_tone(k, 0.5, partials=8 if li == 0 else 12), LAYER_DB[li] - 6.0)
    for k in ROOTS:
        add("release", 62, 0, -1, k, ranges[k][0], ranges[k][1], 0, noise_burst(0.2, k), -30.0)
    add("pedalDown", 63, -1, -1, -1, -1, -1, 0, noise_burst(0.3, 1), -20.0, pitch=False)
    add("pedalUp", 63, -1, -1, -1, -1, -1, 0, noise_burst(0.2, 2), -22.0, pitch=False)
    return regions


def build(out_dir):
    regions = fixture_regions()
    meta = {
        "instrument": "grand", "kit": "grand-std", "version": "2026.09.1", "mode": "XFADE", "xfadeSteps": 4,
        "xfadeLaw": ["gain"], "lastDamper": 88, "aOffsetCents": 0.0, "recordedAHz": 440.0, "pedalGainDb": -20.0,
        "releaseCarriesTail": False, "embeddedRoomDb": 24.0, "embeddedEdtS": 0.3,
        "layers": LAYERS, "stops": [{"index": 0, "name": "main"}],
        "levelCurve": [[{"vel": 40, "db": -30.0}, {"vel": 100, "db": -18.0}]],
        "stretchCents": [0.0] * 128, "inharmB": [0.0004] * 128,
        "damperT60": [kitmap.default_damper_t60("grand", k) for k in range(128)],
        "freeT60": [[kitmap.default_free_t60("grand", "main", k) for k in range(128)]],
        "releaseRule": {"relGainDb": -6.0, "velExp": 0.7, "ageTauS": 3.0, "floor": 0.25, "heldDb": -9.0},
        "credit": "Hammerklavier test fixture (synthesised), CC0", "source": "tools/pipeline/make_fixtures.py",
    }
    units = [{"id": 0, "label": "soft", "order": 1}, {"id": 1, "label": "loud", "order": 0},
             {"id": 62, "label": "releases", "order": 2}, {"id": 63, "label": "pedals", "order": 3}]
    tmp = tempfile.mkdtemp(prefix="hk-fixture-")
    try:
        m = kit_build.assemble_kit(tmp, meta, regions, units, [])
        common.ensure_dir(os.path.join(out_dir, "u"))
        shutil.copyfile(os.path.join(tmp, "map.json"), os.path.join(out_dir, "map_fixture.json"))
        shutil.copyfile(os.path.join(tmp, "env.bin"), os.path.join(out_dir, "env_fixture.bin"))
        for u in m["units"]:
            shutil.copyfile(os.path.join(tmp, u["file"]), os.path.join(out_dir, u["file"]))
    finally:
        shutil.rmtree(tmp)
    return m


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=common.TEST_RES)
    a = ap.parse_args(argv)
    m = build(a.out)
    print("map_fixture.json: %d regions, %d units, sha1 %s" % (len(m["regions"]), len(m["units"]), m["sha1"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
