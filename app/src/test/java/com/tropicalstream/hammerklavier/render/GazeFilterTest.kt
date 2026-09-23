package com.tropicalstream.hammerklavier.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** T6.5 (PLAN §7.2 WP6): world-locked mode has no re-centre drift over 5 min and clamps ±60° / ±45°. */
class GazeFilterTest {
    private val deg = (Math.PI / 180.0).toFloat()

    private fun run(f: GazeFilter, seconds: Int, heading: (Float) -> Float, elevation: (Float) -> Float) {
        val hz = 50
        for (i in 0 until seconds * hz) {
            val t = i.toFloat() / hz
            f.feed(heading(t), elevation(t), (i * 1e9 / hz).toLong() + 1)
        }
    }

    @Test fun worldLockedNoDriftOverFiveMinutes() {
        val f = GazeFilter(); f.worldLocked = true
        // look straight ahead, then 30° right and 10° up and hold for 5 minutes, with sensor noise
        val rnd = java.util.Random(3)
        run(f, 1, { 0.2f }, { 0f })
        run(f, 300, { 0.2f + 30f * deg + (rnd.nextFloat() - 0.5f) * 0.002f }, { 10f * deg + (rnd.nextFloat() - 0.5f) * 0.002f })
        assertEquals(30f * deg, f.yaw, 0.2f * deg)
        assertEquals(10f * deg, f.pitch, 0.2f * deg)
        // and back to the start: exactly centred (the reference never moved)
        run(f, 2, { 0.2f }, { 0f })
        assertEquals(0f, f.yaw, 0.01f * deg); assertEquals(0f, f.pitch, 0.01f * deg)
    }

    @Test fun softRecentreStaysOutsideTheHall() {
        val f = GazeFilter()
        run(f, 1, { 0f }, { 0f })
        run(f, 300, { 30f * deg }, { 0f })
        assertTrue("drifts back toward centre: ${f.yaw / deg}°", f.yaw < 5f * deg)
    }

    @Test fun worldLockedClamps() {
        val f = GazeFilter(); f.worldLocked = true
        run(f, 1, { 0f }, { 0f })
        run(f, 5, { 100f * deg }, { 70f * deg })
        assertEquals(60f * deg, f.yaw, 1e-4f); assertEquals(45f * deg, f.pitch, 1e-4f)
        run(f, 5, { -100f * deg }, { -70f * deg })
        assertEquals(-60f * deg, f.yaw, 1e-4f); assertEquals(-45f * deg, f.pitch, 1e-4f)
    }

    @Test fun yawSignAndOmega() {
        val f = GazeFilter(); f.worldLocked = true
        // turning right at 1 rad/s (heading increases): yaw positive, ω ≈ +1
        run(f, 1, { t -> 0.5f * t }, { 0f })
        run(f, 1, { t -> 0.5f + 1f * t }, { 0f })
        assertTrue(f.yaw > 0f)
        assertEquals(1f, f.omega, 0.05f)
        // wrap-around across ±π stays continuous
        val g = GazeFilter(); g.worldLocked = true
        run(g, 1, { 3.1f }, { 0f })
        run(g, 1, { -3.1f }, { 0f })
        assertTrue(abs(g.yaw - (2 * Math.PI.toFloat() - 6.2f)) < 1e-3f)
    }
}
