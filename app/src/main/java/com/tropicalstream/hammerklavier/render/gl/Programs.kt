package com.tropicalstream.hammerklavier.render.gl

import android.opengl.GLES20
import com.tropicalstream.hammerklavier.contract.ProgramId

/**
 * One linked program with every attribute and uniform location the renderer uses looked up once
 * (−1 where a program lacks it: glUniform* with −1 is a no-op, so draw code stays uniform).
 */
class GlProgram(val name: String, vs: String, fs: String) {
    val id: Int = GlKit.link(vs, fs, name)

    private fun a(n: String) = GLES20.glGetAttribLocation(id, n)
    private fun u(n: String) = GLES20.glGetUniformLocation(id, n)

    // attributes
    val aPos = a("aPos"); val aNrm = a("aNrm"); val aUv = a("aUv"); val aCol = a("aCol")
    val aSlot = a("aSlot"); val aLane = a("aLane"); val aDir = a("aDir"); val aT = a("aT"); val aSide = a("aSide")
    val aRgb = a("aRgb"); val aCorner = a("aCorner")

    // uniforms
    val uVP = u("uVP"); val uModel = u("uModel"); val uEye = u("uEye")
    val uLightPos = u("uLightPos"); val uLightRgb = u("uLightRgb"); val uAmbient = u("uAmbient"); val uSpecExp = u("uSpecExp")
    val uFloor = u("uFloor"); val uUseFloor = u("uUseFloor"); val uFadeC = u("uFadeC"); val uFadeR = u("uFadeR")
    val uBaked = u("uBaked"); val uProbe = u("uProbe"); val uF0 = u("uF0"); val uRim = u("uRim")
    val uState = u("uState"); val uPivot = u("uPivot"); val uP0 = u("uP0"); val uP1 = u("uP1"); val uKind = u("uKind")
    val uShiftX = u("uShiftX"); val uRailM = u("uRailM"); val uClipX = u("uClipX"); val uBevel = u("uBevel")
    val uViewport = u("uViewport"); val uWidthPx = u("uWidthPx"); val uSwellPx = u("uSwellPx"); val uProjY = u("uProjY")
    val uEmissive = u("uEmissive"); val uLight = u("uLight"); val uTex = u("uTex"); val uHasTex = u("uHasTex"); val uTexScale = u("uTexScale"); val uWoodBoost = u("uWoodBoost")
    val uColor = u("uColor"); val uDisc = u("uDisc")

    fun use() = GLES20.glUseProgram(id)
}

/**
 * Every program, compiled in onSurfaceCreated. After a context loss the old ids are simply
 * dropped (the context that owned them is gone; no glDelete, PLAN §5.1) and [compileAll] runs again.
 */
class Programs {
    lateinit var lit: GlProgram; lateinit var lacquer: GlProgram; lateinit var skinned: GlProgram
    lateinit var string: GlProgram; lateinit var ribbon: GlProgram; lateinit var sprite: GlProgram
    lateinit var decal: GlProgram; lateinit var sectionCap: GlProgram; lateinit var fade: GlProgram
    var ready = false; private set

    fun compileAll() {
        lit = GlProgram("lit", Shaders.LIT_VS, Shaders.LIT_FS)
        lacquer = GlProgram("lacquer", Shaders.LIT_VS, Shaders.LACQUER_FS)
        skinned = GlProgram("skinned", Shaders.SKINNED_VS, Shaders.SKINNED_FS)
        string = GlProgram("string", Shaders.STRING_VS, Shaders.STRING_FS)
        ribbon = GlProgram("ribbon", Shaders.RIBBON_VS, Shaders.RIBBON_FS)
        sprite = GlProgram("sprite", Shaders.SPRITE_VS, Shaders.SPRITE_FS)
        decal = GlProgram("decal", Shaders.LIT_VS, Shaders.DECAL_FS)
        sectionCap = GlProgram("sectionCap", Shaders.LIT_VS, Shaders.SECTION_CAP_FS)
        fade = GlProgram("fade", Shaders.FADE_VS, Shaders.FADE_FS)
        ready = true
    }

    /** Forget every id without GL calls (context lost). */
    fun discard() { ready = false }

    fun of(p: ProgramId): GlProgram = when (p) {
        ProgramId.LIT -> lit; ProgramId.LACQUER -> lacquer; ProgramId.SKINNED -> skinned; ProgramId.STRING -> string
        ProgramId.RIBBON -> ribbon; ProgramId.SPRITE -> sprite; ProgramId.DECAL -> decal; ProgramId.SECTION_CAP -> sectionCap
    }
}
