"""T11.8: smf_stats agrees with the test MIDIs' known contents; the test files and the golden file are
up to date with their generators."""
import json
import os
import unittest

import _paths  # noqa: F401
import common
import make_test_midis
import smf_stats

TEST_DIR = os.path.join(common.MIDI_ASSETS, "test")
GOLDEN = os.path.join(common.TEST_RES, "midi_facts_golden.json")


class SmfStatsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.files = make_test_midis.all_files()
        cls.known = make_test_midis.known_contents()

    def test_known_contents(self):
        for name, data in self.files.items():
            s = smf_stats.stats(data, name)
            k = self.known[name]
            for f in ("notes", "lowKey", "highKey", "cc64", "cc66", "cc67", "lastNoteOffUs", "channels"):
                self.assertEqual(s[f], k[f], "%s %s" % (name, f))

    def test_twin_note_lists_exact(self):
        for name in make_test_midis.TWIN_NAMES:
            fmt, div, tracks = smf_stats.read_smf(self.files[name + ".mid"])
            self.assertEqual((fmt, div, len(tracks)), (0, 500, 1))
            tm = smf_stats.TempoMap(div, tracks, fmt)
            ons = [(round(tm.us(t)), a, b) for t, k, _c, a, b in tracks[0] if k == "on"]
            ons.sort()
            k = self.known[name + ".mid"]
            self.assertEqual([o[0] for o in ons], k["onsUs"], name)
            self.assertEqual([o[1] for o in ons], k["keys"], name)
            self.assertEqual([o[2] for o in ons], k["vels"], name)

    def test_pedal_modes(self):
        st = {n: smf_stats.stats(d) for n, d in self.files.items()}
        self.assertEqual(st["pedalhalf.mid"]["pedalMode"], "CONTINUOUS")
        self.assertEqual(st["storm64.mid"]["pedalMode"], "SWITCH")
        self.assertEqual(st["scale.mid"]["pedalMode"], "NONE")
        self.assertTrue(st["sostenuto.mid"]["hasSostenuto"])
        self.assertTrue(st["unacorda.mid"]["hasSoft"])
        self.assertEqual(st["fold.mid"]["folds"], {"grand": 21, "upright": 21, "harpsichord": 48})
        self.assertTrue(st["sync.mid"]["flatVelocity"])
        self.assertFalse(st["crescendo.mid"]["flatVelocity"])

    def test_format_pair_same_music(self):
        a = smf_stats.stats(self.files["format0.mid"])
        b = smf_stats.stats(self.files["format1.mid"])
        self.assertEqual((a["format"], a["tracks"], b["format"], b["tracks"]), (0, 1, 1, 3))
        for f in ("notes", "lowKey", "highKey", "cc64", "lastNoteOffUs", "firstNoteOnUs", "channels",
                  "velocityHistogram", "pedalMode"):
            self.assertEqual(a[f], b[f], f)

    def test_smpte_ignores_tempo(self):
        s = smf_stats.stats(self.files["smpte25.mid"])
        self.assertLess(s["division"], 0)
        self.assertEqual(s["lastNoteOffUs"], 2925000)

    def test_running_status_file_uses_it(self):
        data = self.files["running_status.mid"]
        # far fewer status bytes than events: running status really is used
        self.assertLess(data.count(bytes([0x92])), 8)
        self.assertIn(b"\xf0\x05", data)

    def test_assets_up_to_date(self):
        for name, data in self.files.items():
            p = os.path.join(TEST_DIR, name)
            self.assertTrue(os.path.isfile(p), name)
            self.assertEqual(common.read_bytes(p), data, "%s differs from make_test_midis.py output" % name)

    def test_golden_up_to_date(self):
        with open(GOLDEN, encoding="utf-8") as f:
            g = json.load(f)
        want = json.loads(common.json_dumps(smf_stats.golden(smf_stats.collect()), digits=3))
        self.assertEqual(g, want, "run tools/pipeline/smf_stats.py")
        self.assertEqual(g["schema"], 1)
        for name in self.files:
            self.assertIn("midi/test/" + name, g["files"])

    def test_tolerance(self):
        # RIFF RMID wrapper, CC123 ends open notes, hanging notes end at the track end
        body = make_test_midis.track_chunk([(0, bytes([0x90, 60, 90])), (100, bytes([0x90, 62, 90])),
                                            (200, bytes([0xB0, 123, 0])), (250, bytes([0x90, 64, 90]))])
        smf = make_test_midis.smf(0, make_test_midis.ppq_division(500), [body])
        riff = b"RIFF" + (len(smf) + 12).to_bytes(4, "little") + b"RMIDdata" + len(smf).to_bytes(4, "little") + smf
        s = smf_stats.stats(riff)
        self.assertEqual(s["notes"], 3)
        self.assertEqual(s["lastNoteOffUs"], 250000 + 30000)   # hanging note at the track end (tick 250) → 30 ms


if __name__ == "__main__":
    unittest.main()
