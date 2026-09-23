# WP4 requests

## WP11 (assets)
- Name the M1 decode-bench stream `assets/instruments/bench.opus` (60 s, kit encoding); KitManager.decodeBench() reads it.
- Units' `label` for the grand kits must be `v1`..`v16` (DecodePlan's reduced grand keeps the units labelled `v4` and `v13`).
- For the harpsichord 4′ stop, `regions[].pitchCents` is the measured sounding pitch re 100·root including the octave
  (KeyMapBuilder uses nativeCents = 100·root + pitchCents as the sounding pitch and targets key + 12 for a stop named `4'`).

## WP0 (wiring / DebugControl)
- See docs/wiring/WP4.md. DebugControl: `--ez lowlatency true` → `settings.putBool("audio.lowLatency", true)` before `audio.start()`;
  `--ez bench true` → `kits.decodeBench()` on the voicer executor (cast Wiring.kits to KitManager).
