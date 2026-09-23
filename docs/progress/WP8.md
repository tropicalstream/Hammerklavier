# WP8 progress (Venue)

Branch `wp8-venue` (from `contracts-v1`), worktree `/Users/me/Projects/hk-wp8`. Plan: §7.2 WP8,
§5.5, §5.9 (venue side), §3.12, §5.3, §5.6.

## Done

- `venue/Konzertzimmer.kt`: room constants; every drawn wall opening (`WALL_FEATURES`: 3 N mirrors,
  2 N Pesne, 3 S windows, 2 S pier glasses, E/W doors, supraporten, 4 Pesne); boiserie, wall,
  ceiling (flat + cove runs + mitred corners) and volume from the drawn geometry; fixture
  footprints; case outlines per instrument; `ACOUSTICS` = the contract constant, `GEOMETRY` = the
  same data plus footprints.
- `venue/FlameLayout.kt`: the 50 flames (12 + 6 chandelier, 12 N girandoles, 4 S, 4 door, 2 × 5
  candelabra, 2 desk), groups and Stage flags; 160 crystals; the 5 reflecting glasses.
- `venue/FlameFieldImpl.kt`: body + halo sprites, height ±15%, per-sprite ±8% and global ±4%
  flicker from one prepared noise table; CPU mirror culling (segment eye → image through the glass
  rectangle), one N–S bounce at Q0, floor images at 25% (Q0–Q1), crystal sparkle at Salon; level
  capped by `q.roomCap`; Stage fades non-stage flames by 1 − smoothstep(3.5, 6, d); `lights()` with
  4 aggregated lights. Allocation-free, no transcendental maths per frame.
- `venue/LightBake.kt`: per-vertex light from all flames (Lambert for surfaces, omni for ribbons).
- `venue/RoomShell.kt`: parquet floor (grid, world-metre UVs, baked pools that fall to black),
  Instrument-level contact pool, window frames and 3 × 6 bars, wall glow discs (BOISERIE_NEAR /
  Stadtschloss celadon by palette), gilt ribbons (cornice lip and line, dado broken by openings,
  pilaster trellis with vine in every wall gap, frames with arched mirror heads, door leaves and
  panels, ceiling spider web 16 × 9, flat-field frame, 4 corner trellises), decals (cove cartouches
  every ≈ 2 m cycling hound/hare/stag/putto, 4 corner crests, 3 mirror crests, rosette).
- `venue/Fixtures.kt`: 18 chairs (frame + damask, Hall views), chandelier stem/rings/arms and
  girandole arms (ribbons), candelabra, music stand, candle sticks (lit).
- `venue/ProbeBake.kt`: 128 × 64 equirect probe of flames and gilt bands.
- `venue/tex/Atlas.kt`: `venue.atlas` (512², 8 cells) and `venue.parquet` (256², repeating).
- `venue/VenueSceneImpl.kt`: implements `VenueScene`.
- Tests: `VenueTest` (T8.1–T8.9 + colour rules, winding, texture names), `AtlasTest` (paints every
  recipe, PNGs in `core/build/venue-review/`).

## Test results

- `tools/gw :core:test`: BUILD SUCCESSFUL, 52 tests (13 VenueTest + 2 AtlasTest + WP0's), 0 failures,
  0 ignored. `tools/check_purity.sh`: OK. No test needed contracts-v1.1 or a WP11 fixture.
- Venue triangles checked below the T8.4 limit of 18k (and above 2k) for both palettes.

- Merged main (contracts-v1.1, WP11 fixtures, WP7 MeshBuilder): no conflicts, no `@Ignore` markers in WP8 tests.
- `VenueRasterTest`: MeshRaster review of Hall, Stage, look N/S and ceiling for both palettes
  (`core/build/venue-review/raster-*.png`); reviewed, geometry reads correctly.
- `docs/wiring/WP8.md` written.
- After merge: `tools/gw :core:test` BUILD SUCCESSFUL, 81 tests, 0 failures, 0 skipped.
- After review fixes: `tools/gw :core:test` BUILD SUCCESSFUL, all green (+2 tests).

## Decisions and deviations

- **Footprints:** the contract constant has none; `Konzertzimmer.GEOMETRY` carries them (request to
  WP0 in `docs/requests/WP8.md`). `VenueSceneImpl.geometry` is therefore not the identical object
  but shares its surface list, ER planes and placements.
- **Ceiling area:** the drawn cove ceiling is 106.6 m² vs the table's 108.6 m²; T8.1 checks it within
  2.5 m² (T8.1 names only floor, walls and V). The acoustic constant is unchanged.
- **Case outlines** for T8.9 come from the §5.4 dimensions (WP7 owns the real cases).
- **MeshBuilder:** only the day-0 subset exists; ribbons, discs and decals are built with
  `vertex()`/`tri()` in private helpers (`Geo` in RoomShell.kt). Kept after WP7's builder merged (tested, no benefit to rewriting); the encoding follows the frozen conventions.
- **Candelabra "at 1.6 m"** read as candle height 1.6 m; placed on the audience side of the grand
  at (−1.70, −0.85) and (1.30, −0.85). Music-desk candles are placed for the grand/harpsichord desk.
- **Review fixes (round 1):** flicker noise table is now band-limited (cosines of 6–10 Hz at
  `NOISE_RATE` = 32 steps/s, built in init) instead of [1,2,1]-smoothed white noise at 8 steps/s;
  T8.3 gains a DFT check (10 s at 30 fps, >70% of global and >60% of sprite energy in 5.5–10.5 Hz).
  Light 3 is the N sconce group nearest the instrument via `FlameFieldImpl.setInstrumentOrigin`
  (WP6 asked to call it). Mirror culling rejects hits above the N mirrors' arched head (parabolic,
  matching the drawn frame). Ceiling 2 m² gap and the per-instrument contact pool filed as
  requests (docs/requests/WP8.md) rather than changed: both need a contract change. T8.9 stays on
  §5.4 outlines until integration, when it should switch to the bounds of `Instruments.create`
  meshes (WP7's code is not on this branch).
- **Contact pool** is centred on the Stage centre (the grand/harpsichord); the upright's pool would
  need an instrument-aware venue (not in the contract).
- Chairs are Hall-view only (viewMask bits 4–5); other baked venue meshes are all views.
- Wall fields and ceiling plaster are not drawn at any level (the wearer's room stands in).

## Remaining

- Device checks (T-APL Hall ≤ 12%, Stage ≤ 9%; Hall screencap vs the build sheet; look-around):
  need WP6 and the glasses (not allowed in this stage).
