#!/usr/bin/env python3
"""After the download (PLAN.md §6.5 step 13, §3.11, R30): real Salamander regions as JVM test resources
→ core/src/test/resources/wp11/real/ (LFS).

C3, C4 and C5 (keys 48, 60, 72) at layers v10 and v13, and "the una corda C4": the region the
grand plays for C4 at v64 (HD layer v8, split 57–64; Salamander has no una corda recordings, the
engine's soft bus acts on the ordinary sample). Each is decoded and trimmed exactly as the kit
does (§6.5 steps 1–3 and 9: $OFF start trim, onset refined, 96-frame pre-roll, −3 dBFS peak),
then written as 16-bit mono 48 kHz WAV of 4 s, with a JSON sidecar (root, layer, onsetFrame,
thrFrame, gainDb, pitchCents, inharmB).

    python3 tools/pipeline/export_test_regions.py [--out DIR]
"""
import argparse
import os
import sys
import wave

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import audio  # noqa: E402
import common  # noqa: E402
import kit_build  # noqa: E402
import sfz  # noqa: E402

REGIONS = [("c3_v10", "C3", 10), ("c3_v13", "C3", 13), ("c4_v10", "C4", 10), ("c4_v13", "C4", 13),
           ("c5_v10", "C5", 10), ("c5_v13", "C5", 13), ("c4_unacorda_v8", "C4", 8)]
SECONDS = 4.0


def write_wav16_mono(path, mono):
    x = np.clip(np.round(np.asarray(mono) * 32767.0), -32768, 32767).astype("<i2")
    common.ensure_dir(os.path.dirname(path))
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(common.SR)
        w.writeframes(x.tobytes())


def export(out_dir):
    docs = os.path.join(common.SAMPLES, "grand-docs")
    table = {t["note"]: t for t in sfz.salamander_region_table(docs)}
    facts = {}
    for name, note, vno in REGIONS:
        t = table[note]
        offs = sfz.salamander_offsets(docs, vno)
        path = kit_build._find("%sv%d.flac" % (note, vno), ["grand-sustain", "grand-sustain-hd"])
        seg, info = kit_build.process_sample(audio.decode(path), "sustain", SECONDS, start_offset=offs.get(t["label"]))
        mono = audio.to_mono(seg)
        mono = mono * (audio.db_to_lin(-3.0) / max(1e-12, float(np.max(np.abs(mono)))))
        n = int(SECONDS * common.SR)
        mono = np.concatenate([mono, np.zeros(max(0, n - len(mono)))])[:n]
        write_wav16_mono(os.path.join(out_dir, name + ".wav"), mono)
        fit = kit_build.measure_pitch(seg, t["root"])
        facts[name] = {"root": t["root"], "layer": "v%d" % vno, "onsetFrame": info["onsetFrame"],
                       "thrFrame": info["thrFrame"], "gainDb": info["gainDb"],
                       "pitchCents": audio.pitch_cents(fit["f0"], t["root"]) if fit else 0.0,
                       "inharmB": fit["B"] if fit else 0.0, "frames": n, "format": "wav s16le mono 48000",
                       "sha1": common.sha1_file(os.path.join(out_dir, name + ".wav"))}
    common.write_text(os.path.join(out_dir, "regions.json"), common.json_dumps(facts, digits=7))
    return facts


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=os.path.join(common.TEST_RES, "real"))
    a = ap.parse_args(argv)
    f = export(a.out)
    print("exported %d regions -> %s" % (len(f), a.out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
