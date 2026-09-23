package com.tropicalstream.hammerklavier.dsp

import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.ResonanceMode
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * Run-time tables of PLAN §2.1 rule 5 and the comb sends of §3.11 (WP3 owns SEND and
 * UNA_CORDA_SEND). Everything here is built once at class initialisation (off the audio thread);
 * the lookups are table reads with linear interpolation, safe on HKAudio and GLThread.
 */
object DspTables {
    // ── Comb sends (§3.11), dB re the voice mix. ──
    const val SEND_GRAND_NATURAL_DB = -30f
    const val SEND_GRAND_RICH_DB = -24f
    const val SEND_UPRIGHT_NATURAL_DB = -32f
    const val SEND_UPRIGHT_RICH_DB = -26f
    const val SEND_HARPSICHORD_DB = -34f
    /** Una corda self-feed of keys with ≥ 2 strings, calibrated alone (T3.1 target 2). */
    const val UNA_CORDA_SEND_DB = -48f

    /**
     * Comb input normalisation (calibration, T3.1 targets 1–3): the §3.11 sends were estimated
     * for an input on a comb peak; the loop's peak gain 1/(1 − g) is ≈ +45…+60 dB, so the plan's
     * dB values alone would put a pedal-down C5 comb ≈ 13 dB above the −28 dB target (SineBank-recipe voices). The trim
     * scales every send by the same amount, keeping the plan's relative dB values (Natural vs
     * Rich, instrument vs instrument). Una corda has its own trim (calibrated alone).
     */
    const val COMB_INPUT_TRIM_DB = -37f
    const val UNA_CORDA_TRIM_DB = -14f

    /**
     * Register tilt of the comb input (T3.1 on the real `wp11/real` regions): a real piano C4 has its
     * second partial 8–9 dB above the fundamental (the SineBank recipe: 6 dB below), so an upper comb
     * fed through that partial rings ≈ 10 dB louder than the harmonic proxy predicts, while a lower
     * comb fed through the struck key's fundamental does not (and lifting the lower combs makes a
     * held C3 beat against them, target 4). The trim of the comb of key k is 0 dB up to key 60 and
     * `COMB_TILT_DB_PER_KEY·(k − 60)` above, floored at −[COMB_TILT_MAX_DB].
     */
    const val COMB_TILT_DB_PER_KEY = -0.6f
    const val COMB_TILT_MAX_DB = 12f

    /** Linear register tilt for comb index c (key 21 + c). */
    val COMB_TILT: FloatArray = FloatArray(88) { c ->
        val db = (COMB_TILT_DB_PER_KEY * maxOf(0, 21 + c - 60)).coerceAtLeast(-COMB_TILT_MAX_DB)
        10.0.pow(db / 20.0).toFloat()
    }

    /** SEND in dB for (instrument, mode), before the input trim; OFF = no send. */
    fun sendDb(instrument: InstrumentId, mode: ResonanceMode): Float = when (mode) {
        ResonanceMode.OFF -> Float.NEGATIVE_INFINITY
        ResonanceMode.NATURAL -> when (instrument) {
            InstrumentId.GRAND -> SEND_GRAND_NATURAL_DB
            InstrumentId.UPRIGHT -> SEND_UPRIGHT_NATURAL_DB
            InstrumentId.HARPSICHORD -> SEND_HARPSICHORD_DB
        }
        ResonanceMode.RICH -> when (instrument) {
            InstrumentId.GRAND -> SEND_GRAND_RICH_DB
            InstrumentId.UPRIGHT -> SEND_UPRIGHT_RICH_DB
            InstrumentId.HARPSICHORD -> SEND_HARPSICHORD_DB
        }
    }

    private val sendLin = FloatArray(InstrumentId.entries.size * ResonanceMode.entries.size).also {
        for (i in InstrumentId.entries) for (m in ResonanceMode.entries) {
            val db = sendDb(i, m)
            it[i.ordinal * ResonanceMode.entries.size + m.ordinal] =
                if (db == Float.NEGATIVE_INFINITY) 0f else 10.0.pow((db + COMB_INPUT_TRIM_DB) / 20.0).toFloat()
        }
    }

    /** Linear comb send including the input trim (0 for OFF). Allocation-free. */
    fun send(instrument: InstrumentId, mode: ResonanceMode): Float =
        sendLin[instrument.ordinal * ResonanceMode.entries.size + mode.ordinal]

    /** Linear una corda self-feed including its trim. */
    val UNA_CORDA_SEND: Float = 10.0.pow((UNA_CORDA_SEND_DB + UNA_CORDA_TRIM_DB) / 20.0).toFloat()

    // ── Sine table (1024 entries over one period, one guard entry). ──
    const val SIN_SIZE = 1024
    @JvmField val SIN = FloatArray(SIN_SIZE + 1) { sin(2.0 * PI * it / SIN_SIZE).toFloat() }
    private const val RAD_TO_IDX = (SIN_SIZE / (2.0 * PI)).toFloat()

    /** sin(x) by table with linear interpolation (error < 5e-6). */
    fun sinT(x: Float): Float {
        var p = x * RAD_TO_IDX
        p -= SIN_SIZE * kotlin.math.floor(p / SIN_SIZE)
        var i = p.toInt()
        if (i >= SIN_SIZE) i = SIN_SIZE - 1
        val f = p - i
        return SIN[i] + (SIN[i + 1] - SIN[i]) * f
    }

    fun cosT(x: Float): Float = sinT(x + (PI / 2).toFloat())

    /** Wraps an angle to [−π, π) without transcendental maths. */
    fun wrapPi(x: Float): Float {
        val twoPi = (2 * PI).toFloat()
        var y = x - twoPi * kotlin.math.floor((x + PI.toFloat()) / twoPi)
        if (y >= PI.toFloat()) y -= twoPi
        return y
    }

    // ── dB → linear, −160 … +40 dB in 0.05 dB steps, linear interpolation (relative error < 2e-6). ──
    private const val DB_MIN = -160f
    private const val DB_MAX = 40f
    private const val DB_STEP_INV = 20f
    private val DB_LIN = FloatArray(((DB_MAX - DB_MIN) * DB_STEP_INV).toInt() + 2) {
        10.0.pow((DB_MIN + it / DB_STEP_INV) / 20.0).toFloat()
    }

    fun dbToLin(db: Float): Float {
        if (db <= DB_MIN) return 0f
        val p = ((if (db > DB_MAX) DB_MAX else db) - DB_MIN) * DB_STEP_INV
        val i = p.toInt().coerceAtMost(DB_LIN.size - 2)
        val f = p - i
        return DB_LIN[i] + (DB_LIN[i + 1] - DB_LIN[i]) * f
    }

    // ── linear → dB through exponent + a 256-entry mantissa table (error < 0.001 dB). ──
    private val LOG2_MANT = FloatArray(257) { (ln(1.0 + it / 256.0) / ln(2.0)).toFloat() }
    private const val DB_PER_OCTAVE = 6.0205999f

    fun linToDb(x: Float): Float {
        if (!(x > 1e-12f)) return -240f
        val bits = java.lang.Float.floatToRawIntBits(x)
        val e = ((bits ushr 23) and 0xFF) - 127
        val m = (bits and 0x7FFFFF) * (256f / 8388608f)
        val i = m.toInt()
        val l2 = e + LOG2_MANT[i] + (LOG2_MANT[i + 1] - LOG2_MANT[i]) * (m - i)
        return l2 * DB_PER_OCTAVE
    }

    // ── One-pole low-pass coefficient a = exp(−2π·fc/fs) against fc/fs, 0 … 0.5 in 1/8192 steps. ──
    private const val OP_STEPS = 4096
    private val ONE_POLE = FloatArray(OP_STEPS + 2) { exp(-2.0 * PI * (it * 0.5 / OP_STEPS)).toFloat() }

    /** exp(−2π·hz/fs) by table (for coefficient changes on the audio thread). */
    fun onePoleCoef(hz: Float, fs: Float): Float {
        val p = (hz / fs).coerceIn(0f, 0.5f) * (2 * OP_STEPS)
        val i = p.toInt().coerceAtMost(OP_STEPS)
        val f = p - i
        return ONE_POLE[i] + (ONE_POLE[i + 1] - ONE_POLE[i]) * f
    }

    /** Padé approximant of tanh, the final safety clip (SpyHunt): exact ±1 at |x| = 3, clamped beyond. */
    fun softClip(x: Float): Float {
        if (x >= 3f) return 1f
        if (x <= -3f) return -1f
        val x2 = x * x
        return x * (27f + x2) / (27f + 9f * x2)
    }
}
