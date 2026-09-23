package com.tropicalstream.hammerklavier.midi

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.PedalMode
import com.tropicalstream.hammerklavier.contract.PedalMotion
import com.tropicalstream.hammerklavier.contract.Performance
import com.tropicalstream.hammerklavier.midi.MidiTestUtil.perf
import com.tropicalstream.hammerklavier.midi.SmfWriter.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T1.4: the pedal shaper. */
class PedalShaperTest {
    private val pre = HK.PRE_ROLL_US
    private fun ms(x: Long) = pre + x * 1000

    @Test fun switchDownEdgeCrossesAtEventAndStarts23msEarly() {
        val s = PedalShaper.shape(longArrayOf(1_000_000), intArrayOf(127), PedalShaper.SUSTAIN)
        assertEquals(PedalMode.SWITCH, s.mode)
        val c = s.curve
        val cross = c.nextCrossing(0, PedalMotion.LIFT_START, rising = true)
        assertEquals(1_000_000.0, cross.toDouble(), 100.0)
        val start = c.us[0]
        assertEquals(0f, c.v[0], 0f)
        assertEquals(23_000.0, (1_000_000 - start).toDouble(), 500.0)
        assertEquals(1f, c.valueAt(1_000_000 + 47_000), 1e-3f)          // 70 ms full travel
    }

    @Test fun switchUpEdgeCrossesAtEvent() {
        val s = PedalShaper.shape(longArrayOf(1_000_000, 2_000_000), intArrayOf(127, 0), PedalShaper.SUSTAIN)
        val down = s.curve.nextCrossing(1_500_000, PedalMotion.LIFT_START, rising = false)
        assertEquals(2_000_000.0, down.toDouble(), 100.0)
        assertEquals(0f, s.curve.valueAt(2_000_000 + 20_000), 1e-3f)     // 60 ms, 40.2 before + 19.8 after
    }

    @Test fun upDown30msApartDipsBelowLiftButNotToZero() {
        val s = PedalShaper.shape(longArrayOf(1_000_000, 2_000_000, 2_030_000), intArrayOf(127, 0, 127), PedalShaper.SUSTAIN)
        var min = 1f
        var t = 1_900_000L
        while (t < 2_200_000L) { min = minOf(min, s.curve.valueAt(t)); t += 100 }
        assertTrue("dip $min", min < PedalMotion.LIFT_START)
        assertTrue("dip $min", min > 0f)
        assertEquals(1f, s.curve.valueAt(2_300_000), 1e-3f)
        // Crossings: down at 2.0 s, back up after it.
        val up = s.curve.nextCrossing(2_000_100, PedalMotion.LIFT_START, rising = true)
        assertTrue(up in 2_000_100..2_030_000)
    }

    @Test fun continuousDetectedAtEightDistinctValues() {
        val v7 = intArrayOf(10, 20, 30, 40, 50, 60, 70, 0)
        val v8 = intArrayOf(10, 20, 30, 40, 50, 60, 70, 80, 0)
        assertEquals(PedalMode.SWITCH, PedalShaper.modeOf(v7))
        assertEquals(PedalMode.CONTINUOUS, PedalShaper.modeOf(v8))
        assertEquals(PedalMode.NONE, PedalShaper.modeOf(IntArray(0)))
        val t = LongArray(v8.size) { 1_000_000L + 200_000L * it }
        val c = PedalShaper.shape(t, v8, PedalShaper.SUSTAIN).curve
        assertEquals(40 / 127f, c.valueAt(1_000_000 + 3 * 200_000 + 100_000), 1e-4f)   // held values
        // Slew: a jump 80 → 0 takes 80/127 · 70 ms.
        val jumpAt = 1_000_000L + 8 * 200_000L
        assertEquals(80 / 127f, c.valueAt(jumpAt), 1e-4f)
        assertEquals(0f, c.valueAt(jumpAt + 44_200), 1e-3f)
        assertTrue(c.valueAt(jumpAt + 20_000) > 0.2f)
    }

    @Test fun maxAcrossChannels() {
        val bytes = SmfWriter.file(0, 500, Track().on(0, 0, 60, 80).cc(0, 0, 64, 127).cc(100, 1, 64, 127).cc(100, 0, 64, 0)
            .off(100, 0, 60).cc(100, 1, 64, 0).end().build())
        val p = perf(bytes)
        // Held from 0 to 400 ms (channel 2 releases last).
        assertEquals(ms(400).toDouble(), p.sustain.nextCrossing(ms(10), PedalMotion.LIFT_START, false).toDouble(), 100.0)
    }

    @Test fun pedalNoisesAtLeast150msApart() {
        val t = Track().on(0, 0, 60, 80)
        // Pedal changes every 50 ms: many crossings, noises thinned to ≥ 150 ms.
        for (i in 0 until 40) t.cc(50, 0, 64, if (i % 2 == 0) 127 else 0)
        val p = perf(SmfWriter.file(0, 500, t.off(100, 0, 60).end().build()))
        val noise = p.ev.indices.filter { Performance.type(p.ev[it]) == Performance.EV_PEDAL_NOISE }.map { p.evUs[it] }
        assertTrue(noise.size >= 5)
        for (i in 1 until noise.size) assertTrue(noise[i] - noise[i - 1] >= 150_000)
        // Switch ramps are speed class 2; bit 0 marks down.
        val first = p.ev.first { Performance.type(it) == Performance.EV_PEDAL_NOISE }
        assertEquals(1, Performance.arg(first) and 1)
        assertEquals(2, Performance.arg(first) shr 1)
    }

    @Test fun softAndSostenutoCrossHalfAtEvent() {
        val s = PedalShaper.shape(longArrayOf(1_000_000, 3_000_000), intArrayOf(127, 0), PedalShaper.SOFT)
        assertEquals(1_000_000.0, s.curve.nextCrossing(0, 0.5f, true).toDouble(), 100.0)
        assertEquals(3_000_000.0, s.curve.nextCrossing(1_100_000, 0.5f, false).toDouble(), 100.0)
        assertEquals(1_000_000L - 30_000L, s.curve.us[0])
    }
}
