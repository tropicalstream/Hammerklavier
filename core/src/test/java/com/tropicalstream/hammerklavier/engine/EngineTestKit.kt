package com.tropicalstream.hammerklavier.engine

import com.tropicalstream.hammerklavier.contract.BankInfo
import com.tropicalstream.hammerklavier.contract.Cmd
import com.tropicalstream.hammerklavier.contract.DspSet
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.HeadPose
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.KeyMap
import com.tropicalstream.hammerklavier.contract.LoadedBank
import com.tropicalstream.hammerklavier.contract.MasterProcessor
import com.tropicalstream.hammerklavier.contract.MixSettings
import com.tropicalstream.hammerklavier.contract.OutputRoute
import com.tropicalstream.hammerklavier.contract.PedalCurve
import com.tropicalstream.hammerklavier.contract.Performance
import com.tropicalstream.hammerklavier.contract.QualityLadder
import com.tropicalstream.hammerklavier.contract.ResonanceMode
import com.tropicalstream.hammerklavier.contract.ReverbMode
import com.tropicalstream.hammerklavier.contract.SampleReader
import com.tropicalstream.hammerklavier.contract.ScoreSpec
import com.tropicalstream.hammerklavier.contract.SpeakerBass
import com.tropicalstream.hammerklavier.contract.VoiceCursorBoard
import com.tropicalstream.hammerklavier.contract.stub.KeyMapFixtures
import com.tropicalstream.hammerklavier.contract.stub.PassThroughDsp
import com.tropicalstream.hammerklavier.contract.stub.PerfFixtures
import com.tropicalstream.hammerklavier.contract.stub.SineBank
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// Private test helpers of WP2 (not shared fixtures).

/** A master stage without the soft clip: out = in × gain, so levels and bits can be checked exactly. */
class IdentityMaster : MasterProcessor {
    private var gain = 1f
    override fun setRoute(r: OutputRoute) {}
    override fun setSpeakerBass(m: SpeakerBass) {}
    override fun setGain(linear: Float) { gain = linear }
    override fun process(l: FloatArray, r: FloatArray, n: Int, outInterleaved: FloatArray) {
        for (i in 0 until n) { outInterleaved[2 * i] = l[i] * gain; outInterleaved[2 * i + 1] = r[i] * gain }
    }
    override fun reset() {}
}

fun identityDsp(resonance: com.tropicalstream.hammerklavier.contract.ResonanceProcessor = PassThroughDsp.Resonance()): DspSet =
    DspSet(resonance = resonance, room = PassThroughDsp.Room(), soft = PassThroughDsp.Soft(), master = IdentityMaster())

/** One note in file time (µs from 0, without the pre-roll). */
class N(val onMs: Double, val offMs: Double, val key: Int, val vel: Int)

fun spec(notes: List<N>, cc64: PedalCurve = PedalCurve.EMPTY, cc66: PedalCurve = PedalCurve.EMPTY, cc67: PedalCurve = PedalCurve.EMPTY): ScoreSpec {
    val s = notes.sortedWith(compareBy({ it.onMs }, { it.key }))
    return ScoreSpec(onUs = LongArray(s.size) { Math.round(s[it].onMs * 1000) }, offUs = LongArray(s.size) { Math.round(s[it].offMs * 1000) },
        key = ByteArray(s.size) { s[it].key.toByte() }, vel = ByteArray(s.size) { s[it].vel.toByte() }, cc64 = cc64, cc66 = cc66, cc67 = cc67)
}

/** A Performance from notes given with their SONG times in µs (pre-roll included), as the engine sees them. */
fun perfSong(notes: List<N>, profile: InstrumentProfile = InstrumentProfile.GRAND, sustainSong: PedalCurve = PedalCurve.EMPTY,
             softSong: PedalCurve = PedalCurve.EMPTY, sostenutoSong: PedalCurve = PedalCurve.EMPTY, generation: Int = 1): Performance {
    val pre = HK.PRE_ROLL_US / 1000.0
    val shifted = notes.map { N(it.onMs - pre, it.offMs - pre, it.key, it.vel) }
    return PerfFixtures.build(notes = spec(shifted), sustain = shiftCurve(sustainSong, -HK.PRE_ROLL_US), soft = shiftCurve(softSong, -HK.PRE_ROLL_US),
        sostenuto = shiftCurve(sostenutoSong, -HK.PRE_ROLL_US), profile = profile, generation = generation)
}

fun shiftCurve(c: PedalCurve, d: Long): PedalCurve =
    if (c.isEmpty) PedalCurve.EMPTY else PedalCurve(LongArray(c.us.size) { c.us[it] + d }, c.v.copyOf())

/** A constant pedal value from song time 0 (µs incl. pre-roll) on. */
fun constCurve(v: Float): PedalCurve = PedalCurve(longArrayOf(0L, 0L), floatArrayOf(0f, v))

/** A step curve in song µs: (t0, v0), (t1, v1) … each held until the next. */
fun stepCurve(vararg tv: Pair<Long, Float>): PedalCurve {
    val us = ArrayList<Long>(); val v = ArrayList<Float>()
    var prev = 0f
    for ((t, x) in tv) { us.add(t); v.add(prev); us.add(t); v.add(x); prev = x }
    return PedalCurve(us.toLongArray(), v.toFloatArray())
}

/** Song µs of output frame [f] at rate 1. */
fun usOfFrame(f: Long): Long = Math.round(f / 0.048)

/**
 * A LoadedBank = a SineBank plus extra in-memory regions (releases, pedal noises, custom waves),
 * with an overridable BankInfo (e.g. releaseCarriesTail).
 */
class TestBank(val sine: SineBank, private val extra: List<ShortArray>, private val extraOnset: IntArray,
               releaseCarriesTail: Boolean = sine.info.releaseCarriesTail, instrument: InstrumentId = sine.info.instrument,
               override val generation: Int = 7) : LoadedBank {
    private val n0 = sine.regionCount
    private val envs = extra.map { envOf(it) }
    override val info: BankInfo = with(sine.info) {
        BankInfo(instrument = instrument, kit = "test", version = version, sha1 = sha1, layers = layers, stops = stops,
            lastDamper = InstrumentProfile.of(instrument).lastDamper, recordedAHz = recordedAHz, aOffsetCents = aOffsetCents,
            damperT60 = FloatArray(HK.KEYS) { InstrumentProfile.of(instrument).defaultDamperT60(it) },
            freeT60 = freeT60, inharmB = inharmB, releaseCarriesTail = releaseCarriesTail, embeddedRoomDb = embeddedRoomDb,
            decodeOrder = decodeOrder, isStub = true, fallback = null)
    }
    override val regionCount: Int get() = n0 + extra.size
    fun extraRegion(i: Int): Int = n0 + i
    override fun frames(region: Int): Int = if (region < n0) sine.frames(region) else extra[region - n0].size / 2
    override fun onsetFrame(region: Int): Int = if (region < n0) sine.onsetFrame(region) else extraOnset[region - n0]
    override fun thrFrame(region: Int): Int = if (region < n0) sine.thrFrame(region) else extraOnset[region - n0]
    override fun envByte(region: Int, tenMs: Int): Int {
        if (region < n0) return sine.envByte(region, tenMs)
        val e = envs[region - n0]
        return if (tenMs < 0 || tenMs >= e.size) 255 else e[tenMs]
    }
    override fun newReader(): SampleReader {
        val base = sine.newReader()
        return object : SampleReader {
            override fun read(region: Int, fromFrame: Int, frames: Int, dst: ShortArray, dstOff: Int): Int {
                if (region < n0) return base.read(region, fromFrame, frames, dst, dstOff)
                val w = extra[region - n0]; val nf = w.size / 2
                var c = 0
                for (i in 0 until frames) {
                    val f = fromFrame + i
                    if (f in 0 until nf) { dst[dstOff + 2 * i] = w[2 * f]; dst[dstOff + 2 * i + 1] = w[2 * f + 1]; c++ }
                    else { dst[dstOff + 2 * i] = 0; dst[dstOff + 2 * i + 1] = 0 }
                }
                return c
            }
            override fun prefetch(region: Int, fromFrame: Int, frames: Int) {}
            override val slowReads: Int get() = 0
        }
    }
    @Volatile override var readyMask: Long = -1L

    companion object {
        fun envOf(w: ShortArray): IntArray {
            val nf = w.size / 2
            return IntArray(nf / 480) { b ->
                var s = 0.0
                for (t in b * 480 until (b + 1) * 480) { val x = w[2 * t] / 32768.0; s += x * x }
                val rms = sqrt(s / 480)
                val db = if (rms <= 1e-9) -200.0 else 20 * log10(rms)
                (-db * 2).roundToInt().coerceIn(0, 255)
            }
        }

        /** A decaying sine region: silent until [onset], then amplitude [peak] (linear FS) decaying with [t60] s. */
        fun decayingSine(hz: Double, seconds: Double, peak: Double, t60: Double, onset: Int = 96, attackFrames: Int = 0,
                         endFadeFrames: Int = 2400, phase: Double = 0.0): ShortArray {
            val nf = (seconds * HK.SR).toInt()
            val out = ShortArray(2 * nf)
            val k = 6.907755 / t60
            for (t in onset until nf) {
                val u = (t - onset).toDouble() / HK.SR
                var a = peak * exp(-k * u)
                if (attackFrames > 0 && t - onset < attackFrames) a *= 0.5 - 0.5 * kotlin.math.cos(PI * (t - onset) / attackFrames)
                if (nf - 1 - t < endFadeFrames) a *= (nf - 1 - t).toDouble() / endFadeFrames
                val s = (a * 32767 * sin(2 * PI * hz * u + phase)).roundToInt().coerceIn(-32768, 32767).toShort()
                out[2 * t] = s; out[2 * t + 1] = s
            }
            return out
        }
    }
}

/** A copy of [km] with some arrays replaced (named arguments, as the growth rules require). */
fun KeyMap.copy(rate: FloatArray = this.rate, onsetOut: IntArray = this.onsetOut, gain: FloatArray = this.gain,
                velGainA: FloatArray = this.velGainA, velGainB: FloatArray = this.velGainB,
                release: IntArray = this.release, releaseRate: FloatArray = this.releaseRate, releaseGain: FloatArray = this.releaseGain,
                pedalDown: IntArray = this.pedalDown, pedalUp: IntArray = this.pedalUp, pedalGain: Float = this.pedalGain,
                region: IntArray = this.region, lpHz: FloatArray = this.lpHz): KeyMap =
    KeyMap(tuning = tuning, readyMask = readyMask, layers = layers, stops = stops, velLayerA = velLayerA, velLayerB = velLayerB,
        velGainA = velGainA, velGainB = velGainB, region = region, rate = rate, gain = gain, onsetOut = onsetOut, lpHz = lpHz,
        release = release, releaseRate = releaseRate, releaseGain = releaseGain, pedalDown = pedalDown, pedalUp = pedalUp,
        pedalGain = pedalGain, f0Hz = f0Hz, inharmB = inharmB, strings = strings)

/** [km] with key [key] of every (stop, layer) playing at [rate] (onsetOut recomputed). */
fun KeyMap.withRate(key: Int, rate: Float, stop: Int = -1): KeyMap {
    val r = this.rate.copyOf(); val o = onsetOut.copyOf()
    for (s in 0 until stops) for (l in 0 until layers) {
        if (stop >= 0 && s != stop) continue
        val sk = (s * layers + l) * HK.KEYS + key
        r[sk] = rate; o[sk] = (SineBank.ONSET / rate).roundToInt()
    }
    return copy(rate = r, onsetOut = o)
}

/**
 * Drives one EngineCore block by block and keeps its interleaved output. Commands go straight to
 * `core.on` at the block boundary, as CommandRing.drain would deliver them.
 */
class Harness(val profile: InstrumentProfile = InstrumentProfile.GRAND, val bank: LoadedBank = SineBank(layers = 1),
              keyMap: KeyMap = KeyMapFixtures.forSineBank(1), val dsp: DspSet = identityDsp()) {
    val cursors = VoiceCursorBoard()
    val head = HeadPose()
    val core = EngineCore(dsp, cursors, head)
    private val block = FloatArray(2 * HK.BLOCK)
    var out = FloatArray(2 * HK.SR * 4); private set
    var frames = 0; private set
    /** Called after every rendered block with the block's start frame (index into [out]). */
    var onBlock: ((Int) -> Unit)? = null

    init {
        setBank(bank, keyMap, profile)
        core.on(Cmd.QUALITY, 0L, 0f, QualityLadder.of(0, 128))
    }

    fun setBank(b: LoadedBank, km: KeyMap, p: InstrumentProfile = profile) = cmd(Cmd.SET_BANK, ref = core.prepareBank(b, km, p))
    fun cmd(code: Int, l: Long = 0L, f: Float = 0f, ref: Any? = null) = core.on(code, l, f, ref)
    fun play(p: Performance, startUs: Long = 0L) = cmd(Cmd.SET_PERF, startUs, 1f, p)
    fun mix(masterDb: Float = 0f, releaseNoises: Boolean = true, pedalNoises: Boolean = true) =
        cmd(Cmd.MIX, ref = MixSettings(reverb = ReverbMode.DRY, resonance = ResonanceMode.NATURAL, speakerBass = SpeakerBass.OFF,
            masterDb = masterDb, releaseNoises = releaseNoises, pedalNoises = pedalNoises))

    fun renderBlocks(n: Int) {
        for (b in 0 until n) {
            if (frames + HK.BLOCK > out.size / 2) out = out.copyOf(out.size * 2)
            core.render(block, frames.toLong())
            System.arraycopy(block, 0, out, 2 * frames, 2 * HK.BLOCK)
            val start = frames
            frames += HK.BLOCK
            onBlock?.invoke(start)
        }
    }

    /** Renders until at least [frame] frames exist. */
    fun renderTo(frame: Int) { while (frames < frame) renderBlocks(1) }

    fun left(i: Int): Float = out[2 * i]
    fun right(i: Int): Float = out[2 * i + 1]

    /** First frame ≥ [from] with |L| > [eps]; −1 if none before [frames]. */
    fun firstAbove(eps: Float, from: Int = 0): Int {
        for (i in from until frames) if (abs(out[2 * i]) > eps) return i
        return -1
    }

    /** RMS (of (L² + R²)/2) over [a, b). */
    fun rms(a: Int, b: Int): Double {
        var s = 0.0
        for (i in a until b) { val l = out[2 * i].toDouble(); val r = out[2 * i + 1].toDouble(); s += 0.5 * (l * l + r * r) }
        return sqrt(s / (b - a).coerceAtLeast(1))
    }

    fun maxAbs(a: Int, b: Int): Float { var m = 0f; for (i in a until b) m = maxOf(m, abs(out[2 * i]), abs(out[2 * i + 1])); return m }

    internal fun voicesOf(key: Int): List<Voice> = core.pool.voices.filter { it.state != Voice.IDLE && it.key == key && it.role == Voice.ROLE_MAIN }
    internal fun liveVoices(): List<Voice> = core.pool.voices.filter { it.state != Voice.IDLE }
}

fun db(x: Double): Double = 20 * log10(x.coerceAtLeast(1e-30))

/** Magnitude of the DTFT of x (Hann window) at [hz]. */
fun dtftMag(x: FloatArray, hz: Double, sr: Int = HK.SR): Double {
    var re = 0.0; var im = 0.0
    val n = x.size
    for (i in 0 until n) {
        val w = 0.5 - 0.5 * kotlin.math.cos(2 * PI * i / (n - 1))
        val ph = 2 * PI * hz * i / sr
        re += w * x[i] * kotlin.math.cos(ph); im -= w * x[i] * sin(ph)
    }
    return sqrt(re * re + im * im)
}

/** The frequency of the spectral peak of [x] in [lo, hi] Hz, by golden-section search on the DTFT magnitude. */
fun peakHz(x: FloatArray, lo: Double, hi: Double): Double {
    val g = (sqrt(5.0) - 1) / 2
    var a = lo; var b = hi
    var c = b - g * (b - a); var d = a + g * (b - a)
    var fc = dtftMag(x, c); var fd = dtftMag(x, d)
    repeat(60) {
        if (fc > fd) { b = d; d = c; fd = fc; c = b - g * (b - a); fc = dtftMag(x, c) }
        else { a = c; c = d; fc = fd; d = a + g * (b - a); fd = dtftMag(x, d) }
    }
    return (a + b) / 2
}

fun cents(f: Double, ref: Double): Double = 1200 * kotlin.math.ln(f / ref) / kotlin.math.ln(2.0)

fun hz(key: Int): Double = 440.0 * 2.0.pow((key - 69) / 12.0)
