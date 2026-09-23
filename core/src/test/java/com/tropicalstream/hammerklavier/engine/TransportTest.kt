package com.tropicalstream.hammerklavier.engine

import com.tropicalstream.hammerklavier.contract.Cmd
import com.tropicalstream.hammerklavier.contract.CoreClockState
import com.tropicalstream.hammerklavier.contract.DspSet
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.RoomDesign
import com.tropicalstream.hammerklavier.contract.RoomProcessor
import com.tropicalstream.hammerklavier.contract.stub.KeyMapFixtures
import com.tropicalstream.hammerklavier.contract.stub.PassThroughDsp
import com.tropicalstream.hammerklavier.contract.stub.SineBank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** T2.7: transport (PLAN §2.5, §3.15, R5). */
class TransportTest {
    private val key = 57

    private class RecordingRoom : RoomProcessor {
        val gains = ArrayList<Pair<Float, Float>>()
        override fun setDesign(d: RoomDesign, glideMs: Int) {}
        override fun setLines(n: Int) {}
        override fun setInputGain(g: Float, rampMs: Float) { gains.add(g to rampMs) }
        override fun process(inL: FloatArray, inR: FloatArray, outL: FloatArray, outR: FloatArray, n: Int, headYawRad: Float) {
            System.arraycopy(inL, 0, outL, 0, n); System.arraycopy(inR, 0, outR, 0, n)
        }
        override val tailActive: Boolean get() = false
        override fun reset() {}
    }

    @Test fun pauseFadesOver60msFreezesAndResumes() {
        val room = RecordingRoom()
        val dsp = DspSet(resonance = PassThroughDsp.Resonance(), room = room, soft = PassThroughDsp.Soft(), master = IdentityMaster())
        val h = Harness(dsp = dsp)
        h.play(perfSong(listOf(N(1000.0, 5000.0, key, 100))))
        val b = 60_160
        h.renderTo(b)
        val st = CoreClockState()
        h.cmd(Cmd.PAUSE, 60L)
        assertEquals(0f to 60f, room.gains.last())
        h.renderBlocks(30)
        val fade = 2880
        assertTrue(h.maxAbs(b, b + 64) > 0.05f)                          // still sounding at the pause
        assertEquals(0f, h.maxAbs(b + fade + 256, b + 30 * 256), 0f)    // silent after 60 ms (+ the block)
        assertTrue(h.maxAbs(b + 2560, b + 2816) < 0.15f * h.maxAbs(b, b + 256))    // linear: gain ≈ 0.11 → 0.02 in block 10
        h.core.clockState(st)
        assertEquals((b * 1e6 / 48_000).toLong(), st.songUs)             // the clock froze at the pause
        assertFalse(st.playing)
        val v = h.voicesOf(key).single()
        val frozenAt = v.absFrame()
        h.renderBlocks(10)
        assertEquals(frozenAt, v.absFrame())
        h.cmd(Cmd.PLAY)
        assertEquals(1f to 60f, room.gains.last())
        val r = h.frames
        h.renderBlocks(20)
        assertTrue(v.absFrame() > frozenAt)
        assertTrue(h.maxAbs(r, r + 128) < h.maxAbs(r + fade, r + fade + 512))   // fades back in
        assertTrue(h.maxAbs(r + fade, r + fade + 512) > 0.02f)
    }

    @Test fun seekDoesNotRetriggerHeldNotes() {
        val h = Harness()
        h.play(perfSong(listOf(N(1000.0, 5000.0, key, 100))))
        h.renderTo(96_000)
        h.cmd(Cmd.SEEK, 3_000_000L)
        val at = h.frames
        h.renderBlocks(40)
        assertEquals(0f, h.maxAbs(at + 480 + 256, h.frames), 0f)
        assertTrue(h.voicesOf(key).isEmpty())
        assertTrue(h.core.keys.held[key])                                // the key still shows as down
    }

    @Test fun instrumentSwapFadesIn30ms() {
        val h = Harness()
        h.play(perfSong(listOf(N(1000.0, 5000.0, key, 100))))
        h.renderTo(60_160)
        val st = CoreClockState(); h.core.clockState(st); val e0 = st.epoch
        h.setBank(SineBank(layers = 1), KeyMapFixtures.forSineBank(1))
        val at = h.frames
        h.renderBlocks(12)
        assertTrue(h.maxAbs(at, at + 64) > 0.05f)
        assertEquals(0f, h.maxAbs(at + 1440 + 256, h.frames), 0f)
        h.core.clockState(st)
        assertEquals(e0 + 1, st.epoch)
    }

    /** Output frame of the onset at block boundary [b] + [d]. */
    private fun perfWithOnsetAt(frame: Int) = perfSong(listOf(N(usOfFrame(frame.toLong()) / 1000.0, usOfFrame(frame.toLong()) / 1000.0 + 300, key, 100)))

    private val offsets = intArrayOf(1, 2, 50, 95, 96, 97, 128, 200, 254, 255)
    private val b = 30_720

    @Test fun aPauseBeforeAnOnsetDefersItToExactlyTheResumedClock() {
        for (d in offsets) {
            val h = Harness()
            h.play(perfWithOnsetAt(b + d))
            h.renderTo(b)
            h.cmd(Cmd.PAUSE, 60L)
            h.renderBlocks(30)
            assertEquals("d=$d: heard inside the pause", 0f, h.maxAbs(0, h.frames), 0f)
            val r = h.frames
            h.cmd(Cmd.PLAY)
            h.renderBlocks(40)
            assertEquals("d=$d", r + d, h.firstAbove(0f) - 1)
            assertEquals(1, h.voicesOf(key).size)                        // heard once
            val wave = SineBank.waveOf((key - 21) / 3)
            assertEquals(wave[2 * 97] / 32768f, h.left(r + d + 1), 0f)   // at full level: not faded in
        }
    }

    @Test fun aSeekOrANewPerformanceBeforeAnOnsetNeverPlaysIt() {
        for (d in offsets) {
            val s = Harness()
            s.play(perfWithOnsetAt(b + d))
            s.renderTo(b)
            s.cmd(Cmd.SEEK, usOfFrame((b + d).toLong()) + 1_000_000L)
            s.renderBlocks(40)
            assertEquals("seek d=$d", 0f, s.maxAbs(0, s.frames), 0f)

            val p = Harness()
            p.play(perfWithOnsetAt(b + d))
            p.renderTo(b)
            p.play(perfSong(listOf(N(5000.0, 5300.0, 60, 100)), generation = 2), startUs = -1L)
            p.renderBlocks(40)
            assertEquals("perf d=$d", 0f, p.maxAbs(0, p.frames), 0f)
        }
    }

    @Test fun aBankChangeBeforeAnOnsetPlaysItOnlyFromTheNewBank() {
        for (d in offsets) {
            val h = Harness()
            h.play(perfWithOnsetAt(b + d))
            h.renderTo(b)
            val half = KeyMapFixtures.forSineBank(1).let { km -> km.copy(gain = FloatArray(km.gain.size) { 0.5f }) }
            h.setBank(SineBank(layers = 1), half)
            h.renderBlocks(40)
            val wave = SineBank.waveOf((key - 21) / 3)
            assertEquals("d=$d", b + d, h.firstAbove(0f) - 1)
            for (i in 1..200) assertEquals("d=$d i=$i", 0.5f * wave[2 * (96 + i)] / 32768f, h.left(b + d + i), 1e-7f)
        }
    }

    @Test fun stateEventsApplyInTheBlockThatContainsTheirFrame() {
        for (d in intArrayOf(0, 1, 128, 255)) {
            val h = Harness()
            val off = b + d
            val sosAt = b + 512 + d
            val held = ArrayList<Pair<Int, Boolean>>(); val lat = ArrayList<Pair<Int, Boolean>>()
            h.onBlock = { blk -> held.add(blk to h.core.keys.held[key]); lat.add(blk to h.core.keys.latched(40)) }
            h.play(perfSong(listOf(N(500.0, usOfFrame(off.toLong()) / 1000.0, key, 100), N(500.0, 2000.0, 40, 100)),
                sostenutoSong = stepCurve(usOfFrame(sosAt.toLong()) to 1f)))
            h.renderTo(b + 2048)
            for ((blk, v) in held) if (blk >= 24_576) assertEquals("key-up d=$d block $blk", blk + 256 <= off, v)
            for ((blk, v) in lat) assertEquals("latch d=$d block $blk", blk + 256 > sosAt, v)
        }
    }

    @Test fun aNewPerformanceWithMinusOneKeepsTheEnginesPosition() {
        val h = Harness()
        h.play(perfSong(listOf(N(1000.0, 5000.0, key, 100))))
        h.renderTo(96_000)
        val st = CoreClockState(); h.core.clockState(st)
        val before = st.songUs
        h.play(perfSong(listOf(N(1000.0, 5000.0, key, 100)), generation = 2), startUs = -1L)
        val at = h.frames
        h.renderBlocks(1); h.core.clockState(st)
        assertTrue(abs(before + 256 * 1_000_000L / 48_000 - st.songUs) <= 1)
        assertEquals(2, st.generation)
        h.renderBlocks(10)
        assertEquals(0f, h.maxAbs(at + 1440 + 256, h.frames), 0f)       // the old voice faded in 30 ms, the held note is not re-struck
    }

    @Test fun endedGenerationIsSetOnceTheTailsAreDone() {
        val h = Harness()
        h.play(perfSong(listOf(N(1000.0, 1300.0, 60, 100)), generation = 5))
        val st = CoreClockState()
        var endedAt = -1
        h.onBlock = { blk -> h.core.clockState(st); if (endedAt < 0 && st.endedGeneration == 5) endedAt = blk }
        h.renderTo(48_000 * 3)
        assertTrue(endedAt > 62_400)                                       // after the last key-up …
        assertTrue(endedAt < 62_400 + 4 * 48_000)                          // … once the damped tail is below −80 dB
        assertTrue(h.core.pool.allBelow(-80f))
        h.cmd(Cmd.PAUSE, 60L); h.renderBlocks(20); h.core.clockState(st)
        assertTrue(st.idle)
    }
}
