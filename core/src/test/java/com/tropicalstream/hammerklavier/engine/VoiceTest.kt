package com.tropicalstream.hammerklavier.engine

import com.tropicalstream.hammerklavier.contract.Cmd
import com.tropicalstream.hammerklavier.contract.QualityLadder
import com.tropicalstream.hammerklavier.contract.stub.KeyMapFixtures
import com.tropicalstream.hammerklavier.contract.stub.SineBank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/** T2.4: the voice (PLAN §3.6). */
class VoiceTest {
    @Test fun rateOneIsBitIdenticalToTheSource() {
        val key = 57
        val h = Harness()
        h.play(perfSong(listOf(N(1000.0, 3000.0, key, 100))))
        h.renderTo(48_000 + 96_000)
        val wave = SineBank.waveOf((key - 21) / 3)
        val start = 48_000 - SineBank.ONSET                        // frame 0 of the region
        for (i in 0 until 90_000) {                                // the key is held throughout: no damping
            assertEquals("L $i", wave[2 * i].toFloat(), h.left(start + i) * 32768f, 0f)
            assertEquals("R $i", wave[2 * i + 1].toFloat(), h.right(start + i) * 32768f, 0f)
        }
        for (i in 0 until start) assertEquals(0f, h.left(i), 0f)
    }

    @Test fun semitoneUpPeaksWithinATenthOfACent() {
        val key = 58                                              // root 57 played at 2^(1/12)
        val h = Harness()
        h.play(perfSong(listOf(N(1000.0, 3000.0, key, 100))))
        h.renderTo(48_000 + 60_000)
        val x = FloatArray(48_000) { h.left(48_200 + it) }
        val f = peakHz(x, hz(key) - 3, hz(key) + 3)
        assertTrue("cents ${cents(f, hz(key))}", abs(cents(f, hz(key))) <= 0.1)
    }

    @Test fun windowRefillsLeaveNoDiscontinuity() {
        for (q in intArrayOf(0, 2)) {                            // Hermite and linear
            val key = 56                                          // root 57 at 2^(−1/12): refills every ≈ 1085 frames
            val h = Harness()
            h.cmd(Cmd.QUALITY, ref = QualityLadder.of(q, 128))
            h.play(perfSong(listOf(N(1000.0, 2900.0, key, 100))))
            h.renderTo(48_000 + 90_000)
            val wave = SineBank.waveOf((key + 1 - 21) / 3)
            var srcMax = 0f
            for (i in SineBank.ONSET + 200 until wave.size / 2 - 1) srcMax = maxOf(srcMax, abs(wave[2 * i + 2] - wave[2 * i]) / 32768f)
            var outMax = 0f
            for (i in 48_000 + 200 until 48_000 + 88_000) outMax = maxOf(outMax, abs(h.left(i) - h.left(i - 1)))
            assertTrue("Q$q out $outMax src $srcMax", outMax <= srcMax * 1.05f)
        }
    }

    @Test fun levelDbTracksTheRenderedRms() {
        for (key in intArrayOf(33, 40, 60, 84)) {
            val h = Harness()
            val lv = HashMap<Int, Double>()
            h.onBlock = { b -> val v = h.voicesOf(key).firstOrNull(); if (v != null) lv[b] = 10.0.pow(v.levelDb / 10.0) }
            h.play(perfSong(listOf(N(1000.0, 2800.0, key, 100))))
            h.renderTo(48_000 + 75_000)
            var b = 49_152; var n = 0
            while (b + 2560 < 48_000 + 70_000) {            // 10-block windows: a block is a fraction of a low note's period
                var s = 0.0; for (j in 0 until 10) s += lv.getValue(b + 256 * j)
                val e = 10 * kotlin.math.log10(s / 10) - db(h.rms(b, b + 2560))
                assertTrue("key $key at $b: levelDb − RMS = $e dB", abs(e) <= 1.0)
                b += 2560; n++
            }
            assertTrue(n > 20)
        }
    }

    @Test fun voicesDieBelowMinus80OrAtTheRegionEnd() {
        val h = Harness()
        h.play(perfSong(listOf(N(1000.0, 5000.0, 60, 100))))
        h.renderTo(48_000 + 2 * 48_000 + 2048)                    // 2 s regions
        assertTrue(h.voicesOf(60).isEmpty())
        assertEquals(0, h.core.pool.mainActive)
    }

    @Test fun crossfadedNotesUseTwoVoicesThatSumToTheLayerLevel() {
        val km = KeyMapFixtures.forSineBank(2, KeyMapFixtures.Mode.XFADE)
        val v = 64                                                // split at 1 + round(127/2) = 65: v 61..68 crossfade
        assertTrue(km.velLayerB[v] >= 0)
        val h = Harness(bank = SineBank(layers = 2), keyMap = km)
        h.play(perfSong(listOf(N(1000.0, 2000.0, 57, v))))
        h.renderTo(48_000 + 4096)
        assertEquals(2, h.voicesOf(57).size)
        val wave = SineBank.waveOf(12)
        val start = 48_000 - SineBank.ONSET
        for (i in 100 until 3000) assertEquals(wave[2 * i] / 32768f, h.left(start + i), 2e-6f)
    }

    @Test fun ratesUpToAFifthStayInsideTheWindow() {
        val km = KeyMapFixtures.forSineBank(1).withRate(57, 2.0.pow(7 / 12.0).toFloat())
        val h = Harness(keyMap = km)
        h.play(perfSong(listOf(N(1000.0, 2000.0, 57, 100))))
        h.renderTo(48_000 + 40_000)
        assertTrue(h.maxAbs(48_000, 60_000) > 0.1f)
    }
}
