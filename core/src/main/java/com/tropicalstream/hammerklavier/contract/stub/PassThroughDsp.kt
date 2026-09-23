package com.tropicalstream.hammerklavier.contract.stub

import com.tropicalstream.hammerklavier.contract.BankInfo
import com.tropicalstream.hammerklavier.contract.DspSet
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.KeyMap
import com.tropicalstream.hammerklavier.contract.MasterProcessor
import com.tropicalstream.hammerklavier.contract.OutputRoute
import com.tropicalstream.hammerklavier.contract.ResonanceMode
import com.tropicalstream.hammerklavier.contract.ResonanceProcessor
import com.tropicalstream.hammerklavier.contract.RoomDesign
import com.tropicalstream.hammerklavier.contract.RoomProcessor
import com.tropicalstream.hammerklavier.contract.SoftBusProcessor
import com.tropicalstream.hammerklavier.contract.SoftKind
import com.tropicalstream.hammerklavier.contract.SpeakerBass

/**
 * The DSP until WP3 merges (PLAN §2.3): resonance adds nothing, the room copies dry to out, the
 * soft bus adds, the master applies its gain and the Padé soft clip x(27 + x²)/(27 + 9x²) (|x| ≤ 3,
 * ±1 beyond). Allocation-free.
 */
object PassThroughDsp {
    fun create(sampleRate: Int = HK.SR): DspSet = DspSet(resonance = Resonance(), room = Room(), soft = Soft(), master = Master())

    class Resonance : ResonanceProcessor {
        override fun prepare(keyMap: KeyMap, info: BankInfo, profile: InstrumentProfile): Any = Unit
        override fun apply(prepared: Any, glideMs: Int) {}
        override fun setMode(mode: ResonanceMode, instrument: InstrumentId, maxActive: Int, dispersion: Boolean) {}
        override fun process(mix: FloatArray, self: FloatArray, selfRows: BooleanArray, gate: FloatArray,
                             softFeed: BooleanArray, damping: FloatArray, outL: FloatArray, outR: FloatArray, n: Int) {}
        override fun energy(outMeanSquare: FloatArray) {}
        override val active: Int get() = 0
        override fun reset() {}
    }

    class Room : RoomProcessor {
        override fun setDesign(d: RoomDesign, glideMs: Int) {}
        override fun setLines(n: Int) {}
        override fun setInputGain(g: Float, rampMs: Float) {}
        override fun process(inL: FloatArray, inR: FloatArray, outL: FloatArray, outR: FloatArray, n: Int, headYawRad: Float) {
            System.arraycopy(inL, 0, outL, 0, n); System.arraycopy(inR, 0, outR, 0, n)
        }
        override val tailActive: Boolean get() = false
        override fun reset() {}
    }

    class Soft : SoftBusProcessor {
        override fun configure(kind: SoftKind) {}
        override fun process(inL: FloatArray, inR: FloatArray, outL: FloatArray, outR: FloatArray, n: Int) {
            for (i in 0 until n) { outL[i] += inL[i]; outR[i] += inR[i] }
        }
        override fun reset() {}
    }

    class Master : MasterProcessor {
        @Volatile private var gain = 1f
        override fun setRoute(r: OutputRoute) {}
        override fun setSpeakerBass(m: SpeakerBass) {}
        override fun setGain(linear: Float) { gain = linear }
        override fun process(l: FloatArray, r: FloatArray, n: Int, outInterleaved: FloatArray) {
            val g = gain
            for (i in 0 until n) { outInterleaved[2 * i] = pade(l[i] * g); outInterleaved[2 * i + 1] = pade(r[i] * g) }
        }
        override fun reset() {}
    }

    /** Padé approximant of tanh, exact ±1 at |x| = 3 and clamped beyond. */
    fun pade(x: Float): Float {
        if (x >= 3f) return 1f
        if (x <= -3f) return -1f
        val x2 = x * x
        return x * (27f + x2) / (27f + 9f * x2)
    }
}
