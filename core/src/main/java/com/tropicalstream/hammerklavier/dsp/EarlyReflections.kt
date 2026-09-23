package com.tropicalstream.hammerklavier.dsp

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.RoomDesign

/**
 * Early reflections (PLAN §3.12): 12 image-source taps on the mono direct feed, each with its
 * delay, constant-power pan gains (the design's) × `erGain`, and one of two shared low-passes
 * (bright 12 kHz, dull 5 kHz). On a listener change two 12-tap sets run and crossfade old → new
 * over the glide (delays never jump), and the FDN input crossfades between the old and new
 * pre-delay taps. Allocation-free after construction.
 */
class EarlyReflections(sampleRate: Int = HK.SR) {
    private val fs = sampleRate.toFloat()
    private val line = FloatArray(SIZE)
    private val revLine = FloatArray(SIZE)   // the FDN feed (no directGain), pre-delayed
    private var pos = 0

    private val delay = Array(2) { IntArray(TAPS) }
    private val gl = Array(2) { FloatArray(TAPS) }
    private val gr = Array(2) { FloatArray(TAPS) }
    private val bright = Array(2) { BooleanArray(TAPS) }
    private val pre = IntArray(2)
    private var cur = 0                     // the set being faded in (or the only one)
    private var fade = 1f                   // weight of set cur
    private var fadeStep = 0f
    private var hasDesign = false

    private val bL = OnePole(); private val bR = OnePole(); private val dL = OnePole(); private val dR = OnePole()
    private val accBL = FloatArray(HK.BLOCK); private val accBR = FloatArray(HK.BLOCK)
    private val accDL = FloatArray(HK.BLOCK); private val accDR = FloatArray(HK.BLOCK)

    val crossfading: Boolean get() = fade < 1f

    fun setDesign(d: RoomDesign, glideFrames: Int) {
        val next = if (hasDesign && glideFrames > 0) 1 - cur else cur
        val eg = d.erGain
        for (t in 0 until TAPS) {
            delay[next][t] = d.erDelay[t].coerceIn(1, SIZE - HK.BLOCK - 1)
            gl[next][t] = d.erGainL[t] * eg; gr[next][t] = d.erGainR[t] * eg
            bright[next][t] = d.erBright[t]
        }
        pre[next] = d.preDelayFrames.coerceIn(0, SIZE - HK.BLOCK - 1)
        bL.setHz(d.brightLpHz, fs); bR.setHz(d.brightLpHz, fs); dL.setHz(d.dullLpHz, fs); dR.setHz(d.dullLpHz, fs)
        if (hasDesign && glideFrames > 0) { cur = next; fade = 0f; fadeStep = 1f / glideFrames } else { cur = next; fade = 1f; fadeStep = 0f }
        hasDesign = true
    }

    fun reset() {
        java.util.Arrays.fill(line, 0f); java.util.Arrays.fill(revLine, 0f); bL.reset(); bR.reset(); dL.reset(); dR.reset()
        fade = 1f; fadeStep = 0f
    }

    /**
     * Adds the reflections of [mono] into [outL]/[outR] and writes the pre-delayed [revMono] (the
     * direct mono without directGain) into [fdnIn]. n ≤ BLOCK.
     */
    fun process(mono: FloatArray, revMono: FloatArray, outL: FloatArray, outR: FloatArray, fdnIn: FloatArray, n: Int) {
        val c = cur; val o = 1 - c
        val dc = delay[c]; val lc = gl[c]; val rc = gr[c]; val bc = bright[c]
        val dOld = delay[o]; val lo = gl[o]; val ro = gr[o]; val bo = bright[o]
        val pc = pre[c]; val po = pre[o]
        var p = pos
        var x = fade
        val fading = x < 1f
        for (i in 0 until n) {
            line[p and MASK] = mono[i]; revLine[p and MASK] = revMono[i]
            var sbl = 0f; var sbr = 0f; var sdl = 0f; var sdr = 0f
            val wc = if (fading) x else 1f
            for (t in 0 until TAPS) {
                val v = line[(p - dc[t]) and MASK] * wc
                if (bc[t]) { sbl += v * lc[t]; sbr += v * rc[t] } else { sdl += v * lc[t]; sdr += v * rc[t] }
            }
            var fin = revLine[(p - pc) and MASK] * wc
            if (fading) {
                val wo = 1f - x
                for (t in 0 until TAPS) {
                    val v = line[(p - dOld[t]) and MASK] * wo
                    if (bo[t]) { sbl += v * lo[t]; sbr += v * ro[t] } else { sdl += v * lo[t]; sdr += v * ro[t] }
                }
                fin += revLine[(p - po) and MASK] * wo
                x += fadeStep
                if (x >= 1f) x = 1f
            }
            accBL[i] = sbl; accBR[i] = sbr; accDL[i] = sdl; accDR[i] = sdr
            fdnIn[i] = fin
            p++
        }
        pos = p and MASK
        fade = x
        bL.process(accBL, n); bR.process(accBR, n); dL.process(accDL, n); dR.process(accDR, n)
        for (i in 0 until n) { outL[i] += accBL[i] + accDL[i]; outR[i] += accBR[i] + accDR[i] }
    }

    companion object {
        const val TAPS = 12
        const val SIZE = 8192
        const val MASK = SIZE - 1
    }
}
