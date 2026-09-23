package com.tropicalstream.hammerklavier.geom

import com.tropicalstream.hammerklavier.contract.CameraPose
import com.tropicalstream.hammerklavier.contract.InstrumentAnchors
import com.tropicalstream.hammerklavier.contract.MechanismPose
import com.tropicalstream.hammerklavier.contract.Placement
import com.tropicalstream.hammerklavier.contract.ViewId

/** Head look-around offsets for one frame (from GazeCamera, radians; yaw + = right, pitch + = up). */
class Gaze {
    @JvmField var yaw = 0f
    @JvmField var pitch = 0f
}

/**
 * View state, the dip and the tracking springs (PLAN §5.6). The anchors fill the whole
 * [CameraPose] (position, target, FOV, IPD scale, zero parallax, roomFrame, clipX, lidLift); the
 * director only
 * - tracks: `x_cut` follows `pose.focusKey` through a ±2-semitone dead band and a critically damped
 *   spring (ω = 4 rad/s); `x_c` follows `pose.centroidKey` (ω = 6 rad/s, capped at 0.6 m/s);
 * - dips: 250 ms fade to black, the cut (camera, lid, clip plane, venue level, listener, and any
 *   scene swap the renderer queued), 250 ms fade in; queue depth 1; after a display rest the cut
 *   is immediate;
 * - converts a piano-frame pose to the room frame with the instrument's Placement: [update] always
 *   writes room-frame positions (`out.roomFrame = true`), so the renderer draws one world;
 *   `clipX` stays in the piano frame (the skinned shader tests model-space x);
 * - scales the gaze: world-locked (full look-around) in the Hall, elsewhere ±5° of parallax
 *   (look direction × 0.15, clamped).
 * GLThread; allocation-free.
 */
class CameraDirector(private val dipOutSec: Float = 0.25f, private val dipInSec: Float = 0.25f) {
    enum class Phase { STEADY, OUT, IN }

    private var anchors: InstrumentAnchors? = null
    private var placement: Placement? = null
    private var lowKey = 21
    private var highKey = 108

    /** The view and framing on screen. */
    var view = ViewId.PLAYER; private set
    var framing = 0; private set
    var phase = Phase.STEADY; private set
    private var phaseT = 0f
    private var target = -1                      // view*2+framing the running dip cuts to, -1 = same view (scene swap)
    private var queued = NONE                    // queue depth 1
    private var immediate = false

    /** Black amount of the fade quad this frame, 0 (clear) .. 1 (fully black). */
    var fade = 0f; private set
    /** True in exactly the frame where the cut happens (apply queued scene swaps then). */
    var cutThisFrame = false; private set
    /** Gaze to hand the StereoRig this frame. */
    @JvmField var gazeYaw = 0f
    @JvmField var gazePitch = 0f
    /** True when the current view is world-locked (Hall). */
    val worldLocked: Boolean get() = view == ViewId.HALL

    private val focusBand = DeadBand(2f)
    /** x_cut in metres (piano frame). */
    val cutSpring = CritSpring(omega = 4f)
    /** x_c in metres (piano frame). */
    val followSpring = CritSpring(omega = 6f, maxSpeed = 0.6f)
    private val piano = CameraPose()
    private var overrideIpd = Float.NaN
    private var overrideFov = Float.NaN

    fun setScene(anchors: InstrumentAnchors, placement: Placement, lowKey: Int, highKey: Int) {
        this.anchors = anchors; this.placement = placement; this.lowKey = lowKey; this.highKey = highKey
        focusBand.held = Float.NaN
        cutSpring.snap(keyToX(60f)); followSpring.snap(keyToX(60f))
        primed = false
    }

    private var primed = false

    /** CONTROL overrides for the current framing (NaN = none). */
    fun setOverrides(ipdScale: Float, vFovDeg: Float) { overrideIpd = ipdScale; overrideFov = vFovDeg }

    /** Ask for a view/framing; a dip unless it is already shown (or the next cut is immediate). */
    fun request(v: ViewId, framing: Int) {
        val code = v.ordinal * 2 + framing
        when (phase) {
            Phase.STEADY -> if (code != view.ordinal * 2 + this.framing) startDip(code)
            Phase.OUT -> target = code
            Phase.IN -> queued = code
        }
    }

    /** A dip with no view change, for a scene swap (instrument, venue level, GL re-upload). */
    fun requestDip() {
        when (phase) {
            Phase.STEADY -> startDip(-1)
            Phase.OUT -> {}
            Phase.IN -> if (queued == NONE) queued = -1
        }
    }

    /** The next update applies any pending cut at once, with no fade (the first frame after a display rest). */
    fun cutImmediately() { immediate = true }

    /** Jump to a view with no dip and no springs (start-up). */
    fun jump(v: ViewId, framing: Int) { view = v; this.framing = framing; phase = Phase.STEADY; fade = 0f; queued = NONE; primed = false }

    val dipping: Boolean get() = phase != Phase.STEADY

    private fun startDip(code: Int) { phase = Phase.OUT; phaseT = 0f; target = code }

    private fun applyCut() {
        if (target >= 0) { view = ViewId.entries[target / 2]; framing = target % 2 }
        target = -1
        cutThisFrame = true
        primed = false                            // springs snap to the new framing
    }

    fun update(dt: Float, pose: MechanismPose, gaze: Gaze, out: CameraPose) {
        cutThisFrame = false
        if (immediate) {
            immediate = false
            var code = NONE
            if (phase == Phase.OUT) code = target
            if (queued != NONE) code = queued
            queued = NONE
            if (code != NONE) { target = code; applyCut() }
            phase = Phase.STEADY; phaseT = 0f; fade = 0f
        } else when (phase) {
            Phase.STEADY -> fade = 0f
            Phase.OUT -> {
                phaseT += dt
                if (phaseT >= dipOutSec) {
                    applyCut()
                    phase = Phase.IN; phaseT -= dipOutSec
                    fade = 1f
                } else fade = phaseT / dipOutSec
            }
            Phase.IN -> {
                phaseT += dt
                if (phaseT >= dipInSec) {
                    fade = 0f; phase = Phase.STEADY; phaseT = 0f
                    if (queued != NONE) { val q = queued; queued = NONE; if (q != view.ordinal * 2 + framing || q == -1) startDip(q) }
                } else fade = 1f - phaseT / dipInSec
            }
        }
        track(dt, pose)
        val a = anchors
        if (a == null) {
            out.pos[0] = 0f; out.pos[1] = 1.2f; out.pos[2] = 1.5f; out.target[0] = 0f; out.target[1] = 0.7f; out.target[2] = 0f
            out.roomFrame = false; out.clipX = Float.NaN; out.lidLift = 0f
        } else {
            a.camera(view, framing, xToKey(cutSpring.x), xToKey(followSpring.x), piano)
            copyPose(piano, out)
            if (!piano.roomFrame) {
                val p = placement
                if (p != null) { p.toRoom(out.pos, out.pos); p.toRoom(out.target, out.target) }
            }
            out.roomFrame = true
        }
        if (!overrideIpd.isNaN()) out.ipdScale = overrideIpd
        if (!overrideFov.isNaN()) out.vFovDeg = overrideFov
        if (worldLocked) { gazeYaw = gaze.yaw; gazePitch = gaze.pitch }
        else {
            gazeYaw = (gaze.yaw * PARALLAX).coerceIn(-PARALLAX_MAX, PARALLAX_MAX)
            gazePitch = (gaze.pitch * PARALLAX).coerceIn(-PARALLAX_MAX, PARALLAX_MAX)
        }
    }

    private fun track(dt: Float, pose: MechanismPose) {
        val focus = focusBand.update(pose.focusKey.coerceIn(lowKey.toFloat(), highKey.toFloat()))
        val cx = keyToX(focus)
        val fx = keyToX(pose.centroidKey.coerceIn(lowKey.toFloat(), highKey.toFloat()))
        if (!primed) { cutSpring.snap(cx); followSpring.snap(fx); primed = true }
        else { cutSpring.update(cx, dt); followSpring.update(fx, dt) }
    }

    /** Fractional key → x (m, piano frame), linear between the anchors' keyX. */
    fun keyToX(key: Float): Float {
        val a = anchors ?: return (key - 64.5f) * 0.01371f
        val kx = a.keyX
        val k = key.coerceIn(lowKey.toFloat(), highKey.toFloat())
        val i = k.toInt().coerceIn(lowKey, highKey)
        if (i >= highKey) return kx[highKey]
        val f = k - i
        return kx[i] + (kx[i + 1] - kx[i]) * f
    }

    /** Inverse of [keyToX] (keyX is monotone). */
    fun xToKey(x: Float): Float {
        val a = anchors ?: return x / 0.01371f + 64.5f
        val kx = a.keyX
        if (x <= kx[lowKey]) return lowKey.toFloat()
        if (x >= kx[highKey]) return highKey.toFloat()
        var lo = lowKey; var hi = highKey
        while (hi - lo > 1) { val m = (lo + hi) ushr 1; if (kx[m] <= x) lo = m else hi = m }
        val span = kx[hi] - kx[lo]
        return lo + if (span > 0f) (x - kx[lo]) / span else 0f
    }

    private fun copyPose(s: CameraPose, d: CameraPose) {
        for (i in 0 until 3) { d.pos[i] = s.pos[i]; d.target[i] = s.target[i] }
        d.roomFrame = s.roomFrame; d.vFovDeg = s.vFovDeg; d.ipdScale = s.ipdScale; d.zeroParallaxM = s.zeroParallaxM
        d.clipX = s.clipX; d.lidLift = s.lidLift
    }

    companion object {
        private const val NONE = -2
        const val PARALLAX = 0.15f
        const val PARALLAX_MAX = 5f * 0.017453292f
    }
}
