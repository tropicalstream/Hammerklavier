# WP2 progress: audio engine core (pure)

Branch `wp2-engine` (from `contracts-v1`), worktree `/Users/me/Projects/hk-wp2`.
Plan: PLAN §7.2 WP2; §2.5, §3.5–§3.10, §3.14–§3.16; review log R5, R9, R10, R18, R22, R31, R58, R79, R80,
R85, R86, R101.

## Done (2026-09-22)

Every file of the §2.2 rows for WP2, in `core/src/main/java/com/tropicalstream/hammerklavier/engine/`:

| File | What it does |
|---|---|
| `EngineCore.kt` | `EngineCore(dsp, cursors, head, sampleRate)` : `EngineCoreApi`; the §3.15 block graph; all 16 `Cmd` codes; `PreparedBank` / `PreparedKeyMap` tokens; releases, handoffs, pedal noises; `debugOnset`; `bench` + `benchCpuMhz` |
| `Sequencer.kt` | event cursor, 1,280-frame dispatch window, the ≤ 64 pending-onset list (R22), the ≤ 64 skip set (R5) |
| `PendingEvents.kt` | FIFO of state events (key-up, latch, pedal noise, end, and each note's own key-down) applied in the block containing their play frame |
| `KeyState.kt` | held / undamped / latched keys, damper landings and quill passes counted in play frames, D(k), gates (0..1, harpsichord = held), soft feeds, self-row wishes, seek rebuild |
| `Voice.kt` | window staging and refills, Hermite / linear / copy kernels with a gain ramp split at a landing frame, one-pole spectral low-pass |
| `VoicePool.kt` | 204 voices whose role moves between MAIN (cap), KILL (5 ms, cap/2 slots) and NOISE (12); per-block multipliers, fades (re-strike exp, linear, equal-power handoff, pause), levelDb, energy, cursor board, cull |
| `StealPolicy.kt` | the pure victim choice of §3.14 |
| `DamperModel.kt`, `DecayTables.kt` | DAMP / FCT 128 × 33 per key map; env-byte, dB, `vel^0.7`, `exp(−age/3)`, quarter-sine, LP-coefficient and hash tables |
| `EngineBench.kt` | ns per Hermite / linear / copy voice-frame, spectral extra, comb-frame with and without dispersion, soft / room / master per frame; 2.0 GHz normalisation; `capFor` = clamp(round8(0.25 × 20,833 / ns), 64, 128); step-down savings; blocking `run` or sliced `begin`/`step` |

Tests (`core/src/test/java/.../engine/`), all green, no `@Ignore`:

| Test | Class |
|---|---|
| T2.1 timing (1 s → frame 48,000 at 46 block offsets; rate 0.5 → 96,000; 10 min in at r = 1, 0.95, 1.37 exact; playback rates 2^(±2/12), 2^(5/12) within ±1 frame; across a pause and a seek; debugOnset) | `EngineTimingTest` |
| T2.2 pool (steal order, the 50 ms rule, never pending / kill / noise voices; `CHORD_STORM_64` for 180 s at caps 64 and 128: cap never exceeded, ≤ 3 per key, kill slots ≤ cap/2, max step ≤ 0.02 FS outside the attacks, dropped = 0; noises never take music voices) | `VoicePoolTest` |
| T2.3 dampers (half pedal T60d/D within 2 %; keys > lastDamper; latched keys; re-pedalling freezes; LP never re-opens; damping onset at round(lag·48) ±1 at r = 0.5/1/1.5, Q0 and Q2; tail-carrying handoff ≤ 1 dB step, full damping only) | `DamperTest` |
| T2.4 voice (bit-identical at rate 1; 2^(1/12) peak within 0.1 cent; refills ≤ 1.05 × source step; levelDb vs RMS ±1 dB; cull; crossfades) | `VoiceTest` |
| T2.5 releases and noises | `ReleaseNoiseTest` |
| T2.6 harpsichord (4′ lead = round(staggerMs·48) ±1 at tempos 0.5/1/1.5 × rates 1, 2^(±2/12) × v1/64/127; registration on new notes only and published; RMS ±1 dB over v20–127; quill-pass handoffs; gate follows the key) | `HarpsichordTest` |
| T2.7 transport (pause 60 ms fade and freeze, resume; seek; bank swap 30 ms; pause/seek/new perf/bank 1–255 frames before an onset; state events in their block; `startUs = −1`; `endedGeneration`, `idle`) | `TransportTest` |
| T2.8 comb gates, soft feeds, self rows | `CombGateTest` |
| T2.9 relative levels (monotone, ≤ 0.5 dB per velocity step across every split: XFADE 3 and 6 layers, HARD 16) | `RelativeLevelTest` |
| T2.10 allocation (10 s of the storm at caps 64/128, Q2, pedalhalf; drain + render + clock + energy ring + stats; transport commands) | `AllocationTest` |
| T2.11 energy (−12 dBFS C4 → lane 39 = 0.25 ± 10 %; lanes vs RMS ±1 dB; damped lane slope = 60/T60d + natural within 5 %; comb lane; epoch) | `EnergyTest` |
| T2.12 bench | `EngineBenchTest` |
| listening renders → `core/build/renders/*.wav` | `OfflineRenderTest`, `OfflineRender` (test source set, usable by WP3's JVM renders) |

Private test helpers: `EngineTestKit.kt` (`Harness`, `IdentityMaster`, `TestBank`, `KeyMap.copy`,
`KeyMap.withRate`, `perfSong`, curves, DTFT peak search).

## Test results

`tools/gw :core:test`: 103 tests (WP0's 37 + WP2's 66), 0 failures, 0 skipped. `tools/check_purity.sh`: OK.
JVM bench on this Mac (PassThroughDsp): Hermite ≈ 8 ns, linear ≈ 5 ns, copy ≈ 4 ns per voice-frame.

## Remaining

- Device work (M1 with WP4): `--ez bench true` numbers, the on-device HKAudio allocation count around
  10 s of `storm64` (debug build), T-CPU, T-UND, T-GC. Not allowed in this task (no adb).
- Re-run T2.8/T2.11 against WP3's real `ResonanceProcessor` once it merges (the tests use capture
  stand-ins for the combs).

## Decisions and deviations (with reasons)

1. **Play frames.** Scheduling (voice starts, queued events, damper landings, quill passes) uses a
   play-frame counter that advances only in playing blocks, so countdowns count playing frames only
   (§2.5) and survive a pause; the engine counts its own output frames (256 per render) and treats
   `blockStartFrame` as informational.
2. **"Not yet heard" = before the sampled onset.** PAUSE/SEEK/SET_PERF/SET_BANK/RATE send to IDLE,
   without a fade, every voice whose onset frame has not been reached (not only PENDING ones): a
   voice that started reading its pre-onset frames 1–95 frames before the command would otherwise put
   its attack inside the fade (T2.7 at 1–255 frames). A note-on has "taken effect" once its key-down
   (its event frame) has passed; the skip set holds those notes at or after the rewind index. For a
   harpsichord note paused between its 4′ and 8′ onsets the 4′ fades with the others and the note is
   replayed on resume.
3. **Late dispatches skip in.** A voice dispatched after its start frame (resume, seek, pending-list
   retry, or a rate that makes `k < onsetOut`) starts at the block with its read position advanced by
   the missing frames × rate, so the onset still lands exactly; a pending-list entry more than 50 ms
   late is dropped and counted.
4. **RATE also rewinds** (like PAUSE, without a fade): pending voices were scheduled at the old rate.
5. **Key-down is a queued state event** at the note's event frame: `KeyState.noteOn`, the re-strike
   fades (τ by the pedal at that block) and the soft-feed flag happen when the note sounds, not up to
   1,280 frames early at dispatch. Voices get no damping before their note's key-down block (they
   would otherwise be damped in the pre-roll by D = 1 of a key not yet down).
6. **A landing inside a block** starts the gain ramp and any low-pass change at its own frame
   (T2.3's ±1 frame at every tempo, with the spectral stage on).
7. **Releases and noises start on their recorded onset** (`skipFixed = onsetFrame`) at the landing /
   event frame; their pre-onset frames are not played.
8. **Handoff = 30 ms equal-power crossfade** (sustain cos, release sin from the quarter-sine table),
   release level-matched by `levelDb(loudest sustain voice of that key and stop) − envDb(release,
   onset window)`, clamped −30…+6 dB; the sustain voices are not damped during it.
9. **Pause fades are transport fades** kept apart from the voice's own level: `levelDb` excludes them
   (a frozen voice is neither culled nor stolen for being faded by the pause).
10. **Scratch passes instead of loop copies.** Each kernel writes the voice into a 256-frame scratch;
    the low-pass, the bus mix and the self row are separate passes (the spectral and self-row branches
    are still not per-sample tests). The bench measures the result.
11. **Tables.** lin2db uses the float's exponent plus a 257-entry mantissa table; db2lin a quarter-dB
    table over −160…+40 dB (801 entries); both are within 0.001 dB, finer than the "256 + 256" wording.
12. **T2.2 step test** runs the storm on a private smooth bank (7 Hz waves, 5 ms raised-cosine attacks,
    −24 dBFS): the SineBank's 8-harmonic waves have natural sample steps far above 0.02 FS at full
    level, so only a steal click could exceed 0.02 FS there. The SineBank storm is also run (dropped,
    cap and per-key invariants) and written to `build/renders/storm64_cap64_sine.wav`.
13. **T2.10 tolerance for HotSpot.** A 10 s window may show a one-off of a few hundred bytes charged when
    HotSpot recompiles a hot method; the test then measures a second 10 s window, which must be 0, and
    the first must be < 4 KiB (a per-block allocation would be ≥ 10 kB). Seen once at cap 128 (272 B).
14. **Noise kills count in `stolen`**; a noise evicted when all 12 slots are busy uses a kill slot
    (limited to cap/2 like the others).
15. **`debugOnset`** arms on a note that starts with nothing else sounding (SYNC_CLICK): scheduled =
    the expected −40 dB crossing (onset + (thrFrame − onsetFrame)/rate), detected = the first output
    sample above 1 % of the expected peak.
16. **Cmd.BENCH** runs in ≈ 2 ms slices per block and restores the resonance mode afterwards; the
    results are on `EngineCore.bench` (see `docs/requests/WP4.md`).
