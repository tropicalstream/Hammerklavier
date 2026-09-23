package com.tropicalstream.hammerklavier.audio

import com.tropicalstream.hammerklavier.contract.AudioListener
import com.tropicalstream.hammerklavier.contract.AudioStats
import com.tropicalstream.hammerklavier.contract.ClockSample
import com.tropicalstream.hammerklavier.contract.EngineCoreApi
import com.tropicalstream.hammerklavier.contract.HeadPose
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.OutputRoute
import com.tropicalstream.hammerklavier.contract.RouteInfo
import com.tropicalstream.hammerklavier.contract.StatusCode
import com.tropicalstream.hammerklavier.contract.SyntheticScore
import com.tropicalstream.hammerklavier.contract.VoiceCursorBoard
import com.tropicalstream.hammerklavier.contract.stub.KeyMapFixtures
import com.tropicalstream.hammerklavier.contract.stub.MemSettings
import com.tropicalstream.hammerklavier.contract.stub.SineBank
import com.tropicalstream.hammerklavier.contract.stub.SineCore
import com.tropicalstream.hammerklavier.contract.stub.StubScoreCompiler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** AudioOutput's HKAudio loop on an in-memory sink with SineCore (R86: onEnded exactly once). */
class AudioOutputTest {
    /** In-memory output: accepts every write, reports a head [lag] frames behind. */
    class FakeSink(private val failAt: Long = -1L, private val lag: Int = 3000, private val sleepEvery: Int = 0) : OutputSink {
        @Volatile var written = 0L
        @Volatile var plays = 0
        @Volatile var pauses = 0
        @Volatile var released = false
        @Volatile var peak = 0f
        private var failed = false
        private var n = 0
        private val t0 = System.nanoTime()
        override fun write(buf: FloatArray, off: Int, frames: Int): Int {
            if (!failed && failAt >= 0 && written >= failAt) { failed = true; return TrackSupervisor.ERROR_DEAD_OBJECT }
            for (i in off until off + 2 * frames) { val a = Math.abs(buf[i]); if (a > peak) peak = a }
            written += frames
            if (sleepEvery > 0 && ++n % sleepEvery == 0) Thread.sleep(1)
            return frames
        }
        override fun play() { plays++ }
        override fun pause() { pauses++ }
        override fun release() { released = true }
        override fun timestamp(out: LongArray): Boolean {
            if (written < lag) return false
            // The sink runs faster than real time, so report sink-clock nanos consistent with 48 kHz
            // (the contracts-v1.1 AudioClock rejects pairs whose implied rate is off by more than 0.5%).
            val f = written - lag
            out[0] = f; out[1] = t0 + f * 1_000_000_000L / 48_000L; return true
        }
        override fun playbackHeadPosition(): Long = maxOf(0L, written - lag)
        override fun underrunCount(): Int = 0
        override val bufferFrames: Int get() = 4096
        override val fast: Boolean get() = false
    }

    private val sinks = CopyOnWriteArrayList<FakeSink>()
    private var outputs = ArrayList<AudioOutput>()

    private fun output(core: EngineCoreApi = SineCore(), factory: SinkFactory = SinkFactory { FakeSink().also { sinks += it } }): AudioOutput =
        AudioOutput(core, VoiceCursorBoard(), HeadPose(), MemSettings(), factory, post = { it.run() }, main = null, focusFactory = null)
            .also { outputs += it }

    @After fun tearDown() { outputs.forEach { it.stop() } }

    private fun waitFor(ms: Long, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) { if (System.currentTimeMillis() > end) throw AssertionError("timeout: $what"); Thread.sleep(5) }
    }

    private class Recorder : AudioListener {
        val ended = CopyOnWriteArrayList<Int>()
        val errors = CopyOnWriteArrayList<Pair<StatusCode, String>>()
        val overloads = AtomicInteger()
        override fun onEnded(generation: Int) { ended += generation }
        override fun onOverload(newCap: Int) { overloads.incrementAndGet() }
        override fun onEngineError(code: StatusCode, detail: String) { errors += code to detail }
        override fun onRouteChanged(route: RouteInfo) {}
    }

    private fun loadScale(a: AudioOutput, generation: Int = 5) {
        val bank = SineBank(layers = 2)
        a.setBank(bank, KeyMapFixtures.forSineBank(2), InstrumentProfile.GRAND)
        val p = StubScoreCompiler().synthetic(SyntheticScore.SCALE, InstrumentProfile.of(InstrumentId.GRAND), generation)
        a.setPerformance(p, 0L, autoPlay = true)
    }

    @Test fun endsExactlyOnceThenParks() {
        val rec = Recorder()
        val a = output()
        a.setListener(rec)
        loadScale(a)
        a.start()
        waitFor(30_000, "onEnded") { rec.ended.isNotEmpty() }
        assertEquals(listOf(5), rec.ended.toList())
        a.pause()                                                  // what SessionController does at the end
        // After the end the engine is idle: 10 s of idle frames → park (track paused).
        waitFor(30_000, "park") { val s = AudioStats(); a.stats(s); s.parked }
        Thread.sleep(100)
        assertEquals(1, rec.ended.size)
        assertTrue(sinks[0].pauses >= 1)
        assertTrue("SineCore made sound", sinks[0].peak > 0.05f)
        assertTrue(rec.errors.isEmpty())
    }

    @Test fun clockAdvancesAndGetsTimestamps() {
        val a = output()
        loadScale(a)
        a.start()
        waitFor(10_000, "timestamps") { val c = com.tropicalstream.hammerklavier.contract.ClockStats(); a.clockStats(c); c.tsAccepted > 3 }
        // The unpaced writer can outrun the seqlocked reader's retries; a reader then keeps its previous sample.
        val s = ClockSample()
        waitFor(5_000, "valid sample") { a.sampleClock(s); s.valid }
        assertTrue(s.fromTimestamp)
        assertEquals(OutputRoute.SPEAKER, a.route.route)
        assertTrue(a.latencyAllowance(OutputRoute.SPEAKER) > 0)
    }

    @Test fun unparksOnPlayAndSeek() {
        val a = output()
        loadScale(a)
        a.pause()
        a.start()
        waitFor(30_000, "park") { val s = AudioStats(); a.stats(s); s.parked }
        val plays = sinks[0].plays
        a.seek(2_000_000L)
        waitFor(5_000, "unpark") { sinks[0].plays > plays }
        a.play()
        waitFor(5_000, "playing") { val s = ClockSample(); a.sampleClock(s); s.playing }
    }

    /** The sink's head keeps counting across pause/play, as AudioTrack's does (review WP4 r2 blocker). */
    @Test fun unparkKeepsClockAndHeadroomConsistent() {
        val rec = Recorder()
        val a = output()
        a.setListener(rec)
        loadScale(a)
        a.pause()
        a.start()
        waitFor(30_000, "park") { val s = AudioStats(); a.stats(s); s.parked }
        val plays = sinks[0].plays
        a.play()
        waitFor(5_000, "unpark") { sinks[0].plays > plays }
        val c = com.tropicalstream.hammerklavier.contract.ClockStats()
        waitFor(10_000, "timestamps after unpark") { a.clockStats(c); c.tsAccepted > 3 }
        assertEquals("no timestamp rejected after unpark", 0, c.tsRejected)
        val s = ClockSample()
        waitFor(5_000, "timestamp sample") { a.sampleClock(s); s.valid && s.fromTimestamp }
        // Let HeadroomGuard see well over its 10 s window of post-unpark frames.
        val w0 = sinks[0].written
        waitFor(30_000, "12 s of frames") { sinks[0].written - w0 > 12 * 48_000L }
        val st = AudioStats(); a.stats(st)
        assertTrue("queued never negative: ${st.headroomMinFrames}", st.headroomMinFrames >= 0)
        assertEquals("no VOICE_CAP step-down", 0, rec.overloads.get())
        assertEquals(1, sinks.size)
    }

    @Test fun permanentFocusLossParksAtOnce() {
        val a = output()
        loadScale(a)
        a.start()
        waitFor(10_000, "playing") { val s = ClockSample(); a.sampleClock(s); s.playing }
        a.focusPause(permanent = true)
        waitFor(3_000, "parked") { val s = AudioStats(); a.stats(s); s.parked }
        assertTrue(sinks[0].pauses >= 1)
    }

    @Test fun deadObjectRebuildsTheTrackAtTheSamePosition() {
        val count = AtomicInteger()
        val a = output(factory = SinkFactory { FakeSink(failAt = if (count.getAndIncrement() == 0) 48_000L else -1L).also { sinks += it } })
        val rec = Recorder(); a.setListener(rec)
        loadScale(a)
        a.start()
        waitFor(10_000, "rebuild") { sinks.size >= 2 && sinks[1].written > 96_000 }
        val s = AudioStats(); a.stats(s)
        assertEquals(1, s.trackRebuilds)
        assertTrue(sinks[0].released)
        assertTrue(rec.errors.isEmpty())
    }

    @Test fun outputLostAfterThreeRebuilds() {
        val a = output(factory = SinkFactory { FakeSink(failAt = 4096L).also { sinks += it } })
        val rec = Recorder(); a.setListener(rec)
        loadScale(a)
        a.start()
        waitFor(10_000, "output lost") { rec.errors.isNotEmpty() }
        assertEquals(StatusCode.AUDIO_STOPPED to "output lost", rec.errors[0])
        assertEquals(4, sinks.size)                               // the first + 3 rebuilds
    }

    @Test fun noTrackAtAllRunsSilentlyOnAFakeClock() {
        val a = output(factory = SinkFactory { throw IllegalStateException("no track") })
        val rec = Recorder(); a.setListener(rec)
        loadScale(a)
        a.start()
        waitFor(5_000, "unavailable") { rec.errors.isNotEmpty() }
        assertEquals(StatusCode.AUDIO_UNAVAILABLE, rec.errors[0].first)
        assertTrue(a.isUnavailable)
        val s = ClockSample(); a.sampleClock(s)
        assertTrue(s.valid); assertFalse(s.fromTimestamp); assertEquals(5, s.generation)
    }

    @Test fun renderExceptionRecoversOnceThenStops() {
        val boom = AtomicInteger(0)
        val sine = SineCore()
        val core = object : EngineCoreApi by sine {
            override fun render(out: FloatArray, blockStartFrame: Long) {
                if (blockStartFrame > 20_000 && boom.get() < 2) { boom.incrementAndGet(); throw IllegalStateException("x") }
                sine.render(out, blockStartFrame)
            }
        }
        val a = output(core)
        val rec = Recorder(); a.setListener(rec)
        loadScale(a)
        a.start()
        waitFor(10_000, "stopped") { rec.errors.isNotEmpty() }
        assertEquals(StatusCode.AUDIO_STOPPED, rec.errors[0].first)
        assertEquals(2, boom.get())
    }

    @Test fun stopAndStartRestoreThePausedPosition() {
        val a = output()
        loadScale(a)
        a.start()
        waitFor(10_000, "some song time") { val s = ClockSample(); a.sampleClock(s); s.songUs > 3_000_000 }
        a.pause()
        Thread.sleep(200)
        a.stop()
        val s0 = ClockSample(); a.sampleClock(s0)
        a.start()
        waitFor(10_000, "second session") { val s = ClockSample(); a.sampleClock(s); s.valid && s.session > s0.session }
        val s1 = ClockSample(); a.sampleClock(s1)
        assertFalse(s1.playing)
        assertTrue("restored ${s1.songUs} vs ${s0.songUs}", s1.songUs >= 3_000_000)
        assertEquals(5, s1.generation)
    }

    @Test fun hkAudioLoopAllocatesNothingAfterWarmUp() {
        // Allocation on another thread is measured with the thread's own counter from inside a core.
        val bytes = java.util.concurrent.atomic.AtomicLong(-1)
        val sine = SineCore()
        val core = object : EngineCoreApi by sine {
            var t0 = -1L; var blocksSeen = 0
            override fun render(out: FloatArray, blockStartFrame: Long) {
                sine.render(out, blockStartFrame)
                blocksSeen++
                if (blocksSeen == 2000) t0 = com.tropicalstream.hammerklavier.testutil.AllocProbe.allocatedBytes()
                if (blocksSeen == 4000) bytes.set(com.tropicalstream.hammerklavier.testutil.AllocProbe.allocatedBytes() - t0)
            }
        }
        val a = output(core)
        loadScale(a)
        a.start()
        waitFor(20_000, "4000 blocks") { bytes.get() >= 0 }
        assertEquals("HKAudio allocated", 0L, bytes.get())
    }
}
