package com.tropicalstream.hammerklavier.contract.stub

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.KeyMap
import com.tropicalstream.hammerklavier.contract.TuningSpec
import kotlin.math.pow
import kotlin.math.roundToInt

/** Valid KeyMaps for the stub banks (PLAN §2.3). */
object KeyMapFixtures {
    enum class Mode { HARD, XFADE }

    /** Half-width of an XFADE crossfade in velocity steps (the 6-layer grand's ±4). */
    const val XFADE_HALF = 4

    /**
     * The KeyMap matching `SineBank(layers, stops)`: A440 equal temperament, zero stretch shape;
     * key k (sounding k + 12 on the harpsichord's 4′ stop 1) plays the nearest root at rate
     * 2^((sounding − root)/12); gains 1 (no level-curve trim); onsetOut = round(96 / rate).
     * Velocity splits are equal: layer j covers velocities from 1 + round(127·j/layers). HARD
     * switches at each split; XFADE crossfades linearly over ±[XFADE_HALF] around it. A unit
     * (u = stop * layers + layer) missing from [readyMask] maps no region; velocities whose layer
     * is not ready (on stop 0) use the nearest ready layer. No release or pedal regions.
     */
    fun forSineBank(layers: Int, mode: Mode = Mode.HARD, stops: Int = 1, readyMask: Long = -1L): KeyMap {
        require(layers in 1..16 && stops in 1..2)
        fun ready(stop: Int, layer: Int) = (readyMask ushr (stop * layers + layer)) and 1L == 1L
        val readyLayers = (0 until layers).filter { ready(0, it) }
        fun nearestReady(layer: Int): Int = readyLayers.minByOrNull { kotlin.math.abs(it - layer) } ?: -1

        val splits = IntArray(layers + 1) { if (it == layers) 128 else 1 + (127.0 * it / layers).roundToInt() }
        fun layerOf(v: Int): Int { var j = 0; while (j + 1 < layers && v >= splits[j + 1]) j++; return j }

        val velLayerA = ByteArray(128); val velLayerB = ByteArray(128) { -1 }
        val velGainA = FloatArray(128); val velGainB = FloatArray(128)
        for (v in 0 until 128) {
            val vv = v.coerceAtLeast(1)
            val j = layerOf(vv)
            var a = j; var b = -1; var wb = 0f
            if (mode == Mode.XFADE) {
                // Crossfade with the layer above near its split, or with the layer below near ours.
                if (j + 1 < layers && vv >= splits[j + 1] - XFADE_HALF) {
                    b = j + 1; wb = (vv - (splits[j + 1] - XFADE_HALF)) / (2f * XFADE_HALF)
                } else if (j > 0 && vv < splits[j] + XFADE_HALF) {
                    a = j - 1; b = j; wb = (vv - (splits[j] - XFADE_HALF)) / (2f * XFADE_HALF)
                }
            }
            val ra = nearestReady(a); val rb = if (b >= 0) nearestReady(b) else -1
            if (rb < 0 || rb == ra) {
                velLayerA[v] = ra.toByte(); velLayerB[v] = -1; velGainA[v] = if (ra >= 0) 1f else 0f; velGainB[v] = 0f
            } else {
                velLayerA[v] = ra.toByte(); velLayerB[v] = rb.toByte()
                velGainA[v] = 1f - wb.coerceIn(0f, 1f); velGainB[v] = wb.coerceIn(0f, 1f)
            }
        }

        val n = stops * layers * HK.KEYS
        val region = IntArray(n) { -1 }; val rate = FloatArray(n) { 1f }; val gain = FloatArray(n) { 1f }
        val onsetOut = IntArray(n) { SineBank.ONSET }; val lpHz = FloatArray(n)
        for (stop in 0 until stops) for (layer in 0 until layers) for (k in 0 until HK.KEYS) {
            val sk = (stop * layers + layer) * HK.KEYS + k
            if (!ready(stop, layer)) continue
            val sounding = k + 12 * stop
            val ri = SineBank.nearestRoot(sounding)
            val r = 2.0.pow((sounding - SineBank.rootKey(ri)) / 12.0).toFloat()
            region[sk] = SineBank.regionOf(stop, layer, ri, layers)
            rate[sk] = r
            onsetOut[sk] = (SineBank.ONSET / r).roundToInt()
        }
        return KeyMap(
            tuning = TuningSpec.A440_EQUAL, readyMask = readyMask, layers = layers, stops = stops,
            velLayerA = velLayerA, velLayerB = velLayerB, velGainA = velGainA, velGainB = velGainB,
            region = region, rate = rate, gain = gain, onsetOut = onsetOut, lpHz = lpHz,
            release = IntArray(stops * HK.KEYS) { -1 }, releaseRate = FloatArray(stops * HK.KEYS) { 1f },
            releaseGain = FloatArray(stops * HK.KEYS),
            pedalDown = IntArray(0), pedalUp = IntArray(0), pedalGain = 0f,
            f0Hz = FloatArray(HK.KEYS) { (440.0 * 2.0.pow((it - 69) / 12.0)).toFloat() },
            inharmB = FloatArray(HK.KEYS),
            strings = ByteArray(HK.KEYS) { (if (stops == 2 || it <= 28) 1 else if (it <= 48) 2 else 3).toByte() })   // 2 stops = harpsichord
    }
}
