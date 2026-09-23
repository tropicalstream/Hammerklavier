package com.tropicalstream.hammerklavier.engine

import com.tropicalstream.hammerklavier.contract.BankInfo
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.KeyMap
import com.tropicalstream.hammerklavier.contract.ResonanceMode
import com.tropicalstream.hammerklavier.contract.ResonanceProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/** T2.11: energy lanes are linear RMS re full scale, one sqrt per lane of voice + comb mean-square (R58, R80). */
class EnergyTest {
    private val lanes = FloatArray(HK.LANES)

    /**
     * Per-block lane powers of [key]; compared with the output over 10-block (53 ms) windows, since a
     * 256-frame block holds a fraction of a low note's period.
     */
    private fun windows(h: Harness, key: Int, fromFrame: Int, toFrame: Int): List<Pair<Double, Double>> {
        val pw = HashMap<Int, Double>()
        h.onBlock = { b -> h.core.energy(lanes); val x = lanes[key - 21].toDouble(); pw[b] = x * x }
        h.renderTo(toFrame + 4096)
        val out = ArrayList<Pair<Double, Double>>()
        var b = (fromFrame / 256) * 256
        while (b + 2560 <= toFrame) {
            var s = 0.0; for (j in 0 until 10) s += pw.getValue(b + 256 * j)
            out.add(sqrt(s / 10) to h.rms(b, b + 2560))
            b += 2560
        }
        return out
    }

    @Test fun minus12DbfsC4GivesLane39AtAQuarter() {
        val h = Harness()
        h.play(perfSong(listOf(N(1000.0, 3000.0, 60, 100))))
        val w = windows(h, 60, 48_500, 48_000 + 90_000)
        val near = w.filter { abs(db(it.second) + 12.0) < 0.5 }
        assertTrue("no window at −12 dBFS", near.isNotEmpty())
        for ((lane, _) in near) assertEquals(0.25, lane, 0.025)
    }

    @Test fun aV80LaneTracksItsOutputRms() {
        for (key in intArrayOf(33, 60, 90)) {
            val h = Harness()
            h.play(perfSong(listOf(N(1000.0, 3000.0, key, 80))))
            for ((lane, rms) in windows(h, key, 48_000 + 1024, 48_000 + 80_000))
                assertTrue("key $key lane − RMS = ${db(lane) - db(rms)} dB", abs(db(lane) - db(rms)) <= 1.0)
        }
    }

    @Test fun aDampedNotesLaneFallsAtT60d() {
        val key = 60
        val h = Harness()
        h.cmd(com.tropicalstream.hammerklavier.contract.Cmd.QUALITY, ref = com.tropicalstream.hammerklavier.contract.QualityLadder.of(2, 128))  // no spectral LP
        val t60d = InstrumentProfile.GRAND.defaultDamperT60(key).toDouble()
        val laneDb = ArrayList<Pair<Int, Double>>()
        h.onBlock = { b -> h.core.energy(lanes); laneDb.add(b to db(lanes[key - 21].toDouble())) }
        h.play(perfSong(listOf(N(1000.0, 1500.0, key, 100))))
        h.renderTo(48_000 + 48_000)
        val land = 72_000 + Math.round(InstrumentProfile.GRAND.damperLagMs * 48.0).toInt()
        val a = laneDb.first { it.first >= land + 512 }; val b = laneDb.first { it.first >= land + 512 + 4800 }
        val slope = (a.second - b.second) / ((b.first - a.first) / 48_000.0)   // dB/s
        val natural = 8.685889638 / 1.2                                     // the SineBank's own decay
        val expected = 60.0 / t60d + natural
        assertTrue("slope $slope vs $expected dB/s", abs(slope - expected) / expected < 0.05)
    }

    /** A resonance stand-in that reports a comb for every gated key without voices. */
    private class FakeCombs : ResonanceProcessor {
        val gated = BooleanArray(HK.KEYS)
        override fun prepare(keyMap: KeyMap, info: BankInfo, profile: InstrumentProfile): Any = Unit
        override fun apply(prepared: Any, glideMs: Int) {}
        override fun setMode(mode: ResonanceMode, instrument: InstrumentId, maxActive: Int, dispersion: Boolean) {}
        override fun process(mix: FloatArray, self: FloatArray, selfRows: BooleanArray, gate: FloatArray, softFeed: BooleanArray,
                             damping: FloatArray, outL: FloatArray, outR: FloatArray, n: Int) {
            for (k in 0 until HK.KEYS) gated[k] = gate[k] > 0f
        }
        override fun energy(outMeanSquare: FloatArray) { for (i in 0 until HK.LANES) if (gated[i + 21]) outMeanSquare[i] += 1e-4f }
        override val active: Int get() = 0
        override fun reset() {}
    }

    @Test fun aCombLaneAppearsForASympatheticString() {
        val h = Harness(dsp = identityDsp(FakeCombs()))
        // Pedal down: every damper up, every gate open; only key 60 plays.
        h.play(perfSong(listOf(N(1000.0, 2000.0, 60, 100)), sustainSong = constCurve(1f)))
        h.renderTo(50_000)
        h.core.energy(lanes)
        assertEquals(0.01f, lanes[72 - 21], 1e-5f)                     // √(1e-4): the comb alone
        val voiceOnly = h.rms(49_744, 50_000)
        assertTrue(lanes[60 - 21] > 0.5 * voiceOnly)                    // voice + comb, summed as mean-square
        assertEquals(sqrt(voiceOnly * voiceOnly + 1e-4), lanes[60 - 21].toDouble(), 0.12 * voiceOnly)
    }

    @Test fun energyReturnsTheBlocksEpoch() {
        val h = Harness()
        h.play(perfSong(listOf(N(1000.0, 2000.0, 60, 100))))
        h.renderBlocks(2)
        val e0 = h.core.energy(lanes)
        h.cmd(com.tropicalstream.hammerklavier.contract.Cmd.SEEK, 100_000L)
        h.renderBlocks(1)
        assertEquals(e0 + 1, h.core.energy(lanes))
    }
}
