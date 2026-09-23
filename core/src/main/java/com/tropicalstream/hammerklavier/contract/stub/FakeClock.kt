package com.tropicalstream.hammerklavier.contract.stub

import com.tropicalstream.hammerklavier.contract.ClockSample
import com.tropicalstream.hammerklavier.contract.SongClock

/**
 * A SongClock advancing song time in real time from [play] (PLAN §2.3 stub behaviour): `valid`,
 * `fromTimestamp = false`. Drives visuals when no audio exists. Transport calls on any thread;
 * [sample] is allocation-free. Song time stops at [endUs] (the performance's duration).
 */
class FakeClock(private val nanos: () -> Long = { System.nanoTime() }) : SongClock {
    private val lock = Any()
    private var baseUs = 0L            // song time at baseNanos
    private var baseNanos = 0L
    private var rate = 1f
    private var playing = false
    private var epoch = 0
    private var generation = -1
    private var registration = 3
    private var endUs = Long.MAX_VALUE

    /** New performance: song time jumps to [startUs], the epoch bumps. */
    fun setPerformance(generation: Int, startUs: Long, endUs: Long = Long.MAX_VALUE) {
        synchronized(lock) {
            this.generation = generation; this.endUs = endUs
            baseUs = startUs.coerceAtLeast(0L); baseNanos = nanos(); epoch++
        }
    }

    fun play() {
        synchronized(lock) { if (!playing) { baseNanos = nanos(); playing = true } }
    }

    fun pause() {
        synchronized(lock) { if (playing) { val now = nanos(); baseUs = songUsAt(now); baseNanos = now; playing = false } }
    }

    fun seek(us: Long) {
        synchronized(lock) { baseUs = us.coerceAtLeast(0L); baseNanos = nanos(); epoch++ }
    }

    fun setRate(r: Float) {
        synchronized(lock) { val now = nanos(); baseUs = songUsAt(now); baseNanos = now; rate = r }
    }

    fun setRegistration(mask: Int) {
        synchronized(lock) { registration = mask }
    }

    val isPlaying: Boolean get() = synchronized(lock) { playing }
    val currentGeneration: Int get() = synchronized(lock) { generation }

    /** The song time now (µs). */
    fun songUsNow(): Long = synchronized(lock) { songUsAt(nanos()) }

    /** true once a playing clock has reached the end of its performance. */
    fun atEnd(): Boolean = synchronized(lock) { playing && endUs != Long.MAX_VALUE && songUsAt(nanos()) >= endUs }

    private fun songUsAt(n: Long): Long {
        if (!playing) return baseUs
        val t = baseUs + ((n - baseNanos) / 1000.0 * rate).toLong()
        return if (t > endUs) endUs else t
    }

    override fun sample(nanoTime: Long, out: ClockSample) {
        synchronized(lock) {
            out.songUs = songUsAt(nanoTime); out.rate = rate; out.playing = playing; out.epoch = epoch
            out.session = 0; out.generation = generation; out.registration = registration
            out.heardFrame = out.songUs * 48 / 1000; out.valid = true; out.fromTimestamp = false
        }
    }
}
