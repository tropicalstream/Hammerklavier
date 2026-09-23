package com.tropicalstream.hammerklavier.contract.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Shader
import android.graphics.Typeface
import com.tropicalstream.hammerklavier.contract.Painter2D

/**
 * Painter2D on android.graphics (Bitmap + Canvas), for TextureRecipes on HKLoader. Colours are
 * 0xAARRGGBB ints; [end] returns straight-alpha RGBA8888, rows from the top (the byte layout
 * glTexImage2D(GL_RGBA, GL_UNSIGNED_BYTE) expects). One painter per thread.
 */
class CanvasPainter : Painter2D {
    private var bmp: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private var canvas = Canvas(bmp)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun begin(width: Int, height: Int) {
        bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.SRC)
    }

    private fun build(xy: FloatArray, closed: Boolean): Path {
        path.reset()
        if (xy.size >= 2) path.moveTo(xy[0], xy[1])
        var i = 2
        while (i + 1 < xy.size) { path.lineTo(xy[i], xy[i + 1]); i += 2 }
        if (closed) path.close()
        return path
    }

    private fun fill(argb: Int): Paint = paint.apply { reset(); isAntiAlias = true; style = Paint.Style.FILL; color = argb; shader = null }

    override fun fillPath(xy: FloatArray, closed: Boolean, rgba: Int) { canvas.drawPath(build(xy, true), fill(rgba)) }

    override fun strokePath(xy: FloatArray, closed: Boolean, widthPx: Float, rgba: Int) {
        val p = fill(rgba).apply { style = Paint.Style.STROKE; strokeWidth = widthPx; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        canvas.drawPath(build(xy, closed), p)
    }

    override fun fillRect(x: Float, y: Float, w: Float, h: Float, rgba: Int) { canvas.drawRect(x, y, x + w, y + h, fill(rgba)) }

    override fun fillCircle(cx: Float, cy: Float, r: Float, rgba: Int) { canvas.drawCircle(cx, cy, r, fill(rgba)) }

    override fun linearGradient(x0: Float, y0: Float, x1: Float, y1: Float, rgba0: Int, rgba1: Int, rect: FloatArray) {
        val p = fill(0xFFFFFFFF.toInt()).apply { shader = LinearGradient(x0, y0, x1, y1, rgba0, rgba1, Shader.TileMode.CLAMP) }
        canvas.drawRect(rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3], p)
    }

    override fun text(s: String, x: Float, y: Float, sizePx: Float, rgba: Int, serif: Boolean, italic: Boolean, centred: Boolean) {
        val p = fill(rgba).apply {
            textSize = sizePx
            typeface = Typeface.create(if (serif) Typeface.SERIF else Typeface.SANS_SERIF, if (italic) Typeface.ITALIC else Typeface.NORMAL)
            textAlign = if (centred) Paint.Align.CENTER else Paint.Align.LEFT
        }
        canvas.drawText(s, x, y, p)
    }

    /** Whole-image soft bloom: a 3-pass separable box blur blended over the image (lighten). */
    override fun blur(radiusPx: Float) {
        val r = radiusPx.toInt().coerceAtLeast(1)
        val w = bmp.width; val h = bmp.height
        val src = IntArray(w * h)
        bmp.getPixels(src, 0, w, 0, 0, w, h)
        val ch = Array(4) { c -> FloatArray(w * h) { ((src[it] ushr (8 * c)) and 0xFF).toFloat() } }
        val tmp = FloatArray(w * h)
        for (c in 0..3) repeat(3) { box(ch[c], tmp, w, h, r, horizontal = true); box(tmp, ch[c], w, h, r, horizontal = false) }
        for (i in src.indices) {
            var p = 0
            for (c in 0..3) {
                val orig = (src[i] ushr (8 * c)) and 0xFF
                val v = maxOf(orig, (0.5f * orig + 0.5f * ch[c][i]).toInt()).coerceIn(0, 255)
                p = p or (v shl (8 * c))
            }
            src[i] = p
        }
        bmp.setPixels(src, 0, w, 0, 0, w, h)
    }

    private fun box(a: FloatArray, o: FloatArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var n = 0
            for (d in -r..r) {
                val xx = if (horizontal) x + d else x; val yy = if (horizontal) y else y + d
                if (xx in 0 until w && yy in 0 until h) { s += a[yy * w + xx]; n++ }
            }
            o[y * w + x] = s / n
        }
    }

    override fun end(): ByteArray {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)          // unpremultiplied ARGB
        val out = ByteArray(w * h * 4)
        for (i in px.indices) {
            val p = px[i]
            out[4 * i] = (p ushr 16).toByte(); out[4 * i + 1] = (p ushr 8).toByte()
            out[4 * i + 2] = p.toByte(); out[4 * i + 3] = (p ushr 24).toByte()
        }
        return out
    }
}
