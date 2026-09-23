package com.tropicalstream.hammerklavier.contract.stub

import com.tropicalstream.hammerklavier.contract.BankInfo
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.LoadedBank
import com.tropicalstream.hammerklavier.contract.SampleReader
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
 * A LoadedBank synthesized in memory (PLAN §2.3 stub behaviour), so WP2 needs neither WP4's codec
 * nor map_fixture. Roots every 3 semitones, keys 21 + 3i (i = 0..29, the Salamander spacing; the
 * plan's "3 roots per octave" wording is superseded by this formula, see contracts-changelog).
 * Regions: `stops × layers × 30`, index [regionOf]; each is 2 s of stereo interleaved shorts at
 * 48 kHz: 8 harmonic partials (1/n amplitudes, below 20 kHz), a 1.2 s exponential decay (time
 * constant), silent before a sharp onset at frame 96, a 50 ms fade at the end, peak −3 dBFS.
 * Layers and stops share one waveform per root (the KeyMap sets rate and gain), so every SineBank
 * in the process shares ≈ 11.5 MB of PCM, built once on first use.
 * `envByte` is computed from the data (10 ms RMS, −dBFS × 2) and `thrFrame` is exact.
 * Unit id u = stop * layers + layer; readyMask starts all-ready.
 */
class SineBank(val layers: Int = 2, val stops: Int = 1, val instrument: InstrumentId = InstrumentId.GRAND,
               override val generation: Int = 1) : LoadedBank {
    init { require(layers in 1..16 && stops in 1..2) { "layers 1..16, stops 1..2" } }

    private val profile = InstrumentProfile.of(instrument)

    override val info: BankInfo = BankInfo(
        instrument = instrument, kit = "sine", version = "2026.09.0", sha1 = "0".repeat(40),
        layers = layers, stops = stops, lastDamper = profile.lastDamper, recordedAHz = 440f, aOffsetCents = 0f,
        damperT60 = FloatArray(HK.KEYS) { profile.defaultDamperT60(it) },
        freeT60 = FloatArray(stops * HK.KEYS) { profile.defaultFreeT60(it / HK.KEYS, it % HK.KEYS) },
        inharmB = FloatArray(HK.KEYS),
        releaseCarriesTail = false, embeddedRoomDb = 30f,
        decodeOrder = IntArray(layers * stops) { it },
        isStub = true, fallback = null)

    override val regionCount: Int get() = stops * layers * ROOTS

    override fun frames(region: Int): Int = FRAMES
    override fun onsetFrame(region: Int): Int = ONSET
    override fun thrFrame(region: Int): Int = Shared.thr(rootOf(region))
    override fun envByte(region: Int, tenMs: Int): Int {
        val env = Shared.env(rootOf(region))
        return if (tenMs < 0 || tenMs >= env.size) 255 else env[tenMs].toInt() and 0xFF
    }

    override fun newReader(): SampleReader = Reader()

    @Volatile override var readyMask: Long = -1L

    /** Root index (0..29) of a region. */
    fun rootOf(region: Int): Int = region % ROOTS
    fun stopOf(region: Int): Int = region / (layers * ROOTS)
    fun layerOf(region: Int): Int = (region / ROOTS) % layers

    private inner class Reader : SampleReader {
        override fun read(region: Int, fromFrame: Int, frames: Int, dst: ShortArray, dstOff: Int): Int {
            val wave = Shared.wave(rootOf(region))
            val end = fromFrame + frames
            val a = maxOf(fromFrame, 0)
            val b = minOf(end, FRAMES)
            if (b <= a) { java.util.Arrays.fill(dst, dstOff, dstOff + 2 * frames, 0); return 0 }
            if (a > fromFrame) java.util.Arrays.fill(dst, dstOff, dstOff + 2 * (a - fromFrame), 0)
            System.arraycopy(wave, 2 * a, dst, dstOff + 2 * (a - fromFrame), 2 * (b - a))
            if (end > b) java.util.Arrays.fill(dst, dstOff + 2 * (b - fromFrame), dstOff + 2 * frames, 0)
            return b - a
        }
        override fun prefetch(region: Int, fromFrame: Int, frames: Int) {}
        override val slowReads: Int get() = 0
    }

    companion object {
        const val ROOTS = 30
        const val FRAMES = 2 * HK.SR            // 2 s
        const val ONSET = 96
        const val PEAK_DBFS = -3f

        /** MIDI key of root index i. */
        fun rootKey(i: Int): Int = 21 + 3 * i

        /** Nearest root index for a sounding key (ties go down), clamped to 0..29. */
        fun nearestRoot(soundingKey: Int): Int = Math.floorDiv(soundingKey - 21 + 1, 3).coerceIn(0, ROOTS - 1)

        /** Region index of (stop, layer, root index) in a bank with [layers] layers. */
        fun regionOf(stop: Int, layer: Int, rootIndex: Int, layers: Int): Int = (stop * layers + layer) * ROOTS + rootIndex

        /** The waveform of root i (shared, built on first use; off the audio thread). */
        fun waveOf(i: Int): ShortArray = Shared.wave(i)
    }

    private object Shared {
        private val waves = arrayOfNulls<ShortArray>(ROOTS)
        private val envs = arrayOfNulls<ByteArray>(ROOTS)
        private val thrs = IntArray(ROOTS) { -1 }

        fun wave(i: Int): ShortArray { ensure(i); return waves[i]!! }
        fun env(i: Int): ByteArray { ensure(i); return envs[i]!! }
        fun thr(i: Int): Int { ensure(i); return thrs[i] }

        @Synchronized private fun ensure(i: Int) {
            if (waves[i] != null) return
            val fs = HK.SR.toDouble()
            val f0 = 440.0 * 2.0.pow((rootKey(i) - 69) / 12.0)
            val x = DoubleArray(FRAMES)
            val decay = exp(-1.0 / (1.2 * fs))
            for (n in 1..8) {
                val f = n * f0
                if (f >= 20_000.0) break
                val w = 2.0 * PI * f / fs
                val cw = cos(w); val sw = sin(w)
                var c = 1.0; var s = 0.0          // phase rotation: s = sin(w·τ)
                var e = 1.0 / n
                for (t in ONSET until FRAMES) {
                    x[t] += e * s
                    val c2 = c * cw - s * sw; s = s * cw + c * sw; c = c2
                    e *= decay
                }
            }
            val fade = HK.SR / 20                 // 50 ms
            for (k in 0 until fade) x[FRAMES - 1 - k] *= k.toDouble() / fade
            var peak = 0.0
            for (v in x) peak = maxOf(peak, abs(v))
            val scale = 10.0.pow(PEAK_DBFS / 20.0) * 32767.0 / peak
            val pcm = ShortArray(2 * FRAMES)
            var peakS = 0
            for (t in 0 until FRAMES) {
                val s = (x[t] * scale).roundToInt().coerceIn(-32768, 32767)
                pcm[2 * t] = s.toShort(); pcm[2 * t + 1] = s.toShort()
                peakS = maxOf(peakS, abs(s))
            }
            val thrLevel = peakS * 0.01              // −40 dB re the region peak
            var thr = ONSET
            for (t in 0 until FRAMES) if (abs(pcm[2 * t].toInt()) >= thrLevel) { thr = t; break }
            val nEnv = FRAMES / 480
            val env = ByteArray(nEnv)
            for (b in 0 until nEnv) {
                var sum = 0.0
                for (t in b * 480 until (b + 1) * 480) { val v = pcm[2 * t] / 32768.0; sum += v * v }
                val rms = sqrt(sum / 480)
                val db = if (rms <= 1e-9) -200.0 else 20.0 * log10(rms)
                env[b] = (-db * 2.0).roundToInt().coerceIn(0, 255).toByte()
            }
            envs[i] = env; thrs[i] = thr
            waves[i] = pcm
        }
    }
}
