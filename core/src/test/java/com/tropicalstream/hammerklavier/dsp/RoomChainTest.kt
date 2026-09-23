package com.tropicalstream.hammerklavier.dsp

import com.tropicalstream.hammerklavier.contract.Conventions
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.KonzertzimmerAcoustics
import com.tropicalstream.hammerklavier.contract.ListenerPose
import com.tropicalstream.hammerklavier.contract.ReverbMode
import com.tropicalstream.hammerklavier.contract.RoomDesign
import com.tropicalstream.hammerklavier.contract.stub.FixedRoom
import com.tropicalstream.hammerklavier.dsp.DspTestUtil.db
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** T3.3: FDN and early reflections. */
class RoomChainTest {
    private val fs = HK.SR
    private val B = HK.BLOCK

    private fun fdnIr(low: Float, mid: Float, high: Float, seconds: Double, lines: Int = 8): FloatArray {
        val f = FdnReverb(); f.setT60(low, mid, high); f.setLines(lines)
        val n = (seconds * fs).toInt() / B * B
        val out = FloatArray(n)
        val inp = FloatArray(B); val g = FloatArray(B) { 1f }; val l = FloatArray(B); val r = FloatArray(B)
        for (b in 0 until n / B) {
            java.util.Arrays.fill(inp, 0f); if (b == 0) inp[0] = 1f
            java.util.Arrays.fill(l, 0f); java.util.Arrays.fill(r, 0f)
            f.process(inp, g, l, r, B)
            for (i in 0 until B) out[b * B + i] = l[i] + r[i]
        }
        return out
    }

    @Test fun schroederT60MatchesTheDesign() {
        val d = FixedRoom.PLAYER
        val ir = fdnIr(d.t60Low, d.t60Mid, d.t60High, 4.0)
        val mid = DspTestUtil.filter(ir, BiquadCoefs.highPass(500.0, 0.707, 48000.0), BiquadCoefs.highPass(500.0, 0.707, 48000.0),
            BiquadCoefs.lowPass(2000.0, 0.707, 48000.0), BiquadCoefs.lowPass(2000.0, 0.707, 48000.0))
        val hi = DspTestUtil.filter(ir, BiquadCoefs.bandPass(8000.0, 4.3, 48000.0), BiquadCoefs.bandPass(8000.0, 4.3, 48000.0))
        val tm = DspTestUtil.schroederT60(mid); val th = DspTestUtil.schroederT60(hi)
        println("T3.3 Schroeder: mid %.3f s (design %.3f), 8k %.3f s (design %.3f)".format(tm, d.t60Mid, th, d.t60High))
        assertEquals(d.t60Mid.toDouble(), tm, 0.10 * d.t60Mid)
        assertEquals(d.t60High.toDouble(), th, 0.15 * d.t60High)
    }

    @Test fun stableUnderSixtySecondsOfFullScaleNoise() {
        val room = RoomChain(); room.setDesign(FixedRoom.PLAYER, 0)
        val rnd = java.util.Random(7)
        val l = FloatArray(B); val r = FloatArray(B); val ol = FloatArray(B); val or = FloatArray(B)
        var peak = 0.0
        repeat(60 * fs / B) {
            for (i in 0 until B) { l[i] = rnd.nextFloat() * 2 - 1; r[i] = rnd.nextFloat() * 2 - 1 }
            room.process(l, r, ol, or, B, 0f)
            for (i in 0 until B) { assertTrue(ol[i].isFinite() && or[i].isFinite()); peak = maxOf(peak, kotlin.math.abs(ol[i].toDouble()), kotlin.math.abs(or[i].toDouble())) }
        }
        assertTrue("peak $peak", peak < 20.0)
        assertTrue(room.tailActive)
    }

    @Test fun noModalPeaks() {
        val f = FdnReverb(); val d = FixedRoom.PLAYER; f.setT60(d.t60Low, d.t60Mid, d.t60High)
        val segs = 16; val seg = fs; val nfft = 65536
        val inp = DspTestUtil.noise((segs + 2) * seg, 0.1, 11)
        val out = FloatArray(inp.size)
        val g = FloatArray(B) { 1f }; val l = FloatArray(B); val r = FloatArray(B); val x = FloatArray(B)
        for (b in 0 until inp.size / B) {
            System.arraycopy(inp, b * B, x, 0, B); java.util.Arrays.fill(l, 0f); java.util.Arrays.fill(r, 0f)
            f.process(x, g, l, r, B); System.arraycopy(l, 0, out, b * B, B)
        }
        val pow = DoubleArray(nfft / 2)
        for (s in 0 until segs) {
            val re = DoubleArray(nfft); val im = DoubleArray(nfft)
            for (i in 0 until seg) re[i] = out[(s + 2) * seg + i] * (0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * i / (seg - 1)))
            DspTestUtil.fft(re, im)
            for (k in 0 until nfft / 2) pow[k] += re[k] * re[k] + im[k] * im[k]
        }
        // Deviation (docs/progress/WP3.md): the steady-state response of any 8-line FDN has Rayleigh-
        // distributed bin powers (mode spacing ≈ 3.7 Hz > bandwidth ≈ 1.3 Hz), so raw 1 Hz bins sit
        // ≈ 9–10 dB above their third-octave mean. Metallic ringing shows as peaks that survive a
        // ±4-bin (±3 Hz) smoothing; those must stay within 8 dB of the neighbourhood.
        val raw = pow.copyOf()
        for (k in 4 until pow.size - 4) { var a = 0.0; for (j in -4..4) a += raw[k + j]; pow[k] = a / 9 }
        val hz = fs.toDouble() / nfft
        var worst = -99.0; var at = 0.0
        for (k in (100 / hz).toInt() until (16000 / hz).toInt()) {
            val f0 = k * hz
            val lo = (f0 * Math.pow(2.0, -1.0 / 6) / hz).toInt(); val hi = (f0 * Math.pow(2.0, 1.0 / 6) / hz).toInt()
            var s = 0.0; var n = 0
            for (j in lo..hi) if (j != k) { s += pow[j]; n++ }
            val rel = 10 * kotlin.math.log10(pow[k] / (s / n))
            if (rel > worst) { worst = rel; at = f0 }
        }
        println("T3.3 flatness: worst smoothed bin %.2f dB above its third-octave at %.1f Hz".format(worst, at))
        assertTrue("worst $worst dB at $at Hz", worst <= 8.0)
    }

    @Test fun energyNormalisedWithinOneDb() {
        val res = ArrayList<Double>()
        for (t in floatArrayOf(0.8f, 1.2f, 1.65f, 2.0f, 2.5f)) for (lines in intArrayOf(8, 4)) {
            val f = FdnReverb(); f.setT60(t, t, t); f.setLines(lines)
            val n = 8 * fs
            val inp = DspTestUtil.noise(n, 0.1, 5)
            val g = FloatArray(B) { 1f }; val l = FloatArray(B); val r = FloatArray(B); val x = FloatArray(B)
            var sl = 0.0; var sr = 0.0; var cnt = 0
            for (b in 0 until n / B) {
                System.arraycopy(inp, b * B, x, 0, B); java.util.Arrays.fill(l, 0f); java.util.Arrays.fill(r, 0f)
                f.process(x, g, l, r, B)
                if (b * B >= 4 * fs) for (i in 0 until B) { sl += l[i].toDouble() * l[i]; sr += r[i].toDouble() * r[i]; cnt++ }
            }
            val dl = db(sqrt(sl / cnt) / 0.1); val dr = db(sqrt(sr / cnt) / 0.1)
            println("T3.3 energy T60 $t lines $lines: L %.2f dB R %.2f dB".format(dl, dr))
            res += dl; res += dr
        }
        for (v in res) assertEquals(0.0, v, 1.0)
    }

    private val g = KonzertzimmerAcoustics.GEOMETRY
    private val grand = g.placements.getValue(InstrumentId.GRAND)
    private val src = floatArrayOf(0f, 0.90f, -1.00f)
    private fun roomOf(p: FloatArray): FloatArray { val o = FloatArray(3); grand.toRoom(p, o); return o }
    private fun designFor(earPiano: FloatArray?, earRoom: FloatArray?, width: Float, locked: Boolean): RoomDesign {
        val ear = earRoom ?: roomOf(earPiano!!)
        val s = roomOf(src)
        val fwd = Conventions.yawOf(floatArrayOf(s[0] - ear[0], s[1] - ear[1], s[2] - ear[2]))
        return RoomAcoustics.design(g, grand, src, ListenerPose(ear, fwd, locked, width), ReverbMode.ROOM, 1.579f, 30f)
    }

    /** High-frequency residue (> 12 kHz) of the output of a chain fed low-passed noise. */
    private fun switchResidue(glideMs: Int): Double {
        val room = RoomChain()
        val player = designFor(floatArrayOf(0f, 1.2f, 0.55f), null, 1f, false)
        val action = designFor(floatArrayOf(0.30f, 1.00f, -0.30f), null, 0.8f, false)
        val hall = designFor(null, floatArrayOf(0.4f, 1.2f, 3.0f), 0.4f, true)
        room.setDesign(player, 0)
        val n = 6 * fs
        val lp = BiquadCoefs.lowPass(800.0, 0.707, 48000.0)
        val inp = DspTestUtil.filter(DspTestUtil.noise(n, 0.3, 9), lp, lp, lp, lp)
        val outL = FloatArray(n)
        val x = FloatArray(B); val ol = FloatArray(B); val or = FloatArray(B)
        for (b in 0 until n / B) {
            if (b == 2 * fs / B) room.setDesign(action, glideMs)
            if (b == 4 * fs / B) room.setDesign(hall, glideMs)
            System.arraycopy(inp, b * B, x, 0, B)
            room.process(x, x, ol, or, B, 0f)
            System.arraycopy(ol, 0, outL, b * B, B)
        }
        val hp = BiquadCoefs.highPass(12000.0, 0.707, 48000.0)
        val res = DspTestUtil.filter(outL, hp, hp, hp, hp)
        return DspTestUtil.peakAbs(res, fs, n)
    }

    @Test fun listenerSwitchUnderNoiseHasNoStep() {
        val smooth = switchResidue(500)
        val hard = switchResidue(0)
        println("T3.3 listener switch residue: glide %.1f dBFS, hard cut %.1f dBFS".format(db(smooth), db(hard)))
        assertTrue("glide ${db(smooth)} dBFS", db(smooth) <= -60.0)
        assertTrue("the probe must see a hard cut (${db(hard)} dBFS)", db(hard) > -60.0)
    }

    @Test fun inputGainFadesOnlyTheInputAndTheTailRingsOn() {
        val room = RoomChain(); room.setDesign(FixedRoom.PLAYER, 0)
        val x = DspTestUtil.noise(B, 0.2); val ol = FloatArray(B); val or = FloatArray(B)
        repeat(100) { room.process(x, x, ol, or, B, 0f) }
        room.setInputGain(0f, 60f)
        repeat(12) { room.process(x, x, ol, or, B, 0f) }   // 64 ms
        val z = FloatArray(B)
        room.process(x, x, ol, or, B, 0f)
        assertTrue(room.tailActive)
        assertTrue(DspTestUtil.rms(ol) > 1e-3)             // the tail rings on
        repeat(6 * fs / B) { room.process(x, x, ol, or, B, 0f) }
        assertTrue(!room.tailActive)
        room.process(z, z, ol, or, B, 0f)
    }

    @Test fun worldLockedPanUsesTheTableAndA20msSmoother() {
        val room = RoomChain()
        val hall = designFor(null, floatArrayOf(0.4f, 1.2f, 3.0f), 0.4f, true)
        room.setDesign(hall, 0)
        val x = FloatArray(B) { 0.1f }; val ol = FloatArray(B); val or = FloatArray(B)
        repeat(20) { room.process(x, x, ol, or, B, 0f) }
        assertEquals(0f, room.direct.smoothedYaw, 1e-6f)
        val yaw = 0.8f
        val blocks20ms = Math.round(0.020 * fs / B).toInt()     // ≈ 4 blocks
        repeat(blocks20ms) { room.process(x, x, ol, or, B, yaw) }
        val frac = room.direct.smoothedYaw / yaw
        val want = 1 - Math.exp(-blocks20ms * B / (0.020 * fs))
        assertEquals(want, frac.toDouble(), 0.01)
        repeat(100) { room.process(x, x, ol, or, B, yaw) }
        // Settled: balance = √2·(cos, sin)((SIN[az − yaw] + 1)·π/4).
        val p = DspTables.sinT(hall.sourceAzimuthRad - yaw)
        val phi = (p + 1) * Math.PI / 4
        assertEquals(sqrt(2.0) * kotlin.math.cos(phi), room.direct.balL[B - 1].toDouble(), 1e-4)
        assertEquals(sqrt(2.0) * kotlin.math.sin(phi), room.direct.balR[B - 1].toDouble(), 1e-4)
        // Head turned right → the piano is heard to the left.
        assertTrue(room.direct.balL[B - 1] > room.direct.balR[B - 1])
    }

    /**
     * Rendered direct-to-late power ratio (dB) of a design, from the chain's own components under
     * steady noise: direct = DirectPath output (before the ERs are added), late = FDN output.
     */
    private fun renderedDrr(d: RoomDesign): Double {
        val room = RoomChain(); room.setDesign(d, 0)
        val n = 12 * fs
        // Piano-like band (800 Hz low-passed noise), so the shorter 8 kHz T60 does not bias the late power.
        val lp = BiquadCoefs.lowPass(800.0, 0.707, 48000.0)
        val inp = DspTestUtil.filter(DspTestUtil.noise(n, 0.1, 5), lp, lp, lp, lp)
        val gIn = FloatArray(B) { 1f }; val gRev = FloatArray(B) { d.reverbGain }
        val x = FloatArray(B); val dL = FloatArray(B); val dR = FloatArray(B)
        val mono = FloatArray(B); val monoRev = FloatArray(B); val fin = FloatArray(B)
        val lL = FloatArray(B); val lR = FloatArray(B); val eL = FloatArray(B); val eR = FloatArray(B)
        var pd = 0.0; var pl = 0.0
        for (b in 0 until n / B) {
            System.arraycopy(inp, b * B, x, 0, B)
            room.direct.process(x, x, gIn, dL, dR, mono, monoRev, B, 0f)
            java.util.Arrays.fill(eL, 0f); java.util.Arrays.fill(eR, 0f)
            room.early.process(mono, monoRev, eL, eR, fin, B)
            java.util.Arrays.fill(lL, 0f); java.util.Arrays.fill(lR, 0f)
            room.fdn.process(fin, gRev, lL, lR, B)
            if (b >= 4 * fs / B) for (i in 0 until B) {
                pd += dL[i].toDouble() * dL[i] + dR[i].toDouble() * dR[i]
                pl += lL[i].toDouble() * lL[i] + lR[i].toDouble() * lR[i]
            }
        }
        return 10 * Math.log10(pd / pl)
    }

    @Test fun renderedDrrFollowsTheSeatNotTheDirectGain() {
        val player = designFor(floatArrayOf(0f, 1.2f, 0.55f), null, 1f, false)
        val action = designFor(floatArrayOf(0.30f, 1.00f, -0.30f), null, 0.8f, false)
        val hall = designFor(null, floatArrayOf(0.4f, 1.2f, 3.0f), 1f, false)
        val got = HashMap<String, Double>()
        for ((name, d) in listOf("player" to player, "action" to action, "hall" to hall)) {
            // Geometric DRR r_c/r, with the embedded-room factor (erGain = emb) taken out.
            val want = 20 * Math.log10((d.directGain * d.erGain / d.reverbGain).toDouble())
            val r = renderedDrr(d) + 20 * Math.log10(d.erGain.toDouble())
            got[name] = r
            println("T3.2 rendered DRR $name: %.1f dB (design %.1f dB)".format(r, want))
            assertEquals(name, want, r, 1.0)
        }
        assertEquals(-1.3, got.getValue("player"), 1.0)
        assertEquals(-11.2, got.getValue("hall"), 1.0)
    }

    /** The modulated (allpass-interpolated) lines add no > 12 kHz residue when the integer tap steps. */
    @Test fun modulatedLinesAddNoHighFrequencyResidue() {
        val f = FdnReverb(); f.setT60(2.0f, 2.0f, 2.0f)
        val n = 10 * fs
        val lp = BiquadCoefs.lowPass(800.0, 0.707, 48000.0)
        val inp = DspTestUtil.filter(DspTestUtil.noise(n, 0.3, 11), lp, lp, lp, lp)
        val g = FloatArray(B) { 1f }; val l = FloatArray(B); val r = FloatArray(B); val x = FloatArray(B)
        val out = FloatArray(n)
        for (b in 0 until n / B) {
            System.arraycopy(inp, b * B, x, 0, B); java.util.Arrays.fill(l, 0f); java.util.Arrays.fill(r, 0f)
            f.process(x, g, l, r, B); System.arraycopy(l, 0, out, b * B, B)
        }
        val hp = BiquadCoefs.highPass(12000.0, 0.707, 48000.0)
        val res = DspTestUtil.filter(out, hp, hp, hp, hp)
        val peak = db(DspTestUtil.peakAbs(res, 2 * fs, n))
        println("T3.3 modulated-line > 12 kHz residue: %.1f dBFS".format(peak))
        assertTrue("residue $peak dBFS", peak <= -60.0)
    }
}
