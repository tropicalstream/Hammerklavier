package com.tropicalstream.hammerklavier.kit

import com.tropicalstream.hammerklavier.contract.BankInfo
import com.tropicalstream.hammerklavier.contract.FallbackReason
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.KeyMap
import com.tropicalstream.hammerklavier.contract.LoadedBank
import com.tropicalstream.hammerklavier.contract.SampleReader
import com.tropicalstream.hammerklavier.contract.TuningSpec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The production stand-in bank (PLAN §3.19): used when no Opus decoder works or the DecoderProbe
 * fails. Built entirely in code, deterministically (same instrument → bit-identical PCM), on
 * HKLoader; nothing is computed on the audio thread.
 *
 * Each region is additive: up to 24 slightly inharmonic partials `n·f0·√(1+B n²)` with a
 * two-stage decay (a fast 0.25 s prompt sound over a slow aftersound from the instrument's free
 * T60), a sharp onset at frame [ONSET], a 300 ms raised-cosine end fade, peak-normalised to
 * −3 dBFS; `gainDb` restores the layer's level. Pianos: roots every 3 semitones from 21 (30
 * roots), 2 layers (soft 1–64, loud 65–127) crossfaded ±4 with equal gain. Harpsichord: roots
 * every 3 from 29 to 89, one layer, the 8′ and 4′ stops (the 4′ sounds an octave up).
 *
 * The bank describes itself as a [KitIndex] ([index]), so [KeyMapBuilder] maps it exactly like a
 * real kit ([keyMap]). No release or pedal regions. Unit u = stop · layers + layer; all ready.
 * PCM is mono internally and read out as interleaved stereo.
 */
class SynthBank(val instrument: InstrumentId, override val generation: Int = 1,
                val fallback: FallbackReason? = FallbackReason.DECODER_UNAVAILABLE) : LoadedBank {

    private val profile = InstrumentProfile.of(instrument)
    private val harpsichord = instrument == InstrumentId.HARPSICHORD
    val layers = if (harpsichord) 1 else 2
    val stops = if (harpsichord) 2 else 1
    private val roots: IntArray = if (harpsichord) IntArray(21) { 29 + 3 * it } else IntArray(30) { 21 + 3 * it }
    private val pcm: Array<ShortArray>
    val index: KitIndex

    init {
        val regions = ArrayList<RegionDef>()
        val waves = ArrayList<ShortArray>()
        val env = java.io.ByteArrayOutputStream()
        val unitFrames = IntArray(stops * layers)
        for (stop in 0 until stops) for (layer in 0 until layers) {
            val unit = stop * layers + layer
            for ((ri, root) in roots.withIndex()) {
                val sounding = root + 12 * stop
                val w = synth(sounding, layer, stop)
                val id = regions.size
                val lo = if (ri == 0) (if (harpsichord) 29 else 21) else root - 1
                val hi = if (ri == roots.size - 1) (if (harpsichord) 89 else 108) else root + 1
                val (bytes, thr) = analyse(w)
                regions += RegionDef(id = id, kind = RegionKind.SUSTAIN, unit = unit, streamStart = unitFrames[unit],
                    frames = w.size, stop = stop, layer = layer, root = root, lo = lo, hi = hi, rr = 0,
                    onsetFrame = ONSET, thrFrame = thr, pitchCents = 1200f * stop, gainDb = layerDb(layer),
                    envOffset = env.size(), envCount = bytes.size)
                env.write(bytes)
                unitFrames[unit] += w.size + GAP
                waves += w
            }
        }
        pcm = waves.toTypedArray()
        val layerDefs = if (harpsichord) listOf(LayerDef(0, 1, 127, 64, 0))
                        else listOf(LayerDef(0, 1, 64, 40, 0), LayerDef(1, 65, 127, 100, 1))
        val curve = layerDefs.map { LevelPoint(it.velRef, layerDb(it.index)) }
        val stopDefs = if (harpsichord) listOf(StopDef(0, "8'"), StopDef(1, "4'")) else listOf(StopDef(0, "main"))
        val units = (0 until stops * layers).map { u ->
            UnitDef(u, if (harpsichord) (if (u == 0) "8'" else "4'") else "v${u + 1}", u, "u/$u.opus", unitFrames[u], "0".repeat(40))
        }
        index = KitIndex(schema = 2, instrument = instrument.key, kit = "synth", version = "2026.09.0",
            sha1 = "5".repeat(40), mode = if (harpsichord) "HARD" else "XFADE", xfadeSteps = if (harpsichord) 0 else 4,
            xfadeLaw = if (harpsichord) emptyList() else listOf("gain"),
            lastDamper = profile.lastDamper, aOffsetCents = 0f, recordedAHz = 440f, pedalGainDb = -20f,
            releaseCarriesTail = false, embeddedRoomDb = 30f, embeddedEdtS = 0f,
            layers = layerDefs, stops = stopDefs, levelCurve = List(stops) { curve }, units = units, regions = regions,
            stretchCents = FloatArray(HK.KEYS), inharmB = FloatArray(HK.KEYS) { inharm(it) },
            damperT60 = FloatArray(HK.KEYS) { profile.defaultDamperT60(it) },
            freeT60 = Array(stops) { s -> FloatArray(HK.KEYS) { profile.defaultFreeT60(s, it) } },
            releaseRule = ReleaseRule(relGainDb = -12f, velExp = 0.7f, ageTauS = 3f, floor = 0.05f, heldDb = -6f),
            credit = "Hammerklavier stand-in tones (synthesized)", source = "in-code additive synthesis", env = env.toByteArray())
    }

    override val info: BankInfo = index.toBankInfo(id = instrument, isStub = true, fallback = fallback)
    override val regionCount: Int get() = pcm.size
    override fun frames(region: Int): Int = pcm[region].size
    override fun onsetFrame(region: Int): Int = ONSET
    override fun thrFrame(region: Int): Int = index.regions[region].thrFrame
    override fun envByte(region: Int, tenMs: Int): Int = index.envByte(region, tenMs)
    override fun newReader(): SampleReader = Reader()
    @Volatile override var readyMask: Long = (1L shl (stops * layers)) - 1

    /** The KeyMap for [tuning] (HKLoader). */
    fun keyMap(tuning: TuningSpec): KeyMap = KeyMapBuilder.build(index, tuning, readyMask)

    /** Mono PCM of a region (tests). */
    fun pcmOf(region: Int): ShortArray = pcm[region]

    private inner class Reader : SampleReader {
        override fun read(region: Int, fromFrame: Int, frames: Int, dst: ShortArray, dstOff: Int): Int {
            if (frames <= 0) return 0
            val w = pcm[region]
            var o = dstOff
            var got = 0
            var f = fromFrame
            val end = fromFrame + frames
            while (f < end) {
                val s: Short = if (f >= 0 && f < w.size) { got++; w[f] } else 0
                dst[o] = s; dst[o + 1] = s
                o += 2; f++
            }
            return got
        }
        override fun prefetch(region: Int, fromFrame: Int, frames: Int) {}
        override val slowReads: Int get() = 0
    }

    private fun layerDb(layer: Int): Float = if (harpsichord) 0f else if (layer == 0) -14f else 0f

    private fun inharm(key: Int): Float =
        if (harpsichord) 0f else (1.2e-4 * 2.0.pow((key - 60) / 18.0)).toFloat().coerceAtMost(2e-3f)

    private fun durationS(key: Int): Double {
        val (a, b) = if (harpsichord) 3.0 to 1.5 else 4.0 to 1.5
        val x = ((key - 21) / 87.0).coerceIn(0.0, 1.0)
        return a + (b - a) * x
    }

    private fun synth(sounding: Int, layer: Int, stop: Int): ShortArray {
        val fs = HK.SR.toDouble()
        val n = (durationS(sounding - 12 * stop) * fs).toInt()
        val x = DoubleArray(n)
        val f0 = 440.0 * 2.0.pow((sounding - 69) / 12.0)
        val b = inharm(sounding).toDouble()
        val t60 = profile.defaultFreeT60(stop, sounding.coerceIn(0, 127)).toDouble().coerceIn(0.5, 30.0)
        val tauSlow = t60 / 6.908
        val tauFast = 0.25
        val bright = if (harpsichord) 0.75 else if (layer == 0) 1.7 else 1.05
        val prompt = if (harpsichord) 0.35 else 0.6
        for (p in 1..24) {
            val f = p * f0 * sqrt(1.0 + b * p * p)
            if (f >= 16_000.0) break
            val amp = 1.0 / p.toDouble().pow(bright) * (if (harpsichord && p % 2 == 0) 0.8 else 1.0)
            val kScale = 1.0 + 0.35 * (p - 1)
            val dFast = exp(-1.0 / (tauFast / kScale * fs)); val dSlow = exp(-1.0 / (tauSlow / kScale * fs))
            val w = 2.0 * PI * f / fs
            val cw = cos(w); val sw = sin(w)
            var c = 1.0; var s = 0.0
            var eF = prompt * amp; var eS = (1.0 - prompt) * amp
            for (t in ONSET until n) {
                x[t] += (eF + eS) * c
                val c2 = c * cw - s * sw; s = s * cw + c * sw; c = c2
                eF *= dFast; eS *= dSlow
            }
        }
        val fade = (0.3 * fs).toInt().coerceAtMost(n - ONSET - 1)
        for (k in 0 until fade) x[n - 1 - k] *= 0.5 - 0.5 * cos(PI * k / fade)
        var peak = 0.0
        for (v in x) peak = maxOf(peak, abs(v))
        val scale = if (peak > 0) 10.0.pow(-3.0 / 20.0) * 32767.0 / peak else 0.0
        return ShortArray(n) { (x[it] * scale).roundToInt().coerceIn(-32768, 32767).toShort() }
    }

    /** env bytes (10 ms RMS, −dBFS × 2) and the −40 dB threshold frame. */
    private fun analyse(w: ShortArray): Pair<ByteArray, Int> {
        var peak = 0
        for (s in w) peak = maxOf(peak, abs(s.toInt()))
        val thrLevel = peak * 0.01
        var thr = ONSET
        for (t in w.indices) if (abs(w[t].toInt()) >= thrLevel) { thr = t; break }
        val nEnv = (w.size + 479) / 480
        val out = ByteArray(nEnv)
        for (e in 0 until nEnv) {
            var sum = 0.0; var cnt = 0
            var t = e * 480
            while (t < minOf(w.size, (e + 1) * 480)) { val v = w[t] / 32768.0; sum += v * v; cnt++; t++ }
            val rms = sqrt(sum / cnt.coerceAtLeast(1))
            val db = if (rms <= 1e-9) -200.0 else 20.0 * log10(rms)
            out[e] = (-db * 2.0).roundToInt().coerceIn(0, 254).toByte()
        }
        return out to thr
    }

    companion object {
        const val ONSET = 96
        const val GAP = 1920          // 40 ms, as in the real unit streams
    }
}
