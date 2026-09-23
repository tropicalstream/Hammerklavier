package com.tropicalstream.hammerklavier.contract

import java.util.concurrent.atomic.AtomicLong

/** One reading of the heard song time (PLAN §2.5). Preallocated by the reader; filled in place. */
class ClockSample {
    @JvmField var songUs = 0L        // song time heard at the asked instant (µs incl. pre-roll)
    @JvmField var rate = 1f; @JvmField var playing = false
    @JvmField var epoch = 0          // engine: bumps on seek, new performance, instrument swap
    @JvmField var session = 0        // clock: bumps on every AudioClock.reset() (start, un-park, track rebuild)
    @JvmField var generation = -1    // Performance.generation being heard
    @JvmField var registration = 3   // effective harpsichord register mask of the heard block
    @JvmField var heardFrame = 0L    // output frame reaching the DAC at the asked instant (clamped, see §2.5)
    @JvmField var valid = false      // false until the first block of this session
    @JvmField var fromTimestamp = false
}

/** Allocation-free, any thread. */
interface SongClock { fun sample(nanoTime: Long, out: ClockSample) }

/** Engine state at the START of the block just rendered. */
class CoreClockState {
    @JvmField var songUs = 0L; @JvmField var rate = 1f; @JvmField var playing = false
    @JvmField var epoch = 0; @JvmField var generation = -1; @JvmField var registration = 3
    @JvmField var endedGeneration = -1                                 // set once when EV_END's tails are done (§3.15)
    @JvmField var idle = false                                         // paused (or no performance), no voices, room tail inactive
}

class ClockStats { @JvmField var tsAccepted = 0; @JvmField var tsRejected = 0; @JvmField var clockMiss = 0
    @JvmField var latFrames = 0; @JvmField var fsFit = 48000f; @JvmField var driftP99Frames = 0f; @JvmField var session = 0 }

/**
 * The audio output clock (PLAN §2.5): block records and the timestamp anchor published by
 * HKAudio, read allocation-free by any thread. WP0 implements it fully in contracts-v1.1; the
 * contracts-v1 body keeps the newest published block only and extrapolates it at the nominal rate.
 */
class AudioClock(sampleRate: Int = HK.SR, records: Int = HK.CLOCK_RECORDS) : SongClock {
    private val fs = sampleRate
    private val lock = Any()
    private var session = 0
    private var hasBlock = false
    private var newestF = 0L
    private val st = CoreClockState()
    private var anchorFrame = 0L
    private var anchorNanos = 0L
    private var anchored = false
    private var fromTs = false
    private var accepted = 0
    private var rejected = 0

    /** Before every play() of a new, rebuilt or un-parked track. */
    fun reset() {
        synchronized(lock) { session++; hasBlock = false; anchored = false; fromTs = false }
    }

    /** HKAudio, after each accepted write. */
    fun publishBlock(blockStartFrame: Long, st: CoreClockState) {
        synchronized(lock) {
            newestF = blockStartFrame; hasBlock = true
            this.st.songUs = st.songUs; this.st.rate = st.rate; this.st.playing = st.playing
            this.st.epoch = st.epoch; this.st.generation = st.generation; this.st.registration = st.registration
        }
    }

    /** HKAudio, every 16 blocks and while idle; false = rejected. */
    fun publishTimestamp(frame: Long, nanoTime: Long): Boolean {
        synchronized(lock) {
            if (fromTs && (frame <= anchorFrame || nanoTime <= anchorNanos)) { rejected++; return false }
            anchorFrame = frame; anchorNanos = nanoTime; anchored = true; fromTs = true; accepted++
            return true
        }
    }

    /** Until the first accepted timestamp of a session. */
    fun publishEstimate(framesAccepted: Long, latencyFrames: Int, nanoTime: Long) {
        synchronized(lock) {
            if (fromTs) return
            anchorFrame = framesAccepted - latencyFrames; anchorNanos = nanoTime; anchored = true
        }
    }

    override fun sample(nanoTime: Long, out: ClockSample) {
        synchronized(lock) {
            out.session = session
            if (!hasBlock) { out.valid = false; out.playing = false; out.fromTimestamp = false; return }
            var h = if (anchored) anchorFrame + (nanoTime - anchorNanos) * fs / 1_000_000_000L else newestF
            if (h > newestF + HK.BLOCK) h = newestF + HK.BLOCK
            val dFrames = h - newestF
            out.songUs = if (st.playing) st.songUs + (dFrames * st.rate * 1_000_000.0 / fs).toLong() else st.songUs
            out.rate = st.rate; out.playing = st.playing; out.epoch = st.epoch; out.generation = st.generation
            out.registration = st.registration; out.heardFrame = h; out.valid = true; out.fromTimestamp = fromTs
        }
    }

    fun stats(out: ClockStats) {
        synchronized(lock) {
            out.tsAccepted = accepted; out.tsRejected = rejected; out.clockMiss = 0; out.latFrames = 0
            out.fsFit = fs.toFloat(); out.driftP99Frames = 0f; out.session = session
        }
    }
}

/** One per frame, GLThread. */
class VisTime {
    @JvmField var tUs = 0L                                             // filtered heard song time: monotone within (session, epoch, generation)
    @JvmField var exposeFromUs = 0L; @JvmField var exposeToUs = 0L     // contacts in (from, to] are drawn in this frame (each exactly once)
    @JvmField var reseed = true                                        // cursors must re-seed and exposure state is cleared
    @JvmField var playing = false; @JvmField var rate = 1f; @JvmField var epoch = 0; @JvmField var generation = -1
    @JvmField var registration = 3; @JvmField var heardFrame = 0L
}

/**
 * Keeps the picture's song time monotone within a (session, epoch, generation) and gives each frame
 * its exposure window (PLAN §2.5). Allocation-free. WP0 implements the hold/follow/slew/reseed
 * filter in contracts-v1.1; the contracts-v1 body passes the raw time through, never backwards.
 */
class VisualClock {
    private var first = true
    private var session = 0; private var epoch = 0; private var generation = -1
    private var tPrev = 0L; private var wPrev = 0L

    fun update(s: ClockSample, nowNanos: Long, out: VisTime) {
        val reseed = first || s.session != session || s.epoch != epoch || s.generation != generation
        first = false; session = s.session; epoch = s.epoch; generation = s.generation
        val t = if (reseed || s.songUs > tPrev) s.songUs else tPrev
        out.reseed = reseed
        out.exposeFromUs = if (reseed || !s.playing) t else wPrev
        out.exposeToUs = if (reseed || !s.playing) t else maxOf(wPrev, t)
        wPrev = out.exposeToUs; tPrev = t
        out.tUs = t; out.playing = s.playing; out.rate = s.rate; out.epoch = s.epoch; out.generation = s.generation
        out.registration = s.registration; out.heardFrame = s.heardFrame
    }
}

/** One AtomicLong: yaw and ω as float bits. Written by main (GazeCamera), read by any thread. */
class HeadPose {
    private val bits = AtomicLong(0L)

    /** Main, from GazeCamera's sensor callback. */
    fun write(yawRad: Float, omegaRadPerS: Float) {
        bits.set((yawRad.toRawBits().toLong() shl 32) or (omegaRadPerS.toRawBits().toLong() and 0xFFFFFFFFL))
    }

    fun yaw(): Float = Float.fromBits((bits.get() ushr 32).toInt())
    fun omega(): Float = Float.fromBits(bits.get().toInt())
}
