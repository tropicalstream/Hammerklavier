package com.tropicalstream.hammerklavier.instrument

import com.tropicalstream.hammerklavier.contract.BakedMesh
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.MaterialId
import com.tropicalstream.hammerklavier.contract.ProgramId
import com.tropicalstream.hammerklavier.contract.SkinKind
import com.tropicalstream.hammerklavier.contract.VertexLayout
import com.tropicalstream.hammerklavier.mesh.MeshBuilder
import kotlin.math.sqrt

/**
 * Dimensions of one keyboard (PLAN §5.4 "Keyboard (all)"). Lengths in metres; the piano frame puts
 * the key fronts at z = 0 with the heads centred on x = 0.
 */
class KeyboardSpec(
    val lowKey: Int, val highKey: Int,
    /** Natural head width: 23.5 mm (pianos), 22.7 mm (harpsichord). The back slot pitch is 7/12 of it. */
    val headM: Float,
    val topY: Float,
    val naturalLenM: Float = 0.148f, val sharpLenM: Float = 0.092f, val sharpHM: Float = 0.012f,
    val sharpTopWM: Float = 0.0105f, val naturalThickM: Float = 0.020f,
    /** Drawn beyond the visible length, under the fallboard or nameboard. */
    val hiddenM: Float = 0.016f,
    val whiteMaterial: MaterialId, val whiteRgb: IntArray, val whiteSideRgb: IntArray, val frontRgb: IntArray,
    val blackRgb: IntArray, val blackSideRgb: IntArray,
    /** KEY_ROT: balance line and full-dip angle (about +x, positive = front down). */
    val pivotY: Float, val pivotZ: Float, val maxRad: Float) {
    val pitchM: Float get() = headM * 7f / 12f
}

/**
 * Procedural keys for any compass (PLAN §5.4): equal slots at the back (octave ÷ 12), equal natural
 * heads at the front, tails cut round the sharps (a natural's tail is its own back slot, and runs
 * to its head edge where it has no in-compass sharp neighbour, so the end keys are full), sharps
 * tapered from the slot width to 10.5 mm. Bevels are left to the shader through key-local UV in mm
 * (u across from the key's left edge, v along from its front). One skinned KEY_ROT mesh per colour.
 */
class Keyboard(val spec: KeyboardSpec) {
    val keyX = FloatArray(HK.KEYS) { Float.NaN }
    val headL = FloatArray(HK.KEYS) { Float.NaN }; val headR = FloatArray(HK.KEYS) { Float.NaN }
    val slotL = FloatArray(HK.KEYS) { Float.NaN }; val slotR = FloatArray(HK.KEYS) { Float.NaN }
    val tailL = FloatArray(HK.KEYS) { Float.NaN }; val tailR = FloatArray(HK.KEYS) { Float.NaN }
    val naturals: Int
    val sharps: Int
    /** Width of the natural heads' span (the keyboard width). */
    val widthM: Float

    init {
        var nat = 0
        for (k in spec.lowKey..spec.highKey) if (!isBlack(k)) nat++
        naturals = nat; sharps = spec.highKey - spec.lowKey + 1 - nat
        widthM = nat * spec.headM
        val x0 = -widthM / 2f
        val w = spec.headM; val p = spec.pitchM
        var j = 0
        for (k in spec.lowKey..spec.highKey) {
            if (isBlack(k)) continue
            headL[k] = x0 + j * w; headR[k] = headL[k] + w; j++
        }
        for (k in spec.lowKey..spec.highKey) {
            val s = k % 12
            val ref = if (isBlack(k)) k - 1 else k                       // a sharp's lower natural shares its octave
            val xc = if (ref >= spec.lowKey) headL[ref] - WHITE_INDEX[ref % 12] * w
                     else headL[k + 1] - WHITE_INDEX[(k + 1) % 12] * w
            slotL[k] = xc + s * p; slotR[k] = slotL[k] + p
            keyX[k] = xc + (s + 0.5f) * p
        }
        for (k in spec.lowKey..spec.highKey) {
            if (isBlack(k)) continue
            tailL[k] = if (k - 1 >= spec.lowKey && isBlack(k - 1)) slotL[k] else headL[k]
            tailR[k] = if (k + 1 <= spec.highKey && isBlack(k + 1)) slotR[k] else headR[k]
        }
    }

    /** Linear x of a (fractional) key, clamped to the compass. */
    fun xOf(key: Float): Float {
        val k = key.coerceIn(spec.lowKey.toFloat(), spec.highKey.toFloat())
        return keyX[spec.lowKey] + (k - spec.lowKey) * spec.pitchM
    }

    /** SKINNED KEY_ROT meshes (drawSlot 10): naturals, then sharps. */
    fun meshes(clipped: Boolean, blackMaterial: MaterialId = MaterialId.EBONY_KEY): List<BakedMesh> {
        val white = MeshBuilder(VertexLayout.SKINNED, naturals * 40)
        val black = MeshBuilder(VertexLayout.SKINNED, sharps * 24)
        for (k in spec.lowKey..spec.highKey) {
            val part = k - spec.lowKey
            if (isBlack(k)) { black.part(part / 4, part % 4); sharp(black, k) }
            else { white.part(part / 4, part % 4); natural(white, k) }
        }
        return white.build("keys.naturals", spec.whiteMaterial, SkinKind.KEY_ROT, VM.LEVELS_ALL, VM.ALL, clipped, ProgramId.SKINNED, 10) +
            black.build("keys.sharps", blackMaterial, SkinKind.KEY_ROT, VM.LEVELS_ALL, VM.ALL, clipped, ProgramId.SKINNED, 10)
    }

    val skinParams: FloatArray get() = floatArrayOf(spec.pivotY, spec.pivotZ, spec.maxRad)

    private fun natural(mb: MeshBuilder, k: Int) {
        val hl = headL[k]; val hr = headR[k]; val tl = tailL[k]; val tr = tailR[k]
        val hz = -(spec.naturalLenM - spec.sharpLenM); val lz = -(spec.naturalLenM + spec.hiddenM)
        val pts = ArrayList<Float>()
        fun add(x: Float, z: Float) {
            val n = pts.size
            if (n >= 2 && pts[n - 2] == x && pts[n - 1] == z) return
            pts.add(x); pts.add(z)
        }
        add(hl, 0f); add(hr, 0f); add(hr, hz); add(tr, hz); add(tr, lz); add(tl, lz); add(tl, hz); add(hl, hz)
        // Drop collinear repeats where a side is not cut (tr == hr, tl == hl).
        val raw = pts.toFloatArray()
        val poly = dedupeCollinear(raw)
        val top = spec.topY; val bot = spec.topY - spec.naturalThickM
        fun uv(x: Float, z: Float) = floatArrayOf((x - hl) * 1000f, -z * 1000f)
        // Top.
        mb.color(spec.whiteRgb)
        val tris = MeshBuilder.earClip(MeshBuilder.ccw(poly))
        val ccwPoly = MeshBuilder.ccw(poly)
        val idx = IntArray(ccwPoly.size / 2) { i ->
            val x = ccwPoly[2 * i]; val z = ccwPoly[2 * i + 1]; val t = uv(x, z)
            mb.vertex(x, top, z, 0f, 1f, 0f, t[0], t[1])
        }
        for (t in tris) mb.triOutward(idx[t[0]], idx[t[1]], idx[t[2]])
        // Sides (flat normals), front in the front colour; the hidden back end is left open.
        val n = ccwPoly.size / 2
        for (e in 0 until n) {
            val j = (e + 1) % n
            val ax = ccwPoly[2 * e]; val az = ccwPoly[2 * e + 1]; val bx = ccwPoly[2 * j]; val bz = ccwPoly[2 * j + 1]
            if (az == lz && bz == lz) continue
            val ex = bx - ax; val ez = bz - az; val l = sqrt(ex * ex + ez * ez)
            val nx = ez / l; val nz = -ex / l
            val isFront = az == 0f && bz == 0f
            mb.color(if (isFront) spec.frontRgb else spec.whiteSideRgb)
            // Side faces hidden beside a neighbour still carry the key-local UV; the shader bevels the top edge.
            val y0 = if (isFront) bot else top - 0.006f
            val ua = uv(ax, az); val ub = uv(bx, bz)
            val a = mb.vertex(ax, y0, az, nx, 0f, nz, ua[0], ua[1]); val b = mb.vertex(bx, y0, bz, nx, 0f, nz, ub[0], ub[1])
            val c = mb.vertex(bx, top, bz, nx, 0f, nz, ub[0], ub[1]); val d = mb.vertex(ax, top, az, nx, 0f, nz, ua[0], ua[1])
            mb.triOutward(a, b, c); mb.triOutward(a, c, d)
        }
    }

    private fun sharp(mb: MeshBuilder, k: Int) {
        val gap = 0.0006f
        val bl = slotL[k] + gap; val br = slotR[k] - gap
        val inset = ((br - bl) - spec.sharpTopWM).coerceAtLeast(0f) / 2f
        val tl = bl + inset; val tr = br - inset
        val y0 = spec.topY - 0.004f; val y1 = spec.topY + spec.sharpHM
        val zf = -(spec.naturalLenM - spec.sharpLenM); val zb = -(spec.naturalLenM + spec.hiddenM)
        val zfTop = zf - 0.008f
        fun uv(x: Float, z: Float) = floatArrayOf((x - bl) * 1000f, (zf - z) * 1000f)
        fun face(rgb: IntArray, p: Array<FloatArray>) {
            // Normal from the first three corners, oriented away from the key's centre line.
            val ux = p[1][0] - p[0][0]; val uy = p[1][1] - p[0][1]; val uz = p[1][2] - p[0][2]
            val vx = p[2][0] - p[0][0]; val vy = p[2][1] - p[0][1]; val vz = p[2][2] - p[0][2]
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val cx = (bl + br) / 2f - p[0][0]; val cy = (y0 + y1) / 2f - p[0][1]; val cz = (zf + zb) / 2f - p[0][2]
            if (nx * cx + ny * cy + nz * cz > 0f) { nx = -nx; ny = -ny; nz = -nz }
            val l = sqrt(nx * nx + ny * ny + nz * nz); nx /= l; ny /= l; nz /= l
            mb.color(rgb)
            val id = IntArray(4) { val t = uv(p[it][0], p[it][2]); mb.vertex(p[it][0], p[it][1], p[it][2], nx, ny, nz, t[0], t[1]) }
            mb.triOutward(id[0], id[1], id[2]); mb.triOutward(id[0], id[2], id[3])
        }
        fun p(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
        face(spec.blackRgb, arrayOf(p(tl, y1, zfTop), p(tr, y1, zfTop), p(tr, y1, zb), p(tl, y1, zb)))          // top
        face(spec.blackSideRgb, arrayOf(p(bl, y0, zf), p(br, y0, zf), p(tr, y1, zfTop), p(tl, y1, zfTop)))      // sloped front
        face(spec.blackSideRgb, arrayOf(p(bl, y0, zf), p(tl, y1, zfTop), p(tl, y1, zb), p(bl, y0, zb)))         // left
        face(spec.blackSideRgb, arrayOf(p(br, y0, zf), p(br, y0, zb), p(tr, y1, zb), p(tr, y1, zfTop)))         // right
    }

    companion object {
        private val WHITE_INDEX = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6)
        fun isBlack(k: Int): Boolean = when (k % 12) { 1, 3, 6, 8, 10 -> true; else -> false }

        private fun dedupeCollinear(p: FloatArray): FloatArray {
            val n = p.size / 2
            val keep = ArrayList<Float>()
            for (i in 0 until n) {
                val ip = (i + n - 1) % n; val inx = (i + 1) % n
                val ax = p[2 * i] - p[2 * ip]; val az = p[2 * i + 1] - p[2 * ip + 1]
                val bx = p[2 * inx] - p[2 * i]; val bz = p[2 * inx + 1] - p[2 * i + 1]
                if (ax * ax + az * az < 1e-12f) continue
                if (kotlin.math.abs(ax * bz - az * bx) < 1e-12f && ax * bx + az * bz > 0f) continue
                keep.add(p[2 * i]); keep.add(p[2 * i + 1])
            }
            return keep.toFloatArray()
        }
    }
}
