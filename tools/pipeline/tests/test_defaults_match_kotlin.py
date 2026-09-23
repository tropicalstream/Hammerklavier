"""T11.10: the stub kit's damperT60 / freeT60 match the Kotlin defaults (contract/InstrumentProfile.kt)
within 1%. The Kotlin formulas are read from the source and evaluated independently here."""
import json
import os
import re
import unittest

import _paths  # noqa: F401
import common
import kitmap

KT = os.path.join(common.KOTLIN_CONTRACT, "InstrumentProfile.kt")


def kotlin_expr(src, fn):
    m = re.search(r"fun %s\(key: Int\): Float[^{=]*(\{.*?\n        \}|=\s*\n?\s*[^\n]+\n[^\n]*)" % fn, src, re.S)
    return m.group(1) if m else None


def kt_to_py(expr):
    """The two private Kotlin helpers as Python: literal suffixes dropped, pow/minOf/coerceIn mapped."""
    e = expr.replace("toFloat()", "").replace("f ", " ").replace("f)", ")").replace("f\n", "\n")
    e = re.sub(r"(\d)f\b", r"\1", e)
    e = re.sub(r"10\.0\.pow\(", "10.0 ** (", e)
    e = e.replace("minOf(", "min(")
    e = re.sub(r"key\.coerceIn\((\d+), (\d+)\)", r"min(\2, max(\1, key))", e)
    return e


class DefaultsMatchKotlinTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(KT, encoding="utf-8") as f:
            cls.src = f.read()
        with open(os.path.join(common.INSTRUMENTS, "stub", "map.json"), encoding="utf-8") as f:
            cls.stub = json.load(f)

    def kotlin_grand_damper(self, key):
        body = kotlin_expr(self.src, "grandDamperT60")
        self.assertIsNotNone(body)
        py = kt_to_py(body)
        n = eval(re.search(r"val n = (.*)", py).group(1), {"key": key, "min": min, "max": max})
        x = eval(re.search(r"val x = (.*)", py).group(1), {"n": n})
        return eval(re.search(r"return (.*)", py).group(1), {"x": x})

    def kotlin_grand_free(self, key):
        body = kotlin_expr(self.src, "grandFreeT60")
        self.assertIsNotNone(body)
        py = kt_to_py(body).strip().lstrip("=").strip().rstrip(".")
        py = py.replace(".", ".", 1)
        return eval(py.split("\n")[0].replace(")).", "))"), {"key": key, "min": min})

    def test_kotlin_source_has_the_formulas(self):
        self.assertIn("0.12f + 1.2f * x * x", self.src)
        self.assertIn("(88 - n) / 67f", self.src)
        self.assertIn("6.24 * 10.0.pow(-0.0275 * (key - 60))", self.src)
        self.assertIn("InstrumentId.UPRIGHT -> 1.2f * grandDamperT60(key)", self.src)
        self.assertIn("InstrumentId.UPRIGHT -> 0.8f * grandFreeT60(key)", self.src)
        self.assertIn("0.10f + 0.15f * (88 - n) / 59f", self.src)

    def test_stub_arrays_within_1_percent(self):
        for k in range(128):
            kd = self.kotlin_grand_damper(k)
            kf = self.kotlin_grand_free(k)
            self.assertLess(abs(self.stub["damperT60"][k] - kd) / kd, 0.01, k)
            self.assertLess(abs(self.stub["freeT60"][0][k] - kf) / kf, 0.01, k)

    def test_python_formulas_equal_kotlin(self):
        for k in range(128):
            self.assertAlmostEqual(kitmap.grand_damper_t60(k), self.kotlin_grand_damper(k), places=5)
            self.assertAlmostEqual(kitmap.grand_free_t60(k), self.kotlin_grand_free(k), places=5)
        # spot values from PLAN §3.7: C6 0.12, C4 0.33, C2 0.84, A0 1.3 s
        for key, want in ((84, 0.12), (60, 0.33), (36, 0.84), (21, 1.32)):
            self.assertAlmostEqual(kitmap.grand_damper_t60(key), want, delta=0.02)
        self.assertAlmostEqual(kitmap.default_free_t60("harpsichord", "8'", 89), 4.2, delta=0.1)
        self.assertAlmostEqual(kitmap.default_free_t60("harpsichord", "8'", 29), 20.0, places=6)


if __name__ == "__main__":
    unittest.main()
