package com.tropicalstream.hammerklavier.render

import com.tropicalstream.hammerklavier.contract.EnergyRing
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentLook
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.QualityLadder
import com.tropicalstream.hammerklavier.contract.RenderStats
import com.tropicalstream.hammerklavier.contract.SyntheticScore
import com.tropicalstream.hammerklavier.contract.SyntheticSpecs
import com.tropicalstream.hammerklavier.contract.UprightFinish
import com.tropicalstream.hammerklavier.contract.ViewId
import com.tropicalstream.hammerklavier.contract.stub.FakeClock
import com.tropicalstream.hammerklavier.contract.stub.PerfFixtures
import com.tropicalstream.hammerklavier.contract.stub.StubMechanics
import com.tropicalstream.hammerklavier.contract.stub.StubScenes
import com.tropicalstream.hammerklavier.render.gl.GlKit
import com.tropicalstream.hammerklavier.testutil.AllocProbe
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The renderer's frame logic run headless on the JVM (android.jar's GL stubs are no-ops): the
 * desired-state reconciliation, zero allocation per frame after warm-up (§2.1 rule 1), the draw
 * budget, the dip on a view change, the instant swap after a display rest (T-Q3SWITCH's logic) and
 * the context-loss path (T-GLRESET's logic). The device runs the real thing (§7.2 WP6).
 */
class StereoRendererFrameTest {
    private lateinit var r: StereoRenderer
    private val clock = FakeClock()

    @Before fun setUp() {
        GlKit.skipStatusChecks = true
        r = StereoRenderer(null)
        r.onSurfaceCreated(null, null)
        r.onSurfaceChanged(null, 1280, 480)
        val d = r.desired
        d.clock = clock; d.energy = EnergyRing(); d.mech = StubMechanics(); d.scenes = StubScenes()
        d.instrument = InstrumentId.GRAND; d.look = InstrumentLook(UprightFinish.WALNUT, false); d.lastDamper = 88
        d.instrumentSerial = 1
        r.kickBuild()
        val prof = InstrumentProfile.of(InstrumentId.GRAND)
        val perf = PerfFixtures.build(SyntheticSpecs.notes(SyntheticScore.SCALE), profile = prof, generation = 1)
        r.setPerformance(perf, prof)
        clock.setPerformance(1, 0L, perf.durationUs); clock.play()
    }

    @After fun tearDown() { GlKit.skipStatusChecks = false }

    private fun frames(n: Int, sleepMs: Long = 0) { repeat(n) { r.onDrawFrame(null); if (sleepMs > 0) Thread.sleep(sleepMs) } }

    @Test fun drawsTheSceneWithinBudget() {
        frames(5)
        assertEquals(null, r.lastBuildError)
        assertEquals(InstrumentId.GRAND, r.currentInstrument)
        assertTrue("draws ${r.drawsPerEye}", r.drawsPerEye in 3..28)
        assertTrue(r.trisPerEye > 0)
        val st = RenderStats(); r.stats(st)
        assertEquals(r.drawsPerEye, st.draws)
    }

    /**
     * A per-frame allocation shows in every window; HotSpot's own one-off JIT/OSR transients (seen
     * even in a pure spin loop) do not, so the least of three windows must be zero.
     */
    @Test fun frameAllocatesNothingAfterWarmUp() {
        frames(3000)
        val w = LongArray(3) { AllocProbe.measure { frames(300) } }
        assertEquals("bytes per 300-frame window: " + w.joinToString(), 0L, w.min())
    }

    @Test fun viewChangeDipsAndCuts() {
        frames(3)
        val d = r.desired
        d.view = ViewId.HALL; d.framing = 0; d.viewSerial = d.viewSerial + 1
        var sawDip = false
        val t0 = System.nanoTime()
        while (System.nanoTime() - t0 < 1_500_000_000L) {
            r.onDrawFrame(null); Thread.sleep(10)
            if (r.dipping) sawDip = true
            if (sawDip && !r.dipping) break
        }
        assertTrue(sawDip); assertFalse(r.dipping)
        assertEquals(ViewId.HALL, r.shownView)
    }

    @Test fun instrumentSwitchDuringRestAppearsOnFirstFrameAfter() {
        frames(3)
        val d = r.desired
        d.quality = QualityLadder.of(3, 96)                        // display rest
        frames(1)
        d.instrument = InstrumentId.HARPSICHORD; d.instrumentSerial = 2; r.kickBuild()
        frames(1)                                                  // (the host stops pacing after one black frame)
        d.quality = QualityLadder.of(0, 96); d.wokeSerial = d.wokeSerial + 1
        frames(1)
        assertEquals(InstrumentId.HARPSICHORD, r.currentInstrument)
        assertFalse("no dip after a display rest", r.dipping)
    }

    @Test fun instrumentSwitchWhileAwakeWaitsForTheCut() {
        frames(3)
        val d = r.desired
        d.instrument = InstrumentId.UPRIGHT; d.instrumentSerial = 2; r.kickBuild()
        frames(1)
        assertEquals(InstrumentId.GRAND, r.currentInstrument)      // still dipping out
        assertTrue(r.dipping)
        val t0 = System.nanoTime()
        while (r.currentInstrument != InstrumentId.UPRIGHT && System.nanoTime() - t0 < 1_000_000_000L) { frames(1, 10) }
        assertEquals(InstrumentId.UPRIGHT, r.currentInstrument)
    }

    @Test fun contextLossRecovers() {
        frames(3)
        val before = r.drawsPerEye
        r.onSurfaceCreated(null, null)                             // a new context
        assertEquals(1, r.glGeneration)
        frames(2)
        assertEquals(before, r.drawsPerEye)
        assertNotNull(r.currentInstrument)
    }

    private fun switchDuringRest(id: InstrumentId) {
        val d = r.desired
        d.quality = QualityLadder.of(3, 96); frames(1)
        d.instrument = id; d.instrumentSerial = d.instrumentSerial + 1; r.kickBuild()
        frames(1)
        d.quality = QualityLadder.of(0, 96); d.wokeSerial = d.wokeSerial + 1
        frames(2)
        assertEquals(id, r.currentInstrument)
    }

    private fun live() = GlKit.liveBuffers + GlKit.liveTextures

    @Test fun sceneSwitchesDoNotLeakGlHandles() {
        frames(3)
        switchDuringRest(InstrumentId.HARPSICHORD)
        val harpsi = live()
        switchDuringRest(InstrumentId.GRAND)
        val grand = live()
        assertTrue("handles uploaded", grand > 0 && harpsi > 0)
        repeat(3) {
            switchDuringRest(InstrumentId.HARPSICHORD); assertEquals(harpsi, live())
            switchDuringRest(InstrumentId.GRAND); assertEquals(grand, live())
        }
    }

    @Test fun uniform4fvWithinBudgetInEveryFraming() {
        val d = r.desired
        for (v in ViewId.entries) for (fr in 0..1) {
            d.view = v; d.framing = fr; d.viewSerial = d.viewSerial + 1
            d.quality = QualityLadder.of(3, 96); frames(1)                 // rest → the next wake cuts at once
            d.quality = QualityLadder.of(0, 96); d.wokeSerial = d.wokeSerial + 1
            frames(3)
            assertEquals(v, r.shownView)
            assertTrue("$v/$fr glUniform4fv=${r.uniform4fvPerFrame}", r.uniform4fvPerFrame in 1..20)
        }
    }
}
