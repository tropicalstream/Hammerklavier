#!/usr/bin/env python3
"""PLAN §8.6 coarse A/V check: flash frames (luma of the centre disc) vs click onsets (envelope) in a
scrcpy recording.  python3 tools/device/avsync.py build/sync.mkv [--eye left|right]
Prints each matched pair and median / p10 / p90 of (flash - click) in ms (positive = picture late)."""
import subprocess, sys, json
import numpy as np

def run(cmd): return subprocess.run(cmd, check=True, capture_output=True).stdout

def main():
    f = sys.argv[1]
    eye = "right" if "--eye" in sys.argv and sys.argv[sys.argv.index("--eye") + 1] == "right" else "left"
    info = json.loads(run(["ffprobe", "-v", "quiet", "-print_format", "json", "-show_streams", f]))
    v = next(s for s in info["streams"] if s["codec_type"] == "video")
    W, H = int(v["width"]), int(v["height"])
    # Centre of one eye: a 40x40 box around (W/4, H/2) or (3W/4, H/2).
    cx = W // 4 if eye == "left" else 3 * W // 4
    cw, ch = max(8, W // 32), max(8, H // 12)
    crop = f"crop={cw}:{ch}:{cx - cw // 2}:{H // 2 - ch // 2}"
    # Frame times from the container (VFR): showinfo pts_time.
    p = subprocess.run(["ffmpeg", "-v", "info", "-i", f, "-an", "-fps_mode", "passthrough", "-vf", crop + ",format=gray,showinfo",
                        "-f", "rawvideo", "-"], capture_output=True)
    raw = np.frombuffer(p.stdout, np.uint8)
    n = raw.size // (cw * ch)
    luma = raw[: n * cw * ch].reshape(n, cw * ch).mean(axis=1)
    ts = []
    for line in p.stderr.decode(errors="ignore").splitlines():
        if "pts_time:" in line:
            ts.append(float(line.split("pts_time:")[1].split()[0]))
    n = min(n, len(ts)); luma = luma[:n]; ts = np.array(ts[:n])
    thr = (np.percentile(luma, 5) + luma.max()) / 2
    on = luma > thr
    flashes = [ts[i] for i in range(1, len(on)) if on[i] and not on[i - 1]]
    # Audio: mono float at 48 kHz, envelope onsets.
    a = np.frombuffer(run(["ffmpeg", "-v", "quiet", "-i", f, "-vn", "-ac", "1", "-ar", "48000", "-f", "f32le", "-"]), np.float32)
    astart = float(next(s for s in info["streams"] if s["codec_type"] == "audio").get("start_time", 0))
    vstart = float(v.get("start_time", 0))
    env = np.abs(a)
    k = 48
    env = np.convolve(env, np.ones(k) / k, mode="same")
    athr = 0.25 * np.percentile(env, 99.9)
    clicks, last = [], -1.0
    for i in np.flatnonzero((env[1:] > athr) & (env[:-1] <= athr)):
        t = i / 48000.0 + astart
        if t - last > 0.3: clicks.append(t); last = t
    d = []
    for fl in flashes:
        fl2 = fl + (0 if True else vstart)
        c = min(clicks, key=lambda c: abs(c - fl2), default=None)
        if c is not None and abs(c - fl2) < 0.25: d.append((fl2 - c) * 1000)
    print(f"flashes={len(flashes)} clicks={len(clicks)} matched={len(d)} frames={n}")
    if d:
        d = np.array(d)
        print("median=%.1f ms p10=%.1f p90=%.1f spread=%.1f" % (np.median(d), np.percentile(d, 10), np.percentile(d, 90),
              np.percentile(d, 90) - np.percentile(d, 10)))

if __name__ == "__main__":
    main()
