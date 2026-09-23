package com.tropicalstream.hammerklavier.engine

import com.tropicalstream.hammerklavier.contract.LoadedBank
import com.tropicalstream.hammerklavier.contract.SampleReader

/**
 * One sample playback (PLAN §3.6): a 1024-frame staged window refilled by one bulk
 * [SampleReader.read], Hermite / linear / copy interpolation, a per-sample gain ramp from the
 * previous block's level to this block's, an optional one-pole spectral low-pass, and the
 * bookkeeping for the level, the damping and the fades. Preallocated; never allocates.
 *
 * Gain structure: `level = base · damp · fade · fadeInGain · tFade` (linear, excluding the raw
 * short scale); `g`, the ramped per-sample gain, is `level · 2⁻¹⁵` (the only place the short scale
 * appears). `levelDb = envDb(region, absFrame / 480) + 20·log10(level)`.
 *
 * The kernels write into the pool's scratch arrays; [VoicePool] then filters, mixes to the bus and
 * the key's self row, so the spectral and self-row branches are separate passes, not per-sample
 * tests.
 */
internal class Voice(@JvmField val slot: Int) {
    @JvmField var state = IDLE
    /** The state a PENDING voice takes when it starts (PLAYING, RELEASE_NOISE or PEDAL_NOISE). */
    @JvmField var runState = PLAYING
    @JvmField var role = ROLE_MAIN
    @JvmField var key = 0
    @JvmField var stop = 0
    @JvmField var region = -1
    @JvmField var bus = DRY
    @JvmField var note = -1
    @JvmField var evIndex = -1
    @JvmField var vel = 0
    @JvmField var reader: SampleReader? = null
    @JvmField var bank: LoadedBank? = null
    @JvmField var regionFrames = 0

    /** Play-frame (see [EngineCore]) at which frame 0 (or the skipped-in position) is read. */
    @JvmField var startAt = 0L
    /** Play-frame of the sampled onset. */
    @JvmField var onsetAt = 0L
    /** Play-frame of the note's event (its 8′ pluck / hammer contact; the key-down). */
    @JvmField var eventAt = 0L
    /** Frames of silence before the first sample in the voice's first block. */
    @JvmField var startDelay = 0
    /** True until the voice's first block: its gain starts at the target instead of ramping from 0. */
    @JvmField var fresh = true
    /** Output frame at which the voice produced its first sample (for its age). */
    @JvmField var startedOut = 0L
    /** Source position (32.32) to start from, for voices dispatched after their start frame. */
    @JvmField var skipFixed = 0L

    @JvmField var pos = 0L                          // 32.32 frames relative to winStart
    @JvmField var inc = 1L shl 32
    @JvmField var winStart = 0
    @JvmField val win = ShortArray(2 * (WINF + 4))  // 1 guard frame before, 3 after

    @JvmField var base = 1f
    @JvmField var damp = 1f
    @JvmField var fade = 1f
    @JvmField var fadeMode = FADE_NONE
    @JvmField var fadeMul = 1f                      // FADE_EXP: per block
    @JvmField var fadeStep = 0f                     // FADE_LIN: per frame; FADE_HANDOFF: x per frame
    @JvmField var handX = 0f                        // FADE_HANDOFF progress 0..1
    @JvmField var fade0 = 1f                        // FADE_HANDOFF: the fade when the handoff began
    @JvmField var fadeIn = 1f                       // release fade-in progress 0..1 (1 = none)
    @JvmField var fadeInStep = 0f                   // per frame
    @JvmField var tFade = 1f                        // transport (pause) fade
    @JvmField var tTarget = 1f
    @JvmField var tStep = 0f                        // per frame

    @JvmField var level = 0f
    @JvmField var g = 0f
    @JvmField var levelDb = -200f
    @JvmField var attackDb = -200f
    @JvmField var attackSet = false
    @JvmField var envPow = 0f                       // mean-square of the last block (ENV_POW · level²)

    @JvmField var spectral = false                  // the low-pass stage is engaged
    @JvmField var lpIdx = 0f
    @JvmField var lpL = 0f
    @JvmField var lpR = 0f
    @JvmField var lastL = 0f                        // last emitted samples (to seed the low-pass)
    @JvmField var lastR = 0f

    /** The number of frames of the current block this voice produced (0 if frozen or pending). */
    @JvmField var produced = 0

    fun isIdle(): Boolean = state == IDLE
    fun isPending(): Boolean = state == PENDING

    /** Clears everything a new start sets, keeping the slot and the window buffer. */
    fun clear() {
        state = IDLE; runState = PLAYING; eventAt = 0L; startDelay = 0; fresh = true; fade0 = 1f
        role = ROLE_MAIN; key = 0; stop = 0; region = -1; bus = DRY; note = -1; evIndex = -1; vel = 0
        reader = null; bank = null; regionFrames = 0
        startAt = 0L; onsetAt = 0L; startedOut = 0L; skipFixed = 0L
        pos = 0L; inc = 1L shl 32; winStart = 0
        base = 1f; damp = 1f; fade = 1f; fadeMode = FADE_NONE; fadeMul = 1f; fadeStep = 0f; handX = 0f
        fadeIn = 1f; fadeInStep = 0f; tFade = 1f; tTarget = 1f; tStep = 0f
        level = 0f; g = 0f; levelDb = -200f; attackDb = -200f; attackSet = false; envPow = 0f
        spectral = false; lpIdx = 0f; lpL = 0f; lpR = 0f; lastL = 0f; lastR = 0f; produced = 0
    }

    /** Source frame now being read: winStart + (pos ushr 32). */
    fun absFrame(): Int = winStart + (pos ushr 32).toInt()

    /** True when the next block would read past the staged window. */
    fun needsRefill(): Boolean = ((pos + BLOCK_L * inc) ushr 32) >= WINF

    /** Re-centres the window on the current position with one bulk read. */
    fun refill() {
        val shift = (pos ushr 32).toInt()
        winStart += shift
        pos -= shift.toLong() shl 32
        val r = reader
        if (r != null) r.read(region, winStart - 1, WINF + 4, win, 0)
        else java.util.Arrays.fill(win, 0.toShort())
    }

    // ── kernels: [i0, i1) into tL/tR with the gain ramping from g0 by dg per frame; return the gain after ──

    fun kernelHermite(i0: Int, i1: Int, g0: Float, dg: Float, tL: FloatArray, tR: FloatArray): Float {
        var p = pos; var gg = g0
        val w = win; val step = inc
        for (i in i0 until i1) {
            val ip = (p ushr 32).toInt()
            val fr = (p and 0xFFFFFFFFL).toFloat() * FRAC
            val b = (ip + 1) shl 1
            val lm = w[b - 2].toFloat(); val l0 = w[b].toFloat(); val l1 = w[b + 2].toFloat(); val l2 = w[b + 4].toFloat()
            val rm = w[b - 1].toFloat(); val r0 = w[b + 1].toFloat(); val r1 = w[b + 3].toFloat(); val r2 = w[b + 5].toFloat()
            val lc1 = 0.5f * (l1 - lm); val lc2 = lm - 2.5f * l0 + 2f * l1 - 0.5f * l2; val lc3 = 0.5f * (l2 - lm) + 1.5f * (l0 - l1)
            val rc1 = 0.5f * (r1 - rm); val rc2 = rm - 2.5f * r0 + 2f * r1 - 0.5f * r2; val rc3 = 0.5f * (r2 - rm) + 1.5f * (r0 - r1)
            tL[i] = (((lc3 * fr + lc2) * fr + lc1) * fr + l0) * gg
            tR[i] = (((rc3 * fr + rc2) * fr + rc1) * fr + r0) * gg
            gg += dg; p += step
        }
        pos = p
        return gg
    }

    fun kernelLinear(i0: Int, i1: Int, g0: Float, dg: Float, tL: FloatArray, tR: FloatArray): Float {
        var p = pos; var gg = g0
        val w = win; val step = inc
        for (i in i0 until i1) {
            val ip = (p ushr 32).toInt()
            val fr = (p and 0xFFFFFFFFL).toFloat() * FRAC
            val b = (ip + 1) shl 1
            val l0 = w[b].toFloat(); val r0 = w[b + 1].toFloat()
            tL[i] = (l0 + (w[b + 2] - l0) * fr) * gg
            tR[i] = (r0 + (w[b + 3] - r0) * fr) * gg
            gg += dg; p += step
        }
        pos = p
        return gg
    }

    /** Rate 1 on an integer position: a plain copy, bit-identical to the source at unit gain. */
    fun kernelCopy(i0: Int, i1: Int, g0: Float, dg: Float, tL: FloatArray, tR: FloatArray): Float {
        var gg = g0
        val w = win
        var b = (((pos ushr 32).toInt()) + 1) shl 1
        for (i in i0 until i1) {
            tL[i] = w[b] * gg
            tR[i] = w[b + 1] * gg
            gg += dg; b += 2
        }
        pos += (i1 - i0).toLong() shl 32
        return gg
    }

    /**
     * Synthesises [i0, i1): the gain holds at [g] until [rampFrom], then ramps to [gEnd] at [i1]
     * (a damper landing inside the block starts its damping at its own frame).
     */
    fun synth(i0: Int, rampFrom: Int, i1: Int, gEnd: Float, hermite: Boolean, tL: FloatArray, tR: FloatArray) {
        if (i1 <= i0) return
        val rf = if (rampFrom < i0) i0 else if (rampFrom > i1) i1 else rampFrom
        val copy = inc == ONE && (pos and 0xFFFFFFFFL) == 0L
        if (rf > i0) run(copy, hermite, i0, rf, g, 0f, tL, tR)
        if (i1 > rf) {
            val dg = (gEnd - g) / (i1 - rf)
            run(copy, hermite, rf, i1, g, dg, tL, tR)
        }
        g = gEnd
    }

    private fun run(copy: Boolean, hermite: Boolean, i0: Int, i1: Int, g0: Float, dg: Float, tL: FloatArray, tR: FloatArray) {
        if (copy) kernelCopy(i0, i1, g0, dg, tL, tR)
        else if (hermite) kernelHermite(i0, i1, g0, dg, tL, tR)
        else kernelLinear(i0, i1, g0, dg, tL, tR)
    }

    /** The one-pole low-pass at cutoff index [idx] over [i0, i1) of the scratch, in place. */
    fun lowPass(i0: Int, i1: Int, tL: FloatArray, tR: FloatArray, idx: Float = lpIdx) {
        if (i1 <= i0) return
        val a = DecayTables.lpCoef(idx)
        var yl = lpL; var yr = lpR
        for (i in i0 until i1) {
            yl += a * (tL[i] - yl); tL[i] = yl
            yr += a * (tR[i] - yr); tR[i] = yr
        }
        lpL = yl; lpR = yr
    }

    companion object {
        const val IDLE = 0
        const val PENDING = 1          // start delay not elapsed (no output yet)
        const val PLAYING = 2
        const val FADING = 3           // re-strike, seek, performance or bank fades
        const val KILL = 4             // stolen: 5 ms fade inside this block, free next block
        const val RELEASE_NOISE = 5
        const val PEDAL_NOISE = 6
        const val HANDOFF = 7          // tail-carrying kits: 30 ms equal-power crossfade into the release

        const val ROLE_MAIN = 0        // counted in the voice cap
        const val ROLE_KILL = 1        // one of the cap/2 kill slots
        const val ROLE_NOISE = 2       // one of the 12 noise slots

        const val DRY = 0
        const val SOFT = 1

        const val FADE_NONE = 0
        const val FADE_EXP = 1
        const val FADE_LIN = 2
        const val FADE_HANDOFF = 3

        const val WINF = 1024
        const val ONE = 1L shl 32
        const val FRAC = 2.3283064e-10f  // 2^-32
        const val SHORT_SCALE = 1f / 32768f
        private const val BLOCK_L = 256L
    }
}
