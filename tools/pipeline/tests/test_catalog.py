"""T11.6: the catalogue's measured fields are present and folding stays ≤ 2% per default instrument;
the catalogue fixture (for WP9's T9.1) is up to date."""
import copy
import json
import os
import unittest

import _paths  # noqa: F401
import build_catalog
import common

MEASURED = ("sha1Hex", "bytes", "durationSec", "lowKey", "highKey", "notes", "hasSustain", "hasSoft",
            "hasSostenuto", "pedalMode", "folds")
WORK_FIELDS = ("id", "composer", "composerShort", "title", "shortTitle", "catalogue", "year", "era", "shelf",
               "defaultInstrument", "altInstruments", "source", "tier", "velocityPolicy", "tuning", "movements")


def src():
    with open(build_catalog.FIXTURE_SRC, encoding="utf-8") as f:
        return json.load(f)


class CatalogTest(unittest.TestCase):
    def test_fixture_builds_and_is_current(self):
        cat, errors, listen = build_catalog.build(src(), generated="2026-09-22T00:00:00Z", check_licences=False)
        self.assertEqual(errors, [])
        with open(build_catalog.FIXTURE_OUT, encoding="utf-8") as f:
            on_disk = json.load(f)
        self.assertEqual(on_disk, json.loads(common.json_dumps(cat, digits=3)),
                         "run tools/pipeline/build_catalog.py --fixture")
        self.assertEqual(len(cat["works"]), 3)
        self.assertTrue(any(l.startswith("test.scale.") for l in listen))      # tier C listed for L-6

    def test_measured_fields_and_coverage(self):
        cat, _e, _l = build_catalog.build(src(), generated="x", check_licences=False)
        self.assertEqual(set(cat), {"schema", "generated", "startHere", "shelves", "sources", "works"})
        seen = {"tuning": False, "flat": False, "sha1b32": False, "nullYear": False}
        for w in cat["works"]:
            self.assertEqual(tuple(w), WORK_FIELDS)
            seen["tuning"] |= w["tuning"] is not None
            seen["flat"] |= w["velocityPolicy"] == "flat"
            seen["nullYear"] |= w["year"] is None
            for i, m in enumerate(w["movements"], 1):
                self.assertEqual(m["id"], "%s.%d" % (w["id"], i))
                for f in MEASURED:
                    self.assertIn(f, m)
                self.assertTrue(os.path.isfile(os.path.join(common.ASSETS, m["asset"])))
                self.assertLessEqual(m["folds"][w["defaultInstrument"]], 0.02 * m["notes"])
                seen["sha1b32"] |= "sha1b32" in m
                self.assertEqual("sha1b32" in m, w["source"] == "krueger")
        self.assertTrue(all(seen.values()), seen)
        for mid in cat["startHere"]:
            self.assertTrue(any(m["id"] == mid for w in cat["works"] for m in w["movements"]))

    def test_rejects_folding_missing_and_bad_duration(self):
        s = src()
        s["works"][2]["movements"].append({"title": "fold", "asset": "midi/test/fold.mid"})
        s["works"][2]["defaultInstrument"] = "harpsichord"
        _c, e, _l = build_catalog.build(s, generated="x", check_licences=False)
        self.assertTrue(any("fold" in x and "2%" in x for x in e), e)
        s = src()
        s["works"][0]["movements"][0]["asset"] = "midi/test/nothing.mid"
        self.assertTrue(any("missing" in x for x in build_catalog.build(s, generated="x", check_licences=False)[1]))
        s = src()
        s["works"][0]["movements"][0]["expectDurationSec"] = 30.0
        self.assertTrue(any("duration" in x for x in build_catalog.build(s, generated="x", check_licences=False)[1]))
        s = src()
        self.assertTrue(any("licence file" in x for x in build_catalog.build(s, generated="x", check_licences=True,
                                                                               assets_dir=common.TEST_RES)[1]))

    def test_duration_coverage_warning(self):
        src = {"works": [{"movements": [{"expectDurationSec": 60.0}, {}, {}]}]}
        self.assertEqual(build_catalog.duration_coverage(src), (1, 3))
        self.assertIn("WARNING", build_catalog.coverage_warning(src))
        src["works"][0]["movements"][1]["expectDurationSec"] = 30.0
        self.assertIsNone(build_catalog.coverage_warning(src))

    def test_partial(self):
        cat, e, _l = build_catalog.build(src(), partial=True, generated="x", check_licences=False)
        self.assertEqual(e, [])
        keep = {m.rsplit(".", 1)[0] for m in cat["startHere"]}
        self.assertEqual({w["id"] for w in cat["works"]}, keep)


if __name__ == "__main__":
    unittest.main()
