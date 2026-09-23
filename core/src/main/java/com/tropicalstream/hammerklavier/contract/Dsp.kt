package com.tropicalstream.hammerklavier.contract

/** Where the listener's ears are and which way they face (PLAN §3.12, §5.6). */
class ListenerPose(val earRoom: FloatArray /*3, m*/, val forwardYawRad: Float, val worldLocked: Boolean, val directWidth: Float)

/** A prepared room for one (instrument, placement, listener, mode). Immutable; construct with named arguments. */
class RoomDesign(
    @JvmField val erDelay: IntArray, @JvmField val erGainL: FloatArray, @JvmField val erGainR: FloatArray,  // 12 taps
    @JvmField val erBright: BooleanArray, val brightLpHz: Float, val dullLpHz: Float, val preDelayFrames: Int,
    val t60Low: Float, val t60Mid: Float, val t60High: Float,         // s at 125 Hz, mean of 500 Hz and 1 kHz, 8 kHz (§3.12; the §2.3 comment said 4 kHz)
    val reverbGain: Float,                                            // reverberant level re direct at 1 m, incl. ReverbMode and embedded-room compensation
    val erGain: Float,                                                // early-reflection send, incl. embedded-room compensation
    val directGain: Float, val airLpHz: Float, val width: Float,
    val worldLocked: Boolean, val sourceAzimuthRad: Float)            // azimuth of the source in the room (yaw convention of §2.3)

/** WP3 RoomAcoustics; pure, main/HKLoader. */
interface RoomDesigner {
    fun design(g: VenueGeometry, placement: Placement, sourcePiano: FloatArray, listener: ListenerPose,
               mode: ReverbMode, benchDistanceM: Float, embeddedRoomDb: Float): RoomDesign
}

interface ResonanceProcessor {
    /** Off-thread tables: delays, allpass, dispersion, comb gain per (key, D step). */
    fun prepare(keyMap: KeyMap, info: BankInfo, profile: InstrumentProfile): Any
    /** HKAudio: swap, gliding delays and gains (200 ms on retune). */
    fun apply(prepared: Any, glideMs: Int)
    /** WP3 owns SEND and UNA_CORDA_SEND. */
    fun setMode(mode: ResonanceMode, instrument: InstrumentId, maxActive: Int, dispersion: Boolean)
    /** Adds into out. */
    fun process(mix: FloatArray /*mono voices, n*/, self: FloatArray /*88 × BLOCK: row i = key 21+i's own voices*/,
                selfRows: BooleanArray /*88: rows written this block*/, gate: FloatArray /*128, 0..1*/,
                softFeed: BooleanArray /*128*/, damping: FloatArray /*128, D*/, outL: FloatArray, outR: FloatArray, n: Int)
    /** ADDS each comb's mean-square of the last block. */
    fun energy(outMeanSquare: FloatArray /*88*/)
    val active: Int
    fun reset()
}

/** Direct path + early reflections + FDN. */
interface RoomProcessor {
    fun setDesign(d: RoomDesign, glideMs: Int); fun setLines(n: Int)
    /** Pause/resume/seek fade of the room input. */
    fun setInputGain(g: Float, rampMs: Float)
    /** Writes out. */
    fun process(inL: FloatArray, inR: FloatArray, outL: FloatArray, outR: FloatArray, n: Int, headYawRad: Float)
    /** false once the tail is below -90 dBFS. */
    val tailActive: Boolean
    fun reset()
}

/** Adds into out. */
interface SoftBusProcessor { fun configure(kind: SoftKind); fun process(inL: FloatArray, inR: FloatArray, outL: FloatArray, outR: FloatArray, n: Int); fun reset() }

interface MasterProcessor {
    fun setRoute(r: OutputRoute); fun setSpeakerBass(m: SpeakerBass); fun setGain(linear: Float)
    fun process(l: FloatArray, r: FloatArray, n: Int, outInterleaved: FloatArray); fun reset()
    /** Frames by which process() delays its output (the limiter's lookahead); M2 contract addition. */
    val latencyFrames: Int get() = 0 }

class DspSet(val resonance: ResonanceProcessor, val room: RoomProcessor, val soft: SoftBusProcessor, val master: MasterProcessor)
