package com.tropicalstream.hammerklavier.engine

import com.tropicalstream.hammerklavier.contract.DspSet
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.ResonanceMode
import com.tropicalstream.hammerklavier.contract.SampleReader
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The once-per-APK 2 s bench and `--ez bench true` (PLAN §3.14, §3.16, T2.12): measures ns per
 * Hermite, linear and copy voice-frame (the real [Voice] kernels, window refills amortised), the
 * extra ns of the spectral low-pass, ns per comb-frame of the live [DspSet]'s resonance with and
 * without dispersion, and ns per frame of the soft bus, room and master stages; normalises every
 * figure to a 2.0 GHz core with the CPU frequency the caller read during the run, and derives the
 * Q0 voice cap `C = clamp(round8(0.25 × 20,833 / nsVoice₂), 64, 128)` and the ns saved by each
 * step-down (Hermite → linear, dispersion off, combs 44, cap − 8).
 *
 * [run] blocks; [begin] + [step] run the same measurement in slices (the engine uses slices of
 * ≈ 2 ms per block on HKAudio, so a `Cmd.BENCH` never starves the output). Everything the bench
 * needs is allocated in the constructor; the measurement itself allocates nothing.
 */
class EngineBench(private val sampleRate: Int = HK.SR) {
    /** Results of the last completed run; all ns figures are per unit-frame. */
    class Result {
        @JvmField var cpuMhz = 2000
        @JvmField var nsVoiceHermite = 0.0
        @JvmField var nsVoiceLinear = 0.0
        @JvmField var nsVoiceCopy = 0.0
        @JvmField var nsSpectral = 0.0            // extra per damping voice-frame
        @JvmField var nsComb = 0.0                // per comb-frame, dispersion on
        @JvmField var nsCombNoDispersion = 0.0
        @JvmField var nsSoft = 0.0                // per output frame
        @JvmField var nsRoom = 0.0
        @JvmField var nsMaster = 0.0
        /** nsVoiceHermite normalised to 2.0 GHz. */
        @JvmField var nsVoice2 = 0.0
        @JvmField var q0Cap = HK.VOICE_CAP_MIN
        // ns per output frame saved by each step-down, normalised to 2.0 GHz.
        @JvmField var savedHermiteToLinear = 0.0
        @JvmField var savedDispersionOff = 0.0
        @JvmField var savedCombs44 = 0.0
        @JvmField var savedCapMinus8 = 0.0
        @JvmField var runs = 0

        /** [ns] (measured at [cpuMhz]) on a 2.0 GHz core. */
        fun normalised(ns: Double): Double = ns * cpuMhz / 2000.0
    }

    @JvmField val result = Result()
    /** Bumps when a run completes (published after every [result] field). */
    @Volatile var completed = 0
        private set

    val running: Boolean get() = stage in 0 until STAGES

    // ── the synthetic voices ──
    private val region = ShortArray(2 * REGION_FRAMES).also { pcm ->
        for (t in 0 until REGION_FRAMES) {
            var x = 0.0
            for (n in 1..8) x += sin(2.0 * PI * 220.0 * n * t / sampleRate) / n
            val s = (x * 9000.0).roundToInt().coerceIn(-32768, 32767).toShort()
            pcm[2 * t] = s; pcm[2 * t + 1] = s
        }
    }
    private val reader = object : SampleReader {
        override fun read(region: Int, fromFrame: Int, frames: Int, dst: ShortArray, dstOff: Int): Int {
            var n = 0
            for (i in 0 until frames) {
                val f = fromFrame + i
                if (f in 0 until REGION_FRAMES) { dst[dstOff + 2 * i] = this@EngineBench.region[2 * f]; dst[dstOff + 2 * i + 1] = this@EngineBench.region[2 * f + 1]; n++ }
                else { dst[dstOff + 2 * i] = 0; dst[dstOff + 2 * i + 1] = 0 }
            }
            return n
        }
        override fun prefetch(region: Int, fromFrame: Int, frames: Int) {}
        override val slowReads: Int get() = 0
    }
    private val voices = Array(VOICES) { Voice(it) }
    private val tL = FloatArray(HK.BLOCK); private val tR = FloatArray(HK.BLOCK)
    private val busL = FloatArray(HK.BLOCK); private val busR = FloatArray(HK.BLOCK)
    private val wetL = FloatArray(HK.BLOCK); private val wetR = FloatArray(HK.BLOCK)
    private val out = FloatArray(2 * HK.BLOCK)
    private val mix = FloatArray(HK.BLOCK)
    private val self = FloatArray(HK.LANES * HK.BLOCK)
    private val selfRows = BooleanArray(HK.LANES)
    private val gate = FloatArray(HK.KEYS) { if (it in 21..108) 1f else 0f }
    private val softFeed = BooleanArray(HK.KEYS)
    private val damping = FloatArray(HK.KEYS)

    // ── the incremental state ──
    private var stage = -1
    private var stageBudgetNs = 0L
    private val stageNs = LongArray(STAGES)
    private val stageUnits = DoubleArray(STAGES)
    private var dsp: DspSet? = null
    private var mhz = 2000

    /** Starts a run of [seconds] against [dsp] (null: voices only); [cpuMhz] = the frequency read during the run. */
    fun begin(seconds: Float, dsp: DspSet?, cpuMhz: Int = 2000) {
        this.dsp = dsp
        mhz = if (cpuMhz > 0) cpuMhz else 2000
        stageBudgetNs = (seconds.toDouble().coerceAtLeast(0.05) * 1e9 / STAGES).toLong()
        java.util.Arrays.fill(stageNs, 0L); java.util.Arrays.fill(stageUnits, 0.0)
        for (i in 0 until HK.BLOCK) mix[i] = if (i and 1 == 0) 0.01f else -0.01f
        resetVoices()
        stage = 0
        enterStage()
    }

    /** Runs measurement slices for about [budgetNs]; returns true while the run continues. */
    fun step(budgetNs: Long): Boolean {
        if (!running) return false
        val t0 = System.nanoTime()
        while (running) {
            val a = System.nanoTime()
            val units = runStage(stage)
            val b = System.nanoTime()
            stageNs[stage] += b - a
            stageUnits[stage] += units
            if (stageNs[stage] >= stageBudgetNs) { stage++; if (running) enterStage() else finish() }
            if (b - t0 >= budgetNs) break
        }
        return running
    }

    /** Blocking run. */
    fun run(seconds: Float, dsp: DspSet?, cpuMhz: Int = 2000): Result {
        begin(seconds, dsp, cpuMhz)
        while (step(Long.MAX_VALUE)) { }
        return result
    }

    private fun resetVoices() {
        for (i in 0 until VOICES) {
            val v = voices[i]
            v.clear()
            v.state = Voice.PLAYING; v.region = 0; v.regionFrames = REGION_FRAMES; v.reader = reader
            v.inc = (1.0594630943592953 * 4294967296.0).toLong()     // 2^(1/12): interpolating path
            v.pos = ((i * 997L) % 20_000L) shl 32
            v.refill()
            v.g = 1f / 32768f
        }
    }

    private fun enterStage() {
        val d = dsp
        when (stage) {
            ST_COPY -> for (v in voices) v.inc = Voice.ONE
            ST_HERMITE, ST_LINEAR, ST_SPECTRAL -> for (v in voices) { v.inc = (1.0594630943592953 * 4294967296.0).toLong(); v.lpIdx = 120f; v.spectral = stage == ST_SPECTRAL }
            ST_COMB -> d?.resonance?.setMode(ResonanceMode.RICH, InstrumentId.GRAND, 88, true)
            ST_COMB_NODISP -> d?.resonance?.setMode(ResonanceMode.RICH, InstrumentId.GRAND, 88, false)
            else -> {}
        }
        if (stage == ST_COPY) for (v in voices) { v.pos = v.pos and 0xFFFFFFFFL.inv() }
    }

    /** One block of stage [s]; returns its unit-frames. */
    private fun runStage(s: Int): Double {
        val n = HK.BLOCK
        val d = dsp
        when (s) {
            ST_HERMITE, ST_LINEAR, ST_COPY, ST_SPECTRAL -> {
                java.util.Arrays.fill(busL, 0f); java.util.Arrays.fill(busR, 0f)
                val hermite = s != ST_LINEAR
                for (i in 0 until VOICES) {
                    val v = voices[i]
                    if (v.needsRefill()) v.refill()
                    if (v.absFrame() > REGION_FRAMES - 4 * n) { v.winStart = 0; v.pos = v.pos and 0xFFFFFFFFL; v.refill() }
                    v.synth(0, 0, n, v.g, hermite, tL, tR)
                    if (v.spectral) v.lowPass(0, n, tL, tR)
                    for (j in 0 until n) { busL[j] += tL[j]; busR[j] += tR[j] }
                }
                return (VOICES * n).toDouble()
            }
            ST_COMB, ST_COMB_NODISP -> {
                if (d == null) return (HK.LANES * n).toDouble()
                java.util.Arrays.fill(busL, 0f); java.util.Arrays.fill(busR, 0f)
                d.resonance.process(mix, self, selfRows, gate, softFeed, damping, busL, busR, n)
                return (HK.LANES * n).toDouble()
            }
            ST_SOFT -> { d?.soft?.process(mix, mix, busL, busR, n); return n.toDouble() }
            ST_ROOM -> { d?.room?.process(busL, busR, wetL, wetR, n, 0f); return n.toDouble() }
            else -> { d?.master?.process(mix, mix, n, out); return n.toDouble() }
        }
    }

    private fun finish() {
        val r = result
        fun ns(s: Int): Double = if (stageUnits[s] > 0.0) stageNs[s] / stageUnits[s] else 0.0
        r.cpuMhz = mhz
        r.nsVoiceHermite = ns(ST_HERMITE); r.nsVoiceLinear = ns(ST_LINEAR); r.nsVoiceCopy = ns(ST_COPY)
        r.nsSpectral = (ns(ST_SPECTRAL) - ns(ST_HERMITE)).coerceAtLeast(0.0)
        if (dsp != null) {
            r.nsComb = ns(ST_COMB); r.nsCombNoDispersion = ns(ST_COMB_NODISP)
            r.nsSoft = ns(ST_SOFT); r.nsRoom = ns(ST_ROOM); r.nsMaster = ns(ST_MASTER)
        } else { r.nsComb = 0.0; r.nsCombNoDispersion = 0.0; r.nsSoft = 0.0; r.nsRoom = 0.0; r.nsMaster = 0.0 }
        r.nsVoice2 = r.normalised(r.nsVoiceHermite)
        r.q0Cap = capFor(r.nsVoice2)
        r.savedHermiteToLinear = r.normalised((r.nsVoiceHermite - r.nsVoiceLinear) * r.q0Cap)
        r.savedDispersionOff = r.normalised((r.nsComb - r.nsCombNoDispersion) * HK.LANES)
        r.savedCombs44 = r.normalised(r.nsComb * 44)
        r.savedCapMinus8 = r.normalised(r.nsVoiceHermite * 8)
        r.runs++
        stage = -1
        dsp = null
        completed++
    }

    companion object {
        const val VOICES = 32
        const val REGION_FRAMES = 2 * HK.SR
        private const val ST_HERMITE = 0
        private const val ST_LINEAR = 1
        private const val ST_COPY = 2
        private const val ST_SPECTRAL = 3
        private const val ST_COMB = 4
        private const val ST_COMB_NODISP = 5
        private const val ST_SOFT = 6
        private const val ST_ROOM = 7
        private const val ST_MASTER = 8
        const val STAGES = 9

        /** ns per output frame at 48 kHz. */
        const val FRAME_NS = 20_833.0

        /** The Q0 cap for a normalised Hermite voice-frame cost: clamp(round8(0.25 × 20,833 / ns), 64, 128). */
        @JvmStatic fun capFor(nsVoice2: Double): Int {
            if (!(nsVoice2 > 0.0)) return HK.VOICE_CAP_MAX
            val c = 0.25 * FRAME_NS / nsVoice2
            val r8 = (Math.round(c / 8.0) * 8).toInt()
            return r8.coerceIn(HK.VOICE_CAP_MIN, HK.VOICE_CAP_MAX)
        }
    }
}
