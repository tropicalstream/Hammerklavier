package com.tropicalstream.hammerklavier.contract

/** Every moving part's pose for one frame (PLAN §5.7). Preallocated; filled by MechanicsEvaluator. */
class MechanismPose {
    @JvmField val keyDip = FloatArray(128)     // 0..1 of full dip at the key front
    @JvmField val hammer = FloatArray(128)     // grand/upright: 0 rest .. 1 at string; harpsichord: 8' jack rise 0..1 (1 = jack rail)
    @JvmField val jack4 = FloatArray(128)      // harpsichord 4' jack rise 0..1
    @JvmField val escape = FloatArray(128)     // grand jack escape 0..1 (cutaway)
    @JvmField val damper = FloatArray(128)     // 0 on string .. 1 fully lifted
    @JvmField val tongue = FloatArray(128)     // harpsichord 8' tongue deflection 0..1
    @JvmField val tongue4 = FloatArray(128)
    @JvmField val stringAmp = FloatArray(128)  // 0..1 visual vibration amplitude
    @JvmField val strikeAge = FloatArray(128) { 1e9f }  // seconds since last drawn contact/pluck; 1e9 = none
    @JvmField val flash = BooleanArray(128)    // a contact falls in this frame's exposure window (sync disc, strike pulse start)
    @JvmField var sustain = 0f; @JvmField var soft = 0f; @JvmField var sostenuto = 0f
    @JvmField var shiftMm = 0f; @JvmField var hammerRailMm = 0f; @JvmField var registers = 3
    @JvmField var focusKey = 60f; @JvmField var centroidKey = 60f
    @JvmField var songUs = 0L; @JvmField var playing = false
}

/** GLThread; allocation-free after bind. */
interface MechanicsEvaluator {
    /** Resets cursors; perf.lastDamper is authoritative. */
    fun bind(perf: Performance?, profile: InstrumentProfile)
    /** Registration from v. */
    fun evaluate(v: VisTime, energy: FloatArray? /*88 lanes, linear RMS*/, dtSec: Float, out: MechanismPose)
}
