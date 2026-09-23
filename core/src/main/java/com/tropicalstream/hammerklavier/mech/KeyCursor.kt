package com.tropicalstream.hammerklavier.mech

import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.MechanismPose
import com.tropicalstream.hammerklavier.contract.Performance

/** One governing note, resolved for the current rate. Preallocated and refilled; no allocation. */
class NoteCtx {
    @JvmField var note = -1; @JvmField var key = 0; @JvmField var vel = 0
    @JvmField var onUs = 0L; @JvmField var offUs = 0L
    @JvmField var tStartUs = 0L                       // on − lead
    @JvmField var leadUs = 0f                          // song µs
    @JvmField var first = true                         // first note on its key
    @JvmField var intervalUs = Float.POSITIVE_INFINITY // on − on_prev (song µs)
    @JvmField var d0 = 0f                              // dip at tStart (part-way up after a partial return)
    @JvmField var fast = false                         // fastest physical repetition drawn
    @JvmField var dHeld = 0f                           // dip at offUs
}

/** Per-frame values shared by every key (pedals, sostenuto latch, registration, rate). */
class FrameEnv {
    @JvmField var r = 1f
    @JvmField var sustain = 0f; @JvmField var soft = 0f; @JvmField var sostenuto = 0f
    @JvmField var latchLo = 0L; @JvmField var latchHi = 0L
    @JvmField var lastDamper = 88
    @JvmField var registration = 3

    fun latched(k: Int): Boolean =
        if (k < 64) (latchLo ushr k) and 1L != 0L else (latchHi ushr (k - 64)) and 1L != 0L
}

/** One instrument's per-key pose formulas (PLAN §5.7). Allocation-free, no transcendental maths. */
interface KeyAction {
    val profile: InstrumentProfile
    /** Isolated-stroke lead in song µs (tt(v)·r). */
    fun isolatedLeadUs(vel: Int, r: Float): Float
    /** Hammer–string contact duration c(n)·r in song µs (0 for the harpsichord). */
    fun contactUs(key: Int, r: Float): Float
    /** Additional fast-repetition condition (upright: the jack has not reset). */
    fun forcesFast(n: NoteCtx): Boolean = false
    /** Key dip while the key is held (t < offUs), starting from n.d0 at n.tStartUs. */
    fun pressDip(n: NoteCtx, t: Long, r: Float): Float
    /** Key dip [sinceOffUs] song µs after the release from [dHeld]. */
    fun releaseDip(dHeld: Float, sinceOffUs: Float, r: Float): Float
    /** Writes every per-key field of [out] for key [k] governed by [n] (null = at rest). */
    fun pose(k: Int, n: NoteCtx?, t: Long, env: FrameEnv, out: MechanismPose)
}

/**
 * The governing-note lead rule of §5.7, shared by every instrument:
 * ```
 * avail = on − max(on_prev + c·r, off_prev)
 * lead  = min(tt·r, max(on − max(on_prev + c·r, off_prev + partialReturn·r), min(12 ms·r, avail)))
 * ```
 * and d0, the dip at tStart, from the previous notes' press and release (a bounded chain, so a
 * sequential and a fresh evaluation agree exactly).
 */
class NoteTiming(val action: KeyAction) {
    private var perf: Performance? = null
    private val scratch = NoteCtx()

    fun bind(p: Performance?) { perf = p }

    /** tStart of note at keyNotes position [p] (lo = keyFirst[key]). */
    fun tStartUs(p: Int, lo: Int, r: Float): Long {
        val pf = perf!!
        val n = pf.keyNotes[p]
        val on = pf.onUs[n]
        return on - leadUs(pf, p, lo, r).toLong()
    }

    private fun leadUs(pf: Performance, p: Int, lo: Int, r: Float): Float {
        val n = pf.keyNotes[p]
        val iso = action.isolatedLeadUs(pf.vel[n].toInt(), r)
        if (p <= lo) return iso
        val m = pf.keyNotes[p - 1]
        val on = pf.onUs[n]
        val c = action.contactUs(pf.key[n].toInt(), r).toLong()
        val onPc = pf.onUs[m] + c
        val offP = pf.offUs[m]
        val avail = (on - maxOf(onPc, offP)).toFloat()
        val partial = (action.profile.partialReturnMs * r * 1000f).toLong()
        val b = (on - maxOf(onPc, offP + partial)).toFloat()
        val lead = minOf(iso, maxOf(b, minOf(12_000f * r, avail)))
        return if (lead < 0f) 0f else lead
    }

    private fun fillBasic(pf: Performance, p: Int, lo: Int, r: Float, out: NoteCtx) {
        val n = pf.keyNotes[p]
        out.note = n; out.key = pf.key[n].toInt(); out.vel = pf.vel[n].toInt()
        out.onUs = pf.onUs[n]; out.offUs = pf.offUs[n]
        val lead = leadUs(pf, p, lo, r)
        out.leadUs = lead; out.tStartUs = out.onUs - lead.toLong()
        out.first = p <= lo
        out.intervalUs = if (out.first) Float.POSITIVE_INFINITY else (out.onUs - pf.onUs[pf.keyNotes[p - 1]]).toFloat()
    }

    private fun finish(out: NoteCtx, d0: Float, r: Float) {
        out.d0 = d0
        out.fast = !out.first && out.intervalUs < action.profile.repeatMinMs * r * 1000f
        if (!out.first && action.forcesFast(out)) out.fast = true
        out.dHeld = action.pressDip(out, out.offUs, r)
    }

    /** Fills [out] for the note at keyNotes position [p], including d0 and dHeld. */
    fun fill(p: Int, lo: Int, r: Float, out: NoteCtx) {
        val pf = perf!!
        val start = maxOf(lo, p - CHAIN)
        var d = 0f
        var i = start
        while (i < p) {
            fillBasic(pf, i, lo, r, scratch)
            finish(scratch, d, r)
            val nextStart = tStartUs(i + 1, lo, r)
            d = action.releaseDip(scratch.dHeld, (nextStart - scratch.offUs).toFloat(), r)
            i++
        }
        fillBasic(pf, p, lo, r, out)
        finish(out, d, r)
    }

    companion object { const val CHAIN = 4 }
}

/**
 * Per-key governing-note cursors (PLAN §5.7): a key's cursor steps forward when t ≥ tStart_{j+1}
 * and back while t < tStart_j, and is re-seeded by binary search. pos[k] is an index into
 * Performance.keyNotes, or keyFirst[k] − 1 when no note governs yet.
 */
class KeyCursors(private val timing: NoteTiming) {
    @JvmField val pos = IntArray(128)
    private var perf: Performance? = null

    fun bind(p: Performance?) {
        perf = p
        timing.bind(p)
        if (p != null) for (k in 0 until 128) pos[k] = p.keyFirst[k] - 1
    }

    /** Binary search: the last position whose tStart ≤ t. */
    fun seek(k: Int, t: Long, r: Float) {
        val pf = perf ?: return
        val lo = pf.keyFirst[k]; val hi = pf.keyFirst[k + 1]
        var a = lo; var b = hi                      // count of positions with tStart <= t, searched in [lo, hi)
        while (a < b) {
            val mid = (a + b) ushr 1
            if (timing.tStartUs(mid, lo, r) <= t) a = mid + 1 else b = mid
        }
        pos[k] = a - 1
    }

    /** O(1) amortised step; returns the governing position (keyFirst[k] − 1 = none). */
    fun step(k: Int, t: Long, r: Float): Int {
        val pf = perf ?: return -1
        val lo = pf.keyFirst[k]; val hi = pf.keyFirst[k + 1]
        var p = pos[k]
        while (p >= lo && t < timing.tStartUs(p, lo, r)) p--
        while (p + 1 < hi && t >= timing.tStartUs(p + 1, lo, r)) p++
        pos[k] = p
        return p
    }
}
