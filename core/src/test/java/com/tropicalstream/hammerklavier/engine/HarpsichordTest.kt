package com.tropicalstream.hammerklavier.engine

import com.tropicalstream.hammerklavier.contract.Cmd
import com.tropicalstream.hammerklavier.contract.CoreClockState
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.HarpsiTiming
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.KeyMap
import com.tropicalstream.hammerklavier.contract.stub.KeyMapFixtures
import com.tropicalstream.hammerklavier.contract.stub.SineBank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/** T2.6: harpsichord stops, stagger and registration (PLAN §3.10). */
class HarpsichordTest {
    private val hp = InstrumentProfile.HARPSICHORD
    private val key = 57                                  // 8′ root 57, 4′ sounds 69 (a root too)

    private fun harness(km: KeyMap = KeyMapFixtures.forSineBank(1, stops = 2)) =
        Harness(profile = hp, bank = SineBank(layers = 1, stops = 2, instrument = InstrumentId.HARPSICHORD), keyMap = km)

    /** Sub-frame output time of the sampled onset of a single voice of root [rootIdx] at [rate] (30 % crossing on the rise). */
    private fun onsetOf(h: Harness, rootIdx: Int, rate: Float, from: Int): Double {
        val wave = SineBank.waveOf(rootIdx)
        var peakS = 0; for (i in 0 until wave.size / 2) peakS = maxOf(peakS, abs(wave[2 * i].toInt()))
        val lvl = 0.3 * peakS
        var t = 1; while (abs(wave[2 * t].toInt()) < lvl) t++
        val a0 = abs(wave[2 * (t - 1)].toInt()).toDouble(); val a1 = abs(wave[2 * t].toInt()).toDouble()
        val srcCross = t - 1 + (lvl - a0) / (a1 - a0)
        val lo = lvl / 32768.0
        val det = h.firstAbove(lo.toFloat(), from)
        val b0 = abs(h.left(det - 1)).toDouble(); val b1 = abs(h.left(det)).toDouble()
        return det - 1 + (lo - b0) / (b1 - b0) - (srcCross - SineBank.ONSET) / rate
    }

    private fun onsetFor(mask: Int, vel: Int, tempo: Float, rate: Float): Double {
        var km = KeyMapFixtures.forSineBank(1, stops = 2)
        if (rate != 1f) { km = km.withRate(key, rate) }
        val h = harness(km)
        h.cmd(Cmd.REGISTRATION, mask.toLong())
        if (tempo != 1f) h.cmd(Cmd.RATE, f = tempo)
        h.play(perfSong(listOf(N(1000.0, 1300.0, key, vel)), profile = hp))
        val ev = Math.round(1_000_000 * 0.048 / tempo).toInt()
        h.renderTo(ev + 4096)
        val root = if (mask == HK.REG_8) (key - 21) / 3 else (key + 12 - 21) / 3
        return onsetOf(h, root, rate, ev - 2000)
    }

    @Test fun theFourFootPrecedesTheEightFootByTheStagger() {
        for (tempo in floatArrayOf(0.5f, 1f, 1.5f)) for (rate in floatArrayOf(1f, 2.0.pow(-2 / 12.0).toFloat(), 2.0.pow(2 / 12.0).toFloat()))
            for (vel in intArrayOf(1, 64, 127)) {
                val o8 = onsetFor(HK.REG_8, vel, tempo, rate)
                val o4 = onsetFor(HK.REG_4, vel, tempo, rate)
                val stagger = Math.round(HarpsiTiming.staggerMs(vel) * 48.0)
                val ev = Math.round(1_000_000 * 0.048 / tempo)
                assertTrue("tempo $tempo rate $rate v$vel: 8′ at $o8 vs $ev", abs(o8 - ev) <= 1.0)
                assertTrue("tempo $tempo rate $rate v$vel: lead ${o8 - o4} vs $stagger", abs((o8 - o4) - stagger) <= 1.0)
            }
    }

    @Test fun aRegistrationChangeAffectsOnlyNewNotesAndIsPublished() {
        val h = harness()
        h.play(perfSong(listOf(N(1000.0, 3000.0, key, 100), N(1500.0, 3000.0, 60, 100)), profile = hp))
        h.renderTo(60_000)
        assertEquals(setOf(0, 1), h.voicesOf(key).map { it.stop }.toSet())
        h.cmd(Cmd.REGISTRATION, HK.REG_8.toLong())
        h.renderBlocks(1)
        val st = CoreClockState(); h.core.clockState(st)
        assertEquals(HK.REG_8, st.registration)
        h.renderTo(1600 * 48)
        assertEquals(setOf(0, 1), h.voicesOf(key).map { it.stop }.toSet())   // the sounding note keeps its 4′
        assertEquals(setOf(0), h.voicesOf(60).map { it.stop }.toSet())
    }

    @Test fun levelIsConstantAcrossVelocityWithinOneDb() {
        // One performance, so every note has its own index (the humanising hashes the note index).
        val vels = (20..127 step 9).toList()
        val h = harness()
        h.cmd(Cmd.REGISTRATION, HK.REG_8.toLong())
        h.play(perfSong(vels.mapIndexed { i, v -> N(1000.0 + 700.0 * i, 1300.0 + 700.0 * i, key, v) }, profile = hp))
        h.renderTo(48 * (1000 + 700 * vels.size))
        val levels = vels.indices.map { i -> val on = 48 * (1000 + 700 * i); db(h.rms(on + 480, on + 480 + 4800)) }
        val mean = levels.average()
        for (l in levels) assertTrue("level $l vs mean $mean", abs(l - mean) <= 1.0)
        assertTrue(levels.max() - levels.min() > 0.05)                  // the ±1 dB humanising is there
    }

    @Test fun jackFallHandoffsAtTheQuillPassesAndTheGateFollowsTheKey() {
        val rel8 = TestBank.decayingSine(700.0, 0.6, 0.2, 0.5, onset = 0)
        val rel4 = TestBank.decayingSine(1400.0, 0.6, 0.2, 0.5, onset = 0)
        val bank = TestBank(SineBank(layers = 1, stops = 2, instrument = InstrumentId.HARPSICHORD), listOf(rel8, rel4), intArrayOf(0, 0),
            releaseCarriesTail = true, instrument = InstrumentId.HARPSICHORD)
        val release = IntArray(2 * HK.KEYS) { -1 }.also { it[key] = bank.extraRegion(0); it[HK.KEYS + key] = bank.extraRegion(1) }
        val km = KeyMapFixtures.forSineBank(1, stops = 2).copy(release = release, releaseRate = FloatArray(2 * HK.KEYS) { 1f },
            releaseGain = FloatArray(2 * HK.KEYS) { 1f })
        val h = Harness(profile = hp, bank = bank, keyMap = km)
        val starts = ArrayList<Pair<Long, Int>>()
        val gates = ArrayList<Pair<Int, Float>>()
        h.onBlock = { b ->
            for (v in h.core.pool.voices) if (v.state == Voice.RELEASE_NOISE && v.startedOut >= b && v.startedOut < b + 256) starts.add(v.startedOut to v.region)
            gates.add(b to h.core.keys.gate[key])
        }
        h.play(perfSong(listOf(N(1000.0, 1500.0, key, 100)), profile = hp))
        h.renderTo(80_000)
        val up = 72_000L
        assertEquals(listOf(up + Math.round(HarpsiTiming.quill8PassMs * 48.0) to bank.extraRegion(0),
            up + Math.round(HarpsiTiming.quill4PassMs * 48.0) to bank.extraRegion(1)), starts)
        assertEquals(1f, gates.first { it.first == 71_680 }.second, 0f)    // held
        assertEquals(0f, gates.first { it.first == 71_936 }.second, 0f)    // released in the key-up's block
        assertTrue(h.voicesOf(key).isEmpty())                               // both stops handed off
        assertEquals(1f, h.core.keys.damping[key], 0f)                      // the cloth has landed
    }
}
