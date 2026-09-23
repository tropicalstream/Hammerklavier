package com.tropicalstream.hammerklavier.venue

import com.tropicalstream.hammerklavier.testutil.AwtPainter
import com.tropicalstream.hammerklavier.venue.tex.Atlas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Paints every venue recipe through AwtPainter; the PNGs land in core/build/venue-review for review. */
class AtlasTest {
    @Test fun recipesPaint() {
        val dir = File("build/venue-review").apply { mkdirs() }
        for (r in Atlas.recipes()) {
            val p = AwtPainter()
            p.begin(r.width, r.height)
            r.paint(p)
            p.writePng(File(dir, "${r.name}.png"))
            p.begin(r.width, r.height)
            r.paint(p)
            val px = p.end()
            assertEquals(r.width * r.height * 4, px.size)
            if (r.name == Atlas.ATLAS) {
                // every used cell has drawn (opaque) pixels; unused cells stay transparent black
                for (cell in 0 until 16) {
                    var opaque = 0
                    val cx = (cell % 4) * Atlas.CELL; val cy = (cell / 4) * Atlas.CELL
                    for (y in cy until cy + Atlas.CELL) for (x in cx until cx + Atlas.CELL) {
                        if ((px[(y * r.width + x) * 4 + 3].toInt() and 0xFF) > 0) opaque++
                    }
                    if (cell < Atlas.CELLS_USED) assertTrue("cell $cell", opaque > 200) else assertEquals(0, opaque)
                }
                // gilt never reaches 255 on all channels (255 is reserved for flame cores, §5.9)
                var i = 0
                while (i < px.size) {
                    if ((px[i + 3].toInt() and 0xFF) > 128) assertTrue("texel ${i / 4}", (px[i].toInt() and 0xFF) < 255 || (px[i + 2].toInt() and 0xFF) < 255)
                    i += 4
                }
            } else {
                var opaque = 0
                for (k in 0 until r.width * r.height) if ((px[k * 4 + 3].toInt() and 0xFF) == 255) opaque++
                assertEquals(r.width * r.height, opaque)
            }
        }
    }

    @Test fun cellUvInsideTheAtlas() {
        for (c in 0 until Atlas.CELLS_USED) {
            val uv = Atlas.cellUv(c)
            assertTrue(uv[0] > 0f && uv[1] > 0f && uv[2] < 1f && uv[3] < 1f && uv[0] < uv[2] && uv[1] < uv[3])
        }
    }
}
