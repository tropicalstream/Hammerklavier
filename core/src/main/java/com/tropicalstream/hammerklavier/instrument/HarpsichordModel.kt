package com.tropicalstream.hammerklavier.instrument

import com.tropicalstream.hammerklavier.contract.BakedMesh
import com.tropicalstream.hammerklavier.contract.HK
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
import kotlin.math.pow

/**
 * The Flemish-style single manual (PLAN §5.4, §1.1): 228 × 93 × 26 cm, 61 keys FF–f‴ (0.818 m)
 * between two 40 mm cheek blocks inside 12 mm case sides; painted case (FLEMISH_CASE), papered
 * inside, lid with the motto, turned oak stand; bone naturals, black-stained sharps; 6 mm dip;
 * 122 jacks (8′ back row, 4′ front row) each with tongue, quill and cloth damper; two register
 * slides; 61 8′ and 61 4′ strings on their own bridges; painted soundboard with a gilt rose.
 */
object HarpsichordModel {
    const val WIDTH = 0.93f; const val LENGTH = 2.28f; const val CASE_H = 0.26f
    const val CHEEK = 0.040f; const val SIDE = 0.012f
    const val CASE_BOTTOM = 0.64f; const val CASE_TOP = CASE_BOTTOM + CASE_H     // 0.90
    const val KEY_TOP = 0.78f
    const val X0 = -WIDTH / 2f
    const val BALANCE_Z = -0.165f                   // midway to the jacks: jack rise = key travel (ratio 1.0)
    const val KEY_MAX_RAD = 0.006f / 0.165f
    const val JACK8_Z = -0.328f; const val JACK4_Z = -0.312f
    const val STRING8_Y = 0.875f; const val STRING4_Y = 0.862f
    const val NUT8_Z = -0.24f; const val NUT4_Z = -0.27f
    const val SOUNDBOARD_Y = 0.84f
    const val SOUNDBOARD_FRONT_Z = -0.345f
    const val JACK_TRAVEL = 0.006f; const val REGISTER_OFFSET = 0.0015f
    const val TONGUE_MAX_RAD = 0.35f
    const val JACK_BOTTOM = KEY_TOP - 0.004f
    const val TONGUE_PIVOT_Y = 0.895f
    const val LID_T = 0.015f
    const val LID_OPEN_RAD = 0.8727f                // 50°
    /** Rose centre (piano frame). */
    const val ROSE_X = 0.08f; const val ROSE_Z = -1.15f

    /** Plan points (u across, v along) of the case: straight spine and cheek, bentside, angled tail. */
    val PLAN = floatArrayOf(1f, 0.25f, 0.88f, 0.42f, 0.66f, 0.62f, 0.44f, 0.80f, 0.28f, 0.94f, 0.22f, 1.0f)

    fun outline(): FloatArray {
        val bent = Geo.planChain(PLAN, 6, X0, WIDTH, 0f, LENGTH)
        val out = FloatArray(bent.size + 6)
        out[0] = X0 + WIDTH; out[1] = 0f
        System.arraycopy(bent, 0, out, 2, bent.size)
        out[bent.size + 2] = X0; out[bent.size + 3] = -0.955f * LENGTH
        out[bent.size + 4] = X0; out[bent.size + 5] = 0f
        return Geo.ccw(out)
    }

    fun keyboardSpec(): KeyboardSpec = KeyboardSpec(
        lowKey = 29, highKey = 89, headM = 0.0227f, topY = KEY_TOP,
        whiteMaterial = MaterialId.BONE, whiteRgb = Pal.BONE, whiteSideRgb = Geo.mul(Pal.BONE, 0.75f), frontRgb = Geo.mul(Pal.BONE, 0.6f),
        blackRgb = Pal.EBONY_KEY, blackSideRgb = Geo.mul(Pal.EBONY_RIM, 0.4f),
        pivotY = KEY_TOP - 0.01f, pivotZ = BALANCE_Z, maxRad = KEY_MAX_RAD)

    fun create(look: InstrumentLook): InstrumentSceneImpl {
        val profile = InstrumentProfile.HARPSICHORD
        val kb = Keyboard(keyboardSpec())
        val skin = SkinParams(mapOf(
            SkinKind.KEY_ROT to kb.skinParams,
            SkinKind.JACK_LIFT to floatArrayOf(JACK_TRAVEL, REGISTER_OFFSET),
            SkinKind.JACK4_LIFT to floatArrayOf(JACK_TRAVEL, REGISTER_OFFSET),
            SkinKind.LID to floatArrayOf(X0, CASE_TOP, 0f, LID_OPEN_RAD),
            SkinKind.STRING to floatArrayOf(2.5f, 3.5f, 1.5f),
            SkinKind.ACTION_SET to ACTION_PIVOTS))
        val rim = outline()
        val inner = Geo.inset(rim, SIDE)
        val sbPoly = Geo.clipZBelow(inner, SOUNDBOARD_FRONT_Z)
        val rect = sbRect(sbPoly)
        return InstrumentSceneImpl(InstrumentId.HARPSICHORD, kb, skin,
            buildMeshes = { meshes(kb, profile, look, rim, inner, sbPoly, rect) },
            buildTextures = {
                listOf(InstrumentTextures.harpsiPaper(), InstrumentTextures.harpsiLid(),
                    InstrumentTextures.harpsiSoundboard((ROSE_X - rect[0]) / (rect[2] - rect[0]), (ROSE_Z - rect[1]) / (rect[3] - rect[1])))
            },
            packer = HarpsichordActionPacker(profile, kb.keyX))
    }

    private fun sbRect(p: FloatArray): FloatArray {
        var x0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        for (i in 0 until p.size / 2) { x0 = minOf(x0, p[2 * i]); x1 = maxOf(x1, p[2 * i]); z0 = minOf(z0, p[2 * i + 1]); z1 = maxOf(z1, p[2 * i + 1]) }
        return floatArrayOf(x0, z0, x1, z1)
    }

    /** 8′ and 4′ strings: 8′ 356 mm at c″ doubling per octave, foreshortened below c′; 4′ half; both capped by the case. */
    fun spans(kb: Keyboard, profile: InstrumentProfile, inner: FloatArray): List<StringSpan> {
        val out = ArrayList<StringSpan>()
        for (k in profile.lowKey..profile.highKey) {
            val ideal8 = if (k >= 60) 0.356f * 2f.pow((72 - k) / 12f) else 0.356f * 2f.pow(1f) * 2f.pow((60 - k) / 12f * 0.72f)
            val x8 = kb.keyX[k] + 0.005f
            val l8 = minOf(ideal8, Geo.rayExit(inner, x8, NUT8_Z, 0f, -1f) - 0.05f)
            out.add(StringSpan(k, floatArrayOf(x8, STRING8_Y, NUT8_Z), floatArrayOf(x8, STRING8_Y, NUT8_Z - l8), false))
        }
        for (k in profile.lowKey..profile.highKey) {
            val x4 = kb.keyX[k] - 0.005f
            val ideal4 = 0.5f * (if (k >= 60) 0.356f * 2f.pow((72 - k) / 12f) else 0.712f * 2f.pow((60 - k) / 12f * 0.72f))
            val l4 = minOf(maxOf(ideal4, 0.10f), Geo.rayExit(inner, x4, NUT4_Z, 0f, -1f) - 0.30f)
            out.add(StringSpan(k, floatArrayOf(x4, STRING4_Y, NUT4_Z), floatArrayOf(x4, STRING4_Y, NUT4_Z - l4), false))
        }
        return out
    }

    fun meshes(kb: Keyboard, profile: InstrumentProfile, look: InstrumentLook, rim: FloatArray, inner: FloatArray,
               sbPoly: FloatArray, sbRect: FloatArray): List<BakedMesh> {
        val out = ArrayList<BakedMesh>()
        val kw = kb.widthM / 2f
        val case = Pal.FLEMISH_CASE
        val paper = Geo.mul(Pal.FLEMISH_PAPER, 0.6f)
        val frontEdge = run {
            val n = rim.size / 2
            var e = -1
            for (i in 0 until n) { val j = (i + 1) % n; if (rim[2 * i + 1] == 0f && rim[2 * j + 1] == 0f) e = i }
            e
        }

        // ── Case (slot 7): walls (papered inside), bottom, front board, cheeks, key bed ──
        val c = MeshBuilder(VertexLayout.STATIC, 4096)
        Geo.wall(c, rim, SIDE, CASE_BOTTOM, CASE_TOP, frontEdge, outerRgb = case, innerRgb = paper, topRgb = Geo.mul(case, 0.8f))
        c.color(case)
        Geo.flat(c, rim, CASE_BOTTOM, up = false)
        c.box(X0, CASE_BOTTOM, -0.012f, -X0, KEY_TOP - 0.035f, 0f)                                  // front board below the keys
        c.box(-kw - CHEEK, CASE_BOTTOM, -0.30f, -kw, 0.82f, 0f); c.box(kw, CASE_BOTTOM, -0.30f, kw + CHEEK, 0.82f, 0f)   // cheek blocks
        c.box(-kw, KEY_TOP - 0.035f, -0.30f, kw, KEY_TOP - 0.02f, -0.012f)                         // key bed
        out += c.build("harpsichord.case", MaterialId.FLEMISH_CASE, SkinKind.STATIC, VM.LEVELS_ALL, VM.ALL, true, ProgramId.LIT, 7)

        // Stand (slot 7): six turned oak legs and stretchers.
        val st = MeshBuilder(VertexLayout.STATIC, 2048)
        st.color(Pal.OAK)
        val legs = arrayOf(floatArrayOf(-0.40f, -0.10f), floatArrayOf(0.40f, -0.10f), floatArrayOf(-0.40f, -1.00f),
            floatArrayOf(0.25f, -1.00f), floatArrayOf(-0.40f, -2.00f), floatArrayOf(-0.22f, -2.00f))
        for (l in legs) st.lathe(floatArrayOf(0.028f, 0f, 0.028f, 0.05f, 0.020f, 0.12f, 0.034f, 0.26f, 0.018f, 0.40f,
            0.030f, 0.52f, 0.026f, CASE_BOTTOM - 0.04f, 0.030f, CASE_BOTTOM), 10, l[0], l[1])
        st.box(-0.42f, 0.06f, -2.02f, -0.38f, 0.10f, -0.08f)
        st.box(-0.42f, 0.06f, -0.12f, 0.42f, 0.10f, -0.08f)
        st.box(0.23f, 0.06f, -1.02f, 0.27f, 0.10f, -0.12f)
        st.box(-0.42f, CASE_BOTTOM - 0.06f, -2.02f, -0.38f, CASE_BOTTOM, -0.08f)
        st.box(-0.42f, CASE_BOTTOM - 0.06f, -0.12f, 0.42f, CASE_BOTTOM, -0.08f)
        out += st.build("harpsichord.stand", MaterialId.WOOD_CASE, SkinKind.STATIC, VM.LEVELS_ALL, VM.ALL, true, ProgramId.LIT, 7)

        // Nameboard (slot 7): a block-printed paper over the keys.
        val nb = MeshBuilder(VertexLayout.STATIC, 8)
        nb.color(intArrayOf(255, 255, 255))
        Geo.quadZ(nb, -kw, KEY_TOP + 0.002f, kw, 0.86f, -0.150f)
        out += nb.build("harpsichord.nameboard", MaterialId.PAPER, SkinKind.STATIC, VM.LEVELS_ALL, VM.ALL, true, ProgramId.LIT, 7,
            texture = InstrumentTextures.HARPSI_PAPER)

        // ── Lid (slot 8, not in Overhead), stick; its inside carries the motto ──
        val lid = MeshBuilder(VertexLayout.STATIC, 512)
        lid.color(case)
        lid.extrude(rim, CASE_TOP, CASE_TOP + LID_T, capTop = true, capBottom = true)
        lid.rotateZ(X0, CASE_TOP, LID_OPEN_RAD)           // baked open (integrator M7: LIT_VS does not skin LID)
        out += lid.build("harpsichord.lid", MaterialId.FLEMISH_CASE, SkinKind.LID, VM.LEVELS_ALL, VM.NO_OVER, true, ProgramId.LIT, 8)
        val motto = MeshBuilder(VertexLayout.STATIC, 8)
        motto.color(intArrayOf(255, 255, 255))
        run {
            // Underside (facing −y), u along the lid toward the tail, v across.
            val x0 = X0 + 0.06f; val x1 = 0.20f; val z0 = -0.35f; val z1 = -1.75f; val y = CASE_TOP - 0.0005f
            val a = motto.vertex(x0, y, z0, 0f, -1f, 0f, 0f, 1f); val b = motto.vertex(x1, y, z0, 0f, -1f, 0f, 0f, 0f)
            val cc = motto.vertex(x1, y, z1, 0f, -1f, 0f, 1f, 0f); val d = motto.vertex(x0, y, z1, 0f, -1f, 0f, 1f, 1f)
            motto.triOutward(a, b, cc); motto.triOutward(a, cc, d)
        }
        motto.rotateZ(X0, CASE_TOP, LID_OPEN_RAD)
        out += motto.build("harpsichord.lid.motto", MaterialId.PAPER, SkinKind.LID, VM.LEVELS_ALL, VM.NO_OVER, true, ProgramId.LIT, 8,
            texture = InstrumentTextures.HARPSI_LID)
        // ── Soundboard with rose (slot 9, textured), bridges, nuts, wrestplank, register slides, pins ──
        val spans = spans(kb, profile, inner)
        val sb = MeshBuilder(VertexLayout.STATIC, 512)
        sb.color(intArrayOf(255, 255, 255))
        Geo.flat(sb, sbPoly, SOUNDBOARD_Y, up = true, uvRect = sbRect)
        out += sb.build("harpsichord.soundboard", MaterialId.HARPSI_SOUNDBOARD, SkinKind.STATIC, VM.LEVELS_ALL, VM.ACTION_HALL, true,
            ProgramId.LIT, 9, texture = InstrumentTextures.HARPSI_SOUNDBOARD)
        val wood = MeshBuilder(VertexLayout.STATIC, 4096)
        wood.color(Geo.mul(Pal.ACTION_WOOD, 0.6f))
        val n = profile.highKey - profile.lowKey + 1
        for (g in 0..1) {
            val grp = spans.subList(g * n, (g + 1) * n)
            val path = FloatArray(n * 2) { i -> if (i % 2 == 0) grp[i / 2].b[0] else grp[i / 2].b[2] }
            wood.extrude(Geo.strip(path, 0.007f), SOUNDBOARD_Y, grp[0].b[1] - 0.001f, capTop = true, capBottom = false)
        }
        wood.color(paper)
        wood.box(-kw - CHEEK, 0.82f, -0.30f, kw + CHEEK, 0.86f, -0.150f)                 // wrestplank (papered)
        wood.color(Geo.mul(Pal.ACTION_WOOD, 0.7f))
        wood.box(-kw, 0.86f, NUT8_Z - 0.006f, kw, STRING8_Y - 0.001f, NUT8_Z + 0.006f)   // 8′ nut
        wood.box(-kw, 0.86f, NUT4_Z - 0.006f, kw, STRING4_Y - 0.001f, NUT4_Z + 0.006f)   // 4′ nut
        wood.color(Pal.ACTION_WOOD)
        wood.box(-kw, 0.84f, JACK4_Z - 0.006f, kw, 0.85f, JACK4_Z + 0.006f)              // front register slide (4′)
        wood.box(-kw, 0.84f, JACK8_Z - 0.006f, kw, 0.85f, JACK8_Z + 0.006f)              // back register slide (8′)
        wood.color(Pal.STEEL_BASE)
        for (s in spans) {
            val z = s.a[2] + 0.025f
            wood.box(s.a[0] - 0.0012f, 0.86f, z - 0.0012f, s.a[0] + 0.0012f, 0.878f, z + 0.0012f)
        }
        out += wood.build("harpsichord.bridges", MaterialId.ACTION_WOOD, SkinKind.STATIC, VM.LEVELS_ALL, VM.ACTION_HALL, true, ProgramId.LIT, 9)
        val rail = MeshBuilder(VertexLayout.STATIC, 32)
        rail.color(case)
        rail.box(-kw - CHEEK, 0.95f, -0.345f, kw + CHEEK, 0.97f, -0.295f)
        rail.color(Pal.OAK)
        rail.box(0.02f, CASE_TOP, -1.01f, 0.04f, 1.49f, -0.99f)                            // lid stick (static, at the open lid)
        out += rail.build("harpsichord.jackrail", MaterialId.FLEMISH_CASE, SkinKind.STATIC, VM.LEVELS_ALL, VM.NO_OVER, true, ProgramId.LIT, 7)

        // ── Keys (slot 10) ──
        out += kb.meshes(clipped = true)

        // ── Jacks (8′ slot 11, 4′ slot 12): body, cloth damper, tongue and quill all lift together ──
        out += jacks(kb, profile, JACK8_Z, STRING8_Y, +1f, SkinKind.JACK_LIFT, 11, "harpsichord.jacks8")
        out += jacks(kb, profile, JACK4_Z, STRING4_Y, -1f, SkinKind.JACK4_LIFT, 12, "harpsichord.jacks4")

        // ── Strings (slot 14), action set (16), caps (17), edges (18) ──
        out += StringsMesh.build(spans, profile.lowKey, "harpsichord.strings", VM.ACTION_HALL, true)
        out += actionSet()
        val cap = MeshBuilder(VertexLayout.STATIC, 16)
        cap.color(Pal.SECTION_CAP)
        GrandCase.sectionQuad(cap, KEY_TOP - 0.035f, KEY_TOP - 0.02f, -0.30f, -0.012f)
        GrandCase.sectionQuad(cap, CASE_BOTTOM, KEY_TOP - 0.035f, -0.012f, 0f)
        GrandCase.sectionQuad(cap, 0.82f, 0.86f, -0.30f, -0.150f)
        out += cap.build("harpsichord.sectioncaps", MaterialId.SECTION_CAP, SkinKind.STATIC, VM.LEVELS_ALL, VM.CUT, false, ProgramId.SECTION_CAP, 17)
        val edge = MeshBuilder(VertexLayout.STATIC, 512)
        edge.color(Geo.mul(Pal.GILT_LIT, 0.6f))
        edge.ribbon(GrandCase.to3(rim, CASE_TOP + LID_T), 0.002f, closed = true)
        edge.ribbon(GrandCase.to3(rim, CASE_BOTTOM), 0.002f, closed = true)
        out += edge.build("harpsichord.edges", MaterialId.EDGE_GILT, SkinKind.STATIC, VM.edgeLevels(look), VM.ALL, true, ProgramId.RIBBON, 18)
        return out
    }

    /**
     * One row of 61 jacks, one lift-skinned draw: the body, cloth damper, tongue and quill share the
     * jack's lane, so the tongue rises with its jack (one slot and one lane per vertex, §2.3). The
     * tongue sits at its rest pose here; its tilt is shown only in the Action cutaway, by the action
     * set (parts 1 and 5). The quill points toward [side]·x.
     */
    private fun jacks(kb: Keyboard, profile: InstrumentProfile, z: Float, stringY: Float, side: Float,
                      lift: SkinKind, slot: Int, name: String): List<BakedMesh> {
        val body = MeshBuilder(VertexLayout.SKINNED, 61 * 96)
        val tongue = body
        for (k in profile.lowKey..profile.highKey) {
            val part = k - profile.lowKey
            val x = kb.keyX[k]
            body.part(part / 4, part % 4)
            body.color(Pal.ACTION_WOOD)
            body.box(x - 0.002f, JACK_BOTTOM, z - 0.005f, x + 0.002f, stringY + 0.030f, z + 0.005f)
            body.color(Pal.CLOTH_RED)
            val sx = x + side * 0.005f
            body.box(minOf(x, sx + side * 0.003f), stringY + 0.001f, z - 0.004f, maxOf(x, sx + side * 0.003f), stringY + 0.009f, z + 0.004f)
            tongue.color(Geo.mul(Pal.ACTION_WOOD, 0.8f))
            val ty = stringY + (TONGUE_PIVOT_Y - STRING8_Y)
            tongue.box(x - 0.0015f, stringY - 0.018f, z - 0.0035f, x + 0.0015f, ty, z - 0.0005f)
            tongue.color(Pal.FELT)
            tongue.box(minOf(x, sx + side * 0.002f), stringY - 0.0035f, z - 0.0025f, maxOf(x, sx + side * 0.002f), stringY - 0.0015f, z - 0.0015f)
        }
        return body.build(name, MaterialId.ACTION_WOOD, lift, VM.LEVELS_ALL, VM.ACTION_HALL, true, ProgramId.SKINNED, slot)
    }

    /**
     * Part types: 0 8′ jack (lift, m), 1 8′ tongue + quill (rad), 2 the 8′ register offset (no
     * geometry), 3 key lever (rad), 4 4′ jack (lift, m), 5 4′ tongue + quill (rad).
     */
    fun actionSet(): List<BakedMesh> {
        val mb = MeshBuilder(VertexLayout.SKINNED, 13 * 6 * 48)
        for (s in 0 until ActionSetPacker.SLOTS) {
            fun part(p: Int) = mb.part(ActionSetPacker.slotOf(s, p), ActionSetPacker.laneOf(s, p))
            val u = s.toFloat()
            part(3); mb.color(Pal.KEYLEVER); Geo.cuboidUv(mb, -0.0045f, KEY_TOP - 0.02f, -0.36f, 0.0045f, KEY_TOP, -0.16f, u, 3f)
            for ((pj, pt, z, y) in arrayOf(Quad(0, 1, JACK8_Z, STRING8_Y), Quad(4, 5, JACK4_Z, STRING4_Y))) {
                part(pj); mb.color(Pal.ACTION_WOOD); Geo.cuboidUv(mb, -0.002f, JACK_BOTTOM, z - 0.005f, 0.002f, y + 0.030f, z + 0.005f, u, pj.toFloat())
                mb.color(Pal.CLOTH_RED); Geo.cuboidUv(mb, -0.002f, y + 0.001f, z - 0.004f, 0.008f, y + 0.009f, z + 0.004f, u, pj.toFloat())
                part(pt); mb.color(Pal.FELT); Geo.cuboidUv(mb, -0.0015f, y - 0.018f, z - 0.0035f, 0.007f, y - 0.0015f, z - 0.0005f, u, pt.toFloat())
            }
        }
        return mb.build("harpsichord.actionset", MaterialId.ACTION_WOOD, SkinKind.ACTION_SET, VM.LEVELS_ALL, VM.CUT, false, ProgramId.SKINNED, 16)
    }

    private data class Quad(val a: Int, val b: Int, val z: Float, val y: Float)

    /** axisSign: ±1 = rotation about +x; 0 = translation along +y (m); 2 = translation along +x (m). */
    val ACTION_PIVOTS = floatArrayOf(
        0f, 0f, 0f,                                       // 8′ jack: lift
        TONGUE_PIVOT_Y, JACK8_Z - 0.003f, 1f,             // 8′ tongue
        0f, 0f, 2f,                                       // 8′ register: sideways offset
        KEY_TOP - 0.01f, BALANCE_Z, 1f,                   // key lever
        0f, 0f, 0f,                                       // 4′ jack: lift
        TONGUE_PIVOT_Y - (STRING8_Y - STRING4_Y), JACK4_Z - 0.003f, 1f)   // 4′ tongue
}

/** vec4 33 = (8′ register offset m, 4′ register offset m, 0, 0): a disengaged register slides 1.5 mm. */
class HarpsichordActionPacker(profile: InstrumentProfile, keyX: FloatArray) : ActionSetPacker(profile, keyX) {
    override fun parts(pose: MechanismPose, k: Int, out: FloatArray, o: Int) {
        out[o] = pose.hammer[k] * HarpsichordModel.JACK_TRAVEL
        out[o + 1] = pose.tongue[k] * HarpsichordModel.TONGUE_MAX_RAD
        out[o + 2] = if (pose.registers and HK.REG_8 == 0) HarpsichordModel.REGISTER_OFFSET else 0f
        out[o + 3] = pose.keyDip[k] * HarpsichordModel.KEY_MAX_RAD
        out[o + 4] = pose.jack4[k] * HarpsichordModel.JACK_TRAVEL
        out[o + 5] = pose.tongue4[k] * HarpsichordModel.TONGUE_MAX_RAD
    }

    override fun spare(pose: MechanismPose, out: FloatArray, o: Int) {
        out[o] = if (pose.registers and HK.REG_8 == 0) HarpsichordModel.REGISTER_OFFSET else 0f
        out[o + 1] = if (pose.registers and HK.REG_4 == 0) HarpsichordModel.REGISTER_OFFSET else 0f
        out[o + 2] = 0f; out[o + 3] = 0f
    }
}
