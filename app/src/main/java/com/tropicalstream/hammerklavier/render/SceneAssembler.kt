package com.tropicalstream.hammerklavier.render

import com.tropicalstream.hammerklavier.contract.BakedMesh
import com.tropicalstream.hammerklavier.contract.MaterialId
import com.tropicalstream.hammerklavier.contract.ProgramId
import com.tropicalstream.hammerklavier.contract.RoomLevel
import com.tropicalstream.hammerklavier.contract.SkinKind
import com.tropicalstream.hammerklavier.contract.VertexLayout
import com.tropicalstream.hammerklavier.contract.ViewId
import com.tropicalstream.hammerklavier.render.gl.GlKit

/**
 * The §5.3 merge key: meshes with equal (program, material, skin, texture, levelMask, viewMask,
 * clipped, drawSlot) become one draw. The layout and the space (instrument = piano frame, drawn
 * through the Placement; venue = room frame) and the distance fade are implied by these in
 * practice but kept in the key so a merge can never mix vertex formats or model matrices.
 */
data class MergeKey(val program: ProgramId, val material: MaterialId, val skin: SkinKind, val texture: String?,
    val levelMask: Int, val viewMask: Int, val clipped: Boolean, val drawSlot: Int,
    val layout: VertexLayout, val instrument: Boolean, val fadeNearM: Float, val fadeFarM: Float)

/**
 * One draw: merged vertices and indices (resident on the heap for context loss, PLAN §5.1) and
 * their GL handles for the current GL generation.
 */
class DrawItem(val key: MergeKey, val vertices: FloatArray, val indices: ShortArray, val names: List<String>) {
    val vertexCount: Int get() = vertices.size / key.layout.floats
    val triangleCount: Int get() = indices.size / 3
    @JvmField var vbo = 0
    @JvmField var ibo = 0
    @JvmField var glGeneration = -1

    fun shownIn(viewCode: Int, level: Int): Boolean =
        (key.viewMask and (1 shl viewCode)) != 0 && (key.levelMask and (1 shl level)) != 0

    /** GLThread: (re)upload for [gen] if needed. */
    fun upload(gen: Int) {
        if (glGeneration == gen) return
        vbo = GlKit.makeVbo(vertices); ibo = GlKit.makeIbo(indices); glGeneration = gen
    }
}

/**
 * A scene ready to draw: the merged items and, per (view·2 + framing, venue level), the item
 * indices sorted by drawSlot (stable).
 */
class AssembledScene(val items: Array<DrawItem>, private val lists: Array<IntArray>) {
    fun list(viewCode: Int, level: Int): IntArray = lists[viewCode * LEVELS + level]
    fun list(view: ViewId, framing: Int, level: RoomLevel): IntArray = list(view.ordinal * 2 + framing, level.ordinal)

    fun triangles(viewCode: Int, level: Int): Int { var t = 0; for (i in list(viewCode, level)) t += items[i].triangleCount; return t }

    /** Every item's resident bytes (the §3.17 budget line). */
    val residentBytes: Long get() { var b = 0L; for (it in items) b += it.vertices.size * 4L + it.indices.size * 2L; return b }

    /** Context lost: forget the handles (no glDelete). */
    fun discardGl() { for (it in items) { it.glGeneration = -1; it.vbo = 0; it.ibo = 0 } }

    fun upload(gen: Int) { for (it in items) it.upload(gen) }

    /** GLThread, context current: delete the handles made in [gen]; older ones died with their context. */
    fun deleteGl(gen: Int) {
        for (it in items) {
            if (it.glGeneration == gen) { GlKit.deleteBuffer(it.vbo); GlKit.deleteBuffer(it.ibo) }
            it.glGeneration = -1; it.vbo = 0; it.ibo = 0
        }
    }

    companion object { const val LEVELS = 4; const val VIEW_CODES = 6 }
}

/**
 * Turns the BakedMesh lists of the current InstrumentScene and VenueScene into merged draw items
 * and the per-(view, framing, level) draw lists (PLAN §5.3). Runs on HKLoader (allocates freely);
 * the GL thread only uploads. A merge that would pass 65,535 vertices starts a new item.
 */
object SceneAssembler {
    const val MAX_VERTS = 65_535

    /**
     * Draws per eye outside the mesh list: the sprite batch (flames) when the level shows any,
     * up to 3 glyph labels, the pedal inset's 2 draws in Player follow, the sync disc, the fade quad.
     */
    fun overheadDraws(viewCode: Int, level: Int, labels: Int, syncDisc: Boolean): Int {
        var n = 1                                                    // fade quad
        if (level != RoomLevel.PASSTHROUGH.ordinal) n += 1           // sprites
        n += labels.coerceIn(0, 3)
        if (viewCode == ViewId.PLAYER.ordinal * 2 + 1) n += 2        // pedal inset
        if (syncDisc) n += 1
        return n
    }

    fun assemble(instrument: List<BakedMesh>, venue: List<BakedMesh>): AssembledScene {
        val groups = LinkedHashMap<MergeKey, MutableList<BakedMesh>>()
        fun add(m: BakedMesh, inst: Boolean) {
            if (m.vertices.isEmpty() || m.indices.isEmpty()) return
            val k = MergeKey(m.program, m.material, m.skin, m.texture, m.levelMask, m.viewMask, m.clipped, m.drawSlot,
                m.layout, inst, m.fadeNearM, m.fadeFarM)
            groups.getOrPut(k) { ArrayList() }.add(m)
        }
        for (m in venue) add(m, false)
        for (m in instrument) add(m, true)
        val items = ArrayList<DrawItem>()
        for ((k, ms) in groups) {
            var chunk = ArrayList<BakedMesh>(); var verts = 0
            for (m in ms) {
                if (verts + m.vertexCount > MAX_VERTS && chunk.isNotEmpty()) { items.add(merge(k, chunk)); chunk = ArrayList(); verts = 0 }
                chunk.add(m); verts += m.vertexCount
            }
            if (chunk.isNotEmpty()) items.add(merge(k, chunk))
        }
        val arr = items.toTypedArray()
        val lists = Array(AssembledScene.VIEW_CODES * AssembledScene.LEVELS) { i ->
            val vc = i / AssembledScene.LEVELS; val lv = i % AssembledScene.LEVELS
            arr.indices.filter { arr[it].shownIn(vc, lv) }.sortedWith(compareBy({ arr[it].key.drawSlot }, { it })).toIntArray()
        }
        return AssembledScene(arr, lists)
    }

    private fun merge(k: MergeKey, ms: List<BakedMesh>): DrawItem {
        val f = k.layout.floats
        var nv = 0; var ni = 0
        for (m in ms) { nv += m.vertexCount; ni += m.indices.size }
        val v = FloatArray(nv * f); val ix = ShortArray(ni)
        var vo = 0; var io = 0; var base = 0
        for (m in ms) {
            System.arraycopy(m.vertices, 0, v, vo * f, m.vertexCount * f)
            for (i in m.indices) ix[io++] = ((i.toInt() and 0xFFFF) + base).toShort()
            vo += m.vertexCount; base += m.vertexCount
        }
        return DrawItem(k, v, ix, ms.map { it.name })
    }
}
