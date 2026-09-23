package com.tropicalstream.hammerklavier.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class TuningProfileTest {
    @Test fun werckmeisterTable() {
        val w = floatArrayOf(11.7f, 2.0f, 3.9f, 5.9f, 2.0f, 9.8f, 0.0f, 7.8f, 3.9f, 0f, 7.8f, 3.9f)
        for (pc in 0..11) assertEquals(w[pc], Temperament.WERCKMEISTER_III.offsetCents(pc), 1e-6f)
        for (t in Temperament.entries) assertEquals(0f, t.offsetCents(9), 0f)
    }

    @Test fun keyCentsAtA415() {
        assertEquals(-101.27f, TuningSpec.A415_WERCKMEISTER.keyCents(69), 0.01f)
        assertEquals(0f, TuningSpec.A440_EQUAL.keyCents(69), 1e-6f)
        assertEquals(-101.27f + 11.7f, TuningSpec.A415_WERCKMEISTER.keyCents(60), 0.01f)
    }

    @Test fun profiles() {
        assertEquals(18.4f, InstrumentProfile.GRAND.damperLagMs, 0.1f)
        assertEquals(26.2f, InstrumentProfile.UPRIGHT.damperLagMs, 0.1f)
        assertEquals(47.6f, InstrumentProfile.HARPSICHORD.damperLagMs, 0.1f)
        val g = InstrumentProfile.GRAND
        assertEquals(1, g.stringsPerKey(21)); assertEquals(1, g.stringsPerKey(28))
        assertEquals(2, g.stringsPerKey(29)); assertEquals(2, g.stringsPerKey(48))
        assertEquals(3, g.stringsPerKey(49)); assertEquals(3, g.stringsPerKey(108))
        assertEquals(228, (21..108).sumOf { g.stringsPerKey(it) })        // 8 + 40 + 180 (T7.6)
        assertEquals(1, InstrumentProfile.HARPSICHORD.stringsPerKey(60))
        assertEquals(0.12f, g.defaultDamperT60(84), 0.01f)
        assertEquals(0.33f, g.defaultDamperT60(60), 0.01f)
        assertEquals(0.84f, g.defaultDamperT60(36), 0.01f)
        assertEquals(1.32f, g.defaultDamperT60(21), 0.01f)
        val h = InstrumentProfile.HARPSICHORD
        assertEquals(20f, h.defaultFreeT60(0, 29), 0.01f)
        assertEquals(4.2f, h.defaultFreeT60(0, 89), 0.05f)
        assertEquals(30f, g.defaultFreeT60(0, 21), 0.001f)
        val d = g.withLastDamper(68)
        assertEquals(68, d.lastDamper); assertNotSame(g, d); assertEquals(g.keyReturnMs, d.keyReturnMs, 0f)
        for (id in InstrumentId.entries) assertEquals(id, InstrumentProfile.of(id).id)
        assertEquals(TuningSpec.A415_WERCKMEISTER, h.defaultTuning)
        assertEquals(InstrumentId.UPRIGHT, InstrumentId.of("upright"))
    }
}
