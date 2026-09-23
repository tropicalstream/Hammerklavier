package com.tropicalstream.hammerklavier.midi

import com.tropicalstream.hammerklavier.contract.PedalCurve
import com.tropicalstream.hammerklavier.contract.PedalMode
import com.tropicalstream.hammerklavier.contract.PedalMotion

/**
 * Pedal curves in song time from controller events (PLAN §4.3 step 2, §3.8).
 *
 * Input per pedal: the maximum across channels of CC64, CC66 or CC67 (0..127), as steps.
 * - **Mode:** CONTINUOUS when the file sends ≥ 8 distinct values strictly between 0 and 127,
 *   otherwise SWITCH.
 * - **Switch:** threshold 64. A change of level becomes a ramp of the pedal's speed (sustain: down
 *   0 → 1 over 70 ms, up 1 → 0 over 60 ms; soft and sostenuto 60 ms) placed so it crosses the
 *   pedal's reference level (sustain 0.33, soft and sostenuto 0.5) at the event time. Overlapping
 *   ramps: the curve follows the earlier ramp to its intersection with the later one, then the
 *   later one (a dip that may stay above 0).
 *   *Deviation (documented in docs/progress/WP1.md):* a switch-mode value strictly between 0 and
 *   64 is a deliberate partial press and targets value/127 (SOSTENUTO's CC64 51 = 0.40 must read
 *   0.40 for the T1.3 latch case); ≥ 64 targets 1, 0 targets 0.
 * - **Continuous:** each value is held until the next one, and every change is slewed at 70 ms per
 *   full travel with no lead ("values used directly, slew-limited").
 * - **Noises:** an `EV_PEDAL_NOISE` at each 0.33 crossing of the sustain curve, ≥ 150 ms apart,
 *   speed class 2 in switch files, else 0–3 from the mean slope over ±35 ms.
 */
object PedalShaper {
    const val SUSTAIN = 64; const val SOSTENUTO = 66; const val SOFT = 67
    const val SLEW_FULL_MS = 70f
    const val CONTINUOUS_MIN_VALUES = 8
    const val SPEED_WINDOW_US = 35_000L

    class Shaped(val curve: PedalCurve, val mode: PedalMode, val rawEvents: Int)

    /** The max-across-channels steps of controller [cc] from [ev]: (us, value) with one entry per change. */
    fun steps(ev: MergedEvents, cc: Int): Pair<LongArray, IntArray> {
        val perCh = IntArray(16)
        var cur = 0
        val t = ArrayList<Long>(); val v = ArrayList<Int>()
        for (i in 0 until ev.size) {
            if (ev.kind[i].toInt() != RawTrack.K_CC || (ev.d1[i].toInt() and 0x7F) != cc) continue
            perCh[ev.ch[i].toInt() and 15] = ev.d2[i].toInt() and 0x7F
            var m = 0
            for (c in 0 until 16) if (perCh[c] > m) m = perCh[c]
            val time = ev.us[i]
            if (t.isNotEmpty() && t[t.size - 1] == time) { v[v.size - 1] = m }
            else if (m != cur || t.isEmpty()) { t.add(time); v.add(m) }
            cur = m
        }
        // Drop entries that do not change the value (after same-time collapsing).
        val ot = ArrayList<Long>(); val ov = ArrayList<Int>()
        var prev = 0
        for (i in t.indices) if (v[i] != prev) { ot.add(t[i]); ov.add(v[i]); prev = v[i] }
        return ot.toLongArray() to ov.toIntArray()
    }

    fun modeOf(values: IntArray): PedalMode {
        if (values.isEmpty()) return PedalMode.NONE
        val seen = BooleanArray(128)
        var d = 0
        for (x in values) if (x in 1..126 && !seen[x]) { seen[x] = true; d++ }
        return if (d >= CONTINUOUS_MIN_VALUES) PedalMode.CONTINUOUS else PedalMode.SWITCH
    }

    /** Shapes one pedal. [cc] selects the speeds and reference level. */
    fun shape(t: LongArray, v: IntArray, cc: Int): Shaped {
        val mode = modeOf(v)
        if (mode == PedalMode.NONE) return Shaped(PedalCurve.EMPTY, mode, 0)
        val b = CurveBuilder()
        if (mode == PedalMode.SWITCH) {
            val ref = if (cc == SUSTAIN) PedalMotion.LIFT_START else 0.5f
            val downMs = if (cc == SUSTAIN) PedalMotion.DOWN_RAMP_MS else PedalMotion.SOFT_RAMP_MS
            val upMs = if (cc == SUSTAIN) PedalMotion.UP_RAMP_MS else PedalMotion.SOFT_RAMP_MS
            var level = 0f
            for (i in t.indices) {
                val target = switchLevel(v[i])
                if (target == level) continue
                val rising = target > level
                val perUs = 1.0 / (1000.0 * if (rising) downMs else upMs)
                val lo = minOf(level, target); val hi = maxOf(level, target)
                val start = if (ref > lo && ref < hi || ref == hi && rising || ref == lo && !rising) {
                    t[i] - Math.round(Math.abs(ref - level) / perUs)
                } else t[i]
                b.ramp(level, target, start, perUs)
                level = target
            }
        } else {
            val perUs = 1.0 / (1000.0 * SLEW_FULL_MS)
            for (i in t.indices) {
                val target = v[i] / 127f
                val from = b.valueAt(t[i])
                if (from == target) continue
                b.cutAt(t[i])
                b.appendRamp(from, target, t[i], perUs)
            }
        }
        return Shaped(b.build(), mode, t.size)
    }

    fun switchLevel(value: Int): Float = when {
        value >= 64 -> 1f
        value <= 0 -> 0f
        else -> value / 127f
    }

    /** Pedal-noise events of a sustain curve: times and args (bit0 = down, bits 1..2 = speed class). */
    fun noises(sus: PedalCurve, mode: PedalMode): Pair<LongArray, IntArray> {
        val t = ArrayList<Long>(); val a = ArrayList<Int>()
        if (sus.isEmpty) return LongArray(0) to IntArray(0)
        val spacing = (PedalMotion.NOISE_MIN_SPACING_MS * 1000f).toLong()
        var from = 0L
        var rising = true
        var last = Long.MIN_VALUE / 2
        while (true) {
            val c = sus.nextCrossing(from, PedalMotion.LIFT_START, rising)
            if (c == Long.MAX_VALUE) break
            if (c - last >= spacing) {
                t.add(c); a.add((if (rising) 1 else 0) or ((if (mode == PedalMode.CONTINUOUS) speedClass(sus, c) else 2) shl 1)); last = c
            }
            from = c + 1; rising = !rising
        }
        return t.toLongArray() to a.toIntArray()
    }

    /** Number of crossings of [level] (both directions). */
    fun crossings(c: PedalCurve, level: Float): Int {
        if (c.isEmpty) return 0
        var n = 0
        var from = 0L
        var rising = true
        while (true) {
            val x = c.nextCrossing(from, level, rising)
            if (x == Long.MAX_VALUE) return n
            n++; from = x + 1; rising = !rising
        }
    }

    /** Full-travel time from the mean slope over ±35 ms: < 50 / 100 / 300 ms → 3 / 2 / 1, else 0. */
    fun speedClass(c: PedalCurve, t: Long): Int {
        val dv = Math.abs(c.valueAt(t + SPEED_WINDOW_US) - c.valueAt(t - SPEED_WINDOW_US))
        if (dv <= 0f) return 2
        val fullMs = 2 * SPEED_WINDOW_US / 1000f / dv
        return when { fullMs < 50f -> 3; fullMs < 100f -> 2; fullMs < 300f -> 1; else -> 0 }
    }

    /** Piecewise-linear curve under construction (value 0 before the first point). */
    class CurveBuilder {
        private var us = LongArray(64); private var v = FloatArray(64); private var n = 0

        private fun push(t: Long, x: Float) {
            if (n == us.size) { us = us.copyOf(n * 2); v = v.copyOf(n * 2) }
            if (n > 0 && us[n - 1] == t && v[n - 1] == x) return
            us[n] = t; v[n] = x; n++
        }

        fun valueAt(t: Long): Float {
            if (n == 0 || t < us[0]) return 0f
            var i = n - 1
            while (i > 0 && us[i] > t) i--
            if (i == n - 1) return v[i]
            val t0 = us[i]; val t1 = us[i + 1]
            if (t1 <= t0) return v[i + 1]
            return (v[i] + (v[i + 1] - v[i]) * ((t - t0).toDouble() / (t1 - t0))).toFloat()
        }

        /** Keeps the curve up to [t] and ends it with a point at (t, value at t). */
        fun cutAt(t: Long) {
            val x = valueAt(t)
            while (n > 0 && us[n - 1] > t) n--
            if (n > 0 && us[n - 1] == t) {
                // keep the last value at t (a step's upper point stays)
                v[n - 1] = x
            } else push(t, x)
        }

        fun appendRamp(from: Float, to: Float, start: Long, perUs: Double) {
            val end = start + Math.round(Math.abs(to - from) / perUs)
            if (n == 0 || us[n - 1] < start) push(start, from)
            push(end, to)
        }

        /**
         * A ramp from [from] (its nominal level at [start]) to [to] at [perUs] per µs. It joins the
         * current curve at their first intersection at or after [start] (or at [start] if they
         * already agree there), which makes the dip of overlapping ramps.
         */
        fun ramp(from: Float, to: Float, start: Long, perUs: Double) {
            val dir = if (to > from) 1.0 else -1.0
            val end = start + Math.round(Math.abs(to - from) / perUs)
            fun line(t: Long): Double {
                if (t <= start) return from.toDouble()
                if (t >= end) return to.toDouble()
                return from + dir * (t - start) * perUs
            }
            // Breakpoints at or after start: start, the curve's points after start, end.
            var x = start
            var fPrev = valueAt(start) - line(start)
            if (fPrev != 0.0) {
                var tPrev = start
                var found = false
                var i = 0
                while (i < n && us[i] <= start) i++
                while (true) {
                    val tNext = if (i < n && us[i] < end) us[i] else if (tPrev < end) end else break
                    if (i < n && us[i] < end) i++
                    val fNext = valueAt(tNext) - line(tNext)
                    if (fNext == 0.0 || (fNext < 0) != (fPrev < 0)) {
                        val frac = if (fNext == fPrev) 1.0 else fPrev / (fPrev - fNext)
                        x = tPrev + Math.round(frac * (tNext - tPrev))
                        found = true
                        break
                    }
                    tPrev = tNext; fPrev = fNext
                    if (tNext >= end) break
                }
                if (!found) x = end
            }
            while (n > 0 && us[n - 1] > x) n--
            push(x, line(x).toFloat())
            if (end > x) push(end, to)
        }

        fun build(): PedalCurve {
            if (n == 0) return PedalCurve.EMPTY
            val vv = FloatArray(n) { v[it].coerceIn(0f, 1f) }
            return PedalCurve(us.copyOf(n), vv)
        }
    }
}
