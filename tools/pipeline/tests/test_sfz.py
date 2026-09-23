"""The SFZ and Salamander data readers (used by kit_build.py for the real kits)."""
import os
import unittest

import _paths  # noqa: F401
import common
import sfz

SAMPLE_SFZ = """
// comment
<control> default_path=Samples/
#define $EXT wav
<global> amp_veltrack=80
<group> trigger=attack ampeg_release=0.4
<region> sample=Kit/Sustains/A0.$EXT pitch_keycenter=21 lokey=21 hikey=22 lovel=0 hivel=83
<region>
sample=Kit/Sustains/C1.$EXT
key=24
<group>
trigger=release
<region> sample=Kit/Rel/A0.$EXT lokey=21 hikey=22 pitch_keycenter=21 volume=-5
"""


class SfzTest(unittest.TestCase):
    def test_parse(self):
        r = sfz.parse_sfz_text(SAMPLE_SFZ)
        self.assertEqual(len(r), 3)
        self.assertEqual(r[0]["sample"], "Kit/Sustains/A0.wav")
        self.assertEqual(r[0]["amp_veltrack"], "80")
        self.assertEqual(r[0]["trigger"], "attack")
        self.assertEqual(r[1]["key"], "24")
        self.assertEqual(r[2]["trigger"], "release")
        self.assertNotIn("ampeg_release", r[2])              # a new <group> resets group opcodes
        self.assertEqual(r[2]["amp_veltrack"], "80")         # the <global> stays

    def test_note_names(self):
        self.assertEqual(sfz.note_to_key("A0"), 21)
        self.assertEqual(sfz.note_to_key("C4"), 60)
        self.assertEqual(sfz.note_to_key("A#-1"), 10)
        self.assertEqual(sfz.note_to_key("C8"), 108)

    @unittest.skipUnless(os.path.isdir(os.path.join(common.SAMPLES, "grand-docs")), "samples not downloaded")
    def test_real_maps(self):
        d = os.path.join(common.SAMPLES, "grand-docs")
        t = sfz.salamander_region_table(d)
        self.assertEqual(len(t), 30)
        self.assertEqual((t[0]["root"], t[-1]["root"]), (21, 108))
        splits = sfz.salamander_splits(d)
        self.assertEqual([(a, b) for _n, a, b in splits], [
            (1, 26), (27, 34), (35, 36), (37, 43), (44, 46), (47, 50), (51, 56), (57, 64), (65, 72), (73, 80),
            (81, 88), (89, 96), (97, 104), (105, 112), (113, 120), (121, 127)])
        self.assertEqual(len(sfz.salamander_offsets(d, 1)), 30)
        self.assertEqual(len(sfz.salamander_tune(d, "ret")), 30)
        up = sfz.vcsl_regions(os.path.join(common.SAMPLES, "vcsl-sfz-maps", "Upright Piano, Knight.sfz"))
        self.assertEqual(sum(1 for r in up if r["trigger"] == "release"), 45)
        h8 = sfz.vcsl_regions(os.path.join(common.SAMPLES, "vcsl-sfz-maps", "Harpsichord, Flemish - 8'.sfz"))
        self.assertEqual(sum(1 for r in h8 if r["trigger"] == "attack"), 28)

    @unittest.skipUnless(os.path.isdir(os.path.join(common.SAMPLES, "grand-sustain-hd")), "samples not downloaded")
    def test_kit_sources_resolve(self):
        import kit_build
        for kit, n_sus in (("grand-hd", 480), ("grand-std", 180)):
            layers, stops, units, srcs = kit_build.grand_sources(kit)
            self.assertEqual(sum(1 for s in srcs if s.kind == "sustain"), n_sus)
            self.assertEqual(sum(1 for s in srcs if s.kind == "release"), 88)
            self.assertEqual(sorted(u["order"] for u in units), list(range(len(units))))
        layers, stops, units, srcs = kit_build.upright_sources()
        self.assertEqual(len(layers), 3)
        self.assertEqual(sum(1 for s in srcs if s.kind in ("pedalDown", "pedalUp")), 8)
        layers, stops, units, srcs = kit_build.harpsichord_sources()
        self.assertEqual(len(stops), 2)
        self.assertTrue(any(s.borrowable for s in srcs))


if __name__ == "__main__":
    unittest.main()
