package com.tropicalstream.hammerklavier.venue

import com.tropicalstream.hammerklavier.contract.Pal
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The 128 × 64 equirectangular reflection probe of the room's flames and gilt (PLAN §5.9 item 1),
 * seen from a centre point (the instrument's lid), baked on HKLoader for the lacquer shader.
 *
 * Mapping: column u = (yaw + π) / 2π · 128 with yaw per `Conventions` (0 = north, + toward east),
 * row v = (π/2 − elevation) / π · 64, row 0 straight up. RGBA8888, alpha 255, un-lifted sRGB.
 * Content: a soft disc per flame, brightness min(1, 1.5 / d²) of FLAME_BODY; the cornice lip and
 * the dado rail as dim gilt bands where the view ray meets the walls; black elsewhere. Energy is
 * bounded: no channel exceeds 255 and the mean stays dim (T8.6).
 */
object ProbeBake {
    const val W = 128
    const val H = 64

    fun bake(centerRoom: FloatArray, out: ByteArray) {
        require(out.size >= W * H * 4) { "probe needs ${W * H * 4} bytes" }
        val acc = FloatArray(W * H * 3)
        // gilt bands: for every column, where the horizontal ray meets the walls
        for (u in 0 until W) {
            val yaw = (u + 0.5) / W * 2 * Math.PI - Math.PI
            val dx = Math.sin(yaw); val dz = -Math.cos(yaw)
            val tx = if (dx > 0) (Konzertzimmer.HALF_W - centerRoom[0]) / dx else if (dx < 0) (-Konzertzimmer.HALF_W - centerRoom[0]) / dx else Double.MAX_VALUE
            val tz = if (dz > 0) (Konzertzimmer.HALF_D - centerRoom[2]) / dz else if (dz < 0) (-Konzertzimmer.HALF_D - centerRoom[2]) / dz else Double.MAX_VALUE
            val d = minOf(tx, tz).coerceAtLeast(0.2)
            for ((y, k) in listOf(Konzertzimmer.CORNICE to 0.9f, Konzertzimmer.DADO to 0.5f)) {
                val el = atan2(y - centerRoom[1].toDouble(), d)
                val v = ((Math.PI / 2 - el) / Math.PI * H).toInt().coerceIn(0, H - 1)
                val fall = (3.0 / d).coerceAtMost(1.0).toFloat() * k
                for (c in 0..2) acc[(v * W + u) * 3 + c] += Pal.GILT_LIT[c] * fall * 0.5f
            }
        }
        // flames
        var i = 0
        while (i < FlameLayout.POS.size) {
            val lx = FlameLayout.POS[i] - centerRoom[0]; val ly = FlameLayout.POS[i + 1] - centerRoom[1]; val lz = FlameLayout.POS[i + 2] - centerRoom[2]
            i += 3
            val d = sqrt(lx * lx + ly * ly + lz * lz)
            if (d < 0.05f) continue
            val yaw = atan2(lx, -lz)
            val el = asin((ly / d).coerceIn(-1f, 1f))
            val fu = ((yaw + Math.PI) / (2 * Math.PI) * W).toFloat()
            val fv = ((Math.PI / 2 - el) / Math.PI * H).toFloat()
            val bright = (1.5f / (d * d)).coerceAtMost(1f)
            val sigma = 0.9f
            for (dv in -2..2) for (du in -2..2) {
                val pu = ((fu.toInt() + du) % W + W) % W
                val pv = fv.toInt() + dv
                if (pv < 0 || pv >= H) continue
                val cu = fu.toInt() + du + 0.5f - fu; val cv = pv + 0.5f - fv
                val g = exp(-(cu * cu + cv * cv) / (2 * sigma * sigma))
                for (c in 0..2) acc[(pv * W + pu) * 3 + c] += Pal.FLAME_BODY[c] * bright * g
            }
        }
        for (p in 0 until W * H) {
            for (c in 0..2) out[p * 4 + c] = acc[p * 3 + c].coerceIn(0f, 255f).toInt().toByte()
            out[p * 4 + 3] = 0xFF.toByte()
        }
    }
}
