package com.tropicalstream.hammerklavier.contract.stub.android

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Choreographer
import android.view.View
import com.tropicalstream.hammerklavier.contract.EnergyRing
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentLook
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.MechanicsEvaluator
import com.tropicalstream.hammerklavier.contract.Pal
import com.tropicalstream.hammerklavier.contract.Performance
import com.tropicalstream.hammerklavier.contract.QualityProfile
import com.tropicalstream.hammerklavier.contract.RenderOverrides
import com.tropicalstream.hammerklavier.contract.RenderSettings
import com.tropicalstream.hammerklavier.contract.RenderStats
import com.tropicalstream.hammerklavier.contract.SceneFactory
import com.tropicalstream.hammerklavier.contract.SongClock
import com.tropicalstream.hammerklavier.contract.ViewId
import com.tropicalstream.hammerklavier.contract.android.GlHost
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The GL host until WP6 merges (PLAN §2.2, M0): an ES 2.0 GLSurfaceView that clears to black
 * (transparent on the waveguide), logs the GL info once (`HKRender`), and draws a gilt test frame
 * in each eye's viewport with scissored clears (no shaders). Paced by a main Handler (M1: not Choreographer, see pace): every 6th
 * vsync (10 fps); nothing while paused or in display rest. RenderControl calls only set fields
 * (§2.1 rule 6); every GL call happens in onSurfaceCreated/onDrawFrame.
 */
class StubGlHost(ctx: Context) : GLSurfaceView(ctx), GlHost, GLSurfaceView.Renderer, Choreographer.FrameCallback {
    override val view: View get() = this

    @Volatile private var stereo = true
    @Volatile private var divider = 6
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var resting = false
    @Volatile private var glInfo = ""
    @Volatile private var frames = 0
    @Volatile private var glGeneration = 0
    private var vsyncs = 0
    private var paced = false
    private var surfaceW = 0
    private var surfaceH = 0
    private val gilt = floatArrayOf(Pal.GILT_LIT[0] / 255f, Pal.GILT_LIT[1] / 255f, Pal.GILT_LIT[2] / 255f)

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setEGLConfigChooser(8, 8, 8, 0, 16, 0)
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    // ── GLSurfaceView.Renderer (GLThread) ──
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        glGeneration++
        val uv = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_VERTEX_UNIFORM_VECTORS, uv, 0)
        glInfo = "renderer=${GLES20.glGetString(GLES20.GL_RENDERER)} version=${GLES20.glGetString(GLES20.GL_VERSION)} " +
            "maxVertexUniformVectors=${uv[0]} glGeneration=$glGeneration"
        Log.i(HK.TAG_RENDER, "StubGlHost $glInfo")
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { surfaceW = width; surfaceH = height }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (resting || surfaceW == 0) return
        val eyes = if (stereo) 2 else 1
        val ew = surfaceW / eyes
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(gilt[0], gilt[1], gilt[2], 1f)
        val t = 3                                   // frame line width, px
        val m = 24                                  // inset from the eye's edge, px
        for (e in 0 until eyes) {
            val x0 = e * ew + m; val x1 = (e + 1) * ew - m
            val y0 = m; val y1 = surfaceH - m
            scissorClear(x0, y0, x1 - x0, t); scissorClear(x0, y1 - t, x1 - x0, t)
            scissorClear(x0, y0, t, y1 - y0); scissorClear(x1 - t, y0, t, y1 - y0)
        }
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        frames++
    }

    private fun scissorClear(x: Int, y: Int, w: Int, h: Int) {
        GLES20.glScissor(x, y, w, h); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    // ── Pacing (main) ──
    // A main-thread Handler tick, not a Choreographer frame callback: on this device Qualcomm's
    // BoostFramework.ScrollOptimizer.setVsyncTime builds strings on every vsync a Choreographer
    // delivers (~1.5 KB each, ~92 KB/s at 60 Hz), which alone broke T-GC at M1. The stub needs no
    // vsync phase; WP6's renderer is told in docs/requests/WP6.md.
    private val pace = object : Runnable {
        override fun run() {
            if (!paced) return
            requestRender()
            handler.postDelayed(this, divider * VSYNC_MS)
        }
    }
    override fun doFrame(frameTimeNanos: Long) {}

    private fun startPacing() { handler.removeCallbacks(pace); paced = true; handler.post(pace) }

    private fun stopPacing() { paced = false; handler.removeCallbacks(pace) }

    // ── RenderControl (main): fields only ──
    override fun bind(clock: SongClock, energy: EnergyRing, mech: MechanicsEvaluator, scenes: SceneFactory) {}
    override fun setPerformance(p: Performance?, profile: InstrumentProfile) {}
    override fun setInstrument(id: InstrumentId, look: InstrumentLook, lastDamper: Int) {}
    override fun setView(v: ViewId, framing: Int) {}
    override fun setQuality(q: QualityProfile) {
        resting = q.frameDivider == 0
        if (!resting) divider = maxOf(q.frameDivider, 2) * 3     // the stub runs at a third of the ladder's rate
        requestRender()
    }
    override fun setSettings(s: RenderSettings) {}
    override fun setStereo(on: Boolean) { stereo = on; requestRender() }
    override fun setIdle(idle: Boolean) {}
    override fun setSyncFlash(on: Boolean) {}
    override fun setTitle(text: String?) {}
    override fun setStageHidden(hidden: Boolean) {}
    override fun setOverrides(o: RenderOverrides) {}
    override fun recenter() {}
    override fun onResume() { super.onResume(); startPacing() }
    override fun onPause() { stopPacing(); super.onPause() }
    override fun stats(out: RenderStats) {
        out.fps = 0f; out.draws = 0; out.tris = 0; out.divider = divider; out.dipping = false; out.glGeneration = glGeneration
    }
    override fun diagnostics(): Map<String, String> = mapOf("gl" to glInfo, "frames" to frames.toString(), "host" to "StubGlHost")
    private companion object { const val VSYNC_MS = 17L }
}
