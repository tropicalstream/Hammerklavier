package com.tropicalstream.hammerklavier.render

import android.opengl.GLES20
import com.tropicalstream.hammerklavier.render.gl.GlKit
import com.tropicalstream.hammerklavier.render.gl.GlProgram

/**
 * The full-screen fade quad (§5.3 row 22): multiplies the frame toward black (transparent on the
 * waveguide) by drawing black with alpha = the director's fade. Also the program the sync disc
 * uses. GLThread; allocation-free.
 */
class DipFader {
    private val quad = GlKit.floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f))

    /** Returns the draws issued (0 when clear). */
    fun draw(p: GlProgram, fade: Float): Int {
        if (fade <= 0.001f) return 0
        quadDraw(p, 0f, 0f, 0f, fade.coerceAtMost(1f), 0f, 0f, 0f, 0f)
        return 1
    }

    fun quadDraw(p: GlProgram, r: Float, g: Float, b: Float, a: Float, discX: Float, discY: Float, discR: Float, disc: Float) {
        p.use()
        GLES20.glUniform4f(p.uColor, r, g, b, a)
        GLES20.glUniform4f(p.uDisc, discX, discY, discR, disc)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        quad.position(0)
        GLES20.glVertexAttribPointer(p.aPos, 2, GLES20.GL_FLOAT, false, 8, quad)
        GLES20.glEnableVertexAttribArray(p.aPos)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisableVertexAttribArray(p.aPos)
    }
}
