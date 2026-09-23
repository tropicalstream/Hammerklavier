package com.tropicalstream.hammerklavier.mech

import com.tropicalstream.hammerklavier.contract.ClockSample
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.MechanismPose
import com.tropicalstream.hammerklavier.contract.SyntheticScore
import com.tropicalstream.hammerklavier.contract.VisTime
import com.tropicalstream.hammerklavier.contract.VisualClock
import com.tropicalstream.hammerklavier.mech.MechTestKit.PRE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/** T5.7 */
class ExposureSamplerTest {
    private val profiles = listOf(InstrumentProfile.GRAND, InstrumentProfile.UPRIGHT, InstrumentProfile.HARPSICHORD)

    private fun countFlashes(profile: InstrumentProfile, spacing: LongArray): Pair<Int, Int> {
        val pf = MechTestKit.synthetic(SyntheticScore.REPEAT_15, profile)
        val d = MechTestKit.Driver(pf, profile)
        var t = PRE - 200_000L
        var i = 0
        var flashes = 0
        var forcedOk = 0
        val end = pf.durationUs
        while (t < end) {
            val next = t + spacing[i % spacing.size]
            val o = d.frame(t, next)
            if (o.flash[60]) { flashes++; if (profile.id.name == "HARPSICHORD" || o.hammer[60] == 1f) forcedOk++ }
            t = next; i++
        }
        return flashes to forcedOk
    }

    @Test fun everyContactExactlyOnce() {
        val cases = listOf(longArrayOf(33_333), longArrayOf(50_000), longArrayOf(16_000, 33_000, 50_000))
        for (p in profiles) for (c in cases) {
            val pf = MechTestKit.synthetic(SyntheticScore.REPEAT_15, p)
            val (n, forced) = countFlashes(p, c)
            assertEquals("${p.id} ${c.toList()}", pf.noteCount, n)
            assertEquals(n, forced)
        }
    }

    @Test fun pauseAfterContactForcesNothing() {
        val g = InstrumentProfile.GRAND
        val pf = MechTestKit.synthetic(SyntheticScore.REPEAT_15, g)
        val on = pf.onUs[pf.keyNotes[pf.keyFirst[60] + 35]]         // a v64 note
        val d = MechTestKit.Driver(pf, g)
        var t = on - 300_000
        while (t < on) { d.frame(t, t + 33_333); t += 33_333 }
        val tp = on + 10_000
        var o = d.frame(tp, tp + 33_333)
        for (j in 0 until 30) {
            o = d.frame(tp, tp, playing = false)
            assertFalse(o.flash[60])
            assertTrue("pinned ${o.hammer[60]}", o.hammer[60] < 1f)
        }
    }

    @Test fun afterReseedNothingIsForced() {
        val g = InstrumentProfile.GRAND
        val pf = MechTestKit.synthetic(SyntheticScore.REPEAT_15, g)
        val on = pf.onUs[pf.keyNotes[pf.keyFirst[60] + 5]]
        val ev = MechanicsEvaluatorImpl(); ev.bind(pf, g)
        val o = MechanismPose()
        ev.evaluate(MechTestKit.vt(on - 1000, reseed = true, from = on - 20_000, to = on + 20_000), null, 0.03f, o)
        assertFalse(o.flash[60])
        // The next, non-reseed frame with a window containing a contact does force it.
        ev.evaluate(MechTestKit.vt(on + 66_000, from = on + 20_000, to = on + 80_000), null, 0.03f, o)
        assertTrue(o.flash[60])
        assertEquals(1f, o.hammer[60], 0f)
    }

    /** With the real VisualClock's centred windows: mean display offset ≈ 0 at 20 fps. */
    @Ignore("needs contracts-v1.1")
    @Test fun centredWindowsFromVisualClock() {
        val g = InstrumentProfile.GRAND
        val pf = MechTestKit.synthetic(SyntheticScore.REPEAT_15, g)
        val ev = MechanicsEvaluatorImpl(); ev.bind(pf, g)
        val vc = VisualClock(); val s = ClockSample(); val v = VisTime(); val o = MechanismPose()
        s.valid = true; s.playing = true; s.generation = 1
        var sum = 0.0; var n = 0
        var t = PRE - 100_000L
        var ni = 0L
        while (t < pf.durationUs) {
            s.songUs = t; vc.update(s, ni, v); ev.evaluate(v, null, 0.05f, o)
            if (o.flash[60]) {
                val lo = pf.keyFirst[60]; val hi = pf.keyFirst[61]
                var best = Long.MAX_VALUE
                for (p in lo until hi) { val d = v.tUs - pf.onUs[pf.keyNotes[p]]; if (kotlin.math.abs(d) < kotlin.math.abs(best)) best = d }
                sum += best; n++
            }
            t += 50_000; ni += 50_000_000L
        }
        assertEquals(pf.noteCount, n)
        assertTrue("mean offset ${sum / n} µs", kotlin.math.abs(sum / n) < 3_000)
    }
}
