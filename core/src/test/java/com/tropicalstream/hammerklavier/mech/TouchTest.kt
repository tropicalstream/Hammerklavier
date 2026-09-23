package com.tropicalstream.hammerklavier.mech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T5.1 */
class TouchTest {
    @Test fun travelTimes() {
        assertEquals(230f, Touch.travelMs(20), 0.01f)
        assertEquals(86f, Touch.travelMs(64), 1f)
        assertEquals(20f, Touch.travelMs(110), 0.01f)
        assertEquals(230f * 1.05f, Touch.travelMs(20, 1.05f), 0.01f)
    }

    @Test fun keyBed() {
        assertEquals(5.4f, Touch.bedMs(64), 0.3f)
        assertEquals(18.6f, Touch.bedMs(20), 0.3f)
        assertEquals(-1.9f, Touch.bedMs(110), 0.3f)
    }

    @Test fun monotoneAndBounded() {
        for (v in 1 until 127) {
            assertTrue(Touch.travelMs(v + 1) <= Touch.travelMs(v))
            assertTrue(Touch.freeFlightMs(v) in 0f..20f)
            assertTrue(Touch.hammerVelocity(v) in 0.25f..7f)
        }
        assertEquals(4f, Touch.contactMs(21), 1e-4f)
        assertEquals(0.8f, Touch.contactMs(108), 1e-3f)
    }

    @Test fun pow18Table() {
        for (i in 0..100) { val u = i / 100f; assertEquals(Math.pow(u.toDouble(), 1.8).toFloat(), Touch.pow18(u), 2e-3f) }
    }
}
