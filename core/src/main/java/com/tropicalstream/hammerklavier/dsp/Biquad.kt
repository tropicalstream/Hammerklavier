package com.tropicalstream.hammerklavier.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * RBJ "Audio EQ Cookbook" biquad coefficients (normalised, a0 = 1), designed off the audio thread.
 * Immutable; one set may be shared by several [Biquad] states.
 */
class BiquadCoefs(@JvmField val b0: Float, @JvmField val b1: Float, @JvmField val b2: Float,
                  @JvmField val a1: Float, @JvmField val a2: Float) {

    /** |H(e^{jω})| in dB at [hz] (double precision; tests and preparation only). */
    fun magnitudeDb(hz: Double, fs: Double): Double {
        val w = 2 * PI * hz / fs
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = b0 + b1 * c1 + b2 * c2; val ni = -(b1 * s1 + b2 * s2)
        val dr = 1 + a1 * c1 + a2 * c2; val di = -(a1 * s1 + a2 * s2)
        return 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
    }

    companion object {
        val IDENTITY = BiquadCoefs(1f, 0f, 0f, 0f, 0f)
        const val BUTTERWORTH_Q = 0.70710678

        private fun make(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
            BiquadCoefs((b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(), (a1 / a0).toFloat(), (a2 / a0).toFloat())

        fun lowPass(hz: Double, q: Double, fs: Double): BiquadCoefs {
            val w = 2 * PI * hz / fs; val c = cos(w); val al = sin(w) / (2 * q)
            return make((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al)
        }

        fun highPass(hz: Double, q: Double, fs: Double): BiquadCoefs {
            val w = 2 * PI * hz / fs; val c = cos(w); val al = sin(w) / (2 * q)
            return make((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al)
        }

        /** Band-pass with 0 dB peak gain. */
        fun bandPass(hz: Double, q: Double, fs: Double): BiquadCoefs {
            val w = 2 * PI * hz / fs; val c = cos(w); val al = sin(w) / (2 * q)
            return make(al, 0.0, -al, 1 + al, -2 * c, 1 - al)
        }

        fun peaking(hz: Double, q: Double, gainDb: Double, fs: Double): BiquadCoefs {
            val a = 10.0.pow(gainDb / 40); val w = 2 * PI * hz / fs; val c = cos(w); val al = sin(w) / (2 * q)
            return make(1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a)
        }

        /** Shelf slope S = 1 (the cookbook's steepest monotone shelf). */
        fun lowShelf(hz: Double, gainDb: Double, fs: Double, slope: Double = 1.0): BiquadCoefs {
            val a = 10.0.pow(gainDb / 40); val w = 2 * PI * hz / fs; val c = cos(w); val s = sin(w)
            val al = s / 2 * sqrt((a + 1 / a) * (1 / slope - 1) + 2); val sa = 2 * sqrt(a) * al
            return make(a * ((a + 1) - (a - 1) * c + sa), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - sa),
                (a + 1) + (a - 1) * c + sa, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - sa)
        }

        fun highShelf(hz: Double, gainDb: Double, fs: Double, slope: Double = 1.0): BiquadCoefs {
            val a = 10.0.pow(gainDb / 40); val w = 2 * PI * hz / fs; val c = cos(w); val s = sin(w)
            val al = s / 2 * sqrt((a + 1 / a) * (1 / slope - 1) + 2); val sa = 2 * sqrt(a) * al
            return make(a * ((a + 1) + (a - 1) * c + sa), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - sa),
                (a + 1) - (a - 1) * c + sa, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - sa)
        }

        /** First-order high-pass (bilinear, prewarped) as a biquad with b2 = a2 = 0. */
        fun highPass1(hz: Double, fs: Double): BiquadCoefs {
            val k = kotlin.math.tan(PI * hz / fs)
            val a0 = 1 + k
            return make(1.0, -1.0, 0.0, a0, k - 1, 0.0)
        }
    }
}

/** One channel of biquad state (transposed direct form II). Allocation-free. */
class Biquad(@JvmField var c: BiquadCoefs = BiquadCoefs.IDENTITY) {
    private var z1 = 0f
    private var z2 = 0f

    fun reset() { z1 = 0f; z2 = 0f }

    fun tick(x: Float): Float {
        val k = c
        val y = k.b0 * x + z1
        z1 = k.b1 * x - k.a1 * y + z2
        z2 = k.b2 * x - k.a2 * y
        return y
    }

    fun process(buf: FloatArray, n: Int) {
        val k = c
        val b0 = k.b0; val b1 = k.b1; val b2 = k.b2; val a1 = k.a1; val a2 = k.a2
        var s1 = z1; var s2 = z2
        for (i in 0 until n) {
            val x = buf[i]
            val y = b0 * x + s1
            s1 = b1 * x - a1 * y + s2
            s2 = b2 * x - a2 * y
            buf[i] = y
        }
        // Flush denormals so a decayed filter costs nothing on ARM without FTZ.
        z1 = if (s1 > -1e-20f && s1 < 1e-20f) 0f else s1
        z2 = if (s2 > -1e-20f && s2 < 1e-20f) 0f else s2
    }
}
