"""T11.1 (pitch part): f0 within 1 cent and B within 10% on synthetic inharmonic signals."""
import unittest

import _paths  # noqa: F401
import audio
import kit_build
from test_onset import inharmonic


class PartialsTest(unittest.TestCase):
    CASES = [(28, 0.00015), (33, 0.0002), (45, 0.0003), (60, 0.0005), (72, 0.001), (84, 0.003), (96, 0.006)]

    def test_f0_and_B(self):
        for key, B in self.CASES:
            x = inharmonic(key, 96, seconds=1.6, B=B, seed=key)
            fit = kit_build.measure_pitch(x, key)
            self.assertIsNotNone(fit, key)
            f0 = 440 * 2 ** ((key - 69) / 12)
            self.assertLess(abs(audio.cents(fit["f0"], f0)), 1.0, "key %d f0 %.4f vs %.4f" % (key, fit["f0"], f0))
            self.assertLess(abs(fit["B"] - B) / B, 0.10, "key %d B %.6f vs %.6f" % (key, fit["B"], B))

    def test_detuned(self):
        # a root recorded 30 cents sharp is measured as +30 cents (the pitchCents field)
        key = 57
        import numpy as np
        f0 = 440 * 2 ** ((key - 69) / 12) * 2 ** (30 / 1200)
        n = 48000
        t = np.arange(n) / 48000
        x = sum((1 / p) * np.sin(2 * np.pi * p * f0 * (1 + 0.0004 * p * p) ** 0.5 * t) for p in range(1, 13))
        fit = kit_build.measure_pitch(np.stack([x, x], 1), key)
        self.assertAlmostEqual(audio.pitch_cents(fit["f0"], key), 30.0, delta=1.0)


if __name__ == "__main__":
    unittest.main()
