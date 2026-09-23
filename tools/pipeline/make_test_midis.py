#!/usr/bin/env python3
"""Our own CC0 test MIDI files (PLAN.md §4.5, §6.8) → app/src/main/assets/midi/test/.

- The nine §4.5 twins (sync, scale, storm64, pedalhalf, sostenuto, unacorda, repeat15, fold,
  crescendo): exactly the note and controller lists of contract/ScoreApi.kt `SyntheticSpecs`,
  written as format 0, PPQ 500, tempo 500,000 µs per quarter, so 1 tick = 1 ms and every time is
  exact. One channel (0). At equal ticks: note-offs, then controllers (in the spec's order),
  then note-ons (by key), the class order of §4.3 step 1.
- format0.mid / format1.mid: the same short piece (two voices on channels 0 and 1, sustain pedal,
  a tempo change and a time-signature change) as one track and as a tempo track + two tracks.
- smpte25.mid: SMPTE division −25 fps × 40 ticks per frame (1 tick = 1 ms) with a tempo meta that
  a correct reader ignores.
- running_status.mid: channel messages in running status throughout, note-offs as note-on
  velocity 0, and a meta and a sysex in mid-stream that cancel running status (the status byte is
  restated after each, per the specification).

Deterministic; `known_contents()` gives the facts T11.8 checks smf_stats against.

    python3 tools/pipeline/make_test_midis.py [--out DIR]
"""
import argparse
import math
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common  # noqa: E402

OUT = os.path.join(common.MIDI_ASSETS, "test")
TWIN_NAMES = ["sync", "scale", "storm64", "pedalhalf", "sostenuto", "unacorda", "repeat15", "fold", "crescendo"]
EXTRA_NAMES = ["format0", "format1", "smpte25", "running_status"]


def jround(x):
    """Java/Kotlin Math.round for doubles: floor(x + 0.5)."""
    return int(math.floor(x + 0.5))


def click_offset_ms(i):
    """SyntheticSpecs.clickOffsetMs in 32-bit two's-complement arithmetic."""
    m = 0xFFFFFFFF
    h = (i * (-0x61c88647)) & m
    h ^= h >> 16
    h = (h * 0x45d9f3b) & m
    h ^= h >> 16
    return (h & 0x7FFFFFFF) % 34


class Spec:
    """Notes (on_ms, len_ms, key, vel) and controller steps (ms, value) per CC, in spec order."""

    def __init__(self):
        self.notes = []
        self.cc = {64: [], 66: [], 67: []}

    def note(self, on, length, key, vel):
        self.notes.append((on, on + length, key, max(1, min(127, vel))))

    def sorted_notes(self):
        return sorted(self.notes, key=lambda n: (n[0], n[2]))


def spec(name):
    s = Spec()
    if name == "sync":
        for i in range(100):
            s.note(600 * i + click_offset_ms(i), 50, 69, 118)
    elif name == "scale":
        for j in range(88):
            s.note(250 * j, 200, 21 + j, 20 + jround(107.0 * j / 87.0))
    elif name == "storm64":
        for c in range(720):
            for j in range(64):
                s.note(250 * c, 200, 21 + ((29 * c + 27 * j) % 88), 40 + ((7 * c + 13 * j) % 80))
        s.cc[64] += [(0, 127), (180000, 0)]
    elif name == "pedalhalf":
        for c in range(16):
            for k in (48, 60, 64, 67):
                s.note(1000 * c, 800, k, 80)
        for rep in range(2):
            base = 8000 * rep
            for v in range(128):
                s.cc[64].append((base + jround(v * 4000.0 / 127.0), v))
            for v in range(1, 128):
                s.cc[64].append((base + 4000 + jround(v * 4000.0 / 127.0), 127 - v))
    elif name == "sostenuto":
        s.note(0, 6000, 36, 80)
        s.cc[66] += [(500, 127), (5000, 0)]
        for c in range(8):
            for k in (60, 64, 67):
                s.note(1000 + 500 * c, 100, k, 70)
        s.cc[64] += [(6500, 51), (10000, 0)]
        s.note(7000, 2500, 43, 80)
        s.cc[66] += [(7500, 127), (9000, 0)]
        for t in (8000, 8500):
            for k in (72, 76, 79):
                s.note(t, 100, k, 70)
    elif name == "unacorda":
        s.note(0, 3000, 60, 64)
        s.cc[67].append((4000, 127))
        s.note(4500, 3000, 60, 64)
        s.cc[67].append((8000, 0))
    elif name == "repeat15":
        vels = (20, 64, 110)
        for b in range(3):
            for j in range(30):
                s.note(3000 * b + jround(j * 1000.0 / 15.0), 33, 60, vels[b])
    elif name == "fold":
        for j in range(109):
            s.note(150 * j, 120, 12 + j, 70)
    elif name == "crescendo":
        for j in range(118):
            s.note(400 * j, 300, 60, 10 + j)
    else:
        raise KeyError(name)
    return s


# --------------------------------------------------------------------------- SMF writing

def vlq(n):
    if n < 0:
        raise ValueError("negative delta")
    out = [n & 0x7F]
    n >>= 7
    while n:
        out.append(0x80 | (n & 0x7F))
        n >>= 7
    return bytes(reversed(out))


def meta(kind, data):
    return bytes([0xFF, kind]) + vlq(len(data)) + data


def tempo_meta(us_per_quarter):
    return meta(0x51, struct.pack(">I", us_per_quarter)[1:])


def timesig_meta(num, den_pow, clocks=24, n32=8):
    return meta(0x58, bytes([num, den_pow, clocks, n32]))


def track_chunk(events, running_status=False):
    """events: [(tick, bytes)] already in order. Writes deltas, optional running status for
    channel messages (reset by meta and sysex), and the end-of-track meta at the last tick."""
    out = bytearray()
    last = 0
    status = None
    for tick, ev in events:
        out += vlq(tick - last)
        last = tick
        st = ev[0]
        if 0x80 <= st <= 0xEF:
            if running_status and st == status:
                out += ev[1:]
            else:
                out += ev
            status = st
        else:
            out += ev
            status = None            # meta and sysex cancel running status
    out += vlq(0) + meta(0x2F, b"")
    return b"MTrk" + struct.pack(">I", len(out)) + bytes(out)


def smf(fmt, division_bytes, tracks):
    return b"MThd" + struct.pack(">IHH", 6, fmt, len(tracks)) + division_bytes + b"".join(tracks)


def ppq_division(ppq):
    return struct.pack(">H", ppq)


def twin_events(s, title):
    """Spec → ordered [(tick, bytes)] at 1 tick = 1 ms, channel 0."""
    ev = []
    for i, (on, off, k, v) in enumerate(s.sorted_notes()):
        ev.append((off, 0, k, i, bytes([0x80, k, 0])))
        ev.append((on, 2, k, i, bytes([0x90, k, v])))
    seq = 0
    for cc in (64, 66, 67):
        for ms, val in s.cc[cc]:
            ev.append((ms, 1, cc, seq, bytes([0xB0, cc, val])))
            seq += 1
    ev.sort(key=lambda e: (e[0], e[1], e[2] if e[1] != 1 else 0, e[3]))
    head = [(0, meta(0x03, title.encode("utf-8"))), (0, meta(0x02, b"CC0 1.0, Hammerklavier test file")),
            (0, tempo_meta(500000)), (0, timesig_meta(4, 2))]
    return head + [(e[0], e[4]) for e in ev]


def write_twin(name):
    s = spec(name)
    return smf(0, ppq_division(500), [track_chunk(twin_events(s, "Hammerklavier test: %s" % name))])


# --------------------------------------------------------------------------- the format0/format1 piece

PPQ = 480


def piece():
    """(tempo/meta events, RH events, LH events) as [(tick, order, bytes)]: 8 bars of 4/4 then
    2 bars of 3/4; tempo 500,000 µs/qn, 400,000 from bar 5; sustain pedal re-pressed each bar."""
    meta_ev = [(0, 0, meta(0x03, b"Hammerklavier test: format pair")), (0, 0, meta(0x02, b"CC0 1.0")),
               (0, 0, tempo_meta(500000)), (0, 0, timesig_meta(4, 2)),
               (4 * 4 * PPQ, 0, tempo_meta(400000)), (8 * 4 * PPQ, 0, timesig_meta(3, 2))]
    rh, lh = [], []
    chords = [(60, 64, 67), (57, 60, 64), (53, 57, 60), (55, 59, 62)] * 2 + [(60, 64, 67), (55, 60, 64)]
    bass = [48, 45, 41, 43, 48, 45, 41, 43, 36, 43]
    tick = 0
    for bar, (ch, b) in enumerate(zip(chords, bass)):
        beats = 4 if bar < 8 else 3
        step = PPQ // 2
        for i in range(beats * 2):
            k = ch[[0, 2, 1, 2][i % 4]]
            v = 60 + (i * 7 + bar * 5) % 30
            rh.append((tick + i * step + step - 10, 0, bytes([0x80, k + 12, 64])))
            rh.append((tick + i * step, 2, bytes([0x90, k + 12, v])))
        lh.append((tick, 2, bytes([0x91, b, 70])))
        lh.append((tick + beats * PPQ - 5, 0, bytes([0x81, b, 0])))
        lh.append((tick + 20, 1, bytes([0xB1, 64, 127])))
        lh.append((tick + beats * PPQ - 30, 1, bytes([0xB1, 64, 0])))
        tick += beats * PPQ
    return meta_ev, rh, lh


def _order(evs):
    return [(t, b) for t, _o, b in sorted(evs, key=lambda e: (e[0], e[1]))]


def write_format0():
    m, rh, lh = piece()
    evs = [(t, o, b) for t, o, b in m] + [(t, o + 1, b) for t, o, b in rh + lh]
    return smf(0, ppq_division(PPQ), [track_chunk(_order(evs))])


def write_format1():
    m, rh, lh = piece()
    return smf(1, ppq_division(PPQ), [track_chunk(_order(m)), track_chunk(_order(rh)), track_chunk(_order(lh))])


def write_smpte25():
    # division: high byte −25 (two's complement 0xE7), low byte 40 ticks per frame → 1000 ticks/s
    ev = [(0, 0, meta(0x03, b"Hammerklavier test: smpte25")), (0, 0, tempo_meta(250000))]
    melody = [62, 64, 65, 67, 69, 70, 72, 74]
    for i, k in enumerate(melody):
        ev.append((i * 375, 2, bytes([0x90, k, 50 + 8 * i])))
        ev.append((i * 375 + 300, 0, bytes([0x80, k, 0])))
    return smf(0, bytes([0xE7, 40]), [track_chunk(_order(ev))])


def write_running_status():
    ev = [(0, 0, meta(0x03, b"Hammerklavier test: running status")), (0, 0, tempo_meta(600000))]
    t = 0
    keys = [60, 62, 64, 65, 67, 69, 71, 72, 74, 76, 77, 79]
    for i, k in enumerate(keys):
        ev.append((t, 2, bytes([0x92, k, 40 + 5 * i])))
        ev.append((t + 40, 0, bytes([0x92, k, 0])))            # note-on velocity 0 = note-off
        if i == 3:
            ev.append((t + 40, 1, bytes([0xB2, 64, 100])))
        if i == 5:
            ev.append((t + 44, 3, meta(0x01, b"a meta cancels running status")))
        if i == 8:
            ev.append((t + 44, 3, bytes([0xF0, 0x05, 0x7E, 0x7F, 0x09, 0x01, 0xF7])))   # GM on, cancels too
        if i == 10:
            ev.append((t + 40, 1, bytes([0xB2, 64, 0])))
        t += 48
    return smf(0, ppq_division(96), [track_chunk(_order(ev), running_status=True)])


def all_files():
    out = {}
    for n in TWIN_NAMES:
        out[n + ".mid"] = write_twin(n)
    out["format0.mid"] = write_format0()
    out["format1.mid"] = write_format1()
    out["smpte25.mid"] = write_smpte25()
    out["running_status.mid"] = write_running_status()
    return out


def known_contents():
    """What each file is known to contain, from the generator (T11.8): notes, range, controller
    counts and the last note-off in µs of file time."""
    facts = {}
    for n in TWIN_NAMES:
        s = spec(n)
        ns = s.sorted_notes()
        facts[n + ".mid"] = {"notes": len(ns), "lowKey": min(x[2] for x in ns), "highKey": max(x[2] for x in ns),
                             "cc64": len(s.cc[64]), "cc66": len(s.cc[66]), "cc67": len(s.cc[67]),
                             "lastNoteOffUs": max(x[1] for x in ns) * 1000, "channels": [0],
                             "onsUs": [x[0] * 1000 for x in ns], "keys": [x[2] for x in ns],
                             "vels": [x[3] for x in ns]}
    # the format pair: 8 bars 4/4 at 500 ms/qn (bars 1-4) and 400 ms/qn (5-8), 2 bars 3/4 at 400
    q1, q2 = 500000, 400000
    last_tick = 8 * 4 * PPQ + 2 * 3 * PPQ - 5
    last_us = 16 * q1 + (last_tick - 16 * PPQ) * q2 // PPQ
    for f in ("format0.mid", "format1.mid"):
        facts[f] = {"notes": 8 * 8 + 2 * 6 + 10, "lowKey": 36, "highKey": 79, "cc64": 20, "cc66": 0, "cc67": 0,
                    "lastNoteOffUs": last_us, "channels": [0, 1]}
    facts["smpte25.mid"] = {"notes": 8, "lowKey": 62, "highKey": 74, "cc64": 0, "cc66": 0, "cc67": 0,
                            "lastNoteOffUs": (7 * 375 + 300) * 1000, "channels": [0]}
    facts["running_status.mid"] = {"notes": 12, "lowKey": 60, "highKey": 79, "cc64": 2, "cc66": 0, "cc67": 0,
                                   "lastNoteOffUs": (11 * 48 + 40) * 600000 // 96, "channels": [2]}
    return facts


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=OUT)
    a = ap.parse_args(argv)
    for name, data in all_files().items():
        common.write_bytes(os.path.join(a.out, name), data)
        print("%-20s %7d bytes  sha1 %s" % (name, len(data), common.sha1_bytes(data)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
