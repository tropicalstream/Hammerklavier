package com.tropicalstream.hammerklavier.instrument

import com.tropicalstream.hammerklavier.contract.BakedMesh
import com.tropicalstream.hammerklavier.contract.InstrumentAnchors
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentLook
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.InstrumentScene
import com.tropicalstream.hammerklavier.contract.MechanismPose
import com.tropicalstream.hammerklavier.contract.SkinParams
import com.tropicalstream.hammerklavier.contract.TextureRecipe

/**
 * The instrument factory (PLAN §2.3 row `instrument.Instruments`). Pure; call on HKLoader. The
 * returned scene builds its meshes on each [InstrumentScene.meshes] call (HKLoader) and packs the
 * action set allocation-free on the GL thread.
 */
object Instruments {
    fun create(id: InstrumentId, look: InstrumentLook, lastDamper: Int): InstrumentScene = when (id) {
        InstrumentId.GRAND -> GrandModel.create(look, lastDamper)
        InstrumentId.UPRIGHT -> UprightModel.create(look, lastDamper)
        InstrumentId.HARPSICHORD -> HarpsichordModel.create(look)
    }
}

/** A built instrument: tables, skin parameters, mesh and texture recipes, and its action-set packer. */
class InstrumentSceneImpl internal constructor(
    override val id: InstrumentId,
    val keyboard: Keyboard,
    override val skin: SkinParams,
    private val buildMeshes: () -> List<BakedMesh>,
    private val buildTextures: () -> List<TextureRecipe>,
    private val packer: ActionSetPacker) : InstrumentScene {
    override val anchors: InstrumentAnchors = Anchors(id, keyboard)
    override fun meshes(): List<BakedMesh> = buildMeshes()
    override fun textures(): List<TextureRecipe> = buildTextures()
    override fun packActionSet(pose: MechanismPose, xCutKey: Float, out: FloatArray) = packer.pack(pose, xCutKey, out)
}

/**
 * The ACTION_SET block (PLAN §5.8), 136 floats = 34 vec4: vec4 s (0–12) = (xM = keyX of the slot's
 * key, dim 1 active / 0.4 inactive, key, 0); floats 52 + 6·s + p = part p's value for slot s
 * (vec4 13 + (6s + p)/4, lane (6s + p) % 4); vec4 33 = [spare] (the harpsichord's register
 * offsets). The 13 slots are the keys round(xCutKey) − 6 … + 6, the window clamped inside the
 * compass. A NaN xCutKey (no cutaway) packs zeros, so dim 0 hides every slot. Allocation-free.
 */
abstract class ActionSetPacker(val profile: InstrumentProfile, private val keyX: FloatArray) {
    fun pack(pose: MechanismPose, xCutKey: Float, out: FloatArray) {
        java.util.Arrays.fill(out, 0, BLOCK_FLOATS, 0f)
        if (xCutKey.isNaN()) return
        val kc = Math.round(xCutKey).coerceIn(profile.lowKey + HALF, profile.highKey - HALF)
        for (s in 0 until SLOTS) {
            val k = kc - HALF + s
            val active = pose.keyDip[k] > EPS || pose.hammer[k] > EPS || pose.damper[k] > EPS || pose.jack4[k] > EPS
            out[4 * s] = keyX[k]; out[4 * s + 1] = if (active) 1f else DIM; out[4 * s + 2] = k.toFloat(); out[4 * s + 3] = 0f
            parts(pose, k, out, ANGLES + PARTS * s)
        }
        spare(pose, out, SPARE)
    }

    /** Writes the 6 part values of key [k] at out[o] … out[o + 5]. */
    protected abstract fun parts(pose: MechanismPose, k: Int, out: FloatArray, o: Int)
    protected open fun spare(pose: MechanismPose, out: FloatArray, o: Int) {}

    companion object {
        const val SLOTS = 13; const val HALF = 6; const val PARTS = 6
        const val BLOCK_FLOATS = 136
        const val ANGLES = 52              // vec4 13
        const val SPARE = 132              // vec4 33
        const val DIM = 0.4f
        private const val EPS = 1e-3f

        /** The skin slot (vec4 index) and lane addressing part [p] of action slot [s]. */
        fun slotOf(s: Int, p: Int): Int = 13 + (PARTS * s + p) / 4
        fun laneOf(s: Int, p: Int): Int = (PARTS * s + p) % 4
    }
}
