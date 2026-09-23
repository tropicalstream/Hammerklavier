package com.tropicalstream.hammerklavier.audio

import com.tropicalstream.hammerklavier.contract.HK

/**
 * The LOW_LATENCY fallback's buffer growth (PLAN §3.1): enabled only when the track was built with
 * `PERFORMANCE_MODE_LOW_LATENCY`; grows `bufferSizeInFrames` by one block per new underrun, up to
 * 16 blocks. Pure; HKAudio calls [onUnderruns] with every `getUnderrunCount` it reads.
 */
class LatencyTuner(private val maxBlocks: Int = 16) {
    private var lastUnderruns = -1

    /** Returns the new buffer size in frames, or -1 for no change. */
    fun onUnderruns(count: Int, currentFrames: Int): Int {
        val prev = lastUnderruns
        lastUnderruns = count
        if (prev < 0 || count <= prev) return -1
        val max = maxBlocks * HK.BLOCK
        if (currentFrames >= max) return -1
        return minOf(max, currentFrames + HK.BLOCK)
    }

    fun reset() { lastUnderruns = -1 }
}
