package com.tropicalstream.hammerklavier.render

import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.SyntheticScore
import com.tropicalstream.hammerklavier.contract.SyntheticSpecs
import com.tropicalstream.hammerklavier.contract.stub.PerfFixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** The two Performance slots keyed by generation (PLAN §2.3 RenderControl.setPerformance, §5.2 step 4). */
class StereoRendererSlotsTest {
    @Test fun previousPieceStaysBoundUntilTheNewOneIsHeard() {
        val r = StereoRenderer(null)
        val prof = InstrumentProfile.of(InstrumentId.GRAND)
        fun perf(g: Int) = PerfFixtures.build(SyntheticSpecs.notes(SyntheticScore.SCALE), profile = prof, generation = g)
        val g = IntArray(2)
        r.setPerformance(perf(1), prof); r.slotGenerations(g)
        assertEquals(setOf(1, Int.MIN_VALUE), g.toSet())
        r.setPerformance(perf(2), prof); r.slotGenerations(g)
        assertEquals(setOf(1, 2), g.toSet())                   // both kept: the clock may still be hearing 1
        r.setPerformance(perf(3), prof); r.slotGenerations(g)
        assertEquals(setOf(2, 3), g.toSet())                   // the oldest is replaced
    }
}
