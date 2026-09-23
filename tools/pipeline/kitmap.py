"""The `map.json` (schema 2) validator and the §3.7 default formulas (PLAN.md §6.6, §3.7).

Hand-written from the normative table in docs/contracts/map-json.md (T11.3). It accepts no field
and no value the table does not define. `validate()` returns a list of human-readable errors;
an empty list means the map is valid.

    python3 tools/pipeline/kitmap.py app/src/main/assets/instruments/stub/map.json
"""
import math
import os
import re
import sys

INSTRUMENTS = ("grand", "upright", "harpsichord", "stub")
KITS = ("grand-hd", "grand-std", "upright", "harpsichord", "stub")
KINDS = ("sustain", "release", "pedalDown", "pedalUp")
STOP_NAMES = ("main", "8'", "4'")
RELEASE_UNIT, PEDAL_UNIT = 62, 63

TOP_FIELDS = {
    "schema": "int", "instrument": "str", "kit": "str", "version": "str", "sha1": "str",
    "mode": "str", "xfadeSteps": "int", "xfadeLaw": "list", "lastDamper": "int",
    "aOffsetCents": "num", "recordedAHz": "num", "pedalGainDb": "num", "releaseCarriesTail": "bool",
    "embeddedRoomDb": "num", "embeddedEdtS": "num", "layers": "list", "stops": "list",
    "levelCurve": "list", "units": "list", "regions": "list", "stretchCents": "list",
    "inharmB": "list", "damperT60": "list", "freeT60": "list", "releaseRule": "dict",
    "credit": "str", "source": "str",
}
LAYER_FIELDS = ("index", "velLo", "velHi", "velRef", "unit")
STOP_FIELDS = ("index", "name")
UNIT_FIELDS = ("id", "label", "order", "file", "frames", "sha1")
REGION_REQUIRED = ("id", "kind", "unit", "streamStart", "frames", "stop", "layer", "root", "lo", "hi",
                   "rr", "onsetFrame", "thrFrame", "gainDb", "envOffset", "envCount")
REGION_OPTIONAL = ("pitchCents", "borrowable", "seamGainDb", "seamLpHz")
RELEASE_RULE_FIELDS = ("relGainDb", "velExp", "ageTauS", "floor", "heldDb")
def _sha1_file(path):
    import hashlib
    with open(path, "rb") as f:
        return hashlib.sha1(f.read()).hexdigest()


HEX40 = re.compile(r"^[0-9a-f]{40}$")
VERSION = re.compile(r"^\d{4}\.(0[1-9]|1[0-2])\.\d+$")


def _is(v, t):
    if t == "int":
        return isinstance(v, int) and not isinstance(v, bool)
    if t == "num":
        return isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v)
    if t == "bool":
        return isinstance(v, bool)
    if t == "str":
        return isinstance(v, str)
    if t == "list":
        return isinstance(v, list)
    if t == "dict":
        return isinstance(v, dict)
    raise ValueError(t)


def _nums(arr, n):
    return isinstance(arr, list) and len(arr) == n and all(_is(v, "num") for v in arr)


def validate(m, kit_dir=None, env=None):
    """Validate a parsed map.json. kit_dir: check unit files (presence, sha1, nothing more).
    env: the env.bin bytes (else read from kit_dir/env.bin when kit_dir is given)."""
    errs = []
    if not isinstance(m, dict):
        return ["map is not an object"]
    for k in m:
        if k not in TOP_FIELDS:
            errs.append("unknown field %r" % k)
    for k, t in TOP_FIELDS.items():
        if k not in m:
            errs.append("missing field %r" % k)
        elif not _is(m[k], t):
            errs.append("field %r is not %s" % (k, t))
    if errs:
        return errs

    if m["schema"] != 2:
        errs.append("schema must be 2")
    if m["instrument"] not in INSTRUMENTS:
        errs.append("instrument %r not in %s" % (m["instrument"], INSTRUMENTS))
    if m["kit"] not in KITS:
        errs.append("kit %r not in %s" % (m["kit"], KITS))
    if not VERSION.match(m["version"]):
        errs.append("version %r is not YYYY.MM.n" % m["version"])
    if not HEX40.match(m["sha1"]):
        errs.append("sha1 is not 40 lower-case hex digits")
    if not 0 <= m["lastDamper"] <= 127:
        errs.append("lastDamper outside 0..127")
    if m["recordedAHz"] <= 0:
        errs.append("recordedAHz must be > 0")
    if m["embeddedEdtS"] < 0:
        errs.append("embeddedEdtS must be >= 0")

    # layers
    layers = m["layers"]
    nl = len(layers)
    if nl == 0:
        errs.append("no layers")
    for i, L in enumerate(layers):
        if not isinstance(L, dict) or set(L) != set(LAYER_FIELDS) or not all(_is(L[f], "int") for f in LAYER_FIELDS):
            errs.append("layer %d must be {%s} of ints" % (i, ", ".join(LAYER_FIELDS)))
            return errs
        if L["index"] != i:
            errs.append("layer indices not dense (layer %d has index %d)" % (i, L["index"]))
        if not (1 <= L["velLo"] <= L["velHi"] <= 127):
            errs.append("layer %d split %d..%d invalid" % (i, L["velLo"], L["velHi"]))
        if not (L["velLo"] <= L["velRef"] <= L["velHi"]):
            errs.append("layer %d velRef %d outside its split" % (i, L["velRef"]))
    if layers and not errs:
        order = sorted(layers, key=lambda L: L["velLo"])
        expect = 1
        for L in order:
            if L["velLo"] != expect:
                errs.append("velocity splits have a gap or overlap at %d" % expect)
                break
            expect = L["velHi"] + 1
        else:
            if expect != 128:
                errs.append("velocity splits do not reach 127")
        if [L["index"] for L in order] != list(range(nl)):
            errs.append("layers must be ordered by velocity (index 0 softest)")

    # mode
    mode = m["mode"]
    if mode not in ("HARD", "XFADE"):
        errs.append("mode %r not HARD/XFADE" % mode)
    law = m["xfadeLaw"]
    if not all(v in ("gain", "power") for v in law):
        errs.append("xfadeLaw entries must be gain/power")
    if mode == "HARD" and law:
        errs.append("xfadeLaw must be empty for HARD")
    if mode == "XFADE":
        if len(law) != max(0, nl - 1):
            errs.append("xfadeLaw needs one entry per layer boundary (%d)" % max(0, nl - 1))
        if m["xfadeSteps"] < 1:
            errs.append("xfadeSteps must be >= 1 for XFADE")
    if m["xfadeSteps"] < 0:
        errs.append("xfadeSteps must be >= 0")

    # stops
    stops = m["stops"]
    ns = len(stops)
    if ns == 0:
        errs.append("no stops")
    for i, S in enumerate(stops):
        if not isinstance(S, dict) or set(S) != set(STOP_FIELDS) or not _is(S["index"], "int") or not _is(S["name"], "str"):
            errs.append("stop %d must be {index, name}" % i)
            return errs
        if S["index"] != i:
            errs.append("stop indices not dense")
        if S["name"] not in STOP_NAMES:
            errs.append("stop name %r not in %s" % (S["name"], STOP_NAMES))

    # level curve
    lc = m["levelCurve"]
    if len(lc) != ns:
        errs.append("levelCurve needs one list per stop")
    else:
        for s, pts in enumerate(lc):
            if not isinstance(pts, list) or len(pts) != nl:
                errs.append("levelCurve[%d] needs one point per layer" % s)
                continue
            for j, p in enumerate(pts):
                if not isinstance(p, dict) or set(p) != {"vel", "db"} or not _is(p["vel"], "int") or not _is(p["db"], "num"):
                    errs.append("levelCurve[%d][%d] must be {vel, db}" % (s, j))
                    break
                if j < nl and _is(layers[j].get("velRef"), "int") and p["vel"] != layers[j]["velRef"]:
                    errs.append("levelCurve[%d][%d].vel must equal layer %d velRef" % (s, j, j))
                if j and p["db"] < pts[j - 1]["db"] - 1e-9:
                    errs.append("levelCurve[%d] decreases at point %d" % (s, j))

    # units
    units = {}
    for i, U in enumerate(m["units"]):
        if not isinstance(U, dict) or set(U) != set(UNIT_FIELDS):
            errs.append("unit %d must be {%s}" % (i, ", ".join(UNIT_FIELDS)))
            return errs
        if not (_is(U["id"], "int") and _is(U["order"], "int") and _is(U["frames"], "int")
                and _is(U["label"], "str") and _is(U["file"], "str") and _is(U["sha1"], "str")):
            errs.append("unit %d has a field of the wrong type" % i)
            continue
        if not 0 <= U["id"] <= 63:
            errs.append("unit id %d outside 0..63" % U["id"])
        if U["id"] in units:
            errs.append("duplicate unit id %d" % U["id"])
        units[U["id"]] = U
        if U["file"] != "u/%d.opus" % U["id"]:
            errs.append("unit %d file must be u/%d.opus" % (U["id"], U["id"]))
        if U["frames"] <= 0:
            errs.append("unit %d frames must be > 0" % U["id"])
        if not HEX40.match(U["sha1"]):
            errs.append("unit %d sha1 invalid" % U["id"])
        if kit_dir is not None:
            p = os.path.join(kit_dir, U["file"])
            if not os.path.isfile(p):
                errs.append("unit file %s missing" % U["file"])
            else:
                if _sha1_file(p) != U["sha1"]:
                    errs.append("unit file %s sha1 mismatch" % U["file"])
    if sorted(U["order"] for U in units.values()) != list(range(len(units))):
        errs.append("unit order must be a permutation of 0..%d" % (len(units) - 1))
    for L in layers:
        if L.get("unit") not in units or L.get("unit") >= RELEASE_UNIT:
            errs.append("layer %s unit %s is not a sustain unit" % (L.get("index"), L.get("unit")))

    if env is None and kit_dir is not None:
        ep = os.path.join(kit_dir, "env.bin")
        if os.path.isfile(ep):
            with open(ep, "rb") as f:
                env = f.read()
        else:
            errs.append("env.bin missing")

    # regions
    sustain_cover = set()
    rr_seen = {"pedalDown": [], "pedalUp": []}
    for i, R in enumerate(m["regions"]):
        if not isinstance(R, dict):
            errs.append("region %d is not an object" % i)
            continue
        for k in R:
            if k not in REGION_REQUIRED and k not in REGION_OPTIONAL:
                errs.append("region %d: unknown field %r" % (i, k))
        miss = [k for k in REGION_REQUIRED if k not in R]
        if miss:
            errs.append("region %d: missing %s" % (i, ", ".join(miss)))
            continue
        if R["kind"] not in KINDS:
            errs.append("region %d: kind %r invalid" % (i, R["kind"]))
            continue
        ints = [k for k in REGION_REQUIRED if k not in ("kind", "gainDb")]
        if not all(_is(R[k], "int") for k in ints) or not _is(R["gainDb"], "num"):
            errs.append("region %d: wrong field types" % i)
            continue
        if R["id"] != i:
            errs.append("region ids not dense from 0 (position %d has id %d)" % (i, R["id"]))
        kind = R["kind"]
        U = units.get(R["unit"])
        if U is None:
            errs.append("region %d: unit %d unknown" % (i, R["unit"]))
        else:
            if kind == "release" and R["unit"] != RELEASE_UNIT:
                errs.append("region %d: releases live in unit 62" % i)
            if kind in ("pedalDown", "pedalUp") and R["unit"] != PEDAL_UNIT:
                errs.append("region %d: pedal noises live in unit 63" % i)
            if kind == "sustain" and R["unit"] >= RELEASE_UNIT:
                errs.append("region %d: sustain in a non-sustain unit" % i)
            if R["frames"] <= 0:
                errs.append("region %d: frames must be > 0" % i)
            if R["streamStart"] < 0 or R["streamStart"] + R["frames"] > U["frames"]:
                errs.append("region %d: streamStart + frames outside unit %d" % (i, R["unit"]))
        if not (0 <= R["onsetFrame"] < max(1, R["frames"])) or not (0 <= R["thrFrame"] < max(1, R["frames"])):
            errs.append("region %d: onsetFrame/thrFrame outside the region" % i)
        if R["envCount"] < 1 or R["envOffset"] < 0:
            errs.append("region %d: env range invalid" % i)
        elif env is not None and R["envOffset"] + R["envCount"] > len(env):
            errs.append("region %d: env range beyond env.bin" % i)
        if kind in ("sustain", "release"):
            if "pitchCents" not in R or not _is(R["pitchCents"], "num"):
                errs.append("region %d: pitchCents required for %s" % (i, kind))
            if not (0 <= R["stop"] < ns):
                errs.append("region %d: stop %d invalid" % (i, R["stop"]))
            if not (0 <= R["lo"] <= R["hi"] <= 127 and 0 <= R["root"] <= 127):
                errs.append("region %d: root/lo/hi invalid" % i)
            if R["rr"] != 0:
                errs.append("region %d: rr must be 0 for %s" % (i, kind))
            if kind == "sustain":
                if not (0 <= R["layer"] < nl):
                    errs.append("region %d: layer %d invalid" % (i, R["layer"]))
                else:
                    sustain_cover.add((R["stop"], R["layer"]))
            elif R["layer"] != -1:
                errs.append("region %d: release layer must be -1" % i)
        else:
            for f in ("stop", "layer", "root", "lo", "hi"):
                if R[f] != -1:
                    errs.append("region %d: pedal %s must be -1" % (i, f))
            if R["rr"] < 0:
                errs.append("region %d: rr must be >= 0" % i)
            rr_seen[kind].append(R["rr"])
        if "borrowable" in R and not _is(R["borrowable"], "bool"):
            errs.append("region %d: borrowable must be bool" % i)
        for f in ("seamGainDb", "seamLpHz"):
            if f in R and not _is(R[f], "num"):
                errs.append("region %d: %s must be a number" % (i, f))
        if ("seamGainDb" in R or "seamLpHz" in R) and not R.get("borrowable", False):
            errs.append("region %d: seam trims only on borrowable regions" % i)
    for kind, rrs in rr_seen.items():
        if sorted(rrs) != list(range(len(rrs))):
            errs.append("%s round-robin indices must be 0..n-1" % kind)
    for s in range(ns):
        for l in range(nl):
            if (s, l) not in sustain_cover:
                errs.append("stop %d has no sustain region on layer %d" % (s, l))

    # arrays
    for f in ("stretchCents", "inharmB", "damperT60"):
        if not _nums(m[f], 128):
            errs.append("%s must be 128 numbers" % f)
    if _nums(m["stretchCents"], 128) and abs(m["stretchCents"][69]) > 1e-3:
        errs.append("stretchCents must be 0 at key 69 (shape only)")
    if _nums(m["inharmB"], 128) and any(v < 0 for v in m["inharmB"]):
        errs.append("inharmB must be >= 0")
    if _nums(m["damperT60"], 128) and any(v <= 0 for v in m["damperT60"]):
        errs.append("damperT60 must be > 0")
    ft = m["freeT60"]
    if len(ft) != ns or not all(_nums(a, 128) for a in ft):
        errs.append("freeT60 must be stops × 128 numbers")
    elif any(v <= 0 for a in ft for v in a):
        errs.append("freeT60 must be > 0")
    rr = m["releaseRule"]
    if set(rr) != set(RELEASE_RULE_FIELDS) or not all(_is(rr[f], "num") for f in RELEASE_RULE_FIELDS):
        errs.append("releaseRule must be {%s} of numbers" % ", ".join(RELEASE_RULE_FIELDS))
    if not m["credit"].strip():
        errs.append("credit is empty")
    if not m["source"].strip():
        errs.append("source is empty")
    return errs


# --------------------------------------------------------------------------- §3.7 defaults
# The same formulas as contract/InstrumentProfile.kt (T11.10 checks the two against each other).

def grand_damper_t60(key):
    n = min(88, max(21, key))
    x = (88 - n) / 67.0
    return 0.12 + 1.2 * x * x


def grand_free_t60(key):
    return min(30.0, 6.24 * 10.0 ** (-0.0275 * (key - 60)))


def default_damper_t60(instrument, key):
    if instrument in ("grand", "stub"):
        return grand_damper_t60(key)
    if instrument == "upright":
        return 1.2 * grand_damper_t60(key)
    n = min(88, max(29, key))
    return 0.10 + 0.15 * (88 - n) / 59.0


def default_free_t60(instrument, stop_name, key):
    if instrument in ("grand", "stub"):
        return grand_free_t60(key)
    if instrument == "upright":
        return 0.8 * grand_free_t60(key)
    ratio = 2.0 ** ((key - 29) / 12.0)
    if stop_name == "4'":
        return 0.8 * 20.0 * (2.0 * ratio) ** -0.45
    return 20.0 * ratio ** -0.45


def main(argv):
    import json
    bad = 0
    for p in argv:
        with open(p) as f:
            m = json.load(f)
        e = validate(m, kit_dir=os.path.dirname(os.path.abspath(p)))
        print("%s: %s" % (p, "ok" if not e else "; ".join(e)))
        bad += bool(e)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    sys.exit(main(sys.argv[1:]))
