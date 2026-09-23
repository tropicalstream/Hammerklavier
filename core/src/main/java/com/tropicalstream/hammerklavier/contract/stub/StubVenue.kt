package com.tropicalstream.hammerklavier.contract.stub

import com.tropicalstream.hammerklavier.contract.BakedMesh
import com.tropicalstream.hammerklavier.contract.FlameField
import com.tropicalstream.hammerklavier.contract.KonzertzimmerAcoustics
import com.tropicalstream.hammerklavier.contract.LightRig
import com.tropicalstream.hammerklavier.contract.MaterialId
import com.tropicalstream.hammerklavier.contract.Pal
import com.tropicalstream.hammerklavier.contract.Palette
import com.tropicalstream.hammerklavier.contract.ProgramId
import com.tropicalstream.hammerklavier.contract.QualityProfile
import com.tropicalstream.hammerklavier.contract.RoomLevel
import com.tropicalstream.hammerklavier.contract.SkinKind
import com.tropicalstream.hammerklavier.contract.TextureRecipe
import com.tropicalstream.hammerklavier.contract.VenueGeometry
import com.tropicalstream.hammerklavier.contract.VenueScene
import com.tropicalstream.hammerklavier.contract.VertexLayout
import com.tropicalstream.hammerklavier.mesh.MeshBuilder

/**
 * The stub venue (PLAN §2.3): [GEOMETRY] = KonzertzimmerAcoustics.GEOMETRY with the §5.6
 * placements; meshes = one parquet floor pool (room frame, drawSlot 1); six flames on two
 * three-light candelabra flanking the grand. Flicker from a prepared table (no transcendental
 * maths on the GL thread); allocation-free update.
 */
class StubVenue : VenueScene {
    override val geometry: VenueGeometry = GEOMETRY

    override fun meshes(palette: Palette): List<BakedMesh> {
        val b = MeshBuilder(VertexLayout.STATIC, 4)
        b.color(Pal.PARQUET_POOL)
        val y = 0.002f; val cx = 0f; val cz = -1.9f; val h = 2.6f
        b.quad(floatArrayOf(cx - h, y, cz + h), floatArrayOf(cx + h, y, cz + h), floatArrayOf(cx + h, y, cz - h), floatArrayOf(cx - h, y, cz - h))
        return b.build("stub.floorpool", MaterialId.PARQUET_POOL, SkinKind.STATIC, levelMask = 0b0111, viewMask = 0b111111,
            clipped = false, program = ProgramId.LIT, drawSlot = 1)
    }

    override fun textures(): List<TextureRecipe> = emptyList()
    override fun flames(): FlameField = StubFlames()

    override fun bakeProbe(centerRoom: FloatArray, out: ByteArray) {
        java.util.Arrays.fill(out, 0.toByte())
        val w = 128
        for (row in 28..35) for (x in 0 until w) {
            val o = (row * w + x) * 4
            if (o + 3 >= out.size) return
            out[o] = Pal.GILT_EMISSIVE[0].toByte(); out[o + 1] = Pal.GILT_EMISSIVE[1].toByte()
            out[o + 2] = Pal.GILT_EMISSIVE[2].toByte(); out[o + 3] = 0xFF.toByte()
        }
    }

    companion object {
        val GEOMETRY: VenueGeometry = KonzertzimmerAcoustics.GEOMETRY
    }
}

class StubFlames : FlameField {
    private val pos = floatArrayOf(
        -2.32f, 1.30f, -0.80f, -2.20f, 1.36f, -0.80f, -2.08f, 1.30f, -0.80f,
        0.48f, 1.30f, -0.80f, 0.60f, 1.36f, -0.80f, 0.72f, 1.30f, -0.80f)
    private val table = FloatArray(256).also {
        val r = java.util.Random(1747)
        var v = 0.93f
        for (i in it.indices) { v = (v + (r.nextFloat() - 0.5f) * 0.06f).coerceIn(0.86f, 1.0f); it[i] = v }
    }

    override val maxSprites: Int get() = 6

    private fun flicker(tSec: Float, i: Int): Float {
        val x = tSec * 24f + i * 37f
        val k = x.toInt()
        val f = x - k
        val a = table[k and 255]; val b = table[(k + 1) and 255]
        return a + (b - a) * f
    }

    override fun update(tSec: Float, eyeRoom: FloatArray, q: QualityProfile, level: RoomLevel, out: FloatArray): Int {
        if (level == RoomLevel.PASSTHROUGH || level == RoomLevel.INSTRUMENT) return 0
        for (i in 0 until 6) {
            val o = i * 8
            out[o] = pos[3 * i]; out[o + 1] = pos[3 * i + 1]; out[o + 2] = pos[3 * i + 2]
            out[o + 3] = 0.03f
            out[o + 4] = Pal.FLAME_BODY[0] / 255f; out[o + 5] = Pal.FLAME_BODY[1] / 255f; out[o + 6] = Pal.FLAME_BODY[2] / 255f
            out[o + 7] = flicker(tSec, i)
        }
        return 6
    }

    override fun lights(tSec: Float, out: LightRig) {
        var sum = 0f
        for (l in 0 until 4) {
            val i = if (l < 2) l + 1 else l + 2           // flames 1, 2, 4, 5
            val f = flicker(tSec, i); sum += f
            for (c in 0..2) { out.pos[3 * l + c] = pos[3 * i + c]; out.rgb[3 * l + c] = Pal.FLAME_BODY[c] / 255f * f }
        }
        out.flicker = sum / 4f
    }
}
