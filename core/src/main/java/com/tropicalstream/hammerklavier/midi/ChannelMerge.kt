package com.tropicalstream.hammerklavier.midi

import com.tropicalstream.hammerklavier.contract.HK

/**
 * All channel events of a score on one timeline, in song µs (file time + [HK.PRE_ROLL_US]),
 * sorted by (time, class, track, file order) with class order note-off < controller < note-on
 * (metas are consumed by the [TempoMap]). [kind] uses the [RawTrack] K_ codes (NOTE_OFF, NOTE_ON,
 * CC, ALL_OFF). [trackEndUs] is each track's end (its end-of-track meta or last event).
 */
class MergedEvents(
    @JvmField val us: LongArray, @JvmField val kind: ByteArray, @JvmField val ch: ByteArray,
    @JvmField val d1: ByteArray, @JvmField val d2: ByteArray, @JvmField val track: IntArray,
    @JvmField val trackEndUs: LongArray, val lastEventUs: Long,
    /** Channels (0..15) that carry at least one kept note-on. */
    val noteChannels: Int,
    /** Channel-10 note-ons dropped because other channels have notes. */
    val droppedDrumNotes: Int,
    /** True when channel 10 was kept because it is the only channel with notes. */
    val onlyDrumChannel: Boolean) {
    val size: Int get() = us.size
    val mergedChannels: Int get() = Integer.bitCount(noteChannels)

    companion object {
        /** Class of a K_ code for the tie order: note-off (and all-off) 1 < controller 2 < note-on 3. */
        fun classOf(kind: Int): Int = when (kind) {
            RawTrack.K_NOTE_OFF, RawTrack.K_ALL_OFF -> 1
            RawTrack.K_CC -> 2
            RawTrack.K_NOTE_ON -> 3
            else -> 0
        }
    }
}

/**
 * k-way merge of every track and channel (PLAN §4.3 step 1) with the tie order of [MergedEvents],
 * channel 10 (index 9) dropped unless it is the only channel with notes.
 */
object ChannelMerge {
    const val DRUM_CHANNEL = 9

    fun merge(smf: RawSmf, tempo: TempoMap): MergedEvents {
        val tracks = smf.tracks
        // Which channels have notes?
        var noteCh = 0
        var drumNotes = 0
        for (tr in tracks) for (i in 0 until tr.size) if (tr.kind[i].toInt() == RawTrack.K_NOTE_ON) {
            val c = tr.ch[i].toInt()
            noteCh = noteCh or (1 shl c)
            if (c == DRUM_CHANNEL) drumNotes++
        }
        val dropDrums = noteCh and (1 shl DRUM_CHANNEL) != 0 && noteCh != (1 shl DRUM_CHANNEL)
        fun keep(tr: RawTrack, i: Int): Boolean {
            val k = tr.kind[i].toInt()
            if (k > RawTrack.K_ALL_OFF) return false
            return !(dropDrums && tr.ch[i].toInt() == DRUM_CHANNEL)
        }
        // Per track: indices of kept events, stably ordered by (tick, class).
        val perTrack = Array(tracks.size) { t ->
            val tr = tracks[t]
            var m = 0
            for (i in 0 until tr.size) if (keep(tr, i)) m++
            val idx = IntArray(m)
            m = 0
            for (i in 0 until tr.size) if (keep(tr, i)) idx[m++] = i
            // Stable insertion within equal-tick runs (runs are short; ticks already non-decreasing).
            var s = 0
            while (s < m) {
                var e = s + 1
                while (e < m && tr.tick[idx[e]] == tr.tick[idx[s]]) e++
                for (a in s + 1 until e) {
                    val v = idx[a]; val cv = MergedEvents.classOf(tr.kind[v].toInt())
                    var b = a - 1
                    while (b >= s && MergedEvents.classOf(tr.kind[idx[b]].toInt()) > cv) { idx[b + 1] = idx[b]; b-- }
                    idx[b + 1] = v
                }
                s = e
            }
            idx
        }
        var total = 0
        for (a in perTrack) total += a.size
        val us = LongArray(total); val kind = ByteArray(total); val ch = ByteArray(total)
        val d1 = ByteArray(total); val d2 = ByteArray(total); val track = IntArray(total)
        // Heap of tracks keyed by (tick, class, track).
        val pos = IntArray(tracks.size)
        val heap = IntArray(tracks.size)
        var hn = 0
        fun tickOf(t: Int) = tracks[t].tick[perTrack[t][pos[t]]]
        fun clsOf(t: Int) = MergedEvents.classOf(tracks[t].kind[perTrack[t][pos[t]]].toInt())
        fun less(a: Int, b: Int): Boolean {
            val ta = tickOf(a); val tb = tickOf(b)
            if (ta != tb) return ta < tb
            val ca = clsOf(a); val cb = clsOf(b)
            if (ca != cb) return ca < cb
            return a < b
        }
        fun down(i0: Int) {
            var i = i0
            while (true) {
                val l = 2 * i + 1; val r = l + 1
                var m = i
                if (l < hn && less(heap[l], heap[m])) m = l
                if (r < hn && less(heap[r], heap[m])) m = r
                if (m == i) return
                val x = heap[i]; heap[i] = heap[m]; heap[m] = x; i = m
            }
        }
        for (t in tracks.indices) if (perTrack[t].isNotEmpty()) heap[hn++] = t
        for (i in hn / 2 - 1 downTo 0) down(i)
        val pre = HK.PRE_ROLL_US
        var o = 0
        var lastTick = -1L
        var lastUs = 0L
        while (hn > 0) {
            val t = heap[0]
            val tr = tracks[t]
            val i = perTrack[t][pos[t]]
            val tick = tr.tick[i]
            if (tick != lastTick) { lastUs = tempo.tickToUs(tick) + pre; lastTick = tick }
            us[o] = lastUs; kind[o] = tr.kind[i]; ch[o] = tr.ch[i]; d1[o] = tr.d1[i]; d2[o] = tr.d2[i]; track[o] = t
            o++
            pos[t]++
            if (pos[t] == perTrack[t].size) { heap[0] = heap[--hn] }
            down(0)
        }
        var lastEvent = pre
        val ends = LongArray(tracks.size) { tempo.tickToUs(tracks[it].endTick) + pre }
        for (tr in tracks) if (tr.size > 0) lastEvent = maxOf(lastEvent, tempo.tickToUs(tr.tick[tr.size - 1]) + pre)
        val kept = if (dropDrums) noteCh and (1 shl DRUM_CHANNEL).inv() else noteCh
        return MergedEvents(us, kind, ch, d1, d2, track, ends, lastEvent, kept,
            droppedDrumNotes = if (dropDrums) drumNotes else 0,
            onlyDrumChannel = noteCh == (1 shl DRUM_CHANNEL))
    }
}
