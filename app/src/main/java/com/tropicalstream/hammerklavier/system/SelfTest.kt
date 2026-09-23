package com.tropicalstream.hammerklavier.system

import android.content.Context
import android.os.Handler
import android.os.StatFs
import android.util.Log
import com.tropicalstream.hammerklavier.BuildConfig
import com.tropicalstream.hammerklavier.Wiring
import com.tropicalstream.hammerklavier.contract.AudioClock
import com.tropicalstream.hammerklavier.contract.AudioStats
import com.tropicalstream.hammerklavier.contract.ClockSample
import com.tropicalstream.hammerklavier.contract.ClockStats
import com.tropicalstream.hammerklavier.contract.CoreClockState
import com.tropicalstream.hammerklavier.contract.EnergyRing
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.RenderControl
import com.tropicalstream.hammerklavier.platform.DeviceInfo
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `--ez selftest true` (PLAN §8.3): `HKSelfTest version=… branch=… commit=…`, then one
 * `PASS|FAIL|SKIP <item> <detail>` line per check, ending with `HKSelfTest done pass=… fail=…`.
 * Items a stub cannot answer yet are SKIP (never FAIL); each WP's merge turns its items into real
 * checks. The torn-read test runs a writer thread publishing AudioClock and EnergyRing records
 * with checksum fields at 10 kHz and a reader thread sampling both for [tornSeconds] (60 s by
 * default), then reports mismatches. Runs off the main thread except the final report.
 */
class SelfTest(private val ctx: Context, private val w: Wiring, private val main: Handler) {
    private val running = AtomicBoolean(false)

    fun run(gl: RenderControl?, tornSeconds: Int = 60) {
        if (!running.compareAndSet(false, true)) { Log.i(HK.TAG_SELFTEST, "already running"); return }
        Thread({
            try { body(gl, tornSeconds) } catch (t: Throwable) { line("FAIL", "selftest", t.toString()) }
            finally { running.set(false) }
        }, "HKSelfTest").apply { isDaemon = true }.start()
    }

    private var pass = 0; private var fail = 0; private var skip = 0

    private fun line(result: String, item: String, detail: String) {
        when (result) { "PASS" -> pass++; "FAIL" -> fail++; else -> skip++ }
        Log.i(HK.TAG_SELFTEST, "$result $item $detail")
    }

    private fun body(gl: RenderControl?, tornSeconds: Int) {
        pass = 0; fail = 0; skip = 0
        Log.i(HK.TAG_SELFTEST, "version=${BuildConfig.VERSION_NAME} branch=${BuildConfig.GIT_BRANCH} commit=${BuildConfig.GIT_COMMIT} " +
            "device=${DeviceInfo.summary(ctx)}")
        for (id in InstrumentId.entries) {
            val st = runCatching { w.kits.state(id) }.getOrNull()
            val info = runCatching { w.kits.info(id) }.getOrNull()
            if (info == null) line("SKIP", "kit.$id", "no map.json yet (state=$st)") else line("PASS", "kit.$id", "map.json ok state=$st")
        }
        line("SKIP", "decoderProbe", "WP4 not merged")
        runCatching { w.library.load() }.fold(
            { m -> line("PASS", "catalogue", "${m.shelves.size} shelves") },
            { e -> line("FAIL", "catalogue", e.toString()) })
        val a = AudioStats(); val cs = ClockStats()
        runCatching { w.audio.stats(a); w.audio.clockStats(cs) }
        val r = w.audio.route
        val ad = runCatching { w.audio.diagnostics() }.getOrDefault(emptyMap())
        val audioLine = "route=${r.route} device=${r.deviceType}:${r.name} out=${r.outputFlags} buffer=${a.bufferFrames} underruns=${a.underruns} " +
            "fast=${a.fastTrack} clockSession=${cs.session} lat=${cs.latFrames} tsAcc=${cs.tsAccepted} ${ad.entries.joinToString(" ") { "${it.key}=${it.value}" }}"
        if (ad["host"] == null && a.bufferFrames == 0) line("SKIP", "audiotrack", "stub audio: $audioLine") else line("PASS", "audiotrack", audioLine)
        line("SKIP", "companion", "WP9 not merged")
        val ext = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val free = runCatching { StatFs(ext.path).availableBytes }.getOrDefault(-1L)
        line(if (free > 200L shl 20) "PASS" else "FAIL", "freeSpace", "${free shr 20} MiB at ${ext.path}")
        val crash = File(ctx.filesDir, "crash.txt")
        line(if (crash.exists()) "FAIL" else "PASS", "crash.txt", if (crash.exists()) "present" else "absent")
        val scores = w.library.scoresDir
        line(if (!scores.exists() || (scores.isDirectory && scores.canRead())) "PASS" else "FAIL", "scores", "${scores.path} exists=${scores.exists()}")
        tornRead(tornSeconds)
        val glDiag = runCatching { gl?.diagnostics() }.getOrNull()                // after the torn test: the surface exists by now
        if (glDiag == null || glDiag.isEmpty()) line("SKIP", "gl", "no GL host attached")
        else line(if ((glDiag["gl"] ?: "").contains("renderer=")) "PASS" else "FAIL", "gl", glDiag.entries.joinToString(" ") { "${it.key}=${it.value}" })
        val summary = "done pass=$pass fail=$fail skip=$skip"
        main.post { Log.i(HK.TAG_SELFTEST, summary) }
    }

    /** §8.3: a 10 kHz writer of clock and energy records with checksum fields against a reader, 0 mismatches. */
    private fun tornRead(seconds: Int) {
        val clock = AudioClock()
        val energy = EnergyRing()
        val stop = AtomicBoolean(false)
        val writer = Thread({
            val st = CoreClockState(); val lanes = FloatArray(HK.LANES)
            var k = 0L
            val t0 = System.nanoTime()
            clock.publishTimestamp(0L, t0)
            while (!stop.get()) {
                val f = k * HK.BLOCK
                st.songUs = f * 3 + 7; st.playing = false; st.epoch = (k % 1_000_003).toInt(); st.generation = (k and 0x7FFFFFFF).toInt()
                clock.publishBlock(f, st)
                for (i in 0 until HK.LANES - 1) lanes[i] = (k % 4099).toFloat() + i
                lanes[HK.LANES - 1] = (k % 4099).toFloat() * 88 + 3828          // checksum lane
                energy.write(f, 0, lanes)
                k++
                val due = t0 + k * 100_000L                                       // 10 kHz
                while (System.nanoTime() < due && !stop.get()) Thread.yield()
            }
        }, "HKSelfTestW").apply { priority = Thread.MAX_PRIORITY; isDaemon = true }
        writer.start()
        val s = ClockSample(); val out = FloatArray(HK.LANES)
        var reads = 0L; var torn = 0L
        val end = System.nanoTime() + seconds * 1_000_000_000L
        while (System.nanoTime() < end) {
            clock.sample(Long.MAX_VALUE / 4, s)
            if (s.valid) {
                val kk = (s.songUs - 7) / 3 / HK.BLOCK
                if (s.songUs != kk * HK.BLOCK * 3 + 7 || s.epoch != (kk % 1_000_003).toInt() || s.generation != (kk and 0x7FFFFFFF).toInt()) torn++
            }
            if (energy.read(Long.MAX_VALUE / 4, 0, out)) {
                val base = out[0]
                var bad = false
                for (i in 1 until HK.LANES - 1) if (out[i] != base + i) { bad = true; break }
                if (out[HK.LANES - 1] != base * 88 + 3828) bad = true
                if (bad) torn++
            }
            reads++
        }
        stop.set(true); writer.join(1000)
        line(if (torn == 0L && reads > 0) "PASS" else "FAIL", "tornRead", "reads=$reads mismatches=$torn seconds=$seconds")
    }
}
