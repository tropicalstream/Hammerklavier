package com.tropicalstream.hammerklavier

import android.os.SystemClock
import android.util.Log
import com.tropicalstream.hammerklavier.contract.AudioStats
import com.tropicalstream.hammerklavier.contract.ClockSample
import com.tropicalstream.hammerklavier.contract.Gesture
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentLook
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.Palette
import com.tropicalstream.hammerklavier.contract.ResonanceMode
import com.tropicalstream.hammerklavier.contract.ReverbMode
import com.tropicalstream.hammerklavier.contract.SettingsSnapshot
import com.tropicalstream.hammerklavier.contract.SpeakerBass
import com.tropicalstream.hammerklavier.contract.UiAction
import com.tropicalstream.hammerklavier.contract.UiFacts
import com.tropicalstream.hammerklavier.contract.UprightFinish
import com.tropicalstream.hammerklavier.contract.ViewId
import com.tropicalstream.hammerklavier.contract.android.GlHost
import com.tropicalstream.hammerklavier.contract.android.OverlayHost

/**
 * The thin Android adapter (PLAN §2.2, §2.6): lifecycle, gestures and (from contracts-v1.1)
 * receivers, route and focus events, sensors → HeadPose, and Settings persistence, all forwarded to
 * WP12's SessionController once it merges. Until then it drives the stubs directly: gestures go
 * through the UiStateMachine and its PlayPause / SetView / Leave actions are applied here.
 * Main thread only.
 */
class AppController(private val w: Wiring) {
    private var gl: GlHost? = null
    private var overlay: OverlayHost? = null
    private var view = ViewId.PLAYER
    private var framing = 0
    private val clockSample = ClockSample()
    private val audioStats = AudioStats()

    /** Set by MainActivity: leave the app (the Back key at the root). */
    var onLeave: (() -> Unit)? = null

    fun attach(gl: GlHost, overlay: OverlayHost) {
        this.gl = gl; this.overlay = overlay
        gl.bind(w.audio.clock, w.audio.energy, w.mechanics(), w.scenes)
        gl.setInstrument(InstrumentId.GRAND, InstrumentLook(UprightFinish.WALNUT, edgeOverlay = false), InstrumentProfile.GRAND.lastDamper)
        gl.setView(view, framing)
        refreshOverlay()
    }

    fun detach() { gl = null; overlay = null }

    fun onResume() {
        w.audio.start()
        gl?.onResume()
        refreshOverlay()
    }

    fun onPause() { gl?.onPause() }

    /** §1.10: leaving with the display on fades and pauses; with the display asleep playback continues. */
    fun onStop(displayInteractive: Boolean) {
        if (displayInteractive) w.audio.pause(300)
    }

    fun onDestroy(finishing: Boolean) {
        if (finishing) w.audio.stop()
    }

    fun onGesture(g: Gesture) {
        Log.i(HK.TAG_INPUT, g.name.lowercase())
        val now = SystemClock.uptimeMillis()
        for (a in w.ui.onGesture(g, facts(), now)) apply(a)
        refreshOverlay()
    }

    private fun apply(a: UiAction) {
        when (a) {
            UiAction.PlayPause -> {
                w.audio.clock.sample(System.nanoTime(), clockSample)
                if (clockSample.playing) w.audio.pause() else w.audio.play()
            }
            is UiAction.SetView -> { view = a.view; framing = a.framing; gl?.setView(view, framing) }
            UiAction.Leave -> onLeave?.invoke()
            else -> Log.i(HK.TAG_UI, "action $a (no SessionController yet)")
        }
    }

    private fun refreshOverlay() {
        val o = overlay ?: return
        o.show(w.ui.render(facts(), SystemClock.uptimeMillis()))
    }

    /** Minimal facts for the stub UI; WP12's FactsAssembler replaces this. */
    private fun facts(): UiFacts {
        w.audio.clock.sample(System.nanoTime(), clockSample)
        w.audio.stats(audioStats)
        return UiFacts(playing = clockSample.playing, positionUs = clockSample.songUs, durationUs = 0L, movementId = null, bar = 1,
            instrument = InstrumentId.GRAND, view = view, framing = framing, kitStates = emptyMap(), library = null,
            settings = DEFAULT_SETTINGS, nextTitle = null, quality = 0, companionUrl = null, companionToken = "",
            route = w.audio.route, status = emptyList(), perfInfo = null, firstRun = false, sessions = 0, resumeTitle = null,
            recent = emptyList(), shelfId = null, version = "${BuildConfig.VERSION_NAME} ${BuildConfig.GIT_COMMIT}", debug = null)
    }

    companion object {
        private val DEFAULT_SETTINGS = SettingsSnapshot(
            tuning = InstrumentId.entries.associateWith { InstrumentProfile.of(it).defaultTuning },
            registration = 3, reverb = ReverbMode.ROOM, resonance = ResonanceMode.NATURAL, speakerBass = SpeakerBass.AUTO,
            roomOverride = null, palette = Palette.SANSSOUCI_1747, finish = UprightFinish.WALNUT, stereoDepth = 1f,
            lookAround = true, edgeOverlay = null, reverseSwipe = false, presenceFloor = 22, avLeadMs = emptyMap(),
            tempoPct = 100, msaa = true)
    }
}
