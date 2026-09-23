package com.tropicalstream.hammerklavier.mech

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.HarpsiTiming
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.MechanismPose
import com.tropicalstream.hammerklavier.contract.PedalMotion

/**
 * Harpsichord (PLAN §5.7, [HarpsiTiming]): dip `0.70·u^1.8` from d0 to the 8′ pluck at `on`, then
 * to the bed over 0.45 of the lead; the 4′ plucks at dip 0.433 (on − staggerMs(v)·r). Release is the
 * quadratic 55 ms fall; the 8′ tongue flicks 0 → 1 → 0 over 25 ms·r from off + 30.1 ms·r, the 4′
 * from off + 41.4 ms·r. Jacks rise with the key (hammer = jack4 = dip).
 *
 * Damper (cloth on the jack): lift = clamp((dip·6 mm − 1.5 mm)/1.5 mm, 0, 1), so it is 0 (on the
 * string) exactly when the falling key passes dip 0.25, at off + 47.6 ms·r. (The plan's literal
 * `clamp(dip·6/1.5)` reaches 0 only at rest; the offset form is the one that touches at 47.6 ms,
 * which T5.4 and the audio's damping instant require. Recorded in docs/progress/WP5.md.)
 */
class HarpsichordAction(override val profile: InstrumentProfile = InstrumentProfile.HARPSICHORD) : KeyAction {
    override fun isolatedLeadUs(vel: Int, r: Float): Float = HarpsiTiming.leadMs(vel) * r * 1000f
    override fun contactUs(key: Int, r: Float): Float = 0f

    override fun pressDip(n: NoteCtx, t: Long, r: Float): Float {
        val d0 = n.d0
        if (t < n.onUs) {
            if (t <= n.tStartUs) return d0
            val u = if (n.leadUs <= 0f) 1f else (t - n.tStartUs).toFloat() / n.leadUs
            if (d0 >= PLUCK) {
                // Re-struck before the quill has passed back under its string: the key keeps rising
                // to REENGAGE (the quill re-engages) in the first half, then presses to the pluck.
                if (u < 0.5f) return d0 + (REENGAGE - d0) * PedalMotion.smoothstep(0f, 1f, 2f * u)
                return REENGAGE + (PLUCK - REENGAGE) * Touch.pow18(2f * u - 1f)
            }
            return d0 + (PLUCK - d0) * Touch.pow18(u)
        }
        val span = 0.45f * n.leadUs
        val f = if (span <= 0f) 1f else minOf(1f, (t - n.onUs).toFloat() / span)
        return PLUCK + (1f - PLUCK) * f
    }

    override fun releaseDip(dHeld: Float, sinceOffUs: Float, r: Float): Float =
        HarpsiTiming.returnDip(dHeld, sinceOffUs / (r * 1000f))

    override fun pose(k: Int, n: NoteCtx?, t: Long, env: FrameEnv, out: MechanismPose) {
        out.escape[k] = 0f
        if (n == null) {
            out.keyDip[k] = 0f; out.hammer[k] = 0f; out.jack4[k] = 0f; out.damper[k] = 0f
            out.tongue[k] = 0f; out.tongue4[k] = 0f
            return
        }
        val r = env.r
        var t8 = 0f; var t4 = 0f
        val dip: Float
        if (t < n.offUs) dip = pressDip(n, t, r) else {
            val since = (t - n.offUs).toFloat()
            dip = releaseDip(n.dHeld, since, r)
            val flick = TONGUE_MS * r * 1000f
            if (env.registration and HK.REG_8 != 0 && n.dHeld >= PLUCK - 1e-4f)
                t8 = tri((since - HarpsiTiming.quill8PassMs * r * 1000f) / flick)
            if (env.registration and HK.REG_4 != 0 && n.dHeld >= PLUCK4 - 1e-4f)
                t4 = tri((since - HarpsiTiming.quill4PassMs * r * 1000f) / flick)
        }
        out.keyDip[k] = dip
        out.hammer[k] = dip * HarpsiTiming.JACK_RATIO
        out.jack4[k] = dip * HarpsiTiming.JACK_RATIO
        out.damper[k] = damperLift(dip)
        out.tongue[k] = t8; out.tongue4[k] = t4
    }

    companion object {
        const val PLUCK = HarpsiTiming.PLUCK8_MM / HarpsiTiming.DIP_MM      // 0.70
        const val PLUCK4 = 0.433f
        /** Depth the key rises to on a re-strike caught below the pluck point (quill re-engages). */
        const val REENGAGE = 0.60f
        const val TONGUE_MS = 25f

        fun damperLift(dip: Float): Float =
            ((dip * HarpsiTiming.DIP_MM - HarpsiTiming.CLOTH_MM) / HarpsiTiming.CLOTH_MM).coerceIn(0f, 1f)

        /** 0 → 1 → 0 over x in [0, 1]; 0 outside. */
        fun tri(x: Float): Float = if (x <= 0f || x >= 1f) 0f else 1f - kotlin.math.abs(2f * x - 1f)
    }
}
