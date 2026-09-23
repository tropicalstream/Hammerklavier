package com.tropicalstream.hammerklavier.dsp

import com.tropicalstream.hammerklavier.contract.HK
import kotlin.math.exp
import kotlin.math.pow

/**
 * Look-ahead peak limiter (PLAN §3.13): 1 ms (48-frame) look-ahead, ceiling −1 dBFS, 120 ms
 * exponential release, stereo-linked. In place; the output is delayed by [LOOKAHEAD] frames.
 *
 * Gain law without overshoot: r[t] = min(1, ceiling / max(|L|,|R|)); m[t] = min r over the
 * window [t, t + W − 1] (a sliding minimum by a monotone deque in fixed arrays, W = LOOKAHEAD + 1);
 * the attack gain is the moving average of m over the last W samples, which is ≤ r at the sample
 * leaving the delay (every averaged m covers it), so the gain ramps linearly to its target over
 * the look-ahead. Release: g rises toward the attack gain with the prepared 120 ms coefficient.
 * Allocation-free.
 */
class Limiter(sampleRate: Int = HK.SR, lookahead: Int = LOOKAHEAD) {
    private val la = lookahead
    private val w = lookahead + 1
    val ceiling: Float = 10.0.pow(-1.0 / 20).toFloat()
    private val relCoef = exp(-1.0 / (0.120 * sampleRate)).toFloat()

    private val size = Integer.highestOneBit(w * 2 - 1) shl 1
    private val mask = size - 1
    private val dl = FloatArray(size); private val dr = FloatArray(size)
    private val mRing = FloatArray(size)            // m values of the last W samples (moving average)
    private val dqVal = FloatArray(size); private val dqIdx = IntArray(size)
    private var dqHead = 0; private var dqTail = 0     // deque [head, tail), indices masked
    private var t = 0
    private var sum = 0.0
    private var g = 1f
    private var minGainSeen = 1f

    init { reset() }

    fun reset() {
        java.util.Arrays.fill(dl, 0f); java.util.Arrays.fill(dr, 0f)
        java.util.Arrays.fill(mRing, 1f)
        dqHead = 0; dqTail = 0; t = 0; sum = w.toDouble(); g = 1f; minGainSeen = 1f
    }

    /** The lowest gain applied since the last call (for tests and debug counters). */
    fun takeMinGain(): Float { val v = minGainSeen; minGainSeen = 1f; return v }

    fun process(l: FloatArray, r: FloatArray, n: Int) {
        val c = ceiling
        for (i in 0 until n) {
            val xl = l[i]; val xr = r[i]
            var pk = if (xl < 0f) -xl else xl
            val pr = if (xr < 0f) -xr else xr
            if (pr > pk) pk = pr
            val req = if (pk > c) c / pk else 1f
            // Sliding minimum over the window ending at t (it covers t − la … t, i.e. the
            // sample now leaving the delay and the la samples after it).
            while (dqTail != dqHead && dqVal[(dqTail - 1) and mask] >= req) dqTail--
            dqVal[dqTail and mask] = req; dqIdx[dqTail and mask] = t; dqTail++
            while (dqIdx[dqHead and mask] <= t - w) dqHead++
            val m = dqVal[dqHead and mask]
            // Moving average of m over the last W values.
            sum += m - mRing[(t - w) and mask].toDouble()
            mRing[t and mask] = m
            var att = (sum / w).toFloat()
            if (att > 1f) att = 1f
            // Release (never above the attack gain).
            val gn = if (att < g) att else att + relCoef * (g - att)
            g = gn
            if (gn < minGainSeen) minGainSeen = gn
            val o = (t - la) and mask
            val yl = dl[o] * gn; val yr = dr[o] * gn
            dl[t and mask] = xl; dr[t and mask] = xr
            l[i] = yl; r[i] = yr
            t++
            if (t == 0x40000000) rebase()
        }
        // Re-sum to stop floating drift of the running sum.
        var s = 0.0
        for (k in 0 until w) s += mRing[(t - 1 - k) and mask]
        sum = s
    }

    private fun rebase() {
        val shift = 0x20000000
        var h = dqHead
        while (h != dqTail) { dqIdx[h and mask] -= shift; h++ }
        t -= shift
    }

    companion object { const val LOOKAHEAD = 48 }
}
