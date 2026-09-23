package com.tropicalstream.hammerklavier.dsp

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.ResonanceMode
import com.tropicalstream.hammerklavier.contract.stub.KeyMapFixtures
import com.tropicalstream.hammerklavier.contract.stub.SineBank
import com.tropicalstream.hammerklavier.dsp.CombRig.Companion.blockOf
import com.tropicalstream.hammerklavier.dsp.DspTestUtil.cents
import com.tropicalstream.hammerklavier.dsp.DspTestUtil.db
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** T3.1: the comb string resonators. */
class ResonanceBankTest {
    private val grand = InstrumentProfile.GRAND

    @Test fun everyFeedbackGainBelowOneAndAnImpulseDecays() {
        val b = FloatArray(HK.KEYS) { if (it in 21..59) 2e-4f else 1e-4f }
        val rig = CombRig(mode = ResonanceMode.RICH, b = b)
        val t = rig.bank.design(CombRig.et(440.0), b, null, grand)
        for (v in 0..1) for (g in t.gain[v]) assertTrue("g=$g", g < 1f)
        // Impulse into every comb (pedal down), then silence for 10⁶ samples.
        java.util.Arrays.fill(rig.gate, 1f); java.util.Arrays.fill(rig.damping, 0f)
        rig.voices += CombRig.Voice(108, FloatArray(1) { 1f }, false)
        var first = 0.0; var last = 0.0
        val blocks = 1_000_000 / HK.BLOCK
        rig.run(blocks, sink = { bi ->
            val p = DspTestUtil.peakAbs(rig.outL) + DspTestUtil.peakAbs(rig.outR)
            assertTrue(p.isFinite())
            if (bi in 1..40) first = maxOf(first, p)
            if (bi >= blocks - 40) last = maxOf(last, p)
        })
        assertTrue("first $first last $last", first > 0 && last < first * 1e-2)
    }

    private fun combIr(key: Int, b: FloatArray, dispersion: Boolean, seconds: Double = 2.7, a: Double = 440.0): FloatArray {
        val rig = CombRig(dispersion = dispersion, b = b, f0 = CombRig.et(a))
        rig.gate[key] = 1f; rig.damping[key] = 0f
        // Impulse carried by another key's voice so that mix − self(key) = impulse.
        val other = if (key == 21) 22 else key - 1
        rig.voices += CombRig.Voice(other, FloatArray(1) { 1f }, false)
        val n = blockOf(seconds)
        val out = FloatArray(n * HK.BLOCK)
        val g = (key - 21) / 22
        val panL = kotlin.math.cos((floatArrayOf(-0.6f, -0.2f, 0.2f, 0.6f)[g] + 1.0) * Math.PI / 4)
        rig.run(n, sink = { bi -> for (i in 0 until HK.BLOCK) out[bi * HK.BLOCK + i] = (rig.outL[i] / panL).toFloat() })
        return out
    }

    @Test fun c3PeaksOnItsF0WithinTwoCents() {
        val f0 = 440.0 * 2.0.pow((48 - 69) / 12.0)
        for (disp in listOf(false, true)) {
            val b = FloatArray(HK.KEYS)
            b[48] = if (disp) 1.5e-4f else 0f
            val ir = combIr(48, b, disp)
            val f1 = f0 * sqrt(1 + b[48])
            val pk = DspTestUtil.peakNear(ir, f1)
            assertEquals("disp=$disp", 0.0, cents(pk, f1), 2.0)
        }
    }

    @Test fun dispersionFollowsTheFittedPartialSeries() {
        for ((key, bb) in listOf(48 to 1.5e-4f, 40 to 1.0e-4f, 55 to 2.5e-4f)) {
            val b = FloatArray(HK.KEYS); b[key] = bb
            val ir = combIr(key, b, true)
            val f0 = 440.0 * 2.0.pow((key - 69) / 12.0)
            for (n in 2..10) {
                val fn = n * f0 * sqrt(1 + bb * n * n)
                val pk = DspTestUtil.peakNear(ir, fn, 20.0)
                assertEquals("key $key n $n", 0.0, cents(pk, fn), 3.0)
            }
        }
    }

    @Test fun trebleWithoutDispersionWithinFiveCentsUpToSix() {
        for ((key, bb) in listOf(60 to 1.0e-4f, 72 to 1.2e-4f)) {
            val b = FloatArray(HK.KEYS); b[key] = bb
            val ir = combIr(key, b, true)
            val f0 = 440.0 * 2.0.pow((key - 69) / 12.0)
            for (n in 1..6) {
                val fn = n * f0 * sqrt(1 + bb * n * n)
                val pk = DspTestUtil.peakNear(ir, fn, 20.0)
                assertEquals("key $key n $n", 0.0, cents(pk, fn), 5.0)
            }
        }
    }

    @Test fun retuningGlidesOver200ms() {
        val bank = ResonanceBank()
        val t440 = bank.design(CombRig.et(440.0), FloatArray(HK.KEYS), null, grand)
        val t415 = bank.design(CombRig.et(415.0), FloatArray(HK.KEYS), null, grand)
        bank.apply(t440, 0); bank.setMode(ResonanceMode.NATURAL, InstrumentId.GRAND, 88, true)
        val c = 60 - 21
        val st = FloatArray(5)
        val rig = Array(1) { 0 }
        val z = FloatArray(HK.BLOCK); val self = FloatArray(88 * HK.BLOCK); val rows = BooleanArray(88)
        val gate = FloatArray(128); val sf = BooleanArray(128); val d = FloatArray(128); val o = FloatArray(HK.BLOCK)
        fun block() { bank.process(z, self, rows, gate, sf, d, o, o, HK.BLOCK); rig[0]++ }
        block()
        bank.loopState(c, st); assertEquals(t440.m[0][c].toFloat(), st[0])
        bank.apply(t415, 200)
        val l0 = t440.loopLength(0, c); val l1 = t415.loopLength(0, c)
        repeat(blockOf(0.1)) { block() }
        bank.loopState(c, st)
        val mid = st[0] + (1 - st[1]) / (1 + st[1])
        assertTrue("mid $mid between $l0 and $l1", mid > l0 + 0.3f * (l1 - l0) && mid < l0 + 0.7f * (l1 - l0))
        repeat(blockOf(0.11) + 1) { block() }
        bank.loopState(c, st)
        assertEquals(t415.m[0][c].toFloat(), st[0]); assertEquals(t415.eta[0][c], st[1], 0f)
    }

    @Test fun sendZeroGivesExactlyZero() {
        val rig = CombRig(mode = ResonanceMode.OFF)
        java.util.Arrays.fill(rig.gate, 1f); java.util.Arrays.fill(rig.damping, 0f); java.util.Arrays.fill(rig.softFeed, true)
        rig.voices += CombRig.Voice(60, DspTestUtil.noise(48000, 0.3), true)
        rig.run(blockOf(1.0), sink = { for (i in 0 until HK.BLOCK) { assertEquals(0f, rig.outL[i]); assertEquals(0f, rig.outR[i]) } })
        assertEquals(0, rig.bank.active)
    }

    @Test fun aKeysOwnRowFeedsNothingButUnaCorda() {
        val rig = CombRig()
        rig.gate[60] = 1f; rig.damping[60] = 0f
        rig.voices += CombRig.Voice(60, DspTestUtil.harmonicTone(48000, 60, 0.5), false)
        rig.run(blockOf(1.0), sink = { for (i in 0 until HK.BLOCK) assertEquals(0f, rig.outL[i]) })
        val rig2 = CombRig()
        rig2.gate[60] = 1f; rig2.damping[60] = 0f; rig2.softFeed[60] = true
        rig2.voices += CombRig.Voice(60, DspTestUtil.harmonicTone(48000, 60, 0.5), true)
        var e = 0.0
        rig2.run(blockOf(1.0), sink = { e += DspTestUtil.rms(rig2.outL) })
        assertTrue(e > 0)
        // Una corda never feeds a single-strung key (21–28).
        val rig3 = CombRig()
        rig3.gate[24] = 1f; rig3.damping[24] = 0f; rig3.softFeed[24] = true
        rig3.voices += CombRig.Voice(24, DspTestUtil.harmonicTone(48000, 24, 0.5), true)
        rig3.run(blockOf(1.0), sink = { for (i in 0 until HK.BLOCK) assertEquals(0f, rig3.outL[i]) })
    }

    @Test fun fourWayKernelEqualsScalarReference() {
        val b = FloatArray(HK.KEYS) { if (it < 60) 2e-4f else 1e-4f }
        val a = CombRig(mode = ResonanceMode.RICH, b = b); val s = CombRig(mode = ResonanceMode.RICH, b = b)
        s.bank.scalarReference = true
        for (r in listOf(a, s)) {
            java.util.Arrays.fill(r.damping, 0f)
            for (k in 21..108) r.gate[k] = if (k % 3 == 0) 0.5f else 1f
            r.voices += CombRig.Voice(40, DspTestUtil.noise(48000, 0.2, 3), false)
            r.voices += CombRig.Voice(64, DspTestUtil.harmonicTone(48000, 64, 0.4), true)
            r.softFeed[64] = true
        }
        val outA = FloatArray(blockOf(1.0) * HK.BLOCK); val outS = FloatArray(outA.size)
        a.run(blockOf(1.0), sink = { bi -> System.arraycopy(a.outL, 0, outA, bi * HK.BLOCK, HK.BLOCK) })
        s.run(blockOf(1.0), sink = { bi -> System.arraycopy(s.outL, 0, outS, bi * HK.BLOCK, HK.BLOCK) })
        assertTrue(a.bank.active > 60)
        var maxd = 0.0
        for (i in outA.indices) maxd = maxOf(maxd, abs(outA[i] - outS[i]).toDouble())
        assertTrue("max diff $maxd", maxd <= 1e-6)
    }

    @Test fun inactiveCombsCostNothing() {
        val rig = CombRig()
        rig.run(10)
        assertEquals(0, rig.bank.active)
        val busy = CombRig(mode = ResonanceMode.RICH)
        java.util.Arrays.fill(busy.gate, 1f); java.util.Arrays.fill(busy.damping, 0f)
        busy.voices += CombRig.Voice(40, DspTestUtil.noise(48000 * 4, 0.2), false)
        fun time(r: CombRig): Long { r.run(100, 0); val t0 = System.nanoTime(); r.run(400, 100); return System.nanoTime() - t0 }
        time(rig); time(busy)
        val idle = time(rig); val full = time(busy)
        assertEquals(88, busy.bank.active)
        println("T3.1 bench: idle ${idle / 400 / HK.BLOCK} ns/frame, 88 combs ${full / 400 / HK.BLOCK} ns/frame")
        assertTrue("idle $idle full $full", idle < full / 5)
    }

    @Test fun maxActiveCapsTheSetHeldKeysFirst() {
        val rig = CombRig(maxActive = 44)
        java.util.Arrays.fill(rig.damping, 0f)
        for (k in 21..108) rig.gate[k] = 1f
        rig.voices += CombRig.Voice(40, DspTestUtil.noise(48000, 0.2), false)
        rig.run(20)
        assertEquals(44, rig.bank.active)
        rig.bank.setMode(ResonanceMode.NATURAL, InstrumentId.GRAND, 0, true)
        rig.run(2, 20)
        assertEquals(0, rig.bank.active)
    }

    @Test fun preparesFromTheSineBankKeyMap() {
        val bank = SineBank(layers = 2)
        val km = KeyMapFixtures.forSineBank(2)
        val rb = ResonanceBank()
        val t = rb.prepare(km, bank.info, grand) as ResonanceTables
        for (c in 0 until 88) assertTrue(t.enabled[c])
        val h = ResonanceBank().prepare(KeyMapFixtures.forSineBank(1, stops = 2), SineBank(1, 2, InstrumentId.HARPSICHORD).info,
            InstrumentProfile.HARPSICHORD) as ResonanceTables
        for (c in 0 until 88) assertEquals("harpsichord comb ${c + 21}", c + 21 in 29..89, h.enabled[c])
    }

    @Test fun sendValuesAreThePlans() {
        assertEquals(-30f, DspTables.sendDb(InstrumentId.GRAND, ResonanceMode.NATURAL))
        assertEquals(-24f, DspTables.sendDb(InstrumentId.GRAND, ResonanceMode.RICH))
        assertEquals(-32f, DspTables.sendDb(InstrumentId.UPRIGHT, ResonanceMode.NATURAL))
        assertEquals(-26f, DspTables.sendDb(InstrumentId.UPRIGHT, ResonanceMode.RICH))
        assertEquals(-34f, DspTables.sendDb(InstrumentId.HARPSICHORD, ResonanceMode.NATURAL))
        assertEquals(-48f, DspTables.UNA_CORDA_SEND_DB)
        assertEquals(0f, DspTables.send(InstrumentId.GRAND, ResonanceMode.OFF))
        val ratio = DspTables.send(InstrumentId.GRAND, ResonanceMode.RICH) / DspTables.send(InstrumentId.GRAND, ResonanceMode.NATURAL)
        assertEquals(6.0, db(ratio.toDouble()), 0.01)
    }
}
