# WP8 wiring (wp8-venue)

## Swap
- `Wiring.scenes`: `StubScenes()` → a `SceneFactory` whose `venue()` returns the real venue.
  Until WP7 merges, keep the stub instruments:
  ```kotlin
  val scenes: SceneFactory = object : SceneFactory {
      private val stub = StubScenes()
      private val venue by lazy { com.tropicalstream.hammerklavier.venue.VenueSceneImpl() }
      override fun instrument(id: InstrumentId, look: InstrumentLook, lastDamper: Int) = stub.instrument(id, look, lastDamper)
      override fun venue(): VenueScene = venue
  }
  ```
  After WP7: `instrument(...) = Instruments.create(id, look, lastDamper)`.
- `VenueSceneImpl()` has no constructor arguments. Construct it and call `meshes(palette)`,
  `textures()` and `bakeProbe(...)` on HKLoader (they allocate and bake); `flames().update(...)`
  runs on the GL thread and allocates nothing after construction.
- `Wiring.designer` (WP3) should keep using `KonzertzimmerAcoustics.GEOMETRY`;
  `VenueSceneImpl.geometry` shares its surfaces, ER planes and placements by reference and adds
  the fixture footprints (docs/requests/WP8.md asks WP0 to copy them into the constant).

## Hook-ups
- None in AppController / HammerklavierApp / MainActivity: the renderer (WP6) pulls
  meshes/textures/flames/probe through `SceneFactory.venue()`.
- WP6 must upload `venue.parquet` with GL_REPEAT (floor UVs are world metres) and `venue.atlas`
  with v = 0 at the image's top row.
- No Settings keys, manifest, permission, asset or build changes.

## Smoke
- JVM: `tools/gw :core:test --tests 'com.tropicalstream.hammerklavier.venue.*'` green
  (VenueTest T8.1–T8.9, AtlasTest, VenueRasterTest); review PNGs in `core/build/venue-review/`.
- Device (integrator, M5, needs WP6): open the Hall view — room shell, 18 chairs, chandelier and
  girandoles lit, candle flames flickering, mirror images of flames in the N glasses; switch to
  Stage — non-stage flames fade out beyond 3.5–6 m; turn the head through 360° (look-around) with
  no holes in walls or ceiling; T-APL Hall ≤ 12%, Stage ≤ 9%; no GC log lines on the GL thread
  while the flames animate.

- Call `FlameFieldImpl.setInstrumentOrigin(x, z)` with the current Placement origin when the instrument changes; it picks the N sconce group used as dynamic light 3.
