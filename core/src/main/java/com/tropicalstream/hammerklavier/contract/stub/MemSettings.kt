package com.tropicalstream.hammerklavier.contract.stub

import com.tropicalstream.hammerklavier.contract.SettingsStore

/** In-memory SettingsStore for tests and stubs. Thread-safe (one lock). */
class MemSettings : SettingsStore {
    private val map = HashMap<String, Any>()

    override fun getString(k: String, d: String): String = synchronized(map) { map[k] as? String ?: d }
    override fun putString(k: String, v: String) { synchronized(map) { map[k] = v } }
    override fun getInt(k: String, d: Int): Int = synchronized(map) { map[k] as? Int ?: d }
    override fun putInt(k: String, v: Int) { synchronized(map) { map[k] = v } }
    override fun getFloat(k: String, d: Float): Float = synchronized(map) { map[k] as? Float ?: d }
    override fun putFloat(k: String, v: Float) { synchronized(map) { map[k] = v } }
    override fun getBool(k: String, d: Boolean): Boolean = synchronized(map) { map[k] as? Boolean ?: d }
    override fun putBool(k: String, v: Boolean) { synchronized(map) { map[k] = v } }

    /** Test helper: every stored key. */
    fun keys(): Set<String> = synchronized(map) { map.keys.toSet() }
}
