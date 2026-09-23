# Requests from WP3

1. **Plan owner (§3.11, §7.2 T3.1):** the comb sends needed calibration trims (−37 dB on SEND,
   −14 dB on UNA_CORDA_SEND) plus a register tilt (−0.6 dB/key above key 60, floor −12 dB) to meet
   the §3.11 targets; please record the effective values in §3.11 and T3.1 (grand Natural −67 dB,
   Rich −61, upright −69/−63, harpsichord −71, una corda −62 dB; asserted by
   `ResonanceBankTest.sendValuesAreThePlans`) and the SineBank target-1 relaxation (−35 ± 4), or
   confirm the trims approach. STILL OPEN (no §10 decision yet).
2. **Plan owner (§3.11):** confirm the comb T60 law reading `(0.8·T60free)^(1−D)·(0.5·T60d)^D`.
3. **Plan owner (T3.3):** the "no 1 s FFT bin > 6 dB above its ⅓-octave neighbourhood" criterion
   is not reachable by an 8-line FDN (Rayleigh statistics of the modal response); WP3 tests a
   ±4-bin-smoothed spectrum against 8 dB. Please confirm or give the intended measure.
4. **Plan owner (§3.12):** FDN modulated reads use allpass interpolation instead of linear (the
   linear read broke the energy normalisation by up to −3 dB).
5. **WP0 / WP2 (contract, optional):** §3.8 wants a separate −1.5 dB shelf for single-strung keys
   on the soft bus; `SoftBusProcessor.process` has one soft stereo pair. Either a second soft
   pair (single-strung keys) or accept one shelf class. WP3 currently applies the ≥ 2-string shelf.
6. **WP11:** `core/src/test/resources/wp11/real/` regions for T3.1 (§6.5 step 13): WP3 expects
   16-bit mono, 48 kHz; please document the file names and format there.

7. **WP11 (update 2026-09-22):** please commit `export_test_regions.py`'s output to `core/src/test/resources/wp11/real/`
   (7 WAV + `regions.json`, ≈ 2.7 MB). T3.1 `realRegions` reads it, or `$HK_REAL_REGIONS`, and passes on today's
   export (targets 1–4, v10 and v13).

8. **Plan owner (§3.17, §3.11):** the ResonanceBank delay array is 346 KiB (A392 −100 cents headroom),
   not ≈ 250 KiB, and the whole DspSet allocates ≈ 0.5 MB; please update the §3.17 memory budget.
9. **Plan owner / integrator (§3.16):** the 22-cycle-per-comb budget is unmeasured on the A55 (JVM
   host: ≈ 400 ns/frame for 88 combs). Please make the EngineBench comb measurement an M1 gate.
10. **Plan owner (§3.12, review 2026-09-22):** RoomChain now feeds the FDN without directGain (a
   second, ungained mono pre-delayed in EarlyReflections), so the late level is reverbGain at every
   seat as §3.12 says; `setDesign` also glides the FDN loop gains. No contract change.

## Integrator answers (M1, 2026-09-22)
- **9 (comb budget on the A55):** measured by EngineBench on A06B4A96A733283, release build, speed-compiled:
  `nsComb` 54–57 ns per comb-frame at a reported 1.8 GHz (≈ 100 cycles), and 93–95 ns in the device's slow
  state (see INTEGRATION.md, "bimodal speed"); dispersion off saves nothing measurable (54.6 vs 54.1 ns).
  §3.16's 22 cycles per comb is **not met** (≈ 4.5×). 88 combs cost ≈ 1.2 ms per 256-frame block at 1.8 GHz.
  Carried to M2 (T-CPU with combs, storm64 ≤ 42%); the step-down order should use these numbers.
  Also measured: `nsRoom` 1,161–1,240 ns per output frame (≈ 0.3 ms per block), `nsMaster` 389–393, `nsSoft` 48–52.
- M1 wiring uses the full `DspFactory.create(HK.SR)` (the plan's M1 row says limiter only; the wiring notes of
  WP2/WP3/WP4 all use the full set, and the bench needs the real stages). No room design is posted yet (WP12).

## Integrator answers (M2, 2026-09-23)
- **9 (comb cost):** on the glasses the 4-way kernel runs at 63–64 ns per comb-frame (2.0 GHz, fast state); ART
  spills its state. A 2-way kernel (`kernelWidth = 2`, now the default) runs at 40–42 ns, 1-way at 53 ns. Peak
  is now sampled every 8th frame like the energy. Still ≈ 80 cycles vs §3.16's 22; `nsRoom` is 1.6–1.9 µs/frame.
  T-CPU storm64 fails at 55–65 % normalised (INTEGRATION.md, M2).
