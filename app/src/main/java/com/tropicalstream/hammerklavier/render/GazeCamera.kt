package com.tropicalstream.hammerklavier.render

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.HeadPose
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2

/**
 * 3DoF head look-around from the glasses' IMU, copied from MathCosmos's GazeCamera (game rotation
 * vector: gyro + accelerometer, no compass, so no magnetic jumps; smoothed yaw/pitch offsets
 * relative to a reference captured at start or on [recenter]) with the one permitted change
 * (PLAN §2.2, §5.2, R106): a [worldLocked] mode (the Hall) with no soft re-centre and ±60° yaw /
 * ±45° pitch clamps. Elsewhere the one-minute soft re-centre stays. The sensor callback (main
 * thread) also writes yaw and angular velocity to [HeadPose] for the audio (§3.12).
 *
 * The filter itself is [GazeFilter] (pure, unit-tested by T6.5).
 */
class GazeCamera(context: Context, private val head: HeadPose?) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager?
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val rot = FloatArray(9)
    val filter = GazeFilter()

    /** Smoothed offsets in radians: yaw positive = looking right, pitch positive = looking up. */
    val yaw: Float get() = filter.yaw
    val pitch: Float get() = filter.pitch
    var enabled: Boolean
        get() = filter.enabled
        set(v) { filter.enabled = v }
    var worldLocked: Boolean
        get() = filter.worldLocked
        set(v) { filter.worldLocked = v }

    fun available(): Boolean = sensor != null

    fun start() {
        val s = sensor ?: run { Log.w(HK.TAG_RENDER, "no rotation-vector sensor: head look-around disabled"); return }
        sm?.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        Log.i(HK.TAG_RENDER, "gaze from ${s.name}")
    }

    fun stop() { sm?.unregisterListener(this) }

    /** Make the current head pose "straight ahead". */
    fun recenter() = filter.recenter()

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        // The display faces the eyes, so the glasses' −Z axis (out of the lenses) is the gaze
        // direction; columns of R are the device axes in world (east, north, up).
        val gx = -rot[2]; val gy = -rot[5]; val gz = -rot[8]
        filter.feed(atan2(gx, gy), asin(gz.coerceIn(-1f, 1f)), e.timestamp)
        head?.write(filter.yaw, filter.omega)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

/**
 * MathCosmos's gaze smoothing, pure. [feed] takes the absolute heading and elevation (radians;
 * heading positive = turning right, the §2.3 yaw sign) and the event time.
 */
class GazeFilter {
    private var refYaw = Float.NaN
    private var refPitch = 0f
    private var lastHeading = Float.NaN
    private var lastNs = 0L

    @Volatile var yaw = 0f; private set
    @Volatile var pitch = 0f; private set
    /** Head angular velocity about +y (rad/s, + = turning right), for HeadPose. */
    @Volatile var omega = 0f; private set
    @Volatile var rawYaw = 0f; private set
    @Volatile var rawPitch = 0f; private set
    @Volatile var enabled = true
    /** Hall: no soft re-centre; clamps ±[LOCKED_YAW] / ±[LOCKED_PITCH]. */
    @Volatile var worldLocked = false

    fun recenter() { refYaw = Float.NaN; yaw = 0f; pitch = 0f }

    fun feed(heading: Float, elevation: Float, timestampNs: Long) {
        rawYaw = heading; rawPitch = elevation
        if (!lastHeading.isNaN() && timestampNs > lastNs) {
            val dt = (timestampNs - lastNs) * 1e-9f
            val w = wrap(heading - lastHeading) / dt
            omega += (w - omega) * 0.5f
        }
        lastHeading = heading; lastNs = timestampNs
        if (refYaw.isNaN()) { refYaw = heading; refPitch = elevation }
        val dy = wrap(heading - refYaw)
        val dp = elevation - refPitch
        if (!enabled) {
            // Keep the reference glued to the head so re-enabling starts straight ahead.
            refYaw = heading; refPitch = elevation
            yaw += (0f - yaw) * 0.1f; pitch += (0f - pitch) * 0.1f
            return
        }
        if (worldLocked) {
            yaw += (dy.coerceIn(-LOCKED_YAW, LOCKED_YAW) - yaw) * 0.25f
            pitch += (dp.coerceIn(-LOCKED_PITCH, LOCKED_PITCH) - pitch) * 0.25f
            return
        }
        yaw += (dy.coerceIn(-1.75f, 1.75f) - yaw) * 0.25f
        pitch += (dp.coerceIn(-1.0f, 1.0f) - pitch) * 0.25f
        // Soft re-centre: a held offset walks the reference toward the head (~1 minute).
        refYaw += dy * 0.0015f
        refPitch += dp * 0.0015f
    }

    private fun wrap(a: Float): Float {
        var d = a
        while (d > PI) d -= (2 * PI).toFloat()
        while (d < -PI) d += (2 * PI).toFloat()
        return d
    }

    companion object {
        const val LOCKED_YAW = (60.0 * PI / 180.0).toFloat()
        const val LOCKED_PITCH = (45.0 * PI / 180.0).toFloat()
    }
}
