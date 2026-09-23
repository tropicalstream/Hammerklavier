package com.tropicalstream.hammerklavier.contract.stub

import com.tropicalstream.hammerklavier.contract.AudioStats
import com.tropicalstream.hammerklavier.contract.BankInfo
import com.tropicalstream.hammerklavier.contract.Cmd
import com.tropicalstream.hammerklavier.contract.CoreClockState
import com.tropicalstream.hammerklavier.contract.EngineCoreApi
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.KeyMap
import com.tropicalstream.hammerklavier.contract.LoadedBank
import com.tropicalstream.hammerklavier.contract.Performance
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A working EngineCoreApi without samples (PLAN §2.3 stub behaviour), so WP4 can run AudioOutput
 * before WP2 exists: one sine partial per sounding key (f0 from the KeyMap, else 440·2^((k−69)/12))
 * at −12 dBFS RMS × velocity/127 in cosine phase (the onset frame carries the full amplitude),
 * a 10 ms release ramp at the key-up, no pedals, no DSP.
 * Honours SET_PERF, PLAY, PAUSE (fade), SEEK, RATE, REGISTRATION, DUCK and RESET with the §2.5
 * timing rules: the song position is 32.32 fixed-point song frames advanced by exactly
 * 256 · round(r · 2³²) per playing block, events are applied at output frame
 * round((evSongFrames − S₀) / r) inside the block, so an onset lands on its exact frame.
 * Publishes CoreClockState (state at the start of the block) and 88 energy lanes (linear RMS).
 * Allocation-free after construction; no transcendental maths on the render path (the per-key
 * rotation coefficients are prepared in [prepareKeyMap] or the constructor).
 */
class SineCore(private val sampleRate: Int = HK.SR, private val maxVoices: Int = 128) : EngineCoreApi {
    /** Per-key rotation coefficients; the prepared key-map token. */
    class Tables(@JvmField val cosW: FloatArray, @JvmField val sinW: FloatArray)

    private var tables: Tables = tablesFor(null)

    // Transport.
    private var perf: Performance? = null
    private var pos = 0L                        // song frames, 32.32 fixed point
    private var rate = 1f
    private var rateFixed = 1L shl 32
    private var playing = false
    private var epoch = 0
    private var generation = -1
    private var registration = 3
    private var cursor = 0
    private var endPending = false
    private var endedGeneration = -1
    private var duck = 1f

    // Voices (parallel arrays).
    private val vKey = IntArray(maxVoices)
    private val vNote = IntArray(maxVoices)
    private val vAmp = FloatArray(maxVoices)
    private val vC = FloatArray(maxVoices); private val vS = FloatArray(maxVoices)
    private val vStart = IntArray(maxVoices)    // frame offset in the current block; -1 = already running
    private val vStop = IntArray(maxVoices)     // frame offset of the release start in this block; Int.MAX = none
    private val vRel = FloatArray(maxVoices)    // release gain 1 → 0; < 1 while releasing
    private val vOn = BooleanArray(maxVoices)
    private var voices = 0
    private var voicesPeak = 0
    private var dropped = 0

    private val keySq = FloatArray(HK.LANES)
    private val lanes = FloatArray(HK.LANES)
    private val mix = FloatArray(HK.BLOCK)
    private val state = CoreClockState()
    private val relStep = 1f / (0.010f * sampleRate)            // 10 ms release
    private var fadeStep = 1f / (0.060f * sampleRate)           // pause fade (60 ms default)

    override fun prepareBank(bank: LoadedBank, keyMap: KeyMap, profile: InstrumentProfile): Any = bank
    override fun prepareKeyMap(keyMap: KeyMap, info: BankInfo, profile: InstrumentProfile): Any = tablesFor(keyMap)

    private fun tablesFor(km: KeyMap?): Tables {
        val c = FloatArray(HK.KEYS); val s = FloatArray(HK.KEYS)
        for (k in 0 until HK.KEYS) {
            val f0 = km?.f0Hz?.getOrNull(k)?.takeIf { it > 0f }?.toDouble() ?: (440.0 * 2.0.pow((k - 69) / 12.0))
            val w = 2.0 * Math.PI * f0.coerceAtMost(sampleRate * 0.45) / sampleRate
            c[k] = cos(w).toFloat(); s[k] = sin(w).toFloat()
        }
        return Tables(c, s)
    }

    override fun on(code: Int, l: Long, f: Float, ref: Any?) {
        when (code) {
            Cmd.SET_KEYMAP -> if (ref is Tables) tables = ref
            Cmd.SET_BANK -> { clearVoices(); epoch++ }
            Cmd.SET_PERF -> {
                val p = ref as? Performance
                val startUs = if (l < 0) songUs() else l
                perf = p; generation = p?.generation ?: -1
                setPos(startUs); clearVoices(); epoch++
                endPending = false
                playing = p != null && f >= 0.5f
            }
            Cmd.PLAY -> if (perf != null) playing = true
            Cmd.PAUSE -> {
                playing = false
                val ms = if (l > 0) l.toFloat() else 60f
                fadeStep = 1f / (ms / 1000f * sampleRate)
                for (v in 0 until maxVoices) if (vOn[v]) { vStop[v] = 0; if (vRel[v] > 1f - 1e-6f) vRel[v] = 0.9999f }
            }
            Cmd.SEEK -> { setPos(l); clearVoices(); epoch++; endPending = false }
            Cmd.RATE -> { rate = f.coerceIn(0.5f, 1.5f); rateFixed = (rate.toDouble() * 4294967296.0).toLong() }
            Cmd.REGISTRATION -> registration = l.toInt()
            Cmd.DUCK -> duck = f
            Cmd.RESET -> reset()
            else -> {}                          // QUALITY, ROOM, MIX, ROUTE, VOICE_CAP, BENCH: nothing to do here
        }
    }

    private fun songUs(): Long = ((pos ushr 16).toDouble() / 65536.0 * 1_000_000.0 / sampleRate).toLong()

    private fun setPos(us: Long) {
        val frames = us.coerceAtLeast(0L).toDouble() * sampleRate / 1_000_000.0
        pos = (frames * 4294967296.0).toLong()
        cursor = perf?.eventIndexAtOrAfter(us.coerceAtLeast(0L)) ?: 0
    }

    private fun clearVoices() {
        for (v in 0 until maxVoices) vOn[v] = false
        voices = 0
    }

    override fun render(out: FloatArray, blockStartFrame: Long) {
        state.songUs = songUs(); state.rate = rate; state.playing = playing; state.epoch = epoch
        state.generation = generation; state.registration = registration

        val p = perf
        if (playing && p != null) {
            val s0 = pos.toDouble() / 4294967296.0
            while (cursor < p.ev.size) {
                val evFrames = p.evUs[cursor] * (sampleRate / 1_000_000.0)
                var k = Math.round((evFrames - s0) / rate).toInt()
                if (k >= HK.BLOCK) break
                if (k < 0) k = 0
                val e = p.ev[cursor]
                when (Performance.type(e)) {
                    Performance.EV_NOTE_ON -> startVoice(p, Performance.arg(e), k)
                    Performance.EV_KEY_UP -> { val note = Performance.arg(e); for (v in 0 until maxVoices) if (vOn[v] && vNote[v] == note && vStop[v] == Int.MAX_VALUE) vStop[v] = k }
                    Performance.EV_END -> endPending = true
                    else -> {}
                }
                cursor++
            }
        }

        java.util.Arrays.fill(mix, 0f)
        java.util.Arrays.fill(keySq, 0f)
        var active = 0
        for (v in 0 until maxVoices) {
            if (!vOn[v]) continue
            val key = vKey[v]
            val cw = tables.cosW[key]; val sw = tables.sinW[key]
            var c = vC[v]; var s = vS[v]; var rel = vRel[v]
            val amp = vAmp[v]
            val step = if (playing) relStep else fadeStep
            var sq = 0f
            val from = if (vStart[v] < 0) 0 else vStart[v]
            for (i in from until HK.BLOCK) {
                if (i >= vStop[v]) { rel -= step; if (rel <= 0f) { rel = 0f; break } }
                val x = amp * rel * c                                  // cosine phase: full amplitude at the onset frame
                mix[i] += x; sq += x * x
                val c2 = c * cw - s * sw; s = s * cw + c * sw; c = c2
            }
            val g = 1.5f - 0.5f * (c * c + s * s)                     // keep the rotation on the unit circle
            vC[v] = c * g; vS[v] = s * g; vRel[v] = rel; vStart[v] = -1
            if (vStop[v] != Int.MAX_VALUE) vStop[v] = 0
            if (key in 21..108) keySq[key - 21] += sq
            if (rel <= 0f) vOn[v] = false else active++
        }
        voices = active
        if (active > voicesPeak) voicesPeak = active
        for (i in 0 until HK.LANES) lanes[i] = sqrt(keySq[i] / HK.BLOCK)
        val g = duck
        for (i in 0 until HK.BLOCK) {
            val x = (mix[i] * g).coerceIn(-1f, 1f)
            out[2 * i] = x; out[2 * i + 1] = x
        }
        if (playing) pos += HK.BLOCK.toLong() * rateFixed
        if (endPending && active == 0 && endedGeneration != generation) { endedGeneration = generation; endPending = false }
        state.endedGeneration = endedGeneration
        state.idle = !playing && active == 0
    }

    private fun startVoice(p: Performance, note: Int, offset: Int) {
        var v = -1
        for (i in 0 until maxVoices) if (!vOn[i]) { v = i; break }
        if (v < 0) { dropped++; return }
        val key = p.key[note].toInt() and 0x7F
        vOn[v] = true; vKey[v] = key; vNote[v] = note
        vAmp[v] = AMP_AT_127 * (p.vel[note].toInt() and 0x7F) / 127f
        vC[v] = 1f; vS[v] = 0f; vStart[v] = offset; vStop[v] = Int.MAX_VALUE; vRel[v] = 1f
    }

    override fun clockState(out: CoreClockState) {
        out.songUs = state.songUs; out.rate = state.rate; out.playing = state.playing; out.epoch = state.epoch
        out.generation = state.generation; out.registration = state.registration
        out.endedGeneration = state.endedGeneration; out.idle = state.idle
    }

    override fun energy(outLanes: FloatArray): Int {
        System.arraycopy(lanes, 0, outLanes, 0, HK.LANES)
        return state.epoch
    }

    override fun stats(out: AudioStats) {
        out.voices = voices; out.voicesPeak = voicesPeak; out.noiseVoices = 0; out.stolen = 0; out.dropped = dropped
        out.combsActive = 0; out.voiceCap = maxVoices; out.epoch = epoch; out.generation = generation
    }

    override fun reset() {
        perf = null; generation = -1; playing = false; pos = 0L; cursor = 0; clearVoices()
        endPending = false; endedGeneration = -1; epoch++; voicesPeak = 0; dropped = 0
        java.util.Arrays.fill(lanes, 0f)
    }

    companion object {
        /** Peak amplitude of a v127 sine whose RMS is −12 dBFS: 10^(−12/20) · √2. */
        const val AMP_AT_127 = 0.35481f
    }
}
