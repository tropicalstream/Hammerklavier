package com.tropicalstream.hammerklavier.midi

/**
 * Tick → µs of file time (PLAN §4.2), merged from the tempo metas of every track (some format-1
 * files put tempo outside track 0). Piecewise linear over tempo segments starting at 500,000 µs per
 * quarter; segment starts are accumulated in Double so nothing drifts; lookups are binary searches.
 * SMPTE files use a constant µs per tick and ignore tempo metas. Bar starts come from the time
 * signature metas (default 4/4).
 */
class TempoMap private constructor(
    private val segTick: LongArray, private val segUs: DoubleArray, private val segUsPerTick: DoubleArray,
    private val sigTick: LongArray, private val sigNum: IntArray, private val sigDenPow: IntArray,
    private val ticksPerQuarter: Double) {

    /** File-time µs of [tick] (no pre-roll), rounded to the nearest µs. */
    fun tickToUs(tick: Long): Long {
        val i = segmentOf(tick)
        return Math.round(segUs[i] + (tick - segTick[i]) * segUsPerTick[i])
    }

    /** Inverse of [tickToUs] (nearest tick). */
    fun usToTick(us: Long): Long {
        var lo = 0; var hi = segTick.size - 1
        while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (segUs[mid] <= us) lo = mid else hi = mid - 1 }
        return segTick[lo] + Math.round((us - segUs[lo]) / segUsPerTick[lo])
    }

    private fun segmentOf(tick: Long): Int {
        var lo = 0; var hi = segTick.size - 1
        while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (segTick[mid] <= tick) lo = mid else hi = mid - 1 }
        return lo
    }

    /** Bar start ticks from 0 while ≤ [untilTick] (at most [MAX_BARS]). A time signature starts a new bar at its own tick. */
    fun barTicks(untilTick: Long): LongArray {
        var out = LongArray(64)
        var n = 0
        var s = 0
        var t = 0.0
        while (t <= untilTick && n < MAX_BARS) {
            while (s + 1 < sigTick.size && sigTick[s + 1] <= t + 0.5) s++
            if (n == out.size) out = out.copyOf(n * 2)
            out[n++] = Math.round(t)
            val len = sigNum[s] * ticksPerQuarter * 4.0 / (1 shl sigDenPow[s])
            var next = t + len
            if (s + 1 < sigTick.size && sigTick[s + 1] < next - 0.5) next = sigTick[s + 1].toDouble()
            t = next
        }
        return out.copyOf(n)
    }

    /** Bar starts in file µs up to [untilUs]. */
    fun barUs(untilUs: Long): LongArray {
        val ticks = barTicks(usToTick(untilUs) + 1)
        var n = ticks.size
        val us = LongArray(n) { tickToUs(ticks[it]) }
        while (n > 1 && us[n - 1] > untilUs) n--
        return us.copyOf(n)
    }

    companion object {
        const val DEFAULT_TEMPO = 500_000
        const val MAX_BARS = 65_536

        fun of(smf: RawSmf): TempoMap {
            val tempoT = ArrayList<Long>(); val tempoV = ArrayList<Int>()
            val sigT = ArrayList<Long>(); val sigV = ArrayList<Int>()
            // Collect in (tick, track, file order): a stable sort by tick of the concatenation.
            for (tr in smf.tracks) for (i in 0 until tr.size) {
                when (tr.kind[i].toInt()) {
                    RawTrack.K_TEMPO -> { tempoT.add(tr.tick[i]); tempoV.add(tr.value[i]) }
                    RawTrack.K_TIMESIG -> { sigT.add(tr.tick[i]); sigV.add(tr.value[i]) }
                }
            }
            val tq: Double
            val sT = ArrayList<Long>(); val sUs = ArrayList<Double>(); val sR = ArrayList<Double>()
            if (smf.ppq == 0) {
                tq = DEFAULT_TEMPO / smf.smpteUsPerTick
                sT.add(0); sUs.add(0.0); sR.add(smf.smpteUsPerTick)
            } else {
                tq = smf.ppq.toDouble()
                val order = tempoT.indices.sortedBy { tempoT[it] }          // stable
                sT.add(0); sUs.add(0.0); sR.add(DEFAULT_TEMPO / tq)
                for (j in order) {
                    val t = tempoT[j]; val r = tempoV[j] / tq
                    val last = sT.size - 1
                    if (t == sT[last]) { sR[last] = r; continue }            // same tick: the later one wins
                    sUs.add(sUs[last] + (t - sT[last]) * sR[last]); sT.add(t); sR.add(r)
                }
            }
            val so = sigT.indices.sortedBy { sigT[it] }
            val gT = ArrayList<Long>(); val gN = ArrayList<Int>(); val gD = ArrayList<Int>()
            gT.add(0); gN.add(4); gD.add(2)
            for (j in so) {
                val t = sigT[j]; val num = sigV[j] shr 8; val den = sigV[j] and 0xFF
                if (t == gT[gT.size - 1]) { gN[gN.size - 1] = num; gD[gD.size - 1] = den } else { gT.add(t); gN.add(num); gD.add(den) }
            }
            return TempoMap(sT.toLongArray(), sUs.toDoubleArray(), sR.toDoubleArray(),
                gT.toLongArray(), gN.toIntArray(), gD.toIntArray(), tq)
        }

        /** Constant 120 bpm, 4/4 (one quarter = 500 ms) at [ppq]: the synthetic scores' grid. */
        fun constant(ppq: Int = 500): TempoMap = TempoMap(longArrayOf(0), doubleArrayOf(0.0), doubleArrayOf(DEFAULT_TEMPO.toDouble() / ppq),
            longArrayOf(0), intArrayOf(4), intArrayOf(2), ppq.toDouble())
    }
}
