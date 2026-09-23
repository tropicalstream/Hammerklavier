package com.tropicalstream.hammerklavier.library

import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.PedalMode
import com.tropicalstream.hammerklavier.contract.Temperament
import com.tropicalstream.hammerklavier.library.CatalogCodec.assets
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** T9.1: the catalogue codec. */
class CatalogCodecTest {
    private fun res(path: String): String? = javaClass.classLoader.getResource(path)?.readText(Charsets.UTF_8)
    private val json = res("wp9/catalog_wp9.json")!!

    @Test fun everyFieldParses() {
        val m = CatalogCodec.parse(json)
        val bach = m.works.getValue("bach.bwv846.krueger")
        assertEquals("Johann Sebastian Bach", bach.composer); assertEquals("Bach", bach.composerShort)
        assertEquals("WTC I: C major, BWV 846", bach.shortTitle); assertEquals("BWV 846", bach.catalogue)
        assertEquals(1722, bach.year); assertEquals("baroque", bach.era)
        assertEquals(InstrumentId.HARPSICHORD, bach.defaultInstrument)
        assertEquals(listOf(InstrumentId.GRAND, InstrumentId.UPRIGHT), bach.altInstruments)
        assertEquals("krueger", bach.sourceId); assertEquals("A", bach.tier); assertEquals("flat", bach.velocityPolicy)
        assertEquals(415f, bach.tuning!!.aHz, 0f); assertEquals(Temperament.WERCKMEISTER_III, bach.tuning!!.temperament)
        assertEquals(listOf("bach.bwv846.krueger.1", "bach.bwv846.krueger.2"), bach.movementIds)
        assertFalse(bach.imported)
        val handel = m.works.getValue("handel.hwv430")
        assertNull(handel.catalogue); assertNull(handel.year); assertNull(handel.tuning); assertEquals("Händel", handel.composerShort)
        val mv = m.movements.getValue("beethoven.op106.1")
        assertEquals("midi/krueger/beethoven/beethoven_hammerklavier_1.mid", mv.asset); assertNull(mv.file)
        assertEquals("ee", mv.sha1); assertEquals(593.1f, mv.durationSec, 1e-4f); assertEquals(22, mv.lowKey); assertEquals(105, mv.highKey)
        assertTrue(mv.hasSustain); assertTrue(mv.hasSoft); assertFalse(mv.hasSostenuto); assertEquals(PedalMode.CONTINUOUS, mv.pedalMode)
        assertEquals("beethoven.op106", mv.workId)
        assertEquals(PedalMode.SWITCH, m.movements.getValue("beethoven.woo59.1").pedalMode)
        val k = m.sources.getValue("krueger")
        assertEquals("CC-BY-SA-3.0-DE", k.licence); assertEquals("licenses/CC-BY-SA-3.0-DE.txt", k.licenceFile)
        assertEquals("step-sequenced", k.performanceType); assertFalse(k.exportAllowed)
        assertNull(m.sources.getValue("imslp").licenceFile); assertTrue(m.sources.getValue("imslp").exportAllowed)
    }

    @Test fun shelvesAndStartHere() {
        val m = CatalogCodec.parse(json)
        assertEquals(listOf("start-here", "bach-wtc", "handel", "beethoven"), m.shelves.map { it.id })
        assertEquals("Start here", m.shelves[0].title)
        assertEquals(listOf("bach.bwv846.krueger", "handel.hwv430", "beethoven.woo59", "beethoven.op106"), m.shelves[0].workIds)
        assertEquals(listOf("beethoven.woo59", "beethoven.op106"), m.shelves[3].workIds)      // unknown work dropped
        assertEquals(4, m.startHere.size)
    }

    @Test fun missingAssetIsSkippedEverywhere() {
        val m = CatalogCodec.parse(json) { it != "midi/imslp/hwv430.mid" }
        assertFalse(m.works.containsKey("handel.hwv430")); assertFalse(m.movements.containsKey("handel.hwv430.1"))
        assertEquals(emptyList<String>(), m.shelves.first { it.id == "handel" }.workIds)
        assertEquals(listOf("bach.bwv846.krueger.1", "beethoven.woo59.1", "beethoven.op106.1"), m.startHere)
    }

    @Test fun everyKeptMovementAssetIsInTheList() {
        val list = setOf("midi/krueger/bach/bach_846.mid", "midi/krueger/beethoven/elise.mid")
        val m = CatalogCodec.parse(json) { it in list }
        assertTrue(m.assets.isNotEmpty()); assertTrue(m.assets.all { it in list })
        assertEquals(listOf("bach.bwv846.krueger.1"), m.works.getValue("bach.bwv846.krueger").movementIds)
    }

    @Test(expected = IllegalArgumentException::class) fun wrongSchemaThrows() { CatalogCodec.parse("""{"schema": 7}""") }

    @Test fun libraryJsonKeepsUnicode() {
        val out = CatalogCodec.encodeLibrary(CatalogCodec.parse(json))
        assertTrue(out.contains("Für Elise")); assertTrue(out.contains("Händel"))
        val o = JSONObject(String(out.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        assertEquals(4, o.getJSONArray("shelves").length()); assertEquals(5, o.getJSONArray("movements").length())
    }

    @Test fun wp11FixtureParses() {
        val text = res("wp11/catalog_fixture.json")!!
        val m = CatalogCodec.parse(text)
        assertEquals(3, m.works.size)
        val raw = JSONObject(text)
        val listed = raw.optJSONArray("assets")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        for (mv in m.movements.values) {
            assertTrue(mv.id, mv.asset != null && mv.sha1.isNotEmpty() && mv.durationSec > 0f)
            if (listed != null) assertTrue(mv.asset, mv.asset in listed)
        }
        assertTrue(m.shelves.all { s -> s.workIds.all(m.works::containsKey) })
    }

    @Test fun fullCatalogueHas70Works() {
        val f = File("../app/src/main/assets/catalog.json")
        // WP11 delivers the full catalog.json at M6; until then this check is skipped, not ignored.
        org.junit.Assume.assumeTrue("catalog.json not delivered yet (WP11, M6)", f.isFile)
        val m = CatalogCodec.parse(f.readText()) { File("../app/src/main/assets", it).isFile }
        assertTrue(m.works.size in 67..70)
        assertTrue("startHere ${m.startHere.size}", m.startHere.size in 12..13)   // Handel is skipped when absent (§4.8)
    }
}
