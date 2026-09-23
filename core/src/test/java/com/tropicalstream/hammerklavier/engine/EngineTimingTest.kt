package com.tropicalstream.hammerklavier.engine

import com.tropicalstream.hammerklavier.contract.Cmd
import com.tropicalstream.hammerklavier.contract.CoreClockState
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.stub.KeyMapFixtures
import com.tropicalstream.hammerklavier.contract.stub.SineBank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/** T2.1: sample-accurate, drift-free onsets (PLAN §2.5, R9, R10). */
class EngineTimingTest {
    private val root = 57                      // a SineBank root: rate 1, onsetOut 96, bit-exact copy

    /** Output frame of the first non-zero sample minus one = the sampled onset (the region is 0 up to and at frame 96). */
    private fun onsetOf(h: Harness, from: Int = 0): Int {
        val i = h.firstAbove(0f, from)
        assertTrue("no onset found", i >= 0)
        return i - 1
    }

    @Test fun oneSecondLandsOnFrame48000AtEveryBlockOffset() {
        for (m in 0..45) {
            val h = Harness()
            val onUs = 1_000_000L + 125L * m                       // 6 frames per 125 µs: offsets 128 + 6m cover a block
            h.play(perfSong(listOf(N(onUs / 1000.0, onUs / 1000.0 + 400, root, 100))))
            h.renderTo(48_000 + 6 * m + 512)
            assertEquals("m=$m", 48_000 + 6 * m, onsetOf(h))
        }
    }

    @Test fun halfRateDoublesTheFrame() {
        val h = Harness()
        h.cmd(Cmd.RATE, f = 0.5f)
        h.play(perfSong(listOf(N(1000.0, 1400.0, root, 100))))
        h.renderTo(96_512)
        assertEquals(96_000, onsetOf(h))
    }

    @Test fun tenMinutesInLandsExactlyAtThreeTempi() {
        for (r in floatArrayOf(1f, 0.95f, 1.37f)) {
            val h = Harness()
            h.cmd(Cmd.RATE, f = r)
            val onUs = 600_000_000L
            h.play(perfSong(listOf(N(onUs / 1000.0, onUs / 1000.0 + 300, root, 100))))
            val rf = Math.round(r.toDouble() * 4294967296.0)
            val expected = Math.round(onUs * 0.048 * 4294967296.0 / rf)
            // Render to just before the onset without keeping 10 minutes of audio.
            val core = h.core
            val blk = FloatArray(2 * HK.BLOCK)
            var frame = 0L
            while (frame + HK.BLOCK < expected - 2048) { core.render(blk, frame); frame += HK.BLOCK }
            var found = -1L
            while (found < 0 && frame < expected + 4096) {
                core.render(blk, frame)
                for (i in 0 until HK.BLOCK) if (found < 0 && blk[2 * i] != 0f) found = frame + i
                frame += HK.BLOCK
            }
            assertEquals("rate $r", expected, found - 1)
        }
    }

    @Test fun playbackRatesLandWithinOneFrame() {
        for (semis in intArrayOf(-2, 2, 5)) {
            val rate = 2.0.pow(semis / 12.0).toFloat()
            val km = KeyMapFixtures.forSineBank(1).withRate(root, rate)
            val h = Harness(keyMap = km)
            h.play(perfSong(listOf(N(1000.0, 1500.0, root, 100))))
            h.renderTo(60_000)
            // Locate a level crossing on the attack with sub-frame precision in the source and in the output; the
            // sampled onset is the output crossing minus the source's (crossing − onset) at the rate.
            val wave = SineBank.waveOf((root - 21) / 3)
            var peakS = 0; for (i in 0 until wave.size / 2) peakS = maxOf(peakS, abs(wave[2 * i].toInt()))
            val lvl = 0.3 * peakS                                     // on the smooth rise, where interpolation is exact enough
            var t = 1; while (abs(wave[2 * t].toInt()) < lvl) t++
            val a0 = abs(wave[2 * (t - 1)].toInt()).toDouble(); val a1 = abs(wave[2 * t].toInt()).toDouble()
            val srcCross = t - 1 + (lvl - a0) / (a1 - a0)
            val lo = lvl / 32768.0
            val det = h.firstAbove(lo.toFloat())
            val b0 = abs(h.left(det - 1)).toDouble(); val b1 = abs(h.left(det)).toDouble()
            val outCross = det - 1 + (lo - b0) / (b1 - b0)
            val predictedOnset = outCross - (srcCross - SineBank.ONSET) / rate
            assertTrue("semis $semis: onset ${predictedOnset} vs 48000", abs(predictedOnset - 48_000) <= 1.0 + 1e-6)
        }
    }

    @Test fun onsetAfterAPauseAndAfterASeekLandsExactly() {
        // Pause 3000 frames before the onset for 40 blocks: the onset moves by exactly the paused frames.
        val h = Harness()
        h.play(perfSong(listOf(N(1000.0, 1400.0, root, 100))))
        h.renderTo(45_056)                                            // a block boundary
        h.cmd(Cmd.PAUSE, 60L)
        h.renderBlocks(40)
        val resumedAt = h.frames
        h.cmd(Cmd.PLAY)
        h.renderTo(resumedAt + 4096)
        assertEquals(48_000 + (resumedAt - 45_056), onsetOf(h))

        // Seek at a block boundary to 0.5 s: the 1 s onset lands 24,000 frames later.
        val s = Harness()
        s.play(perfSong(listOf(N(1000.0, 1400.0, root, 100))))
        s.renderTo(2560)
        val at = s.frames
        s.cmd(Cmd.SEEK, 500_000L)
        s.renderTo(at + 26_000)
        assertEquals(at + 24_000, onsetOf(s))
    }

    @Test fun clockStatePublishesTheBlockStart() {
        val h = Harness()
        h.play(perfSong(listOf(N(1000.0, 1400.0, root, 100))))
        val st = CoreClockState()
        h.renderBlocks(1); h.core.clockState(st)
        assertEquals(0L, st.songUs); assertTrue(st.playing)
        h.renderBlocks(1874); h.core.clockState(st)                // block 1874 starts at 479,744 frames = 9.994666 s
        assertEquals((1874 * 256 / 0.048).toLong(), st.songUs)
        assertEquals(1, st.generation)
        assertEquals(3, st.registration)
    }

    @Test fun debugOnsetReportsTheScheduledAndDetectedFrames() {
        val h = Harness()
        val o = LongArray(2)
        h.play(perfSong(listOf(N(1000.0, 1050.0, 69, 118))))
        h.renderTo(40_000)
        assertTrue(!h.core.debugOnset(o))
        h.renderTo(50_000)
        assertTrue(h.core.debugOnset(o))
        assertTrue("scheduled ${o[0]} detected ${o[1]}", abs(o[0] - o[1]) <= 2)
        assertTrue(abs(o[0] - 48_000) < 48)
    }

    /** A skipped block in WP4's clock (blockStartFrame jumps): the engine resyncs, debugOnset follows WP4's frames. */
    @Test fun aSkippedBlockStartFrameResyncsTheOutputClock() {
        val h = Harness()
        val o = LongArray(2)
        val blk = FloatArray(2 * HK.BLOCK)
        h.play(perfSong(listOf(N(1000.0, 1050.0, 69, 118))))
        var f = 0L
        while (f < 20_480) { h.core.render(blk, f); f += HK.BLOCK }
        f += 10 * HK.BLOCK                                   // WP4 skipped ten blocks
        while (f < 70_000) { h.core.render(blk, f); f += HK.BLOCK }
        assertTrue(h.core.debugOnset(o))
        assertTrue("scheduled ${o[0]} detected ${o[1]}", abs(o[0] - o[1]) <= 2)
        assertTrue("scheduled ${o[0]}", abs(o[0] - (48_000 + 10 * HK.BLOCK)) < 48)
    }
}
