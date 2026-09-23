package com.tropicalstream.hammerklavier.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Mathematical notation in the 3D world.
 *
 * InnerCosmos never needed this: an alveolus looks like an alveolus. A derivative does not look
 * like anything, and the one thing a maths tour cannot do without is the ability to hang
 * `dy/dx`, `∫`, `x²` or `∇·F` in the air beside the object it describes.
 *
 * There is no text rendering in GL ES 2.0, so a label is drawn once with an Android [Canvas] into
 * a bitmap, uploaded as a texture, and cached by its string. Drawing one afterwards is a single
 * textured quad — cheap enough to hang a dozen in a scene without touching the frame budget.
 *
 * Labels are drawn WHITE into the texture and tinted at draw time, so the same string costs one
 * texture however many colours it is shown in. They are additively blended: black is transparent
 * on the waveguides, so a glyph glows rather than sitting on a card.
 *
 * The little markup the layout understands is the part of TeX people actually type:
 *   `x^2`  `e^{-x}`  `a_1`  `v_{max}`   — one character, or a braced group, raised or lowered.
 * Everything else is literal, so `∫`, `Σ`, `√`, `π`, `θ`, `Δ`, `∂`, `∇`, `≈`, `→` all just work.
 *
 * All GL work must happen on the GL thread; [label] is therefore only safe from inside a draw.
 *
 * Hammerklavier copy (PLAN §2.2, §5.10): the label cap is lowered from 96 to 32, [release] drops
 * the cache without GL calls (the context that owned the textures is gone, §5.1), and the hot
 * path allocates nothing after warm-up (per-style caches keyed by the caller's string, no
 * capturing local functions; §2.1 rule 1).
 */
class GlyphBoard {

    /** How a label is set: the maths italic of a variable, or the upright of a name or a number. */
    enum class Style { MATH, PLAIN, SMALL, TITLE }

    class Label(val texture: Int, val aspect: Float, val text: String)

    private val caches = Array(Style.entries.size) { LinkedHashMap<String, Label>(16, 0.75f, true) }
    private var count = 0
    private var k = 0
    private var budget = 0

    // One quad, rewritten per draw: position(3) + uv(2).
    private val quad = FloatArray(6 * 5)
    private val quadBuf = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val model = FloatArray(16)
    private val mv = FloatArray(16)
    private val mvp = FloatArray(16)

    private var shaderRef: LabelShader? = null
    private val shader: LabelShader get() = shaderRef ?: LabelShader().also { shaderRef = it }

    /**
     * The label for [text], rendered on first use and cached. Returns null once the per-frame
     * budget is spent, so a runaway scene degrades by dropping labels rather than by stuttering.
     */
    fun label(text: String, style: Style = Style.MATH): Label? {
        val cache = caches[style.ordinal]
        val hit = cache[text]
        if (hit != null) return hit
        if (budget >= MAX_NEW_PER_FRAME) return null
        budget++
        val made = render(text, style) ?: return null
        if (count >= MAX_LABELS) evictOne()
        cache[text] = made
        count++
        return made
    }

    private fun evictOne() {
        for (c in caches) {
            val it = c.entries.iterator()
            if (it.hasNext()) {
                val e = it.next()
                GLES20.glDeleteTextures(1, intArrayOf(e.value.texture), 0)
                it.remove(); count--
                return
            }
        }
    }

    /** Called once per frame by the renderer: lets a few new labels be rasterised, no more. */
    fun beginFrame() { budget = 0 }

    /**
     * Hang [text] at a world point, facing the camera. [height] is the glyph height in world
     * units; the quad widens to the string's aspect. [anchor] shifts it sideways in units of its
     * own width (0 = centred, -0.5 = left edge on the point, +0.5 = right edge).
     */
    fun drawBillboard(
        text: String, style: Style,
        x: Float, y: Float, z: Float, height: Float,
        rightX: Float, rightY: Float, rightZ: Float,
        upX: Float, upY: Float, upZ: Float,
        tint: FloatArray, alpha: Float, glow: Float = 1f,
        anchor: Float = 0f, rise: Float = 0f,
        view: FloatArray, projection: FloatArray
    ) {
        if (alpha <= 0.01f) return
        val lab = label(text, style) ?: return
        val hw = height * lab.aspect * 0.5f
        val hh = height * 0.5f
        val ox = -anchor * hw * 2f
        val oy = rise * height
        val cx = x + rightX * ox + upX * oy
        val cy = y + rightY * ox + upY * oy
        val cz = z + rightZ * ox + upZ * oy
        k = 0
        putB(cx, cy, cz, rightX, rightY, rightZ, upX, upY, upZ, -hw, -hh, 0f, 1f)
        putB(cx, cy, cz, rightX, rightY, rightZ, upX, upY, upZ, hw, -hh, 1f, 1f)
        putB(cx, cy, cz, rightX, rightY, rightZ, upX, upY, upZ, hw, hh, 1f, 0f)
        putB(cx, cy, cz, rightX, rightY, rightZ, upX, upY, upZ, -hw, -hh, 0f, 1f)
        putB(cx, cy, cz, rightX, rightY, rightZ, upX, upY, upZ, hw, hh, 1f, 0f)
        putB(cx, cy, cz, rightX, rightY, rightZ, upX, upY, upZ, -hw, hh, 0f, 0f)
        Matrix.setIdentityM(model, 0)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        blit(lab, tint, alpha, glow)
    }

    /**
     * Hang [text] on a plane the caller orients — used for axis labels and tick numbers, which
     * should lie in the plane of the graph rather than swivel to face the viewer.
     */
    fun drawOriented(
        text: String, style: Style, modelMatrix: FloatArray, height: Float,
        tint: FloatArray, alpha: Float, glow: Float = 1f, anchor: Float = 0f,
        view: FloatArray, projection: FloatArray
    ) {
        if (alpha <= 0.01f) return
        val lab = label(text, style) ?: return
        val hw = height * lab.aspect * 0.5f
        val hh = height * 0.5f
        val ox = -anchor * hw * 2f
        k = 0
        putO(ox, -hw, -hh, 0f, 1f); putO(ox, hw, -hh, 1f, 1f); putO(ox, hw, hh, 1f, 0f)
        putO(ox, -hw, -hh, 0f, 1f); putO(ox, hw, hh, 1f, 0f); putO(ox, -hw, hh, 0f, 0f)
        Matrix.multiplyMM(mv, 0, view, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        blit(lab, tint, alpha, glow)
    }

    private fun putB(cx: Float, cy: Float, cz: Float, rx: Float, ry: Float, rz: Float, ux: Float, uy: Float, uz: Float,
                     sx: Float, sy: Float, u: Float, v: Float) {
        quad[k++] = cx + rx * sx + ux * sy
        quad[k++] = cy + ry * sx + uy * sy
        quad[k++] = cz + rz * sx + uz * sy
        quad[k++] = u; quad[k++] = v
    }

    private fun putO(ox: Float, sx: Float, sy: Float, u: Float, v: Float) {
        quad[k++] = sx + ox; quad[k++] = sy; quad[k++] = 0f
        quad[k++] = u; quad[k++] = v
    }

    private fun blit(lab: Label, tint: FloatArray, alpha: Float, glow: Float) {
        quadBuf.position(0); quadBuf.put(quad); quadBuf.position(0)
        // Additive: a glyph is light in the air, not a card with a background. Depth is tested so
        // notation can sit behind geometry, but never written, so labels never occlude each other.
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GLES20.glDepthMask(false)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        shader.use(mvp, lab.texture, tint, alpha, glow)
        quadBuf.position(0)
        GLES20.glVertexAttribPointer(shader.positionHandle, 3, GLES20.GL_FLOAT, false, 20, quadBuf)
        GLES20.glEnableVertexAttribArray(shader.positionHandle)
        quadBuf.position(3)
        GLES20.glVertexAttribPointer(shader.uvHandle, 2, GLES20.GL_FLOAT, false, 20, quadBuf)
        GLES20.glEnableVertexAttribArray(shader.uvHandle)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        GLES20.glDisableVertexAttribArray(shader.positionHandle)
        GLES20.glDisableVertexAttribArray(shader.uvHandle)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glDepthMask(true)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    // ------------------------------------------------------------ rasterising

    /** One run of the laid-out string: its text, its size relative to the base, its baseline shift. */
    private class Run(val text: String, val scale: Float, val dy: Float, val italic: Boolean)

    /**
     * Split `e^{-x}` / `v_{max}` / `x^2` into runs. A caret or underscore takes the next single
     * character, or the next braced group; nothing nests, which is all the notation here needs.
     */
    private fun layout(text: String, baseItalic: Boolean): List<Run> {
        val runs = ArrayList<Run>(4)
        val sb = StringBuilder()
        var i = 0
        fun flush() { if (sb.isNotEmpty()) { runs.add(Run(sb.toString(), 1f, 0f, baseItalic)); sb.setLength(0) } }
        while (i < text.length) {
            val c = text[i]
            if ((c == '^' || c == '_') && i + 1 < text.length) {
                flush()
                val up = c == '^'
                i++
                val piece: String
                if (text[i] == '{') {
                    val end = text.indexOf('}', i)
                    if (end < 0) { piece = text.substring(i + 1); i = text.length }
                    else { piece = text.substring(i + 1, end); i = end + 1 }
                } else { piece = text[i].toString(); i++ }
                runs.add(Run(piece, 0.62f, if (up) -0.42f else 0.22f, baseItalic))
            } else { sb.append(c); i++ }
        }
        flush()
        return runs
    }

    private fun render(text: String, style: Style): Label? {
        if (text.isEmpty()) return null
        val basePx = when (style) { Style.TITLE -> 96f; Style.MATH, Style.PLAIN -> 72f; Style.SMALL -> 44f }
        val italic = style == Style.MATH
        val runs = layout(text, italic)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.LEFT
        }
        fun configure(r: Run) {
            paint.textSize = basePx * r.scale
            paint.typeface = when {
                style == Style.TITLE -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                r.italic -> Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                else -> Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            }
        }
        var width = 0f
        for (r in runs) { configure(r); width += paint.measureText(r.text) }
        if (width <= 0f) return null
        // Room for the glow, and for a raised exponent or a dropped subscript.
        val pad = basePx * 0.34f
        val w = Math.ceil((width + pad * 2f).toDouble()).toInt().coerceIn(8, 2048)
        val h = Math.ceil((basePx * 1.7f + pad).toDouble()).toInt().coerceIn(8, 1024)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val baseline = h * 0.68f
        // Two passes: a soft bloom, then the glyph itself. The waveguides lose thin strokes, so
        // the bloom is what actually makes small notation readable on the glasses.
        for (pass in 0..1) {
            var x = pad
            for (r in runs) {
                configure(r)
                if (pass == 0) {
                    paint.setShadowLayer(basePx * 0.18f, 0f, 0f, Color.WHITE)
                    paint.alpha = 150
                } else {
                    paint.clearShadowLayer()
                    paint.alpha = 255
                }
                canvas.drawText(r.text, x, baseline + r.dy * basePx, paint)
                x += paint.measureText(r.text)
            }
        }
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        if (ids[0] == 0) { bmp.recycle(); return null }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        // NPOT textures are legal in ES 2.0 only without mipmaps and clamped, which is exactly this.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        bmp.recycle()
        return Label(ids[0], w.toFloat() / h.toFloat(), text)
    }

    /**
     * Drop every cached label WITHOUT GL calls: the GL context that owned the textures is gone
     * (context loss, PLAN §5.1); the labels are re-rasterised on demand. The shader is rebuilt too.
     */
    fun release() {
        for (c in caches) c.clear()
        count = 0
        shaderRef = null
    }

    /** Delete every cached texture while the context is current (a deliberate flush, not a loss). */
    fun deleteAll() {
        for (c in caches) {
            if (c.isEmpty()) continue
            val ids = IntArray(c.size); var i = 0
            for (l in c.values) ids[i++] = l.texture
            GLES20.glDeleteTextures(ids.size, ids, 0)
            c.clear()
        }
        count = 0
    }

    private companion object {
        const val MAX_LABELS = 32
        /** Rasterising is the only expensive part; spread a scene's first frame over several. */
        const val MAX_NEW_PER_FRAME = 3
    }
}

/** Tints a white-on-transparent glyph texture and adds it to the frame. */
private class LabelShader {
    private val program = compileLabelProgram(
        """
        attribute vec3 aPosition;
        attribute vec2 aUv;
        uniform mat4 uMvp;
        varying vec2 vUv;
        void main() {
            vUv = aUv;
            gl_Position = uMvp * vec4(aPosition, 1.0);
        }
        """,
        """
        precision mediump float;
        uniform sampler2D uTex;
        uniform vec4 uTint;
        uniform float uAlpha;
        uniform float uGlow;
        varying vec2 vUv;
        void main() {
            float a = texture2D(uTex, vUv).a;
            gl_FragColor = vec4(uTint.rgb * uGlow, 1.0) * a * uAlpha;
        }
        """
    )
    val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
    val uvHandle = GLES20.glGetAttribLocation(program, "aUv")
    private val mvpHandle = GLES20.glGetUniformLocation(program, "uMvp")
    private val texHandle = GLES20.glGetUniformLocation(program, "uTex")
    private val tintHandle = GLES20.glGetUniformLocation(program, "uTint")
    private val alphaHandle = GLES20.glGetUniformLocation(program, "uAlpha")
    private val glowHandle = GLES20.glGetUniformLocation(program, "uGlow")

    fun use(mvp: FloatArray, texture: Int, tint: FloatArray, alpha: Float, glow: Float) {
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(texHandle, 0)
        GLES20.glUniform4f(tintHandle, tint[0], tint[1], tint[2], 1f)
        GLES20.glUniform1f(alphaHandle, alpha)
        GLES20.glUniform1f(glowHandle, glow)
    }
}

private fun compileLabelProgram(vertexSource: String, fragmentSource: String): Int {
    fun shader(type: Int, src: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, src)
        GLES20.glCompileShader(id)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
        require(ok[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(id) }
        return id
    }
    val v = shader(GLES20.GL_VERTEX_SHADER, vertexSource)
    val f = shader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
    val p = GLES20.glCreateProgram()
    GLES20.glAttachShader(p, v)
    GLES20.glAttachShader(p, f)
    GLES20.glLinkProgram(p)
    val ok = IntArray(1)
    GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
    require(ok[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(p) }
    GLES20.glDeleteShader(v)
    GLES20.glDeleteShader(f)
    return p
}
