# WP3 wiring (wp3-dsp)

## Swap
- `Wiring.designer`: `StubRoomDesigner` → `com.tropicalstream.hammerklavier.dsp.RoomAcoustics` (a Kotlin `object`,
  no constructor; implements `RoomDesigner`). Replace the `StubRoomDesigner` import with
  `import com.tropicalstream.hammerklavier.dsp.RoomAcoustics`.
- The `DspSet` handed to the engine: wherever `PassThroughDsp.create(...)` is used (today only inside the WP4/WP2
  construction of `Wiring.audio`; `NullAudio()` has none), use
  `com.tropicalstream.hammerklavier.dsp.DspFactory.create(sampleRate = HK.SR)`, i.e.
  `audio.AudioOutput(ctx, core = engine.EngineCore(dsp = DspFactory.create(HK.SR), cursors, head), cursors, head, settings)`.
  One `DspSet` per `EngineCore`; it is created on the loader/main thread (it allocates its delay lines, ≈ 0.5 MB),
  then only ever called on HKAudio. Nothing else in `Wiring` changes.

## Hook-ups
- None new in `AppController` / `HammerklavierApp`: the engine (WP2) already calls `setDesign`, `setLines`,
  `setInputGain`, `ResonanceProcessor.configure/process`, `SoftBusProcessor`, `MasterProcessor` through the
  contract interfaces. `AppController` keeps calling `wiring.designer.design(...)` on a listener/venue/mode change
  and posting the `RoomDesign` to the engine exactly as with the stub (the real designer is pure and cheap,
  < 1 ms, and may run on main).
- No Settings keys, manifest, permission, asset or build changes.

## Smoke
- JVM: `tools/gw :core:test --tests 'com.tropicalstream.hammerklavier.dsp.*'` all green (T3.1–T3.5). With the real
  regions: `python3 tools/pipeline/export_test_regions.py --out <dir>` then
  `HK_REAL_REGIONS=<dir> tools/gw :core:test --tests '*CombCalibrationTest*'` (prints `T3.1 target 1..3 (real v10|v13)`).
- Glasses (integrator, M1/M2): play `asset:midi/test/…` or Bach 846 with Room mode Room and resonance Natural; audible
  room tail after a staccato chord, pedal-down wash, no clicks on a listener change; Resonance Off → dry. EngineBench
  T-CPU with the real DspSet (88 combs ≈ 400 ns/frame on the JVM host; budget per §3.16) and L-2 speaker route.
