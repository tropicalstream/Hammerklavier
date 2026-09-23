"""Shared paths and helpers for the asset pipeline (PLAN.md §6.1). Standard library only.

Every writer in the pipeline is deterministic: the same inputs give byte-identical outputs
(json_dumps sorts nothing but rounds floats to a fixed number of digits, so a re-run on another
machine gives the same text).
"""
import base64
import csv
import hashlib
import json
import math
import os

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PIPELINE = os.path.join(ROOT, "tools", "pipeline")
MANIFESTS = os.path.join(ROOT, "docs", "manifests")
CACHE = os.path.join(ROOT, "tools", "cache")
SAMPLES = os.path.join(CACHE, "samples")
BUILD = os.path.join(ROOT, "build")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
INSTRUMENTS = os.path.join(ASSETS, "instruments")
MIDI_ASSETS = os.path.join(ASSETS, "midi")
TEST_RES = os.path.join(ROOT, "core", "src", "test", "resources", "wp11")
KOTLIN_CONTRACT = os.path.join(ROOT, "core", "src", "main", "java", "com", "tropicalstream",
                               "hammerklavier", "contract")

SR = 48000                      # every kit stream is 48 kHz stereo
UA = "Hammerklavier-pipeline/1.0 (+github tropicalstream)"


def sha1_bytes(data):
    return hashlib.sha1(data).hexdigest()


def sha1_file(path):
    h = hashlib.sha1()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def sha1_b32_file(path):
    return base64.b32encode(bytes.fromhex(sha1_file(path))).decode()


def read_tsv(path):
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f, delimiter="\t"))


def ensure_dir(path):
    os.makedirs(path, exist_ok=True)
    return path


def _round_floats(obj, digits):
    if isinstance(obj, float):
        if math.isnan(obj) or math.isinf(obj):
            raise ValueError("non-finite float in JSON output")
        r = round(obj, digits)
        return 0.0 if r == 0 else r          # no "-0.0"
    if isinstance(obj, dict):
        return {k: _round_floats(v, digits) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [_round_floats(v, digits) for v in obj]
    return obj


def json_dumps(obj, digits=6, compact_lists=True):
    """Deterministic JSON: insertion order kept, floats rounded, arrays of numbers on one line."""
    obj = _round_floats(obj, digits)
    text = json.dumps(obj, indent=1, ensure_ascii=False)
    if compact_lists:
        text = _compact_number_lists(text)
    return text + "\n"


def _compact_number_lists(text):
    out = []
    lines = text.split("\n")
    i = 0
    while i < len(lines):
        ln = lines[i]
        if ln.rstrip().endswith("[") and i + 1 < len(lines):
            j = i + 1
            items = []
            while j < len(lines) and _is_scalar_line(lines[j]):
                items.append(lines[j].strip().rstrip(","))
                j += 1
            if items and j < len(lines) and lines[j].strip() in ("]", "],"):
                out.append(ln.rstrip() + ", ".join(items) + lines[j].strip())
                i = j + 1
                continue
        out.append(ln)
        i += 1
    return "\n".join(out)


def _is_scalar_line(ln):
    s = ln.strip().rstrip(",")
    if not s:
        return False
    if s in ("true", "false", "null"):
        return True
    if s.startswith('"') and s.endswith('"') and '": ' not in s:
        return True
    try:
        float(s)
        return True
    except ValueError:
        return False


def write_text(path, text):
    ensure_dir(os.path.dirname(path))
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def write_bytes(path, data):
    ensure_dir(os.path.dirname(path))
    with open(path, "wb") as f:
        f.write(data)


def rel_to_root(path):
    return os.path.relpath(path, ROOT)


def read_bytes(path):
    with open(path, "rb") as f:
        return f.read()
