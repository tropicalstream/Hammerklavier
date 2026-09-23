package com.tropicalstream.hammerklavier.audio

import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.QualityProfile

/**
 * The voicing policy (PLAN §3.3): voicing runs only when nothing plays, needs
 * `QualityProfile.voicingAllowed` (Q0) and battery < 37.5 °C; playback start makes the voicer
 * yield within one output buffer. The active kit voices first; other kits only after it and after
 * ≥ 10 s idle. **Deviation (recorded in the progress file):** the first playable set of the kit
 * the user is waiting for ignores the thermal and quality gates, since nothing can play without it.
 * Pure apart from the clock; read by HKVoicer, written by main.
 */
class VoicingScheduler(private val nowMs: () -> Long) {
    @Volatile var playing = false; private set
    @Volatile var activeId: InstrumentId? = null; private set
    @Volatile var voicingAllowed = true; private set
    @Volatile var batteryTenths = -1; private set
    @Volatile private var idleSinceMs = 0L

    fun update(playing: Boolean, activeId: InstrumentId?, q: QualityProfile?, batteryTenths: Int) {
        if (this.playing && !playing) idleSinceMs = nowMs()
        this.playing = playing
        if (activeId != null) this.activeId = activeId
        if (q != null) voicingAllowed = q.voicingAllowed
        this.batteryTenths = batteryTenths
    }

    /** HKVoicer polls this between output buffers. */
    fun shouldYield(): Boolean = playing

    private fun coolEnough(): Boolean = batteryTenths < 0 || batteryTenths < MAX_BATTERY_TENTHS

    /**
     * May [id] voice now? [playableYet] = its playable set is ready; [activeComplete] = the active
     * kit (if another) is complete.
     */
    fun mayVoice(id: InstrumentId, playableYet: Boolean, activeComplete: Boolean): Boolean {
        if (playing) return false
        val active = activeId
        if (id == active || active == null) {
            return !playableYet || (voicingAllowed && coolEnough())
        }
        return voicingAllowed && coolEnough() && activeComplete && nowMs() - idleSinceMs >= OTHER_IDLE_MS
    }

    /** Milliseconds until another kit may voice (0 = now). */
    fun otherKitWaitMs(): Long = (OTHER_IDLE_MS - (nowMs() - idleSinceMs)).coerceAtLeast(0L)

    companion object {
        const val MAX_BATTERY_TENTHS = 375
        const val OTHER_IDLE_MS = 10_000L
    }
}
