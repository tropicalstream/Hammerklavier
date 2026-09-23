package com.tropicalstream.hammerklavier.dsp

import kotlin.math.PI
import kotlin.math.exp

/**
 * One-pole low-pass y = (1 − a)·x + a·y₁ with a = exp(−2π·fc/fs). The coefficient is set with
 * [setHz] (table, audio-thread safe) or [coefFor] (exact, preparation time). Allocation-free.
 */
class OnePole(@JvmField var a: Float = 0f) {
    @JvmField var y1 = 0f

    fun setHz(hz: Float, fs: Float) { a = DspTables.onePoleCoef(hz, fs) }
    fun reset() { y1 = 0f }
    fun tick(x: Float): Float { val y = x + a * (y1 - x); y1 = y; return y }

    fun process(buf: FloatArray, n: Int) {
        val k = a
        var s = y1
        for (i in 0 until n) { s = buf[i] + k * (s - buf[i]); buf[i] = s }
        y1 = if (s > -1e-20f && s < 1e-20f) 0f else s
    }

    companion object {
        fun coefFor(hz: Double, fs: Double): Float = exp(-2 * PI * hz / fs).toFloat()
    }
}

/** DC blocker y = x − x₁ + R·y₁ (R ≈ 0.995 at 48 kHz, ≈ 38 Hz corner). Allocation-free. */
class DcBlocker(@JvmField val r: Float = 0.995f) {
    private var x1 = 0f
    private var y1 = 0f
    fun reset() { x1 = 0f; y1 = 0f }
    fun tick(x: Float): Float { val y = x - x1 + r * y1; x1 = x; y1 = if (y > -1e-20f && y < 1e-20f) 0f else y; return y }
}
