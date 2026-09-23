"""Readers for the sample-map data the kits are built from (PLAN.md §6.1, §6.5).

- Salamander V3 `Data/*.txt` (kinwie's SFZ data, in tools/cache/samples/grand-docs/): the $OFFnn
  start offsets in vel_NN.txt, the $TUNEnn tables in tune_nat.txt / tune_ret.txt, the region
  table in region.txt and the velocity splits in notes.txt.
- VCSL SFZ files (tools/cache/samples/vcsl-sfz-maps/): <group>/<region> opcodes, `trigger`,
  `lokey`, `hikey`, `pitch_keycenter`, `lovel`, `hivel`, `amp_veltrack`, `ampeg_release`, `sample`.
- VSCO-2 CE upright pp `MappingChart.txt` (NNN=key).
"""
import os
import re

_DEFINE = re.compile(r"#define\s+(\$\w+)\s+(\S+)")
_OPCODE = re.compile(r"([A-Za-z_][\w$]*)=(.*?)(?=\s+[A-Za-z_][\w$]*=|\s*$)")


def read_defines(path):
    out = {}
    with open(path, encoding="utf-8", errors="replace") as f:
        for ln in f:
            m = _DEFINE.search(ln)
            if m:
                out[m.group(1)] = m.group(2)
    return out


def _strip_comment(ln):
    i = ln.find("//")
    return ln if i < 0 else ln[:i]


def parse_sfz_text(text, defines=None):
    """Regions of an SFZ file (no #include): a list of dicts, each the opcodes of the
    <global>/<master>/<group> headers in force merged with the region's own. Values are strings;
    $DEFINES are substituted."""
    defines = dict(defines or {})
    scope = {"global": {}, "master": {}, "group": {}}
    target = None                 # dict receiving opcodes
    regions = []
    for raw in text.splitlines():
        m = _DEFINE.search(raw)
        if m:
            defines[m.group(1)] = m.group(2)
            continue
        ln = _strip_comment(raw).strip()
        if not ln:
            continue
        for tok in re.split(r"(<\w+>)", ln):
            tok = tok.strip()
            if not tok:
                continue
            if tok.startswith("<") and tok.endswith(">"):
                h = tok[1:-1]
                if h == "region":
                    target = {}
                    target.update(scope["global"]); target.update(scope["master"]); target.update(scope["group"])
                    regions.append(target)
                elif h in scope:
                    if h == "global":
                        scope["master"], scope["group"] = {}, {}
                    elif h == "master":
                        scope["group"] = {}
                    scope[h] = {}
                    target = scope[h]
                else:
                    target = None             # <control>, <curve>, ...: ignored
                continue
            if target is None:
                continue
            for k, v in _OPCODE.findall(tok):
                v = v.strip()
                for dk in sorted(defines, key=len, reverse=True):
                    v = v.replace(dk, defines[dk])
                    k = k.replace(dk, defines[dk])
                target[k] = v
    return regions


def parse_sfz(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        return parse_sfz_text(f.read())


def vcsl_regions(path):
    """VCSL SFZ → list of dicts with typed fields: sample (basename), trigger (attack|release),
    lokey, hikey, root, lovel, hivel, amp_veltrack, ampeg_release, tune, volume."""
    out = []
    for r in parse_sfz(path):
        if "sample" not in r:
            continue
        root = int(r.get("pitch_keycenter", r.get("key", r.get("lokey", "60"))))
        out.append({
            "sample": os.path.basename(r["sample"].replace("\\", "/")),
            "trigger": r.get("trigger", "attack"),
            "lokey": int(r.get("lokey", r.get("key", root))),
            "hikey": int(r.get("hikey", r.get("key", root))),
            "root": root,
            "lovel": int(r.get("lovel", "0")),
            "hivel": int(r.get("hivel", "127")),
            "amp_veltrack": float(r.get("amp_veltrack", "100")),
            "ampeg_release": float(r.get("ampeg_release", "0")),
            "tune": float(r.get("tune", "0")),
            "volume": float(r.get("volume", "0")),
        })
    return out


# --------------------------------------------------------------------------- Salamander

_NOTE = {"C": 0, "C#": 1, "D": 2, "D#": 3, "E": 4, "F": 5, "F#": 6, "G": 7, "G#": 8, "A": 9, "A#": 10, "B": 11}


def note_to_key(name):
    """'A0' → 21, 'C#4' → 61, 'A-1' → 9 (VCSL uses C4 = 60)."""
    m = re.match(r"^([A-G]#?)(-?\d+)$", name)
    if not m:
        raise ValueError(name)
    return 12 * (int(m.group(2)) + 1) + _NOTE[m.group(1)]


def salamander_region_table(docs_dir):
    """region.txt → list of {label, lokey, hikey, root, note} (note = sample stem such as 'A0')."""
    out = []
    with open(os.path.join(docs_dir, "region.txt"), encoding="utf-8") as f:
        for ln in f:
            if "<region>" not in ln:
                continue
            kv = dict(re.findall(r"(\w[\w$]*)=(\S+)", ln))
            sample = kv["sample"]
            note = sample.split("$VEL")[0]
            out.append({"label": kv["region_label"], "lokey": int(kv["lokey"]), "hikey": int(kv["hikey"]),
                        "root": int(kv["pitch_keycenter"]), "note": note})
    return out


def salamander_splits(docs_dir):
    """notes.txt → [(vel_file_number, lovel, hivel)] for the 16 layers."""
    out = []
    with open(os.path.join(docs_dir, "notes.txt"), encoding="utf-8") as f:
        for ln in f:
            m = re.search(r'vel_(\d+)\.txt"\s+lovel=(\d+)\s+hivel=(\d+)', ln)
            if m:
                out.append((int(m.group(1)), int(m.group(2)), int(m.group(3))))
    return out


def salamander_offsets(docs_dir, layer_no):
    """vel_NN.txt → {region_label: start offset in samples @48k}."""
    d = read_defines(os.path.join(docs_dir, "vel_%02d.txt" % layer_no))
    return {k[4:]: int(v) for k, v in d.items() if k.startswith("$OFF")}


def salamander_tune(docs_dir, which):
    """tune_nat.txt / tune_ret.txt → {region_label: cents}."""
    d = read_defines(os.path.join(docs_dir, "tune_%s.txt" % which))
    return {k[5:]: float(v) for k, v in d.items() if k.startswith("$TUNE")}


def vsco_mapping(path):
    """MappingChart.txt → {index: key}."""
    out = {}
    with open(path, encoding="utf-8", errors="replace") as f:
        for ln in f:
            m = re.match(r"^\s*(\d{3})=(\d+)", ln)
            if m:
                out[int(m.group(1))] = int(m.group(2))
    return out
