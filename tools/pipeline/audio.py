"""Audio analysis and codec helpers for the kit pipeline (PLAN.md §6.1, §6.5). numpy + ffmpeg only.

Arrays are float32/float64 numpy arrays of shape (frames, 2) for stereo PCM (48 kHz unless said
otherwise) and (frames,) for mono. Levels are dBFS re a full-scale sample of 1.0.
"""
import json
import math
import subprocess

import numpy as np

SR = 48000
OPUS_ARGS = ["-c:a", "libopus", "-b:a", "112k", "-vbr", "on", "-application", "audio",
             "-frame_duration", "60"]
# Bit-exact muxing: fixed Ogg serial numbers and no encoder/version tags, so a rebuild is
# byte-identical (T11.7).
BITEXACT = ["-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact"]
SWR = "aresample=48000:resampler=swr:filter_size=64:cutoff=0.97"


class AudioError(RuntimeError):
    pass


def _run(cmd, stdin=None):
    p = subprocess.run(cmd, input=stdin, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if p.returncode != 0:
        raise AudioError("%s failed: %s" % (cmd[0], p.stderr.decode(errors="replace")[-800:]))
    return p.stdout


# --------------------------------------------------------------------------- ffmpeg I/O

def probe(path):
    """{'rate', 'channels', 'duration', 'codec', 'bits'} of the first audio stream."""
    out = _run(["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries",
                "stream=sample_rate,channels,duration,codec_name,bits_per_raw_sample,bits_per_sample",
                "-of", "json", path])
    s = json.loads(out)["streams"][0]
    bits = s.get("bits_per_raw_sample") or s.get("bits_per_sample") or 0
    return {"rate": int(s["sample_rate"]), "channels": int(s["channels"]),
            "duration": float(s.get("duration") or 0.0), "codec": s.get("codec_name"),
            "bits": int(bits) if str(bits).isdigit() else 0}


def decode(path, sr=SR):
    """Any file ffmpeg reads → float32 (frames, 2) at sr; mono duplicated; swr only if needed."""
    info = probe(path)
    cmd = ["ffmpeg", "-v", "error", "-nostdin", "-i", path]
    if info["rate"] != sr:
        cmd += ["-af", SWR]
    cmd += ["-f", "f32le", "-ac", "2", "-ar", str(sr), "-"]
    raw = _run(cmd)
    return np.frombuffer(raw, dtype="<f4").reshape(-1, 2).copy()


def encode_opus(pcm, out_path):
    """float (frames, 2) at 48 kHz → one Ogg Opus stream with the kit encoding (§3.2)."""
    pcm = np.ascontiguousarray(pcm, dtype="<f4")
    if pcm.ndim != 2 or pcm.shape[1] != 2:
        raise AudioError("encode_opus wants (frames, 2)")
    cmd = (["ffmpeg", "-v", "error", "-nostdin", "-y", "-f", "f32le", "-ar", str(SR), "-ac", "2", "-i", "-"]
           + OPUS_ARGS + BITEXACT + ["-f", "opus", out_path])
    _run(cmd, stdin=pcm.tobytes())


def to_mono(pcm):
    pcm = np.asarray(pcm, dtype=np.float64)
    return pcm if pcm.ndim == 1 else pcm.mean(axis=1)


def peak_dbfs(pcm):
    p = float(np.max(np.abs(pcm))) if len(pcm) else 0.0
    return -math.inf if p <= 0 else 20.0 * math.log10(p)


def db_to_lin(db):
    return 10.0 ** (db / 20.0)


# --------------------------------------------------------------------------- onsets

def _abs_env(pcm, box=8):
    """Causal RMS envelope over `box` frames of both channels."""
    x = np.asarray(pcm, dtype=np.float64)
    e = (x * x).sum(axis=1) if x.ndim == 2 else x * x
    c = np.concatenate([[0.0], np.cumsum(e)])
    n = len(e)
    idx = np.arange(n)
    lo = np.maximum(0, idx - box + 1)
    s = c[idx + 1] - c[lo]
    return np.sqrt(np.maximum(s, 0.0) / box)


def coarse_start(pcm, db=-50.0, within_frames=None):
    """First frame above `db` re the peak (the VCSL start trim); None if silent."""
    a = np.max(np.abs(np.asarray(pcm)), axis=1) if np.asarray(pcm).ndim == 2 else np.abs(pcm)
    if within_frames is not None:
        a = a[:within_frames]
    pk = float(a.max()) if len(a) else 0.0
    if pk <= 0:
        return None
    hits = np.nonzero(a > pk * db_to_lin(db))[0]
    return int(hits[0]) if len(hits) else None


def onset_frame(pcm, cross_db=-30.0, floor_db=-60.0):
    """Hammer/pluck onset to the sample: the energy envelope's crossing of `cross_db` re its peak,
    walked back to where the envelope leaves the floor (max of `floor_db` re peak and 3× the
    pre-onset noise). Returns None for silence."""
    env = _abs_env(pcm)
    pk = float(env.max()) if len(env) else 0.0
    if pk <= 0:
        return None
    i = int(np.nonzero(env > pk * db_to_lin(cross_db))[0][0])
    noise = 0.0
    if i > 480:
        noise = float(np.median(env[: min(240, i - 240)]))
    floor = max(pk * db_to_lin(floor_db), 3.0 * noise)
    j = i
    while j > 0 and env[j - 1] > floor:
        j -= 1
    return j


def crossing_frame(pcm, db=-20.0):
    """First frame whose |sample| exceeds `db` re the peak (alignment check after the codec)."""
    a = np.max(np.abs(np.asarray(pcm)), axis=1)
    pk = float(a.max()) if len(a) else 0.0
    if pk <= 0:
        return None
    return int(np.nonzero(a > pk * db_to_lin(db))[0][0])


def alignment_lag(src, dec, start, length=960, max_lag=48, plateau=0.99):
    """Lag (frames) of `dec` against `src` that maximises the correlation of their first
    differences (the attack transient) over [start, start + length): 0 when a codec round trip
    kept the region's timing. 20 ms: over 10 ms a bass note's correlation peak is flat enough
    that a few frames of codec smearing win (F#1 v1 of Salamander read +4 at 10 ms, 0 at 20 ms).
    A soft attack still has a broad peak; when the correlation at lag 0 is within `plateau` of
    the best one, the timing is not distinguishable from 0 and 0 is returned (a real shift of a
    sharp attack drops the lag-0 correlation far below that)."""
    a = np.diff(to_mono(src))
    b = np.diff(to_mono(dec))
    length = max(16, min(length, len(a) - start - max_lag))   # every lag in ±max_lag stays inside
    seg = a[start:start + length]
    best, best_lag, c0 = -math.inf, 0, None
    for lag in range(-max_lag, max_lag + 1):
        lo = start + lag
        if lo < 0 or lo + len(seg) > len(b):
            continue
        c = float(np.dot(seg, b[lo:lo + len(seg)]))
        if lag == 0:
            c0 = c
        if c > best:
            best, best_lag = c, lag
    if c0 is not None and best > 0 and c0 >= plateau * best:
        return 0
    return best_lag


def thr_frame(pcm, db=-40.0):
    """First frame above −40 dB re the region peak (T-ALIGN)."""
    return crossing_frame(pcm, db)


# --------------------------------------------------------------------------- pitch

def _peak_interp(mag_db, k):
    """Parabolic interpolation of a spectral peak on the dB magnitude."""
    if k <= 0 or k >= len(mag_db) - 1:
        return float(k), float(mag_db[k])
    a, b, c = mag_db[k - 1], mag_db[k], mag_db[k + 1]
    den = a - 2 * b + c
    if den == 0:
        return float(k), float(b)
    p = 0.5 * (a - c) / den
    return k + p, b - 0.25 * (a - c) * p


def fit_partials(mono, f0_nominal, sr=SR, t0=0.3, t1=1.3, n_max=24, n_min=6, floor_db=-70.0):
    """Partial-series fit f_n = n·f0·sqrt(1 + B·n²) over FFT peaks in [t0, t1] s.

    Returns dict(f0, B, partials=[(n, f)], resid_cents) or None. Robust weighted least squares
    on (f_n/n)² = f0² + f0²·B·n², dropping outliers (> 3 MAD) and refitting.
    """
    x = np.asarray(mono, dtype=np.float64)
    a, b = int(t0 * sr), min(len(x), int(t1 * sr))
    if b - a < sr // 10:
        a, b = 0, len(x)
    seg = x[a:b]
    if len(seg) < 1024 or not np.any(seg):
        return None
    w = np.hanning(len(seg))
    nfft = 1 << int(math.ceil(math.log2(len(seg) * 8)))
    spec = np.abs(np.fft.rfft(seg * w, nfft))
    mag_db = 20 * np.log10(spec + 1e-20)
    hz_per_bin = sr / nfft
    # the floor is relative to the strongest peak at or above half the nominal f0: room rumble and
    # handling noise below the fundamental would otherwise set it (a treble upright note's decay
    # is 20-30 dB under its sub-50 Hz rumble by 0.3 s)
    top = float(mag_db[max(1, int(0.5 * f0_nominal / hz_per_bin)):].max())
    f0, B = float(f0_nominal), 0.0
    pts = []
    for n in range(1, n_max + 1):
        f_exp = n * f0 * math.sqrt(1 + B * n * n)
        if f_exp > 0.45 * sr:
            break
        half = max(3.0 * hz_per_bin, min(0.25 * f0, 0.03 * f_exp))
        lo, hi = int((f_exp - half) / hz_per_bin), int(math.ceil((f_exp + half) / hz_per_bin))
        lo, hi = max(1, lo), min(len(mag_db) - 2, hi)
        if hi <= lo:
            break
        k = lo + int(np.argmax(mag_db[lo:hi + 1]))
        kf, m = _peak_interp(mag_db, k)
        if m < top + floor_db:
            continue
        pts.append((n, kf * hz_per_bin, m))
        if len(pts) >= 2:
            f0, B = _ls_fit(pts)
            B = max(B, 0.0)                 # a noisy early pair can give B < 0; the search needs B >= 0
    if len(pts) < 2:
        return None
    keep = pts
    for _ in range(3):
        f0, B = _ls_fit(keep)
        B = max(B, 0.0)
        res = np.array([_cents(f, n * f0 * math.sqrt(1 + B * n * n)) for n, f, _m in keep])
        mad = float(np.median(np.abs(res - np.median(res)))) + 1e-3
        nk = [p for p, r in zip(keep, res) if abs(r) <= max(3 * mad, 0.5)]
        if len(nk) == len(keep) or len(nk) < max(2, n_min // 2):
            break
        keep = nk
    f0, B = _ls_fit(keep)
    B = max(B, 0.0)
    res = [_cents(f, n * f0 * math.sqrt(1 + B * n * n)) for n, f, _m in keep]
    return {"f0": f0, "B": max(B, 0.0), "partials": [(n, f) for n, f, _m in keep],
            "resid_cents": float(np.sqrt(np.mean(np.square(res))))}


def fundamental_peak(mono, f0_nominal, sr=SR, t0=0.05, t1=0.55, span=0.07):
    """Frequency of the strongest spectral peak within ±`span` (±117 cents at 7%) of the nominal
    f0 over [t0, t1] s, parabolically interpolated; None when there is no signal. The top octave's
    pitch: few partials lie below Nyquist and the fundamental dominates (Salamander's C8 sounds
    about +99 cents, outside the partial search window)."""
    x = np.asarray(mono, dtype=np.float64)
    seg = x[int(t0 * sr):min(len(x), int(t1 * sr))]
    if len(seg) < 1024 or not np.any(seg):
        return None
    nfft = 1 << int(math.ceil(math.log2(len(seg) * 8)))
    mag_db = 20 * np.log10(np.abs(np.fft.rfft(seg * np.hanning(len(seg)), nfft)) + 1e-20)
    hz_per_bin = sr / nfft
    lo = max(1, int(f0_nominal * (1 - span) / hz_per_bin))
    hi = min(len(mag_db) - 2, int(math.ceil(f0_nominal * (1 + span) / hz_per_bin)))
    if hi <= lo:
        return None
    k = lo + int(np.argmax(mag_db[lo:hi + 1]))
    return _peak_interp(mag_db, k)[0] * hz_per_bin


def _ls_fit(pts):
    n = np.array([p[0] for p in pts], dtype=np.float64)
    f = np.array([p[1] for p in pts], dtype=np.float64)
    y = (f / n) ** 2
    wts = 1.0 / n                     # lower partials are better resolved relative to their spacing
    A = np.stack([np.ones_like(n), n * n], axis=1)
    if len(pts) == 2 and pts[0][0] == pts[1][0]:
        return float(f[0] / n[0]), 0.0
    if len(pts) < 3:
        sol = np.linalg.lstsq(A, y, rcond=None)[0]
    else:
        W = np.sqrt(wts)
        sol = np.linalg.lstsq(A * W[:, None], y * W, rcond=None)[0]
    c0, c1 = float(sol[0]), float(sol[1])
    if c0 <= 0:
        return float(np.median(f / n)), 0.0
    return math.sqrt(c0), c1 / c0


def _cents(f, ref):
    return 1200.0 * math.log2(f / ref)


def cents(f, ref):
    return _cents(f, ref)


def key_hz(key, a4=440.0):
    return a4 * 2.0 ** ((key - 69) / 12.0)


def pitch_cents(f0, root):
    """Measured sounding pitch re 100·root on A440 ET (map.json pitchCents)."""
    return _cents(f0, key_hz(root))


# --------------------------------------------------------------------------- loudness

def a_weight_db(f):
    f = np.asarray(f, dtype=np.float64)
    f2 = f * f
    ra = (12194.0 ** 2 * f2 * f2) / ((f2 + 20.6 ** 2) * np.sqrt((f2 + 107.7 ** 2) * (f2 + 737.9 ** 2))
                                     * (f2 + 12194.0 ** 2))
    with np.errstate(divide="ignore"):
        return 20 * np.log10(np.maximum(ra, 1e-30)) + 2.0


def aweighted_rms_db(pcm, start, sr=SR, dur=0.150):
    """A-weighted RMS (dB) over [start, start + dur): FFT weighting, Parseval, both channels."""
    x = np.asarray(pcm, dtype=np.float64)
    if x.ndim == 1:
        x = x[:, None]
    seg = x[start:start + int(dur * sr)]
    if len(seg) < 16:
        return -math.inf
    n = len(seg)
    freqs = np.fft.rfftfreq(n, 1.0 / sr)
    wlin = 10 ** (a_weight_db(np.maximum(freqs, 1e-3)) / 20.0)
    wlin[0] = 0.0
    tot = 0.0
    for ch in range(seg.shape[1]):
        X = np.fft.rfft(seg[:, ch])
        P = np.abs(X * wlin) ** 2
        scale = np.full(len(P), 2.0)
        scale[0] = 1.0
        if n % 2 == 0:
            scale[-1] = 1.0
        tot += float((P * scale).sum()) / (n * n)
    ms = tot / seg.shape[1]
    return -math.inf if ms <= 0 else 10 * math.log10(ms)


ENV_BLOCK = 480                   # 10 ms at 48 kHz


def env_bytes(pcm):
    """env.bin bytes for one region: 10 ms RMS over both channels, round(−dBFS × 2) in 0..255."""
    x = np.asarray(pcm, dtype=np.float64)
    n = len(x)
    count = (n + ENV_BLOCK - 1) // ENV_BLOCK
    out = bytearray(count)
    for i in range(count):
        blk = x[i * ENV_BLOCK:(i + 1) * ENV_BLOCK]
        ms = float(np.mean(blk * blk)) if len(blk) else 0.0
        if ms <= 0:
            out[i] = 255
            continue
        db = 10 * math.log10(ms)
        out[i] = int(min(255, max(0, round(-db * 2))))
    return bytes(out)


def rms_env_db(pcm, block=ENV_BLOCK):
    x = np.asarray(pcm, dtype=np.float64)
    if x.ndim == 1:
        x = x[:, None]
    n = len(x) // block
    if n == 0:
        return np.zeros(0)
    ms = (x[: n * block] ** 2).reshape(n, block, x.shape[1]).mean(axis=(1, 2))
    return 10 * np.log10(np.maximum(ms, 1e-20))


# --------------------------------------------------------------------------- decays

def schroeder_db(mono):
    e = np.asarray(mono, dtype=np.float64) ** 2
    s = np.cumsum(e[::-1])[::-1]
    return 10 * np.log10(np.maximum(s / max(s[0], 1e-30), 1e-30))


def _fit_decay(curve_db, t, hi_db, lo_db):
    m = (curve_db <= hi_db) & (curve_db >= lo_db)
    if m.sum() < 4:
        return None
    slope = np.polyfit(t[m], curve_db[m], 1)[0]
    return None if slope >= 0 else float(-60.0 / slope)


def schroeder_t60(mono, sr=SR, hi_db=-5.0, lo_db=-35.0):
    """T60 (s) from the Schroeder backward integral, fitted between hi_db and lo_db."""
    c = schroeder_db(mono)
    t = np.arange(len(c)) / sr
    return _fit_decay(c, t, hi_db, lo_db)


def edt(mono, sr=SR):
    """Early decay time: the 0 to −10 dB slope of the Schroeder curve, scaled to 60 dB."""
    return schroeder_t60(mono, sr, hi_db=0.0, lo_db=-10.0)


def env_decay_t60(env_db, block_s=0.010, start=0, span_db=12.0):
    """T60 from a 10 ms envelope: linear fit over the first `span_db` dB after `start`."""
    e = np.asarray(env_db[start:], dtype=np.float64)
    if len(e) < 4:
        return None
    top = float(e[: max(1, min(5, len(e)))].max())
    t = np.arange(len(e)) * block_s
    m = np.zeros(len(e), dtype=bool)
    for i, v in enumerate(e):
        if v < top - span_db:
            break
        m[i] = True
    if m.sum() < 4:
        return None
    slope = np.polyfit(t[m], e[m], 1)[0]
    return None if slope >= 0 else float(-60.0 / slope)


def drr_db(mono, onset, sr=SR, direct_ms=5.0, late_from_ms=50.0):
    """Direct-to-reverberant ratio: energy in the first `direct_ms` after the onset against the
    energy after `late_from_ms` minus the free decay a dry string would give there (floor 1e-12)."""
    x = np.asarray(mono, dtype=np.float64)
    d = x[onset:onset + int(direct_ms * sr / 1000)]
    r = x[onset + int(late_from_ms * sr / 1000):]
    ed, er = float((d * d).sum()), float((r * r).sum())
    return 10 * math.log10(max(ed, 1e-12) / max(er, 1e-12))


# --------------------------------------------------------------------------- statistics

def pav(y, w=None):
    """Isotonic (non-decreasing) regression by pool-adjacent-violators."""
    y = [float(v) for v in y]
    w = [1.0] * len(y) if w is None else [float(v) for v in w]
    blocks = []                       # [value, weight, count]
    for v, wt in zip(y, w):
        blocks.append([v, wt, 1])
        while len(blocks) > 1 and blocks[-2][0] > blocks[-1][0]:
            v2, w2, c2 = blocks.pop()
            v1, w1, c1 = blocks.pop()
            ws = w1 + w2
            blocks.append([(v1 * w1 + v2 * w2) / ws, ws, c1 + c2])
    out = []
    for v, _w, c in blocks:
        out += [v] * c
    return out


def smooth3(y):
    """3-point moving average with the ends kept (non-decreasing input stays non-decreasing)."""
    y = [float(v) for v in y]
    if len(y) < 3:
        return y
    return [y[0]] + [(y[i - 1] + y[i] + y[i + 1]) / 3.0 for i in range(1, len(y) - 1)] + [y[-1]]


def median3(y):
    y = [float(v) for v in y]
    if len(y) < 3:
        return y
    return [y[0]] + [sorted(y[i - 1:i + 2])[1] for i in range(1, len(y) - 1)] + [y[-1]]


def correlation(a, b):
    a = np.asarray(a, dtype=np.float64).ravel()
    b = np.asarray(b, dtype=np.float64).ravel()
    n = min(len(a), len(b))
    a, b = a[:n] - a[:n].mean(), b[:n] - b[:n].mean()
    den = math.sqrt(float((a * a).sum()) * float((b * b).sum()))
    return 0.0 if den == 0 else float((a * b).sum()) / den


def raised_cosine_fade(pcm, frames):
    """Fade the last `frames` frames to zero with a raised cosine (in place, returns pcm)."""
    n = min(frames, len(pcm))
    if n > 0:
        g = 0.5 * (1 + np.cos(np.pi * np.arange(1, n + 1) / n))
        pcm[-n:] *= g[:, None] if pcm.ndim == 2 else g
    return pcm


def interp_keys(points, keys=range(128)):
    """Linear interpolation of {key: value} to every key, held flat outside the known roots."""
    ks = sorted(points)
    xs = np.array(ks, dtype=np.float64)
    ys = np.array([points[k] for k in ks], dtype=np.float64)
    return [float(v) for v in np.interp(np.array(list(keys), dtype=np.float64), xs, ys)]


def robust_poly2(keys, values, iters=4):
    """Robust 2nd-order polynomial fit in key (Tukey biweight on the residuals)."""
    x = np.asarray(keys, dtype=np.float64)
    y = np.asarray(values, dtype=np.float64)
    deg = 2 if len(x) >= 3 else max(0, len(x) - 1)
    w = np.ones_like(x)
    coef = np.polyfit(x, y, deg, w=w)
    for _ in range(iters):
        r = y - np.polyval(coef, x)
        s = 1.4826 * float(np.median(np.abs(r))) + 1e-6
        u = r / (4.685 * s)
        w = np.where(np.abs(u) < 1, (1 - u * u) ** 2, 0.0) + 1e-6
        coef = np.polyfit(x, y, deg, w=np.sqrt(w))
    return coef


def railsback_check(stretch, piano=True):
    """PLAN §6.5 step 4: pianos run −10…−30 cents at A0 (key 21), +15…+45 at C8 (key 108) and are
    non-decreasing above C4 (key 60); the harpsichord only non-decreasing within ±3 cents.
    Returns a list of failure strings (empty = pass)."""
    fails = []
    if piano:
        if not (-30.0 <= stretch[21] <= -10.0):
            fails.append("A0 stretch %.1f c outside -30..-10" % stretch[21])
        if not (15.0 <= stretch[108] <= 45.0):
            fails.append("C8 stretch %.1f c outside +15..+45" % stretch[108])
        for k in range(61, 109):
            if stretch[k] < stretch[k - 1] - 1e-6:
                fails.append("stretch decreases at key %d" % k)
                break
    else:
        run_max = -math.inf
        for k in range(29, 90):
            run_max = max(run_max, stretch[k])
            if stretch[k] < run_max - 3.0:
                fails.append("harpsichord stretch falls more than 3 c at key %d" % k)
                break
    return fails
