package com.tropicalstream.hammerklavier.engine

import com.tropicalstream.hammerklavier.contract.Performance

/** What the [Sequencer] hands its events to (the engine). */
internal interface Dispatcher {
    /** Starts (PENDING) the voices of [note]; false when no slot could be found this block. */
    fun noteOn(note: Int, evIndex: Int, eventPf: Long): Boolean
    /** Queues a state event at play frame [framePf] (applied in the block that contains it). */
    fun stateEvent(type: Int, arg: Int, evIndex: Int, framePf: Long)
}

/**
 * The event cursor (PLAN §2.5, §3.15): at the start of each playing block it dispatches every
 * event whose output-frame offset `k = round((evSongFrames − S₀) / r)` is below
 * `BLOCK + LOOK_FRAMES`; note-ons become PENDING voices, state events are queued with their
 * absolute play frame. It also keeps the ≤ 64-entry pending list of onsets that found no slot
 * (allocated in the next block, R22) and the ≤ 64-entry set of already-started note-ons that a
 * resume after a pause must skip once (R5). Allocation-free.
 */
internal class Sequencer(private val sampleRate: Int) {
    @JvmField var perf: Performance? = null
    @JvmField var cursor = 0

    private val skip = IntArray(MAX_LIST)
    private var skipN = 0

    private val pendNote = IntArray(MAX_LIST)
    private val pendEv = IntArray(MAX_LIST)
    private val pendPf = LongArray(MAX_LIST)
    @JvmField var pendN = 0

    /** Onsets that found no slot even in the pending list (T2.2 requires 0). */
    @JvmField var dropped = 0

    private val framesPerUs = sampleRate / 1_000_000.0

    /** Binds [p] and places the cursor at the first event at or after [us]. */
    fun bind(p: Performance?, us: Long) {
        perf = p
        cursor = p?.eventIndexAtOrAfter(us) ?: 0
        skipN = 0; pendN = 0
    }

    fun seek(us: Long) {
        cursor = perf?.eventIndexAtOrAfter(us) ?: 0
        skipN = 0; pendN = 0
    }

    /** Output-frame offset of event time [evUs] from a block whose song position is [s0] frames at rate [rate]. */
    fun offsetOf(evUs: Long, s0: Double, rate: Double): Long = Math.round((evUs * framesPerUs - s0) / rate)

    /** Retries the pending list (first thing in a block, before new events). */
    fun retryPending(d: Dispatcher) {
        if (pendN == 0) return
        var w = 0
        for (i in 0 until pendN) {
            if (d.noteOn(pendNote[i], pendEv[i], pendPf[i])) continue
            pendNote[w] = pendNote[i]; pendEv[w] = pendEv[i]; pendPf[w] = pendPf[i]; w++
        }
        pendN = w
    }

    /** Dispatches every event with offset below [limit] frames from the block at play frame [pf]. */
    fun dispatch(d: Dispatcher, s0: Double, rate: Double, pf: Long, limit: Int) {
        val p = perf ?: return
        val evUs = p.evUs; val ev = p.ev
        val n = ev.size
        while (cursor < n) {
            val k = offsetOf(evUs[cursor], s0, rate)
            if (k >= limit) break
            val at = pf + (if (k < 0) 0L else k)
            val e = ev[cursor]
            val idx = cursor
            cursor++
            when (Performance.type(e)) {
                Performance.EV_NOTE_ON -> {
                    val note = Performance.arg(e)
                    if (takeSkip(note)) continue
                    d.stateEvent(PendingEvents.KEY_DOWN, note, idx, at)
                    if (!d.noteOn(note, idx, at)) addPending(note, idx, at)
                }
                Performance.EV_KEY_UP, Performance.EV_LATCH, Performance.EV_PEDAL_NOISE, Performance.EV_END ->
                    d.stateEvent(Performance.type(e), Performance.arg(e), idx, at)
                else -> {}
            }
        }
    }

    private fun addPending(note: Int, ev: Int, at: Long) {
        if (pendN >= MAX_LIST) { dropped++; return }
        pendNote[pendN] = note; pendEv[pendN] = ev; pendPf[pendN] = at; pendN++
    }

    /** The lowest event index in the pending list (Int.MAX_VALUE if empty). */
    fun minPendingEv(): Int {
        var m = Int.MAX_VALUE
        for (i in 0 until pendN) if (pendEv[i] < m) m = pendEv[i]
        return m
    }

    fun clearPending() { pendN = 0 }

    /** Adds a note-on that has already taken effect and must be skipped once after a rewind. */
    fun addSkip(note: Int) {
        for (i in 0 until skipN) if (skip[i] == note) return
        if (skipN < MAX_LIST) skip[skipN++] = note
    }

    fun clearSkip() { skipN = 0 }
    val skipCount: Int get() = skipN

    private fun takeSkip(note: Int): Boolean {
        for (i in 0 until skipN) if (skip[i] == note) { skip[i] = skip[--skipN]; return true }
        return false
    }

    companion object { const val MAX_LIST = 64 }
}
