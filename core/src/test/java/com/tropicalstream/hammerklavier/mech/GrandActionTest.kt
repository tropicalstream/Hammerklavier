package com.tropicalstream.hammerklavier.mech

import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.mech.MechTestKit.N
import com.tropicalstream.hammerklavier.mech.MechTestKit.PRE
import com.tropicalstream.hammerklavier.mech.MechTestKit.fresh
import com.tropicalstream.hammerklavier.mech.MechTestKit.hold
import com.tropicalstream.hammerklavier.mech.MechTestKit.perf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T5.2 and T5.3 */
class GrandActionTest {
    private val g = InstrumentProfile.GRAND
    private val k = 60
    private val on = PRE + 1_000_000L
    private val off = on + 500_000L
    private fun p(vel: Int = 64) = perf(g, listOf(N(1000.0, 500.0, k, vel)))
    private fun ms(x: Double, r: Float) = (x * 1000 * r).toLong()

    @Test fun keyStartsExactlyAtTStart() {
        for (r in floatArrayOf(0.5f, 1f, 1.5f)) for (vel in intArrayOf(20, 64, 110)) {
            val pf = p(vel)
            val tStart = on - (Touch.travelMs(vel) * r * 1000f).toLong()
            assertEquals(0f, fresh(pf, g, tStart - 1000, r).keyDip[k], 0f)
            assertTrue(fresh(pf, g, tStart + 1000, r).keyDip[k] > 0f)
        }
    }

    @Test fun hammerAtStringOnTheOnset() {
        for (r in floatArrayOf(0.5f, 1f, 1.5f)) for (vel in intArrayOf(20, 64, 110)) {
            val pf = p(vel)
            assertEquals(1f, fresh(pf, g, on, r).hammer[k], 1e-4f)
            assertEquals(1f, fresh(pf, g, on + 500, r).hammer[k], 1e-4f)
            assertTrue(fresh(pf, g, on - 1000, r).hammer[k] < 0.9999f)
            assertTrue(fresh(pf, g, on - 500, r).hammer[k] > 0.95f)
        }
    }

    @Test fun checkedWhileHeld() {
        val h = fresh(p(), g, on + 200_000, 1f).hammer[k]
        assertEquals((47f - 15f) / 47f, h, 1e-4f)
        assertEquals(1f, fresh(p(), g, on + 200_000, 1f).keyDip[k], 0f)
    }

    @Test fun keyReturnsInRealTime() {
        for (r in floatArrayOf(0.5f, 1f, 1.5f)) {
            val pf = p()
            assertEquals(0f, fresh(pf, g, off + ms(35.0, r), r).keyDip[k], 1e-6f)
            assertTrue(fresh(pf, g, off + ms(34.0, r), r).keyDip[k] > 0f)
            assertTrue(fresh(pf, g, off + ms(1.0, r), r).keyDip[k] > 0.99f)   // smoothstep starts at rest speed
            assertEquals(0.5f, fresh(pf, g, off + ms(17.5, r), r).keyDip[k], 1e-3f)
        }
        // At rate 0.5, song-time durations halve.
        assertEquals(0f, fresh(p(), g, off + 17_500, 0.5f).keyDip[k], 1e-6f)
        assertTrue(fresh(p(), g, off + 17_500, 1f).keyDip[k] > 0.4f)
    }

    @Test fun damperLandsAtDamperLag() {
        for (r in floatArrayOf(0.5f, 1f, 1.5f)) {
            val pf = p()
            val land = off + (g.damperLagMs * r * 1000f).toLong()
            var first = -1L
            var t = off
            while (t < off + ms(40.0, r)) { if (fresh(pf, g, t, r).damper[k] == 0f) { first = t; break }; t += 100 }
            assertTrue("first touch $first vs $land", kotlin.math.abs(first - land) <= 1000)
            assertEquals(1f, fresh(pf, g, off - 1000, r).damper[k], 0f)
        }
        assertEquals(18.4f, g.damperLagMs, 0.1f)
    }

    @Test fun damperLiftsFromSustainAndKey() {
        val none = perf(g, listOf(N(1000.0, 500.0, 40, 64)))
        val half = perf(g, listOf(N(1000.0, 500.0, 40, 64)), sustain = hold(0.5f))
        val low = perf(g, listOf(N(1000.0, 500.0, 40, 64)), sustain = hold(0.3f))
        val t = PRE + 3_000_000L
        assertEquals(0f, fresh(none, g, t).damper[k], 0f)
        assertEquals(0f, fresh(low, g, t).damper[k], 0f)
        assertEquals((0.5f - 0.33f) / 0.67f, fresh(half, g, t).damper[k], 1e-3f)
        assertEquals(1f, fresh(p(), g, on + 10_000).damper[k], 0f)
        assertTrue(fresh(p(), g, on).damper[k] > 0.5f)
        assertEquals(0.5f, fresh(half, g, t).sustain, 1e-6f)
    }

    @Test fun unaCordaShift() {
        val sp = perf(g, listOf(N(1000.0, 500.0, 40, 64)), soft = hold(1f))
        assertEquals(2.5f, fresh(sp, g, PRE + 100_000).shiftMm, 1e-5f)
    }

    /** T5.3 */
    @Test fun restrikeFromPartWayUpIsContinuous() {
        for (r in floatArrayOf(0.5f, 1f, 1.5f)) {
            // Released at 1000 ms, re-struck at 1040 ms: the key is caught part-way up.
            val pf = perf(g, listOf(N(500.0, 500.0, k, 80), N(1040.0, 300.0, k, 80)))
            val from = PRE + 900_000L; val to = PRE + 1_200_000L
            var prev = fresh(pf, g, from, r).keyDip[k]
            var t = from + 100
            var minMid = 1f
            while (t < to) {
                val d = fresh(pf, g, t, r).keyDip[k]
                assertTrue("step at $t r=$r: ${d - prev}", kotlin.math.abs(d - prev) <= 0.05f * 0.1f / r + 1e-4f)
                if (t > PRE + 1_000_000L && t < PRE + 1_040_000L) minMid = minOf(minMid, d)
                prev = d; t += 100
            }
            assertTrue("part-way up $minMid", minMid > 0.05f && minMid < 0.95f)
        }
    }
}
