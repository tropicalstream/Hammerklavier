package com.tropicalstream.hammerklavier.mesh

import com.tropicalstream.hammerklavier.contract.BakedMesh
import com.tropicalstream.hammerklavier.contract.MaterialId
import com.tropicalstream.hammerklavier.contract.Pal
import com.tropicalstream.hammerklavier.contract.ProgramId
import com.tropicalstream.hammerklavier.contract.SkinKind
import com.tropicalstream.hammerklavier.contract.VertexLayout
import com.tropicalstream.hammerklavier.contract.stub.StubScenes
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentLook
import com.tropicalstream.hammerklavier.contract.UprightFinish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.tropicalstream.hammerklavier.testutil.MeshRaster
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

class MeshBuilderTest {
    /** Every triangle's winding normal agrees with its vertices' normals, which are unit length. */
    private fun checkWinding(m: BakedMesh) {
        val f = m.layout.floats
        fun p(i: Int, c: Int) = m.vertices[i * f + c]
        var t = 0
        while (t < m.indices.size) {
            val a = m.indices[t].toInt() and 0xFFFF; val b = m.indices[t + 1].toInt() and 0xFFFF; val c = m.indices[t + 2].toInt() and 0xFFFF
            assertTrue(a < m.vertexCount && b < m.vertexCount && c < m.vertexCount)
            val ux = p(b, 0) - p(a, 0); val uy = p(b, 1) - p(a, 1); val uz = p(b, 2) - p(a, 2)
            val vx = p(c, 0) - p(a, 0); val vy = p(c, 1) - p(a, 1); val vz = p(c, 2) - p(a, 2)
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            for (i in intArrayOf(a, b, c)) {
                val l = sqrt(p(i, 3) * p(i, 3) + p(i, 4) * p(i, 4) + p(i, 5) * p(i, 5))
                assertEquals(1f, l, 1e-5f)
                assertTrue("winding against the normal", nx * p(i, 3) + ny * p(i, 4) + nz * p(i, 5) > 0f)
            }
            t += 3
        }
    }

    @Test fun boxIsClosedOutwardAndUnitNormal() {
        val b = MeshBuilder(VertexLayout.STATIC)
        b.color(Pal.IVORY)
        b.box(-0.5f, 0f, -1f, 0.5f, 0.2f, 0f)
        val m = b.build("box", MaterialId.WOOD_CASE, SkinKind.STATIC, 1, 1, false, ProgramId.LIT, 7).single()
        assertEquals(24, m.vertexCount); assertEquals(12, m.triangleCount)
        checkWinding(m)
        // Outward: each face normal points away from the centre.
        for (i in 0 until m.vertexCount) {
            val o = i * 12
            val dx = m.vertices[o] - 0f; val dy = m.vertices[o + 1] - 0.1f; val dz = m.vertices[o + 2] + 0.5f
            assertTrue(dx * m.vertices[o + 3] + dy * m.vertices[o + 4] + dz * m.vertices[o + 5] > 0f)
            assertEquals(Pal.IVORY[0] / 255f, m.vertices[o + 8], 1e-6f)
            // UVs in metres, within the face size.
            assertTrue(m.vertices[o + 6] in -1e-6f..1.0001f && m.vertices[o + 7] in -1e-6f..1.0001f)
        }
    }

    @Test fun skinnedLanesAreOneHot() {
        val b = MeshBuilder(VertexLayout.SKINNED)
        for (part in 0 until 10) { b.part(part / 4, part % 4); b.box(part.toFloat(), 0f, 0f, part + 0.5f, 1f, 1f) }
        val m = b.build("k", MaterialId.IVORY, SkinKind.KEY_ROT, 1, 1, false, ProgramId.SKINNED, 10).single()
        checkWinding(m)
        for (i in 0 until m.vertexCount) {
            val o = i * 17
            val slot = m.vertices[o + 12]; val lanes = (0..3).map { m.vertices[o + 13 + it] }
            assertEquals(1f, lanes.sum(), 0f); assertTrue(lanes.all { it == 0f || it == 1f })
            val part = m.vertices[o].toInt()
            assertEquals((part / 4).toFloat(), slot, 0f); assertEquals(1f, lanes[part % 4], 0f)
        }
    }

    @Test fun splitAbove65535Vertices() {
        val b = MeshBuilder(VertexLayout.STATIC, 70_000)
        repeat(3000) { i -> b.box(i.toFloat(), 0f, 0f, i + 0.5f, 1f, 1f) }      // 72,000 vertices
        val parts = b.build("big", MaterialId.WOOD_CASE, SkinKind.STATIC, 1, 1, false, ProgramId.LIT, 7)
        assertTrue(parts.size >= 2)
        assertEquals(36_000, parts.sumOf { it.triangleCount })
        for (m in parts) {
            assertTrue(m.vertexCount <= 65_535)
            assertTrue(m.indices.any { (it.toInt() and 0xFFFF) > 32_767 } || m.vertexCount <= 32_768)
            checkWinding(m)
        }
    }

    @Test fun stubKeyboardFollowsConventions() {
        val scene = StubScenes().instrument(InstrumentId.GRAND, InstrumentLook(UprightFinish.WALNUT, false), 88)
        val meshes = scene.meshes()
        assertEquals(88 * 24, meshes.sumOf { it.vertexCount })
        for (m in meshes) { assertEquals(ProgramId.SKINNED, m.program); assertEquals(10, m.drawSlot); checkWinding(m) }
        val x = scene.anchors.keyX
        for (k in 22..108) assertTrue(x[k] > x[k - 1])
        assertTrue(x[20].isNaN())
        assertEquals(0.01371f * 87, x[108] - x[21], 1e-4f)
        assertTrue(abs(x[64] + x[65]) < 0.02f)
    }

    /** Signed volume by the divergence theorem: positive and equal to the solid's volume when closed and outward. */
    private fun volume(m: BakedMesh): Float {
        val f = m.layout.floats
        var s = 0.0
        var t = 0
        while (t < m.indices.size) {
            val a = (m.indices[t].toInt() and 0xFFFF) * f; val b = (m.indices[t + 1].toInt() and 0xFFFF) * f; val c = (m.indices[t + 2].toInt() and 0xFFFF) * f
            val v = m.vertices
            s += (v[a] * (v[b + 1] * v[c + 2] - v[b + 2] * v[c + 1]) - v[a + 1] * (v[b] * v[c + 2] - v[b + 2] * v[c]) + v[a + 2] * (v[b] * v[c + 1] - v[b + 1] * v[c])).toDouble()
            t += 3
        }
        return (s / 6.0).toFloat()
    }

    private fun one(b: MeshBuilder, skin: SkinKind = SkinKind.STATIC, program: ProgramId = ProgramId.LIT) =
        b.build("t", MaterialId.WOOD_CASE, skin, 1, 1, false, program, 7).single()

    private fun noNaN(m: BakedMesh) = assertTrue(m.vertices.none { it.isNaN() })

    // An L-shaped (concave) outline, 0.64 m² area.
    private val lShape = floatArrayOf(0f, 0f, 1f, 0f, 1f, 0.4f, 0.4f, 0.4f, 0.4f, 1f, 0f, 1f)

    @Test fun extrudeConcaveEitherOrientation() {
        for (outline in listOf(lShape, MeshBuilder.ccw(lShape).let { c -> FloatArray(c.size) { c[(c.size / 2 - 1 - it / 2) * 2 + it % 2] } })) {
            val b = MeshBuilder(VertexLayout.STATIC); b.color(Pal.WALNUT)
            b.extrude(outline, 0.66f, 0.96f, capTop = true, capBottom = true)
            val m = one(b); checkWinding(m); noNaN(m)
            assertEquals(0.64f * 0.3f, volume(m), 1e-4f)
            // UV rules: caps (x, z), sides v = y and u = arc length within the 4.0 m perimeter.
            for (i in 0 until m.vertexCount) {
                val o = i * 12; val ny = m.vertices[o + 4]
                if (abs(ny) > 0.5f) { assertEquals(m.vertices[o], m.vertices[o + 6], 1e-6f); assertEquals(m.vertices[o + 2], m.vertices[o + 7], 1e-6f) }
                else { assertEquals(m.vertices[o + 1], m.vertices[o + 7], 1e-6f); assertTrue(m.vertices[o + 6] in -1e-6f..4.0001f) }
            }
        }
    }

    @Test fun extrudeSmoothRimFromCatmullRom() {
        val plan = floatArrayOf(0f, 0f, 1f, 0f, 1f, .16f, .93f, .30f, .80f, .45f, .70f, .58f, .66f, .70f, .62f, .82f, .52f, .94f, .35f, 1f, .12f, .99f, 0f, .93f)
        val pts = MeshBuilder.catmullRom(plan, 2, 6, closed = true)
        for (i in pts.indices step 2) { pts[i] *= 1.49f; pts[i + 1] *= 2.00f }
        // The spline passes through the control points.
        assertEquals(1.49f, pts[6 * 2], 1e-5f); assertEquals(0f, pts[6 * 2 + 1], 1e-5f)
        val b = MeshBuilder(VertexLayout.STATIC); b.extrude(pts, 0.66f, 0.96f, true, false)
        val m = one(b); checkWinding(m); noNaN(m)
        val open = floatArrayOf(0f, 0f, 1f, 2f, 3f, 1f)
        val c = MeshBuilder.catmullRom(open, 2, 4, closed = false)
        assertEquals(9, c.size / 2); assertEquals(3f, c[16], 0f); assertEquals(1f, c[4 * 2], 1e-6f); assertEquals(2f, c[4 * 2 + 1], 1e-6f)
    }

    @Test fun latheClosedWithPoles() {
        // A cylinder r = 0.1, h = 0.5 with flat ends through the axis (poles).
        val prof = floatArrayOf(0f, 0f, 0.1f, 0f, 0.1f, 0.5f, 0f, 0.5f)
        val b = MeshBuilder(VertexLayout.STATIC); b.lathe(prof, 64, 1f, -2f)
        val m = one(b); checkWinding(m); noNaN(m)
        val expect = (64 * 0.5 * Math.sin(2 * Math.PI / 64) * 0.01 * 0.5).toFloat()
        assertEquals(expect, volume(m), 1e-5f)
        for (i in 0 until m.vertexCount) {
            val o = i * 12
            assertTrue(m.vertices[o + 6] in 0f..1f)
            assertTrue(m.vertices[o + 7] in 0f..0.70001f)                   // profile arc length 0.1 + 0.5 + 0.1
        }
    }

    @Test fun sweepOpenAndClosed() {
        val square = floatArrayOf(-0.01f, -0.01f, -0.01f, 0.01f, 0.01f, 0.01f, 0.01f, -0.01f)    // clockwise on purpose
        val path = floatArrayOf(0f, 0f, 0f, 0.3f, 0.1f, 0f, 0.6f, 0.1f, 0.3f, 0.6f, 0.5f, 0.6f)
        val b = MeshBuilder(VertexLayout.STATIC); b.sweep(path, square, closed = false)
        val m = one(b); checkWinding(m); noNaN(m)
        assertTrue(volume(m) > 0f)
        // Straight tube: exact volume and uv.
        val b2 = MeshBuilder(VertexLayout.STATIC); b2.sweep(floatArrayOf(0f, 0f, 0f, 0f, 0f, -1f), square, false, samplesPerSegment = 1)
        val m2 = one(b2); checkWinding(m2)
        assertEquals(0.0004f, volume(m2), 1e-7f)
        for (i in 0 until m2.vertexCount) {
            val o = i * 12
            if (abs(m2.vertices[o + 5]) < 0.5f) {            // side vertex: u = path arc length, v = profile arc length
                assertEquals(-m2.vertices[o + 2], m2.vertices[o + 6], 1e-6f)
                assertTrue(m2.vertices[o + 7] in 0f..0.08001f)
            }
        }
        // A closed ring (torus-like): twist spread so the seam closes; it is watertight.
        val circle = FloatArray(16 * 3); for (i in 0 until 16) { val a = 2 * Math.PI * i / 16; circle[i * 3] = Math.cos(a).toFloat(); circle[i * 3 + 1] = (0.3 * Math.sin(3 * a)).toFloat(); circle[i * 3 + 2] = Math.sin(a).toFloat() }
        val disc = FloatArray(12 * 2); for (i in 0 until 12) { val a = 2 * Math.PI * i / 12; disc[i * 2] = 0.05f * Math.cos(a).toFloat(); disc[i * 2 + 1] = 0.05f * Math.sin(a).toFloat() }
        val b3 = MeshBuilder(VertexLayout.STATIC); b3.sweep(circle, disc, closed = true, samplesPerSegment = 4)
        val m3 = one(b3); checkWinding(m3); noNaN(m3)
        val approx = (Math.PI * 0.05 * 0.05 * 2 * Math.PI).toFloat()          // ≥ the flat torus; the wave lengthens it
        assertTrue(volume(m3) > approx * 0.95f && volume(m3) < approx * 1.4f)
        // Seam: first and last rings coincide.
        val f = 12; val rk = m3.vertexCount / (16 * 4 + 1)
        for (k in 0 until rk) for (c in 0..2) assertEquals(m3.vertices[k * f + c], m3.vertices[((16 * 4) * rk + k) * f + c], 1e-4f)
    }

    @Test fun ribbonEncoding() {
        val b = MeshBuilder(VertexLayout.STATIC); b.color(Pal.PLATE_GOLD)
        val path = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 2f)
        b.ribbon(path, 0.004f)
        val m = one(b, program = ProgramId.RIBBON)
        assertEquals(6, m.vertexCount); assertEquals(4, m.triangleCount)
        for (i in 0 until 6) {
            val o = i * 12
            assertEquals(path[(i / 2) * 3], m.vertices[o], 0f); assertEquals(path[(i / 2) * 3 + 2], m.vertices[o + 2], 0f)
            val l = sqrt(m.vertices[o + 3] * m.vertices[o + 3] + m.vertices[o + 4] * m.vertices[o + 4] + m.vertices[o + 5] * m.vertices[o + 5])
            assertEquals(1f, l, 1e-6f)
            assertEquals(if (i % 2 == 0) -1f else 1f, m.vertices[o + 6], 0f); assertEquals(0.002f, m.vertices[o + 7], 1e-7f)
        }
        assertEquals(1f, m.vertices[3], 1e-6f)                              // first tangent along +x
        assertEquals(1f, m.vertices[5 * 12 + 5], 1e-6f)                     // last along +z
        val c = MeshBuilder(VertexLayout.STATIC); c.ribbon(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f), 0.01f, closed = true)
        assertEquals(6, one(c).triangleCount)
    }

    @Test fun spindleStringEncoding() {
        val b = MeshBuilder(VertexLayout.STRING); b.part(5, 2)
        b.spindle(floatArrayOf(0f, 0.7f, 0f), floatArrayOf(0f, 0.7f, 1.5f), 4, Pal.STEEL_HI)
        val m = one(b, SkinKind.STRING, ProgramId.STRING)
        assertEquals(10, m.vertexCount); assertEquals(8, m.triangleCount)
        for (i in 0 until 10) {
            val o = i * 16
            assertEquals((i / 2) / 4f, m.vertices[o + 6], 1e-6f)
            assertEquals(if (i % 2 == 0) -1f else 1f, m.vertices[o + 7], 0f)
            assertEquals(1f, m.vertices[o + 5], 1e-6f)
            assertEquals(1.5f * (i / 2) / 4f, m.vertices[o + 2], 1e-6f)
            assertEquals(5f, m.vertices[o + 8], 0f)
            assertEquals(listOf(0f, 0f, 1f, 0f), (0..3).map { m.vertices[o + 9 + it] })
            assertEquals(Pal.STEEL_HI[0] / 255f, m.vertices[o + 13], 1e-6f)
        }
    }

    @Test fun rawVertexTriAndUnsignedIndices() {
        val b = MeshBuilder(VertexLayout.STATIC, 40_000)
        repeat(1500) { i -> b.box(i.toFloat(), 0f, 0f, i + 0.5f, 1f, 1f) }      // 36,000 vertices: one mesh, indices > 32,767
        val n0 = floatArrayOf(0f, 0f, 1f)
        val a = b.vertex(floatArrayOf(0f, 0f, 5f), n0, floatArrayOf(0f, 0f))
        val c = b.vertex(floatArrayOf(1f, 0f, 5f), n0, floatArrayOf(1f, 0f))
        val d = b.vertex(floatArrayOf(0f, 1f, 5f), n0, floatArrayOf(0f, 1f))
        assertEquals(36_000, a)
        b.tri(a, c, d)
        val m = one(b)
        assertTrue(m.indices.any { it < 0 })                                // stored signed, read as unsigned
        checkWinding(m)
        assertEquals(36_002, m.indices.last().toInt() and 0xFFFF)
    }

    @Test fun skinnedPrimitivesCarrySlotAndLane() {
        val b = MeshBuilder(VertexLayout.SKINNED)
        b.part(33, 3); b.lathe(floatArrayOf(0f, 0f, 0.02f, 0f, 0.02f, 0.05f, 0f, 0.05f), 12, 0f, 0f)
        b.part(7, 0); b.extrude(lShape, 0f, 0.1f, true, true)
        b.part(2, 1); b.sweep(floatArrayOf(0f, 0f, 0f, 0f, 0.2f, 0f), floatArrayOf(0f, 0f, 0.01f, 0f, 0f, 0.01f), false)
        val m = one(b, SkinKind.HAMMER_ROT, ProgramId.SKINNED); checkWinding(m); noNaN(m)
        for (i in 0 until m.vertexCount) {
            val o = i * 17; val lanes = (0..3).map { m.vertices[o + 13 + it] }
            assertEquals(1f, lanes.sum(), 0f); assertTrue(m.vertices[o + 12] < 34f)
            assertEquals(1f, lanes[when (m.vertices[o + 12].toInt()) { 33 -> 3; 7 -> 0; else -> 1 }], 0f)
        }
    }

    @Test fun rasterReviewsGeometry() {
        val b = MeshBuilder(VertexLayout.STATIC); b.color(Pal.IVORY)
        b.box(-0.2f, 0f, -0.2f, 0.2f, 0.4f, 0.2f)
        b.color(Pal.PLATE_GOLD); b.lathe(floatArrayOf(0f, 0.4f, 0.1f, 0.4f, 0.05f, 0.6f, 0f, 0.6f), 24, 0f, 0f)
        val m = one(b)
        val view = MeshRaster.View(floatArrayOf(0.8f, 0.8f, 1.2f), floatArrayOf(0f, 0.25f, 0f))
        val img = MeshRaster.render(listOf(m), view, 320, 240)
        assertTrue(MeshRaster.coverage(img) > 0.05f)
        // Back faces are culled: a quad facing the camera draws, the same quad reversed does not.
        val q = MeshBuilder(VertexLayout.STATIC); q.color(Pal.IVORY)
        q.quad(floatArrayOf(-0.3f, 0f, 0f), floatArrayOf(0.3f, 0f, 0f), floatArrayOf(0.3f, 0.5f, 0f), floatArrayOf(-0.3f, 0.5f, 0f))
        val front = one(q)
        val back = BakedMesh("back", front.layout, front.vertices, ShortArray(front.indices.size) { front.indices[it - it % 3 + (2 - it % 3)] },
            front.material, front.skin, 1, 1, false, front.program, 7)
        val v2 = MeshRaster.View(floatArrayOf(0f, 0.25f, 1.5f), floatArrayOf(0f, 0.25f, 0f))
        assertTrue(MeshRaster.coverage(MeshRaster.render(listOf(front), v2, 320, 240)) > 0.05f)
        assertEquals(0f, MeshRaster.coverage(MeshRaster.render(listOf(back), v2, 320, 240)), 0f)
        MeshRaster.writePng(listOf(m), view, File("build/shots/meshbuilder_review.png"), 320, 240)
    }
}
