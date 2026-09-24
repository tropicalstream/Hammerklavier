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
import com.tropicalstream.hammerklavier.contract.UprightFinish
import com.tropicalstream.hammerklavier.instrument.tex.Wood
import com.tropicalstream.hammerklavier.contract.VertexLayout
import com.tropicalstream.hammerklavier.instrument.tex.InstrumentTextures
import com.tropicalstream.hammerklavier.mesh.MeshBuilder
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * The upright (PLAN §5.4 "Upright (U3, 131 × 153 × 65 cm)"): case with top lid, fallboard, knee
 * board and the upper front panel (0.76–1.25 m, not drawn in Overhead); vertical strings, the bass
 * overstrung at 18°; hammers thrown horizontally toward −z (strike line 1.08 m); the underdamper
 * action with its contact line 50 mm below the strike line (1.03 m), heads swinging toward the
 * player (DAMPER_LIFT axis +z); hammer rail; three pedals (the middle one static in use).
 */
object UprightModel {
    const val WIDTH = 1.53f; const val HEIGHT = 1.31f; const val DEPTH = 0.65f
    const val KEY_TOP = 0.72f
    const val STRIKE_Y = 1.08f
    const val DAMPER_Y = 1.03f
    const val STRING_Z = -0.44f
    const val BASS_Z = -0.428f
    const val PIN_Y = 1.22f
    const val BLOW = 0.047f
    const val HAMMER_R = 0.133f
    const val HAMMER_FACE_Z = STRING_Z + BLOW       // rest: the felt face 47 mm in front of the strings
    const val PIVOT_Y = STRIKE_Y - HAMMER_R          // 0.947
    const val PIVOT_Z = HAMMER_FACE_Z + 0.015f
    const val BALANCE_Z = -0.23f
    const val KEY_MAX_RAD = 0.0435f                  // 10 mm at 230 mm
    const val RAIL_Y = 1.00f; const val RAIL_Z = PIVOT_Z + 0.02f
    const val OVERSTRUNG_TOP = 40
    const val PEDAL_PIVOT_Y = 0.07f; const val PEDAL_PIVOT_Z = -0.30f; const val PEDAL_TIP_Z = -0.20f

    fun keyboardSpec(): KeyboardSpec = KeyboardSpec(
        lowKey = 21, highKey = 108, headM = 0.0235f, topY = KEY_TOP,
        whiteMaterial = MaterialId.IVORY, whiteRgb = Pal.IVORY, whiteSideRgb = Pal.IVORY_SIDE, frontRgb = Pal.IVORY_SIDE,
        blackRgb = Pal.EBONY_KEY, blackSideRgb = Geo.mul(Pal.EBONY_RIM, 0.4f),
        pivotY = KEY_TOP - 0.01f, pivotZ = BALANCE_Z, maxRad = KEY_MAX_RAD)

    fun create(look: InstrumentLook, lastDamper: Int): InstrumentSceneImpl {
        val profile = InstrumentProfile.UPRIGHT.withLastDamper(lastDamper)
        val kb = Keyboard(keyboardSpec())
        val skin = SkinParams(mapOf(
            SkinKind.KEY_ROT to kb.skinParams,
            SkinKind.HAMMER_ROT to floatArrayOf(PIVOT_Y, PIVOT_Z, -BLOW / HAMMER_R),
            SkinKind.DAMPER_LIFT to floatArrayOf(0.006f, 0f, 0f, 1f),
            SkinKind.PEDAL_ROT to floatArrayOf(PEDAL_PIVOT_Y, PEDAL_PIVOT_Z, 0.08727f),
            SkinKind.HAMMER_RAIL to floatArrayOf(0.022f),
            SkinKind.STRING to floatArrayOf(2.5f, 3.5f, 1.5f),
            SkinKind.ACTION_SET to ACTION_PIVOTS))
        return InstrumentSceneImpl(InstrumentId.UPRIGHT, kb, skin,
            buildMeshes = { meshes(kb, profile, look, lastDamper) },
            buildTextures = { listOf(InstrumentTextures.fallboard(), Wood.recipe()) },
            packer = UprightActionPacker(profile, kb.keyX))
    }

    /** Case material and colour by finish: walnut (default) and mahogany are wood, ebony is lacquer. */
    fun finish(f: UprightFinish): Pair<MaterialId, IntArray> = when (f) {
        UprightFinish.WALNUT -> MaterialId.WOOD_CASE to Wood.WALNUT          // tints of the shared TapGem wood tile
        UprightFinish.MAHOGANY -> MaterialId.WOOD_CASE to Wood.MAHOGANY
        UprightFinish.EBONY -> MaterialId.LACQUER to Pal.EBONY_KEY
    }

    fun spans(kb: Keyboard, profile: InstrumentProfile): List<StringSpan> {
        val out = ArrayList<StringSpan>()
        for (k in profile.lowKey..profile.highKey) {
            val n = profile.stringsPerKey(k)
            for (i in 0 until n) {
                val x = kb.keyX[k] + StringsMesh.unison(n, i)
                if (k <= OVERSTRUNG_TOP) {
                    val a = 0.31416f
                    val x0 = x - (PIN_Y - STRIKE_Y) * tan(a)
                    val len = (PIN_Y - 0.18f) / cos(a)
                    out.add(StringSpan(k, floatArrayOf(x0, PIN_Y, BASS_Z), floatArrayOf(x0 + len * sin(a), PIN_Y - len * cos(a), BASS_Z), true))
                } else {
                    val len = minOf(StringsMesh.pianoIdeal(k), PIN_Y - 0.20f)
                    out.add(StringSpan(k, floatArrayOf(x, PIN_Y, STRING_Z), floatArrayOf(x, PIN_Y - len, STRING_Z), k <= 53))
                }
            }
        }
        return out
    }

    fun meshes(kb: Keyboard, profile: InstrumentProfile, look: InstrumentLook, lastDamper: Int): List<BakedMesh> {
        val out = ArrayList<BakedMesh>()
        val (mat, rgb) = finish(look.finish)
        val prog = if (mat == MaterialId.LACQUER) ProgramId.LACQUER else ProgramId.LIT
        val tex = if (mat == MaterialId.WOOD_CASE) Wood.NAME else null
        val hx = WIDTH / 2f; val kw = kb.widthM / 2f; val back = -DEPTH
        val side = Geo.mul(rgb, 0.8f)

        // ── Case (slot 7) ──
        val c = MeshBuilder(VertexLayout.STATIC, 1024)
        c.color(rgb)
        c.box(-hx, 0f, back, -hx + 0.025f, HEIGHT - 0.02f, -0.15f); c.box(hx - 0.025f, 0f, back, hx, HEIGHT - 0.02f, -0.15f)   // sides
        c.box(-hx, 0.60f, -0.15f, hx, 0.66f, 0f)                                                                            // key bed
        c.box(-kw, 0.66f, -0.15f, kw, KEY_TOP - 0.02f, -0.13f)
        c.color(side)
        c.box(-hx + 0.025f, 0.10f, -0.20f, hx - 0.025f, 0.60f, -0.18f)                                                       // knee board
        c.box(-hx, 0f, back, hx, HEIGHT - 0.02f, back + 0.03f)                                                               // back
        out += c.build("upright.case", mat, SkinKind.STATIC, VM.LEVELS_ALL, VM.ALL, true, prog, 7, texture = tex)
        // Family look (M8): the grand's ebony lacquer on the arms/key cheeks, fallboard, plinth and toe
        // blocks, and the top lid (below); the wood finishes keep the walnut/mahogany body.
        val lac = Pal.EBONY_KEY
        val lq = MeshBuilder(VertexLayout.STATIC, 256)
        lq.color(lac)
        lq.box(-hx + 0.025f, 0.62f, -0.15f, -kw, 0.79f, 0f); lq.box(kw, 0.62f, -0.15f, hx - 0.025f, 0.79f, 0f)       // arms (key cheeks)
        lq.box(-kw, KEY_TOP, -0.17f, kw, 0.80f, -0.148f)                                                                     // fallboard
        lq.box(-hx, 0f, -0.224f, hx, 0.10f, -0.15f)                                                      // plinth
        lq.box(-hx, 0f, -0.15f, -hx + 0.07f, 0.06f, -0.03f); lq.box(hx - 0.07f, 0f, -0.15f, hx, 0.06f, -0.03f)                 // toe blocks
        out += lq.build("upright.lacquer", MaterialId.LACQUER, SkinKind.STATIC, VM.LEVELS_ALL, VM.ALL, true, ProgramId.LACQUER, 7)
        val upper = MeshBuilder(VertexLayout.STATIC, 64)
        upper.color(rgb)
        upper.box(-hx + 0.025f, 0.80f, -0.17f, hx - 0.025f, 1.25f, -0.15f)                                                   // upper front panel
        upper.box(-0.35f, 0.80f, -0.15f, 0.35f, 0.82f, -0.10f)                                                               // desk ledge
        out += upper.build("upright.upperpanel", mat, SkinKind.STATIC, VM.LEVELS_ALL, VM.NO_OVER, true, prog, 7, texture = tex)
        val top = MeshBuilder(VertexLayout.STATIC, 32)
        top.color(lac)
        top.box(-hx, HEIGHT - 0.02f, back, hx, HEIGHT, -0.126f)
        out += top.build("upright.toplid", MaterialId.LACQUER, SkinKind.STATIC, VM.LEVELS_ALL, VM.NO_OVER, true, ProgramId.LACQUER, 8)
        val decal = MeshBuilder(VertexLayout.STATIC, 8)
        decal.color(intArrayOf(255, 255, 255))
        Geo.quadZ(decal, -0.13f, 0.75f, 0.13f, 0.78f, -0.1475f)
        out += decal.build("upright.fallboard.lettering", MaterialId.GILT_EMISSIVE, SkinKind.STATIC, VM.LEVELS_ALL, VM.ALL, true,
            ProgramId.DECAL, 7, texture = InstrumentTextures.FALLBOARD)

        // ── Soundboard, plate, pins (slot 9) ──
        val spans = spans(kb, profile)
        val sb = MeshBuilder(VertexLayout.STATIC, 256)
        sb.color(Geo.mul(Pal.SOUNDBOARD, 0.25f))
        sb.box(-hx + 0.03f, 0.10f, back + 0.03f, hx - 0.03f, 1.25f, back + 0.04f)
        sb.color(Geo.mul(Pal.ACTION_WOOD, 0.55f))
        sb.box(-0.60f, 0.20f, STRING_Z - 0.03f, 0.70f, 0.24f, STRING_Z - 0.004f)                                              // bottom bridge
        out += sb.build("upright.soundboard", MaterialId.SOUNDBOARD, SkinKind.STATIC, VM.LEVELS_ALL, VM.ACTION_HALL, true, ProgramId.LIT, 9)
        val plate = MeshBuilder(VertexLayout.STATIC, 4096)
        plate.color(Pal.PLATE_GOLD)
        val pz0 = STRING_Z - 0.05f; val pz1 = STRING_Z - 0.035f
        plate.box(-0.72f, 1.18f, pz0, 0.72f, 1.26f, STRING_Z - 0.006f)          // pin block cover
        plate.box(-0.72f, 0.14f, pz0, 0.72f, 0.20f, pz1)                          // bottom rail
        plate.box(-0.72f, 0.14f, pz0, -0.66f, 1.26f, pz1); plate.box(0.66f, 0.14f, pz0, 0.72f, 1.26f, pz1)
        for (sx in floatArrayOf(-0.30f, 0.05f, 0.38f)) plate.box(sx - 0.02f, 0.20f, pz0, sx + 0.02f, 1.18f, pz1)
        plate.color(Pal.STEEL_BASE)
        var row = 0
        for (s in spans) {
            val y = if (row % 2 == 0) 1.235f else 1.215f
            plate.box(s.a[0] - 0.0015f, y - 0.0015f, STRING_Z - 0.006f, s.a[0] + 0.0015f, y + 0.0015f, STRING_Z + 0.014f)
            row++
        }
        out += plate.build("upright.plate", MaterialId.PLATE, SkinKind.STATIC, VM.LEVELS_ALL, VM.ACTION_HALL, true, ProgramId.LIT, 9)
        val gilt = MeshBuilder(VertexLayout.STATIC, 256)
        gilt.color(Pal.GILT_LIT)
        for (sx in floatArrayOf(-0.30f, 0.05f, 0.38f)) for (ex in floatArrayOf(sx - 0.021f, sx + 0.021f))
            gilt.ribbon(floatArrayOf(ex, 0.20f, pz1 + 0.001f, ex, 1.18f, pz1 + 0.001f), 0.003f)
        gilt.ribbon(floatArrayOf(-0.66f, 0.20f, pz1 + 0.001f, 0.66f, 0.20f, pz1 + 0.001f, 0.66f, 1.18f, pz1 + 0.001f,
            -0.66f, 1.18f, pz1 + 0.001f), 0.003f, closed = true)
        out += gilt.build("upright.plate.gilt", MaterialId.GILT, SkinKind.STATIC, VM.LEVELS_ALL, VM.ACTION_HALL, true, ProgramId.RIBBON, 9)

        // ── Keys (slot 10) ──
        out += kb.meshes(clipped = true)

        // ── Hammers (slot 11) and the hammer rail ──
        val hm = MeshBuilder(VertexLayout.SKINNED, 88 * 72)
        for (k in profile.lowKey..profile.highKey) {
            val part = k - profile.lowKey
            hm.part(part / 4, part % 4)
            val x = kb.keyX[k]; val hw = kb.spec.pitchM * 0.40f
            val h = 0.050f - 0.020f * part / (profile.highKey - profile.lowKey)
            hm.color(Pal.FELT); hm.box(x - hw, STRIKE_Y - h / 2f, HAMMER_FACE_Z, x + hw, STRIKE_Y + h / 2f, HAMMER_FACE_Z + 0.012f)
            hm.color(Pal.ACTION_WOOD); hm.box(x - hw, STRIKE_Y - h / 2f + 0.004f, HAMMER_FACE_Z + 0.012f, x + hw, STRIKE_Y + h / 2f - 0.004f, HAMMER_FACE_Z + 0.030f)
            hm.box(x - 0.0025f, PIVOT_Y, PIVOT_Z - 0.003f, x + 0.0025f, STRIKE_Y - h / 2f + 0.004f, PIVOT_Z + 0.003f)
            hm.box(x - hw, PIVOT_Y - 0.02f, PIVOT_Z - 0.012f, x + hw, PIVOT_Y + 0.01f, PIVOT_Z + 0.012f)
        }
        out += hm.build("upright.hammers", MaterialId.FELT, SkinKind.HAMMER_ROT, VM.LEVELS_ALL, VM.ACTION_HALL, true, ProgramId.SKINNED, 11)
        val rail = MeshBuilder(VertexLayout.SKINNED, 32)
        rail.part(0, 0); rail.color(Pal.CLOTH_RED)
        rail.box(-kw, RAIL_Y - 0.006f, RAIL_Z, kw, RAIL_Y + 0.006f, RAIL_Z + 0.012f)
        out += rail.build("upright.hammerrail", MaterialId.CLOTH_RED, SkinKind.HAMMER_RAIL, VM.LEVELS_ALL, VM.ACTION_HALL, true, ProgramId.SKINNED, 11)

        // ── Dampers (slot 13): under the strike line, between the shanks and the strings ──
        val dm = MeshBuilder(VertexLayout.SKINNED, 88 * 72)
        for (k in profile.lowKey..minOf(lastDamper, profile.highKey)) {
            val part = k - profile.lowKey
            dm.part(part / 4, part % 4)
            val x = kb.keyX[k]; val hw = kb.spec.pitchM * 0.42f
            val sz = if (k <= OVERSTRUNG_TOP) BASS_Z else STRING_Z
            dm.color(Pal.FELT); dm.box(x - hw, DAMPER_Y - 0.015f, sz + 0.0015f, x + hw, DAMPER_Y + 0.015f, sz + 0.010f)
            dm.color(Pal.DAMPER_TOP); dm.box(x - hw, DAMPER_Y - 0.012f, sz + 0.010f, x + hw, DAMPER_Y + 0.012f, sz + 0.022f)
            dm.box(x - 0.002f, 0.90f, sz + 0.016f, x + 0.002f, DAMPER_Y - 0.012f, sz + 0.020f)
        }
        out += dm.build("upright.dampers", MaterialId.DAMPER_TOP, SkinKind.DAMPER_LIFT, VM.LEVELS_ALL, VM.ACTION, true, ProgramId.SKINNED, 13)

        // ── Strings (slot 14), pedals (slot 15), action set (slot 16), caps (17), edges (18) ──
        out += StringsMesh.build(spans, profile.lowKey, "upright.strings", VM.ACTION_HALL, true)
        out += GrandCase.pedals("upright.pedals", PEDAL_PIVOT_Y, PEDAL_PIVOT_Z, PEDAL_TIP_Z)
        out += actionSet()
        val cap = MeshBuilder(VertexLayout.STATIC, 16)
        cap.color(Pal.SECTION_CAP)
        GrandCase.sectionQuad(cap, 0.60f, KEY_TOP - 0.02f, -0.15f, 0f)
        GrandCase.sectionQuad(cap, 0f, HEIGHT - 0.02f, back, back + 0.03f)
        out += cap.build("upright.sectioncaps", MaterialId.SECTION_CAP, SkinKind.STATIC, VM.LEVELS_ALL, VM.CUT, false, ProgramId.SECTION_CAP, 17)
        val edge = MeshBuilder(VertexLayout.STATIC, 128)
        edge.color(Geo.mul(Pal.GILT_LIT, 0.6f))
        edge.ribbon(floatArrayOf(-hx, 0f, -0.15f, -hx, HEIGHT, -0.15f, hx, HEIGHT, -0.15f, hx, 0f, -0.15f), 0.002f)
        edge.ribbon(floatArrayOf(-kw, KEY_TOP, 0f, kw, KEY_TOP, 0f), 0.002f)
        out += edge.build("upright.edges", MaterialId.EDGE_GILT, SkinKind.STATIC, VM.edgeLevels(look), VM.ALL, true, ProgramId.RIBBON, 18)
        return out
    }

    /** Part types: 0 key lever, 1 wippen, 2 jack, 3 backcheck/catcher, 4 hammer (butt, shank, head), 5 damper lever. */
    fun actionSet(): List<BakedMesh> {
        val mb = MeshBuilder(VertexLayout.SKINNED, 13 * 6 * 48)
        val w = 0.0045f
        for (s in 0 until ActionSetPacker.SLOTS) {
            fun part(p: Int) = mb.part(ActionSetPacker.slotOf(s, p), ActionSetPacker.laneOf(s, p))
            val u = s.toFloat()
            part(0); mb.color(Pal.KEYLEVER); Geo.cuboidUv(mb, -w, KEY_TOP - 0.02f, -0.45f, w, KEY_TOP, -0.16f, u, 0f)
            mb.color(Pal.BRASS_MID); Geo.cuboidUv(mb, -0.002f, KEY_TOP, -0.382f, 0.002f, 0.80f, -0.378f, u, 0f)      // capstan + sticker
            part(1); mb.color(Pal.ACTION_WOOD); Geo.cuboidUv(mb, -w, 0.80f, -0.41f, w, 0.82f, -0.33f, u, 1f)
            part(2); mb.color(Pal.ACTION_WOOD); Geo.cuboidUv(mb, -0.003f, 0.82f, -0.372f, 0.003f, PIVOT_Y - 0.02f, -0.366f, u, 2f)
            part(3); mb.color(Pal.LEATHER); Geo.cuboidUv(mb, -0.003f, 0.82f, -0.334f, 0.003f, 0.90f, -0.326f, u, 3f)
            part(4); mb.color(Pal.ACTION_WOOD)
            Geo.cuboidUv(mb, -w, PIVOT_Y - 0.02f, PIVOT_Z - 0.012f, w, PIVOT_Y + 0.01f, PIVOT_Z + 0.012f, u, 4f)
            Geo.cuboidUv(mb, -0.0025f, PIVOT_Y, PIVOT_Z - 0.003f, 0.0025f, STRIKE_Y - 0.02f, PIVOT_Z + 0.003f, u, 4f)
            mb.color(Pal.FELT); Geo.cuboidUv(mb, -w, STRIKE_Y - 0.02f, HAMMER_FACE_Z, w, STRIKE_Y + 0.02f, HAMMER_FACE_Z + 0.03f, u, 4f)
            part(5); mb.color(Pal.DAMPER_TOP); Geo.cuboidUv(mb, -0.002f, 0.90f, STRING_Z + 0.016f, 0.002f, DAMPER_Y, STRING_Z + 0.020f, u, 5f)
        }
        return mb.build("upright.actionset", MaterialId.ACTION_WOOD, SkinKind.ACTION_SET, VM.LEVELS_ALL, VM.CUT, false, ProgramId.SKINNED, 16)
    }

    val ACTION_PIVOTS = floatArrayOf(
        KEY_TOP - 0.01f, BALANCE_Z, 1f,       // key lever
        0.81f, -0.41f, -1f,                   // wippen: flange at the back, lifted in front by the sticker
        0.82f, -0.369f, 1f,                   // jack
        0.82f, -0.33f, 1f,                    // backcheck
        PIVOT_Y, PIVOT_Z, -1f,                // hammer: the head (above) swings toward −z
        0.90f, STRING_Z + 0.018f, 1f)         // damper lever: the head (above) swings toward the player
}

class UprightActionPacker(profile: InstrumentProfile, keyX: FloatArray) : ActionSetPacker(profile, keyX) {
    override fun parts(pose: MechanismPose, k: Int, out: FloatArray, o: Int) {
        val key = pose.keyDip[k] * UprightModel.KEY_MAX_RAD
        out[o] = key
        out[o + 1] = key * 0.9f
        out[o + 2] = pose.escape[k] * 0.20f
        out[o + 3] = 0f
        out[o + 4] = pose.hammer[k] * (UprightModel.BLOW / UprightModel.HAMMER_R)
        out[o + 5] = pose.damper[k] * (0.006f / 0.13f)
    }
}
