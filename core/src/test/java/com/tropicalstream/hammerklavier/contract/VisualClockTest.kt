package com.tropicalstream.hammerklavier.contract

import com.tropicalstream.hammerklavier.testutil.AllocProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** PLAN §2.5, §7.2 WP0 VisualClockTest. The true heard time is wall time (rate 1) unless stated. */
class VisualClockTest {
    private val s = ClockSample().apply { valid = true; playing = true; rate = 1f; generation = 0 }
    private val v = VisTime()

    /** Frame spacings of 16, 33 and 50 ms in a fixed irregular order. */
    private val spacingMs = longArrayOf(33, 16, 50, 33, 33, 16, 16, 50, 33)

    private class Run(val times: LongArray, val t: LongArray, val truth: LongArray, val reseed: BooleanArray)

    /** Runs [frames] frames; [raw] maps (wall µs, frame index) → raw song µs; [truth] the ideal. */
    private fun run(frames: Int, raw: (Long, Int) -> Long, truth: (Long) -> Long = { it }, clock: VisualClock = VisualClock()): Run {
        val times = LongArray(frames); val t = LongArray(frames); val tr = LongArray(frames); val rs = BooleanArray(frames)
        var wall = 0L
        for (i in 0 until frames) {
            s.songUs = raw(wall, i)
            clock.update(s, wall * 1000, v)
            times[i] = wall; t[i] = v.tUs; tr[i] = truth(wall); rs[i] = v.reseed
            wall += spacingMs[i % spacingMs.size] * 1000
        }
        return Run(times, t, tr, rs)
    }

    private fun assertMonotone(r: Run) {
        for (i in 1 until r.t.size) if (!r.reseed[i]) assertTrue("frame $i: ${r.t[i]} < ${r.t[i - 1]}", r.t[i] >= r.t[i - 1])
    }

    private fun assertErrorBelow2msAfter(r: Run, fromUs: Long) {
        for (i in r.t.indices) if (r.times[i] >= fromUs)
            assertTrue("frame $i at ${r.times[i]} µs: error ${r.t[i] - r.truth[i]} µs", Math.abs(r.t[i] - r.truth[i]) < 2_000)
    }

    @Test fun timestampJitter2ms() {
        val rnd = Random(7)
        val r = run(300, { w, _ -> w + (rnd.nextInt(4001) - 2000) })
        assertMonotone(r); assertErrorBelow2msAfter(r, 500_000)
    }

    @Test fun underrun200ms() {
        // Heard time stalls for 200 ms at 1 s (raw is clamped), then runs on 200 ms behind wall time.
        val truth = { w: Long -> if (w < 1_000_000) w else if (w < 1_200_000) 1_000_000L else w - 200_000 }
        val r = run(300, { w, _ -> truth(w) }, truth)
        assertMonotone(r); assertErrorBelow2msAfter(r, 0)
        assertFalse(r.reseed.drop(1).any { it })
    }

    @Test fun latencyStepPlus150ms() {
        // The output latency grows by 150 ms at 2 s: heard time jumps back 150 ms → one specified reseed.
        val truth = { w: Long -> if (w < 2_000_000) w else w - 150_000 }
        val r = run(300, { w, _ -> truth(w) }, truth)
        assertEquals(1, r.reseed.drop(1).count { it })
        assertMonotone(r); assertErrorBelow2msAfter(r, 0)
    }

    @Test fun leadChangeMinus5ms() {
        // The display lead drops by 5 ms at 2 s: raw steps back 5 ms, the picture holds, then follows; no reseed.
        val truth = { w: Long -> if (w < 2_000_000) w else w - 5_000 }
        val r = run(300, { w, _ -> truth(w) }, truth)
        assertFalse(r.reseed.drop(1).any { it })
        assertMonotone(r)
        assertErrorBelow2msAfter(r, 2_500_000)
        for (i in r.t.indices) if (r.times[i] in 2_000_000 until 2_500_000) assertTrue(r.t[i] - r.truth[i] in 0..5_000)
    }

    @Test fun firstTimestampCorrection30ms() {
        // The estimate was 30 ms early (heard time jumps +30 ms at 1 s) or 30 ms late (−30 ms): slew or hold, never a reseed.
        for (sign in intArrayOf(1, -1)) {
            val truth = { w: Long -> if (w < 1_000_000) w else w + sign * 30_000 }
            val r = run(300, { w, _ -> truth(w) }, truth)
            assertFalse(r.reseed.drop(1).any { it })
            assertMonotone(r)
            assertErrorBelow2msAfter(r, 2_000_000)
        }
    }

    @Test fun exposureWindowsTileTimeAndAreEmptyWhilePaused() {
        val clock = VisualClock()
        var wall = 0L
        var song = 0L
        var prevTo = Long.MIN_VALUE
        for (i in 0 until 500) {
            val paused = i in 200 until 260
            s.playing = !paused
            s.songUs = song
            clock.update(s, wall * 1000, v)
            if (i == 0) { assertTrue(v.reseed); assertEquals(v.exposeFromUs, v.exposeToUs) }
            else {
                assertFalse(v.reseed)
                assertEquals("frame $i", prevTo, v.exposeFromUs)                  // no gap, no overlap
                assertTrue(v.exposeToUs >= v.exposeFromUs)
                if (paused) assertEquals(v.exposeFromUs, v.exposeToUs)
            }
            prevTo = v.exposeToUs
            val dt = spacingMs[i % spacingMs.size] * 1000
            wall += dt; if (!paused) song += dt
        }
        assertTrue(prevTo > song - 60_000)
        s.playing = true
    }

    @Test fun reseedOnEpochGenerationAndSession() {
        val clock = VisualClock()
        s.songUs = 1_000_000; clock.update(s, 0, v); assertTrue(v.reseed)
        s.songUs = 1_033_000; clock.update(s, 33_000_000, v); assertFalse(v.reseed)
        s.epoch = 1; s.songUs = 9_000_000; clock.update(s, 66_000_000, v); assertTrue(v.reseed); assertEquals(9_000_000, v.tUs)
        assertEquals(v.exposeFromUs, v.exposeToUs)
        s.generation = 3; clock.update(s, 99_000_000, v); assertTrue(v.reseed)
        s.session = 2; clock.update(s, 132_000_000, v); assertTrue(v.reseed)
        s.songUs = 9_400_000; clock.update(s, 165_000_000, v); assertTrue(v.reseed)            // > +250 ms beyond expectation
        s.epoch = 0; s.generation = 0; s.session = 0
    }

    @Test fun updateAllocatesNothing() {
        val clock = VisualClock()
        AllocProbe.assertNoAllocation("VisualClock") {
            for (i in 0 until 10_000) { s.songUs = i * 33_000L; clock.update(s, i * 33_000_000L, v) }
        }
    }
}
