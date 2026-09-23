package com.tropicalstream.hammerklavier.midi

import com.tropicalstream.hammerklavier.contract.CompileOptions
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.PedalCurve
import com.tropicalstream.hammerklavier.contract.Performance
import com.tropicalstream.hammerklavier.contract.SoftKind

/**
 * Per-instrument policy (PLAN §4.3 steps 4, 5, 9; §4.4):
 * - compass folding by octaves into `profile.lowKey..highKey` (F_FOLDED; collisions are merged by
 *   [NotePairing.serialise]);
 * - CC64: sustain on pianos; on the harpsichord **legato hold**: while the pedal is down (the
 *   file's value ≥ 64, read from the raw steps, not the shaped curve) each note-off moves to the
 *   next release (value < 64), capped
 *   at +[CompileOptions.legatoCapMs] and at the next onset on that key − 2 ms; then the curve is dropped;
 * - CC66 on the grand only; CC67 unless the profile has no soft pedal;
 * - `flatVelocity` on pianos only (the harpsichord has no dynamics to flatten).
 */
object InstrumentAdapter {

    /** Folds every note into the compass; returns the number folded. */
    fun fold(notes: NoteList, profile: InstrumentProfile): Int {
        var folded = 0
        for (i in 0 until notes.size) {
            var k = notes.key[i]
            var f = false
            while (k < profile.lowKey) { k += 12; f = true }
            while (k > profile.highKey) { k -= 12; f = true }
            if (f) { notes.key[i] = k; notes.flags[i] = notes.flags[i] or Performance.F_FOLDED; folded++ }
        }
        return folded
    }

    /** The raw CC steps (us, 0..127) as a step curve, value/127 held until the next step. */
    fun rawCurve(t: LongArray, v: IntArray): PedalCurve {
        if (t.isEmpty()) return PedalCurve.EMPTY
        val us = LongArray(2 * t.size - 1); val vv = FloatArray(us.size)
        var o = 0
        for (i in t.indices) {
            if (i > 0) { us[o] = t[i]; vv[o++] = v[i - 1] / 127f }
            us[o] = t[i]; vv[o++] = v[i] / 127f
        }
        return PedalCurve(us, vv)
    }

    /** Applies legato hold from the raw CC64 step curve [raw] (see [rawCurve]); true if any note-off moved. */
    fun legatoHold(notes: NoteList, raw: PedalCurve, capMs: Int): Boolean {
        val sustain = raw
        val down = 0.5f
        if (sustain.isEmpty) return false
        val order = notes.order()
        val nextOn = LongArray(notes.size) { Long.MAX_VALUE }
        val last = IntArray(128) { -1 }
        for (i in order) { val p = last[notes.key[i]]; if (p >= 0) nextOn[p] = notes.on[i]; last[notes.key[i]] = i }
        val cap = capMs * 1000L
        var moved = false
        for (i in order) {
            val off = notes.off[i]
            if (sustain.valueAt(off) < down) continue
            val release = sustain.nextCrossing(off, down, rising = false)
            val target = minOf(release, off + cap, if (nextOn[i] == Long.MAX_VALUE) Long.MAX_VALUE else nextOn[i] - NotePairing.RESTRIKE_GAP_US)
            if (target > off && target != Long.MAX_VALUE) { notes.off[i] = target; moved = true }
        }
        return moved
    }

    fun usesSoft(profile: InstrumentProfile) = profile.softKind != SoftKind.NONE

    /** The velocity a note plays at under [opts]. */
    fun velocity(v: Int, profile: InstrumentProfile, opts: CompileOptions): Int =
        if (opts.flatVelocity > 0 && profile.velocityGain) opts.flatVelocity.coerceIn(1, 127) else v.coerceIn(1, 127)
}
