# Requests and notes from WP7 (instrument models)

## To WP6 (renderer) — conventions the WP7 meshes rely on

These fill gaps in §2.3/§5.8; the paired T6.4 decode test should use them.

1. **Rotation sense.** Every skinned rotation is about the **+x axis** through (pivotY, pivotZ) of its
   `SkinParams`, by `angle = poseValue × signedMax` (right-handed: y' = y cosθ − z sinθ, z' = y sinθ + z cosθ,
   relative to the pivot). The sign lives in the max:
   - `KEY_ROT [pivotY, pivotZ, maxRad]`: maxRad > 0, the front (z > pivot) dips; poseValue = `keyDip`.
   - `HAMMER_ROT [pivotY, pivotZ, blowRad]`: blowRad **< 0** for both pianos (grand: the head lies in front
     of its flange and rises; upright: the head lies above its butt and swings toward −z); poseValue = `hammer`.
   - `PEDAL_ROT [pivotY, pivotZ, maxRad]`: maxRad > 0 presses the tip (in front of the pivot) down.
   - `TONGUE_ROT` / `TONGUE4_ROT [pivotY, pivotZ, maxRad]`: maxRad > 0 tilts the tongue back.
   - `SOSTENUTO_ROT [pivotY, pivotZ, rad45, rad90]`, `LID [hingeX, hingeY, hingeZ, stickRad]`: the lid turns about
     the **z axis** through (hingeX, hingeY) (the spine), opening toward +x (treble side) by stickRad × lidOpen
     (grand 38°, harpsichord 50°). The upright has no LID mesh: its top lid is a static mesh left out of Overhead.
2. **ACTION_SET** `SkinParams` = 6 × (pivotY, pivotZ, axisSign): angle about +x = value × axisSign for
   axisSign ±1. The harpsichord also uses **axisSign 0 = translate along +y by the value (m)** and **2 = translate
   along +x (m)**. Harpsichord part types: 0 8′ jack lift (m), 1 8′ tongue (rad), 2 8′ register offset (m, no
   geometry), 3 key lever (rad), 4 4′ jack lift (m), 5 4′ tongue (rad); vec4 33 = (8′ offset, 4′ offset, 0, 0),
   which the 4′ jack (part 4) should add along +x. Headers are `(keyX, dim, key, 0)`, dim 1 active / 0.4
   inactive / 0 (all zeros) when `xCutKey` is NaN. Action-set vertices are built at x = 0 (add the header x).
3. **Tongues** (`TONGUE_ROT`, `TONGUE4_ROT`) ride their jack: the tongue draw needs the jack lift too
   (hammer lane for 8′, jack4 for 4′) plus the tongue lane. If one lane per draw is a hard limit, merge the
   tongue into the jack draw and read the tongue angle from the spare lanes; tell WP7 and it will re-pack.
4. **Section caps** (`SECTION_CAP`, drawSlot 17) are built in the plane **x = 0**, facing +x: translate them to
   `clipX`. `clipped = false` on them, on the action set and on the pedals.
5. **Texture recipes only draw**: the uploader calls `painter.begin(recipe.width, recipe.height)`,
   `recipe.paint(painter)`, `painter.end()`. Textured meshes use uv 0..1 (decals, the nameboard, the lid motto,
   the harpsichord soundboard) with a white vertex colour.
6. **Strings**: one STRING mesh per instrument (steel and copper by vertex colour); partIndex = key − lowKey
   for both harpsichord choirs (they share the key's `stringAmp`). `SkinParams[STRING]` = (2.5, 3.5, 1.5) px.

## To WP0 / WP12
- Pass the bank's `lastDamper` to `setInstrument` (see `docs/wiring/WP7.md`).
