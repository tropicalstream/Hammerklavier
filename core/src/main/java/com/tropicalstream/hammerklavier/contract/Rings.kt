package com.tropicalstream.hammerklavier.contract

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReferenceArray

// Cross-thread rings (PLAN §2.1 rule 2): payloads in atomic arrays touched only through get/set,
// so every access is a synchronisation action and the protocols are data-race-free on ART/ARMv8.

/** Receives drained commands. A long-lived object (EngineCoreApi itself), never a lambda per call. */
fun interface CommandHandler { fun on(code: Int, l: Long, f: Float, ref: Any?) }

/** SPSC: main → HKAudio. [offer] on main only, [drain] on HKAudio only. Allocation-free. */
class CommandRing(capacityPow2: Int = 256) {
    init { require(capacityPow2 > 0 && capacityPow2 and (capacityPow2 - 1) == 0) { "capacity must be a power of two" } }
    private val mask = capacityPow2 - 1
    private val codes = AtomicIntegerArray(capacityPow2)
    private val longs = AtomicLongArray(capacityPow2)
    private val floats = AtomicIntegerArray(capacityPow2)          // raw float bits
    private val refs = AtomicReferenceArray<Any?>(capacityPow2)
    private val head = AtomicLong(0)                               // next slot to read (consumer)
    private val tail = AtomicLong(0)                               // next slot to write (producer)
    private val droppedCount = AtomicInteger(0)

    /** false = full (counted in [dropped]). */
    fun offer(code: Int, l: Long = 0L, f: Float = 0f, ref: Any? = null): Boolean {
        val t = tail.get()
        if (t - head.get() > mask) { droppedCount.incrementAndGet(); return false }
        val i = (t and mask.toLong()).toInt()
        codes.set(i, code); longs.set(i, l); floats.set(i, f.toRawBits()); refs.set(i, ref)
        tail.set(t + 1)                                            // publishes the slot
        return true
    }

    /** Drains up to [max] commands in order into [h]; returns how many. */
    fun drain(max: Int, h: CommandHandler): Int {
        var n = 0
        var hd = head.get()
        val t = tail.get()
        while (n < max && hd < t) {
            val i = (hd and mask.toLong()).toInt()
            val ref = refs.get(i)
            refs.set(i, null)                                      // drop the reference early (no leak)
            h.on(codes.get(i), longs.get(i), Float.fromBits(floats.get(i)), ref)
            hd++; n++
            head.set(hd)
        }
        return n
    }

    val dropped: Int get() = droppedCount.get()
}

/**
 * Seqlock per slot (AtomicIntegerArray): HKAudio writes one slot per block; GL reads the newest
 * slot at or before its heard frame (PLAN §2.5). Lane i = key 21 + i, linear RMS.
 */
class EnergyRing(slots: Int = HK.ENERGY_SLOTS) {
    private val n = slots
    private val lanes = HK.LANES
    private val version = AtomicIntegerArray(slots)                // odd = being written
    private val frame = AtomicLongArray(slots)
    private val epochs = AtomicIntegerArray(slots)
    private val data = AtomicIntegerArray(slots * HK.LANES)        // raw float bits
    private val written = AtomicLong(0)                            // slots ever written (writer's head)
    private val missCount = AtomicInteger(0)

    /** Writer thread (or while nobody writes): forget every slot. */
    fun reset() { written.set(0) }

    /** HKAudio. [lanes] holds 88 linear RMS values. */
    fun write(blockStartFrame: Long, epoch: Int, lanes: FloatArray /*88, linear RMS*/) {
        val w = written.get()
        val s = (w % n).toInt()
        val v = version.get(s)
        version.set(s, v + 1)                                      // odd: writing
        frame.set(s, blockStartFrame); epochs.set(s, epoch)
        val base = s * this.lanes
        for (i in 0 until this.lanes) data.set(base + i, lanes[i].toRawBits())
        version.set(s, v + 2)                                      // even: stable
        written.set(w + 1)
    }

    /**
     * GL: newest slot with frame <= heardFrame and the same epoch; older than the oldest → the
     * oldest and a miss. false = nothing usable (out untouched).
     */
    fun read(heardFrame: Long, epoch: Int, out: FloatArray): Boolean {
        val w = written.get()
        if (w == 0L) return false
        val count = if (w < n) w.toInt() else n
        var oldest = -1
        for (back in 0 until count) {
            val s = ((w - 1 - back) % n).toInt()
            for (attempt in 0 until 3) {
                val v0 = version.get(s)
                if (v0 and 1 != 0) continue
                val f = frame.get(s); val e = epochs.get(s)
                if (e != epoch) break
                if (f > heardFrame) { oldest = s; break }
                val base = s * lanes
                for (i in 0 until lanes) out[i] = Float.fromBits(data.get(base + i))
                if (version.get(s) == v0) return true
            }
        }
        if (oldest >= 0) {
            for (attempt in 0 until 3) {
                val v0 = version.get(oldest)
                if (v0 and 1 != 0) continue
                if (epochs.get(oldest) != epoch) break
                val base = oldest * lanes
                for (i in 0 until lanes) out[i] = Float.fromBits(data.get(base + i))
                if (version.get(oldest) == v0) { missCount.incrementAndGet(); return true }
            }
        }
        return false
    }

    val misses: Int get() = missCount.get()
}

/** HKAudio writes (lazySet), HKPrefetch reads. Slot value = (region shl 32) or frame; -1 = empty. */
class VoiceCursorBoard(size: Int = 208) {
    private val cells = AtomicLongArray(size).also { for (i in 0 until size) it.set(i, -1L) }

    fun set(slot: Int, region: Int, frame: Int) {
        cells.lazySet(slot, (region.toLong() shl 32) or (frame.toLong() and 0xFFFFFFFFL))
    }
    fun clear(slot: Int) { cells.lazySet(slot, -1L) }
    fun clearAll() { for (i in 0 until cells.length()) cells.lazySet(i, -1L) }

    /** -1 = empty; else (region shl 32) or frame. */
    fun get(slot: Int): Long = cells.get(slot)

    val size: Int get() = cells.length()
}
