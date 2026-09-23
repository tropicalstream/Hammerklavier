package com.tropicalstream.hammerklavier.contract

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

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
 * HKAudio (the one writer), read allocation-free by any thread.
 *
 * Every published value lives in an `AtomicLongArray` under a per-record version (a seqlock,
 * §2.1 rule 2): the writer makes the version odd, sets the fields and a checksum, makes it even;
 * a reader retries (≤ 3 times) on an odd or changed version or a checksum mismatch, else keeps
 * its previous sample. `reset()` hides every record (head = 0), drops the anchor, re-enters
 * estimate mode and then bumps `session`; readers compare the session before and after a read.
 *
 * Timestamps are accepted only if the frame advanced, the nanos are newer and the rate implied
 * against the oldest pair in the fit window lies within ±0.5% of `fs_fit` (least squares over
 * the last 32 accepted pairs, seeded with [sampleRate], kept across resets). The first
 * timestamp of a session replaces the estimate unconditionally. After [RESEED_AFTER] consecutive
 * rate rejections the fit window is dropped and the pair accepted (M1: the AR1's start-up
 * timestamp lies off the line and otherwise blocked every later one).
 */
class AudioClock(sampleRate: Int = HK.SR, records: Int = HK.CLOCK_RECORDS) : SongClock {
    private val fs = sampleRate
    private val n = records
    private val rec = AtomicLongArray(records * REC)
    private val anchor = AtomicLongArray(REC)
    private val head = AtomicLong(0L)                 // records published this session
    private val sessionA = AtomicInteger(0)
    private val clockMissA = AtomicInteger(0)
    @Volatile private var accepted = 0
    @Volatile private var rejected = 0
    /** Times the fit window was dropped after RESEED_AFTER consecutive rate rejections. */
    @Volatile var reseeds = 0; private set
    @Volatile private var latFrames = 0
    @Volatile private var fsFitV = sampleRate.toDouble()
    @Volatile private var driftP99 = 0f

    // Writer-private (HKAudio).
    private var wHead = 0L
    private var wNewestF = 0L
    private var wAnchored = false
    private var wFromTs = false
    private var wAnchorF = 0L
    private var wAnchorN = 0L
    private val fitF = LongArray(FIT); private val fitN = LongArray(FIT)
    private var fitCount = 0; private var fitNext = 0
    private var rateRejects = 0                    // consecutive implied-rate rejections (reseed at RESEED_AFTER)
    private val drift = FloatArray(DRIFT); private val driftScratch = FloatArray(DRIFT)
    private var driftCount = 0; private var driftNext = 0

    init { writeAnchor(0L, 0L, fsFitV, 0L) }

    /** Before every play() of a new, rebuilt or un-parked track (writer thread). */
    fun reset() {
        wHead = 0L; head.set(0L)
        wAnchored = false; wFromTs = false
        writeAnchor(0L, 0L, fsFitV, 0L)
        fitCount = 0; fitNext = 0; rateRejects = 0
        sessionA.incrementAndGet()
    }

    /** HKAudio, after each accepted write. */
    fun publishBlock(blockStartFrame: Long, st: CoreClockState) {
        val base = ((wHead % n).toInt()) * REC
        val m1 = (st.rate.toRawBits().toLong() and 0xFFFFFFFFL) or (if (st.playing) 1L shl 32 else 0L) or
            ((st.registration.toLong() and 0xFFL) shl 40)
        val m2 = (st.epoch.toLong() shl 32) or (st.generation.toLong() and 0xFFFFFFFFL)
        val v = rec.get(base)
        rec.set(base, v + 1)
        rec.set(base + 1, blockStartFrame); rec.set(base + 2, st.songUs); rec.set(base + 3, m1); rec.set(base + 4, m2)
        rec.set(base + 5, check(blockStartFrame, st.songUs, m1, m2))
        rec.set(base, v + 2)
        wHead++; wNewestF = blockStartFrame
        head.set(wHead)
    }

    /** HKAudio, every 16 blocks and while idle; false = rejected. */
    fun publishTimestamp(frame: Long, nanoTime: Long): Boolean {
        val fit = fsFitV
        if (wFromTs) {
            if (frame <= wAnchorF || nanoTime <= wAnchorN) { rejected++; return false }
            if (fitCount > 0) {
                val o = (fitNext - fitCount + FIT) % FIT
                val dn = (nanoTime - fitN[o]).toDouble()
                val implied = (frame - fitF[o]).toDouble() * 1e9 / dn
                if (dn <= 0.0 || implied < fit * (1 - RATE_TOL) || implied > fit * (1 + RATE_TOL)) {
                    // A bad pair in the window (typically the start-up timestamp) would otherwise
                    // reject every later, consistent one: after RESEED_AFTER in a row, drop the window.
                    if (++rateRejects < RESEED_AFTER) { rejected++; return false }
                    fitCount = 0; fitNext = 0; reseeds++
                }
            }
        }
        // Drift of this pair against the previous accepted one (§8.6).
        if (fitCount > 0) {
            val p = (fitNext - 1 + FIT) % FIT
            val e = frame - (fitF[p] + (nanoTime - fitN[p]) * fit / 1e9)
            drift[driftNext] = if (e < 0) (-e).toFloat() else e.toFloat()
            driftNext = (driftNext + 1) % DRIFT; if (driftCount < DRIFT) driftCount++
            driftP99 = p99()
        }
        fitF[fitNext] = frame; fitN[fitNext] = nanoTime
        fitNext = (fitNext + 1) % FIT; if (fitCount < FIT) fitCount++
        val newFit = leastSquares()
        if (newFit > 0.0) fsFitV = newFit
        if (wHead > 0) latFrames = (wNewestF + HK.BLOCK - frame).toInt()
        wAnchored = true; wFromTs = true; wAnchorF = frame; wAnchorN = nanoTime
        writeAnchor(frame, nanoTime, fsFitV, FLAG_ANCHORED or FLAG_TS)
        rateRejects = 0
        accepted++
        return true
    }

    /** Until the first accepted timestamp of a session. */
    fun publishEstimate(framesAccepted: Long, latencyFrames: Int, nanoTime: Long) {
        if (wFromTs) return
        wAnchored = true; wAnchorF = framesAccepted - latencyFrames; wAnchorN = nanoTime
        writeAnchor(wAnchorF, nanoTime, fsFitV, FLAG_ANCHORED)
    }

    override fun sample(nanoTime: Long, out: ClockSample) {
        var attempt = 0
        while (attempt < 4) {
            attempt++
            val s1 = sessionA.get()
            val h0 = head.get()
            if (h0 == 0L) {
                if (sessionA.get() != s1) continue
                out.session = s1; out.valid = false; out.playing = false; out.fromTimestamp = false
                return
            }
            // Anchor.
            val av = anchor.get(0)
            if (av and 1L != 0L) continue
            val aF = anchor.get(1); val aN = anchor.get(2); val aFs = anchor.get(3); val aFl = anchor.get(4); val aC = anchor.get(5)
            if (anchor.get(0) != av || aC != check(aF, aN, aFs, aFl)) continue
            // Newest record.
            val newestIdx = h0 - 1
            val nb = ((newestIdx % n).toInt()) * REC
            val nv = rec.get(nb)
            if (nv and 1L != 0L) continue
            val newestF = rec.get(nb + 1)
            if (rec.get(nb) != nv) continue
            var h = if (aFl and FLAG_ANCHORED != 0L) {
                aF + Math.floor((nanoTime - aN).toDouble() * Double.fromBits(aFs) / 1e9).toLong()
            } else newestF
            if (h > newestF + HK.BLOCK) h = newestF + HK.BLOCK
            // Walk back to the newest record with F ≤ H.
            val oldestIdx = if (h0 > n) h0 - n else 0L
            var idx = newestIdx
            var torn = false
            var found = false
            var fK = 0L; var sK = 0L; var m1 = 0L; var m2 = 0L
            while (idx >= oldestIdx) {
                val b = ((idx % n).toInt()) * REC
                val v = rec.get(b)
                if (v and 1L != 0L) { torn = true; break }
                fK = rec.get(b + 1); sK = rec.get(b + 2); m1 = rec.get(b + 3); m2 = rec.get(b + 4)
                val c = rec.get(b + 5)
                if (rec.get(b) != v || c != check(fK, sK, m1, m2)) { torn = true; break }
                if (fK <= h) { found = true; break }
                idx--
            }
            if (torn) continue
            if (!found) { idx = oldestIdx; h = fK }        // older than the oldest record: the oldest, at its start
            if (head.get() - n > idx) continue            // the chosen slot may have been overwritten meanwhile
            if (sessionA.get() != s1) continue
            if (!found) clockMissA.incrementAndGet()
            val rate = Float.fromBits((m1 and 0xFFFFFFFFL).toInt())
            val playing = (m1 ushr 32) and 1L != 0L
            out.songUs = if (playing) sK + Math.floor((h - fK).toDouble() * rate * 1e6 / fs).toLong() else sK
            out.rate = rate; out.playing = playing
            out.registration = ((m1 ushr 40) and 0xFFL).toInt()
            out.epoch = (m2 ushr 32).toInt(); out.generation = m2.toInt()
            out.session = s1; out.heardFrame = h; out.valid = true
            out.fromTimestamp = aFl and FLAG_TS != 0L
            return
        }
        // Retries exhausted: keep the previous sample.
    }

    fun stats(out: ClockStats) {
        out.tsAccepted = accepted; out.tsRejected = rejected; out.clockMiss = clockMissA.get(); out.latFrames = latFrames
        out.fsFit = fsFitV.toFloat(); out.driftP99Frames = driftP99; out.session = sessionA.get()
    }

    private fun writeAnchor(f: Long, nanos: Long, fsFit: Double, flags: Long) {
        val v = anchor.get(0)
        val fsBits = fsFit.toRawBits()
        anchor.set(0, v + 1)
        anchor.set(1, f); anchor.set(2, nanos); anchor.set(3, fsBits); anchor.set(4, flags)
        anchor.set(5, check(f, nanos, fsBits, flags))
        anchor.set(0, v + 2)
    }

    private fun leastSquares(): Double {
        if (fitCount < 2) return fsFitV
        val o = (fitNext - fitCount + FIT) % FIT
        val f0 = fitF[o]; val n0 = fitN[o]
        var sx = 0.0; var sy = 0.0
        for (i in 0 until fitCount) { val j = (o + i) % FIT; sx += (fitN[j] - n0).toDouble(); sy += (fitF[j] - f0).toDouble() }
        val mx = sx / fitCount; val my = sy / fitCount
        var sxx = 0.0; var sxy = 0.0
        for (i in 0 until fitCount) {
            val j = (o + i) % FIT
            val dx = (fitN[j] - n0).toDouble() - mx; val dy = (fitF[j] - f0).toDouble() - my
            sxx += dx * dx; sxy += dx * dy
        }
        if (sxx < MIN_SPAN_NS2) return fsFitV
        val r = sxy / sxx * 1e9
        return if (r > fs * 0.9 && r < fs * 1.1) r else fsFitV
    }

    private fun p99(): Float {
        val c = driftCount
        for (i in 0 until c) driftScratch[i] = drift[i]
        for (i in 1 until c) {                          // insertion sort, ≤ 128 values every 85 ms
            val x = driftScratch[i]; var j = i - 1
            while (j >= 0 && driftScratch[j] > x) { driftScratch[j + 1] = driftScratch[j]; j-- }
            driftScratch[j + 1] = x
        }
        val k = ((c * 99 + 99) / 100 - 1).coerceIn(0, c - 1)
        return driftScratch[k]
    }

    private companion object {
        const val REC = 6                                 // version, 4 payload longs, checksum
        const val FIT = 32
        const val DRIFT = 128
        const val RATE_TOL = 0.005
        const val RESEED_AFTER = 4
        const val MIN_SPAN_NS2 = 1e14                     // ≥ ~10 ms spread before the fit moves
        const val FLAG_ANCHORED = 1L; const val FLAG_TS = 2L
        fun check(a: Long, b: Long, c: Long, d: Long): Long =
            (a * -0x61c8864680b583ebL) xor (b * 0x5851F42D4C957F2DL) xor (c * 0x2545F4914F6CDD1DL) xor d xor 0x3C6EF372FE94F82BL
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
 * its exposure window (PLAN §2.5): hold (raw went back < 60 ms), follow (raw advanced by no more
 * than real time × rate), slew forward corrections at 10% per frame, or reseed (a session, epoch
 * or generation change, the first frame, raw < −60 ms or > +250 ms beyond the expectation).
 * Windows `(w_prev, w]` with `w = max(w_prev, t + span/2)`, `span = clamp(t − t_prev, 0, 100 ms)`
 * tile song time; they are empty after a reseed and while paused. Allocation-free, GLThread.
 */
class VisualClock {
    private var first = true
    private var session = 0; private var epoch = 0; private var generation = -1
    private var tPrev = 0L; private var wPrev = 0L; private var nPrev = 0L

    fun update(s: ClockSample, nowNanos: Long, out: VisTime) {
        val raw = s.songUs
        var reseed = first || !s.valid || s.session != session || s.epoch != epoch || s.generation != generation
        var t = raw
        if (!reseed) {
            val expected = if (s.playing) ((nowNanos - nPrev).coerceAtLeast(0L) / 1000.0 * s.rate).toLong() else 0L
            val d = raw - tPrev
            when {
                d < -RESEED_BACK_US || d - expected > RESEED_FWD_US -> reseed = true
                d < 0 -> t = tPrev
                d <= expected -> t = tPrev + d
                else -> t = tPrev + expected + (d - expected) / 10
            }
        }
        if (reseed) {
            out.exposeFromUs = raw; out.exposeToUs = raw
            wPrev = raw; t = raw
        } else if (!s.playing) {
            out.exposeFromUs = wPrev; out.exposeToUs = wPrev
        } else {
            val span = (t - tPrev).coerceIn(0L, MAX_SPAN_US)
            val w = maxOf(wPrev, t + span / 2)
            out.exposeFromUs = wPrev; out.exposeToUs = w
            wPrev = w
        }
        first = !s.valid
        session = s.session; epoch = s.epoch; generation = s.generation
        tPrev = t; nPrev = nowNanos
        out.reseed = reseed
        out.tUs = t; out.playing = s.playing; out.rate = s.rate; out.epoch = s.epoch; out.generation = s.generation
        out.registration = s.registration; out.heardFrame = s.heardFrame
    }

    private companion object {
        const val RESEED_BACK_US = 60_000L
        const val RESEED_FWD_US = 250_000L
        const val MAX_SPAN_US = 100_000L
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
