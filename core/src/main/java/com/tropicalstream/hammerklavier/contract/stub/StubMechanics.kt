package com.tropicalstream.hammerklavier.contract.stub

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.MechanicsEvaluator
import com.tropicalstream.hammerklavier.contract.MechanismPose
import com.tropicalstream.hammerklavier.contract.Performance
import com.tropicalstream.hammerklavier.contract.VisTime

/**
 * A travelling wave across the keys (PLAN §2.3): a triangular bump 6 keys wide sweeps from the
 * lowest to the highest key once every 2 s of song time (or of real time while paused, from dt),
 * driving keyDip, hammer, damper and jacks. Allocation-free, no transcendental maths.
 */
class StubMechanics : MechanicsEvaluator {
    private var low = 21
    private var high = 108
    private var idleSec = 0f

    override fun bind(perf: Performance?, profile: InstrumentProfile) {
        low = profile.lowKey; high = profile.highKey; idleSec = 0f
    }

    override fun evaluate(v: VisTime, energy: FloatArray?, dtSec: Float, out: MechanismPose) {
        if (!v.playing) idleSec += dtSec
        val t = if (v.playing) v.tUs / 1e6f else idleSec
        val span = (high - low + 1).toFloat()
        val phase = (t / 2f) - (t / 2f).toInt()                 // 0..1 every 2 s
        val centre = low + phase * span
        for (k in 0 until HK.KEYS) {
            val d = if (k < low || k > high) 0f else (1f - kotlin.math.abs(k - centre) / 3f).coerceIn(0f, 1f)
            out.keyDip[k] = d; out.hammer[k] = d; out.jack4[k] = d; out.escape[k] = 0f
            out.damper[k] = d; out.tongue[k] = 0f; out.tongue4[k] = 0f
            val lane = k - 21
            out.stringAmp[k] = if (energy != null && lane in 0 until HK.LANES) (energy[lane] * 3.2f).coerceIn(0f, 1f) else d * 0.5f
            out.strikeAge[k] = if (d > 0.99f) 0f else 1e9f
            out.flash[k] = false
        }
        out.sustain = 0f; out.soft = 0f; out.sostenuto = 0f; out.shiftMm = 0f; out.hammerRailMm = 0f
        out.registers = v.registration; out.focusKey = centre; out.centroidKey = centre
        out.songUs = v.tUs; out.playing = v.playing
    }
}
