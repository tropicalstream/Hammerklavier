"""T11.4: the ledger covers every asset and matches its bytes; modified=no files equal their source."""
import os
import shutil
import tempfile
import unittest

import _paths  # noqa: F401
import build_credits
import check_ledger
import common


class LedgerTest(unittest.TestCase):
    def test_repo_ledger_ok(self):
        self.assertEqual(check_ledger.check(check_ledger.read_ledger()), [])

    def test_rows_for_every_asset(self):
        rows = {r["asset_path"] for r in check_ledger.read_ledger()}
        for rel in check_ledger.scan():
            self.assertIn(rel, rows)
        for r in check_ledger.read_ledger():
            if r["asset_path"].startswith("midi/"):
                self.assertEqual(r["modified"], "no", r["asset_path"])

    def make_assets(self, d):
        a = os.path.join(d, "assets")
        common.write_bytes(os.path.join(a, "midi", "test", "x.mid"), b"MThd-x")
        common.write_bytes(os.path.join(a, "instruments", "probe.opus"), b"OggS-probe")
        return a

    def test_detects_problems(self):
        with tempfile.TemporaryDirectory() as d:
            a = self.make_assets(d)
            rows, unknown = check_ledger.update([], assets=a)
            self.assertEqual(unknown, [])
            self.assertEqual(check_ledger.check(rows, assets=a, root=d), [])
            # an asset without a row
            common.write_bytes(os.path.join(a, "midi", "test", "y.mid"), b"MThd-y")
            self.assertTrue(any("no ledger row" in e for e in check_ledger.check(rows, assets=a, root=d)))
            os.remove(os.path.join(a, "midi", "test", "y.mid"))
            # a changed file
            common.write_bytes(os.path.join(a, "midi", "test", "x.mid"), b"MThd-changed")
            e = check_ledger.check(rows, assets=a, root=d)
            self.assertTrue(any("sha1" in x for x in e) and any("bytes" in x for x in e), e)
            # modified=no must equal its cache source
            rows, _u = check_ledger.update(rows, assets=a)
            rows[1]["notes"] = "cache=src.mid"
            common.write_bytes(os.path.join(d, "src.mid"), b"MThd-original")
            e = check_ledger.check(rows, assets=a, root=d)
            self.assertTrue(any("differs from its source" in x for x in e), e)
            shutil.copyfile(os.path.join(a, "midi", "test", "x.mid"), os.path.join(d, "src.mid"))
            self.assertEqual(check_ledger.check(rows, assets=a, root=d), [])
            # bad fields
            rows[0]["licence_id"] = "WTFPL"
            rows[1]["modified"] = "yes"
            e = check_ledger.check(rows, assets=a, root=d)
            self.assertTrue(any("unknown licence" in x for x in e))
            self.assertTrue(any("how_modified" in x for x in e))

    def test_credits_text_conditional(self):
        rows = [{"asset_path": "instruments/grand/u/0.opus", "licence_id": "CC-BY-3.0"},
                {"asset_path": "midi/krueger/beethoven/elise.mid", "licence_id": "CC-BY-SA-3.0-DE"}]
        t = build_credits.credits_text(rows)
        self.assertIn("Salamander Grand Piano V3", t)
        self.assertIn("Bernd Krueger", t)
        self.assertNotIn("Gouin", t)             # IMSLP not installed
        self.assertNotIn("Madore", t)            # not permitted
        self.assertIn("Konzertzimmer", t)
        rows.append({"asset_path": "midi/imslp/handel_hwv430_gouin.mid", "licence_id": "CC-BY-SA-4.0"})
        self.assertIn("Gouin", build_credits.credits_text(rows))
        n = build_credits.notice(rows)
        self.assertIn("declared public domain by the author, 2022", n)

    def test_credits_need_licence_texts(self):
        with tempfile.TemporaryDirectory() as d:
            rows = [{"asset_path": "midi/krueger/x.mid", "licence_id": "CC-BY-SA-3.0-DE"}]
            self.assertTrue(build_credits.build(rows, assets=os.path.join(d, "a"), root=d))
            common.write_bytes(os.path.join(d, "a", "licenses", "CC-BY-SA-3.0-DE.txt"), b"licence")
            self.assertEqual(build_credits.build(rows, assets=os.path.join(d, "a"), root=d), [])
            for f in ("CREDITS.md", "NOTICE", os.path.join("a", "credits.txt"), os.path.join("a", "licenses", "SOURCES.csv")):
                self.assertTrue(os.path.isfile(os.path.join(d, f)), f)


if __name__ == "__main__":
    unittest.main()
