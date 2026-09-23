package com.tropicalstream.hammerklavier.mech

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.HarpsiTiming
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.mech.MechTestKit.N
import com.tropicalstream.hammerklavier.mech.MechTestKit.PRE
import com.tropicalstream.hammerklavier.mech.MechTestKit.fresh
import com.tropicalstream.hammerklavier.mech.MechTestKit.perf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T5.4 */
class HarpsichordActionTest {
    private val h = InstrumentProfile.HARPSICHORD
    private val k = 60
    private val on = PRE + 1_000_000L
    private val off = on + 500_000L
    private fun p(vel: Int = 64) = perf(h, listOf(N(1000.0, 500.0, k, vel)))
    private val rates = floatArrayOf(0.5f, 1f, 1.5f)

    /** First time in [from, to) (step [dt] µs) at which [f] holds. */
    private inline fun firstTime(from: Long, to: Long, dt: Long, f: (Long) -> Boolean): Long {
        var t = from
        while (t < to) { if (f(t)) return t; t += dt }
        return Long.MIN_VALUE
    }

    @Test fun pluckDepths() {
        for (r in rates) for (vel in intArrayOf(20, 64, 110)) {
            val pf = p(vel)
            assertEquals(0.70f, fresh(pf, h, on, r).keyDip[k], 1e-3f)
            val want = on - (HarpsiTiming.staggerMs(vel) * r * 1000f).toLong()
            val got = firstTime(on - 60_000, on, 10) { fresh(pf, h, it, r).keyDip[k] >= 0.433f }
            assertTrue("4' depth at $got, want $want", kotlin.math.abs(got - want) <= 200)
            assertEquals(fresh(pf, h, on, r).keyDip[k], fresh(pf, h, on, r).hammer[k], 0f)
            assertEquals(fresh(pf, h, on, r).keyDip[k], fresh(pf, h, on, r).jack4[k], 0f)
        }
    }

    @Test fun tongueFlicksAtTheQuillPass() {
        for (r in rates) {
            val pf = p()
            val flick = off + (HarpsiTiming.quill8PassMs * r * 1000f).toLong()
            val got = firstTime(off, off + (100_000 * r).toLong(), 50) { fresh(pf, h, it, r).tongue[k] > 0f }
            assertTrue("flick at $got, want $flick", kotlin.math.abs(got - flick) <= 100)
            assertEquals(30.1f, HarpsiTiming.quill8PassMs, 0.1f)
            val f4 = off + (HarpsiTiming.quill4PassMs * r * 1000f).toLong()
            val g4 = firstTime(off, off + (100_000 * r).toLong(), 50) { fresh(pf, h, it, r).tongue4[k] > 0f }
            assertTrue(kotlin.math.abs(g4 - f4) <= 100)
            // Back to 0 after 25 ms·r.
            assertEquals(0f, fresh(pf, h, flick + (25.5f * r * 1000f).toLong(), r).tongue[k], 0f)
        }
    }

    @Test fun clothTouchesAt47_6() {
        for (r in rates) {
            val pf = p()
            val want = off + (HarpsiTiming.damperLandMs * r * 1000f).toLong()
            val got = firstTime(off, off + (100_000 * r).toLong(), 20) { fresh(pf, h, it, r).damper[k] == 0f }
            assertTrue("cloth at $got, want $want", kotlin.math.abs(got - want) <= 300)
            assertTrue(fresh(pf, h, on + 100_000, r).damper[k] == 1f)
        }
    }

    @Test fun quadraticReturnFromRestSpeed() {
        for (r in rates) {
            val pf = p()
            val ms = r * 1000f
            assertTrue(fresh(pf, h, off + ms.toLong(), r).keyDip[k] > 0.999f)
            assertEquals(0.75f, fresh(pf, h, off + (27.5f * ms).toLong(), r).keyDip[k], 1e-3f)
            assertEquals(0f, fresh(pf, h, off + (55.5f * ms).toLong(), r).keyDip[k], 0f)
        }
    }

    @Test fun disengagedRegisterShowsNoPluck() {
        val pf = p()
        fun run(reg: Int): Triple<Boolean, Float, Float> {
            val d = MechTestKit.Driver(pf, h); d.registration = reg
            var flashed = false; var t8 = 0f; var t4 = 0f
            var t = PRE + 500_000L
            while (t < off + 100_000) {
                val o = d.frame(t, t + 10_000)
                if (o.flash[k]) flashed = true
                t8 = maxOf(t8, o.tongue[k]); t4 = maxOf(t4, o.tongue4[k])
                t += 10_000
            }
            return Triple(flashed, t8, t4)
        }
        val both = run(3); assertTrue(both.first); assertTrue(both.second > 0f); assertTrue(both.third > 0f)
        val none = run(0); assertFalse(none.first); assertEquals(0f, none.second, 0f); assertEquals(0f, none.third, 0f)
        val only4 = run(HK.REG_4); assertTrue(only4.first); assertEquals(0f, only4.second, 0f); assertTrue(only4.third > 0f)
        assertEquals(HK.REG_4, fresh(pf, h, on, registration = HK.REG_4).registers)
    }
}
