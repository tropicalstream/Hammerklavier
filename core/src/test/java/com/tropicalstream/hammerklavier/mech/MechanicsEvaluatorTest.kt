package com.tropicalstream.hammerklavier.mech

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.MechanismPose
import com.tropicalstream.hammerklavier.contract.PedalCurve
import com.tropicalstream.hammerklavier.contract.Performance
import com.tropicalstream.hammerklavier.contract.SyntheticScore
import com.tropicalstream.hammerklavier.contract.VisTime
import com.tropicalstream.hammerklavier.mech.MechTestKit.N
import com.tropicalstream.hammerklavier.mech.MechTestKit.PRE
import com.tropicalstream.hammerklavier.mech.MechTestKit.fresh
import com.tropicalstream.hammerklavier.mech.MechTestKit.perf
import com.tropicalstream.hammerklavier.testutil.AllocProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** T5.8–T5.11, focus, and an unbound / null performance. */
class MechanicsEvaluatorTest {
    private val profiles = listOf(InstrumentProfile.GRAND, InstrumentProfile.UPRIGHT, InstrumentProfile.HARPSICHORD)

    private fun randomPerf(profile: InstrumentProfile, seed: Long): Performance {
        val rnd = Random(seed)
        val notes = ArrayList<N>()
        val nextFree = DoubleArray(128)
        var t = 0.0
        for (i in 0 until 600) {
            t += rnd.nextInt(60)
            val k = profile.lowKey + rnd.nextInt(profile.highKey - profile.lowKey + 1)
            if (t < nextFree[k]) continue
            val len = 25.0 + rnd.nextInt(400)
            notes.add(N(t, len, k, 1 + rnd.nextInt(127)))
            nextFree[k] = t + len + 5.0 + rnd.nextInt(80)
        }
        val sus = PedalCurve(longArrayOf(0, 2_000_000, 2_070_000, 6_000_000, 6_060_000, 9_000_000, 9_070_000),
            floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, 0.6f))
        return perf(profile, notes, sustain = sus, soft = sus)
    }

    private fun assertSamePose(what: String, a: MechanismPose, b: MechanismPose) {
        for (k in 0 until 128) {
            assertEquals("$what keyDip[$k]", b.keyDip[k], a.keyDip[k], 1e-6f)
            assertEquals("$what hammer[$k]", b.hammer[k], a.hammer[k], 1e-6f)
            assertEquals("$what damper[$k]", b.damper[k], a.damper[k], 1e-6f)
            assertEquals("$what escape[$k]", b.escape[k], a.escape[k], 1e-6f)
            assertEquals("$what jack4[$k]", b.jack4[k], a.jack4[k], 1e-6f)
            assertEquals("$what tongue[$k]", b.tongue[k], a.tongue[k], 1e-6f)
            assertEquals("$what tongue4[$k]", b.tongue4[k], a.tongue4[k], 1e-6f)
        }
        assertEquals(b.sustain, a.sustain, 1e-6f); assertEquals(b.soft, a.soft, 1e-6f)
        assertEquals(b.shiftMm, a.shiftMm, 1e-6f); assertEquals(b.hammerRailMm, a.hammerRailMm, 1e-6f)
    }

    /** T5.8 */
    @Test fun seekInvariance() {
        for ((pi, p) in profiles.withIndex()) for (r in floatArrayOf(1f, 0.5f, 1.5f)) {
            val pf = randomPerf(p, 11L + pi)
            val rnd = Random(99L + pi)
            val times = LongArray(1000) { PRE - 400_000 + (rnd.nextDouble() * (pf.durationUs - PRE + 400_000)).toLong() }.sorted()
            val ev = MechanicsEvaluatorImpl(); ev.exposureEnabled = false; ev.bind(pf, p)
            val o = MechanismPose()
            val v = VisTime().also { it.generation = 1; it.rate = r; it.playing = true }
            for ((i, t) in times.withIndex()) {
                v.tUs = t; v.reseed = i == 0; v.exposeFromUs = t; v.exposeToUs = t
                ev.evaluate(v, null, 0.03f, o)
                assertSamePose("${p.id} r=$r t=$t", o, fresh(pf, p, t, r))
            }
            // Small forward steps with backward steps of 5–60 ms (no reseed): the cursors step back.
            val ev2 = MechanicsEvaluatorImpl(); ev2.exposureEnabled = false; ev2.bind(pf, p)
            var t = PRE - 100_000L
            var i = 0
            while (i < 1500) {
                t += if (rnd.nextInt(4) == 0) -(5_000L + rnd.nextInt(55_001)) else 1_000L + rnd.nextInt(40_000)
                v.tUs = t; v.reseed = i == 0; v.exposeFromUs = t; v.exposeToUs = t
                ev2.evaluate(v, null, 0.03f, o)
                if (i % 3 == 0) assertSamePose("${p.id} back r=$r t=$t", o, fresh(pf, p, t, r))
                i++
            }
        }
    }

    /** T5.9 (timing informational). */
    @Test fun evaluateAllocatesNothing() {
        for (p in profiles) {
            val pf = MechTestKit.synthetic(SyntheticScore.CHORD_STORM_64, p)
            val ev = MechanicsEvaluatorImpl(); ev.bind(pf, p)
            val o = MechanismPose()
            val v = VisTime().also { it.generation = 1; it.rate = 1f; it.playing = true }
            val lanes = FloatArray(HK.LANES) { 0.1f }
            var t = PRE
            var edge = t
            val frame = {
                t += 33_333; v.tUs = t; v.reseed = false; v.exposeFromUs = edge; v.exposeToUs = t + 16_666; edge = v.exposeToUs
                ev.evaluate(v, if ((t / 33_333) % 2L == 0L) lanes else null, 0.033f, o)
            }
            v.tUs = t; v.reseed = true; ev.evaluate(v, null, 0f, o)
            AllocProbe.assertNoAllocation("evaluate ${p.id}", warmUp = { repeat(200) { frame() } }) { repeat(1000) { frame() } }
            val t0 = System.nanoTime()
            repeat(2000) { frame() }
            val per = (System.nanoTime() - t0) / 2000 / 1e6
            println("T5.9 ${p.id}: evaluate ${"%.4f".format(per)} ms per frame (informational, budget 0.3)")
        }
    }

    /** T5.10 */
    @Test fun repeatedNotesAnalytic() {
        for (p in profiles) {
            val pf = MechTestKit.synthetic(SyntheticScore.REPEAT_15, p)
            val ev = MechanicsEvaluatorImpl(); ev.exposureEnabled = false; ev.bind(pf, p)
            val o = MechanismPose()
            val v = VisTime().also { it.generation = 1; it.rate = 1f; it.playing = true }
            val lo = pf.keyFirst[60]; val hi = pf.keyFirst[61]
            assertEquals(90, hi - lo)
            fun at(t: Long): MechanismPose { v.tUs = t; v.exposeFromUs = t; v.exposeToUs = t; v.reseed = false; ev.evaluate(v, null, 0f, o); return o }
            v.tUs = PRE - 100_000; v.reseed = true; ev.evaluate(v, null, 0f, o)
            for (q in lo until hi) {
                val n = pf.keyNotes[q]; val on = pf.onUs[n]; val off = pf.offUs[n]
                val tStart = if (q > lo) pf.offUs[pf.keyNotes[q - 1]] else PRE - 100_000
                // Monotone descent from the previous key-up to this note's key-up (never a new descent early).
                var prev = at(maxOf(tStart, on - 200_000)).keyDip[60]
                var t = maxOf(tStart, on - 200_000) + 200
                var rising = true
                while (t < off) {
                    val d = at(t).keyDip[60]
                    if (t >= on) assertTrue("${p.id} note $q released before off at $t", d >= prev - 1e-6f)
                    prev = d; t += 200
                }
                if (p.id.name == "HARPSICHORD") {
                    assertEquals("${p.id} pluck $q", 0.70f, at(on).keyDip[60], 1e-3f)
                    assertTrue(at(on - 500).keyDip[60] < 0.70f + 1e-4f)
                } else {
                    assertEquals("${p.id} contact $q", 1f, at(on).hammer[60], 1e-4f)
                    assertEquals(1f, at(on + 500).hammer[60], 1e-4f)
                    assertTrue(at(on - 500).hammer[60] > 0.9f)
                }
                // The next descent never begins before this note's key-up.
                if (q + 1 < hi) {
                    val offDip = at(off).keyDip[60]
                    assertTrue(at(off + 200).keyDip[60] <= offDip + 1e-6f)
                }
                if (!rising) break
            }
        }
    }

    /** T5.11 */
    @Test fun preRollCoversTheSlowestStroke() {
        val u = InstrumentProfile.UPRIGHT
        val pf = perf(u, listOf(N(0.0, 300.0, 60, 20)))
        assertEquals(0f, fresh(pf, u, 0L, 1.5f).keyDip[60], 0f)
        val tStart = PRE - (230f * 1.05f * 1.5f * 1000f).toLong()
        assertTrue(tStart > 0)
        assertEquals(0f, fresh(pf, u, tStart - 1000, 1.5f).keyDip[60], 0f)
        assertTrue(fresh(pf, u, tStart + 2000, 1.5f).keyDip[60] > 0f)
        assertEquals(1f, fresh(pf, u, PRE, 1.5f).hammer[60], 1e-4f)
    }

    @Test fun focusAndCentroid() {
        val g = InstrumentProfile.GRAND
        val pf = perf(g, listOf(N(0.0, 400.0, 72, 80), N(600.0, 3000.0, 48, 80)))
        val d = MechTestKit.Driver(pf, g)
        var t = PRE - 100_000L
        var at500 = 0f; var at1500 = 0f; var at3500 = 0f
        while (t < PRE + 3_600_000L) {
            val o = d.frame(t, t + 33_333)
            if (at500 == 0f && t >= PRE + 500_000) at500 = o.focusKey
            if (at1500 == 0f && t >= PRE + 1_500_000) at1500 = o.focusKey
            if (at3500 == 0f && t >= PRE + 3_500_000) at3500 = o.focusKey
            t += 33_333
        }
        assertEquals(72f, at500, 0f)
        assertEquals(72f, at1500, 0f)                 // held ≥ 1.5 s before moving down
        assertEquals(48f, at3500, 0f)
        assertTrue(d.out.centroidKey in 40f..60f)
    }

    @Test fun nullPerformanceIsAtRest() {
        val ev = MechanicsEvaluatorImpl(); ev.bind(null, InstrumentProfile.GRAND)
        val o = MechanismPose()
        ev.evaluate(MechTestKit.vt(1_000_000, reseed = true), null, 0.03f, o)
        for (k in 0 until 128) { assertEquals(0f, o.keyDip[k], 0f); assertEquals(0f, o.hammer[k], 0f) }
        assertEquals(1_000_000L, o.songUs)
    }
}
