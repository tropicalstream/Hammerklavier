package com.tropicalstream.hammerklavier.instrument.tex

import com.tropicalstream.hammerklavier.contract.TextureRecipe
import kotlin.math.abs
import kotlin.math.sin

/**
 * The one shared wood tile (M8 user feedback: "the wood outside still looks washed"): a pixel-exact
 * port of TapGem's `PanelTextures.wood()` — the dark walnut the user likes on these glasses.
 * Dark walnut base #2B1A10, light grain #4A2E1A, dark streaks #170D07; long grain along x (u) with a
 * slow wander, tighter rings, fine noise and a knot modulation. 256 × 256, tileable, drawn with
 * GL_REPEAT at [TILE_M] metres per tile.
 *
 * Unlike every other recipe these texels are the FINAL on-glass colour (TapGem shows them as is),
 * so the LIT shader's wood path un-lifts them (pow(t, 1/0.85)) before its pow(c, 0.85) lift, and a
 * wood surface's vertex colour is a tint around [NEUTRAL] (= × 1.0 in the shader).
 */
object Wood {
    const val NAME = "hk.wood"
    const val SIZE = 256
    /** Metres per tile (the shader's uv scale is 1 / TILE_M). */
    const val TILE_M = 0.6f

    /** Vertex tint that the shader maps to × 1.0 (it multiplies wood tints by 255 / 204). */
    @JvmField val NEUTRAL = intArrayOf(204, 204, 204)
    /**
     * Instrument cases: TapGem walnut at 96 % (196 / 204). A case fills most of the Player view, and at 100 % the
     * upright's Player screencap measured APL 9.09 % (budget ≤ 9 %); at 96 % it is ≈ 8.7 %.
     */
    @JvmField val WALNUT = intArrayOf(196, 196, 196)
    /** Upright mahogany finish: the same grain, redder (same 96 % level). */
    @JvmField val MAHOGANY = intArrayOf(223, 177, 163)
    /** Harpsichord case, lid and stand. */
    @JvmField val CASE = WALNUT

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)

    /** 0xAARRGGBB, row-major, row 0 first — the same numbers TapGem's Bitmap holds. */
    fun pixels(): IntArray {
        val n = SIZE
        val px = IntArray(n * n)
        val base = floatArrayOf(0x2B / 255f, 0x1A / 255f, 0x10 / 255f)     // #2B1A10
        val light = floatArrayOf(0x4A / 255f, 0x2E / 255f, 0x1A / 255f)    // #4A2E1A
        val dark = floatArrayOf(0x17 / 255f, 0x0D / 255f, 0x07 / 255f)     // #170D07
        var seed = 0x9E3779B9.toInt()
        fun rnd(): Float { seed = seed * 1664525 + 1013904223; return ((seed ushr 8) and 0xFFFF) / 65535f }
        val phase = FloatArray(n) { rnd() * 6.283f }
        for (y in 0 until n) {
            val ty = y * 6.283f / n
            for (x in 0 until n) {
                val tx = x * 6.283f / n
                val wander = 0.6f * sin(tx) + 0.3f * sin(2f * tx + phase[y] * 0.05f)
                var g = sin(ty * 9f + wander) * 0.5f + sin(ty * 23f + wander * 1.7f) * 0.25f + sin(ty * 47f + 0.5f * sin(tx * 3f)) * 0.12f
                g += (rnd() - 0.5f) * 0.10f
                val knot = 1f - (abs(sin(ty * 2f + sin(tx * 1.5f) * 2f)) * 0.35f)
                g = (g * knot).coerceIn(-1f, 1f)
                val t = (g + 1f) / 2f
                val r = if (t < 0.5f) lerp(dark[0], base[0], t * 2f) else lerp(base[0], light[0], (t - 0.5f) * 2f)
                val gg = if (t < 0.5f) lerp(dark[1], base[1], t * 2f) else lerp(base[1], light[1], (t - 0.5f) * 2f)
                val b = if (t < 0.5f) lerp(dark[2], base[2], t * 2f) else lerp(base[2], light[2], (t - 0.5f) * 2f)
                px[y * n + x] = (0xFF shl 24) or ((r * 255).toInt() shl 16) or ((gg * 255).toInt() shl 8) or (b * 255).toInt()
            }
        }
        return px
    }

    fun rgba(): ByteArray {
        val px = pixels()
        val out = ByteArray(px.size * 4)
        for (i in px.indices) {
            val c = px[i]
            out[4 * i] = (c shr 16).toByte(); out[4 * i + 1] = (c shr 8).toByte(); out[4 * i + 2] = c.toByte(); out[4 * i + 3] = 0xFF.toByte()
        }
        return out
    }

    /**
     * Uploaded from [rgba] directly (Canvas anti-aliasing would soften the grain), repeat-wrapped; [TextureRecipe.paint]
     * draws the same texels as 1-px rects for Painter2D consumers (the AWT review tests).
     */
    fun recipe(): TextureRecipe = TextureRecipe(NAME, SIZE, SIZE, repeat = true, rgba = { rgba() }) { p ->
        val px = pixels()
        for (y in 0 until SIZE) for (x in 0 until SIZE) p.fillRect(x.toFloat(), y.toFloat(), 1f, 1f, px[y * SIZE + x])
    }
}
