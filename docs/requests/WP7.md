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
3. **Tongues** (settled, review round 1): there are **no TONGUE_ROT / TONGUE4_ROT meshes**. Each tongue and quill
   is merged into its jack's JACK_LIFT / JACK4_LIFT draw at the rest pose, so it rises with the jack under one lane
   per vertex; the tongue tilt is animated only in the cutaway (action-set parts 1 and 5). WP6 needs no TONGUE program.
4. **Section caps** (`SECTION_CAP`, drawSlot 17) are built in the plane **x = 0**, facing +x: translate them to
   `clipX`. `clipped = false` on them, on the action set and on the pedals.
5. **Texture recipes only draw**: the uploader calls `painter.begin(recipe.width, recipe.height)`,
   `recipe.paint(painter)`, `painter.end()`. Textured meshes use uv 0..1 (decals, the nameboard, the lid motto,
   the harpsichord soundboard) with a white vertex colour.
6. **Strings**: one STRING mesh per instrument (steel and copper by vertex colour); partIndex = key − lowKey
   for both harpsichord choirs (they share the key's `stringAmp`). `SkinParams[STRING]` = (2.5, 3.5, 1.5) px.

## CONTRACT-CHANGE REQUEST (WP6 + plan owner, confirm before M7): §5.8 harpsichord action set
§5.8 defines harpsichord parts 0–2 and a rotation-only axisSign. WP7 packs six parts (item 2 above: 3 key lever,
4 4′ jack lift, 5 4′ tongue) and uses axisSign 0 = translate +y by the value (m), 2 = translate +x (m), ±1 = rotate
about +x. Please adopt this in §5.8 / a §10 decision, or reply with the layout WP6 wants and WP7 will re-pack.
Until then T7.7's harpsichord assertions are the reference for the paired T6.4 decode.

## To WP0 / WP12
- Pass the bank's `lastDamper` to `setInstrument` (see `docs/wiring/WP7.md`).

## Answers (WP0, M3)
- lastDamper: kept at the profile default (88, correct for Salamander) until WP12 lands.
- Harpsichord action-set request: open for the plan owner, before M7.

## Answers (WP0, M7)
- Harpsichord action set: adopted as packed (docs/contracts-changelog.md, M7).
- SkinKind.LID: the lid meshes use LIT/LACQUER (no skinning), so the open angle is baked into the mesh at build time
  (`MeshBuilder.rotateZ(X0, top, stickRad)` in GrandCase / HarpsichordModel). If WP7 prefers a SKINNED lid program,
  say so and remove the bake.
