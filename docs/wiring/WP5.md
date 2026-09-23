# WP5 wiring · Mechanism animation

The real evaluator is `com.tropicalstream.hammerklavier.mech.MechanicsEvaluatorImpl` in `:core`; it implements
`contract.MechanicsEvaluator` with no constructor arguments, so the swap is one line.

## Wiring.kt
- Replace `import com.tropicalstream.hammerklavier.contract.stub.StubMechanics`
  with `import com.tropicalstream.hammerklavier.mech.MechanicsEvaluatorImpl`.
- Line 60: `fun mechanics(): MechanicsEvaluator = MechanicsEvaluatorImpl()`.
  Keep it a factory (a new instance per GL view): the evaluator holds GLThread-owned cursors and must not be shared.

## AppController / HammerklavierApp
- No change. `AppController` already calls `gl.bind(w.audio.clock, w.audio.energy, w.mechanics(), w.scenes)`;
  the GL host calls `bind(performance, profile)` on a score/instrument change and `evaluate(vt, energy, dtSec, pose)`
  every frame, both on the GL thread. `bind` allocates (cursor tables); `evaluate` allocates nothing.
- `exposureEnabled` defaults to true; leave it (only T5.10 turns it off).

## Smoke steps
1. `tools/gw :core:test --tests 'com.tropicalstream.hammerklavier.mech.*'` → 36 tests, 0 skipped, 0 failed.
2. `tools/gw :app:assembleDebug` builds.
3. On device (integrator, M3/M4): load `synth:repeat15` on the grand, Player view: keys dip ahead of the sound and
   return; Action view: hammer 60 flashes once per note (15 strikes), dampers drop on release; pause mid-note →
   pose frozen, no flash; seek → no spurious flash on the first frame.
4. Switch to upright and harpsichord (M7): jacks rise and pluck, cloth dampers settle ~48 ms after release.
