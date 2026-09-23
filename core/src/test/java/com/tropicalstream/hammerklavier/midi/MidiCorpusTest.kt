package com.tropicalstream.hammerklavier.midi

import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.PedalMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T1.7: every bundled MIDI asset parses Ok and matches WP11's `midi_facts_golden.json` (written by
 * the independent `smf_stats.py`): duration ± 1 ms, ranges and counts exact. The schema this test
 * reads is requested in docs/requests/WP1.md: `{"files": [{"asset", "durationSec", "lowKey",
 * "highKey", "notes", "hasSustain", "hasSoft", "hasSostenuto", "pedalMode", "harpsichordFolds"?}]}`.
 */
class MidiCorpusTest {
    @Test fun everyAssetMatchesTheGoldenFacts() {
        val golden = File("src/test/resources/wp11/midi_facts_golden.json")
        val files = JSONObject(golden.readText()).getJSONObject("files")
        val c = ScoreCompilerImpl()
        assertTrue(files.length() > 0)
        val failures = ArrayList<String>()
        for (asset in files.keys()) {
            val g = files.getJSONObject(asset)
            val bytes = File("../app/src/main/assets/$asset").readBytes()
            val f = c.inspect(bytes)
            assertTrue("$asset: ${f.error}", f.ok)
            fun chk(what: String, ok: Boolean, a: Any, b: Any) { if (!ok) failures.add("$asset $what: golden $a, mine $b") }
            val dur = g.getDouble("durationSec")
            chk("durationSec", Math.abs(dur - f.durationSec) <= 0.001 + 1e-6, dur, f.durationSec)
            chk("lowKey", g.getInt("lowKey") == f.lowKey, g.getInt("lowKey"), f.lowKey)
            chk("highKey", g.getInt("highKey") == f.highKey, g.getInt("highKey"), f.highKey)
            chk("notes", g.getInt("notes") == f.noteCount, g.getInt("notes"), f.noteCount)
            chk("hasSustain", g.getBoolean("hasSustain") == f.hasSustain, g.getBoolean("hasSustain"), f.hasSustain)
            chk("hasSoft", g.getBoolean("hasSoft") == f.hasSoft, g.getBoolean("hasSoft"), f.hasSoft)
            chk("hasSostenuto", g.getBoolean("hasSostenuto") == f.hasSostenuto, g.getBoolean("hasSostenuto"), f.hasSostenuto)
            val pm = PedalMode.valueOf(g.getString("pedalMode").uppercase())
            chk("pedalMode", pm == f.pedalMode, pm, f.pedalMode)
            val folds = g.optJSONObject("folds")
            if (folds != null) for (id in InstrumentId.entries) {
                if (!folds.has(id.key)) continue
                val p = MidiTestUtil.perf(bytes, InstrumentProfile.of(id))
                chk("folds ${id.key}", folds.getInt(id.key) == p.info.folded, folds.getInt(id.key), p.info.folded)
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
