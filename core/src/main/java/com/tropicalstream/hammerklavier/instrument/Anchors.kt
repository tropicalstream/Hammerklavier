package com.tropicalstream.hammerklavier.instrument

import com.tropicalstream.hammerklavier.contract.CameraPose
import com.tropicalstream.hammerklavier.contract.InstrumentAnchors
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.ViewId

/**
 * Camera, listener and key-position tables per instrument (PLAN §5.6), exactly as tabled. Piano
 * frame unless `roomFrame` (the Hall rows). `x_c` = the x of [centroidKey], `x_cut` = the x of
 * [focusKey] (CameraDirector passes the spring-filtered keys); both clamp to the compass.
 * Allocation-free (GLThread).
 */
class Anchors(val id: InstrumentId, private val keyboard: Keyboard) : InstrumentAnchors {
    override val keyX: FloatArray = keyboard.keyX
    override val soundSource: FloatArray = when (id) {
        InstrumentId.GRAND -> floatArrayOf(0f, 0.90f, -1.00f)
        InstrumentId.UPRIGHT -> floatArrayOf(0f, 1.00f, -0.45f)
        InstrumentId.HARPSICHORD -> floatArrayOf(0f, 0.85f, -1.00f)
    }
    /** The Player listener. */
    override val benchEar: FloatArray = if (id == InstrumentId.HARPSICHORD) floatArrayOf(0f, 1.15f, 0.50f) else floatArrayOf(0f, 1.20f, 0.55f)

    private fun set(out: CameraPose, px: Float, py: Float, pz: Float, tx: Float, ty: Float, tz: Float, fov: Float, ipd: Float, zp: Float) {
        out.pos[0] = px; out.pos[1] = py; out.pos[2] = pz
        out.target[0] = tx; out.target[1] = ty; out.target[2] = tz
        out.vFovDeg = fov; out.ipdScale = ipd; out.zeroParallaxM = zp
    }

    override fun camera(view: ViewId, framing: Int, focusKey: Float, centroidKey: Float, out: CameraPose) {
        out.roomFrame = false; out.clipX = Float.NaN; out.lidLift = 0f
        val harp = id == InstrumentId.HARPSICHORD
        when (view) {
            ViewId.PLAYER -> if (framing == 0) {
                if (harp) set(out, -0.06f, 1.22f, 1.05f, 0f, 0.62f, -0.10f, 34f, 0.6f, 1.25f)
                else set(out, -0.10f, 1.30f, 1.55f, 0f, 0.50f, -0.12f, 34f, 0.6f, 1.75f)
            } else {
                val xc = keyboard.xOf(centroidKey)
                if (harp) set(out, xc, 1.10f, 0.55f, xc, 0.66f, -0.08f, 30f, 0.5f, 0.85f)
                else set(out, xc, 1.15f, 0.62f, xc, 0.70f, -0.08f, 30f, 0.5f, 0.95f)
            }
            ViewId.ACTION -> if (framing == 0) {
                val x = keyboard.xOf(focusKey)
                when (id) {
                    InstrumentId.GRAND -> set(out, x + 0.95f, 0.95f, 0.30f, x, 0.76f, -0.24f, 22f, 0.35f, 1.1f)
                    InstrumentId.UPRIGHT -> set(out, x + 1.05f, 1.00f, 0.10f, x, 0.93f, -0.20f, 26f, 0.35f, 1.1f)
                    InstrumentId.HARPSICHORD -> set(out, x + 0.60f, 0.93f, -0.02f, x, 0.86f, -0.42f, 24f, 0.3f, 0.73f)
                }
                out.clipX = x
            } else {
                when (id) {
                    InstrumentId.GRAND -> set(out, 0f, 1.95f, 0.55f, 0f, 0.84f, -0.90f, 44f, 0.5f, 1.8f)
                    InstrumentId.UPRIGHT -> set(out, 1.05f, 1.40f, 0.75f, 0f, 1.06f, -0.28f, 36f, 0.5f, 1.5f)
                    InstrumentId.HARPSICHORD -> set(out, 0f, 1.85f, 0.45f, 0f, 0.80f, -0.95f, 44f, 0.5f, 1.8f)
                }
                out.lidLift = 1f
            }
            ViewId.HALL -> {
                out.roomFrame = true
                if (id == InstrumentId.UPRIGHT) {
                    if (framing == 0) set(out, 0.4f, 1.20f, 3.0f, -1.6f, 1.60f, -2.65f, 40f, 1.0f, 6.0f)
                    else set(out, 0.4f, 1.20f, 3.0f, -1.8f, 1.00f, -2.85f, LIFE_SIZE_FOV, 1.0f, 6.0f)
                } else {
                    if (framing == 0) set(out, 0.4f, 1.20f, 3.0f, 0f, 1.65f, -1.9f, 40f, 1.0f, 4.9f)   // M5: was 1.95 (chandelier cut at the top edge; it stays overhead, +45° look-around)
                    else set(out, 0.4f, 1.20f, 3.0f, 0f, 1.05f, -1.9f, LIFE_SIZE_FOV, 1.0f, 4.9f)
                }
            }
        }
    }

    override fun listener(view: ViewId, framing: Int, out: FloatArray): Boolean {
        fun s(x: Float, y: Float, z: Float) { out[0] = x; out[1] = y; out[2] = z }
        when (view) {
            ViewId.PLAYER -> { s(benchEar[0], benchEar[1], benchEar[2]); return false }
            ViewId.ACTION -> {
                when (id) {
                    InstrumentId.GRAND -> if (framing == 0) s(0.30f, 1.00f, -0.30f) else s(0f, 1.60f, 0.10f)
                    InstrumentId.UPRIGHT -> if (framing == 0) s(0.30f, 1.05f, 0.05f) else s(0.50f, 1.45f, 0.40f)
                    InstrumentId.HARPSICHORD -> if (framing == 0) s(0.20f, 0.95f, -0.20f) else s(0f, 1.55f, 0.10f)
                }
                return false
            }
            ViewId.HALL -> { s(0.4f, 1.20f, 3.0f); return true }
        }
    }

    companion object {
        /** Hall life-size default (CONTROL `--ef fov` overrides it through RenderOverrides). */
        const val LIFE_SIZE_FOV = 18.27f
    }
}
