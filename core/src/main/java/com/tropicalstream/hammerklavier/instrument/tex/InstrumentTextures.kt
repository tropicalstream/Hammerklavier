package com.tropicalstream.hammerklavier.instrument.tex

import com.tropicalstream.hammerklavier.contract.Painter2D
import com.tropicalstream.hammerklavier.contract.Pal
import com.tropicalstream.hammerklavier.contract.TextureRecipe
import kotlin.math.cos
import kotlin.math.sin

/**
 * The instruments' texture recipes (PLAN §5.4): painted through [Painter2D] on HKLoader, no image
 * assets. The caller runs `begin(width, height)`, `paint`, then `end()`; a recipe only draws. Colours are un-lifted sRGB from `Pal` (the shaders lift), kept below 220 over wide areas
 * (§5.9 rule 2); black = transparent.
 */
object InstrumentTextures {
    const val FALLBOARD = "hk.fallboard"
    const val HARPSI_PAPER = "hk.harpsi.paper"
    const val HARPSI_LID = "hk.harpsi.lid"
    const val HARPSI_SOUNDBOARD = "hk.harpsi.soundboard"

    /** Motto on the harpsichord lid (public domain). */
    const val MOTTO = "MUSICA LAETITIAE COMES MEDICINA DOLORUM"

    fun argb(rgb: IntArray, a: Int = 255, f: Float = 1f): Int {
        fun c(i: Int) = (rgb[i] * f + 0.5f).toInt().coerceIn(0, 255)
        return (a shl 24) or (c(0) shl 16) or (c(1) shl 8) or c(2)
    }

    /** "HAMMERKLAVIER" in gilt serif capitals on transparent black (a DECAL on the fallboard). */
    fun fallboard(): TextureRecipe = TextureRecipe(FALLBOARD, 512, 64) { p ->
        p.text("HAMMERKLAVIER", 256f, 44f, 34f, argb(Pal.GILT_LIT), serif = true, italic = false, centred = true)
        p.strokePath(floatArrayOf(96f, 54f, 416f, 54f), false, 1.5f, argb(Pal.GILT_SHADE))
        p.blur(0.6f)
    }

    /** Block-printed paper band (Flemish sea-horse and scroll repeat, simplified to scrolls and dots). */
    fun harpsiPaper(): TextureRecipe = TextureRecipe(HARPSI_PAPER, 512, 64) { p ->
        p.fillRect(0f, 0f, 512f, 64f, argb(Pal.FLEMISH_PAPER, f = 0.6f))
        val ink = argb(Pal.FLEMISH_CASE, f = 0.55f)
        var x = 16f
        while (x < 512f) {
            val arc = FloatArray(2 * 13)
            for (i in 0 until 13) {
                val t = Math.PI * i / 12.0
                arc[2 * i] = (x + 14f * cos(t)).toFloat(); arc[2 * i + 1] = (32f - 14f * sin(t)).toFloat()
            }
            p.strokePath(arc, false, 3f, ink)
            p.fillCircle(x, 32f, 4f, ink)
            p.fillCircle(x + 16f, 46f, 2.5f, ink)
            x += 32f
        }
        p.fillRect(0f, 0f, 512f, 4f, ink); p.fillRect(0f, 60f, 512f, 4f, ink)
    }

    /** The lid's inside: paper with the motto in italic serif capitals. u along the lid (toward the tail). */
    fun harpsiLid(): TextureRecipe = TextureRecipe(HARPSI_LID, 1024, 128) { p ->
        p.fillRect(0f, 0f, 1024f, 128f, argb(Pal.FLEMISH_PAPER, f = 0.6f))
        p.fillRect(0f, 8f, 1024f, 3f, argb(Pal.FLEMISH_CASE, f = 0.7f)); p.fillRect(0f, 117f, 1024f, 3f, argb(Pal.FLEMISH_CASE, f = 0.7f))
        p.text(MOTTO, 512f, 76f, 38f, argb(Pal.FLEMISH_CASE, f = 0.5f), serif = true, italic = true, centred = true)
    }

    /**
     * The painted soundboard: HARPSI_SOUNDBOARD × 0.7, flower sprays in the three §5.9 colours
     * and a gilt rose centred at ([roseU], [roseV]) (0..1 of the texture).
     */
    fun harpsiSoundboard(roseU: Float, roseV: Float): TextureRecipe = TextureRecipe(HARPSI_SOUNDBOARD, 256, 512) { p ->
        val w = 256f; val h = 512f
        p.fillRect(0f, 0f, w, h, argb(Pal.HARPSI_SOUNDBOARD, f = 0.7f))
        val flowers = arrayOf(intArrayOf(200, 70, 60), intArrayOf(90, 120, 190), intArrayOf(90, 140, 70))
        val sprays = floatArrayOf(0.25f, 0.12f, 0.70f, 0.20f, 0.35f, 0.45f, 0.78f, 0.62f, 0.30f, 0.80f, 0.62f, 0.90f)
        var i = 0
        while (i < sprays.size) {
            val cx = sprays[i] * w; val cy = sprays[i + 1] * h
            p.strokePath(floatArrayOf(cx - 18f, cy + 16f, cx, cy, cx + 14f, cy - 12f), false, 2f, argb(flowers[2], f = 0.8f))
            for (j in 0 until 5) {
                val a = 2.0 * Math.PI * j / 5
                p.fillCircle((cx + 7f * cos(a)).toFloat(), (cy + 7f * sin(a)).toFloat(), 4f, argb(flowers[(i / 2 + j) % 2]))
            }
            p.fillCircle(cx, cy, 3f, argb(Pal.GILT_LIT))
            i += 2
        }
        val rx = roseU * w; val ry = roseV * h
        val rose = intArrayOf(226, 176, 86)
        p.fillCircle(rx, ry, 30f, argb(rose, f = 0.55f))
        p.fillCircle(rx, ry, 26f, argb(Pal.HARPSI_SOUNDBOARD, f = 0.25f))
        for (j in 0 until 12) {
            val a = 2.0 * Math.PI * j / 12
            p.strokePath(floatArrayOf(rx, ry, (rx + 25f * cos(a)).toFloat(), (ry + 25f * sin(a)).toFloat()), false, 2f, argb(rose))
        }
        p.fillCircle(rx, ry, 6f, argb(rose))
        p.blur(0.8f)
    }
}
