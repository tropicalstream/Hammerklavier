# Requests from WP3

1. **Plan owner (§3.11, §7.2 T3.1):** the comb sends needed calibration trims (−37 dB on SEND,
   −12 dB on UNA_CORDA_SEND) to meet the §3.11 targets; please record the effective values
   (grand Natural ≈ −67 dB re the mix, una corda ≈ −60 dB) or confirm the trims approach.
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
