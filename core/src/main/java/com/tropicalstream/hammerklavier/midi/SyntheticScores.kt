package com.tropicalstream.hammerklavier.midi

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.PedalCurve
import com.tropicalstream.hammerklavier.contract.PerfWarning
import com.tropicalstream.hammerklavier.contract.Performance
import com.tropicalstream.hammerklavier.contract.ScoreSpec
import com.tropicalstream.hammerklavier.contract.SyntheticScore
import com.tropicalstream.hammerklavier.contract.SyntheticSpecs

/**
 * The §4.5 test performances, compiled from [SyntheticSpecs] through the real
 * [PerformanceBuilder]: the spec's notes and raw controller steps become merged channel events
 * on channel 1 (a 120 bpm 4/4 grid, as the `.mid` twins at PPQ 500 and 500,000 µs per quarter).
 */
object SyntheticScores {

    fun build(kind: SyntheticScore, profile: InstrumentProfile, generation: Int): Performance {
        val name = SyntheticSpecs.NAMES.getValue(kind)
        val r = PerformanceBuilder.build(input(SyntheticSpecs.notes(kind), name), "synth:$name", generation, profile)
        return (r as PerformanceBuilder.Result.Ok).perf              // every spec has notes
    }

    /** Builder input for a spec (file time µs; controller curves as raw steps value = cc/127). */
    fun input(spec: ScoreSpec, title: String?): PerformanceBuilder.Input {
        val pre = HK.PRE_ROLL_US
        val t = ArrayList<Long>(); val k = ArrayList<Int>(); val a = ArrayList<Int>(); val b = ArrayList<Int>()
        for (i in spec.onUs.indices) {
            t.add(spec.onUs[i] + pre); k.add(RawTrack.K_NOTE_ON); a.add(spec.key[i].toInt()); b.add(spec.vel[i].toInt())
            t.add(spec.offUs[i] + pre); k.add(RawTrack.K_NOTE_OFF); a.add(spec.key[i].toInt()); b.add(0)
        }
        fun cc(c: PedalCurve, num: Int) {
            for (i in c.us.indices) {
                if (i + 1 < c.us.size && c.us[i + 1] == c.us[i]) continue      // the (t, previous) point of a step pair
                t.add(c.us[i] + pre); k.add(RawTrack.K_CC); a.add(num); b.add(Math.round(c.v[i] * 127f))
            }
        }
        cc(spec.cc64, 64); cc(spec.cc66, 66); cc(spec.cc67, 67)
        val n = t.size
        val order = IdxSort.sort(IntArray(n) { it }) { x, y ->
            val c = t[x].compareTo(t[y])
            if (c != 0) c else MergedEvents.classOf(k[x]).compareTo(MergedEvents.classOf(k[y]))
        }
        var last = pre
        for (x in t) if (x > last) last = x
        val ev = MergedEvents(
            us = LongArray(n) { t[order[it]] }, kind = ByteArray(n) { k[order[it]].toByte() }, ch = ByteArray(n),
            d1 = ByteArray(n) { a[order[it]].toByte() }, d2 = ByteArray(n) { b[order[it]].toByte() }, track = IntArray(n),
            trackEndUs = longArrayOf(last), lastEventUs = last, noteChannels = if (spec.onUs.isEmpty()) 0 else 1,
            droppedDrumNotes = 0, onlyDrumChannel = false)
        val grid = TempoMap.constant()
        return PerformanceBuilder.Input(ev, { until -> grid.barUs(until) }, title, emptySet<PerfWarning>())
    }
}
