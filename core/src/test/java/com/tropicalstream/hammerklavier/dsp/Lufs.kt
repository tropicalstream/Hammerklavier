package com.tropicalstream.hammerklavier.dsp

import kotlin.math.log10
import kotlin.math.max

/**
 * ITU-R BS.1770-4 loudness at 48 kHz (test helper): K-weighting (the standard's two biquads),
 * 400 ms blocks with 75 % overlap, absolute gate −70 LUFS, relative gate −10 LU. [integrated]
 * is the programme loudness; [momentary] the ungated 400 ms loudness per 100 ms hop.
 */
object Lufs {
    private val B1 = doubleArrayOf(1.53512485958697, -2.69169618940638, 1.19839281085285)
    private val A1 = doubleArrayOf(-1.69065929318241, 0.73248077421585)
    private val B2 = doubleArrayOf(1.0, -2.0, 1.0)
    private val A2 = doubleArrayOf(-1.99004745483398, 0.99007225036621)

    private fun biquad(x: DoubleArray, b: DoubleArray, a: DoubleArray): DoubleArray {
        val y = DoubleArray(x.size); var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in x.indices) {
            val v = b[0] * x[i] + b[1] * x1 + b[2] * x2 - a[0] * y1 - a[1] * y2
            x2 = x1; x1 = x[i]; y2 = y1; y1 = v; y[i] = v
        }
        return y
    }

    /** K-weighted squares summed over the channels of an interleaved stereo signal. */
    private fun kSquares(interleaved: FloatArray): DoubleArray {
        val n = interleaved.size / 2
        val out = DoubleArray(n)
        for (c in 0..1) {
            val x = DoubleArray(n) { interleaved[2 * it + c].toDouble() }
            val y = biquad(biquad(x, B1, A1), B2, A2)
            for (i in 0 until n) out[i] += y[i] * y[i]
        }
        return out
    }

    private fun blockPowers(sq: DoubleArray, fs: Int = 48_000, blockMs: Int = 400, hopMs: Int = 100): DoubleArray {
        val len = fs * blockMs / 1000; val hop = fs * hopMs / 1000
        if (sq.size < len) return doubleArrayOf()
        val pre = DoubleArray(sq.size + 1); for (i in sq.indices) pre[i + 1] = pre[i] + sq[i]
        val count = (sq.size - len) / hop + 1
        return DoubleArray(count) { (pre[it * hop + len] - pre[it * hop]) / len }
    }

    private fun lk(p: Double): Double = -0.691 + 10 * log10(max(p, 1e-30))

    fun integrated(interleaved: FloatArray): Double {
        val z = blockPowers(kSquares(interleaved))
        val abs = z.filter { lk(it) > -70.0 }
        if (abs.isEmpty()) return -120.0
        val rel = lk(abs.average()) - 10.0
        val g = abs.filter { lk(it) > rel }
        return lk(g.average())
    }

    /** Momentary loudness (400 ms window) every 100 ms. */
    fun momentary(interleaved: FloatArray): DoubleArray = blockPowers(kSquares(interleaved)).map { lk(it) }.toDoubleArray()
}
