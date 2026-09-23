package com.tropicalstream.hammerklavier.mesh

import com.tropicalstream.hammerklavier.contract.BakedMesh
import com.tropicalstream.hammerklavier.contract.MaterialId
import com.tropicalstream.hammerklavier.contract.ProgramId
import com.tropicalstream.hammerklavier.contract.SkinKind
import com.tropicalstream.hammerklavier.contract.VertexLayout
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
 * Runs on HKLoader; allocates freely.
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
    }
}
