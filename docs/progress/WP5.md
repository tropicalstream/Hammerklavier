# WP5 · Mechanism animation (pure) — progress

Branch `wp5-mech` (from `contracts-v1`). Package `com.tropicalstream.hammerklavier.mech` in `:core`.

## Done
- `Touch.kt` — Goebl/Askenfelt fits as 128-entry tables (HV, s, tt, tb, ff, contact c(n), analytic A0).
- `KeyCursor.kt` — `NoteCtx`, `FrameEnv`, `KeyAction` interface, `NoteTiming` (the §5.7 lead rule, d0 from a
  bounded chain (see review fixes) so sequential == fresh exactly), `KeyCursors` (step forward/back, binary-search reseed).
- `GrandAction.kt`, `UprightAction.kt`, `HarpsichordAction.kt` — per-key pose formulas of §5.7.
- `PedalPose.kt` (pedal cursors, una-corda shift, hammer rail, sostenuto latch mask, registration),
  `StringVisual.kt` (energy → amp with a table log2; analytic two-stage envelope with an exp table; ribbon helpers),
  `ExposureSampler.kt` (cursor over onUs, contacts in (from, to], harpsichord 4′-only shift),
  `FocusTracker.kt`.
- `MechanicsEvaluatorImpl()` implements `MechanicsEvaluator`; `exposureEnabled` switch (T5.10).

## Tests (JVM, `tools/gw :core:test --tests 'com.tropicalstream.hammerklavier.mech.*'`)
38 tests, all pass, none ignored (after merging main / contracts-v1.1). T5.1 TouchTest; T5.2/T5.3 GrandActionTest; T5.4 HarpsichordActionTest;
T5.5 UprightActionTest; T5.6 StringVisualTest; T5.7 ExposureSamplerTest; T5.8–T5.11 + focus MechanicsEvaluatorTest.
T5.9 on this Mac: 0.03–0.05 ms per frame (88 keys, CHORD_STORM_64), zero allocation with escape analysis off.
`ExposureSamplerTest.centredWindowsFromVisualClock` now runs against v1.1's VisualClock and passes.

## Decisions / deviations
- Harpsichord cloth damper: lift = clamp((dip·6 − 1.5)/1.5, 0, 1) instead of the literal clamp(dip·6/1.5): only
  the offset form is 0 (touching) at off + 47.6 ms·r as §5.7, T5.4 and the audio's damping instant require.
- Harpsichord re-strike caught with the key still below the pluck depth (d0 ≥ 0.70, e.g. REPEAT_15): the key first
  rises to 0.60 (quill re-engages) over half the lead, then presses to 0.70 at `on`. §5.7 does not define this case.
- T5.10 for the harpsichord checks the pluck depth 0.70 at `on` (the jack is not at 1.0 at the pluck).
- Upright "80% return": a stroke starting with d0 > 0.2 is drawn as the fast-repetition approximation even when
  slower than 143 ms. Since the review fix the "from half the blow" is a target, not a start: the hammer starts
  where it actually is and the key-driven curve (floored at 0.5) takes over as the key goes down.
- Keys above lastDamper report damper = 1 (never damping). Escape is 1 from tEscape until the key has returned half way.
- Analytic A0(v) = 10^((−40 + 34·v/127)/20) linear RMS, mapped through the same dB → amp curve as the lanes.
- strikeAge advances by dtSec each frame and is 0 on a drawn contact; 1e9 after a reseed.
- FocusTracker: "down" = governing note held; "struck" = last contact within 0.5 s of song time.

## Review fixes (round 1)
- MAJOR hammer step at re-strikes: fixed. Each stroke starts from the height the previous note leaves at tStart,
  hStart = min(rebound_prev(tStart), keyToBlow·d0) (the rebound alone if tStart < off_prev). Before tEscape,
  h = keyHammer(dip) + (hStart − keyHammer(d0))·(1 − (dip − d0)/(1 − d0)): for an isolated stroke (hStart = 0 =
  keyHammer(0)) this is exactly the old key-driven curve, so T5.2 is unchanged. NoteCtx gained prevOnUs, prevOffUs,
  prevVel, prevDHeld (filled by NoteTiming.fill). New T5.3 test `restrikeHammerIsContinuous` (grand + upright,
  r 0.5/1/1.5, partial-return re-strike, release during rebound, REPEAT_15): no step > 0.01 per 10 µs across tStart.
  The upright fast-repetition test no longer asserts h ≥ 0.5 just after tStart (that was the jump itself).
- Release during the rebound: fixed. §5.7 "rebound to the check level, then down with the key" is read as
  h = min(max(rebound, check), keyToBlow·dip) after the key-up: no clamp to the check level until the rebound reaches it.
- Harpsichord tongue flick on fast repetitions: fixed; the press branch also evaluates the previous note's 8′/4′
  flick from prevOffUs/prevDHeld. T5.10 asserts tongue > 0 at off_j + 42.6 ms for REPEAT_15.
- Harpsichord 4′ dip vs on − staggerMs·r: ACCEPTED DEVIATION (not fixed). On an isolated stroke the drawn 4′ jack
  passes 0.433 at about on − stagger; on a compressed lead or a re-strike from d0 > 0 it passes it earlier or later
  by up to a few ms. The audio pluck and the 4′-only flash both use on − staggerMs·r (the timing that is heard and
  flashed is exact); only the jack position in that one frame differs, below one frame at 30 fps in every case we
  measured. A piecewise press through the point would add a kink the eye sees more than the offset.
- d0 chain hand-off: fixed. The chain now starts at an anchor, the latest of the previous CHAIN = 8 notes whose key
  reaches the bed before release (dHeld = 1 whatever its d0), or the first note on the key; consecutive notes share
  it, so dHeld_j and d0_{j+1} agree. Test `staccatoRunIsContinuous` (12 notes of 15 ms, 70 ms apart). Only a run of
  more than 8 notes none of which reaches the bed can still differ (then from a 8-note chain; negligible).
- ExposureSampler capacity: now 88 × 8 = 704 (a key cannot give more than about 8 contacts in 100 ms given the
  12 ms minimum lead), and an `overflow` counter (reset in bind) for the debug HUD.
- T5.10 strengthened: tStart_j ≥ off_{j−1} and dip non-increasing from off_{j−1} to tStart_j; the first t with
  hammer ≥ 1 − 1e-4 is within ±0.5 ms of on; the dead `rising` flag removed.

## Remaining
- None in code. Device checks (M3/M4/M7) are run by the integrator; wiring in docs/wiring/WP5.md.
