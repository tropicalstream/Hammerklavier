package com.tropicalstream.hammerklavier.dsp

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.ResonanceMode
import com.tropicalstream.hammerklavier.dsp.CombRig.Companion.blockOf
import com.tropicalstream.hammerklavier.dsp.DspTestUtil.db
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import kotlin.math.sqrt

/**
 * T3.1 calibration targets of §3.11 on SineBank-recipe harmonic voices (the real decoded regions
 * of WP11 follow in [realRegions]).
 */
class CombCalibrationTest {
    private val sr = HK.SR

    /** Comb RMS of [key] and voice RMS over [a, b) seconds. */
    private class Meter(val key: Int) { var comb = 0.0; var n = 0 }

    @Test fun target1PedalDownC4StruckC5CombAtMinus28() {
        val rig = CombRig(mode = ResonanceMode.NATURAL)
        java.util.Arrays.fill(rig.damping, 0f); for (k in 21..108) rig.gate[k] = 1f
        val c4 = DspTestUtil.harmonicTone(2 * sr, 60, 0.5)
        rig.voices += CombRig.Voice(60, c4, false)
        var ms = 0.0; var cnt = 0
        val b0 = blockOf(0.5); val b1 = blockOf(1.5)
        rig.run(b1, sink = { bi -> if (bi >= b0) { ms += 2 * rig.lanes[72 - 21]; cnt++ } })
        val comb = sqrt(ms / cnt)
        val voice = DspTestUtil.rms(c4, b0 * HK.BLOCK, b1 * HK.BLOCK)
        val rel = db(comb / voice)
        println("T3.1 target 1: C5 comb re C4 voice = %.1f dB".format(rel))
        assertEquals(-28.0, rel, 4.0)
    }

    @Test fun target2UnaCordaC4CombAtMinus12() {
        val rig = CombRig(mode = ResonanceMode.NATURAL)
        rig.gate[60] = 1f; rig.damping[60] = 0f; rig.softFeed[60] = true
        val c4 = DspTestUtil.harmonicTone(3 * sr + HK.BLOCK, 60, 0.4)
        rig.voices += CombRig.Voice(60, c4, true)
        var ms = 0.0; var cnt = 0
        val b0 = blockOf(1.0); val b1 = blockOf(3.0)
        rig.run(b1, sink = { bi -> if (bi >= b0) { ms += 2 * rig.lanes[60 - 21]; cnt++ } })
        val rel = db(sqrt(ms / cnt) / DspTestUtil.rms(c4, b0 * HK.BLOCK, b1 * HK.BLOCK))
        println("T3.1 target 2: una corda C4 comb re dry = %.1f dB".format(rel))
        assertEquals(-12.0, rel, 4.0)
    }

    @Test fun target3HeldC3RingsWhenC4IsStruck() {
        val rig = CombRig(mode = ResonanceMode.NATURAL)
        // Pedal up: every damper down except the held C3 and the struck C4.
        rig.gate[48] = 1f; rig.damping[48] = 0f; rig.gate[60] = 1f; rig.damping[60] = 0f
        val start = (1.2 * 24 / 8.686 * sr).toInt()      // C3 has decayed 24 dB
        val n = start + 2 * sr
        rig.voices += CombRig.Voice(48, DspTestUtil.harmonicTone(n, 48, 0.5), false)
        val c4 = DspTestUtil.harmonicTone(n, 60, 0.5, start)
        rig.voices += CombRig.Voice(60, c4, false)
        var combPeak = 0.0
        rig.run(n / HK.BLOCK, sink = { bi -> if (bi * HK.BLOCK >= start) combPeak = maxOf(combPeak, sqrt(2.0 * rig.lanes[48 - 21])) })
        var c4Peak = 0.0
        for (b in start / HK.BLOCK until n / HK.BLOCK) c4Peak = maxOf(c4Peak, DspTestUtil.rms(c4, b * HK.BLOCK, (b + 1) * HK.BLOCK))
        val rel = db(combPeak / c4Peak)
        println("T3.1 target 3: C3 comb re C4 peak = %.1f dB".format(rel))
        assertTrue("rel $rel", rel >= -40.0)
    }

    @Test fun target4C3AloneStaysBelowAndDecaysMonotonically() {
        for (pedal in listOf(false, true)) {
            val rig = CombRig(mode = ResonanceMode.RICH)
            if (pedal) { java.util.Arrays.fill(rig.damping, 0f); for (k in 21..108) rig.gate[k] = 1f }
            else { rig.gate[48] = 1f; rig.damping[48] = 0f }
            val n = 10 * sr
            val c3 = DspTestUtil.harmonicTone(n, 48, 0.5)
            rig.voices += CombRig.Voice(48, c3, false)
            val win = sr / 10 / HK.BLOCK * HK.BLOCK
            val sum = DoubleArray(n / win); val selfComb = DoubleArray(n / win)
            rig.run(n / HK.BLOCK, sink = { bi ->
                val w = bi * HK.BLOCK / win
                if (w < sum.size) {
                    for (i in 0 until HK.BLOCK) {
                        val t = bi * HK.BLOCK + i
                        val s = c3[t] + 0.5f * (rig.outL[i] + rig.outR[i])
                        sum[w] += s.toDouble() * s
                    }
                    selfComb[w] += 2.0 * rig.lanes[48 - 21] * HK.BLOCK
                }
            })
            var prev = Double.MAX_VALUE
            for (w in sum.indices) {
                val voice = DspTestUtil.rms(c3, w * win, (w + 1) * win)
                val comb = sqrt(selfComb[w] / win)
                assertTrue("pedal=$pedal w=$w C3 comb ${db(comb / voice)} dB", comb == 0.0 || db(comb / voice) <= -30.0)
                val e = sqrt(sum[w] / win)
                if (w >= 1) assertTrue("pedal=$pedal window $w rises: ${db(e)} after ${db(prev)}", e <= prev * 1.0001)
                prev = e
            }
        }
    }

    @Ignore("needs wp11 fixture")
    @Test fun realRegions() {
        // core/src/test/resources/wp11/real/: C3, C4, C5 at v10 and v13 and the una corda C4 (§6.5 step 13).
        // Once present: repeat targets 1–4 with those regions in place of harmonicTone.
        val dir = java.io.File("src/test/resources/wp11/real")
        assertTrue("missing $dir", dir.isDirectory)
    }
}
