package com.tropicalstream.hammerklavier.session

import com.tropicalstream.hammerklavier.contract.Conventions
import com.tropicalstream.hammerklavier.contract.InstrumentAnchors
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.ListenerPose
import com.tropicalstream.hammerklavier.contract.Placement
import com.tropicalstream.hammerklavier.contract.RoomDesign
import com.tropicalstream.hammerklavier.contract.ViewId
import kotlin.math.sqrt

/**
 * Listener poses per (instrument, view, framing) (PLAN §5.6, §3.12). The ear comes from the
 * instrument's [InstrumentAnchors.listener] (WP7); [TABLE] is the §5.6 table itself, used when
 * the anchors return no position and by the tests. The listener faces the sound source
 * ([Conventions.forwardYaw]); direct width Player 1.0, Action 0.8, Hall 0.4; world-locked only in
 * the Hall. Main or HKLoader; allocates (preparation time only).
 */
object ListenerRooms {

    /** §5.6 sound sources, piano frame. */
    val SOURCE: Map<InstrumentId, FloatArray> = mapOf(
        InstrumentId.GRAND to floatArrayOf(0f, 0.90f, -1.00f),
        InstrumentId.UPRIGHT to floatArrayOf(0f, 1.00f, -0.45f),
        InstrumentId.HARPSICHORD to floatArrayOf(0f, 0.85f, -1.00f))

    /** The Hall seat, room frame (row 3), for every instrument. */
    val HALL_EAR = floatArrayOf(0.4f, 1.20f, 3.0f)

    /**
     * §5.6 listeners: key = (instrument, view, framing). Player: both framings; Action: 0 =
     * cutaway, 1 = overhead; Hall: room frame.
     */
    fun table(id: InstrumentId, view: ViewId, framing: Int): FloatArray = when (view) {
        ViewId.PLAYER -> when (id) {
            InstrumentId.HARPSICHORD -> floatArrayOf(0f, 1.15f, 0.50f)
            else -> floatArrayOf(0f, 1.20f, 0.55f)
        }
        ViewId.ACTION -> when (id) {
            InstrumentId.GRAND -> if (framing == 0) floatArrayOf(0.30f, 1.00f, -0.30f) else floatArrayOf(0f, 1.60f, 0.10f)
            InstrumentId.UPRIGHT -> if (framing == 0) floatArrayOf(0.30f, 1.05f, 0.05f) else floatArrayOf(0.50f, 1.45f, 0.40f)
            InstrumentId.HARPSICHORD -> if (framing == 0) floatArrayOf(0.20f, 0.95f, -0.20f) else floatArrayOf(0f, 1.55f, 0.10f)
        }
        ViewId.HALL -> HALL_EAR.copyOf()
    }

    fun directWidth(view: ViewId): Float = when (view) { ViewId.PLAYER -> 1.0f; ViewId.ACTION -> 0.8f; ViewId.HALL -> 0.4f }

    /** Result of [resolve]: the pose plus the ear in the piano frame (NaN in the Hall). */
    class Resolved(val pose: ListenerPose, val earPiano: FloatArray?)

    /**
     * The listener for (view, framing) of the instrument drawn by [anchors] at [placement].
     * Falls back to [table] for [id] when the anchors leave a NaN in the ear.
     */
    fun resolve(id: InstrumentId, anchors: InstrumentAnchors?, placement: Placement, view: ViewId, framing: Int): Resolved {
        val ear = FloatArray(3)
        var roomFrame: Boolean
        if (anchors != null) {
            roomFrame = anchors.listener(view, framing, ear)
            if (ear.any { it.isNaN() }) { table(id, view, framing).copyInto(ear); roomFrame = view == ViewId.HALL }
        } else {
            table(id, view, framing).copyInto(ear); roomFrame = view == ViewId.HALL
        }
        val source = anchors?.soundSource ?: SOURCE.getValue(id)
        val earRoom = FloatArray(3)
        val yaw: Float
        if (roomFrame) {
            ear.copyInto(earRoom)
            val s = FloatArray(3); placement.toRoom(source, s)
            yaw = Conventions.yawOf(floatArrayOf(s[0] - earRoom[0], s[1] - earRoom[1], s[2] - earRoom[2]))
        } else {
            placement.toRoom(ear, earRoom)
            yaw = Conventions.forwardYaw(placement, ear, source)
        }
        val pose = ListenerPose(earRoom = earRoom, forwardYawRad = yaw, worldLocked = view == ViewId.HALL,
            directWidth = directWidth(view))
        return Resolved(pose, if (roomFrame) null else ear)
    }

    /**
     * Per-seat loudness residual, dB (INTEGRATION.md, loudness across instruments and views): what
     * [com.tropicalstream.hammerklavier.dsp.RoomAcoustics.levelGain]'s power model leaves between a
     * view and the Player, measured as BS.1770 integrated loudness on real-kit renders (six pieces,
     * Room mode, LoudnessProbeTest) and averaged. Player framings share the bench ear.
     */
    fun levelTrimDb(id: InstrumentId, view: ViewId, framing: Int): Float = when (view) {
        ViewId.PLAYER -> 0f
        ViewId.ACTION -> TRIM_ACTION.getValue(id)[framing.coerceIn(0, 1)]
        ViewId.HALL -> TRIM_HALL.getValue(id)
    }

    private val TRIM_ACTION: Map<InstrumentId, FloatArray> = mapOf(
        InstrumentId.GRAND to floatArrayOf(0.52f, 0.60f),
        InstrumentId.UPRIGHT to floatArrayOf(0.24f, 0.38f),
        InstrumentId.HARPSICHORD to floatArrayOf(1.27f, -0.04f))
    private val TRIM_HALL: Map<InstrumentId, Float> = mapOf(
        InstrumentId.GRAND to 1.19f, InstrumentId.UPRIGHT to -0.67f, InstrumentId.HARPSICHORD to -0.74f)

    /** [d] with the seat residual of (id, view, framing) applied to its levelGain. */
    fun leveled(d: RoomDesign, id: InstrumentId, view: ViewId, framing: Int): RoomDesign =
        d.timesLevel(Math.pow(10.0, levelTrimDb(id, view, framing) / 20.0).toFloat())

    /** `benchDistanceM`: the Player listener's distance to the source (piano frame). */
    fun benchDistance(id: InstrumentId, anchors: InstrumentAnchors?): Float {
        val ear = FloatArray(3)
        if (anchors == null || anchors.listener(ViewId.PLAYER, 0, ear) || ear.any { it.isNaN() })
            table(id, ViewId.PLAYER, 0).copyInto(ear)
        val s = anchors?.soundSource ?: SOURCE.getValue(id)
        val dx = ear[0] - s[0]; val dy = ear[1] - s[1]; val dz = ear[2] - s[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
