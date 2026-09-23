package com.tropicalstream.hammerklavier.contract

import com.tropicalstream.hammerklavier.contract.stub.StubScoreCompiler
import org.junit.Assert.assertEquals
import org.junit.Test

class PerformanceHelpersTest {
    @Test fun searches() {
        val p = StubScoreCompiler().synthetic(SyntheticScore.SCALE, InstrumentProfile.GRAND, 1)
        assertEquals(0, p.eventIndexAtOrAfter(0))
        assertEquals(p.ev.size, p.eventIndexAtOrAfter(Long.MAX_VALUE))
        val i = p.eventIndexAtOrAfter(p.onUs[10])
        assertEquals(p.onUs[10], p.evUs[i]); assertEquals(true, i == 0 || p.evUs[i - 1] < p.onUs[10])
        assertEquals(1, p.barAt(0)); assertEquals(1, p.barAt(HK.PRE_ROLL_US)); assertEquals(2, p.barAt(HK.PRE_ROLL_US + 2_000_000))
        assertEquals(-1, p.latchIndexAt(Long.MAX_VALUE))
        assertEquals(5, Performance.type(Performance.pack(Performance.EV_NOTE_ON, 123_456)))
        assertEquals(123_456, Performance.arg(Performance.pack(Performance.EV_NOTE_ON, 123_456)))
        assertEquals(15, Performance.type(Performance.pack(Performance.EV_END, 0)))
    }
}
