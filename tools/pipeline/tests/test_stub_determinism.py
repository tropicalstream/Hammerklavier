"""T11.7: the stub bank (and the probe and the kit fixture) are byte-identical across two runs, and
the committed files equal a fresh build."""
import os
import tempfile
import unittest

import _paths  # noqa: F401
import common
import make_fixtures
import make_probe
import make_stub_bank


def tree(d):
    out = {}
    for root, _s, files in os.walk(d):
        for f in files:
            p = os.path.join(root, f)
            out[os.path.relpath(p, d)] = common.read_bytes(p)
    return out


class StubDeterminismTest(unittest.TestCase):
    def test_two_runs_identical_and_match_assets(self):
        with tempfile.TemporaryDirectory() as a, tempfile.TemporaryDirectory() as b:
            make_stub_bank.build(a)
            make_stub_bank.build(b)
            ta, tb = tree(a), tree(b)
            self.assertEqual(sorted(ta), ["env.bin", "map.json", "u/0.opus", "u/bench.opus"])
            self.assertEqual(ta, tb)
            self.assertEqual(ta, tree(os.path.join(common.INSTRUMENTS, "stub")),
                             "assets/instruments/stub is stale: run tools/pipeline/make_stub_bank.py")

    def test_stub_budget(self):
        t = tree(os.path.join(common.INSTRUMENTS, "stub"))
        self.assertLess(len(t["u/0.opus"]), 700_000)          # ≈ 0.5 MB (§3.19)
        self.assertLess(sum(len(v) for v in t.values()), 1_500_000)

    def test_probe_matches_assets(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "probe.opus")
            make_probe.main(["--out", p])
            self.assertEqual(common.read_bytes(p), common.read_bytes(os.path.join(common.INSTRUMENTS, "probe.opus")))

    def test_fixture_matches_resources(self):
        with tempfile.TemporaryDirectory() as d:
            make_fixtures.build(d)
            t = tree(d)
            for rel, data in t.items():
                self.assertEqual(data, common.read_bytes(os.path.join(common.TEST_RES, rel)), rel)


if __name__ == "__main__":
    unittest.main()
