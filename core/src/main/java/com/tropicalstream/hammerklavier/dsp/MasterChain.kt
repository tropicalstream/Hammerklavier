package com.tropicalstream.hammerklavier.dsp

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.MasterProcessor
import com.tropicalstream.hammerklavier.contract.OutputRoute
import com.tropicalstream.hammerklavier.contract.SpeakerBass

/**
 * The master bus (PLAN §3.13): master gain (ramped linearly over each block, so a change never
 * zips) → [SpeakerEnhancer] → [Limiter] → Padé soft clip → interleave. Setters are called on
 * HKAudio (command ring) and only select prepared coefficients. The input arrays are not
 * modified. Allocation-free.
 */
class MasterChain(sampleRate: Int = HK.SR) : MasterProcessor {
    override val latencyFrames: Int get() = Limiter.LOOKAHEAD

    val enhancer = SpeakerEnhancer(sampleRate)
    val limiter = Limiter(sampleRate)
    private val bl = FloatArray(HK.BLOCK); private val br = FloatArray(HK.BLOCK)
    private var target = 1f
    private var gain = 1f

    override fun setRoute(r: OutputRoute) = enhancer.setRoute(r)
    override fun setSpeakerBass(m: SpeakerBass) = enhancer.setSpeakerBass(m)
    override fun setGain(linear: Float) { target = linear }

    override fun process(l: FloatArray, r: FloatArray, n: Int, outInterleaved: FloatArray) {
        var off = 0
        while (off < n) {
            val m = minOf(HK.BLOCK, n - off)
            val g0 = gain; val step = (target - g0) / m
            var g = g0
            for (i in 0 until m) { g += step; bl[i] = l[off + i] * g; br[i] = r[off + i] * g }
            gain = target
            enhancer.process(bl, br, m)
            limiter.process(bl, br, m)
            for (i in 0 until m) {
                outInterleaved[2 * (off + i)] = DspTables.softClip(bl[i])
                outInterleaved[2 * (off + i) + 1] = DspTables.softClip(br[i])
            }
            off += m
        }
    }

    override fun reset() { enhancer.reset(); limiter.reset(); gain = target }
}
