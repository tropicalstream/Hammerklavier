package com.tropicalstream.hammerklavier.mech

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.MechanismPose

/**
 * String vibration amplitude (PLAN §5.7). From the EnergyRing's linear-RMS lanes:
 * `amp = clamp((20·log10(e) + 60)/60, 0, 1)` (−60 … 0 dBFS → 0 … 1), with log10 from a 256-entry
 * mantissa table (no `log` on GLThread). Without a matching slot (energy == null) an analytic
 * two-stage envelope per key, `A0(v)·(0.8·e^(−3t/τ) + 0.2·e^(−t/τ))`, τ = T60f/6.91, damped by
 * `e^(−6.91·dt·D/T60d)` with D = 1 − damper lift, integrated per frame with an exp table; its linear
 * RMS goes through the same dB mapping. [reset] on reseed. Also the ribbon helpers WP7 draws with.
 */
class StringVisual {
    private val a1 = FloatArray(128)
    private val a2 = FloatArray(128)
    // Per-instrument decay rates (1/s), prepared once.
    private val k1 = Array(3) { FloatArray(128) }
    private val k2 = Array(3) { FloatArray(128) }
    private val kd = Array(3) { FloatArray(128) }
    private var inst = 0

    init {
        for (id in InstrumentId.values()) {
            val p = InstrumentProfile.of(id)
            val i = id.ordinal
            for (k in 0 until 128) {
                val t60f = p.defaultFreeT60(0, k).coerceAtLeast(0.05f)
                val t60d = p.defaultDamperT60(k).coerceAtLeast(0.02f)
                k1[i][k] = 3f * 6.91f / t60f
                k2[i][k] = 6.91f / t60f
                kd[i][k] = 6.91f / t60d
            }
        }
    }

    fun bind(profile: InstrumentProfile) { inst = profile.id.ordinal; reset() }

    fun reset() { a1.fill(0f); a2.fill(0f) }

    /** A drawn contact or pluck of key [k] at velocity [vel] (starts the analytic envelope). */
    fun strike(k: Int, vel: Int) {
        val a0 = Touch.envelopeStart(vel)
        a1[k] = 0.8f * a0; a2[k] = 0.2f * a0
    }

    /** Fills pose.stringAmp from [energy] (88 lanes) or the analytic envelope; uses pose.damper. */
    fun update(energy: FloatArray?, dtSec: Float, lowKey: Int, highKey: Int, lastDamper: Int, out: MechanismPose) {
        val dt = if (dtSec < 0f) 0f else if (dtSec > 0.2f) 0.2f else dtSec
        val r1 = k1[inst]; val r2 = k2[inst]; val rd = kd[inst]
        for (k in 0 until HK.KEYS) {
            // The analytic state always integrates, so the fallback is continuous when slots go missing.
            val d = if (k <= lastDamper) 1f - out.damper[k] else 0f
            val damp = expNeg(dt * d * rd[k])
            a1[k] *= expNeg(dt * r1[k]) * damp
            a2[k] *= expNeg(dt * r2[k]) * damp
            if (k < lowKey || k > highKey) { out.stringAmp[k] = 0f; continue }
            val lane = k - 21
            out.stringAmp[k] = if (energy != null) {
                if (lane in 0 until HK.LANES) ampOf(energy[lane]) else 0f
            } else ampOf(a1[k] + a2[k])
        }
    }

    companion object {
        private const val LOG_N = 256
        private val LOG2_MANT = FloatArray(LOG_N) { (Math.log(1.0 + (it + 0.5) / LOG_N) / Math.log(2.0)).toFloat() }
        private const val EXP_N = 2048
        private const val EXP_MAX = 32f
        private val EXP_TAB = FloatArray(EXP_N + 1) { Math.exp(-(it * EXP_MAX / EXP_N).toDouble()).toFloat() }

        /** log2(x) for x > 0 from the float's exponent and a mantissa table (error < 0.003). */
        fun log2(x: Float): Float {
            val bits = x.toRawBits()
            val e = ((bits ushr 23) and 0xFF) - 127
            val m = (bits ushr 15) and 0xFF
            return e + LOG2_MANT[m]
        }

        /** e^(−x) for x ≥ 0 from a table (0 beyond 32). */
        fun expNeg(x: Float): Float {
            if (x <= 0f) return 1f
            if (x >= EXP_MAX) return 0f
            val f = x * (EXP_N / EXP_MAX)
            val i = f.toInt()
            val w = f - i
            return EXP_TAB[i] + (EXP_TAB[i + 1] - EXP_TAB[i]) * w
        }

        /** Linear RMS re full scale → 0..1 (−60 … 0 dBFS). */
        fun ampOf(e: Float): Float {
            if (!(e > 0f)) return 0f
            val db = 6.0206f * log2(e)           // 20·log10(e)
            return ((db + 60f) / 60f).coerceIn(0f, 1f)
        }

        /** Ribbon half-width in px: max(0.6, 0.6 + amp·W); W = 2.5 cutaway, 3.5 overhead, 1.5 Hall. */
        fun halfWidthPx(amp: Float, widthPx: Float): Float = maxOf(0.6f, 0.6f + amp * widthPx)

        /** Ribbon alpha: min(1, 1.2 px / halfWidth). */
        fun alpha(halfWidthPx: Float): Float = minOf(1f, 1.2f / halfWidthPx)

        /** Single-mode wobble, 2–12 Hz: lower for longer (lower) strings, detuned per unison string. */
        fun wobbleHz(key: Int, unisonIndex: Int): Float =
            2f + 10f * ((key - 21).coerceIn(0, 87) / 87f) + 0.13f * unisonIndex

        const val STRIKE_PULSE_S = 0.15f
        const val W_CUTAWAY = 2.5f; const val W_OVERHEAD = 3.5f; const val W_HALL = 1.5f
    }
}
