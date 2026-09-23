"""T11.3: the map.json validator (hand-written from docs/contracts/map-json.md) accepts every kit and
the fixture and rejects each broken variant."""
import copy
import json
import os
import unittest

import _paths  # noqa: F401
import common
import kitmap


def load(p):
    with open(p, encoding="utf-8") as f:
        return json.load(f)


FIXTURE = os.path.join(common.TEST_RES, "map_fixture.json")
FIXTURE_ENV = os.path.join(common.TEST_RES, "env_fixture.bin")


class MapJsonTest(unittest.TestCase):
    def setUp(self):
        self.m = load(FIXTURE)
        with open(FIXTURE_ENV, "rb") as f:
            self.env = f.read()

    def errs(self, m):
        return kitmap.validate(m, env=self.env)

    def test_fixture_valid_with_files(self):
        # the fixture dir holds u/*.opus; env is named env_fixture.bin, so pass it explicitly
        self.assertEqual(kitmap.validate(self.m, kit_dir=common.TEST_RES, env=self.env), [])

    def test_fixture_sha1(self):
        import hashlib
        h = hashlib.sha1()
        for u in sorted(self.m["units"], key=lambda u: u["id"]):
            h.update(common.read_bytes(os.path.join(common.TEST_RES, u["file"])))
        h.update(self.env)
        self.assertEqual(h.hexdigest(), self.m["sha1"])

    def test_fixture_shape(self):
        m = self.m
        self.assertEqual(sorted({r["root"] for r in m["regions"] if r["kind"] == "sustain"}), [58, 60, 62])
        self.assertEqual(len(m["layers"]), 2)
        self.assertTrue(all(v == 0 for v in m["stretchCents"]))
        self.assertTrue(all(r["pitchCents"] == 0 for r in m["regions"] if "pitchCents" in r))
        self.assertEqual({r["kind"] for r in m["regions"]}, {"sustain", "release", "pedalDown", "pedalUp"})

    def test_every_kit_in_assets_valid(self):
        found = 0
        for d in sorted(os.listdir(common.INSTRUMENTS)) if os.path.isdir(common.INSTRUMENTS) else []:
            p = os.path.join(common.INSTRUMENTS, d, "map.json")
            if os.path.isfile(p):
                found += 1
                self.assertEqual(kitmap.validate(load(p), kit_dir=os.path.dirname(p)), [], d)
        self.assertGreaterEqual(found, 1)          # at least the stub bank

    def broken(self, fn):
        m = copy.deepcopy(self.m)
        fn(m)
        return self.errs(m)

    def test_rejects_each_broken_variant(self):
        cases = {
            "unknown top field": lambda m: m.__setitem__("extra", 1),
            "missing field": lambda m: m.pop("releaseRule"),
            "schema": lambda m: m.__setitem__("schema", 1),
            "instrument": lambda m: m.__setitem__("instrument", "organ"),
            "kit": lambda m: m.__setitem__("kit", "grand-xl"),
            "version": lambda m: m.__setitem__("version", "2026-09"),
            "sha1": lambda m: m.__setitem__("sha1", "abc"),
            "mode": lambda m: m.__setitem__("mode", "SOFT"),
            "xfadeLaw length": lambda m: m.__setitem__("xfadeLaw", []),
            "xfadeLaw value": lambda m: m.__setitem__("xfadeLaw", ["linear"]),
            "HARD with law": lambda m: m.__setitem__("mode", "HARD"),
            "velocity gap": lambda m: m["layers"][1].__setitem__("velLo", 66),
            "velocity overlap": lambda m: m["layers"][0].__setitem__("velHi", 70),
            "velRef outside": lambda m: m["layers"][0].__setitem__("velRef", 90),
            "layer index": lambda m: m["layers"][1].__setitem__("index", 5),
            "layer unit": lambda m: m["layers"][0].__setitem__("unit", 40),
            "stop name": lambda m: m["stops"][0].__setitem__("name", "16'"),
            "level curve decreasing": lambda m: m["levelCurve"][0][1].__setitem__("db", -40.0),
            "level curve vel": lambda m: m["levelCurve"][0][0].__setitem__("vel", 41),
            "level curve length": lambda m: m["levelCurve"][0].pop(),
            "duplicate unit": lambda m: m["units"][1].__setitem__("id", 0),
            "unit file": lambda m: m["units"][0].__setitem__("file", "u/zero.opus"),
            "unit frames": lambda m: m["units"][0].__setitem__("frames", 0),
            "unit id range": lambda m: m["units"][0].__setitem__("id", 64),
            "region ids not dense": lambda m: m["regions"][3].__setitem__("id", 7),
            "region kind": lambda m: m["regions"][0].__setitem__("kind", "attack"),
            "region past unit": lambda m: m["regions"][0].__setitem__("streamStart", 10 ** 9),
            "region frames": lambda m: m["regions"][0].__setitem__("frames", 0),
            "region env range": lambda m: m["regions"][0].__setitem__("envOffset", 10 ** 6),
            "region unknown field": lambda m: m["regions"][0].__setitem__("velocity", 3),
            "missing pitchCents": lambda m: m["regions"][0].pop("pitchCents"),
            "release layer": lambda m: [r.__setitem__("layer", 0) for r in m["regions"] if r["kind"] == "release"],
            "release unit": lambda m: [r.__setitem__("unit", 0) for r in m["regions"] if r["kind"] == "release"],
            "pedal root": lambda m: [r.__setitem__("root", 60) for r in m["regions"] if r["kind"] == "pedalUp"],
            "pedal rr": lambda m: [r.__setitem__("rr", 3) for r in m["regions"] if r["kind"] == "pedalUp"],
            "seam without borrowable": lambda m: m["regions"][0].__setitem__("seamGainDb", 1.0),
            "no sustain for a layer": lambda m: [r.__setitem__("layer", 0) for r in m["regions"] if r["kind"] == "sustain"],
            "stretch length": lambda m: m["stretchCents"].pop(),
            "stretch not zero at A4": lambda m: m["stretchCents"].__setitem__(69, 3.0),
            "inharmB negative": lambda m: m["inharmB"].__setitem__(10, -1.0),
            "damperT60 zero": lambda m: m["damperT60"].__setitem__(10, 0.0),
            "freeT60 stops": lambda m: m["freeT60"].append([1.0] * 128),
            "releaseRule field": lambda m: m["releaseRule"].pop("heldDb"),
            "bool type": lambda m: m.__setitem__("releaseCarriesTail", 1),
            "int type": lambda m: m.__setitem__("lastDamper", 88.5),
            "lastDamper range": lambda m: m.__setitem__("lastDamper", 200),
            "empty credit": lambda m: m.__setitem__("credit", " "),
        }
        for name, fn in cases.items():
            self.assertNotEqual(self.broken(fn), [], name)

    def test_missing_unit_file(self):
        import tempfile, shutil
        with tempfile.TemporaryDirectory() as d:
            os.makedirs(os.path.join(d, "u"))
            for u in self.m["units"][1:]:
                shutil.copyfile(os.path.join(common.TEST_RES, u["file"]), os.path.join(d, u["file"]))
            e = kitmap.validate(self.m, kit_dir=d, env=self.env)
            self.assertTrue(any("missing" in x for x in e), e)
            shutil.copyfile(os.path.join(common.TEST_RES, "u", "1.opus"), os.path.join(d, "u", "0.opus"))
            e = kitmap.validate(self.m, kit_dir=d, env=self.env)
            self.assertTrue(any("sha1 mismatch" in x for x in e), e)

    def test_env_bytes(self):
        # env.bin covers every region: one byte per 10 ms from region frame 0
        for r in self.m["regions"]:
            self.assertEqual(r["envCount"], (r["frames"] + 479) // 480)
        self.assertEqual(sum(r["envCount"] for r in self.m["regions"]), len(self.env))


if __name__ == "__main__":
    unittest.main()
