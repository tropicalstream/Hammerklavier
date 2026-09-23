package com.tropicalstream.hammerklavier.contract

import kotlin.math.sqrt

// Shared by audio and visuals: ONE implementation (PLAN §2.3, §3.7, §5.7).
// Every function here is allocation-free and uses no transcendental maths (sqrt is allowed).

object PedalMotion {
    const val DOWN_RAMP_MS = 70f; const val UP_RAMP_MS = 60f; const val SOFT_RAMP_MS = 60f   // song time (baked into the curves)
    const val LIFT_START = 0.33f                  // dampers leave the strings at 1/3 of travel (Redekop)
    const val CLEAR = 0.55f                       // dampers fully clear: sostenuto can catch every tab from here
    const val NOISE_MIN_SPACING_MS = 150f

    /** Cubic smoothstep: 0 at x <= a, 1 at x >= b. */
    fun smoothstep(a: Float, b: Float, x: Float): Float {
        if (b == a) return if (x < a) 0f else 1f
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** ((p - 0.33) / 0.67).coerceIn(0, 1): damper lift from the sustain pedal. */
    fun damperLiftByPedal(p: Float): Float = ((p - LIFT_START) / (1f - LIFT_START)).coerceIn(0f, 1f)

    /** 1 - smoothstep(0.33, 0.55, p); 1 = fully damping. */
    fun pedalDamping(p: Float): Float = 1f - smoothstep(LIFT_START, CLEAR, p)

    /** ((h - 0.5) / 0.5).coerceIn(0, 1): damper lift from the key's own hammer travel. */
    fun keyDamperLift(hammerFrac: Float): Float = ((hammerFrac - 0.5f) / 0.5f).coerceIn(0f, 1f)
}

/** Grand and upright key release. */
object KeyReturn {
    /** dHeld · (1 − smoothstep(0, 1, x)), x = (t − off) / keyReturn; starts and ends at rest speed. */
    fun dip(dHeld: Float, x: Float): Float = dHeld * (1f - PedalMotion.smoothstep(0f, 1f, x))

    const val LAND_X = 0.5247f                    // 1.08 · dip = 0.5 at x = 0.5247 (dHeld = 1)

    /** LAND_X · keyReturnMs: grand 18.4 ms, upright 26.2 ms (real time). */
    fun damperLandMs(keyReturnMs: Float): Float = LAND_X * keyReturnMs
}

object HarpsiTiming {
    const val DIP_MM = 6.0f; const val PLUCK8_MM = 4.2f; const val PLUCK4_MM = 2.6f; const val JACK_RATIO = 1.0f
    const val CLOTH_MM = 1.5f; const val RETURN_MS = 55f                     // quadratic (gravity-like) return

    /** 40 - 25 * vel / 127: key start -> 8' pluck (real time, ms). */
    fun leadMs(vel: Int): Float = 40f - 25f * vel.coerceIn(0, 127) / 127f

    /** 0.2339 * leadMs(vel) = lead * (1 - (2.6/4.2)^(1/1.8)); 3.5–9.4 ms, real time. */
    fun staggerMs(vel: Int): Float = STAGGER_FRACTION * leadMs(vel)

    /** Key depth fraction at normalised time u in [0,1] to the 8' pluck: 0.70 * u^1.8. */
    fun depth(u: Float): Float = 0.70f * pow18(u.coerceIn(0f, 1f))   // table, no pow on GLThread

    /** dHeld · (1 − (t / RETURN_MS)²), clamped at 0. */
    fun returnDip(dHeld: Float, t: Float): Float {
        val x = t / RETURN_MS
        return (dHeld * (1f - x * x)).coerceAtLeast(0f)
    }

    /** 55 · √(1 − 4.2/6) = 30.1 ms: 8' tongue flick, jack-fall noise and release start. */
    val quill8PassMs: Float = RETURN_MS * sqrt(1f - PLUCK8_MM / DIP_MM)
    /** 55 · √(1 − 2.6/6) = 41.4 ms. */
    val quill4PassMs: Float = RETURN_MS * sqrt(1f - PLUCK4_MM / DIP_MM)
    /** 55 · √(1 − 1.5/6) = 47.6 ms: the cloth touches the string. */
    val damperLandMs: Float = RETURN_MS * sqrt(1f - CLOTH_MM / DIP_MM)

    /** 1 − (2.6/4.2)^(1/1.8), computed once (0.2339). */
    private val STAGGER_FRACTION: Float =
        (1.0 - Math.pow((PLUCK4_MM / PLUCK8_MM).toDouble(), 1.0 / 1.8)).toFloat()

    /** u^1.8 for u in [0, 1], from a 257-entry table prepared once (no pow per call). */
    private val POW18 = FloatArray(POW_N + 1) { Math.pow(it.toDouble() / POW_N, 1.8).toFloat() }
    private const val POW_N = 256
    private fun pow18(u: Float): Float {
        val x = u * POW_N
        val i = x.toInt().coerceIn(0, POW_N - 1)
        val f = x - i
        return POW18[i] + (POW18[i + 1] - POW18[i]) * f
    }
}
