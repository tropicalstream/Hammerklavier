# WP1 wiring (MIDI and performance)

WP1 delivers one class that the app sees: `com.tropicalstream.hammerklavier.midi.ScoreCompilerImpl` (in `:core`,
pure Kotlin, implements `contract.ScoreCompiler` exactly as frozen: `sniff`, `inspect`, `compile`, `synthetic`).
Constructor `ScoreCompilerImpl()`, no arguments, no Android, stateless apart from reusable scratch, never throws.
It may be called from any thread; calls on one instance must not overlap (use it from `loader` only, or make one
instance per thread).

## Changes WP0 makes

### `app/src/main/java/com/tropicalstream/hammerklavier/Wiring.kt`
1. Replace
   ```kotlin
   import com.tropicalstream.hammerklavier.contract.stub.StubScoreCompiler
   ...
   val compiler: ScoreCompiler = StubScoreCompiler()                    // WP1: midi.ScoreCompilerImpl()
   ```
   with
   ```kotlin
   import com.tropicalstream.hammerklavier.midi.ScoreCompilerImpl
   ...
   val compiler: ScoreCompiler = ScoreCompilerImpl()
   ```
   Keep `StubScoreCompiler` in `contract.stub` (tests and the stub library still use it).
2. When WP9 lands, `LibraryServiceImpl(ctx, compiler)` receives this same instance. Until then `StubLibrary` does
   not call the compiler, so nothing else changes.

### `AppController`
- No signature changes. Every place that turns a movement into a `Performance` must call
  `wiring.compiler.compile(bytes, movementId, seed, profile, options)` **on the `loader` executor** (a 20k-note
  score compiles in < 60 ms on the JVM; allow ~3x on the AR1) and post the `CompileResult` to main.
  `CompileResult.Failed(reason, detail, byteOffset)` goes to the UI as the rejection toast; never retry it.
- Instrument changes recompile from the same bytes with the new `InstrumentProfile` (folds, legato hold and voice
  demand depend on it); do not reuse a `Performance` across instruments.
- `synthetic(kind, profile, seed)` is safe to call directly for the diagnostic / bench scores.

### `HammerklavierApp`
- Nothing. The compiler has no global state and needs no init.

## Behaviour the integrator should know
- `ScoreFacts.durationSec` = last key-up in file time, rounded to 1 ms, **excluding** pre-roll and the 1.5 s
  release tail (matches WP11's `durationSec` in `catalog.json` and `midi_facts_golden.json`).
  `Performance.durationUs` still includes pre-roll and tail (playback end).
- Deviations from plan text are listed in docs/progress/WP1.md (switch-mode partial pedal values, drums-only rule,
  zero-length notes, etc.).

## Smoke steps
1. JVM: `tools/gw :core:test --tests 'com.tropicalstream.hammerklavier.midi.*'` -> all pass, 0 skipped
   (includes T1.7 against WP11's golden facts and T1.8 against the bundled twins).
2. `tools/gw :app:assembleDebug` after the Wiring.kt edit -> builds (no new dependencies: `:app` already depends on `:core`).
3. Device (integrator, milestone): launch, open Test piano -> Format pair -> I. Format 1. The position readout runs
   0:00 -> 0:16.8 and stops ~1.5 s later; the sustain pedal indicator goes down and up; no rejection toast.
4. Open `midi/test/storm64.mid` (46,080 notes): the loading spinner shows < ~0.5 s, playback starts, no ANR.
5. Push a non-MIDI file named `x.mid` with `tools/device/push_scores.sh`: the importer lists it under rejected
   with `NOT_MIDI`, and the app keeps running.
6. Switch the instrument to harpsichord on `scale.mid`: notes below 29 / above 89 are folded into the compass
   (27 folds per the golden facts), no stuck notes at the end.
