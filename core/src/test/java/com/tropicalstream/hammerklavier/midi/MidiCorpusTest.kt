package com.tropicalstream.hammerklavier.midi

import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.PedalMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.io.File

/**
 * T1.7: every bundled MIDI asset parses Ok and matches WP11's `midi_facts_golden.json` (written by
 * the independent `smf_stats.py`): duration ± 1 ms, ranges and counts exact. The schema this test
 * reads is requested in docs/requests/WP1.md: `{"files": [{"asset", "durationSec", "lowKey",
 * "highKey", "notes", "hasSustain", "hasSoft", "hasSostenuto", "pedalMode", "harpsichordFolds"?}]}`.
 */
class MidiCorpusTest {
    @Ignore("needs wp11 fixture") @Test fun everyAssetMatchesTheGoldenFacts() {
        val golden = File("src/test/resources/wp11/midi_facts_golden.json")
        val files = JSONObject(golden.readText()).getJSONArray("files")
        val c = ScoreCompilerImpl()
        assertTrue(files.length() > 0)
        for (i in 0 until files.length()) {
            val g = files.getJSONObject(i)
            val asset = g.getString("asset")
            val bytes = File("../app/src/main/assets/$asset").readBytes()
            val f = c.inspect(bytes)
            assertTrue("$asset: ${f.error}", f.ok)
            assertEquals("$asset duration", g.getDouble("durationSec"), f.durationSec.toDouble(), 0.001)
            assertEquals("$asset lowKey", g.getInt("lowKey"), f.lowKey)
            assertEquals("$asset highKey", g.getInt("highKey"), f.highKey)
            assertEquals("$asset notes", g.getInt("notes"), f.noteCount)
            assertEquals("$asset hasSustain", g.getBoolean("hasSustain"), f.hasSustain)
            assertEquals("$asset hasSoft", g.getBoolean("hasSoft"), f.hasSoft)
            assertEquals("$asset hasSostenuto", g.getBoolean("hasSostenuto"), f.hasSostenuto)
            assertEquals("$asset pedalMode", PedalMode.valueOf(g.getString("pedalMode").uppercase()), f.pedalMode)
            if (g.has("harpsichordFolds")) {
                val p = MidiTestUtil.perf(bytes, InstrumentProfile.HARPSICHORD)
                assertEquals("$asset folds", g.getInt("harpsichordFolds"), p.info.folded)
            }
        }
    }
}
