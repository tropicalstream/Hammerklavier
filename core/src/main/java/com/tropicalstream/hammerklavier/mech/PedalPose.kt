package com.tropicalstream.hammerklavier.mech

import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.MechanismPose
import com.tropicalstream.hammerklavier.contract.PedalCurve
import com.tropicalstream.hammerklavier.contract.PedalMotion
import com.tropicalstream.hammerklavier.contract.Performance
import com.tropicalstream.hammerklavier.contract.SoftKind

/**
 * Pedals and shifts (PLAN §5.7): the song-time pedal curves (the same curves the audio reads),
 * read with rebindable cursors; grand una corda `shiftMm = 2.5·smoothstep(0, 1, soft)`; upright
 * hammer rail `hammerRailMm = 22·soft`; the sostenuto latch mask in effect; the heard registration.
 * The drawn pedal angle is 5°·p (WP7 reads sustain / soft / sostenuto from the pose).
 */
class PedalPose {
    private val sus = PedalCurve.Cursor()
    private val soft = PedalCurve.Cursor()
    private val sos = PedalCurve.Cursor()
    private var perf: Performance? = null
    private var profile: InstrumentProfile = InstrumentProfile.GRAND

    fun bind(p: Performance?, prof: InstrumentProfile) {
        perf = p; profile = prof
        sus.bind(p?.sustain ?: PedalCurve.EMPTY)
        soft.bind(p?.soft ?: PedalCurve.EMPTY)
        sos.bind(p?.sostenuto ?: PedalCurve.EMPTY)
    }

    fun seek(t: Long) { sus.seek(t); soft.seek(t); sos.seek(t) }

    fun update(t: Long, registration: Int, env: FrameEnv, out: MechanismPose) {
        val p = perf
        val s = if (profile.usesSustain) sus.advanceTo(t) else 0f
        val so = if (profile.softKind != SoftKind.NONE) soft.advanceTo(t) else 0f
        val st = if (profile.usesSostenuto) sos.advanceTo(t) else 0f
        env.sustain = s; env.soft = so; env.sostenuto = st
        env.registration = registration
        env.latchLo = 0L; env.latchHi = 0L
        if (p != null && profile.usesSostenuto && p.latchUs.isNotEmpty()) {
            val i = p.latchIndexAt(t)
            if (i >= 0) { env.latchLo = p.latchLo[i]; env.latchHi = p.latchHi[i] }
        }
        out.sustain = s; out.soft = so; out.sostenuto = st
        out.shiftMm = if (profile.softKind == SoftKind.UNA_CORDA) SHIFT_MM * PedalMotion.smoothstep(0f, 1f, so) else 0f
        out.hammerRailMm = if (profile.softKind == SoftKind.HAMMER_RAIL) UprightAction.RAIL_MM * so else 0f
        out.registers = if (profile.id == InstrumentId.HARPSICHORD) registration else 3
    }

    companion object { const val SHIFT_MM = 2.5f; const val ANGLE_DEG = 5f }
}
