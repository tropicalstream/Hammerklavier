package com.tropicalstream.hammerklavier.engine

import com.tropicalstream.hammerklavier.contract.HK
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * The run-time lookup tables of PLAN §2.1 rule 5 that do not depend on a bank or a key map:
 * env byte → dB and → mean-square (256), linear → dB and dB → linear, `vel^0.7` (128),
 * `exp(−age/3 s)` over 0–6 s (64), a 1024-step quarter sine (crossfades), and the 256-entry
 * log-spaced one-pole coefficient table of the spectral low-pass (§3.7).
 *
 * Everything is computed once when the object initialises (on main or HKLoader, the first time an
 * [EngineCore] is built); the audio thread only reads.
 */
object DecayTables {
    /** Damping steps: D is quantised to j / 32, j = 0..32 (§3.7). */
    const val DSTEPS = 33

    /** env byte b → dBFS (−b / 2). */
    @JvmField val ENV_DB = FloatArray(256) { -it / 2f }

    /** env byte b → mean-square re full scale of the 10 ms window: 10^(envDb / 10). */
    @JvmField val ENV_POW = FloatArray(256) { 10.0.pow(-it / 20.0).toFloat() }

    /** `vel^0.7` on 0..127 (velocity / 127). */
    @JvmField val VEL07 = FloatArray(128) { (it / 127.0).pow(0.7).toFloat() }

    /** `exp(−age / 3 s)` sampled at 64 points over 0–6 s, plus a guard entry. */
    @JvmField val EXPAGE = FloatArray(65) { exp(-(it * 6.0 / 64.0) / 3.0).toFloat() }

    /** sin(π/2 · i / 1024), i = 0..1024: equal-power crossfades (sustain cos, release sin). */
    @JvmField val SIN_Q = FloatArray(1025) { sin(PI / 2.0 * it / 1024.0).toFloat() }

    // ── log2 of the mantissa, for lin2db without a transcendental call ──
    private const val MANT_BITS = 8
    private val LOG2_MANT = FloatArray((1 shl MANT_BITS) + 1) { (ln(1.0 + it / (1 shl MANT_BITS).toDouble()) / ln(2.0)).toFloat() }
    private const val DB_PER_LOG2 = 6.0205999f

    /** 20·log10(x) by exponent bits and an interpolated 256-entry mantissa table (±0.001 dB); −200 for x ≤ 0. */
    @JvmStatic fun lin2db(x: Float): Float {
        if (!(x > 0f)) return -200f
        val bits = x.toRawBits()
        val e = ((bits ushr 23) and 0xFF) - 127
        if (e == -127) return -200f                                  // denormal: far below anything audible
        val m = bits and 0x7FFFFF
        val idx = m ushr (23 - MANT_BITS)
        val frac = (m and ((1 shl (23 - MANT_BITS)) - 1)) * (1f / (1 shl (23 - MANT_BITS)))
        val l2 = e + LOG2_MANT[idx] + (LOG2_MANT[idx + 1] - LOG2_MANT[idx]) * frac
        return l2 * DB_PER_LOG2
    }

    // ── dB → linear over −160..+40 dB in quarter-dB steps ──
    private const val DB_MIN = -160f
    private const val DB_MAX = 40f
    private const val DB_STEPS_PER_DB = 4
    private val DB2LIN = FloatArray(((DB_MAX - DB_MIN) * DB_STEPS_PER_DB).toInt() + 2) {
        10.0.pow((DB_MIN + it.toDouble() / DB_STEPS_PER_DB) / 20.0).toFloat()
    }

    /** 10^(db/20) by table with linear interpolation (relative error < 4e-5); 0 below −160 dB. */
    @JvmStatic fun db2lin(db: Float): Float {
        if (db <= DB_MIN) return 0f
        val x = ((if (db > DB_MAX) DB_MAX else db) - DB_MIN) * DB_STEPS_PER_DB
        val i = x.toInt()
        val f = x - i
        return DB2LIN[i] + (DB2LIN[i + 1] - DB2LIN[i]) * f
    }

    // ── spectral low-pass: index i ∈ 0..255 ↔ fc = 20 · 1200^(i/255) Hz (20 Hz … 24 kHz), log-spaced ──
    const val LP_N = 256
    private const val LP_FMIN = 20.0
    private const val LP_FMAX = 24_000.0

    /** One-pole coefficient a = 1 − exp(−2π fc / fs) per index, at [HK.SR]. */
    @JvmField val LP_COEF = FloatArray(LP_N) {
        val fc = LP_FMIN * (LP_FMAX / LP_FMIN).pow(it / (LP_N - 1.0))
        (1.0 - exp(-2.0 * PI * fc / HK.SR)).coerceIn(0.0, 1.0).toFloat()
    }

    /** Preparation only (uses ln): fractional LP index of a cutoff in Hz. */
    @JvmStatic fun lpIndexOf(fcHz: Double): Float {
        val f = fcHz.coerceIn(LP_FMIN, LP_FMAX)
        return (ln(f / LP_FMIN) / ln(LP_FMAX / LP_FMIN) * (LP_N - 1)).toFloat()
    }

    /** The LP index of the 18 kHz starting cutoff (§3.7). */
    @JvmField val LP_IDX_18K: Float = lpIndexOf(18_000.0)

    /** Per-block glide factor of the 40 ms cutoff glide: 1 − exp(−256 / (0.040 · fs)). */
    @JvmField val LP_GLIDE: Float = (1.0 - exp(-HK.BLOCK / (0.040 * HK.SR))).toFloat()

    /** Coefficient of a fractional LP index (nearest entry). */
    @JvmStatic fun lpCoef(idx: Float): Float {
        var i = (idx + 0.5f).toInt()
        if (i < 0) i = 0 else if (i > LP_N - 1) i = LP_N - 1
        return LP_COEF[i]
    }

    /** `exp(−age/3 s)` by table for an age in seconds (clamped to 0..6 s). */
    @JvmStatic fun expAge(ageSec: Float): Float {
        val x = (if (ageSec < 0f) 0f else if (ageSec > 6f) 6f else ageSec) * (64f / 6f)
        val i = x.toInt()
        val f = x - i
        return EXPAGE[i] + (EXPAGE[i + 1] - EXPAGE[i]) * f
    }

    /** sin(π/2 · x) for x ∈ [0, 1] by the quarter table. */
    @JvmStatic fun sinQ(x: Float): Float {
        val y = (if (x < 0f) 0f else if (x > 1f) 1f else x) * 1024f
        val i = y.toInt().coerceAtMost(1023)
        val f = y - i
        return SIN_Q[i] + (SIN_Q[i + 1] - SIN_Q[i]) * f
    }

    /** cos(π/2 · x) for x ∈ [0, 1]. */
    @JvmStatic fun cosQ(x: Float): Float = sinQ(1f - x)

    /**
     * ±1 dB deterministic humanising for the harpsichord (§3.5), 256 linear gains indexed by a
     * hash of the note index ([hashGain]).
     */
    private val HASH_GAIN = FloatArray(256) {
        val u = mix(it) and 0xFFFF
        10.0.pow((u / 65535.0 * 2.0 - 1.0) / 20.0).toFloat()
    }

    @JvmStatic private fun mix(x0: Int): Int {
        var x = x0 * -0x61c88647
        x = x xor (x ushr 15); x *= 0x2c1b3c6d; x = x xor (x ushr 12); x *= 0x297a2d39; x = x xor (x ushr 15)
        return x
    }

    /** The ±1 dB humanising gain of note index [note]. */
    @JvmStatic fun hashGain(note: Int): Float = HASH_GAIN[mix(note) and 0xFF]
}
