# WP7 wiring (wp7-instruments)

## Swap
- `Wiring.scenes`: `StubScenes()` → a `SceneFactory` that takes instruments from WP7 and keeps the stub
  venue until WP8 merges:

  ```kotlin
  val scenes: SceneFactory = object : SceneFactory {
      override fun instrument(id: InstrumentId, look: InstrumentLook, lastDamper: Int): InstrumentScene =
          com.tropicalstream.hammerklavier.instrument.Instruments.create(id, look, lastDamper)
      override fun venue(): VenueScene = StubVenue()          // WP8: venue.VenueSceneImpl()
  }
  ```
  Imports: `contract.InstrumentId`, `contract.InstrumentLook`, `contract.InstrumentScene`, `contract.VenueScene`,
  `contract.stub.StubVenue`. Nothing else in the app names a WP7 class.

## Hook-ups
- `AppController` (or WP12's `SessionController`) must pass the **bank's** `lastDamper`
  (`bank.info.lastDamper`) to `RenderControl.setInstrument(id, look, lastDamper)` once the kit is open, not
  `InstrumentProfile.GRAND.lastDamper`: the grand draws `lastDamper − 20` dampers (68 for Salamander's 88),
  the upright the same formula from its measured value. Today's call at `AppController.kt:97` with the
  profile default (88) is correct for the Salamander grand and may stay until WP12 lands.
- `InstrumentLook.finish` selects the upright case (WALNUT, MAHOGANY → `WOOD_CASE`/lit; EBONY →
  `LACQUER`); `edgeOverlay = true` puts the feature-edge ribbons (drawSlot 18) on every venue level,
  otherwise they are drawn at `PASSTHROUGH` only.
- `Instruments.create` is pure and cheap; `meshes()` and `textures()` build on each call (run them on HKLoader,
  as §5.1 says). `packActionSet` and `anchors.camera()/listener()` are allocation-free (GLThread).
- No Settings keys, no manifest, permission, asset or build changes.
- Conventions WP6 must implement to draw these meshes are listed in `docs/requests/WP7.md` (rotation sense,
  the harpsichord action-set part layout, section caps built at x = 0, texture recipes that only draw).

## Smoke
- JVM: `tools/gw :core:test --tests '*InstrumentsTest*' --tests '*MeshBuilderTest*'` (T7.0–T7.8, 23 tests).
  Review PNGs land in `core/build/shots/` (18 instrument × framing rasters and the textures).
- Device (integrator, with WP6's real renderer; the stub GL host draws no meshes):
  1. `tools/device/run.sh`, then `--es instrument grand`: the keyboard, lacquer case, lid on its stick and
     the brass pedals show in both eyes in the Player framing.
  2. `--es view action --ei framing 0` while a piece plays: the cutaway follows the highest key; 13 action
     slots move; the case is cut at the tracked key and the section cap shows.
  3. `--es view action --ei framing 1`: lid and music desk gone, plate, strings (copper bass, steel treble),
     hammers and 68 dampers visible.
  4. Repeat for `upright` (walnut; dampers under the hammers in Overhead) and `harpsichord` (61 bone keys,
     two jack rows, painted soundboard with the rose, motto inside the lid).
  5. Logged draws per eye stay ≤ 28 and triangles ≤ 45k in every framing (instrument share printed by T7.8:
     grand ≤ 17, upright ≤ 16, harpsichord ≤ 18 draws; 18.3k / 16.9k / 13.9k triangles).
  6. Mono screencaps of every instrument × framing (18 shots) into `docs/shots/`; T-APL on them.
