package com.tropicalstream.hammerklavier.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Log
import com.tropicalstream.hammerklavier.contract.HK

/**
 * The CONTROL receiver (PLAN §8.2): `am broadcast -a com.tropicalstream.hammerklavier.CONTROL …`.
 * Registered with the permission `android.permission.DUMP`, which only the shell holds, so another
 * app's broadcast never reaches it (plus `RECEIVER_EXPORTED` on API 33+). Lives as long as the
 * engine ([start]/[stop]), so `faketemp`, `rescan` and `soak` work with the display off. Every
 * broadcast is echoed as `HKUi CONTROL k=v …` and handed to [onCommand] on main.
 */
class DebugControl(private val ctx: Context, private val main: Handler, private val onCommand: (Bundle) -> Unit) {
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val extras = intent?.extras ?: Bundle()
            Log.i(HK.TAG_UI, "CONTROL ${echo(extras)}")
            runCatching { onCommand(extras) }.onFailure { Log.w(HK.TAG_UI, "CONTROL failed: $it") }
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter(ACTION)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, filter, PERMISSION, main, RECEIVER_EXPORTED_33)
        else ctx.registerReceiver(receiver, filter, PERMISSION, main)
        registered = true
    }

    fun stop() {
        if (!registered) return
        runCatching { ctx.unregisterReceiver(receiver) }
        registered = false
    }

    companion object {
        const val ACTION = "com.tropicalstream.hammerklavier.CONTROL"
        const val PERMISSION = "android.permission.DUMP"
        private const val RECEIVER_EXPORTED_33 = 0x2              // Context.RECEIVER_EXPORTED (API 33)

        /** `k=v` pairs in key order, for the echo line. */
        @Suppress("DEPRECATION")
        fun echo(b: Bundle): String = b.keySet().sorted().joinToString(" ") { k -> "$k=${b.get(k)}" }
    }
}
