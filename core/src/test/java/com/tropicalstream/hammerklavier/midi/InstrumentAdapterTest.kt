package com.tropicalstream.hammerklavier.midi

import com.tropicalstream.hammerklavier.contract.CompileOptions
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.PerfWarning
import com.tropicalstream.hammerklavier.midi.MidiTestUtil.perf
import com.tropicalstream.hammerklavier.midi.SmfWriter.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T1.5: the instrument adapter. PPQ 500 at 120 bpm: 1 tick = 1 ms. */
class InstrumentAdapterTest {
    private val pre = HK.PRE_ROLL_US
    private fun ms(x: Long) = pre + x * 1000
    private val H = InstrumentProfile.HARPSICHORD

    @Test fun legatoHoldDelaysNoteOffsToPedalUp() {
        // Pedal down 0–800 ms; note 60 0–200 ms → held to 800 ms.
        val bytes = SmfWriter.file(0, 500, Track().cc(0, 0, 64, 127).on(0, 0, 60, 80).off(200, 0, 60).cc(600, 0, 64, 0).end().build())
        val p = perf(bytes, H)
        assertEquals(ms(800).toDouble(), p.offUs[0].toDouble(), 100.0)
        assertTrue(p.info.fingerPedalled)
        assertTrue(p.info.warnings.contains(PerfWarning.FINGER_PEDALLED))
        assertTrue(p.sustain.isEmpty && p.soft.isEmpty && p.sostenuto.isEmpty)          // pedals removed
        // The grand keeps the note-off and the pedal.
        val g = perf(bytes, InstrumentProfile.GRAND)
        assertEquals(ms(200), g.offUs[0]); assertFalse(g.sustain.isEmpty)
    }

    @Test fun legatoHoldCappedAt1500ms() {
        val bytes = SmfWriter.file(0, 500, Track().cc(0, 0, 64, 127).on(0, 0, 60, 80).off(200, 0, 60).cc(4000, 0, 64, 0).end().build())
        assertEquals(ms(1700), perf(bytes, H).offUs[0])
        assertEquals(ms(1200), perf(bytes, H, CompileOptions(legatoCapMs = 1000)).offUs[0])
        assertEquals(ms(200), perf(bytes, H, CompileOptions(legatoHold = false)).offUs[0])
    }

    @Test fun legatoHoldStopsAtNextSameKeyOnset() {
        val bytes = SmfWriter.file(0, 500, Track().cc(0, 0, 64, 127).on(0, 0, 60, 80).off(200, 0, 60).on(300, 0, 60, 80)
            .off(100, 0, 60).cc(300, 0, 64, 0).end().build())
        val p = perf(bytes, H)
        assertEquals(ms(498), p.offUs[0])
        assertEquals(ms(900).toDouble(), p.offUs[1].toDouble(), 100.0)
    }

    @Test fun uprightDropsCc66AndHarpsichordDropsCc67() {
        val bytes = SmfWriter.file(0, 500, Track().on(0, 0, 60, 80).cc(0, 0, 66, 127).cc(0, 0, 67, 127).off(500, 0, 60)
            .cc(0, 0, 66, 0).cc(0, 0, 67, 0).end().build())
        val u = perf(bytes, InstrumentProfile.UPRIGHT)
        assertTrue(u.sostenuto.isEmpty); assertEquals(0, u.latchUs.size); assertFalse(u.soft.isEmpty)
        val g = perf(bytes, InstrumentProfile.GRAND)
        assertFalse(g.sostenuto.isEmpty); assertEquals(2, g.latchUs.size); assertFalse(g.soft.isEmpty)
        val h = perf(bytes, H)
        assertTrue(h.soft.isEmpty && h.sostenuto.isEmpty)
    }
}
