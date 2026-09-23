package com.tropicalstream.hammerklavier.instrument

import com.tropicalstream.hammerklavier.contract.BakedMesh
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.MaterialId
import com.tropicalstream.hammerklavier.contract.Pal
import com.tropicalstream.hammerklavier.contract.ProgramId
import com.tropicalstream.hammerklavier.contract.SkinKind
import com.tropicalstream.hammerklavier.contract.VertexLayout
import com.tropicalstream.hammerklavier.mesh.MeshBuilder
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** One speaking length: from the front termination a to the bridge b (piano frame, m). */
class StringSpan(val key: Int, val a: FloatArray, val b: FloatArray, val wound: Boolean)

/**
 * Strings as STRING-layout spindles (PLAN §5.4, §5.7): one draw, steel and copper by vertex colour,
 * partIndex = key − lowKey (the stringAmp / strikeAge lanes). drawSlot 14.
 */
object StringsMesh {
    /** Spans per string; every string has the same count (tests count strings by it). */
    const val SEGMENTS = 10
    const val VERTS_PER_STRING = 2 * (SEGMENTS + 1)

    fun build(spans: List<StringSpan>, lowKey: Int, name: String, viewMask: Int, clipped: Boolean): List<BakedMesh> {
        val mb = MeshBuilder(VertexLayout.STRING, spans.size * VERTS_PER_STRING)
        for (s in spans) {
            val part = s.key - lowKey
            mb.part(part / 4, part % 4)
            mb.spindle(s.a, s.b, SEGMENTS, if (s.wound) Pal.COPPER else Pal.STEEL_HI)
        }
        return mb.build(name, MaterialId.STEEL, SkinKind.STRING, VM.LEVELS_ALL, viewMask, clipped, ProgramId.STRING, 14)
    }

    /** Ideal piano speaking length (m): 52 mm at C8, ×2^0.078 per semitone down (foreshortened). */
    fun pianoIdeal(key: Int): Float = (0.052 * 2.0.pow(0.078 * (108 - key))).toFloat()

    /** Unison offsets across a key (m). */
    fun unison(n: Int, i: Int, spacing: Float = 0.0035f): Float = (i - (n - 1) / 2f) * spacing

    /**
     * The grand's 228 strings: keys 21–40 overstrung at 18° toward the treble and 25 mm higher,
     * keys 41–108 straight back; each runs from the front termination to the lesser of its ideal
     * length and the rim's inside (minus 60 mm). Keys 21–53 wound. `stringsPerKey` sets the unisons.
     */
    fun grandSpans(kb: Keyboard, profile: InstrumentProfile, innerRim: FloatArray): List<StringSpan> {
        val out = ArrayList<StringSpan>()
        val zf = GrandDims.FRONT_TERM_Z
        for (k in profile.lowKey..profile.highKey) {
            val n = profile.stringsPerKey(k)
            val bass = k <= GrandDims.OVERSTRUNG_TOP
            val ang = if (bass) GrandDims.OVERSTRING_RAD else 0f
            val dx = sin(ang); val dz = -cos(ang)
            val y = if (bass) GrandDims.STRING_Y + 0.025f else GrandDims.STRING_Y
            for (i in 0 until n) {
                val x = kb.keyX[k] + unison(n, i)
                val lim = Geo.rayExit(innerRim, x + dx * 0.05f, zf + dz * 0.05f, dx, dz) + 0.05f - 0.06f
                val len = minOf(pianoIdeal(k), lim)
                out.add(StringSpan(k, floatArrayOf(x, y, zf), floatArrayOf(x + dx * len, y, zf + dz * len), k <= 53))
            }
        }
        return out
    }
}
