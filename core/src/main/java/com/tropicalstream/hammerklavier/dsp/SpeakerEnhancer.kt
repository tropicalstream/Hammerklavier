package com.tropicalstream.hammerklavier.dsp

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.OutputRoute
import com.tropicalstream.hammerklavier.contract.SpeakerBass
import kotlin.math.pow

/**
 * Route voicing (PLAN §3.13), in place on a stereo block.
 *
 * SPEAKER: 2nd-order Butterworth high-pass 110 Hz; virtual bass (mono sum of the input → LP
 * 150 Hz → full-wave rectify → DC block → HP 150 Hz + LP 450 Hz → −6 dB, added to both channels);
 * +3 dB peaking at 250 Hz (Q 0.9); −1.5 dB high shelf at 7 kHz. WIRED / BLUETOOTH: a 1st-order
 * high-pass at 20 Hz only. Virtual bass: AUTO = speaker route only, ON = always, OFF = never; when
 * it is off its path is skipped entirely (bit-exact bypass). Coefficients are designed at
 * construction; the setters only select. Allocation-free.
 */
class SpeakerEnhancer(sampleRate: Int = HK.SR) {
    private val fs = sampleRate.toDouble()
    @JvmField val hp110 = BiquadCoefs.highPass(110.0, BiquadCoefs.BUTTERWORTH_Q, fs)
    @JvmField val peak250 = BiquadCoefs.peaking(250.0, 0.9, 3.0, fs)
    @JvmField val shelf7k = BiquadCoefs.highShelf(7000.0, -1.5, fs)
    @JvmField val hp20 = BiquadCoefs.highPass1(20.0, fs)
    private val vbLp150 = BiquadCoefs.lowPass(150.0, BiquadCoefs.BUTTERWORTH_Q, fs)
    private val vbHp150 = BiquadCoefs.highPass(150.0, BiquadCoefs.BUTTERWORTH_Q, fs)
    private val vbLp450 = BiquadCoefs.lowPass(450.0, BiquadCoefs.BUTTERWORTH_Q, fs)
    private val vbGain = 10.0.pow(-6.0 / 20).toFloat()

    private val hpL = Biquad(hp110); private val hpR = Biquad(hp110)
    private val pkL = Biquad(peak250); private val pkR = Biquad(peak250)
    private val shL = Biquad(shelf7k); private val shR = Biquad(shelf7k)
    private val w20L = Biquad(hp20); private val w20R = Biquad(hp20)
    private val vb1 = Biquad(vbLp150); private val vb2 = Biquad(vbHp150); private val vb3 = Biquad(vbLp450)
    private val dc = DcBlocker(0.995f)
    private val mono = FloatArray(HK.BLOCK)

    var route: OutputRoute = OutputRoute.SPEAKER
        private set
    var bass: SpeakerBass = SpeakerBass.AUTO
        private set

    val virtualBassOn: Boolean get() = when (bass) {
        SpeakerBass.ON -> true
        SpeakerBass.OFF -> false
        SpeakerBass.AUTO -> route == OutputRoute.SPEAKER
    }

    fun setRoute(r: OutputRoute) { if (r != route) { route = r; reset() } }
    fun setSpeakerBass(m: SpeakerBass) { if (m != bass) { bass = m; resetBass() } }

    fun reset() {
        hpL.reset(); hpR.reset(); pkL.reset(); pkR.reset(); shL.reset(); shR.reset(); w20L.reset(); w20R.reset()
        resetBass()
    }

    private fun resetBass() { vb1.reset(); vb2.reset(); vb3.reset(); dc.reset() }

    /** In place; n ≤ any length (processed in BLOCK chunks). */
    fun process(l: FloatArray, r: FloatArray, n: Int) {
        var off = 0
        while (off < n) { val m = minOf(HK.BLOCK, n - off); chunk(l, r, off, m); off += m }
    }

    private fun chunk(l: FloatArray, r: FloatArray, off: Int, m: Int) {
        val vb = virtualBassOn
        if (vb) {
            for (i in 0 until m) mono[i] = 0.5f * (l[off + i] + r[off + i])
            for (i in 0 until m) {
                var v = vb1.tick(mono[i])
                if (v < 0f) v = -v
                v = dc.tick(v)
                v = vb3.tick(vb2.tick(v))
                mono[i] = v * vbGain
            }
        }
        if (route == OutputRoute.SPEAKER) {
            for (i in 0 until m) {
                var a = hpL.tick(l[off + i]); var b = hpR.tick(r[off + i])
                if (vb) { a += mono[i]; b += mono[i] }
                l[off + i] = shL.tick(pkL.tick(a)); r[off + i] = shR.tick(pkR.tick(b))
            }
        } else {
            for (i in 0 until m) {
                var a = w20L.tick(l[off + i]); var b = w20R.tick(r[off + i])
                if (vb) { a += mono[i]; b += mono[i] }
                l[off + i] = a; r[off + i] = b
            }
        }
    }
}
