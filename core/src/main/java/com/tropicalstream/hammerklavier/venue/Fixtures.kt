package com.tropicalstream.hammerklavier.venue

import com.tropicalstream.hammerklavier.contract.BakedMesh
import com.tropicalstream.hammerklavier.contract.MaterialId
import com.tropicalstream.hammerklavier.contract.Pal
import com.tropicalstream.hammerklavier.contract.ProgramId
import com.tropicalstream.hammerklavier.contract.SkinKind
import com.tropicalstream.hammerklavier.contract.VertexLayout
import com.tropicalstream.hammerklavier.instrument.tex.Wood
import com.tropicalstream.hammerklavier.mesh.MeshBuilder

/**
 * Furniture and light fittings (PLAN §5.5; §5.3 rows 4–5), room frame: 3 rows × 6 rococo chairs
 * (frames and damask, Hall only), the chandelier's gilt-bronze stem, rings and arms, the girandole
 * arms of every sconce, the two floor candelabra and Frederick's music stand. The mirror glass
 * itself is never drawn (black is transparent); only the flames it reflects (FlameFieldImpl).
 */
object Fixtures {
    /** M8: a tint of the shared TapGem wood tile (Wood.NEUTRAL = the tile as is). */
    val CHAIR_FRAME_RGB = Wood.NEUTRAL
    val DAMASK_RGB = intArrayOf(80, 30, 26)
    val CANDLE_RGB = intArrayOf(150, 142, 128)          // M5: was (214,204,184), brighter than the flames it carries

    fun build(): List<BakedMesh> {
        val out = ArrayList<BakedMesh>()
        out += chairs()
        out += fittingsRibbons()
        out += fittingsSolid()
        return out
    }

    /** Chairs face north (the instrument); their backs are on the +z side. */
    fun chairs(): List<BakedMesh> {
        val fr = MeshBuilder(VertexLayout.STATIC, 2048); fr.color(CHAIR_FRAME_RGB)
        val dm = MeshBuilder(VertexLayout.STATIC, 512); dm.color(DAMASK_RGB)
        for (z in Konzertzimmer.CHAIR_ROWS_Z) for (i in 0 until 6) {
            val x = Konzertzimmer.chairX(i)
            val hw = 0.23f; val hd = 0.21f; val leg = 0.022f
            for (sx in floatArrayOf(-1f, 1f)) for (sz in floatArrayOf(-1f, 1f)) {
                val lx = x + sx * (hw - leg); val lz = z + sz * (hd - leg)
                fr.box(lx - leg, 0f, lz - leg, lx + leg, 0.42f, lz + leg)
            }
            fr.box(x - hw, 0.40f, z - hd, x + hw, 0.46f, z + hd)                          // seat rail
            for (sx in floatArrayOf(-1f, 1f)) fr.box(x + sx * (hw - 0.03f) - 0.02f, 0.46f, z + hd - 0.05f,
                x + sx * (hw - 0.03f) + 0.02f, 0.98f, z + hd)                                 // back posts
            fr.box(x - hw + 0.02f, 0.92f, z + hd - 0.05f, x + hw - 0.02f, 1.00f, z + hd)     // crest rail
            dm.box(x - hw + 0.02f, 0.46f, z - hd + 0.02f, x + hw - 0.02f, 0.50f, z + hd - 0.06f)   // cushion
            dm.box(x - hw + 0.05f, 0.54f, z + hd - 0.04f, x + hw - 0.05f, 0.90f, z + hd - 0.02f)   // back panel
        }
        val a = fr.build("venue.chairs.frame", MaterialId.CHAIR_FRAME, SkinKind.STATIC, Masks.SALON, Masks.HALL_VIEWS,
            clipped = false, program = ProgramId.LIT, drawSlot = 4, texture = Wood.NAME)
        val b = dm.build("venue.chairs.damask", MaterialId.DAMASK, SkinKind.STATIC, Masks.SALON, Masks.HALL_VIEWS,
            clipped = false, program = ProgramId.LIT, drawSlot = 4)
        for (m in a + b) LightBake.bake(m, useNormal = true, gain = 1.4f, floor = 0.1f)
        return a + b
    }

    /** Chandelier and girandole arms: gilt ribbons (one merge key with distance fade at Stage). */
    fun fittingsRibbons(): List<BakedMesh> {
        val b = MeshBuilder(VertexLayout.STATIC, 2048)
        b.color(Pal.GILT_LIT)
        val hw = 0.010f
        // stem from the rosette to the finial
        Geo.ribbon(b, floatArrayOf(0f, Konzertzimmer.CEILING, 0f, 0f, 4.2f, 0f, 0f, 3.6f, 0f, 0f, 2.75f, 0f), false, hw * 1.6f)
        for ((y, r) in listOf(FlameLayout.CHANDELIER_Y - 0.05f to FlameLayout.CHANDELIER_R, FlameLayout.CHANDELIER_UPPER_Y - 0.05f to FlameLayout.CHANDELIER_UPPER_R,
                3.12f to 0.62f, 3.00f to 0.44f, 2.86f to 0.24f)) {
            val ring = FloatArray(24 * 3)
            for (s in 0 until 24) {
                val a = 2.0 * Math.PI * s / 24
                ring[3 * s] = (r * Math.sin(a)).toFloat(); ring[3 * s + 1] = y; ring[3 * s + 2] = (r * Math.cos(a)).toFloat()
            }
            Geo.ribbon(b, ring, true, hw)
        }
        // S-curved arms from the stem to each chandelier candle
        for (i in 0 until FlameLayout.COUNT) {
            val g = FlameLayout.GROUP[i]
            val x = FlameLayout.POS[3 * i]; val y = FlameLayout.POS[3 * i + 1]; val z = FlameLayout.POS[3 * i + 2]
            when (g) {
                FlameLayout.G_CHANDELIER -> Geo.ribbon(b, floatArrayOf(0f, y - 0.25f, 0f, x * 0.4f, y - 0.30f, z * 0.4f,
                    x * 0.8f, y - 0.20f, z * 0.8f, x, y - 0.06f, z), false, hw)
                FlameLayout.G_SCONCE_N, FlameLayout.G_SCONCE_N + 1, FlameLayout.G_SCONCE_N + 2, FlameLayout.G_SCONCE_S, FlameLayout.G_DOOR -> {
                    // from the wall (the nearest plane) out and up to the candle cup
                    val wx: Float; val wz: Float
                    if (g == FlameLayout.G_DOOR) { wx = if (x > 0) Konzertzimmer.HALF_W - 0.02f else -Konzertzimmer.HALF_W + 0.02f; wz = z }
                    else { wx = x; wz = if (z < 0) -Konzertzimmer.HALF_D + 0.02f else Konzertzimmer.HALF_D - 0.02f }
                    Geo.ribbon(b, floatArrayOf(wx, y - 0.28f, wz, (wx + x) / 2, y - 0.22f, (wz + z) / 2, x, y - 0.12f, z, x, y - 0.06f, z), false, hw)
                }
                else -> {}
            }
        }
        val m = b.build("venue.fittings.gilt", MaterialId.GILT, SkinKind.STATIC, Masks.SALON or Masks.STAGE, Masks.ALL_VIEWS,
            clipped = false, program = ProgramId.RIBBON, drawSlot = 2,   // M5: merges with venue.gilt (same RIBBON GILT key); Hall Salon was 29 draws
            fadeNearM = Konzertzimmer.STAGE_FADE_NEAR, fadeFarM = Konzertzimmer.STAGE_FADE_FAR)
        for (x in m) LightBake.bake(x, useNormal = false, gain = 1.2f, floor = 0.35f)
        return m
    }

    /** Candelabra, music stand and every candle stick: low-poly lit boxes in gilt bronze and wax. */
    fun fittingsSolid(): List<BakedMesh> {
        val b = MeshBuilder(VertexLayout.STATIC, 2048)
        for (c in 0 until 2) {
            val x = Konzertzimmer.CANDELABRA_XZ[2 * c]; val z = Konzertzimmer.CANDELABRA_XZ[2 * c + 1]
            b.color(Pal.GILT_LIT)
            b.box(x - 0.16f, 0f, z - 0.16f, x + 0.16f, 0.05f, z + 0.16f)                 // tripod base
            b.box(x - 0.08f, 0.05f, z - 0.08f, x + 0.08f, 0.14f, z + 0.08f)
            b.box(x - 0.025f, 0.14f, z - 0.025f, x + 0.025f, Konzertzimmer.CANDELABRA_TOP - 0.06f, z + 0.025f)   // shaft
            b.box(x - 0.20f, Konzertzimmer.CANDELABRA_TOP - 0.08f, z - 0.015f, x + 0.20f, Konzertzimmer.CANDELABRA_TOP - 0.05f, z + 0.015f)
            b.box(x - 0.015f, Konzertzimmer.CANDELABRA_TOP - 0.08f, z - 0.20f, x + 0.015f, Konzertzimmer.CANDELABRA_TOP - 0.05f, z + 0.20f)
        }
        // music stand: tripod foot, shaft, sloped desk (as a thin box), two candle arms
        val sx = Konzertzimmer.MUSIC_STAND_XZ[0]; val sz = Konzertzimmer.MUSIC_STAND_XZ[1]
        b.color(Pal.GILT_LIT)
        b.box(sx - 0.20f, 0f, sz - 0.20f, sx + 0.20f, 0.04f, sz + 0.20f)
        b.box(sx - 0.02f, 0.04f, sz - 0.02f, sx + 0.02f, 1.05f, sz + 0.02f)
        b.box(sx - 0.24f, 1.05f, sz - 0.03f, sx + 0.24f, 1.40f, sz + 0.0f)
        // candle sticks under every flame except the chandelier's (its cups ride on the arms)
        b.color(CANDLE_RGB)
        for (i in 0 until FlameLayout.COUNT) {
            val x = FlameLayout.POS[3 * i]; val y = FlameLayout.POS[3 * i + 1]; val z = FlameLayout.POS[3 * i + 2]
            b.box(x - 0.011f, y - 0.12f, z - 0.011f, x + 0.011f, y - 0.004f, z + 0.011f)
        }
        val m = b.build("venue.fittings.solid", MaterialId.BRASS, SkinKind.STATIC, Masks.SALON or Masks.STAGE, Masks.ALL_VIEWS,
            clipped = false, program = ProgramId.LIT, drawSlot = 5,
            fadeNearM = Konzertzimmer.STAGE_FADE_NEAR, fadeFarM = Konzertzimmer.STAGE_FADE_FAR)
        for (x in m) LightBake.bake(x, useNormal = true, gain = 0.9f, floor = 0.25f)
        return m
    }
}
