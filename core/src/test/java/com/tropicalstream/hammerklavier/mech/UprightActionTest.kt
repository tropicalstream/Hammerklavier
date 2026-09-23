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

/** T5.5 */
class UprightActionTest {
    private val u = InstrumentProfile.UPRIGHT
    private val k = 60

    @Test fun softPedalRaisesTheRest() {
        val pf = perf(u, listOf(N(1000.0, 300.0, k, 64)), soft = hold(1f))
        val rest = fresh(pf, u, PRE + 200_000)
        assertEquals(22f / 47f, rest.hammer[k], 1e-4f)
        assertEquals(22f, rest.hammerRailMm, 1e-4f)
        assertEquals(0f, rest.shiftMm, 0f)
        assertEquals(1f, fresh(pf, u, PRE + 1_000_000).hammer[k], 1e-4f)      // still reaches the string
        val half = perf(u, listOf(N(1000.0, 300.0, k, 64)), soft = hold(0.5f))
        assertEquals(11f / 47f, fresh(half, u, PRE + 200_000).hammer[k], 1e-4f)
    }

    @Test fun checkAndReturn() {
        val pf = perf(u, listOf(N(1000.0, 300.0, k, 64)))
        val on = PRE + 1_000_000L; val off = on + 300_000L
        assertEquals((47f - 16f) / 47f, fresh(pf, u, on + 200_000).hammer[k], 1e-4f)
        assertEquals(0f, fresh(pf, u, off + 50_000).keyDip[k], 1e-6f)
        assertTrue(fresh(pf, u, off + 49_000).keyDip[k] > 0f)
        assertEquals(26.2f, u.damperLagMs, 0.1f)
        // Travel scale 1.05.
        val tStart = on - (Touch.travelMs(64) * 1.05f * 1000f).toLong()
        assertEquals(0f, fresh(pf, u, tStart - 500).keyDip[k], 0f)
        assertTrue(fresh(pf, u, tStart + 500).keyDip[k] > 0f)
    }

    private fun secondNote(pf: com.tropicalstream.hammerklavier.contract.Performance): NoteCtx {
        val tm = NoteTiming(UprightAction()); tm.bind(pf)
        val lo = pf.keyFirst[k]
        return NoteCtx().also { tm.fill(lo + 1, lo, 1f, it) }
    }

    @Test fun repetitionOnlyAfter80PercentReturn() {
        // Slow repetition (200 ms >= 143 ms) but restarted 10 ms after the release: not 80% returned.
        val caught = secondNote(perf(u, listOf(N(1000.0, 190.0, k, 64), N(1200.0, 200.0, k, 64))))
        assertTrue(caught.d0 > 0.2f); assertTrue(caught.fast)
        // Fully returned before the next stroke, slow: a normal stroke from rest.
        val free = secondNote(perf(u, listOf(N(1000.0, 100.0, k, 64), N(1400.0, 200.0, k, 64))))
        assertEquals(0f, free.d0, 1e-6f); assertTrue(!free.fast)
        // Returned but faster than 143 ms: the fast-repetition approximation.
        val fastPf = perf(u, listOf(N(1000.0, 50.0, k, 64), N(1100.0, 200.0, k, 64)))
        val fast = secondNote(fastPf)
        assertTrue(fast.fast)
        // Relaunched toward half the blow from where the hammer is (no jump at tStart, T5.3):
        // it rises well above its start height before escapement.
        val hh = fresh(fastPf, u, fast.onUs - 3000).hammer[k]; assertTrue("h=$hh", hh > fresh(fastPf, u, fast.tStartUs).hammer[k] + 0.05f)
    }

    @Test fun dampersLiftWithTheKeyAndLand() {
        val pf = perf(u, listOf(N(1000.0, 300.0, k, 64)))
        val on = PRE + 1_000_000L; val off = on + 300_000L
        assertEquals(1f, fresh(pf, u, on + 10_000).damper[k], 0f)
        assertTrue(fresh(pf, u, off + 25_000).damper[k] > 0f)
        assertEquals(0f, fresh(pf, u, off + 27_000).damper[k], 0f)
    }
}
