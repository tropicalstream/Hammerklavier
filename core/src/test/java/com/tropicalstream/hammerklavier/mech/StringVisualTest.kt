package com.tropicalstream.hammerklavier.mech

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.MechanismPose
import com.tropicalstream.hammerklavier.mech.MechTestKit.N
import com.tropicalstream.hammerklavier.mech.MechTestKit.PRE
import com.tropicalstream.hammerklavier.mech.MechTestKit.perf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T5.6 */
class StringVisualTest {
    private val g = InstrumentProfile.GRAND

    @Test fun energyMapping() {
        assertEquals(1f, StringVisual.ampOf(1f), 0.005f)
        assertEquals(0f, StringVisual.ampOf(0.001f), 0.005f)
        assertEquals(0f, StringVisual.ampOf(0f), 0f)
        assertEquals(0f, StringVisual.ampOf(1e-6f), 0f)
        assertEquals(1f, StringVisual.ampOf(4f), 0f)
        assertEquals(0.8f, StringVisual.ampOf(0.25f), 0.005f)       // −12 dBFS C4 golden (R80)
        assertEquals(2f / 3f, StringVisual.ampOf(0.1f), 0.005f)
        for (i in 1..1000) {
            val e = i / 1000f
            val want = ((20.0 * Math.log10(e.toDouble()) + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
            assertEquals(want, StringVisual.ampOf(e), 0.002f)
        }
    }

    @Test fun lanesDriveTheStrings() {
        val pf = perf(g, listOf(N(1000.0, 500.0, 60, 64)))
        val ev = MechanicsEvaluatorImpl(); ev.bind(pf, g)
        val lanes = FloatArray(HK.LANES); lanes[39] = 0.25f; lanes[0] = 1f
        val o = MechanismPose()
        ev.evaluate(MechTestKit.vt(PRE + 100_000, reseed = true), lanes, 0.033f, o)
        assertEquals(0.8f, o.stringAmp[60], 0.01f)
        assertEquals(1f, o.stringAmp[21], 0.01f)
        assertEquals(0f, o.stringAmp[61], 0f)
    }

    private fun ampAfter(lenMs: Double, atMs: Long): Float {
        val pf = perf(g, listOf(N(1000.0, lenMs, 60, 90)))
        val d = MechTestKit.Driver(pf, g)
        var t = PRE + 500_000L
        val end = PRE + 1_000_000L + atMs * 1000
        var o = d.out
        while (t <= end) { o = d.frame(t, t + 33_333); t += 33_333 }
        return o.stringAmp[60]
    }

    @Test fun analyticFallback() {
        val early = ampAfter(3000.0, 50)
        val held = ampAfter(3000.0, 1000)
        val damped = ampAfter(100.0, 1000)
        assertTrue("struck $early", early > 0.4f)
        assertTrue("held decays $held < $early", held < early && held > 0.1f)
        assertTrue("damped $damped < held $held", damped < held - 0.1f)
        assertEquals(0f, ampAfter(3000.0, -100), 0f)                   // nothing before the strike
    }

    @Test fun resetOnReseed() {
        val pf = perf(g, listOf(N(1000.0, 3000.0, 60, 90)))
        val d = MechTestKit.Driver(pf, g)
        var t = PRE + 900_000L
        while (t < PRE + 1_200_000L) { d.frame(t, t + 33_333); t += 33_333 }
        assertTrue(d.out.stringAmp[60] > 0.3f)
        d.frame(t, t + 33_333, reseed = true)
        assertEquals(0f, d.out.stringAmp[60], 0f)
        assertEquals(1e9f, d.out.strikeAge[60], 0f)
    }

    @Test fun ribbonHelpers() {
        assertEquals(0.6f, StringVisual.halfWidthPx(0f, StringVisual.W_CUTAWAY), 0f)
        assertEquals(3.1f, StringVisual.halfWidthPx(1f, StringVisual.W_CUTAWAY), 1e-5f)
        assertEquals(1f, StringVisual.alpha(0.6f), 0f)
        assertEquals(1.2f / 3.1f, StringVisual.alpha(3.1f), 1e-5f)
        assertTrue(StringVisual.wobbleHz(21, 0) >= 2f && StringVisual.wobbleHz(108, 2) <= 12.5f)
        assertEquals(Math.exp(-3.7).toFloat(), StringVisual.expNeg(3.7f), 1e-4f)
    }
}
