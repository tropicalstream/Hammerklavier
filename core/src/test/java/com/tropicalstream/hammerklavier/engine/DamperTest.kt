package com.tropicalstream.hammerklavier.engine

import com.tropicalstream.hammerklavier.contract.Cmd
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.PedalCurve
import com.tropicalstream.hammerklavier.contract.PedalMotion
import com.tropicalstream.hammerklavier.contract.QualityLadder
import com.tropicalstream.hammerklavier.contract.stub.KeyMapFixtures
import com.tropicalstream.hammerklavier.contract.stub.SineBank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** T2.3: dampers (PLAN §3.7, §3.8). */
class DamperTest {
    private val grand = InstrumentProfile.GRAND
    private val lag = Math.round(grand.damperLagMs * 48.0).toInt()

    private fun render(notes: List<N>, sustain: PedalCurve = PedalCurve.EMPTY, sostenuto: PedalCurve = PedalCurve.EMPTY,
                       frames: Int, q: Int = 0, rate: Float = 1f, h: Harness = Harness()): Harness {
        h.cmd(Cmd.QUALITY, ref = QualityLadder.of(q, 128))
        if (rate != 1f) h.cmd(Cmd.RATE, f = rate)
        h.play(perfSong(notes, sustainSong = sustain, sostenutoSong = sostenuto))
        h.renderTo(frames)
        return h
    }

    /** dB/s slope of 20·log10(rms(a)/rms(b)) over 10-block windows in [from, to). */
    private fun ratioSlope(a: Harness, b: Harness, from: Int, to: Int): Double {
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        var f = from
        while (f + 2560 <= to) { xs.add((f + 1280) / 48_000.0); ys.add(db(a.rms(f, f + 2560)) - db(b.rms(f, f + 2560))); f += 2560 }
        val mx = xs.average(); val my = ys.average()
        var sxy = 0.0; var sxx = 0.0
        for (i in xs.indices) { sxy += (xs[i] - mx) * (ys[i] - my); sxx += (xs[i] - mx) * (xs[i] - mx) }
        return sxy / sxx
    }

    @Test fun halfPedalGivesT60dOverD() {
        val p = 0.44f
        val d = PedalMotion.pedalDamping(p)
        assertEquals(0.5f, d, 1e-6f)
        for (key in intArrayOf(40, 60)) {
            val t60d = grand.defaultDamperT60(key)
            val damped = render(listOf(N(1000.0, 1300.0, key, 100)), sustain = constCurve(p), frames = 110_000, q = 2)
            val held = render(listOf(N(1000.0, 3000.0, key, 100)), sustain = constCurve(p), frames = 110_000, q = 2)
            val land = 62_400 + lag
            val slope = -ratioSlope(damped, held, land + 2560, land + 2560 + 12_800)
            val tEff = 60.0 / slope
            val expected = t60d / d
            assertTrue("key $key: T60 $tEff vs ${expected}", abs(tEff - expected) / expected < 0.02)
        }
    }

    @Test fun keysAboveLastDamperNeverDamp() {
        val key = 100
        val up = render(listOf(N(1000.0, 1200.0, key, 100)), frames = 90_000)
        val held = render(listOf(N(1000.0, 3000.0, key, 100)), frames = 90_000)
        for (i in 0 until 90_000) assertEquals(held.left(i), up.left(i), 0f)
        assertEquals(0f, up.core.keys.damping[key], 0f)
    }

    @Test fun latchedKeysAreNotDamped() {
        val key = 48
        val sos = stepCurve(1_200_000L to 1f)                          // latched at 1.2 s while the key is down
        val up = render(listOf(N(1000.0, 1600.0, key, 100)), sostenuto = sos, frames = 100_000)
        val held = render(listOf(N(1000.0, 3000.0, key, 100)), sostenuto = sos, frames = 100_000)
        assertTrue(up.core.keys.latched(key))
        assertEquals(0f, up.core.keys.damping[key], 0f)
        for (i in 0 until 100_000) assertEquals(held.left(i), up.left(i), 0f)
        // An unlatched key released at the same time is damped.
        val other = render(listOf(N(1300.0, 1600.0, 52, 100)), sostenuto = sos, frames = 100_000)
        assertFalse(other.core.keys.latched(52))
        assertEquals(1f, other.core.keys.damping[52], 0f)
    }

    @Test fun rePedallingFreezesTheLevelAndTheLowPassNeverReopens() {
        val key = 60
        val sus = stepCurve(1_500_000L to 1f)                          // the pedal goes down 200 ms after the key-up
        val h = Harness()
        val damps = ArrayList<Pair<Int, Float>>(); val lps = ArrayList<Float>()
        h.onBlock = { b -> h.voicesOf(key).firstOrNull()?.let { damps.add(b to it.damp); if (it.spectral) lps.add(it.lpIdx) } }
        render(listOf(N(1000.0, 1300.0, key, 100)), sustain = sus, frames = 96_000, h = h)
        val after = damps.filter { it.first > 72_000 + 512 }
        assertTrue(after.size > 50)
        val frozen = after.first().second
        assertTrue(frozen < 0.5f)                                     // it did damp for 180 ms
        for ((_, dmp) in after) assertEquals(frozen, dmp, 0f)
        assertTrue(lps.isNotEmpty())
        for (i in 1 until lps.size) assertTrue("lp reopened", lps[i] <= lps[i - 1])
        assertEquals(lps.last(), lps[lps.size / 2 + lps.size / 4], 0f)  // stays where it is once D = 0
    }

    @Test fun dampingOnsetFallsTheDamperLagAfterTheKeyUpAtEveryTempo() {
        for (r in floatArrayOf(0.5f, 1f, 1.5f)) for (q in intArrayOf(0, 2)) {
            val key = 60
            val offUs = 1_300_000L
            val up = render(listOf(N(1000.0, offUs / 1000.0, key, 100)), frames = 130_000, q = q, rate = r)
            val held = render(listOf(N(1000.0, 4000.0, key, 100)), frames = 130_000, q = q, rate = r)
            val rf = Math.round(r.toDouble() * 4294967296.0)
            val keyUp = Math.round(offUs * 0.048 * 4294967296.0 / rf)
            var first = -1
            for (i in 0 until 130_000) if (up.left(i) != held.left(i)) { first = i; break }
            assertTrue("r $r q $q: first difference $first vs landing ${keyUp + lag}", abs(first - (keyUp + lag)) <= 1)
        }
    }

    @Test fun tailCarryingKitsHandOffLevelMatchedAtFullDampingOnly() {
        val upright = InstrumentProfile.UPRIGHT
        val key = 60
        val rel = TestBank.decayingSine(523.25, 1.5, 0.3, 3.0, onset = 0)   // slow tail: isolates the handoff from the decay
        val bank = TestBank(SineBank(layers = 1, instrument = InstrumentId.UPRIGHT), listOf(rel), intArrayOf(0), releaseCarriesTail = true,
            instrument = InstrumentId.UPRIGHT)
        val base = KeyMapFixtures.forSineBank(1)
        val release = IntArray(HK.KEYS) { -1 }.also { it[key] = bank.extraRegion(0) }
        val km = base.copy(release = release, releaseGain = FloatArray(HK.KEYS) { 1f })
        val lagU = Math.round(upright.damperLagMs * 48.0).toInt()

        // Pedal up: the release takes over, and the summed envelope never steps by more than 1 dB.
        val h = Harness(profile = upright, bank = bank, keyMap = km)
        h.play(perfSong(listOf(N(1000.0, 1500.0, key, 100)), profile = upright))
        h.renderTo(120_000)
        val land = 72_000 + lagU
        val noises = h.liveVoices().count { it.state == Voice.RELEASE_NOISE }
        assertEquals(1, noises)
        // 20 ms windows hopping by 10 ms (a 10 ms window holds 2.6 periods of C4 and wobbles by itself)
        var prev = db(h.rms(land - 4800, land - 3840))
        var maxStep = 0.0
        var f = land - 4320
        while (f < land + 9600) { val e = db(h.rms(f, f + 960)); maxStep = maxOf(maxStep, abs(e - prev)); prev = e; f += 480 }
        assertTrue("envelope step $maxStep dB", maxStep <= 1.0)
        assertTrue(h.voicesOf(key).isEmpty())                        // the sustain voice has handed off

        // Half pedal at the landing: no release, the T60d multiplier damps instead.
        val half = Harness(profile = upright, bank = bank, keyMap = km)
        half.play(perfSong(listOf(N(1000.0, 1500.0, key, 100)), profile = upright, sustainSong = constCurve(0.44f)))
        half.renderTo(80_000)
        assertEquals(0, half.liveVoices().count { it.state == Voice.RELEASE_NOISE })
        assertTrue(half.voicesOf(key).isNotEmpty())
        assertTrue(half.voicesOf(key).first().damp < 0.9f)
    }
}
