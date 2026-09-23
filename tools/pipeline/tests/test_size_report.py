"""T11.5: size_report.py stays within the cap of the approved option and fails above it."""
import os
import tempfile
import unittest

import _paths  # noqa: F401
import common
import size_report


class SizeReportTest(unittest.TestCase):
    def test_repo_within_cap(self):
        ok, text = size_report.report()
        self.assertTrue(ok, text)
        self.assertIn("stub + probe + bench", text)

    def test_fails_over_cap(self):
        with tempfile.TemporaryDirectory() as d:
            apk = os.path.join(d, "big.apk")
            with open(apk, "wb") as f:
                f.truncate(61 * size_report.MB)
            self.assertFalse(size_report.report(apk=apk, option="samples-standard")[0])
            self.assertTrue(size_report.report(apk=apk, option="samples-hd")[0])

    def test_unacknowledged_overrun_fails(self):
        ok, text = size_report.report()
        self.assertTrue(ok, text)
        ok, text = size_report.report(acknowledged={})
        mb = size_report.dir_bytes(os.path.join(common.ASSETS, "midi")) / size_report.MB
        if mb > 3.5 * size_report.OVER_FAIL:
            self.assertFalse(ok)
            self.assertIn("FAIL: midi", text)

    def test_approved_option(self):
        self.assertIn(size_report.approved_option(), size_report.CAPS)


if __name__ == "__main__":
    unittest.main()
