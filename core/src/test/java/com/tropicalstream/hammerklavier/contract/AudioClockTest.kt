package com.tropicalstream.hammerklavier.contract

import com.tropicalstream.hammerklavier.testutil.AllocProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/** PLAN §2.5, §7.2 WP0 AudioClockTest. Times in ns; 48 kHz; BLOCK = 256. */
class AudioClockTest {
    private val st = CoreClockState()
    private val out = ClockSample()
    private val B = HK.BLOCK.toLong()

    private fun block(c: AudioClock, f: Long, songUs: Long, playing: Boolean = true, rate: Float = 1f, epoch: Int = 0, gen: Int = 0) {
        st.songUs = songUs; st.playing = playing; st.rate = rate; st.epoch = epoch; st.generation = gen; st.registration = 3
        c.publishBlock(f, st)
    }

    private fun nsForFrame(f: Long) = (f * 1_000_000_000L + 47_999L) / 48_000L    // ceil: H(n) = f exactly

    @Test fun invalidUntilTheFirstBlock() {
        val c = AudioClock()
        c.sample(0L, out); assertFalse(out.valid)
        block(c, 0, 0); c.sample(0L, out); assertTrue(out.valid)
    }

    @Test fun songTimeExactAcrossPauseSeekAndRate() {
        val c = AudioClock()
        // Blocks 0..9 playing at 1.0 from song 0; 10..14 paused at the reached position; 15..19 after a seek to 5 s, epoch 1;
        // 20..29 at rate 0.5.
        var song = 0L
        val songAt = LongArray(30)
        for (k in 0 until 30) {
            val f = k * B
            val playing = k < 10 || k >= 15
            val rate = if (k >= 20) 0.5f else 1f
            if (k == 15) song = 5_000_000L
            songAt[k] = song
            block(c, f, song, playing, rate, epoch = if (k >= 15) 1 else 0)
            if (playing) song += (B * rate * 1_000_000.0 / 48_000).toLong()
        }
        c.publishTimestamp(0L, 0L)                     // anchor: frame 0 heard at t = 0
        fun at(frame: Long) { c.sample(nsForFrame(frame), out) }
        at(5 * B + 100); assertEquals(songAt[5] + 100L * 1_000_000 / 48_000, out.songUs); assertTrue(out.playing)
        at(12 * B + 17); assertEquals(songAt[12], out.songUs); assertFalse(out.playing)          // paused: S_k
        at(15 * B); assertEquals(5_000_000L, out.songUs); assertEquals(1, out.epoch)
        at(14 * B + 255); assertEquals(0, out.epoch)                                              // the seek is not heard before its block
        at(22 * B + 96); assertEquals(songAt[22] + (96 * 0.5 * 1e6 / 48_000).toLong(), out.songUs); assertEquals(0.5f, out.rate)
        assertTrue(out.fromTimestamp)
    }

    @Test fun estimateBeforeTheFirstTimestampThenTheTimestamp() {
        val c = AudioClock()
        for (k in 0 until 40) block(c, k * B, k * B * 1_000_000 / 48_000)
        val framesAccepted = 40 * B
        c.publishEstimate(framesAccepted, 6_720, 1_000_000_000L)
        c.sample(1_000_000_000L, out)
        assertFalse(out.fromTimestamp); assertEquals(framesAccepted - 6_720, out.heardFrame)
        assertEquals((framesAccepted - 6_720) * 1_000_000 / 48_000, out.songUs)
        assertTrue(c.publishTimestamp(framesAccepted - 5_000, 1_000_000_000L))
        c.sample(1_000_000_000L, out)
        assertTrue(out.fromTimestamp); assertEquals(framesAccepted - 5_000, out.heardFrame)
        c.publishEstimate(framesAccepted, 6_720, 2_000_000_000L)                               // ignored after a timestamp
        c.sample(1_000_000_000L, out); assertEquals(framesAccepted - 5_000, out.heardFrame)
    }

    @Test fun stopStartCycleWithReset() {
        val c = AudioClock()
        for (k in 0 until 10) block(c, k * B, 1_000_000L + k * 5_333, gen = 4)
        c.publishTimestamp(1_000, 0L)
        c.sample(0L, out); val s0 = out.session; assertTrue(out.valid)
        c.reset()                                                                               // a new track: frames from 0 again
        c.sample(0L, out); assertFalse(out.valid); assertEquals(s0 + 1, out.session)
        block(c, 0, 2_000_000L, gen = 4)
        c.sample(10_000_000L, out)
        assertTrue(out.valid); assertFalse(out.fromTimestamp); assertEquals(s0 + 1, out.session)
        assertEquals(2_000_000L, out.songUs)                                                    // no anchor: the newest record
        c.publishEstimate(B, 6_720, 10_000_000L)
        c.sample(10_000_000L, out); assertEquals(2_000_000L, out.songUs)                       // H before the only record → oldest
        val stats = ClockStats(); c.stats(stats); assertEquals(0, stats.clockMiss)                 // before a new session's first record: not a miss (M1)
        assertTrue(c.publishTimestamp(10, 20_000_000L))                                          // first timestamp of the session accepted
    }

    @Test fun shortAndNegativeWrites() {
        val c = AudioClock()
        // A short write: 100 of 256 frames accepted; framesAccepted = 100, no record yet; the rest follows next iteration.
        var framesAccepted = 100L
        c.sample(0L, out); assertFalse(out.valid)
        framesAccepted += 156
        block(c, 0, 0)                                                                          // the whole block is recorded once
        c.publishEstimate(framesAccepted, 0, 0L)
        c.sample(0L, out); assertEquals(256L, out.heardFrame); assertEquals(256L * 1_000_000 / 48_000, out.songUs)
        // A negative write accepts nothing: no record, framesAccepted unchanged, H stays clamped at newestF + BLOCK.
        c.publishEstimate(framesAccepted, 0, 0L)
        c.sample(1_000_000_000L, out); assertEquals(256L, out.heardFrame)
    }

    @Test fun staleAndRejectedTimestamps() {
        val c = AudioClock()
        for (k in 0 until 400) block(c, k * B, k * B * 1_000_000 / 48_000)
        assertTrue(c.publishTimestamp(48_000, 1_000_000_000L))
        assertFalse(c.publishTimestamp(48_100, 999_000_000L))                                   // older nanos
        assertFalse(c.publishTimestamp(48_000, 1_100_000_000L))                                 // frame did not advance
        assertFalse(c.publishTimestamp(48_000 + 48_480, 2_000_000_000L))                        // rate 1% high
        assertTrue(c.publishTimestamp(48_000 + 48_010, 2_000_000_000L))                         // 0.02%: accepted
        assertTrue(c.publishTimestamp(48_000 + 2 * 48_010, 3_000_000_000L))
        val s = ClockStats(); c.stats(s)
        assertEquals(3, s.tsAccepted); assertEquals(3, s.tsRejected)
        assertEquals(48_010f, s.fsFit, 1f)                                                        // the session's first pair anchors only; two in-fit pairs
        c.sample(2_000_000_000L, out); assertEquals(96_010L, out.heardFrame)
    }

    @Test fun fitFollowsTheHardwareRateAndDrift() {
        val c = AudioClock()
        for (k in 0 until 256) block(c, k * B * 20, 0)
        val fsHw = 47_931.0
        for (i in 0 until 40) {
            val nanos = 1_000_000_000L + i * 85_333_333L
            val jitter = if (i % 2 == 0) 3 else -3
            assertTrue(c.publishTimestamp((i * 85_333_333L * fsHw / 1e9).toLong() + 1000 + jitter, nanos))
        }
        val s = ClockStats(); c.stats(s)
        assertEquals(fsHw.toFloat(), s.fsFit, 2f)
        assertTrue("drift p99 ${s.driftP99Frames}", s.driftP99Frames in 4f..14f)
    }

    @Test fun hClampedAtNewestPlusBlock() {
        val c = AudioClock()
        for (k in 0 until 4) block(c, k * B, k * 5_333L)
        c.publishTimestamp(0, 0L)
        c.sample(10_000_000_000L, out)
        assertEquals(4 * B, out.heardFrame)
        assertEquals(3 * 5_333L + B * 1_000_000 / 48_000, out.songUs)
    }

    @Test fun oldestRecordFallbackCountsClockMiss() {
        val c = AudioClock(records = 16)
        for (k in 0 until 100) block(c, k * B, k * 1000L, playing = false)
        c.publishTimestamp(10 * B, 0L)                                                          // heard: block 10, overwritten long ago
        c.sample(0L, out)
        assertEquals(84_000L, out.songUs)                                                       // oldest kept record: block 84
        val s = ClockStats(); c.stats(s); assertEquals(1, s.clockMiss)
        c.sample(nsForFrame(80 * B + 10), out); assertEquals(90_000L, out.songUs)
        c.stats(s); assertEquals(1, s.clockMiss)
    }

    @Test fun sampleAllocatesNothing() {
        val c = AudioClock()
        for (k in 0 until 300) block(c, k * B, k * 5333L)
        c.publishTimestamp(100 * B, 0L)
        fun run(base: Int) { for (i in 0 until 10_000) { val j = base + i; c.sample(j * 1000L, out); block(c, (300 + j) * B, j.toLong()); if (i % 16 == 0) c.publishTimestamp(100 * B + j * 16L * 256, j * 85_333_333L + 1) } }
        run(0)                                  // warm-up: HotSpot's (re)compilation may charge a few hundred bytes once
        AllocProbe.assertNoAllocation("AudioClock") { run(10_000) }
    }

    /** 10⁶ reads against a writer thread: never a torn record (checksum fields) nor a torn anchor. */
    @Test(timeout = 120_000) fun noTornRecordsOrAnchors() {
        val c = AudioClock()
        val stop = AtomicBoolean(false)
        val ns0 = 1_000_000_000L
        val f0 = 1_000_000_020L                                           // not a multiple of 256: clamped H never matches the line
        val latestAnchorNs = java.util.concurrent.atomic.AtomicLong(ns0)
        // Every anchor lies on one line frame = f0 + (nanos − ns0) · 48000 / 1e9, so any consistent anchor gives the same H(n).
        val writer = Thread {
            val s = CoreClockState()
            var k = 0L
            c.publishTimestamp(f0, ns0)
            while (!stop.get()) {
                val f = f0 + k * 256
                s.songUs = f * 3 + 7; s.playing = false; s.rate = 1f
                s.epoch = (f % 1_000_003).toInt(); s.generation = (f / 256).toInt(); s.registration = (f % 3).toInt() + 1
                c.publishBlock(f, s)
                if (k % 12 == 0L && k > 0) {
                    val n = ns0 + k * 256 * 1_000_000_000L / 48_000                // exact: k·256 divisible by 3·… when k % 3 == 0
                    c.publishTimestamp(f, n); latestAnchorNs.set(n)
                }
                k++
            }
        }
        writer.start()
        while (true) { c.sample(Long.MAX_VALUE / 4, out); if (out.valid) break }
        var torn = 0; var reads = 0; var anchorChecks = 0; var misses = 0
        while (reads < 1_000_000) {
            val n = latestAnchorNs.get() + 1_000_000L                            // 1 ms after an anchor: H = its frame + 48
            out.valid = false
            c.sample(n, out)
            reads++
            if (!out.valid) continue
            val f = (out.songUs - 7) / 3
            if (out.songUs != f * 3 + 7 || out.epoch != (f % 1_000_003).toInt() || out.generation != (f / 256).toInt() ||
                out.registration != (f % 3).toInt() + 1 || out.playing) torn++
            val expected = f0 + (n - ns0) * 48_000 / 1_000_000_000L
            if ((out.heardFrame - f0) % 256 != 0L) {                              // not clamped to a record boundary
                anchorChecks++
                if (Math.abs(out.heardFrame - expected) > 1) misses++
            }
        }
        stop.set(true); writer.join()
        assertEquals(0, torn); assertEquals(0, misses); assertTrue("anchor checks $anchorChecks", anchorChecks > 1000)
    }

    /** M1 on the glasses: an off-line start-up pair must not block the consistent ones after it. */
    @Test fun offLineFirstTimestampIsDroppedAfterReseed() {
        val c = AudioClock()
        for (k in 0 until 64) block(c, k * B, 0L)
        assertTrue(c.publishTimestamp(3840, 1_000_000_000L))                  // start-up pair, off the line below
        var ok = 0
        for (i in 1..20) {
            val f = 10_080L + (i - 1) * 7680L
            val nanos = 1_234_225_260L + (i - 1) * 160_000_000L
            if (c.publishTimestamp(f, nanos)) ok++
        }
        val st = ClockStats(); c.stats(st)
        assertTrue("accepted $ok", ok >= 16)
        assertEquals(1, c.reseeds)
        assertEquals(48000f, st.fsFit, 1f)
    }
}
