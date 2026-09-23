package com.tropicalstream.hammerklavier.instrument

import com.tropicalstream.hammerklavier.contract.BakedMesh
import com.tropicalstream.hammerklavier.contract.InstrumentLook
import com.tropicalstream.hammerklavier.contract.MaterialId
import com.tropicalstream.hammerklavier.contract.Pal
import com.tropicalstream.hammerklavier.contract.ProgramId
import com.tropicalstream.hammerklavier.contract.SkinKind
import com.tropicalstream.hammerklavier.contract.VertexLayout
import com.tropicalstream.hammerklavier.instrument.tex.InstrumentTextures
import com.tropicalstream.hammerklavier.mesh.MeshBuilder

/** The grand's dimensions (PLAN §5.4 "Grand (C5, 200 × 149 × 101 cm)"), piano frame, metres. */
object GrandDims {
    const val WIDTH = 1.49f; const val LENGTH = 2.00f
    const val X0 = -WIDTH / 2f
    const val FRONT_Z = -0.01f                 // case front (cheeks); key fronts at 0
    const val KEY_TOP = 0.715f
    const val RIM_BOTTOM = 0.66f; const val RIM_TOP = 0.96f       // extruded 0.30 m
    const val RIM_T = 0.05f
    const val RIM_FRONT_V = 0.10f              // the rim starts behind the key well (v = 0.10 → z = −0.21)
    const val LID_T = 0.02f
    const val LID_STICK_RAD = 0.6632f          // 38°
    const val STRING_Y = 0.845f
    const val FRONT_TERM_Z = -0.265f
    const val STRIKE_Z = -0.30f
    const val BLOW = 0.047f
    const val HAMMER_R = 0.133f
    const val HAMMER_TOP_REST = STRING_Y - BLOW  // 0.798
    const val HAMMER_BOTTOM = 0.753f
    const val SHANK_Y = 0.750f
    const val PIVOT_Z = STRIKE_Z - HAMMER_R      // −0.433: the flange is behind the head
    const val DAMPER_Z = -0.50f
    const val DAMPER_TRAVEL = 0.006f
    const val BALANCE_Z = -0.2595f
    const val KEY_MAX_RAD = 0.0391f            // 2.24° full dip
    const val OVERSTRUNG_TOP = 40
    const val OVERSTRING_RAD = 0.31416f        // 18°
    const val SOUNDBOARD_Y = 0.76f
    const val PLATE_Y0 = 0.80f; const val PLATE_Y1 = 0.815f
    const val PEDAL_PIVOT_Y = 0.09f; const val PEDAL_PIVOT_Z = -0.36f; const val PEDAL_TIP_Z = -0.22f
    const val PEDAL_MAX_RAD = 0.08727f         // 5°

    /** Plan points (u across 0..1 → x, v along 0..1 → −z) of §5.4. */
    val PLAN = floatArrayOf(0f, 0f, 1f, 0f, 1f, .16f, .93f, .30f, .80f, .45f, .70f, .58f, .66f, .70f, .62f, .82f, .52f, .94f,
        .35f, 1f, .12f, .99f, 0f, .93f)

    /**
     * The rim outline (x, z), CCW: the bentside chain from the treble cheek (1, 0.10) through the
     * §5.4 plan points to the tail (0, 0.93) as an open Catmull-Rom curve, then the straight spine
     * back to (0, 0.10); the front edge (the key-well side, edge index [frontEdge]) closes it.
     */
    fun rimOutline(): FloatArray {
        val chain = ArrayList<Float>()
        chain.add(1f); chain.add(RIM_FRONT_V)
        var i = 4
        while (i < PLAN.size) { chain.add(PLAN[i]); chain.add(PLAN[i + 1]); i += 2 }
        val pts = Geo.planChain(chain.toFloatArray(), 6, X0, WIDTH, FRONT_Z, LENGTH)
        val out = FloatArray(pts.size + 2)
        System.arraycopy(pts, 0, out, 0, pts.size)
        out[pts.size] = X0; out[pts.size + 1] = FRONT_Z - RIM_FRONT_V * LENGTH
        return Geo.ccw(out)
    }

    /** Index of the front (key-well) edge in a CCW outline: the edge whose both ends lie on the front line. */
    fun frontEdge(ccw: FloatArray): Int {
        val zf = FRONT_Z - RIM_FRONT_V * LENGTH
        val n = ccw.size / 2
        for (e in 0 until n) {
            val j = (e + 1) % n
            if (kotlin.math.abs(ccw[2 * e + 1] - zf) < 1e-4f && kotlin.math.abs(ccw[2 * j + 1] - zf) < 1e-4f &&
                kotlin.math.abs(ccw[2 * e] - ccw[2 * j]) > 0.5f) return e
        }
        return -1
    }
}

/**
 * Grand case, lid, legs, lyre, pedals, plate, soundboard, fallboard, section caps and the edge
 * overlay (PLAN §5.4, §5.3 rows 7–9, 15, 17, 18). Colours from §5.9 (ebony lacquer = EBONY_FLOOR,
 * lifted by the lacquer shader's presence floor and rim).
 */
object GrandCase {
    fun meshes(kb: Keyboard, look: InstrumentLook, spans: List<StringSpan>): List<BakedMesh> {
        val out = ArrayList<BakedMesh>()
        val g = GrandDims
        val rim = g.rimOutline()
        val front = g.frontEdge(rim)
        val kw = kb.widthM / 2f
        val lac = Pal.EBONY_FLOOR
        val zRimFront = g.FRONT_Z - g.RIM_FRONT_V * g.LENGTH

        // ── Case (slot 7): rim, bottom, cheeks, key bed, fallboard, music desk, legs, lyre ──
        val c = MeshBuilder(VertexLayout.STATIC, 4096)
        Geo.wall(c, rim, g.RIM_T, g.RIM_BOTTOM, g.RIM_TOP, front, outerRgb = lac, innerRgb = Geo.mul(Pal.EBONY_RIM, 0.35f), topRgb = lac)
        c.color(lac)
        Geo.flat(c, rim, g.RIM_BOTTOM, up = false)
        c.box(-(-g.X0 - g.RIM_T), g.RIM_BOTTOM, zRimFront - g.RIM_T, -g.X0 - g.RIM_T, BELLY_TOP, zRimFront)   // belly rail under the pin block
        c.box(g.X0, 0.62f, zRimFront, -kw, 0.78f, g.FRONT_Z)                     // bass cheek
        c.box(kw, 0.62f, zRimFront, -g.X0, 0.78f, g.FRONT_Z)                     // treble cheek
        c.box(-kw, 0.62f, zRimFront, kw, 0.66f, g.FRONT_Z)                       // key bed
        c.box(-kw, 0.66f, zRimFront, kw, 0.695f, -0.17f)                         // key frame under the fallboard
        for (leg in LEGS) {
            c.lathe(floatArrayOf(0.030f, 0.06f, 0.036f, 0.30f, 0.045f, 0.52f, 0.062f, 0.60f, 0.062f, g.RIM_BOTTOM), 14, leg[0], leg[1])
        }
        c.box(-0.09f, 0.12f, -0.42f, -0.07f, 0.62f, -0.39f); c.box(0.07f, 0.12f, -0.42f, 0.09f, 0.62f, -0.39f)   // lyre posts
        c.box(-0.16f, 0.05f, -0.44f, 0.16f, 0.12f, -0.36f)                                                       // pedal box
        out += c.build("grand.case", MaterialId.LACQUER, SkinKind.STATIC, VM.LEVELS_ALL, VM.ALL, true, ProgramId.LACQUER, 7)

        // Fallboard, key-well back and music desk: off in the Action framings, where they stand between
        // the camera and the action (the cutaway removes them as a drawing would; Overhead has the lid off).
        val fb = MeshBuilder(VertexLayout.STATIC, 64)
        fb.color(lac)
        fb.box(-kw, g.KEY_TOP, -0.19f, kw, 0.80f, -0.15f)                        // fallboard
        fb.box(-kw, 0.80f, -0.21f, kw, g.RIM_TOP, -0.19f)                        // key-well back (behind the fallboard)
        fb.box(-0.40f, g.RIM_TOP, -0.30f, 0.40f, 1.26f, -0.27f)                  // music desk
        out += fb.build("grand.fallboard", MaterialId.LACQUER, SkinKind.STATIC, VM.LEVELS_ALL, VM.PLAYER_HALL, true, ProgramId.LACQUER, 7)

        val casters = MeshBuilder(VertexLayout.STATIC, 512)
        casters.color(Pal.BRASS_MID)
        for (leg in LEGS) casters.lathe(floatArrayOf(0.001f, 0.0f, 0.028f, 0.01f, 0.03f, 0.04f, 0.022f, 0.06f), 12, leg[0], leg[1])
        out += casters.build("grand.casters", MaterialId.BRASS, SkinKind.STATIC, VM.LEVELS_ALL, VM.ALL, true, ProgramId.LIT, 7)

        val decal = MeshBuilder(VertexLayout.STATIC, 8)
        decal.color(intArrayOf(255, 255, 255))
        Geo.quadZ(decal, -0.13f, 0.745f, 0.13f, 0.775f, -0.1495f)
        out += decal.build("grand.fallboard.lettering", MaterialId.GILT_EMISSIVE, SkinKind.STATIC, VM.LEVELS_ALL, VM.PLAYER_HALL, true,
            ProgramId.DECAL, 7, texture = InstrumentTextures.FALLBOARD)

        // ── Lid (slot 8, own transform, not in Overhead) and its stick ──
        val lid = MeshBuilder(VertexLayout.STATIC, 512)
        lid.color(lac)
        lid.extrude(rim, g.RIM_TOP, g.RIM_TOP + g.LID_T, capTop = true, capBottom = true)
        out += lid.build("grand.lid", MaterialId.LACQUER, SkinKind.LID, VM.LEVELS_ALL, VM.NO_OVER, true, ProgramId.LACQUER, 8)
        val stick = MeshBuilder(VertexLayout.STATIC, 32)
        stick.color(lac)
        stick.box(0.27f, g.RIM_TOP, -1.015f, 0.295f, 1.78f, -0.99f)
        out += stick.build("grand.lidstick", MaterialId.LACQUER, SkinKind.STATIC, VM.LEVELS_ALL, VM.NO_OVER, true, ProgramId.LACQUER, 8)

        // ── Plate, pins (slot 9) ──
        val inner = Geo.inset(rim, g.RIM_T + 0.005f)
        val plate = MeshBuilder(VertexLayout.STATIC, 8192)
        Geo.wall(plate, inner, 0.07f, g.PLATE_Y0, g.PLATE_Y1, outerRgb = Pal.PLATE_GOLD)
        plate.color(Pal.PLATE_GOLD)
        val struts = floatArrayOf(-0.42f, -0.12f, 0.18f, 0.44f)
        for (sx in struts) {
            val zEnd = -0.40f - Geo.rayExit(inner, sx, -0.40f, 0f, -1f) + 0.07f
            plate.box(sx - 0.02f, g.PLATE_Y0, zEnd, sx + 0.02f, g.PLATE_Y1 + 0.015f, -0.40f)
        }
        plate.box(-kw - 0.06f, 0.855f, -0.262f, kw + 0.06f, 0.875f, -0.212f)                      // pin-block bar over the capo
        plate.color(Pal.STEEL_BASE)
        var row = 0
        for (s in spans) {
            val z = if (row % 2 == 0) -0.222f else -0.242f
            plate.box(s.a[0] - 0.0015f, 0.875f, z - 0.0015f, s.a[0] + 0.0015f, 0.895f, z + 0.0015f)
            row++
        }
        out += plate.build("grand.plate", MaterialId.PLATE, SkinKind.STATIC, VM.LEVELS_ALL, VM.ACTION_HALL, true, ProgramId.LIT, 9)

        // Gilt ribbons round the lightening holes (the plate band's inner edge, both edges of each strut).
        val gilt = MeshBuilder(VertexLayout.STATIC, 1024)
        gilt.color(Pal.GILT_LIT)
        val holeEdge = Geo.inset(inner, 0.075f)
        gilt.ribbon(to3(holeEdge, g.PLATE_Y1 + 0.001f), 0.004f, closed = true)
        for (sx in struts) {
            val zEnd = -0.40f - Geo.rayExit(inner, sx, -0.40f, 0f, -1f) + 0.07f
            for (ex in floatArrayOf(sx - 0.021f, sx + 0.021f))
                gilt.ribbon(floatArrayOf(ex, g.PLATE_Y1 + 0.016f, -0.40f, ex, g.PLATE_Y1 + 0.016f, zEnd), 0.003f)
        }
        out += gilt.build("grand.plate.gilt", MaterialId.GILT, SkinKind.STATIC, VM.LEVELS_ALL, VM.ACTION_HALL, true, ProgramId.RIBBON, 9)

        // ── Soundboard and bridges (slot 9) ──
        val sb = MeshBuilder(VertexLayout.STATIC, 2048)
        sb.color(Geo.mul(Pal.SOUNDBOARD, 0.25f))
        Geo.flat(sb, Geo.clipZBelow(inner, -0.50f), g.SOUNDBOARD_Y, up = true)
        sb.color(Geo.mul(Pal.ACTION_WOOD, 0.55f))
        for (group in listOf(spans.filter { it.key <= g.OVERSTRUNG_TOP }, spans.filter { it.key > g.OVERSTRUNG_TOP })) {
            val ends = ArrayList<Float>()
            var last = -1
            for (s in group) if (s.key != last) { ends.add(s.b[0]); ends.add(s.b[2]); last = s.key }
            if (ends.size >= 4) sb.extrude(Geo.strip(ends.toFloatArray(), 0.008f), g.SOUNDBOARD_Y, group[0].b[1] - 0.001f, capTop = true, capBottom = false)
        }
        out += sb.build("grand.soundboard", MaterialId.SOUNDBOARD, SkinKind.STATIC, VM.LEVELS_ALL, VM.ACTION_HALL, true, ProgramId.LIT, 9)

        // ── Pedals (slot 15): soft 0, sostenuto 1, sustain 2, left to right ──
        out += pedals("grand.pedals", g.PEDAL_PIVOT_Y, g.PEDAL_PIVOT_Z, g.PEDAL_TIP_Z)

        // ── Section caps (slot 17): the key bed / key frame / rim-front section in the plane x = 0, facing +x ──
        val cap = MeshBuilder(VertexLayout.STATIC, 16)
        cap.color(Pal.SECTION_CAP)
        sectionQuad(cap, 0.62f, 0.695f, zRimFront, g.FRONT_Z)
        sectionQuad(cap, g.RIM_BOTTOM, BELLY_TOP, zRimFront - g.RIM_T, zRimFront)
        out += cap.build("grand.sectioncaps", MaterialId.SECTION_CAP, SkinKind.STATIC, VM.LEVELS_ALL, VM.CUT, false, ProgramId.SECTION_CAP, 17)

        // ── Feature-edge overlay (slot 18) ──
        val edge = MeshBuilder(VertexLayout.STATIC, 512)
        edge.color(Geo.mul(Pal.GILT_LIT, 0.6f))
        edge.ribbon(to3(rim, g.RIM_TOP + g.LID_T), 0.002f, closed = true)
        edge.ribbon(to3(rim, g.RIM_BOTTOM), 0.002f, closed = true)
        edge.ribbon(floatArrayOf(-kw, g.KEY_TOP, 0f, kw, g.KEY_TOP, 0f), 0.002f)
        out += edge.build("grand.edges", MaterialId.EDGE_GILT, SkinKind.STATIC, VM.edgeLevels(look), VM.ALL, true, ProgramId.RIBBON, 18)
        return out
    }

    /** The rim's front (key-well) side is the low belly rail; the pin block and plate bar sit above it. */
    const val BELLY_TOP = 0.80f

    /** Legs: under both cheeks and under the tail. */
    private val LEGS = arrayOf(floatArrayOf(-0.675f, -0.14f), floatArrayOf(0.675f, -0.14f), floatArrayOf(GrandDims.X0 + 0.42f, -1.80f))

    /** Three brass pedals 9 cm apart (tips at z = [tipZ], y = pivotY), PEDAL_ROT parts 0/1/2 (slot 0, lanes 0–2). */
    fun pedals(name: String, pivotY: Float, pivotZ: Float, tipZ: Float): List<BakedMesh> {
        val p = MeshBuilder(VertexLayout.SKINNED, 128)
        p.color(Pal.BRASS_HI)
        for (i in 0..2) {
            val x = (i - 1) * 0.09f
            p.part(0, i)
            p.box(x - 0.012f, pivotY - 0.01f, pivotZ, x + 0.012f, pivotY + 0.01f, tipZ)
        }
        return p.build(name, MaterialId.BRASS, SkinKind.PEDAL_ROT, VM.LEVELS_ALL, VM.PLAYER_HALL, false, ProgramId.SKINNED, 15)
    }

    internal fun sectionQuad(mb: MeshBuilder, y0: Float, y1: Float, z0: Float, z1: Float) {
        val a = mb.vertex(0f, y0, z0, 1f, 0f, 0f, z0, y0); val b = mb.vertex(0f, y0, z1, 1f, 0f, 0f, z1, y0)
        val c = mb.vertex(0f, y1, z1, 1f, 0f, 0f, z1, y1); val d = mb.vertex(0f, y1, z0, 1f, 0f, 0f, z0, y1)
        mb.triOutward(a, b, c); mb.triOutward(a, c, d)
    }

    internal fun to3(xz: FloatArray, y: Float): FloatArray {
        val n = xz.size / 2
        return FloatArray(n * 3) { i -> when (i % 3) { 0 -> xz[(i / 3) * 2]; 1 -> y; else -> xz[(i / 3) * 2 + 1] } }
    }
}
