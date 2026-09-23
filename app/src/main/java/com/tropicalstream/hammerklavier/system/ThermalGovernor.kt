package com.tropicalstream.hammerklavier.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.PowerManager
import android.util.Log
import com.tropicalstream.hammerklavier.contract.HK

/**
 * The quality governor's inputs (PLAN §5.11): the sticky `ACTION_BATTERY_CHANGED` intent (tenths °C)
 * and the PowerManager thermal status listener, both inside `runCatching`, fed to [ThermalPolicy].
 * Its lifetime is the engine's ([start] when the engine starts, [stop] when it stops), not the
 * activity's, so it keeps updating with the display off. `HKThermal status=… battery=…C -> Qn` is
 * logged on every level change and once a minute as a heartbeat. [fakeTenths] (≥ 0) replaces the
 * battery reading (`--ei faketemp`); [forcedLevel] (0..3, −1 = automatic) overrides the result
 * (`--ei quality`). Main thread.
 */
class ThermalGovernor(private val ctx: Context, private val main: Handler, private val onLevel: (Int) -> Unit) {
    private val policy = ThermalPolicy()
    private var running = false
    private var status = 0
    var batteryTenths = 0; private set
    var level = 0; private set
    private var fake = -1
    private var forced = -1
    private var lastLogged = -1

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { i?.let { readBattery(it) }; evaluate(false) }
    }
    private val statusListener = PowerManager.OnThermalStatusChangedListener { s -> status = s; evaluate(false) }
    private val heartbeat = object : Runnable {
        override fun run() { if (!running) return; evaluate(true); main.postDelayed(this, HEARTBEAT_MS) }
    }

    fun start() {
        if (running) return
        running = true
        runCatching { ctx.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { readBattery(it) } }
        runCatching {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            status = pm.currentThermalStatus
            pm.addThermalStatusListener(statusListener)
        }
        evaluate(true)
        main.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(heartbeat)
        runCatching { ctx.unregisterReceiver(receiver) }
        runCatching { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).removeThermalStatusListener(statusListener) }
    }

    val isRunning: Boolean get() = running

    /** `--ei faketemp <tenths>`; a negative value returns to the real battery. */
    fun fakeTenths(t: Int) { fake = t; evaluate(true) }

    /** `--ei quality N`; −1 = automatic. */
    fun forcedLevel(q: Int) { forced = if (q in 0..3) q else -1; evaluate(true) }

    /** The temperature the policy sees (the fake one when set). */
    val effectiveTenths: Int get() = if (fake >= 0) fake else batteryTenths

    private fun readBattery(i: Intent) {
        val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (t != Int.MIN_VALUE) batteryTenths = t
    }

    private fun evaluate(log: Boolean) {
        val auto = policy.update(effectiveTenths, status)
        val q = if (forced >= 0) forced else auto
        val changed = q != level
        level = q
        if (changed || log || lastLogged < 0) {
            lastLogged = q
            val t = effectiveTenths
            Log.i(HK.TAG_THERMAL, "status=$status battery=${t / 10}.${Math.abs(t % 10)}C${if (fake >= 0) " (fake)" else ""}" +
                "${if (forced >= 0) " forced" else ""} -> Q$q")
        }
        if (changed) onLevel(q)
    }

    private companion object { const val HEARTBEAT_MS = 60_000L }
}
