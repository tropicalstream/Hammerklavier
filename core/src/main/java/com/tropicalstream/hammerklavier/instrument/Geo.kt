package com.tropicalstream.hammerklavier.instrument

import com.tropicalstream.hammerklavier.contract.InstrumentLook
import com.tropicalstream.hammerklavier.contract.RoomLevel
import com.tropicalstream.hammerklavier.mesh.MeshBuilder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * View-mask bits (BakedMesh.viewMask: bit view.ordinal * 2 + framing) and level masks used by the
 * instrument models (PLAN §5.3 "Shown in").
 */
internal object VM {
    const val PLAYER0 = 1; const val PLAYER1 = 2; const val CUT = 4; const val OVER = 8; const val HALL0 = 16; const val HALL1 = 32
    const val ALL = 63
    const val PLAYER = PLAYER0 or PLAYER1
    const val ACTION = CUT or OVER
    const val HALL = HALL0 or HALL1
    const val NO_OVER = ALL and OVER.inv()
    const val ACTION_HALL = ACTION or HALL
    const val PLAYER_HALL = PLAYER or HALL
    const val LEVELS_ALL = 0xF

    /** Feature-edge overlay (§5.3 row 18): Passthrough only, or every level when the look turns it on. */
    fun edgeLevels(look: InstrumentLook): Int = if (look.edgeOverlay) LEVELS_ALL else 1 shl RoomLevel.PASSTHROUGH.ordinal
}

/** Pure helpers on top of MeshBuilder for the instrument models (HKLoader only; they allocate). */
internal object Geo {
    fun mul(rgb: IntArray, f: Float): IntArray = IntArray(3) { (rgb[it] * f + 0.5f).toInt().coerceIn(0, 255) }

    /** Closed polygon (x, z pairs) made counter-clockwise in the (x, z) number plane. */
    fun ccw(poly: FloatArray): FloatArray = MeshBuilder.ccw(poly)

    /**
     * Offsets a closed polygon inward by [d] (mitred; moderate corners only). Input in any
     * orientation; output counter-clockwise.
     */
    fun inset(poly: FloatArray, d: Float): FloatArray {
        val p = ccw(poly); val n = p.size / 2
        val out = FloatArray(p.size)
        for (i in 0 until n) {
            val ip = (i + n - 1) % n; val inx = (i + 1) % n
            var ax = p[2 * i] - p[2 * ip]; var az = p[2 * i + 1] - p[2 * ip + 1]
            var bx = p[2 * inx] - p[2 * i]; var bz = p[2 * inx + 1] - p[2 * i + 1]
            val la = sqrt(ax * ax + az * az); val lb = sqrt(bx * bx + bz * bz)
            ax /= la; az /= la; bx /= lb; bz /= lb
            // Left normals point inside for a CCW polygon.
            val n1x = -az; val n1z = ax; val n2x = -bz; val n2z = bx
            var mx = n1x + n2x; var mz = n1z + n2z
            val ml = sqrt(mx * mx + mz * mz)
            if (ml < 1e-6f) { mx = n1x; mz = n1z } else { mx /= ml; mz /= ml }
            val cosHalf = (mx * n1x + mz * n1z).coerceAtLeast(0.35f)
            out[2 * i] = p[2 * i] + mx * d / cosHalf; out[2 * i + 1] = p[2 * i + 1] + mz * d / cosHalf
        }
        return out
    }

    /** Sutherland–Hodgman clip of a closed polygon to z ≤ zMax. */
    fun clipZBelow(poly: FloatArray, zMax: Float): FloatArray {
        val n = poly.size / 2
        val out = ArrayList<Float>()
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ax = poly[2 * i]; val az = poly[2 * i + 1]; val bx = poly[2 * j]; val bz = poly[2 * j + 1]
            val ain = az <= zMax; val bin = bz <= zMax
            if (ain) { out.add(ax); out.add(az) }
            if (ain != bin) {
                val t = (zMax - az) / (bz - az)
                out.add(ax + (bx - ax) * t); out.add(zMax)
            }
        }
        return out.toFloatArray()
    }

    /** Distance along the ray (ox, oz) + t·(dx, dz) (unit) to the first polygon edge; +inf if none. */
    fun rayExit(poly: FloatArray, ox: Float, oz: Float, dx: Float, dz: Float): Float {
        val n = poly.size / 2
        var best = Float.POSITIVE_INFINITY
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ax = poly[2 * i]; val az = poly[2 * i + 1]
            val ex = poly[2 * j] - ax; val ez = poly[2 * j + 1] - az
            val den = dx * ez - dz * ex
            if (abs(den) < 1e-9f) continue
            val wx = ax - ox; val wz = az - oz
            val t = (wx * ez - wz * ex) / den
            val s = (wx * dz - wz * dx) / den
            if (t > 1e-5f && s >= 0f && s <= 1f && t < best) best = t
        }
        return best
    }

    /** Horizontal polygon at height y, facing up or down; uv = (x, z) m, or normalised to [uvRect] (x0, z0, x1, z1). */
    fun flat(mb: MeshBuilder, poly: FloatArray, y: Float, up: Boolean, uvRect: FloatArray? = null) {
        val p = ccw(poly)
        val tris = MeshBuilder.earClip(p)
        val ny = if (up) 1f else -1f
        val base = IntArray(p.size / 2)
        for (i in base.indices) {
            val x = p[2 * i]; val z = p[2 * i + 1]
            val u: Float; val v: Float
            if (uvRect != null) { u = (x - uvRect[0]) / (uvRect[2] - uvRect[0]); v = (z - uvRect[1]) / (uvRect[3] - uvRect[1]) }
            else { u = x; v = z }
            base[i] = mb.vertex(x, y, z, 0f, ny, 0f, u, v)
        }
        for (t in tris) mb.triOutward(base[t[0]], base[t[1]], base[t[2]])
    }

    /**
     * A wall of [thickness] along a closed outline from y0 to y1: outer faces (smooth, outward),
     * inner faces (smooth, facing the inside), top strip. [openEdge] (index i = edge i → i+1 of
     * the CCW outline) is left out (a key-well opening). Inner faces use [innerRgb] when given.
     */
    fun wall(mb: MeshBuilder, outline: FloatArray, thickness: Float, y0: Float, y1: Float, openEdge: Int = -1,
             outerRgb: IntArray, innerRgb: IntArray = outerRgb, topRgb: IntArray = outerRgb) {
        val o = ccw(outline); val inner = inset(o, thickness); val n = o.size / 2
        fun normals(p: FloatArray, sign: Float): FloatArray {
            val nn = FloatArray(p.size)
            for (i in 0 until n) {
                var sx = 0f; var sz = 0f
                for (e in intArrayOf((i + n - 1) % n, i)) {
                    if (e == openEdge) continue
                    val j = (e + 1) % n
                    val ex = p[2 * j] - p[2 * e]; val ez = p[2 * j + 1] - p[2 * e + 1]
                    val l = sqrt(ex * ex + ez * ez); if (l <= 0f) continue
                    sx += sign * ez / l; sz += -sign * ex / l      // outward (sign +1) of a CCW polygon
                }
                val l = sqrt(sx * sx + sz * sz)
                if (l > 0f) { nn[2 * i] = sx / l; nn[2 * i + 1] = sz / l } else { nn[2 * i] = 0f; nn[2 * i + 1] = 1f }
            }
            return nn
        }
        val no = normals(o, 1f); val ni = normals(inner, -1f)
        var arc = 0f
        for (e in 0 until n) {
            val j = (e + 1) % n
            val len = sqrt((o[2 * j] - o[2 * e]).let { it * it } + (o[2 * j + 1] - o[2 * e + 1]).let { it * it })
            if (e != openEdge) {
                mb.color(outerRgb)
                val a = mb.vertex(o[2 * e], y0, o[2 * e + 1], no[2 * e], 0f, no[2 * e + 1], arc, y0)
                val b = mb.vertex(o[2 * j], y0, o[2 * j + 1], no[2 * j], 0f, no[2 * j + 1], arc + len, y0)
                val c = mb.vertex(o[2 * j], y1, o[2 * j + 1], no[2 * j], 0f, no[2 * j + 1], arc + len, y1)
                val d = mb.vertex(o[2 * e], y1, o[2 * e + 1], no[2 * e], 0f, no[2 * e + 1], arc, y1)
                mb.triOutward(a, b, c); mb.triOutward(a, c, d)
                mb.color(innerRgb)
                val ia = mb.vertex(inner[2 * e], y0, inner[2 * e + 1], ni[2 * e], 0f, ni[2 * e + 1], arc, y0)
                val ib = mb.vertex(inner[2 * j], y0, inner[2 * j + 1], ni[2 * j], 0f, ni[2 * j + 1], arc + len, y0)
                val ic = mb.vertex(inner[2 * j], y1, inner[2 * j + 1], ni[2 * j], 0f, ni[2 * j + 1], arc + len, y1)
                val id = mb.vertex(inner[2 * e], y1, inner[2 * e + 1], ni[2 * e], 0f, ni[2 * e + 1], arc, y1)
                mb.triOutward(ia, ib, ic); mb.triOutward(ia, ic, id)
                mb.color(topRgb)
                val ta = mb.vertex(o[2 * e], y1, o[2 * e + 1], 0f, 1f, 0f, o[2 * e], o[2 * e + 1])
                val tb = mb.vertex(o[2 * j], y1, o[2 * j + 1], 0f, 1f, 0f, o[2 * j], o[2 * j + 1])
                val tc = mb.vertex(inner[2 * j], y1, inner[2 * j + 1], 0f, 1f, 0f, inner[2 * j], inner[2 * j + 1])
                val td = mb.vertex(inner[2 * e], y1, inner[2 * e + 1], 0f, 1f, 0f, inner[2 * e], inner[2 * e + 1])
                mb.triOutward(ta, tb, tc); mb.triOutward(ta, tc, td)
            } else {
                // The opening: cap the two wall ends so the cut is not hollow.
                mb.color(topRgb)
                for ((k, kk) in arrayOf(e to e, j to j)) {
                    val ex = o[2 * j] - o[2 * e]; val ez = o[2 * j + 1] - o[2 * e + 1]
                    val l = sqrt(ex * ex + ez * ez); val s = if (k == e) 1f else -1f
                    val nx = s * ex / l; val nz = s * ez / l
                    val a = mb.vertex(o[2 * k], y0, o[2 * k + 1], nx, 0f, nz, 0f, y0)
                    val b = mb.vertex(inner[2 * kk], y0, inner[2 * kk + 1], nx, 0f, nz, thickness, y0)
                    val c = mb.vertex(inner[2 * kk], y1, inner[2 * kk + 1], nx, 0f, nz, thickness, y1)
                    val d = mb.vertex(o[2 * k], y1, o[2 * k + 1], nx, 0f, nz, 0f, y1)
                    mb.triOutward(a, b, c); mb.triOutward(a, c, d)
                }
            }
            arc += len
        }
    }

    /**
     * An axis-aligned cuboid with the given faces (bit 0 −x, 1 +x, 2 −y, 3 +y, 4 −z, 5 +z) and a
     * fixed uv for every vertex ([u], [v]); used where uv carries data (ACTION_SET: (slot, part)).
     */
    fun cuboidUv(mb: MeshBuilder, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, u: Float, v: Float,
                 faces: Int = 63) {
        fun f(nx: Float, ny: Float, nz: Float, ax: FloatArray, bx: FloatArray, cx: FloatArray, dx: FloatArray) {
            val a = mb.vertex(ax[0], ax[1], ax[2], nx, ny, nz, u, v); val b = mb.vertex(bx[0], bx[1], bx[2], nx, ny, nz, u, v)
            val c = mb.vertex(cx[0], cx[1], cx[2], nx, ny, nz, u, v); val d = mb.vertex(dx[0], dx[1], dx[2], nx, ny, nz, u, v)
            mb.triOutward(a, b, c); mb.triOutward(a, c, d)
        }
        fun p(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
        if (faces and 1 != 0) f(-1f, 0f, 0f, p(x0, y0, z0), p(x0, y0, z1), p(x0, y1, z1), p(x0, y1, z0))
        if (faces and 2 != 0) f(1f, 0f, 0f, p(x1, y0, z0), p(x1, y1, z0), p(x1, y1, z1), p(x1, y0, z1))
        if (faces and 4 != 0) f(0f, -1f, 0f, p(x0, y0, z0), p(x1, y0, z0), p(x1, y0, z1), p(x0, y0, z1))
        if (faces and 8 != 0) f(0f, 1f, 0f, p(x0, y1, z0), p(x0, y1, z1), p(x1, y1, z1), p(x1, y1, z0))
        if (faces and 16 != 0) f(0f, 0f, -1f, p(x0, y0, z0), p(x0, y1, z0), p(x1, y1, z0), p(x1, y0, z0))
        if (faces and 32 != 0) f(0f, 0f, 1f, p(x0, y0, z1), p(x1, y0, z1), p(x1, y1, z1), p(x0, y1, z1))
    }

    /** A vertical rectangle in the plane z = [z] facing +z (front) with uv 0..1 (a decal or a textured board). */
    fun quadZ(mb: MeshBuilder, x0: Float, y0: Float, x1: Float, y1: Float, z: Float, facePlus: Boolean = true) {
        val nz = if (facePlus) 1f else -1f
        val a = mb.vertex(x0, y0, z, 0f, 0f, nz, 0f, 1f); val b = mb.vertex(x1, y0, z, 0f, 0f, nz, 1f, 1f)
        val c = mb.vertex(x1, y1, z, 0f, 0f, nz, 1f, 0f); val d = mb.vertex(x0, y1, z, 0f, 0f, nz, 0f, 0f)
        mb.triOutward(a, b, c); mb.triOutward(a, c, d)
    }

    /** Samples an open Catmull-Rom chain of (u, v) plan points, mapped by x = x0 + u·w, z = z0 − v·l. */
    fun planChain(uv: FloatArray, samples: Int, x0: Float, w: Float, z0: Float, l: Float): FloatArray {
        val c = MeshBuilder.catmullRom(uv, 2, samples, false)
        return FloatArray(c.size) { i -> if (i % 2 == 0) x0 + c[i] * w else z0 - c[i] * l }
    }

    /** Axis-aligned bounds of the xyz of every vertex: (minX, minY, minZ, maxX, maxY, maxZ). */
    fun bounds(meshes: List<com.tropicalstream.hammerklavier.contract.BakedMesh>): FloatArray {
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (m in meshes) {
            val f = m.layout.floats
            for (i in 0 until m.vertexCount) for (c in 0..2) {
                val x = m.vertices[i * f + c]
                if (x < b[c]) b[c] = x; if (x > b[c + 3]) b[c + 3] = x
            }
        }
        return b
    }

    /** An outline strip of half-width [hw] along a polyline in (x, z), as a closed polygon. */
    fun strip(path: FloatArray, hw: Float): FloatArray {
        val m = path.size / 2
        val left = FloatArray(m * 2); val right = FloatArray(m * 2)
        for (i in 0 until m) {
            val ip = maxOf(0, i - 1); val inx = minOf(m - 1, i + 1)
            var dx = path[2 * inx] - path[2 * ip]; var dz = path[2 * inx + 1] - path[2 * ip + 1]
            val l = sqrt(dx * dx + dz * dz); dx /= l; dz /= l
            left[2 * i] = path[2 * i] - dz * hw; left[2 * i + 1] = path[2 * i + 1] + dx * hw
            right[2 * i] = path[2 * i] + dz * hw; right[2 * i + 1] = path[2 * i + 1] - dx * hw
        }
        val out = FloatArray(m * 4)
        for (i in 0 until m) { out[2 * i] = left[2 * i]; out[2 * i + 1] = left[2 * i + 1] }
        for (i in 0 until m) { val s = m - 1 - i; out[2 * (m + i)] = right[2 * s]; out[2 * (m + i) + 1] = right[2 * s + 1] }
        return out
    }
}
