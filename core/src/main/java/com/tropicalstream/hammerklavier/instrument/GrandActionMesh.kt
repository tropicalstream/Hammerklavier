package com.tropicalstream.hammerklavier.instrument

import com.tropicalstream.hammerklavier.contract.BakedMesh
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentLook
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.MaterialId
import com.tropicalstream.hammerklavier.contract.MechanismPose
import com.tropicalstream.hammerklavier.contract.Pal
import com.tropicalstream.hammerklavier.contract.ProgramId
import com.tropicalstream.hammerklavier.contract.SkinKind
import com.tropicalstream.hammerklavier.contract.SkinParams
import com.tropicalstream.hammerklavier.contract.VertexLayout
import com.tropicalstream.hammerklavier.instrument.tex.InstrumentTextures
import com.tropicalstream.hammerklavier.mesh.MeshBuilder

/**
 * The grand's moving parts (PLAN §5.4, §5.3 rows 11, 13, 15, 16): 88 hammers (heads 50 → 30 mm,
 * 133 mm strike radius, the flange behind the head), `lastDamper − 20` dampers (keys 21 …
 * lastDamper; wooden head over felt, wire), the sostenuto rail and the 13-slot action set.
 *
 * Rotation sense (all skinned rotations are about the +x axis through (pivotY, pivotZ), angle =
 * pose value × the kind's signed max): KEY_ROT maxRad > 0 dips the front; HAMMER_ROT blowRad < 0
 * lifts a head that lies in front (+z) of its flange; PEDAL_ROT maxRad > 0 presses the tip down.
 */
object GrandActionMesh {
    fun hammers(kb: Keyboard, profile: InstrumentProfile): List<BakedMesh> {
        val g = GrandDims
        val mb = MeshBuilder(VertexLayout.SKINNED, 88 * 72)
        for (k in profile.lowKey..profile.highKey) {
            val part = k - profile.lowKey
            mb.part(part / 4, part % 4)
            val x = kb.keyX[k]; val hw = kb.spec.pitchM * 0.40f
            val felt = 0.050f - 0.020f * part / (profile.highKey - profile.lowKey)
            val top = g.HAMMER_TOP_REST
            val zs = g.STRIKE_Z
            mb.color(Pal.FELT); mb.box(x - hw, top - felt, zs - 0.015f, x + hw, top, zs + 0.015f)
            mb.color(Pal.ACTION_WOOD); mb.box(x - hw, g.HAMMER_BOTTOM, zs - 0.012f, x + hw, top - felt, zs + 0.012f)
            mb.box(x - 0.0025f, g.SHANK_Y - 0.003f, g.PIVOT_Z, x + 0.0025f, g.SHANK_Y + 0.003f, zs - 0.012f)
        }
        return mb.build("grand.hammers", MaterialId.FELT, SkinKind.HAMMER_ROT, VM.LEVELS_ALL, VM.ACTION_HALL, true, ProgramId.SKINNED, 11)
    }

    /** Dampers for keys lowKey … min(lastDamper, highKey): lastDamper − 20 of them (68 for the Salamander kit). */
    fun dampers(kb: Keyboard, profile: InstrumentProfile, lastDamper: Int): List<BakedMesh> {
        val g = GrandDims
        val top = minOf(lastDamper, profile.highKey)
        val mb = MeshBuilder(VertexLayout.SKINNED, 88 * 72)
        for (k in profile.lowKey..top) {
            val part = k - profile.lowKey
            mb.part(part / 4, part % 4)
            val x = kb.keyX[k]; val hw = kb.spec.pitchM * 0.42f
            val y = if (k <= g.OVERSTRUNG_TOP) g.STRING_Y + 0.025f else g.STRING_Y
            mb.color(Pal.FELT); mb.box(x - hw, y + 0.001f, g.DAMPER_Z - 0.016f, x + hw, y + 0.010f, g.DAMPER_Z + 0.016f)
            mb.color(Pal.DAMPER_TOP); mb.box(x - hw, y + 0.010f, g.DAMPER_Z - 0.016f, x + hw, y + 0.032f, g.DAMPER_Z + 0.016f)
            mb.color(Pal.STEEL_BASE); mb.box(x - 0.001f, 0.705f, g.DAMPER_Z - 0.001f, x + 0.001f, y + 0.001f, g.DAMPER_Z + 0.001f)
        }
        return mb.build("grand.dampers", MaterialId.DAMPER_TOP, SkinKind.DAMPER_LIFT, VM.LEVELS_ALL, VM.ACTION, true, ProgramId.SKINNED, 13)
    }

    fun sostenutoRail(kb: Keyboard): List<BakedMesh> {
        val mb = MeshBuilder(VertexLayout.SKINNED, 32)
        mb.part(0, 0); mb.color(Pal.ACTION_WOOD)
        val kw = kb.widthM / 2f
        mb.box(-kw, SOST_Y - 0.005f, SOST_Z - 0.005f, kw, SOST_Y + 0.005f, SOST_Z + 0.005f)
        return mb.build("grand.sostenuto", MaterialId.ACTION_WOOD, SkinKind.SOSTENUTO_ROT, VM.LEVELS_ALL, VM.ACTION, true, ProgramId.SKINNED, 15)
    }

    const val SOST_Y = 0.805f; const val SOST_Z = -0.535f

    /**
     * The 13-slot action set (slot 16, cutaway only), built at x = 0: the shader adds the slot's
     * header xM. Part types: 0 key lever (with capstan and backcheck), 1 wippen, 2 jack,
     * 3 repetition lever, 4 hammer shank with knuckle, 5 damper underlever. uv = (s, p).
     */
    fun actionSet(): List<BakedMesh> {
        val mb = MeshBuilder(VertexLayout.SKINNED, 13 * 6 * 48)
        val w = 0.0045f
        for (s in 0 until ActionSetPacker.SLOTS) {
            fun part(p: Int) = mb.part(ActionSetPacker.slotOf(s, p), ActionSetPacker.laneOf(s, p))
            val u = s.toFloat()
            part(0); mb.color(Pal.KEYLEVER)
            Geo.cuboidUv(mb, -w, 0.695f, -0.48f, w, 0.715f, -0.16f, u, 0f)
            mb.color(Pal.BRASS_MID); Geo.cuboidUv(mb, -0.002f, 0.715f, -0.342f, 0.002f, 0.725f, -0.338f, u, 0f)
            mb.color(Pal.ACTION_WOOD); Geo.cuboidUv(mb, -0.002f, 0.715f, -0.312f, 0.002f, 0.76f, -0.306f, u, 0f)
            part(1); mb.color(Pal.ACTION_WOOD); Geo.cuboidUv(mb, -w, 0.725f, -0.44f, w, 0.733f, -0.33f, u, 1f)
            part(2); mb.color(Pal.ACTION_WOOD); Geo.cuboidUv(mb, -0.003f, 0.733f, -0.40f, 0.003f, 0.747f, -0.392f, u, 2f)
            part(3); mb.color(Pal.KEYLEVER); Geo.cuboidUv(mb, -0.003f, 0.735f, -0.43f, 0.003f, 0.740f, -0.37f, u, 3f)
            part(4); mb.color(Pal.ACTION_WOOD)
            Geo.cuboidUv(mb, -0.002f, GrandDims.SHANK_Y - 0.0025f, GrandDims.PIVOT_Z, 0.002f, GrandDims.SHANK_Y + 0.0025f, GrandDims.STRIKE_Z - 0.012f, u, 4f)
            mb.color(Pal.CLOTH_RED); Geo.cuboidUv(mb, -0.003f, 0.742f, -0.40f, 0.003f, 0.747f, -0.385f, u, 4f)
            part(5); mb.color(Pal.ACTION_WOOD); Geo.cuboidUv(mb, -w, 0.698f, -0.54f, w, 0.705f, -0.47f, u, 5f)
        }
        return mb.build("grand.actionset", MaterialId.ACTION_WOOD, SkinKind.ACTION_SET, VM.LEVELS_ALL, VM.CUT, false, ProgramId.SKINNED, 16)
    }

    /** SkinParams[ACTION_SET]: 6 × (pivotY, pivotZ, axisSign); angle about +x = value × axisSign. */
    val ACTION_PIVOTS = floatArrayOf(
        0.705f, GrandDims.BALANCE_Z, 1f,           // key lever: back up when the front dips
        0.729f, -0.44f, -1f,                       // wippen: flange at the back, lifted in front by the capstan
        0.733f, -0.396f, 1f,                       // jack: tilts out on escape
        0.737f, -0.43f, -1f,                       // repetition lever
        GrandDims.SHANK_Y, GrandDims.PIVOT_Z, -1f, // hammer shank: the head (in front) rises
        0.701f, -0.54f, -1f)                       // damper underlever: lifted in front by the key's end

    const val WIPPEN_RATIO = 0.805f                // capstan arm (80.5 mm behind the balance) / wippen arm (100 mm)
    const val BLOW_RAD = 0.3534f                   // 47 mm / 133 mm
    const val UNDERLEVER_RAD = 0.10f               // 6 mm at a 60 mm arm
}

class GrandActionPacker(profile: InstrumentProfile, keyX: FloatArray) : ActionSetPacker(profile, keyX) {
    override fun parts(pose: MechanismPose, k: Int, out: FloatArray, o: Int) {
        val key = pose.keyDip[k] * GrandDims.KEY_MAX_RAD
        out[o] = key
        out[o + 1] = key * GrandActionMesh.WIPPEN_RATIO
        out[o + 2] = pose.escape[k] * 0.20f
        out[o + 3] = pose.escape[k] * 0.06f
        out[o + 4] = pose.hammer[k] * GrandActionMesh.BLOW_RAD
        out[o + 5] = pose.damper[k] * GrandActionMesh.UNDERLEVER_RAD
    }
}

/** The grand: assembles keyboard, case, action and strings. */
object GrandModel {
    fun keyboardSpec(): KeyboardSpec = KeyboardSpec(
        lowKey = 21, highKey = 108, headM = 0.0235f, topY = GrandDims.KEY_TOP,
        whiteMaterial = MaterialId.IVORY, whiteRgb = Pal.IVORY, whiteSideRgb = Pal.IVORY_SIDE, frontRgb = Pal.IVORY_SIDE,
        blackRgb = Pal.EBONY_FLOOR, blackSideRgb = Geo.mul(Pal.EBONY_RIM, 0.4f),
        pivotY = 0.705f, pivotZ = GrandDims.BALANCE_Z, maxRad = GrandDims.KEY_MAX_RAD)

    fun create(look: InstrumentLook, lastDamper: Int): InstrumentSceneImpl {
        val profile = InstrumentProfile.GRAND.withLastDamper(lastDamper)
        val kb = Keyboard(keyboardSpec())
        val skin = SkinParams(mapOf(
            SkinKind.KEY_ROT to kb.skinParams,
            SkinKind.HAMMER_ROT to floatArrayOf(GrandDims.SHANK_Y, GrandDims.PIVOT_Z, -GrandActionMesh.BLOW_RAD),
            SkinKind.DAMPER_LIFT to floatArrayOf(GrandDims.DAMPER_TRAVEL, 0f, 1f, 0f),
            SkinKind.PEDAL_ROT to floatArrayOf(GrandDims.PEDAL_PIVOT_Y, GrandDims.PEDAL_PIVOT_Z, GrandDims.PEDAL_MAX_RAD),
            SkinKind.SOSTENUTO_ROT to floatArrayOf(GrandActionMesh.SOST_Y, GrandActionMesh.SOST_Z, 0.7854f, 1.5708f),
            SkinKind.LID to floatArrayOf(GrandDims.X0, GrandDims.RIM_TOP, 0f, GrandDims.LID_STICK_RAD),
            SkinKind.SHIFT_X to floatArrayOf(0.0025f),
            SkinKind.STRING to floatArrayOf(2.5f, 3.5f, 1.5f),
            SkinKind.ACTION_SET to GrandActionMesh.ACTION_PIVOTS))
        return InstrumentSceneImpl(InstrumentId.GRAND, kb, skin,
            buildMeshes = {
                val inner = Geo.inset(GrandDims.rimOutline(), GrandDims.RIM_T)
                val spans = StringsMesh.grandSpans(kb, profile, inner)
                GrandCase.meshes(kb, look, spans) + kb.meshes(clipped = true) +
                    GrandActionMesh.hammers(kb, profile) + GrandActionMesh.dampers(kb, profile, lastDamper) +
                    StringsMesh.build(spans, profile.lowKey, "grand.strings", VM.ACTION_HALL, true) +
                    GrandActionMesh.sostenutoRail(kb) + GrandActionMesh.actionSet()
            },
            buildTextures = { listOf(InstrumentTextures.fallboard()) },
            packer = GrandActionPacker(profile, kb.keyX))
    }
}
