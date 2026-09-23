# Requests from WP8 (Venue)

## To WP0: fill `KonzertzimmerAcoustics.GEOMETRY.fixtureFootprints`

PLAN R64 adds the fixture footprints to `VenueGeometry`, but contracts-v1 ships the shared
constant with `fixtureFootprints = FloatArray(0)`. WP8 works around it locally:
`venue/Konzertzimmer.GEOMETRY` is a `VenueGeometry` that shares the constant's `surfaces`,
`erPlanes`, `personSabins`, `airM` and `placements` by reference and carries
`Konzertzimmer.FOOTPRINTS` (n × 4: xMin zMin xMax zMax, room frame):

- 2 floor candelabra: centres (−1.70, −0.85) and (1.30, −0.85), ±0.20 m
- music stand: (−2.70, −1.30), ±0.25 m
- 18 chairs: rows z = 1.40, 2.35, 3.30; x = 0.40 + (i − 2)·0.62, i = 0..5; x ±0.24, z −0.24..+0.30
- door swings: E x 4.45..5.25, W x −5.25..−4.45, both z −0.75..0.75

Request: copy these numbers into the constant (the contract cannot import `venue`). As soon as the
constant is non-empty, `Konzertzimmer.GEOMETRY` uses the constant's footprints; nothing else in WP8
changes. VenueSceneImpl.geometry is then equal in content to the constant.

## To WP7: case outlines

T8.9 uses `Konzertzimmer.CASE_OUTLINES` (piano-frame xMin zMin xMax zMax) taken from the §5.4
dimensions: grand (−0.745, −1.95, 0.745, 0.05), upright (−0.765, −0.65, 0.765, 0.0), harpsichord
(−0.465, −2.26, 0.465, 0.02). If the built cases differ, please say so (or expose the case
outline from `Instruments`) and WP8 will switch the test to it.

## To WP6: conventions the venue relies on

- Sprite record from `FlameField.update`: x y z (room frame), size = half-height in metres, rgb
  0..1 un-lifted sRGB, alpha = additive weight. `maxSprites` = 860.
- `LightRig.rgb` = FLAME_BODY/255 × (flames in the group / 5) × flicker, light 0 the chandelier
  aggregate, 1 and 2 the candelabra, 3 the sconces of the centre N mirror bay.
- Probe: column u = (yaw + π)/2π·128 (yaw 0 = north, + east), row v = (π/2 − elevation)/π·64, row 0
  straight up; RGBA, alpha 255.
- Decal UVs index `venue.atlas` with v = 0 at the painted image's top row; the floor's UVs are world
  metres (x, z) for `venue.parquet`, which must be uploaded with GL_REPEAT.
- Meshes with `fadeNearM`/`fadeFarM` 3.5/6.0 fade by distance at the Stage level only; the Stage
  centre is (0, 1.0, −1.9) (`Konzertzimmer.STAGE_CENTRE`).
