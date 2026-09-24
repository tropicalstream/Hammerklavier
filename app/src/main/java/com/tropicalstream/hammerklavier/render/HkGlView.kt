package com.tropicalstream.hammerklavier.render

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Choreographer
import android.view.View
import com.tropicalstream.hammerklavier.contract.EnergyRing
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.HeadPose
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentLook
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.MechanicsEvaluator
import com.tropicalstream.hammerklavier.contract.Performance
import com.tropicalstream.hammerklavier.contract.QualityProfile
import com.tropicalstream.hammerklavier.contract.RenderOverrides
import com.tropicalstream.hammerklavier.contract.RenderSettings
import com.tropicalstream.hammerklavier.contract.RenderStats
import com.tropicalstream.hammerklavier.contract.SceneFactory
import com.tropicalstream.hammerklavier.contract.SongClock
import com.tropicalstream.hammerklavier.contract.ViewId
import com.tropicalstream.hammerklavier.contract.android.GlHost
import java.util.concurrent.ExecutorService
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/**
 * The GL view (PLAN §5.1, WP6): a GLSurfaceView implementing [GlHost]. ES 2.0,
 * `preserveEGLContextOnPause`, the EGL chooser (RGB888 + depth 24 + 4× MSAA if [msaa], then
 * RGB888 + depth 24, then the default; no stencil; fixed for the session), and Choreographer
 * pacing with `RENDERMODE_WHEN_DIRTY`: every 2nd vsync (30 fps), 3rd at Q2 (20 fps), 6th when
 * idle (paused, no dip, head still: 10 fps), none in display rest (one black frame) or paused.
 *
 * Every RenderControl call only sets fields of the renderer's desired state (§2.1 rule 6); scene
 * builds are kicked onto [loader] from here so they progress while GL is paused or resting.
 * [head] receives GazeCamera's yaw and ω for the audio (§3.12).
 */
class HkGlView(ctx: Context, loader: ExecutorService?, private val msaa: Boolean, head: HeadPose? = null) :
    GLSurfaceView(ctx), GlHost, Choreographer.FrameCallback {
    override val view: View get() = this

    val renderer = StereoRenderer(loader)
    val gaze = GazeCamera(ctx, head)
    private val chooser = Chooser(msaa)
    @Volatile private var paced = false
    private var resumed = false
    private var vsyncs = 0            // pacer thread only
    // M8: pacing runs on its own looper's Choreographer, so a stalled main thread (HWUI's first-install
    // shader compile on the first menu open blocked the next UI traversal ~130 ms) no longer delays requestRender.
    private val pacerThread = android.os.HandlerThread("HKPacer", android.os.Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
    private val pacer = android.os.Handler(pacerThread.looper)
    private var resting = false
    @Volatile private var restFrameDone = false

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setEGLConfigChooser(chooser)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        renderer.gazeSource = gaze.filter
        @Suppress("KotlinConstantConditions")
        if (com.tropicalstream.hammerklavier.BuildConfig.DEBUG) renderer.debugAlloc = true
    }

    // ── Choreographer pacing (HKPacer thread) ──
    override fun doFrame(frameTimeNanos: Long) {
        if (!paced) return
        renderer.desired.vsyncNanos = frameTimeNanos
        val div = divider()
        if (div > 0) {
            if (++vsyncs >= div) { vsyncs = 0; requestRender() }
        } else {
            // display rest: one black frame, then pacing stops until setQuality ends the rest (§5.1)
            if (!restFrameDone) { restFrameDone = true; requestRender() }
            paced = false
            return
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    /** 0 = display rest (no frames after the black one). */
    fun divider(): Int {
        val d = renderer.desired
        val q = d.quality.frameDivider
        if (q == 0) return 0
        val idle = d.idle && !renderer.dipping && renderer.headStill
        return if (idle) IDLE_DIVIDER else maxOf(q, 2)
    }

    private fun startPacing() {
        paced = true
        pacer.post {
            val c = Choreographer.getInstance()
            c.removeFrameCallback(this)          // WanderQuest: never two callbacks after a resume
            if (paced) c.postFrameCallback(this)
        }
    }

    private fun stopPacing() { paced = false; pacer.post { Choreographer.getInstance().removeFrameCallback(this) } }

    // ── RenderControl (main): fields only ──
    override fun bind(clock: SongClock, energy: EnergyRing, mech: MechanicsEvaluator, scenes: SceneFactory) {
        val d = renderer.desired
        d.clock = clock; d.energy = energy; d.mech = mech; d.scenes = scenes
        renderer.kickBuild()
    }

    override fun setPerformance(p: Performance?, profile: InstrumentProfile) = renderer.setPerformance(p, profile)

    override fun setInstrument(id: InstrumentId, look: InstrumentLook, lastDamper: Int) {
        val d = renderer.desired
        d.instrument = id; d.look = look; d.lastDamper = lastDamper
        d.instrumentSerial = d.instrumentSerial + 1
        renderer.kickBuild()
    }

    /** Set by the controller just before a touch-swipe's setView: the pad event's time (System.nanoTime base). */
    @Volatile var pendingInputNanos = 0L

    override fun setView(v: ViewId, framing: Int) {
        val d = renderer.desired
        d.inputNanos = pendingInputNanos; pendingInputNanos = 0L
        d.view = v; d.framing = framing.coerceIn(0, 1); d.viewNanos = System.nanoTime(); d.viewSerial = d.viewSerial + 1
    }

    override fun setQuality(q: QualityProfile) {
        val d = renderer.desired
        val wasResting = d.quality.frameDivider == 0
        d.quality = q
        val nowResting = q.frameDivider == 0
        if (nowResting && !wasResting) { restFrameDone = false; resting = true; Log.i(HK.TAG_RENDER, "display rest: GL paused") }
        if (!nowResting && wasResting) {
            resting = false; d.wokeSerial = d.wokeSerial + 1; Log.i(HK.TAG_RENDER, "display rest over")
            if (resumed && !paced) startPacing()
        }
    }

    override fun setSettings(s: RenderSettings) {
        renderer.desired.settings = s
        gaze.enabled = s.lookAround
        renderer.kickBuild()
    }

    override fun setStereo(on: Boolean) { renderer.desired.stereo = on }
    override fun setIdle(idle: Boolean) { renderer.desired.idle = idle }
    override fun setSyncFlash(on: Boolean) { renderer.desired.syncFlash = on; renderer.syncEnabled(on) }
    override fun setTitle(text: String?) { renderer.desired.title = text }
    override fun setStageHidden(hidden: Boolean) { renderer.desired.stageHidden = hidden }
    override fun setOverrides(o: RenderOverrides) { renderer.desired.overrides = o }
    override fun recenter() { val d = renderer.desired; d.recenterSerial = d.recenterSerial + 1 }

    override fun onResume() {
        super.onResume()
        resumed = true
        renderer.desired.wokeSerial = renderer.desired.wokeSerial + 1
        gaze.start()
        startPacing()
    }

    override fun onPause() {
        resumed = false
        stopPacing()
        gaze.stop()
        // §5.1: leave one black frame on the waveguide. Queued events run before the GL thread
        // honours the pause, so this lands ahead of it.
        queueEvent { renderer.presentBlack() }
        super.onPause()
    }

    /**
     * Debug (`--ez glreset true`, T-GLRESET): a real context loss on this view and this renderer.
     * Releasing the context on pause makes the next resume create a new one, so the renderer's
     * second onSurfaceCreated bumps glGeneration and re-uploads from the resident arrays.
     */
    fun resetContext() {
        if (!resumed) return
        preserveEGLContextOnPause = false
        onPause()                        // returns once the GL thread has paused and dropped the context
        preserveEGLContextOnPause = true
        onResume()
    }

    override fun stats(out: RenderStats) {
        renderer.stats(out)
        out.divider = divider()
    }

    override fun diagnostics(): Map<String, String> = mapOf(
        "host" to "HkGlView",
        "gl" to renderer.glInfo,
        "egl" to chooser.chosen,
        "eglSamples" to chooser.samples.toString(),
        "msaaRequested" to msaa.toString(),
        "glGeneration" to renderer.glGeneration.toString(),
        "frames" to renderer.frames.toString(),
        "drawsPerEye" to renderer.drawsPerEye.toString(),
        "maxDrawsPerEye" to renderer.maxDrawsPerEye.toString(),
        "trisPerEye" to renderer.trisPerEye.toString(),
        "glErrors" to renderer.glErrors.toString(),
        "glAllocs" to renderer.glAllocs.toString(),
        "divider" to divider().toString(),
        "resting" to resting.toString(),
        "gaze" to gaze.available().toString())

    /** EGL chooser (§5.1): 4× MSAA if asked, then RGB888 + D24, then the default; no stencil. */
    private class Chooser(private val msaa: Boolean) : EGLConfigChooser {
        @Volatile var chosen = ""
        @Volatile var samples = 0

        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            val tries = ArrayList<IntArray>()
            if (msaa) tries.add(attribs(4))
            tries.add(attribs(0))
            for (a in tries) {
                val n = IntArray(1)
                val cfgs = arrayOfNulls<EGLConfig>(1)
                if (egl.eglChooseConfig(display, a, cfgs, 1, n) && n[0] > 0 && cfgs[0] != null) {
                    describe(egl, display, cfgs[0]!!)
                    return cfgs[0]!!
                }
            }
            // The default: any ES 2 window config.
            val def = intArrayOf(EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL10.EGL_NONE)
            val n = IntArray(1); val cfgs = arrayOfNulls<EGLConfig>(1)
            egl.eglChooseConfig(display, def, cfgs, 1, n)
            val c = cfgs[0] ?: throw IllegalStateException("no EGL config")
            describe(egl, display, c)
            return c
        }

        private fun attribs(samples: Int): IntArray {
            val l = arrayListOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 24, EGL10.EGL_STENCIL_SIZE, 0, EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT)
            if (samples > 0) { l += listOf(EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, samples) }
            l += EGL10.EGL_NONE
            return l.toIntArray()
        }

        private fun describe(egl: EGL10, d: EGLDisplay, c: EGLConfig) {
            fun get(a: Int): Int { val v = IntArray(1); egl.eglGetConfigAttrib(d, c, a, v); return v[0] }
            samples = get(EGL10.EGL_SAMPLES)
            chosen = "r" + get(EGL10.EGL_RED_SIZE) + "g" + get(EGL10.EGL_GREEN_SIZE) + "b" + get(EGL10.EGL_BLUE_SIZE) +
                "a" + get(EGL10.EGL_ALPHA_SIZE) + " depth=" + get(EGL10.EGL_DEPTH_SIZE) + " stencil=" + get(EGL10.EGL_STENCIL_SIZE) +
                " EGL_SAMPLES=" + samples
            Log.i(HK.TAG_RENDER, "HKRender cfg=$chosen")
        }

        companion object { const val EGL_OPENGL_ES2_BIT = 4 }
    }

    companion object { const val IDLE_DIVIDER = 6 }
}
