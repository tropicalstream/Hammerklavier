# WP1 progress (MIDI and performance, pure)

Branch `wp1-midi` (from `contracts-v1`), worktree `/Users/me/Projects/hk-wp1`. Plan: §4.1–§4.5, §3.14, §7.2 WP1.

## Done
- `core/.../midi/`: `SmfModel` (RawSmf, RawTrack parallel arrays, SmfLimits, SmfError → RejectReason, SmfResult),
  `SmfParser`, `TempoMap`, `ChannelMerge` (+ `MergedEvents`), `NotePairing` (+ `NoteList`, `IdxSort`),
  `PedalShaper`, `InstrumentAdapter`, `VoiceDemand`, `PerformanceBuilder`, `SyntheticScores`,
  `ScoreCompilerImpl` (constructor `ScoreCompilerImpl()` as frozen).
- Tests (`core/src/test/.../midi/`): `SmfWriter` (test-only), `MidiTestUtil`, `SmfParserTest` (T1.1),
  `TempoMapTest` (T1.2), `PerformanceBuilderTest` (T1.3), `PedalShaperTest` (T1.4), `InstrumentAdapterTest` (T1.5),
  `FuzzAndSpeedTest` (T1.6), `MidiCorpusTest` (T1.7), `SyntheticParityTest` (T1.8), `VoiceDemandTest` (T1.9;
  writes `core/build/voice_demand.txt` for the synthetic scores).
- Results: `tools/gw :core:test` → 101 tests, 0 failures, 4 skipped (all `@Ignore("needs wp11 fixture")`:
  MidiCorpusTest, op. 106 iv speed, WP11 twins parity, catalogue voice-demand report). 20k-note build ≈ well under
  60 ms; 10,000 mutations of every test file (1,000 for the 280 KB storm twin) never throw, slowest parse < 50 ms.

## Remaining
- Remove the four `@Ignore`s once WP11 lands `midi_facts_golden.json`, the test twins, `catalog.json` and the
  corpus (see docs/requests/WP1.md for the golden schema WP1 reads).
- None of WP1's tests needs contracts-v1.1: PerfFixtures/SyntheticSpecs/PedalCurve were already working in v1.

## Decisions and deviations (with reasons)
1. **Switch-mode partial values:** a CC64/66/67 value 1–63 in a switch-mode file targets value/127 rather than 0
   (≥ 64 → 1, 0 → 0). The SOSTENUTO spec's "CC64 51 (0.40)" and T1.3's "a press at sustain 0.4" require the
   curve to read 0.40; a strict threshold would make it 0.
2. **Continuous mode:** each value is held and every change slewed at 70 ms full travel (no lead). "Linear
   between events" would contradict "slew-limited" and shift crossings by up to one controller step.
3. **Legato hold** (harpsichord) reads the raw CC64 steps (down = value ≥ 64, release = the next value < 64),
   not the shaped curve; this keeps half-pedal files from finger-pedalling at 0.33 and matches PerfFixtures.
4. **Zero-length notes:** the merge puts note-offs before note-ons at one tick, so an on/off pair at the same tick
   arrives off-first; an orphan note-off is remembered and the note-on that follows at the same time takes it
   (→ 30 ms). Without this such notes would hang.
5. **Drums-only files:** §4.1 says channel 10 is kept when it is the only channel with notes, T1.1 says drums-only
   fails. Resolved: a channel-10-only file is refused (`NO_KEYBOARD_NOTES`) when its median note is < 100 ms
   (percussion hits); otherwise played.
6. **Re-strike serialisation** follows PerfFixtures exactly (prev ends at max(newOn − 2 ms, prevOn + 20 ms); if that
   is after newOn the new note starts 1 µs after it) and runs after folding, so folded collisions merge first.
7. **Pedal-noise speed class:** switch files → 2 (as §4.3); continuous files → from the mean slope over ±35 ms.
8. **VoiceDemand model:** voices per note = profile stops (harpsichord 2); crossfade bands are supported by the
   `Model` but empty by default (grand HD is hard-switched); voices end at min(trim §3.2, −80 dB natural decay
   by T60f, damper landing + −80 dB by T60d, re-strike fade 9.21 τ); a 4th voice on a key moves the oldest to an
   uncounted kill slot; demand is sampled at each onset. Catalogue p99/max will be checked against the 60–90
   estimate when the corpus exists.
9. **T1.8 tolerances** as documented in `SyntheticParityTest` (crossings ± 1 ms, values ± 0.02 away from ramps,
   speed classes ignored).
10. `ScoreFacts` from `inspect` use the raw (unfolded) key range and note count; `durationSec` excludes the pre-roll.
