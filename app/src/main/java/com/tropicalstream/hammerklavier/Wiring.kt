package com.tropicalstream.hammerklavier

import android.app.Application
import android.content.Context
import android.os.Handler
import com.tropicalstream.hammerklavier.contract.AudioControl
import com.tropicalstream.hammerklavier.contract.KitService
import com.tropicalstream.hammerklavier.contract.LibraryService
import com.tropicalstream.hammerklavier.contract.MechanicsEvaluator
import com.tropicalstream.hammerklavier.contract.RoomDesigner
import com.tropicalstream.hammerklavier.contract.SceneFactory
import com.tropicalstream.hammerklavier.contract.ScoreCompiler
import com.tropicalstream.hammerklavier.contract.SettingsStore
import com.tropicalstream.hammerklavier.contract.UiStateMachine
import com.tropicalstream.hammerklavier.contract.android.GlHost
import com.tropicalstream.hammerklavier.contract.android.OverlayHost
import com.tropicalstream.hammerklavier.contract.HeadPose
import com.tropicalstream.hammerklavier.contract.VoiceCursorBoard
import com.tropicalstream.hammerklavier.system.Settings
import com.tropicalstream.hammerklavier.audio.AudioOutput
import com.tropicalstream.hammerklavier.audio.KitManager
import com.tropicalstream.hammerklavier.dsp.DspFactory
import com.tropicalstream.hammerklavier.dsp.RoomAcoustics
import com.tropicalstream.hammerklavier.engine.EngineCore
import com.tropicalstream.hammerklavier.midi.ScoreCompilerImpl
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.library.android.LibraryServiceImpl
import com.tropicalstream.hammerklavier.mech.MechanicsEvaluatorImpl
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentLook
import com.tropicalstream.hammerklavier.contract.InstrumentScene
import com.tropicalstream.hammerklavier.contract.VenueScene
import com.tropicalstream.hammerklavier.contract.stub.StubVenue
import com.tropicalstream.hammerklavier.contract.stub.StubUi
import com.tropicalstream.hammerklavier.render.HkGlView
import com.tropicalstream.hammerklavier.contract.stub.android.StubOverlay
import java.io.File
import java.util.concurrent.ExecutorService

/**
 * The one place that constructs real or stub components (PLAN §2.2, §7.1 rule 5). Each merge swaps
 * a stub for the real class exactly as that WP's `docs/wiring/WP<N>.md` says; nothing else in the
 * app names a concrete component. Built once by HammerklavierApp (process singletons); the GL view,
 * the overlay and the GL-thread mechanics are made per activity.
 *
 * Current state (M3): WP1, WP2, WP3, WP4 (real grand), WP5 mechanics, WP6 GL host, WP7 instruments, WP9 library are real;
 * venue, UI and overlay are still contract stubs.
 */
class Wiring(val app: Application, val loader: ExecutorService, val voicer: ExecutorService, val main: Handler) {
    val post: (Runnable) -> Unit = { r -> main.post(r) }

    val settings: SettingsStore = Settings(app)                           // system/Settings (typed SharedPreferences)
    /** Written by main (GazeCamera, WP6), read by HKAudio and GLThread. */
    val head = HeadPose()
    /** Written by HKAudio, read by HKPrefetch (WP4). */
    val cursors = VoiceCursorBoard()
    val compiler: ScoreCompiler = ScoreCompilerImpl()                    // WP1
    /** M2: the real kits; `--ez standin true` restores the WP11 stand-in (stub) bank. */
    val kits: KitService = KitManager(app, voicer, loader) { settings.getBool(KEY_STAND_IN, false) }   // WP4
    /** WP2 engine with WP3's DspSet; the concrete handle is kept here only for the EngineBench results. */
    val engine = EngineCore(DspFactory.create(HK.SR), cursors, head, HK.SR)
    val audio: AudioControl = AudioOutput(app, engine, cursors, head, settings)   // WP4
    val library: LibraryService = LibraryServiceImpl(app, compiler)      // WP9 (bundled-only at M2)
    val designer: RoomDesigner = RoomAcoustics                            // WP3
    val scenes: SceneFactory = object : SceneFactory {                  // WP7 instruments; WP8 venue still stub
        override fun instrument(id: InstrumentId, look: InstrumentLook, lastDamper: Int): InstrumentScene =
            com.tropicalstream.hammerklavier.instrument.Instruments.create(id, look, lastDamper)
        override fun venue(): VenueScene = StubVenue()                  // WP8: venue.VenueSceneImpl()
    }
    val ui: UiStateMachine = StubUi()                                    // WP10: ui.model.UiStateMachineImpl()

    /** GLThread-owned; one per GL view. WP5: mech.MechanicsEvaluatorImpl(). */
    fun mechanics(): MechanicsEvaluator = MechanicsEvaluatorImpl()

    /** WP6: render.HkGlView(ctx, loader, msaa). */
    fun glHost(ctx: Context, msaa: Boolean): GlHost = HkGlView(ctx, loader = loader, msaa = msaa, head = head)

    /** WP10: ui.OverlayViews(ctx). */
    fun overlay(ctx: Context): OverlayHost = StubOverlay(ctx)

    companion object {
        const val KEY_STAND_IN = "kit.standIn"
    }
}
