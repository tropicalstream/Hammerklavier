package com.tropicalstream.hammerklavier.render

import com.tropicalstream.hammerklavier.contract.MechanismPose
import com.tropicalstream.hammerklavier.contract.Pal
import com.tropicalstream.hammerklavier.render.gl.GlProgram

/**
 * The A/V sync disc (§1.4, §8.6): a 60 px disc (255,244,214) at the centre of each eye in the
 * frame whose exposure window contains an A4 hammer contact (`pose.flash[69]`). The contact
 * comes from the mechanics' exposure sampler, so the disc shows exactly what the keys show.
 */
class SyncFlash(private val fader: DipFader) {
    @Volatile var enabled = false
    private val r = Pal.FLAME_CORE[0] / 255f
    private val g = Pal.FLAME_CORE[1] / 255f
    private val b = Pal.FLAME_CORE[2] / 255f

    fun lit(pose: MechanismPose): Boolean = enabled && pose.flash[A4]

    /** Eye viewport origin and size in pixels. Returns the draws issued. */
    fun draw(p: GlProgram, pose: MechanismPose, vx: Int, vy: Int, vw: Int, vh: Int): Int {
        if (!lit(pose)) return 0
        fader.quadDraw(p, r, g, b, 1f, vx + vw * 0.5f, vy + vh * 0.5f, RADIUS_PX, 1f)
        return 1
    }

    companion object { const val A4 = 69; const val RADIUS_PX = 30f }
}
