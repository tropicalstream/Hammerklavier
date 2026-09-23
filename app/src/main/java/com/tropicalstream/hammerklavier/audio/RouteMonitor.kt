package com.tropicalstream.hammerklavier.audio

import android.media.AudioDeviceInfo
import com.tropicalstream.hammerklavier.contract.OutputRoute
import com.tropicalstream.hammerklavier.contract.RouteInfo

/**
 * The routed output device → [RouteInfo] (PLAN §3.1). Uses the track's routing listener and
 * `getRoutedDevice()` (never device-added callbacks). Built-in speaker → SPEAKER; wired headset,
 * headphones, line, USB → WIRED; Bluetooth A2DP / SCO / LE → BLUETOOTH with key `bt:<address>`.
 * Main thread: [onRouting] is called on main by the listener AudioOutput registers.
 */
class RouteMonitor(private val onChange: (RouteInfo) -> Unit) {
    @Volatile var current: RouteInfo = SPEAKER; private set

    /** Re-reads the routed device of [sink]; fires [onChange] when the route key or class changed. */
    fun onRouting(sink: OutputSink?) {
        val dev = runCatching { sink?.routedDevice() }.getOrNull()
        val info = if (dev == null) current else classify(dev.type, runCatching { dev.address }.getOrDefault(""),
            dev.productName?.toString() ?: "", if (sink?.fast == true) "fast" else "normal")
        if (info.key != current.key || info.route != current.route || info.outputFlags != current.outputFlags) {
            current = info
            onChange(info)
        }
    }

    companion object {
        /** AudioDeviceInfo.TYPE_BUILTIN_SPEAKER = 2. */
        val SPEAKER = RouteInfo(route = OutputRoute.SPEAKER, key = "speaker", deviceType = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            name = "speaker", outputFlags = "")

        fun routeOf(type: Int): OutputRoute = when (type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_AUX_LINE, AudioDeviceInfo.TYPE_HDMI -> OutputRoute.WIRED
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER,
            TYPE_HEARING_AID -> OutputRoute.BLUETOOTH
            else -> OutputRoute.SPEAKER
        }

        fun classify(type: Int, address: String, name: String, flags: String): RouteInfo {
            val r = routeOf(type)
            val key = when (r) {
                OutputRoute.SPEAKER -> "speaker"
                OutputRoute.WIRED -> "wired"
                OutputRoute.BLUETOOTH -> "bt:$address"
            }
            return RouteInfo(route = r, key = key, deviceType = type, name = name, outputFlags = flags)
        }

        /** Default latency allowance in frames until measured (PLAN §2.5). */
        fun defaultLatencyFrames(r: OutputRoute): Int = if (r == OutputRoute.BLUETOOTH) 14_400 else 6_720

        // API 31 constants spelled out (compileSdk has them; kept literal for clarity on SDK 32).
        const val TYPE_HEARING_AID = 23
        const val TYPE_BLE_HEADSET = 26
        const val TYPE_BLE_SPEAKER = 27
    }
}
