package com.tropicalstream.hammerklavier.engine

import com.tropicalstream.hammerklavier.contract.KeyMap
import com.tropicalstream.hammerklavier.contract.stub.KeyMapFixtures
import com.tropicalstream.hammerklavier.contract.stub.SineBank
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/** T2.9: relative levels with PassThroughDsp-like stages (PLAN §3.5, §3.13, R101). */
class RelativeLevelTest {
    /** RMS (dB) of each velocity 1..127 on [key], one note at a time, over 5–55 ms after its onset. */
    private fun levels(km: KeyMap, layers: Int, key: Int = 60): DoubleArray {
        val h = Harness(bank = SineBank(layers = layers), keyMap = km)
        val step = 900.0                      // the previous note is fully damped away (no residue in the window)
        h.play(perfSong((1..127).map { v -> N(1000.0 + step * (v - 1), 1000.0 + step * (v - 1) + 300, key, v) }))
        h.renderTo((48 * (1000 + step * 127)).toInt())
        return DoubleArray(128) { v ->
            if (v == 0) Double.NaN else { val on = (48 * (1000 + step * (v - 1))).toInt(); db(h.rms(on + 240, on + 2640)) }
        }
    }

    /** A key map whose velocity gains follow a 0.39 dB/step level curve (as the pipeline's levelCurve trims do). */
    private fun withLevelCurve(km: KeyMap): KeyMap {
        val t = FloatArray(128) { 10.0.pow(0.39 * (it - 127) / 20.0).toFloat() }
        return km.copy(velGainA = FloatArray(128) { km.velGainA[it] * t[it] }, velGainB = FloatArray(128) { km.velGainB[it] * t[it] })
    }

    private fun check(lv: DoubleArray, label: String, strictlyRising: Boolean) {
        for (v in 2..127) {
            assertTrue("$label: v$v not monotone (${lv[v - 1]} → ${lv[v]})", lv[v] >= lv[v - 1] - 0.05)
            assertTrue("$label: v$v step ${lv[v] - lv[v - 1]} dB", abs(lv[v] - lv[v - 1]) <= 0.5)
        }
        if (strictlyRising) assertTrue("$label: range ${lv[127] - lv[1]}", lv[127] - lv[1] > 40)
    }

    @Test fun pianoLevelIsMonotoneAndContinuousAcrossEverySplit() {
        for (layers in intArrayOf(3, 6)) {
            val xf = KeyMapFixtures.forSineBank(layers, KeyMapFixtures.Mode.XFADE)
            check(levels(withLevelCurve(xf), layers), "XFADE $layers + curve", true)
            check(levels(xf, layers), "XFADE $layers", false)
        }
        val hard = KeyMapFixtures.forSineBank(16, KeyMapFixtures.Mode.HARD)
        check(levels(withLevelCurve(hard), 16), "HARD 16 + curve", true)
    }
}
