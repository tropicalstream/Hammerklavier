"""kit_build.build_real_kit end to end on synthetic recordings (the real kits are built after the
download with the same code): a two-stop harpsichord-like and a three-layer upright-like source set."""
import math
import os
import tempfile
import unittest
import wave

import numpy as np

import _paths  # noqa: F401
import audio
import kit_build
import kitmap

SR = 48000


def write_wav(path, x):
    x = np.clip(np.round(np.asarray(x) * 32767), -32768, 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(x.tobytes())


def tone(key, seconds, level_db, t60, lead=0.02, octave=1.0, B=0.0003, cents=0.0, seed=0):
    f0 = 440 * 2 ** ((key - 69) / 12) * octave * 2 ** (cents / 1200)
    n = int(seconds * SR)
    n0 = int(lead * SR)
    t = np.arange(n - n0) / SR
    rng = np.random.default_rng(seed)
    x = np.zeros(n)
    for p in range(1, 13):
        fp = p * f0 * math.sqrt(1 + B * p * p)
        if fp > 18000:
            break
        x[n0:] += (1 / p) * np.sin(2 * math.pi * fp * t + rng.uniform(0, 6.28))
    x[n0:] *= np.exp(-6.91 * t / t60)
    x *= 10 ** (level_db / 20) / np.max(np.abs(x))
    x += rng.standard_normal(n) * 1e-5
    return np.stack([x, 0.9 * x], 1)


class KitBuildTest(unittest.TestCase):
    def harpsichord(self, d):
        srcs = []
        for stop, roots in ((0, list(range(29, 90, 4))), (1, list(range(29, 78, 4)))):
            octave = 2.0 if stop else 1.0
            for i, r in enumerate(roots):
                p = os.path.join(d, "s%d_%d.wav" % (stop, r))
                write_wav(p, tone(r, 1.6, -6 - 0.02 * r, kitmap.default_free_t60("harpsichord", "8'", r),
                                  octave=octave, cents=-1.0 + 0.02 * (r - 60), seed=r + stop))
                lo = 0 if i == 0 else r - 1
                hi = 127 if i == len(roots) - 1 else r + 2
                srcs.append(kit_build.Source(p, "sustain", stop, 0, r, lo, hi, octave=octave,
                                             borrowable=(stop == 1 and r + 12 >= 85)))
                q = os.path.join(d, "r%d_%d.wav" % (stop, r))
                write_wav(q, tone(r, 0.8, -20, kitmap.default_damper_t60("harpsichord", r), lead=0.01,
                                  octave=octave, seed=100 + r))
                srcs.append(kit_build.Source(q, "release", stop, -1, r, lo, hi, octave=octave))
        layers = [{"index": 0, "velLo": 1, "velHi": 127, "velRef": 64, "unit": 0}]
        stops = [{"index": 0, "name": "8'"}, {"index": 1, "name": "4'"}]
        units = [{"id": 0, "label": "8'", "order": 0}, {"id": 62, "label": "releases", "order": 1},
                 {"id": 1, "label": "4'", "order": 2}]
        return layers, stops, units, srcs

    def test_damper_fallback_limits(self):
        fitted, flag = kit_build.damper_fallback("upright", {60: 0.3, 62: 0.3}, list(range(63, 80)))
        self.assertEqual(fitted, {})
        self.assertTrue(flag.startswith("DAMPER-FALLBACK instrument=upright fitted=2 roots=19"))
        kit_build.damper_fallback("harpsichord", {k: 0.2 for k in range(13)}, list(range(40, 59)))
        with self.assertRaises(RuntimeError):
            kit_build.damper_fallback("upright", {}, list(range(18)))
        with self.assertRaises(RuntimeError):
            kit_build.damper_fallback("harpsichord", {}, list(range(20)))
        with self.assertRaises(RuntimeError):
            kit_build.damper_fallback("grand", {}, [60, 61])
        fitted, flag = kit_build.damper_fallback("upright", {60: 0.3, 61: 0.3}, [62])
        self.assertEqual(len(fitted), 2)
        self.assertTrue(flag.startswith("DAMPER-FIT"))

    def test_release_attack_levels_follow_the_sfz_volumes(self):
        regs = [{"id": 0, "kind": "sustain", "stop": 0, "layer": 1, "root": 60, "_sfzDb": -10.0},
                {"id": 1, "kind": "sustain", "stop": 0, "layer": 2, "root": 60, "_sfzDb": 0.0},
                {"id": 2, "kind": "release", "stop": 0, "layer": -1, "root": 60, "_sfzDb": -40.0},
                {"id": 3, "kind": "release", "stop": 0, "layer": -1, "root": 63, "_sfzDb": -35.0}]
        kit_build.release_attack_levels(regs, 1, [])
        self.assertAlmostEqual(regs[2]["attackRelDb"], -30.0)
        self.assertAlmostEqual(regs[3]["attackRelDb"], -25.0)      # nearest root's sustain on the reference layer

    def test_harpsichord_like_kit(self):
        with tempfile.TemporaryDirectory() as d:
            orig = kit_build.harpsichord_sources
            kit_build.harpsichord_sources = lambda: self.harpsichord(d)
            try:
                out = os.path.join(d, "kit")
                m = kit_build.build_real_kit("harpsichord", out, os.path.join(d, "report.txt"))
            finally:
                kit_build.harpsichord_sources = orig
            self.assertEqual(kitmap.validate(m, kit_dir=out), [])
            self.assertEqual(m["mode"], "HARD")
            self.assertEqual(m["lastDamper"], 127)
            # VCSL releases play at their SFZ level under the note (attackRelDb), with no handoff
            self.assertFalse(m["releaseCarriesTail"])
            # the recording's pitch standard is separated from the shape (−1 c at key 60, +0.02 c/key)
            self.assertAlmostEqual(m["aOffsetCents"], -1.0 + 0.02 * 9, delta=0.5)
            self.assertAlmostEqual(m["stretchCents"][89] - m["stretchCents"][29], 0.02 * 60, delta=0.6)
            s4 = [r for r in m["regions"] if r["kind"] == "sustain" and r["stop"] == 1]
            self.assertTrue(all(abs(r["pitchCents"] - 1200) < 30 for r in s4))
            self.assertTrue(any("seamGainDb" in r and "seamLpHz" in r for r in s4))
            # damper T60 fitted from the releases: within 25% of the formula they were made with
            for k in (33, 61, 85):
                want = kitmap.default_damper_t60("harpsichord", k)
                self.assertLess(abs(m["damperT60"][k] - want) / want, 0.25, k)
            report = open(os.path.join(d, "report.txt")).read()
            for word in ("aOffsetCents", "embedded room", "seam root", "free T60"):
                self.assertIn(word, report)

    def upright(self, d):
        srcs = []
        roots = list(range(21, 109, 8))
        layers = [{"index": 0, "velLo": 1, "velHi": 40, "velRef": 20, "unit": 2},
                  {"index": 1, "velLo": 41, "velHi": 83, "velRef": 62, "unit": 0},
                  {"index": 2, "velLo": 84, "velHi": 127, "velRef": 105, "unit": 1}]
        for li, lvl in enumerate((-26.0, -14.0, -4.0)):
            for i, r in enumerate(roots):
                p = os.path.join(d, "u%d_%d.wav" % (li, r))
                write_wav(p, tone(r, 1.4, lvl, 0.8 * kitmap.grand_free_t60(r), seed=r))                # same phases in every layer
                srcs.append(kit_build.Source(p, "sustain", 0, li, r, 0 if i == 0 else r - 3,
                                             127 if i == len(roots) - 1 else r + 4))
        for i, r in enumerate(roots):
            q = os.path.join(d, "rel_%d.wav" % r)
            t60 = 3.0 if r >= 90 else kitmap.default_damper_t60("upright", r)       # undamped treble rings on
            write_wav(q, tone(r, 1.2, -24, t60, lead=0.01, seed=300 + r))
            srcs.append(kit_build.Source(q, "release", 0, -1, r, 0 if i == 0 else r - 3,
                                         127 if i == len(roots) - 1 else r + 4))
        for kind, n in (("pedalDown", 2), ("pedalUp", 2)):
            for rr in range(n):
                q = os.path.join(d, "%s%d.wav" % (kind, rr))
                write_wav(q, tone(40, 0.6, -30, 0.2, seed=400 + rr + (10 if kind == "pedalUp" else 0)))
                srcs.append(kit_build.Source(q, kind, -1, -1, -1, -1, -1, rr=rr))
        units = [{"id": 0, "label": "mf (vl1)", "order": 0}, {"id": 62, "label": "releases", "order": 1},
                 {"id": 63, "label": "pedals", "order": 2}, {"id": 1, "label": "f (vl2)", "order": 3},
                 {"id": 2, "label": "pp (dyn1)", "order": 4}]
        return layers, [{"index": 0, "name": "main"}], units, srcs

    def test_upright_like_kit(self):
        with tempfile.TemporaryDirectory() as d:
            orig = kit_build.upright_sources
            kit_build.upright_sources = lambda: self.upright(d)
            try:
                out = os.path.join(d, "kit")
                m = kit_build.build_real_kit("upright", out, os.path.join(d, "report.txt"))
            finally:
                kit_build.upright_sources = orig
            self.assertEqual(kitmap.validate(m, kit_dir=out), [])
            self.assertEqual((m["mode"], m["xfadeSteps"], len(m["xfadeLaw"])), ("XFADE", 6, 2))
            self.assertEqual(m["xfadeLaw"], ["gain", "gain"])        # same tones, correlated layers
            self.assertEqual(m["lastDamper"], 92)                     # lowest ringing release root 93, minus 1
            dbs = [p["db"] for p in m["levelCurve"][0]]
            self.assertTrue(dbs[0] < dbs[1] < dbs[2])
            self.assertAlmostEqual(dbs[2] - dbs[0], 22.0, delta=3.0)
            self.assertEqual(sum(1 for r in m["regions"] if r["kind"].startswith("pedal")), 4)


if __name__ == "__main__":
    unittest.main()
