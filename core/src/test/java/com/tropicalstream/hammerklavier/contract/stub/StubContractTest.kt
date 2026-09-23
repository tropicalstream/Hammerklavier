package com.tropicalstream.hammerklavier.contract.stub

import com.tropicalstream.hammerklavier.contract.Cmd
import com.tropicalstream.hammerklavier.contract.CompileResult
import com.tropicalstream.hammerklavier.contract.CoreClockState
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.PedalCurve
import com.tropicalstream.hammerklavier.contract.Performance
import com.tropicalstream.hammerklavier.contract.RejectReason
import com.tropicalstream.hammerklavier.contract.ScoreSpec
import com.tropicalstream.hammerklavier.contract.SyntheticScore
import com.tropicalstream.hammerklavier.contract.SyntheticSpecs
import com.tropicalstream.hammerklavier.testutil.AllocProbe
import com.tropicalstream.hammerklavier.testutil.PerformanceValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class StubContractTest {
    private val compiler = StubScoreCompiler()

    @Test fun everySyntheticPerformanceIsValidOnEveryInstrument() {
        for (id in InstrumentId.entries) for (kind in SyntheticScore.entries) {
            val profile = InstrumentProfile.of(id)
            val p = compiler.synthetic(kind, profile, 7)
            PerformanceValidator.assertValid(p, profile)
            assertEquals(7, p.generation); assertEquals(id, p.instrument)
        }
    }

    @Test fun syntheticSpecsMatchTheTable() {
        val sync = SyntheticSpecs.notes(SyntheticScore.SYNC_CLICK)
        assertEquals(100, sync.onUs.size)
        for (i in 0 until 100) {
            val off = sync.onUs[i] / 1000 - 600L * i
            assertTrue(off in 0..33); assertEquals(69, sync.key[i].toInt()); assertEquals(118, sync.vel[i].toInt())
        }
        assertTrue((0 until 100).map { SyntheticSpecs.clickOffsetMs(it) }.toSet().size > 20)
        val scale = SyntheticSpecs.notes(SyntheticScore.SCALE)
        assertEquals(88, scale.onUs.size); assertEquals(20, scale.vel[0].toInt()); assertEquals(127, scale.vel[87].toInt())
        val storm = SyntheticSpecs.notes(SyntheticScore.CHORD_STORM_64)
        assertEquals(720 * 64, storm.onUs.size); assertFalse(storm.cc64.isEmpty)
        for (c in listOf(0, 1, 719)) {
            val keys = (0 until storm.onUs.size).filter { storm.onUs[it] == 250_000L * c }.map { storm.key[it].toInt() }
            assertEquals(64, keys.toSet().size); assertTrue(keys.all { it in 21..108 })
        }
        assertEquals(109, SyntheticSpecs.notes(SyntheticScore.FOLD).onUs.size)
        assertEquals(118, SyntheticSpecs.notes(SyntheticScore.CRESCENDO_C4).onUs.size)
        assertEquals(90, SyntheticSpecs.notes(SyntheticScore.REPEAT_15).onUs.size)
        for (kind in SyntheticScore.entries) {
            val s = SyntheticSpecs.notes(kind)
            for (i in s.onUs.indices) { assertEquals(0L, s.onUs[i] % 1000); assertEquals(0L, s.offUs[i] % 1000) }
            assertEquals(kind, SyntheticSpecs.kindOf("synth:" + SyntheticSpecs.NAMES.getValue(kind)))
            assertEquals(kind, SyntheticSpecs.kindOf("test:" + SyntheticSpecs.NAMES.getValue(kind)))
        }
        val half = SyntheticSpecs.notes(SyntheticScore.PEDAL_HALF).cc64
        assertTrue(half.v.filter { it > 0f && it < 1f }.toSet().size >= 8)
    }

    @Test fun fixturesFoldLatchAndPedalNoise() {
        val fold = compiler.synthetic(SyntheticScore.FOLD, InstrumentProfile.HARPSICHORD, 1)
        assertTrue(fold.info.folded > 0)
        assertTrue((0 until fold.noteCount).any { fold.flags[it].toInt() and Performance.F_FOLDED != 0 })
        val sos = compiler.synthetic(SyntheticScore.SOSTENUTO, InstrumentProfile.GRAND, 1)
        assertEquals(4, sos.latchUs.size)                                    // two presses, two releases
        assertEquals(1L shl 36, sos.latchLo[0]); assertEquals(0L, sos.latchHi[0])   // only the held C2
        assertEquals(0L, sos.latchLo[1])
        assertEquals(1L shl 43, sos.latchLo[2])                              // at sustain 0.4 only the held G2
        val upright = compiler.synthetic(SyntheticScore.SOSTENUTO, InstrumentProfile.UPRIGHT, 1)
        assertEquals(0, upright.latchUs.size)                                // the upright has no sostenuto
        val storm = compiler.synthetic(SyntheticScore.CHORD_STORM_64, InstrumentProfile.GRAND, 1)
        val noises = storm.ev.filter { Performance.type(it) == Performance.EV_PEDAL_NOISE }
        assertEquals(2, noises.size)
        assertEquals(1, Performance.arg(noises[0]) and 1); assertEquals(0, Performance.arg(noises[1]) and 1)
        val harpsi = compiler.synthetic(SyntheticScore.CHORD_STORM_64, InstrumentProfile.HARPSICHORD, 1)
        assertTrue(harpsi.sustain.isEmpty); assertTrue(harpsi.info.fingerPedalled)
    }

    @Test fun compilerRecognisesOnlyTwins() {
        val r = compiler.compile(byteArrayOf(1, 2, 3), "x", 1, InstrumentProfile.GRAND)
        assertTrue(r is CompileResult.Failed && r.reason == RejectReason.NOT_MIDI)
        assertTrue(compiler.compile(ByteArray(0), "test:scale", 1, InstrumentProfile.GRAND) is CompileResult.Ok)
        val twin = "MThd-twin".toByteArray()
        compiler.registerTwin(twin, SyntheticScore.UNA_CORDA)
        val ok = compiler.compile(twin.copyOf(), "whatever", 2, InstrumentProfile.GRAND) as CompileResult.Ok
        assertEquals(2, ok.perf.noteCount)
        assertTrue(compiler.inspect(twin).ok)
        assertTrue(compiler.sniff("MThd....".toByteArray())); assertFalse(compiler.sniff("RIFF".toByteArray()))
        assertTrue(compiler.sniff("RIFF\u0000\u0000\u0000\u0000RMID".toByteArray()))
    }

    private fun oneNote(onFileUs: Long, key: Int, vel: Int, lenUs: Long = 300_000L): Performance =
        PerfFixtures.build(ScoreSpec(longArrayOf(onFileUs), longArrayOf(onFileUs + lenUs), byteArrayOf(key.toByte()), byteArrayOf(vel.toByte()),
            PedalCurve.EMPTY, PedalCurve.EMPTY, PedalCurve.EMPTY), profile = InstrumentProfile.GRAND, generation = 1)

    private fun renderUntilOnset(core: SineCore, maxBlocks: Int): Long {
        val out = FloatArray(2 * HK.BLOCK)
        for (b in 0 until maxBlocks) {
            core.render(out, b * 256L)
            for (i in 0 until HK.BLOCK) if (out[2 * i] != 0f) return b * 256L + i
        }
        return -1
    }

    @Test fun sineCoreOnsetIsExact() {
        for (rate in floatArrayOf(1f, 0.5f)) {
            val core = SineCore()
            val p = oneNote(1_000_000L - HK.PRE_ROLL_US, 60, 100)            // onUs = 1,000,000 song µs
            assertEquals(1_000_000L, p.onUs[0])
            core.on(Cmd.RATE, 0L, rate, null)
            core.on(Cmd.SET_PERF, 0L, 1f, p)
            assertEquals(if (rate == 1f) 48_000L else 96_000L, renderUntilOnset(core, 1000))
        }
        // An odd offset inside a block and a start position.
        val core = SineCore()
        val p = oneNote(123_457L, 64, 90)
        core.on(Cmd.SET_PERF, 100_000L, 1f, p)
        val expect = Math.round((p.onUs[0] - 100_000L) * 0.048)
        assertEquals(expect, renderUntilOnset(core, 1000))
    }

    @Test fun sineCorePauseHoldsTheOnset() {
        val core = SineCore()
        val p = oneNote(1_000_000L - HK.PRE_ROLL_US, 60, 100)
        core.on(Cmd.SET_PERF, 0L, 1f, p)
        val out = FloatArray(2 * HK.BLOCK)
        for (b in 0 until 180) core.render(out, b * 256L)                    // up to frame 46,080
        core.on(Cmd.PAUSE, 60L, 0f, null)
        for (b in 180 until 400) { core.render(out, b * 256L); assertTrue(out.all { it == 0f }) }
        core.on(Cmd.PLAY, 0L, 0f, null)
        // resumes at song frame 46,080; the onset (song frame 48,000) is 1,920 frames later.
        assertEquals(1920L, renderUntilOnset(core, 1000))                     // frames counted from the resume
    }

    @Test fun sineCoreLevelsEnergyAndAllocation() {
        val core = SineCore()
        val p = oneNote(0L, 60, 127, lenUs = 5_000_000L)
        core.on(Cmd.SET_PERF, 0L, 1f, p)
        val out = FloatArray(2 * HK.BLOCK)
        val lanes = FloatArray(HK.LANES)
        val st = CoreClockState()
        for (b in 0 until 200) core.render(out, b * 256L)                    // past the pre-roll and onset
        var sq = 0.0; var n = 0
        AllocProbe.assertNoAllocation("SineCore.render") {
            for (b in 200 until 400) {
                core.render(out, b * 256L); core.energy(lanes); core.clockState(st)
                for (i in 0 until HK.BLOCK) { sq += out[2 * i] * out[2 * i]; n++ }
            }
        }
        val rms = sqrt(sq / n).toFloat()
        assertEquals(0.2512f, rms, 0.01f)                                   // −12 dBFS RMS at v127
        assertEquals(0.25f, lanes[60 - 21], 0.025f)                          // lane 39 (T2.11's anchor)
        assertTrue(st.playing); assertEquals(1, st.generation)
        assertTrue(abs(st.songUs - (599 * 256L * 1_000_000 / 48_000)) < 30)   // 200 + 2 × 200 blocks (the probe runs the block twice)
    }

    @Test fun sineCoreEndsOnce() {
        val core = SineCore()
        val p = oneNote(0L, 72, 80, lenUs = 50_000L)
        core.on(Cmd.SET_PERF, 0L, 1f, p)
        val out = FloatArray(2 * HK.BLOCK)
        val st = CoreClockState()
        var endedAt = -1
        for (b in 0 until 200) { core.render(out, b * 256L); core.clockState(st); if (st.endedGeneration == 1 && endedAt < 0) endedAt = b }
        assertTrue("ended at block $endedAt", endedAt in 84..88)             // key-up at 450 ms (frame 21,600) + the 10 ms release
    }

    @Test fun sineBankAndKeyMap() {
        val bank = SineBank(layers = 3, stops = 2, instrument = InstrumentId.HARPSICHORD)
        assertEquals(180, bank.regionCount)
        val region = SineBank.regionOf(1, 2, 13, 3)
        assertEquals(13, bank.rootOf(region)); assertEquals(2, bank.layerOf(region)); assertEquals(1, bank.stopOf(region))
        assertEquals(96, bank.onsetFrame(0)); assertEquals(96_000, bank.frames(0))
        val thr = bank.thrFrame(0)
        assertTrue(thr in 96..140)
        assertTrue(bank.envByte(0, 1) < 30)                                  // loud just after the onset
        assertTrue(bank.envByte(0, 150) > bank.envByte(0, 10) + 20)          // decays (1.2 s time constant)
        assertEquals(255, bank.envByte(0, 1000))
        val rd = bank.newReader()
        val buf = ShortArray(2 * 512)
        assertEquals(256, rd.read(0, -256, 512, buf, 0))
        assertTrue(buf.copyOfRange(0, 2 * 256 + 2 * 96).all { it.toInt() == 0 })
        var peak = 0
        val all = ShortArray(2 * 96_000); rd.read(5, 0, 96_000, all, 0)
        for (s in all) peak = maxOf(peak, abs(s.toInt()))
        assertEquals(0.708f * 32767, peak.toFloat(), 40f)                    // −3 dBFS
        val km = KeyMapFixtures.forSineBank(layers = 3, mode = KeyMapFixtures.Mode.XFADE, stops = 2)
        for (sk in km.region.indices) { val r = km.region[sk]; assertTrue(r in 0 until bank.regionCount) }
        assertEquals(1f, km.rate[69], 1e-6f)                                  // key 69 is a root (21 + 3·16)
        for (v in 1..127) { val g = km.velGainA[v] + km.velGainB[v]; assertEquals(1f, g, 1e-5f) }
        val partial = KeyMapFixtures.forSineBank(layers = 2, stops = 1, readyMask = 0b01L)
        for (v in 1..127) assertEquals(0, partial.velLayerA[v].toInt())
        assertTrue((128 until 256).all { partial.region[it] == -1 })
        val kits = StubKits()
        assertNotNull(kits.info(InstrumentId.GRAND))
        assertTrue(kits.info(InstrumentId.GRAND).isStub)
    }

    @Test fun nullAudioEndsOnce() {
        var now = 0L
        val fake = FakeClock { now }
        val audio = NullAudio(fake)
        var ended = 0
        audio.setListener(object : com.tropicalstream.hammerklavier.contract.AudioListener {
            override fun onEnded(generation: Int) { ended++ }
            override fun onOverload(newCap: Int) {}
            override fun onEngineError(code: com.tropicalstream.hammerklavier.contract.StatusCode, detail: String) {}
        })
        val p = oneNote(0L, 60, 80)
        audio.setPerformance(p, 0L, autoPlay = true)
        val stats = com.tropicalstream.hammerklavier.contract.AudioStats()
        now = 1_000_000_000L; audio.stats(stats); assertEquals(0, ended)
        now = 10_000_000_000L; audio.stats(stats); audio.stats(stats); assertEquals(1, ended)
        val s = com.tropicalstream.hammerklavier.contract.ClockSample()
        audio.clock.sample(now, s); assertEquals(p.durationUs, s.songUs); assertFalse(s.playing)
    }
}
