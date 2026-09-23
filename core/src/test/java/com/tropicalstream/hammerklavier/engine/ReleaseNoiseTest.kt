package com.tropicalstream.hammerklavier.engine

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.KeyMap
import com.tropicalstream.hammerklavier.contract.PedalCurve
import com.tropicalstream.hammerklavier.contract.stub.KeyMapFixtures
import com.tropicalstream.hammerklavier.contract.stub.SineBank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

/** T2.5: release samples and pedal noises (PLAN §3.9). */
class ReleaseNoiseTest {
    private class Start(val frame: Long, val kind: Int, val region: Int, val base: Float, val key: Int)

    private fun kit(instrument: InstrumentId = InstrumentId.GRAND, tail: Boolean = false): Pair<TestBank, KeyMap> {
        val waves = listOf(
            TestBank.decayingSine(2000.0, 0.4, 0.2, 0.3),      // 0: release of every key
            TestBank.decayingSine(90.0, 1.0, 0.1, 1.0),        // 1, 2: pedal down
            TestBank.decayingSine(95.0, 1.0, 0.1, 1.0),
            TestBank.decayingSine(120.0, 0.5, 0.1, 0.5),       // 3, 4: pedal up
            TestBank.decayingSine(125.0, 0.5, 0.1, 0.5))
        val bank = TestBank(SineBank(layers = 1, instrument = instrument), waves, IntArray(waves.size) { 96 }, releaseCarriesTail = tail,
            instrument = instrument)
        val km = KeyMapFixtures.forSineBank(1).copy(
            release = IntArray(HK.KEYS) { bank.extraRegion(0) }, releaseGain = FloatArray(HK.KEYS) { 0.5f },
            pedalDown = intArrayOf(bank.extraRegion(1), bank.extraRegion(2)), pedalUp = intArrayOf(bank.extraRegion(3), bank.extraRegion(4)),
            pedalGain = 0.1f)
        return bank to km
    }

    private fun run(notes: List<N>, sustain: PedalCurve = PedalCurve.EMPTY, frames: Int, profile: InstrumentProfile = InstrumentProfile.GRAND,
                    tail: Boolean = false, pedalNoises: Boolean = true): Pair<Harness, List<Start>> {
        val (bank, km) = kit(profile.id, tail)
        val h = Harness(profile = profile, bank = bank, keyMap = km)
        h.mix(pedalNoises = pedalNoises)
        val starts = ArrayList<Start>()
        h.onBlock = { b ->
            for (v in h.core.pool.voices) {
                if ((v.state == Voice.RELEASE_NOISE || v.state == Voice.PEDAL_NOISE) && v.startedOut >= b && v.startedOut < b + 256)
                    starts.add(Start(v.startedOut, v.state, v.region, v.base, v.key))
            }
        }
        h.play(perfSong(notes, profile = profile, sustainSong = sustain))
        h.renderTo(frames)
        return h to starts
    }

    private val lag = Math.round(InstrumentProfile.GRAND.damperLagMs * 48.0)
    private fun ruleGain(vel: Int, ageSec: Double) = 0.5 * Math.pow(vel / 127.0, 0.7) * maxOf(0.25, exp(-ageSec / 3.0))

    @Test fun keyUpWithThePedalUpPlaysTheReleaseAtTheRuleGainWhenTheDamperLands() {
        val (_, starts) = run(listOf(N(1000.0, 1500.0, 60, 100)), frames = 80_000)
        assertEquals(1, starts.size)
        val s = starts[0]
        assertEquals(Voice.RELEASE_NOISE, s.kind)
        assertEquals(72_000 + lag, s.frame)
        assertEquals(ruleGain(100, 0.5), s.base.toDouble(), 2e-3)
        // An old note decays towards the 0.25 floor.
        val (_, old) = run(listOf(N(1000.0, 7000.0, 62, 64)), frames = 7 * 48_000 + 4096)
        assertEquals(ruleGain(64, 6.0), old.single().base.toDouble(), 2e-3)
    }

    @Test fun underThePedalTheGrandPlaysItAtMinus9dB() {
        val (_, starts) = run(listOf(N(1000.0, 1500.0, 60, 100)), sustain = constCurve(1f), frames = 80_000)
        assertEquals(1, starts.size)
        assertEquals(ruleGain(100, 0.5) * 0.35481339, starts[0].base.toDouble(), 2e-3)
        // An undamped treble key (above lastDamper) also plays only the key-return noise.
        val (_, treble) = run(listOf(N(1000.0, 1500.0, 100, 100)), frames = 80_000)
        assertEquals(ruleGain(100, 0.5) * 0.35481339, treble.single().base.toDouble(), 2e-3)
    }

    @Test fun tailCarryingKitsPlayNoReleaseWhenTheDamperDoesNotLand() {
        val (_, starts) = run(listOf(N(1000.0, 1500.0, 60, 100)), sustain = constCurve(1f), frames = 80_000,
            profile = InstrumentProfile.UPRIGHT, tail = true)
        assertTrue(starts.none { it.kind == Voice.RELEASE_NOISE })
    }

    @Test fun aPedalLiftPlaysOnlyThePedalUpNoise() {
        val sus = stepCurve(900_000L to 1f, 2_500_000L to 0f)
        val (_, starts) = run(listOf(N(1000.0, 1500.0, 60, 100), N(1000.0, 1500.0, 64, 100), N(1000.0, 1500.0, 67, 100)), sustain = sus,
            frames = 2_800 * 48)
        val atLift = starts.filter { it.frame >= 2_400 * 48 }
        assertEquals(1, atLift.size)
        assertEquals(Voice.PEDAL_NOISE, atLift[0].kind)
        val n0 = SineBank(layers = 1).regionCount
        assertTrue(atLift[0].region == n0 + 3 || atLift[0].region == n0 + 4)   // from the pedal-up list
    }

    @Test fun pedalNoiseExactlyOncePerLiftCrossing() {
        // Two full cycles with 70 ms ramps: four crossings of 0.33.
        val us = longArrayOf(1_000_000, 1_070_000, 2_000_000, 2_060_000, 3_000_000, 3_070_000, 4_000_000, 4_060_000)
        val v = floatArrayOf(0f, 1f, 1f, 0f, 0f, 1f, 1f, 0f)
        val sus = PedalCurve(us, v)
        val (_, starts) = run(listOf(N(900.0, 4500.0, 60, 80)), sustain = sus, frames = 4_600 * 48)
        val noises = starts.filter { it.kind == Voice.PEDAL_NOISE }
        assertEquals(4, noises.size)
        val n0 = SineBank(layers = 1).regionCount
        // down, up, down, up — round robin within each list
        assertEquals(listOf(n0 + 1, n0 + 3, n0 + 2, n0 + 4), noises.map { it.region })
        for (n in noises) assertTrue(n.base in 0.0349f..0.1001f)
        val (_, off) = run(listOf(N(900.0, 4500.0, 60, 80)), sustain = sus, frames = 4_600 * 48, pedalNoises = false)
        assertTrue(off.none { it.kind == Voice.PEDAL_NOISE })
    }

    @Test fun releasesAndNoisesLiveOutsideTheVoiceCap() {
        // 12 noise slots: 16 releases in quick succession never take a music voice.
        val notes = (0 until 16).map { N(1000.0 + 5 * it, 1100.0 + 5 * it, 40 + it, 100) } + listOf(N(1000.0, 3000.0, 90, 100))
        val (h, starts) = run(notes, frames = 60_000)
        assertEquals(16, starts.count { it.kind == Voice.RELEASE_NOISE })
        assertTrue(h.core.pool.noiseActive <= HK.NOISE_SLOTS)
        assertEquals(1, h.voicesOf(90).size)                   // the held note kept its voice
    }
}
