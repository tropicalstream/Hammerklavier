# Requests from WP1 (MIDI and performance)

## To WP11 (fixtures)

1. **`core/src/test/resources/wp11/midi_facts_golden.json`** — `MidiCorpusTest` (T1.7) reads this shape:
   ```json
   { "files": [ { "asset": "midi/krueger/bach/bach_846.mid", "durationSec": 123.456, "lowKey": 36, "highKey": 84,
                  "notes": 1234, "hasSustain": true, "hasSoft": false, "hasSostenuto": false,
                  "pedalMode": "switch", "harpsichordFolds": 0 } ] }
   ```
   - `durationSec` = last key-up + 1.5 s, in file time (no pre-roll), as `Performance.durationUs − PRE_ROLL_US` on the grand.
   - `lowKey`/`highKey`/`notes` are the file's raw notes after channel-10 dropping, before folding; a note-on
     and note-off on one (channel, key) at the same tick is one (zero-length) note.
   - `hasSustain`: the shaped CC64 curve crosses 0.33 at least once (any CC64 ≥ 1 in continuous files, ≥ 64 or a
     partial press ≥ 43 in switch files); `hasSoft`/`hasSostenuto`: CC67/CC66 reach ≥ 64 (switch) or 0.5.
   - `pedalMode`: `none` | `switch` | `continuous` (continuous = ≥ 8 distinct CC64 values strictly between 0 and 127,
     taken as the max across channels).
   - `harpsichordFolds` optional: notes outside 29–89.
   If `smf_stats.py` writes another shape, tell WP1 and it adapts the reader.
2. The `.mid` twins under `app/src/main/assets/midi/test/<name>.mid` (names from `SyntheticSpecs.NAMES`).
   WP1's own twin writer (`SmfWriter.twin`, test code) uses format 0, PPQ 500, tempo 500,000, a 4/4 time
   signature, notes and CC steps on channel 1 in (time, note-off < controller < note-on) order; the WP11
   twins must compile to the same arrays (`SyntheticParityTest.wp11TwinsCompileToTheirSyntheticScore`).
3. `catalog.json` and the bundled assets for `VoiceDemandTest.writesReportForTheCatalogue` and the op. 106 iv speed test.

## To WP0 (contract / stub, optional)

4. `PerfFixtures` reads raw controller steps; the real builder shapes them (§4.3). T1.8 therefore compares pedal
   curves by their reference-level crossings (± 1 ms) and by value (± 0.02) away from ramps, and ignores
   pedal-noise speed classes (the fixture calls every raw step class 2; the builder measures continuous slopes).
   No change needed unless WP0 prefers the fixture to measure the class the same way.
