package com.tropicalstream.hammerklavier.render

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Debug
import android.util.Log
import com.tropicalstream.hammerklavier.contract.CameraPose
import com.tropicalstream.hammerklavier.contract.ClockSample
import com.tropicalstream.hammerklavier.contract.EnergyRing
import com.tropicalstream.hammerklavier.contract.FlameField
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentLook
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.InstrumentScene
import com.tropicalstream.hammerklavier.contract.KonzertzimmerAcoustics
import com.tropicalstream.hammerklavier.contract.LightRig
import com.tropicalstream.hammerklavier.contract.MechanicsEvaluator
import com.tropicalstream.hammerklavier.contract.MechanismPose
import com.tropicalstream.hammerklavier.contract.Pal
import com.tropicalstream.hammerklavier.contract.Palette
import com.tropicalstream.hammerklavier.contract.Performance
import com.tropicalstream.hammerklavier.contract.Placement
import com.tropicalstream.hammerklavier.contract.QualityLadder
import com.tropicalstream.hammerklavier.contract.QualityProfile
import com.tropicalstream.hammerklavier.contract.RenderOverrides
import com.tropicalstream.hammerklavier.contract.RenderSettings
import com.tropicalstream.hammerklavier.contract.RenderStats
import com.tropicalstream.hammerklavier.contract.RoomLevel
import com.tropicalstream.hammerklavier.contract.SceneFactory
import com.tropicalstream.hammerklavier.contract.SongClock
import com.tropicalstream.hammerklavier.contract.UprightFinish
import com.tropicalstream.hammerklavier.contract.ViewId
import com.tropicalstream.hammerklavier.contract.VisTime
import com.tropicalstream.hammerklavier.contract.VisualClock
import com.tropicalstream.hammerklavier.contract.android.CanvasPainter
import com.tropicalstream.hammerklavier.geom.CameraDirector
import com.tropicalstream.hammerklavier.geom.Gaze
import com.tropicalstream.hammerklavier.geom.Mat4
import com.tropicalstream.hammerklavier.geom.SinTable
import com.tropicalstream.hammerklavier.geom.StereoRig
import java.util.concurrent.ExecutorService
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Everything a built scene needs on the GL thread; made whole on HKLoader and handed over by a
 * @Volatile reference (PLAN §2.1 rules 2 and 4). Mesh arrays and texture RGBA stay resident for
 * context loss (§5.1).
 */
class BuiltScene(val request: Long, val id: InstrumentId, val profile: InstrumentProfile, val instrument: InstrumentScene,
    val flames: FlameField, val placement: Placement, val assembled: AssembledScene, val textures: List<ResidentTexture>,
    val probe: ResidentTexture, val palette: Palette)

/**
 * The GL renderer (PLAN §5.1–§5.3): reconcile the desired state → clock → VisualClock → mechanics
 * → camera → uniforms once → two eyes. The main thread only writes [Desired] fields (no GL calls,
 * §2.1 rule 6); scenes are built on HKLoader and swapped in under the next dip (instantly after a
 * display rest or with the display off); a GL generation counter re-creates every handle after a
 * context loss from the resident arrays. Allocation-free in the steady state (§2.1 rule 1).
 */
class StereoRenderer(private val loader: ExecutorService?,
                     private val painterFactory: (() -> com.tropicalstream.hammerklavier.contract.Painter2D)? = null) : GLSurfaceView.Renderer {

    /** Written by main (HkGlView), read by GL. */
    class Desired {
        @Volatile var clock: SongClock? = null
        @Volatile var energy: EnergyRing? = null
        @Volatile var mech: MechanicsEvaluator? = null
        @Volatile var scenes: SceneFactory? = null
        @Volatile var instrument = InstrumentId.GRAND
        @Volatile var look = InstrumentLook(UprightFinish.WALNUT, false)
        @Volatile var lastDamper = 88
        @Volatile var instrumentSerial = 0L
        @Volatile var view = ViewId.PLAYER
        @Volatile var framing = 0
        @Volatile var viewSerial = 0L
        @Volatile var viewNanos = 0L
        @Volatile var inputNanos = 0L       // the pad event behind this view change (0 = CONTROL)
        @Volatile var quality: QualityProfile = QualityLadder.of(0, 96)
        @Volatile var settings: RenderSettings? = null
        @Volatile var stereo = true
        @Volatile var idle = false
        @Volatile var syncFlash = false
        @Volatile var title: String? = null
        @Volatile var stageHidden = false
        @Volatile var overrides = RenderOverrides()
        @Volatile var recenterSerial = 0L
        /** Set by main when GL resumes after a pause or a display rest: the next cut is immediate. */
        @Volatile var wokeSerial = 0L
        @Volatile var vsyncNanos = 0L
        val lock = Any()
        // two Performance slots, keyed by generation (under lock)
        val slotPerf = arrayOfNulls<Performance>(2)
        val slotProfile = arrayOfNulls<InstrumentProfile>(2)
        val slotGen = intArrayOf(Int.MIN_VALUE, Int.MIN_VALUE)
        var lastSlot = 1
    }

    val desired = Desired()
    /** The GazeCamera (main writes, GL reads its volatile yaw/pitch). */
    @Volatile var gazeSource: GazeFilter? = null

    // ── GL-thread state ──
    private val programs = com.tropicalstream.hammerklavier.render.gl.Programs()
    private val packer = UniformPacker()
    private val textures = TextureUploader()
    private val drawer = ItemDrawer(programs, packer, textures)
    private val inset = PedalInset(drawer)
    private val fader = DipFader()
    private val sync = SyncFlash(fader)
    private val glyphs = GlyphBoard()
    private val director = CameraDirector()
    private val rig = StereoRig()
    private val frame = FrameUniforms()
    private val sample = ClockSample()
    private val vis = VisTime()
    private val visual = VisualClock()
    private val pose = MechanismPose()
    private val cam = CameraPose()
    private val gaze = Gaze()
    private val energyBuf = FloatArray(HK.LANES)
    private val lightRig = LightRig()
    private var sprites = SpriteBatch(64)
    private val noteNames = Array(HK.KEYS) { k -> NOTE[k % 12] + (k / 12 - 1) }
    private val titleModel = FloatArray(16)
    private val tmpM = FloatArray(16)
    private val tmp3 = FloatArray(3)
    private val white = floatArrayOf(Pal.HUD_TEXT[0] / 255f, Pal.HUD_TEXT[1] / 255f, Pal.HUD_TEXT[2] / 255f)

    @Volatile private var current: BuiltScene? = null
    /** The instrument on screen (diagnostics, T-Q3SWITCH). */
    val currentInstrument: InstrumentId? get() = current?.id
    /** The view and framing on screen. */
    val shownView: ViewId get() = director.view
    val shownFraming: Int get() = director.framing
    @Volatile private var ready: BuiltScene? = null
    /** The last scene build failure (diagnostics). */
    @Volatile var lastBuildError: String? = null; private set
    @Volatile private var building = -1L
    @Volatile private var buildPalette: Palette? = null
    private var boundSlot = -2
    private var boundGen = Int.MIN_VALUE
    private var seenViewSerial = -1L
    private var seenWoke = 0L
    private var seenRecenter = 0L
    private var width = 0
    private var height = 0
    @Volatile private var gen = 0
    val glGeneration: Int get() = gen

    /** Main: the A/V sync disc on or off. */
    fun syncEnabled(on: Boolean) { sync.enabled = on }
    private var surfaces = 0
    private var uploadedGen = -1
    private var lastNanos = 0L
    /** The first frame after a pause or rest is not a hitch. */
    private var resumedGap = true
    private var realSec = 0f
    private var lastGazeYaw = 0f
    private var lastGazePitch = 0f
    /** The cutaway note label's anchor (piano frame y, z) for the instrument on screen (§5.6, §5.10). */
    private var labelY = 0.93f
    private var labelZ = -0.24f

    // ── stats (written on GL, read on main) ──
    @Volatile var fps = 0f; private set
    @Volatile var drawsPerEye = 0; private set
    @Volatile var trisPerEye = 0; private set
    @Volatile var hitches = 0; private set
    @Volatile var lateFrames = 0; private set
    @Volatile var energyMiss = 0; private set
    @Volatile var headStill = true; private set
    @Volatile var dipping = false; private set
    @Volatile var frames = 0L; private set
    @Volatile var glErrors = 0; private set
    @Volatile var glAllocs = -1; private set
    @Volatile var glInfo = ""; private set
    @Volatile var maxDrawsPerEye = 0; private set
    @Volatile var syncFrames = 0; private set
    /** glUniform4fv calls in the last frame (§5.8: ≤ 20). */
    @Volatile var uniform4fvPerFrame = 0; private set
    private val cpuUs = IntArray(128)
    private val lateUs = IntArray(128)
    private var ringI = 0

    // ───────────────────────── main-thread API (fields only) ─────────────────────────

    fun setPerformance(p: Performance?, profile: InstrumentProfile) {
        val d = desired
        synchronized(d.lock) {
            val s = 1 - d.lastSlot
            d.slotPerf[s] = p; d.slotProfile[s] = profile; d.slotGen[s] = p?.generation ?: Int.MIN_VALUE + 1
            d.lastSlot = s
        }
    }

    /** The two slots' generations, for tests and diagnostics. */
    fun slotGenerations(out: IntArray) { synchronized(desired.lock) { out[0] = desired.slotGen[0]; out[1] = desired.slotGen[1] } }

    fun stats(out: RenderStats) {
        out.fps = fps; out.draws = drawsPerEye; out.tris = trisPerEye; out.hitches = hitches
        out.dipping = dipping; out.lateFrames = lateFrames; out.glGeneration = gen; out.energyMiss = energyMiss
        out.headYawRad = gazeSource?.yaw ?: 0f
        out.cpuUsP99 = p99(cpuUs); out.lateP99Us = p99(lateUs)
    }

    private fun p99(a: IntArray): Int { val c = a.copyOf(); c.sort(); return c[(c.size * 99) / 100 - 1] }

    // ───────────────────────── GLSurfaceView.Renderer ─────────────────────────

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        surfaces++
        if (surfaces > 1) {
            // Context loss: every handle belonged to the old context. Drop them without glDelete.
            gen++
            programs.discard(); current?.assembled?.discardGl(); textures.discardGl(); glyphs.release()
            com.tropicalstream.hammerklavier.render.gl.GlKit.contextLost()
            uploadedGen = -1
        }
        val uv = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_VERTEX_UNIFORM_VECTORS, uv, 0)
        glInfo = "renderer=" + GLES20.glGetString(GLES20.GL_RENDERER) + " version=" + GLES20.glGetString(GLES20.GL_VERSION) +
            " maxVertexUniformVectors=" + uv[0] + " glGeneration=" + gen
        Log.i(HK.TAG_RENDER, glInfo)
        if (uv[0] in 1 until 128) Log.w(HK.TAG_RENDER, "GL_MAX_VERTEX_UNIFORM_VECTORS=" + uv[0] + " < 128 (the engine needs >= 64)")
        try { programs.compileAll() } catch (e: IllegalStateException) { Log.e(HK.TAG_RENDER, "program build failed", e) }
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glCullFace(GLES20.GL_BACK)
        GLES20.glFrontFace(GLES20.GL_CCW)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        glErrors += com.tropicalstream.hammerklavier.render.gl.GlKit.checkError("onSurfaceCreated")
        director.cutImmediately()
    }

    /** GLThread, outside a frame (a queued event): clear to black and present it (§5.1 pause). */
    fun presentBlack() {
        val dpy = android.opengl.EGL14.eglGetCurrentDisplay()
        val srf = android.opengl.EGL14.eglGetCurrentSurface(android.opengl.EGL14.EGL_DRAW)
        if (dpy == null || srf == null || srf == android.opengl.EGL14.EGL_NO_SURFACE) return
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        android.opengl.EGL14.eglSwapBuffers(dpy, srf)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) { width = w; height = h; GLES20.glViewport(0, 0, w, h) }

    override fun onDrawFrame(gl: GL10?) {
        val t0 = System.nanoTime()
        val d = desired
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val vs = d.vsyncNanos
        val frameNanos = if (vs in 1..t0) vs else t0
        var dt = if (lastNanos == 0L) 0.033f else (t0 - lastNanos) * 1e-9f
        if (lastNanos != 0L && dt > 0.12f && d.quality.frameDivider != 0 && !resumedGap && d.wokeSerial == seenWoke) { hitches++; Log.w(HK.TAG_RENDER, "FRAME HITCH") }
        resumedGap = false
        lastNanos = t0
        if (dt < 0f) dt = 0f else if (dt > 0.05f) dt = 0.05f
        realSec += dt
        if (d.quality.frameDivider == 0) {                                                  // display rest: one black frame
            reconcile(d, true); director.cutImmediately(); finish(t0, t0 - frameNanos); return
        }
        var woke = false
        if (d.wokeSerial != seenWoke) { seenWoke = d.wokeSerial; director.cutImmediately(); woke = true; resumedGap = true }
        reconcile(d, woke)
        val sc = current
        if (sc == null || !programs.ready || width == 0) { finish(t0, t0 - frameNanos); return }
        if (uploadedGen != gen) { sc.assembled.upload(gen); textures.uploadAll(gen); uploadedGen = gen }

        // clock → VisualClock → mechanics
        val settings = d.settings
        val leadNs = (settings?.displayLeadMs ?: 30) * 1_000_000L
        val clock = d.clock
        val mech = d.mech
        val energyOk: Boolean
        if (clock != null) {
            clock.sample((if (frameNanos > t0) frameNanos else t0) + leadNs, sample)
            val e = d.energy
            energyOk = e != null && sample.valid && e.read(sample.heardFrame, sample.epoch, energyBuf)
            if (!energyOk && sample.valid && sample.playing) energyMiss++
        } else { sample.valid = false; sample.playing = false; energyOk = false }
        visual.update(sample, t0, vis)
        bindSlot(d, vis.generation, vis.reseed, mech, sc.profile)
        if (mech != null && boundSlot >= 0) mech.evaluate(vis, if (energyOk) energyBuf else null, dt, pose) else restPose()
        auditStrikes()

        // camera
        val gz = gazeSource
        if (d.recenterSerial != seenRecenter) { seenRecenter = d.recenterSerial; gz?.recenter() }
        val look = settings?.lookAround ?: true
        gaze.yaw = if (gz != null && look) gz.yaw else 0f
        gaze.pitch = if (gz != null && look) gz.pitch else 0f
        gz?.worldLocked = director.worldLocked
        val dy = gaze.yaw - lastGazeYaw; lastGazeYaw = gaze.yaw
        val dp = gaze.pitch - lastGazePitch; lastGazePitch = gaze.pitch
        headStill = dy < HEAD_STILL_RAD && dy > -HEAD_STILL_RAD && dp < HEAD_STILL_RAD && dp > -HEAD_STILL_RAD
        if (d.viewSerial != seenViewSerial) { seenViewSerial = d.viewSerial; director.request(d.view, d.framing); fadeReqNanos = d.viewNanos; fadeInputNanos = d.inputNanos }
        val ov = d.overrides
        director.setOverrides(ov.ipdScale ?: Float.NaN, ov.vFovDeg ?: lifeSizeFov(settings))
        director.update(dt, pose, gaze, cam)
        if (director.cutThisFrame) onCut()
        dipping = director.dipping
        if (fadeReqNanos != 0L && dipping) {
            val pad = if (fadeInputNanos in 1..fadeReqNanos) " fromPad ms=" + "%.1f".format((t0 - fadeInputNanos) / 1e6) else ""
            Log.i(HK.TAG_RENDER, "swipe to first fade ms=" + "%.1f".format((t0 - fadeReqNanos) / 1e6) + " view=" + d.view + "/" + d.framing + pad)
            fadeReqNanos = 0L
        }
        val stereo = d.stereo
        val eyes = if (stereo) 2 else 1
        val ew = width / eyes
        rig.update(cam.pos, cam.target, cam.vFovDeg, cam.ipdScale, cam.zeroParallaxM, settings?.stereoDepth ?: 1f,
            ew.toFloat() / height, director.gazeYaw, director.gazePitch, stereo)

        // uniforms once
        val scn = current ?: return
        val viewCode = director.view.ordinal * 2 + director.framing
        val level = levelFor(d, director.view)
        packer.pack(pose, scn.profile.lowKey, scn.profile.highKey, cam.lidLift, scn.instrument, director.xToKey(director.cutSpring.x))
        val tSec = realSec
        scn.flames.lights(tSec, lightRig)
        frame.lights(lightRig)
        val nSprites = if (level == RoomLevel.PASSTHROUGH.ordinal) 0
            else scn.flames.update(tSec, rig.centre, d.quality, RoomLevel.entries[level], sprites.sprites)
        sprites.build(nSprites, rig.right, rig.up, rig.centre)
        frame.clipX = cam.clipX
        frame.stageFade = level == RoomLevel.STAGE.ordinal
        frame.stringWidthPx = drawer.stringWidth(if (director.view == ViewId.HALL) 2 else if (director.view == ViewId.ACTION) director.framing else 2)
        frame.probeTex = textures.id(TextureUploader.PROBE, gen)
        val pf = (settings?.presenceFloor ?: 22) / 22f * (if (level == RoomLevel.PASSTHROUGH.ordinal) 1.5f else 1f)
        for (i in 0 until 3) frame.floorRgb[i] = Pal.EBONY_FLOOR[i] / 255f * pf
        frame.viewportW = ew.toFloat(); frame.viewportH = height.toFloat()
        glyphs.beginFrame()

        // two eyes
        val list = scn.assembled.list(viewCode, level)
        var draws = 0
        drawer.trianglesDrawn = 0
        drawer.uniform4fvCalls = 0
        frame.frameStamp++
        for (e in 0 until eyes) {
            val ex = e * ew
            GLES20.glViewport(ex, 0, ew, height)
            val eye = rig.eye(e)
            frame.viewProj = eye.viewProj
            frame.eye[0] = eye.pos[0]; frame.eye[1] = eye.pos[1]; frame.eye[2] = eye.pos[2]
            frame.projY = eye.proj[5]
            frame.eyeStamp++
            var n = 0
            if (!d.stageHidden) {
                for (i in list) { drawer.draw(scn.assembled.items[i], frame, gen); n++ }
                if (director.view != ViewId.HALL) n += fader.draw(programs.fade, 1f - aplGain(director.view, director.framing, level))   // APL cap: surfaces only, flames stay full
                n += sprites.draw(programs.sprite, eye.viewProj)
                n += drawLabels(d, scn, eye.view, eye.proj)
                if (viewCode == INSET_VIEW) { n += inset.draw(scn.assembled, frame, gen, ex, 0, ew); GLES20.glViewport(ex, 0, ew, height) }
            }
            val sd = sync.draw(programs.fade, pose, ex, 0, ew, height)
            if (sd > 0 && e == 0) { syncFrames++; if (syncFrames % 8 == 1) Log.i(HK.TAG_RENDER, "sync flash frame n=" + syncFrames) }
            n += sd
            n += fader.draw(programs.fade, if (director.view == ViewId.HALL) 1f - (1f - director.fade) * aplGain(director.view, director.framing, level) else director.fade)   // Hall: cap folded into the fade (28-draw budget)
            if (e == 0) draws = n
        }
        drawsPerEye = draws
        uniform4fvPerFrame = drawer.uniform4fvCalls
        if (draws > maxDrawsPerEye) maxDrawsPerEye = draws
        trisPerEye = drawer.trianglesDrawn / eyes
        if ((frames and 255L) == 0L) glErrors += com.tropicalstream.hammerklavier.render.gl.GlKit.checkError("frame")
        finish(t0, t0 - frameNanos)
    }

    /**
     * T-APL cap (PLAN §1 budgets, §9 risk 1 "APL caps"): a black multiply over the lit surfaces, drawn
     * before the flame sprites, glyphs and inset (in the Hall, folded into the fade quad: no draw to spare), so the average picture level stays ≤ 9% (Stage views)
     * and ≤ 12% (Hall) with the M5 materials. Gains are the measured M5 APL scaled to ≈ 8.5% / 11.5%.
     */
    private fun aplGain(view: ViewId, framing: Int, level: Int): Float = when {
        level == RoomLevel.PASSTHROUGH.ordinal -> 1f
        view == ViewId.HALL -> APL_GAIN_HALL
        view == ViewId.ACTION && framing == 1 -> APL_GAIN_OVERHEAD
        view == ViewId.ACTION -> APL_GAIN_CUTAWAY
        else -> APL_GAIN_PLAYER
    }

    private fun finish(t0: Long, lateNs: Long) {
        val now = System.nanoTime()
        val us = ((now - t0) / 1000L).toInt()
        cpuUs[ringI] = us
        val late = (lateNs / 1000L).toInt().coerceAtLeast(0)
        lateUs[ringI] = late
        if (late > 8000) lateFrames++
        ringI = (ringI + 1) and 127
        frames++
        val inst = if (lastFpsNanos == 0L) 0f else 1e9f / (t0 - lastFpsNanos).coerceAtLeast(1L)
        lastFpsNanos = t0
        fps += (inst - fps) * 0.1f
        if (debugAlloc) {
            if (frames == WARM_FRAMES) { allocBase = Debug.getThreadAllocCount() }
            else if (frames > WARM_FRAMES && (frames and 511L) == 0L) glAllocs = Debug.getThreadAllocCount() - allocBase
        }
    }

    private var lastFpsNanos = 0L
    private var allocBase = 0
    /** Debug builds: count GL-thread allocations after warm-up (T2.10 on the device). */
    var debugAlloc = false
        set(v) { field = v; if (v) Debug.startAllocCounting() }

    // ───────────────────────── reconciliation ─────────────────────────

    private fun reconcile(d: Desired, resting: Boolean) {
        val palette = d.settings?.palette ?: Palette.SANSSOUCI_1747
        val want = d.instrumentSerial
        val cur = current
        val r = ready
        if (r != null && r.request == want && r.palette == palette && r !== cur) {
            // First scene, display rest or display off: at once. Otherwise under the next dip.
            if (cur == null || resting) install(r) else director.requestDip()
        }
        if (cur == null || cur.request != want || cur.palette != palette) kickBuild()
    }

    private val buildLock = Any()

    /**
     * Any thread (main calls it from setInstrument/setSettings so a scene is built even while GL
     * is paused or resting): start building the desired scene on HKLoader unless it is built or
     * being built.
     */
    fun kickBuild() {
        val d = desired
        val scenes = d.scenes ?: return
        val palette = d.settings?.palette ?: Palette.SANSSOUCI_1747
        val want = d.instrumentSerial
        synchronized(buildLock) {
            val r = ready
            if (r != null && r.request == want && r.palette == palette) return
            if (building == want && buildPalette == palette) return
            building = want; buildPalette = palette
        }
        val id = d.instrument; val look = d.look; val lastDamper = d.lastDamper
        val job = Runnable { val b = build(want, scenes, id, look, lastDamper, palette); if (b != null) ready = b }
        if (loader != null) loader.execute(job) else job.run()
    }

    private fun onCut() {
        val r = ready
        val cur = current
        if (r != null && r !== cur && r.request == desired.instrumentSerial) install(r)
        logDraws()
    }

    private fun install(s: BuiltScene) {
        // The context is current here: free the outgoing scene's buffers and textures (handles from
        // an older generation died with their context and are only forgotten).
        val old = current
        if (old != null && old !== s) { old.assembled.deleteGl(gen); textures.deleteAll(gen) }
        current = s
        textures.install(s.textures); textures.add(s.probe)
        s.assembled.deleteGl(gen)
        uploadedGen = -1
        drawer.setSkin(s.instrument.skin)
        labelAnchor(s.id, tmp3); labelY = tmp3[0]; labelZ = tmp3[1]
        director.setScene(s.instrument.anchors, s.placement, s.profile.lowKey, s.profile.highKey)
        val c = SinTable.cos(s.placement.yawRad); val sn = SinTable.sin(s.placement.yawRad)
        Mat4.rigidY(frame.instModel, s.placement.originRoom, c, sn)
        Mat4.identity(frame.venueModel)
        frame.fadeCentre[0] = s.placement.originRoom[0]; frame.fadeCentre[1] = s.placement.originRoom[1]; frame.fadeCentre[2] = s.placement.originRoom[2]
        if (sprites.maxSprites < s.flames.maxSprites) sprites = SpriteBatch(s.flames.maxSprites)
        inset.install(s.assembled, s.placement, s.id != InstrumentId.HARPSICHORD)
        // title sheet on the music desk: piano frame (0, 0.98, −0.22), tilted back 15°
        Mat4.identity(tmpM)
        val tc = SinTable.cos(-15f * SinTable.DEG); val ts = SinTable.sin(-15f * SinTable.DEG)
        tmpM[5] = tc; tmpM[6] = ts; tmpM[9] = -ts; tmpM[10] = tc
        tmpM[12] = 0f; tmpM[13] = 0.98f; tmpM[14] = -0.22f
        Mat4.multiply(titleModel, frame.instModel, tmpM)
        boundSlot = -2; boundGen = Int.MIN_VALUE
        Log.i(HK.TAG_RENDER, "scene " + s.id.key + " items=" + s.assembled.items.size + " resident=" +
            (s.assembled.residentBytes + textures.residentBytes) / 1024 + " KiB")
    }

    private fun logDraws() {
        val s = current ?: return
        val vc = director.view.ordinal * 2 + director.framing
        val lv = levelFor(desired, director.view)
        val n = s.assembled.list(vc, lv).size + SceneAssembler.overheadDraws(vc, lv, 2, desired.syncFlash)
        Log.i(HK.TAG_RENDER, "view=" + director.view + "/" + director.framing + " level=" + RoomLevel.entries[lv] +
            " draws<=" + n + " tris=" + s.assembled.triangles(vc, lv) + if (n > 28) " OVER BUDGET" else "")
    }

    /** HKLoader. */
    private fun build(request: Long, scenes: SceneFactory, id: InstrumentId, look: InstrumentLook, lastDamper: Int,
                      palette: Palette): BuiltScene? = try {
        val inst = scenes.instrument(id, look, lastDamper)
        val venue = scenes.venue()
        val recipes = inst.textures() + venue.textures()
        val tex = if (recipes.isEmpty()) emptyList() else textures.paint(recipes, painterFactory?.invoke() ?: CanvasPainter())
        val placement = venue.geometry.placements[id] ?: KonzertzimmerAcoustics.PLACEMENTS.getValue(id)
        val probeBytes = ByteArray(TextureUploader.PROBE_W * TextureUploader.PROBE_H * 4)
        val centre = FloatArray(3); placement.toRoom(floatArrayOf(0f, 0.8f, -0.8f), centre)
        venue.bakeProbe(centre, probeBytes)
        (venue.flames() as? com.tropicalstream.hammerklavier.venue.FlameFieldImpl)?.setInstrumentOrigin(placement.originRoom[0], placement.originRoom[2])   // WP8 light 3
        val assembled = SceneAssembler.assemble(inst.meshes(), venue.meshes(palette))
        BuiltScene(request, id, InstrumentProfile.of(id).withLastDamper(lastDamper), inst, venue.flames(), placement, assembled, tex,
            ResidentTexture(TextureUploader.PROBE, TextureUploader.PROBE_W, TextureUploader.PROBE_H, probeBytes), palette)
    } catch (e: RuntimeException) {
        lastBuildError = e.toString()
        Log.e(HK.TAG_RENDER, "scene build failed", e); null
    }

    private fun bindSlot(d: Desired, generation: Int, reseed: Boolean, mech: MechanicsEvaluator?, fallback: InstrumentProfile) {
        var slot = -1
        var perf: Performance? = null
        var prof: InstrumentProfile? = null
        synchronized(d.lock) {
            for (s in 0..1) if (d.slotGen[s] == generation && d.slotPerf[s] != null) { slot = s; perf = d.slotPerf[s]; prof = d.slotProfile[s] }
        }
        boundPerf = perf
        if (mech == null) { boundSlot = slot; return }
        if (slot != boundSlot || generation != boundGen || reseed) {
            mech.bind(perf, prof ?: fallback)
            boundSlot = slot; boundGen = generation
        }
    }

    // ── M4 strike audit: every contact in a frame's exposure window is drawn exactly once (pose.flash) ──
    private var boundPerf: Performance? = null
    private var fadeReqNanos = 0L
    private val DAMPER_STOP_MS = 1500f
    private var fadeInputNanos = 0L
    private var auditGen = Int.MIN_VALUE
    private var auditNext = 0
    private var auditExpected = 0
    private var auditDrawn = 0
    private var auditSameKey = 0
    private var auditLogged = true
    private val auditSeen = BooleanArray(128)

    private fun auditStrikes() {
        val p = boundPerf ?: return
        if (p.generation != auditGen || vis.reseed) {
            if (!auditLogged && auditExpected > 0) logAudit("reset")
            auditGen = p.generation; auditExpected = 0; auditDrawn = 0; auditSameKey = 0; auditLogged = false
            damperLands = 0; damperStopped = 0; damperMaxMs = 0f; damperLate = 0
            java.util.Arrays.fill(damperLandUs, -1L); java.util.Arrays.fill(prevDamper, 0f)
            var lo = 0; var hi = p.onUs.size
            while (lo < hi) { val m = (lo + hi) ushr 1; if (p.onUs[m] <= vis.exposeToUs) lo = m + 1 else hi = m }
            auditNext = lo
            return
        }
        java.util.Arrays.fill(auditSeen, false)
        while (auditNext < p.onUs.size && p.onUs[auditNext] <= vis.exposeToUs) {
            if (p.onUs[auditNext] > vis.exposeFromUs) {
                val k = p.key[auditNext].toInt() and 127
                if (auditSeen[k]) auditSameKey++ else { auditSeen[k] = true; auditExpected++ }
            }
            auditNext++
        }
        for (k in 0 until 128) if (pose.flash[k]) auditDrawn++
        auditDampers()
        if (!auditLogged && auditNext >= p.onUs.size && vis.tUs > p.onUs[p.onUs.size - 1] + 300_000L) logAudit("end")
    }

    // Dampers stop strings: a damper that lands (lift > 0.05 → 0) on a ringing string (amp > 0.2) with the
    // sustain pedal up must bring the drawn amplitude under 0.1 within DAMPER_STOP_MS.
    private val damperLandUs = LongArray(128) { -1L }
    private val prevDamper = FloatArray(128)
    private var damperLands = 0; private var damperStopped = 0; private var damperLate = 0; private var damperMaxMs = 0f

    private fun auditDampers() {
        val t = vis.tUs
        for (k in 0 until 128) {
            val dmp = pose.damper[k]; val amp = pose.stringAmp[k]
            if (pose.flash[k]) damperLandUs[k] = -1L
            if (prevDamper[k] > 0.05f && dmp <= 0.001f && pose.sustain < 0.1f && amp > 0.2f) { damperLandUs[k] = t; damperLands++ }
            prevDamper[k] = dmp
            val l = damperLandUs[k]
            if (l >= 0L) {
                val ms = (t - l) / 1000f
                if (dmp > 0.05f) damperLandUs[k] = -1L                                   // lifted again before it settled
                else if (amp < 0.1f) { damperStopped++; if (ms > damperMaxMs) damperMaxMs = ms; damperLandUs[k] = -1L }
                else if (ms > DAMPER_STOP_MS) { damperLate++; damperLandUs[k] = -1L }
            }
        }
    }

    private fun logAudit(why: String) {
        auditLogged = true
        Log.i(HK.TAG_RENDER, "damper audit " + why + " id=" + boundPerf?.id + " landed=" + damperLands + " stopped=" + damperStopped +
            " late=" + damperLate + " maxStopMs=" + "%.0f".format(damperMaxMs) + " limitMs=" + DAMPER_STOP_MS.toInt())
        Log.i(HK.TAG_RENDER, "strike audit " + why + " id=" + boundPerf?.id + " gen=" + auditGen + " notes=" + (boundPerf?.noteCount ?: 0) +
            " expected=" + auditExpected + " drawn=" + auditDrawn + " sameKeySameFrame=" + auditSameKey + " fps=" + "%.1f".format(fps))
    }

    private fun restPose() {
        java.util.Arrays.fill(pose.keyDip, 0f); java.util.Arrays.fill(pose.hammer, 0f); java.util.Arrays.fill(pose.jack4, 0f)
        java.util.Arrays.fill(pose.escape, 0f); java.util.Arrays.fill(pose.damper, 0f); java.util.Arrays.fill(pose.tongue, 0f)
        java.util.Arrays.fill(pose.tongue4, 0f); java.util.Arrays.fill(pose.stringAmp, 0f); java.util.Arrays.fill(pose.strikeAge, 1e9f)
        java.util.Arrays.fill(pose.flash, false)
        pose.sustain = 0f; pose.soft = 0f; pose.sostenuto = 0f; pose.shiftMm = 0f; pose.hammerRailMm = 0f
        pose.playing = false
    }

    private fun lifeSizeFov(s: RenderSettings?): Float =
        if (s != null && director.view == ViewId.HALL && director.framing == 1 && s.lifeSizeVFov > 0f) s.lifeSizeVFov else Float.NaN

    private fun levelFor(d: Desired, view: ViewId): Int = levelFor(d.settings?.roomOverride, view, d.quality.roomCap)


    private fun drawLabels(d: Desired, s: BuiltScene, view: FloatArray, proj: FloatArray): Int {
        var n = 0
        if (director.view == ViewId.ACTION && director.framing == 0) {
            val key = (director.xToKey(director.cutSpring.x) + 0.5f).toInt().coerceIn(s.profile.lowKey, s.profile.highKey)
            tmp3[0] = director.cutSpring.x + 0.035f; tmp3[1] = labelY; tmp3[2] = labelZ
            Mat4.transformPoint(frame.instModel, tmp3, tmp3)
            val dx = tmp3[0] - rig.centre[0]; val dy = tmp3[1] - rig.centre[1]; val dz = tmp3[2] - rig.centre[2]
            val dist = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            val h = LABEL_PX * 2f * dist * SinTable.tan(cam.vFovDeg * 0.5f * SinTable.DEG) / height
            glyphs.drawBillboard(noteNames[key], GlyphBoard.Style.PLAIN, tmp3[0], tmp3[1], tmp3[2], h,
                rig.right[0], rig.right[1], rig.right[2], rig.up[0], rig.up[1], rig.up[2], white, 1f, 1f, -0.5f, 0f, view, proj)
            n++
        }
        val title = d.title
        if (title != null && director.view == ViewId.PLAYER) {
            glyphs.drawOriented(title, GlyphBoard.Style.PLAIN, titleModel, 0.018f, white, 0.9f, 1f, 0f, view, proj)
            n++
        }
        return n
    }

    companion object {
        const val APL_GAIN_PLAYER = 0.35f; const val APL_GAIN_CUTAWAY = 0.28f   // M6: -5% for the HUD (credit, pills) now live
        const val APL_GAIN_OVERHEAD = 0.245f; const val APL_GAIN_HALL = 0.70f
        private val NOTE = arrayOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")
        /** §5.9: the thermal cap overrides Auto; an explicit user choice overrides both. */
        internal fun levelFor(userOverride: RoomLevel?, view: ViewId, cap: RoomLevel): Int {
            if (userOverride != null) return userOverride.ordinal
            val auto = if (view == ViewId.HALL) RoomLevel.SALON else RoomLevel.STAGE
            return maxOf(auto.ordinal, cap.ordinal)
        }

        /** Cutaway label anchor (piano frame y, z) per instrument: beside the hammer or jack (§5.6 targets). */
        internal fun labelAnchor(id: InstrumentId, out: FloatArray) {
            when (id) {
                InstrumentId.HARPSICHORD -> { out[0] = 0.86f; out[1] = -0.42f }
                InstrumentId.UPRIGHT -> { out[0] = 0.93f; out[1] = -0.20f }
                else -> { out[0] = 0.93f; out[1] = -0.24f }
            }
        }

        const val HEAD_STILL_RAD = 0.2f * 0.017453292f
        const val INSET_VIEW = 1          // PLAYER, framing 1
        const val LABEL_PX = 16f
        const val WARM_FRAMES = 120L
    }
}
