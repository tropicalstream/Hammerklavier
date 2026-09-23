package com.tropicalstream.hammerklavier.dsp

import com.tropicalstream.hammerklavier.contract.HK
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Late reverb (PLAN §3.12): an 8-line feedback delay network with a fast 8×8 Hadamard (× 1/√8),
 * mutually prime lines of 853 … 2521 frames, per-line 3-band loss (Jot): a mid gain
 * 10^(−3·d/(fs·t60Mid)), a complementary low shelf toward `t60Low` (one-pole split at 125 Hz) and
 * a one-pole low-pass that meets `t60High` at 8 kHz. Lines 2 and 5 are modulated ±12 frames at
 * 0.31 and 0.47 Hz (table sine; the fractional read is a first-order allpass interpolator, not the plan's linear one, because linear interpolation loses up to 3 dB per pass at high frequencies and biased T60 and the energy normalisation). Input spread with alternating signs;
 * output lines 0/2/4/6 → L, 1/3/5/7 → R with alternating signs. Energy-normalised: a steady input
 * of RMS x gives an output of RMS ≈ x per channel. [setLines] (4) runs lines 0–3 with a 4×4
 * Hadamard (Q3). [setT60] uses only tables and sqrt (audio-thread safe). Allocation-free.
 */
class FdnReverb(sampleRate: Int = HK.SR) {
    private val fs = sampleRate.toFloat()
    private val buf = FloatArray(LINES * LEN)
    private var pos = 0
    private var lines = LINES
    private val apY = FloatArray(LINES)       // allpass-interpolator state of the modulated lines

    private val gMid = FloatArray(LINES)
    private val gLow = FloatArray(LINES)
    private val hiA = FloatArray(LINES)
    private val tMid = FloatArray(LINES)      // glide targets of gMid / gLow / hiA / norm
    private val tLow = FloatArray(LINES)
    private val tHi = FloatArray(LINES)
    private var tNorm = 0f
    private var glideLeft = 0
    private val loS = FloatArray(LINES)       // low-split one-pole state
    private val hiS = FloatArray(LINES)       // high-loss one-pole state
    private val r = FloatArray(LINES)
    private var norm = 0f

    private val splitA = OnePole.coefFor(SPLIT_HZ, sampleRate.toDouble())
    private val cosHigh = cos(2 * PI * HIGH_HZ / sampleRate).toFloat()
    private val cosMid = cos(2 * PI * MID_HZ / sampleRate).toFloat()
    private var ph2 = 0f; private var ph5 = 0f
    private val inc2 = (2 * PI * 0.31 / sampleRate).toFloat()
    private val inc5 = (2 * PI * 0.47 / sampleRate).toFloat()

    init { setT60(1.65f, 1.65f, 1.0f) }

    /**
     * Per-line gains and filters for the three band T60s (s). With [glideFrames] > 0 the loop
     * gains and filters glide linearly (per block) to the new values, so a mode or venue change
     * does not step the ringing tail. Call off the audio thread or between blocks.
     */
    fun setT60(t60Low: Float, t60Mid: Float, t60High: Float, glideFrames: Int = 0) {
        var sumLoss = 0f
        for (i in 0 until LINES) {
            val d = DELAYS[i].toFloat()
            val gm0 = DspTables.dbToLin(-60f * d / (fs * t60Mid.coerceAtLeast(0.05f)))
            val gl = DspTables.dbToLin(-60f * d / (fs * t60Low.coerceAtLeast(0.05f)))
            val gh = DspTables.dbToLin(-60f * d / (fs * t60High.coerceAtLeast(0.05f)))
            // Solve the high-loss one-pole so that |H(8k)|·gMid' = gh, where gMid' = gm0 / |H(mid)|
            // compensates the low-pass's small loss in the mid band (two passes converge).
            var a = 0f; var gm = gm0
            for (pass in 0 until 2) {
                val m = (gh / gm).coerceAtMost(0.9999f)
                a = if (m >= 0.9999f) 0f else solveOnePole(m, cosHigh)
                gm = (gm0 / onePoleMag(a, cosMid)).coerceAtMost(0.99999f)
            }
            tMid[i] = gm; tLow[i] = gl; tHi[i] = a
            if (i < lines) sumLoss += 1f - gm * gm
        }
        tNorm = sqrt(sumLoss / (lines / 2))
        if (glideFrames <= 0) {
            System.arraycopy(tMid, 0, gMid, 0, LINES); System.arraycopy(tLow, 0, gLow, 0, LINES)
            System.arraycopy(tHi, 0, hiA, 0, LINES); norm = tNorm; glideLeft = 0
        } else glideLeft = glideFrames
    }

    /** Advances the T60 glide by one block of [n] frames. */
    private fun stepGlide(n: Int) {
        val f = if (n >= glideLeft) 1f else n.toFloat() / glideLeft
        for (i in 0 until LINES) {
            gMid[i] += f * (tMid[i] - gMid[i]); gLow[i] += f * (tLow[i] - gLow[i]); hiA[i] += f * (tHi[i] - hiA[i])
        }
        norm += f * (tNorm - norm)
        glideLeft = if (n >= glideLeft) 0 else glideLeft - n
    }

    fun setLines(n: Int) {
        val nl = if (n <= 4) 4 else 8
        if (nl == lines) return
        lines = nl
        for (i in 4 until LINES) { java.util.Arrays.fill(buf, i * LEN, (i + 1) * LEN, 0f); loS[i] = 0f; hiS[i] = 0f }
        var sumLoss = 0f
        var tLoss = 0f
        for (i in 0 until lines) { sumLoss += 1f - gMid[i] * gMid[i]; tLoss += 1f - tMid[i] * tMid[i] }
        norm = sqrt(sumLoss / (lines / 2)); tNorm = sqrt(tLoss / (lines / 2))
    }

    val lineCount: Int get() = lines

    fun reset() {
        java.util.Arrays.fill(buf, 0f); java.util.Arrays.fill(loS, 0f); java.util.Arrays.fill(hiS, 0f); java.util.Arrays.fill(apY, 0f)
    }

    /** Adds the reverb of [input] × [inGain] (per frame) into [outL]/[outR]. */
    fun process(input: FloatArray, inGain: FloatArray, outL: FloatArray, outR: FloatArray, n: Int) {
        if (glideLeft > 0) stepGlide(n)
        val nl = lines
        val b = if (nl == 8) INV_SQRT8 else 0.5f
        val hScale = if (nl == 8) INV_SQRT8 else 0.5f
        val sa = splitA
        val nm = norm
        var p = pos
        for (i in 0 until n) {
            // Read the line outputs.
            for (k in 0 until nl) {
                if (k == 2 || k == 5) {
                    // First-order allpass interpolation (flat magnitude, so the modulation adds no loss).
                    val ph = if (k == 2) ph2 else ph5
                    val dd = DELAYS[k] + MOD_DEPTH * DspTables.sinT(ph)
                    val mi = (dd - 0.5f).toInt(); val d = dd - mi
                    val eta = (1f - d) / (1f + d)
                    val base = k * LEN
                    val x0 = buf[base + ((p - mi) and MASK)]; val x1 = buf[base + ((p - mi - 1) and MASK)]
                    val y = eta * (x0 - apY[k]) + x1
                    apY[k] = y
                    r[k] = y
                } else {
                    r[k] = buf[k * LEN + ((p - DELAYS[k]) and MASK)]
                }
            }
            if (nl == 8) {
                outL[i] += nm * (r[0] - r[2] + r[4] - r[6])
                outR[i] += nm * (r[1] - r[3] + r[5] - r[7])
            } else {
                outL[i] += nm * (r[0] - r[2])
                outR[i] += nm * (r[1] - r[3])
            }
            // Loss filters.
            for (k in 0 until nl) {
                val y = r[k]
                val lo = y + sa * (loS[k] - y); loS[k] = lo
                val f = gMid[k] * (y - lo) + gLow[k] * lo
                val a = hiA[k]
                val h = f + a * (hiS[k] - f); hiS[k] = h
                r[k] = h
            }
            // Hadamard.
            if (nl == 8) {
                val a0 = r[0] + r[1]; val a1 = r[0] - r[1]; val a2 = r[2] + r[3]; val a3 = r[2] - r[3]
                val a4 = r[4] + r[5]; val a5 = r[4] - r[5]; val a6 = r[6] + r[7]; val a7 = r[6] - r[7]
                val c0 = a0 + a2; val c2 = a0 - a2; val c1 = a1 + a3; val c3 = a1 - a3
                val c4 = a4 + a6; val c6 = a4 - a6; val c5 = a5 + a7; val c7 = a5 - a7
                r[0] = c0 + c4; r[4] = c0 - c4; r[1] = c1 + c5; r[5] = c1 - c5
                r[2] = c2 + c6; r[6] = c2 - c6; r[3] = c3 + c7; r[7] = c3 - c7
            } else {
                val a0 = r[0] + r[1]; val a1 = r[0] - r[1]; val a2 = r[2] + r[3]; val a3 = r[2] - r[3]
                r[0] = a0 + a2; r[2] = a0 - a2; r[1] = a1 + a3; r[3] = a1 - a3
            }
            val u = input[i] * inGain[i] * b
            for (k in 0 until nl) {
                val sgn = if ((k and 1) == 0) u else -u
                buf[k * LEN + (p and MASK)] = r[k] * hScale + sgn
            }
            p++
            ph2 += inc2; ph5 += inc5
        }
        pos = p and MASK
        val twoPi = (2 * PI).toFloat()
        if (ph2 > twoPi) ph2 -= twoPi
        if (ph5 > twoPi) ph5 -= twoPi
        for (k in 0 until nl) {
            if (loS[k] > -1e-20f && loS[k] < 1e-20f) loS[k] = 0f
            if (hiS[k] > -1e-20f && hiS[k] < 1e-20f) hiS[k] = 0f
        }
    }

    companion object {
        const val LINES = 8
        const val LEN = 4096
        const val MASK = LEN - 1
        @JvmField val DELAYS = intArrayOf(853, 1031, 1277, 1471, 1693, 1951, 2203, 2521)
        const val MOD_DEPTH = 12f
        const val SPLIT_HZ = 125.0
        const val HIGH_HZ = 8000.0
        const val MID_HZ = 707.0
        private const val INV_SQRT8 = 0.35355339f

        /** |(1 − a)/(1 − a·e^{−jω})| from cos ω. */
        fun onePoleMag(a: Float, cosW: Float): Float = (1f - a) / sqrt(1f - 2f * a * cosW + a * a)

        /** The one-pole coefficient a ∈ [0, 1) with |H(ω)| = m (0 < m < 1). */
        fun solveOnePole(m: Float, cosW: Float): Float {
            val m2 = m * m
            val qa = m2 - 1f; val qb = 2f - 2f * m2 * cosW
            val disc = (qb * qb - 4f * qa * qa).coerceAtLeast(0f)
            val r1 = (-qb + sqrt(disc)) / (2f * qa); val r2 = (-qb - sqrt(disc)) / (2f * qa)
            val a = if (r1 in 0f..1f) r1 else r2
            return a.coerceIn(0f, 0.999f)
        }
    }
}
