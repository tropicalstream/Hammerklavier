package com.tropicalstream.hammerklavier.mech

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.MechanismPose

/**
 * Focus and centroid keys (PLAN §5.7): `focusKey` = the highest key that is down or was struck
 * within the last 0.5 s of song time; it moves up at once and moves down only after it has gone
 * unsupported for 1.5 s. `centroidKey` = the mean of sounding keys (stringAmp > 0.02) weighted by
 * stringAmp; both hold their last value when nothing qualifies. Springs live in CameraDirector.
 */
class FocusTracker {
    private var focus = 60
    private var supportedUs = Long.MIN_VALUE      // last time the current focus key was itself active
    private var centroid = 60f

    fun reset() { supportedUs = Long.MIN_VALUE }

    /** [down]: key currently held; [lastOnUs]: last contact at or before t (Long.MIN_VALUE = none). */
    fun update(t: Long, down: BooleanArray, lastOnUs: LongArray, out: MechanismPose) {
        var cand = -1
        var k = HK.KEYS - 1
        while (k >= 0) {
            if (down[k] || (lastOnUs[k] != Long.MIN_VALUE && t - lastOnUs[k] <= ACTIVE_US)) { cand = k; break }
            k--
        }
        if (cand >= 0) {
            val focusActive = down[focus] || (lastOnUs[focus] != Long.MIN_VALUE && t - lastOnUs[focus] <= ACTIVE_US)
            if (focusActive) supportedUs = t
            if (cand > focus || supportedUs == Long.MIN_VALUE) { focus = cand; supportedUs = t }
            else if (cand < focus && t - supportedUs >= HOLD_US) { focus = cand; supportedUs = t }
        }
        var sw = 0f; var sk = 0f
        for (i in 0 until HK.KEYS) {
            val a = out.stringAmp[i]
            if (a > 0.02f) { sw += a; sk += a * i }
        }
        if (sw > 0f) centroid = sk / sw
        out.focusKey = focus.toFloat()
        out.centroidKey = centroid
    }

    companion object { const val ACTIVE_US = 500_000L; const val HOLD_US = 1_500_000L }
}
