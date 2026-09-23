package com.tropicalstream.hammerklavier.contract

/** CommandRing codes (l, f, ref as noted). */
object Cmd {
    const val SET_BANK = 1       // ref = prepared bank token from EngineCoreApi.prepareBank
    const val SET_KEYMAP = 2     // ref = prepared key-map token
    const val SET_PERF = 3       // ref = Performance?, l = startUs (−1 = the engine's own current song position), f = 1 autoplay
    const val PLAY = 4
    const val PAUSE = 5          // l = fade ms (60 default; 300 when leaving the app)
    const val SEEK = 6           // l = song µs
    const val RATE = 7           // f = 0.5..1.5
    const val QUALITY = 8        // ref = QualityProfile
    const val ROOM = 9           // ref = RoomDesign, l = glide ms
    const val MIX = 10           // ref = MixSettings
    const val ROUTE = 11         // l = OutputRoute.ordinal, f = measured output latency ms (for yaw prediction)
    const val DUCK = 12          // f = gain
    const val REGISTRATION = 13  // l = mask; applied at the block boundary and published in CoreClockState
    const val VOICE_CAP = 14     // l = cap (AudioOutput's self-protection)
    const val BENCH = 15         // l = seconds
    const val RESET = 16
}

class MixSettings(val reverb: ReverbMode, val resonance: ResonanceMode,
    val speakerBass: SpeakerBass, val masterDb: Float, val releaseNoises: Boolean = true, val pedalNoises: Boolean = true)

class AudioStats {
    @JvmField var voices = 0; @JvmField var voicesPeak = 0; @JvmField var noiseVoices = 0; @JvmField var stolen = 0; @JvmField var dropped = 0
    @JvmField var combsActive = 0; @JvmField var blockP50Us = 0; @JvmField var blockP99Us = 0; @JvmField var blockMaxUs = 0; @JvmField var cpuPct = 0f
    @JvmField var underruns = 0; @JvmField var slowReads = 0; @JvmField var bufferFrames = 0; @JvmField var voiceCap = 0
    @JvmField var headroomMinFrames = 0; @JvmField var parked = false; @JvmField var trackRebuilds = 0; @JvmField var energyMiss = 0
    @JvmField var epoch = 0; @JvmField var generation = -1; @JvmField var bankGeneration = -1; @JvmField var bankStub = false
    @JvmField var fastTrack = false; @JvmField var tid = 0                // HKAudio kernel tid, for the majflt probe
}

/** Main; posted with preallocated Runnables. */
interface AudioListener {
    fun onEnded(generation: Int); fun onOverload(newCap: Int); fun onEngineError(code: StatusCode, detail: String)
    fun onRouteChanged(route: RouteInfo) {}
}

/** Main thread; WP4 AudioOutput. */
interface AudioControl {
    val clock: SongClock
    val energy: EnergyRing
    val route: RouteInfo
    /**
     * Idempotent; start() after stop() re-sends the last bank, key map and Performance and
     * restores the paused position; stop joins ≤ 350 ms.
     */
    fun start(); fun stop()
    /** 30 ms crossfade at a block boundary. */
    fun setBank(bank: LoadedBank, keyMap: KeyMap, profile: InstrumentProfile)
    /** Tuning/readiness change; new notes only. */
    fun setKeyMap(keyMap: KeyMap)
    /** startUs −1 = keep the engine's position. */
    fun setPerformance(p: Performance?, startUs: Long, autoPlay: Boolean)
    fun play(); fun pause(fadeMs: Int = 60); fun seek(us: Long); fun setRate(rate: Float)
    fun setQuality(q: QualityProfile); fun setRoom(d: RoomDesign, glideMs: Int); fun setMix(m: MixSettings)
    fun setRegistration(mask: Int); fun setDuck(gain: Float); fun bench(seconds: Int)
    fun setListener(l: AudioListener?); fun stats(out: AudioStats); fun clockStats(out: ClockStats)
    fun diagnostics(): Map<String, String> = emptyMap()
}

/**
 * WP2 EngineCore; HKAudio except prepare*. on() = drained commands.
 * Engine construction (frozen): EngineCore(dsp: DspSet, cursors: VoiceCursorBoard, head: HeadPose, sampleRate: Int = HK.SR)
 */
interface EngineCoreApi : CommandHandler {
    /** Any thread; allocates tables. */
    fun prepareBank(bank: LoadedBank, keyMap: KeyMap, profile: InstrumentProfile): Any
    /** Any thread. */
    fun prepareKeyMap(keyMap: KeyMap, info: BankInfo, profile: InstrumentProfile): Any
    fun render(out: FloatArray /*2 × BLOCK interleaved*/, blockStartFrame: Long)
    /** For the block just rendered. */
    fun clockState(out: CoreClockState)
    /** For the block just rendered; returns its epoch. */
    fun energy(outLanes: FloatArray /*88, linear RMS*/): Int
    fun stats(out: AudioStats)
    fun reset()
    /** T-ALIGN hook (§8.4). */
    fun debugOnset(out: LongArray /*[scheduledFrame, detectedFrame]*/): Boolean = false
}
