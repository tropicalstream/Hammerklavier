# Wiring notes

`app/src/main/java/com/tropicalstream/hammerklavier/Wiring.kt` is the one place that constructs a
real or stub component (PLAN §2.2, §7.1 rule 5). Only WP0 edits it, `MainActivity.kt`,
`AppController.kt` and `HammerklavierApp.kt`. When a WP is ready to merge it ships
**`docs/wiring/WP<N>.md`** on its branch, and the integrator applies exactly that.

## What a wiring note contains

```markdown
# WP<N> wiring (<branch>, <commit>)

## Swap
- `Wiring.<field>`: `<Stub>(…)` → `<RealClass>(<frozen constructor of PLAN §2.3, named arguments>)`

## Hook-ups
- listeners, callbacks, lifecycle calls AppController/MainActivity must add (thread, order)
- Settings keys read or written (key, type, default)
- manifest, permission, asset or build changes (if any)

## Smoke
- the CONTROL / adb steps that prove it on the glasses, and the log lines that must appear
- the JVM tests that must be green (`tools/gw :core:test --tests …`)
```

Keep it to what the integrator must do; design notes belong in `docs/progress/WP<N>.md`.

## Current wiring (contracts-v1)

| `Wiring` member | Now | Real class (WP), frozen constructor (PLAN §2.3) |
|---|---|---|
| `settings` | `MemSettings()` | `system.Settings` (WP0, contracts-v1.1) |
| `compiler` | `StubScoreCompiler()` | `midi.ScoreCompilerImpl()` (WP1) |
| `kits` | `StubKits(post)` | `audio.KitManager(ctx, voicer, loader)` (WP4) |
| `audio` | `NullAudio()` | `audio.AudioOutput(ctx, core = engine.EngineCore(dsp = dsp.DspFactory.create(HK.SR), cursors, head), cursors, head, settings)` (WP4 + WP2 + WP3); `SineCore()` in place of EngineCore until WP2 merges |
| `library` | `StubLibrary(readAsset, scoresDir)` | `library.android.LibraryServiceImpl(ctx, compiler)` (WP9) |
| `designer` | `StubRoomDesigner` | `dsp.RoomAcoustics` (WP3, an object) |
| `scenes` | `StubScenes()` | a `SceneFactory` wrapping `instrument.Instruments.create(id, look, lastDamper)` and `venue.VenueSceneImpl()` (WP7, WP8) |
| `ui` | `StubUi()` | `ui.model.UiStateMachineImpl()` (WP10) |
| `mechanics()` | `StubMechanics()` | `mech.MechanicsEvaluatorImpl()` (WP5) |
| `glHost(ctx, msaa)` | `StubGlHost(ctx)` | `render.HkGlView(ctx, loader, msaa)` (WP6) |
| `overlay(ctx)` | `StubOverlay(ctx)` | `ui.OverlayViews(ctx)` (WP10) |
| (AppController drives the stubs) | — | `session.SessionController(audio, render, kits, library, compiler, designer, scenes, settings, loader, post, nowMs)` (WP12); `AppController` then only forwards |
| — | — | `companion.CompanionServer(port = HK.COMPANION_PORT, token, library, model, commands, page, post)` (WP9) |

Shared singletons `Wiring` will own from contracts-v1.1: one `VoiceCursorBoard()` and one
`HeadPose()` (passed to EngineCore, AudioOutput and GazeCamera), the HKLoader and HKVoicer
executors (already there), `post` (main `Handler.post`).
