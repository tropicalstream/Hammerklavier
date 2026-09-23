package com.tropicalstream.hammerklavier.contract

import com.tropicalstream.hammerklavier.system.ThermalPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class SmallPrimitivesTest {
    @Test fun playlist() {
        val p = Playlist()
        assertNull(p.current); assertNull(p.next()); assertNull(p.previous(0f)); assertEquals(0, p.size)
        p.set(listOf("a", "b", "c"), 1)
        assertEquals("b", p.current); assertEquals(1, p.index); assertEquals("c", p.peekNext())
        assertEquals("b", p.previous(5f))                 // > 3 s in: restart
        assertEquals("a", p.previous(1f)); assertEquals("a", p.previous(1f))   // first item restarts
        assertEquals("b", p.next()); assertEquals("c", p.next()); assertNull(p.next()); assertEquals("c", p.current)
        assertNull(p.peekNext())
        p.set(listOf("x"), 9); assertEquals("x", p.current)
    }

    @Test fun qualityLadder() {
        val q0 = QualityLadder.of(0, 96); val q1 = QualityLadder.of(1, 96); val q2 = QualityLadder.of(2, 96); val q3 = QualityLadder.of(3, 96)
        assertEquals(96, q0.voiceCap); assertEquals(72, q1.voiceCap); assertEquals(60, q2.voiceCap); assertEquals(48, q3.voiceCap)
        assertEquals(48, QualityLadder.of(1, 64).voiceCap); assertEquals(40, QualityLadder.of(2, 64).voiceCap); assertEquals(32, QualityLadder.of(3, 64).voiceCap)
        assertEquals(2, q0.frameDivider); assertEquals(2, q1.frameDivider); assertEquals(3, q2.frameDivider); assertEquals(0, q3.frameDivider)
        assertEquals(RoomLevel.SALON, q1.roomCap); assertEquals(RoomLevel.STAGE, q2.roomCap)
        assertTrue(q0.mirrorFlames); assertFalse(q1.mirrorFlames)
        assertEquals(160, q0.crystals); assertEquals(60, q1.crystals); assertEquals(0, q2.crystals)
        assertEquals(-1f, q0.brightnessCap, 0f); assertEquals(0.6f, q2.brightnessCap, 0f)
        assertTrue(q1.hermite); assertFalse(q2.hermite); assertTrue(q1.spectralDamping); assertFalse(q2.spectralDamping)
        assertEquals(88, q1.combs); assertEquals(44, q2.combs); assertEquals(0, q3.combs)
        assertTrue(q1.dispersion); assertFalse(q2.dispersion)
        assertEquals(8, q2.fdnLines); assertEquals(4, q3.fdnLines)
        assertTrue(q0.voicingAllowed); assertFalse(q1.voicingAllowed)
        assertEquals(3, QualityLadder.MAX)
    }

    @Test fun thermalPolicySequence() {
        val t = ThermalPolicy()
        val temps = intArrayOf(370, 391, 422, 441, 430, 424, 410, 404)
        val want = intArrayOf(0, 1, 2, 3, 3, 2, 2, 1)
        for (i in temps.indices) assertEquals("at ${temps[i]}", want[i], t.update(temps[i]))
        assertEquals(1, t.update(380)); assertEquals(0, t.update(374))
        assertEquals(2, ThermalPolicy().update(300, ThermalPolicy.STATUS_SEVERE))
    }

    @Test fun conventionsGrandPlayer() {
        val pl = KonzertzimmerAcoustics.PLACEMENTS.getValue(InstrumentId.GRAND)
        val ear = floatArrayOf(0f, 1.20f, 0.55f); val src = floatArrayOf(0f, 0.90f, -1.00f)
        val fwd = Conventions.forwardYaw(pl, ear, src)
        assertEquals(PI.toFloat() / 2, fwd, 1e-4f)          // the grand's keyboard faces east
        val e = FloatArray(3); val s = FloatArray(3); pl.toRoom(ear, e); pl.toRoom(src, s)
        val az = Conventions.yawOf(floatArrayOf(s[0] - e[0], s[1] - e[1], s[2] - e[2])) - fwd
        assertEquals(0f, az, 1e-5f)                         // the source straight ahead
        val treble = FloatArray(3); pl.toRoom(floatArrayOf(0.5f, 1.2f, 0.55f), treble)
        val tAz = Conventions.yawOf(floatArrayOf(treble[0] - e[0], 0f, treble[2] - e[2])) - fwd
        assertTrue("treble to the listener's right", tAz > 0f && tAz < PI.toFloat())
        assertEquals(0f, Conventions.yawOf(floatArrayOf(0f, 0f, -1f)), 1e-6f)        // north
        assertEquals(PI.toFloat() / 2, Conventions.yawOf(floatArrayOf(1f, 0f, 0f)), 1e-6f)   // east
        val out = FloatArray(3); pl.toRoom(floatArrayOf(0f, 0f, 0f), out)
        assertArrayEquals(floatArrayOf(-1.0f, 0f, -1.9f), out, 1e-6f)
    }

    @Test fun konzertzimmer() {
        val g = KonzertzimmerAcoustics.GEOMETRY
        assertEquals(84.0f, g.surfaces.filter { it.plane == 0 }.sumOf { it.areaM2.toDouble() }.toFloat(), 0.01f)
        assertEquals(170.2f, g.surfaces.filter { it.plane >= 2 }.sumOf { it.areaM2.toDouble() }.toFloat(), 0.5f)
        assertEquals(469.7f, g.volumeM3, 0.01f)
        val t500 = 0.161 * g.volumeM3 / (g.surfaces.sumOf { it.areaM2.toDouble() * it.material.alpha[2] } +
            g.people * g.personSabins[2] + 4 * g.airM[2] * g.volumeM3)
        assertTrue("T60(500 Hz) = $t500", t500 in 1.5..1.9)
        assertEquals(3, g.placements.size)
        for (s in g.surfaces) assertEquals(7, s.material.alpha.size)
    }

    @Test fun headPoseRoundTrip() {
        val h = HeadPose()
        assertEquals(0f, h.yaw(), 0f); assertEquals(0f, h.omega(), 0f)
        h.write(-1.25f, 3.5f); assertEquals(-1.25f, h.yaw(), 0f); assertEquals(3.5f, h.omega(), 0f)
        h.write(Float.MIN_VALUE, -0f); assertEquals(Float.MIN_VALUE, h.yaw(), 0f); assertTrue(abs(h.omega()) == 0f)
    }

    @Test fun palAndMaterials() {
        assertArrayEquals(intArrayOf(255, 244, 214), Pal.FLAME_CORE)
        assertArrayEquals(intArrayOf(240, 190, 100), Pal.HUD_ACCENT)
        for (m in MaterialId.entries) MaterialTable.of(m)
        assertEquals(ProgramId.LACQUER, MaterialTable.of(MaterialId.LACQUER).program)
        assertEquals(ProgramId.STRING, MaterialTable.of(MaterialId.STEEL).program)
        assertEquals(ProgramId.RIBBON, MaterialTable.of(MaterialId.GILT).program)
        assertEquals(ProgramId.DECAL, MaterialTable.of(MaterialId.GILT_EMISSIVE).program)
        assertEquals(ProgramId.SECTION_CAP, MaterialTable.of(MaterialId.SECTION_CAP).program)
        assertEquals(ProgramId.LIT, MaterialTable.of(MaterialId.FELT).program)
    }
}
