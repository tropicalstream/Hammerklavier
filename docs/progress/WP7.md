# WP7 progress (Instrument models and the mesh builder)

Status: **complete per §7.2** (JVM side). Device checks (18 mono screencaps into `docs/shots/`, T-APL) are the
integrator's at the milestones, once WP6's renderer draws the meshes.

## Done
- Stage 1: `mesh/MeshBuilder.kt` complete and `testutil/MeshRaster.kt` (T7.0, 13 tests). MeshRaster now also
  takes `clipX` for cutaway review (drops wholly-cut triangles of `clipped` meshes, shifts section caps and
  the action set to the cut).
- `instrument/Keyboard.kt`: any compass; equal back slots (7/12 of the head: 13.708 / 13.242 mm), equal heads
  (23.5 / 22.7 mm), tails = own slot where a sharp neighbour exists (end keys full), sharps tapered to 10.5 mm,
  key-local UV in mm, one skinned KEY_ROT mesh per colour (drawSlot 10).
- `instrument/Anchors.kt`: every §5.6 camera and listener row, `roomFrame`, `clipX`, `lidLift`; allocation-free.
- `instrument/GrandCase.kt` (`GrandDims`, rim from the §5.4 plan points, belly rail, cheeks, fallboard + lettering
  decal, legs, casters, lyre, pedals, lid + stick + music desk, plate band/struts/pin bar/pins, gilt hole
  ribbons, soundboard, bridges, section caps, edge overlay), `GrandActionMesh.kt` (88 hammers, `lastDamper − 20`
  dampers, sostenuto rail, 13-slot action set, `GrandActionPacker`, `GrandModel`), `StringsMesh.kt` (228
  strings, overstrung bass at 18°, wound 21–53).
- `instrument/UprightModel.kt`: case by finish, upper panel and top lid out of Overhead, vertical strings with
  the bass overstrung, horizontal hammers at 1.08 m, underdampers at 1.03 m (DAMPER_LIFT +z), hammer rail,
  pedals, action set, `UprightActionPacker`.
- `instrument/HarpsichordModel.kt`: 228 × 93 × 26 cm case (papered inside), stand, nameboard paper, lid with
  motto, soundboard (texture with rose), 8′/4′ bridges and nuts, register slides, jack rail + lid stick (out of
  Overhead), 61 keys, 122 jacks + tongues, 122 strings, action set, `HarpsichordActionPacker`.
- `instrument/tex/InstrumentTextures.kt`: fallboard lettering, harpsichord paper, lid motto, soundboard.
- `instrument/Instruments.kt`: `object Instruments.create(id, look, lastDamper)`, `InstrumentSceneImpl`,
  `ActionSetPacker` (§5.8 block). `instrument/Geo.kt`: view masks and polygon helpers.
- `docs/wiring/WP7.md`, `docs/requests/WP7.md` (conventions for WP6).

## Tests (`tools/gw :core:test`: 11 classes, 75 tests, 0 failures)
`InstrumentsTest` (10): T7.1 triangles grand 18,260 / upright 16,926 / harpsichord 13,944; T7.2 pitch, counts,
cut-out rule, centring; T7.3 slots < 34, one-hot lanes; T7.5 no NaN/Inf, index bounds, winding vs normals for
every lit/skinned mesh; T7.4 every camera/listener row exactly, clamping, no allocation; T7.6 228 strings
(8/40/180), 88 hammers, 68 dampers (and ld − 20 for 80/88/92), grand case 1.49 × 2.00, upright 1.31 tall with
dampers 50 mm under the strike line, harpsichord 122 jacks/tongues/strings and 0.93 ≥ 0.817 + 0.104; T7.7
packActionSet deterministic, §5.8 layout and shader addressing, window clamping, NaN → zeros, no allocation,
action-set uv/slot/lane; T7.8 programs vs MaterialTable, merge keys per framing (grand 9/9/17/13/14/14,
upright 8/8/16/12/14/14, harpsichord 9/9/18/13/16/16); upright finishes; textures paint; review PNGs in
`core/build/shots/`. No `@Ignore` markers exist in the tree.

## Decisions / deviations (with reasons)
- `sweep.closed` = the path is a loop; `spindle` is the string primitive; `ribbon` has optional `closed`.
- Rotation signs are carried by the signed max in `SkinParams` (see `docs/requests/WP7.md`); the contract has
  no sign field. Harpsichord action-set part layout extended to six parts (8′ jack, tongue, register; key
  lever; 4′ jack, tongue) with vec4 33 holding both register offsets.
- Grand rim: the §5.4 plan points drive the bentside as an open Catmull-Rom chain from (1, 0.10) to the tail,
  the spine straight; the key-well side (v < 0.10) is cheeks + key bed, and the rim's front side is a low
  belly rail (to 0.80 m) so the pin block and plate bar show above it. Case bounds 1.49 × 2.00 m hold.
- Soundboard colour SOUNDBOARD × 0.25 (the "shadowed ≤ 0.25" note). Grand key tops 0.715 m, strings 0.845 m,
  strike line z −0.30, flange z −0.433 (head in front of the flange), dampers at z −0.50.
- Harpsichord: key tops 0.78 m, case 0.64–0.90 m, balance at z −0.165 (midway to the jacks, so jack rise = key
  travel), lid opens 50°; 4′ strings at least 0.10 m. Arcaded key fronts are a darker front colour only.
- Upright: no LID skin (the top lid is static and hidden in Overhead, which the Overhead camera sees past).
- Section caps are a few fixed quads (key bed/frame, belly rail/back) at x = 0 for WP6 to translate to clipX.
- Texture recipes do not call begin/end (the uploader does).

## Review fixes (round 1)
- **major, tongues**: fixed by merging. Each harpsichord tongue + quill is now part of its jack's lift-skinned draw
  (`harpsichord.jacks8` JACK_LIFT, `harpsichord.jacks4` JACK4_LIFT), at the rest pose, so it rises with the jack
  in Overhead and Hall under one slot/lane per vertex. No TONGUE_ROT / TONGUE4_ROT meshes or skin params remain;
  the tongue tilt is shown only in the cutaway by action-set parts 1 and 5. Test `t76TongueRidesJack` decodes a
  key-29 tongue vertex under a full lift (8′ and 4′). One fewer draw per framing in Action/Hall.
- **minor, action-set extension (parts 3–5, axisSign 0/2)**: filed as a contract-change request in
  `docs/requests/WP7.md` (to WP6 / plan owner, confirm before M7). T7.7 harpsichord assertions stay the source for T6.4.
- **minor, sostenuto rail**: now VM.PLAYER_HALL, clipped = false, slot 15, so it merges with the pedals as §5.3 row 15 says.
- **minor, T7.4 probes**: camera() and listener() are allocation-probed for every instrument × view × framing.
- **minor, active slots**: `ActionSetPacker` counts escape, tongue and tongue4 too (KDoc: active = any pose lane moving);
  T7.7 checks an escape-only and a tongue-only key.
- **minor, T7.8 limits**: derived in the test: per framing 28 − venue rows 1–6 (≤ 6) − glyph + fade (2) − pedal inset
  (2, Player follow) = 20 / 18; each mesh's viewMask must lie inside its §5.3 row's "Shown in" framings, and
  instrument + venue + glyph/fade ≤ 28 is asserted. The venue's own ≤ 6 is WP8's T8.8. That row check moved the
  harpsichord jack rail from slot 9 (Action/Hall only) to slot 7 (case), since it is shown in Player too.

## Remaining
- Nothing on the JVM side. Device: the 18 screencaps and T-APL (integrator, with WP6).
