package com.tropicalstream.hammerklavier.geom

/**
 * Parallel stereo eyes with off-axis frusta (PLAN §5.2; SpyHunt's perspectiveOffAxis), never
 * toe-in. IPD = 63 mm × the framing's IPD scale × the Stereo depth setting; the frustum shift is
 * eyeShift · near / zeroParallax, so a point at the zero-parallax distance lands on the same NDC x
 * in both eyes. Frame axes: right = forward × worldUp, then up = right × forward, in that order.
 * World units are metres; near 0.05 m, far 30 m. GLThread; allocation-free after construction.
 */
class StereoRig {
    class Eye {
        @JvmField val view = FloatArray(16)
        @JvmField val proj = FloatArray(16)
        @JvmField val viewProj = FloatArray(16)
        @JvmField val pos = FloatArray(3)
    }

    private val eyes = arrayOf(Eye(), Eye())
    /** Unit axes of the (gaze-adjusted) camera, shared by both eyes. */
    @JvmField val right = FloatArray(3)
    @JvmField val up = FloatArray(3)
    @JvmField val forward = FloatArray(3)
    /** The midpoint between the eyes. */
    @JvmField val centre = FloatArray(3)
    @JvmField var ipdM = 0f
    @JvmField var eyeCount = 2
    private val tmp = FloatArray(3)

    fun eye(e: Int): Eye = eyes[e]

    /**
     * @param pos camera position, @param target look-at point (same frame)
     * @param aspect one eye's viewport width / height
     * @param gazeYaw radians, positive = looking right; @param gazePitch positive = looking up
     * @param stereo false = one centred eye (mono screenshots, emulator)
     */
    fun update(pos: FloatArray, target: FloatArray, vFovDeg: Float, ipdScale: Float, zeroParallaxM: Float,
               stereoDepth: Float, aspect: Float, gazeYaw: Float, gazePitch: Float, stereo: Boolean) {
        val f = forward
        f[0] = target[0] - pos[0]; f[1] = target[1] - pos[1]; f[2] = target[2] - pos[2]
        if (Mat4.normalize(f) < 1e-9f) { f[0] = 0f; f[1] = 0f; f[2] = -1f }
        if (gazeYaw != 0f || gazePitch != 0f) {
            axes(f)
            val cy = SinTable.cos(gazeYaw); val sy = SinTable.sin(gazeYaw)
            val cp = SinTable.cos(gazePitch); val sp = SinTable.sin(gazePitch)
            for (i in 0 until 3) tmp[i] = f[i] * cy * cp + right[i] * sy * cp + up[i] * sp
            f[0] = tmp[0]; f[1] = tmp[1]; f[2] = tmp[2]
            Mat4.normalize(f)
        }
        axes(f)
        centre[0] = pos[0]; centre[1] = pos[1]; centre[2] = pos[2]
        eyeCount = if (stereo) 2 else 1
        ipdM = if (stereo) IPD_M * ipdScale * stereoDepth else 0f
        val top = NEAR * SinTable.tan(vFovDeg * 0.5f * SinTable.DEG)
        val halfW = top * aspect
        val zp = if (zeroParallaxM > NEAR) zeroParallaxM else NEAR * 2f
        for (e in 0 until eyeCount) {
            val off = if (!stereo) 0f else if (e == 0) -ipdM * 0.5f else ipdM * 0.5f
            val ey = eyes[e]
            for (i in 0 until 3) ey.pos[i] = pos[i] + right[i] * off
            Mat4.view(ey.view, ey.pos, right, up, f)
            val shift = -off * NEAR / zp
            Mat4.frustum(ey.proj, -halfW + shift, halfW + shift, -top, top, NEAR, FAR)
            Mat4.multiply(ey.viewProj, ey.proj, ey.view)
        }
    }

    private fun axes(f: FloatArray) {
        // right = forward × worldUp (worldUp = +y): (f.y·0 − f.z·1, f.z·0 − f.x·0, f.x·1 − f.y·0)
        right[0] = -f[2]; right[1] = 0f; right[2] = f[0]
        if (Mat4.normalize(right) < 1e-6f) { right[0] = 1f; right[1] = 0f; right[2] = 0f }
        Mat4.cross(right, f, up)
        Mat4.normalize(up)
    }

    companion object {
        const val IPD_M = 0.063f
        const val NEAR = 0.05f
        const val FAR = 30f
    }
}
