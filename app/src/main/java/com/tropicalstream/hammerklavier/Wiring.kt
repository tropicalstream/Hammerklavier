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
import com.tropicalstream.hammerklavier.contract.stub.NullAudio
import com.tropicalstream.hammerklavier.contract.stub.StubKits
import com.tropicalstream.hammerklavier.contract.stub.StubLibrary
import com.tropicalstream.hammerklavier.contract.stub.StubMechanics
import com.tropicalstream.hammerklavier.contract.stub.StubRoomDesigner
import com.tropicalstream.hammerklavier.contract.stub.StubScenes
import com.tropicalstream.hammerklavier.contract.stub.StubScoreCompiler
import com.tropicalstream.hammerklavier.contract.stub.StubUi
import com.tropicalstream.hammerklavier.contract.stub.android.StubGlHost
import com.tropicalstream.hammerklavier.contract.stub.android.StubOverlay
import java.io.File
import java.util.concurrent.ExecutorService

/**
 * The one place that constructs real or stub components (PLAN §2.2, §7.1 rule 5). Each merge swaps
 * a stub for the real class exactly as that WP's `docs/wiring/WP<N>.md` says; nothing else in the
 * app names a concrete component. Built once by HammerklavierApp (process singletons); the GL view,
 * the overlay and the GL-thread mechanics are made per activity.
 *
 * Current state (contracts-v1.1): every component is a contract stub except Settings (system/).
 */
class Wiring(private val app: Application, val loader: ExecutorService, val voicer: ExecutorService, val main: Handler) {
    val post: (Runnable) -> Unit = { r -> main.post(r) }

    val settings: SettingsStore = Settings(app)                           // system/Settings (typed SharedPreferences)
    /** Written by main (GazeCamera, WP6), read by HKAudio and GLThread. */
    val head = HeadPose()
    /** Written by HKAudio, read by HKPrefetch (WP4). */
    val cursors = VoiceCursorBoard()
    val compiler: ScoreCompiler = StubScoreCompiler()                    // WP1: midi.ScoreCompilerImpl()
    val kits: KitService = StubKits(post)                                // WP4: audio.KitManager(ctx, voicer, loader)
    val audio: AudioControl = NullAudio()                                // WP4: audio.AudioOutput(ctx, EngineCore(...), cursors, head, settings)
    val library: LibraryService = StubLibrary(                           // WP9: library.android.LibraryServiceImpl(ctx, compiler)
        readAsset = { path -> runCatching { app.assets.open(path).use { it.readBytes() } }.getOrNull() },
        scoresDir = File(app.getExternalFilesDir(null) ?: app.filesDir, "Scores"))
    val designer: RoomDesigner = StubRoomDesigner                        // WP3: dsp.RoomAcoustics
    val scenes: SceneFactory = StubScenes()                              // WP7/WP8: Instruments + VenueSceneImpl()
    val ui: UiStateMachine = StubUi()                                    // WP10: ui.model.UiStateMachineImpl()

    /** GLThread-owned; one per GL view. WP5: mech.MechanicsEvaluatorImpl(). */
    fun mechanics(): MechanicsEvaluator = StubMechanics()

    /** WP6: render.HkGlView(ctx, loader, msaa). */
    fun glHost(ctx: Context, msaa: Boolean): GlHost = StubGlHost(ctx)

    /** WP10: ui.OverlayViews(ctx). */
    fun overlay(ctx: Context): OverlayHost = StubOverlay(ctx)
}
