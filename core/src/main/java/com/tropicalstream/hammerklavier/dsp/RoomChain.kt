package com.tropicalstream.hammerklavier.dsp

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.RoomDesign
import com.tropicalstream.hammerklavier.contract.RoomProcessor

/**
 * The room (PLAN §3.12): [DirectPath] + [EarlyReflections] + [FdnReverb]. The input gain
 * ([setInputGain], pause / resume / seek) scales everything that enters the room, so a paused
 * tail rings on. Output = balance · (direct + early) + late. [setDesign] glides every parameter
 * over `glideMs` (two tap sets crossfaded, pre-delay crossfaded); the first design applies at
 * once. Writes the output (does not add). Allocation-free.
 */
class RoomChain(sampleRate: Int = HK.SR) : RoomProcessor {
    private val fs = sampleRate
    val direct = DirectPath(sampleRate)
    val early = EarlyReflections(sampleRate)
    val fdn = FdnReverb(sampleRate)

    private val inGain = Glide(1f)
    private val revGain = Glide(0f)
    private var hasDesign = false

    private val gIn = FloatArray(HK.BLOCK)
    private val gRev = FloatArray(HK.BLOCK)
    private val dL = FloatArray(HK.BLOCK); private val dR = FloatArray(HK.BLOCK)
    private val mono = FloatArray(HK.BLOCK)
    private val monoRev = FloatArray(HK.BLOCK)
    private val fdnIn = FloatArray(HK.BLOCK)
    private val lateL = FloatArray(HK.BLOCK); private val lateR = FloatArray(HK.BLOCK)
    private var tail = false

    override fun setDesign(d: RoomDesign, glideMs: Int) {
        val frames = if (hasDesign) (glideMs.toLong() * fs / 1000).toInt() else 0
        direct.setTarget(d.directGain, d.airLpHz, d.width, d.worldLocked, d.sourceAzimuthRad, frames)
        early.setDesign(d, frames)
        fdn.setT60(d.t60Low, d.t60Mid, d.t60High, frames)
        revGain.set(d.reverbGain, frames)
        hasDesign = true
    }

    override fun setLines(n: Int) = fdn.setLines(n)

    override fun setInputGain(g: Float, rampMs: Float) { inGain.set(g, (rampMs * fs / 1000f).toInt()) }

    override fun process(inL: FloatArray, inR: FloatArray, outL: FloatArray, outR: FloatArray, n: Int, headYawRad: Float) {
        var off = 0
        var peak = 0f
        while (off < n) {
            val m = minOf(HK.BLOCK, n - off)
            for (i in 0 until m) { gIn[i] = inGain.next(); gRev[i] = revGain.next() }
            if (off == 0) {
                direct.process(inL, inR, gIn, dL, dR, mono, monoRev, m, headYawRad)
            } else {
                // Rare path (n > BLOCK): copy the chunk to the front of the scratch buffers.
                System.arraycopy(inL, off, lateL, 0, m); System.arraycopy(inR, off, lateR, 0, m)
                direct.process(lateL, lateR, gIn, dL, dR, mono, monoRev, m, headYawRad)
            }
            early.process(mono, monoRev, dL, dR, fdnIn, m)
            java.util.Arrays.fill(lateL, 0, m, 0f); java.util.Arrays.fill(lateR, 0, m, 0f)
            fdn.process(fdnIn, gRev, lateL, lateR, m)
            val bl = direct.balL; val br = direct.balR
            for (i in 0 until m) {
                val l = bl[i] * dL[i] + lateL[i]; val r = br[i] * dR[i] + lateR[i]
                outL[off + i] = l; outR[off + i] = r
                val a = if (lateL[i] < 0f) -lateL[i] else lateL[i]; if (a > peak) peak = a
                val b = if (lateR[i] < 0f) -lateR[i] else lateR[i]; if (b > peak) peak = b
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
