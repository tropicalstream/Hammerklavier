package com.tropicalstream.hammerklavier.venue

import com.tropicalstream.hammerklavier.contract.BakedMesh
import com.tropicalstream.hammerklavier.contract.VertexLayout
import kotlin.math.sqrt

/**
 * Per-vertex light baked from all ≈ 50 flames at load (PLAN §5.5 "Lighting", §5.3 row 1), on
 * HKLoader. Each flame is a point of strength [K]: E = Σ K · cosθ / (d² + [SOFT]), with cosθ =
 * max(0, n·l) for surfaces and 1 for ribbons (whose normal slot holds the path tangent). The vertex
 * colour is multiplied by clamp(floor + gain · E − cut, 0, 1), so light pools fall to black
 * (transparent on the waveguide) away from the candles.
 */
object LightBake {
    const val K = 0.6f
    const val SOFT = 0.25f

    /** Irradiance at (x, y, z) with normal n (ignored when [useNormal] is false). */
    fun irradiance(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, useNormal: Boolean,
                   flames: FloatArray = FlameLayout.POS): Float {
        var e = 0f
        var i = 0
        while (i < flames.size) {
            val lx = flames[i] - x; val ly = flames[i + 1] - y; val lz = flames[i + 2] - z
            val d2 = lx * lx + ly * ly + lz * lz
            var c = 1f
            if (useNormal) {
                val d = sqrt(d2).coerceAtLeast(1e-4f)
                c = ((lx * nx + ly * ny + lz * nz) / d).coerceAtLeast(0f)
            }
            e += K * c / (d2 + SOFT)
            i += 3
        }
        return e
    }

    /** The factor a vertex colour is multiplied by. */
    fun factor(e: Float, gain: Float, floor: Float, cut: Float): Float = (floor + gain * e - cut).coerceIn(0f, 1f)

    /** Multiplies the rgb of every vertex of a STATIC mesh in place. */
    fun bake(m: BakedMesh, useNormal: Boolean, gain: Float = 1f, floor: Float = 0f, cut: Float = 0f) {
        require(m.layout == VertexLayout.STATIC) { "LightBake works on STATIC meshes" }
        val f = m.layout.floats
        val v = m.vertices
        var o = 0
        while (o < v.size) {
            val e = irradiance(v[o], v[o + 1], v[o + 2], v[o + 3], v[o + 4], v[o + 5], useNormal)
            val k = factor(e, gain, floor, cut)
            v[o + 8] *= k; v[o + 9] *= k; v[o + 10] *= k
            o += f
        }
    }
}
