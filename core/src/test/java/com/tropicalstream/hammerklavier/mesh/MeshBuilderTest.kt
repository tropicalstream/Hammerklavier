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
            assertEquals(232 / 255f, m.vertices[o + 8], 1e-6f)
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
}
