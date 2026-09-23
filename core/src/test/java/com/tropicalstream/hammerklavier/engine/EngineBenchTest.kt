package com.tropicalstream.hammerklavier.engine

import com.tropicalstream.hammerklavier.contract.Cmd
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.HeadPose
import com.tropicalstream.hammerklavier.contract.VoiceCursorBoard
import com.tropicalstream.hammerklavier.contract.stub.PassThroughDsp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T2.12: EngineBench reports ns per voice-frame, comb-frame and stage and derives the §3.14 cap. */
class EngineBenchTest {
    @Test fun capFormula() {
        assertEquals(96, EngineBench.capFor(55.0))                     // ≈ 94.7 → 96
        assertEquals(64, EngineBench.capFor(200.0))                    // clamped low
        assertEquals(128, EngineBench.capFor(10.0))                    // clamped high
        assertEquals(80, EngineBench.capFor(0.25 * 20_833 / 80.0))     // exactly 80
        assertEquals(128, EngineBench.capFor(0.0))
    }

    @Test fun aRunReportsEveryStageAndTheCap() {
        val b = EngineBench()
        val r = b.run(0.45f, PassThroughDsp.create(), cpuMhz = 1500)
        assertTrue(r.nsVoiceHermite > 0.0); assertTrue(r.nsVoiceLinear > 0.0); assertTrue(r.nsVoiceCopy > 0.0)
        assertTrue(r.nsSpectral >= 0.0)
        assertTrue(r.nsComb >= 0.0); assertTrue(r.nsSoft > 0.0); assertTrue(r.nsRoom > 0.0); assertTrue(r.nsMaster > 0.0)
        assertEquals(r.nsVoiceHermite * 0.75, r.nsVoice2, 1e-9)       // normalised to 2.0 GHz
        assertEquals(EngineBench.capFor(r.nsVoice2), r.q0Cap)
        assertTrue(r.q0Cap in HK.VOICE_CAP_MIN..HK.VOICE_CAP_MAX && r.q0Cap % 8 == 0)
        assertEquals(r.normalised(r.nsVoiceHermite * 8), r.savedCapMinus8, 1e-9)
        assertEquals(r.normalised(r.nsComb * 44), r.savedCombs44, 1e-9)
        assertEquals(1, b.completed)
        println("EngineBench (JVM, PassThroughDsp): hermite ${"%.1f".format(r.nsVoiceHermite)} ns, linear ${"%.1f".format(r.nsVoiceLinear)} ns, " +
            "copy ${"%.1f".format(r.nsVoiceCopy)} ns, spectral +${"%.1f".format(r.nsSpectral)} ns per voice-frame; cap ${r.q0Cap}")
    }

    @Test fun benchCommandRunsInSlicesOnTheRenderThread() {
        val core = EngineCore(PassThroughDsp.create(), VoiceCursorBoard(), HeadPose())
        core.benchCpuMhz = 2000
        core.on(Cmd.BENCH, 1L, 0f, null)
        val out = FloatArray(2 * HK.BLOCK)
        var blocks = 0
        var worst = 0L
        while (core.bench.running && blocks < 20_000) {
            val t0 = System.nanoTime(); core.render(out, blocks * 256L); worst = maxOf(worst, System.nanoTime() - t0); blocks++
        }
        assertEquals(1, core.bench.completed)
        assertTrue(core.bench.result.q0Cap >= HK.VOICE_CAP_MIN)
        assertTrue("slices of ≈ 2 ms: worst block ${worst / 1000} µs", worst < 50_000_000L)
        for (i in 0 until 2 * HK.BLOCK) assertEquals(0f, out[i], 0f)        // silent while benchmarking
    }
}
