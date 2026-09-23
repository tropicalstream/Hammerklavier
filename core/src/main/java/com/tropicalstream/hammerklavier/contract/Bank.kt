package com.tropicalstream.hammerklavier.contract

/** map.json facts about one kit (PLAN §6.6), available before any decoding. */
class BankInfo(val instrument: InstrumentId, val kit: String, val version: String, val sha1: String,
    val layers: Int, val stops: Int, val lastDamper: Int, val recordedAHz: Float, val aOffsetCents: Float,
    val damperT60: FloatArray /*128, s*/, val freeT60: FloatArray /*stops × 128, s*/, val inharmB: FloatArray /*128*/,
    val releaseCarriesTail: Boolean, val embeddedRoomDb: Float, val decodeOrder: IntArray,
    val isStub: Boolean, val fallback: FallbackReason?)

/** One per thread. */
interface SampleReader {
    /** HKAudio: from the mapping; stereo interleaved; zero-fills outside the region. Returns frames read. */
    fun read(region: Int, fromFrame: Int, frames: Int, dst: ShortArray, dstOff: Int): Int
    /** HKPrefetch: positional FileChannel.read in 64 KiB chunks into one reused direct buffer (thread in Native state; never touches the mapping). */
    fun prefetch(region: Int, fromFrame: Int, frames: Int)
    /** Bulk reads that took > 1 ms. */
    val slowReads: Int
}

interface LoadedBank {
    val info: BankInfo
    /** Bumps per open; echoed in AudioStats and the prefetcher state. */
    val generation: Int
    val regionCount: Int
    fun frames(region: Int): Int
    /** Attack index in source frames, ≈ 96. */
    fun onsetFrame(region: Int): Int
    /** First frame above −40 dB re the region peak (T-ALIGN). */
    fun thrFrame(region: Int): Int
    /** 0..255 = −dBFS × 2 of the normalised region; 255 past the end. */
    fun envByte(region: Int, tenMs: Int): Int
    fun newReader(): SampleReader
    /**
     * Informational only (bit u = unit id u; 62 releases; 63 pedals); the engine never reads it.
     * Implementations back it with a `@Volatile` field (Kotlin does not allow the annotation on an
     * abstract property).
     */
    var readyMask: Long
}

/**
 * Immutable, built off-thread; see §3.5. Index "sk" = (stop * layers + layer) * 128 + key.
 * Always construct with named arguments.
 */
class KeyMap(
    val tuning: TuningSpec, val readyMask: Long, val layers: Int, val stops: Int,
    @JvmField val velLayerA: ByteArray, @JvmField val velLayerB: ByteArray,     // 128; B = -1 when not crossfading
    @JvmField val velGainA: FloatArray, @JvmField val velGainB: FloatArray,     // 128; crossfade weight × level-curve trim (linear)
    @JvmField val region: IntArray,                                   // sk → region, -1 none (never an unready region)
    @JvmField val rate: FloatArray, @JvmField val gain: FloatArray,   // sk: playback rate; 10^(gainDb/20) × seam trim × fallback trim
    @JvmField val onsetOut: IntArray,                                 // sk: round(onsetFrame / rate), output frames
    @JvmField val lpHz: FloatArray,                                   // sk: fixed seam low-pass for borrowed regions, 0 = none
    @JvmField val release: IntArray, @JvmField val releaseRate: FloatArray, @JvmField val releaseGain: FloatArray,  // stop * 128 + key
    @JvmField val pedalDown: IntArray, @JvmField val pedalUp: IntArray, @JvmField val pedalGain: Float,
    @JvmField val f0Hz: FloatArray, @JvmField val inharmB: FloatArray, @JvmField val strings: ByteArray)   // 128 (main stop)

sealed class KitState {
    object Missing : KitState()
    class Voicing(val fraction: Float, val playable: Boolean) : KitState()
    object Complete : KitState()
    class Fallback(val reason: FallbackReason) : KitState()
}

/** Main thread. */
interface KitCallback {
    fun onProgress(id: InstrumentId, fraction: Float)
    /** ≥ 1 sustain unit + releases + pedals ready. */
    fun onPlayable(bank: LoadedBank)
    /** readyMask grew: rebuild the KeyMap. */
    fun onLayersChanged(bank: LoadedBank)
    fun onComplete(bank: LoadedBank)
    /** Stub or synthesized bank in use. */
    fun onFallback(bank: LoadedBank, reason: FallbackReason)
}

interface KitService {
    fun state(id: InstrumentId): KitState
    /** map.json facts, available before any decoding. */
    fun info(id: InstrumentId): BankInfo?
    /** Decodes if needed; callbacks on main. */
    fun open(id: InstrumentId, cb: KitCallback)
    /** Pure; call on HKLoader. */
    fun keyMap(bank: LoadedBank, tuning: TuningSpec): KeyMap
    fun setPlaybackHint(playing: Boolean, activeId: InstrumentId?, q: QualityProfile, batteryTenths: Int)
    fun decodeWhenIdle(ids: List<InstrumentId>)
    /**
     * Stop voicing and prefetching; drop references once AudioStats.bankGeneration and the
     * prefetcher both show a newer bank. NEVER unmaps: the mapping lives until GC (clean,
     * reclaimable pages; counted in RSS meanwhile).
     */
    fun release(id: InstrumentId)
    fun diagnostics(): Map<String, String> = emptyMap()
}
