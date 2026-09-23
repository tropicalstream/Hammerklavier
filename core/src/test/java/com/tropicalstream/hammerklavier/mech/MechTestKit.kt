package com.tropicalstream.hammerklavier.mech

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.MechanismPose
import com.tropicalstream.hammerklavier.contract.PedalCurve
import com.tropicalstream.hammerklavier.contract.Performance
import com.tropicalstream.hammerklavier.contract.ScoreSpec
import com.tropicalstream.hammerklavier.contract.SyntheticScore
import com.tropicalstream.hammerklavier.contract.SyntheticSpecs
import com.tropicalstream.hammerklavier.contract.VisTime
import com.tropicalstream.hammerklavier.contract.stub.PerfFixtures

/** WP5's private test helpers. Times in the note lists are file ms (the pre-roll is added). */
object MechTestKit {
    const val PRE = HK.PRE_ROLL_US

    class N(val onMs: Double, val lenMs: Double, val key: Int, val vel: Int)

    fun perf(profile: InstrumentProfile, notes: List<N>, sustain: PedalCurve = PedalCurve.EMPTY,
             soft: PedalCurve = PedalCurve.EMPTY, sostenuto: PedalCurve = PedalCurve.EMPTY): Performance {
        val s = notes.sortedWith(compareBy({ it.onMs }, { it.key }))
        val spec = ScoreSpec(
            onUs = LongArray(s.size) { (s[it].onMs * 1000).toLong() },
            offUs = LongArray(s.size) { ((s[it].onMs + s[it].lenMs) * 1000).toLong() },
            key = ByteArray(s.size) { s[it].key.toByte() }, vel = ByteArray(s.size) { s[it].vel.toByte() },
            cc64 = PedalCurve.EMPTY, cc66 = PedalCurve.EMPTY, cc67 = PedalCurve.EMPTY)
        return PerfFixtures.build(spec, sustain = sustain, soft = soft, sostenuto = sostenuto, profile = profile, generation = 1)
    }

    fun synthetic(kind: SyntheticScore, profile: InstrumentProfile): Performance =
        PerfFixtures.build(SyntheticSpecs.notes(kind), profile = profile, generation = 1)

    /** A constant-value pedal curve in file time from [fromMs]. */
    fun hold(value: Float, fromMs: Long = 0): PedalCurve =
        PedalCurve(longArrayOf(fromMs * 1000, fromMs * 1000), floatArrayOf(0f, value))

    /** Fresh evaluation at song time [t] (reseed; empty window). */
    fun fresh(perf: Performance?, profile: InstrumentProfile, t: Long, r: Float = 1f, registration: Int = 3,
              exposure: Boolean = false): MechanismPose {
        val ev = MechanicsEvaluatorImpl(); ev.exposureEnabled = exposure
        ev.bind(perf, profile)
        val out = MechanismPose()
        ev.evaluate(vt(t, r, reseed = true, registration = registration), null, 0f, out)
        return out
    }

    fun vt(t: Long, r: Float = 1f, reseed: Boolean = false, from: Long = t, to: Long = t, registration: Int = 3,
           playing: Boolean = true): VisTime = VisTime().also {
        it.tUs = t; it.rate = r; it.reseed = reseed; it.exposeFromUs = from; it.exposeToUs = to
        it.registration = registration; it.playing = playing; it.epoch = 0; it.generation = 1
    }

    /** A reusable sequential driver with centred, tiling exposure windows. */
    class Driver(val perf: Performance, val profile: InstrumentProfile, exposure: Boolean = true, val r: Float = 1f) {
        val ev = MechanicsEvaluatorImpl().also { it.exposureEnabled = exposure; it.bind(perf, profile) }
        val out = MechanismPose()
        private val v = VisTime()
        private var prevT = Long.MIN_VALUE
        private var edge = 0L
        var registration = 3

        /** Frame at [t]; its window runs from the previous edge to halfway to the next frame [next]. */
        fun frame(t: Long, next: Long, playing: Boolean = true, reseed: Boolean = false): MechanismPose {
            val first = prevT == Long.MIN_VALUE || reseed
            val newEdge = t + (next - t) / 2
            v.tUs = t; v.rate = r; v.playing = playing; v.registration = registration; v.epoch = 0; v.generation = 1
            v.reseed = first
            if (first || !playing) { v.exposeFromUs = newEdge; v.exposeToUs = newEdge }
            else { v.exposeFromUs = edge; v.exposeToUs = maxOf(edge, newEdge) }
            edge = v.exposeToUs
            ev.evaluate(v, null, ((t - (if (prevT == Long.MIN_VALUE) t else prevT)) / 1e6f), out)
            prevT = t
            return out
        }
    }
}
