package com.tropicalstream.hammerklavier.midi

import com.tropicalstream.hammerklavier.contract.CompileResult
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.PerfWarning
import com.tropicalstream.hammerklavier.contract.RejectReason
import com.tropicalstream.hammerklavier.midi.MidiTestUtil.ok
import com.tropicalstream.hammerklavier.midi.MidiTestUtil.perf
import com.tropicalstream.hammerklavier.midi.SmfWriter.Track
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T1.1: the parser on hand-built byte arrays. */
class SmfParserTest {
    private val pre = HK.PRE_ROLL_US

    private fun failed(bytes: ByteArray): CompileResult.Failed =
        MidiTestUtil.compiler.compile(bytes, "t", 0, InstrumentProfile.GRAND) as CompileResult.Failed

    @Test fun format0RunningStatusAcrossNotes() {
        val t = Track(runningStatus = true).on(0, 0, 60, 80).on(0, 0, 64, 81).on(480, 0, 60, 0).on(0, 0, 64, 0).end()
        val bytes = SmfWriter.file(0, 480, t.build())
        val plain = SmfWriter.file(0, 480, Track().on(0, 0, 60, 80).on(0, 0, 64, 81).on(480, 0, 60, 0).on(0, 0, 64, 0).end().build())
        assertEquals(plain.size - 3, bytes.size)                    // three status bytes saved
        val p = perf(bytes)
        assertEquals(2, p.noteCount)
        assertArrayEquals(byteArrayOf(60, 64), p.key)
        assertArrayEquals(byteArrayOf(80, 81), p.vel)
        assertEquals(pre + 500_000, p.offUs[0])
        assertFalse(ok(bytes).warnings.contains(PerfWarning.RUNNING_STATUS_REPAIRED))
    }

    @Test fun runningStatusCancelledByMetaIsRepairedWithWarning() {
        val t = Track().on(0, 0, 60, 80).meta(0, 0x01, "x".toByteArray()).bytes(0x00, 62, 70)   // data bytes after a meta
            .off(480, 0, 60).off(0, 0, 62).end()
        val smf = ok(SmfWriter.file(0, 480, t.build()))
        assertTrue(smf.warnings.contains(PerfWarning.RUNNING_STATUS_REPAIRED))
        val p = perf(SmfWriter.file(0, 480, t.build()))
        assertArrayEquals(byteArrayOf(60, 62), p.key)
    }

    @Test fun runningStatusCancelledBySysex() {
        val t = Track().on(0, 0, 60, 80).sysex(0, byteArrayOf(0x7E, 0x7F, 0x09, 0x01, 0xF7.toByte())).bytes(0x00, 62, 70)
            .off(480, 0, 60).off(0, 0, 62).end()
        val bytes = SmfWriter.file(0, 480, t.build())
        assertTrue(ok(bytes).warnings.contains(PerfWarning.RUNNING_STATUS_REPAIRED))
        assertEquals(2, perf(bytes).noteCount)
    }

    @Test fun sysexIsSkipped() {
        val t = Track().on(0, 0, 60, 80).sysex(10, ByteArray(40) { 0x11 }).off(470, 0, 60).end()
        val p = perf(SmfWriter.file(0, 480, t.build()))
        assertEquals(1, p.noteCount); assertEquals(pre + 500_000, p.offUs[0])
    }

    @Test fun format1TempoOnTrack2() {
        val notes = Track().on(960, 0, 60, 80).off(480, 0, 60).end()
        val tempo = Track().tempo(0, 250_000).end()
        val p = perf(SmfWriter.file(1, 480, Track().end().build(), notes.build(), tempo.build()))
        assertEquals(pre + 500_000, p.onUs[0])                    // 960 ticks at 250 ms per quarter
        assertEquals(pre + 750_000, p.offUs[0])
    }

    @Test fun smpte25x40IsOneMsPerTick() {
        val div = (0xE7 shl 8) or 40                               // −25 fps, 40 ticks per frame
        val t = Track().tempo(0, 1_000_000).on(1000, 0, 60, 80).off(250, 0, 60).end()
        val smf = ok(SmfWriter.file(0, div, t.build()))
        assertEquals(1000.0, smf.smpteUsPerTick, 1e-9)
        val p = perf(SmfWriter.file(0, div, t.build()))
        assertEquals(pre + 1_000_000, p.onUs[0])                   // tempo metas ignored
        assertEquals(pre + 1_250_000, p.offUs[0])
    }

    @Test fun unknownChunkSkipped() {
        val t = Track().on(0, 0, 60, 80).off(480, 0, 60).end().build()
        val o = java.io.ByteArrayOutputStream()
        o.write(SmfWriter.header(0, 1, 480)); o.write(SmfWriter.chunk("XFIH", ByteArray(13) { 0x55 })); o.write(SmfWriter.chunk("MTrk", t))
        assertEquals(1, perf(o.toByteArray()).noteCount)
    }

    @Test fun truncatedChunkIsClamped() {
        val t = Track().on(0, 0, 60, 80).off(480, 0, 60).on(0, 0, 62, 80).build()   // no end, hanging 62
        val bytes = SmfWriter.file(0, 480, t)
        // Claim a longer body than present.
        val cut = bytes.copyOf()
        val lenAt = 14 + 4
        cut[lenAt] = 0; cut[lenAt + 1] = 0; cut[lenAt + 2] = 0x10; cut[lenAt + 3] = 0
        val smf = ok(cut)
        assertTrue(smf.warnings.contains(PerfWarning.TRUNCATED_CHUNK))
        val p = perf(cut)
        assertEquals(2, p.noteCount)
        assertTrue(p.info.warnings.contains(PerfWarning.HANGING_NOTES))
    }

    @Test fun vlqValues() {
        fun tickOf(vararg vlq: Int): Long {
            val t = Track().bytes(*vlq).bytes(0x90, 60, 80).off(1, 0, 60).end()
            return ok(SmfWriter.file(0, 480, t.build())).tracks[0].tick[0]
        }
        assertEquals(0L, tickOf(0x00))
        assertEquals(127L, tickOf(0x7F))
        assertEquals(128L, tickOf(0x81, 0x00))
        assertEquals(0x0FFFFFFFL, tickOf(0xFF, 0xFF, 0xFF, 0x7F))
    }

    @Test fun fiveByteVlqEndsTheTrack() {
        val t = Track().on(0, 0, 60, 80).off(100, 0, 60).bytes(0x81, 0x81, 0x81, 0x81, 0x01).bytes(0x90, 62, 80).off(10, 0, 62).end()
        val smf = ok(SmfWriter.file(0, 480, t.build()))
        assertEquals(2, smf.tracks[0].size)                       // the note-on and note-off of 60 only
        assertTrue(smf.warnings.contains(PerfWarning.TRUNCATED_CHUNK))
    }

    @Test fun velocityZeroNoteOnIsNoteOff() {
        val p = perf(SmfWriter.file(0, 480, Track().on(0, 0, 60, 80).on(240, 0, 60, 0).end(240).build()))
        assertEquals(1, p.noteCount)
        assertEquals(pre + 250_000, p.offUs[0])
    }

    @Test fun rmidUnwrapped() {
        val smf = SmfWriter.file(0, 480, Track().on(0, 0, 60, 80).off(480, 0, 60).end().build())
        val r = SmfWriter.rmid(smf)
        assertTrue(MidiTestUtil.compiler.sniff(r))
        MidiTestUtil.assertSameArrays("rmid", perf(smf), perf(r))
    }

    @Test fun channel10DroppedWhenOthersHaveNotes() {
        val t = Track().on(0, 0, 60, 80).on(0, 9, 36, 100).off(10, 9, 36).off(470, 0, 60).end()
        val p = perf(SmfWriter.file(0, 480, t.build()))
        assertEquals(1, p.noteCount)
        assertEquals(1, p.info.droppedDrumNotes)
        assertTrue(p.info.warnings.contains(PerfWarning.DRUMS_DROPPED))
    }

    @Test fun channel10KeptWhenOnlyChannelAndNotDrums() {
        val p = perf(SmfWriter.file(0, 480, Track().on(0, 9, 60, 80).off(480, 9, 60).end().build()))
        assertEquals(1, p.noteCount)
    }

    @Test fun drumsOnlyIsNoKeyboardNotes() {
        val t = Track()
        for (i in 0 until 16) t.on(if (i == 0) 0 else 110, 9, 36 + (i % 3), 100).off(10, 9, 36 + (i % 3))
        val f = failed(SmfWriter.file(0, 480, t.end().build()))
        assertEquals(RejectReason.NO_KEYBOARD_NOTES, f.reason)
    }

    @Test fun cc120ClosesNotes() {
        val t = Track().on(0, 0, 60, 80).on(0, 0, 64, 80).on(0, 1, 67, 80).cc(480, 0, 120, 0).end(480)
        val p = perf(SmfWriter.file(0, 480, t.build()))
        assertEquals(pre + 500_000, p.offUs[0]); assertEquals(pre + 500_000, p.offUs[1])
        assertEquals(pre + 1_000_000, p.offUs[2])                   // channel 2's note hangs to the track end
    }

    @Test fun limitsExceeded() {
        val big = ByteArray((8 shl 20) + 1); "MThd".toByteArray().copyInto(big)
        assertEquals(RejectReason.TOO_LARGE, failed(big).reason)
        val t = Track()
        for (i in 0 until 10) t.on(1, 0, 60, 80).off(1, 0, 60)
        val bytes = SmfWriter.file(0, 480, t.end().build())
        val r = SmfParser.parse(bytes, SmfLimits(maxEvents = 5)) as SmfResult.Failed
        assertEquals(SmfError.TOO_MANY_EVENTS, r.error)
        assertEquals(RejectReason.TOO_MANY_EVENTS, r.error.reason)
        val tracks = Array(4) { Track().end().build() }
        val r2 = SmfParser.parse(SmfWriter.file(1, 480, *tracks), SmfLimits(maxTracks = 3)) as SmfResult.Failed
        assertEquals(SmfError.TOO_LARGE, r2.error)
    }

    @Test fun notMidiAndBadHeader() {
        assertEquals(RejectReason.NOT_MIDI, failed("hello world, not a midi file".toByteArray()).reason)
        val bad = SmfWriter.file(0, 0, Track().end().build())
        assertEquals(RejectReason.BAD_HEADER, failed(bad).reason)
        assertEquals(RejectReason.TRUNCATED, failed("MThd".toByteArray()).reason)
        assertEquals(RejectReason.NO_KEYBOARD_NOTES, failed(SmfWriter.file(0, 480, Track().end().build())).reason)
    }

    @Test fun trailingJunkWarns() {
        val f = SmfWriter.file(0, 480, Track().on(0, 0, 60, 80).off(480, 0, 60).end().build()) + byteArrayOf(0, 1, 2)
        assertTrue(ok(f).warnings.contains(PerfWarning.TRAILING_JUNK))
    }

    @Test fun format2PlaysTracksInSequence() {
        val a = Track().on(0, 0, 60, 80).off(480, 0, 60).end().build()
        val b = Track().on(0, 0, 62, 80).off(480, 0, 62).end().build()
        val p = perf(SmfWriter.file(2, 480, a, b))
        assertEquals(pre + 500_000, p.onUs[1])
        assertTrue(p.info.warnings.contains(PerfWarning.FORMAT2_SEQUENTIAL))
    }

    @Test fun titleFromTrackName() {
        val p = perf(SmfWriter.file(0, 480, Track().name(0, "Invention 1").on(0, 0, 60, 80).off(480, 0, 60).end().build()))
        assertEquals("Invention 1", p.info.title)
    }

    @Test fun format0EqualsFormat1OfTheSameMusic() {
        // Two voices + pedal; format 0 interleaves them, format 1 splits them over three tracks.
        val f0 = Track().tempo(0, 600_000).timeSig(0, 3, 2)
            .on(0, 0, 60, 70).on(0, 1, 48, 60).cc(0, 0, 64, 127).off(240, 0, 60).on(0, 0, 62, 72).off(240, 1, 48)
            .off(0, 0, 62).cc(0, 0, 64, 0).on(0, 1, 50, 65).off(480, 1, 50).end()
        val t0 = Track().tempo(0, 600_000).timeSig(0, 3, 2).end(960)
        val t1 = Track().on(0, 0, 60, 70).cc(0, 0, 64, 127).off(240, 0, 60).on(0, 0, 62, 72).off(240, 0, 62).cc(0, 0, 64, 0).end(480)
        val t2 = Track().on(0, 1, 48, 60).off(480, 1, 48).on(0, 1, 50, 65).off(480, 1, 50).end()
        val a = perf(SmfWriter.file(0, 480, f0.build()))
        val b = perf(SmfWriter.file(1, 480, t0.build(), t1.build(), t2.build()))
        MidiTestUtil.assertSameArrays("f0 vs f1", a, b)
        assertArrayEquals(a.sustain.us, b.sustain.us)
        assertArrayEquals(a.sustain.v, b.sustain.v, 0f)
        assertArrayEquals(a.barUs, b.barUs)
    }
}
