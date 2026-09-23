#!/usr/bin/env python3
"""DecoderProbe asset (PLAN.md §3.3, §6.1): assets/instruments/probe.opus = 0.5 s of silence with a
one-sample click (0.5 FS, both channels) at frame 4800, encoded with the kit settings. The app
records offset = argmax|x| − 4800 (0 when the decoder drops the 312-frame pre-skip).

    python3 tools/pipeline/make_probe.py [--out PATH]
"""
import argparse
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import audio  # noqa: E402
import common  # noqa: E402

FRAMES = 24000
CLICK_FRAME = 4800
CLICK = 0.5


def probe_pcm():
    x = np.zeros((FRAMES, 2), dtype=np.float32)
    x[CLICK_FRAME, :] = CLICK
    return x


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=os.path.join(common.INSTRUMENTS, "probe.opus"))
    a = ap.parse_args(argv)
    common.ensure_dir(os.path.dirname(a.out))
    audio.encode_opus(probe_pcm(), a.out)
    dec = audio.decode(a.out)
    at = int(np.argmax(np.abs(dec).max(axis=1)))
    print("probe.opus: %d bytes; ffmpeg decode: %d frames, click at %d (offset %d)" % (
        os.path.getsize(a.out), len(dec), at, at - CLICK_FRAME))
    return 0 if abs(at - CLICK_FRAME) <= 1 and len(dec) == FRAMES else 1


if __name__ == "__main__":
    sys.exit(main())
