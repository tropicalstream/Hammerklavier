package com.tropicalstream.hammerklavier.instrument

import com.tropicalstream.hammerklavier.instrument.tex.Wood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared wood tile is TapGem's PanelTextures.wood(): dark walnut, never the washed M5-M8 flat browns. */
class WoodTest {
    @Test fun tapGemWalnutRange() {
        val px = Wood.pixels()
        assertEquals(Wood.SIZE * Wood.SIZE, px.size)
        var r = 0L; var g = 0L; var b = 0L
        for (c in px) {
            val cr = (c shr 16) and 255; val cg = (c shr 8) and 255; val cb = c and 255
            assertTrue("texel inside #170D07..#4A2E1A", cr in 0x17..0x4A && cg in 0x0D..0x2E && cb in 0x07..0x1A)
            assertEquals(255, (c ushr 24))
            r += cr; g += cg; b += cb
        }
        val n = px.size
        // mean ≈ the #2B1A10 base: dark, warm (r > g > b), nowhere near the old WALNUT 123,91,77
        assertTrue("mean r ${r / n}", r / n in 34..52); assertTrue(g / n in 20..32); assertTrue(b / n in 11..20)
        assertTrue(r > g && g > b)
    }

    @Test fun deterministicAndGrained() {
        val a = Wood.pixels(); val b = Wood.pixels()
        assertTrue(a.contentEquals(b))
        assertTrue("grain contrast", a.toSet().size > 30)
    }

    @Test fun rgbaMatchesPixels() {
        val px = Wood.pixels(); val by = Wood.rgba()
        for (i in px.indices step 997) {
            assertEquals((px[i] shr 16) and 255, by[4 * i].toInt() and 255)
            assertEquals(px[i] and 255, by[4 * i + 2].toInt() and 255)
        }
    }
}
