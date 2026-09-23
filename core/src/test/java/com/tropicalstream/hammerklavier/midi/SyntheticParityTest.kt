package com.tropicalstream.hammerklavier.midi

import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.PedalCurve
import com.tropicalstream.hammerklavier.contract.PedalMotion
import com.tropicalstream.hammerklavier.contract.Performance
import com.tropicalstream.hammerklavier.contract.SyntheticScore
import com.tropicalstream.hammerklavier.contract.SyntheticSpecs
import com.tropicalstream.hammerklavier.contract.stub.PerfFixtures
import com.tropicalstream.hammerklavier.contract.stub.StubScoreCompiler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T1.8: SyntheticScores (the real builder) against PerfFixtures (raw curves, no shaping) for every
 * kind and instrument, and every `.mid` twin against its SyntheticScore.
 *
 * Tolerances (PLAN §2.3 "pedal curves within 1 ms and 0.02"), as interpreted here: notes, CSR
 * and every note/key-up/end event exact; latch and pedal-noise times within 1 ms; pedal-noise speed
 * classes not compared (PerfFixtures reads a raw step as class 2, the builder measures the shaped
 * slope); each pedal curve crosses its reference level (sustain 0.33, soft and sostenuto 0.5) at the
 * same times ± 1 ms and agrees within 0.02 everywhere at least 70 ms (one ramp) away from a raw step.
 */
class SyntheticParityTest {
    private val profiles = listOf(InstrumentProfile.GRAND, InstrumentProfile.UPRIGHT, InstrumentProfile.HARPSICHORD)

    @Test fun syntheticScoresEqualPerfFixtures() {
        val stub = StubScoreCompiler()
        for (kind in SyntheticScore.entries) for (pr in profiles) {
            val msg = "$kind/${pr.id}"
            val mine = SyntheticScores.build(kind, pr, 3)
            val ref = stub.synthetic(kind, pr, 3)
            MidiTestUtil.assertValid(mine, pr)
            MidiTestUtil.assertSameArrays(msg, mine, ref, noiseTolUs = 1_000, ignoreNoiseClass = true)
            assertCurve("$msg sustain", mine.sustain, ref.sustain, PedalMotion.LIFT_START)
            assertCurve("$msg soft", mine.soft, ref.soft, 0.5f)
            assertCurve("$msg sostenuto", mine.sostenuto, ref.sostenuto, 0.5f)
            assertArrayEquals("$msg bars", ref.barUs, mine.barUs)
        }
    }

    @Test fun perfFixturesBuildMatchesStubForSpecs() {
        // Guards the reference itself: StubScoreCompiler.synthetic = PerfFixtures.build of the spec.
        val spec = SyntheticSpecs.notes(SyntheticScore.SCALE)
        val a = PerfFixtures.build(spec, spec.cc64, spec.cc67, spec.cc66, InstrumentProfile.GRAND, 0)
        val b = StubScoreCompiler().synthetic(SyntheticScore.SCALE, InstrumentProfile.GRAND, 0)
        assertArrayEquals(a.onUs, b.onUs)
    }

    @Test fun ownTwinsCompileToTheirSyntheticScore() {
        for (kind in SyntheticScore.entries) for (rs in listOf(false, true)) {
            val bytes = SmfWriter.twin(SyntheticSpecs.notes(kind), runningStatus = rs)
            for (pr in profiles) {
                val a = MidiTestUtil.perf(bytes, pr)
                val b = SyntheticScores.build(kind, pr, 1)
                val msg = "twin $kind/${pr.id}/rs=$rs"
                MidiTestUtil.assertSameArrays(msg, a, b)
                assertArrayEquals("$msg sustain us", b.sustain.us, a.sustain.us)
                assertArrayEquals("$msg sustain v", b.sustain.v, a.sustain.v, 0f)
                assertArrayEquals("$msg soft us", b.soft.us, a.soft.us)
                assertArrayEquals("$msg sost us", b.sostenuto.us, a.sostenuto.us)
                assertArrayEquals("$msg bars", b.barUs, a.barUs)
            }
        }
    }

    @Test fun wp11TwinsCompileToTheirSyntheticScore() {
        val dir = File("../app/src/main/assets/midi/test")
        for (kind in SyntheticScore.entries) {
            val f = File(dir, SyntheticSpecs.NAMES.getValue(kind) + ".mid")
            assertTrue("missing ${f.path}", f.isFile)
            for (pr in profiles) MidiTestUtil.assertSameArrays("wp11 twin $kind/${pr.id}", MidiTestUtil.perf(f.readBytes(), pr), SyntheticScores.build(kind, pr, 1))
        }
    }

    private fun crossings(c: PedalCurve, level: Float): List<Long> {
        val out = ArrayList<Long>()
        if (c.isEmpty) return out
        var from = 0L; var rising = true
        while (true) {
            val x = c.nextCrossing(from, level, rising)
            if (x == Long.MAX_VALUE) return out
            out.add(x); from = x + 1; rising = !rising
        }
    }

    private fun assertCurve(msg: String, mine: PedalCurve, ref: PedalCurve, level: Float) {
        assertEquals("$msg empty", ref.isEmpty, mine.isEmpty)
        if (ref.isEmpty) return
        val a = crossings(mine, level); val b = crossings(ref, level)
        assertEquals("$msg crossings $a vs $b", b.size, a.size)
        for (i in a.indices) assertTrue("$msg crossing $i ${a[i]} vs ${b[i]}", Math.abs(a[i] - b[i]) <= 1_000)
        val steps = ref.us
        val end = maxOf(ref.us.last(), mine.us.last()) + 200_000
        var t = ref.us.first() - 200_000
        var j = 0
        while (t < end) {
            while (j < steps.size && steps[j] < t - 70_000) j++
            val near = j < steps.size && Math.abs(steps[j] - t) <= 70_000
            if (!near) assertEquals("$msg value at $t", ref.valueAt(t), mine.valueAt(t), 0.02f)
            t += 1_000
        }
    }
}
