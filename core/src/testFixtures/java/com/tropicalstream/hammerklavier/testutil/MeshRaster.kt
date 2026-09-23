package com.tropicalstream.hammerklavier.testutil

import com.tropicalstream.hammerklavier.contract.BakedMesh
import com.tropicalstream.hammerklavier.contract.VertexLayout
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Rasterises [BakedMesh]es to a PNG for geometry review before integration (PLAN §5.4). A tiny
 * software renderer: perspective camera (y up), z-buffer, back-face culling (so wrong winding
 * shows as holes), Lambert + ambient shading of the vertex colours with the §2.3 pow(c, 0.85)
 * lift, on black (transparent on the waveguide). Skinned meshes draw in their rest pose; ribbons
 * (STATIC, RIBBON program) and strings (STRING layout) draw as 1-px lines along their paths.
 */
object MeshRaster {
    class View(val eye: FloatArray, val target: FloatArray, val vFovDeg: Float = 34f, val up: FloatArray = floatArrayOf(0f, 1f, 0f))

    /** Light direction (towards the light), normalised inside. */
    var light = floatArrayOf(0.35f, 0.8f, -0.45f)

    fun render(meshes: List<BakedMesh>, view: View, width: Int = 640, height: Int = 480, cull: Boolean = true): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val zbuf = FloatArray(width * height) { Float.POSITIVE_INFINITY }
        val cam = Camera(view, width, height)
        val ll = norm(light)
        for (m in meshes) {
            val f = m.layout.floats
            val n = m.vertexCount
            val sx = FloatArray(n); val sy = FloatArray(n); val sz = FloatArray(n)
            val col = IntArray(n)
            val tmp = FloatArray(3)
            val lines = m.layout == VertexLayout.STRING || isRibbon(m)
            for (i in 0 until n) {
                val o = i * f
                cam.project(m.vertices[o], m.vertices[o + 1], m.vertices[o + 2], tmp)
                sx[i] = tmp[0]; sy[i] = tmp[1]; sz[i] = tmp[2]
                val (r, g, b) = when (m.layout) {
                    VertexLayout.STRING -> Triple(m.vertices[o + 13], m.vertices[o + 14], m.vertices[o + 15])
                    else -> Triple(m.vertices[o + 8], m.vertices[o + 9], m.vertices[o + 10])
                }
                val shade = if (lines) 1f else {
                    val d = m.vertices[o + 3] * ll[0] + m.vertices[o + 4] * ll[1] + m.vertices[o + 5] * ll[2]
                    0.28f + 0.72f * max(0f, d)
                }
                col[i] = rgb(lift(r) * shade, lift(g) * shade, lift(b) * shade)
            }
            var t = 0
            val ix = m.indices
            while (t < ix.size) {
                val a = ix[t].toInt() and 0xFFFF; val b = ix[t + 1].toInt() and 0xFFFF; val c = ix[t + 2].toInt() and 0xFFFF
                t += 3
                if (sz[a] <= 0f || sz[b] <= 0f || sz[c] <= 0f) continue
                if (lines) { line(img, zbuf, sx, sy, sz, col, a, b); line(img, zbuf, sx, sy, sz, col, b, c); continue }
                val area = (sx[b] - sx[a]) * (sy[c] - sy[a]) - (sy[b] - sy[a]) * (sx[c] - sx[a])
                // Screen y grows downward, so a CCW-from-the-front triangle has negative screen area.
                if (cull && area >= 0f) continue
                if (area == 0f) continue
                fill(img, zbuf, sx, sy, sz, col, a, b, c, area)
            }
        }
        return img
    }

    fun writePng(meshes: List<BakedMesh>, view: View, file: File, width: Int = 640, height: Int = 480) {
        file.parentFile?.mkdirs()
        ImageIO.write(render(meshes, view, width, height), "png", file)
    }

    /** Fraction of non-black pixels: a cheap "something was drawn" check for tests. */
    fun coverage(img: BufferedImage): Float {
        var lit = 0
        for (y in 0 until img.height) for (x in 0 until img.width) if (img.getRGB(x, y) and 0xFFFFFF != 0) lit++
        return lit.toFloat() / (img.width * img.height)
    }

    private fun isRibbon(m: BakedMesh) = m.program.name == "RIBBON"

    private fun lift(c: Float) = c.coerceIn(0f, 1f).pow(0.85f)

    private fun rgb(r: Float, g: Float, b: Float): Int {
        fun q(c: Float) = (c.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val v = (q(r) shl 16) or (q(g) shl 8) or q(b)
        return if (v == 0) 0x010101 else v
    }

    private fun fill(img: BufferedImage, zb: FloatArray, sx: FloatArray, sy: FloatArray, sz: FloatArray, col: IntArray,
                     a: Int, b: Int, c: Int, area: Float) {
        val w = img.width; val h = img.height
        val x0 = max(0, min(sx[a], min(sx[b], sx[c])).toInt()); val x1 = min(w - 1, max(sx[a], max(sx[b], sx[c])).toInt() + 1)
        val y0 = max(0, min(sy[a], min(sy[b], sy[c])).toInt()); val y1 = min(h - 1, max(sy[a], max(sy[b], sy[c])).toInt() + 1)
        if (x0 > x1 || y0 > y1) return
        val ca = col[a]; val cb = col[b]; val cc = col[c]
        for (y in y0..y1) for (x in x0..x1) {
            val px = x + 0.5f; val py = y + 0.5f
            val w0 = ((sx[b] - px) * (sy[c] - py) - (sy[b] - py) * (sx[c] - px)) / area
            val w1 = ((sx[c] - px) * (sy[a] - py) - (sy[c] - py) * (sx[a] - px)) / area
            val w2 = 1f - w0 - w1
            if (w0 < 0f || w1 < 0f || w2 < 0f) continue
            val z = w0 * sz[a] + w1 * sz[b] + w2 * sz[c]
            val k = y * w + x
            if (z >= zb[k]) continue
            zb[k] = z
            img.setRGB(x, y, mix(ca, cb, cc, w0, w1, w2))
        }
    }

    private fun mix(a: Int, b: Int, c: Int, wa: Float, wb: Float, wc: Float): Int {
        fun ch(s: Int) = (((a shr s) and 255) * wa + ((b shr s) and 255) * wb + ((c shr s) and 255) * wc).toInt().coerceIn(0, 255)
        val v = (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        return if (v == 0) 0x010101 else v
    }

    private fun line(img: BufferedImage, zb: FloatArray, sx: FloatArray, sy: FloatArray, sz: FloatArray, col: IntArray, a: Int, b: Int) {
        val steps = max(1, max(abs(sx[b] - sx[a]), abs(sy[b] - sy[a])).toInt())
        for (s in 0..steps) {
            val t = s.toFloat() / steps
            val x = (sx[a] + (sx[b] - sx[a]) * t).toInt(); val y = (sy[a] + (sy[b] - sy[a]) * t).toInt()
            if (x < 0 || y < 0 || x >= img.width || y >= img.height) continue
            val z = sz[a] + (sz[b] - sz[a]) * t - 1e-4f
            val k = y * img.width + x
            if (z >= zb[k]) continue
            zb[k] = z; img.setRGB(x, y, col[a])
        }
    }

    private fun norm(v: FloatArray): FloatArray { val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); return floatArrayOf(v[0] / l, v[1] / l, v[2] / l) }

    private class Camera(v: View, private val w: Int, private val h: Int) {
        private val e = v.eye
        private val fw: FloatArray; private val rt: FloatArray; private val up: FloatArray
        private val focal: Float
        init {
            fw = norm(floatArrayOf(v.target[0] - e[0], v.target[1] - e[1], v.target[2] - e[2]))
            val r = floatArrayOf(fw[1] * v.up[2] - fw[2] * v.up[1], fw[2] * v.up[0] - fw[0] * v.up[2], fw[0] * v.up[1] - fw[1] * v.up[0])
            rt = norm(r)
            up = floatArrayOf(rt[1] * fw[2] - rt[2] * fw[1], rt[2] * fw[0] - rt[0] * fw[2], rt[0] * fw[1] - rt[1] * fw[0])
            focal = (h / 2f) / tan(Math.toRadians(v.vFovDeg / 2.0)).toFloat()
        }
        /** out = (screen x px, screen y px downward, view depth m; ≤ 0 behind the eye). */
        fun project(x: Float, y: Float, z: Float, out: FloatArray) {
            val dx = x - e[0]; val dy = y - e[1]; val dz = z - e[2]
            val cz = dx * fw[0] + dy * fw[1] + dz * fw[2]
            val cx = dx * rt[0] + dy * rt[1] + dz * rt[2]
            val cy = dx * up[0] + dy * up[1] + dz * up[2]
            out[2] = cz
            if (cz <= 1e-4f) { out[0] = 0f; out[1] = 0f; return }
            out[0] = w / 2f + focal * cx / cz
            out[1] = h / 2f - focal * cy / cz
        }
    }
}
