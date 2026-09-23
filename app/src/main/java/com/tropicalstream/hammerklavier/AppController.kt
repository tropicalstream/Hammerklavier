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
import com.tropicalstream.hammerklavier.contract.RenderControl
import com.tropicalstream.hammerklavier.contract.SongClock
import com.tropicalstream.hammerklavier.contract.EnergyRing
import com.tropicalstream.hammerklavier.contract.MechanicsEvaluator
import com.tropicalstream.hammerklavier.contract.SceneFactory
import com.tropicalstream.hammerklavier.contract.Performance
import com.tropicalstream.hammerklavier.contract.RenderSettings
import com.tropicalstream.hammerklavier.contract.RenderOverrides
import com.tropicalstream.hammerklavier.contract.UiContext
import com.tropicalstream.hammerklavier.contract.UiEvent
import com.tropicalstream.hammerklavier.companion.CompanionServer
import com.tropicalstream.hammerklavier.companion.NetInfo
import com.tropicalstream.hammerklavier.session.SessionController
import com.tropicalstream.hammerklavier.ui.model.CardKind
import com.tropicalstream.hammerklavier.ui.model.UiOverlayState
import com.tropicalstream.hammerklavier.ui.model.UiStateMachineImpl
import com.tropicalstream.hammerklavier.ui.model.UiText
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
 * buttons, the thermal governor, the soak recorder, the perf probe, the self-test and the
 * companion server, all forwarded to WP12's [SessionController] (M6). UiActions go to the
 * session except Recenter and Leave's fade; the overlay facts come from `session.facts()`.
 * [Playback] keeps only the bench / align / wavdump / stats tools (no kit, no listener).
 *
 * The engine services live as long as the engine, not the activity (§1.10, §5.11): [startEngine]
 * runs at process start and on every resume (idempotent); [stopEngine] only on `onDestroy` with
 * `isFinishing`. Main thread only.
 */
class AppController(private val ctx: Context, private val w: Wiring) {
    private var gl: GlHost? = null
    private var overlay: OverlayHost? = null
    private var resumed = false
    private var engineRunning = false
    private var sessionStarted = false
    private var debug = false
    private val clockSample = ClockSample()
    private val audioStats = AudioStats()
    private val renderStats = RenderStats()

    /** Set by MainActivity: leave the app (the Back key at the root). */
    var onLeave: (() -> Unit)? = null
    /** Set by MainActivity: the window brightness cap (−1 = the user's brightness). */
    var onBrightness: ((Float) -> Unit)? = null
    /** Set by MainActivity: gesture-engine raw trace on/off (`--ez debug true`). */
    var onDebug: ((Boolean) -> Unit)? = null

    val quality: QualityProfile get() = session.quality
    private var brightnessOverride = -2f                                // −2 = none; else `--ef brightness`
    private var sessionCap = -1f

    // ── RenderControl forwarder: the GlHost attaches after construction and can detach (WP12 wiring 1) ──
    private var rInstrument: Triple<InstrumentId, InstrumentLook, Int>? = null
    private var rView: Pair<ViewId, Int>? = null
    private var rSettings: RenderSettings? = null
    private var rPerf: Pair<Performance?, InstrumentProfile>? = null
    private var rStageHidden = false
    private var rOverrides: RenderOverrides? = null
    private val renderFwd = object : RenderControl {
        override fun bind(clock: SongClock, energy: EnergyRing, mech: MechanicsEvaluator, scenes: SceneFactory) { gl?.bind(clock, energy, mech, scenes) }
        override fun setPerformance(p: Performance?, profile: InstrumentProfile) { rPerf = p to profile; gl?.setPerformance(p, profile) }
        override fun setInstrument(id: InstrumentId, look: InstrumentLook, lastDamper: Int) { rInstrument = Triple(id, look, lastDamper); gl?.setInstrument(id, look, lastDamper) }
        override fun setView(v: ViewId, framing: Int) {
            rView = v to framing; gl?.setView(v, framing)
            describePose(v, framing)
        }
        override fun setQuality(q: QualityProfile) { if (resumed) gl?.setQuality(q) }
        override fun setSettings(s: RenderSettings) { rSettings = s; gl?.setSettings(s) }
        override fun setStereo(on: Boolean) { gl?.setStereo(on) }
        override fun setIdle(idle: Boolean) { gl?.setIdle(idle) }
        override fun setSyncFlash(on: Boolean) { gl?.setSyncFlash(on) }
        override fun setTitle(text: String?) { gl?.setTitle(text) }
        override fun setStageHidden(hidden: Boolean) { rStageHidden = hidden; gl?.setStageHidden(hidden) }
        override fun setOverrides(o: RenderOverrides) { rOverrides = o; gl?.setOverrides(o) }
        override fun recenter() { gl?.recenter() }
        override fun onResume() { gl?.onResume() }
        override fun onPause() { gl?.onPause() }
        override fun stats(out: RenderStats) { gl?.stats(out) }
    }

    /** Logs each setRoom with the §5.6 listener of the current view (smoke M5: one line per view change). */
    private var roomPose = ""
    private val audioLog = object : com.tropicalstream.hammerklavier.contract.AudioControl by w.audio {
        override fun setRoom(d: com.tropicalstream.hammerklavier.contract.RoomDesign, glideMs: Int) {
            val t0 = System.nanoTime()
            w.audio.setRoom(d, glideMs)
            Log.i(HK.TAG_UI, "setRoom $roomPose direct=${"%.3f".format(d.directGain)} erGain=${"%.3f".format(d.erGain)} reverbGain=${"%.3f".format(d.reverbGain)} " +
                "preDelay=${d.preDelayFrames} t60Mid=${"%.2f".format(d.t60Mid)} az=${"%.2f".format(d.sourceAzimuthRad)} ms=${"%.2f".format((System.nanoTime() - t0) / 1e6)}")
        }
    }
    private fun describePose(v: ViewId, framing: Int) {
        roomPose = runCatching {
            val id = session.instrument
            val g = w.scenes.venue().geometry
            val placement = g.placements[id] ?: com.tropicalstream.hammerklavier.contract.KonzertzimmerAcoustics.PLACEMENTS.getValue(id)
            val a = poseAnchors.getOrPut(id) { w.scenes.instrument(id, InstrumentLook(UprightFinish.WALNUT, edgeOverlay = false), InstrumentProfile.of(id).lastDamper).anchors }
            val r = com.tropicalstream.hammerklavier.session.ListenerRooms.resolve(id, a, placement, v, framing)
            val e = r.pose.earRoom
            "view=$v/$framing ear=${"%.2f,%.2f,%.2f".format(e[0], e[1], e[2])} worldLocked=${r.pose.worldLocked} width=${r.pose.directWidth}"
        }.getOrDefault("view=$v/$framing")
    }
    private val poseAnchors = HashMap<InstrumentId, com.tropicalstream.hammerklavier.contract.InstrumentAnchors>()

    val session: SessionController = SessionController(audioLog, renderFwd, w.kits, w.library, w.compiler, w.designer,
        w.scenes, w.settings, w.loader, w.post, nowMs = { SystemClock.elapsedRealtime() }).also { s ->
        s.onUiEvent = { e -> uiEvent(e) }
        s.onBrightnessCap = { c -> sessionCap = c; if (resumed) applyBrightness() }
        s.onLeave = { onLeave?.invoke() }
        s.onRotateToken = { rotateToken() }
        s.statusPriority = UiText::priority
        s.version = "${BuildConfig.VERSION_NAME} ${BuildConfig.GIT_COMMIT}"
    }

    private val uiImpl: UiStateMachineImpl? get() = w.ui as? UiStateMachineImpl

    // ── Companion (WP9) ──
    private val companion = CompanionServer(port = CompanionServer.PORT, token = { token() }, library = w.library,
        model = { null }, commands = session, post = w.post,
        page = { ctx.assets.open("companion.html").use { String(it.readBytes(), Charsets.UTF_8) } })
    init {
        companion.onResults = { rs ->
            val f = w.library as? com.tropicalstream.hammerklavier.library.ImportedFacts
            session.onUploadResults(rs) { id -> f?.let { (it.importedNotes(id) ?: 0) to (it.importedDurationSec(id) ?: 0f) } }
            refreshOverlay()
        }
    }
    private var companionRunning = false
    private var unwatchWifi: (() -> Unit)? = null
    private fun token(): String {
        val t = w.settings.getString(KEY_TOKEN, "")
        if (CompanionServer.isToken(t)) return t
        return CompanionServer.newToken().also { w.settings.putString(KEY_TOKEN, it) }
    }
    private fun rotateToken() { w.settings.putString(KEY_TOKEN, CompanionServer.newToken()); updateCompanionFacts(); Log.i(HK.TAG_UI, "companion token rotated") }
    private fun updateCompanionFacts() {
        session.companionUrl = if (companionRunning) companion.url() else null
        session.companionToken = token()
        Log.i(HK.TAG_UI, "companion url=${session.companionUrl} running=$companionRunning")
        refreshOverlay()
    }
    private fun startCompanion() {
        if (companionRunning) return
        companionRunning = runCatching { companion.start(5000, true); true }.getOrElse { Log.w(HK.TAG_UI, "companion start failed: $it"); false }
        if (unwatchWifi == null) unwatchWifi = NetInfo.watchWifi(ctx) { w.main.post { updateCompanionFacts() } }
        updateCompanionFacts()
    }
    private fun stopCompanion() {
        unwatchWifi?.invoke(); unwatchWifi = null
        if (companionRunning) runCatching { companion.stop() }
        companionRunning = false
    }

    val governor = ThermalGovernor(ctx, w.main) { level -> applyQuality(level) }
    private val control = DebugControl(ctx, w.main) { b -> onControl(b) }
    private val perf = PerfProbe(w.main, audioTid = { w.audio.stats(audioStats); audioStats.tid }, extra = { perfExtra() })
    private val selfTest = SelfTest(ctx, w, w.main)
    private var media: MediaButtons? = null
    val soak = SoakRecorder(ctx, w.main, SoakSource())
    /** Tools only (bench, align, wavdump, the 10 s stats line); the session owns kits, listener and plays. */
    val playback = Playback(w).also { pb ->
        pb.onVoiceCap = { cap -> session.setQ0Cap(cap) }
    }
    /** 1 Hz: session tick (resume point, now-playing), idle pacing, render log every 5 s. */
    private val tick = object : Runnable { override fun run() {
        if (!engineRunning) return
        session.batteryTenths = governor.effectiveTenths
        session.debugLine = if (debug) playback.debugLine() else null
        session.tick()
        syncIdle()
        if (resumed && ++renderTicks % 5 == 0) logRender()
        w.main.postDelayed(this, 1000)
    } }
    private var shownPlaying = false
    private val playWatch = object : Runnable { override fun run() {
        if (overlay == null) return
        if (session.isPlaying() != shownPlaying || ++watchTicks % 4 == 0) refreshOverlay()
        w.main.postDelayed(this, 250) } }
    private var watchTicks = 0

    fun startEngine() {
        if (engineRunning) return
        engineRunning = true
        w.audio.start()
        if (!sessionStarted) { sessionStarted = true; session.start() } else w.audio.setListener(session)
        playback.start()
        governor.start(); control.start(); perf.start()
        startCompanion()
        w.main.removeCallbacks(tick); w.main.post(tick)
        if (media == null) media = runCatching { MediaButtons(ctx) { a -> applyAll(listOf(a)) } }.getOrNull()
        w.loader.execute {
            val credits = runCatching { ctx.assets.open("credits.txt").use { String(it.readBytes(), Charsets.UTF_8) } }.getOrNull()
            w.main.post { uiImpl?.creditsText = credits }
        }
        Log.i(HK.TAG_UI, "engine started")
    }

    fun stopEngine() {
        if (!engineRunning) return
        engineRunning = false
        session.onAppLeft()
        playback.stop(); soak.stop(); perf.stop(); control.stop(); governor.stop()
        stopCompanion()
        media?.release(); media = null
        w.audio.setListener(null)
        w.audio.stop()
        Log.i(HK.TAG_UI, "engine stopped")
    }

    fun attach(gl: GlHost, overlay: OverlayHost) {
        this.gl = gl; this.overlay = overlay
        gl.bind(w.audio.clock, w.audio.energy, w.mechanics(), w.scenes)
        val inst = rInstrument ?: Triple(session.instrument, InstrumentLook(UprightFinish.WALNUT, edgeOverlay = false), InstrumentProfile.of(session.instrument).lastDamper)
        gl.setInstrument(inst.first, inst.second, inst.third)
        val v = rView ?: (session.view to session.framing)
        gl.setView(v.first, v.second)
        gl.setQuality(quality)
        rSettings?.let { gl.setSettings(it) }
        rOverrides?.let { gl.setOverrides(it) }
        gl.setStageHidden(rStageHidden)
        gl.setIdle(!session.isPlaying())
        rPerf?.let { gl.setPerformance(it.first, it.second) }
        refreshOverlay()
        w.main.removeCallbacks(playWatch); w.main.post(playWatch)
    }

    private var lastIdle: Boolean? = null
    private var renderTicks = 0

    /** T-FPS: `HKRender fps= late=` every 5 s while resumed. */
    private fun logRender() {
        val g = gl ?: return
        g.stats(renderStats)
        val d = g.diagnostics()
        Log.i(HK.TAG_RENDER, "fps=${"%.1f".format(renderStats.fps)} late=${renderStats.lateFrames} lateP99Us=${renderStats.lateP99Us} " +
            "hitches=${renderStats.hitches} divider=${renderStats.divider} draws=${renderStats.draws} maxDraws=${d["maxDrawsPerEye"]} tris=${renderStats.tris} " +
            "cpuUsP99=${renderStats.cpuUsP99} glGen=${renderStats.glGeneration} glErrors=${d["glErrors"]} glAllocs=${d["glAllocs"]} view=${session.view}/${session.framing} q=${quality.level} idle=$lastIdle")
    }

    fun syncIdle() { val idle = !session.isPlaying(); if (idle != lastIdle) { lastIdle = idle; gl?.setIdle(idle) } }

    fun detach() { gl = null; overlay = null; w.main.removeCallbacks(playWatch) }

    /** §1.10 onResume: restart the engine if it was stopped, pacing, the render-side quality, a rescan (§1.7). */
    fun onResume() {
        resumed = true
        startEngine()
        w.audio.start()
        gl?.setQuality(quality)
        applyBrightness()
        gl?.onResume()
        perf.setResumed(true)
        session.onAction(UiAction.Rescan)
        refreshOverlay()
    }

    fun onPause(displayInteractive: Boolean = false) {
        if (displayInteractive) fadeAndPause("onPause")
        resumed = false
        perf.setResumed(false)
        gl?.onPause()
    }

    /** §1.10: leaving with the display on fades and pauses (resume point saved); asleep playback continues. */
    fun onStop(displayInteractive: Boolean) {
        if (displayInteractive) fadeAndPause("onStop")
        else { session.onAppLeft(); Log.i(HK.TAG_UI, "left asleep: playing=${session.isPlaying()} continues") }
    }

    /** §1.10 leaving with the display on: 300 ms fade, pause, resume point saved (T-LEAVE). */
    private fun fadeAndPause(why: String) {
        if (session.isPlaying()) {
            session.toggle()                     // pause through the session (intended state, resume point)
            w.audio.pause(300)                   // the 300 ms fade (§1.10)
            media?.setPlaying(false, positionMs())
            Log.i(HK.TAG_UI, "left with the display on: fade and pause ($why), resume point saved")
        }
        session.onAppLeft()
    }

    fun onDestroy(finishing: Boolean) {
        if (finishing) stopEngine()
    }

    fun onGesture(g: Gesture, source: String = "pad", downUptimeMs: Long = 0L, eventUptimeMs: Long = 0L) {
        if (eventUptimeMs != 0L) {
            val now = SystemClock.uptimeMillis()
            Log.i(HK.TAG_INPUT, "${g.name.lowercase()} gesture=${g.name} src=$source fingerMs=${eventUptimeMs - downUptimeMs} recogMs=${now - eventUptimeMs}")
            (gl as? com.tropicalstream.hammerklavier.render.HkGlView)?.pendingInputNanos = eventUptimeMs * 1_000_000L
        } else Log.i(HK.TAG_INPUT, "${g.name.lowercase()} gesture=${g.name} src=$source")
        applyAll(w.ui.onGesture(g, facts(), SystemClock.uptimeMillis()))
    }

    private fun applyAll(actions: List<UiAction>) {
        val t0 = System.nanoTime()
        for (a in actions) apply(a)
        val t1 = System.nanoTime()
        refreshOverlay()
        val t2 = System.nanoTime()
        if (t2 - t0 > 16_000_000L) Log.w(HK.TAG_UI, "slow main: actions ${(t1 - t0) / 1_000_000} ms overlay ${(t2 - t1) / 1_000_000} ms $actions")
    }

    private fun apply(a: UiAction) {
        Log.i(HK.TAG_UI, "action $a")
        when (a) {
            UiAction.Leave -> { fadeAndPause("Back at the root"); onLeave?.invoke() }
            UiAction.PlayPause -> { session.onAction(a); media?.setPlaying(session.isPlaying(), positionMs()) }
            else -> session.onAction(a)
        }
    }

    private fun uiEvent(e: UiEvent) {
        w.ui.onEvent(e, facts(), SystemClock.uptimeMillis())
        Log.i(HK.TAG_UI, "event $e")
        refreshOverlay()
    }

    // ── Quality (§5.11): the session applies the ladder, the Q3 rest and the brightness cap ──
    private fun applyQuality(level: Int) {
        session.onThermalLevel(level)
        if (resumed) { gl?.setQuality(quality); applyBrightness() }
        refreshOverlay()
    }

    private fun applyBrightness() {
        val b = if (brightnessOverride > -2f) brightnessOverride else if (sessionCap != -1f) sessionCap else quality.brightnessCap
        onBrightness?.invoke(b)
    }

    // ── CONTROL (§8.2) ──
    fun onControl(b: Bundle) {
        for (k in b.keySet().sorted()) {
            when (k) {
                "gesture" -> gestureOf(b.getString(k))?.let { onGesture(it, "control") } ?: unknown(k, b)
                "view" -> viewOf(b, k)?.let { session.view(it, if (b.containsKey("framing")) b.getInt("framing", 0).coerceIn(0, 1) else session.framing); refreshOverlay() } ?: unknown(k, b)
                "framing" -> if (!b.containsKey("view")) { session.view(session.view, b.getInt(k, 0).coerceIn(0, 1)); refreshOverlay() }
                "instrument" -> b.getString(k)?.let { s -> (InstrumentId.of(s) ?: runCatching { InstrumentId.valueOf(s.uppercase()) }.getOrNull())?.let { session.instrument(it) } } ?: unknown(k, b)
                "pause" -> if (b.getBoolean(k) && session.isPlaying()) session.toggle()
                "resume" -> if (b.getBoolean(k)) { if (!session.isPlaying()) session.toggle(); w.main.postDelayed({ playback.logClock("resume") }, 1000) }
                "seek" -> { @Suppress("DEPRECATION") val v = b.get(k); session.controlSeekDisplayMs((v as? Number)?.toLong() ?: v?.toString()?.toLongOrNull() ?: 0L); refreshOverlay() }
                "rate" -> w.audio.setRate(b.getFloat(k, 1f))
                "leave" -> if (b.getBoolean(k)) apply(UiAction.Leave)
                "quality" -> governor.forcedLevel(b.getInt(k, -1))
                "faketemp" -> governor.fakeTenths(b.getInt(k, -1))
                "recenter" -> if (b.getBoolean(k)) gl?.recenter()
                "brightness" -> { brightnessOverride = b.getFloat(k, -1f).let { if (it < 0f) -2f else it.coerceIn(0.05f, 1f) }; if (resumed) applyBrightness() }
                "debug" -> { debug = b.getBoolean(k); onDebug?.invoke(debug); refreshOverlay() }
                "play" -> b.getString(k)?.let { session.controlPlay(it); enterStage() } ?: unknown(k, b)
                "enter" -> if (b.getBoolean(k)) applyAll(listOf(UiAction.Enter))
                "rescan" -> if (b.getBoolean(k)) session.onAction(UiAction.Rescan)
                "menu" -> if (b.getBoolean(k)) { uiImpl?.openTransport(); refreshOverlay() }
                "library" -> if (b.getBoolean(k)) uiImpl?.let { applyAll(it.openLibrary()) }
                "card" -> uiImpl?.let { u -> when (b.getString(k)) { "floor" -> applyAll(u.openCard(CardKind.FLOOR, facts())); "sync" -> applyAll(u.openCard(CardKind.SYNC, facts())); else -> unknown(k, b) } }
                "bench" -> if (b.getBoolean(k)) playback.bench(b.getInt("benchsecs", 2).coerceIn(1, 30))
                "lowlatency" -> w.settings.putBool("audio.lowLatency", b.getBoolean(k))
                "standin" -> w.settings.putBool(Wiring.KEY_STAND_IN, b.getBoolean(k))              // applies at the next launch
                "stats" -> if (b.getBoolean(k)) playback.logStats()
                "align" -> if (b.getBoolean(k)) playback.align()
                "wavdump" -> playback.captureWav(b.getInt(k, 20).coerceIn(1, 60))
                "selftest" -> if (b.getBoolean(k)) selfTest.run(gl, b.getInt("selftestsecs", 60).coerceIn(1, 600))
                "lead" -> session.onAction(UiAction.SetAvLead(b.getInt(k, 30).coerceIn(0, 400)))
                "sync" -> session.onAction(UiAction.SyncTest(b.getBoolean(k)))
                "glreset" -> if (b.getBoolean(k)) (gl as? com.tropicalstream.hammerklavier.render.HkGlView)?.resetContext()
                "selftestsecs", "benchsecs", "soakplan", "mono", "echo", "n" -> {}
                "gcstats" -> if (b.getBoolean(k)) logGcStats()
                "dump" -> if (b.getBoolean(k)) dump()
                "companion" -> if (b.getBoolean(k)) { Log.i(HK.TAG_UI, "companion url=${session.companionUrl} token=${token()}") }
                "soak" -> if (b.getBoolean(k)) soak.start(b.getString("soakplan")) else soak.stop()
                else -> unknown(k, b)
            }
        }
    }

    private fun viewOf(b: Bundle, k: String): ViewId? {
        @Suppress("DEPRECATION") val v = b.get(k)
        return when (v) { is Int -> ViewId.entries.getOrNull(v); is String -> runCatching { ViewId.valueOf(v.uppercase()) }.getOrNull(); else -> null }
    }

    /** A CONTROL play leaves the title card for the stage (UiEvent.ENTERED). */
    private fun enterStage() {
        if (w.ui.context == UiContext.TITLE) uiEvent(UiEvent.ENTERED)
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
        val lib = session.model
        Log.i(HK.TAG_UI, "dump view=${session.view} framing=${session.framing} resumed=$resumed engine=$engineRunning quality=$quality " +
            "battery=${governor.effectiveTenths} playing=${session.isPlaying()} positionMs=${positionMs()} soak=${soak.recording} " +
            "movement=${session.movementId} instrument=${session.instrument} ui=${w.ui.context} companion=${session.companionUrl} " +
            "works=${lib?.works?.size} movements=${lib?.movements?.size} shelves=${lib?.shelves?.size} imported=${lib?.shelves?.firstOrNull { it.id == "imported" }?.workIds?.size} " +
            "startHere=${lib?.startHere?.size} resume=${session.resume?.movementId}@${session.resume?.songUs}")
        for ((name, m) in listOf("audio" to w.audio.diagnostics(), "gl" to (gl?.diagnostics() ?: emptyMap())))
            Log.i(HK.TAG_UI, "dump $name ${m.entries.joinToString(" ") { "${it.key}=${it.value}" }}")
    }

    private fun perfExtra(): String {
        w.audio.stats(audioStats)
        return "q=${quality.level} underruns=${audioStats.underruns} voices=${audioStats.voices}"
    }

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
        override fun movement() = session.movementId ?: "none"
        override fun positionMs() = this@AppController.positionMs()
        override fun play(id: String) { Log.i(HK.TAG_SOAK, "play $id"); session.controlPlay(id); enterStage() }
        override fun setView(v: ViewId, framing: Int) { session.view(v, framing); refreshOverlay() }
        override fun forceQuality(q: Int) = governor.forcedLevel(q)
        override fun setBrightness(b: Float) { brightnessOverride = b; if (resumed) applyBrightness() }
    }

    private var lastStageHidden: Boolean? = null
    private var lastStatus: String? = null
    private fun refreshOverlay() {
        val o = overlay ?: return
        val f = facts(); shownPlaying = f.playing
        val st = w.ui.render(f, SystemClock.uptimeMillis())
        (st as? UiOverlayState)?.let { u ->
            val sig = "${u.context}/${u.title != null}"
            if (sig != lastOverlaySig) { lastOverlaySig = sig; Log.i(HK.TAG_UI, "overlay context=${u.context} titleCard=${u.title != null}") }
            if (u.status != lastStatus) { lastStatus = u.status; if (u.status != null) Log.i(HK.TAG_UI, "status ${u.status}") }
            if (u.stageHidden != lastStageHidden) { lastStageHidden = u.stageHidden; renderFwd.setStageHidden(u.stageHidden) }
        }
        o.show(st)
    }
    private var lastOverlaySig = ""

    private fun facts(): UiFacts {
        session.debugLine = if (debug) playback.debugLine() else null
        return session.facts()
    }

    companion object {
        const val KEY_TOKEN = "companion.token"
    }
}
