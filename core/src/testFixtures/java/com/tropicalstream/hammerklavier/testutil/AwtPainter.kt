package com.tropicalstream.hammerklavier.testutil

import com.tropicalstream.hammerklavier.contract.Painter2D
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Painter2D on java.awt for PNG review of texture recipes in JVM tests (PLAN §2.2, R95). Colours
 * are 0xAARRGGBB ints; [end] returns straight-alpha RGBA8888, rows from the top.
 *
 *     val p = AwtPainter(); recipe.paint(p.also { it.begin(recipe.width, recipe.height) }); p.writePng(File("build/x.png"))
 */
class AwtPainter : Painter2D {
    private var img = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    private var g = img.createGraphics()

    override fun begin(width: Int, height: Int) {
        img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.composite = AlphaComposite.Src
        g.color = Color(0, 0, 0, 0)
        g.fillRect(0, 0, width, height)
        g.composite = AlphaComposite.SrcOver
    }

    private fun color(argb: Int) = Color(argb, true)

    private fun path(xy: FloatArray, closed: Boolean): Path2D.Float {
        val p = Path2D.Float()
        if (xy.size >= 2) p.moveTo(xy[0], xy[1])
        var i = 2
        while (i + 1 < xy.size) { p.lineTo(xy[i], xy[i + 1]); i += 2 }
        if (closed) p.closePath()
        return p
    }

    override fun fillPath(xy: FloatArray, closed: Boolean, rgba: Int) { g.color = color(rgba); g.fill(path(xy, true)) }

    override fun strokePath(xy: FloatArray, closed: Boolean, widthPx: Float, rgba: Int) {
        g.color = color(rgba); g.stroke = BasicStroke(widthPx, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); g.draw(path(xy, closed))
    }

    override fun fillRect(x: Float, y: Float, w: Float, h: Float, rgba: Int) { g.color = color(rgba); g.fill(Rectangle2D.Float(x, y, w, h)) }

    override fun fillCircle(cx: Float, cy: Float, r: Float, rgba: Int) {
        g.color = color(rgba); g.fill(Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r))
    }

    override fun linearGradient(x0: Float, y0: Float, x1: Float, y1: Float, rgba0: Int, rgba1: Int, rect: FloatArray) {
        val old = g.paint
        g.paint = GradientPaint(x0, y0, color(rgba0), x1, y1, color(rgba1))
        g.fill(Rectangle2D.Float(rect[0], rect[1], rect[2], rect[3]))
        g.paint = old
    }

    override fun text(s: String, x: Float, y: Float, sizePx: Float, rgba: Int, serif: Boolean, italic: Boolean, centred: Boolean) {
        g.font = Font(if (serif) Font.SERIF else Font.SANS_SERIF, if (italic) Font.ITALIC else Font.PLAIN, 12).deriveFont(sizePx)
        g.color = color(rgba)
        val w = if (centred) g.fontMetrics.stringWidth(s) else 0
        g.drawString(s, x - w / 2f, y)
    }

    /** Whole-image soft bloom: a separable box blur (3 passes ≈ Gaussian) added over the image. */
    override fun blur(radiusPx: Float) {
        val r = radiusPx.toInt().coerceAtLeast(1)
        val w = img.width; val h = img.height
        val src = img.getRGB(0, 0, w, h, null, 0, w)
        val ch = Array(4) { c -> FloatArray(w * h) { ((src[it] ushr (8 * c)) and 0xFF).toFloat() } }
        val tmp = FloatArray(w * h)
        for (c in 0..3) repeat(3) {
            boxH(ch[c], tmp, w, h, r); boxV(tmp, ch[c], w, h, r)
        }
        val out = IntArray(w * h) { i ->
            var p = 0
            for (c in 0..3) {
                val orig = (src[i] ushr (8 * c)) and 0xFF
                val v = maxOf(orig, (0.5f * orig + 0.5f * ch[c][i]).toInt()).coerceIn(0, 255)
                p = p or (v shl (8 * c))
            }
            p
        }
        img.setRGB(0, 0, w, h, out, 0, w)
    }

    private fun boxH(a: FloatArray, o: FloatArray, w: Int, h: Int, r: Int) {
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var n = 0
            for (d in -r..r) { val xx = x + d; if (xx in 0 until w) { s += a[y * w + xx]; n++ } }
            o[y * w + x] = s / n
        }
    }

    private fun boxV(a: FloatArray, o: FloatArray, w: Int, h: Int, r: Int) {
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var n = 0
            for (d in -r..r) { val yy = y + d; if (yy in 0 until h) { s += a[yy * w + x]; n++ } }
            o[y * w + x] = s / n
        }
    }

    override fun end(): ByteArray {
        val w = img.width; val h = img.height
        val px = img.getRGB(0, 0, w, h, null, 0, w)
        val out = ByteArray(w * h * 4)
        for (i in px.indices) {
            val p = px[i]
            out[4 * i] = (p ushr 16).toByte(); out[4 * i + 1] = (p ushr 8).toByte()
            out[4 * i + 2] = p.toByte(); out[4 * i + 3] = (p ushr 24).toByte()
        }
        return out
    }

    /** Test helper: the current image as a PNG. */
    fun writePng(f: File) { f.parentFile?.mkdirs(); ImageIO.write(img, "png", f) }
}
