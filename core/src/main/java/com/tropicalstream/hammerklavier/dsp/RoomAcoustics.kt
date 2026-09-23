package com.tropicalstream.hammerklavier.dsp

import com.tropicalstream.hammerklavier.contract.Conventions
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.ListenerPose
import com.tropicalstream.hammerklavier.contract.Placement
import com.tropicalstream.hammerklavier.contract.ReverbMode
import com.tropicalstream.hammerklavier.contract.RoomDesign
import com.tropicalstream.hammerklavier.contract.RoomDesigner
import com.tropicalstream.hammerklavier.contract.VenueGeometry
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The room designer (PLAN §3.12): Sabine T60 per band from the venue's plane × material table,
 * 6 first- and 6 second-order image sources of the soundboard centre for the current ear, the
 * direct path, the critical distance and the embedded-room compensation. Pure; main or HKLoader.
 */
object RoomAcoustics : RoomDesigner {
    const val BANDS = 7                         // 125, 250, 500, 1k, 2k, 4k, 8k Hz
    const val SPEED_OF_SOUND = 343.0
    const val DIRECTIVITY_Q = 2.0
    const val BRIGHT_LP_HZ = 12_000f
    const val DULL_LP_HZ = 5_000f
    const val BRIGHT_ALPHA_4K = 0.15f

    /** Tap order: first-order floor, ceiling, N, S, E, W; then floor–ceiling, ceiling–floor, N–S, S–N, E–W, W–E. */
    val TAP_PLANES: Array<IntArray> = arrayOf(intArrayOf(0), intArrayOf(1), intArrayOf(2), intArrayOf(3), intArrayOf(4), intArrayOf(5),
        intArrayOf(0, 1), intArrayOf(1, 0), intArrayOf(2, 3), intArrayOf(3, 2), intArrayOf(4, 5), intArrayOf(5, 4))
    private val PLANE_AXIS = intArrayOf(1, 1, 2, 2, 0, 0)

    /** Reverberant level of each mode in dB and its T60 scale. */
    fun modeDb(mode: ReverbMode): Double = when (mode) { ReverbMode.DRY -> -6.0; ReverbMode.ROOM -> 0.0; ReverbMode.RESONANT -> 3.0 }
    fun modeT60Scale(mode: ReverbMode): Double = if (mode == ReverbMode.RESONANT) 1.2 else 1.0

    /** Total absorption in sabins (m²) per band: Σ S·α + N·A_person + 4mV. */
    fun absorption(g: VenueGeometry): DoubleArray = DoubleArray(BANDS) { b ->
        var a = 0.0
        for (s in g.surfaces) a += s.areaM2 * s.material.alpha[b]
        a + g.people * g.personSabins[b] + 4.0 * g.airM[b] * g.volumeM3
    }

    /** Sabine T60 per band, s. */
    fun sabineT60(g: VenueGeometry): DoubleArray { val a = absorption(g); return DoubleArray(BANDS) { 0.161 * g.volumeM3 / a[it] } }

    fun t60Mid(t: DoubleArray): Double = 0.5 * (t[2] + t[3])

    /** Critical distance r_c = 0.057·√(Q·V/T) with T = t60Mid. */
    fun criticalDistance(g: VenueGeometry): Double = 0.057 * sqrt(DIRECTIVITY_Q * g.volumeM3 / t60Mid(sabineT60(g)))

    /** Direct-to-reverberant ratio at distance r, dB: 20·log10(r_c / r). */
    fun drrDb(g: VenueGeometry, r: Double): Double = 20.0 * kotlin.math.log10(criticalDistance(g) / r)

    /** Area-weighted α of one plane in one band. */
    fun planeAlpha(g: VenueGeometry, plane: Int, band: Int): Double {
        var sa = 0.0; var s = 0.0
        for (x in g.surfaces) if (x.plane == plane) { sa += x.areaM2 * x.material.alpha[band]; s += x.areaM2 }
        return if (s > 0) sa / s else 0.0
    }

    /** Image of [p] (room frame) through the planes in order. */
    fun image(g: VenueGeometry, p: DoubleArray, planes: IntArray): DoubleArray {
        val q = p.copyOf()
        for (pl in planes) { val ax = PLANE_AXIS[pl]; q[ax] = 2.0 * g.erPlanes[pl] - q[ax] }
        return q
    }

    /** The embedded-room factor √(R_sim / R_target) of §3.12 (power ratios reverberant / direct). */
    fun embeddedFactor(rTarget: Double, embeddedRoomDb: Float): Double {
        val rEmb = 10.0.pow(-embeddedRoomDb / 10.0)
        val rSim = max(rTarget - rEmb, 0.1 * rTarget)
        return sqrt(rSim / rTarget)
    }

    override fun design(g: VenueGeometry, placement: Placement, sourcePiano: FloatArray, listener: ListenerPose,
                        mode: ReverbMode, benchDistanceM: Float, embeddedRoomDb: Float): RoomDesign {
        val fs = HK.SR.toDouble()
        val srcF = FloatArray(3); placement.toRoom(sourcePiano, srcF)
        val src = DoubleArray(3) { srcF[it].toDouble() }
        val ear = DoubleArray(3) { listener.earRoom[it].toDouble() }
        val d = dist(ear, src)
        val t60 = sabineT60(g)
        val rc = criticalDistance(g)
        val fwd = listener.forwardYawRad.toDouble()

        val n = TAP_PLANES.size
        val erDelay = IntArray(n); val erGainL = FloatArray(n); val erGainR = FloatArray(n); val erBright = BooleanArray(n)
        for (i in 0 until n) {
            val planes = TAP_PLANES[i]
            val p = image(g, src, planes)
            val di = dist(ear, p)
            var gain = d / di
            var bright = true
            for (pl in planes) {
                gain *= sqrt(1.0 - planeAlpha(g, pl, 2))
                if (planeAlpha(g, pl, 5) >= BRIGHT_ALPHA_4K) bright = false
            }
            val az = yaw(p[0] - ear[0], p[2] - ear[2])
            val th = (sin(az - fwd) + 1.0) * PI / 4
            erDelay[i] = max(1, Math.round((di - d) * fs / SPEED_OF_SOUND).toInt())
            erGainL[i] = (gain * cos(th)).toFloat(); erGainR[i] = (gain * sin(th)).toFloat()
            erBright[i] = bright
        }
        val pre = erDelay.min()

        val bench = benchDistanceM.toDouble()
        val rTarget = (d / rc).pow(2)               // reverberant / direct power at this listener
        val emb = embeddedFactor(rTarget, embeddedRoomDb)
        val modeLin = 10.0.pow(modeDb(mode) / 20.0)
        val ts = modeT60Scale(mode)
        return RoomDesign(
            erDelay = erDelay, erGainL = erGainL, erGainR = erGainR, erBright = erBright,
            brightLpHz = BRIGHT_LP_HZ, dullLpHz = DULL_LP_HZ, preDelayFrames = pre,
            t60Low = (t60[0] * ts).toFloat(), t60Mid = (t60Mid(t60) * ts).toFloat(), t60High = (t60[6] * ts).toFloat(),
            reverbGain = (bench / rc * modeLin * emb).toFloat(),
            erGain = emb.toFloat(),
            directGain = (bench / d).coerceIn(0.25, 1.6).toFloat(),
            airLpHz = (18000.0 - 1600.0 * (d - 1.6)).coerceIn(9000.0, 18000.0).toFloat(),
            width = listener.directWidth,
            worldLocked = listener.worldLocked,
            sourceAzimuthRad = yaw(src[0] - ear[0], src[2] - ear[2]).toFloat())
    }

    private fun dist(a: DoubleArray, b: DoubleArray): Double {
        val x = a[0] - b[0]; val y = a[1] - b[1]; val z = a[2] - b[2]; return sqrt(x * x + y * y + z * z)
    }

    private fun yaw(dx: Double, dz: Double): Double = Conventions.yawOf(floatArrayOf(dx.toFloat(), 0f, dz.toFloat())).toDouble()
}
