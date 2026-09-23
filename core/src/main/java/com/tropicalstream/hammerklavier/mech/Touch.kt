package com.tropicalstream.hammerklavier.mech

import com.tropicalstream.hammerklavier.contract.HarpsiTiming

/**
 * Goebl/Askenfelt piano touch fits (PLAN §5.7, [R:mechanics §1.1–1.2]) as 128-entry tables,
 * prepared once when the class loads (all `pow` happens here, never on GLThread).
 *
 * - `HV = clamp(10^((v − 57.96)/71.3), 0.25, 7)` m/s, `s = smoothstep(2.0, 4.5, HV)`
 * - `tt` key start → contact of an isolated stroke, clamped 20…230 ms (× travelScale by the caller)
 * - `tb` key bed relative to contact (ms, negative = before contact)
 * - `ff` free flight (ms, ≤ 20)
 * - `c(n) = 4·0.2^((n−21)/87)` ms hammer–string contact per key
 * - `A0(v)`: the analytic string envelope's starting linear RMS, `10^((−40 + 34·v/127)/20)`
 */
object Touch {
    @JvmField val HV = FloatArray(128)
    @JvmField val S = FloatArray(128)
    @JvmField val TT_MS = FloatArray(128)
    @JvmField val TB_MS = FloatArray(128)
    @JvmField val FF_MS = FloatArray(128)
    @JvmField val CONTACT_MS = FloatArray(128)
    @JvmField val A0 = FloatArray(128)

    init {
        for (v in 0 until 128) {
            val vel = if (v < 1) 1 else v
            val hv = Math.pow(10.0, (vel - 57.96) / 71.3).coerceIn(0.25, 7.0)
            val s = smooth(2.0, 4.5, hv)
            val tt = ((1 - s) * 98.57 * Math.pow(hv, -0.7147) + s * 65.19 * Math.pow(hv, -0.7268)).coerceIn(20.0, 230.0)
            val tb = (1 - s) * (19.09 * Math.pow(hv, -0.3936) - 12.30) + s * (59.57 * Math.pow(hv, -0.1131) - 51.19)
            val ff = minOf(20.0, (1 - s) * 1.63 * Math.pow(hv, -1.403) + s * 3.04 * Math.pow(hv, -1.581))
            HV[v] = hv.toFloat(); S[v] = s.toFloat(); TT_MS[v] = tt.toFloat(); TB_MS[v] = tb.toFloat(); FF_MS[v] = ff.toFloat()
            A0[v] = Math.pow(10.0, (-40.0 + 34.0 * vel / 127.0) / 20.0).toFloat()
            CONTACT_MS[v] = (4.0 * Math.pow(0.2, (v - 21) / 87.0)).toFloat()
        }
    }

    private fun smooth(a: Double, b: Double, x: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    private fun vi(vel: Int): Int = if (vel < 0) 0 else if (vel > 127) 127 else vel

    /** Isolated stroke, key start → contact, real ms (travelScale applied). */
    fun travelMs(vel: Int, travelScale: Float = 1f): Float = TT_MS[vi(vel)] * travelScale
    /** Key bed relative to contact, real ms. */
    fun bedMs(vel: Int): Float = TB_MS[vi(vel)]
    fun freeFlightMs(vel: Int): Float = FF_MS[vi(vel)]
    fun hammerVelocity(vel: Int): Float = HV[vi(vel)]
    fun blend(vel: Int): Float = S[vi(vel)]
    fun contactMs(key: Int): Float = CONTACT_MS[vi(key)]
    fun envelopeStart(vel: Int): Float = A0[vi(vel)]

    /** u^1.8 for u in [0, 1]: the shared table behind [HarpsiTiming.depth] (no pow per call). */
    fun pow18(u: Float): Float = HarpsiTiming.depth(u) * (1f / 0.70f)
}
