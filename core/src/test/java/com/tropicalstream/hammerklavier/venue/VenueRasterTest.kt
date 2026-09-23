package com.tropicalstream.hammerklavier.venue

import com.tropicalstream.hammerklavier.contract.Palette
import com.tropicalstream.hammerklavier.testutil.MeshRaster
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Geometry review (PLAN §5.4): rasterises the whole venue from the Hall, the Stage and a
 * look-around framing into core/build/venue-review/raster-*.png. Checks that each framing draws
 * something and that the room does not fill the frame (black = transparent on the waveguide).
 */
class VenueRasterTest {
    private val views = mapOf(
        "hall" to MeshRaster.View(floatArrayOf(0.4f, 1.2f, 3.3f), floatArrayOf(0f, 1.0f, -1.9f)),
        "stage" to MeshRaster.View(floatArrayOf(0.9f, 1.25f, 0.2f), Konzertzimmer.STAGE_CENTRE),
        "lookN" to MeshRaster.View(floatArrayOf(0f, 1.6f, 1.0f), floatArrayOf(0f, 2.2f, -4.0f), 50f),
        "lookS" to MeshRaster.View(floatArrayOf(0f, 1.6f, -1.0f), floatArrayOf(0f, 2.2f, 4.0f), 50f),
        "ceiling" to MeshRaster.View(floatArrayOf(0f, 1.6f, 0f), floatArrayOf(0.01f, 6f, 0f), 70f, floatArrayOf(0f, 0f, -1f)),
    )

    @Test fun framingsRender() {
        val dir = File("build/venue-review").apply { mkdirs() }
        val scene = VenueSceneImpl()
        for (p in Palette.entries) {
            val meshes = scene.meshes(p)
            for ((name, v) in views) {
                val img = MeshRaster.render(meshes, v)
                javax.imageio.ImageIO.write(img, "png", File(dir, "raster-${p.name}-$name.png"))
                val c = MeshRaster.coverage(img)
                assertTrue("$name/$p coverage $c", c > 0.002f && c < 0.95f)
            }
        }
    }
}
