package com.tropicalstream.hammerklavier.midi

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.SyntheticScore
import com.tropicalstream.hammerklavier.midi.MidiTestUtil.perf
import com.tropicalstream.hammerklavier.midi.SmfWriter.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.io.File

/** T1.9: voice demand on hand-computed cases, and the report. PPQ 500: 1 tick = 1 ms. */
class VoiceDemandTest {
    private val pre = HK.PRE_ROLL_US
    private val G = InstrumentProfile.GRAND

    @Test fun heldPedalledChord() {
        // Pedal down, C4 E4 G4 struck together at 0, released at 1 s; pedal up at 20 s.
        val p = perf(SmfWriter.file(0, 500, Track().cc(0, 0, 64, 127).on(0, 0, 60, 80).on(0, 0, 64, 80).on(0, 0, 67, 80)
            .off(1000, 0, 60).off(0, 0, 64).off(0, 0, 67).cc(19_000, 0, 64, 0).end().build()))
        assertEquals(3, p.info.voiceDemandMax); assertEquals(3, p.info.voiceDemandP99)
        // Each voice ends at the earliest of its trim and its −80 dB natural decay (the pedal outlasts both).
        val ends = VoiceDemand.ends(p.onUs, p.offUs, p.key, p.keyFirst, p.keyNotes, p.sustain, p.latchUs, p.latchLo, p.latchHi, G)
        for (i in 0 until 3) {
            val k = p.key[i].toInt()
            val trim = 14.0 + (3.0 - 14.0) * (k - 21) / 87.0
            val cull = 80.0 / 60.0 * minOf(30.0, 6.24 * Math.pow(10.0, -0.0275 * (k - 60)))
            assertEquals((pre + Math.round(minOf(trim, cull) * 1e6)).toDouble(), ends[i].toDouble(), 2.0)
        }
    }

    @Test fun unpedalledNoteIsDampedAtTheDamperLanding() {
        val p = perf(SmfWriter.file(0, 500, Track().on(0, 0, 60, 80).off(1000, 0, 60).end().build()))
        val ends = VoiceDemand.ends(p.onUs, p.offUs, p.key, p.keyFirst, p.keyNotes, p.sustain, p.latchUs, p.latchLo, p.latchHi, G)
        val land = 1.0 + G.damperLagMs / 1000.0                     // s after the onset
        val t60f = 6.24; val t60d = G.defaultDamperT60(60).toDouble()
        val expect = land + (80.0 - 60.0 * land / t60f) / 60.0 * t60d
        assertEquals(pre + expect * 1e6, ends[0].toDouble(), 5.0)
    }

    @Test fun repeatedKeyWithThreeVoiceLimit() {
        val t = Track().cc(0, 0, 64, 127)
        for (i in 0 until 5) t.on(if (i == 0) 0 else 50, 0, 60, 80).off(50, 0, 60)
        val p = perf(SmfWriter.file(0, 500, t.cc(1000, 0, 64, 0).end().build()))
        assertEquals(5, p.noteCount)
        // Samples at the five onsets: 1, 2, 3, 3, 3.
        assertEquals(3, p.info.voiceDemandMax); assertEquals(3, p.info.voiceDemandP99)
        val ends = VoiceDemand.ends(p.onUs, p.offUs, p.key, p.keyFirst, p.keyNotes, p.sustain, p.latchUs, p.latchLo, p.latchHi, G)
        // Note 0 is faded (τ 200 ms, pedal down) by note 1 and moved to a kill slot (uncounted) by note 3, the 4th voice.
        assertEquals(p.onUs[3], ends[0])
        assertEquals(p.onUs[4], ends[1])
        assertTrue(ends[2] <= p.onUs[3] + Math.round(9.21 * 200_000))           // faded by note 3 (or damped sooner)
    }

    @Test fun harpsichordCountsBothStops() {
        val p = MidiTestUtil.compiler.synthetic(SyntheticScore.SCALE, InstrumentProfile.HARPSICHORD, 0)
        assertTrue(p.info.voiceDemandMax >= 2)
        assertEquals(0, p.info.voiceDemandMax % 2)
    }

    @Test fun writesReportForSyntheticScores() {
        val sb = StringBuilder("# voice demand (uncapped): score\tinstrument\tp99\tmax\n")
        for (k in SyntheticScore.entries) for (id in InstrumentId.entries) {
            val p = SyntheticScores.build(k, InstrumentProfile.of(id), 0)
            sb.append("synth:").append(k.name).append('\t').append(id.key).append('\t')
                .append(p.info.voiceDemandP99).append('\t').append(p.info.voiceDemandMax).append('\n')
        }
        val storm = SyntheticScores.build(SyntheticScore.CHORD_STORM_64, G, 0)
        assertTrue(storm.info.voiceDemandMax >= 64)
        File("build").mkdirs()
        File("build/voice_demand.txt").writeText(sb.toString())
    }

    @Test fun writesReportForTheCatalogue() {
        // The shipped catalogue once WP11 bundles it; until then WP11's catalogue fixture (same schema, test assets).
        val shipped = File("../app/src/main/assets/catalog.json")
        val cat = JSONObject((if (shipped.isFile) shipped else File("src/test/resources/wp11/catalog_fixture.json")).readText())
        var rows = 0
        val works = cat.getJSONArray("works")
        val sb = StringBuilder("# voice demand (uncapped) per catalogue movement × default instrument: movement\tinstrument\tp99\tmax\n")
        for (w in 0 until works.length()) {
            val work = works.getJSONObject(w)
            val prof = InstrumentProfile.of(InstrumentId.of(work.getString("defaultInstrument"))!!)
            val mv = work.getJSONArray("movements")
            for (m in 0 until mv.length()) {
                val o = mv.getJSONObject(m)
                val f = File("../app/src/main/assets/" + o.getString("asset"))
                if (!f.isFile) continue
                val p = perf(f.readBytes(), prof)
                sb.append(o.getString("id")).append('\t').append(prof.id.key).append('\t')
                    .append(p.info.voiceDemandP99).append('\t').append(p.info.voiceDemandMax).append('\n')
                assertTrue(o.getString("id"), p.info.voiceDemandMax >= 1 && p.info.voiceDemandP99 <= p.info.voiceDemandMax)
                rows++
            }
        }
        assertTrue("no catalogue movement found", rows > 0)
        File("build").mkdirs()
        File("build/voice_demand_catalogue.txt").writeText(sb.toString())
    }
}
