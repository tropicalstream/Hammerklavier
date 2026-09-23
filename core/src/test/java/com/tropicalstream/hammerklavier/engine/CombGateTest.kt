package com.tropicalstream.hammerklavier.engine

import com.tropicalstream.hammerklavier.contract.BankInfo
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.KeyMap
import com.tropicalstream.hammerklavier.contract.PedalMotion
import com.tropicalstream.hammerklavier.contract.ResonanceMode
import com.tropicalstream.hammerklavier.contract.ResonanceProcessor
import com.tropicalstream.hammerklavier.contract.stub.KeyMapFixtures
import com.tropicalstream.hammerklavier.contract.stub.SineBank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T2.8: comb gates, soft feeds and self rows (PLAN §3.11, R18, R85). */
class CombGateTest {
    /** Captures what the engine hands the resonance processor each block. */
    private class Capture : ResonanceProcessor {
        val gate = FloatArray(HK.KEYS); val softFeed = BooleanArray(HK.KEYS); val damping = FloatArray(HK.KEYS)
        val selfRows = BooleanArray(HK.LANES); val self = FloatArray(HK.LANES * HK.BLOCK); val mix = FloatArray(HK.BLOCK)
        override fun prepare(keyMap: KeyMap, info: BankInfo, profile: InstrumentProfile): Any = Unit
        override fun apply(prepared: Any, glideMs: Int) {}
        override fun setMode(mode: ResonanceMode, instrument: InstrumentId, maxActive: Int, dispersion: Boolean) {}
        override fun process(mix: FloatArray, self: FloatArray, selfRows: BooleanArray, gate: FloatArray, softFeed: BooleanArray,
                             damping: FloatArray, outL: FloatArray, outR: FloatArray, n: Int) {
            System.arraycopy(gate, 0, this.gate, 0, HK.KEYS); System.arraycopy(softFeed, 0, this.softFeed, 0, HK.KEYS)
            System.arraycopy(damping, 0, this.damping, 0, HK.KEYS); System.arraycopy(selfRows, 0, this.selfRows, 0, HK.LANES)
            System.arraycopy(self, 0, this.self, 0, self.size); System.arraycopy(mix, 0, this.mix, 0, n)
        }
        override fun energy(outMeanSquare: FloatArray) {}
        override val active: Int get() = 0
        override fun reset() {}
    }

    @Test fun gateIsOneMinusDAndClosedWhenDamped() {
        for (p in floatArrayOf(0f, 0.40f, 0.44f, 0.50f, 1f)) {
            val cap = Capture()
            val h = Harness(dsp = identityDsp(cap))
            h.play(perfSong(listOf(N(1000.0, 3000.0, 60, 100), N(1000.0, 1100.0, 64, 100)), sustainSong = constCurve(p)))
            h.renderTo(1500 * 48)
            val d = PedalMotion.pedalDamping(p)
            assertEquals(1f, cap.gate[60], 0f)                                  // key down: D = 0
            val expectUp = if (d >= 0.999f) 0f else 1f - d
            assertEquals("p=$p", expectUp, cap.gate[64], 1e-6f)                 // key up and landed
            assertEquals("p=$p", expectUp, cap.gate[30], 1e-6f)                 // never played
            assertEquals(1f, cap.gate[100], 0f)                                 // above lastDamper: always free
            assertEquals(d, cap.damping[64], 1e-6f)
        }
    }

    @Test fun softFeedOnlyOnMultiStrungKeysUnderUnaCorda() {
        val cap = Capture()
        val h = Harness(dsp = identityDsp(cap))
        h.play(perfSong(listOf(N(1000.0, 3000.0, 60, 100), N(1000.0, 3000.0, 25, 100), N(1000.0, 3000.0, 40, 100)), softSong = constCurve(1f)))
        h.renderTo(60_000)
        assertTrue(cap.softFeed[60]); assertTrue(cap.softFeed[40])
        assertFalse(cap.softFeed[25])                                           // one string (21–28)
        assertFalse(cap.softFeed[62])                                           // no voice
        assertTrue(h.voicesOf(60).all { it.bus == Voice.SOFT })
        // Upright: the soft pedal is a hammer rail, the soft bus but no comb feed.
        val capU = Capture()
        val u = Harness(profile = InstrumentProfile.UPRIGHT, bank = SineBank(layers = 1, instrument = InstrumentId.UPRIGHT),
            keyMap = KeyMapFixtures.forSineBank(1), dsp = identityDsp(capU))
        u.play(perfSong(listOf(N(1000.0, 3000.0, 60, 100)), profile = InstrumentProfile.UPRIGHT, softSong = constCurve(1f)))
        u.renderTo(60_000)
        assertFalse(capU.softFeed[60])
        assertTrue(u.voicesOf(60).all { it.bus == Voice.SOFT })
        // Without the pedal: dry bus, no feed.
        val cap0 = Capture()
        val g = Harness(dsp = identityDsp(cap0))
        g.play(perfSong(listOf(N(1000.0, 3000.0, 60, 100))))
        g.renderTo(60_000)
        assertFalse(cap0.softFeed[60]); assertTrue(g.voicesOf(60).all { it.bus == Voice.DRY })
    }

    @Test fun selfRowsAreWrittenExactlyForGatedKeysWithVoices() {
        val cap = Capture()
        val h = Harness(dsp = identityDsp(cap))
        // 60 held (gated, sounding); 64 released without pedal (damped: gate 0, still sounding); 67 never played.
        h.play(perfSong(listOf(N(1000.0, 3000.0, 60, 100), N(1000.0, 1100.0, 64, 100))))
        var checked = 0
        h.onBlock = { b ->
            if (b >= 1200 * 48 && b < 1400 * 48) {
                for (k in 21..108) {
                    val sounding = h.voicesOf(k).isNotEmpty()
                    val want = (cap.gate[k] > 0f || cap.softFeed[k]) && sounding
                    assertEquals("key $k block $b", want, cap.selfRows[k - 21])
                }
                assertTrue(cap.selfRows[60 - 21]); assertFalse(cap.selfRows[64 - 21]); assertFalse(cap.selfRows[67 - 21])
                assertTrue(h.voicesOf(64).isNotEmpty())
                // Row 60 holds key 60's own mono contribution: with key 64 damped out of the rows, mix = row 60 + key 64.
                var e = 0.0
                for (i in 0 until HK.BLOCK) e += Math.abs(cap.self[(60 - 21) * HK.BLOCK + i] - 0.5 * (h.left(b + i) + h.right(b + i)))
                assertTrue(e > 0.0)      // key 64 still sounds in the mix, so the row is not the whole mix …
                checked++
            }
        }
        h.renderTo(1500 * 48)
        assertTrue(checked > 10)
        // … and alone, the row equals the mono output exactly.
        val cap2 = Capture()
        val one = Harness(dsp = identityDsp(cap2))
        one.onBlock = { b ->
            if (b >= 50_000 && b < 52_000) for (i in 0 until HK.BLOCK)
                assertEquals(0.5f * (one.left(b + i) + one.right(b + i)), cap2.self[(60 - 21) * HK.BLOCK + i], 1e-7f)
        }
        one.play(perfSong(listOf(N(1000.0, 3000.0, 60, 100))))
        one.renderTo(52_500)
    }
}
