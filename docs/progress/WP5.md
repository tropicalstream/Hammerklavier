# WP5 · Mechanism animation (pure) — progress

Branch `wp5-mech` (from `contracts-v1`). Package `com.tropicalstream.hammerklavier.mech` in `:core`.

## Done
- `Touch.kt` — Goebl/Askenfelt fits as 128-entry tables (HV, s, tt, tb, ff, contact c(n), analytic A0).
- `KeyCursor.kt` — `NoteCtx`, `FrameEnv`, `KeyAction` interface, `NoteTiming` (the §5.7 lead rule, d0 from a
  bounded 4-note chain so sequential == fresh exactly), `KeyCursors` (step forward/back, binary-search reseed).
- `GrandAction.kt`, `UprightAction.kt`, `HarpsichordAction.kt` — per-key pose formulas of §5.7.
- `PedalPose.kt` (pedal cursors, una-corda shift, hammer rail, sostenuto latch mask, registration),
  `StringVisual.kt` (energy → amp with a table log2; analytic two-stage envelope with an exp table; ribbon helpers),
  `ExposureSampler.kt` (cursor over onUs, contacts in (from, to], harpsichord 4′-only shift),
  `FocusTracker.kt`.
- `MechanicsEvaluatorImpl()` implements `MechanicsEvaluator`; `exposureEnabled` switch (T5.10).

## Tests (JVM, `tools/gw :core:test --tests 'com.tropicalstream.hammerklavier.mech.*'`)
36 tests, all pass, none ignored (after merging main / contracts-v1.1). T5.1 TouchTest; T5.2/T5.3 GrandActionTest; T5.4 HarpsichordActionTest;
T5.5 UprightActionTest; T5.6 StringVisualTest; T5.7 ExposureSamplerTest; T5.8–T5.11 + focus MechanicsEvaluatorTest.
T5.9 on this Mac: 0.03–0.05 ms per frame (88 keys, CHORD_STORM_64), zero allocation with escape analysis off.
`ExposureSamplerTest.centredWindowsFromVisualClock` now runs against v1.1's VisualClock and passes.

## Decisions / deviations
- Harpsichord cloth damper: lift = clamp((dip·6 − 1.5)/1.5, 0, 1) instead of the literal clamp(dip·6/1.5): only
  the offset form is 0 (touching) at off + 47.6 ms·r as §5.7, T5.4 and the audio's damping instant require.
- Harpsichord re-strike caught with the key still below the pluck depth (d0 ≥ 0.70, e.g. REPEAT_15): the key first
  rises to 0.60 (quill re-engages) over half the lead, then presses to 0.70 at `on`. §5.7 does not define this case.
- T5.10 for the harpsichord checks the pluck depth 0.70 at `on` (the jack is not at 1.0 at the pluck).
- Upright "80% return": a stroke starting with d0 > 0.2 is drawn as the fast-repetition approximation (from half
  the blow) even when slower than 143 ms.
- Keys above lastDamper report damper = 1 (never damping). Escape is 1 from tEscape until the key has returned half way.
- Analytic A0(v) = 10^((−40 + 34·v/127)/20) linear RMS, mapped through the same dB → amp curve as the lanes.
- strikeAge advances by dtSec each frame and is 0 on a drawn contact; 1e9 after a reseed.
- FocusTracker: "down" = governing note held; "struck" = last contact within 0.5 s of song time.

## Remaining
- None in code. Device checks (M3/M4/M7) are run by the integrator; wiring in docs/wiring/WP5.md.
