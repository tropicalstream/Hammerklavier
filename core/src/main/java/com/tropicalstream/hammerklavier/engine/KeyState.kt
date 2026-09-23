package com.tropicalstream.hammerklavier.engine

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.PedalMotion
import com.tropicalstream.hammerklavier.contract.Performance
import com.tropicalstream.hammerklavier.contract.SoftKind

/**
 * Per-key state (PLAN §3.7, §3.8, §3.11): held and latched keys, damper landings counted in
 * play frames (they survive a pause, and count playing frames only), the damping strength
 * `D(k)`, the comb gates (0..1) and the una-corda soft-feed flags. Allocation-free.
 *
 * D(k) = 0 if k > lastDamper, while the key's damper is up (note-on … landing), or while it is
 * sostenuto-latched; otherwise `pedalDamping(p)`. `gate = 0` when D ≥ 0.999, else `1 − D`; the
 * harpsichord's gate is 1 while the key is held, else 0. `softFeed` = the key's newest voice
 * started on the soft bus, una corda, ≥ 2 strings, and the key still sounds.
 */
class KeyState {
    /** Note-on applied … key-up applied (the finger is on the key). */
    @JvmField val held = BooleanArray(HK.KEYS)
    /** Note-on applied … damper landing (the damper is off the strings). */
    @JvmField val undamped = BooleanArray(HK.KEYS)
    @JvmField var latchLo = 0L
    @JvmField var latchHi = 0L
    /** Play frame of the pending damper landing; [NONE] = none. */
    @JvmField val landAt = LongArray(HK.KEYS) { NONE }
    /** Harpsichord: play frames of the pending 8′ and 4′ quill passes. */
    @JvmField val q8At = LongArray(HK.KEYS) { NONE }
    @JvmField val q4At = LongArray(HK.KEYS) { NONE }
    /** The note of the key's last key-up (for the release gain). */
    @JvmField val lastNote = IntArray(HK.KEYS) { -1 }
    /** The key's newest main voice started on the soft bus. */
    @JvmField val newestSoft = BooleanArray(HK.KEYS)
    /** The key had a sounding voice in the last rendered block. */
    @JvmField val hasVoice = BooleanArray(HK.KEYS)

    // Outputs of update().
    @JvmField val damping = FloatArray(HK.KEYS)
    @JvmField val gate = FloatArray(HK.KEYS)
    @JvmField val softFeed = BooleanArray(HK.KEYS)
    @JvmField val selfWanted = BooleanArray(HK.KEYS)
    /** Frame offset inside the current block at which the key's damper landed; −1 = not this block. */
    @JvmField val landOffset = IntArray(HK.KEYS) { -1 }

    @JvmField var lastDamper = 88
    @JvmField var harpsichord = false
    @JvmField var unaCorda = false
    @JvmField var pedalDamp = 1f
    private val strings = IntArray(HK.KEYS) { 1 }

    fun configure(profile: InstrumentProfile) {
        lastDamper = profile.lastDamper
        harpsichord = profile.stops >= 2
        unaCorda = profile.softKind == SoftKind.UNA_CORDA
        for (k in 0 until HK.KEYS) strings[k] = profile.stringsPerKey(k)
    }

    fun latched(k: Int): Boolean =
        if (k < 64) (latchLo ushr k) and 1L == 1L else (latchHi ushr (k - 64)) and 1L == 1L

    /** The key goes down (at its onset frame). */
    fun noteOn(k: Int) {
        held[k] = true; undamped[k] = true
        landAt[k] = NONE; q8At[k] = NONE; q4At[k] = NONE
    }

    /** The key comes up at play frame [frame]; its damper lands [lag] frames later (quill passes on the harpsichord). */
    fun keyUp(k: Int, note: Int, frame: Long, lag: Int, quill8: Int, quill4: Int) {
        held[k] = false
        lastNote[k] = note
        if (undamped[k]) landAt[k] = frame + lag
        if (harpsichord) { q8At[k] = frame + quill8; q4At[k] = frame + quill4 }
    }

    /** Applies a landing (called by the engine when [landAt] falls in the block, at offset [offset]). */
    fun land(k: Int, offset: Int) {
        undamped[k] = false; landAt[k] = NONE; landOffset[k] = offset
    }

    fun setLatch(lo: Long, hi: Long) { latchLo = lo; latchHi = hi }

    /** Starts a block: clears the per-block landing offsets. */
    fun beginBlock() { java.util.Arrays.fill(landOffset, -1) }

    /** Computes D, gates, soft feeds and the self-row wishes for the block, at sustain value [pSus]. */
    fun update(pSus: Float) {
        val pd = PedalMotion.pedalDamping(pSus)
        pedalDamp = pd
        for (k in 0 until HK.KEYS) {
            val d = if (k > lastDamper || undamped[k] || latched(k)) 0f else pd
            damping[k] = d
            val gt = if (harpsichord) (if (held[k]) 1f else 0f) else if (d >= 0.999f) 0f else 1f - d
            gate[k] = gt
            val sf = unaCorda && newestSoft[k] && strings[k] >= 2 && hasVoice[k]
            softFeed[k] = sf
            selfWanted[k] = gt > 0f || sf
        }
    }

    /** Forgets every key (new bank or reset). */
    fun clear() {
        java.util.Arrays.fill(held, false); java.util.Arrays.fill(undamped, false)
        latchLo = 0L; latchHi = 0L
        java.util.Arrays.fill(landAt, NONE); java.util.Arrays.fill(q8At, NONE); java.util.Arrays.fill(q4At, NONE)
        java.util.Arrays.fill(lastNote, -1); java.util.Arrays.fill(newestSoft, false); java.util.Arrays.fill(hasVoice, false)
        java.util.Arrays.fill(landOffset, -1)
    }

    /**
     * Rebuilds the state (not the sound) at song time [us] of [p]: keys whose notes span [us] are
     * down, the sostenuto latch in effect is applied, and no landing is pending (§3.15 seek).
     */
    fun rebuild(p: Performance?, us: Long) {
        clear()
        if (p == null) return
        val on = p.onUs; val off = p.offUs; val keyFirst = p.keyFirst; val keyNotes = p.keyNotes
        for (k in 0 until HK.KEYS) {
            val a = keyFirst[k]; val b = keyFirst[k + 1]
            // notes of a key are sorted by onUs and never overlap: find the last with on <= us
            var lo = a; var hi = b
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (on[keyNotes[mid]] <= us) lo = mid + 1 else hi = mid }
            if (lo > a) {
                val n = keyNotes[lo - 1]
                if (us < off[n]) { held[k] = true; undamped[k] = true }
                lastNote[k] = n
            }
        }
        val li = p.latchIndexAt(us)
        if (li >= 0) setLatch(p.latchLo[li], p.latchHi[li])
    }

    companion object { const val NONE = Long.MAX_VALUE }
}
