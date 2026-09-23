package com.tropicalstream.hammerklavier.render

import android.opengl.GLES20
import com.tropicalstream.hammerklavier.render.gl.DynMesh
import com.tropicalstream.hammerklavier.render.gl.GlProgram

/**
 * The dynamic sprite buffer (§5.3 row 6): FlameField writes 8 floats per sprite (xyz size rgb
 * alpha, room frame); [build] expands them once per frame into camera-facing quads with the
 * rig's right/up (shared by both eyes: the eyes are parallel), and [draw] adds them to each eye
 * in one additive draw. GLThread; allocation-free.
 */
class SpriteBatch(val maxSprites: Int) {
    /** FlameField.update's output. */
    val sprites = FloatArray(maxOf(1, maxSprites) * 8)
    private val mesh = DynMesh(maxOf(1, maxSprites) * 6, 9)
    var count = 0; private set
    private var verts = 0

    /** [n] sprites in [sprites]; [right]/[up] unit camera axes; [model] null = room frame as is. */
    fun build(n: Int, right: FloatArray, up: FloatArray) {
        count = n.coerceIn(0, maxSprites)
        val d = mesh.data
        var o = 0
        for (i in 0 until count) {
            val b = i * 8
            val x = sprites[b]; val y = sprites[b + 1]; val z = sprites[b + 2]; val s = sprites[b + 3] * 0.5f
            val r = sprites[b + 4]; val g = sprites[b + 5]; val bl = sprites[b + 6]; val a = sprites[b + 7]
            o = corner(d, o, x, y, z, s, right, up, -1f, -1f, r, g, bl, a)
            o = corner(d, o, x, y, z, s, right, up, 1f, -1f, r, g, bl, a)
            o = corner(d, o, x, y, z, s, right, up, 1f, 1f, r, g, bl, a)
            o = corner(d, o, x, y, z, s, right, up, -1f, -1f, r, g, bl, a)
            o = corner(d, o, x, y, z, s, right, up, 1f, 1f, r, g, bl, a)
            o = corner(d, o, x, y, z, s, right, up, -1f, 1f, r, g, bl, a)
        }
        verts = count * 6
        if (verts > 0) mesh.upload(verts)
    }

    private fun corner(d: FloatArray, o0: Int, x: Float, y: Float, z: Float, s: Float, right: FloatArray, up: FloatArray,
                       cx: Float, cy: Float, r: Float, g: Float, b: Float, a: Float): Int {
        var o = o0
        d[o++] = x + (right[0] * cx + up[0] * cy) * s
        d[o++] = y + (right[1] * cx + up[1] * cy) * s
        d[o++] = z + (right[2] * cx + up[2] * cy) * s
        d[o++] = cx; d[o++] = cy
        d[o++] = r; d[o++] = g; d[o++] = b; d[o++] = a
        return o
    }

    /** One draw per eye (skipped when empty). Returns the draws issued. */
    fun draw(p: GlProgram, viewProj: FloatArray): Int {
        if (verts == 0) return 0
        p.use()
        GLES20.glUniformMatrix4fv(p.uVP, 1, false, viewProj, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        mesh.attrib(p.aPos, 3, 0); mesh.attrib(p.aCorner, 2, 3); mesh.attrib(p.aCol, 4, 5)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
        GLES20.glDepthMask(false)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, verts)
        GLES20.glDepthMask(true)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        if (p.aPos >= 0) GLES20.glDisableVertexAttribArray(p.aPos)
        if (p.aCorner >= 0) GLES20.glDisableVertexAttribArray(p.aCorner)
        if (p.aCol >= 0) GLES20.glDisableVertexAttribArray(p.aCol)
        return 1
    }
}
