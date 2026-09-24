package com.tropicalstream.hammerklavier.dsp

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.RoomDesign
import com.tropicalstream.hammerklavier.contract.RoomProcessor

/**
 * The room (PLAN §3.12): [DirectPath] + [EarlyReflections] + [FdnReverb]. The input gain
 * ([setInputGain], pause / resume / seek) scales everything that enters the room, so a paused
 * tail rings on. Output = balance · (direct + early) + levelGain · late, levelGain also in the direct gain. [setDesign] glides every parameter
 * over `glideMs` (two tap sets crossfaded, pre-delay crossfaded); the first design applies at
 * once. Writes the output (does not add). Allocation-free.
 */
class RoomChain(sampleRate: Int = HK.SR) : RoomProcessor {
    private val fs = sampleRate
    val direct = DirectPath(sampleRate)
    val early = EarlyReflections(sampleRate)
    val fdn = FdnReverb(sampleRate)

    private val inGain = Glide(1f)
    private val lateGain = Glide(0f)          // reverbGain × levelGain, on the FDN's output
    private var hasDesign = false

    private val gIn = FloatArray(HK.BLOCK)
    private val ones = FloatArray(HK.BLOCK) { 1f }
    private val gLate = FloatArray(HK.BLOCK)
    private val dL = FloatArray(HK.BLOCK); private val dR = FloatArray(HK.BLOCK)
    private val mono = FloatArray(HK.BLOCK)
    private val monoRev = FloatArray(HK.BLOCK)
    private val fdnIn = FloatArray(HK.BLOCK)
    private val lateL = FloatArray(HK.BLOCK); private val lateR = FloatArray(HK.BLOCK)
    private var tail = false

    override fun setDesign(d: RoomDesign, glideMs: Int) {
        val frames = if (hasDesign) (glideMs.toLong() * fs / 1000).toInt() else 0
        // levelGain goes into the direct gain (one glide of the product: two glides multiplied bump
        // mid-way when one falls as the other rises) and the late gain (reverbGain × levelGain),
        // which scales the FDN's OUTPUT so that on a view change the late level follows the direct
        // one at once instead of lagging by the tail (a 2 LU dip on Action → Hall). Both glide
        // linearly in amplitude: of the laws tried this keeps the momentary loudness of a view
        // change closest to the two views' (ViewLoudnessTest; power-linear glides bump ~1 LU through
        // the direct–late cross term).
        direct.setTarget(d.directGain * d.levelGain, d.airLpHz, d.width, d.worldLocked, d.sourceAzimuthRad, frames)
        early.setDesign(d, frames)
        fdn.setT60(d.t60Low, d.t60Mid, d.t60High, frames)
        lateGain.set(d.reverbGain * d.levelGain, frames)
        hasDesign = true
    }

    override fun setLines(n: Int) = fdn.setLines(n)

    override fun setInputGain(g: Float, rampMs: Float) { inGain.set(g, (rampMs * fs / 1000f).toInt()) }

    override fun process(inL: FloatArray, inR: FloatArray, outL: FloatArray, outR: FloatArray, n: Int, headYawRad: Float) {
        var off = 0
        var peak = 0f
        while (off < n) {
            val m = minOf(HK.BLOCK, n - off)
            for (i in 0 until m) { gIn[i] = inGain.next(); gLate[i] = lateGain.next() }
            if (off == 0) {
                direct.process(inL, inR, gIn, dL, dR, mono, monoRev, m, headYawRad)
            } else {
                // Rare path (n > BLOCK): copy the chunk to the front of the scratch buffers.
                System.arraycopy(inL, off, lateL, 0, m); System.arraycopy(inR, off, lateR, 0, m)
                direct.process(lateL, lateR, gIn, dL, dR, mono, monoRev, m, headYawRad)
            }
            early.process(mono, monoRev, dL, dR, fdnIn, m)
            java.util.Arrays.fill(lateL, 0, m, 0f); java.util.Arrays.fill(lateR, 0, m, 0f)
            fdn.process(fdnIn, ones, lateL, lateR, m)
            val bl = direct.balL; val br = direct.balR
            for (i in 0 until m) {
                val gl = gLate[i]
                val wl = gl * lateL[i]; val wr = gl * lateR[i]
                outL[off + i] = bl[i] * dL[i] + wl; outR[off + i] = br[i] * dR[i] + wr
                val a = if (wl < 0f) -wl else wl; if (a > peak) peak = a
                val b = if (wr < 0f) -wr else wr; if (b > peak) peak = b
            }
            off += m
        }
        tail = peak > TAIL_FLOOR
    }

    override val tailActive: Boolean get() = tail

    override fun reset() {
        direct.reset(); early.reset(); fdn.reset(); tail = false
    }

    companion object { const val TAIL_FLOOR = 3.1623e-5f }   // −90 dBFS
}
