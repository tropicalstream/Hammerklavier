package com.tropicalstream.hammerklavier.audio

import android.os.Process
import com.tropicalstream.hammerklavier.contract.ClockSample
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.KeyMap
import com.tropicalstream.hammerklavier.contract.LoadedBank
import com.tropicalstream.hammerklavier.contract.Performance
import com.tropicalstream.hammerklavier.contract.SampleReader
import com.tropicalstream.hammerklavier.contract.SongClock
import com.tropicalstream.hammerklavier.contract.VoiceCursorBoard

/**
 * HKPrefetch (PLAN §3.4): while playing, keeps the page cache ahead of the audio thread with
 * positional reads (`SampleReader.prefetch`), never touching the mapping. Each wake it reads the
 * next 300 ms of every active voice (from the [VoiceCursorBoard]) and, for every note whose onset
 * enters the next 1.6 s of song time, asks the same [KeyMap] the audio thread uses which regions
 * will sound and reads their first 400 ms plus the first 200 ms of the release. After a seek (or a
 * new performance or bank) the heads of the next 1.6 s are read at once. Parked while nothing plays.
 */
class Prefetcher(private val cursors: VoiceCursorBoard, private val clock: SongClock) {
    @Volatile var bank: LoadedBank? = null
    @Volatile var keyMap: KeyMap? = null
    @Volatile var perf: Performance? = null
    /** Generation of the bank the prefetcher reads now (KitService.release waits for it). */
    @Volatile var bankGeneration = -1; private set
    @Volatile var wakes = 0; private set

    private val lock = Object()
    @Volatile private var running = false
    @Volatile private var kick = false
    private var thread: Thread? = null

    // HKPrefetch-confined.
    private var reader: SampleReader? = null
    private var readerBank: LoadedBank? = null
    private val sample = ClockSample()
    private var coveredUs = -1L
    private var lastEpoch = Int.MIN_VALUE
    private var lastGen = Int.MIN_VALUE
    private var lastPerf: Performance? = null
    private var lastMap: KeyMap? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(null, { loop() }, "HKPrefetch").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        wake()
        thread?.join(200)
        thread = null
    }

    /** Main: something changed (play, seek, new bank / performance). */
    fun wake() { synchronized(lock) { kick = true; lock.notifyAll() } }

    private fun loop() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
        while (running) {
            try { step() } catch (t: Throwable) { /* a failed prefetch only costs a page fault later */ }
            val playing = sample.playing
            synchronized(lock) {
                if (!kick && running) lock.wait(if (playing) WAKE_MS else PARK_MS)
                kick = false
            }
        }
    }

    /** One prefetch pass (HKPrefetch; also callable from a test thread). */
    fun step() {
        wakes++
        val b = bank ?: return
        if (b !== readerBank) { reader = b.newReader(); readerBank = b; coveredUs = -1L; bankGeneration = b.generation }
        val rd = reader ?: return
        clock.sample(System.nanoTime(), sample)
        if (!sample.valid || !sample.playing) { coveredUs = -1L; return }
        val p = perf; val m = keyMap
        if (sample.epoch != lastEpoch || sample.generation != lastGen || p !== lastPerf || m !== lastMap) {
            lastEpoch = sample.epoch; lastGen = sample.generation; lastPerf = p; lastMap = m; coveredUs = -1L
        }
        // Active voices: the next 300 ms of each.
        for (slot in 0 until cursors.size) {
            val c = cursors.get(slot)
            if (c == -1L) continue
            val region = (c ushr 32).toInt(); val frame = c.toInt()
            if (region in 0 until b.regionCount) rd.prefetch(region, frame, VOICE_AHEAD)
        }
        if (p == null || m == null || p.generation != sample.generation) return
        val s = sample.songUs
        val from = if (coveredUs < s) s else coveredUs
        val to = s + NOTES_AHEAD_US
        if (to <= from) return
        var i = firstNoteAtOrAfter(p, from)
        while (i < p.onUs.size && p.onUs[i] <= to) {
            heads(rd, b, m, p.key[i].toInt(), p.vel[i].toInt())
            i++
        }
        coveredUs = to
    }

    private fun heads(rd: SampleReader, b: LoadedBank, m: KeyMap, key: Int, vel: Int) {
        if (key !in 0 until HK.KEYS) return
        val v = vel.coerceIn(0, 127)
        for (stop in 0 until m.stops) {
            val la = m.velLayerA[v].toInt(); val lb = m.velLayerB[v].toInt()
            if (la >= 0) head(rd, b, m.region[(stop * m.layers + la) * HK.KEYS + key], HEAD)
            if (lb >= 0) head(rd, b, m.region[(stop * m.layers + lb) * HK.KEYS + key], HEAD)
            if (stop * HK.KEYS + key < m.release.size) head(rd, b, m.release[stop * HK.KEYS + key], RELEASE_HEAD)
        }
    }

    private fun head(rd: SampleReader, b: LoadedBank, region: Int, frames: Int) {
        if (region in 0 until b.regionCount) rd.prefetch(region, 0, frames)
    }

    private fun firstNoteAtOrAfter(p: Performance, us: Long): Int {
        var lo = 0; var hi = p.onUs.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (p.onUs[mid] <= us) lo = mid + 1 else hi = mid }
        return lo
    }

    companion object {
        const val VOICE_AHEAD = HK.SR * 300 / 1000        // 300 ms
        const val HEAD = HK.SR * 400 / 1000               // 400 ms
        const val RELEASE_HEAD = HK.SR * 200 / 1000       // 200 ms
        const val NOTES_AHEAD_US = 1_600_000L
        const val WAKE_MS = 100L                          // lookahead 1.6 s − 0.1 s ≥ 1.5 s
        const val PARK_MS = 1_000L
    }
}
