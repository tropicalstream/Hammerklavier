package com.tropicalstream.hammerklavier.render

import android.opengl.GLES20
import com.tropicalstream.hammerklavier.contract.LightRig
import com.tropicalstream.hammerklavier.contract.MaterialTable
import com.tropicalstream.hammerklavier.contract.Pal
import com.tropicalstream.hammerklavier.contract.ProgramId
import com.tropicalstream.hammerklavier.contract.SkinKind
import com.tropicalstream.hammerklavier.contract.SkinParams
import com.tropicalstream.hammerklavier.contract.VertexLayout
import com.tropicalstream.hammerklavier.render.gl.GlProgram
import com.tropicalstream.hammerklavier.render.gl.Programs

/**
 * Per-frame values every draw reads (filled once per frame by StereoRenderer, both eyes share
 * them except [viewProj], [eye] and the viewport).
 */
class FrameUniforms {
    @JvmField val lightPos = FloatArray(12)
    @JvmField val lightRgb = FloatArray(12)
    @JvmField val ambient = floatArrayOf(0.45f, 0.42f, 0.38f)
    @JvmField val floorRgb = FloatArray(3)
    @JvmField val fadeCentre = FloatArray(4)
    @JvmField var stageFade = false
    @JvmField var clipX = Float.NaN
    @JvmField var stringWidthPx = 1.5f
    @JvmField var viewportW = 640f
    @JvmField var viewportH = 480f
    @JvmField var projY = 1f
    @JvmField var probeTex = 0
    @JvmField val eye = FloatArray(3)
    @JvmField var viewProj = FloatArray(16)
    /** Instrument (piano → room) and venue (identity) model matrices. */
    @JvmField val instModel = FloatArray(16)
    @JvmField val venueModel = FloatArray(16)
    /**
     * Bumped by the renderer once per frame ([frameStamp]) and whenever the view-projection or
     * eye changes ([eyeStamp]: each eye, the pedal inset); ItemDrawer re-sends shared uniforms
     * to a program only when its stamp is stale (§5.8 uniform budget).
     */
    @JvmField var frameStamp = 0
    @JvmField var eyeStamp = 0

    fun lights(rig: LightRig) {
        val f = rig.flicker
        for (i in 0 until 12) { lightPos[i] = rig.pos[i]; lightRgb[i] = rig.rgb[i] * f }
    }
}

/**
 * Binds and draws one merged [DrawItem] with its program, material and skin uniforms (PLAN §5.8).
 * GLThread; allocation-free after [setSkin].
 */
class ItemDrawer(private val programs: Programs, private val packer: UniformPacker, private val textures: TextureUploader) {
    private val skinP0 = Array(SkinKind.entries.size) { FloatArray(4) }
    private val skinP1 = Array(SkinKind.entries.size) { FloatArray(4) }
    private val pivots = FloatArray(24)
    private val stringWidths = floatArrayOf(1.5f, 1.2f, 1.0f)
    var trianglesDrawn = 0
    /** glUniform4fv calls issued (the §5.8 budget: ≤ 20 per frame); the renderer resets it per frame. */
    var uniform4fvCalls = 0
    private val progFrame = IntArray(ProgramId.entries.size) { Int.MIN_VALUE }
    private val progEye = IntArray(ProgramId.entries.size) { Int.MIN_VALUE }
    private val progModel = IntArray(ProgramId.entries.size) { -1 }
    /** Per program, a bit per SkinKind whose uState block was sent this frame; bit 31 = uPivot. */
    private val progBlocks = IntArray(ProgramId.entries.size)

    /** The instrument's SkinParams (§5.8), unpacked into per-kind vec4s. Off the hot path. */
    fun setSkin(skin: SkinParams) {
        for (a in skinP0) java.util.Arrays.fill(a, 0f)
        for (a in skinP1) java.util.Arrays.fill(a, 0f)
        java.util.Arrays.fill(pivots, 0f)
        for ((kind, v) in skin.p) {
            when (kind) {
                SkinKind.ACTION_SET -> for (p in 0 until 6) for (j in 0 until 3) if (p * 3 + j < v.size) pivots[p * 4 + j] = v[p * 3 + j]
                SkinKind.STRING -> for (j in 0 until 3) if (j < v.size) stringWidths[j] = v[j]
                SkinKind.LID -> { val d = skinP0[kind.ordinal]; for (j in 0 until 4) if (j < v.size) d[j] = v[j] }
                SkinKind.JACK_LIFT, SkinKind.JACK4_LIFT -> { val d = skinP0[kind.ordinal]; if (v.isNotEmpty()) d[0] = v[0]; if (v.size > 1) d[1] = v[1] }
                SkinKind.DAMPER_LIFT -> {
                    val d = skinP0[kind.ordinal]
                    if (v.isNotEmpty()) d[0] = v[0]
                    // axis normalised here, not per vertex
                    val ax = if (v.size > 1) v[1] else 0f; val ay = if (v.size > 2) v[2] else 1f; val az = if (v.size > 3) v[3] else 0f
                    val l = kotlin.math.sqrt(ax * ax + ay * ay + az * az).let { if (it > 1e-6f) it else 1f }
                    d[1] = ax / l; d[2] = ay / l; d[3] = az / l
                }
                else -> { val d = skinP0[kind.ordinal]; for (j in 0 until 4) if (j < v.size) d[j] = v[j] }
            }
        }
        if (skin.p[SkinKind.DAMPER_LIFT] == null) { skinP0[SkinKind.DAMPER_LIFT.ordinal][2] = 1f }
    }

    /** STRING widths: [cutaway, overhead, hall] px. */
    fun stringWidth(index: Int): Float = stringWidths[index.coerceIn(0, 2)]

    fun draw(item: DrawItem, f: FrameUniforms, gen: Int) {
        val k = item.key
        if (item.glGeneration != gen) item.upload(gen)
        val progId = when (k.layout) {
            VertexLayout.STRING -> ProgramId.STRING
            VertexLayout.SKINNED -> ProgramId.SKINNED
            VertexLayout.STATIC -> if (k.program == ProgramId.SKINNED || k.program == ProgramId.STRING) ProgramId.LIT else k.program
        }
        val p = programs.of(progId)
        val mat = MaterialTable.of(k.material)
        p.use()
        val pi = progId.ordinal
        if (progFrame[pi] != f.frameStamp) {
            progFrame[pi] = f.frameStamp; progBlocks[pi] = 0
            GLES20.glUniform3fv(p.uLightPos, 4, f.lightPos, 0)
            GLES20.glUniform3fv(p.uLightRgb, 4, f.lightRgb, 0)
            GLES20.glUniform3f(p.uAmbient, f.ambient[0], f.ambient[1], f.ambient[2])
            GLES20.glUniform3f(p.uFloor, f.floorRgb[0], f.floorRgb[1], f.floorRgb[2])
            GLES20.glUniform4f(p.uFadeC, f.fadeCentre[0], f.fadeCentre[1], f.fadeCentre[2], 0f)
        }
        if (progEye[pi] != f.eyeStamp) {
            progEye[pi] = f.eyeStamp; progModel[pi] = -1
            GLES20.glUniformMatrix4fv(p.uVP, 1, false, f.viewProj, 0)
            GLES20.glUniform3f(p.uEye, f.eye[0], f.eye[1], f.eye[2])
            GLES20.glUniform1f(p.uProjY, f.projY)
            GLES20.glUniform2f(p.uViewport, f.viewportW, f.viewportH)
        }
        val model = if (k.instrument) 1 else 0
        if (progModel[pi] != model) {
            progModel[pi] = model
            GLES20.glUniformMatrix4fv(p.uModel, 1, false, if (k.instrument) f.instModel else f.venueModel, 0)
        }
        GLES20.glUniform1f(p.uSpecExp, if (mat.specExp > 0f) mat.specExp else 16f)
        GLES20.glUniform1f(p.uUseFloor, if (mat.presenceFloor && k.instrument) 1f else 0f)
        if (f.stageFade && k.fadeFarM > 0f) GLES20.glUniform2f(p.uFadeR, k.fadeNearM, k.fadeFarM) else GLES20.glUniform2f(p.uFadeR, 0f, 0f)
        GLES20.glUniform1f(p.uBaked, if (k.instrument) 0f else 1f)
        GLES20.glUniform1f(p.uClipX, if (k.clipped && !f.clipX.isNaN()) f.clipX else 1e9f)
        GLES20.glUniform1f(p.uEmissive, mat.emissive)
        GLES20.glUniform1f(p.uLight, 1f)
        when (progId) {
            ProgramId.LACQUER -> {
                GLES20.glUniform1f(p.uF0, mat.f0)
                GLES20.glUniform3f(p.uRim, RIM[0], RIM[1], RIM[2])
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, f.probeTex)
                GLES20.glUniform1i(p.uProbe, 0)
            }
            ProgramId.DECAL -> {
                val t = textures.id(k.texture, gen)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
                GLES20.glUniform1i(p.uTex, 0)
                GLES20.glUniform1f(p.uHasTex, if (t != 0) 1f else 0f)
            }
            ProgramId.SKINNED -> {
                val ki = k.skin.ordinal
                sendBlock(p, pi, k.skin)
                if (progBlocks[pi] and PIVOT_BIT == 0) {
                    progBlocks[pi] = progBlocks[pi] or PIVOT_BIT
                    GLES20.glUniform4fv(p.uPivot, 6, pivots, 0); uniform4fvCalls++
                }
                val p0 = skinP0[ki]
                GLES20.glUniform4f(p.uP0, p0[0], p0[1], p0[2], p0[3])
                val engaged = when (k.skin) {
                    SkinKind.JACK_LIFT -> packer.registers and 1
                    SkinKind.JACK4_LIFT -> packer.registers and 2
                    else -> 1
                }
                GLES20.glUniform4f(p.uP1, if (engaged != 0) 0f else 1f, 0f, 0f, 0f)
                GLES20.glUniform1f(p.uKind, ki.toFloat())
                GLES20.glUniform1f(p.uShiftX, shiftFor(k.skin, packer.shiftXM))
                GLES20.glUniform1f(p.uRailM, packer.railM)
                GLES20.glUniform1f(p.uBevel, if (k.skin == SkinKind.KEY_ROT) 1f else 0f)
            }
            ProgramId.STRING -> {
                sendBlock(p, pi, SkinKind.STRING)
                GLES20.glUniform1f(p.uWidthPx, f.stringWidthPx)
                GLES20.glUniform1f(p.uSwellPx, 2.5f)
            }
            ProgramId.SECTION_CAP -> GLES20.glUniform1f(p.uShiftX, if (f.clipX.isNaN()) 0f else f.clipX - CAP_EPS_M)
            else -> {}
        }
        blendFor(progId)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, item.vbo)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, item.ibo)
        bindAttribs(p, k.layout)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, item.indices.size, GLES20.GL_UNSIGNED_SHORT, 0)
        unbindAttribs(p)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        restoreBlend(progId)
        trianglesDrawn += item.triangleCount
    }

    /**
     * uState is one uniform per program: re-send only when this program last held a different
     * block this frame. A single block per program (STRING) goes once a frame, not once per draw.
     */
    private val lastKind = IntArray(ProgramId.entries.size) { -1 }
    private fun sendBlock(p: GlProgram, pi: Int, kind: SkinKind) {
        val bit = 1 shl kind.ordinal
        if (lastKind[pi] == kind.ordinal && (progBlocks[pi] and bit) != 0) return
        lastKind[pi] = kind.ordinal
        progBlocks[pi] = progBlocks[pi] or bit
        GLES20.glUniform4fv(p.uState, UniformPacker.VEC4S, packer.block(kind), 0); uniform4fvCalls++
    }

    private fun blendFor(p: ProgramId) {
        when (p) {
            ProgramId.RIBBON, ProgramId.SPRITE -> { GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE); GLES20.glDepthMask(false) }
            ProgramId.STRING -> GLES20.glDepthMask(false)
            else -> {}
        }
        if (p == ProgramId.RIBBON || p == ProgramId.STRING) GLES20.glDisable(GLES20.GL_CULL_FACE)
    }

    private fun restoreBlend(p: ProgramId) {
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(true)
        if (p == ProgramId.RIBBON || p == ProgramId.STRING) GLES20.glEnable(GLES20.GL_CULL_FACE)
    }

    private fun attr(loc: Int, size: Int, stride: Int, offset: Int) {
        if (loc < 0) return
        GLES20.glVertexAttribPointer(loc, size, GLES20.GL_FLOAT, false, stride, offset)
        GLES20.glEnableVertexAttribArray(loc)
    }

    private fun bindAttribs(p: GlProgram, layout: VertexLayout) {
        val s = layout.floats * 4
        when (layout) {
            VertexLayout.STATIC, VertexLayout.SKINNED -> {
                attr(p.aPos, 3, s, 0); attr(p.aNrm, 3, s, 12); attr(p.aUv, 2, s, 24); attr(p.aCol, 4, s, 32)
                if (layout == VertexLayout.SKINNED) { attr(p.aSlot, 1, s, 48); attr(p.aLane, 4, s, 52) }
                else if (p.aSlot >= 0) { GLES20.glDisableVertexAttribArray(p.aSlot); GLES20.glVertexAttrib1f(p.aSlot, 0f) }
            }
            VertexLayout.STRING -> {
                attr(p.aPos, 3, s, 0); attr(p.aDir, 3, s, 12); attr(p.aT, 1, s, 24); attr(p.aSide, 1, s, 28)
                attr(p.aSlot, 1, s, 32); attr(p.aLane, 4, s, 36); attr(p.aRgb, 3, s, 52)
            }
        }
    }

    private fun unbindAttribs(p: GlProgram) {
        for (loc in intArrayOf0(p)) if (loc >= 0) GLES20.glDisableVertexAttribArray(loc)
    }

    private val locs = IntArray(10)
    private fun intArrayOf0(p: GlProgram): IntArray {
        locs[0] = p.aPos; locs[1] = p.aNrm; locs[2] = p.aUv; locs[3] = p.aCol; locs[4] = p.aSlot
        locs[5] = p.aLane; locs[6] = p.aDir; locs[7] = p.aT; locs[8] = p.aSide; locs[9] = p.aRgb
        return locs
    }

    companion object {
        /** Section caps sit this far on the kept side of the cut plane so the clip never discards them. */
        const val CAP_EPS_M = 0.0005f
        private const val PIVOT_BIT = 1 shl 31

        /**
         * The una corda shift (§5.8) moves only the keys, hammers, the action set and the
         * harpsichord's jacks and tongues; dampers, pedals, the sostenuto, the hammer rail and
         * the lid stay with the case.
         */
        fun shiftFor(kind: SkinKind, shiftXM: Float): Float = when (kind) {
            SkinKind.KEY_ROT, SkinKind.HAMMER_ROT, SkinKind.JACK_LIFT, SkinKind.JACK4_LIFT,
            SkinKind.TONGUE_ROT, SkinKind.TONGUE4_ROT, SkinKind.ACTION_SET -> shiftXM
            else -> 0f
        }

        /** Fresnel rim colour Pal.EBONY_RIM (M8: cool), 0..1 (§5.9). */
        private val RIM = floatArrayOf(Pal.EBONY_RIM[0] / 255f, Pal.EBONY_RIM[1] / 255f, Pal.EBONY_RIM[2] / 255f)
    }
}
