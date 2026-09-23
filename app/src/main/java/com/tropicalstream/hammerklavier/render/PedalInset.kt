package com.tropicalstream.hammerklavier.render

import android.opengl.GLES20
import com.tropicalstream.hammerklavier.contract.Placement
import com.tropicalstream.hammerklavier.geom.StereoRig

/**
 * The pedal inset of the Player follow framing on the grand and upright (§5.3 rows 20–21): the
 * pedals and the floor pool from a fixed low camera, mono, in a 200 × 150 px scissored viewport at
 * the bottom right of each eye. GLThread; allocation-free after [install].
 */
class PedalInset(private val drawer: ItemDrawer) {
    private val rig = StereoRig()
    private val pos = FloatArray(3)
    private val target = FloatArray(3)
    private var items = IntArray(0)
    private val savedVP = FloatArray(16)
    private val savedEye = FloatArray(3)
    var enabled = false; private set

    /** Off the hot path: [hasPedals] false (harpsichord) disables the inset. */
    fun install(scene: AssembledScene, placement: Placement, hasPedals: Boolean) {
        // M3: closer than WP6's (0.30, 0.40, 0.55): at 0.8 m the pedals were ~10 px wide in the 200 px box.
        pos[0] = 0.03f; pos[1] = 0.24f; pos[2] = 0.02f
        target[0] = 0f; target[1] = 0.07f; target[2] = -0.29f
        placement.toRoom(pos, pos); placement.toRoom(target, target)
        rig.update(pos, target, 34f, 0f, 1f, 0f, W.toFloat() / H, 0f, 0f, false)
        val l = ArrayList<Int>()
        for (i in scene.items.indices) {
            val k = scene.items[i].key
            if (k.drawSlot == PEDALS_SLOT || (k.drawSlot == FLOOR_SLOT && !k.instrument)) l.add(i)
        }
        l.sortBy { scene.items[it].key.drawSlot }
        items = l.toIntArray()
        enabled = hasPedals && items.isNotEmpty()
    }

    /** [ex], [ey], [ew] the eye viewport. Returns the draws issued. */
    fun draw(scene: AssembledScene, f: FrameUniforms, gen: Int, ex: Int, ey: Int, ew: Int): Int {
        if (!enabled) return 0
        val x = ex + ew - W - MARGIN; val y = ey + MARGIN
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(x, y, W, H)
        GLES20.glViewport(x, y, W, H)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
        System.arraycopy(f.viewProj, 0, savedVP, 0, 16); System.arraycopy(f.eye, 0, savedEye, 0, 3)
        val vw = f.viewportW; val vh = f.viewportH; val py = f.projY
        val e = rig.eye(0)
        System.arraycopy(e.viewProj, 0, f.viewProj, 0, 16); System.arraycopy(e.pos, 0, f.eye, 0, 3); f.eyeStamp++
        f.viewportW = W.toFloat(); f.viewportH = H.toFloat(); f.projY = e.proj[5]
        var n = 0
        for (i in items) { drawer.draw(scene.items[i], f, gen); n++ }
        System.arraycopy(savedVP, 0, f.viewProj, 0, 16); System.arraycopy(savedEye, 0, f.eye, 0, 3); f.eyeStamp++
        f.viewportW = vw; f.viewportH = vh; f.projY = py
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        return n
    }

    companion object { const val W = 200; const val H = 150; const val MARGIN = 8; const val PEDALS_SLOT = 15; const val FLOOR_SLOT = 1 }
}
