package com.tropicalstream.hammerklavier.engine

import com.tropicalstream.hammerklavier.contract.AudioStats
import com.tropicalstream.hammerklavier.contract.Cmd
import com.tropicalstream.hammerklavier.contract.CommandRing
import com.tropicalstream.hammerklavier.contract.CoreClockState
import com.tropicalstream.hammerklavier.contract.EnergyRing
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.HeadPose
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.QualityLadder
import com.tropicalstream.hammerklavier.contract.SyntheticScore
import com.tropicalstream.hammerklavier.contract.VoiceCursorBoard
import com.tropicalstream.hammerklavier.contract.stub.KeyMapFixtures
import com.tropicalstream.hammerklavier.contract.stub.PassThroughDsp
import com.tropicalstream.hammerklavier.contract.stub.SineBank
import com.tropicalstream.hammerklavier.contract.stub.StubScoreCompiler
import com.tropicalstream.hammerklavier.testutil.AllocProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2.10: 0 bytes allocated on the render thread over 10 s of CHORD_STORM_64 after warm-up,
 * including the command drain (the handler is EngineCoreApi itself) and the per-block publishing
 * (clock state, energy ring, stats). Tests run with escape analysis off (core/build.gradle.kts).
 */
class AllocationTest {
    private fun run(cap: Int, profileQ: Int, pedalStorm: Boolean) {
        val dsp = PassThroughDsp.create()
        val core = EngineCore(dsp, VoiceCursorBoard(), HeadPose())
        val ring = CommandRing()
        val energy = EnergyRing()
        val bank = SineBank(layers = 2)
        val profile = InstrumentProfile.GRAND
        val bankTok = core.prepareBank(bank, KeyMapFixtures.forSineBank(2, KeyMapFixtures.Mode.XFADE), profile)
        val kmTok = core.prepareKeyMap(KeyMapFixtures.forSineBank(2, KeyMapFixtures.Mode.XFADE), bank.info, profile)
        val q = QualityLadder.of(profileQ, cap)
        val perf = StubScoreCompiler().synthetic(if (pedalStorm) SyntheticScore.CHORD_STORM_64 else SyntheticScore.PEDAL_HALF, profile, 1)
        ring.offer(Cmd.SET_BANK, ref = bankTok); ring.offer(Cmd.QUALITY, ref = q)
        ring.offer(Cmd.SET_PERF, 0L, 1f, perf)
        val out = FloatArray(2 * HK.BLOCK)
        val lanes = FloatArray(HK.LANES)
        val st = CoreClockState(); val stats = AudioStats()
        var frame = 0L
        val blocksPerSec = HK.SR / HK.BLOCK
        fun block(i: Int) {
            // Main-thread traffic that crosses the ring every block (preallocated refs only).
            when (i % 200) {
                17 -> ring.offer(Cmd.DUCK, 0L, 0.9f)
                37 -> ring.offer(Cmd.DUCK, 0L, 1f)
                57 -> ring.offer(Cmd.SET_KEYMAP, ref = kmTok)
                77 -> ring.offer(Cmd.REGISTRATION, 3L)
                97 -> ring.offer(Cmd.QUALITY, ref = q)
                else -> {}
            }
            ring.drain(16, core)
            core.render(out, frame)
            core.clockState(st)
            val ep = core.energy(lanes)
            energy.write(frame, ep, lanes)
            if (i % 16 == 0) core.stats(stats)
            frame += HK.BLOCK
        }
        var i = 0
        repeat(5 * blocksPerSec) { block(i++) }                       // warm-up: 5 s (the stub bank builds its waves lazily)
        // HotSpot can charge a few bytes once to the thread when a hot method is recompiled (tier change or
        // deoptimisation); an allocation in the engine would recur every block. So a window with a one-off
        // is measured again: two windows of 10 s must not both allocate, and none may allocate per block.
        val first = AllocProbe.measure { repeat(10 * blocksPerSec) { block(i++) } }
        val bytes = if (first == 0L) 0L else AllocProbe.measure { repeat(10 * blocksPerSec) { block(i++) } }
        assertTrue("cap $cap Q$profileQ: $first bytes in 10 s (a per-block allocation?)", first < 4096)
        assertEquals("cap $cap Q$profileQ: bytes allocated on the render thread", 0L, bytes)
        core.stats(stats)
        assertEquals(0, stats.dropped)
    }

    @Test fun stormAllocatesNothingAtCap64() = run(64, 0, true)
    @Test fun stormAllocatesNothingAtCap128() = run(128, 0, true)
    @Test fun linearQualityAllocatesNothing() = run(64, 2, true)
    @Test fun pedalHalfAllocatesNothing() = run(96, 0, false)

    @Test fun transportCommandsAllocateNothing() {
        val core = EngineCore(PassThroughDsp.create(), VoiceCursorBoard(), HeadPose())
        val profile = InstrumentProfile.GRAND
        val bank = SineBank(layers = 1)
        core.on(Cmd.SET_BANK, 0L, 0f, core.prepareBank(bank, KeyMapFixtures.forSineBank(1), profile))
        val perf = StubScoreCompiler().synthetic(SyntheticScore.SCALE, profile, 1)
        core.on(Cmd.SET_PERF, 0L, 1f, perf)
        val out = FloatArray(2 * HK.BLOCK)
        var f = 0L
        repeat(400) { core.render(out, f); f += 256 }
        AllocProbe.assertNoAllocation("transport") {
            core.on(Cmd.PAUSE, 60L, 0f, null); repeat(20) { core.render(out, f); f += 256 }
            core.on(Cmd.PLAY, 0L, 0f, null); repeat(20) { core.render(out, f); f += 256 }
            core.on(Cmd.SEEK, 2_000_000L, 0f, null); repeat(20) { core.render(out, f); f += 256 }
            core.on(Cmd.RATE, 0L, 1.2f, null); repeat(20) { core.render(out, f); f += 256 }
            core.on(Cmd.RATE, 0L, 1f, null); repeat(20) { core.render(out, f); f += 256 }
            core.on(Cmd.SET_PERF, -1L, 1f, perf); repeat(20) { core.render(out, f); f += 256 }
        }
    }
}
