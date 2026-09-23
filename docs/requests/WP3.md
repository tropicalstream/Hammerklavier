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
