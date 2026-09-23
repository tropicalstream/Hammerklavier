# WP2 wiring: EngineCore

WP2 is pure (`:core`, package `com.tropicalstream.hammerklavier.engine`). It has no Android entry point of its
own: the engine is constructed by the app and handed to WP4's `audio.AudioOutput`, which owns HKAudio.

## What WP0 changes

1. `Wiring.kt`: add the import `com.tropicalstream.hammerklavier.engine.EngineCore` and construct one engine per
   process, next to `cursors` / `head`:

   ```kotlin
   val dsp: DspSet = PassThroughDsp.create()            // WP3: dsp.DspFactory.create(HK.SR) once WP3 merges
   val engine: EngineCoreApi = EngineCore(dsp, cursors, head, HK.SR)
   ```

   Until WP4 merges, `audio` stays `NullAudio()` (nothing drives the engine). When WP4 merges, replace
   `NullAudio()` with `audio.AudioOutput(app, engine, cursors, head, settings)` — i.e. pass `engine` where
   WP4's stub-era wiring passed `SineCore(...)`. No other file names `EngineCore`.
2. `AppController` / `HammerklavierApp`: no change. They talk only to `AudioControl` / `KitService`; the engine is
   reached through the command ring. Bank and key-map tokens come from `EngineCore.prepareBank` /
   `prepareKeyMap`, called by WP4's KitManager on HKVoicer (see `docs/requests/WP4.md`).
3. `Cmd.BENCH` results are not yet on the frozen `EngineCoreApi`. A contract-change request (bench
   results on `diagnostics()` / `AudioStats`) is recorded in `docs/requests/WP4.md`; until WP0 accepts it,
   WP4 must not hard-cast `engine as EngineCore`. The `--ez bench true` path is optional and may use only a
   safe `(engine as? EngineCore)?.bench`, reporting "bench unavailable" when a SineCore or wrapper sits in the slot.

## Smoke steps

1. `tools/gw :core:test` — 131 tests, 0 skipped (T2.1–T2.12 all run; renders in `core/build/renders/`).
2. `tools/check_purity.sh` — OK.
3. `tools/gw :app:assembleDebug` after the Wiring edit — builds.
4. With WP4 merged, on device (integrator, M1): play the demo score; hear sampled notes, not SineCore sines;
   pause/resume fades in 60 ms without clicks; seek lands on the note; `adb shell am start ... --ez bench true`
   logs ns/voice-frame and the chosen cap (64..128); logcat shows `dropped=0` for `storm64`.
