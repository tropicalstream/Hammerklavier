"""T11.2: the Opus round trip of a packed unit keeps every region's length and onset (±1 frame) and
decoded peaks ≤ −0.5 dBFS (with the re-normalisation step)."""
import os
import tempfile
import unittest

import numpy as np

import _paths  # noqa: F401
import audio
import common
import kit_build
import make_probe
import make_stub_bank
from make_fixtures import noise_burst


class OpusRoundTripTest(unittest.TestCase):
    def regions(self):
        rs = []
        for i, k in enumerate((24, 45, 60, 81, 102)):
            rs.append({"id": i, "onsetFrame": kit_build.PRE_ROLL, "gainDb": 0.0,
                       "pcm": make_stub_bank.synth_tone(k, 0.8).astype(np.float32)})
        # a click-like transient that overshoots after the codec
        click = np.zeros((24000, 2))
        click[kit_build.PRE_ROLL:kit_build.PRE_ROLL + 3] = [[1, 1], [-1, -1], [1, 1]]
        click *= 10 ** (-3 / 20)
        rs.append({"id": 5, "onsetFrame": kit_build.PRE_ROLL, "gainDb": 0.0, "pcm": click.astype(np.float32)})
        rs.append({"id": 6, "onsetFrame": kit_build.PRE_ROLL, "gainDb": 0.0,
                   "pcm": noise_burst(0.3, 7).astype(np.float32)})
        return rs

    def test_unit_round_trip(self):
        rs = self.regions()
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "u.opus")
            report = []
            frames = kit_build.encode_and_verify(rs, p, report, "test")
            dec = audio.decode(p)
            self.assertEqual(frames, len(dec))
            stream, starts = kit_build.pack_unit(rs)
            self.assertEqual(len(dec), len(stream))
            for r, s in zip(rs, starts):
                self.assertEqual(r["streamStart"], s)
                got = dec[s:s + len(r["pcm"])]
                self.assertEqual(len(got), len(r["pcm"]))
                self.assertLessEqual(abs(audio.alignment_lag(r["pcm"], got, 48)), 1)
                self.assertLessEqual(audio.peak_dbfs(got), kit_build.MAX_DECODED_PEAK_DB + 1e-6, r["id"])
            # gaps: 40 ms of silence before every region
            self.assertEqual(starts[0], kit_build.GAP)
            for r, s, s2 in zip(rs, starts, starts[1:]):
                self.assertEqual(s2 - s, len(r["pcm"]) + kit_build.GAP)

    def test_renormalised_region_keeps_its_level(self):
        rs = self.regions()
        before = {r["id"]: r["gainDb"] for r in rs}
        with tempfile.TemporaryDirectory() as d:
            kit_build.encode_and_verify(rs, os.path.join(d, "u.opus"), [], "t")
        for r in rs:
            if r["gainDb"] != before[r["id"]]:
                self.assertGreater(r["gainDb"], before[r["id"]])      # attenuated pcm, compensating gain

    def test_probe(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "probe.opus")
            self.assertEqual(make_probe.main(["--out", p]), 0)
            dec = audio.decode(p)
            self.assertEqual(len(dec), make_probe.FRAMES)
            self.assertEqual(int(np.argmax(np.abs(dec).max(axis=1))), make_probe.CLICK_FRAME)

    def test_bitexact(self):
        x = make_stub_bank.synth_tone(60, 0.5).astype(np.float32)
        with tempfile.TemporaryDirectory() as d:
            a, b = os.path.join(d, "a.opus"), os.path.join(d, "b.opus")
            audio.encode_opus(x, a)
            audio.encode_opus(x, b)
            self.assertEqual(common.read_bytes(a), common.read_bytes(b))


if __name__ == "__main__":
    unittest.main()
