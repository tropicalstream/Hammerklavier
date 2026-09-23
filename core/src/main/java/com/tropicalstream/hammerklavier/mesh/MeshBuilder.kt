package com.tropicalstream.hammerklavier.mesh

import com.tropicalstream.hammerklavier.contract.BakedMesh
import com.tropicalstream.hammerklavier.contract.MaterialId
import com.tropicalstream.hammerklavier.contract.ProgramId
import com.tropicalstream.hammerklavier.contract.SkinKind
import com.tropicalstream.hammerklavier.contract.VertexLayout
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The shared mesh builder (conventions frozen in `contract/Scene.kt`). WP0 ships the day-0 subset
 * (part, color, vertex, tri, quad, box, build with the 65,535-vertex split); WP7 owns this file
 * from its first merge and adds extrude, lathe, sweep, ribbon and spindle.
 *
 * Vertex packing per layout (floats):
 * - STATIC (12): pos3 nrm3 uv2 rgba4
 * - SKINNED (17): STATIC + slot1 + lane4 (one-hot, from [part])
 * - STRING (16): pos3, dir3 (the `nrm` argument), t1 and side1 (the `uv` argument), slot1, lane4, rgb3
 * Runs on HKLoader; allocates freely (and may use transcendental maths: it never runs per frame).
 *
 * Coordinates: y up. Every lit primitive emits CCW-outward triangles with unit normals; the
 * surface primitives go through [triOutward], which orders each triangle so its geometric normal
 * agrees with its vertex normals, so winding is correct by construction. Zero-area triangles
 * (lathe poles, collapsed profile points) are dropped.
 */
class MeshBuilder(val layout: VertexLayout, capacityVerts: Int = 1024) {
    private var v = FloatArray(maxOf(16, capacityVerts) * layout.floats)
    private var vn = 0                                  // vertices written
    private var ix = IntArray(maxOf(48, capacityVerts * 3 / 2))
    private var ixn = 0                                 // indices written

    private var slot = 0
    private var lane = 0
    private var r = 1f; private var g = 1f; private var b = 1f; private var a = 1f

    val vertexCount: Int get() = vn
    val triangleCount: Int get() = ixn / 3

    /** Skin slot (vec4 index into uState) and one-hot lane 0..3 for the following vertices. */
    fun part(slot: Int, lane: Int) {
        require(lane in 0..3) { "lane must be 0..3" }
        this.slot = slot; this.lane = lane
    }

    /** UN-lifted sRGB 0..255 (a Pal token); stored as 0..1 floats. */
    fun color(rgb: IntArray, a: Float = 1f) {
        r = rgb[0] / 255f; g = rgb[1] / 255f; b = rgb[2] / 255f; this.a = a
    }

    /** Appends one vertex; returns its index. */
    fun vertex(pos: FloatArray, nrm: FloatArray, uv: FloatArray): Int =
        vertex(pos[0], pos[1], pos[2], nrm[0], nrm[1], nrm[2], uv[0], uv[1])

    fun vertex(px: Float, py: Float, pz: Float, nx: Float, ny: Float, nz: Float, u: Float, w: Float): Int {
        val f = layout.floats
        if ((vn + 1) * f > v.size) v = v.copyOf(v.size * 2)
        var o = vn * f
        v[o++] = px; v[o++] = py; v[o++] = pz
        v[o++] = nx; v[o++] = ny; v[o++] = nz
        v[o++] = u; v[o++] = w
        when (layout) {
            VertexLayout.STATIC -> { v[o++] = r; v[o++] = g; v[o++] = b; v[o] = a }
            VertexLayout.SKINNED -> {
                v[o++] = r; v[o++] = g; v[o++] = b; v[o++] = a
                v[o++] = slot.toFloat()
                for (l in 0..3) v[o++] = if (l == lane) 1f else 0f
            }
            VertexLayout.STRING -> {
                v[o++] = slot.toFloat()
                for (l in 0..3) v[o++] = if (l == lane) 1f else 0f
                v[o++] = r; v[o++] = g; v[o] = b
            }
        }
        return vn++
    }

    /** One triangle, CCW seen from the front. */
    fun tri(a: Int, b: Int, c: Int) {
        require(a in 0 until vn && b in 0 until vn && c in 0 until vn) { "index out of range" }
        if (ixn + 3 > ix.size) ix = ix.copyOf(ix.size * 2)
        ix[ixn++] = a; ix[ixn++] = b; ix[ixn++] = c
    }

    /**
     * A planar quad p0 p1 p2 p3, CCW seen from the front: the normal is (p1 − p0) × (p3 − p0),
     * normalised; u runs along p0 → p1 and v along p0 → p3, in metres. Two triangles (p0 p1 p2),
     * (p0 p2 p3). Returns the index of p0's vertex.
     */
    fun quad(p0: FloatArray, p1: FloatArray, p2: FloatArray, p3: FloatArray): Int {
        val e1x = p1[0] - p0[0]; val e1y = p1[1] - p0[1]; val e1z = p1[2] - p0[2]
        val e3x = p3[0] - p0[0]; val e3y = p3[1] - p0[1]; val e3z = p3[2] - p0[2]
        var nx = e1y * e3z - e1z * e3y; var ny = e1z * e3x - e1x * e3z; var nz = e1x * e3y - e1y * e3x
        val nl = sqrt(nx * nx + ny * ny + nz * nz)
        require(nl > 0f) { "degenerate quad" }
        nx /= nl; ny /= nl; nz /= nl
        val l1 = sqrt(e1x * e1x + e1y * e1y + e1z * e1z)
        val l3 = sqrt(e3x * e3x + e3y * e3y + e3z * e3z)
        fun uvOf(p: FloatArray, axis: Int): Float {
            val dx = p[0] - p0[0]; val dy = p[1] - p0[1]; val dz = p[2] - p0[2]
            return if (axis == 0) (dx * e1x + dy * e1y + dz * e1z) / l1 else (dx * e3x + dy * e3y + dz * e3z) / l3
        }
        val i0 = vertex(p0[0], p0[1], p0[2], nx, ny, nz, 0f, 0f)
        val i1 = vertex(p1[0], p1[1], p1[2], nx, ny, nz, uvOf(p1, 0), uvOf(p1, 1))
        val i2 = vertex(p2[0], p2[1], p2[2], nx, ny, nz, uvOf(p2, 0), uvOf(p2, 1))
        val i3 = vertex(p3[0], p3[1], p3[2], nx, ny, nz, uvOf(p3, 0), uvOf(p3, 1))
        tri(i0, i1, i2); tri(i0, i2, i3)
        return i0
    }

    /**
     * An axis-aligned box between two corners: 6 faces, 24 vertices, 12 triangles, outward unit
     * normals, per-face UV in metres (u along the face's first edge, v along its second).
     */
    fun box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float) {
        val ax = minOf(x0, x1); val bx = maxOf(x0, x1)
        val ay = minOf(y0, y1); val by = maxOf(y0, y1)
        val az = minOf(z0, z1); val bz = maxOf(z0, z1)
        fun p(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
        quad(p(bx, ay, bz), p(bx, ay, az), p(bx, by, az), p(bx, by, bz))   // +x
        quad(p(ax, ay, az), p(ax, ay, bz), p(ax, by, bz), p(ax, by, az))   // −x
        quad(p(ax, by, bz), p(bx, by, bz), p(bx, by, az), p(ax, by, az))   // +y
        quad(p(ax, ay, az), p(bx, ay, az), p(bx, ay, bz), p(ax, ay, bz))   // −y
        quad(p(ax, ay, bz), p(bx, ay, bz), p(bx, by, bz), p(ax, by, bz))   // +z
        quad(p(bx, ay, az), p(ax, ay, az), p(ax, by, az), p(bx, by, az))   // −z
    }


    /**
     * A triangle whose order is chosen so that its geometric normal agrees with the vertex normals
     * (CCW outward). Zero-area triangles are dropped. Returns false when dropped.
     */
    fun triOutward(a: Int, b: Int, c: Int): Boolean {
        val f = layout.floats
        val oa = a * f; val ob = b * f; val oc = c * f
        val ux = v[ob] - v[oa]; val uy = v[ob + 1] - v[oa + 1]; val uz = v[ob + 2] - v[oa + 2]
        val wx = v[oc] - v[oa]; val wy = v[oc + 1] - v[oa + 1]; val wz = v[oc + 2] - v[oa + 2]
        val nx = uy * wz - uz * wy; val ny = uz * wx - ux * wz; val nz = ux * wy - uy * wx
        val area2 = sqrt(nx * nx + ny * ny + nz * nz)
        val scale = sqrt(ux * ux + uy * uy + uz * uz) * sqrt(wx * wx + wy * wy + wz * wz)
        if (area2 <= 1e-7f * scale || area2 < 1e-14f) return false
        val sx = v[oa + 3] + v[ob + 3] + v[oc + 3]
        val sy = v[oa + 4] + v[ob + 4] + v[oc + 4]
        val sz = v[oa + 5] + v[ob + 5] + v[oc + 5]
        if (nx * sx + ny * sy + nz * sz >= 0f) tri(a, b, c) else tri(a, c, b)
        return true
    }

    /**
     * A prism: the closed outline (x, z pairs, either orientation, may be concave, not
     * self-intersecting) extruded from y0 to y1. Sides: u = outline arc length (m), v = y (m);
     * normals are smoothed across corners that turn less than [creaseDeg] and split at sharper
     * ones. Caps (optional): ear-clipped, normal ±y, uv = (x, z) m.
     */
    fun extrude(outlineXZ: FloatArray, y0: Float, y1: Float, capTop: Boolean, capBottom: Boolean, creaseDeg: Float = 30f) {
        val lo = minOf(y0, y1); val hi = maxOf(y0, y1)
        val poly = ccw(outlineXZ)
        val ring = Ring.of(poly, creaseDeg)
        for (s in 0 until ring.edges) {
            val k0 = ring.startOf(s); val k1 = ring.endOf(s)
            val b0 = vertex(ring.a[k0], lo, ring.b[k0], ring.na[k0], 0f, ring.nb[k0], ring.arc[k0], lo)
            val b1 = vertex(ring.a[k1], lo, ring.b[k1], ring.na[k1], 0f, ring.nb[k1], ring.arc[k1], lo)
            val t1 = vertex(ring.a[k1], hi, ring.b[k1], ring.na[k1], 0f, ring.nb[k1], ring.arc[k1], hi)
            val t0 = vertex(ring.a[k0], hi, ring.b[k0], ring.na[k0], 0f, ring.nb[k0], ring.arc[k0], hi)
            triOutward(b0, b1, t1); triOutward(b0, t1, t0)
        }
        if (capTop) cap(poly) { x, z -> vertex(x, hi, z, 0f, 1f, 0f, x, z) }
        if (capBottom) cap(poly) { x, z -> vertex(x, lo, z, 0f, -1f, 0f, x, z) }
    }

    /**
     * A surface of revolution about the vertical axis through (cx, cz): the profile is (r, y)
     * pairs with r ≥ 0, running bottom to top for an outward-facing surface (normal = the profile
     * tangent turned clockwise). u = angle / 2π, v = profile arc length (m); normals are smooth.
     * A seam column is duplicated so u reaches 1.
     */
    fun lathe(profileRY: FloatArray, segments: Int, cx: Float, cz: Float) {
        val n = profileRY.size / 2
        require(n >= 2 && segments >= 3) { "lathe needs 2 profile points and 3 segments" }
        val pr = FloatArray(n) { profileRY[2 * it] }; val py = FloatArray(n) { profileRY[2 * it + 1] }
        val nr = FloatArray(n); val ny = FloatArray(n); val arc = FloatArray(n)
        for (i in 0 until n) {
            var tr = 0f; var ty = 0f
            if (i > 0) { val dr = pr[i] - pr[i - 1]; val dy = py[i] - py[i - 1]; val l = sqrt(dr * dr + dy * dy); if (l > 0f) { tr += dr / l; ty += dy / l }; arc[i] = arc[i - 1] + l }
            if (i < n - 1) { val dr = pr[i + 1] - pr[i]; val dy = py[i + 1] - py[i]; val l = sqrt(dr * dr + dy * dy); if (l > 0f) { tr += dr / l; ty += dy / l } }
            var ar = ty; var ay = -tr
            val l = sqrt(ar * ar + ay * ay)
            if (l > 0f) { ar /= l; ay /= l } else { ar = 1f; ay = 0f }
            nr[i] = ar; ny[i] = ay
        }
        val base = vn
        val cols = segments + 1
        for (i in 0 until n) for (j in 0 until cols) {
            val th = (2.0 * Math.PI * j / segments)
            val c = cos(th).toFloat(); val s = if (j == segments) 0f else sin(th).toFloat()
            vertex(cx + pr[i] * c, py[i], cz - pr[i] * s, nr[i] * c, ny[i], -nr[i] * s, j.toFloat() / segments, arc[i])
        }
        for (i in 0 until n - 1) for (j in 0 until segments) {
            val a = base + i * cols + j; val b = a + 1; val c = a + cols + 1; val d = a + cols
            triOutward(a, b, c); triOutward(a, c, d)
        }
    }

    /**
     * A tube: the closed profile (x, y pairs in the path's normal/binormal frame, either
     * orientation) swept along the Catmull-Rom curve through [pathXYZ] ([samplesPerSegment] points
     * per span; 1 uses the points as given). Frames are parallel-transported; a closed path spreads
     * the residual twist evenly. u = path arc length, v = profile arc length (m). Open paths get
     * flat end caps when [caps] is true.
     */
    fun sweep(pathXYZ: FloatArray, profileXY: FloatArray, closed: Boolean, samplesPerSegment: Int = 4,
              caps: Boolean = true, creaseDeg: Float = 30f) {
        val path = if (samplesPerSegment <= 1) pathXYZ.copyOf() else catmullRom(pathXYZ, 3, samplesPerSegment, closed)
        var m = path.size / 3
        val pts = if (closed) path.copyOf((m + 1) * 3).also { System.arraycopy(path, 0, it, m * 3, 3); m += 1 } else path
        require(m >= 2) { "sweep needs 2 path points" }
        val prof = ccw(profileXY)
        val ring = Ring.of(prof, creaseDeg)
        // Tangents.
        val t = FloatArray(m * 3)
        for (i in 0 until m) {
            val ip = if (i > 0) i - 1 else if (closed) m - 2 else 0
            val inx = if (i < m - 1) i + 1 else if (closed) 1 else m - 1
            var x = pts[inx * 3] - pts[ip * 3]; var y = pts[inx * 3 + 1] - pts[ip * 3 + 1]; var z = pts[inx * 3 + 2] - pts[ip * 3 + 2]
            val l = sqrt(x * x + y * y + z * z); require(l > 0f) { "repeated sweep path point" }
            x /= l; y /= l; z /= l
            t[i * 3] = x; t[i * 3 + 1] = y; t[i * 3 + 2] = z
        }
        // Parallel transport of N.
        val nN = FloatArray(m * 3); val nB = FloatArray(m * 3)
        run {
            val tx = t[0]; val ty = t[1]; val tz = t[2]
            var ax = 0f; var ay = 1f; var az = 0f
            if (abs(ty) > 0.9f) { ax = 1f; ay = 0f }
            val d = ax * tx + ay * ty + az * tz
            ax -= d * tx; ay -= d * ty; az -= d * tz
            val l = sqrt(ax * ax + ay * ay + az * az)
            nN[0] = ax / l; nN[1] = ay / l; nN[2] = az / l
        }
        binormal(t, nN, nB, 0)
        for (i in 1 until m) {
            val o = i * 3
            var ax = nN[o - 3]; var ay = nN[o - 2]; var az = nN[o - 1]
            val d = ax * t[o] + ay * t[o + 1] + az * t[o + 2]
            ax -= d * t[o]; ay -= d * t[o + 1]; az -= d * t[o + 2]
            var l = sqrt(ax * ax + ay * ay + az * az)
            if (l < 1e-6f) { ax = nB[o - 3]; ay = nB[o - 2]; az = nB[o - 1]; l = 1f }
            nN[o] = ax / l; nN[o + 1] = ay / l; nN[o + 2] = az / l
            binormal(t, nN, nB, i)
        }
        if (closed) {
            val e = (m - 1) * 3
            val cx = nN[e + 1] * nN[2] - nN[e + 2] * nN[1]
            val cy = nN[e + 2] * nN[0] - nN[e] * nN[2]
            val cz = nN[e] * nN[1] - nN[e + 1] * nN[0]
            val phi = atan2(cx * t[0] + cy * t[1] + cz * t[2], nN[e] * nN[0] + nN[e + 1] * nN[1] + nN[e + 2] * nN[2])
            for (i in 1 until m) {
                val ang = phi * i / (m - 1); val c = cos(ang); val s = sin(ang); val o = i * 3
                val x = c * nN[o] + s * nB[o]; val y = c * nN[o + 1] + s * nB[o + 1]; val z = c * nN[o + 2] + s * nB[o + 2]
                nN[o] = x; nN[o + 1] = y; nN[o + 2] = z
                binormal(t, nN, nB, i)
            }
        }
        // Vertices.
        val rk = ring.count
        val base = vn
        var arcP = 0f
        for (i in 0 until m) {
            val o = i * 3
            if (i > 0) { val dx = pts[o] - pts[o - 3]; val dy = pts[o + 1] - pts[o - 2]; val dz = pts[o + 2] - pts[o - 1]; arcP += sqrt(dx * dx + dy * dy + dz * dz) }
            for (k in 0 until rk) {
                val a = ring.a[k]; val b = ring.b[k]
                vertex(pts[o] + a * nN[o] + b * nB[o], pts[o + 1] + a * nN[o + 1] + b * nB[o + 1], pts[o + 2] + a * nN[o + 2] + b * nB[o + 2],
                    ring.na[k] * nN[o] + ring.nb[k] * nB[o], ring.na[k] * nN[o + 1] + ring.nb[k] * nB[o + 1], ring.na[k] * nN[o + 2] + ring.nb[k] * nB[o + 2],
                    arcP, ring.arc[k])
            }
        }
        for (i in 0 until m - 1) for (s in 0 until ring.edges) {
            val a = base + i * rk + ring.startOf(s); val b = base + i * rk + ring.endOf(s)
            triOutward(a, b, b + rk); triOutward(a, b + rk, a + rk)
        }
        if (caps && !closed) {
            for (end in 0..1) {
                val o = if (end == 0) 0 else (m - 1) * 3
                val sg = if (end == 0) -1f else 1f
                cap(prof) { a, b ->
                    vertex(pts[o] + a * nN[o] + b * nB[o], pts[o + 1] + a * nN[o + 1] + b * nB[o + 1], pts[o + 2] + a * nN[o + 2] + b * nB[o + 2],
                        sg * t[o], sg * t[o + 1], sg * t[o + 2], a, b)
                }
            }
        }
    }

    /**
     * A screen-space line strip (STATIC layout, RIBBON program): two vertices per path point with
     * pos = the point, nrm = the unit path tangent, uv = (side −1 / +1, half-width m). A closed
     * ribbon joins its last point to its first.
     */
    fun ribbon(pathXYZ: FloatArray, widthM: Float, closed: Boolean = false) {
        require(layout == VertexLayout.STATIC) { "ribbons use the STATIC layout" }
        val m = pathXYZ.size / 3
        require(m >= 2) { "ribbon needs 2 points" }
        val hw = widthM * 0.5f
        val base = vn
        for (i in 0 until m) {
            val ip = if (i > 0) i - 1 else if (closed) m - 1 else 0
            val inx = if (i < m - 1) i + 1 else if (closed) 0 else m - 1
            var x = pathXYZ[inx * 3] - pathXYZ[ip * 3]; var y = pathXYZ[inx * 3 + 1] - pathXYZ[ip * 3 + 1]; var z = pathXYZ[inx * 3 + 2] - pathXYZ[ip * 3 + 2]
            val l = sqrt(x * x + y * y + z * z); require(l > 0f) { "repeated ribbon point" }
            x /= l; y /= l; z /= l
            val px = pathXYZ[i * 3]; val py = pathXYZ[i * 3 + 1]; val pz = pathXYZ[i * 3 + 2]
            vertex(px, py, pz, x, y, z, -1f, hw); vertex(px, py, pz, x, y, z, 1f, hw)
        }
        val spans = if (closed) m else m - 1
        for (i in 0 until spans) {
            val a = base + 2 * i; val b = base + 2 * ((i + 1) % m)
            tri(a, a + 1, b + 1); tri(a, b + 1, b)
        }
    }

    /**
     * A string (STRING layout): [segments] spans from a to b, two vertices per station with
     * pos = the rest point, dir = unit(b − a), t = station / segments (0 at a, 1 at b), side ±1,
     * and rgb from [rgb] (un-lifted sRGB 0..255; it also becomes the current colour). The skin
     * slot and lane come from [part]. The shader widens it in screen space and displaces it by t.
     */
    fun spindle(a: FloatArray, b: FloatArray, segments: Int, rgb: IntArray) {
        require(layout == VertexLayout.STRING) { "spindle uses the STRING layout" }
        require(segments >= 1)
        color(rgb, this.a)
        var dx = b[0] - a[0]; var dy = b[1] - a[1]; var dz = b[2] - a[2]
        val l = sqrt(dx * dx + dy * dy + dz * dz); require(l > 0f) { "zero-length spindle" }
        dx /= l; dy /= l; dz /= l
        val base = vn
        for (i in 0..segments) {
            val t = i.toFloat() / segments
            val px = a[0] + (b[0] - a[0]) * t; val py = a[1] + (b[1] - a[1]) * t; val pz = a[2] + (b[2] - a[2]) * t
            vertex(px, py, pz, dx, dy, dz, t, -1f); vertex(px, py, pz, dx, dy, dz, t, 1f)
        }
        for (i in 0 until segments) {
            val p = base + 2 * i
            tri(p, p + 1, p + 3); tri(p, p + 3, p + 2)
        }
    }

    private fun binormal(t: FloatArray, n: FloatArray, b: FloatArray, i: Int) {
        val o = i * 3
        // B = T × N (right-handed T, N, B)
        b[o] = t[o + 1] * n[o + 2] - t[o + 2] * n[o + 1]
        b[o + 1] = t[o + 2] * n[o] - t[o] * n[o + 2]
        b[o + 2] = t[o] * n[o + 1] - t[o + 1] * n[o]
    }

    /** Ear-clips the CCW polygon [poly] (a, b pairs); [emit] makes one vertex per corner. */
    private inline fun cap(poly: FloatArray, emit: (Float, Float) -> Int) {
        val n = poly.size / 2
        val idx = IntArray(n) { emit(poly[2 * it], poly[2 * it + 1]) }
        for (t in earClip(poly)) triOutward(idx[t[0]], idx[t[1]], idx[t[2]])
    }

    /** A closed 2D polygon's corners with outward normals and arc length; sharp corners duplicated. */
    private class Ring(val a: FloatArray, val b: FloatArray, val na: FloatArray, val nb: FloatArray, val arc: FloatArray,
                       private val start: IntArray, private val end: IntArray) {
        val count: Int get() = a.size
        val edges: Int get() = start.size
        fun startOf(e: Int) = start[e]
        fun endOf(e: Int) = end[e]

        companion object {
            /** [poly] is CCW (positive area): the outward normal of edge d is (d.b, −d.a). */
            fun of(poly: FloatArray, creaseDeg: Float): Ring {
                val n = poly.size / 2
                val ena = FloatArray(n); val enb = FloatArray(n); val elen = FloatArray(n)
                for (i in 0 until n) {
                    val j = (i + 1) % n
                    val da = poly[2 * j] - poly[2 * i]; val db = poly[2 * j + 1] - poly[2 * i + 1]
                    val l = sqrt(da * da + db * db); require(l > 0f) { "repeated outline point" }
                    ena[i] = db / l; enb[i] = -da / l; elen[i] = l
                }
                val cosCrease = cos(Math.toRadians(creaseDeg.toDouble())).toFloat()
                val a = ArrayList<Float>(); val b = ArrayList<Float>(); val na = ArrayList<Float>(); val nb = ArrayList<Float>(); val arc = ArrayList<Float>()
                val inIdx = IntArray(n); val outIdx = IntArray(n)     // vertex used by the edge arriving at / leaving corner i
                var s = 0f
                for (i in 0 until n) {
                    val p = (i + n - 1) % n
                    val smooth = ena[p] * ena[i] + enb[p] * enb[i] >= cosCrease
                    fun add(x: Float, y: Float, u: Float): Int { a.add(poly[2 * i]); b.add(poly[2 * i + 1]); na.add(x); nb.add(y); arc.add(u); return a.size - 1 }
                    if (smooth) {
                        var x = ena[p] + ena[i]; var y = enb[p] + enb[i]; val l = sqrt(x * x + y * y); x /= l; y /= l
                        val k = add(x, y, s); inIdx[i] = k; outIdx[i] = k
                    } else {
                        inIdx[i] = add(ena[p], enb[p], s); outIdx[i] = add(ena[i], enb[i], s)
                    }
                    s += elen[i]
                }
                // The seam: corner 0 again at the full perimeter so u is continuous.
                val closeK = run {
                    val k0 = inIdx[0]
                    a.add(a[k0]); b.add(b[k0]); na.add(na[k0]); nb.add(nb[k0]); arc.add(s); a.size - 1
                }
                val start = IntArray(n) { outIdx[it] }
                val end = IntArray(n) { if (it == n - 1) closeK else inIdx[it + 1] }
                return Ring(a.toFloatArray(), b.toFloatArray(), na.toFloatArray(), nb.toFloatArray(), arc.toFloatArray(), start, end)
            }
        }
    }

    /**
     * The finished mesh, split into several BakedMeshes when it has more than 65,535 vertices
     * (indices are unsigned shorts). Chunks keep triangle order; names get "#1", "#2" … suffixes.
     */
    fun build(name: String, material: MaterialId, skin: SkinKind, levelMask: Int, viewMask: Int,
              clipped: Boolean, program: ProgramId, drawSlot: Int, texture: String? = null,
              fadeNearM: Float = 0f, fadeFarM: Float = 0f): List<BakedMesh> {
        val f = layout.floats
        fun mesh(n: String, verts: FloatArray, idx: ShortArray) = BakedMesh(name = n, layout = layout, vertices = verts,
            indices = idx, material = material, skin = skin, levelMask = levelMask, viewMask = viewMask, clipped = clipped,
            program = program, drawSlot = drawSlot, texture = texture, fadeNearM = fadeNearM, fadeFarM = fadeFarM)
        if (vn <= MAX_VERTS) {
            return listOf(mesh(name, v.copyOf(vn * f), ShortArray(ixn) { ix[it].toShort() }))
        }
        val out = ArrayList<BakedMesh>()
        val remap = IntArray(vn) { -1 }
        val used = ArrayList<Int>()
        val chunkIdx = ArrayList<Int>()
        fun flush() {
            if (chunkIdx.isEmpty()) return
            val verts = FloatArray(used.size * f)
            for ((j, src) in used.withIndex()) System.arraycopy(v, src * f, verts, j * f, f)
            out.add(mesh("$name#${out.size + 1}", verts, ShortArray(chunkIdx.size) { chunkIdx[it].toShort() }))
            for (src in used) remap[src] = -1
            used.clear(); chunkIdx.clear()
        }
        var t = 0
        while (t < ixn) {
            var fresh = 0
            for (k in 0..2) if (remap[ix[t + k]] < 0) fresh++
            if (used.size + fresh > MAX_VERTS) flush()
            for (k in 0..2) {
                val src = ix[t + k]
                if (remap[src] < 0) { remap[src] = used.size; used.add(src) }
                chunkIdx.add(remap[src])
            }
            t += 3
        }
        flush()
        return out
    }

    companion object {
        const val MAX_VERTS = 65_535

        /** Twice the signed area of a 2D polygon (a, b pairs); positive = CCW with a right, b up. */
        fun signedArea2(poly: FloatArray): Float {
            val n = poly.size / 2
            var s = 0f
            for (i in 0 until n) { val j = (i + 1) % n; s += poly[2 * i] * poly[2 * j + 1] - poly[2 * j] * poly[2 * i + 1] }
            return s
        }

        /** The polygon in CCW order (a copy, reversed if it was clockwise). */
        fun ccw(poly: FloatArray): FloatArray {
            require(poly.size >= 6 && poly.size % 2 == 0) { "polygon needs 3 points" }
            if (signedArea2(poly) > 0f) return poly.copyOf()
            val n = poly.size / 2
            return FloatArray(poly.size) { val k = n - 1 - it / 2; poly[2 * k + it % 2] }
        }

        /** Ear clipping of a simple CCW polygon: n − 2 triangles of corner indices, CCW. */
        fun earClip(poly: FloatArray): List<IntArray> {
            val n = poly.size / 2
            val rem = ArrayList<Int>((0 until n).toList())
            val out = ArrayList<IntArray>()
            fun x(i: Int) = poly[2 * i]
            fun y(i: Int) = poly[2 * i + 1]
            fun cross(o: Int, p: Int, q: Int) = (x(p) - x(o)) * (y(q) - y(o)) - (y(p) - y(o)) * (x(q) - x(o))
            var guard = 0
            while (rem.size > 3 && guard < n * n) {
                guard++
                var clipped = false
                for (k in rem.indices) {
                    val p = rem[(k + rem.size - 1) % rem.size]; val c = rem[k]; val q = rem[(k + 1) % rem.size]
                    if (cross(p, c, q) <= 0f) continue
                    var inside = false
                    for (o in rem) {
                        if (o == p || o == c || o == q) continue
                        if (cross(p, c, o) >= 0f && cross(c, q, o) >= 0f && cross(q, p, o) >= 0f) { inside = true; break }
                    }
                    if (inside) continue
                    out.add(intArrayOf(p, c, q)); rem.removeAt(k); clipped = true; break
                }
                if (!clipped) {             // degenerate input: fall back to a fan over what is left
                    for (k in 1 until rem.size - 1) out.add(intArrayOf(rem[0], rem[k], rem[k + 1]))
                    return out
                }
            }
            if (rem.size == 3) out.add(intArrayOf(rem[0], rem[1], rem[2]))
            return out
        }

        /**
         * Uniform Catmull-Rom through the points ([dim] floats each), [samples] points per span.
         * Open curves pass through every point, first and last included (end tangents mirror);
         * closed curves return samples × n points with the first not repeated.
         */
        fun catmullRom(pts: FloatArray, dim: Int, samples: Int, closed: Boolean): FloatArray {
            val n = pts.size / dim
            require(n >= 2 && samples >= 1)
            val spans = if (closed) n else n - 1
            val count = spans * samples + if (closed) 0 else 1
            val out = FloatArray(count * dim)
            fun at(i: Int, c: Int): Float = when {
                closed -> pts[((i % n + n) % n) * dim + c]
                i < 0 -> 2 * pts[c] - pts[dim + c]
                i >= n -> 2 * pts[(n - 1) * dim + c] - pts[(n - 2) * dim + c]
                else -> pts[i * dim + c]
            }
            var o = 0
            for (s in 0 until spans) for (k in 0 until samples) {
                val t = k.toFloat() / samples; val t2 = t * t; val t3 = t2 * t
                for (c in 0 until dim) {
                    val p0 = at(s - 1, c); val p1 = at(s, c); val p2 = at(s + 1, c); val p3 = at(s + 2, c)
                    out[o++] = 0.5f * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3)
                }
            }
            if (!closed) for (c in 0 until dim) out[o++] = pts[(n - 1) * dim + c]
            return out
        }
    }
}
