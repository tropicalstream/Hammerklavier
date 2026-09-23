package com.tropicalstream.hammerklavier.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRouting
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Handler
import com.tropicalstream.hammerklavier.contract.HK

/**
 * The output device seen by the HKAudio loop (PLAN §3.1). [AudioTrackSink] is the device; tests
 * use an in-memory sink. Every method except [release] and [routedDevice] runs on HKAudio.
 */
interface OutputSink {
    /** Frames written (stereo float, [frames] frames from [off] floats), or a negative AudioTrack error. */
    fun write(buf: FloatArray, off: Int, frames: Int): Int
    fun play(); fun pause(); fun release()
    /** false = no timestamp; else [out] [0] = frame position, [1] = nanoTime. */
    fun timestamp(out: LongArray): Boolean
    fun playbackHeadPosition(): Long
    fun underrunCount(): Int
    val bufferFrames: Int
    val fast: Boolean
    fun setBufferFrames(frames: Int): Int = bufferFrames
    fun routedDevice(): AudioDeviceInfo? = null
    fun addRoutingListener(onChange: () -> Unit, handler: Handler?) {}
}

/** Builds sinks; attempt 0 is the normal path, later attempts may use the fallback path. */
fun interface SinkFactory { fun create(lowLatency: Boolean): OutputSink }

/** The §3.1 AudioTrack: float stereo 48 kHz, 4096-frame request, PERFORMANCE_MODE_NONE. */
class AudioTrackSink(lowLatency: Boolean) : OutputSink {
    private val track: AudioTrack
    private val ts = AudioTimestamp()
    override val fast: Boolean

    init {
        val minBytes = AudioTrack.getMinBufferSize(HK.SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(HK.SR)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBytes, HK.TRACK_FRAMES * 2 * 4))
            .setPerformanceMode(if (lowLatency) AudioTrack.PERFORMANCE_MODE_LOW_LATENCY else AudioTrack.PERFORMANCE_MODE_NONE)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) { track.release(); throw IllegalStateException("AudioTrack not initialised") }
        fast = lowLatency && track.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
    }

    override fun write(buf: FloatArray, off: Int, frames: Int): Int {
        val n = track.write(buf, off, frames * 2, AudioTrack.WRITE_BLOCKING)
        return if (n < 0) n else n / 2
    }
    override fun play() = track.play()
    override fun pause() = track.pause()
    override fun release() { runCatching { track.stop() }; track.release() }
    override fun timestamp(out: LongArray): Boolean {
        if (!track.getTimestamp(ts)) return false
        out[0] = ts.framePosition; out[1] = ts.nanoTime
        return true
    }
    override fun playbackHeadPosition(): Long = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
    override fun underrunCount(): Int = track.underrunCount
    override val bufferFrames: Int get() = track.bufferSizeInFrames
    override fun setBufferFrames(frames: Int): Int = track.setBufferSizeInFrames(frames)
    override fun routedDevice(): AudioDeviceInfo? = track.routedDevice
    override fun addRoutingListener(onChange: () -> Unit, handler: Handler?) {
        track.addOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener { onChange() }, handler)
    }
}

/**
 * Output-error policy (PLAN §3.1 `TrackSupervisor`): a `write` < 0 (ERROR_DEAD_OBJECT,
 * ERROR_INVALID_OPERATION) or 16 consecutive failed timestamps while playing → rebuild the track,
 * at most 3 times within 10 s; the next failure stops audio ("output lost"). Pure; HKAudio.
 */
class TrackSupervisor(private val maxRebuilds: Int = 3, private val windowNs: Long = 10_000_000_000L) {
    private val times = LongArray(maxRebuilds)
    private var n = 0
    private var failedTs = 0
    var rebuilds = 0; private set

    /** A timestamp call result while playing; true = the track must be rebuilt. */
    fun onTimestamp(ok: Boolean, playing: Boolean): Boolean {
        if (ok || !playing) { failedTs = 0; return false }
        failedTs++
        return failedTs >= DEAD_TIMESTAMPS
    }

    /** A rebuild is needed at [nowNs]; true = allowed, false = give up ("output lost"). */
    fun allowRebuild(nowNs: Long): Boolean {
        var recent = 0
        for (i in 0 until n) if (nowNs - times[i] < windowNs) recent++
        if (recent >= maxRebuilds) return false
        // Replace the oldest entry.
        if (n < maxRebuilds) times[n++] = nowNs else {
            var o = 0; for (i in 1 until n) if (times[i] < times[o]) o = i
            times[o] = nowNs
        }
        failedTs = 0
        rebuilds++
        return true
    }

    companion object {
        const val DEAD_TIMESTAMPS = 16
        const val ERROR_INVALID_OPERATION = -3
        const val ERROR_DEAD_OBJECT = -6
    }
}

/**
 * Render-exception policy (PLAN §3.1): the first exception → a silent block and one `reset()`;
 * a second within 10 s → stop audio. Pure; HKAudio.
 */
class RenderGuard(private val windowNs: Long = 10_000_000_000L) {
    private var lastNs = Long.MIN_VALUE
    /** true = recover (silence + reset), false = stop. */
    fun onException(nowNs: Long): Boolean {
        val recover = lastNs == Long.MIN_VALUE || nowNs - lastNs >= windowNs
        lastNs = nowNs
        return recover
    }
}

/**
 * Headroom self-protection (PLAN §3.1): the queued frames' minimum over a rolling 10 s; below
 * [HK.HEADROOM_MIN_FRAMES] → one step down. The first [COMB_STEPS] steps shed combs (88 → 44 → 22,
 * dispersion off: on the glasses the combs cost ≈ 5× a voice-cap step, M2 §3.16 measured order), then cap − 8, not below 32; after 60 s with the minimum
 * ≥ 3,072 frames → one step back up. Time is counted in output frames. Pure; HKAudio.
 */
class HeadroomGuard(private val sampleRate: Int = HK.SR) {
    private val window = 10L * sampleRate
    private val restore = 60L * sampleRate
    private var winStart = -1L
    private var winMin = Int.MAX_VALUE
    private var goodSince = -1L
    private var lastUrgent = Long.MIN_VALUE / 2
    /** Steps of −8 currently applied. */
    var steps = 0; private set
    /** The minimum of the last complete window (stats). */
    var lastWindowMin = 0; private set

    fun reset() { lastUrgent = Long.MIN_VALUE / 2; winStart = -1L; winMin = Int.MAX_VALUE; goodSince = -1L }

    /**
     * One wake: [queued] frames at output frame [frame]. Returns +1 when a step down was taken,
     * −1 for a step back up, 0 otherwise.
     */
    fun sample(frame: Long, queued: Int, baseCap: Int, underrun: Boolean = false): Int {
        if (winStart < 0) { winStart = frame; winMin = queued; goodSince = frame; return 0 }
        if (queued < winMin) winMin = queued
        // M8: a real underrun steps down at once (at most one step per second) instead of waiting for the
        // 10 s window: storm64 on the grand at cap 96 underran for ~80 s (8,397 underruns) before four
        // window steps reached cap 40.
        if (underrun && frame - lastUrgent >= sampleRate) {
            lastUrgent = frame; goodSince = frame; winStart = frame; winMin = Int.MAX_VALUE; lastWindowMin = 0
            // Both comb steps at once plus one cap step: shedding combs alone did not stop storm64's underruns.
            if (cap(baseCap) > MIN_CAP) { steps = maxOf(steps, COMB_STEPS) + 1; return 1 }
            if (steps < COMB_STEPS) { steps = COMB_STEPS; return 1 }
            return 0
        }
        if (frame - winStart < window) return 0
        lastWindowMin = winMin
        val min = winMin
        winStart = frame; winMin = Int.MAX_VALUE
        if (min < HK.HEADROOM_MIN_FRAMES) {
            goodSince = frame
            if (steps < COMB_STEPS || cap(baseCap) > MIN_CAP) { steps++; return 1 }
            return 0
        }
        if (min < 2 * HK.HEADROOM_MIN_FRAMES) goodSince = frame
        if (steps > 0 && frame - goodSince >= restore) { steps--; goodSince = frame; return -1 }
        return 0
    }

    fun cap(baseCap: Int): Int = maxOf(MIN_CAP, baseCap - 8 * maxOf(0, steps - COMB_STEPS))

    /** Comb protection steps in effect (0..[COMB_STEPS]). */
    val combSteps: Int get() = minOf(steps, COMB_STEPS)

    companion object { const val MIN_CAP = 32; const val COMB_STEPS = 2 }
}
