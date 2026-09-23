"""T11.1 (onset part): the onset detector lands within 1 frame on synthetic inharmonic signals."""
import math
import unittest

import numpy as np

import _paths  # noqa: F401
import audio
import kit_build

SR = 48000


def inharmonic(key, n0, seconds=1.0, B=0.0004, noise_db=None, seed=0, attack=1):
    f0 = 440 * 2 ** ((key - 69) / 12)
    n = int(seconds * SR)
    t = np.arange(n - n0) / SR
    x = np.zeros(n)
    rng = np.random.default_rng(seed)
    for p in range(1, 16):
        fp = p * f0 * math.sqrt(1 + B * p * p)
        if fp > 20000:
            break
        x[n0:] += (1 / p) * np.exp(-t / (0.5 + 0.1 * p)) * np.sin(2 * math.pi * fp * t + rng.uniform(0, 2 * math.pi))
    if attack > 1:
        x[n0:n0 + attack] *= np.linspace(1 / attack, 1, attack)
    x /= np.max(np.abs(x))
    if noise_db is not None:
        x += rng.standard_normal(n) * 10 ** (noise_db / 20)
    return np.stack([x, 0.8 * x], axis=1)


class OnsetTest(unittest.TestCase):
    def test_sharp_onsets(self):
        for key in (21, 40, 60, 80, 100):
            for n0 in (500, 1234, 4800):
                on = audio.onset_frame(inharmonic(key, n0, seed=key + n0))
                self.assertLessEqual(abs(on - n0), 1, "key %d n0 %d got %d" % (key, n0, on))

    def test_with_noise_floor(self):
        for key in (30, 60, 90):
            on = audio.onset_frame(inharmonic(key, 2000, noise_db=-70, seed=key))
            self.assertLessEqual(abs(on - 2000), 1)

    def test_short_attack(self):
        on = audio.onset_frame(inharmonic(60, 3000, attack=4, seed=3))
        self.assertLessEqual(abs(on - 3000), 1)

    def test_process_sample_places_onset_at_preroll(self):
        src = inharmonic(60, 3000, seconds=2.0, seed=9)
        seg, info = kit_build.process_sample(src, "sustain", 1.5)
        self.assertEqual(info["onsetFrame"], kit_build.PRE_ROLL)
        self.assertLessEqual(abs(audio.onset_frame(seg) - kit_build.PRE_ROLL), 1)
        self.assertAlmostEqual(audio.peak_dbfs(seg), -3.0, places=3)
        self.assertEqual(len(seg), int(1.5 * SR))
        # gainDb restores the natural level
        self.assertAlmostEqual(info["gainDb"], audio.peak_dbfs(src) + 3.0, places=3)

    def test_silence(self):
        self.assertIsNone(audio.onset_frame(np.zeros((1000, 2))))


if __name__ == "__main__":
    unittest.main()
