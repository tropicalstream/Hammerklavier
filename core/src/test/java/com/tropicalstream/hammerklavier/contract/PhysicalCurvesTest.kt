package com.tropicalstream.hammerklavier.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalCurvesTest {
    @Test fun pedalDampingEndsAndMonotone() {
        assertEquals(1f, PedalMotion.pedalDamping(0.33f), 1e-6f)
        assertEquals(0f, PedalMotion.pedalDamping(0.55f), 1e-6f)
        var prev = 2f
        for (i in 0..1000) { val d = PedalMotion.pedalDamping(i / 1000f); assertTrue(d <= prev + 1e-7f); prev = d }
        assertEquals(0f, PedalMotion.damperLiftByPedal(0.33f), 1e-6f)
        assertEquals(1f, PedalMotion.damperLiftByPedal(1f), 1e-6f)
        assertEquals(0f, PedalMotion.keyDamperLift(0.5f), 1e-6f)
        assertEquals(1f, PedalMotion.keyDamperLift(1f), 1e-6f)
    }

    @Test fun harpsichordTiming() {
        assertEquals(3.51f, HarpsiTiming.staggerMs(127), 0.05f)
        assertEquals(9.36f, HarpsiTiming.staggerMs(0), 0.05f)
        assertEquals(30.1f, HarpsiTiming.quill8PassMs, 0.1f)
        assertEquals(41.4f, HarpsiTiming.quill4PassMs, 0.1f)
        assertEquals(47.6f, HarpsiTiming.damperLandMs, 0.1f)
        assertEquals(0.70f, HarpsiTiming.depth(1f), 1e-5f)
        for (i in 0..100) {
            val u = i / 100f
            assertEquals(0.70f * Math.pow(u.toDouble(), 1.8).toFloat(), HarpsiTiming.depth(u), 2e-4f)
        }
        // The 4' plucks at depth 2.6/6 of the key: 0.433 at on − stagger (T5.4's anchor).
        val v = 80
        val u = 1f - HarpsiTiming.staggerMs(v) / HarpsiTiming.leadMs(v)
        assertEquals(0.433f, HarpsiTiming.depth(u), 0.002f)
        assertEquals(0f, HarpsiTiming.returnDip(1f, HarpsiTiming.RETURN_MS), 1e-6f)
        assertEquals(1f, HarpsiTiming.returnDip(1f, 0f), 1e-6f)
    }

    @Test fun keyReturn() {
        assertEquals(18.4f, KeyReturn.damperLandMs(35f), 0.1f)
        assertEquals(26.2f, KeyReturn.damperLandMs(50f), 0.1f)
        assertEquals(1f, KeyReturn.dip(1f, 0f), 1e-6f)
        assertEquals(0f, KeyReturn.dip(1f, 1f), 1e-6f)
        assertEquals(0.5f, 1.08f * KeyReturn.dip(1f, KeyReturn.LAND_X), 0.005f)
    }
}
