package com.tropicalstream.hammerklavier.system

import android.os.Handler
import android.util.Log
import com.tropicalstream.hammerklavier.contract.HK
import java.io.File

/**
 * Performance probe (PLAN §2.2, §8.4), main thread:
 * - a frame-hitch detector: the main thread blocked > 120 ms (a late heartbeat) while [resumed] logs
 *   `HKPerf FRAME HITCH <ms>` (smoke fails on it);
 * - `majflt` of one task (the HKAudio tid from [audioTid]) from `/proc/self/task/<tid>/stat` field 12;
 * - `scaling_cur_freq` per CPU and `time_in_state` deltas per policy;
 * - the 10 s `HKPerf` line (plus [extra], e.g. audio stats).
 */
class PerfProbe(private val main: Handler, private val audioTid: () -> Int = { -1 }, private val extra: () -> String = { "" }) {
    var hitches = 0; private set
    private var running = false
    private var resumed = false
    private var lastFrameNs = 0L
    private var lastMajflt = -1L
    private val lastTis = HashMap<String, LongArray>()

    /**
     * Main-thread stall detector: a [BEAT_MS] Handler heartbeat; a beat arriving more than
     * HITCH_MS late logs `FRAME HITCH <gap>ms`. Not a Choreographer frame callback: every vsync a
     * Choreographer delivers runs Qualcomm's BoostFramework.ScrollOptimizer.setVsyncTime, which
     * allocates ~1.5 KB per vsync (~92 KB/s, T-GC failed at M1).
     */
    private val frame = object : Runnable {
        override fun run() {
            if (!resumed) return
            val t = System.nanoTime()
            if (lastFrameNs != 0L) {
                val gapMs = (t - lastFrameNs) / 1_000_000
                if (gapMs - BEAT_MS > HITCH_MS) { hitches++; Log.w(HK.TAG_PERF, "FRAME HITCH ${gapMs - BEAT_MS}ms") }
            }
            lastFrameNs = t
            main.postDelayed(this, BEAT_MS)
        }
    }
    private val tick = object : Runnable {
        override fun run() { if (!running) return; Log.i(HK.TAG_PERF, line()); main.postDelayed(this, PERIOD_MS) }
    }

    fun start() { if (running) return; running = true; main.postDelayed(tick, PERIOD_MS) }
    fun stop() { running = false; main.removeCallbacks(tick); setResumed(false) }

    /** Hitch detection only while the activity is resumed (display rest and sleep are not hitches). */
    fun setResumed(r: Boolean) {
        if (r == resumed) return
        resumed = r; lastFrameNs = 0L
        main.removeCallbacks(frame)
        if (r) main.post(frame)
    }

    /** One HKPerf line: majflt (total, delta), CPU frequencies (MHz), time_in_state deltas (top 3 per policy). */
    fun line(): String {
        val sb = StringBuilder("hitches=").append(hitches)
        val tid = audioTid()
        if (tid > 0) {
            val m = majflt(tid)
            sb.append(" majflt=").append(m)
            if (lastMajflt >= 0 && m >= 0) sb.append(" dMajflt=").append(m - lastMajflt)
            lastMajflt = m
        }
        sb.append(" freqMHz=").append(curFreqs().joinToString("/"))
        for ((policy, delta) in timeInStateDeltas()) sb.append(' ').append(policy).append('=').append(delta)
        val e = extra()
        if (e.isNotEmpty()) sb.append(' ').append(e)
        return sb.toString()
    }

    /** Major faults of a task of this process (stat field 12), or −1. */
    fun majflt(tid: Int): Long = runCatching {
        val stat = File("/proc/self/task/$tid/stat").readText()
        val rest = stat.substring(stat.lastIndexOf(')') + 2).split(' ')    // field 3 onwards
        rest[12 - 3].toLong()
    }.getOrDefault(-1L)

    private fun curFreqs(): List<Int> = (0 until 8).mapNotNull { c ->
        runCatching { File("/sys/devices/system/cpu/cpu$c/cpufreq/scaling_cur_freq").readText().trim().toInt() / 1000 }.getOrNull()
    }

    /** Per cpufreq policy: the three busiest frequencies since the last call, "kHz:10ms,…". */
    private fun timeInStateDeltas(): List<Pair<String, String>> {
        val dir = File("/sys/devices/system/cpu/cpufreq")
        val policies = dir.listFiles { f -> f.name.startsWith("policy") }?.sortedBy { it.name } ?: return emptyList()
        return policies.mapNotNull { p ->
            val rows = runCatching { File(p, "stats/time_in_state").readLines() }.getOrNull() ?: return@mapNotNull null
            val freqs = LongArray(rows.size); val times = LongArray(rows.size)
            rows.forEachIndexed { i, r -> val a = r.trim().split(' '); if (a.size >= 2) { freqs[i] = a[0].toLong(); times[i] = a[1].toLong() } }
            val prev = lastTis[p.name]; lastTis[p.name] = times
            if (prev == null || prev.size != times.size) return@mapNotNull null
            val top = times.indices.sortedByDescending { times[it] - prev[it] }.take(3).filter { times[it] > prev[it] }
            p.name to top.joinToString(",") { "${freqs[it] / 1000}:${times[it] - prev[it]}" }
        }
    }

    private companion object { const val HITCH_MS = 120L; const val BEAT_MS = 50L; const val PERIOD_MS = 10_000L }
}
