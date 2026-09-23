# WP3 progress (DSP, pure)

Branch `wp3-dsp` (from `contracts-v1`), worktree `/Users/me/Projects/hk-wp3`. Plan: §7.2 WP3, §3.11–§3.13.

## Done

All files of the §2.2 WP3 rows, package `com.tropicalstream.hammerklavier.dsp` (`core/src/main/java/.../dsp/`):

- `DspTables` — SEND / UNA_CORDA_SEND (plan dB values) plus calibration trims, 1024-entry sine
  table, dB↔linear tables, one-pole coefficient table, Padé `softClip`.
- `Biquad` (`BiquadCoefs` RBJ designs + TDF-II state), `OnePole`, `DcBlocker`.
- `ResonanceBank : ResonanceProcessor` — 88 combs, packed power-of-two delay lines (88,576 floats
  = 346 KiB at 48 kHz, see deviations), fraction allpass + two dispersion allpasses + loop
  low-pass, gain table G[88][33] per variant, 200 ms retune glide, active set with `maxActive`
  (held keys first, then loudest), 4-way kernel (`processFour`) + scalar reference, energy lanes
  (every 8th sample, halved, added as mean-square).
- `RoomAcoustics : RoomDesigner` (object) — Sabine per band, critical distance, DRR, 12 image
  sources, direct gain/air/width, embedded-room compensation, modes (Dry −6, Room 0, Resonant +3
  and T60 × 1.2). Reproduces `FixedRoom.PLAYER` (test).
- `DirectPath` (+ `Glide`), `EarlyReflections` (two 12-tap sets crossfaded, pre-delay
  crossfaded), `FdnReverb` (8 lines, Hadamard, 3-band Jot loss, lines 2/5 modulated, energy
  normalised, 4-line Q3 mode), `RoomChain : RoomProcessor`.
- `SoftBus : SoftBusProcessor`, `SpeakerEnhancer`, `Limiter`, `MasterChain : MasterProcessor`.
- `DspFactory.create(sampleRate): DspSet`.

Tests (`core/src/test/java/.../dsp/`): T3.1 `ResonanceBankTest` + `CombCalibrationTest`, T3.2
`RoomAcousticsTest`, T3.3 `RoomChainTest`, T3.4 `MasterChainTest`, T3.5 `DspAllocAndBenchTest`.
Private helpers `DspTestUtil`, `CombRig`.

## Test results (2026-09-22)

`tools/gw :core:test`: BUILD SUCCESSFUL, all green; 1 skipped (`CombCalibrationTest.realRegions`,
`@Ignore("needs wp11 fixture")`). Numbers printed by the tests:
- T3.1 calibration (SineBank-recipe voices): target 1 C5 comb −28.1 dB (−28 ± 4); target 2 una
  corda −12.2 dB (−12 ± 4); target 3 C3 comb −32.8 dB re C4 peak (≥ −40); target 4 C3 comb silent,
  envelope monotone, pedal up and down.
- T3.3: Schroeder mid 1.578 s (design 1.649, −4%), 8 kHz 0.930 s (design 0.928); energy
  normalisation −0.06 … −0.70 dB for T60 0.8–2.5 s, 8 and 4 lines; listener switch residue
  −109 dBFS (hard cut −42.6 dBFS, so the probe sees steps); flatness 7.3 dB (smoothed, see below).
- T3.5 JVM host bench (build/dsp_bench.txt): 88 combs ≈ 400 ns/frame, RoomChain ≈ 90,
  SoftBus ≈ 13, MasterChain ≈ 55.

## Decisions and deviations (with reasons)

1. **Comb input trims** (`DspTables.COMB_INPUT_TRIM_DB = −37`, `UNA_CORDA_TRIM_DB = −12`): the plan's
   SEND (−30/−24 …) and UNA_CORDA_SEND (−48) dB values are kept as the named constants and relative
   levels, but with them alone target 1 came out at ≈ −2 dB instead of −28 (the comb peak gain is
   +45…+60 dB). The trims were calibrated so that targets 1–3 hold on SineBank voices. Re-check with
   the WP11 real regions (T3.1 `realRegions`) and at M2/M5/L-7.
2. **Comb T60 law**: implemented as the geometric interpolation
   `T60(D) = (0.8·T60free)^(1−D) · (0.5·T60d)^D`; the §3.11 text `T60free·0.8^(1−D)·(T60d·0.5)^D`
   multiplies two times at D = 1 (dimensionally a s², ≈ 0.8 s² at C4), read as a typo.
3. **Loop gain compensates the loop low-pass at f₁** (g = 10^(−3N/(fs·T60)) / |H_lp(f₁)|, still
   ≤ 0.9995), so the fundamental decays at the table T60.
4. **Dispersion allpasses always run** (coefficient 0 = two unit delays, included in the fit), so
   the 4-way kernel is branch-free; dispersion is fitted only for keys ≤ 59 with B > 0 (variant 0)
   as the plan says; variant 1 (Q2 / `dispersion = false`) caps the loop low-pass at n_max·f0.
   The comb's M, η and dispersion coefficient are fitted numerically on the exact loop phase.
5. **Delay-line budget**: segments are sized for A392 with −100 cents of stretch plus a block of
   headroom (safe for A415 and stretched bass), which is 346 KiB, not the §3.11 ≈ 250 KiB
   (that figure assumed ≈ N + 256 per comb at A440).
6. **FDN modulated reads use a first-order allpass interpolator**, not linear interpolation: linear
   interpolation lost up to 3 dB per pass at high frequency, which biased the energy normalisation
   by −1…−3 dB and the 8 kHz T60 by −8%.
7. **T3.3 flatness criterion**: an 8-line FDN's steady-state response has Rayleigh-distributed bin
   powers (mode spacing ≈ 3.7 Hz vs bandwidth ≈ 1.3 Hz); raw 1 Hz bins are ≈ 9–10 dB above their
   third-octave mean with or without modulation (measured: 13 dB unmodulated, 10 dB with lines
   2/5, 8.5 dB with all 8 lines modulated). The test smooths the averaged 16 × 1 s spectrum over
   ±4 bins and requires ≤ 8 dB (measured 7.3). Plan owner to confirm (docs/requests/WP3.md).
8. **SoftBus single-strung class** (§3.8, −1.5 dB on keys 21–28) cannot be separated with one soft
   stereo pair in the contract; the whole bus uses the ≥ 2-string shelf (request filed).
9. **Harpsichord SEND** is −34 dB in both Natural and Rich (the plan gives one value).
10. Una corda feed is enabled only for the grand (the upright's hammer rail has no comb feed, §3.8).
11. T3.3 listener-switch "step" is measured as the > 12 kHz residue of the output for 800 Hz
    low-passed noise input (a step shows as broadband energy); glide 500 ms.
12. A new `setDesign` during an ER crossfade restarts it from the set being faded in (the older
    set is dropped); listener changes are ≥ 0.5 s apart in the UI (dip transition).

## Remaining

- `CombCalibrationTest.realRegions`: waits for WP11's `core/src/test/resources/wp11/real/`
  (C3/C4/C5 v10/v13, una corda C4); then re-run targets 1–4 on them and retune the trims if needed.
- Device checks at M1/M2/M5 (T-CPU via EngineBench, L-2, L-7) belong to the integrator.
