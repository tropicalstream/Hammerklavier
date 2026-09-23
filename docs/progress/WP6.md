# WP6 progress (render core)

Branch `wp6-render` (from `contracts-v1`), worktree `/Users/me/Projects/hk-wp6`. Plan: §7.2 WP6, §5.1–§5.3, §5.6, §5.8–§5.11, R39, R40, R83, R84.

## Done (JVM stage)

- **core `geom/`**: `Mat4` (column-major helpers, off-axis `frustum`, `view`, rigid R_y placement and inverse) and `SinTable`
  (4096-entry table, the renderer's only sin/cos/tan: no transcendental maths on GLThread, §2.1 rule 5); `Springs`
  (`CritSpring`: critically damped with a rational exp approximation and a speed cap; `DeadBand`); `StereoRig` (parallel
  eyes, off-axis frusta, IPD 63 mm × IPD scale × Stereo depth, right = forward × up then up = right × forward, gaze-adjusted
  forward, mono); `CameraDirector` (dip 250/250 ms with the cut at the midpoint, queue depth 1, `requestDip` for scene swaps,
  `cutImmediately` after a rest/resume, x_cut dead band ±2 + ω 4, x_c ω 6 capped 0.6 m/s, piano → room conversion with the
  Placement, CONTROL overrides, gaze scaling: world-locked in the Hall, ±5° parallax elsewhere).
- **app `render/`**: `HkGlView` (GlHost; ES 2.0; preserveEGLContextOnPause; EGL chooser 4× MSAA → RGB888+D24 → default,
  `HKRender cfg=… EGL_SAMPLES=…` logged; Choreographer pacing with dividers 2 / 3 / 6, one black frame in display rest,
  removeFrameCallback before postFrameCallback; RenderControl calls only set fields; scene builds kicked onto HKLoader from
  main so they progress during rest/pause), `StereoRenderer` (§5.2 frame order; desired-state reconciliation; two Performance
  slots by generation; `VisualClock`; EnergyRing read with miss count; GL generation counter and handle discard without
  glDelete; resident arrays re-uploaded; FRAME HITCH; late > 8 ms counted; p99 CPU and late; debug GL-thread alloc count),
  `SceneAssembler` (merge by the §5.3 key + layout/space/fade, 65,535 split, per (view·2+framing, level) lists sorted by
  drawSlot, overhead-draw count), `UniformPacker` (pose → uState blocks once per frame, lane k − lowKey, pedals 0/1/2,
  ACTION_SET via `packActionSet`, decode helpers mirroring the shader), `ItemDrawer` (per-draw program, material, skin and
  blend state), `SpriteBatch`, `TextureUploader` (+ probe), `DipFader`, `PedalInset`, `SyncFlash`, `gl/GlKit`, `gl/Programs`,
  `gl/Shaders` (lit, lacquer, skinned with every SkinKind, string spindle, ribbon, sprite, decal, sectionCap, fade/disc),
  `GazeCamera` (+ pure `GazeFilter`, worldLocked mode) and `GlyphBoard` (cap 32, `release()` without GL, allocation-free hot path).
- **Docs**: `docs/wiring/WP6.md`, `docs/requests/WP0.md`.

## Tests (all green, 2026-09-22, after merging main: contracts-v1.1, WP11 fixtures, WP7 MeshBuilder)

`tools/gw :core:test :app:testDebugUnitTest :app:assembleRelease`: 104 tests, 0 failures, 0 skipped. No @Ignore remains. WP6's own:
- core `StereoRigTest` (T6.1: zero parallax, crossed/uncrossed disparity, right = forward × up for every framing of every
  instrument, mono, gaze, SinTable accuracy, rigid inverse = Placement), `CameraDirectorTest` (T6.2: 250/250 ms, cut at the
  midpoint, queue depth 1, scene-swap dip, no dip after rest, follow ≤ 0.6 m/s, x_cut dead band, no overshoot, gaze scaling,
  zero allocation).
- app `SceneAssemblerTest` (T6.3), `UniformPackerTest` (T6.4, incl. zero allocation), `GazeFilterTest` (T6.5),
  `ShadersTest` (every program compiles as GLSL ES 1.00 with glslangValidator; uniform budget ≤ 68 vec4; reserved words),
  `StereoRendererSlotsTest`, `StereoRendererFrameTest` (headless frames on android.jar's no-op GL: budget, **zero allocation per
  frame after warm-up**, view dip, instrument swap under the cut, instant swap after display rest, context loss recovery).
- Pairing tests (formerly @Ignore): `SceneAssemblerTest.realScenesWithinBudget` and `UniformPackerTest.actionSetDecodeAgreesWithWp7Pack`
  run through the test helper `RealScenes`, which loads WP7's `instrument.Instruments` and WP8's `venue.VenueSceneImpl` by name
  when merged and otherwise the contract stubs; they run (and pass) today on the stubs and switch to the real scenes automatically.

## Remaining

- Device checks (no adb in this task): M-GL, T-FPS, T-GLRESET, T-Q3SWITCH, EGL/uniform-vector logs, draws ≤ 28 logged,
  `glAllocs` = 0 in a debug build, display rest pauses GL, `--ez mono true`.
- On-glasses tuning of the lighting constants (ambient 0.45, light attenuation 1/(1+d²), swell 2.5 px) once WP7/WP8 meshes exist.
- Re-run the suite once WP7/WP8 merge (the pairing tests then exercise the real scenes; no code change needed).

## Decisions and deviations

1. RenderControl setters write `@Volatile` fields of `StereoRenderer.Desired` directly instead of posting `queueEvent`
   runnables: the same rule (fields only, no GL calls, §2.1 rule 6) without a Runnable per call; the Performance slots are
   under a small lock.
2. `CameraDirector.update` always outputs room-frame positions (`roomFrame = true` after conversion); instrument meshes are
   drawn through the Placement matrix, the venue as is, so lights, flames and the eye share one frame. `clipX` stays piano-frame
   (the skinned shader tests model-space x).
3. STRING lanes carry max(stringAmp, strike pulse), the pulse falling linearly over 150 ms after `strikeAge` 0 (one float per key
   fits the 34-vec4 block).
4. Shader interpretations where §5.8 gives only parameter lists: KEY/HAMMER/TONGUE/PEDAL rotate about the x axis through
   (pivotY, pivotZ) by value × maxRad; DAMPER_LIFT translates along the normalised axis; JACK lifts by value × travel and shifts
   x by registerOffset when its register is disengaged (from `pose.registers`); SOSTENUTO_ROT goes 0 → rad45 → rad90;
   HAMMER_RAIL translates −z by `uRailM`; LID rotates about z through the hinge by stickRad and is hidden when lidLift > 0.5;
   ACTION_SET adds the header's xM, rotates by angle × axisSign about its part's pivot, dims by the header, hides dim 0.
   The key bevel darkens only the left and front edges (the vertex has no key width).
5. GazeCamera world-locked pitch clamp is symmetric ±45° (the plan says "+45°").
6. HkGlView takes a fourth defaulted parameter `head: HeadPose? = null` (request in `docs/requests/WP0.md`).
7. `GlKit.skipStatusChecks` exists only so the frame logic runs headless in JVM tests.
8. The allocation test takes the least of three 300-frame windows: HotSpot shows one-off JIT/OSR transients even in a pure
   spin loop; a per-frame allocation would appear in every window.
9. Pairing tests find WP7/WP8 classes by reflection (test-only `RealScenes`) so no test is ignored and WP6 edits no file it does not own.
