package com.tropicalstream.hammerklavier.venue

import com.tropicalstream.hammerklavier.contract.BakedMesh
import com.tropicalstream.hammerklavier.contract.MaterialId
import com.tropicalstream.hammerklavier.contract.Pal
import com.tropicalstream.hammerklavier.contract.Palette
import com.tropicalstream.hammerklavier.contract.ProgramId
import com.tropicalstream.hammerklavier.contract.RoomLevel
import com.tropicalstream.hammerklavier.contract.SkinKind
import com.tropicalstream.hammerklavier.contract.VertexLayout
import com.tropicalstream.hammerklavier.mesh.MeshBuilder
import com.tropicalstream.hammerklavier.venue.Konzertzimmer.FeatureKind
import com.tropicalstream.hammerklavier.venue.tex.Atlas
import kotlin.math.sqrt

/** Level and view bits (BakedMesh.levelMask / viewMask). */
internal object Masks {
    val SALON = 1 shl RoomLevel.SALON.ordinal
    val STAGE = 1 shl RoomLevel.STAGE.ordinal
    val INSTRUMENT = 1 shl RoomLevel.INSTRUMENT.ordinal
    const val ALL_VIEWS = 0b111111
    const val HALL_VIEWS = 0b110000
}

/**
 * Geometry helpers on top of the day-0 MeshBuilder (vertex/tri/quad/box), room frame. The ribbon
 * encoding follows contract/Scene.kt: pos = path point, nrm = unit path tangent, uv = (side ±1,
 * half-width m), two vertices per path point.
 */
internal object Geo {
    fun ribbon(b: MeshBuilder, pts: FloatArray, closed: Boolean, halfW: Float) {
        val n = pts.size / 3
        require(n >= 2)
        val first = b.vertexCount
        for (i in 0 until n) {
            val a = if (i > 0) i - 1 else if (closed) n - 1 else 0
            val c = if (i < n - 1) i + 1 else if (closed) 0 else n - 1
            var tx = pts[3 * c] - pts[3 * a]; var ty = pts[3 * c + 1] - pts[3 * a + 1]; var tz = pts[3 * c + 2] - pts[3 * a + 2]
            val l = sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-6f)
            tx /= l; ty /= l; tz /= l
            b.vertex(pts[3 * i], pts[3 * i + 1], pts[3 * i + 2], tx, ty, tz, -1f, halfW)
            b.vertex(pts[3 * i], pts[3 * i + 1], pts[3 * i + 2], tx, ty, tz, 1f, halfW)
        }
        val segs = if (closed) n else n - 1
        for (s in 0 until segs) {
            val i0 = first + 2 * s; val i1 = first + 2 * ((s + 1) % n)
            b.tri(i0, i0 + 1, i1 + 1); b.tri(i0, i1 + 1, i1)
        }
    }

    /** Wall basis: the in-room normal and the tangent T with T × Y = N (so (u, v) = (T, Y) is CCW from the room). */
    fun wallNormal(plane: Int): FloatArray = when (plane) {
        Konzertzimmer.PLANE_N -> floatArrayOf(0f, 0f, 1f)
        Konzertzimmer.PLANE_S -> floatArrayOf(0f, 0f, -1f)
        Konzertzimmer.PLANE_E -> floatArrayOf(-1f, 0f, 0f)
        else -> floatArrayOf(1f, 0f, 0f)
    }

    fun wallTangent(plane: Int): FloatArray = when (plane) {
        Konzertzimmer.PLANE_N -> floatArrayOf(1f, 0f, 0f)
        Konzertzimmer.PLANE_S -> floatArrayOf(-1f, 0f, 0f)
        Konzertzimmer.PLANE_E -> floatArrayOf(0f, 0f, 1f)
        else -> floatArrayOf(0f, 0f, -1f)
    }

    /** Room-frame point on a wall: [along] = x on N/S, z on E/W; [off] metres into the room. */
    fun wallPoint(plane: Int, along: Float, y: Float, off: Float): FloatArray = when (plane) {
        Konzertzimmer.PLANE_N -> floatArrayOf(along, y, -Konzertzimmer.HALF_D + off)
        Konzertzimmer.PLANE_S -> floatArrayOf(along, y, Konzertzimmer.HALF_D - off)
        Konzertzimmer.PLANE_E -> floatArrayOf(Konzertzimmer.HALF_W - off, y, along)
        else -> floatArrayOf(-Konzertzimmer.HALF_W + off, y, along)
    }

    /** Flattens a list of points. */
    fun flat(pts: List<FloatArray>): FloatArray {
        val o = FloatArray(pts.size * 3)
        for ((i, p) in pts.withIndex()) { o[3 * i] = p[0]; o[3 * i + 1] = p[1]; o[3 * i + 2] = p[2] }
        return o
    }

    /**
     * A radial glow disc on a plane (centre c, tangent t, bitangent bt, normal n = t × bt), colour
     * [rgb] at the centre falling smoothly to black at radius [r].
     */
    fun glowDisc(b: MeshBuilder, c: FloatArray, t: FloatArray, bt: FloatArray, n: FloatArray, r: Float, rgb: IntArray) {
        val seg = 12; val rings = 3
        b.color(rgb)
        val centre = b.vertex(c[0], c[1], c[2], n[0], n[1], n[2], 0f, 0f)
        val idx = IntArray(rings * seg)
        for (k in 1..rings) {
            val rr = r * k / rings
            val fall = 1f - k.toFloat() / rings
            val w = fall * fall * (3f - 2f * fall)
            b.color(intArrayOf((rgb[0] * w).toInt(), (rgb[1] * w).toInt(), (rgb[2] * w).toInt()))
            for (s in 0 until seg) {
                val a = 2.0 * Math.PI * s / seg
                val cu = (rr * Math.cos(a)).toFloat(); val cv = (rr * Math.sin(a)).toFloat()
                idx[(k - 1) * seg + s] = b.vertex(c[0] + t[0] * cu + bt[0] * cv, c[1] + t[1] * cu + bt[1] * cv, c[2] + t[2] * cu + bt[2] * cv,
                    n[0], n[1], n[2], cu, cv)
            }
        }
        for (s in 0 until seg) b.tri(centre, idx[s], idx[(s + 1) % seg])
        for (k in 1 until rings) for (s in 0 until seg) {
            val a0 = idx[(k - 1) * seg + s]; val a1 = idx[(k - 1) * seg + (s + 1) % seg]
            val b0 = idx[k * seg + s]; val b1 = idx[k * seg + (s + 1) % seg]
            b.tri(a0, b0, b1); b.tri(a0, b1, a1)
        }
    }

    /** A textured quad with explicit atlas UVs (c = centre, t/bt half extents, normal n = t × bt direction). */
    fun decal(b: MeshBuilder, c: FloatArray, t: FloatArray, bt: FloatArray, n: FloatArray, uv: FloatArray) {
        fun v(su: Float, sv: Float, u: Float, w: Float) = b.vertex(c[0] + t[0] * su + bt[0] * sv, c[1] + t[1] * su + bt[1] * sv,
            c[2] + t[2] * su + bt[2] * sv, n[0], n[1], n[2], u, w)
        // v = 0 is the top of the cell: the +bt edge
        val i0 = v(-1f, -1f, uv[0], uv[3]); val i1 = v(1f, -1f, uv[2], uv[3])
        val i2 = v(1f, 1f, uv[2], uv[1]); val i3 = v(-1f, 1f, uv[0], uv[1])
        b.tri(i0, i1, i2); b.tri(i0, i2, i3)
    }
}

/**
 * Room shell, cove, trellis, web, cornice and dado (PLAN §5.5; §5.3 rows 1–3): the parquet floor
 * with its baked light pools, the Instrument-level contact pool, window frames and bars, the wall
 * glow near flames (BOISERIE_NEAR, or the Stadtschloss celadon), all gilt ribbons (dado, cornice
 * lip, pilaster trellis with vines, frames of every opening, ceiling trellis and the spider web),
 * and the cove cartouches and mirror crests as atlas decals. Wall fields and plaster are not drawn
 * (the wearer's room stands in for them).
 */
object RoomShell {
    fun build(palette: Palette): List<BakedMesh> {
        val out = ArrayList<BakedMesh>()
        out += floor()
        out += contactPool()
        out += framesAndGlow(palette)
        out += gilt()
        out += decals()
        return out
    }

    private const val FLOOR_STEP = 0.5f

    fun floor(): List<BakedMesh> {
        val b = MeshBuilder(VertexLayout.STATIC, 400)
        b.color(Pal.PARQUET_POOL)
        val nx = (Konzertzimmer.WIDTH / FLOOR_STEP).toInt(); val nz = (Konzertzimmer.DEPTH / FLOOR_STEP).toInt()
        val x0 = -Konzertzimmer.HALF_W; val z0 = -Konzertzimmer.HALF_D
        val sx = Konzertzimmer.WIDTH / nx; val sz = Konzertzimmer.DEPTH / nz
        for (j in 0..nz) for (i in 0..nx) {
            val x = x0 + i * sx; val z = z0 + j * sz
            b.vertex(x, 0f, z, 0f, 1f, 0f, x, z)
        }
        val row = nx + 1
        for (j in 0 until nz) for (i in 0 until nx) {
            val v00 = j * row + i; val v10 = v00 + 1; val v01 = v00 + row; val v11 = v01 + 1
            b.tri(v00, v01, v10); b.tri(v10, v01, v11)
        }
        val m = b.build("venue.floor", MaterialId.PARQUET_POOL, SkinKind.STATIC, Masks.SALON or Masks.STAGE, Masks.ALL_VIEWS,
            clipped = false, program = ProgramId.LIT, drawSlot = 1, texture = Atlas.PARQUET,
            fadeNearM = Konzertzimmer.STAGE_FADE_NEAR, fadeFarM = Konzertzimmer.STAGE_FADE_FAR)
        for (x in m) LightBake.bake(x, useNormal = true, gain = 0.83f, cut = 0.75f)   // pools: full near the candelabra and under the chandelier, black by E ≈ 0.9
        for (x in m) poolFalloff(x)
        return m
    }

    /** §5.5 floor row (M5 integration): (150,100,55) only at a pool centre, falling to 0 by r = 2.6 m, instead of a
     *  saturated plateau (T-APL: the flat plateau alone put the Player view at 30%). Pools: the two candelabra, the
     *  chandelier (room centre) and the stage centre. */
    private val POOLS = floatArrayOf(Konzertzimmer.CANDELABRA_XZ[0], Konzertzimmer.CANDELABRA_XZ[1],
        Konzertzimmer.CANDELABRA_XZ[2], Konzertzimmer.CANDELABRA_XZ[3], 0f, 0f, Konzertzimmer.STAGE_CENTRE[0], Konzertzimmer.STAGE_CENTRE[2])
    const val POOL_R = 2.6f
    private fun poolFalloff(m: BakedMesh) {
        val f = m.layout.floats; val v = m.vertices
        var o = 0
        while (o < v.size) {
            var k = 0f
            var i = 0
            while (i < POOLS.size) {
                val dx = v[o] - POOLS[i]; val dz = v[o + 2] - POOLS[i + 1]
                val t = (kotlin.math.sqrt(dx * dx + dz * dz) / POOL_R).coerceIn(0f, 1f)
                val q = 1f - t * t * (3f - 2f * t)
                if (q > k) k = q
                i += 2
            }
            k *= k
            v[o + 8] *= k; v[o + 9] *= k; v[o + 10] *= k
            o += f
        }
    }

    /** The Instrument level's contact pool under the stage centre (r 1.4 m). */
    fun contactPool(): List<BakedMesh> {
        val b = MeshBuilder(VertexLayout.STATIC, 64)
        val c = floatArrayOf(Konzertzimmer.STAGE_CENTRE[0], 0.002f, Konzertzimmer.STAGE_CENTRE[2])
        // t × bt must be +y: t = +x, bt = −z
        Geo.glowDisc(b, c, floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, -1f), floatArrayOf(0f, 1f, 0f), 1.4f, Pal.PARQUET_POOL)
        return b.build("venue.contactpool", MaterialId.PARQUET_POOL, SkinKind.STATIC, Masks.INSTRUMENT, Masks.ALL_VIEWS,
            clipped = false, program = ProgramId.LIT, drawSlot = 1)
    }

    val WINDOW_FRAME_RGB = intArrayOf(120, 96, 64)
    val WINDOW_BAR_RGB = intArrayOf(70, 56, 38)

    /** Window frames and bars (3 × 6 panes) plus the wall glow discs: one LIT merge key. */
    fun framesAndGlow(palette: Palette): List<BakedMesh> {
        val b = MeshBuilder(VertexLayout.STATIC, 2048)
        for (f in Konzertzimmer.WALL_FEATURES) {
            if (f.kind != FeatureKind.WINDOW) continue
            val zIn = Konzertzimmer.HALF_D - 0.02f; val zOut = Konzertzimmer.HALF_D - 0.10f
            val x0 = f.along - f.w / 2; val x1 = f.along + f.w / 2; val y0 = f.y0; val y1 = f.y0 + f.h
            val fw = 0.07f
            b.color(WINDOW_FRAME_RGB)
            b.box(x0, y0, zOut, x0 + fw, y1, zIn); b.box(x1 - fw, y0, zOut, x1, y1, zIn)
            b.box(x0, y1 - fw, zOut, x1, y1, zIn); b.box(x0, y0, zOut, x1, y0 + fw, zIn)
            // segmental head: three short boxes stepping up to the crown
            b.box(x0 + 0.25f, y1, zOut, x1 - 0.25f, y1 + 0.06f, zIn)
            b.color(WINDOW_BAR_RGB)
            val bw = 0.025f
            for (k in 1..2) { val x = x0 + f.w * k / 3; b.box(x - bw, y0 + fw, zOut + 0.02f, x + bw, y1 - fw, zIn - 0.02f) }
            b.box(f.along - bw * 1.6f, y0 + fw, zOut + 0.01f, f.along + bw * 1.6f, y1 - fw, zIn - 0.01f)   // meeting stiles
            for (k in 1..5) { val y = y0 + f.h * k / 6; b.box(x0 + fw, y - bw, zOut + 0.02f, x1 - fw, y + bw, zIn - 0.02f) }
        }
        val glow = if (palette == Palette.STADTSCHLOSS_1747) Pal.STADTSCHLOSS_GREEN else Pal.BOISERIE_NEAR
        // one disc per girandole (N mirror frames, S pier glasses) and per door candle, 1 cm into the room
        val centres = ArrayList<FloatArray>()          // plane, along, y
        for (cx in floatArrayOf(-3f, 0f, 3f)) for (s in floatArrayOf(-0.8f, 0.8f)) centres.add(floatArrayOf(Konzertzimmer.PLANE_N.toFloat(), cx + s, FlameLayout.SCONCE_Y))
        for (cx in floatArrayOf(-1.5f, 1.5f)) centres.add(floatArrayOf(Konzertzimmer.PLANE_S.toFloat(), cx, FlameLayout.SCONCE_S_Y))
        for (i in 0 until FlameLayout.COUNT) {
            if (FlameLayout.GROUP[i] != FlameLayout.G_DOOR) continue
            val x = FlameLayout.POS[3 * i]
            val plane = if (x > 0f) Konzertzimmer.PLANE_E else Konzertzimmer.PLANE_W
            centres.add(floatArrayOf(plane.toFloat(), FlameLayout.POS[3 * i + 2], FlameLayout.POS[3 * i + 1]))
        }
        // M5 integration: one grid per wall carrying the max of the overlapping glows. Separate discs at one depth
        // z-fought where neighbours overlap (the dark shards across the N mirrors in the Hall).
        for (plane in intArrayOf(Konzertzimmer.PLANE_N, Konzertzimmer.PLANE_E, Konzertzimmer.PLANE_S, Konzertzimmer.PLANE_W)) {
            val mine = centres.filter { it[0].toInt() == plane }
            if (mine.isNotEmpty()) glowGrid(b, plane, mine, glow)
        }
        val m = b.build("venue.frames", MaterialId.WINDOW_FRAME, SkinKind.STATIC, Masks.SALON or Masks.STAGE, Masks.ALL_VIEWS,
            clipped = false, program = ProgramId.LIT, drawSlot = 1,
            fadeNearM = Konzertzimmer.STAGE_FADE_NEAR, fadeFarM = Konzertzimmer.STAGE_FADE_FAR)
        return m
    }

    const val GLOW_R = 1.2f
    private const val GLOW_STEP = 0.15f

    /** A wall-plane grid 1 cm into the room: vertex colour = [rgb] × max over [cs] (plane, along, y) of the disc falloff. */
    private fun glowGrid(b: MeshBuilder, plane: Int, cs: List<FloatArray>, rgb: IntArray) {
        val a0 = cs.minOf { it[1] } - GLOW_R; val a1 = cs.maxOf { it[1] } + GLOW_R
        val y0 = maxOf(0f, cs.minOf { it[2] } - GLOW_R); val y1 = cs.maxOf { it[2] } + GLOW_R
        val na = kotlin.math.ceil((a1 - a0) / GLOW_STEP).toInt(); val ny = kotlin.math.ceil((y1 - y0) / GLOW_STEP).toInt()
        val w = FloatArray((na + 1) * (ny + 1))
        for (j in 0..ny) for (i in 0..na) {
            val a = a0 + (a1 - a0) * i / na; val y = y0 + (y1 - y0) * j / ny
            var m = 0f
            for (c in cs) {
                val da = a - c[1]; val dy = y - c[2]
                val fall = 1f - (sqrt(da * da + dy * dy) / GLOW_R).coerceIn(0f, 1f)
                m = maxOf(m, fall * fall * (3f - 2f * fall))
            }
            w[j * (na + 1) + i] = m
        }
        val n = Geo.wallNormal(plane)
        val idx = IntArray((na + 1) * (ny + 1)) { -1 }
        fun v(i: Int, j: Int): Int {
            val k = j * (na + 1) + i
            if (idx[k] < 0) {
                val p = Geo.wallPoint(plane, a0 + (a1 - a0) * i / na, y0 + (y1 - y0) * j / ny, 0.01f)
                val q = w[k]
                b.color(intArrayOf((rgb[0] * q).toInt(), (rgb[1] * q).toInt(), (rgb[2] * q).toInt()))
                idx[k] = b.vertex(p[0], p[1], p[2], n[0], n[1], n[2], 0f, 0f)
            }
            return idx[k]
        }
        // (T, Y) is CCW seen from the room; on S and W "along" runs against T
        val flip = plane == Konzertzimmer.PLANE_S || plane == Konzertzimmer.PLANE_W
        for (j in 0 until ny) for (i in 0 until na) {
            val k = j * (na + 1) + i
            if (w[k] <= 0f && w[k + 1] <= 0f && w[k + na + 1] <= 0f && w[k + na + 2] <= 0f) continue
            val v00 = v(i, j); val v10 = v(i + 1, j); val v01 = v(i, j + 1); val v11 = v(i + 1, j + 1)
            if (flip) { b.tri(v00, v11, v10); b.tri(v00, v01, v11) } else { b.tri(v00, v10, v11); b.tri(v00, v11, v01) }
        }
    }

    /** Intervals along a wall occupied by its openings (merged), sorted. */
    fun occupied(plane: Int): List<FloatArray> {
        val iv = Konzertzimmer.WALL_FEATURES.filter { it.plane == plane }.map { floatArrayOf(it.along - it.w / 2, it.along + it.w / 2) }
            .sortedBy { it[0] }
        val out = ArrayList<FloatArray>()
        for (i in iv) {
            val last = out.lastOrNull()
            if (last != null && i[0] <= last[1]) last[1] = maxOf(last[1], i[1]) else out.add(i.copyOf())
        }
        return out
    }

    /** Pilaster centres: the middle of every wall gap (between openings and corners) at least 0.28 m wide. */
    fun pilasters(plane: Int): FloatArray {
        val half = Konzertzimmer.wallLength(plane) / 2
        val occ = occupied(plane)
        val res = ArrayList<Float>()
        var start = -half
        for (o in occ + listOf(floatArrayOf(half, half))) {
            if (o[0] - start >= 0.28f) res.add((start + o[0]) / 2)
            start = o[1]
        }
        return res.toFloatArray()
    }

    fun gilt(): List<BakedMesh> {
        val b = MeshBuilder(VertexLayout.STATIC, 4096)
        b.color(Pal.GILT_LIT)
        val hw = 0.012f
        val walls = intArrayOf(Konzertzimmer.PLANE_N, Konzertzimmer.PLANE_E, Konzertzimmer.PLANE_S, Konzertzimmer.PLANE_W)
        // cornice lip and lower cornice line: closed loops round the room
        for ((y, off) in listOf(Konzertzimmer.CORNICE to Konzertzimmer.CORNICE_DEPTH, Konzertzimmer.CORNICE - Konzertzimmer.CORNICE_DEPTH to 0.02f)) {
            val hx = Konzertzimmer.HALF_W - off; val hz = Konzertzimmer.HALF_D - off
            Geo.ribbon(b, floatArrayOf(-hx, y, -hz, hx, y, -hz, hx, y, hz, -hx, y, hz), true, hw * 1.5f)
        }
        for (plane in walls) {
            val half = Konzertzimmer.wallLength(plane) / 2
            // dado rail, broken by the openings that cross it
            var start = -half
            val cutting = Konzertzimmer.WALL_FEATURES.filter { it.plane == plane && it.y0 < Konzertzimmer.DADO && it.y0 + it.h > Konzertzimmer.DADO }
                .map { floatArrayOf(it.along - it.w / 2, it.along + it.w / 2) }.sortedBy { it[0] }
            for (c in cutting + listOf(floatArrayOf(half, half))) {
                if (c[0] - start > 0.05f) Geo.ribbon(b, Geo.flat(listOf(Geo.wallPoint(plane, start, Konzertzimmer.DADO, 0.02f),
                    Geo.wallPoint(plane, c[0], Konzertzimmer.DADO, 0.02f))), false, hw)
                start = c[1]
            }
            // pilaster trellis: two edge lines and a vine zigzag with leaf ticks
            val yTop = Konzertzimmer.CORNICE - Konzertzimmer.CORNICE_DEPTH
            for (a in pilasters(plane)) {
                val e = 0.12f
                for (s in floatArrayOf(-e, e)) Geo.ribbon(b, Geo.flat(listOf(Geo.wallPoint(plane, a + s, Konzertzimmer.DADO, 0.02f),
                    Geo.wallPoint(plane, a + s, yTop, 0.02f))), false, hw * 0.8f)
                val zig = ArrayList<FloatArray>()
                val steps = 14
                for (k in 0..steps) {
                    val y = Konzertzimmer.DADO + (yTop - Konzertzimmer.DADO) * k / steps
                    zig.add(Geo.wallPoint(plane, a + if (k % 2 == 0) -0.08f else 0.08f, y, 0.025f))
                }
                Geo.ribbon(b, Geo.flat(zig), false, hw * 0.7f)
            }
            // frames of every opening
            for (f in Konzertzimmer.WALL_FEATURES) {
                if (f.plane != plane || f.kind == FeatureKind.WINDOW) continue
                val x0 = f.along - f.w / 2; val x1 = f.along + f.w / 2; val y0 = f.y0; val y1 = f.y0 + f.h
                val pts = ArrayList<FloatArray>()
                pts.add(Geo.wallPoint(plane, x0, y0, 0.03f)); pts.add(Geo.wallPoint(plane, x1, y0, 0.03f))
                if (f.kind == FeatureKind.MIRROR) {
                    for (k in 0..8) {
                        val t = k / 8f
                        val rise = 0.15f * (1f - (2f * t - 1f) * (2f * t - 1f))
                        pts.add(Geo.wallPoint(plane, x1 + (x0 - x1) * t, y1 - 0.15f + rise, 0.03f))
                    }
                } else {
                    pts.add(Geo.wallPoint(plane, x1, y1, 0.03f)); pts.add(Geo.wallPoint(plane, x0, y1, 0.03f))
                }
                Geo.ribbon(b, Geo.flat(pts), true, hw * 1.3f)
                if (f.kind == FeatureKind.DOOR) {           // the meeting line of the two leaves and two panel rows
                    Geo.ribbon(b, Geo.flat(listOf(Geo.wallPoint(plane, f.along, y0 + 0.05f, 0.03f), Geo.wallPoint(plane, f.along, y1 - 0.05f, 0.03f))), false, hw)
                    for (s in floatArrayOf(-1f, 1f)) {
                        val a0 = f.along + s * 0.12f; val a1 = f.along + s * (f.w / 2 - 0.12f)
                        for ((py0, py1) in listOf(0.25f to 1.35f, 1.6f to 2.95f)) Geo.ribbon(b, Geo.flat(listOf(
                            Geo.wallPoint(plane, minOf(a0, a1), py0, 0.035f), Geo.wallPoint(plane, maxOf(a0, a1), py0, 0.035f),
                            Geo.wallPoint(plane, maxOf(a0, a1), py1, 0.035f), Geo.wallPoint(plane, minOf(a0, a1), py1, 0.035f))), true, hw * 0.7f)
                    }
                }
            }
        }
        // ceiling: spider web of 16 spokes and 9 turns, the rosette, and a trellis from each corner of the flat field
        val y = Konzertzimmer.CEILING - 0.01f
        val webR = 1.6f
        for (s in 0 until 16) {
            val a = 2.0 * Math.PI * s / 16
            val sx = Math.sin(a).toFloat(); val cz = Math.cos(a).toFloat()
            Geo.ribbon(b, floatArrayOf(0.15f * sx, y, 0.15f * cz, webR * 0.5f * sx, y, webR * 0.5f * cz, webR * sx, y, webR * cz), false, hw)
        }
        for (t in 1..9) {
            val r = webR * t / 9f
            val ring = FloatArray(16 * 3)
            for (s in 0 until 16) {
                val a = 2.0 * Math.PI * s / 16
                ring[3 * s] = (r * Math.sin(a)).toFloat(); ring[3 * s + 1] = y; ring[3 * s + 2] = (r * Math.cos(a)).toFloat()
            }
            Geo.ribbon(b, ring, true, hw * 0.8f)
        }
        val fx = Konzertzimmer.HALF_W - Konzertzimmer.COVE_R; val fz = Konzertzimmer.HALF_D - Konzertzimmer.COVE_R
        Geo.ribbon(b, floatArrayOf(-fx, y, -fz, fx, y, -fz, fx, y, fz, -fx, y, fz), true, hw * 1.2f)
        for (sx in floatArrayOf(-1f, 1f)) for (sz in floatArrayOf(-1f, 1f)) {
            val cx = sx * fx; val cz = sz * fz
            val len = sqrt(cx * cx + cz * cz)
            val ux = -cx / len; val uz = -cz / len                 // toward the centre
            val px = -uz; val pz = ux                             // across
            val end = len - webR
            for (o in floatArrayOf(-0.15f, 0.15f)) Geo.ribbon(b, floatArrayOf(cx + px * o, y, cz + pz * o,
                cx + ux * end + px * o, y, cz + uz * end + pz * o), false, hw)
            val zig = FloatArray(21 * 3)
            for (k in 0..20) {
                val d = end * k / 20f; val o = if (k % 2 == 0) -0.15f else 0.15f
                zig[3 * k] = cx + ux * d + px * o; zig[3 * k + 1] = y; zig[3 * k + 2] = cz + uz * d + pz * o
            }
            Geo.ribbon(b, zig, false, hw * 0.7f)
        }
        val m = b.build("venue.gilt", MaterialId.GILT, SkinKind.STATIC, Masks.SALON or Masks.STAGE, Masks.ALL_VIEWS,
            clipped = false, program = ProgramId.RIBBON, drawSlot = 2,
            fadeNearM = Konzertzimmer.STAGE_FADE_NEAR, fadeFarM = Konzertzimmer.STAGE_FADE_FAR)
        for (x in m) LightBake.bake(x, useNormal = false, gain = 1.2f, floor = 0.3f)
        return m
    }

    /** Cove cartouches every ≈ 2 m, corner crests and the N mirror crests: atlas decals. */
    fun decals(): List<BakedMesh> {
        val b = MeshBuilder(VertexLayout.STATIC, 256)
        b.color(Pal.GILT_LIT)
        val r = Konzertzimmer.COVE_R
        val c45 = 0.70710677f
        val d = r - r * c45                                     // distance from the wall at 45°
        val cy = Konzertzimmer.CORNICE + r * c45
        var motif = 0
        for (plane in intArrayOf(Konzertzimmer.PLANE_N, Konzertzimmer.PLANE_E, Konzertzimmer.PLANE_S, Konzertzimmer.PLANE_W)) {
            val len = Konzertzimmer.wallLength(plane) - 2 * r
            val count = maxOf(1, Math.round(len / 2f))
            val n = Geo.wallNormal(plane)
            val t = Geo.wallTangent(plane)
            // up the cove: (inward, up)/√2; the decal faces down and inward
            val bt = floatArrayOf(n[0] * c45, c45, n[2] * c45)
            val nn = floatArrayOf(n[0] * c45, -c45, n[2] * c45)
            // T × BT must point along nn; flip T when it does not
            val cr = cross(t, bt)
            val tt = if (dot(cr, nn) >= 0f) t else floatArrayOf(-t[0], -t[1], -t[2])
            for (k in 0 until count) {
                val along = -len / 2 + len * (k + 0.5f) / count
                val c = Geo.wallPoint(plane, along, cy, d - 0.01f)
                Geo.decal(b, c, scale(tt, 0.38f), scale(bt, 0.26f), nn, Atlas.cellUv(motif % 4))
                motif++
            }
        }
        for (sx in floatArrayOf(-1f, 1f)) for (sz in floatArrayOf(-1f, 1f)) {
            val c = floatArrayOf(sx * (Konzertzimmer.HALF_W - d), cy, sz * (Konzertzimmer.HALF_D - d))
            val inward = floatArrayOf(-sx * c45, 0f, -sz * c45)
            val bt = floatArrayOf(inward[0] * c45, c45, inward[2] * c45)
            val nn = floatArrayOf(inward[0] * c45, -c45, inward[2] * c45)
            var t = floatArrayOf(-inward[2], 0f, inward[0])
            if (dot(cross(t, bt), nn) < 0f) t = floatArrayOf(-t[0], 0f, -t[2])
            Geo.decal(b, c, scale(t, 0.45f), scale(bt, 0.34f), nn, Atlas.cellUv(Atlas.CORNER_CREST))
        }
        for (f in Konzertzimmer.WALL_FEATURES) {
            if (f.kind != FeatureKind.MIRROR) continue
            val c = Geo.wallPoint(f.plane, f.along, f.y0 + f.h + 0.05f, 0.04f)
            Geo.decal(b, c, scale(Geo.wallTangent(f.plane), 0.30f), floatArrayOf(0f, 0.18f, 0f), Geo.wallNormal(f.plane), Atlas.cellUv(Atlas.MIRROR_CREST))
        }
        val cc = floatArrayOf(0f, Konzertzimmer.CEILING - 0.012f, 0f)
        // the rosette faces down: t × bt = −y with t = +x, bt = +z
        Geo.decal(b, cc, floatArrayOf(0.22f, 0f, 0f), floatArrayOf(0f, 0f, 0.22f), floatArrayOf(0f, -1f, 0f), Atlas.cellUv(Atlas.ROSETTE))
        val m = b.build("venue.decals", MaterialId.GILT_EMISSIVE, SkinKind.STATIC, Masks.SALON, Masks.ALL_VIEWS,
            clipped = false, program = ProgramId.DECAL, drawSlot = 3, texture = Atlas.ATLAS)
        for (x in m) LightBake.bake(x, useNormal = false, gain = 1f, floor = 0.35f)
        return m
    }

    internal fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    internal fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    internal fun scale(a: FloatArray, s: Float) = floatArrayOf(a[0] * s, a[1] * s, a[2] * s)
}
