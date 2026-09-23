package com.tropicalstream.hammerklavier.dsp

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.ResonanceMode
import com.tropicalstream.hammerklavier.dsp.CombRig.Companion.blockOf
import com.tropicalstream.hammerklavier.dsp.DspTestUtil.db
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * T3.1 calibration targets of §3.11 on SineBank-recipe harmonic voices (the real decoded regions
 * of WP11 follow in [realRegions]).
 */
class CombCalibrationTest {
    private val sr = HK.SR

    /** A voice source: [n] frames of [key] at peak-ish [amp], silent before frame [start]; [soft] = una corda region. */
    fun interface Tones { fun tone(n: Int, key: Int, amp: Double, start: Int, soft: Boolean): FloatArray }

    private val sine = Tones { n, key, amp, start, _ -> DspTestUtil.harmonicTone(n, key, amp, start) }

    /**
     * The −28 dB target is calibrated on the real regions ([realRegions]). The SineBank recipe's C4 has its
     * second partial (the C5 comb's feed) 6 dB below the fundamental where the real C4 has it 8–9 dB
     * above, so with the same trims the harmonic proxy sits ≈ 7 dB lower: −35 ± 4 dB (deviation 13).
     */
    @Test fun target1PedalDownC4StruckC5CombAtMinus28() = target1(sine, "SineBank", -35.0)

    private fun target1(src: Tones, tag: String, expected: Double = -28.0) {
        val rig = CombRig(mode = ResonanceMode.NATURAL)
        java.util.Arrays.fill(rig.damping, 0f); for (k in 21..108) rig.gate[k] = 1f
        val c4 = src.tone(2 * sr, 60, 0.5, 0, false)
        rig.voices += CombRig.Voice(60, c4, false)
        var ms = 0.0; var cnt = 0
        val b0 = blockOf(0.5); val b1 = blockOf(1.5)
        rig.run(b1, sink = { bi -> if (bi >= b0) { ms += 2 * rig.lanes[72 - 21]; cnt++ } })
        val comb = sqrt(ms / cnt)
        val voice = DspTestUtil.rms(c4, b0 * HK.BLOCK, b1 * HK.BLOCK)
        val rel = db(comb / voice)
        println("T3.1 target 1 ($tag): C5 comb re C4 voice = %.1f dB".format(rel))
        assertEquals(expected, rel, 4.0)
    }

    @Test fun target2UnaCordaC4CombAtMinus12() = target2(sine, "SineBank")

    private fun target2(src: Tones, tag: String) {
        val rig = CombRig(mode = ResonanceMode.NATURAL)
        rig.gate[60] = 1f; rig.damping[60] = 0f; rig.softFeed[60] = true
        val c4 = src.tone(3 * sr + HK.BLOCK, 60, 0.4, 0, true)
        rig.voices += CombRig.Voice(60, c4, true)
        var ms = 0.0; var cnt = 0
        val b0 = blockOf(1.0); val b1 = blockOf(3.0)
        rig.run(b1, sink = { bi -> if (bi >= b0) { ms += 2 * rig.lanes[60 - 21]; cnt++ } })
        val rel = db(sqrt(ms / cnt) / DspTestUtil.rms(c4, b0 * HK.BLOCK, b1 * HK.BLOCK))
        println("T3.1 target 2 ($tag): una corda C4 comb re dry = %.1f dB".format(rel))
        assertEquals(-12.0, rel, 4.0)
    }

    @Test fun target3HeldC3RingsWhenC4IsStruck() = target3(sine, "SineBank", (1.2 * 24 / 8.686 * HK.SR).toInt())

    private fun target3(src: Tones, tag: String, start: Int) {
        val rig = CombRig(mode = ResonanceMode.NATURAL)
        // Pedal up: every damper down except the held C3 and the struck C4.
        rig.gate[48] = 1f; rig.damping[48] = 0f; rig.gate[60] = 1f; rig.damping[60] = 0f
        val n = start + 2 * sr
        rig.voices += CombRig.Voice(48, src.tone(n, 48, 0.5, 0, false), false)
        val c4 = src.tone(n, 60, 0.5, start, false)
        rig.voices += CombRig.Voice(60, c4, false)
        var combPeak = 0.0
        rig.run(n / HK.BLOCK, sink = { bi -> if (bi * HK.BLOCK >= start) combPeak = maxOf(combPeak, sqrt(2.0 * rig.lanes[48 - 21])) })
        var c4Peak = 0.0
        for (b in start / HK.BLOCK until n / HK.BLOCK) c4Peak = maxOf(c4Peak, DspTestUtil.rms(c4, b * HK.BLOCK, (b + 1) * HK.BLOCK))
        val rel = db(combPeak / c4Peak)
        println("T3.1 target 3 ($tag): C3 comb re C4 peak = %.1f dB".format(rel))
        assertTrue("rel $rel", rel >= -40.0)
    }

    @Test fun target4C3AloneStaysBelowAndDecaysMonotonically() = target4(sine, "SineBank", 10 * HK.SR)

    /** [n] frames: 10 s on SineBank; the real regions are 4 s long, so the check stops where the recording does. */
    private fun target4(src: Tones, tag: String, n: Int) {
        for (pedal in listOf(false, true)) {
            val rig = CombRig(mode = ResonanceMode.RICH)
            if (pedal) { java.util.Arrays.fill(rig.damping, 0f); for (k in 21..108) rig.gate[k] = 1f }
            else { rig.gate[48] = 1f; rig.damping[48] = 0f }
            val c3 = src.tone(n, 48, 0.5, 0, false)
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
                assertTrue("$tag pedal=$pedal w=$w C3 comb ${db(comb / voice)} dB", comb == 0.0 || db(comb / voice) <= -30.0)
                val e = sqrt(sum[w] / win)
                if (w >= 1) assertTrue("$tag pedal=$pedal window $w rises: ${db(e)} after ${db(prev)}", e <= prev * 1.0001)
                prev = e
            }
        }
    }

    /**
     * Targets 1–4 on the real decoded Salamander regions (`export_test_regions.py`, §6.5 step 13): C3, C4, C5
     * at v10 and v13 and the una corda C4 (the v8 region the soft bus acts on). The directory is
     * `src/test/resources/wp11/real/`, or `$HK_REAL_REGIONS` when WP11 has not committed it; the test
     * is skipped (assumption) only when neither exists.
     */
    @Test fun realRegions() {
        val env = System.getenv("HK_REAL_REGIONS")
        val dir = listOfNotNull(env?.let { java.io.File(it) }, java.io.File("src/test/resources/wp11/real")).firstOrNull { java.io.File(it, "c4_v13.wav").isFile }
        assumeTrue("no real regions (wp11/real or HK_REAL_REGIONS)", dir != null)
        for (layer in listOf("v10", "v13")) {
            val src = Tones { n, key, amp, start, soft ->
                val name = if (soft) "c4_unacorda_v8" else "${NAMES.getValue(key)}_$layer"
                val pcm = readWav16(java.io.File(dir, "$name.wav"))
                val peak = pcm.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-9f)
                val g = (amp / peak).toFloat()
                FloatArray(n) { i -> val j = i - start; if (j in pcm.indices) pcm[j] * g else 0f }
            }
            val tag = "real $layer"
            target1(src, tag); target2(src, tag); target3(src, tag, 2 * sr); target4(src, tag, 4 * sr)
        }
    }

    private companion object {
        val NAMES = mapOf(48 to "c3", 60 to "c4", 72 to "c5")

        /** 16-bit mono PCM WAV → floats (walks the RIFF chunks to "data"). */
        fun readWav16(f: java.io.File): FloatArray {
            val b = java.nio.ByteBuffer.wrap(f.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            var p = 12
            while (p + 8 <= b.limit()) {
                val id = String(ByteArray(4) { b.get(p + it) }, Charsets.US_ASCII); val len = b.getInt(p + 4)
                if (id == "data") return FloatArray(len / 2) { b.getShort(p + 8 + 2 * it) / 32768f }
                p += 8 + len + (len and 1)
            }
            error("no data chunk in $f")
        }
    }
}
