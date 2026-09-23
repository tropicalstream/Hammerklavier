package com.tropicalstream.hammerklavier.system

import android.content.Context
import android.content.SharedPreferences
import com.tropicalstream.hammerklavier.contract.SettingsStore

/**
 * Typed SharedPreferences (`hk_settings`) implementing [SettingsStore] (PLAN §2.2), with change
 * listeners. Main thread writes; reads are safe from any thread (SharedPreferences caches in
 * memory). Writes use `apply()` (asynchronous commit).
 */
class Settings(ctx: Context) : SettingsStore {
    private val prefs: SharedPreferences = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val listeners = ArrayList<(String) -> Unit>()
    // Kept strongly: SharedPreferences holds its listeners weakly.
    private val relay = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key != null) for (l in listeners.toList()) l(key) }

    init { prefs.registerOnSharedPreferenceChangeListener(relay) }

    fun addListener(l: (String) -> Unit) { listeners.add(l) }
    fun removeListener(l: (String) -> Unit) { listeners.remove(l) }

    override fun getString(k: String, d: String): String = runCatching { prefs.getString(k, d) ?: d }.getOrDefault(d)
    override fun putString(k: String, v: String) = prefs.edit().putString(k, v).apply()
    override fun getInt(k: String, d: Int): Int = runCatching { prefs.getInt(k, d) }.getOrDefault(d)
    override fun putInt(k: String, v: Int) = prefs.edit().putInt(k, v).apply()
    override fun getFloat(k: String, d: Float): Float = runCatching { prefs.getFloat(k, d) }.getOrDefault(d)
    override fun putFloat(k: String, v: Float) = prefs.edit().putFloat(k, v).apply()
    override fun getBool(k: String, d: Boolean): Boolean = runCatching { prefs.getBoolean(k, d) }.getOrDefault(d)
    override fun putBool(k: String, v: Boolean) = prefs.edit().putBoolean(k, v).apply()

    companion object { const val FILE = "hk_settings" }
}
