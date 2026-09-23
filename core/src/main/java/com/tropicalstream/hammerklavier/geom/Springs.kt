package com.tropicalstream.hammerklavier.geom

/**
 * A critically damped spring (the "SmoothDamp" form: a rational approximation of exp(−ωdt), so no
 * transcendental maths runs per frame, PLAN §2.1 rule 5), with an optional speed cap. Stable for
 * any dt. GLThread; allocation-free.
 */
class CritSpring(var omega: Float, var maxSpeed: Float = Float.POSITIVE_INFINITY) {
    @JvmField var x = 0f
    @JvmField var v = 0f
    private var primed = false

    /** Jump to [value] with no motion (a cut). */
    fun snap(value: Float) { x = value; v = 0f; primed = true }

    val isPrimed: Boolean get() = primed

    fun update(target: Float, dt: Float): Float {
        if (!primed) { snap(target); return x }
        if (dt <= 0f) return x
        val w = omega
        val k = w * dt
        val e = 1f / (1f + k + 0.48f * k * k + 0.235f * k * k * k)     // ≈ exp(−ωdt)
        val change = x - target
        val temp = (v + w * change) * dt
        var nx = target + (change + temp) * e
        var nv = (v - w * temp) * e
        if (maxSpeed.isFinite()) {
            val maxStep = maxSpeed * dt
            val step = nx - x
            if (step > maxStep) { nx = x + maxStep; nv = minOf(nv, maxSpeed) }
            else if (step < -maxStep) { nx = x - maxStep; nv = maxOf(nv, -maxSpeed) }
            if (nv > maxSpeed) nv = maxSpeed else if (nv < -maxSpeed) nv = -maxSpeed
        }
        x = nx; v = nv
        return x
    }
}

/**
 * A dead band: the held value moves to the input only when the input leaves ±[band] around it
 * (the Action cutaway's ±2-semitone band on the focus key, PLAN §5.6).
 */
class DeadBand(var band: Float) {
    @JvmField var held = Float.NaN

    fun reset(value: Float) { held = value }

    fun update(input: Float): Float {
        if (held.isNaN() || input > held + band || input < held - band) held = input
        return held
    }
}
