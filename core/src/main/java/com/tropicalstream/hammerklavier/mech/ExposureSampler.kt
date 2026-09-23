package com.tropicalstream.hammerklavier.mech

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.HarpsiTiming
import com.tropicalstream.hammerklavier.contract.Performance
import com.tropicalstream.hammerklavier.contract.VisTime

/**
 * Exposure of contacts (PLAN §5.7, §2.5): a contact (grand/upright) or pluck (harpsichord) at
 * song time c is drawn in the one frame whose window (exposeFromUs, exposeToUs] contains it. The
 * windows tile song time (VisualClock), so each contact is reported exactly once; an empty window
 * (paused, reseed, first frame) reports nothing. Harpsichord: the 8′ pluck at onUs when the 8′ is
 * engaged; with only the 4′ engaged, at onUs − staggerMs(v)·r; none when both are off.
 *
 * [scan] walks a cursor over Performance.onUs (sorted): O(contacts in the window) per frame.
 */
class ExposureSampler {
    private var perf: Performance? = null
    private var harpsi = false
    private var cursor = 0                 // first note with onUs > the last window's from
    private var lastFrom = Long.MIN_VALUE

    /** Contact notes of the last [scan]: note indices, count in [count]. */
    @JvmField val notes = IntArray(CAPACITY)
    @JvmField var count = 0
    /** Contacts dropped because [notes] was full, since [bind] (for the debug HUD; expected 0). */
    @JvmField var overflow = 0L

    fun bind(p: Performance?, harpsichord: Boolean) {
        perf = p; harpsi = harpsichord; cursor = 0; lastFrom = Long.MIN_VALUE; count = 0; overflow = 0L
    }

    fun reset() { lastFrom = Long.MIN_VALUE; count = 0 }

    fun scan(v: VisTime) {
        count = 0
        val p = perf ?: return
        val from = v.exposeFromUs; val to = v.exposeToUs
        if (v.reseed || to <= from) { lastFrom = Long.MIN_VALUE; return }
        val on = p.onUs
        val n = on.size
        if (from < lastFrom || lastFrom == Long.MIN_VALUE) {
            var a = 0; var b = n
            while (a < b) { val m = (a + b) ushr 1; if (on[m] <= from) a = m + 1 else b = m }
            cursor = a
        } else while (cursor < n && on[cursor] <= from) cursor++
        lastFrom = from
        val r = if (v.rate > 0.05f) v.rate else 1f
        val reg = v.registration
        val only4 = harpsi && (reg and HK.REG_8) == 0
        if (harpsi && (reg and (HK.REG_8 or HK.REG_4)) == 0) return
        val ext = if (only4) (HarpsiTiming.staggerMs(0) * r * 1000f).toLong() + 1 else 0L
        var i = cursor
        while (i < n && on[i] <= to + ext) {
            val c = if (only4) on[i] - (HarpsiTiming.staggerMs(p.vel[i].toInt()) * r * 1000f).toLong() else on[i]
            if (c > from && c <= to) { if (count < notes.size) notes[count++] = i else overflow++ }
            i++
        }
    }

    /** 88 keys × 8 strokes per key in a 100 ms window (a key cannot repeat faster than ~12 ms). */
    companion object { const val CAPACITY = 88 * 8 }
}
