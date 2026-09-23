package com.tropicalstream.hammerklavier.venue.tex

import com.tropicalstream.hammerklavier.contract.Painter2D
import com.tropicalstream.hammerklavier.contract.Pal
import com.tropicalstream.hammerklavier.contract.TextureRecipe

/**
 * The venue's texture recipes (PLAN §5.4: zero image assets), painted through [Painter2D] on
 * HKLoader.
 *
 * - [ATLAS] ("venue.atlas", 512 × 512): a 4 × 4 grid of 128-px cells for the DECAL program. Row 0:
 *   the four cove cartouches (hound, hare, stag, putto with horn); row 1: the corner crest, the
 *   mirror rocaille crest, a trellis tile, the ceiling rosette. Relief in GILT_LIT on transparent
 *   black, outlines in GILT_SHADE.
 * - [PARQUET] ("venue.parquet", 256 × 256): one 1 m oak panel square with diagonal fillets, a
 *   near-white modulation texture (the vertex colour carries PARQUET_POOL and the baked pool) that
 *   repeats with world-metre UVs.
 *
 * Texture coordinates: u to the right, v = 0 at the painted image's top row (the first row
 * [Painter2D.end] returns).
 */
object Atlas {
    const val ATLAS = "venue.atlas"
    const val PARQUET = "venue.parquet"
    const val ATLAS_SIZE = 512
    const val CELL = 128
    const val PARQUET_SIZE = 256

    const val HOUND = 0; const val HARE = 1; const val STAG = 2; const val PUTTO = 3
    const val CORNER_CREST = 4; const val MIRROR_CREST = 5; const val TRELLIS = 6; const val ROSETTE = 7
    const val CELLS_USED = 8

    /** (u0, v0, u1, v1) of a cell, inset by half a texel against bleeding. */
    fun cellUv(cell: Int): FloatArray {
        val cx = cell % 4; val cy = cell / 4
        val inset = 0.5f / ATLAS_SIZE
        val s = CELL.toFloat() / ATLAS_SIZE
        return floatArrayOf(cx * s + inset, cy * s + inset, (cx + 1) * s - inset, (cy + 1) * s - inset)
    }

    fun argb(rgb: IntArray, a: Int = 255): Int = (a shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]

    fun recipes(): List<TextureRecipe> = listOf(
        TextureRecipe(ATLAS, ATLAS_SIZE, ATLAS_SIZE) { p -> paintAtlas(p) },
        TextureRecipe(PARQUET, PARQUET_SIZE, PARQUET_SIZE) { p -> paintParquet(p) })

    private val GILT = argb(Pal.GILT_LIT)
    private val HI = argb(Pal.GILT_HI)
    private val SHADE = argb(Pal.GILT_SHADE)

    fun paintAtlas(p: Painter2D) {
        for (c in 0 until CELLS_USED) {
            val ox = (c % 4) * CELL.toFloat(); val oy = (c / 4) * CELL.toFloat()
            when (c) {
                HOUND, HARE, STAG, PUTTO -> { cartoucheFrame(p, ox, oy); motif(p, c, ox, oy) }
                CORNER_CREST -> crest(p, ox, oy, 1.0f)
                MIRROR_CREST -> crest(p, ox, oy, 0.8f)
                TRELLIS -> trellis(p, ox, oy)
                ROSETTE -> rosette(p, ox, oy)
            }
        }
    }

    /** A rocaille shield: an oval with C-scroll ears, filled dim and outlined in gilt. */
    private fun cartoucheFrame(p: Painter2D, ox: Float, oy: Float) {
        val cx = ox + 64f; val cy = oy + 66f
        val oval = FloatArray(2 * 40)
        for (i in 0 until 40) {
            val a = 2.0 * Math.PI * i / 40
            val wob = 1.0 + 0.06 * Math.cos(6 * a)
            oval[2 * i] = (cx + 44 * wob * Math.cos(a)).toFloat(); oval[2 * i + 1] = (cy + 36 * wob * Math.sin(a)).toFloat()
        }
        p.fillPath(oval, true, argb(Pal.GILT_EMISSIVE, 200))
        p.strokePath(oval, true, 5f, GILT)
        p.strokePath(oval, true, 1.5f, HI)
        for (s in intArrayOf(-1, 1)) {
            val scroll = FloatArray(2 * 12)
            for (i in 0 until 12) {
                val a = Math.PI * 1.6 * i / 11
                val r = 12.0 - 0.7 * i
                scroll[2 * i] = (cx + s * (50 + r * Math.cos(a))).toFloat(); scroll[2 * i + 1] = (oy + 18 + r * Math.sin(a)).toFloat()
            }
            p.strokePath(scroll, false, 3f, GILT)
            val low = FloatArray(scroll.size) { if (it % 2 == 0) scroll[it] else 2 * cy - scroll[it] + 10 }
            p.strokePath(low, false, 3f, GILT)
        }
    }

    private fun poly(p: Painter2D, ox: Float, oy: Float, pts: FloatArray, rgb: Int) {
        val xy = FloatArray(pts.size) { if (it % 2 == 0) ox + pts[it] else oy + pts[it] }
        p.fillPath(xy, true, rgb)
        p.strokePath(xy, true, 1.2f, SHADE)
    }

    /** Silhouettes in 128-px cell coordinates. */
    private fun motif(p: Painter2D, c: Int, ox: Float, oy: Float) {
        when (c) {
            HOUND -> poly(p, ox, oy, floatArrayOf(34f, 74f, 44f, 62f, 70f, 60f, 84f, 50f, 92f, 52f, 90f, 60f, 84f, 64f, 86f, 80f,
                80f, 80f, 76f, 68f, 52f, 70f, 46f, 82f, 40f, 82f, 42f, 70f), GILT)
            HARE -> poly(p, ox, oy, floatArrayOf(40f, 78f, 48f, 64f, 64f, 60f, 76f, 56f, 78f, 38f, 82f, 38f, 82f, 54f, 88f, 60f,
                84f, 66f, 76f, 70f, 80f, 80f, 70f, 80f, 60f, 74f, 50f, 80f), GILT)
            STAG -> {
                poly(p, ox, oy, floatArrayOf(36f, 82f, 40f, 66f, 72f, 64f, 80f, 54f, 88f, 56f, 84f, 66f, 80f, 82f, 74f, 82f,
                    72f, 72f, 46f, 72f, 42f, 82f), GILT)
                p.strokePath(floatArrayOf(ox + 82f, oy + 54f, ox + 78f, oy + 38f, ox + 70f, oy + 32f), false, 2.5f, HI)
                p.strokePath(floatArrayOf(ox + 86f, oy + 54f, ox + 92f, oy + 38f, ox + 100f, oy + 34f), false, 2.5f, HI)
                p.strokePath(floatArrayOf(ox + 79f, oy + 44f, ox + 72f, oy + 42f), false, 2f, HI)
                p.strokePath(floatArrayOf(ox + 90f, oy + 44f, ox + 97f, oy + 43f), false, 2f, HI)
            }
            PUTTO -> {
                p.fillCircle(ox + 58f, oy + 48f, 9f, GILT)
                poly(p, ox, oy, floatArrayOf(48f, 58f, 68f, 58f, 72f, 76f, 64f, 88f, 52f, 88f, 44f, 76f), GILT)
                p.strokePath(floatArrayOf(ox + 68f, oy + 64f, ox + 80f, oy + 60f, ox + 88f, oy + 52f), false, 3f, HI)
                p.fillCircle(ox + 90f, oy + 50f, 5f, HI)                        // horn bell
            }
        }
    }

    /** Symmetric rocaille crest: a shell fan over two C-scrolls. */
    private fun crest(p: Painter2D, ox: Float, oy: Float, scale: Float) {
        val cx = ox + 64f; val base = oy + 104f
        for (i in 0..8) {
            val a = Math.PI * (0.1 + 0.8 * i / 8.0)
            val r = 54.0 * scale
            p.strokePath(floatArrayOf(cx, base, (cx - r * Math.cos(a)).toFloat(), (base - r * Math.sin(a)).toFloat()), false, 4f,
                if (i % 2 == 0) GILT else HI)
        }
        val rim = FloatArray(2 * 17)
        for (i in 0..16) {
            val a = Math.PI * i / 16
            val r = 56.0 * scale + 4 * Math.cos(8 * a)
            rim[2 * i] = (cx - r * Math.cos(a)).toFloat(); rim[2 * i + 1] = (base - r * Math.sin(a)).toFloat()
        }
        p.strokePath(rim, false, 4f, GILT)
        for (s in intArrayOf(-1, 1)) {
            val sc = FloatArray(2 * 10)
            for (i in 0 until 10) {
                val a = Math.PI * 1.5 * i / 9
                val r = 14.0 - i
                sc[2 * i] = (cx + s * (40 + r * Math.cos(a))).toFloat(); sc[2 * i + 1] = (base + 6 - r * Math.sin(a)).toFloat()
            }
            p.strokePath(sc, false, 3f, GILT)
        }
    }

    private fun trellis(p: Painter2D, ox: Float, oy: Float) {
        val m = 6f; val e = CELL - 2 * m                      // stay 6 px inside the cell (no bleeding)
        val x0 = ox + m; val y0 = oy + m
        for (k in 0..4) {
            val t = k * e / 4f
            p.strokePath(floatArrayOf(x0 + t, y0, x0 + e, y0 + e - t), false, 3f, GILT)
            p.strokePath(floatArrayOf(x0, y0 + t, x0 + e - t, y0 + e), false, 3f, GILT)
            p.strokePath(floatArrayOf(x0 + e - t, y0, x0, y0 + e - t), false, 3f, GILT)
            p.strokePath(floatArrayOf(x0 + e, y0 + t, x0 + t, y0 + e), false, 3f, GILT)
        }
        for (i in 0..3) for (j in 0..3) p.fillCircle(x0 + e / 8f + e / 4f * i, y0 + e / 8f + e / 4f * j, 4f, HI)
    }

    private fun rosette(p: Painter2D, ox: Float, oy: Float) {
        val cx = ox + 64f; val cy = oy + 64f
        for (i in 0 until 16) {
            val a = 2.0 * Math.PI * i / 16
            val leaf = floatArrayOf(cx, cy,
                (cx + 30 * Math.cos(a - 0.18)).toFloat(), (cy + 30 * Math.sin(a - 0.18)).toFloat(),
                (cx + 58 * Math.cos(a)).toFloat(), (cy + 58 * Math.sin(a)).toFloat(),
                (cx + 30 * Math.cos(a + 0.18)).toFloat(), (cy + 30 * Math.sin(a + 0.18)).toFloat())
            p.fillPath(leaf, true, GILT)
            p.strokePath(leaf, true, 1.2f, SHADE)
        }
        p.fillCircle(cx, cy, 12f, HI)
    }

    /** One 1 m panel: frame boards, diagonal fillets, grain lines. Near white so the vertex colour rules. */
    fun paintParquet(p: Painter2D) {
        val s = PARQUET_SIZE.toFloat()
        p.fillRect(0f, 0f, s, s, 0xFFE8DCCC.toInt())
        val dark = 0xFF9C8A74.toInt(); val mid = 0xFFC8B8A2.toInt()
        val b = s * 0.08f
        p.strokePath(floatArrayOf(b, b, s - b, b, s - b, s - b, b, s - b), true, 2f, dark)
        p.strokePath(floatArrayOf(1f, 1f, s - 1, 1f, s - 1, s - 1, 1f, s - 1), true, 2f, dark)
        p.strokePath(floatArrayOf(b, b, s - b, s - b), false, 2f, dark)
        p.strokePath(floatArrayOf(s - b, b, b, s - b), false, 2f, dark)
        var k = 1
        while (k < 12) {
            val t = k * s / 12f
            p.strokePath(floatArrayOf(b, t, s - b, t), false, 0.8f, mid)
            k += 1
        }
    }
}
