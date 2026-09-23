# WP4 progress (Audio I/O, clock publishing, kits and storage)

Branch `wp4-audio` (from contracts-v1), worktree `/Users/me/Projects/hk-wp4`. Plan: §3.1–§3.5, §3.19, §6.6, §7.2 WP4.

## Done
- **core `kit/*` (pure):** `KitIndex` (+ RegionDef, LayerDef, StopDef, UnitDef, LevelPoint, ReleaseRule, RegionKind),
  `KitMapCodec` (every §6.6 rule, never throws, reasons listed), `KeyMapBuilder` (§3.5: frequency-based region choice,
  borrowing with seam trims, HARD/XFADE with gain/power law, level-curve trims, not-ready substitution ±6 dB clamp, releases,
  pedals, f0Hz, onsetOut), `PcmCacheFormat` (HKPCM2 header, 4 KiB alignment, `.ready` = mask + 64 CRC32 + BOOT_COUNT with
  atomic replace, unit CRC and verification), `SampleStore` (read-only mmap for HKAudio, positional 64 KiB reads for prefetch,
  slow-read counter), `MappedBank`, `SynthBank` (deterministic additive stand-in described as a KitIndex, so KeyMapBuilder maps it),
  `DecodePlan` (order, resume, CRC verification plan, storage check, reduced grand, stale-file list).
- **app `audio/*`:** `AudioOutput` (AudioControl; HKAudio loop, CommandRing, clock/energy publishing, estimate until the first
  timestamp, latency allowance per route, idle zeros without DSP, park/unpark, onEnded once per generation, render-exception
  guard, headroom self-protection via Cmd.VOICE_CAP, silent FakeClock mode when no track, seqlocked stats, replay on restart),
  `TrackSupervisor` (+ OutputSink, AudioTrackSink, RenderGuard, HeadroomGuard), `LatencyTuner`, `RouteMonitor`,
  `AudioFocusGate`, `Prefetcher` (HKPrefetch), `KitDecoder` (+ DecodeResult), `DecoderProbe`, `VoicingScheduler`,
  `KitManager` (KitService), `PlaybackService` (disabled).
- docs/wiring/WP4.md, docs/requests/WP4.md.

## Tests (2026-09-22)
- `tools/gw :core:test --tests 'com.tropicalstream.hammerklavier.kit.*'`: 73 tests, 0 failures, 3 skipped
  (`@Ignore("needs wp11 fixture")`: map_fixture parse, bad fixtures, the worked example on the WP11 fixture).
  T4.1 KitMapCodecTest (41), T4.2 KeyMapBuilderTest (15), T4.3/T4.4 CacheAndStoreTest (10), T4.5/T4.6 SynthBankAndPlanTest (7).
  The same properties run on WP4's private `ToyKit` generator (fixture-like 58/60/62 kit, 16-layer HD grand, 6-layer XFADE grand
  with gain and power laws, harpsichord 8′+4′ with borrowable 74/76), each recorded at A440 and A415.
- `tools/gw :app:testDebugUnitTest --tests 'com.tropicalstream.hammerklavier.audio.*'`: 17 tests, 0 failures
  (AudioOutputTest 9 on an in-memory sink with SineCore: onEnded exactly once, park and unpark, timestamps, DEAD_OBJECT rebuild,
  output lost after 3 rebuilds, silent mode, render exception, stop/start restores the paused position, zero allocation on
  HKAudio over 2000 blocks; PoliciesTest 8).
- `tools/check_purity.sh` OK; `:app:assembleDebug` builds.

## Remaining
- Un-ignore the three WP11-fixture tests when `core/src/test/resources/wp11/` lands (map_fixture.json, env_fixture.bin, bad/*.json).
- Device checks (not allowed in this stage): M1, decode bench, T-DEC, T-ALIGN, T-RESUME, T-PF, T-CLOCK, route switch,
  focus duck, `--ez lowlatency true`, DecoderProbe offset, T-UND on Bluetooth (M3).

## Decisions and deviations
- **Harpsichord shift bound across pitch standards:** T4.2's "≤ 1 semitone for every 8′ key" is tested at the recording's own
  standard; at the other standard key 89 would need a 4′ root 77 (the fixture has 74/76), so only the grand's ≤ 1.5 bound is
  tested across standards (+1.3 cent slack for the −101.27 cent offset).
- **Velocity arrays use stop 0's level curve** (KeyMap has one set of velocity arrays); substitution by readiness uses stop 0.
- **Voicing of the first playable set ignores the Q0/battery gate** (nothing can play without it); everything after it obeys
  the gate (VoicingScheduler).
- **Reduced grand** keeps units labelled v4 and v13 plus releases and pedals (otherwise nothing is playable); the bank reports
  `FallbackReason.LOW_STORAGE`. If even that does not fit, SynthBank(LOW_STORAGE).
- **Missing probe asset** → offset 0 assumed (logged), not a failure, so early builds without WP11 assets still voice.
- **AudioOutput has an internal test constructor** (sink factory, post, no Handler) beside the frozen public one.
- **Stats cross threads** as a seqlocked AtomicIntegerArray published every 16 blocks (rule 2 (e) spirit, data-race-free).
- **KitService.release** drops KitManager's reference at once; the engine/prefetcher keep theirs until replaced (no unmapping).
- The HKAudio time source is a primitive `NanoSource` (a `() -> Long` boxed and allocated on every call).
