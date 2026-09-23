# WP7 progress (Instrument models and the mesh builder)

## Stage 1: done (MeshBuilder + MeshRaster, T7.0 green)
- `core/.../mesh/MeshBuilder.kt` complete: part/color/vertex/tri (day-0), quad, box, plus
  `triOutward` (orders a triangle to agree with its vertex normals, drops zero-area ones),
  `extrude(outlineXZ, y0, y1, capTop, capBottom, creaseDeg = 30)` (concave outlines, either orientation, ear-clipped caps),
  `lathe(profileRY, segments, cx, cz)` (profile bottom to top, poles dropped, seam column duplicated),
  `sweep(pathXYZ, profileXY, closed, samplesPerSegment = 4, caps = true, creaseDeg = 30)` (Catmull-Rom path, parallel transport, twist spread on closed paths),
  `ribbon(pathXYZ, widthM, closed = false)`, `spindle(a, b, segments, rgb)`, `build` with the 65,535 split.
  Companion helpers: `catmullRom(pts, dim, samples, closed)`, `ccw`, `signedArea2`, `earClip`.
- `core/src/testFixtures/.../testutil/MeshRaster.kt`: software rasteriser (perspective, z-buffer,
  back-face culling, Lambert on vertex colours with the pow 0.85 lift; ribbons/strings as lines), `render`, `writePng`, `coverage`.
- T7.0 `MeshBuilderTest`: 13 tests green (`tools/gw :core:test --tests '*MeshBuilderTest*'`).

## Decisions / interpretations (the §2.3 signature line is terse)
- `sweep.closed` = the path is a loop; the profile is always a closed cross-section.
- `spindle(a, b, segments, rgb)` is the string primitive: STRING layout, t = station/segments, side ±1, dir = unit(b − a), rgb also becomes the current colour. It requires the STRING layout.
- `ribbon` requires the STATIC layout; added optional `closed` for plate lightening-hole loops.
- Extra optional parameters (creaseDeg, samplesPerSegment, caps, closed) all default, so the contract call shapes still compile.

## Remaining (stage 2+)
- Instruments (§5.4), Anchors (§5.6), colours (§5.9), packActionSet, T7.1–T7.8. Not started by design.
