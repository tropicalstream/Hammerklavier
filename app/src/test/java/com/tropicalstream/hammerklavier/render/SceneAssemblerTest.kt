package com.tropicalstream.hammerklavier.render

import com.tropicalstream.hammerklavier.contract.BakedMesh
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentLook
import com.tropicalstream.hammerklavier.contract.MaterialId
import com.tropicalstream.hammerklavier.contract.Palette
import com.tropicalstream.hammerklavier.contract.ProgramId
import com.tropicalstream.hammerklavier.contract.RoomLevel
import com.tropicalstream.hammerklavier.contract.SkinKind
import com.tropicalstream.hammerklavier.contract.UprightFinish
import com.tropicalstream.hammerklavier.contract.VertexLayout
import com.tropicalstream.hammerklavier.contract.ViewId
import com.tropicalstream.hammerklavier.contract.stub.StubScenes
import com.tropicalstream.hammerklavier.mesh.MeshBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T6.3 (PLAN §7.2 WP6): merge by the §2.3 key; every framing's draw list from StubScenes within 28 draws per eye, in drawSlot order. */
class SceneAssemblerTest {
    private fun box(name: String, x: Float, slot: Int = 7, mat: MaterialId = MaterialId.WOOD_CASE, view: Int = 0b111111,
                    level: Int = 0b1111, program: ProgramId = ProgramId.LIT, verts: Int = 0): List<BakedMesh> {
        val b = MeshBuilder(VertexLayout.STATIC, 64)
        b.color(intArrayOf(200, 100, 50))
        if (verts == 0) b.box(x, 0f, 0f, x + 0.1f, 0.1f, 0.1f)
        else { var i = 0; while (b.vertexCount + 24 <= verts) { b.box(x + i * 0.01f, 0f, 0f, x + i * 0.01f + 0.005f, 0.1f, 0.1f); i++ } }
        return b.build(name, mat, SkinKind.STATIC, level, view, false, program, slot)
    }

    @Test fun mergesEqualKeysOnly() {
        val a = box("a", 0f); val b = box("b", 1f)
        val c = box("c", 2f, slot = 9, mat = MaterialId.PLATE)
        val d = box("d", 3f, view = 0b000011)
        val s = SceneAssembler.assemble(a + b + c + d, emptyList())
        assertEquals(3, s.items.size)
        val merged = s.items.first { it.names.size == 2 }
        assertEquals(listOf("a", "b"), merged.names)
        assertEquals(48, merged.vertexCount); assertEquals(24, merged.triangleCount)
        // the second mesh's indices are offset past the first's vertices
        val maxIdx = merged.indices.maxOf { it.toInt() and 0xFFFF }
        assertEquals(47, maxIdx)
        val minB = merged.indices.copyOfRange(36, 72).minOf { it.toInt() and 0xFFFF }
        assertEquals(24, minB)
        // vertex data is copied verbatim: b's vertices (24..47) span x = 1 .. 1.1
        val bx = (24 until 48).map { merged.vertices[it * 12] }
        assertEquals(1f, bx.min(), 1e-6f); assertEquals(1.1f, bx.max(), 1e-6f)
        // instrument and venue never merge (different model matrix)
        val s2 = SceneAssembler.assemble(box("i", 0f), box("v", 0f))
        assertEquals(2, s2.items.size)
    }

    @Test fun splitsAbove65535Vertices() {
        val big = (0 until 4).flatMap { box("big$it", it.toFloat(), verts = 30_000) }
        val s = SceneAssembler.assemble(big, emptyList())
        assertTrue(s.items.size >= 2)
        for (it in s.items) assertTrue(it.vertexCount <= 65_535)
        assertEquals(big.sumOf { it.vertexCount }, s.items.sumOf { it.vertexCount })
    }

    @Test fun listsAreFilteredAndSortedByDrawSlot() {
        val meshes = box("s14", 0f, slot = 14, mat = MaterialId.PLATE) + box("s7", 1f, slot = 7) +
            box("s10player", 2f, slot = 10, view = 0b000001, mat = MaterialId.IVORY) + box("s1salon", 3f, slot = 1, level = 0b0001, mat = MaterialId.PARQUET_POOL)
        val s = SceneAssembler.assemble(meshes, emptyList())
        val player = s.list(ViewId.PLAYER, 0, RoomLevel.SALON).map { s.items[it].key.drawSlot }
        assertEquals(listOf(1, 7, 10, 14), player)
        val action = s.list(ViewId.ACTION, 0, RoomLevel.STAGE).map { s.items[it].key.drawSlot }
        assertEquals(listOf(7, 14), action)
    }

    @Test fun stubScenesEveryFramingWithinBudget() {
        val f = StubScenes()
        for (id in InstrumentId.entries) {
            val inst = f.instrument(id, InstrumentLook(UprightFinish.WALNUT, false), 88)
            val venue = f.venue()
            val s = SceneAssembler.assemble(inst.meshes(), venue.meshes(Palette.SANSSOUCI_1747))
            assertTrue(s.items.isNotEmpty())
            for (v in ViewId.entries) for (fr in 0..1) for (lv in RoomLevel.entries) {
                val vc = v.ordinal * 2 + fr
                val list = s.list(vc, lv.ordinal)
                val slots = list.map { s.items[it].key.drawSlot }
                assertEquals("drawSlot order", slots.sorted(), slots)
                val draws = list.size + SceneAssembler.overheadDraws(vc, lv.ordinal, 3, true)
                assertTrue("$id $v/$fr $lv: $draws draws", draws <= 28)
                // every item in the list is meant for this framing and level
                for (i in list) assertTrue(s.items[i].shownIn(vc, lv.ordinal))
                assertTrue(s.triangles(vc, lv.ordinal) <= 45_000)
            }
            // the skinned keyboard is one draw per colour (merged by key), present in every framing
            val keys = s.items.filter { it.key.skin == SkinKind.KEY_ROT }
            assertEquals(2, keys.size)
            assertTrue(s.list(ViewId.PLAYER, 0, RoomLevel.STAGE).any { s.items[it].key.skin == SkinKind.KEY_ROT })
            // the floor pool (venue, drawSlot 1) at Salon
            assertTrue(s.list(ViewId.HALL, 0, RoomLevel.SALON).any { !s.items[it].key.instrument && s.items[it].key.drawSlot == 1 })
        }
    }

    /**
     * T7.8/T8.8 pair: the same budget over the real instruments and venue. Uses WP7's `instrument.Instruments` and WP8's
     * `venue.VenueSceneImpl` when they are on the classpath (found by name so this file compiles before they merge),
     * otherwise the contract stubs, so the check always runs.
     */
    @Test fun realScenesWithinBudget() {
        val f = RealScenes.factory()
        for (id in InstrumentId.entries) for (lastDamper in intArrayOf(88, 66)) {
            val inst = f.instrument(id, InstrumentLook(UprightFinish.WALNUT, false), lastDamper)
            for (pal in Palette.entries) {
                val s = SceneAssembler.assemble(inst.meshes(), f.venue().meshes(pal))
                assertTrue(s.items.isNotEmpty())
                for (it in s.items) assertTrue(it.vertexCount <= 65_535)
                for (v in ViewId.entries) for (fr in 0..1) for (lv in RoomLevel.entries) {
                    val vc = v.ordinal * 2 + fr
                    val list = s.list(vc, lv.ordinal)
                    val slots = list.map { s.items[it].key.drawSlot }
                    assertEquals("drawSlot order", slots.sorted(), slots)
                    val draws = list.size + SceneAssembler.overheadDraws(vc, lv.ordinal, 3, true)
                    assertTrue("${RealScenes.label} $id $pal $v/$fr $lv: $draws draws", draws <= 28)
                    assertTrue(s.triangles(vc, lv.ordinal) <= 45_000)
                }
            }
        }
    }
}
