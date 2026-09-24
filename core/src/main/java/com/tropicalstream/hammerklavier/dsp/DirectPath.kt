package com.tropicalstream.hammerklavier.dsp

import com.tropicalstream.hammerklavier.contract.HK
import kotlin.math.exp

/**
 * A value that glides linearly to a target over a number of frames (audio thread, no allocation).
 */
class Glide(initial: Float = 0f) {
    @JvmField var value = initial
    @JvmField var target = initial
    private var step = 0f
    private var left = 0

    fun set(t: Float, frames: Int) {
        target = t
        if (frames <= 0) { value = t; left = 0; step = 0f } else { left = frames; step = (t - value) / frames }
    }

    /** Advances one frame and returns the new value. */
    fun next(): Float {
        if (left > 0) { value += step; if (--left == 0) value = target }
        return value
    }

    /** Advances [n] frames at once (for parameters updated per block). */
    fun skip(n: Int): Float {
        if (left > 0) { if (n >= left) { value = target; left = 0 } else { value += step * n; left -= n } }
        return value
    }

    val gliding: Boolean get() = left > 0
}

/**
 * The direct path (PLAN §3.12): gain `directGain`, air absorption one-pole at `airLpHz`, mid/side
 * width, and in the Hall (`worldLocked`) the constant-power balance `pan = SIN[sourceAzimuth −
 * yawPred]` from the 1024-entry table with a 20 ms one-pole smoother on the yaw. The balance gains
 * are exposed per frame ([balL], [balR], interpolated over the block) so that RoomChain applies
 * them to the direct + early sum. Allocation-free.
 */
class DirectPath(sampleRate: Int = HK.SR) {
    private val fs = sampleRate.toFloat()
    private val gain = Glide(1f)
    private val air = Glide(18000f)
    private val width = Glide(1f)
    private val lpL = OnePole(); private val lpR = OnePole()
    private var worldLocked = false
    private var sourceAz = 0f
    private var yawS = 0f
    private var yawInit = false
    private val yawCoef = exp(-HK.BLOCK / (0.020 * sampleRate)).toFloat()     // 20 ms one-pole, per block
    private var bl0 = 1f; private var br0 = 1f
    @JvmField val balL = FloatArray(HK.BLOCK); @JvmField val balR = FloatArray(HK.BLOCK)

    init { lpL.setHz(18000f, fs); lpR.setHz(18000f, fs) }

    fun setTarget(directGain: Float, airLpHz: Float, w: Float, locked: Boolean, sourceAzimuthRad: Float, glideFrames: Int) {
        gain.set(directGain, glideFrames); air.set(airLpHz, glideFrames); width.set(w, glideFrames)
        if (locked != worldLocked) yawInit = false
        worldLocked = locked; sourceAz = sourceAzimuthRad
    }

    fun reset() { lpL.reset(); lpR.reset(); yawInit = false }

    /** The smoothed yaw of the last block (tests). */
    val smoothedYaw: Float get() = yawS

    /**
     * Direct output (width applied, balance NOT applied) into [outL]/[outR]; the mono of the
     * gained, air-filtered input into [mono] (the early-reflection feed); the same mono without
     * `directGain` into [monoRev] (the FDN feed: §3.12 reverbGain is the same at every seat, so the
     * late level must not follow the direct distance law). n ≤ BLOCK.
     */
    fun process(inL: FloatArray, inR: FloatArray, inGain: FloatArray, outL: FloatArray, outR: FloatArray, mono: FloatArray, monoRev: FloatArray, n: Int, headYawRad: Float) {
        val a = air.skip(n)
        lpL.setHz(a, fs); lpR.setHz(a, fs)
        val ca = lpL.a
        var sl = lpL.y1; var sr = lpR.y1
        for (i in 0 until n) {
            val gd = gain.next()
            val g = gd * inGain[i]
            val w = width.next()
            val xl = inL[i] * g; val xr = inR[i] * g
            sl = xl + ca * (sl - xl); sr = xr + ca * (sr - xr)
            val m = 0.5f * (sl + sr); val s = 0.5f * (sl - sr) * w
            mono[i] = m
            monoRev[i] = m / gd                   // gd = directGain (≥ 0.25) × levelGain (≥ 0.5)
            outL[i] = m + s; outR[i] = m - s
        }
        lpL.y1 = if (sl > -1e-20f && sl < 1e-20f) 0f else sl
        lpR.y1 = if (sr > -1e-20f && sr < 1e-20f) 0f else sr

        // Balance for this block, interpolated from the last block's gains.
        var tl = 1f; var tr = 1f
        if (worldLocked) {
            if (!yawInit) { yawS = headYawRad; yawInit = true }
            yawS = DspTables.wrapPi(yawS + (1f - yawCoef) * DspTables.wrapPi(headYawRad - yawS))
            val p = DspTables.sinT(sourceAz - yawS)
            val phi = (p + 1f) * (Math.PI / 4).toFloat()
            tl = 1.41421356f * DspTables.cosT(phi); tr = 1.41421356f * DspTables.sinT(phi)
        }
        val dl = (tl - bl0) / n; val dr = (tr - br0) / n
        var gl = bl0; var gr = br0
        for (i in 0 until n) { gl += dl; gr += dr; balL[i] = gl; balR[i] = gr }
        bl0 = tl; br0 = tr
    }
}
