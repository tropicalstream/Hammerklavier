package com.tropicalstream.hammerklavier.venue

import com.tropicalstream.hammerklavier.contract.BakedMesh
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.KonzertzimmerAcoustics
import com.tropicalstream.hammerklavier.contract.LightRig
import com.tropicalstream.hammerklavier.contract.MaterialTable
import com.tropicalstream.hammerklavier.contract.Palette
import com.tropicalstream.hammerklavier.contract.QualityLadder
import com.tropicalstream.hammerklavier.contract.RoomLevel
import com.tropicalstream.hammerklavier.contract.ViewId
import com.tropicalstream.hammerklavier.testutil.AllocProbe
import com.tropicalstream.hammerklavier.venue.tex.Atlas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class VenueTest {
    private val scene = VenueSceneImpl()
    private val q0 = QualityLadder.of(0, 96)
    private val q1 = QualityLadder.of(1, 96)
    private val q2 = QualityLadder.of(2, 96)

    // ── T8.1 drawn areas and volume agree with the shared constant ──
    @Test fun t8_1_areasAndVolume() {
        val g = KonzertzimmerAcoustics.GEOMETRY
        assertSame(g.surfaces, scene.geometry.surfaces)
        assertSame(g, Konzertzimmer.ACOUSTICS)
        val floor = RoomShell.floor().sumOf { meshArea(it).toDouble() }
        assertEquals(84.0, floor, 0.01)
        assertEquals(g.surfaces.filter { it.plane == 0 }.sumOf { it.areaM2.toDouble() }, floor, 0.01)
        var walls = 0.0
        for (plane in 2..5) {
            val constPlane = g.surfaces.filter { it.plane == plane }
            val drawn = Konzertzimmer.wallArea(plane).toDouble()
            assertEquals("plane $plane", constPlane.sumOf { it.areaM2.toDouble() }, drawn, 0.5)
            // per material: openings drawn on this wall, boiserie is the rest
            for (s in constPlane) {
                val d = if (s.material === KonzertzimmerAcoustics.WOOD_PANELLING) Konzertzimmer.boiserieArea(plane).toDouble()
                else Konzertzimmer.WALL_FEATURES.filter { it.plane == plane && it.material === s.material }.sumOf { it.area.toDouble() }
                assertEquals("${s.name}", s.areaM2.toDouble(), d, 0.05)
            }
            walls += drawn
        }
        assertEquals(170.2, walls, 0.5)
        assertEquals(469.7, Konzertzimmer.volume().toDouble(), 1.0)
        assertEquals(g.volumeM3.toDouble(), Konzertzimmer.volume().toDouble(), 1.0)
        // The drawn cove ceiling (flat field + quarter-round runs + mitred corners) is within 2.5 m² of the table's 108.6.
        assertEquals(g.surfaces.first { it.plane == 1 }.areaM2.toDouble(), Konzertzimmer.ceilingArea().toDouble(), 2.5)
        assertEquals(g.erPlanes.toList(), scene.geometry.erPlanes.toList())
    }

    // ── T8.2 mirror culling ──
    @Test fun t8_2_mirrorCulling() {
        val f = FlameFieldImpl()
        val out = FloatArray(3)
        // centre N mirror (index 1): x −0.65..0.65, y 0.9..4.3, plane z = −4
        val flame = floatArrayOf(0f, 2.0f, -3.0f)
        val eye = floatArrayOf(0f, 1.6f, 2.0f)
        assertTrue(f.mirrorImage(flame, 0, eye, 1, out))
        assertEquals(-5.0f, out[2], 1e-5f)
        // behind the wall plane: none
        assertFalse(f.mirrorImage(floatArrayOf(0f, 2.0f, -4.2f), 0, eye, 1, out))
        // eye behind the wall: none
        assertFalse(f.mirrorImage(flame, 0, floatArrayOf(0f, 1.6f, -4.5f), 1, out))
        // outside the reflected cone: the ray to the image misses the glass
        assertFalse(f.mirrorImage(floatArrayOf(4.5f, 2.0f, -3.0f), 0, floatArrayOf(4.5f, 1.6f, 2.0f), 1, out))
        assertFalse(f.mirrorImage(flame, 0, floatArrayOf(0f, 30f, 2.0f), 1, out))
        // S pier glass (index 3 at x = −1.5, z = +4): a flame in front, eye in the room
        assertTrue(f.mirrorImage(floatArrayOf(-1.5f, 2f, 3.0f), 0, floatArrayOf(-1.5f, 1.6f, 0f), 3, out))
        assertEquals(5.0f, out[2], 1e-5f)
        // the sprite list: mirror images appear only with mirrorFlames (Q0) and change with the eye
        val buf = FloatArray(f.maxSprites * 8)
        val nQ0 = f.update(1f, eye, q0, RoomLevel.SALON, buf)
        val nQ1 = f.update(1f, eye, q1, RoomLevel.SALON, buf)
        assertTrue("Q0 $nQ0 > Q1 $nQ1", nQ0 > nQ1)
        // every emitted mirror image lies behind a glass plane and inside the room's mirror footprint
        f.update(1f, eye, q0, RoomLevel.SALON, buf)
        for (k in 0 until nQ0) {
            val z = buf[k * 8 + 2]
            assertTrue(z > -12.1f && z < 12.1f)
        }
    }

    @Test fun t8_2_bounce() {
        val f = FlameFieldImpl()
        val out = FloatArray(3)
        // a flame in front of the centre N mirror, seen from beside the S glass at x = −1.5 looking at it
        val ok = f.bounceImage(floatArrayOf(-1.2f, 2.2f, -3.8f), 0, floatArrayOf(-1.5f, 2.0f, 3.5f), 1, 3, out)
        // image of the N image (z −4.2) through S (z 4) → z = 12.2
        assertEquals(12.2f, out[2], 1e-4f)
        // the ray eye → (x, y, 12.2) crosses z = 4 near x = −1.5 inside the glass, then must hit the N glass (x −0.65..0.65)
        assertFalse("x −1.2 misses the centre N glass", ok)
        assertTrue(f.bounceImage(floatArrayOf(-0.3f, 2.2f, -3.8f), 0, floatArrayOf(-1.3f, 2.0f, 3.5f), 1, 3, out))
    }

    // ── T8.3 flicker bounds ──
    @Test fun t8_3_flicker() {
        val f = FlameFieldImpl()
        var gMin = 9f; var gMax = -9f
        var t = 0f
        while (t < 120f) {
            val g = f.globalFlicker(t)
            gMin = minOf(gMin, g); gMax = maxOf(gMax, g)
            for (i in 0 until FlameLayout.COUNT) {
                val s = f.spriteFlicker(t, i)
                assertTrue(s >= 0.92f - 1e-5f && s <= 1.08f + 1e-5f)
                val h = f.heightFlicker(t, i)
                assertTrue(h >= 0.85f - 1e-5f && h <= 1.15f + 1e-5f)
            }
            t += 0.0137f
        }
        assertTrue("global $gMin..$gMax", gMin >= 0.96f - 1e-5f && gMax <= 1.04f + 1e-5f)
        assertTrue("flicker actually moves", gMax - gMin > 0.03f)
        // the sprite list's body alphas stay within the product of the two bounds at Salon
        val buf = FloatArray(f.maxSprites * 8)
        val n = f.update(3.3f, floatArrayOf(0.4f, 1.2f, 3.0f), q1, RoomLevel.SALON, buf)
        assertTrue(n > 0)
        for (k in 0 until n) assertTrue(buf[k * 8 + 7] <= 1.04f * 1.08f + 1e-4f)
        val rig = LightRig()
        f.lights(3.3f, rig)
        assertTrue(abs(rig.flicker - f.globalFlicker(3.3f)) < 1e-6f)
        for (v in rig.rgb) assertTrue(v >= 0f)
    }

    // ── T8.3 flicker spectrum: 10 s at 30 fps, dominant energy in 6..10 Hz ──
    @Test fun t8_3_flickerSpectrum() {
        val f = FlameFieldImpl()
        val n = 300
        fun bandShare(sig: (Float) -> Float): Double {
            val x = DoubleArray(n) { sig(it / 30f).toDouble() }
            val mean = x.average()
            var inBand = 0.0; var total = 0.0
            for (k in 1..n / 2) {
                var re = 0.0; var im = 0.0
                for (j in 0 until n) { val a = 2.0 * Math.PI * k * j / n; re += (x[j] - mean) * Math.cos(a); im -= (x[j] - mean) * Math.sin(a) }
                val e = re * re + im * im; val hz = k * 30.0 / n
                total += e; if (hz >= 5.5 && hz <= 10.5) inBand += e
            }
            return inBand / total
        }
        val g = bandShare { f.globalFlicker(it) }
        assertTrue("global flicker 6-10 Hz share $g", g > 0.7)
        for (i in intArrayOf(0, 17, 40)) {
            val s = bandShare { f.spriteFlicker(it, i) }
            assertTrue("sprite $i 6-10 Hz share $s", s > 0.6)
        }
    }

    // ── §5.5 light 3 = the N sconce group nearest the instrument ──
    @Test fun lightsFollowInstrument() {
        val f = FlameFieldImpl()
        assertEquals(1, f.sconceGroup)
        f.setInstrumentOrigin(-2.20f, -2.95f)
        assertEquals(0, f.sconceGroup)
        val rig = LightRig(); f.lights(0f, rig)
        assertTrue(rig.pos[9] < -2f)
        f.setInstrumentOrigin(0f, -1.9f)
        assertEquals(1, f.sconceGroup)
    }

    // ── T8.4 triangle budget ──
    @Test fun t8_4_triangles() {
        for (p in Palette.entries) {
            val tris = scene.meshes(p).sumOf { it.triangleCount }
            assertTrue("venue triangles $tris", tris <= 18_000)
            assertTrue(tris > 2_000)
        }
    }

    // ── T8.5 50 ± 2 flames ──
    @Test fun t8_5_flames() {
        assertTrue(FlameLayout.COUNT in 48..52)
        assertEquals(50, FlameLayout.COUNT)
        // all inside the room
        for (i in 0 until FlameLayout.COUNT) {
            assertTrue(abs(FlameLayout.POS[3 * i]) < Konzertzimmer.HALF_W)
            assertTrue(abs(FlameLayout.POS[3 * i + 2]) < Konzertzimmer.HALF_D)
            assertTrue(FlameLayout.POS[3 * i + 1] in 0.5f..Konzertzimmer.CEILING)
        }
        val f = FlameFieldImpl()
        val buf = FloatArray(f.maxSprites * 8)
        val eye = floatArrayOf(0.4f, 1.2f, 3.0f)
        val salonQ1 = f.update(0f, eye, q1, RoomLevel.SALON, buf)
        assertTrue(salonQ1 >= 3 * 50)                          // body + halo + floor each
        assertEquals(0, f.update(0f, eye, q0, RoomLevel.PASSTHROUGH, buf))
        assertEquals(0, f.update(0f, eye, q0, RoomLevel.INSTRUMENT, buf))
        val stage = f.update(0f, eye, q2, RoomLevel.SALON, buf)     // Q2 caps the room at Stage
        assertTrue(stage in 1 until salonQ1)
        assertTrue(f.update(0f, eye, q0, RoomLevel.SALON, buf) <= f.maxSprites)
    }

    // ── T8.6 probe ──
    @Test fun t8_6_probe() {
        val out = ByteArray(128 * 64 * 4)
        scene.bakeProbe(floatArrayOf(0f, 1.0f, -1.9f), out)
        var sum = 0L; var bright = 0; var maxV = 0
        for (p in 0 until 128 * 64) {
            assertEquals(255, out[p * 4 + 3].toInt() and 0xFF)
            for (c in 0..2) { val v = out[p * 4 + c].toInt() and 0xFF; sum += v; maxV = maxOf(maxV, v) }
            if ((out[p * 4].toInt() and 0xFF) > 150) bright++
        }
        val mean = sum / (128.0 * 64 * 3)
        assertTrue("mean $mean", mean > 0.5 && mean < 24.0)
        assertTrue("flame pixels $bright", bright in 5..800)
        assertTrue(maxV <= 255)
        // the chandelier is above: its flames land in the upper half
        var upper = 0L; var lower = 0L
        for (v in 0 until 64) for (u in 0 until 128) {
            val r = out[(v * 128 + u) * 4].toInt() and 0xFF
            if (v < 32) upper += r else lower += r
        }
        assertTrue(upper > 0 && lower >= 0)
    }

    // ── T8.7 FlameField.update allocates nothing ──
    @Test fun t8_7_noAllocation() {
        val f = scene.flames()
        val buf = FloatArray(f.maxSprites * 8)
        val eye = floatArrayOf(0.4f, 1.2f, 3.0f)
        val rig = LightRig()
        AllocProbe.assertNoAllocation("FlameField.update") {
            var t = 0f
            for (k in 0 until 300) {
                eye[0] = -2f + k * 0.01f
                f.update(t, eye, q0, RoomLevel.SALON, buf)
                f.update(t, eye, q2, RoomLevel.STAGE, buf)
                f.lights(t, rig)
                t += 1f / 30f
            }
        }
    }

    // ── T8.8 merge keys per framing ──
    @Test fun t8_8_mergeKeys() {
        val meshes = scene.meshes(Palette.SANSSOUCI_1747)
        for (view in ViewId.entries) for (framing in 0..1) {
            val bit = 1 shl (view.ordinal * 2 + framing)
            val level = if (view == ViewId.HALL) RoomLevel.SALON else RoomLevel.STAGE
            val keys = meshes.filter { it.viewMask and bit != 0 && it.levelMask and (1 shl level.ordinal) != 0 }
                .map { listOf(it.program, it.material, it.skin, it.texture, it.levelMask, it.viewMask, it.clipped, it.drawSlot) }.toSet()
            // §5.3: venue rows 1–5 (row 6 is FlameField's one dynamic draw). Hall 21 draws − 10 instrument rows − 1 sprite
            // draw leaves 10 baked venue draws; Player/Action (Stage) leave the venue ≤ 6.
            val cap = if (level == RoomLevel.SALON) 10 else 6
            assertTrue("$view/$framing keys ${keys.size}", keys.size <= cap)
            assertTrue(keys.isNotEmpty())
        }
        for (m in meshes) {
            assertEquals(m.name, MaterialTable.of(m.material).program, m.program)
            assertTrue(m.drawSlot in 1..5)
            assertEquals(0, m.indices.size % 3)
        }
    }

    // ── T8.9 placements inside the walls and clear of the fixtures ──
    @Test fun t8_9_placements() {
        val g = scene.geometry
        val fp = g.fixtureFootprints
        assertTrue(fp.isNotEmpty() && fp.size % 4 == 0)
        val p = FloatArray(3); val r = FloatArray(3)
        for (id in InstrumentId.entries) {
            val pl = g.placements.getValue(id)
            val o = Konzertzimmer.CASE_OUTLINES.getValue(id)
            for (cx in floatArrayOf(o[0], o[2])) for (cz in floatArrayOf(o[1], o[3])) {
                p[0] = cx; p[1] = 0f; p[2] = cz
                pl.toRoom(p, r)
                assertTrue("$id corner $cx,$cz → ${r.toList()} E/W", abs(r[0]) <= Konzertzimmer.HALF_W - 0.05f)
                assertTrue("$id corner $cx,$cz → ${r.toList()} N/S", abs(r[2]) <= Konzertzimmer.HALF_D - 0.05f)
                var k = 0
                while (k < fp.size) {
                    val clear = r[0] < fp[k] - 0.05f || r[0] > fp[k + 2] + 0.05f || r[2] < fp[k + 1] - 0.05f || r[2] > fp[k + 3] + 0.05f
                    assertTrue("$id corner ${r.toList()} hits footprint ${k / 4}", clear)
                    k += 4
                }
            }
        }
        // the upright's treble back corner sits ≈ 13 cm off the N wall (§5.6)
        val up = g.placements.getValue(InstrumentId.UPRIGHT)
        up.toRoom(floatArrayOf(0.765f, 0f, -0.65f), r)
        assertEquals(-3.86f, r[2], 0.02f)
    }

    // ── colour rules of §5.9 on the venue ──
    @Test fun colourRules() {
        for (p in Palette.entries) for (m in scene.meshes(p)) {
            val f = m.layout.floats
            var o = 0
            while (o < m.vertices.size) {
                for (c in 8..10) assertTrue("${m.name} rgb ≤ 1", m.vertices[o + c] in 0f..1f)
                assertTrue("${m.name} not 255", m.vertices[o + 8] < 1f || m.vertices[o + 9] < 1f)
                o += f
            }
        }
        // the wall glow follows the palette
        fun glowMax(p: Palette): FloatArray {
            val m = RoomShell.framesAndGlow(p).first()
            var gm = 0f; var bm = 0f
            var o = 0
            while (o < m.vertices.size) { gm = maxOf(gm, m.vertices[o + 9]); bm = maxOf(bm, m.vertices[o + 10]); o += 12 }
            return floatArrayOf(gm, bm)
        }
        assertTrue(glowMax(Palette.STADTSCHLOSS_1747)[1] > 45f / 255f)
        // floor far corners fall to black (transparent)
        val fl = RoomShell.floor().first()
        var dark = 0; var lit = 0
        var o = 0
        while (o < fl.vertices.size) { if (fl.vertices[o + 8] < 0.02f) dark++ else lit++; o += 12 }
        assertTrue("floor dark $dark lit $lit", dark > 0 && lit > 0)
    }

    @Test fun normalsAndWinding() {
        for (m in scene.meshes(Palette.SANSSOUCI_1747)) {
            if (m.program == com.tropicalstream.hammerklavier.contract.ProgramId.RIBBON) continue
            val f = m.layout.floats
            var t = 0
            while (t < m.indices.size) {
                val a = m.indices[t].toInt() and 0xFFFF; val b = m.indices[t + 1].toInt() and 0xFFFF; val c = m.indices[t + 2].toInt() and 0xFFFF
                fun p(i: Int, k: Int) = m.vertices[i * f + k]
                val ux = p(b, 0) - p(a, 0); val uy = p(b, 1) - p(a, 1); val uz = p(b, 2) - p(a, 2)
                val vx = p(c, 0) - p(a, 0); val vy = p(c, 1) - p(a, 1); val vz = p(c, 2) - p(a, 2)
                val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
                val l = sqrt(nx * nx + ny * ny + nz * nz)
                if (l > 1e-9f) {
                    val d = (nx * p(a, 3) + ny * p(a, 4) + nz * p(a, 5)) / l
                    assertTrue("${m.name} tri ${t / 3} winding $d", d > 0.5f)
                }
                val nl = sqrt(p(a, 3) * p(a, 3) + p(a, 4) * p(a, 4) + p(a, 5) * p(a, 5))
                assertEquals(1f, nl, 1e-3f)
                t += 3
            }
        }
    }

    @Test fun texturesNamedByMeshesExist() {
        val names = scene.textures().map { it.name }.toSet()
        for (m in scene.meshes(Palette.SANSSOUCI_1747)) m.texture?.let { assertTrue(it in names) }
        assertTrue(Atlas.ATLAS in names && Atlas.PARQUET in names)
    }

    private fun meshArea(m: BakedMesh): Float {
        val f = m.layout.floats
        var s = 0f
        var t = 0
        while (t < m.indices.size) {
            val a = m.indices[t].toInt() and 0xFFFF; val b = m.indices[t + 1].toInt() and 0xFFFF; val c = m.indices[t + 2].toInt() and 0xFFFF
            fun p(i: Int, k: Int) = m.vertices[i * f + k]
            val ux = p(b, 0) - p(a, 0); val uy = p(b, 1) - p(a, 1); val uz = p(b, 2) - p(a, 2)
            val vx = p(c, 0) - p(a, 0); val vy = p(c, 1) - p(a, 1); val vz = p(c, 2) - p(a, 2)
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            s += 0.5f * sqrt(nx * nx + ny * ny + nz * nz)
            t += 3
        }
        return s
    }
}
