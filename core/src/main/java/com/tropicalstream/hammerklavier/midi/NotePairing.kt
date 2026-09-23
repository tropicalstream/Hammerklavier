package com.tropicalstream.hammerklavier.midi

import com.tropicalstream.hammerklavier.contract.Performance

/** Mutable note lists in song µs, used while building (HKLoader only). */
class NoteList(capacity: Int) {
    @JvmField var on = LongArray(capacity); @JvmField var off = LongArray(capacity)
    @JvmField var key = IntArray(capacity); @JvmField var vel = IntArray(capacity)
    @JvmField var ch = IntArray(capacity); @JvmField var flags = IntArray(capacity)
    @JvmField var keep = BooleanArray(capacity)
    var size = 0

    fun add(onUs: Long, offUs: Long, k: Int, v: Int, c: Int): Int {
        if (size == on.size) {
            val n = maxOf(16, size * 2)
            on = on.copyOf(n); off = off.copyOf(n); key = key.copyOf(n); vel = vel.copyOf(n)
            ch = ch.copyOf(n); flags = flags.copyOf(n); keep = keep.copyOf(n)
        }
        on[size] = onUs; off[size] = offUs; key[size] = k; vel[size] = v; ch[size] = c; flags[size] = 0; keep[size] = true
        return size++
    }

    /** Indices of kept notes sorted by (on, key), stable. */
    fun order(): IntArray {
        var m = 0
        for (i in 0 until size) if (keep[i]) m++
        val idx = IntArray(m)
        m = 0
        for (i in 0 until size) if (keep[i]) idx[m++] = i
        return IdxSort.sort(idx) { a, b ->
            val c = on[a].compareTo(on[b]); if (c != 0) c else key[a].compareTo(key[b])
        }
    }
}

/**
 * Note pairing and re-strike serialisation (PLAN §4.3 step 3).
 * - `(channel, key)` note-offs match their note-ons first-in first-out; CC120/CC123 end every
 *   open note of that channel.
 * - Notes without a note-off end at their track's end (if later than the onset) or at the last
 *   event + 1 s ([hanging] counts them).
 * - Zero-length notes get 30 ms.
 * - [serialise]: all channels drive one keyboard; a note-on for a key still down ends the previous
 *   note at `newOn − 2 ms`, never before `prevOn + 20 ms` (the new note moves just past that, and
 *   gets F_RESTRIKE). A note folded onto a key that sounds a note begun ≤ 20 ms earlier merges into
 *   it (higher velocity kept).
 */
object NotePairing {
    const val MIN_LENGTH_US = 30_000L
    const val RESTRIKE_GAP_US = 2_000L
    const val RESTRIKE_MIN_US = 20_000L
    const val FOLD_MERGE_US = 20_000L
    const val HANG_TAIL_US = 1_000_000L

    class Paired(val notes: NoteList, val hanging: Int)

    fun pair(ev: MergedEvents): Paired {
        val notes = NoteList(maxOf(16, ev.size / 2))
        val head = IntArray(16 * 128) { -1 }; val tail = IntArray(16 * 128) { -1 }
        var next = IntArray(maxOf(16, ev.size / 2))
        var track = IntArray(next.size)
        // A note-off that found no open note, by (channel, key): its time. The merge orders note-offs
        // before note-ons at one tick, so a zero-length note (on and off at the same tick) arrives
        // off-first; the note-on that follows at that same time takes the orphan as its own note-off.
        val orphan = LongArray(16 * 128) { Long.MIN_VALUE }
        for (i in 0 until ev.size) {
            val c = ev.ch[i].toInt() and 15
            when (ev.kind[i].toInt()) {
                RawTrack.K_NOTE_ON -> {
                    val k = ev.d1[i].toInt() and 0x7F
                    val n = notes.add(ev.us[i], Long.MIN_VALUE, k, ev.d2[i].toInt() and 0x7F, c)
                    if (n >= next.size) { next = next.copyOf(n * 2); track = track.copyOf(n * 2) }
                    next[n] = -1; track[n] = ev.track[i]
                    val q = c * 128 + k
                    if (orphan[q] == ev.us[i]) { notes.off[n] = ev.us[i]; orphan[q] = Long.MIN_VALUE; continue }
                    if (tail[q] < 0) head[q] = n else next[tail[q]] = n
                    tail[q] = n
                }
                RawTrack.K_NOTE_OFF -> {
                    val q = c * 128 + (ev.d1[i].toInt() and 0x7F)
                    val n = head[q]
                    if (n >= 0) {
                        notes.off[n] = ev.us[i]
                        head[q] = next[n]; if (head[q] < 0) tail[q] = -1
                    } else orphan[q] = ev.us[i]
                }
                RawTrack.K_ALL_OFF -> for (k in 0 until 128) {
                    val q = c * 128 + k
                    var n = head[q]
                    while (n >= 0) { notes.off[n] = ev.us[i]; n = next[n] }
                    head[q] = -1; tail[q] = -1
                }
            }
        }
        var hanging = 0
        for (n in 0 until notes.size) {
            if (notes.off[n] == Long.MIN_VALUE) {
                hanging++
                val end = ev.trackEndUs[track[n]]
                notes.off[n] = if (end > notes.on[n]) end else ev.lastEventUs + HANG_TAIL_US
            }
            if (notes.off[n] <= notes.on[n]) notes.off[n] = notes.on[n] + MIN_LENGTH_US
        }
        return Paired(notes, hanging)
    }

    /** Serialises same-key overlaps in onset order and merges folded collisions (clears keep). */
    fun serialise(notes: NoteList) {
        val order = notes.order()
        val last = IntArray(128) { -1 }
        for (i in order) {
            val k = notes.key[i]
            val p = last[k]
            if (p >= 0 && notes.off[p] > notes.on[i]) {
                if (notes.flags[i] and Performance.F_FOLDED != 0 && notes.on[i] - notes.on[p] <= FOLD_MERGE_US) {
                    notes.vel[p] = maxOf(notes.vel[p], notes.vel[i]); notes.off[p] = maxOf(notes.off[p], notes.off[i])
                    notes.keep[i] = false
                    continue
                }
                notes.off[p] = maxOf(notes.on[i] - RESTRIKE_GAP_US, notes.on[p] + RESTRIKE_MIN_US)
                if (notes.off[p] > notes.on[i]) {
                    notes.on[i] = notes.off[p] + 1
                    if (notes.off[i] <= notes.on[i]) notes.off[i] = notes.on[i] + MIN_LENGTH_US
                }
                notes.flags[i] = notes.flags[i] or Performance.F_RESTRIKE
            }
            last[k] = i
        }
    }
}

/** Stable merge sort of an index array with an Int comparator (off the real-time threads only). */
object IdxSort {
    inline fun sort(idx: IntArray, crossinline cmp: (Int, Int) -> Int): IntArray {
        val n = idx.size
        if (n < 2) return idx
        var src = idx
        var dst = IntArray(n)
        var w = 1
        while (w < n) {
            var lo = 0
            while (lo < n) {
                val mid = minOf(lo + w, n); val hi = minOf(lo + 2 * w, n)
                var a = lo; var b = mid; var o = lo
                while (a < mid && b < hi) { if (cmp(src[b], src[a]) < 0) dst[o++] = src[b++] else dst[o++] = src[a++] }
                while (a < mid) dst[o++] = src[a++]
                while (b < hi) dst[o++] = src[b++]
                lo = hi
            }
            val t = src; src = dst; dst = t
            w *= 2
        }
        return src
    }
}
