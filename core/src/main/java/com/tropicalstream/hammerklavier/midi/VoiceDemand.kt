package com.tropicalstream.hammerklavier.midi

import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.PedalCurve
import com.tropicalstream.hammerklavier.contract.PedalMotion

/**
 * Uncapped voice-demand simulation (PLAN §3.14, R23) → `PerfInfo.voiceDemandP99/Max`.
 *
 * Every note starts [Model.voicesPerNote] voices (2 on the harpsichord, 8′ + 4′; one more when
 * its velocity lies within a crossfade band). A voice ends at the earliest of:
 * - the kit's trim (§3.2): sustains 14 s at key 21 falling linearly to 3 s at key 108 (upright
 *   12 → 3 s, harpsichord 8 → 3 s);
 * - the −80 dB cull of its natural decay, `80 / 60 · T60f(key)` after the onset (§3.7);
 * - damping: the damper lands at `offUs + damperLagMs` once the sustain pedal is below 0.33 and
 *   the key is not sostenuto-latched (keys above `lastDamper` never); from the level reached then,
 *   the voice falls to −80 dB at 60 dB per `T60d(key)`;
 * - a re-strike of its key: every older voice fades with τ = restrikeTauPedalMs (sustain ≥ 0.33)
 *   or restrikeTauUpMs and is culled after 9.21 τ (80 dB);
 * - the per-key limit of 3: a 4th voice sends the oldest to a kill slot, which the cap does not
 *   count (§3.14), so it stops counting at that onset.
 * Demand is the number of live voices sampled at each onset (after the onset's own voices
 * start); p99 is the 99th percentile of those samples, max their maximum. Release and pedal-noise
 * voices live in their own pool (§3.14) and are not counted.
 */
object VoiceDemand {
    const val PER_KEY = 3
    const val CULL_DB = 80.0

    class Model(val voicesPerNote: Int, val xfadeSplits: IntArray = IntArray(0), val xfadeHalfWidth: Int = 0) {
        companion object {
            /** Grand HD (hard-switched layers) and the upright/harpsichord as published by default. */
            fun of(profile: InstrumentProfile): Model = Model(voicesPerNote = maxOf(1, profile.stops))
        }
    }

    class Result(val p99: Int, val max: Int)

    fun trimSec(profile: InstrumentProfile, key: Int): Double {
        val top = when (profile.id) { InstrumentId.GRAND -> 14.0; InstrumentId.UPRIGHT -> 12.0; InstrumentId.HARPSICHORD -> 8.0 }
        val k = key.coerceIn(21, 108)
        return top + (3.0 - top) * (k - 21) / 87.0
    }

    /** Voices started by one note. */
    fun voicesOf(model: Model, vel: Int): Int {
        var w = model.voicesPerNote
        if (model.xfadeHalfWidth > 0) for (s in model.xfadeSplits) if (Math.abs(vel - s) <= model.xfadeHalfWidth) { w *= 2; break }
        return w
    }

    /**
     * End time (µs) of each note's voices. Notes are the Performance's arrays (sorted by onset),
     * [keyFirst]/[keyNotes] its CSR index; latch arrays as in the Performance.
     */
    fun ends(onUs: LongArray, offUs: LongArray, key: ByteArray, keyFirst: IntArray, keyNotes: IntArray,
             sustain: PedalCurve, latchUs: LongArray, latchLo: LongArray, latchHi: LongArray,
             profile: InstrumentProfile): LongArray {
        val n = onUs.size
        val end = LongArray(n)
        val lagUs = Math.round(profile.damperLagMs * 1000.0)
        // Per-key constants, computed once per key that is used (the profile's T60 curves use pow).
        val t60fK = DoubleArray(128); val t60dK = DoubleArray(128); val lifeK = LongArray(128); val have = BooleanArray(128)
        for (i in 0 until n) {
            val k = key[i].toInt()
            if (!have[k]) {
                have[k] = true
                t60fK[k] = profile.defaultFreeT60(0, k).toDouble(); t60dK[k] = profile.defaultDamperT60(k).toDouble()
                lifeK[k] = Math.round(minOf(trimSec(profile, k), CULL_DB / 60.0 * t60fK[k]) * 1e6)
            }
            val t60f = t60fK[k]
            val t60d = t60dK[k]
            val on = onUs[i]
            var e = on + lifeK[k]
            if (k <= profile.lastDamper) {
                val land = damperLanding(offUs[i] + lagUs, k, sustain, latchUs, latchLo, latchHi)
                if (land < e) {
                    val levelDb = -60.0 * (land - on) / 1e6 / t60f
                    val rest = (CULL_DB + levelDb) / 60.0 * t60d
                    e = minOf(e, land + Math.round(maxOf(0.0, rest) * 1e6))
                }
            }
            end[i] = e
        }
        // Re-strikes and the per-key limit, in onset order per key.
        val liveIdx = IntArray(PER_KEY + 1)
        for (k in 0 until 128) {
            var m = 0
            for (j in keyFirst[k] until keyFirst[k + 1]) {
                val i = keyNotes[j]
                val t = onUs[i]
                // drop voices already over
                var w = 0
                for (a in 0 until m) if (end[liveIdx[a]] > t) { liveIdx[w++] = liveIdx[a] }
                m = w
                if (m > 0) {
                    val tauMs = if (sustain.valueAt(t) >= PedalMotion.LIFT_START) profile.restrikeTauPedalMs else profile.restrikeTauUpMs
                    val fade = t + Math.round(9.21 * tauMs * 1000.0)
                    for (a in 0 until m) { val v = liveIdx[a]; if (end[v] > fade) end[v] = fade }
                    if (m >= PER_KEY) {                     // kill the oldest
                        val v = liveIdx[0]; end[v] = minOf(end[v], t)
                        for (a in 1 until m) liveIdx[a - 1] = liveIdx[a]
                        m--
                    }
                }
                liveIdx[m++] = i
            }
        }
        return end
    }

    /** First time ≥ [t0] with sustain < 0.33 and key [k] not latched; Long.MAX_VALUE if never. */
    fun damperLanding(t0: Long, k: Int, sustain: PedalCurve, latchUs: LongArray, latchLo: LongArray, latchHi: LongArray): Long {
        var t = t0
        // Terminates: every pass either returns or moves t strictly forward to a later sustain
        // crossing or a later latch entry, and both are finite.
        while (true) {
            if (!sustain.isEmpty && sustain.valueAt(t) > PedalMotion.LIFT_START) {
                t = sustain.nextCrossing(t, PedalMotion.LIFT_START, rising = false)
                if (t == Long.MAX_VALUE) return t
            }
            val li = latchAt(latchUs, t)
            if (li < 0 || !latched(latchLo[li], latchHi[li], k)) return t
            // latched: wait for the next latch entry that releases it
            var j = li + 1
            while (j < latchUs.size && latched(latchLo[j], latchHi[j], k)) j++
            if (j >= latchUs.size) return Long.MAX_VALUE
            if (latchUs[j] <= t) return Long.MAX_VALUE     // cannot happen (latch times increase); guards the loop
            t = latchUs[j]
        }
    }

    private fun latched(lo: Long, hi: Long, k: Int) = if (k < 64) (lo ushr k) and 1L == 1L else (hi ushr (k - 64)) and 1L == 1L

    private fun latchAt(latchUs: LongArray, t: Long): Int {
        var lo = 0; var hi = latchUs.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (latchUs[mid] <= t) lo = mid + 1 else hi = mid }
        return lo - 1
    }

    /** Demand from voice end times. */
    fun demand(onUs: LongArray, vel: ByteArray, end: LongArray, model: Model): Result {
        val n = onUs.size
        if (n == 0) return Result(0, 0)
        val idx = IdxSort.sort(IntArray(n) { it }) { a, b -> end[a].compareTo(end[b]) }
        var e = 0
        var live = 0
        val samples = IntArray(n)
        var s = 0
        var i = 0
        var maxLive = 0
        while (i < n) {
            val t = onUs[i]
            while (e < n && end[idx[e]] <= t) { live -= voicesOf(model, vel[idx[e]].toInt()); e++ }
            while (i < n && onUs[i] == t) { live += voicesOf(model, vel[i].toInt()); i++ }
            samples[s++] = live
            if (live > maxLive) maxLive = live
        }
        val sm = samples.copyOf(s); sm.sort()
        return Result(sm[maxOf(0, Math.ceil(0.99 * s).toInt() - 1)], maxLive)
    }

    fun simulate(onUs: LongArray, offUs: LongArray, key: ByteArray, vel: ByteArray, keyFirst: IntArray, keyNotes: IntArray,
                 sustain: PedalCurve, latchUs: LongArray, latchLo: LongArray, latchHi: LongArray,
                 profile: InstrumentProfile, model: Model = Model.of(profile)): Result {
        val end = ends(onUs, offUs, key, keyFirst, keyNotes, sustain, latchUs, latchLo, latchHi, profile)
        return demand(onUs, vel, end, model)
    }
}
