package com.tropicalstream.hammerklavier.contract

/**
 * A piecewise-linear pedal curve in song time: points (us[i], v[i]) with us non-decreasing and
 * v in [0, 1]. The value is 0 before the first point and v[last] after the last one; two points at
 * the same time make a step. Immutable once built.
 */
class PedalCurve(@JvmField val us: LongArray, @JvmField val v: FloatArray) {
    init { require(us.size == v.size) { "us and v differ in length" } }

    val isEmpty: Boolean get() = us.isEmpty()

    /** Binary search; 0 before the first point. Allocation-free, any thread. */
    fun valueAt(t: Long): Float {
        val n = us.size
        if (n == 0 || t < us[0]) return 0f
        if (t >= us[n - 1]) return v[n - 1]
        val i = segmentAt(t)             // us[i] <= t < us[i + 1]
        return interp(i, t)
    }

    /** Index i of the last point with us[i] <= t (t within [us[0], us[last])). */
    internal fun segmentAt(t: Long): Int {
        var lo = 0
        var hi = us.size - 1
        while (lo < hi) {                // last index with us[idx] <= t
            val mid = (lo + hi + 1) ushr 1
            if (us[mid] <= t) lo = mid else hi = mid - 1
        }
        return lo
    }

    internal fun interp(i: Int, t: Long): Float {
        if (i + 1 >= us.size) return v[i]
        val t0 = us[i]; val t1 = us[i + 1]
        if (t1 <= t0) return v[i + 1]
        val f = ((t - t0).toDouble() / (t1 - t0).toDouble()).toFloat()
        return v[i] + (v[i + 1] - v[i]) * f
    }

    /**
     * Preallocated by its owner; rebindable, allocation-free. [advanceTo] is O(1) amortised for
     * non-decreasing times and falls back to a binary search when time steps backwards.
     */
    class Cursor {
        private var c: PedalCurve = EMPTY
        private var i = -1               // last point with us[i] <= t; -1 = before the first point

        fun bind(c: PedalCurve) { this.c = c; i = -1 }

        fun seek(t: Long) {
            val us = c.us
            i = if (us.isEmpty() || t < us[0]) -1 else c.segmentAt(t)
        }

        fun advanceTo(t: Long): Float {
            val us = c.us
            val n = us.size
            if (n == 0) return 0f
            if (i >= 0 && t < us[i]) seek(t)
            while (i + 1 < n && us[i + 1] <= t) i++
            if (i < 0) return 0f
            return c.interp(i, t)
        }
    }

    /**
     * The first time >= [fromUs] at which the curve crosses [level] upward (rising: from below to
     * >= level) or downward (falling: from above to <= level); Long.MAX_VALUE if none.
     */
    fun nextCrossing(fromUs: Long, level: Float, rising: Boolean): Long {
        val n = us.size
        if (n == 0) return Long.MAX_VALUE
        // The implicit step from 0 up to v[0] at us[0].
        if (fromUs <= us[0]) {
            val hit = if (rising) 0f < level && v[0] >= level else 0f > level && v[0] <= level
            if (hit) return us[0]
        }
        var i = if (fromUs < us[0]) 0 else segmentAt(fromUs)
        while (i + 1 < n) {
            val v0 = v[i]; val v1 = v[i + 1]
            val crosses = if (rising) v0 < level && v1 >= level else v0 > level && v1 <= level
            if (crosses) {
                val t0 = us[i]; val t1 = us[i + 1]
                val t = if (t1 <= t0) t1 else {
                    val f = (level - v0).toDouble() / (v1 - v0).toDouble()
                    t0 + Math.ceil(f * (t1 - t0)).toLong()
                }
                if (t >= fromUs) return t
            }
            i++
        }
        return Long.MAX_VALUE
    }

    companion object {
        val EMPTY: PedalCurve = PedalCurve(LongArray(0), FloatArray(0))
    }
}
