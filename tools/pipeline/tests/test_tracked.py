"""Every ledgered asset and the real test regions are committed to git, not merely present on disk."""
import json
import os
import subprocess
import unittest

import _paths  # noqa: F401
import check_ledger
import common

REAL = os.path.join(common.ROOT, "core", "src", "test", "resources", "wp11", "real")
REAL_NAMES = ["c3_v10", "c3_v13", "c4_v10", "c4_v13", "c4_unacorda_v8", "c5_v10", "c5_v13"]


def tracked():
    try:
        out = subprocess.run(["git", "-C", common.ROOT, "ls-files", "-z"], capture_output=True, check=True).stdout
    except (OSError, subprocess.CalledProcessError):
        return None
    return set(p for p in out.decode("utf-8").split("\0") if p)


class TrackedTest(unittest.TestCase):
    def setUp(self):
        self.files = tracked()
        if self.files is None:
            self.skipTest("not a git checkout")

    def test_ledger_rows_are_tracked(self):
        missing = [r["asset_path"] for r in check_ledger.read_ledger()
                   if "app/src/main/assets/" + r["asset_path"] not in self.files]
        self.assertEqual(missing, [], "ledger rows whose files are not committed")

    def test_sankey_notice_is_binary_exact(self):
        out = subprocess.run(["git", "-C", common.ROOT, "check-attr", "text", "app/src/main/assets/licenses/SANKEY.txt"],
                             capture_output=True, check=True).stdout.decode()
        self.assertIn("unset", out)

    def test_real_regions_committed_with_sha1(self):
        rj = os.path.join(REAL, "regions.json")
        self.assertIn(os.path.relpath(rj, common.ROOT), self.files)
        facts = json.load(open(rj))
        self.assertEqual(sorted(facts), sorted(REAL_NAMES))
        for n in REAL_NAMES:
            p = os.path.join(REAL, n + ".wav")
            self.assertIn(os.path.relpath(p, common.ROOT), self.files, n)
            self.assertEqual(common.sha1_file(p), facts[n]["sha1"], n)


if __name__ == "__main__":
    unittest.main()
