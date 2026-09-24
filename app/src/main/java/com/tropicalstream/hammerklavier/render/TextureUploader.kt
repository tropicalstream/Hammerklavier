package com.tropicalstream.hammerklavier.render

import com.tropicalstream.hammerklavier.contract.Painter2D
import com.tropicalstream.hammerklavier.contract.TextureRecipe
import com.tropicalstream.hammerklavier.render.gl.GlKit

/** One painted texture, its RGBA kept resident on the heap for context loss (PLAN §5.1, §3.17). */
class ResidentTexture(val name: String, val width: Int, val height: Int, val rgba: ByteArray, val repeat: Boolean = false) {
    @JvmField var id = 0
    @JvmField var glGeneration = -1
}

/**
 * TextureRecipes → RGBA (painted on HKLoader through Painter2D: CanvasPainter on the device) →
 * GL textures (uploaded on the GL thread, again after every context loss). The probe
 * (VenueScene.bakeProbe, 128 × 64) is registered under [PROBE].
 */
class TextureUploader {
    private val byName = HashMap<String, ResidentTexture>()

    /** HKLoader. */
    fun paint(recipes: List<TextureRecipe>, painter: Painter2D): List<ResidentTexture> = recipes.distinctBy { it.name }.map { r ->
        val direct = r.rgba
        if (direct != null) ResidentTexture(r.name, r.width, r.height, direct(), r.repeat)
        else {
            painter.begin(r.width, r.height)
            r.paint(painter)
            ResidentTexture(r.name, r.width, r.height, painter.end(), r.repeat)
        }
    }

    /** GLThread: take a newly built set (replaces the previous set's entries). */
    fun install(textures: List<ResidentTexture>) { byName.clear(); for (t in textures) byName[t.name] = t }

    fun add(t: ResidentTexture) { byName[t.name] = t }

    /** GLThread: the texture id for [name] in generation [gen] (0 = none), uploading on first use. */
    fun id(name: String?, gen: Int): Int {
        if (name == null) return 0
        val t = byName[name] ?: return 0
        if (t.glGeneration != gen) { t.id = GlKit.makeTexture(t.width, t.height, t.rgba, t.repeat); t.glGeneration = gen }
        return t.id
    }

    fun uploadAll(gen: Int) { for (t in byName.values) id(t.name, gen) }

    /** Context lost: forget the ids (no glDelete). */
    /** GLThread, context current: delete every texture made in [gen] (older ones died with their context). */
    fun deleteAll(gen: Int) {
        for (t in byName.values) { if (t.glGeneration == gen) GlKit.deleteTexture(t.id); t.glGeneration = -1; t.id = 0 }
    }

    fun discardGl() { for (t in byName.values) { t.glGeneration = -1; t.id = 0 } }

    val residentBytes: Long get() { var b = 0L; for (t in byName.values) b += t.rgba.size; return b }

    companion object { const val PROBE = "venue.probe"; const val PROBE_W = 128; const val PROBE_H = 64 }
}
