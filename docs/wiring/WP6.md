# WP6 wiring (wp6-render, after merging main v1.1)

## Swap
- `Wiring.kt` line 63: `fun glHost(ctx: Context, msaa: Boolean): GlHost = StubGlHost(ctx)` →
  `render.HkGlView(ctx, loader = loader, msaa = msaa, head = head)` (`head` is Wiring's existing `val head = HeadPose()`);
  drop the `StubGlHost` import. Nothing else in Wiring changes; `scenes` stays StubScenes until WP7/WP8 swap in.

## Hook-ups
- MainActivity (as today): `gl.onResume()` / `gl.onPause()` from the activity; `gl.setStereo(!mono)` for `--ez mono true`.
- AppController: `gl.bind(clock, energy, wiring.mechanics(), wiring.scenes)` once; then `setInstrument`, `setView`,
  `setQuality` (every governor change, including Q3 = display rest), `setSettings`, `setPerformance` for every new
  Performance (the renderer keeps two slots by generation), `setIdle(paused)`, `setSyncFlash`, `setTitle`,
  `setStageHidden`, `setOverrides`, `recenter()` on triple tap. All calls are main-thread, fields only.
- `--ez glreset true` (debug): MainActivity recreates the GLSurfaceView (remove the view, `wiring.glHost(...)` again,
  re-bind, re-send the desired state). The renderer's GL generation path handles the new context.
- `HkGlView.renderer.debugAlloc` is on in debug builds; `diagnostics()["glAllocs"]` is the GL-thread allocation count
  since frame 120 (must stay 0).
- No settings keys, permissions or manifest changes. The sensor (game rotation vector) is registered in `onResume`.

## Smoke
- Log on start: `HKRender cfg=r8g8b8a0 depth=24 stencil=0 EGL_SAMPLES=4` (or 0), `renderer=… maxVertexUniformVectors=…`,
  `scene grand items=… resident=… KiB`, and on every cut `view=PLAYER/0 level=STAGE draws<=N tris=M` (N ≤ 28).
- M-GL: StubScenes' keyboard animated by StubMechanics in both eyes; `--ez mono true` gives one full-width eye.
- T-FPS: `dumpsys gfxinfo` / RenderStats.divider 2 / 3 / 6 at Q0, Q2 (`--ei quality 2`) and idle (paused).
- T-Q3SWITCH: `--ei quality 3` (black, GL paused, `display rest: GL paused` logged), switch instrument,
  `--ei quality -1`: the new instrument is on the first frame, no dip.
- T-GLRESET: `--ez glreset true`: `glGeneration=1` logged, scene back within 1 s, `glErrors` 0 in diagnostics.
- JVM: `tools/gw :core:test --tests 'com.tropicalstream.hammerklavier.geom.*'` and
  `tools/gw :app:testDebugUnitTest --tests 'com.tropicalstream.hammerklavier.render.*'`.
