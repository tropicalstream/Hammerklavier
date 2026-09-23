package com.tropicalstream.hammerklavier.mech

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.MechanicsEvaluator
import com.tropicalstream.hammerklavier.contract.MechanismPose
import com.tropicalstream.hammerklavier.contract.Performance
import com.tropicalstream.hammerklavier.contract.VisTime

/**
 * The mechanism pose from song time (PLAN §5.7), GLThread, allocation-free after construction.
 * Per frame: pedals → per-key governing note (cursor step, or binary-search re-seed on
 * VisTime.reseed) → the instrument's pose formulas → the ExposureSampler forces each contact in its
 * frame (hammer 1.0, flash, strike pulse) → string amplitudes (energy lanes, analytic fallback) →
 * focus and centroid. Every duration is real time × rate.
 */
class MechanicsEvaluatorImpl : MechanicsEvaluator {
    private val grand = GrandAction(InstrumentProfile.GRAND)
    private val upright = UprightAction(InstrumentProfile.UPRIGHT)
    private val harpsichord = HarpsichordAction(InstrumentProfile.HARPSICHORD)
    private val timings = arrayOf(NoteTiming(grand), NoteTiming(upright), NoteTiming(harpsichord))
    private val cursorsFor = arrayOf(KeyCursors(timings[0]), KeyCursors(timings[1]), KeyCursors(timings[2]))

    private var action: KeyAction = grand
    private var timing: NoteTiming = timings[0]
    private var cursors: KeyCursors = cursorsFor[0]
    private var perf: Performance? = null
    private var profile: InstrumentProfile = InstrumentProfile.GRAND
    private var lastDamper = 88
    private var needSeed = true
    private var lastEpoch = Int.MIN_VALUE
    private var lastGeneration = Int.MIN_VALUE

    private val ctx = NoteCtx()
    private val env = FrameEnv()
    private val pedals = PedalPose()
    private val sampler = ExposureSampler()
    private val strings = StringVisual()
    private val focus = FocusTracker()
    private val down = BooleanArray(HK.KEYS)
    private val lastOn = LongArray(HK.KEYS)

    /** Force contacts in their exposure frame (off only for T5.10's analytic checks). */
    @JvmField var exposureEnabled = true

    override fun bind(perf: Performance?, profile: InstrumentProfile) {
        this.perf = perf
        this.profile = profile
        val i = when (profile.id) { InstrumentId.GRAND -> 0; InstrumentId.UPRIGHT -> 1; InstrumentId.HARPSICHORD -> 2 }
        action = when (i) { 0 -> grand; 1 -> upright; else -> harpsichord }
        timing = timings[i]; cursors = cursorsFor[i]
        lastDamper = perf?.lastDamper ?: profile.lastDamper
        cursors.bind(perf)
        pedals.bind(perf, profile)
        sampler.bind(perf, profile.id == InstrumentId.HARPSICHORD)
        strings.bind(profile)
        focus.reset()
        needSeed = true
    }

    override fun evaluate(v: VisTime, energy: FloatArray?, dtSec: Float, out: MechanismPose) {
        val t = v.tUs
        val r = if (v.rate > 0.05f) v.rate else 1f
        val pf = perf
        val reseed = v.reseed || needSeed || v.epoch != lastEpoch || v.generation != lastGeneration
        needSeed = false; lastEpoch = v.epoch; lastGeneration = v.generation
        env.r = r; env.lastDamper = lastDamper
        if (reseed) { pedals.seek(t); strings.reset(); sampler.reset(); focus.reset() }
        pedals.update(t, v.registration, env, out)

        for (k in 0 until HK.KEYS) {
            out.flash[k] = false
            down[k] = false; lastOn[k] = Long.MIN_VALUE
            if (pf == null || pf.keyFirst[k] == pf.keyFirst[k + 1]) { action.pose(k, null, t, env, out); continue }
            val lo = pf.keyFirst[k]
            if (reseed) cursors.seek(k, t, r)
            val p = cursors.step(k, t, r)
            if (p < lo) { action.pose(k, null, t, env, out); continue }
            timing.fill(p, lo, r, ctx)
            action.pose(k, ctx, t, env, out)
            down[k] = t < ctx.offUs
            lastOn[k] = if (t >= ctx.onUs) ctx.onUs else if (p > lo) pf.onUs[pf.keyNotes[p - 1]] else Long.MIN_VALUE
        }

        for (k in 0 until HK.KEYS) {
            val a = out.strikeAge[k]
            out.strikeAge[k] = if (reseed || a >= 1e9f) 1e9f else a + (if (dtSec > 0f) dtSec else 0f)
        }

        if (pf != null && !reseed) {
            sampler.scan(v)
            val piano = profile.id != InstrumentId.HARPSICHORD
            for (i in 0 until sampler.count) {
                val n = sampler.notes[i]
                val k = pf.key[n].toInt()
                strings.strike(k, pf.vel[n].toInt())
                out.strikeAge[k] = 0f
                if (exposureEnabled) {
                    out.flash[k] = true
                    if (piano) out.hammer[k] = 1f
                }
            }
        }

        strings.update(energy, dtSec, profile.lowKey, profile.highKey, lastDamper, out)
        focus.update(t, down, lastOn, out)
        out.songUs = t; out.playing = v.playing
    }
}
