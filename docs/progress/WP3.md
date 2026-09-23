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

After merging main: `tools/gw :core:test`: BUILD SUCCESSFUL, 108 tests, 0 failed, 0 skipped when run with
`HK_REAL_REGIONS` set; no `@Ignore` left. Numbers printed by the tests:
- T3.1 calibration, real regions (`export_test_regions.py` run from the main checkout's download cache into a
  scratch dir, passed via `HK_REAL_REGIONS`): target 1 C5 comb −25.8 (v10) / −25.6 (v13) dB (−28 ± 4); target 2
  una corda −10.6 dB (−12 ± 4); target 3 C3 comb −37.1 / −37.0 dB re C4 peak (≥ −40); target 4 passes (4 s).
- T3.1 calibration, SineBank voices: target 1 −35.3 dB (expected −35 ± 4, see deviation 13); target 2 −14.2 dB;
  target 3 −32.8 dB; target 4 passes (10 s), pedal up and down.
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

13. **Real-region calibration** (T3.1 second pass): real Salamander C4 has partial 2 at +8…+9 dB re the
   fundamental (SineBank: −6 dB), so the C5 comb rang at −18.5 dB. Fix: a register tilt on the comb input,
   0 dB up to key 60 and −0.6 dB/key above (floor −12 dB) (`DspTables.COMB_TILT`), and `UNA_CORDA_TRIM_DB`
   −12 → −14. A global trim cannot satisfy both target 1 (needs −9 dB) and target 3 (3 dB slack); lifting the
   lower combs broke target 4 (beating). The SineBank target 1 therefore expects −35 ± 4 (the proxy's weak
   second partial), the real regions keep the plan's −28 ± 4.
14. **`realRegions` test source**: `src/test/resources/wp11/real/` if WP11 commits it, else `$HK_REAL_REGIONS`;
   without either it is an assumption skip (not `@Ignore`). Target 4 on real regions runs over the 4 s the
   export holds (a truncated recording's comb tail beats after the cut), not 10 s.

## Remaining

- None in WP3's JVM scope. WP11 should commit the `export_test_regions.py` output to `wp11/real/` so the
  real-region pass runs in every clone (requested).
- Device checks at M1/M2/M5 (T-CPU via EngineBench, L-2, L-7) belong to the integrator.
