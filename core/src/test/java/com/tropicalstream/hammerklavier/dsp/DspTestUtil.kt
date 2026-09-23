package com.tropicalstream.hammerklavier.dsp

import com.tropicalstream.hammerklavier.contract.HK
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** WP3's private test helpers (signals, spectra, levels). */
object DspTestUtil {
    const val FS = HK.SR

    fun db(x: Double): Double = 20 * log10(x.coerceAtLeast(1e-30))

    fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0; for (i in from until to) s += x[i].toDouble() * x[i]; return sqrt(s / (to - from).coerceAtLeast(1))
    }

    fun peakAbs(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var p = 0.0; for (i in from until to) p = maxOf(p, kotlin.math.abs(x[i].toDouble())); return p
    }

    /** White noise, uniform, given RMS. */
    fun noise(n: Int, rms: Double, seed: Long = 1): FloatArray {
        val r = java.util.Random(seed); val a = rms * sqrt(3.0)
        return FloatArray(n) { ((r.nextDouble() * 2 - 1) * a).toFloat() }
    }

    fun sine(n: Int, hz: Double, amp: Double): FloatArray = FloatArray(n) { (amp * sin(2 * PI * hz * it / FS)).toFloat() }

    /**
     * The SineBank recipe (contract stub): 8 harmonic partials with 1/n amplitudes below 20 kHz, an
     * exponential decay with a 1.2 s time constant, starting at [start]; any length.
     */
    fun harmonicTone(n: Int, key: Int, amp: Double, start: Int = 0, tau: Double = 1.2, f0: Double = 440.0 * 2.0.pow((key - 69) / 12.0)): FloatArray {
        val x = DoubleArray(n)
        val decay = exp(-1.0 / (tau * FS))
        for (p in 1..8) {
            val f = p * f0; if (f >= 20000) break
            val w = 2 * PI * f / FS; val cw = cos(w); val sw = sin(w)
            var c = 1.0; var s = 0.0; var e = 1.0 / p
            for (t in start until n) { x[t] += e * s; val c2 = c * cw - s * sw; s = s * cw + c * sw; c = c2; e *= decay }
        }
        var peak = 0.0; for (v in x) peak = maxOf(peak, kotlin.math.abs(v))
        return FloatArray(n) { (x[it] / peak * amp).toFloat() }
    }

    /** |DFT| of x (Hann window) at hz. */
    fun dftMag(x: FloatArray, hz: Double, from: Int = 0, to: Int = x.size): Double {
        val w = 2 * PI * hz / FS; val cw = cos(w); val sw = sin(w)
        var c = 1.0; var s = 0.0; var re = 0.0; var im = 0.0
        val len = to - from
        for (i in 0 until len) {
            val win = 0.5 - 0.5 * cos(2 * PI * i / (len - 1))
            val v = x[from + i] * win
            re += v * c; im -= v * s
            val c2 = c * cw - s * sw; s = s * cw + c * sw; c = c2
        }
        return sqrt(re * re + im * im)
    }

    /** Frequency of the spectral peak within ±[spanCents] of [hz] (golden search on the Hann DFT). */
    fun peakNear(x: FloatArray, hz: Double, spanCents: Double = 30.0): Double {
        // Coarse scan, then golden refinement.
        var best = hz; var bm = -1.0
        var c = -spanCents
        while (c <= spanCents) { val f = hz * 2.0.pow(c / 1200); val m = dftMag(x, f); if (m > bm) { bm = m; best = f }; c += 1.0 }
        var lo = best * 2.0.pow(-1.0 / 1200); var hi = best * 2.0.pow(1.0 / 1200)
        repeat(30) {
            val m1 = lo + (hi - lo) * 0.382; val m2 = lo + (hi - lo) * 0.618
            if (dftMag(x, m1) > dftMag(x, m2)) hi = m2 else lo = m1
        }
        return 0.5 * (lo + hi)
    }

    fun cents(f: Double, ref: Double): Double = 1200 * kotlin.math.ln(f / ref) / kotlin.math.ln(2.0)

    /** In-place radix-2 FFT (re, im), n a power of two. */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len; val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k]; val ai = im[i + k]
                    val br = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val bi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ar + br; im[i + k] = ai + bi
                    re[i + k + len / 2] = ar - br; im[i + k + len / 2] = ai - bi
                    val t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Schroeder T60 (s) of an impulse response by the −5 … −25 dB slope (T20 × 3). */
    fun schroederT60(ir: FloatArray): Double {
        val e = DoubleArray(ir.size)
        var acc = 0.0
        for (i in ir.indices.reversed()) { acc += ir[i].toDouble() * ir[i]; e[i] = acc }
        val e0 = e[0]
        var i5 = -1; var i25 = -1
        for (i in e.indices) {
            val d = 10 * log10(e[i] / e0)
            if (i5 < 0 && d <= -5) i5 = i
            if (i25 < 0 && d <= -25) { i25 = i; break }
        }
        // Least-squares slope over [i5, i25].
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0; var n = 0
        for (i in i5..i25) { val x = i.toDouble() / FS; val y = 10 * log10(e[i] / e0); sx += x; sy += y; sxx += x * x; sxy += x * y; n++ }
        val slope = (n * sxy - sx * sy) / (n * sxx - sx * sx)
        return -60.0 / slope
    }

    /** Filters x through a chain of biquads (fresh state), returning a new array. */
    fun filter(x: FloatArray, vararg c: BiquadCoefs): FloatArray {
        val y = x.copyOf()
        for (k in c) Biquad(k).process(y, y.size)
        return y
    }
}
