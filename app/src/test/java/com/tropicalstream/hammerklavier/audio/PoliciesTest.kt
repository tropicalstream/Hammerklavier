package com.tropicalstream.hammerklavier.audio

import android.media.AudioDeviceInfo
import com.tropicalstream.hammerklavier.contract.ClockSample
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.LoadedBank
import com.tropicalstream.hammerklavier.contract.OutputRoute
import com.tropicalstream.hammerklavier.contract.QualityLadder
import com.tropicalstream.hammerklavier.contract.SampleReader
import com.tropicalstream.hammerklavier.contract.SongClock
import com.tropicalstream.hammerklavier.contract.SyntheticScore
import com.tropicalstream.hammerklavier.contract.VoiceCursorBoard
import com.tropicalstream.hammerklavier.contract.stub.KeyMapFixtures
import com.tropicalstream.hammerklavier.contract.stub.SineBank
import com.tropicalstream.hammerklavier.contract.stub.StubScoreCompiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoliciesTest {
    @Test fun supervisorAllowsThreeRebuildsPerTenSeconds() {
        val s = TrackSupervisor()
        val sec = 1_000_000_000L
        assertTrue(s.allowRebuild(0)); assertTrue(s.allowRebuild(1 * sec)); assertTrue(s.allowRebuild(2 * sec))
        assertFalse(s.allowRebuild(3 * sec))
        assertTrue(s.allowRebuild(11 * sec))              // the first fell out of the window
        assertEquals(4, s.rebuilds)
    }

    @Test fun sixteenDeadTimestampsWhilePlaying() {
        val s = TrackSupervisor()
        repeat(15) { assertFalse(s.onTimestamp(false, playing = true)) }
        assertTrue(s.onTimestamp(false, playing = true))
        val t = TrackSupervisor()
        repeat(100) { assertFalse(t.onTimestamp(false, playing = false)) }
        repeat(15) { t.onTimestamp(false, true) }; t.onTimestamp(true, true)
        assertFalse(t.onTimestamp(false, true))
    }

    @Test fun renderGuard() {
        val g = RenderGuard()
        assertTrue(g.onException(0)); assertFalse(g.onException(5_000_000_000L))
        assertTrue(g.onException(20_000_000_000L))
    }

    @Test fun headroomStepsDownAndRecovers() {
        val h = HeadroomGuard()
        val w = 10L * HK.SR
        var f = 0L
        fun run(seconds: Int, queued: Int): Int { var r = 0; repeat(seconds * 50) { f += HK.SR / 50; val x = h.sample(f, queued, 96); if (x != 0) r = x }; return r }
        run(10, 4000); assertEquals(0, h.steps)
        run(10, 1000); assertEquals(1, h.steps); assertEquals(1, h.combSteps); assertEquals(96, h.cap(96))   // combs first
        run(10, 4000); val after = h.steps                  // the window still holding lows may step once more
        assertTrue(after in 1..2)
        run(50, 4000); assertEquals(after, h.steps)         // < 60 s of good headroom since the last low window
        run(15, 4000); assertEquals(after - 1, h.steps)     // one step back up
        // Floor at 32.
        repeat(20) { run(11, 100) }
        assertEquals(32, h.cap(96)); assertEquals(2, h.combSteps); assertEquals(HeadroomGuard.COMB_STEPS + 8, h.steps); assertTrue(w > 0)
    }

    @Test fun headroomUnderrunStepsDownOncePerSecond() {
        val h = HeadroomGuard()
        var f = 0L; var downs = 0
        h.sample(f, 5000, 96)
        repeat(5 * 50) { f += HK.SR / 50; if (h.sample(f, 0, 96, underrun = true) == 1) downs++ }
        assertTrue("one step per second: $downs", downs in 5..6)   // each: both comb steps + one cap step
        assertEquals(2, h.combSteps)
        assertEquals(96 - 8 * downs, h.cap(96))
    }

    @Test fun latencyTunerGrowsOneBlockPerNewUnderrun() {
        val t = LatencyTuner()
        assertEquals(-1, t.onUnderruns(0, 512))
        assertEquals(768, t.onUnderruns(1, 512))
        assertEquals(-1, t.onUnderruns(1, 768))
        assertEquals(1024, t.onUnderruns(3, 768))
        assertEquals(-1, t.onUnderruns(4, 16 * HK.BLOCK))
    }

    @Test fun routeClassification() {
        assertEquals(OutputRoute.SPEAKER, RouteMonitor.classify(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "", "", "normal").route)
        val w = RouteMonitor.classify(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "", "hp", "normal")
        assertEquals(OutputRoute.WIRED, w.route); assertEquals("wired", w.key)
        assertEquals(OutputRoute.WIRED, RouteMonitor.classify(AudioDeviceInfo.TYPE_USB_HEADSET, "", "", "").route)
        val b = RouteMonitor.classify(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "AA:BB", "buds", "normal")
        assertEquals(OutputRoute.BLUETOOTH, b.route); assertEquals("bt:AA:BB", b.key)
        assertEquals(OutputRoute.BLUETOOTH, RouteMonitor.classify(RouteMonitor.TYPE_BLE_HEADSET, "x", "", "").route)
        assertEquals(14_400, RouteMonitor.defaultLatencyFrames(OutputRoute.BLUETOOTH))
        assertEquals(6_720, RouteMonitor.defaultLatencyFrames(OutputRoute.WIRED))
    }

    @Test fun voicingOnlyWhenNothingPlays() {
        var now = 0L
        val v = VoicingScheduler { now }
        val q0 = QualityLadder.of(0, 96); val q1 = QualityLadder.of(1, 96)
        v.update(false, InstrumentId.GRAND, q0, 300)
        assertTrue(v.mayVoice(InstrumentId.GRAND, playableYet = true, activeComplete = false))
        v.update(true, InstrumentId.GRAND, q0, 300)
        assertTrue(v.shouldYield()); assertFalse(v.mayVoice(InstrumentId.GRAND, true, false))
        assertTrue("playing before playable must not deadlock", v.mayVoice(InstrumentId.GRAND, false, false))
        assertFalse(v.mayVoice(InstrumentId.UPRIGHT, false, false))
        now = 1_000
        v.update(false, InstrumentId.GRAND, q1, 300)
        assertFalse("Q1 forbids voicing", v.mayVoice(InstrumentId.GRAND, true, false))
        assertTrue("the first playable set is exempt", v.mayVoice(InstrumentId.GRAND, false, false))
        v.update(false, InstrumentId.GRAND, q0, 380)
        assertFalse("too hot", v.mayVoice(InstrumentId.GRAND, true, false))
        v.update(false, InstrumentId.GRAND, q0, 300)
        // Other kits: after the active one completes and ≥ 10 s idle.
        assertFalse(v.mayVoice(InstrumentId.UPRIGHT, true, activeComplete = false))
        assertFalse(v.mayVoice(InstrumentId.UPRIGHT, true, activeComplete = true))
        now = 11_001
        assertTrue(v.mayVoice(InstrumentId.UPRIGHT, true, activeComplete = true))
    }

    /** Records prefetch calls. */
    private class SpyBank(val inner: SineBank) : LoadedBank by inner {
        val calls = ArrayList<Triple<Int, Int, Int>>()
        override fun newReader(): SampleReader = object : SampleReader {
            override fun read(region: Int, fromFrame: Int, frames: Int, dst: ShortArray, dstOff: Int) = 0
            override fun prefetch(region: Int, fromFrame: Int, frames: Int) { synchronized(calls) { calls += Triple(region, fromFrame, frames) } }
            override val slowReads: Int get() = 0
        }
        override var readyMask: Long
            get() = inner.readyMask
            set(v) { inner.readyMask = v }
    }

    @Test fun prefetcherReadsVoicesAndUpcomingHeads() {
        var songUs = 1_000_000L
        val clock = object : SongClock {
            override fun sample(nanoTime: Long, out: ClockSample) {
                out.valid = true; out.playing = true; out.songUs = songUs; out.epoch = 1; out.generation = 3; out.rate = 1f
            }
        }
        val cursors = VoiceCursorBoard()
        val p = Prefetcher(cursors, clock)
        val bank = SpyBank(SineBank(layers = 2))
        p.bank = bank; p.keyMap = KeyMapFixtures.forSineBank(2)
        p.perf = StubScoreCompiler().synthetic(SyntheticScore.SCALE, InstrumentProfile.GRAND, 3)
        cursors.set(4, 17, 12_345)
        p.step()
        val first = synchronized(bank.calls) { bank.calls.toList() }
        assertTrue(first.contains(Triple(17, 12_345, Prefetcher.VOICE_AHEAD)))
        val heads = first.filter { it.second == 0 && it.third == Prefetcher.HEAD }
        // SCALE: one note per 250 ms → the next 1.6 s holds 6–7 onsets.
        assertTrue("heads ${heads.size}", heads.size in 5..8)
        bank.calls.clear()
        songUs += 100_000
        p.step()
        val second = bank.calls.filter { it.third == Prefetcher.HEAD }
        assertTrue("only newly entering notes: ${second.size}", second.size <= 1)
        assertEquals(bank.generation, p.bankGeneration)
    }
}
