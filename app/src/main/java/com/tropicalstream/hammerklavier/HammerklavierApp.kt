package com.tropicalstream.hammerklavier

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import com.tropicalstream.hammerklavier.contract.HK
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The Application: owns the engine as process singletons (PLAN §1.10, §2.2), so a configuration
 * change or a second activity never builds a second engine; MainActivity only attaches views and
 * input. Creates the HKLoader and HKVoicer single-thread executors once, the [Wiring] (every
 * component, real or stub) and the [AppController]. An uncaught exception is written to
 * `files/crash.txt` before the default handler runs; its first line is shown on the next launch.
 */
class HammerklavierApp : Application() {
    lateinit var wiring: Wiring; private set
    lateinit var controller: AppController; private set

    /** The first line of the previous session's crash.txt (then renamed crash.prev.txt), or null. */
    var lastCrash: String? = null; private set

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        lastCrash = readLastCrash()
        val loader = backgroundExecutor("HKLoader")
        val voicer = backgroundExecutor("HKVoicer")
        wiring = Wiring(this, loader, voicer, Handler(Looper.getMainLooper()))
        controller = AppController(this, wiring)
        controller.startEngine()                                    // the engine services outlive the activity (§1.10)
        Log.i(HK.TAG_LOADER, "Hammerklavier ${BuildConfig.VERSION_NAME} branch=${BuildConfig.GIT_BRANCH} commit=${BuildConfig.GIT_COMMIT}")
    }

    private fun backgroundExecutor(name: String): ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, name).apply { isDaemon = true }
    }

    private fun crashFile() = File(filesDir, "crash.txt")

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                crashFile().writeText("${e.javaClass.simpleName}: ${e.message} (thread ${t.name}, commit ${BuildConfig.GIT_COMMIT})\n$sw")
            }
            previous?.uncaughtException(t, e)
        }
    }

    private fun readLastCrash(): String? = runCatching {
        val f = crashFile()
        if (!f.isFile) null else f.useLines { it.firstOrNull() }.also { f.renameTo(File(filesDir, "crash.prev.txt")) }
    }.getOrNull()
}
