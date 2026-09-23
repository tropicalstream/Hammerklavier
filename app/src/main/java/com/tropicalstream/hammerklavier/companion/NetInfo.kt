package com.tropicalstream.hammerklavier.companion

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The companion's address (PLAN §1.6): the first site-local IPv4 (10/8, 172.16/12, 192.168/16) of
 * an up, non-loopback interface, preferring `wlan*`; and a Wi-Fi watcher that calls back on main's
 * behalf (the caller posts) when Wi-Fi comes or goes.
 */
object NetInfo {
    /** Pure java.net: callable from JVM tests. Null when no site-local IPv4 exists. */
    fun siteLocalIpv4(): String? {
        val candidates = ArrayList<Pair<Int, String>>()
        try {
            val ifs = NetworkInterface.getNetworkInterfaces() ?: return null
            for (ni in ifs) {
                if (!ni.isUp || ni.isLoopback) continue
                val rank = if (ni.name.startsWith("wlan")) 0 else if (ni.name.startsWith("eth")) 1 else 2
                for (a in ni.inetAddresses) if (a is Inet4Address && a.isSiteLocalAddress) a.hostAddress?.let { candidates += rank to it }
            }
        } catch (e: Exception) { return null }
        return candidates.minByOrNull { it.first }?.second
    }

    /** Registers a callback on Wi-Fi networks; [onChange] runs on a binder thread (post it). Returns an unregister function. */
    fun watchWifi(ctx: Context, onChange: () -> Unit): () -> Unit {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return {}
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChange()
            override fun onLost(network: Network) = onChange()
            override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) = onChange()
        }
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val unregister: () -> Unit = { runCatching { cm.unregisterNetworkCallback(cb) } }
        return try {
            cm.registerNetworkCallback(req, cb)
            unregister
        } catch (e: Exception) { {} }
    }
}
