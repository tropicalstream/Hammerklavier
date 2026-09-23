package com.tropicalstream.hammerklavier.midi

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.midi.MidiTestUtil.ok
import com.tropicalstream.hammerklavier.midi.MidiTestUtil.perf
import com.tropicalstream.hammerklavier.midi.SmfWriter.Track
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** T1.2: the tempo map and bars. */
class TempoMapTest {
    private val pre = HK.PRE_ROLL_US

    @Test fun ppq480At120BpmPutsTick960AtOneSecond() {
        val bytes = SmfWriter.file(0, 480, Track().tempo(0, 500_000).on(960, 0, 60, 80).off(480, 0, 60).end().build())
        val tm = TempoMap.of(ok(bytes))
        assertEquals(1_000_000L, tm.tickToUs(960))
        assertEquals(pre + 1_000_000L, perf(bytes).onUs[0])
    }

    @Test fun tempoChangeAtTick480() {
        val bytes = SmfWriter.file(0, 480, Track().tempo(0, 500_000).tempo(480, 1_000_000).on(480, 0, 60, 80).off(480, 0, 60).end().build())
        val tm = TempoMap.of(ok(bytes))
        assertEquals(500_000L, tm.tickToUs(480))
        assertEquals(1_500_000L, tm.tickToUs(960))
        assertEquals(2_500_000L, tm.tickToUs(1440))
        assertEquals(960L, tm.usToTick(1_500_000))
    }

    @Test fun tempoZeroIgnoredAndNoDriftOverLongFiles() {
        val bytes = SmfWriter.file(0, 96, Track().tempo(0, 0).tempo(0, 333_333).on(96 * 10_000L, 0, 60, 80).off(96, 0, 60).end().build())
        val tm = TempoMap.of(ok(bytes))
        assertEquals(Math.round(10_000 * 333_333.0), tm.tickToUs(96 * 10_000L))
    }

    @Test fun barStartsIn3over4() {
        val t = Track().timeSig(0, 3, 2)
        for (i in 0 until 12) t.on(if (i == 0) 0 else 0, 0, 60, 80).off(480, 0, 60)
        val bytes = SmfWriter.file(0, 480, t.end().build())
        val tm = TempoMap.of(ok(bytes))
        val ticks = tm.barTicks(1440L * 3)
        assertArrayEquals(longArrayOf(0, 1440, 2880, 4320), ticks)
        val p = perf(bytes)
        assertEquals(pre, p.barUs[0]); assertEquals(pre + 1_500_000, p.barUs[1]); assertEquals(pre + 3_000_000, p.barUs[2])
        assertEquals(3, p.barAt(pre + 3_100_000))
    }

    @Test fun timeSignatureChangeStartsNewGrid() {
        val bytes = SmfWriter.file(0, 480, Track().timeSig(0, 4, 2).timeSig(1920, 3, 3).on(0, 0, 60, 80).off(1920, 0, 60).end().build())
        val tm = TempoMap.of(ok(bytes))
        assertArrayEquals(longArrayOf(0, 1920, 2640, 3360), tm.barTicks(3360))   // 3/8 = 720 ticks
    }
}
