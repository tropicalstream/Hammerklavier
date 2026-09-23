package com.tropicalstream.hammerklavier.contract

class RenderSettings(val stereoDepth: Float, val lookAround: Boolean, val roomOverride: RoomLevel?, val palette: Palette,
    val presenceFloor: Int, val displayLeadMs: Int /* for the current route */, val lifeSizeVFov: Float, val look: InstrumentLook,
    val msaa: Boolean /* read once at GL view creation; a change applies at the next launch */)

/** CONTROL, current framing. */
class RenderOverrides(val ipdScale: Float? = null, val vFovDeg: Float? = null)

class RenderStats {
    @JvmField var fps = 0f; @JvmField var draws = 0; @JvmField var tris = 0; @JvmField var cpuUsP99 = 0
    @JvmField var hitches = 0; @JvmField var divider = 2; @JvmField var dipping = false; @JvmField var headYawRad = 0f
    @JvmField var lateP99Us = 0; @JvmField var lateFrames = 0 /* late > 8 ms */; @JvmField var glGeneration = 0; @JvmField var energyMiss = 0
}

/** Main thread; queueEvent runnables only set fields (§2.1 rule 6). */
interface RenderControl {
    fun bind(clock: SongClock, energy: EnergyRing, mech: MechanicsEvaluator, scenes: SceneFactory)
    /** Kept in two slots keyed by generation; the slot matching clock.generation is drawn. */
    fun setPerformance(p: Performance?, profile: InstrumentProfile)
    /** Desired scene: built on HKLoader, uploaded in onDrawFrame under the next dip (instantly after a display rest). */
    fun setInstrument(id: InstrumentId, look: InstrumentLook, lastDamper: Int)
    /** A dip (instant after a display rest). */
    fun setView(v: ViewId, framing: Int)
    fun setQuality(q: QualityProfile); fun setSettings(s: RenderSettings)
    fun setStereo(on: Boolean); fun setIdle(idle: Boolean); fun setSyncFlash(on: Boolean)
    fun setTitle(text: String?); fun setStageHidden(hidden: Boolean); fun setOverrides(o: RenderOverrides)
    fun recenter(); fun onResume(); fun onPause(); fun stats(out: RenderStats)
    /** GL renderer, EGL config incl. EGL_SAMPLES, uniform vectors, GL generation. */
    fun diagnostics(): Map<String, String> = emptyMap()
}
