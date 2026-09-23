package com.tropicalstream.hammerklavier.system

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.ViewId
import java.io.File

/**
 * The in-app soak recorder (PLAN §8.5): `--ez soak true` writes one CSV row every 10 s to
 * `getExternalFilesDir(null)/soak.csv` (made world-readable, mode 0644), and `--es soakplan`
 * runs a named plan from its own timer, so an unplugged soak needs no adb. Lives as long as the
 * engine. Main thread.
 *
 * Plans (minute → step): `therm45` op. 106 for 35 min, then the Moonlight, the last 5 min
 * `synth:storm64` in a loop, the view rotating Player → Action cutaway → Action overhead → Hall
 * every 5 min; `bright` the same at brightness 1.0; `rest10` Q3 forced for 10 min with the music
 * playing; `sleep20` the WTC I fugues as a playlist (the user presses the sleep button).
 */
class SoakRecorder(private val ctx: Context, private val main: Handler, private val source: Source) {
    /** What a row needs and what a plan does; AppController implements it. */
    interface Source {
        fun batteryTenths(): Int; fun thermalStatus(): Int; fun quality(): Int
        fun fps(): Float; fun lateFrames(): Int; fun underruns(): Int; fun headroomMin(): Int; fun audioTid(): Int
        fun brightness(): Float; fun movement(): String; fun positionMs(): Long
        fun play(id: String); fun setView(v: ViewId, framing: Int); fun forceQuality(q: Int); fun setBrightness(b: Float)
    }

    private class Step(val atMin: Int, val run: (Source) -> Unit)

    var recording = false; private set
    private var plan: List<Step> = emptyList()
    private var planName = ""
    private var nextStep = 0
    private var startMs = 0L
    private var file: File? = null
    private val perf = PerfProbe(main)

    private val tick = object : Runnable {
        override fun run() {
            if (!recording) return
            val min = ((SystemClock.elapsedRealtime() - startMs) / 60_000).toInt()
            while (nextStep < plan.size && plan[nextStep].atMin <= min) {
                runCatching { plan[nextStep].run(source) }.onFailure { Log.w(HK.TAG_SOAK, "step failed: $it") }
                nextStep++
            }
            row()
            main.postDelayed(this, PERIOD_MS)
        }
    }

    fun start(planName: String?) {
        stop()
        this.planName = planName ?: ""
        plan = plans(this.planName)
        nextStep = 0
        startMs = SystemClock.elapsedRealtime()
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val f = File(dir, "soak.csv")
        runCatching {
            f.writeText(HEADER + "\n")
            f.setReadable(true, false)
        }.onFailure { Log.w(HK.TAG_SOAK, "cannot write $f: $it"); return }
        file = f
        recording = true
        Log.i(HK.TAG_SOAK, "start plan=${this.planName.ifEmpty { "none" }} file=${f.path}")
        main.post(tick)
    }

    fun stop() {
        if (!recording) return
        recording = false
        main.removeCallbacks(tick)
        Log.i(HK.TAG_SOAK, "stop after ${(SystemClock.elapsedRealtime() - startMs) / 1000} s")
    }

    private fun row() {
        val f = file ?: return
        val s = source
        val cpu = threadCpuMs()
        val t = s.batteryTenths()
        val r = listOf(
            (SystemClock.elapsedRealtime() - startMs) / 1000, "${t / 10}.${Math.abs(t % 10)}", s.thermalStatus(), s.quality(),
            cpu["HKAudio"] ?: 0, cpu["GLThread"] ?: 0, cpu["HKPrefetch"] ?: 0, cpu["HKVoicer"] ?: 0, cpu["main"] ?: 0,
            "%.1f".format(s.fps()), s.lateFrames(), s.underruns(), s.headroomMin(),
            s.audioTid().let { if (it > 0) perf.majflt(it) else -1 }, "%.2f".format(s.brightness()),
            s.movement().replace(',', ';'), s.positionMs(), planName)
        runCatching { f.appendText(r.joinToString(",") + "\n") }
        Log.i(HK.TAG_SOAK, r.joinToString(","))
    }

    /** utime + stime in ms per thread name (GLThread matches "GLThread <n>"); main = the process's main thread. */
    private fun threadCpuMs(): Map<String, Long> {
        val out = HashMap<String, Long>()
        val tick = 10L                                             // USER_HZ = 100 on Android
        val pid = android.os.Process.myPid()
        File("/proc/self/task").listFiles()?.forEach { task ->
            runCatching {
                val stat = File(task, "stat").readText()
                val name = stat.substring(stat.indexOf('(') + 1, stat.lastIndexOf(')'))
                val rest = stat.substring(stat.lastIndexOf(')') + 2).split(' ')
                val ms = (rest[14 - 3].toLong() + rest[15 - 3].toLong()) * tick
                val key = when {
                    task.name == pid.toString() -> "main"
                    name.startsWith("GLThread") -> "GLThread"
                    else -> name
                }
                out[key] = (out[key] ?: 0L) + ms
            }
        }
        return out
    }

    private fun plans(name: String): List<Step> {
        val rotate = (0 until 9).map { i ->
            val (v, fr) = when (i % 4) { 0 -> ViewId.PLAYER to 0; 1 -> ViewId.ACTION to 0; 2 -> ViewId.ACTION to 1; else -> ViewId.HALL to 0 }
            Step(i * 5) { s -> s.setView(v, fr) }
        }
        val therm = listOf(Step(0) { it.play("beethoven.op106.1") }, Step(35) { it.play("beethoven.op27-2.1") },
            Step(40) { it.play("synth:storm64") })
        return when (name) {
            "therm45" -> (therm + rotate).sortedBy { it.atMin }
            "bright" -> (listOf(Step(0) { it.setBrightness(1f) }) + therm + rotate).sortedBy { it.atMin }
            "rest10" -> listOf(Step(0) { it.forceQuality(3); it.play("beethoven.op106.1") }, Step(10) { it.forceQuality(-1) })
            "sleep20" -> listOf(Step(0) { it.play("bach.wtc1.sankey") })
            else -> emptyList()
        }
    }

    private companion object {
        const val PERIOD_MS = 10_000L
        const val HEADER = "t_s,battery_c,thermal_status,q,cpu_ms_hkaudio,cpu_ms_gl,cpu_ms_prefetch,cpu_ms_voicer,cpu_ms_main," +
            "fps,late_frames,underruns,headroom_min,majflt,brightness,movement,position_ms,plan"
    }
}
