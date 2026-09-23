"""T11.9: the level curve is continuous and non-decreasing (with corrections clamped to ±2.5 dB);
the stretch shape passes the Railsback check."""
import math
import unittest

import numpy as np

import _paths  # noqa: F401
import audio
import kit_build

HD_LAYERS = [{"index": i, "velRef": v} for i, v in enumerate([14, 31, 36, 40, 45, 49, 54, 61, 69, 77, 85, 93, 101,
                                                              109, 117, 124])]


def target(points, v):
    """The piecewise-linear dB target of §3.5 (0.39 dB/step beyond the outer centres)."""
    vs = [p["vel"] for p in points]
    ds = [p["db"] for p in points]
    if v <= vs[0]:
        return ds[0] - 0.39 * (vs[0] - v)
    if v >= vs[-1]:
        return ds[-1] + 0.39 * (v - vs[-1])
    return float(np.interp(v, vs, ds))


class LevelCurveTest(unittest.TestCase):
    def synthetic(self, seed=1, swap=True):
        rng = np.random.default_rng(seed)
        loud = {}
        for root in range(21, 109, 3):
            base = -30 + 0.05 * (root - 60)
            vals = [base + 0.35 * (L["velRef"] - 14) + rng.normal(0, 1.2) for L in HD_LAYERS]
            if swap and root % 2:
                vals[5], vals[6] = vals[6] + 1.0, vals[5]        # an inverted pair of layers
            loud[root] = vals
        return loud

    def test_curve_non_decreasing_and_continuous(self):
        report = []
        pts, corr = kit_build.level_curve(self.synthetic(), HD_LAYERS, report)
        self.assertEqual([p["vel"] for p in pts], [L["velRef"] for L in HD_LAYERS])
        dbs = [p["db"] for p in pts]
        self.assertTrue(all(b >= a - 1e-9 for a, b in zip(dbs, dbs[1:])))
        self.assertTrue(all(abs(c) <= 2.5 + 1e-9 for c in corr.values()))
        # continuity: the target has no step at any velocity (neighbours differ by at most the max slope)
        vals = [target(pts, v) for v in range(1, 128)]
        steps = [b - a for a, b in zip(vals, vals[1:])]
        self.assertTrue(all(s >= -1e-9 for s in steps))
        self.assertLess(max(steps), 3.0)
        # the trim of velocity v on its own layer L is target(v) − loudness(L): 0 at every velRef
        for p in pts:
            self.assertAlmostEqual(target(pts, p["vel"]) - p["db"], 0.0, places=9)

    def test_normalised_pack_follows_039(self):
        loud = {r: [-20.0 + 0.01 * i for i in range(len(HD_LAYERS))] for r in range(21, 109, 3)}
        report = []
        pts, _c = kit_build.level_curve(loud, HD_LAYERS, report)
        slopes = [(b["db"] - a["db"]) / (b["vel"] - a["vel"]) for a, b in zip(pts, pts[1:])]
        for s in slopes:
            self.assertAlmostEqual(s, 0.39, places=6)
        self.assertTrue(any("0.39" in r for r in report))

    def test_pav(self):
        self.assertEqual(audio.pav([1, 3, 2, 4]), [1, 2.5, 2.5, 4])
        self.assertEqual(audio.pav([5, 4, 3]), [4, 4, 4])

    def railsback_shape(self, k):
        # a typical grand: −20 c at A0, 0 near A4, +30 c at C8, flat-ish in the middle
        x = (k - 64.5) / 43.5
        return 25.0 * x ** 3 + 3.0 * x

    def test_railsback_passes_and_fits(self):
        pts = [(k, 4.0 + self.railsback_shape(k) + (0.8 if k % 2 else -0.8)) for k in range(21, 109, 3)]
        a_off, stretch, resid, fails = kit_build.tuning_shape(pts, piano=True)
        # a 2nd-order fit of a cubic-ish shape is not exact, but must keep the Railsback character
        self.assertEqual(fails, [], fails)
        self.assertEqual(stretch[69], 0.0)
        self.assertLess(abs(a_off - (4.0 + self.railsback_shape(69))), 3.0)

    def test_railsback_rejects(self):
        flat = [0.0] * 128
        self.assertTrue(audio.railsback_check(flat, piano=True))
        inverted = [-(k - 69) * 0.4 for k in range(128)]
        self.assertTrue(audio.railsback_check(inverted, piano=True))
        good = [((k - 64.5) / 43.5) * 25 for k in range(128)]
        self.assertEqual(audio.railsback_check(good, piano=True), [])
        self.assertEqual(audio.railsback_check([0.0] * 128, piano=False), [])
        dip = [0.0] * 128
        dip[70] = -5.0
        self.assertTrue(audio.railsback_check(dip, piano=False))


if __name__ == "__main__":
    unittest.main()
