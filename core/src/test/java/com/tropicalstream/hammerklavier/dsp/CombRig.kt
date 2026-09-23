package com.tropicalstream.hammerklavier.dsp

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.ResonanceMode
import kotlin.math.pow

/**
 * Drives a [ResonanceBank] the way EngineCore does (§3.15): per block, the mono voice mix, the
 * per-key self rows, gate / softFeed / D, and records per-comb mean-square lanes, the voice RMS
 * and the comb outputs. Voices are whole pre-rendered mono signals.
 */
class CombRig(val instrument: InstrumentId = InstrumentId.GRAND, mode: ResonanceMode = ResonanceMode.NATURAL,
              dispersion: Boolean = true, f0: FloatArray = et(440.0), b: FloatArray = FloatArray(HK.KEYS),
              maxActive: Int = 88) {
    val bank = ResonanceBank()
    val profile = InstrumentProfile.of(instrument)
    class Voice(val key: Int, val x: FloatArray, val soft: Boolean)
    val voices = ArrayList<Voice>()

    val mix = FloatArray(HK.BLOCK)
    val self = FloatArray(88 * HK.BLOCK)
    val selfRows = BooleanArray(88)
    val gate = FloatArray(HK.KEYS)
    val softFeed = BooleanArray(HK.KEYS)
    val damping = FloatArray(HK.KEYS) { 1f }
    val outL = FloatArray(HK.BLOCK); val outR = FloatArray(HK.BLOCK)
    val lanes = FloatArray(88)

    init {
        bank.apply(bank.design(f0, b, null, profile), 0)
        bank.setMode(mode, instrument, maxActive, dispersion)
    }

    /** Runs [blocks] blocks starting at block index [b0]; [state] sets gate/D/softFeed per block. */
    fun run(blocks: Int, b0: Int = 0, state: (Int) -> Unit = {}, sink: (Int) -> Unit = {}) {
        for (bi in b0 until b0 + blocks) {
            state(bi)
            java.util.Arrays.fill(mix, 0f); java.util.Arrays.fill(selfRows, false)
            val t0 = bi * HK.BLOCK
            for (v in voices) {
                val row = v.key - 21
                if (!selfRows[row]) { java.util.Arrays.fill(self, row * HK.BLOCK, (row + 1) * HK.BLOCK, 0f); selfRows[row] = true }
                for (i in 0 until HK.BLOCK) {
                    val s = if (t0 + i < v.x.size) v.x[t0 + i] else 0f
                    mix[i] += s; self[row * HK.BLOCK + i] += s
                }
            }
            java.util.Arrays.fill(outL, 0f); java.util.Arrays.fill(outR, 0f)
            bank.process(mix, self, selfRows, gate, softFeed, damping, outL, outR, HK.BLOCK)
            java.util.Arrays.fill(lanes, 0f)
            bank.energy(lanes)
            sink(bi)
        }
    }

    /** RMS of comb [key] over blocks [from, to) from recorded lanes (lane ms is halved: ×2). */
    companion object {
        fun et(a: Double): FloatArray = FloatArray(HK.KEYS) { (a * 2.0.pow((it - 69) / 12.0)).toFloat() }
        fun blockOf(sec: Double): Int = (sec * HK.SR / HK.BLOCK).toInt()
    }
}
