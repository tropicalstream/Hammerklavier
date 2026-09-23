# WP4 wiring (wp4-audio)

## Swap
- `Wiring.kits`: `StubKits(post)` → `audio.KitManager(ctx = app, voicer = voicer, loader = loader)`
- `Wiring.audio`: `NullAudio()` → `audio.AudioOutput(ctx = app, core = SineCore(), cursors = cursors, head = head, settings = settings)`
  until WP2 merges, then `core = engine.EngineCore(dsp = DspFactory.create(HK.SR), cursors = cursors, head = head)`.
  `cursors` (VoiceCursorBoard) and `head` (HeadPose) are the Wiring singletons shared with the engine.

## Exact Wiring.kt edit
```kotlin
import com.tropicalstream.hammerklavier.audio.AudioOutput
import com.tropicalstream.hammerklavier.audio.KitManager
import com.tropicalstream.hammerklavier.contract.stub.SineCore        // until WP2's EngineCore merges

val kits: KitService = KitManager(app, voicer, loader)
val audio: AudioControl = AudioOutput(app, SineCore(), cursors, head, settings)
```
Drop the `NullAudio` / `StubKits` imports. `cursors`, `head` and `settings` must be declared before `audio` (they are, on main).

## AppController / HammerklavierApp (main has none of these yet)
- AppController init: `w.audio.setListener(listener)` with onEnded → SessionController end handling (then `w.audio.pause()`),
  onEngineError → UI error toast/state, onRouteChanged → debug line, onOverload → log.
- DebugControl: `"bench"` extra → `w.voicer.execute { (w.kits as? KitManager)?.decodeBench() }` and `w.audio.bench(2)`;
  `"lowlatency"` extra → `w.settings.putBool("audio.lowLatency", b.getBoolean(k))` before `w.audio.start()`.
- HammerklavierApp: no change (Wiring is still built once there).

## Hook-ups
- `AudioOutput.start()` / `stop()` follow the §1.10 lifecycle (main thread). `stop()` joins ≤ 350 ms.
- `AudioOutput.setListener(...)`: onEnded, onOverload, onEngineError (AUDIO_UNAVAILABLE / AUDIO_STOPPED), onRouteChanged, all on main.
- Settings keys (SettingsStore): `audio.lowLatency` (bool, false; DebugControl writes it for `--ez lowlatency true`, read at start()),
  `audio.latFrames.speaker|wired|bluetooth` (int, measured latency allowance, written at stop()).
- KitManager keeps its own SharedPreferences file `hk_kits` (DecoderProbe offset per Build.FINGERPRINT).
- `--ez bench true`: call `KitManager.decodeBench()` on HKVoicer (asset `instruments/stub/u/bench.opus`); the result is in `kits.diagnostics()["decodeBench"]`.
- `PlaybackService.start(ctx, title)` / `stop(ctx)`: no-ops while `HK.USE_FG_SERVICE = false` (manifest entry already present, disabled).

## Assets expected (WP11)
- `assets/instruments/<grand|upright|harpsichord>/{map.json, env.bin, u/<unit>.opus}`, `assets/instruments/stub/` (same layout),
  `assets/instruments/probe.opus`, `assets/instruments/stub/u/bench.opus` (all present on main). Without them: stub bank, then SynthBank stand-in tones.

## Smoke
- `tools/gw :core:test --tests 'com.tropicalstream.hammerklavier.kit.*'` and
  `tools/gw :app:testDebugUnitTest --tests 'com.tropicalstream.hammerklavier.audio.*'` green.
- Device (M1): `--es play synth:scale` sounds (SynthBank or stub bank); logcat `HKKit` shows the probe offset and per-unit
  voicing speed; the debug line shows AudioStats from AudioOutput (bufferFrames, voiceCap, parked after 10 s paused).
