package com.tropicalstream.hammerklavier.instrument

import com.tropicalstream.hammerklavier.contract.BakedMesh
import com.tropicalstream.hammerklavier.contract.CameraPose
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentLook
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.KonzertzimmerAcoustics
import com.tropicalstream.hammerklavier.contract.MaterialId
import com.tropicalstream.hammerklavier.contract.MaterialTable
import com.tropicalstream.hammerklavier.instrument.tex.Wood
import com.tropicalstream.hammerklavier.contract.MechanismPose
import com.tropicalstream.hammerklavier.contract.ProgramId
import com.tropicalstream.hammerklavier.contract.SkinKind
import com.tropicalstream.hammerklavier.contract.UprightFinish
import com.tropicalstream.hammerklavier.contract.VertexLayout
import com.tropicalstream.hammerklavier.contract.ViewId
import com.tropicalstream.hammerklavier.instrument.tex.InstrumentTextures
import com.tropicalstream.hammerklavier.testutil.AllocProbe
import com.tropicalstream.hammerklavier.contract.Pal
import com.tropicalstream.hammerklavier.testutil.AwtPainter
import com.tropicalstream.hammerklavier.testutil.MeshRaster
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** T7.1–T7.8 (PLAN §7.2 WP7) plus winding and a PNG review of every instrument × framing. */
class InstrumentsTest {
    private val look = InstrumentLook(UprightFinish.WALNUT, edgeOverlay = false)
    private fun scene(id: InstrumentId, lastDamper: Int = InstrumentProfile.of(id).lastDamper) =
        Instruments.create(id, look, lastDamper) as InstrumentSceneImpl
    private val all by lazy { InstrumentId.entries.associateWith { scene(it) } }
    private val meshes by lazy { all.mapValues { it.value.meshes() } }

    private fun slotLaneOffset(l: VertexLayout) = if (l == VertexLayout.STRING) 8 else 12

    // ── T7.1 triangle budgets ──
    @Test fun t71TriangleBudgets() {
        val budget = mapOf(InstrumentId.GRAND to 26_000, InstrumentId.UPRIGHT to 22_000, InstrumentId.HARPSICHORD to 20_000)
        for ((id, ms) in meshes) {
            val tris = ms.sumOf { it.triangleCount }
            println("T7.1 $id: $tris triangles in ${ms.size} meshes")
            assertTrue("$id $tris triangles", tris <= budget.getValue(id))
            assertTrue(tris > 3000)
        }
    }

    // ── T7.2 keys ──
    @Test fun t72Keyboard() {
        for ((id, pitchMm, nat, sh) in listOf(Q(InstrumentId.GRAND, 13.71f, 52, 36), Q(InstrumentId.UPRIGHT, 13.71f, 52, 36),
                                            Q(InstrumentId.HARPSICHORD, 13.25f, 36, 25))) {
            val kb = all.getValue(id).keyboard
            val p = InstrumentProfile.of(id)
            assertEquals(nat, kb.naturals); assertEquals(sh, kb.sharps)
            for (k in p.lowKey until p.highKey) assertEquals("$id pitch at $k", pitchMm, (kb.keyX[k + 1] - kb.keyX[k]) * 1000f, 0.01f)
            assertTrue(kb.keyX[p.lowKey - 1].isNaN() && kb.keyX[p.highKey + 1].isNaN())
            assertEquals(0f, (kb.headL[p.lowKey] + kb.headR[firstNat(kb, p.highKey)]) / 2f, 1e-5f)       // centred
            for (k in p.lowKey..p.highKey) {
                if (Keyboard.isBlack(k)) continue
                val cutL = kb.tailL[k] > kb.headL[k] + 1e-6f; val cutR = kb.tailR[k] < kb.headR[k] - 1e-6f
                val expectL = when (k % 12) { 2, 4, 7, 9, 11 -> k > p.lowKey; else -> false }
                val expectR = when (k % 12) { 0, 2, 5, 7, 9 -> k < p.highKey; else -> false }
                assertEquals("$id key $k left cut", expectL, cutL); assertEquals("$id key $k right cut", expectR, cutR)
            }
        }
        assertEquals(0.818f, all.getValue(InstrumentId.HARPSICHORD).keyboard.widthM, 0.002f)
    }

    private fun firstNat(kb: Keyboard, k: Int) = if (Keyboard.isBlack(k)) k - 1 else k
    private data class Q(val id: InstrumentId, val pitch: Float, val nat: Int, val sh: Int)

    // ── T7.3 skinned slots and lanes; T7.5 no NaNs; winding and unit normals ──
    @Test fun t73SkinLanesT75NoNansAndWinding() {
        for ((id, ms) in meshes) for (m in ms) {
            val f = m.layout.floats
            assertEquals(0, m.vertices.size % f)
            for (x in m.vertices) assertFalse("${m.name} NaN", x.isNaN() || x.isInfinite())
            for (t in m.indices) assertTrue((t.toInt() and 0xFFFF) < m.vertexCount)
            if (m.layout != VertexLayout.STATIC) {
                val o = slotLaneOffset(m.layout)
                for (i in 0 until m.vertexCount) {
                    val slot = m.vertices[i * f + o]
                    assertTrue("${m.name} slot $slot", slot >= 0f && slot < 34f && slot == slot.toInt().toFloat())
                    var ones = 0; var zeros = 0
                    for (l in 1..4) { val v = m.vertices[i * f + o + l]; if (v == 1f) ones++ else if (v == 0f) zeros++ }
                    assertEquals("${m.name} one-hot", 1, ones); assertEquals(3, zeros)
                }
            }
            if (m.layout != VertexLayout.STRING && m.program != ProgramId.RIBBON) checkWinding(m)
        }
    }

    private fun checkWinding(m: BakedMesh) {
        val f = m.layout.floats
        fun p(i: Int, c: Int) = m.vertices[i * f + c]
        var t = 0
        while (t < m.indices.size) {
            val a = m.indices[t].toInt() and 0xFFFF; val b = m.indices[t + 1].toInt() and 0xFFFF; val c = m.indices[t + 2].toInt() and 0xFFFF
            val ux = p(b, 0) - p(a, 0); val uy = p(b, 1) - p(a, 1); val uz = p(b, 2) - p(a, 2)
            val vx = p(c, 0) - p(a, 0); val vy = p(c, 1) - p(a, 1); val vz = p(c, 2) - p(a, 2)
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            for (i in intArrayOf(a, b, c)) {
                val l = sqrt(p(i, 3) * p(i, 3) + p(i, 4) * p(i, 4) + p(i, 5) * p(i, 5))
                assertEquals("${m.name} unit normal", 1f, l, 1e-4f)
                assertTrue("${m.name} winding", nx * p(i, 3) + ny * p(i, 4) + nz * p(i, 5) > 0f)
            }
            t += 3
        }
    }

    // ── T7.4 anchors ──
    @Test fun t74Anchors() {
        val cam = CameraPose()
        fun check(id: InstrumentId, v: ViewId, fr: Int, focus: Float, cent: Float, pos: FloatArray, tgt: FloatArray,
                  fov: Float, ipd: Float, zp: Float, room: Boolean, clip: Float, lid: Float) {
            all.getValue(id).anchors.camera(v, fr, focus, cent, cam)
            val tag = "$id $v $fr"
            assertArrayEquals(tag, pos, cam.pos, 1e-5f); assertArrayEquals(tag, tgt, cam.target, 1e-5f)
            assertEquals(tag, fov, cam.vFovDeg, 1e-5f); assertEquals(tag, ipd, cam.ipdScale, 1e-6f); assertEquals(tag, zp, cam.zeroParallaxM, 1e-6f)
            assertEquals(tag, room, cam.roomFrame); assertEquals(tag, lid, cam.lidLift, 0f)
            if (clip.isNaN()) assertTrue(tag, cam.clipX.isNaN()) else assertEquals(tag, clip, cam.clipX, 1e-5f)
        }
        val nan = Float.NaN
        for (id in listOf(InstrumentId.GRAND, InstrumentId.UPRIGHT)) {
            val kx = all.getValue(id).keyboard.keyX
            check(id, ViewId.PLAYER, 0, 60f, 60f, f(-0.10f, 1.30f, 1.55f), f(0f, 0.50f, -0.12f), 34f, 0.6f, 1.75f, false, nan, 0f)
            val xc = kx[72]
            check(id, ViewId.PLAYER, 1, 60f, 72f, f(xc, 1.15f, 0.62f), f(xc, 0.70f, -0.08f), 30f, 0.5f, 0.95f, false, nan, 0f)
        }
        val g = all.getValue(InstrumentId.GRAND).keyboard.keyX; val u = all.getValue(InstrumentId.UPRIGHT).keyboard.keyX
        val h = all.getValue(InstrumentId.HARPSICHORD).keyboard.keyX
        check(InstrumentId.HARPSICHORD, ViewId.PLAYER, 0, 60f, 60f, f(-0.06f, 1.22f, 1.05f), f(0f, 0.62f, -0.10f), 34f, 0.6f, 1.25f, false, nan, 0f)
        check(InstrumentId.HARPSICHORD, ViewId.PLAYER, 1, 60f, 50f, f(h[50], 1.10f, 0.55f), f(h[50], 0.66f, -0.08f), 30f, 0.5f, 0.85f, false, nan, 0f)
        check(InstrumentId.GRAND, ViewId.ACTION, 0, 64f, 60f, f(g[64] + 0.95f, 0.95f, 0.30f), f(g[64], 0.76f, -0.24f), 22f, 0.35f, 1.1f, false, g[64], 0f)
        check(InstrumentId.UPRIGHT, ViewId.ACTION, 0, 64f, 60f, f(u[64] + 1.05f, 1.00f, 0.10f), f(u[64], 0.93f, -0.20f), 26f, 0.35f, 1.1f, false, u[64], 0f)
        check(InstrumentId.HARPSICHORD, ViewId.ACTION, 0, 64f, 60f, f(h[64] + 0.60f, 0.93f, -0.02f), f(h[64], 0.86f, -0.42f), 24f, 0.3f, 0.73f, false, h[64], 0f)
        check(InstrumentId.GRAND, ViewId.ACTION, 1, 64f, 60f, f(0f, 1.95f, 0.55f), f(0f, 0.84f, -0.90f), 44f, 0.5f, 1.8f, false, nan, 1f)
        check(InstrumentId.UPRIGHT, ViewId.ACTION, 1, 64f, 60f, f(1.05f, 1.40f, 0.75f), f(0f, 1.06f, -0.28f), 36f, 0.5f, 1.5f, false, nan, 1f)
        check(InstrumentId.HARPSICHORD, ViewId.ACTION, 1, 64f, 60f, f(0f, 1.85f, 0.45f), f(0f, 0.80f, -0.95f), 44f, 0.5f, 1.8f, false, nan, 1f)
        for (id in listOf(InstrumentId.GRAND, InstrumentId.HARPSICHORD)) {
            check(id, ViewId.HALL, 0, 60f, 60f, f(0.4f, 1.20f, 3.0f), f(0f, 1.65f, -1.9f), 40f, 1f, 4.9f, true, nan, 0f)
            check(id, ViewId.HALL, 1, 60f, 60f, f(0.4f, 1.20f, 3.0f), f(0f, 1.05f, -1.9f), 18.27f, 1f, 4.9f, true, nan, 0f)
        }
        check(InstrumentId.UPRIGHT, ViewId.HALL, 0, 60f, 60f, f(0.4f, 1.20f, 3.0f), f(-1.6f, 1.60f, -2.65f), 40f, 1f, 6.0f, true, nan, 0f)
        check(InstrumentId.UPRIGHT, ViewId.HALL, 1, 60f, 60f, f(0.4f, 1.20f, 3.0f), f(-1.8f, 1.00f, -2.85f), 18.27f, 1f, 6.0f, true, nan, 0f)
        // Clamped outside the compass.
        all.getValue(InstrumentId.GRAND).anchors.camera(ViewId.ACTION, 0, 200f, 0f, cam)
        assertEquals(g[108], cam.clipX, 1e-5f)

        val out = FloatArray(3)
        fun lis(id: InstrumentId, v: ViewId, fr: Int, e: FloatArray, room: Boolean) {
            assertEquals("$id $v $fr", room, all.getValue(id).anchors.listener(v, fr, out)); assertArrayEquals("$id $v $fr", e, out, 1e-6f)
        }
        lis(InstrumentId.GRAND, ViewId.PLAYER, 0, f(0f, 1.20f, 0.55f), false); lis(InstrumentId.GRAND, ViewId.PLAYER, 1, f(0f, 1.20f, 0.55f), false)
        lis(InstrumentId.GRAND, ViewId.ACTION, 0, f(0.30f, 1.00f, -0.30f), false); lis(InstrumentId.GRAND, ViewId.ACTION, 1, f(0f, 1.60f, 0.10f), false)
        lis(InstrumentId.UPRIGHT, ViewId.PLAYER, 0, f(0f, 1.20f, 0.55f), false)
        lis(InstrumentId.UPRIGHT, ViewId.ACTION, 0, f(0.30f, 1.05f, 0.05f), false); lis(InstrumentId.UPRIGHT, ViewId.ACTION, 1, f(0.50f, 1.45f, 0.40f), false)
        lis(InstrumentId.HARPSICHORD, ViewId.PLAYER, 1, f(0f, 1.15f, 0.50f), false)
        lis(InstrumentId.HARPSICHORD, ViewId.ACTION, 0, f(0.20f, 0.95f, -0.20f), false); lis(InstrumentId.HARPSICHORD, ViewId.ACTION, 1, f(0f, 1.55f, 0.10f), false)
        for (id in InstrumentId.entries) for (fr in 0..1) lis(id, ViewId.HALL, fr, f(0.4f, 1.20f, 3.0f), true)
        assertArrayEquals(f(0f, 0.90f, -1.00f), all.getValue(InstrumentId.GRAND).anchors.soundSource, 0f)
        assertArrayEquals(f(0f, 1.00f, -0.45f), all.getValue(InstrumentId.UPRIGHT).anchors.soundSource, 0f)
        assertArrayEquals(f(0f, 0.85f, -1.00f), all.getValue(InstrumentId.HARPSICHORD).anchors.soundSource, 0f)
        assertArrayEquals(f(0f, 1.15f, 0.50f), all.getValue(InstrumentId.HARPSICHORD).anchors.benchEar, 0f)
        for (id in InstrumentId.entries) for (v in ViewId.entries) for (fr in 0..1) {
            val a = all.getValue(id).anchors
            AllocProbe.assertNoAllocation("$id $v $fr camera") { a.camera(v, fr, 64f, 60f, cam) }
            AllocProbe.assertNoAllocation("$id $v $fr listener") { a.listener(v, fr, out) }
        }
    }

    /** The major review fix: a tongue vertex decoded under a fully lifted 8′ / 4′ jack rises with it. */
    @Test fun t76TongueRidesJack() {
        val h = meshes.getValue(InstrumentId.HARPSICHORD)
        val tongueRgb = Geo.mul(Pal.ACTION_WOOD, 0.8f)
        for ((kind, restTop) in listOf(SkinKind.JACK_LIFT to HarpsichordModel.TONGUE_PIVOT_Y,
                                       SkinKind.JACK4_LIFT to HarpsichordModel.TONGUE_PIVOT_Y - (HarpsichordModel.STRING8_Y - HarpsichordModel.STRING4_Y))) {
            val m = h.single { it.skin == kind }
            val fl = m.layout.floats
            val travel = all.getValue(InstrumentId.HARPSICHORD).skin.p.getValue(kind)[0]
            var top = -1f; var bodyTop = -1f
            for (i in 0 until m.vertexCount) {
                val o = i * fl
                if (m.vertices[o + 12] != 0f || m.vertices[o + 13] != 1f) continue          // key 29: slot 0, lane 0
                val lifted = m.vertices[o + 1] + 1f * travel                                 // JACK_LIFT: y += value · travel
                val isTongue = (0..2).all { Math.abs(m.vertices[o + 8 + it] - tongueRgb[it] / 255f) < 1e-4f }
                if (isTongue) top = maxOf(top, lifted) else bodyTop = maxOf(bodyTop, lifted)
            }
            assertEquals("$kind tongue top lifted", restTop + travel, top, 1e-5f)
            assertTrue("$kind tongue stays inside its lifted jack", top < bodyTop)
        }
    }

    private fun f(vararg v: Float) = v

    // ── T7.6 counts and dimensions ──
    private fun parts(m: List<BakedMesh>): Set<Int> {
        val s = HashSet<Int>()
        for (x in m) {
            val f = x.layout.floats; val o = slotLaneOffset(x.layout)
            for (i in 0 until x.vertexCount) {
                var lane = 0; for (l in 0..3) if (x.vertices[i * f + o + 1 + l] == 1f) lane = l
                s.add(x.vertices[i * f + o].toInt() * 4 + lane)
            }
        }
        return s
    }

    @Test fun t76Counts() {
        val g = meshes.getValue(InstrumentId.GRAND)
        val strings = g.filter { it.skin == SkinKind.STRING }
        assertEquals(228, strings.sumOf { it.vertexCount } / StringsMesh.VERTS_PER_STRING)
        val p = InstrumentProfile.GRAND
        val spans = StringsMesh.grandSpans(all.getValue(InstrumentId.GRAND).keyboard, p, Geo.inset(GrandDims.rimOutline(), GrandDims.RIM_T))
        assertEquals(8, spans.count { it.key <= 28 }); assertEquals(40, spans.count { it.key in 29..48 }); assertEquals(180, spans.count { it.key >= 49 })
        assertEquals(88, parts(g.filter { it.skin == SkinKind.HAMMER_ROT }).size)
        assertEquals(68, parts(g.filter { it.skin == SkinKind.DAMPER_LIFT }).size)
        assertEquals((0 until 68).toSet(), parts(g.filter { it.skin == SkinKind.DAMPER_LIFT }))
        for (ld in intArrayOf(80, 88, 92)) {
            val d = scene(InstrumentId.GRAND, ld).meshes().filter { it.skin == SkinKind.DAMPER_LIFT }
            assertEquals(ld - 20, parts(d).size)
        }
        val caseB = Geo.bounds(g.filter { it.drawSlot == 7 })
        assertEquals(1.49f, caseB[3] - caseB[0], 0.01f); assertEquals(2.00f, caseB[5] - caseB[2], 0.01f)
        assertEquals(0f, caseB[1], 1e-4f)

        val u = meshes.getValue(InstrumentId.UPRIGHT)
        val ub = Geo.bounds(u)
        assertEquals(1.31f, ub[4] - ub[1], 0.005f)
        assertEquals(0.050f, UprightModel.STRIKE_Y - UprightModel.DAMPER_Y, 1e-6f)
        val db = Geo.bounds(u.filter { it.skin == SkinKind.DAMPER_LIFT && it.name.startsWith("upright.dampers") })
        assertEquals(UprightModel.DAMPER_Y + 0.015f, db[4], 0.001f)             // felt centred on the 1.03 m contact line
        assertTrue("upright dampers under the strike line", db[4] < UprightModel.STRIKE_Y - 0.02f)
        val hb = Geo.bounds(u.filter { it.skin == SkinKind.HAMMER_ROT })
        assertTrue(hb[1] < UprightModel.STRIKE_Y && hb[4] > UprightModel.STRIKE_Y)
        assertEquals(90 - 20, parts(u.filter { it.skin == SkinKind.DAMPER_LIFT }).size)
        assertEquals(228, u.filter { it.skin == SkinKind.STRING }.sumOf { it.vertexCount } / StringsMesh.VERTS_PER_STRING)
        val uCase = Geo.bounds(u.filter { it.drawSlot == 7 || it.drawSlot == 8 })
        assertEquals(1.53f, uCase[3] - uCase[0], 0.005f); assertEquals(0.65f, uCase[5] - uCase[2], 0.005f)

        val h = meshes.getValue(InstrumentId.HARPSICHORD)
        val jacks = parts(h.filter { it.skin == SkinKind.JACK_LIFT }).size + parts(h.filter { it.skin == SkinKind.JACK4_LIFT }).size
        assertEquals(122, jacks)
        assertTrue("tongues ride their jack draw", h.none { it.skin == SkinKind.TONGUE_ROT || it.skin == SkinKind.TONGUE4_ROT })
        assertEquals(122, h.filter { it.skin == SkinKind.STRING }.sumOf { it.vertexCount } / StringsMesh.VERTS_PER_STRING)
        val hc = Geo.bounds(h.filter { it.name == "harpsichord.case" })
        val width = hc[3] - hc[0]
        val kbw = all.getValue(InstrumentId.HARPSICHORD).keyboard.widthM
        println("T7.6 harpsichord case ${width} × ${hc[5] - hc[2]} × ${hc[4] - hc[1]}, keyboard $kbw")
        assertEquals(0.93f, width, 0.005f); assertEquals(2.28f, hc[5] - hc[2], 0.01f); assertEquals(0.26f, hc[4] - hc[1], 0.001f)
        assertTrue(width >= kbw + 2 * (HarpsichordModel.CHEEK + HarpsichordModel.SIDE))
        assertTrue(h.none { it.skin == SkinKind.DAMPER_LIFT || it.skin == SkinKind.HAMMER_ROT || it.skin == SkinKind.PEDAL_ROT })
    }

    // ── T7.7 packActionSet ──
    @Test fun t77PackActionSet() {
        for (id in InstrumentId.entries) {
            val sc = all.getValue(id)
            val p = InstrumentProfile.of(id)
            val pose = MechanismPose()
            for (k in p.lowKey..p.highKey) {
                pose.keyDip[k] = ((k * 37) % 100) / 100f; pose.hammer[k] = ((k * 53) % 100) / 100f
                pose.damper[k] = ((k * 17) % 100) / 100f; pose.escape[k] = ((k * 11) % 100) / 100f
                pose.tongue[k] = ((k * 7) % 100) / 100f; pose.tongue4[k] = ((k * 3) % 100) / 100f; pose.jack4[k] = ((k * 29) % 100) / 100f
            }
            pose.keyDip[66] = 0f; pose.hammer[66] = 0f; pose.damper[66] = 0f; pose.jack4[66] = 0f
            pose.escape[66] = 0f; pose.tongue[66] = 0f; pose.tongue4[66] = 0f
            for (k in intArrayOf(60, 61)) { pose.keyDip[k] = 0f; pose.hammer[k] = 0f; pose.damper[k] = 0f; pose.jack4[k] = 0f; pose.tongue4[k] = 0f }
            pose.escape[60] = 0.5f; pose.tongue[60] = 0f; pose.escape[61] = 0f; pose.tongue[61] = 0.5f   // escape-only, tongue-only: active
            pose.registers = 2
            val a = FloatArray(136) { 7f }; val b = FloatArray(136)
            sc.packActionSet(pose, 63.6f, a); sc.packActionSet(pose, 63.6f, b)
            assertArrayEquals(a, b, 0f)
            // 13 slots centred on round(63.6) = 64.
            for (s in 0 until 13) {
                val k = 58 + s
                assertEquals(sc.keyboard.keyX[k], a[4 * s], 0f); assertEquals(k.toFloat(), a[4 * s + 2], 0f); assertEquals(0f, a[4 * s + 3], 0f)
                assertEquals(if (k == 66) 0.4f else 1f, a[4 * s + 1], 0f)
                val o = 52 + 6 * s
                when (id) {
                    InstrumentId.GRAND -> {
                        assertEquals(pose.keyDip[k] * GrandDims.KEY_MAX_RAD, a[o], 1e-7f)
                        assertEquals(pose.hammer[k] * GrandActionMesh.BLOW_RAD, a[o + 4], 1e-7f)
                        assertEquals(pose.damper[k] * GrandActionMesh.UNDERLEVER_RAD, a[o + 5], 1e-7f)
                    }
                    InstrumentId.UPRIGHT -> assertEquals(pose.hammer[k] * UprightModel.BLOW / UprightModel.HAMMER_R, a[o + 4], 1e-7f)
                    InstrumentId.HARPSICHORD -> {
                        assertEquals(pose.hammer[k] * 0.006f, a[o], 1e-7f); assertEquals(0.0015f, a[o + 2], 0f)
                        assertEquals(pose.jack4[k] * 0.006f, a[o + 4], 1e-7f)
                    }
                }
                // The shader's addressing: vec4 13 + (6s + p) / 4, lane (6s + p) % 4.
                for (pp in 0..5) assertEquals(a[o + pp], a[4 * ActionSetPacker.slotOf(s, pp) + ActionSetPacker.laneOf(s, pp)], 0f)
            }
            if (id == InstrumentId.HARPSICHORD) { assertEquals(0.0015f, a[132], 0f); assertEquals(0f, a[133], 0f) }
            else for (i in 130 until 136) assertEquals(0f, a[i], 0f)
            // Clamped window at the ends, and zeros without a cut.
            sc.packActionSet(pose, 0f, a); assertEquals((p.lowKey).toFloat(), a[2], 0f)
            sc.packActionSet(pose, 500f, a); assertEquals((p.highKey).toFloat(), a[4 * 12 + 2], 0f)
            sc.packActionSet(pose, Float.NaN, a); assertTrue(a.all { it == 0f })
            AllocProbe.assertNoAllocation("$id packActionSet") { for (i in 0 until 100) sc.packActionSet(pose, 40f + i * 0.4f, b) }
            // Every ACTION_SET vertex: uv = (s, p) and slot/lane address that part's value.
            val m = meshes.getValue(id).single { it.skin == SkinKind.ACTION_SET }
            for (i in 0 until m.vertexCount) {
                val s = m.vertices[i * 17 + 6].toInt(); val pp = m.vertices[i * 17 + 7].toInt()
                assertTrue(s in 0..12 && pp in 0..5)
                assertEquals(ActionSetPacker.slotOf(s, pp).toFloat(), m.vertices[i * 17 + 12], 0f)
                assertEquals(1f, m.vertices[i * 17 + 13 + ActionSetPacker.laneOf(s, pp)], 0f)
            }
            assertEquals(18, sc.skin.p.getValue(SkinKind.ACTION_SET).size)
        }
    }

    // ── T7.8 programs and merge keys ──
    @Test fun t78ProgramsAndDrawCounts() {
        // §5.3 instrument rows 7–18 and the framings their "Shown in" column names (bit order Player,
        // Player follow, cutaway, overhead, Hall, Hall close).
        val rowMask = mapOf(7 to VM.ALL, 8 to VM.NO_OVER, 9 to VM.ACTION_HALL, 10 to VM.ALL, 11 to VM.ACTION_HALL,
            12 to VM.ACTION_HALL, 13 to VM.ACTION, 14 to VM.ACTION_HALL, 15 to VM.PLAYER_HALL, 16 to VM.CUT, 17 to VM.CUT, 18 to VM.ALL)
        // Per framing: 28 hard budget − venue rows 1–6 (at most 6 draws, every level) − glyphs (row 19) − fade/sync
        // (row 22) − the pedal inset (rows 20–21, Player follow only). WP8's T8.8 holds the venue to its 6.
        val venueMax = 6; val glyphFade = 2
        val limits = IntArray(6) { bit -> 28 - venueMax - glyphFade - (if (bit == 1) 2 else 0) }
        for ((id, ms) in meshes) {
            for (m in ms) {
                when {
                    m.layout == VertexLayout.STRING -> assertEquals(m.name, ProgramId.STRING, m.program)
                    m.layout == VertexLayout.SKINNED -> assertEquals(m.name, ProgramId.SKINNED, m.program)
                    else -> assertEquals(m.name, MaterialTable.of(m.material).program, m.program)
                }
                if (m.skin == SkinKind.STATIC || m.skin == SkinKind.LID) assertEquals(m.name, VertexLayout.STATIC, m.layout)
                assertTrue(m.name, m.drawSlot in 7..18)
                assertTrue(m.name, m.viewMask in 1..63 && m.levelMask in 1..15)
                assertEquals("${m.name} shown only where §5.3 row ${m.drawSlot} is", 0, m.viewMask and rowMask.getValue(m.drawSlot).inv())
                if (m.texture != null) assertTrue(m.name, m.texture in all.getValue(id).textures().map { it.name })
            }
            for (bit in 0 until 6) {
                val keys = ms.filter { it.viewMask and (1 shl bit) != 0 }
                    .map { listOf(it.program, it.material, it.skin, it.texture, it.levelMask, it.viewMask, it.clipped, it.drawSlot) }.toSet()
                println("T7.8 $id framing bit $bit: ${keys.size} draws")
                assertTrue("$id framing $bit: ${keys.size}", keys.size <= limits[bit])
                assertTrue("$id framing $bit total", keys.size + venueMax + glyphFade + (if (bit == 1) 2 else 0) <= 28)
            }
        }
        val edge = Instruments.create(InstrumentId.GRAND, InstrumentLook(UprightFinish.WALNUT, true), 88).meshes().single { it.drawSlot == 18 }
        assertEquals(15, edge.levelMask)
        assertEquals(8, meshes.getValue(InstrumentId.GRAND).single { it.drawSlot == 18 }.levelMask)
    }

    @Test fun uprightFinishes() {
        for (f in UprightFinish.entries) {
            val ms = Instruments.create(InstrumentId.UPRIGHT, InstrumentLook(f, false), 90).meshes()
            val case = ms.single { it.name == "upright.case" }
            assertEquals(if (f == UprightFinish.EBONY) ProgramId.LACQUER else ProgramId.LIT, case.program)
        }
    }

    /** M8 family look: the grand's ebony lacquer trims the upright and harpsichord; the harpsichord's inner walls are paper. */
    @Test fun familyLacquerAndPaper() {
        val u = Instruments.create(InstrumentId.UPRIGHT, InstrumentLook(UprightFinish.WALNUT, false), 90).meshes()
        for (n in listOf("upright.lacquer", "upright.toplid")) {
            val m = u.single { it.name == n }
            assertEquals(n, MaterialId.LACQUER, m.material); assertEquals(n, ProgramId.LACQUER, m.program)
        }
        assertEquals(Wood.NAME, u.single { it.name == "upright.case" }.texture)
        val h = meshes.getValue(InstrumentId.HARPSICHORD)
        for (n in listOf("harpsichord.lacquer", "harpsichord.lid")) assertEquals(n, ProgramId.LACQUER, h.single { it.name == n }.program)
        assertEquals(Wood.NAME, h.single { it.name == "harpsichord.case" }.texture)
        val paper = h.single { it.name == "harpsichord.paperwalls" }
        assertEquals(MaterialId.PAPER, paper.material); assertEquals(null, paper.texture)
        val pr = Geo.mul(Pal.FLEMISH_PAPER, 0.6f)
        for (i in 0 until paper.vertexCount) for (ch in 0..2)
            assertEquals(pr[ch] / 255f, paper.vertices[i * paper.layout.floats + 8 + ch], 0.01f)
    }

    @Test fun texturesPaint() {
        val dir = File("build/shots/textures").apply { mkdirs() }
        for (id in InstrumentId.entries) for (t in all.getValue(id).textures()) {
            val p = AwtPainter()
            p.begin(t.width, t.height); t.paint(p); val bytes = p.end()
            assertEquals(t.width * t.height * 4, bytes.size)
            var lit = 0; for (i in 0 until bytes.size / 4) if ((bytes[4 * i + 3].toInt() and 255) > 0) lit++
            assertTrue("${t.name} painted", lit > 100)
            p.writePng(File(dir, "${t.name}.png"))
        }
        assertEquals(setOf(InstrumentTextures.HARPSI_PAPER, InstrumentTextures.HARPSI_LID, InstrumentTextures.HARPSI_SOUNDBOARD,
            com.tropicalstream.hammerklavier.instrument.tex.Wood.NAME),
            all.getValue(InstrumentId.HARPSICHORD).textures().map { it.name }.toSet())
    }

    /** PNG review (PLAN §5.4): every instrument × framing into build/shots/ (not an assertion of looks, only coverage). */
    @Test fun reviewShots() {
        val dir = File("build/shots").apply { mkdirs() }
        val cam = CameraPose()
        for (id in InstrumentId.entries) for (v in ViewId.entries) for (fr in 0..1) {
            val sc = all.getValue(id)
            sc.anchors.camera(v, fr, 64f, 60f, cam)
            val eye = cam.pos.copyOf(); val tgt = cam.target.copyOf()
            if (cam.roomFrame) { toPiano(id, eye); toPiano(id, tgt) }
            val bit = 1 shl (v.ordinal * 2 + fr)
            val ms = meshes.getValue(id).filter { it.viewMask and bit != 0 && it.levelMask and 1 != 0 }
            val img = MeshRaster.render(ms, MeshRaster.View(eye, tgt, cam.vFovDeg), clipX = cam.clipX)
            javax.imageio.ImageIO.write(img, "png", File(dir, "${id.key}_${v.name.lowercase()}_$fr.png"))
            val cov = MeshRaster.coverage(img)
            assertTrue("$id $v $fr coverage $cov", cov > 0.01f)
        }
    }

    private fun toPiano(id: InstrumentId, p: FloatArray) {
        val pl = KonzertzimmerAcoustics.PLACEMENTS.getValue(id)
        val x = p[0] - pl.originRoom[0]; val y = p[1] - pl.originRoom[1]; val z = p[2] - pl.originRoom[2]
        val c = cos(pl.yawRad); val s = sin(pl.yawRad)
        // Inverse of R_y: (x, z) → (x cos − z sin, x sin + z cos).
        p[0] = x * c - z * s; p[1] = y; p[2] = x * s + z * c
        assertTrue(abs(p[0]) < 100f)
    }
}
