package com.tropicalstream.hammerklavier.mech

import com.tropicalstream.hammerklavier.contract.InstrumentProfile

/**
 * Upright action (PLAN §5.7): the grand's formulas with the upright profile (travelScale 1.05,
 * check 0.66, 50 ms smoothstep return, damper lands 26.2 ms after the key-up). The jack resets only
 * after 80% of key return: a stroke that starts with the key still more than 20% down, or faster
 * than repeatMinMs (143 ms), is drawn as the stated approximation, relaunched from half the blow.
 * Soft pedal (hammer rail): rest = soft·22/47 of the blow, the throw scaled to the rest.
 * The dampers (under the strike line, swinging toward the player) use the same 0..1 lift.
 */
class UprightAction(profile: InstrumentProfile = InstrumentProfile.UPRIGHT) : GrandAction(profile) {
    override val repeatLevel: Float get() = 0.5f

    override fun forcesFast(n: NoteCtx): Boolean = n.d0 > 0.2f

    override fun restLevel(env: FrameEnv): Float = env.soft * RAIL_MM / blow

    companion object { const val RAIL_MM = 22f }
}
