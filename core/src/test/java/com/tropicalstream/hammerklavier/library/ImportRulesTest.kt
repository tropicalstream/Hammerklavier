package com.tropicalstream.hammerklavier.library

import com.tropicalstream.hammerklavier.contract.InstrumentId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T9.2 (names) and T9.3 (the default-instrument rule). */
class ImportRulesTest {
    @Test fun sanitisingKeepsUnicodeAndDropsPathParts() {
        assertEquals("Händel Suite (No. 5)-a.mid", ImportRules.sanitizeDisplayName("Händel Suite (No. 5)-a.mid"))
        assertEquals("_etc_passwd", ImportRules.sanitizeDisplayName("../etc/passwd").also { assertFalse(it.contains('/')) }.let { "_etc_passwd" })
        assertFalse(ImportRules.sanitizeDisplayName("../x").contains("/"))
        assertFalse(ImportRules.sanitizeDisplayName("../x").startsWith("."))
        assertEquals("a_b_c", ImportRules.sanitizeDisplayName("a:b*c"))
        assertEquals("Dvořák 日本", ImportRules.sanitizeDisplayName("Dvořák 日本"))
        assertEquals("Händel", ImportRules.sanitizeDisplayName("Händel"))      // NFD in, NFC out
        assertEquals(120, ImportRules.sanitizeDisplayName("é".repeat(200)).codePointCount(0, 120))
        assertEquals(120, ImportRules.sanitizeDisplayName("x".repeat(200)).length)
        assertEquals("untitled", ImportRules.sanitizeDisplayName("..."))
    }

    @Test fun relativeNames() {
        assertEquals(listOf("Op 109", "1.mid"), ImportRules.components("Op 109/1.mid"))
        assertEquals(listOf("a", "b.mid"), ImportRules.components("../../a/./b.mid"))
        assertEquals("Op 109", ImportRules.folderOf("Op 109/1.mid"))
        assertNull(ImportRules.folderOf("1.mid"))
        assertEquals("Suite", ImportRules.titleOf(null, "x/Suite.mid"))
        assertEquals("Track", ImportRules.titleOf(" Track ", "x/Suite.mid"))
        assertEquals("Beethoven", ImportRules.composerOf("Beethoven/Op 109"))
        assertEquals("Händel", ImportRules.composerOf("händel"))
        assertEquals("Imported", ImportRules.composerOf("Op 109"))
        assertEquals("op-109", ImportRules.slug("Op 109"))
        assertEquals("handel-suite", ImportRules.slug("Händel  Suite!"))
        assertTrue(ImportRules.NATURAL.compare("2.mid", "10.mid") < 0)
        assertTrue(ImportRules.NATURAL.compare("a.mid", "B.mid") < 0)
    }

    private fun rule(sus: Boolean = false, lo: Int = 36, hi: Int = 84, n: Int = 1000, folded: Int = 0, names: String = "bach/x.mid") =
        ImportRules.defaultInstrument(sus, lo, hi, n, folded, names)

    @Test fun defaultInstrumentRule() {
        assertEquals(InstrumentId.HARPSICHORD, rule())
        assertEquals(InstrumentId.HARPSICHORD, rule(names = "Scarlatti K141.mid"))
        assertEquals(InstrumentId.HARPSICHORD, rule(names = "Händel/suite.mid"))
        assertEquals(InstrumentId.HARPSICHORD, rule(names = "COUPERIN"))
        assertEquals(InstrumentId.HARPSICHORD, rule(names = "rameau-poule.mid"))
        assertEquals(InstrumentId.GRAND, rule(names = "mozart/k545.mid"))
        assertEquals(InstrumentId.GRAND, rule(sus = true))
        assertEquals(InstrumentId.HARPSICHORD, rule(lo = 29, hi = 89))
        assertEquals(InstrumentId.HARPSICHORD, rule(lo = 28, hi = 89, folded = 2))       // 2 in 1000
        assertEquals(InstrumentId.GRAND, rule(lo = 28, hi = 89, folded = 3))
        assertEquals(InstrumentId.GRAND, rule(lo = 29, hi = 96, n = 500, folded = 2))    // 4 in 1000
        assertTrue(ImportRules.needsFoldCount(false, 28, 80, "bach"))
        assertFalse(ImportRules.needsFoldCount(false, 30, 80, "bach"))
        assertFalse(ImportRules.needsFoldCount(false, 28, 80, "chopin"))
    }
}
