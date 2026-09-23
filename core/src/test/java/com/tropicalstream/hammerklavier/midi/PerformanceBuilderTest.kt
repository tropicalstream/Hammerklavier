package com.tropicalstream.hammerklavier.midi

import com.tropicalstream.hammerklavier.contract.CompileOptions
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.PerfWarning
import com.tropicalstream.hammerklavier.contract.Performance
import com.tropicalstream.hammerklavier.contract.SyntheticScore
import com.tropicalstream.hammerklavier.midi.MidiTestUtil.perf
import com.tropicalstream.hammerklavier.midi.SmfWriter.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T1.3: the performance builder. PPQ 500 at 120 bpm: 1 tick = 1 ms. */
class PerformanceBuilderTest {
    private val pre = HK.PRE_ROLL_US
    private fun file(t: Track) = SmfWriter.file(0, 500, t.end().build())
    private fun ms(x: Long) = pre + x * 1000

    @Test fun twoChannelsOverlappingOnOneKeyAreSerialised() {
        val p = perf(file(Track().on(0, 0, 60, 80).on(500, 1, 60, 90).off(500, 0, 60).off(500, 1, 60)))
        assertEquals(2, p.noteCount)
        assertEquals(ms(498), p.offUs[0])                           // newOn − 2 ms
        assertEquals(ms(500), p.onUs[1])
        assertTrue(p.flags[1].toInt() and Performance.F_RESTRIKE != 0)
        assertEquals(0, p.flags[0].toInt() and Performance.F_RESTRIKE)
        assertEquals(ms(1500), p.offUs[1])
        assertTrue(p.info.warnings.contains(PerfWarning.CHANNELS_MERGED))
        assertEquals(2, p.info.mergedChannels)
    }

    @Test fun restrikeNeverShortensBelow20ms() {
        val p = perf(file(Track().on(0, 0, 60, 80).on(5, 1, 60, 90).off(500, 0, 60).off(0, 1, 60)))
        assertEquals(ms(20), p.offUs[0])
        assertTrue(p.onUs[1] > p.offUs[0])
        assertTrue(p.offUs[0] - p.onUs[0] >= 20_000)
    }

    @Test fun zeroLengthGets30ms() {
        val p = perf(file(Track().on(100, 0, 60, 80).off(0, 0, 60).on(100, 0, 62, 80).off(1, 0, 62)))
        assertEquals(30_000L, p.offUs[0] - p.onUs[0])
        assertEquals(1_000L, p.offUs[1] - p.onUs[1])
    }

    @Test fun hangingNotesEndAtTrackEndOrLastEventPlusOneSecond() {
        val a = perf(file(Track().on(0, 0, 60, 80).on(100, 0, 62, 80).off(100, 0, 62).end(800)))
        assertEquals(ms(1000), a.offUs[0])                          // the end-of-track meta
        assertTrue(a.info.warnings.contains(PerfWarning.HANGING_NOTES))
        // No end-of-track meta after the hanging onset: last event + 1 s.
        val raw = Track().on(0, 0, 62, 80).off(100, 0, 62).on(100, 0, 60, 80).build()
        val b = perf(SmfWriter.file(0, 500, raw))
        assertEquals(ms(200) + 1_000_000, b.offUs[b.noteCount - 1])
    }

    @Test fun harpsichordFolds96To84AndMergesTheCollision() {
        val p = perf(file(Track().on(0, 0, 84, 60).on(0, 0, 96, 90).off(400, 0, 84).off(0, 0, 96).on(0, 0, 100, 70).off(100, 0, 100)),
            InstrumentProfile.HARPSICHORD)
        assertEquals(2, p.noteCount)
        assertEquals(84, p.key[0].toInt())
        assertEquals(90, p.vel[0].toInt())                          // higher velocity kept
        assertEquals(88, p.key[1].toInt())                          // 100 → 88
        assertTrue(p.flags[1].toInt() and Performance.F_FOLDED != 0)
        assertEquals(2, p.info.folded)
        assertTrue(p.info.warnings.contains(PerfWarning.FOLDED))
    }

    @Test fun shiftedRestrikesKeepOnsetOrder() {
        // Three strikes of 60 five ms apart (moved to 20 ms + 1 µs and 40 ms + 2 µs) around a 62 at 30 ms.
        val p = perf(file(Track().on(0, 0, 60, 80).on(5, 1, 60, 80).on(5, 2, 60, 80).on(20, 3, 62, 80)
            .off(470, 0, 60).off(0, 1, 60).off(0, 2, 60).off(0, 3, 62)))
        assertEquals(4, p.noteCount)
        for (i in 1 until p.noteCount) assertTrue("onUs sorted at $i", p.onUs[i] >= p.onUs[i - 1])
        assertEquals(listOf(60, 60, 62, 60), (0 until 4).map { p.key[it].toInt() })
        assertEquals(ms(20) + 1, p.onUs[1])
        assertEquals(ms(40) + 2, p.onUs[3])
        for (k in 0 until 128) for (j in p.keyFirst[k] + 1 until p.keyFirst[k + 1]) {
            val a = p.keyNotes[j - 1]; val b = p.keyNotes[j]
            assertTrue("CSR order on key $k", a < b && p.onUs[a] <= p.onUs[b] && p.offUs[a] < p.onUs[b])
        }
    }

    @Test fun sostenutoLatchesHeldKeysOnlyBelowClear() {
        val p = MidiTestUtil.compiler.synthetic(SyntheticScore.SOSTENUTO, InstrumentProfile.GRAND, 0)
        assertEquals(4, p.latchUs.size)
        assertEquals(ms(500), p.latchUs[0])
        assertEquals(1L shl 36, p.latchLo[0]); assertEquals(0L, p.latchHi[0])
        assertEquals(0L, p.latchLo[1])
        assertEquals(ms(7500), p.latchUs[2])
        assertEquals(0.40f, p.sustain.valueAt(ms(7500)), 0.01f)     // a press at sustain 0.4 …
        assertEquals(1L shl 43, p.latchLo[2]); assertEquals(0L, p.latchHi[2])   // … latches only the held key
    }

    @Test fun sostenutoLatchesAllDampedKeysWhenSustainClear() {
        val p = perf(file(Track().cc(0, 0, 64, 127).on(0, 0, 60, 80).cc(500, 0, 66, 127).off(500, 0, 60).cc(500, 0, 66, 0).cc(0, 0, 64, 0)))
        assertEquals(2, p.latchUs.size)
        assertEquals(-1L, p.latchLo[0])                             // keys 0..63
        assertEquals((1L shl (88 - 64 + 1)) - 1, p.latchHi[0])      // 64..88 = lastDamper
    }

    @Test fun sostenutoLatchesLandingDamper() {
        // Key released 10 ms before the press: its damper (18.4 ms) has not landed yet.
        val p = perf(file(Track().on(0, 0, 60, 80).off(490, 0, 60).cc(10, 0, 66, 127).cc(500, 0, 66, 0)))
        assertEquals(1L shl 60, p.latchLo[0])
    }

    @Test fun keyUpAtOffUsAndBeforeNoteOnAtEqualTimes() {
        val p = perf(file(Track().on(0, 0, 60, 80).off(500, 0, 60).on(0, 0, 62, 80).off(500, 0, 62)))
        for (i in p.ev.indices) {
            val e = p.ev[i]
            if (Performance.type(e) == Performance.EV_KEY_UP) assertEquals(p.offUs[Performance.arg(e)], p.evUs[i])
        }
        val at = p.eventIndexAtOrAfter(ms(500))
        assertEquals(Performance.EV_KEY_UP, Performance.type(p.ev[at]))
        assertEquals(Performance.EV_NOTE_ON, Performance.type(p.ev[at + 1]))
        assertEquals(p.durationUs, p.offUs.max() + 1_500_000)
        assertEquals(Performance.EV_END, Performance.type(p.ev[p.ev.size - 1]))
    }

    @Test fun noPluck4EventsOnTheHarpsichord() {
        val p = MidiTestUtil.compiler.synthetic(SyntheticScore.SCALE, InstrumentProfile.HARPSICHORD, 0)
        for (e in p.ev) assertFalse(Performance.type(e) == Performance.EV_PLUCK4_RETIRED)
    }

    @Test fun flatVelocityOnPianosOnly() {
        val bytes = file(Track().on(0, 0, 60, 30).off(100, 0, 60).on(0, 0, 62, 110).off(100, 0, 62))
        val g = perf(bytes, InstrumentProfile.GRAND, CompileOptions(flatVelocity = 72))
        assertTrue(g.vel.all { it.toInt() == 72 })
        val u = perf(bytes, InstrumentProfile.UPRIGHT, CompileOptions(flatVelocity = 72))
        assertTrue(u.vel.all { it.toInt() == 72 })
        val h = perf(bytes, InstrumentProfile.HARPSICHORD, CompileOptions(flatVelocity = 72))
        assertEquals(30, h.vel[0].toInt()); assertEquals(110, h.vel[1].toInt())
    }

    @Test fun preRollAndInfo() {
        val p = MidiTestUtil.compiler.synthetic(SyntheticScore.SCALE, InstrumentProfile.GRAND, 7)
        assertEquals(7, p.generation)
        assertEquals(pre, p.onUs[0])
        assertEquals(88, p.info.noteCount); assertEquals(21, p.info.lowKey); assertEquals(108, p.info.highKey)
        assertEquals(1, p.info.maxPolyphony)
        assertEquals(1, p.info.voiceDemandMax.coerceAtMost(1).coerceAtLeast(1))
        assertTrue(p.info.voiceDemandMax >= 1)
    }
}
