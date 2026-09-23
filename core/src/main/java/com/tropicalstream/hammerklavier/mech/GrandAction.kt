package com.tropicalstream.hammerklavier.mech

import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.KeyReturn
import com.tropicalstream.hammerklavier.contract.MechanismPose
import com.tropicalstream.hammerklavier.contract.PedalMotion

/**
 * Grand action (PLAN §5.7). Key: `u = (t − tStart)/(tBottom − tStart)`, pressed `u^1.8`, struck
 * piecewise, blended by s(HV), from d0; held 1; release by [KeyReturn.dip]. Hammer as a fraction
 * of the blow: driven by the key to let-off, free flight to 1.0 at `on`, contact c(n)·r, rebound at
 * 0.45·HV to the check level, then down with the key. Damper from the key, the sustain pedal and
 * the sostenuto latch; the key part lands exactly at `off + damperLagMs·r`.
 * [UprightAction] reuses it with its own profile, rest level and repetition rule.
 */
open class GrandAction(final override val profile: InstrumentProfile = InstrumentProfile.GRAND) : KeyAction {
    protected val blow = profile.blowMm
    protected val keyToBlow = profile.actionRatio * profile.keyDipMm / profile.blowMm   // h per unit dip
    protected val letOffLevel = (profile.blowMm - profile.letOffMm) / profile.blowMm
    /** (blow − check)/blow: grand 0.68, upright 0.66. */
    val checkLevel = (profile.blowMm - profile.checkMm) / profile.blowMm

    /** Height the hammer is relaunched from on a fast repetition (grand: the checked height). */
    open val repeatLevel: Float get() = checkLevel

    override fun isolatedLeadUs(vel: Int, r: Float): Float = Touch.travelMs(vel, profile.travelScale) * r * 1000f
    override fun contactUs(key: Int, r: Float): Float = Touch.contactMs(key) * r * 1000f

    override fun pressDip(n: NoteCtx, t: Long, r: Float): Float {
        if (t <= n.tStartUs) return n.d0
        val tBottom = n.onUs + (Touch.bedMs(n.vel) * r * 1000f).toLong()
        val span = (tBottom - n.tStartUs).toFloat()
        if (span <= 0f || t >= tBottom) return 1f
        val u = (t - n.tStartUs).toFloat() / span
        val s = Touch.blend(n.vel)
        val struck = if (u < 0.25f) 1.32f * u else if (u < 0.40f) 0.33f else 0.33f + 0.67f * (u - 0.40f) / 0.60f
        val mix = (1f - s) * Touch.pow18(u) + s * struck
        return n.d0 + (1f - n.d0) * mix
    }

    override fun releaseDip(dHeld: Float, sinceOffUs: Float, r: Float): Float =
        KeyReturn.dip(dHeld, sinceOffUs / (profile.keyReturnMs * r * 1000f))

    /** Hammer driven by the key before escapement. */
    protected fun keyHammer(dip: Float, fast: Boolean): Float {
        var h = minOf(keyToBlow * dip, letOffLevel)
        if (fast && h < repeatLevel) h = repeatLevel
        return h
    }

    /** Hammer rest level (0; the upright's soft pedal raises it). */
    protected open fun restLevel(env: FrameEnv): Float = 0f

    /** Analytic hammer fraction for the governing note (before the rest-level scaling). */
    fun hammerOf(n: NoteCtx, t: Long, dip: Float, r: Float): Float {
        val on = n.onUs
        val tEscape = on - minOf(Touch.freeFlightMs(n.vel) * r * 1000f, 0.5f * n.leadUs).toLong()
        if (t < tEscape) return keyHammer(dip, n.fast)
        if (t < on) {
            val hE = keyHammer(pressDip(n, tEscape, r), n.fast)
            val span = (on - tEscape).toFloat()
            return if (span <= 0f) 1f else hE + (1f - hE) * (t - tEscape).toFloat() / span
        }
        val cEnd = on + contactUs(n.key, r).toLong()
        if (t < cEnd) return 1f
        // Rebound at 0.45·HV m/s (= mm/ms) of real time, caught by the backcheck.
        val fallMs = (t - cEnd).toFloat() / (r * 1000f)
        var h = 1f - 0.45f * Touch.hammerVelocity(n.vel) * fallMs / blow
        if (h < checkLevel) h = checkLevel
        if (t >= n.offUs) {
            val keyed = minOf(checkLevel, keyToBlow * dip)
            if (keyed < h) h = keyed
        }
        return h
    }

    override fun pose(k: Int, n: NoteCtx?, t: Long, env: FrameEnv, out: MechanismPose) {
        val r = env.r
        val pedalLift = if (profile.usesSustain) PedalMotion.damperLiftByPedal(env.sustain) else 0f
        val latch = if (profile.usesSostenuto && env.latched(k)) 1f else 0f
        val damped = k <= env.lastDamper
        val h0 = restLevel(env)
        if (n == null) {
            out.keyDip[k] = 0f; out.hammer[k] = h0; out.escape[k] = 0f
            out.damper[k] = if (damped) maxOf(pedalLift, latch) else 1f
            out.jack4[k] = 0f; out.tongue[k] = 0f; out.tongue4[k] = 0f
            return
        }
        val dip: Float
        val keyPart: Float
        if (t < n.offUs) {
            dip = pressDip(n, t, r)
            keyPart = PedalMotion.keyDamperLift(minOf(1f, 1.08f * dip))
        } else {
            val since = (t - n.offUs).toFloat()
            dip = releaseDip(n.dHeld, since, r)
            val lift0 = PedalMotion.keyDamperLift(minOf(1f, 1.08f * n.dHeld))
            keyPart = lift0 * (1f - PedalMotion.smoothstep(0f, 1f, since / (profile.damperLagMs * r * 1000f)))
        }
        val h = hammerOf(n, t, dip, r)
        out.keyDip[k] = dip
        out.hammer[k] = h0 + (1f - h0) * h
        val tEscape = n.onUs - minOf(Touch.freeFlightMs(n.vel) * r * 1000f, 0.5f * n.leadUs).toLong()
        out.escape[k] = if (t >= tEscape && (t < n.offUs || dip > 0.5f * n.dHeld)) 1f else 0f
        out.damper[k] = if (damped) maxOf(keyPart, pedalLift, latch) else 1f
        out.jack4[k] = 0f; out.tongue[k] = 0f; out.tongue4[k] = 0f
    }
}
