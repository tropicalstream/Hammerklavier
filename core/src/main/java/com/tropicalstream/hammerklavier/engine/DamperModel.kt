package com.tropicalstream.hammerklavier.engine

import com.tropicalstream.hammerklavier.contract.BankInfo
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.HarpsiTiming
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.KeyMap
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The damper and fade tables of one (key map, bank, profile), prepared off-thread (PLAN §3.7,
 * §3.9) and swapped in whole by the audio thread:
 * - `DAMP[k][j] = exp(−6.91 · (256/fs) · (j/32) / T60d(k))`, 128 × 33;
 * - `FCT[k][j]`: the spectral-damping cutoff target `18000 · (min(18000, 6·f0(k)) / 18000)^(j/32)`
 *   as a fractional index into [DecayTables.LP_COEF];
 * - the per-block re-strike multipliers for τ = `restrikeTauUpMs` / `restrikeTauPedalMs`;
 * - the real-time offsets in output frames: damper lag, the harpsichord quill passes, the 4′
 *   stagger per velocity, and the fixed fades (kill 5 ms, handoff 30 ms).
 */
class DamperModel(keyMap: KeyMap, info: BankInfo, val profile: InstrumentProfile, sampleRate: Int = HK.SR) {
    @JvmField val damp = FloatArray(HK.KEYS * DecayTables.DSTEPS)
    @JvmField val fct = FloatArray(HK.KEYS * DecayTables.DSTEPS)
    @JvmField val lastDamper: Int = profile.lastDamper
    @JvmField val harpsichord: Boolean = profile.id == InstrumentId.HARPSICHORD
    @JvmField val releaseCarriesTail: Boolean = info.releaseCarriesTail

    /** Per-block amplitude multipliers of a re-struck voice (pedal below / above 0.33). */
    @JvmField val restrikeMulUp: Float
    @JvmField val restrikeMulPedal: Float

    /** Frames from EV_KEY_UP to the damper landing: round(damperLagMs · fs / 1000). */
    @JvmField val lagFrames: Int = (profile.damperLagMs * sampleRate / 1000f).roundToInt()
    /** Harpsichord: key-up → 8′ and 4′ quill passes (jack-fall noise and the release handoff). */
    @JvmField val quill8Frames: Int = (HarpsiTiming.quill8PassMs * sampleRate / 1000f).roundToInt()
    @JvmField val quill4Frames: Int = (HarpsiTiming.quill4PassMs * sampleRate / 1000f).roundToInt()
    /** 4′ onset lead per velocity: round(staggerMs(v) · fs / 1000). */
    @JvmField val staggerFrames = IntArray(128) { (HarpsiTiming.staggerMs(it) * sampleRate / 1000f).roundToInt() }
    @JvmField val handoffFrames: Int = (0.030f * sampleRate).roundToInt()

    init {
        val dt = HK.BLOCK.toDouble() / sampleRate
        for (k in 0 until HK.KEYS) {
            val t60 = info.damperT60.getOrNull(k)?.takeIf { it > 0f }?.toDouble() ?: profile.defaultDamperT60(k).toDouble()
            val f0 = keyMap.f0Hz.getOrNull(k)?.takeIf { it > 0f }?.toDouble() ?: (440.0 * 2.0.pow((k - 69) / 12.0))
            val floor = min(18_000.0, 6.0 * f0) / 18_000.0
            for (j in 0 until DecayTables.DSTEPS) {
                val d = j / 32.0
                damp[k * DecayTables.DSTEPS + j] = exp(-6.91 * dt * d / t60).toFloat()
                fct[k * DecayTables.DSTEPS + j] = DecayTables.lpIndexOf(18_000.0 * floor.pow(d))
            }
        }
        restrikeMulUp = exp(-dt / (profile.restrikeTauUpMs / 1000.0)).toFloat()
        restrikeMulPedal = exp(-dt / (profile.restrikeTauPedalMs / 1000.0)).toFloat()
    }

    /** The damping multiplier of key [k] for one block at damping strength [d] (0..1). */
    fun dampOf(k: Int, d: Float): Float = damp[k * DecayTables.DSTEPS + step(d)]

    /** The spectral cutoff target (LP index) of key [k] at damping strength [d]. */
    fun fcTargetOf(k: Int, d: Float): Float = fct[k * DecayTables.DSTEPS + step(d)]

    companion object {
        /** round(32 · D), clamped to 0..32. */
        @JvmStatic fun step(d: Float): Int {
            val j = (d * 32f + 0.5f).toInt()
            return if (j < 0) 0 else if (j > 32) 32 else j
        }
    }
}
