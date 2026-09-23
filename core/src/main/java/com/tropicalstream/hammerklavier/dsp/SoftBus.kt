package com.tropicalstream.hammerklavier.dsp

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.SoftBusProcessor
import com.tropicalstream.hammerklavier.contract.SoftKind
import kotlin.math.pow

/**
 * The soft bus (PLAN §3.8, §3.13): the voices started under the soft pedal are filtered here and
 * ADDED to the dry bus. Grand una corda: −2.5 dB and a high shelf −4 dB at 2.2 kHz; upright
 * hammer rail: −4 dB, shelf −2 dB at 3 kHz; NONE: plain add. All coefficients are designed at
 * construction, so [configure] is a reference swap (audio-thread safe). Allocation-free.
 *
 * Deviation: §3.8 asks for a second, smaller shelf (−1.5 dB) on the single-strung keys 21–28, but
 * the contract carries one soft stereo pair, so those keys get the ≥ 2-string class here (see
 * docs/requests/WP3.md).
 */
class SoftBus(sampleRate: Int = HK.SR) : SoftBusProcessor {
    private val fs = sampleRate.toDouble()
    private val unaCorda = BiquadCoefs.highShelf(2200.0, -4.0, fs)
    private val hammerRail = BiquadCoefs.highShelf(3000.0, -2.0, fs)
    private val unaGain = 10.0.pow(-2.5 / 20).toFloat()
    private val railGain = 10.0.pow(-4.0 / 20).toFloat()

    private val l = Biquad(); private val r = Biquad()
    private var gain = 1f
    private var filtered = false
    @JvmField val scratchL = FloatArray(HK.BLOCK); @JvmField val scratchR = FloatArray(HK.BLOCK)

    init { configure(SoftKind.NONE) }

    override fun configure(kind: SoftKind) {
        when (kind) {
            SoftKind.NONE -> { filtered = false; gain = 1f }
            SoftKind.UNA_CORDA -> { filtered = true; gain = unaGain; l.c = unaCorda; r.c = unaCorda }
            SoftKind.HAMMER_RAIL -> { filtered = true; gain = railGain; l.c = hammerRail; r.c = hammerRail }
        }
        reset()
    }

    override fun process(inL: FloatArray, inR: FloatArray, outL: FloatArray, outR: FloatArray, n: Int) {
        if (!filtered) { for (i in 0 until n) { outL[i] += inL[i]; outR[i] += inR[i] }; return }
        var off = 0
        while (off < n) {
            val m = minOf(HK.BLOCK, n - off)
            System.arraycopy(inL, off, scratchL, 0, m); System.arraycopy(inR, off, scratchR, 0, m)
            l.process(scratchL, m); r.process(scratchR, m)
            val g = gain
            for (i in 0 until m) { outL[off + i] += g * scratchL[i]; outR[off + i] += g * scratchR[i] }
            off += m
        }
    }

    override fun reset() { l.reset(); r.reset() }
}
