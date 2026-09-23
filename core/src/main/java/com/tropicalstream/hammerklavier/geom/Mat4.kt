package com.tropicalstream.hammerklavier.geom

import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Column-major 4×4 matrix helpers on plain FloatArrays (the android.opengl.Matrix layout:
 * element (row r, column c) at index c * 4 + r). Pure, allocation-free, no transcendental maths:
 * angles go through [SinTable], prepared once when this object loads (PLAN §2.1 rule 5).
 */
object Mat4 {
    fun identity(m: FloatArray) {
        for (i in 0 until 16) m[i] = 0f
        m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
    }

    fun copy(src: FloatArray, dst: FloatArray) { System.arraycopy(src, 0, dst, 0, 16) }

    /** out = a · b. out must not alias a or b. */
    fun multiply(out: FloatArray, a: FloatArray, b: FloatArray) {
        for (c in 0 until 4) {
            val b0 = b[c * 4]; val b1 = b[c * 4 + 1]; val b2 = b[c * 4 + 2]; val b3 = b[c * 4 + 3]
            for (r in 0 until 4) out[c * 4 + r] = a[r] * b0 + a[4 + r] * b1 + a[8 + r] * b2 + a[12 + r] * b3
        }
    }

    /**
     * The view matrix of an eye at [eye] with orthonormal axes right, up and forward (the camera
     * looks along +forward; GL's camera space looks along −z).
     */
    fun view(out: FloatArray, eye: FloatArray, right: FloatArray, up: FloatArray, fwd: FloatArray) {
        out[0] = right[0]; out[4] = right[1]; out[8] = right[2]
        out[1] = up[0]; out[5] = up[1]; out[9] = up[2]
        out[2] = -fwd[0]; out[6] = -fwd[1]; out[10] = -fwd[2]
        out[3] = 0f; out[7] = 0f; out[11] = 0f
        out[12] = -(right[0] * eye[0] + right[1] * eye[1] + right[2] * eye[2])
        out[13] = -(up[0] * eye[0] + up[1] * eye[1] + up[2] * eye[2])
        out[14] = fwd[0] * eye[0] + fwd[1] * eye[1] + fwd[2] * eye[2]
        out[15] = 1f
    }

    /** glFrustum: an asymmetric (off-axis) perspective projection. */
    fun frustum(out: FloatArray, l: Float, r: Float, b: Float, t: Float, n: Float, f: Float) {
        for (i in 0 until 16) out[i] = 0f
        out[0] = 2f * n / (r - l)
        out[5] = 2f * n / (t - b)
        out[8] = (r + l) / (r - l)
        out[9] = (t + b) / (t - b)
        out[10] = -(f + n) / (f - n)
        out[11] = -1f
        out[14] = -2f * f * n / (f - n)
    }

    /** Rigid transform out = T(origin) · R_y(yaw), with R_y as in Placement.toRoom. cos/sin given. */
    fun rigidY(out: FloatArray, origin: FloatArray, c: Float, s: Float) {
        identity(out)
        // column 0 = image of x: (c, 0, −s); column 2 = image of z: (s, 0, c)
        out[0] = c; out[2] = -s
        out[8] = s; out[10] = c
        out[12] = origin[0]; out[13] = origin[1]; out[14] = origin[2]
    }

    /** Inverse of [rigidY]: R_y(−yaw) · T(−origin). */
    fun rigidYInverse(out: FloatArray, origin: FloatArray, c: Float, s: Float) {
        identity(out)
        out[0] = c; out[2] = s
        out[8] = -s; out[10] = c
        out[12] = -(c * origin[0] - s * origin[2])
        out[13] = -origin[1]
        out[14] = -(s * origin[0] + c * origin[2])
    }

    /** out[0..3] = m · (x, y, z, 1). */
    fun transform(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray) {
        out[0] = m[0] * x + m[4] * y + m[8] * z + m[12]
        out[1] = m[1] * x + m[5] * y + m[9] * z + m[13]
        out[2] = m[2] * x + m[6] * y + m[10] * z + m[14]
        out[3] = m[3] * x + m[7] * y + m[11] * z + m[15]
    }

    /** out[0..2] = the affine part of m applied to p (w = 1). out may alias p. */
    fun transformPoint(m: FloatArray, p: FloatArray, out: FloatArray) {
        val x = p[0]; val y = p[1]; val z = p[2]
        out[0] = m[0] * x + m[4] * y + m[8] * z + m[12]
        out[1] = m[1] * x + m[5] * y + m[9] * z + m[13]
        out[2] = m[2] * x + m[6] * y + m[10] * z + m[14]
    }

    fun cross(a: FloatArray, b: FloatArray, out: FloatArray) {
        val x = a[1] * b[2] - a[2] * b[1]
        val y = a[2] * b[0] - a[0] * b[2]
        val z = a[0] * b[1] - a[1] * b[0]
        out[0] = x; out[1] = y; out[2] = z
    }

    fun dot(a: FloatArray, b: FloatArray): Float = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    /** Normalises v in place; returns its former length (0 leaves v unchanged). */
    fun normalize(v: FloatArray): Float {
        val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (l > 1e-12f) { v[0] /= l; v[1] /= l; v[2] /= l }
        return l
    }
}

/**
 * A 4096-entry sine table over one turn with linear interpolation (error < 3e-7): the renderer's
 * only source of sin, cos and tan (PLAN §2.1 rule 5). Built once, on first use, off the hot path.
 */
object SinTable {
    private const val N = 4096
    private const val TWO_PI = 6.2831855f
    private val t = FloatArray(N + 1) { i -> sin(i * (2.0 * Math.PI) / N).toFloat() }

    fun sin(rad: Float): Float {
        var x = rad / TWO_PI
        x -= kotlin.math.floor(x)
        val f = x * N
        val i = f.toInt().coerceIn(0, N - 1)
        val fr = f - i
        return t[i] + (t[i + 1] - t[i]) * fr
    }

    fun cos(rad: Float): Float = sin(rad + 1.5707964f)

    fun tan(rad: Float): Float = sin(rad) / cos(rad)

    const val DEG = 0.017453292f
}
