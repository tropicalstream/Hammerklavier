package com.tropicalstream.hammerklavier.dsp

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.OutputRoute
import com.tropicalstream.hammerklavier.contract.SoftKind
import com.tropicalstream.hammerklavier.contract.SpeakerBass
import com.tropicalstream.hammerklavier.dsp.DspTestUtil.db
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** T3.4: master bus, limiter, speaker voicing, soft bus. */
class MasterChainTest {
    private val fs = HK.SR
    private val B = HK.BLOCK

    private fun runMaster(m: MasterChain, l: FloatArray, r: FloatArray): FloatArray {
        val out = FloatArray(2 * l.size); val bo = FloatArray(2 * B); val bl = FloatArray(B); val br = FloatArray(B)
        for (b in 0 until l.size / B) {
            System.arraycopy(l, b * B, bl, 0, B); System.arraycopy(r, b * B, br, 0, B)
            m.process(bl, br, B, bo); System.arraycopy(bo, 0, out, 2 * b * B, 2 * B)
        }
        return out
    }

    @Test fun plusSixDbfsSineIsHeldBelowMinusOne() {
        for (route in OutputRoute.entries) {
            val m = MasterChain(); m.setRoute(route); m.setGain(1f); m.reset()
            val s = DspTestUtil.sine(fs, 1000.0, 2.0)
            val out = runMaster(m, s, s)
            val pk = DspTestUtil.peakAbs(out)
            assertTrue("$route peak ${db(pk)}", db(pk) <= -1.0)
        }
    }

    @Test fun plusTwelveDbfsImpulseCaughtWithoutOvershoot() {
        val lim = Limiter()
        val n = 4096
        val l = FloatArray(n); val r = FloatArray(n); l[1000] = 3.981f; r[1000] = -3.981f
        lim.process(l, r, n)
        val pk = maxOf(DspTestUtil.peakAbs(l), DspTestUtil.peakAbs(r))
        assertTrue("peak $pk", pk <= lim.ceiling + 1e-6)
        assertEquals(lim.ceiling.toDouble(), pk, 1e-3)                  // the impulse comes out at the ceiling
        assertEquals(1000 + Limiter.LOOKAHEAD, (0 until n).maxByOrNull { kotlin.math.abs(l[it]) })
        // Random bursts, never above the ceiling.
        val lim2 = Limiter(); val x = DspTestUtil.noise(fs, 1.5, 3); val y = DspTestUtil.noise(fs, 1.5, 4)
        lim2.process(x, y, fs)
        assertTrue(maxOf(DspTestUtil.peakAbs(x), DspTestUtil.peakAbs(y)) <= lim2.ceiling + 1e-6)
    }

    @Test fun limiterReleasesIn120ms() {
        val lim = Limiter(); val n = fs
        val l = FloatArray(n) { if (it < 4800) 2f * sin(2 * PI * 1000 * it / fs).toFloat() else 0.1f * sin(2 * PI * 1000 * it / fs).toFloat() }
        val r = l.copyOf()
        lim.process(l, r, n)
        // 120 ms after the loud part the gain has recovered to within 1/e of unity.
        val t = 4800 + Limiter.LOOKAHEAD + (0.120 * fs).toInt()
        val g = DspTestUtil.peakAbs(l, t, t + 48) / 0.1
        assertTrue("g $g", g > 1 - (1 - 0.445) / Math.E - 0.05 && g < 1.0 + 1e-3)
    }

    /** Textbook RBJ magnitude in double precision (independent of BiquadCoefs). */
    private fun rbjDb(type: String, f0: Double, q: Double, gainDb: Double, f: Double): Double {
        val w0 = 2 * PI * f0 / fs; val a = Math.pow(10.0, gainDb / 40); val al = sin(w0) / (2 * q); val c = cos(w0)
        val (b, aa) = when (type) {
            "hp" -> doubleArrayOf((1 + c) / 2, -(1 + c), (1 + c) / 2) to doubleArrayOf(1 + al, -2 * c, 1 - al)
            "peak" -> doubleArrayOf(1 + al * a, -2 * c, 1 - al * a) to doubleArrayOf(1 + al / a, -2 * c, 1 - al / a)
            else -> { // high shelf, S = 1
                val alS = sin(w0) / 2 * sqrt((a + 1 / a) * (1 / 1.0 - 1) + 2); val sa = 2 * sqrt(a) * alS
                doubleArrayOf(a * ((a + 1) + (a - 1) * c + sa), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - sa)) to
                    doubleArrayOf((a + 1) - (a - 1) * c + sa, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - sa)
            }
        }
        val w = 2 * PI * f / fs
        fun mag(k: DoubleArray): Double { val re = k[0] + k[1] * cos(w) + k[2] * cos(2 * w); val im = -(k[1] * sin(w) + k[2] * sin(2 * w)); return sqrt(re * re + im * im) }
        return 20 * Math.log10(mag(b) / mag(aa))
    }

    /** Steady-state gain of a biquad on a sine, measured. */
    private fun measuredDb(c: BiquadCoefs, f: Double): Double {
        val n = fs
        val x = DspTestUtil.sine(n, f, 0.5)
        val y = DspTestUtil.filter(x, c)
        return db(DspTestUtil.rms(y, n / 2, n) / DspTestUtil.rms(x, n / 2, n))
    }

    @Test fun biquadsMatchTheRbjReference() {
        val e = SpeakerEnhancer()
        for (f in doubleArrayOf(80.0, 250.0, 3000.0)) {
            assertEquals(rbjDb("hp", 110.0, 0.70710678, 0.0, f), measuredDb(e.hp110, f), 0.1)
            assertEquals(rbjDb("peak", 250.0, 0.9, 3.0, f), measuredDb(e.peak250, f), 0.1)
        }
        for (f in doubleArrayOf(1000.0, 7000.0, 15000.0)) assertEquals(rbjDb("hs", 7000.0, 0.0, -1.5, f), measuredDb(e.shelf7k, f), 0.1)
    }

    @Test fun speakerHighPassIsMinus3At110Hz() {
        val c = SpeakerEnhancer().hp110
        var f = 60.0
        while (c.magnitudeDb(f, fs.toDouble()) < -3.0) f += 0.1
        assertTrue("-3 dB at $f Hz", f in 99.0..121.0)
    }

    @Test fun virtualBassOffIsABitExactBypass() {
        val e = SpeakerEnhancer(); e.setRoute(OutputRoute.SPEAKER); e.setSpeakerBass(SpeakerBass.OFF)
        val l = DspTestUtil.noise(fs, 0.2, 1); val r = DspTestUtil.noise(fs, 0.2, 2)
        val refL = DspTestUtil.filter(l, e.hp110, e.peak250, e.shelf7k); val refR = DspTestUtil.filter(r, e.hp110, e.peak250, e.shelf7k)
        e.process(l, r, fs)
        for (i in 0 until fs) { assertEquals(refL[i], l[i], 0f); assertEquals(refR[i], r[i], 0f) }
        // AUTO on the speaker route adds bass harmonics; on WIRED it is off (only the 20 Hz high-pass).
        val a = SpeakerEnhancer(); a.setRoute(OutputRoute.SPEAKER); a.setSpeakerBass(SpeakerBass.AUTO)
        assertTrue(a.virtualBassOn)
        a.setRoute(OutputRoute.WIRED); assertTrue(!a.virtualBassOn)
        a.setSpeakerBass(SpeakerBass.ON); assertTrue(a.virtualBassOn)
    }

    @Test fun virtualBassMakesHarmonicsOfA60HzTone() {
        val e = SpeakerEnhancer(); e.setSpeakerBass(SpeakerBass.ON)
        val l = DspTestUtil.sine(fs, 60.0, 0.5); val r = l.copyOf()
        e.process(l, r, fs)
        val h2 = DspTestUtil.dftMag(l, 120.0, fs / 2, fs); val h4 = DspTestUtil.dftMag(l, 240.0, fs / 2, fs)
        val off = SpeakerEnhancer(); off.setSpeakerBass(SpeakerBass.OFF)
        val l2 = DspTestUtil.sine(fs, 60.0, 0.5); val r2 = l2.copyOf(); off.process(l2, r2, fs)
        assertTrue(h2 + h4 > 10 * (DspTestUtil.dftMag(l2, 120.0, fs / 2, fs) + DspTestUtil.dftMag(l2, 240.0, fs / 2, fs) + 1e-9))
    }

    @Test fun softBusAppliesItsShelf() {
        val s = SoftBus(); s.configure(SoftKind.UNA_CORDA)
        fun gainAt(f: Double): Double {
            val x = DspTestUtil.sine(fs, f, 0.3); val ol = FloatArray(fs); val or = FloatArray(fs)
            for (b in 0 until fs / B) {
                val bl = x.copyOfRange(b * B, (b + 1) * B); val o1 = FloatArray(B); val o2 = FloatArray(B)
                s.process(bl, bl, o1, o2, B); System.arraycopy(o1, 0, ol, b * B, B)
            }
            return db(DspTestUtil.rms(ol, fs / 2, fs) / DspTestUtil.rms(x, fs / 2, fs))
        }
        assertEquals(-2.5, gainAt(200.0), 0.2)
        assertEquals(-6.5, gainAt(15000.0), 0.3)
        s.configure(SoftKind.NONE)
        val a = floatArrayOf(0.5f); val o = floatArrayOf(0.25f); s.process(a, a, o, o, 1)
        assertEquals(1.25f, o[0], 0f)      // NONE adds (the same array given twice gets both channels)
    }

    @Test fun masterGainRampsAndClips() {
        val m = MasterChain(); m.setRoute(OutputRoute.WIRED); m.setGain(0.5f); m.reset()
        val x = FloatArray(B) { 0.2f }; val out = FloatArray(2 * B)
        repeat(4) { m.process(x, x, B, out) }
        m.setGain(0.25f)
        m.process(x, x, B, out)
        // No step: consecutive samples differ by far less than the gain change.
        for (i in 2 until 2 * B step 2) assertTrue(kotlin.math.abs(out[i] - out[i - 2]) < 0.002f)
        assertEquals(1f, DspTables.softClip(5f)); assertEquals(-1f, DspTables.softClip(-3f))
    }
}
