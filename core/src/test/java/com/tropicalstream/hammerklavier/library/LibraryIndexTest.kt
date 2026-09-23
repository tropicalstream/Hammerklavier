package com.tropicalstream.hammerklavier.library

import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.PedalMode
import com.tropicalstream.hammerklavier.contract.RejectReason
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class LibraryIndexTest {
    private val item = ImportItem("user.0123456789", "user.handel", "0123456789abcdef0123456789abcdef01234567.mid", "upload",
        10, 20, "Händel.mid", "0123456789abcdef0123456789abcdef01234567", 10, 30, null, "Händel Suite", "Imported",
        12.5f, 40, 80, true, false, true, PedalMode.SWITCH, InstrumentId.HARPSICHORD, InstrumentId.UPRIGHT, 99)

    @Test fun roundTrip() {
        val ix = ImportIndex(listOf(item), listOf(RejectRecord("x.mid", 1, 2, RejectReason.TRUNCATED, "at 14")),
            listOf(DeletedRecord("y.mid", 3, 4)))
        val back = LibraryIndex.parse(LibraryIndex.encode(ix))
        assertEquals(ix, back)
    }

    @Test fun mergePlacesImportedAfterStartHere() {
        val json = javaClass.classLoader.getResource("wp9/catalog_wp9.json")!!.readText()
        val bundled = CatalogCodec.parse(json)
        val m = LibraryIndex.merge(bundled, ImportIndex(listOf(item), emptyList(), emptyList()), File("/x"))
        assertEquals(listOf("start", "imported", "bach-wtc", "handel", "beethoven"), m.shelves.map { it.id })
        val w = m.works.getValue("user.handel")
        assertEquals(InstrumentId.UPRIGHT, w.defaultInstrument)       // the last chosen instrument wins
        assertEquals("/x/${item.file}", m.movements.getValue(item.movementId).file)
        assertEquals(false, m.sources.getValue("user").exportAllowed)
        assertEquals(bundled.startHere, m.startHere)
    }
}
