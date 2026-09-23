package com.tropicalstream.hammerklavier.midi

import com.tropicalstream.hammerklavier.contract.CompileOptions
import com.tropicalstream.hammerklavier.contract.CompileResult
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.Performance
import com.tropicalstream.hammerklavier.testutil.PerformanceValidator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/** Shared helpers for the WP1 tests. */
object MidiTestUtil {
    val compiler = ScoreCompilerImpl()

    fun ok(bytes: ByteArray): RawSmf = when (val r = SmfParser.parse(bytes)) {
        is SmfResult.Ok -> r.smf
        is SmfResult.Failed -> { fail("parse failed: ${r.error} ${r.detail} @${r.byteOffset}"); throw IllegalStateException() }
    }

    fun perf(bytes: ByteArray, profile: InstrumentProfile = InstrumentProfile.GRAND, opts: CompileOptions = CompileOptions()): Performance =
        when (val r = compiler.compile(bytes, "t", 1, profile, opts)) {
            is CompileResult.Ok -> r.perf.also { assertValid(it, profile) }
            is CompileResult.Failed -> { fail("compile failed: ${r.reason} ${r.detail}"); throw IllegalStateException() }
        }

    fun assertValid(p: Performance, profile: InstrumentProfile) {
        val probs = PerformanceValidator.problems(p, profile)
        assertTrue(probs.joinToString("\n"), probs.isEmpty())
    }

    fun assertSameArrays(msg: String, a: Performance, b: Performance, noiseTolUs: Long = 0, ignoreNoiseClass: Boolean = false) {
        assertArrayEquals("$msg onUs", a.onUs, b.onUs)
        assertArrayEquals("$msg offUs", a.offUs, b.offUs)
        assertArrayEquals("$msg key", a.key, b.key)
        assertArrayEquals("$msg vel", a.vel, b.vel)
        assertArrayEquals("$msg flags", a.flags, b.flags)
        assertArrayEquals("$msg keyFirst", a.keyFirst, b.keyFirst)
        assertArrayEquals("$msg keyNotes", a.keyNotes, b.keyNotes)
        assertArrayEquals("$msg latchLo", a.latchLo, b.latchLo)
        assertArrayEquals("$msg latchHi", a.latchHi, b.latchHi)
        assertEquals("$msg latch count", a.latchUs.size, b.latchUs.size)
        for (i in a.latchUs.indices) assertTrue("$msg latch $i ${a.latchUs[i]} vs ${b.latchUs[i]}", Math.abs(a.latchUs[i] - b.latchUs[i]) <= noiseTolUs)
        assertEquals("$msg event count", a.ev.size, b.ev.size)
        for (i in a.ev.indices) {
            val ta = Performance.type(a.ev[i]); val tb = Performance.type(b.ev[i])
            assertEquals("$msg event $i type", ta, tb)
            if (ta == Performance.EV_PEDAL_NOISE || ta == Performance.EV_LATCH) {
                assertTrue("$msg event $i time ${a.evUs[i]} vs ${b.evUs[i]}", Math.abs(a.evUs[i] - b.evUs[i]) <= noiseTolUs)
                val ma = if (ignoreNoiseClass && ta == Performance.EV_PEDAL_NOISE) 1 else -1
                assertEquals("$msg event $i arg", Performance.arg(a.ev[i]) and ma, Performance.arg(b.ev[i]) and ma)
            } else {
                assertEquals("$msg event $i time", a.evUs[i], b.evUs[i])
                assertEquals("$msg event $i arg", Performance.arg(a.ev[i]), Performance.arg(b.ev[i]))
            }
        }
        assertEquals("$msg duration", a.durationUs, b.durationUs)
    }

    fun onCount(p: Performance) = p.noteCount
}
