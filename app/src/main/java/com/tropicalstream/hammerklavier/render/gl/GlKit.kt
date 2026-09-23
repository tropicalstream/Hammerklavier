package com.tropicalstream.hammerklavier.render.gl

import android.opengl.GLES20
import android.util.Log
import com.tropicalstream.hammerklavier.contract.HK
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * GL helpers (after MathCosmos's StereoMathRenderer): VBO/IBO upload, a dynamic client-side
 * mesh, shader compilation. GLThread only, inside onSurfaceCreated/onDrawFrame (PLAN §2.1 rule 6).
 */
object GlKit {
    /**
     * Fragment precision header from MathCosmos: highp wherever the GPU offers it (world-space
     * lighting maths turns to static in fp16), mediump otherwise.
     */
    const val FRAG_PRECISION = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
"""

    /**
     * JVM tests only: android.jar's stubs return 0 for every GL query, so compile/link status
     * checks are skipped when this is set (the renderer's frame logic then runs headless).
     */
    @Volatile var skipStatusChecks = false

    fun floatBuffer(data: FloatArray, count: Int = data.size): FloatBuffer {
        val b = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        b.put(data, 0, count); b.position(0); return b
    }

    fun shortBuffer(data: ShortArray, count: Int = data.size): ShortBuffer {
        val b = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        b.put(data, 0, count); b.position(0); return b
    }

    /** Uploads static vertex data once; draws then bind the VBO instead of copying a client array. */
    fun makeVbo(data: FloatArray, count: Int = data.size): Int {
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, count * 4, floatBuffer(data, count), GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        return ids[0]
    }

    /** Index buffer; the shorts are read as unsigned by GL_UNSIGNED_SHORT. */
    fun makeIbo(data: ShortArray, count: Int = data.size): Int {
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ids[0])
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, count * 2, shortBuffer(data, count), GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        return ids[0]
    }

    /** RGBA8888 texture, linear, clamped, no mipmaps (NPOT-safe in ES 2.0). */
    fun makeTexture(width: Int, height: Int, rgba: ByteArray): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val buf = ByteBuffer.allocateDirect(rgba.size).order(ByteOrder.nativeOrder())
        buf.put(rgba); buf.position(0)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    fun compile(type: Int, src: String, name: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, src)
        GLES20.glCompileShader(id)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] != GLES20.GL_TRUE && !skipStatusChecks) {
            val log = GLES20.glGetShaderInfoLog(id)
            GLES20.glDeleteShader(id)
            throw IllegalStateException("shader $name: $log")
        }
        return id
    }

    fun link(vs: String, fs: String, name: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs, "$name.vert")
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs, "$name.frag")
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        GLES20.glDeleteShader(v); GLES20.glDeleteShader(f)
        if (ok[0] != GLES20.GL_TRUE && !skipStatusChecks) throw IllegalStateException("program $name: ${GLES20.glGetProgramInfoLog(p)}")
        return p
    }

    /** Logs and counts GL errors (debug checks, T-GLRESET "no GL errors"). */
    fun checkError(where: String): Int {
        var n = 0
        while (true) {
            val e = GLES20.glGetError()
            if (e == GLES20.GL_NO_ERROR || n > 8) return n
            n++
            Log.e(HK.TAG_RENDER, "GL error 0x" + Integer.toHexString(e) + " at " + where)
        }
    }
}

/**
 * A small per-frame vertex stream in a preallocated direct buffer (MathCosmos's DynMesh,
 * generalised to any float stride). Allocation-free after construction.
 */
class DynMesh(maxVerts: Int, val floatsPerVertex: Int) {
    val data = FloatArray(maxVerts * floatsPerVertex)
    val capacity = maxVerts
    private val buffer: FloatBuffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** Copies the first [verts] vertices to the direct buffer; returns it positioned at 0. */
    fun upload(verts: Int): FloatBuffer {
        buffer.position(0); buffer.put(data, 0, verts * floatsPerVertex); buffer.position(0)
        return buffer
    }

    /** Points [attrib] at component [offsetFloats] of the uploaded stream. */
    fun attrib(attrib: Int, size: Int, offsetFloats: Int) {
        if (attrib < 0) return
        buffer.position(offsetFloats)
        GLES20.glVertexAttribPointer(attrib, size, GLES20.GL_FLOAT, false, floatsPerVertex * 4, buffer)
        GLES20.glEnableVertexAttribArray(attrib)
        buffer.position(0)
    }
}
