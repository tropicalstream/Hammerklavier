package com.tropicalstream.hammerklavier

import android.content.Context
import android.os.Bundle
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
import com.tropicalstream.hammerklavier.contract.QualityLadder
import com.tropicalstream.hammerklavier.contract.QualityProfile
import com.tropicalstream.hammerklavier.contract.RenderStats
import com.tropicalstream.hammerklavier.system.DebugControl
import com.tropicalstream.hammerklavier.system.MediaButtons
import com.tropicalstream.hammerklavier.system.PerfProbe
import com.tropicalstream.hammerklavier.system.SelfTest
import com.tropicalstream.hammerklavier.system.SoakRecorder
import com.tropicalstream.hammerklavier.system.ThermalGovernor
import com.tropicalstream.hammerklavier.contract.android.GlHost
import com.tropicalstream.hammerklavier.contract.android.OverlayHost

/**
 * The thin Android adapter (PLAN §2.2, §2.6): lifecycle, gestures, the CONTROL receiver, media
 * buttons, the thermal governor, the soak recorder, the perf probe and the self-test, all
 * forwarded to WP12's SessionController once it merges. Until then it drives the stubs directly:
 * gestures go through the UiStateMachine and its PlayPause / SetView / Leave actions are applied
 * here; commands that need a SessionController are logged as deferred.
 *
 * The engine services (governor, CONTROL receiver, media buttons, perf probe) live as long as the
 * engine, not the activity (§1.10, §5.11): [startEngine] runs at process start and on every
 * resume (idempotent); [stopEngine] only on `onDestroy` with `isFinishing`. Main thread only.
 */
class AppController(private val ctx: Context, private val w: Wiring) {
    private var gl: GlHost? = null
    private var overlay: OverlayHost? = null
    private var view = ViewId.PLAYER
    private var framing = 0
    private var resumed = false
    private var engineRunning = false
    private var debug = false
    private var q0Cap = HK.VOICE_CAP_MAX                          // until EngineBench sets C (§3.14)
    private val clockSample = ClockSample()
    private val audioStats = AudioStats()
    private val renderStats = RenderStats()

    /** Set by MainActivity: leave the app (the Back key at the root). */
    var onLeave: (() -> Unit)? = null
    /** Set by MainActivity: the window brightness cap (−1 = the user's brightness). */
    var onBrightness: ((Float) -> Unit)? = null
    /** Set by MainActivity: gesture-engine raw trace on/off (`--ez debug true`). */
    var onDebug: ((Boolean) -> Unit)? = null

    var quality: QualityProfile = QualityLadder.of(0, q0Cap); private set
    private var brightnessOverride = -2f                                // −2 = none; else `--ef brightness`

    val governor = ThermalGovernor(ctx, w.main) { level -> applyQuality(level) }
    private val control = DebugControl(ctx, w.main) { b -> onControl(b) }
    private val perf = PerfProbe(w.main, audioTid = { w.audio.stats(audioStats); audioStats.tid }, extra = { perfExtra() })
    private val selfTest = SelfTest(ctx, w, w.main)
    private var media: MediaButtons? = null
    val soak = SoakRecorder(ctx, w.main, SoakSource())
    /** M1 interim playback driver (kits → audio, `play`, `bench`, stats lines); WP12 replaces it. */
    val playback = Playback(w).also { pb ->
        pb.onVoiceCap = { cap -> q0Cap = cap; applyQuality(quality.level) }
        pb.onPlaying = { runCatching { w.kits.setPlaybackHint(true, InstrumentId.GRAND, quality, governor.effectiveTenths) } }
    }
    /** Re-sends the playback hint on every play/pause edge (pause and end paths do not call back). */
    private var hintPlaying = false
    private val hintTick = object : Runnable { override fun run() {
        if (!engineRunning) return
        val p = isPlaying()
        if (p != hintPlaying) { hintPlaying = p; runCatching { w.kits.setPlaybackHint(p, InstrumentId.GRAND, quality, governor.effectiveTenths) } }
        w.main.postDelayed(this, 500)
    } }
    private val debugTick = object : Runnable { override fun run() { if (!debug) return; refreshOverlay(); w.main.postDelayed(this, 500) } }

    fun startEngine() {
        if (engineRunning) return
        engineRunning = true
        w.audio.start()
        playback.start()
        governor.start(); control.start(); perf.start()
        w.main.removeCallbacks(hintTick); w.main.post(hintTick)
        if (media == null) media = runCatching { MediaButtons(ctx) { a -> applyAll(listOf(a)) } }.getOrNull()
        Log.i(HK.TAG_UI, "engine started")
    }

    fun stopEngine() {
        if (!engineRunning) return
        engineRunning = false
        playback.stop(); soak.stop(); perf.stop(); control.stop(); governor.stop()
        media?.release(); media = null
        w.audio.stop()
        Log.i(HK.TAG_UI, "engine stopped")
    }

    fun attach(gl: GlHost, overlay: OverlayHost) {
        this.gl = gl; this.overlay = overlay
        gl.bind(w.audio.clock, w.audio.energy, w.mechanics(), w.scenes)
        gl.setInstrument(InstrumentId.GRAND, InstrumentLook(UprightFinish.WALNUT, edgeOverlay = false), InstrumentProfile.GRAND.lastDamper)
        gl.setView(view, framing)
        gl.setQuality(quality)
        refreshOverlay()
    }

    fun detach() { gl = null; overlay = null }

    /** §1.10 onResume: restart the engine if it was stopped, pacing, the render-side quality. */
    fun onResume() {
        resumed = true
        startEngine()
        w.audio.start()
        gl?.setQuality(quality)
        applyBrightness()
        gl?.onResume()
        perf.setResumed(true)
        refreshOverlay()
    }

    /** §1.10 onPause: GL pauses, audio keeps playing; the engine services stay alive. */
    fun onPause() {
        resumed = false
        perf.setResumed(false)
        gl?.onPause()
    }

    /** §1.10: leaving with the display on fades and pauses; with the display asleep playback continues. */
    fun onStop(displayInteractive: Boolean) {
        if (displayInteractive) { w.audio.pause(300); media?.setPlaying(false, positionMs()) }
    }

    fun onDestroy(finishing: Boolean) {
        if (finishing) stopEngine()
    }

    fun onGesture(g: Gesture, source: String = "pad") {
        Log.i(HK.TAG_INPUT, "${g.name.lowercase()} gesture=${g.name} src=$source")
        applyAll(w.ui.onGesture(g, facts(), SystemClock.uptimeMillis()))
    }

    private fun applyAll(actions: List<UiAction>) {
        for (a in actions) apply(a)
        refreshOverlay()
    }

    private fun apply(a: UiAction) {
        when (a) {
            UiAction.PlayPause -> {
                w.audio.clock.sample(System.nanoTime(), clockSample)
                if (clockSample.playing) w.audio.pause() else { w.audio.play(); w.main.postDelayed({ playback.logClock("resume") }, 1000) }
                media?.setPlaying(!clockSample.playing, positionMs())
            }
            is UiAction.SetView -> { view = a.view; framing = a.framing; gl?.setView(view, framing) }
            is UiAction.Seek -> w.audio.seek(a.us)
            UiAction.Recenter -> gl?.recenter()
            UiAction.Leave -> { w.audio.pause(300); onLeave?.invoke() }
            else -> Log.i(HK.TAG_UI, "action $a deferred (SessionController, WP12)")
        }
    }

    // ── Quality (§5.11) ──
    private fun applyQuality(level: Int) {
        quality = QualityLadder.of(level, q0Cap)
        w.audio.setQuality(quality)
        runCatching { w.kits.setPlaybackHint(isPlaying(), InstrumentId.GRAND, quality, governor.effectiveTenths) }
        if (resumed) { gl?.setQuality(quality); applyBrightness() }
        refreshOverlay()
    }

    private fun applyBrightness() {
        val b = if (brightnessOverride > -2f) brightnessOverride else quality.brightnessCap
        onBrightness?.invoke(b)
    }

    // ── CONTROL (§8.2) ──
    /** One CONTROL broadcast (or `am start` extras). Unknown or not-yet-wired keys are logged. */
    fun onControl(b: Bundle) {
        for (k in b.keySet().sorted()) {
            when (k) {
                "gesture" -> gestureOf(b.getString(k))?.let { onGesture(it, "control") } ?: unknown(k, b)
                "view" -> ViewId.entries.getOrNull(b.getInt(k, -1))?.let { applyAll(listOf(UiAction.SetView(it, framing))) } ?: unknown(k, b)
                "framing" -> applyAll(listOf(UiAction.SetView(view, b.getInt(k, 0).coerceIn(0, 1))))
                "pause" -> if (b.getBoolean(k)) { w.audio.pause(); media?.setPlaying(false, positionMs()) }
                "resume" -> if (b.getBoolean(k)) { w.audio.play(); w.main.postDelayed({ playback.logClock("resume") }, 1000); media?.setPlaying(isPlaying(), positionMs()) }
                "seek" -> w.audio.seek(b.getLong(k, 0L) * 1000 + HK.PRE_ROLL_US)
                "rate" -> w.audio.setRate(b.getFloat(k, 1f))
                "leave" -> if (b.getBoolean(k)) apply(UiAction.Leave)
                "quality" -> governor.forcedLevel(b.getInt(k, -1))
                "faketemp" -> governor.fakeTenths(b.getInt(k, -1))
                "recenter" -> if (b.getBoolean(k)) gl?.recenter()
                "brightness" -> { brightnessOverride = b.getFloat(k, -1f).let { if (it < 0f) -2f else it.coerceIn(0.05f, 1f) }; if (resumed) applyBrightness() }
                "debug" -> { debug = b.getBoolean(k); onDebug?.invoke(debug); w.main.removeCallbacks(debugTick); if (debug) w.main.post(debugTick) else refreshOverlay() }
                "play" -> b.getString(k)?.let { playback.play(it) } ?: unknown(k, b)
                "bench" -> if (b.getBoolean(k)) playback.bench(b.getInt("benchsecs", 2).coerceIn(1, 30))
                "lowlatency" -> w.settings.putBool("audio.lowLatency", b.getBoolean(k))
                "standin" -> w.settings.putBool(Wiring.KEY_STAND_IN, b.getBoolean(k))              // applies at the next launch
                "stats" -> if (b.getBoolean(k)) playback.logStats()
                "align" -> if (b.getBoolean(k)) playback.align()
                "wavdump" -> playback.captureWav(b.getInt(k, 20).coerceIn(1, 60))
                "selftest" -> if (b.getBoolean(k)) selfTest.run(gl, b.getInt("selftestsecs", 60).coerceIn(1, 600))
                "selftestsecs", "benchsecs", "soakplan", "mono", "echo", "n" -> {}                   // parameters of other keys; echo is for the smoke test
                "gcstats" -> if (b.getBoolean(k)) logGcStats()
                "dump" -> if (b.getBoolean(k)) dump()
                "soak" -> if (b.getBoolean(k)) soak.start(b.getString("soakplan")) else soak.stop()
                else -> Log.i(HK.TAG_UI, "CONTROL $k deferred (SessionController, WP12)")
            }
        }
    }

    private fun unknown(k: String, b: Bundle) { @Suppress("DEPRECATION") Log.w(HK.TAG_UI, "CONTROL $k=${b.get(k)} not understood") }

    private fun gestureOf(s: String?): Gesture? = when (s?.lowercase()) {
        "tap" -> Gesture.TAP; "double" -> Gesture.DOUBLE; "triple" -> Gesture.TRIPLE
        "fwd", "forward" -> Gesture.FORWARD; "back" -> Gesture.BACK; "up" -> Gesture.UP; "down" -> Gesture.DOWN
        "sysback" -> Gesture.SYSTEM_BACK; else -> null
    }

    private fun logGcStats() {
        val keys = listOf("art.gc.gc-count", "art.gc.gc-time", "art.gc.bytes-allocated", "art.gc.blocking-gc-count")
        val rt = Runtime.getRuntime()
        Log.i(HK.TAG_PERF, keys.joinToString(" ") { "$it=${runCatching { android.os.Debug.getRuntimeStat(it) }.getOrNull()}" } +
            " heapUsedKiB=${(rt.totalMemory() - rt.freeMemory()) / 1024} heapTotalKiB=${rt.totalMemory() / 1024}")
    }

    private fun dump() {
        w.audio.stats(audioStats); gl?.stats(renderStats)
        Log.i(HK.TAG_UI, "dump view=$view framing=$framing resumed=$resumed engine=$engineRunning quality=$quality " +
            "battery=${governor.effectiveTenths} playing=${isPlaying()} positionMs=${positionMs()} soak=${soak.recording}")
        for ((name, m) in listOf("audio" to w.audio.diagnostics(), "gl" to (gl?.diagnostics() ?: emptyMap())))
            Log.i(HK.TAG_UI, "dump $name ${m.entries.joinToString(" ") { "${it.key}=${it.value}" }}")
    }

    private fun perfExtra(): String {
        w.audio.stats(audioStats)
        return "q=${quality.level} underruns=${audioStats.underruns} voices=${audioStats.voices}"
    }

    private fun isPlaying(): Boolean { w.audio.clock.sample(System.nanoTime(), clockSample); return clockSample.playing }
    private fun positionMs(): Long { w.audio.clock.sample(System.nanoTime(), clockSample); return (clockSample.songUs - HK.PRE_ROLL_US) / 1000 }

    private inner class SoakSource : SoakRecorder.Source {
        override fun batteryTenths() = governor.effectiveTenths
        override fun thermalStatus() = runCatching { (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).currentThermalStatus }.getOrDefault(-1)
        override fun quality() = quality.level
        override fun fps(): Float { gl?.stats(renderStats) ?: return 0f; return renderStats.fps }
        override fun lateFrames(): Int { gl?.stats(renderStats) ?: return 0; return renderStats.lateFrames }
        override fun underruns(): Int { w.audio.stats(audioStats); return audioStats.underruns }
        override fun headroomMin(): Int { w.audio.stats(audioStats); return audioStats.headroomMinFrames }
        override fun audioTid(): Int { w.audio.stats(audioStats); return audioStats.tid }
        override fun brightness() = if (brightnessOverride > -2f) brightnessOverride else quality.brightnessCap
        override fun movement() = "none"
        override fun positionMs() = this@AppController.positionMs()
        override fun play(id: String) { Log.i(HK.TAG_SOAK, "play $id deferred (SessionController, WP12)") }
        override fun setView(v: ViewId, framing: Int) = applyAll(listOf(UiAction.SetView(v, framing)))
        override fun forceQuality(q: Int) = governor.forcedLevel(q)
        override fun setBrightness(b: Float) { brightnessOverride = b; if (resumed) applyBrightness() }
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
            settings = DEFAULT_SETTINGS, nextTitle = null, quality = quality.level, companionUrl = null, companionToken = "",
            route = w.audio.route, status = emptyList(), perfInfo = null, firstRun = false, sessions = 0, resumeTitle = null,
            recent = emptyList(), shelfId = null, version = "${BuildConfig.VERSION_NAME} ${BuildConfig.GIT_COMMIT}",
            debug = if (debug) playback.debugLine() else null)
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
