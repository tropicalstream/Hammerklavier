package com.tropicalstream.hammerklavier.contract

/** Facts about a compiled Performance (PLAN §4.3). */
class PerfInfo(val title: String?, val lowKey: Int, val highKey: Int, val noteCount: Int, val maxPolyphony: Int,
    val pedalMode: PedalMode, val sustainEvents: Int, val softEvents: Int, val sostenutoEvents: Int,
    val folded: Int, val mergedChannels: Int, val droppedDrumNotes: Int, val fingerPedalled: Boolean,
    val voiceDemandP99: Int, val voiceDemandMax: Int,                 // uncapped simulation (§3.14)
    val warnings: List<PerfWarning>)

/**
 * An immutable, fully built performance of one file on one instrument. All times are song µs
 * including [HK.PRE_ROLL_US]. Built on HKLoader, handed over whole (§2.1 rule 4).
 */
class Performance(
    val id: String, val generation: Int, val instrument: InstrumentId, val lastDamper: Int,
    val durationUs: Long,                                   // includes PRE_ROLL_US; last key-up + 1.5 s
    @JvmField val onUs: LongArray, @JvmField val offUs: LongArray,  // per note, sorted by (onUs, key). onUs = hammer contact / 8' pluck; offUs = key release starts
    @JvmField val key: ByteArray, @JvmField val vel: ByteArray, @JvmField val flags: ByteArray,
    @JvmField val keyFirst: IntArray,                       // size 129 (CSR): notes of key k = keyNotes[keyFirst[k] until keyFirst[k+1]]
    @JvmField val keyNotes: IntArray,                       // note indices per key, sorted by onUs, never overlapping
    val sustain: PedalCurve, val soft: PedalCurve, val sostenuto: PedalCurve,
    @JvmField val latchUs: LongArray, @JvmField val latchLo: LongArray, @JvmField val latchHi: LongArray,  // sostenuto masks, keys 0..63 / 64..127
    @JvmField val evUs: LongArray, @JvmField val ev: IntArray,  // audio event stream sorted by (evUs, type)
    @JvmField val barUs: LongArray, val info: PerfInfo) {

    val noteCount: Int get() = onUs.size

    /** Index of the first event with evUs >= us (evUs.size if none). Binary search, allocation-free. */
    fun eventIndexAtOrAfter(us: Long): Int {
        var lo = 0
        var hi = evUs.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (evUs[mid] < us) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** 1-based bar number at song time [us] (1 before the second bar start). */
    fun barAt(us: Long): Int {
        var lo = 0
        var hi = barUs.size
        while (lo < hi) {                    // count of bar starts <= us
            val mid = (lo + hi) ushr 1
            if (barUs[mid] <= us) lo = mid + 1 else hi = mid
        }
        return if (lo < 1) 1 else lo
    }

    /** Index of the latch entry in effect at [us]; -1 = none active. */
    fun latchIndexAt(us: Long): Int {
        var lo = 0
        var hi = latchUs.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (latchUs[mid] <= us) lo = mid + 1 else hi = mid
        }
        return lo - 1
    }

    companion object {
        const val F_RESTRIKE = 1; const val F_FOLDED = 2
        // Event types; numeric order = tie order at equal time. Real-time offsets (damper lag, 4' stagger) are applied by the
        // Sequencer in OUTPUT frames, independent of the rate; events carry only score times.
        const val EV_KEY_UP = 1          // arg = note; at offUs. The damper lands damperLagMs (real time) later unless held
        const val EV_LATCH = 2           // arg = latch index (sostenuto mask change)
        const val EV_PEDAL_NOISE = 3     // arg: bit0 1 = down / 0 = up, bits 1..2 = speed class 0..3
        const val EV_PLUCK4_RETIRED = 4  // reserved; the 4' voice is started by EV_NOTE_ON at -staggerFrames(vel)
        const val EV_NOTE_ON = 5         // arg = note; at onUs
        const val EV_END = 15
        fun type(e: Int) = e ushr 28
        fun arg(e: Int) = e and 0x0FFFFFFF
        fun pack(type: Int, arg: Int) = (type shl 28) or arg
    }
}
