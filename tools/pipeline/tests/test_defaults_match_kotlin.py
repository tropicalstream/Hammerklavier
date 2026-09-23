"""T11.10: the stub kit's damperT60 / freeT60 match the Kotlin defaults (contract/InstrumentProfile.kt)
within 1%. The Kotlin formulas are pinned by literal-string asserts on the source; their 128-key values
are a golden table (fixtures/kotlin_defaults_golden.json) evaluated once from those formulas, so a
reformat of the Kotlin file cannot make this test evaluate the wrong expression. If a literal assert
fails, the Kotlin formula changed: re-derive the golden table from the new formula by hand."""
import json
import os
import unittest

import _paths  # noqa: F401
import common
import kitmap

KT = os.path.join(common.KOTLIN_CONTRACT, "InstrumentProfile.kt")
GOLDEN = os.path.join(common.PIPELINE, "fixtures", "kotlin_defaults_golden.json")


def _squash(s):
    return " ".join(s.split())


class DefaultsMatchKotlinTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(KT, encoding="utf-8") as f:
            cls.src = _squash(f.read())
        with open(os.path.join(common.INSTRUMENTS, "stub", "map.json"), encoding="utf-8") as f:
            cls.stub = json.load(f)
        with open(GOLDEN, encoding="utf-8") as f:
            cls.golden = json.load(f)

    def test_kotlin_source_has_the_formulas(self):
        for lit in ("key.coerceIn(21, 88)", "0.12f + 1.2f * x * x", "(88 - n) / 67f",
                    "minOf(30.0, 6.24 * 10.0.pow(-0.0275 * (key - 60)))",
                    "InstrumentId.UPRIGHT -> 1.2f * grandDamperT60(key)",
                    "InstrumentId.UPRIGHT -> 0.8f * grandFreeT60(key)", "0.10f + 0.15f * (88 - n) / 59f"):
            self.assertIn(_squash(lit), self.src, lit)

    def test_golden_table_shape_and_spots(self):
        d, f = self.golden["grandDamperT60"], self.golden["grandFreeT60"]
        self.assertEqual((len(d), len(f)), (128, 128))
        # the formulas by hand at a few keys: n = clamp(key, 21, 88), x = (88 - n) / 67
        for key in (0, 21, 60, 84, 88, 127):
            n = min(88, max(21, key))
            x = (88 - n) / 67.0
            self.assertAlmostEqual(d[key], 0.12 + 1.2 * x * x, places=5)
        self.assertAlmostEqual(f[60], 6.24, places=5)
        self.assertAlmostEqual(f[72], 6.24 * 10 ** (-0.0275 * 12), places=4)

    def test_stub_arrays_within_1_percent(self):
        for k in range(128):
            kd, kf = self.golden["grandDamperT60"][k], self.golden["grandFreeT60"][k]
            self.assertLess(abs(self.stub["damperT60"][k] - kd) / kd, 0.01, k)
            self.assertLess(abs(self.stub["freeT60"][0][k] - kf) / kf, 0.01, k)

    def test_python_formulas_equal_kotlin(self):
        for k in range(128):
            self.assertAlmostEqual(kitmap.grand_damper_t60(k), self.golden["grandDamperT60"][k], places=5)
            self.assertAlmostEqual(kitmap.grand_free_t60(k), self.golden["grandFreeT60"][k], places=5)
        # spot values from PLAN §3.7: C6 0.12, C4 0.33, C2 0.84, A0 1.3 s
        for key, want in ((84, 0.12), (60, 0.33), (36, 0.84), (21, 1.32)):
            self.assertAlmostEqual(kitmap.grand_damper_t60(key), want, delta=0.02)
        self.assertAlmostEqual(kitmap.default_free_t60("harpsichord", "8'", 89), 4.2, delta=0.1)
        self.assertAlmostEqual(kitmap.default_free_t60("harpsichord", "8'", 29), 20.0, places=6)


if __name__ == "__main__":
    unittest.main()
