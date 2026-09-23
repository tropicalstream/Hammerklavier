package com.tropicalstream.hammerklavier.contract

// Pure mesh data from WP7/WP8, drawn by WP6 (PLAN §2.3, §5.3, §5.4, §5.8).

/**
 * STATIC: pos3 nrm3 uv2 rgba4 · SKINNED: STATIC + slot1 (vec4 index) + lane4 (one-hot).
 * STRING: pos3 (rest, along the string) dir3 (string direction) t1 (0..1 along speaking length) side1 (±1) slot1 lane4 rgb3.
 */
enum class VertexLayout(val floats: Int) { STATIC(12), SKINNED(17), STRING(16) }

// ── MeshBuilder conventions (normative; mesh/MeshBuilder.kt implements them) ──
// • Triangles are CCW seen from the front (outside); normals are unit length and point outward; back-face culling is on.
// • UV: box = per face, u along the face's first edge and v along its second, in metres; extrude sides u = outline arc length (m),
//   v = y (m), caps (x, z) m; lathe u = angle/2π, v = profile arc length (m); sweep u = path arc length, v = profile arc length (m);
//   keys (Keyboard) key-local UV in mm: u across the key from its left edge, v along from its front (the bevel shader relies on it).
// • Ribbon (STATIC layout, RIBBON program): pos = path point, nrm3 = unit path tangent, uv = (side ±1, half-width m); the shader widens it
//   in screen space with 1.5–2 px AA.
// • color() takes UN-lifted sRGB 0..255 (the Pal tokens); shaders apply pow(c, 0.85). rgba stored as 0..1 floats.
// • Indices are ShortArray read as unsigned (i and 0xFFFF); a mesh above 65,535 vertices is split by build().
// • Skinned vertices: slot = vec4 index into uState, lane = one-hot component. KEY_ROT/HAMMER_ROT/DAMPER_LIFT/JACK*/TONGUE*: partIndex =
//   key − lowKey → slot = partIndex / 4, lane = partIndex % 4. PEDAL_ROT partIndex 0 soft/una corda, 1 sostenuto, 2 sustain (left to right).
//   ACTION_SET: slot/lane address the part's angle (vec4 13 + (6·s + p) / 4); uv = (s = slot 0..12, p = part type 0..5) selects the
//   header vec4 s and the pivot of part type p.

enum class SkinKind { STATIC, KEY_ROT, HAMMER_ROT, DAMPER_LIFT, JACK_LIFT, JACK4_LIFT, TONGUE_ROT, TONGUE4_ROT,
                      STRING, PEDAL_ROT, ACTION_SET, LID, SHIFT_X, HAMMER_RAIL, SOSTENUTO_ROT }
enum class ProgramId { LIT, LACQUER, SKINNED, STRING, RIBBON, SPRITE, DECAL, SECTION_CAP }
enum class MaterialId { LACQUER, WOOD_CASE, FLEMISH_CASE, IVORY, EBONY_KEY, BONE, KEY_FRONT, KEYLEVER, ACTION_WOOD,
    FELT, DAMPER_TOP, CLOTH_RED, LEATHER, QUILL, BRASS, PLATE, SOUNDBOARD, HARPSI_SOUNDBOARD, PAPER, STEEL, COPPER,
    GILT, GILT_EMISSIVE, PARQUET_POOL, CHAIR_FRAME, DAMASK, MIRROR_FRAME, WINDOW_FRAME, SECTION_CAP, EDGE_GILT }

class MaterialParams(val program: ProgramId, val specExp: Float, val f0: Float, val presenceFloor: Boolean, val emissive: Float, val rim: Boolean)

/**
 * The §5.8 table; WP6 reads it, WP7/WP8 choose materials from it. [of] gives the program of a
 * STATIC mesh; moving parts use SKINNED and strings STRING whatever their material (T7.8).
 */
object MaterialTable {
    private val table: Array<MaterialParams> = Array(MaterialId.entries.size) { i ->
        when (MaterialId.entries[i]) {
            MaterialId.LACQUER -> MaterialParams(ProgramId.LACQUER, 96f, 0.05f, presenceFloor = true, emissive = 0f, rim = true)
            MaterialId.WOOD_CASE -> lit(32f)
            MaterialId.FLEMISH_CASE -> lit(24f)
            MaterialId.PLATE -> lit(64f)
            MaterialId.SOUNDBOARD -> lit(24f)
            MaterialId.HARPSI_SOUNDBOARD -> lit(24f)
            MaterialId.PAPER -> lit(24f)
            MaterialId.CHAIR_FRAME -> lit(40f)
            MaterialId.DAMASK -> lit(24f)
            MaterialId.PARQUET_POOL -> lit(32f)
            MaterialId.WINDOW_FRAME -> lit(32f)
            MaterialId.BRASS -> lit(64f)
            MaterialId.IVORY -> lit(48f, floor = true)
            MaterialId.EBONY_KEY -> lit(64f, floor = true)
            MaterialId.BONE -> lit(40f)
            MaterialId.KEY_FRONT -> lit(32f)
            MaterialId.KEYLEVER -> lit(24f)
            MaterialId.ACTION_WOOD -> lit(24f)
            MaterialId.FELT -> lit(12f)
            MaterialId.DAMPER_TOP -> lit(16f)
            MaterialId.CLOTH_RED -> lit(12f)
            MaterialId.LEATHER -> lit(16f)
            MaterialId.QUILL -> lit(32f)
            MaterialId.STEEL -> MaterialParams(ProgramId.STRING, 64f, 0.5f, presenceFloor = false, emissive = 0f, rim = false)
            MaterialId.COPPER -> MaterialParams(ProgramId.STRING, 48f, 0.5f, presenceFloor = false, emissive = 0f, rim = false)
            MaterialId.GILT -> MaterialParams(ProgramId.RIBBON, 0f, 0f, presenceFloor = false, emissive = 0.2f, rim = false)
            MaterialId.EDGE_GILT -> MaterialParams(ProgramId.RIBBON, 0f, 0f, presenceFloor = false, emissive = 0.2f, rim = false)
            MaterialId.MIRROR_FRAME -> MaterialParams(ProgramId.RIBBON, 0f, 0f, presenceFloor = false, emissive = 0.2f, rim = false)
            MaterialId.GILT_EMISSIVE -> MaterialParams(ProgramId.DECAL, 0f, 0f, presenceFloor = false, emissive = 1f, rim = false)
            MaterialId.SECTION_CAP -> MaterialParams(ProgramId.SECTION_CAP, 0f, 0f, presenceFloor = false, emissive = 0f, rim = false)
        }
    }

    private fun lit(spec: Float, floor: Boolean = false) =
        MaterialParams(ProgramId.LIT, spec, 0.04f, presenceFloor = floor, emissive = 0f, rim = false)

    fun of(m: MaterialId): MaterialParams = table[m.ordinal]
}

/** One mesh ready for upload (resident on the heap for context loss, §5.1). */
class BakedMesh(val name: String, val layout: VertexLayout, val vertices: FloatArray, val indices: ShortArray,
    val material: MaterialId, val skin: SkinKind,
    val levelMask: Int,          // bit RoomLevel.ordinal: drawn at that venue level
    val viewMask: Int,           // bit (view.ordinal * 2 + framing): drawn in that framing
    val clipped: Boolean,        // discarded by the Action cut plane
    val program: ProgramId,      // must equal MaterialTable.of(material).program unless skinned (SKINNED) or a string (STRING)
    val drawSlot: Int,           // the §5.3 row number; SceneAssembler merges meshes with equal (program, material, skin, texture, levelMask, viewMask, clipped, drawSlot)
    val texture: String? = null, val fadeNearM: Float = 0f, val fadeFarM: Float = 0f) {   // distance fade (Stage level)
    val vertexCount: Int get() = vertices.size / layout.floats
    val triangleCount: Int get() = indices.size / 3
}

class CameraPose {
    @JvmField val pos = FloatArray(3); @JvmField val target = FloatArray(3)
    @JvmField var roomFrame = false                                   // true: pos/target are room-frame (Hall); false: piano frame
    @JvmField var vFovDeg = 34f; @JvmField var ipdScale = 0.6f; @JvmField var zeroParallaxM = 1.75f
    @JvmField var clipX = Float.NaN; @JvmField var lidLift = 0f       // set by the anchors; CameraDirector only applies springs and the dip
}

class InstrumentLook(val finish: UprightFinish, val edgeOverlay: Boolean)

/** Per kind (§5.8). */
class SkinParams(val p: Map<SkinKind, FloatArray>)

interface InstrumentAnchors {
    /** 128, m in the piano frame; NaN outside the compass. */
    val keyX: FloatArray
    /** Piano frame. */
    val soundSource: FloatArray
    /** Piano frame. */
    val benchEar: FloatArray
    /** Sets roomFrame, clipX, lidLift. */
    fun camera(view: ViewId, framing: Int, focusKey: Float, centroidKey: Float, out: CameraPose)
    /** true = room frame (Hall), false = piano frame. */
    fun listener(view: ViewId, framing: Int, out: FloatArray): Boolean
}

/** Pure 2D drawing API; CanvasPainter (app) and AwtPainter (tests) implement it. Colours are 0xAARRGGBB. */
interface Painter2D {
    /** Transparent black. */
    fun begin(width: Int, height: Int)
    fun fillPath(xy: FloatArray, closed: Boolean, rgba: Int); fun strokePath(xy: FloatArray, closed: Boolean, widthPx: Float, rgba: Int)
    fun fillRect(x: Float, y: Float, w: Float, h: Float, rgba: Int); fun fillCircle(cx: Float, cy: Float, r: Float, rgba: Int)
    fun linearGradient(x0: Float, y0: Float, x1: Float, y1: Float, rgba0: Int, rgba1: Int, rect: FloatArray)
    fun text(s: String, x: Float, y: Float, sizePx: Float, rgba: Int, serif: Boolean, italic: Boolean, centred: Boolean)
    /** Whole-image soft bloom. */
    fun blur(radiusPx: Float)
    /** RGBA8888. */
    fun end(): ByteArray
}

/** Run on HKLoader. */
class TextureRecipe(val name: String, val width: Int, val height: Int, val paint: (Painter2D) -> Unit)

interface InstrumentScene {
    val id: InstrumentId; val anchors: InstrumentAnchors; val skin: SkinParams
    /** HKLoader. */
    fun meshes(): List<BakedMesh>
    fun textures(): List<TextureRecipe>
    /** GLThread, allocation-free. out = 136 floats = 34 vec4, §5.8. */
    fun packActionSet(pose: MechanismPose, xCutKey: Float, out: FloatArray)
}

class LightRig { @JvmField val pos = FloatArray(12); @JvmField val rgb = FloatArray(12); @JvmField var flicker = 1f }

interface FlameField {
    val maxSprites: Int
    /** eyeRoom = eye in the room frame (WP6 applies the Placement for piano-frame cameras); out = 8 floats per sprite: xyz size rgb alpha. */
    fun update(tSec: Float, eyeRoom: FloatArray, q: QualityProfile, level: RoomLevel, out: FloatArray): Int
    fun lights(tSec: Float, out: LightRig)
}

interface VenueScene {
    val geometry: VenueGeometry
    fun meshes(palette: Palette): List<BakedMesh>
    fun textures(): List<TextureRecipe>
    fun flames(): FlameField
    /** out = 128 * 64 * 4 bytes. */
    fun bakeProbe(centerRoom: FloatArray, out: ByteArray)
}

interface SceneFactory { fun instrument(id: InstrumentId, look: InstrumentLook, lastDamper: Int): InstrumentScene; fun venue(): VenueScene }

// mesh/MeshBuilder.kt (WP7; WP0 ships box/quad/vertex/tri on day 0):
//   class MeshBuilder(layout: VertexLayout, capacityVerts: Int) { part(slot, lane); color(rgb: IntArray, a = 1f); vertex(pos, nrm, uv): Int; tri(a, b, c);
//     box(…); quad(…); extrude(outlineXZ, y0, y1, capTop, capBottom); lathe(profileRY, segments, cx, cz); sweep(pathXYZ, profileXY, closed);
//     ribbon(pathXYZ, widthM); spindle(a, b, segments, rgb); build(name, material, skin, levelMask, viewMask, clipped, program, drawSlot): List<BakedMesh> }
