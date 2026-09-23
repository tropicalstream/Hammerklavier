#!/usr/bin/env python3
"""An independent standard-library SMF reader (PLAN.md §6.7 step 3, §4.6) → build/midi_stats.json and
core/src/test/resources/wp11/midi_facts_golden.json (WP1's MidiCorpusTest, T1.7).

It is written separately from the app's SmfParser on purpose, from the SMF 1.0 specification and
the parser rules of §4.1 that change measured facts (running status, note-on velocity 0 = off,
SMPTE division ignores tempo, format 2 tracks played one after another, channel 10 dropped
unless it is the only channel with notes, CC120/CC123 end a channel's open notes).

    python3 tools/pipeline/smf_stats.py                     # every .mid under assets/midi
    python3 tools/pipeline/smf_stats.py FILE.mid ...        # print the facts of some files

Definitions (also written into the golden file):
- times are µs of file time from tick 0 (no pre-roll), via the tempo map built from every track;
- notes = note-ons with velocity > 0 on the kept channels;
- lastNoteOffUs = the latest note end, notes paired first-in first-out per (channel, key); a note
  without an off ends at the end of its track; a zero-length note lasts 30 ms (§4.3 step 3);
- cc64/cc66/cc67 = controller event counts on the kept channels; has* = any value > 0;
- pedalMode = CONTINUOUS if CC64 takes ≥ 8 distinct values strictly between 0 and 127, SWITCH if
  there is any CC64 event, else NONE (§4.3 step 2);
- folds[instrument] = notes outside the instrument's compass (grand and upright 21–108,
  harpsichord 29–89);
- flatVelocity = true when ≥ 95% of the notes share one velocity (catalogue velocityPolicy flat).
"""
import json
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common  # noqa: E402

COMPASS = {"grand": (21, 108), "upright": (21, 108), "harpsichord": (29, 89)}
ZERO_LEN_US = 30000


class SmfError(Exception):
    pass


def _vlq(b, i, end):
    v = 0
    for n in range(4):
        if i >= end:
            raise SmfError("VLQ past the end")
        c = b[i]
        i += 1
        v = (v << 7) | (c & 0x7F)
        if not c & 0x80:
            return v, i
    raise SmfError("VLQ longer than 4 bytes")


def read_smf(data):
    """→ (format, division, [track events]); each event (tick, kind, a, b, c):
    ('on'|'off', ch, key, vel), ('cc', ch, num, val), ('tempo', us, 0, 0), ('name', text), ('end',)."""
    if data[:4] == b"RIFF" and data[8:12] == b"RMID":
        j = data.find(b"MThd")
        if j < 0:
            raise SmfError("RMID without MThd")
        data = data[j:]
    if data[:4] != b"MThd":
        raise SmfError("not MIDI")
    hlen = struct.unpack(">I", data[4:8])[0]
    fmt, ntrk, div = struct.unpack(">HHh", data[8:14])
    pos = 8 + hlen
    tracks = []
    while pos + 8 <= len(data) and len(tracks) < ntrk:
        cid = data[pos:pos + 4]
        clen = struct.unpack(">I", data[pos + 4:pos + 8])[0]
        body_end = min(len(data), pos + 8 + clen)
        if cid == b"MTrk":
            tracks.append(_read_track(data, pos + 8, body_end))
        pos = pos + 8 + clen
    return fmt, div, tracks


def _read_track(b, i, end):
    ev = []
    tick = 0
    status = None
    while i < end:
        d, i = _vlq(b, i, end)
        tick += d
        if i >= end:
            break
        s = b[i]
        if s < 0x80:
            if status is None:
                raise SmfError("data byte without running status")
            s = status                           # running status: do not consume
        else:
            i += 1
        if s == 0xFF:
            status = None
            kind = b[i]
            ln, i = _vlq(b, i + 1, end)
            payload = b[i:i + ln]
            i += ln
            if kind == 0x51 and ln == 3:
                us = (payload[0] << 16) | (payload[1] << 8) | payload[2]
                if us > 0:
                    ev.append((tick, "tempo", us, 0, 0))
            elif kind == 0x03:
                ev.append((tick, "name", payload.decode("latin-1"), 0, 0))
            elif kind == 0x2F:
                ev.append((tick, "end", 0, 0, 0))
                break
            continue
        if s in (0xF0, 0xF7):
            status = None
            ln, i = _vlq(b, i, end)
            i += ln
            continue
        if s >= 0xF8:
            continue
        if s >= 0xF1:                            # system common: skip by its length
            status = None
            i += {0xF1: 1, 0xF2: 2, 0xF3: 1}.get(s, 0)
            continue
        status = s
        hi, ch = s & 0xF0, s & 0x0F
        n = 1 if hi in (0xC0, 0xD0) else 2
        a = b[i]
        c = b[i + 1] if n == 2 else 0
        i += n
        if hi == 0x90 and c > 0:
            ev.append((tick, "on", ch, a, c))
        elif hi == 0x80 or (hi == 0x90 and c == 0):
            ev.append((tick, "off", ch, a, c))
        elif hi == 0xB0:
            ev.append((tick, "cc", ch, a, c))
    if not ev or ev[-1][1] != "end":
        ev.append((tick, "end", 0, 0, 0))
    return ev


class TempoMap:
    def __init__(self, division, tracks, fmt):
        self.smpte = division < 0
        if self.smpte:
            fps = -(division >> 8)
            fps = 29.97 if fps == 29 else float(fps)
            tpf = division & 0xFF
            self.us_per_tick = 1e6 / (fps * tpf)
            return
        self.ppq = division
        changes = {}
        if fmt == 2:
            return                                # per-track tempo handled by the caller
        for tr in tracks:
            for t, k, us, _b, _c in tr:
                if k == "tempo":
                    changes[t] = us               # a later track's change at the same tick wins
        pts = sorted(changes.items())
        self.segs = [(0, 0.0, 500000)]
        for t, us in pts:
            t0, u0, q = self.segs[-1]
            base = u0 + (t - t0) * q / self.ppq
            if t == t0:
                self.segs[-1] = (t0, u0, us)
            else:
                self.segs.append((t, base, us))

    def us(self, tick):
        if self.smpte:
            return tick * self.us_per_tick
        lo, hi = 0, len(self.segs) - 1
        while lo < hi:
            mid = (lo + hi + 1) // 2
            if self.segs[mid][0] <= tick:
                lo = mid
            else:
                hi = mid - 1
        t0, u0, q = self.segs[lo]
        return u0 + (tick - t0) * q / self.ppq


def stats(data, name=""):
    fmt, div, tracks = read_smf(data)
    if fmt == 2:
        # play the tracks one after another: shift each by the previous tracks' lengths
        shifted, offset = [], 0
        for tr in tracks:
            end = max(t for t, *_ in tr)
            shifted.append([(t + offset,) + tuple(e[1:]) for e in tr])
            offset += end
        tracks = shifted
        fmt_eff = 1
    else:
        fmt_eff = fmt
    tm = TempoMap(div, tracks, fmt_eff)
    note_channels = sorted({e[2] for tr in tracks for e in tr if e[1] == "on"})
    drop = {9} if (9 in note_channels and len(note_channels) > 1) else set()
    kept = [c for c in note_channels if c not in drop]
    notes = []                                    # (on_us, off_us, key, vel, ch)
    cc = {64: [], 66: [], 67: []}
    last_event_us = 0.0
    names = []
    for tr in tracks:
        open_ = {}
        end_tick = tr[-1][0]
        for t, k, a, b, c in tr:
            u = tm.us(t)
            if k == "name":
                names.append(a)
                continue
            if k in ("on", "off", "cc") and a in drop:
                continue
            if k in ("on", "off", "cc"):
                last_event_us = max(last_event_us, u)
            if k == "on":
                open_.setdefault((a, b), []).append((u, c))
            elif k == "off":
                q = open_.get((a, b))
                if q:
                    on_u, vel = q.pop(0)
                    notes.append((on_u, u if u > on_u else on_u + ZERO_LEN_US, b, vel, a))
            elif k == "cc":
                if b in cc:
                    cc[b].append((u, c))
                elif b in (120, 123):
                    for (ch, key), q in list(open_.items()):
                        if ch == a:
                            for on_u, vel in q:
                                notes.append((on_u, u if u > on_u else on_u + ZERO_LEN_US, key, vel, ch))
                            open_[(ch, key)] = []
        end_us = tm.us(end_tick)
        for (ch, key), q in open_.items():
            for on_u, vel in q:
                notes.append((on_u, end_us if end_us > on_u else on_u + ZERO_LEN_US, key, vel, ch))
    notes.sort(key=lambda n: (n[0], n[2]))
    keys = [n[2] for n in notes]
    vel_hist = [0] * 128
    for n in notes:
        vel_hist[n[3]] += 1
    mid = {v for _u, v in cc[64] if 0 < v < 127}
    pedal = "CONTINUOUS" if len(mid) >= 8 else ("SWITCH" if cc[64] else "NONE")
    ncount = len(notes)
    return {
        "bytes": len(data), "sha1Hex": common.sha1_bytes(data),
        "format": fmt, "division": div, "tracks": len(tracks),
        "channels": kept, "drumsDropped": bool(drop),
        "notes": ncount,
        "lowKey": min(keys) if keys else -1, "highKey": max(keys) if keys else -1,
        "cc64": len(cc[64]), "cc66": len(cc[66]), "cc67": len(cc[67]),
        "hasSustain": any(v > 0 for _u, v in cc[64]), "hasSostenuto": any(v > 0 for _u, v in cc[66]),
        "hasSoft": any(v > 0 for _u, v in cc[67]), "pedalMode": pedal,
        "firstNoteOnUs": int(round(notes[0][0])) if notes else 0,
        "lastNoteOffUs": int(round(max(n[1] for n in notes))) if notes else 0,
        "lastEventUs": int(round(last_event_us)),
        "durationSec": round(max(n[1] for n in notes) / 1e6, 3) if notes else 0.0,
        "folds": {ins: sum(1 for k in keys if not lo <= k <= hi) for ins, (lo, hi) in COMPASS.items()},
        "flatVelocity": bool(ncount) and max(vel_hist) >= 0.95 * ncount,
        "velocityHistogram": [[v, c] for v, c in enumerate(vel_hist) if c],
        "trackNames": names,
    }


DEFINITIONS = {
    "time": "µs of file time from tick 0, no pre-roll; tempo map from every track (SMPTE: tempo ignored)",
    "notes": "note-ons with velocity > 0 on kept channels (channel 10 dropped unless the only one with notes)",
    "lastNoteOffUs": "latest note end; FIFO pairing per (channel, key); unpaired notes end at their track's end; "
                     "zero-length notes last 30 ms; CC120/CC123 end a channel's open notes",
    "durationSec": "lastNoteOffUs / 1e6, rounded to 1 ms (the catalogue's durationSec)",
    "cc": "controller event counts; has* = any value > 0",
    "pedalMode": "CONTINUOUS if CC64 has >= 8 distinct values strictly between 0 and 127; SWITCH if any CC64; else NONE",
    "folds": "notes outside the compass: grand/upright 21-108, harpsichord 29-89",
    "flatVelocity": ">= 95% of the notes share one velocity",
}


def collect(root=common.MIDI_ASSETS):
    out = {}
    for d, _sub, files in sorted(os.walk(root)):
        for f in sorted(files):
            if f.lower().endswith((".mid", ".midi", ".kar")):
                p = os.path.join(d, f)
                rel = os.path.relpath(p, common.ASSETS).replace(os.sep, "/")
                with open(p, "rb") as fh:
                    out[rel] = stats(fh.read(), rel)
    return out


def golden(all_stats):
    keep = ("bytes", "sha1Hex", "format", "division", "tracks", "channels", "drumsDropped", "notes", "lowKey",
            "highKey", "cc64", "cc66", "cc67", "hasSustain", "hasSostenuto", "hasSoft", "pedalMode",
            "firstNoteOnUs", "lastNoteOffUs", "durationSec", "folds", "flatVelocity")
    return {"schema": 1, "generator": "tools/pipeline/smf_stats.py", "definitions": DEFINITIONS,
            "files": {k: {f: v[f] for f in keep} for k, v in sorted(all_stats.items())}}


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if argv:
        for p in argv:
            with open(p, "rb") as f:
                print(p, json.dumps(stats(f.read()), indent=1))
        return 0
    s = collect()
    common.write_text(os.path.join(common.BUILD, "midi_stats.json"), common.json_dumps(s, digits=3))
    common.write_text(os.path.join(common.TEST_RES, "midi_facts_golden.json"), common.json_dumps(golden(s), digits=3))
    print("smf_stats: %d files -> build/midi_stats.json, %s" % (
        len(s), common.rel_to_root(os.path.join(common.TEST_RES, "midi_facts_golden.json"))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
