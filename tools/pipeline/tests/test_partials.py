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

    def test_top_octave_far_off(self):
        # Salamander's C8 sounds about +99 cents: outside the partial search, found by the peak
        import numpy as np
        key = 108
        f0 = 440 * 2 ** ((key - 69) / 12) * 2 ** (97 / 1200)
        t = np.arange(48000) / 48000
        rng = np.random.default_rng(7)
        x = np.sin(2 * np.pi * f0 * t) * np.exp(-t / 0.15) + 0.3 * np.sin(2 * np.pi * 2.001 * f0 * t) * np.exp(-t / 0.1)
        x = x + 0.002 * rng.standard_normal(len(t)) + 0.05 * np.sin(2 * np.pi * 40 * t)   # hiss and rumble
        fit = kit_build.measure_pitch(np.stack([x, x], 1), key)
        self.assertAlmostEqual(audio.pitch_cents(fit["f0"], key), 97.0, delta=2.0)
        self.assertLessEqual(fit["B"], kit_build.TOP_OCTAVE_MAX_B)

    def test_rumble_does_not_set_the_floor(self):
        # a treble note 30 dB under sub-50 Hz rumble is still fitted
        import numpy as np
        key = 100
        f0 = 440 * 2 ** ((key - 69) / 12)
        t = np.arange(96000) / 48000
        x = sum((1 / p) * np.sin(2 * np.pi * p * f0 * (1 + 0.002 * p * p) ** 0.5 * t) for p in range(1, 4)) * 0.03
        x = x + np.sin(2 * np.pi * 31 * t)
        fit = kit_build.measure_pitch(np.stack([x, x], 1), key)
        self.assertIsNotNone(fit)
        self.assertAlmostEqual(audio.pitch_cents(fit["f0"], key), 0.0, delta=1.0)


if __name__ == "__main__":
    unittest.main()
