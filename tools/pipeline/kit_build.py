#!/usr/bin/env python3
"""Build an instrument kit: assets/instruments/<id>/{map.json, env.bin, u/<unit>.opus} and
build/kit-<id>-report.txt (PLAN.md §3.2, §3.5, §3.7, §6.5, §6.6).

    python3 tools/pipeline/kit_build.py --kit stub            # same as make_stub_bank.py
    python3 tools/pipeline/kit_build.py --kit grand-hd        # needs the approved download
    python3 tools/pipeline/kit_build.py --kit harpsichord --out /tmp/kit

The real kits (grand-hd, grand-std, upright, harpsichord) read tools/cache/samples/**. The packing,
encoding, round-trip verification and map writing (`assemble_kit`) are shared with the stub bank
and the test fixture, so every kit goes through the same code as the ones the tests exercise.
"""
import argparse
import datetime
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import audio  # noqa: E402
import common  # noqa: E402
import kitmap  # noqa: E402
import sfz  # noqa: E402

SR = common.SR
PRE_ROLL = 96                      # 2 ms before the onset (§6.5 step 2)
GAP = 1920                         # 40 ms of silence before each region in a unit stream (§3.2)
FADE = int(0.300 * SR)             # 300 ms raised-cosine end fade
TARGET_PEAK_DB = -3.0              # normalisation (§6.5 step 9)
MAX_DECODED_PEAK_DB = -0.5         # round-trip ceiling (§6.5 step 11)
RELEASE_UNIT, PEDAL_UNIT = 62, 63

CREDITS = {
    "grand": ("Salamander Grand Piano V3 by Alexander Holm (Yamaha C5), CC-BY 3.0, declared public domain by "
              "the author (2022); retuning tables by Markus Fiedler; SFZ data by kinwie. Trimmed, level-matched, "
              "normalised and Opus-encoded.",
              "https://github.com/sfzinstruments/SalamanderGrandPiano"),
    "upright": ("Versilian Community Sample Library (VCSL) \"Upright Piano, Knight\" and VS Chamber Orchestra 2: "
                "Community Edition upright pp layer, Versilian Studios LLC / Sam Gossner; sampled by Simon Dalzell "
                "(Ivy Audio). CC0 1.0. Trimmed, level-matched, normalised and Opus-encoded.",
                "https://github.com/sgossner/VCSL"),
    "harpsichord": ("Versilian Community Sample Library (VCSL) \"Harpsichord, Flemish\", Versilian Studios LLC / "
                    "Sam Gossner. CC0 1.0. Trimmed, level-matched, normalised and Opus-encoded.",
                    "https://github.com/sgossner/VCSL"),
    "stub": ("Hammerklavier stand-in bank: additive partials synthesised by tools/pipeline/make_stub_bank.py. CC0.",
             "tools/pipeline/make_stub_bank.py"),
}


class KitBuildError(RuntimeError):
    pass


# --------------------------------------------------------------------------- trim model (§3.2)

def sustain_seconds(instrument, key):
    top = {"grand": 14.0, "upright": 12.0, "harpsichord": 8.0}.get(instrument, 14.0)
    k = min(108, max(21, key))
    return top + (3.0 - top) * (k - 21) / 87.0


RELEASE_SECONDS = {"grand": 0.4, "upright": 1.0, "harpsichord": 0.6}
# Release level under the notes (releaseRule.relGainDb). The Salamander rel<n> are damper/key noises
# recorded near note level and played by its SFZ at volume=-37 (hammer.txt); at 0 dB they sounded as a
# per-note "rubbing" as loud as the music (INTEGRATION.md, release-noise level). The VCSL upright and
# harpsichord releases are set per region instead (attackRelDb, from their SFZ volumes: see
# release_attack_levels), so their value is unused.
RELEASE_GAIN_DB = {"grand": -37.0, "upright": 0.0, "harpsichord": 0.0}
# The layer whose SFZ sustains the VCSL releases are measured against (upright vl1 = mf; harpsichord's one).
RELEASE_REF_LAYER = {"upright": 1, "harpsichord": 0}
PEDAL_SECONDS = {"pedalDown": 4.5, "pedalUp": 0.5}


# --------------------------------------------------------------------------- one sample → region

def process_sample(pcm, kind, seconds, start_offset=None, instrument="grand"):
    """Steps 2, 3 and 9 of §6.5 on one decoded source (float32 (n, 2) at 48 kHz).

    Returns (region_pcm normalised to −3 dBFS, info) with info = {onsetFrame, thrFrame, gainDb,
    srcStart, naturalPeakDb, untrimmed (the natural-level source from the onset, for decays)}.
    """
    x = np.asarray(pcm, dtype=np.float64)
    if start_offset is None:
        c = audio.coarse_start(x, db=-50.0, within_frames=int(0.200 * SR))
        start_offset = max(0, (c or 0) - 480)
    look = x[start_offset:start_offset + int(0.400 * SR)]
    on = audio.onset_frame(look)
    if on is None:
        raise KitBuildError("silent sample")
    onset_abs = start_offset + on
    begin = onset_abs - PRE_ROLL
    if begin < 0:
        x = np.concatenate([np.zeros((-begin, 2)), x])
        onset_abs -= begin
        begin = 0
    n = int(round(seconds * SR))
    seg = x[begin:begin + n].copy()
    # early end: where the 50 ms RMS falls below −75 dBFS (after at least 0.5 s for sustains)
    blk = int(0.050 * SR)
    nb = len(seg) // blk
    if nb > 2:
        ms = (seg[: nb * blk] ** 2).reshape(nb, blk, 2).mean(axis=(1, 2))
        db = 10 * np.log10(np.maximum(ms, 1e-20))
        min_block = int(0.5 * SR) // blk if kind == "sustain" else 2
        below = np.nonzero(db[min_block:] < -75.0)[0]
        if len(below):
            end = (below[0] + min_block + 1) * blk
            seg = seg[: max(end, FADE + PRE_ROLL + 480)]
    fade = min(FADE, len(seg) // 2)
    audio.raised_cosine_fade(seg, fade)
    pk = float(np.max(np.abs(seg)))
    if pk <= 0:
        raise KitBuildError("silent region")
    g = audio.db_to_lin(TARGET_PEAK_DB) / pk
    seg *= g
    info = {
        "onsetFrame": PRE_ROLL,
        "thrFrame": audio.thr_frame(seg),
        "gainDb": -20 * math.log10(g),
        "srcStart": begin,
        "naturalPeakDb": 20 * math.log10(pk),
        "untrimmed": x[begin:],
    }
    return seg.astype(np.float32), info


# --------------------------------------------------------------------------- packing, encoding, verify

def pack_unit(regions):
    """[(region dict with 'pcm')] in id order → (stream float32 (n, 2), [streamStart])."""
    parts, starts, pos = [], [], 0
    for r in regions:
        parts.append(np.zeros((GAP, 2), dtype=np.float32))
        pos += GAP
        starts.append(pos)
        parts.append(np.asarray(r["pcm"], dtype=np.float32))
        pos += len(r["pcm"])
    parts.append(np.zeros((GAP, 2), dtype=np.float32))      # tail pad: codec look-ahead lands in silence
    return np.concatenate(parts), starts


def encode_and_verify(unit_regions, path, report, label):
    """Encode one unit; verify every region's length, onset (±1 frame) and decoded peak
    (≤ −0.5 dBFS). A region that overshoots is re-normalised by its overshoot + 0.5 dB (its gainDb
    compensates) and the unit re-encoded; a second failure fails the build. Returns the decoded
    stream length in frames."""
    for attempt in range(2):
        stream, starts = pack_unit(unit_regions)
        audio.encode_opus(stream, path)
        dec = audio.decode(path)
        bad = []
        for r, s in zip(unit_regions, starts):
            r["streamStart"] = s
            n = len(r["pcm"])
            got = dec[s:s + n]
            if len(got) != n:
                raise KitBuildError("%s: region %d truncated in the decoded stream" % (label, r["id"]))
            lag = audio.alignment_lag(r["pcm"], got, max(0, r["onsetFrame"] - 48))
            if abs(lag) > 1:
                # no sharp attack (a release that starts on the sounding tone): a periodic signal
                # correlates as well one period away; 0.5 s of decay and noise settles it
                lag = audio.alignment_lag(r["pcm"], got, max(0, r["onsetFrame"] - 48), length=24000)
            if abs(lag) > 1:
                raise KitBuildError("%s: region %d onset moved by %d frames" % (label, r["id"], lag))
            pk = audio.peak_dbfs(got)
            if pk > MAX_DECODED_PEAK_DB:
                bad.append((r, pk))
        if not bad:
            return len(dec)
        if attempt == 1:
            raise KitBuildError("%s: decoded peak still above %.1f dBFS after re-normalising: %s" % (
                label, MAX_DECODED_PEAK_DB, ", ".join("r%d %.2f" % (r["id"], p) for r, p in bad)))
        for r, pk in bad:
            cut = pk - MAX_DECODED_PEAK_DB + 0.5
            r["pcm"] = (np.asarray(r["pcm"], dtype=np.float64) * audio.db_to_lin(-cut)).astype(np.float32)
            r["gainDb"] += cut
            report.append("  %s region %d: decoded peak %.2f dBFS, re-normalised by -%.2f dB" % (label, r["id"], pk, cut))
    raise AssertionError("unreachable")


def assemble_kit(out_dir, meta, regions, units, report):
    """Write u/<id>.opus, env.bin and map.json for a kit whose regions are processed.

    meta: every top-level map.json field except units, regions and sha1 (instrument, kit,
    version, mode, xfadeSteps, xfadeLaw, lastDamper, aOffsetCents, recordedAHz, pedalGainDb,
    releaseCarriesTail, embeddedRoomDb, embeddedEdtS, layers, stops, levelCurve, stretchCents,
    inharmB, damperT60, freeT60, releaseRule, credit, source).
    regions: list of dicts with id (dense, in order), kind, unit, stop, layer, root, lo, hi, rr,
    onsetFrame, thrFrame, pitchCents (sustain/release), gainDb, pcm, optional borrowable/seam*.
    units: list of {id, label, order}.
    """
    common.ensure_dir(os.path.join(out_dir, "u"))
    by_unit = {}
    for r in regions:
        by_unit.setdefault(r["unit"], []).append(r)
    unit_rows = []
    for u in sorted(units, key=lambda u: u["id"]):
        rs = by_unit.get(u["id"], [])
        if not rs:
            raise KitBuildError("unit %d has no regions" % u["id"])
        path = os.path.join(out_dir, "u", "%d.opus" % u["id"])
        frames = encode_and_verify(rs, path, report, "unit %d" % u["id"])
        unit_rows.append({"id": u["id"], "label": u["label"], "order": u["order"],
                          "file": "u/%d.opus" % u["id"], "frames": frames, "sha1": common.sha1_file(path)})
    # remove stale unit files from an earlier build of this kit
    keep = {"%d.opus" % u["id"] for u in units}
    for f in os.listdir(os.path.join(out_dir, "u")):
        if f.endswith(".opus") and f not in keep and f != "bench.opus":
            os.remove(os.path.join(out_dir, "u", f))
    env = bytearray()
    region_rows = []
    for r in regions:
        eb = audio.env_bytes(r["pcm"])
        row = {"id": r["id"], "kind": r["kind"], "unit": r["unit"], "streamStart": r["streamStart"],
               "frames": len(r["pcm"]), "stop": r["stop"], "layer": r["layer"], "root": r["root"],
               "lo": r["lo"], "hi": r["hi"], "rr": r["rr"], "onsetFrame": r["onsetFrame"],
               "thrFrame": r["thrFrame"]}
        if r["kind"] in ("sustain", "release"):
            row["pitchCents"] = float(r["pitchCents"])
        row["gainDb"] = float(r["gainDb"])
        row["envOffset"] = len(env)
        row["envCount"] = len(eb)
        for f in ("borrowable", "seamGainDb", "seamLpHz", "attackRelDb"):
            if f in r:
                row[f] = r[f]
        env += eb
        region_rows.append(row)
    env = bytes(env)
    env_path = os.path.join(out_dir, "env.bin")
    common.write_bytes(env_path, env)
    import hashlib
    h = hashlib.sha1()
    for u in sorted(unit_rows, key=lambda u: u["id"]):
        with open(os.path.join(out_dir, u["file"]), "rb") as f:
            h.update(f.read())
    h.update(env)
    m = {"schema": 2, "instrument": meta["instrument"], "kit": meta["kit"], "version": meta["version"],
         "sha1": h.hexdigest()}
    for k in ("mode", "xfadeSteps", "xfadeLaw", "lastDamper", "aOffsetCents", "recordedAHz", "pedalGainDb",
              "releaseCarriesTail", "embeddedRoomDb", "embeddedEdtS", "layers", "stops", "levelCurve"):
        m[k] = meta[k]
    m["units"] = unit_rows
    m["regions"] = region_rows
    for k in ("stretchCents", "inharmB", "damperT60", "freeT60", "releaseRule", "credit", "source"):
        m[k] = meta[k]
    errs = kitmap.validate(m, kit_dir=out_dir, env=env)
    if errs:
        raise KitBuildError("map.json invalid: " + "; ".join(errs[:10]))
    common.write_text(os.path.join(out_dir, "map.json"), common.json_dumps(m, digits=7))
    return m


# --------------------------------------------------------------------------- analysis (§6.5 steps 4–8)

TOP_OCTAVE_MAX_B = 0.003


def measure_pitch(pcm_or_mono, root, octave_mult=1.0):
    mono = audio.to_mono(pcm_or_mono)
    n_max = 24 if root < 48 else (12 if root < 72 else 6)
    # the top octave has decayed into the noise by 0.3 s: measure it earlier
    t0, t1 = (0.3, 1.3) if root < 90 else (0.05, 0.55)
    fit = audio.fit_partials(mono[PRE_ROLL:], audio.key_hz(root) * octave_mult, t0=t0, t1=t1, n_max=n_max)
    if root >= 96:
        # the top octave: when the partial fit (which searches ±51 cents) is missing or its first
        # partial disagrees with the fundamental's own peak (±117 cents) by > 10 cents, it locked onto
        # noise; the peak then gives the pitch, and B (2-3 loose partials under noise) is capped
        f = audio.fundamental_peak(mono[PRE_ROLL:], audio.key_hz(root) * octave_mult)
        if f is not None and (fit is None or abs(audio.cents(fit["f0"] * math.sqrt(1 + fit["B"]), f)) > 10.0):
            fit = dict(fit or {"B": 0.0, "partials": [(1, f)], "resid_cents": 0.0})
            fit["B"] = min(fit["B"], TOP_OCTAVE_MAX_B)
            fit["f0"] = f                  # the loose B is not trusted to move the pitch
            fit["f0Source"] = "peak"
        if fit is not None:
            # an independent estimate on the same window for the cross-check (top_octave_check)
            fit["f0Acf"] = audio.acf_f0(mono[PRE_ROLL:], audio.key_hz(root) * octave_mult)
    if fit is None:
        return None
    return fit


# Top-octave roots whose measured pitch is > TOP_RESID_MAX cents off the tuning shape and has been
# confirmed by the independent autocorrelation estimate and by inspection of the spectrum, pending
# the L-3 listening check. Any other top-octave root that far off fails the build.
# 108 (Salamander C8): every layer's strongest partial is at 4433 Hz (+99 c, three unison strings
# at +85..+99 c); nothing near 4186 Hz rises above -36 dB; ACF +92..+95 c. tune_ret gives -38 c here,
# the same as A7 (measured +38 c), so the retuning table does not model it (docs/progress/WP11.md).
TOP_OCTAVE_L3_PENDING = {108}
TOP_RESID_MAX = 50.0
TOP_ACF_TOL = 10.0


# Plan deviation (PLAN §10, WP11): §3.x says a damper T60 outside 0.5-2x the formula fails the
# build. The VCSL release takes are _Far room recordings whose early decay the room lengthens, so
# most roots fall outside; the build keeps the formula for every key instead of failing, and emits a
# machine-checkable DAMPER-FALLBACK flag. The fallback is allowed only for these instruments and
# for at most this many out-of-range roots (the counts measured on the 2026-09 corpus); anything
# more fails the build. The grand never measures (it uses the R3 formula by design).
DAMPER_FALLBACK_MAX_REJECTED = {"upright": 17, "harpsichord": 19}


def damper_fallback(instrument, fitted, rejected):
    """fitted: {root: T60} in range; rejected: [root] out of range → (fitted to use, report flag).
    Raises RuntimeError when the fallback is not allowed for `instrument` or rejects too many roots."""
    n_roots = len(fitted) + len(rejected)
    if n_roots and len(fitted) < n_roots / 2:
        allowed = DAMPER_FALLBACK_MAX_REJECTED.get(instrument)
        flag = "DAMPER-FALLBACK instrument=%s fitted=%d roots=%d rejected=%d" % (
            instrument, len(fitted), n_roots, len(rejected))
        if allowed is None or len(rejected) > allowed:
            raise RuntimeError("%s: damper T60 fallback not allowed (limit %s)" % (flag, allowed))
        return {}, flag + " (formula used for every key; plan deviation, section 10)"
    return fitted, "DAMPER-FIT instrument=%s fitted=%d roots=%d" % (instrument, len(fitted), n_roots)


def top_octave_check(top, resid, pending=TOP_OCTAVE_L3_PENDING):
    """top: {root >= 96: [(pitchCents, acfCents or None)] per layer}; resid: {root: cents off the
    shape}. → (failures, flags). Fails when a root's median measured pitch and median ACF pitch
    disagree by > TOP_ACF_TOL, or when its residual exceeds TOP_RESID_MAX and it is not pending L-3
    (then it is only flagged)."""
    fails, flags = [], []
    for root in sorted(top):
        pcs = [p for p, _a in top[root]]
        acs = [a for _p, a in top[root] if a is not None]
        pm = float(np.median(pcs))
        if not acs:
            fails.append("root %d: no autocorrelation estimate to cross-check %.1f c" % (root, pm))
            continue
        am = float(np.median(acs))
        if abs(pm - am) > TOP_ACF_TOL:
            fails.append("root %d: measured %.1f c but autocorrelation %.1f c" % (root, pm, am))
        r = resid.get(root, 0.0)
        if abs(r) > TOP_RESID_MAX:
            msg = "root %d: %.1f c off the shape (measured %.1f c, ACF %.1f c)" % (root, r, pm, am)
            if root in pending:
                flags.append("PITCH-L3 " + msg)
            else:
                fails.append(msg + ": not in TOP_OCTAVE_L3_PENDING")
    return fails, flags


def tuning_shape(points, piano=True):
    """points: [(root, pitchCents)] → (aOffsetCents, stretch[128], residuals {root: cents}, fails)."""
    ks = [p[0] for p in points]
    vs = [p[1] for p in points]
    coef = audio.robust_poly2(ks, vs)
    lo, hi = min(ks), max(ks)

    def fit(k):
        return float(np.polyval(coef, min(hi, max(lo, k))))
    a_off = fit(69)
    stretch = [fit(k) - a_off for k in range(128)]
    stretch[69] = 0.0
    resid = {}
    for k, v in points:
        resid.setdefault(k, []).append(v - fit(k))
    fails = audio.railsback_check(stretch, piano=piano) if (lo <= 21 and hi >= 108) or not piano else []
    return a_off, stretch, {k: float(np.median(v)) for k, v in resid.items()}, fails


def level_curve(loud, layers, report, spread_min_db=12.0):
    """loud: {root: [natural A-weighted dB per layer]} for one stop → (levelCurve points,
    {(root, layer): correction dB}). Isotonic regression per root, 3-point smooth, 3-tap median
    across roots, corrections clamped ±2.5 dB (§3.5)."""
    roots = sorted(loud)
    nl = len(layers)
    smooth = {r: audio.smooth3(audio.pav(loud[r])) for r in roots}
    med = {}
    for l in range(nl):
        col = audio.median3([smooth[r][l] for r in roots])
        for r, v in zip(roots, col):
            med[(r, l)] = v
    corr = {}
    corrected = {l: [] for l in range(nl)}
    for r in roots:
        for l in range(nl):
            c = max(-2.5, min(2.5, med[(r, l)] - loud[r][l]))
            corr[(r, l)] = c
            corrected[l].append(loud[r][l] + c)
    centres = audio.pav([float(np.median(corrected[l])) for l in range(nl)])
    if nl > 1 and centres[-1] - centres[0] < spread_min_db:
        report.append("  level spread %.1f dB < %.0f dB: target follows 0.39 dB/step" % (centres[-1] - centres[0], spread_min_db))
        top = centres[-1]
        centres = [top - 0.39 * (layers[-1]["velRef"] - L["velRef"]) for L in layers]
    pts = [{"vel": L["velRef"], "db": float(c)} for L, c in zip(layers, centres)]
    for j in range(1, nl):
        dv = layers[j]["velRef"] - layers[j - 1]["velRef"]
        slope = (centres[j] - centres[j - 1]) / max(1, dv)
        flag = "" if 0 <= slope <= 1.0 else "  <-- L-3"
        report.append("  segment %d-%d: %.3f dB/step%s" % (j - 1, j, slope, flag))
    return pts, corr


def xfade_law(pcm_by_root_layer, nl):
    """Median correlation of adjacent onset-aligned layers over 0–300 ms per boundary: > 0.5 → gain."""
    law = []
    for j in range(nl - 1):
        cs = []
        for (root, l), pcm in pcm_by_root_layer.items():
            if l != j or (root, j + 1) not in pcm_by_root_layer:
                continue
            a = audio.to_mono(pcm)[PRE_ROLL:PRE_ROLL + int(0.3 * SR)]
            b = audio.to_mono(pcm_by_root_layer[(root, j + 1)])[PRE_ROLL:PRE_ROLL + int(0.3 * SR)]
            cs.append(audio.correlation(a, b))
        law.append("gain" if cs and float(np.median(cs)) > 0.5 else "power")
    return law


def release_decay_t60(pcm):
    """Early decay of a release sample: the first 12 dB after the damper-landing transient."""
    blk = 120                                   # 2.5 ms: short damped decays span only a few 10 ms blocks
    env = audio.rms_env_db(pcm, block=blk)
    if len(env) < 12:
        return None, 0
    knee = int(np.argmax(env[: min(len(env), 40)]))
    return audio.env_decay_t60(env, block_s=blk / SR, start=knee, span_db=12.0), int(round(knee * blk * 1000 / SR))


def _rings_on(root, t60):
    """An upright release whose string is not damped (§6.5 step 6): decay T60 > 1 s, in the treble
    (root ≥ 60) and above twice the damped formula (the bass formula itself exceeds 1 s)."""
    return t60 is not None and root >= 60 and t60 > max(1.0, 2.0 * kitmap.default_damper_t60("upright", root))


def free_decay_t60(untrimmed):
    env = audio.rms_env_db(untrimmed)
    if len(env) < 80:
        return None
    return audio.env_decay_t60(env, start=50, span_db=20.0)


def per_key(values_by_root, default_fn):
    pts = {k: v for k, v in values_by_root.items() if v is not None and math.isfinite(v)}
    if not pts:
        return [float(default_fn(k)) for k in range(128)]
    return audio.interp_keys(pts)


def version_string(n=1):
    d = datetime.date.today()
    return "%04d.%02d.%d" % (d.year, d.month, n)


# --------------------------------------------------------------------------- sources

class Source:
    """One recorded sample: file path, kind, stop, layer, root, lo, hi, rr, start offset."""

    def __init__(self, path, kind, stop, layer, root, lo, hi, rr=0, start=None, tune_nat=0.0, tune_ret=0.0,
                 octave=1.0, borrowable=False, sfz_volume=None):
        self.path, self.kind, self.stop, self.layer = path, kind, stop, layer
        self.sfz_volume = sfz_volume
        self.root, self.lo, self.hi, self.rr, self.start = root, lo, hi, rr, start
        self.tune_nat, self.tune_ret, self.octave, self.borrowable = tune_nat, tune_ret, octave, borrowable


def _find(name, groups):
    for g in groups:
        p = os.path.join(common.SAMPLES, g, name)
        if os.path.isfile(p):
            return p
    raise KitBuildError("sample %s not in tools/cache/samples/{%s}" % (name, ",".join(groups)))


GRAND_HD_SPLITS = [(1, 26), (27, 34), (35, 36), (37, 43), (44, 46), (47, 50), (51, 56), (57, 64), (65, 72),
                   (73, 80), (81, 88), (89, 96), (97, 104), (105, 112), (113, 120), (121, 127)]
GRAND_HD_ORDER = [10, 62, 63, 13, 7, 16, 4, 1, 8, 11, 5, 14, 2, 9, 12, 6, 15, 3]
GRAND_STD = [(1, 1, 30), (4, 31, 46), (7, 47, 64), (10, 65, 88), (13, 89, 112), (16, 113, 127)]
GRAND_STD_ORDER = [10, 62, 63, 13, 7, 16, 4, 1]


def grand_sources(kit):
    docs = os.path.join(common.SAMPLES, "grand-docs")
    table = sfz.salamander_region_table(docs)
    nat, ret = sfz.salamander_tune(docs, "nat"), sfz.salamander_tune(docs, "ret")
    if kit == "grand-hd":
        vel_nos = list(range(1, 17))
        splits = GRAND_HD_SPLITS
        order = GRAND_HD_ORDER
    else:
        vel_nos = [v for v, _a, _b in GRAND_STD]
        splits = [(a, b) for _v, a, b in GRAND_STD]
        order = GRAND_STD_ORDER
    velrefs_hd = [14, 31, 36, 40, 45, 49, 54, 61, 69, 77, 85, 93, 101, 109, 117, 124]
    layers, units, srcs = [], [], []
    for li, (vno, (lo_v, hi_v)) in enumerate(zip(vel_nos, splits)):
        layers.append({"index": li, "velLo": lo_v, "velHi": hi_v, "velRef": velrefs_hd[vno - 1], "unit": li})
        units.append({"id": li, "label": "v%d" % vno, "order": order.index(vno)})
        offs = sfz.salamander_offsets(docs, vno)
        for t in table:
            name = "%sv%d.flac" % (t["note"], vno)
            srcs.append(Source(_find(name, ["grand-sustain", "grand-sustain-hd"]), "sustain", 0, li, t["root"],
                               t["lokey"], t["hikey"], start=offs.get(t["label"]),
                               tune_nat=nat.get(t["label"], 0.0), tune_ret=ret.get(t["label"], 0.0)))
    units.append({"id": RELEASE_UNIT, "label": "releases", "order": order.index(62)})
    units.append({"id": PEDAL_UNIT, "label": "pedals", "order": order.index(63)})
    for n in range(1, 89):
        k = 20 + n
        srcs.append(Source(_find("rel%d.flac" % n, ["grand-release"]), "release", 0, -1, k, k, k))
    for kind, stem in (("pedalDown", "pedalD"), ("pedalUp", "pedalU")):
        for rr in range(2):
            srcs.append(Source(_find("%s%d.flac" % (stem, rr + 1), ["grand-pedal"]), kind, -1, -1, -1, -1, -1, rr=rr))
    # fill region key ranges so the three sustain roots per octave cover 0..127
    _widen(srcs)
    return layers, [{"index": 0, "name": "main"}], units, srcs


def upright_sources():
    sfzp = os.path.join(common.SAMPLES, "vcsl-sfz-maps", "Upright Piano, Knight.sfz")
    regs = sfz.vcsl_regions(sfzp)
    layers = [{"index": 0, "velLo": 1, "velHi": 40, "velRef": 20, "unit": 2},
              {"index": 1, "velLo": 41, "velHi": 83, "velRef": 62, "unit": 0},
              {"index": 2, "velLo": 84, "velHi": 127, "velRef": 105, "unit": 1}]
    units = [{"id": 0, "label": "mf (vl1)", "order": 0}, {"id": RELEASE_UNIT, "label": "releases", "order": 1},
             {"id": PEDAL_UNIT, "label": "pedals", "order": 2}, {"id": 1, "label": "f (vl2)", "order": 3},
             {"id": 2, "label": "pp (dyn1)", "order": 4}]
    srcs = []
    for r in regs:
        if r["sample"].startswith("Player_Ped"):
            continue
        if r["trigger"] == "attack":
            li = 1 if "_vl1_" in r["sample"] else 2
            srcs.append(Source(_find(r["sample"], ["upright-sustain"]), "sustain", 0, li, r["root"], r["lokey"], r["hikey"],
                               sfz_volume=r["volume"]))
        else:
            srcs.append(Source(_find(r["sample"], ["upright-release"]), "release", 0, -1, r["root"], r["lokey"], r["hikey"],
                               sfz_volume=r["volume"]))
    mp = sfz.vsco_mapping(os.path.join(common.SAMPLES, "upright-pp-docs", "MappingChart.txt"))
    pp_dir = os.path.join(common.SAMPLES, "upright-sustain-pp")
    for f in sorted(os.listdir(pp_dir)):
        if f.endswith(".wav"):
            idx = int(f.rsplit("_", 1)[1].split(".")[0])
            k = mp[idx]
            srcs.append(Source(os.path.join(pp_dir, f), "sustain", 0, 0, k, k, k))
    ped = os.path.join(common.SAMPLES, "upright-pedal")
    for kind, tag in (("pedalDown", "PedOn"), ("pedalUp", "PedOff")):
        fs = sorted(f for f in os.listdir(ped) if tag in f)
        for rr, f in enumerate(fs):
            srcs.append(Source(os.path.join(ped, f), kind, -1, -1, -1, -1, -1, rr=rr))
    _widen(srcs)
    return layers, [{"index": 0, "name": "main"}], units, srcs


def harpsichord_sources():
    base = os.path.join(common.SAMPLES, "vcsl-sfz-maps")
    layers = [{"index": 0, "velLo": 1, "velHi": 127, "velRef": 64, "unit": 0}]
    stops = [{"index": 0, "name": "8'"}, {"index": 1, "name": "4'"}]
    units = [{"id": 0, "label": "8'", "order": 0}, {"id": RELEASE_UNIT, "label": "releases", "order": 1},
             {"id": 1, "label": "4'", "order": 2}]
    srcs = []
    for stop, fname, grp, rgrp in ((0, "Harpsichord, Flemish - 8'.sfz", "harpsichord-8ft", "harpsichord-8ft-release"),
                                   (1, "Harpsichord, Flemish - 4'.sfz", "harpsichord-4ft", "harpsichord-4ft-release")):
        for r in sfz.vcsl_regions(os.path.join(base, fname)):
            octave = 2.0 if stop == 1 else 1.0
            if r["trigger"] == "attack":
                srcs.append(Source(_find(r["sample"], [grp]), "sustain", stop, 0, r["root"], r["lokey"], r["hikey"],
                                   octave=octave, borrowable=(stop == 1 and r["root"] + 12 >= 85), sfz_volume=r["volume"]))
            else:
                srcs.append(Source(_find(r["sample"], [rgrp]), "release", stop, -1, r["root"], r["lokey"], r["hikey"],
                                   octave=octave, sfz_volume=r["volume"]))
    return layers, stops, units, srcs


def _widen(srcs):
    """Extend the outermost sustain/release regions of each (kind, stop, layer) to keys 0 and 127
    (folded notes never occur, but the KeyMap needs total coverage)."""
    groups = {}
    for s in srcs:
        if s.kind in ("sustain", "release"):
            groups.setdefault((s.kind, s.stop, s.layer), []).append(s)
    for g in groups.values():
        g.sort(key=lambda s: s.root)
        g[0].lo = 0
        g[-1].hi = 127


# --------------------------------------------------------------------------- real kit build

def build_real_kit(kit, out_dir, report_path):
    instrument = {"grand-hd": "grand", "grand-std": "grand"}.get(kit, kit)
    if kit.startswith("grand"):
        layers, stops, units, srcs = grand_sources(kit)
    elif kit == "upright":
        layers, stops, units, srcs = upright_sources()
    elif kit == "harpsichord":
        layers, stops, units, srcs = harpsichord_sources()
    else:
        raise KitBuildError("unknown kit %s" % kit)
    report = ["kit %s (%s), %d sources" % (kit, instrument, len(srcs))]
    unit_of = {}
    for L in layers:
        unit_of[(0, L["index"])] = L["unit"]
    if instrument == "harpsichord":
        unit_of[(1, 0)] = 1
    order = {"sustain": 0, "release": 1, "pedalDown": 2, "pedalUp": 3}
    srcs.sort(key=lambda s: (order[s.kind], s.stop, s.layer, s.root, s.rr))
    regions = []
    loud = {}
    pitch_pts = {"raw": [], "nat": [], "ret": []}
    inh, free, damp, rel_t60 = {}, {}, {}, {}
    top_pts = {}
    pcm_rl = {}
    knees, drrs, edts = [], [], []
    onset_jitter = []
    for s in srcs:
        pcm = audio.decode(s.path)
        if s.kind == "sustain":
            secs = sustain_seconds(instrument, s.root)
        elif s.kind == "release":
            secs = RELEASE_SECONDS[instrument]
        else:
            secs = PEDAL_SECONDS[s.kind]
        seg, info = process_sample(pcm, s.kind, secs, start_offset=s.start, instrument=instrument)
        r = {"id": len(regions), "kind": s.kind, "stop": s.stop, "layer": s.layer, "root": s.root, "lo": s.lo,
             "hi": s.hi, "rr": s.rr, "onsetFrame": info["onsetFrame"], "thrFrame": info["thrFrame"],
             "gainDb": info["gainDb"], "pcm": seg}
        if s.sfz_volume is not None:
            r["_sfzDb"] = sfz_natural_db(seg, s.kind, info["gainDb"]) + s.sfz_volume
        if s.kind == "sustain":
            r["unit"] = unit_of[(s.stop, s.layer)]
        elif s.kind == "release":
            r["unit"] = RELEASE_UNIT
        else:
            r["unit"] = PEDAL_UNIT
        if s.kind in ("sustain", "release"):
            fit = measure_pitch(seg, s.root, s.octave) if s.kind == "sustain" else None
            if fit is not None:
                pc = audio.pitch_cents(fit["f0"], s.root)
                r["pitchCents"] = pc
                if s.stop == 0:
                    if s.root >= 96 and s.octave == 1.0:
                        acf = fit.get("f0Acf")
                        top_pts.setdefault(s.root, []).append(
                            (pc, audio.pitch_cents(acf, s.root) if acf else None))
                    inh.setdefault(s.root, []).append(fit["B"])
                    pitch_pts["raw"].append((s.root, pc))
                    pitch_pts["nat"].append((s.root, pc + s.tune_nat))
                    pitch_pts["ret"].append((s.root, pc + s.tune_ret))
            else:
                r["pitchCents"] = 1200.0 if s.octave == 2.0 else 0.0
                report.append("  no partial fit for %s: pitchCents from the nominal root" % os.path.basename(s.path))
        if s.kind == "sustain":
            loud.setdefault(s.stop, {}).setdefault(s.root, {})[s.layer] = (
                audio.aweighted_rms_db(seg, PRE_ROLL) + r["gainDb"])
            pcm_rl[(s.root, s.layer)] = seg if s.stop == 0 else pcm_rl.get((s.root, s.layer), seg)
            t = free_decay_t60(info["untrimmed"])
            free.setdefault(s.stop, {}).setdefault(s.root, []).append(t)
        if s.kind == "release":
            t, knee = release_decay_t60(seg)
            rel_t60.setdefault(s.root, []).append(t)
            knees.append((s.root, knee))
            m = audio.to_mono(seg)
            drrs.append(audio.drr_db(m, PRE_ROLL, direct_ms=30, late_from_ms=150))
            e = audio.edt(m[PRE_ROLL:])
            if e:
                edts.append(e)
        if s.borrowable:
            r["borrowable"] = True
        onset_jitter.append(0)
        regions.append(r)
        del pcm
    # tuning
    piano = instrument != "harpsichord"
    fits = {k: tuning_shape(v, piano=piano) for k, v in pitch_pts.items() if len(v) >= 3}
    use = "raw"
    if fits["raw"][3] and "nat" in fits and not fits["nat"][3] and kit.startswith("grand"):
        use = "nat"
    a_off, stretch, resid, fails = fits[use]
    report.append("tuning: using the %s fit; aOffsetCents %.2f, recordedAHz %.2f" % (use, a_off, 440 * 2 ** (a_off / 1200)))
    for name, (ao, st, _res, fl) in fits.items():
        if kit.startswith("grand") or name == "raw":
            report.append("  fit %s: A0 %.1f c, C4 %.1f c, C8 %.1f c, Railsback %s" % (
                name, st[21], st[60], st[108], "pass" if not fl else "; ".join(fl)))
    for k, v in sorted(resid.items()):
        if abs(v) > 5:
            report.append("  root %d is %.1f cents off the shape" % (k, v))
    if piano:
        t_fails, t_flags = top_octave_check(top_pts, resid)
        report.extend("  " + f for f in t_flags)
        if t_fails:
            report.append("  TOP-OCTAVE PITCH FAILURE: " + "; ".join(t_fails))
            common.write_text(report_path, "\n".join(report) + "\n")
            raise RuntimeError("kit %s: top-octave pitch check failed: %s" % (kit, "; ".join(t_fails)))
    if fails and use == "raw" and piano:
        report.append("  WARNING: stretch shape fails the Railsback check: %s" % "; ".join(fails))
    inharm = per_key({k: float(np.median(v)) for k, v in inh.items()}, lambda k: 0.0)
    # levels
    level = []
    for s_i in range(len(stops)):
        lr = loud.get(s_i, {})
        full = {root: [v[l] for l in range(len(layers))] for root, v in lr.items() if len(v) == len(layers)}
        report.append("levels, stop %d:" % s_i)
        pts, corr = level_curve(full, layers, report)
        level.append(pts)
        for r in regions:
            if r["kind"] == "sustain" and r["stop"] == s_i and (r["root"], r["layer"]) in corr:
                c = corr[(r["root"], r["layer"])]
                r["gainDb"] += c
                if abs(c) > 0.05:
                    report.append("  correction root %d layer %d: %+.2f dB" % (r["root"], r["layer"], c))
    mode = "HARD" if kit == "grand-hd" or len(layers) == 1 else "XFADE"
    law = xfade_law(pcm_rl, len(layers)) if mode == "XFADE" else []
    xsteps = {"grand-std": 4, "upright": 6}.get(kit, 0)
    # dampers and decays
    if instrument == "grand":
        damper = [kitmap.default_damper_t60("grand", k) for k in range(128)]
        last_damper = 88
        release_carries = False
    else:
        fitted, rejected = {}, []
        for root, ts in sorted(rel_t60.items()):
            ts = [t for t in ts if t]
            if not ts:
                continue
            d = kitmap.default_damper_t60(instrument, root)
            if instrument == "upright" and _rings_on(root, float(np.median(ts))):
                continue                     # undamped string: evidence for lastDamper, not a damper fit
            # the fastest take: the recording room only ever lengthens a measured early decay
            t = float(min(ts))
            if not (0.5 * d <= t <= 2.0 * d):
                rejected.append(root)
                report.append("  damper T60 root %d: measured %.2f s (takes %s) outside 0.5-2x the formula "
                              "(%.2f s), knee %s ms: formula used" % (
                                  root, t, ", ".join("%.2f" % x for x in ts), d, dict(knees).get(root)))
                continue
            fitted[root] = t
        fitted, flag = damper_fallback(instrument, fitted, rejected)
        report.append("  " + flag)
        damper = per_key(fitted, lambda k: kitmap.default_damper_t60(instrument, k))
        release_carries = True
        if instrument == "upright":
            undamped = sorted(root for root, ts in rel_t60.items() if ts and _rings_on(root, max(t or 0 for t in ts)))
            last_damper = (undamped[0] - 1) if undamped else 92
            if not 86 <= last_damper <= 92:
                report.append("  lastDamper evidence %d inconclusive: clamped to 86..92" % last_damper)
                last_damper = min(92, max(86, last_damper))
            report.append("lastDamper %d (undamped release roots %s; SFZ 10 s releases would give 100)" % (last_damper, undamped))
        else:
            last_damper = 127
            report.append("harpsichord release knees (ms after the release onset): %s" % knees)
    free_arr = []
    for s_i, S in enumerate(stops):
        byroot = {root: float(np.median([t for t in ts if t])) for root, ts in free.get(s_i, {}).items() if any(ts)}
        free_arr.append(per_key(byroot, lambda k, n=S["name"]: kitmap.default_free_t60(instrument, n, k)))
        report.append("free T60 stop %s at C4: %.2f s (default %.2f s)" % (
            S["name"], free_arr[-1][60], kitmap.default_free_t60(instrument, S["name"], 60)))
    embedded_db = float(np.median(drrs)) if drrs else 30.0
    embedded_edt = float(np.median(edts)) if edts else 0.0
    report.append("embedded room: DRR %.1f dB, EDT %.2f s" % (embedded_db, embedded_edt))
    # seam trims (harpsichord 4' borrowable regions against the 8' region of key 84)
    if instrument == "harpsichord":
        ref = min((r for r in regions if r["kind"] == "sustain" and r["stop"] == 0),
                  key=lambda r: abs(r["root"] - 84))
        ref_l = audio.aweighted_rms_db(ref["pcm"], PRE_ROLL) + ref["gainDb"]
        ref_c = _centroid(ref["pcm"])
        for r in regions:
            if r.get("borrowable"):
                l4 = audio.aweighted_rms_db(r["pcm"], PRE_ROLL) + r["gainDb"]
                r["seamGainDb"] = float(ref_l - l4)
                r["seamLpHz"] = float(_seam_lp(r["pcm"], ref_c))
                report.append("seam root %d: gain %+.2f dB, lp %.0f Hz" % (r["root"], r["seamGainDb"], r["seamLpHz"]))
    if instrument in RELEASE_REF_LAYER:
        release_attack_levels(regions, RELEASE_REF_LAYER[instrument], report)
        release_carries = False
    for r in regions:
        r.pop("_sfzDb", None)
    credit, source = CREDITS[instrument]
    meta = {"instrument": instrument, "kit": kit, "version": version_string(), "mode": mode, "xfadeSteps": xsteps,
            "xfadeLaw": law, "lastDamper": last_damper, "aOffsetCents": a_off,
            "recordedAHz": 440.0 * 2 ** (a_off / 1200.0), "pedalGainDb": -20.0 if instrument == "grand" else -12.0,
            "releaseCarriesTail": release_carries, "embeddedRoomDb": embedded_db, "embeddedEdtS": embedded_edt,
            "layers": layers, "stops": stops, "levelCurve": level, "stretchCents": stretch, "inharmB": inharm,
            "damperT60": damper, "freeT60": free_arr,
            "releaseRule": {"relGainDb": RELEASE_GAIN_DB[instrument], "velExp": 0.7, "ageTauS": 3.0, "floor": 0.25, "heldDb": -9.0},
            "credit": credit, "source": source}
    m = assemble_kit(out_dir, meta, regions, units, report)
    report.append("onset jitter: max %d frames (onsets placed at frame %d by construction)" % (max(onset_jitter or [0]), PRE_ROLL))
    report.append("sha1 %s, %d regions, %d units" % (m["sha1"], len(m["regions"]), len(m["units"])))
    common.write_text(report_path, "\n".join(report) + "\n")
    return m


ATTACK_BLOCKS = 5                  # a sustain's attack level: its loudest 10 ms block in the first 50 ms


def sfz_natural_db(seg, kind, gain_db):
    """The natural level (dBFS of the source) a VCSL SFZ region is compared at: a sustain's loudest
    10 ms block in its first 50 ms (its attack), a release's loudest 10 ms block."""
    blocks = [-b / 2.0 for b in audio.env_bytes(seg)]
    lvl = max(blocks[:ATTACK_BLOCKS]) if kind == "sustain" else max(blocks)
    return lvl + gain_db


def release_attack_levels(regions, ref_layer, report):
    """VCSL upright / harpsichord: each release region's attackRelDb = its loudest 10 ms block re the
    attack of the same root's sustain (layer ref_layer, same stop), both at their SFZ volumes: the SFZ
    plays these release takes at a fixed volume well under the notes (INTEGRATION.md, VCSL release
    level). The engine plays the release so that its loudest block sits attackRelDb under the attack
    (the loudest of the first 5 env blocks, at the voice's gain) of the note that is released."""
    sus = {(r["stop"], r["root"]): r for r in regions
           if r["kind"] == "sustain" and r["layer"] == ref_layer and "_sfzDb" in r}
    vals = []
    for r in regions:
        if r["kind"] != "release" or "_sfzDb" not in r:
            continue
        cand = [v for (st, root), v in sus.items() if st == r["stop"]]
        if not cand:
            raise KitBuildError("release region %d: no sustain on stop %d to level against" % (r["id"], r["stop"]))
        s = sus.get((r["stop"], r["root"])) or min(cand, key=lambda v: abs(v["root"] - r["root"]))
        r["attackRelDb"] = float(r["_sfzDb"] - s["_sfzDb"])
        vals.append((r["stop"], r["root"], r["attackRelDb"]))
    if vals:
        a = np.array([v[2] for v in vals])
        report.append("release attackRelDb (SFZ volumes): median %.1f dB, range %.1f..%.1f over %d regions" % (
            float(np.median(a)), float(a.min()), float(a.max()), len(a)))
    return vals


def _centroid(pcm):
    x = audio.to_mono(pcm)[PRE_ROLL:PRE_ROLL + int(0.3 * SR)]
    X = np.abs(np.fft.rfft(x))
    f = np.fft.rfftfreq(len(x), 1.0 / SR)
    return float((X * f).sum() / max(X.sum(), 1e-20))


def _seam_lp(pcm, target_centroid):
    x = audio.to_mono(pcm)[PRE_ROLL:PRE_ROLL + int(0.3 * SR)]
    X = np.abs(np.fft.rfft(x))
    f = np.fft.rfftfreq(len(x), 1.0 / SR)
    if (X * f).sum() / max(X.sum(), 1e-20) <= target_centroid:
        return 20000.0
    lo, hi = 500.0, 20000.0
    for _ in range(40):
        fc = math.sqrt(lo * hi)
        h = 1.0 / np.sqrt(1.0 + (f / fc) ** 2)
        c = float((X * h * f).sum() / max((X * h).sum(), 1e-20))
        if c > target_centroid:
            hi = fc
        else:
            lo = fc
    return math.sqrt(lo * hi)


def patch_release_levels(kit, kit_dir):
    """Apply release_attack_levels to an already built VCSL kit's map.json in place (units and env.bin
    untouched, so the kit sha1 and the device PCM cache stay valid): the same process_sample levels a
    full build computes, from the same sources."""
    import json
    _layers, _stops, _units, srcs = upright_sources() if kit == "upright" else harpsichord_sources()
    ref = RELEASE_REF_LAYER[kit]
    regions = []
    for s in srcs:
        if s.sfz_volume is None or (s.kind == "sustain" and s.layer != ref):
            continue
        secs = sustain_seconds(kit, s.root) if s.kind == "sustain" else RELEASE_SECONDS[kit]
        seg, info = process_sample(audio.decode(s.path), s.kind, secs, start_offset=s.start, instrument=kit)
        regions.append({"id": len(regions), "kind": s.kind, "stop": s.stop, "layer": s.layer, "root": s.root,
                        "_sfzDb": sfz_natural_db(seg, s.kind, info["gainDb"]) + s.sfz_volume})
    report = []
    release_attack_levels(regions, ref, report)
    by = {(r["stop"], r["root"]): r["attackRelDb"] for r in regions if r["kind"] == "release"}
    path = os.path.join(kit_dir, "map.json")
    with open(path) as f:
        m = json.load(f)
    n = 0
    for R in m["regions"]:
        if R["kind"] == "release":
            R["attackRelDb"] = by[(R["stop"], R["root"])]
            n += 1
    m["releaseCarriesTail"] = False
    with open(os.path.join(kit_dir, "env.bin"), "rb") as f:
        env = f.read()
    errs = kitmap.validate(m, kit_dir=kit_dir, env=env)
    if errs:
        raise KitBuildError("map.json invalid: " + "; ".join(errs[:10]))
    common.write_text(path, common.json_dumps(m, digits=7))
    print("%s: attackRelDb on %d release regions; %s" % (kit, n, report[0] if report else ""))
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--kit", required=True, choices=["grand-hd", "grand-std", "upright", "harpsichord", "stub"])
    ap.add_argument("--out", help="output kit directory (default assets/instruments/<id>)")
    ap.add_argument("--patch-release-levels", action="store_true",
                    help="upright/harpsichord: write attackRelDb into the existing map.json only")
    a = ap.parse_args(argv)
    if a.patch_release_levels:
        if a.kit not in RELEASE_REF_LAYER:
            ap.error("--patch-release-levels is for upright and harpsichord")
        return patch_release_levels(a.kit, a.out or os.path.join(common.INSTRUMENTS, a.kit))
    if a.kit == "stub":
        import make_stub_bank
        return make_stub_bank.main(["--out", a.out] if a.out else [])
    kit_id = "grand" if a.kit.startswith("grand") else a.kit
    out = a.out or os.path.join(common.INSTRUMENTS, kit_id)
    rep = os.path.join(common.BUILD, "kit-%s-report.txt" % a.kit)
    m = build_real_kit(a.kit, out, rep)
    print("%s: %d regions, sha1 %s; report %s" % (a.kit, len(m["regions"]), m["sha1"], rep))
    return 0


if __name__ == "__main__":
    sys.exit(main())
