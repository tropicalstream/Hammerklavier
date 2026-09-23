package com.tropicalstream.hammerklavier.contract.stub

import com.tropicalstream.hammerklavier.contract.AudioControl
import com.tropicalstream.hammerklavier.contract.AudioListener
import com.tropicalstream.hammerklavier.contract.AudioStats
import com.tropicalstream.hammerklavier.contract.ClockStats
import com.tropicalstream.hammerklavier.contract.EnergyRing
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.KeyMap
import com.tropicalstream.hammerklavier.contract.LoadedBank
import com.tropicalstream.hammerklavier.contract.MixSettings
import com.tropicalstream.hammerklavier.contract.OutputRoute
import com.tropicalstream.hammerklavier.contract.Performance
import com.tropicalstream.hammerklavier.contract.QualityProfile
import com.tropicalstream.hammerklavier.contract.RoomDesign
import com.tropicalstream.hammerklavier.contract.RouteInfo
import com.tropicalstream.hammerklavier.contract.SongClock

/**
 * Silent AudioControl (PLAN §2.3): keeps the transport state and publishes it through a
 * [FakeClock]; route = speaker. The end of a performance is noticed when [stats] is polled (the
 * 1 Hz HUD tick): the clock pauses and the listener's onEnded fires once per generation.
 */
class NullAudio(val fake: FakeClock = FakeClock()) : AudioControl {
    override val clock: SongClock get() = fake
    override val energy: EnergyRing = EnergyRing()
    override val route: RouteInfo = SPEAKER

    private var listener: AudioListener? = null
    private var perf: Performance? = null
    private var started = false
    private var endedGeneration = -1
    private var voiceCap = 96

    var lastBank: LoadedBank? = null; private set
    var lastKeyMap: KeyMap? = null; private set
    var lastProfile: InstrumentProfile? = null; private set
    var lastQuality: QualityProfile? = null; private set
    var lastRoom: RoomDesign? = null; private set
    var lastMix: MixSettings? = null; private set
    var registration = 3; private set
    var duck = 1f; private set

    override fun start() { started = true }
    override fun stop() { fake.pause(); started = false }

    override fun setBank(bank: LoadedBank, keyMap: KeyMap, profile: InstrumentProfile) {
        lastBank = bank; lastKeyMap = keyMap; lastProfile = profile
    }
    override fun setKeyMap(keyMap: KeyMap) { lastKeyMap = keyMap }

    override fun setPerformance(p: Performance?, startUs: Long, autoPlay: Boolean) {
        val keep = if (startUs < 0) fake.songUsNow() else startUs
        perf = p
        fake.pause()
        fake.setPerformance(p?.generation ?: -1, if (p == null) 0L else keep, p?.durationUs ?: Long.MAX_VALUE)
        if (autoPlay && p != null) fake.play()
    }

    override fun play() { if (perf != null) fake.play() }
    override fun pause(fadeMs: Int) { fake.pause() }
    override fun seek(us: Long) { fake.seek(us) }
    override fun setRate(rate: Float) { fake.setRate(rate.coerceIn(0.5f, 1.5f)) }
    override fun setQuality(q: QualityProfile) { lastQuality = q; voiceCap = q.voiceCap }
    override fun setRoom(d: RoomDesign, glideMs: Int) { lastRoom = d }
    override fun setMix(m: MixSettings) { lastMix = m }
    override fun setRegistration(mask: Int) { registration = mask; fake.setRegistration(mask) }
    override fun setDuck(gain: Float) { duck = gain }
    override fun bench(seconds: Int) {}
    override fun setListener(l: AudioListener?) { listener = l }

    override fun stats(out: AudioStats) {
        val p = perf
        if (p != null && fake.atEnd() && endedGeneration != p.generation) {
            endedGeneration = p.generation
            fake.pause()
            listener?.onEnded(p.generation)
        }
        out.voices = 0; out.voicesPeak = 0; out.noiseVoices = 0; out.stolen = 0; out.dropped = 0
        out.combsActive = 0; out.blockP50Us = 0; out.blockP99Us = 0; out.blockMaxUs = 0; out.cpuPct = 0f
        out.underruns = 0; out.slowReads = 0; out.bufferFrames = 0; out.voiceCap = voiceCap
        out.headroomMinFrames = 0; out.parked = !started; out.trackRebuilds = 0; out.energyMiss = energy.misses
        out.generation = fake.currentGeneration; out.bankGeneration = lastBank?.generation ?: -1
        out.bankStub = lastBank?.info?.isStub ?: false; out.fastTrack = false; out.tid = 0
    }

    override fun clockStats(out: ClockStats) {
        out.tsAccepted = 0; out.tsRejected = 0; out.clockMiss = 0; out.latFrames = 0
        out.fsFit = 48000f; out.driftP99Frames = 0f; out.session = 0
    }

    override fun diagnostics(): Map<String, String> = mapOf("audio" to "NullAudio", "started" to started.toString())

    companion object {
        /** AudioDeviceInfo.TYPE_BUILTIN_SPEAKER = 2. */
        val SPEAKER = RouteInfo(route = OutputRoute.SPEAKER, key = "speaker", deviceType = 2, name = "speaker", outputFlags = "")
    }
}
