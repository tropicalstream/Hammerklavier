#!/usr/bin/env python3
"""PLAN §8.4 T-APL: mean Rec.709 luma (Y' = .2126 R' + .7152 G' + .0722 B', 0..100 %) of a 1280x480 screencap.
usage: apl_meter.py <png> [...]  (decodes with ffmpeg to raw rgb24; numpy only)"""
import subprocess, sys
import numpy as np
for p in sys.argv[1:]:
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", p, "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], check=True, capture_output=True).stdout
    a = np.frombuffer(raw, np.uint8).astype(np.float32).reshape(-1, 3)
    y = a @ np.array([0.2126, 0.7152, 0.0722], np.float32)
    print(f"{p} pixels={len(y)} apl={100.0 * y.mean() / 255.0:.2f}%")
