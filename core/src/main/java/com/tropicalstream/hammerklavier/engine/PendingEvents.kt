package com.tropicalstream.hammerklavier.engine

/**
 * Queued state events (PLAN §2.5): key-ups, latches, pedal noises, the end marker and the internal
 * key-down of each note-on, each with its absolute play frame, applied only in the block that
 * contains that frame. Dispatch happens in event order and every entry's frame is its event's
 * own frame, so the queue is a FIFO in frame order. Preallocated ring; allocation-free.
 */
class PendingEvents(capacityPow2: Int = 4096) {
    private val mask = capacityPow2 - 1
    private val frame = LongArray(capacityPow2)
    private val type = IntArray(capacityPow2)
    private val arg = IntArray(capacityPow2)
    private val evIndex = IntArray(capacityPow2)
    private var head = 0
    private var tail = 0
    /** Entries that did not fit (never expected: the lookahead holds a few hundred at most). */
    @JvmField var overflow = 0

    init { require(capacityPow2 > 0 && capacityPow2 and (capacityPow2 - 1) == 0) }

    val size: Int get() = tail - head
    fun isEmpty(): Boolean = head == tail

    fun add(atFrame: Long, t: Int, a: Int, ev: Int) {
        if (tail - head > mask) { overflow++; return }
        val i = tail and mask
        frame[i] = atFrame; type[i] = t; arg[i] = a; evIndex[i] = ev
        tail++
    }

    /** True when the head entry's frame is before [end]. */
    fun headBefore(end: Long): Boolean = head != tail && frame[head and mask] < end
    fun headFrame(): Long = frame[head and mask]
    fun headType(): Int = type[head and mask]
    fun headArg(): Int = arg[head and mask]
    fun headEvIndex(): Int = evIndex[head and mask]
    fun pop() { if (head != tail) head++ }

    /** The lowest event index still queued (Int.MAX_VALUE if none). */
    fun minEvIndex(): Int {
        var m = Int.MAX_VALUE
        var i = head
        while (i != tail) { val e = evIndex[i and mask]; if (e < m) m = e; i++ }
        return m
    }

    fun clear() { head = 0; tail = 0 }

    companion object {
        // Types: the Performance event types, plus the engine's own key-down at each onset.
        const val KEY_UP = 1
        const val LATCH = 2
        const val PEDAL_NOISE = 3
        const val KEY_DOWN = 6
        const val END = 15
    }
}
