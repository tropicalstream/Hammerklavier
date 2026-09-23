package com.tropicalstream.hammerklavier.kit

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.KeyMap
import com.tropicalstream.hammerklavier.contract.Temperament
import com.tropicalstream.hammerklavier.contract.TuningSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** T4.2: KeyMapBuilder as properties. */
class KeyMapBuilderTest {
    private val tunings = listOf(TuningSpec.A440_EQUAL, TuningSpec(415f, Temperament.EQUAL), TuningSpec.A415_WERCKMEISTER,
        TuningSpec(440f, Temperament.KELLNER))
    private val ALL = -1L

    private fun cents(rate: Float): Double = 1200.0 * ln(rate.toDouble()) / ln(2.0)
    private fun sk(m: KeyMap, stop: Int, layer: Int, k: Int) = (stop * m.layers + layer) * HK.KEYS + k

    private fun kits(): List<Pair<String, KitIndex>> = listOf(440f, 415f).flatMap { a ->
        listOf("fixture@$a" to ToyKit(pitchCents = { _, _ -> ToyKit.cents(a) }).index(),
               "grandHd@$a" to ToyKit.grandHd(a).index(), "grandStd@$a" to ToyKit.grandStd(aHz = a).index(),
               "harpsichord@$a" to ToyKit.harpsichord(a).index())
    }

    private fun range(idx: KitIndex, stop: Int): IntRange {
        val r = idx.regions.filter { it.kind == RegionKind.SUSTAIN && it.stop == stop }
        return r.minOf { it.lo }..r.maxOf { it.hi }
    }

    @Test fun everyKeyAndVelocityMapsToAReadyRegion() {
        for ((name, idx) in kits()) for (t in tunings) {
            val m = KeyMapBuilder.build(idx, t, ALL)
            for (stop in 0 until idx.stopCount) for (k in range(idx, stop)) for (v in 1..127) {
                for (layer in listOf(m.velLayerA[v].toInt(), m.velLayerB[v].toInt()).filter { it >= 0 }) {
                    val r = m.region[sk(m, stop, layer, k)]
                    assertTrue("$name $t stop $stop key $k vel $v", r >= 0)
                    val reg = idx.regions[r]
                    assertEquals(RegionKind.SUSTAIN, reg.kind)
                    assertTrue(reg.layer == layer)
                }
                assertTrue(m.velLayerA[v] >= 0)
            }
        }
    }

    @Test fun outputPitchWithinHalfCentOfTarget() {
        for ((name, idx) in kits()) for (t in tunings) {
            val m = KeyMapBuilder.build(idx, t, ALL)
            for (stop in 0 until idx.stopCount) for (layer in 0 until idx.layerCount) for (k in range(idx, stop)) {
                val i = sk(m, stop, layer, k)
                val reg = idx.regions[m.region[i]]
                val out = reg.nativeCents + cents(m.rate[i])
                val target = KeyMapBuilder.targetCents(idx, t, k, idx.stops[stop].octaveSemis)
                assertEquals("$name $t stop $stop key $k", target.toDouble(), out, 0.5)
            }
        }
    }

    @Test fun shiftsAreBounded() {
        for (a in listOf(440f, 415f)) for (t in listOf(TuningSpec.A440_EQUAL, TuningSpec(415f, Temperament.EQUAL))) {
            // Matched standard: ≤ 1 semitone; across standards the −101.27 cent offset can add 1.27 cents.
            val slack = if (abs(a - t.aHz) < 0.01f) 0.01 else 1.3
            val h = ToyKit.harpsichord(a).index()
            val mh = KeyMapBuilder.build(h, t, ALL)
            // Across standards the 4′ roots 74/76 cannot reach key 89 within a semitone (it would need
            // a root 77): the harpsichord property holds for the recording's own standard.
            if (slack < 1.0) for (k in 29..89) assertTrue("8' key $k at $a/$t: ${cents(mh.rate[k])}", abs(cents(mh.rate[k])) <= 100 + slack)
            val g = ToyKit.grandHd(a).index()
            val mg = KeyMapBuilder.build(g, t, ALL)
            for (layer in 0 until 16) for (k in 21..108) assertTrue(abs(cents(mg.rate[sk(mg, 0, layer, k)])) <= 150 + slack)
        }
    }

    @Test fun harpsichordBorrowsFourFootForTopKeys() {
        val h = ToyKit.harpsichord().index()
        val m = KeyMapBuilder.build(h, TuningSpec.A440_EQUAL, ALL)
        for (k in 86..89) {
            val r = h.regions[m.region[k]]
            assertEquals("key $k borrows", 1, r.stop)
            assertTrue(r.root == 74 || r.root == 76)
            assertEquals(6000f, m.lpHz[k], 0f)
            assertEquals(10.0.pow(-1.5 / 20).toFloat() * 10.0.pow(0.0).toFloat(), m.gain[k], 1e-5f)
        }
        val r85 = h.regions[m.region[85]]
        assertEquals(0, r85.stop); assertEquals(84, r85.root); assertEquals(100.0, cents(m.rate[85]), 0.01)
        for (k in 29..85) assertEquals(0f, m.lpHz[k], 0f)
    }

    @Test fun hardLayersMatchSalamanderSplits() {
        val g = ToyKit.grandHd().index()
        val m = KeyMapBuilder.build(g, TuningSpec.A440_EQUAL, ALL)
        for (v in 1..127) {
            val expect = ToyKit.SALAMANDER_SPLITS.indexOfFirst { v in it.first..it.second }
            assertEquals("vel $v", expect, m.velLayerA[v].toInt())
            assertEquals(-1, m.velLayerB[v].toInt())
        }
    }

    private fun summedLevelDb(idx: KitIndex, m: KeyMap, v: Int, power: Boolean): Double {
        val c = idx.levelCurve[0]
        val a = m.velLayerA[v].toInt(); val b = m.velLayerB[v].toInt()
        val la = m.velGainA[v] * 10.0.pow(c[a].db / 20.0)
        val lb = if (b >= 0) m.velGainB[v] * 10.0.pow(c[b].db / 20.0) else 0.0
        return 20 * log10(if (power) sqrt(la * la + lb * lb) else la + lb)
    }

    @Test fun crossfadeLevelFollowsTheCurve() {
        for (law in listOf("gain", "power")) {
            val idx = ToyKit.grandStd(law).index()
            val m = KeyMapBuilder.build(idx, TuningSpec.A440_EQUAL, ALL)
            var crossfaded = 0
            for (v in 1..127) {
                val target = KeyMapBuilder.targetDb(idx, idx.levelCurve[0], v.toFloat())
                assertEquals("$law vel $v", target.toDouble(), summedLevelDb(idx, m, v, law == "power"), 0.5)
                if (m.velLayerB[v] >= 0) crossfaded++
            }
            assertTrue(crossfaded >= 5 * 7)
            // Continuity across every split: no step above 1 dB between adjacent velocities.
            for (v in 2..127) assertTrue(abs(summedLevelDb(idx, m, v, law == "power") - summedLevelDb(idx, m, v - 1, law == "power")) < 1.0)
        }
    }

    @Test fun onlyV10ReadyMapsEverythingToV10() {
        val idx = ToyKit.grandStd().index()
        val v10 = 3
        val mask = (1L shl idx.unitOf(0, v10)) or (1L shl 62) or (1L shl 63)
        val m = KeyMapBuilder.build(idx, TuningSpec.A440_EQUAL, mask)
        val loud = idx.levelCurve[0][v10].db
        for (v in 1..127) {
            assertEquals(v10, m.velLayerA[v].toInt()); assertEquals(-1, m.velLayerB[v].toInt())
            val target = KeyMapBuilder.targetDb(idx, idx.levelCurve[0], v.toFloat())
            val d = (target - loud).coerceIn(-6f, 6f)
            assertEquals("vel $v", d.toDouble(), 20 * log10(m.velGainA[v].toDouble()), 0.01)
        }
        for (layer in 0 until 6) for (k in 21..108) {
            val r = m.region[sk(m, 0, layer, k)]
            if (layer == v10) assertTrue(r >= 0) else assertEquals(-1, r)
        }
    }

    @Test fun substituteTiesGoLouder() {
        // Layers v7 (54) and v13 (101) around v10 (77): 23 vs 24 → v7 is nearer; make it a tie.
        val idx = ToyKit(mode = "HARD", layers = listOf(Triple(1, 40, 20), Triple(41, 80, 60), Triple(81, 127, 100)),
            layerDb = { -20f + 10f * it }).index()
        val mask = (1L shl 0) or (1L shl 2) or (3L shl 62)
        val m = KeyMapBuilder.build(idx, TuningSpec.A440_EQUAL, mask)
        for (v in 41..80) assertEquals(2, m.velLayerA[v].toInt())
        for (v in 1..40) assertEquals(0, m.velLayerA[v].toInt())
    }

    @Test fun fourFootNotReadyMeansNoFourFoot() {
        val h = ToyKit.harpsichord().index()
        val mask = ALL and (1L shl h.unitOf(1, 0)).inv()
        val m = KeyMapBuilder.build(h, TuningSpec.A415_WERCKMEISTER, mask)
        for (k in 0 until 128) assertEquals(-1, m.region[sk(m, 1, 0, k)])
        // The 8′ can no longer borrow either.
        for (k in 86..89) assertEquals(0, h.regions[m.region[k]].stop)
        for (k in 29..89) assertTrue(m.region[k] >= 0)
    }

    @Test fun releasesAndPedalsNeedTheirUnits() {
        val idx = ToyKit.grandStd().index()
        val all = KeyMapBuilder.build(idx, TuningSpec.A440_EQUAL, ALL)
        assertTrue((21..108).all { all.release[it] >= 0 })
        assertEquals(2, all.pedalDown.size); assertEquals(2, all.pedalUp.size)
        assertEquals(0.1f, all.pedalGain, 1e-5f)
        val none = KeyMapBuilder.build(idx, TuningSpec.A440_EQUAL, ALL and (3L shl 62).inv())
        assertTrue((0..127).all { none.release[it] == -1 })
        assertEquals(0, none.pedalDown.size)
    }

    @Test fun f0AndOnsetFormulas() {
        for ((name, idx) in kits()) for (t in tunings) {
            val m = KeyMapBuilder.build(idx, t, ALL)
            for (k in 0 until 128) {
                val f = 440.0 * 2.0.pow((KeyMapBuilder.targetCents(idx, t, k) - 6900.0) / 1200.0)
                assertEquals("$name f0 $k", f, m.f0Hz[k].toDouble(), f * 1e-5)
            }
            for (i in m.region.indices) if (m.region[i] >= 0)
                assertEquals(name, (idx.regions[m.region[i]].onsetFrame / m.rate[i].toDouble()).roundToInt(), m.onsetOut[i])
        }
    }

    @Test fun workedExampleA415Key61() {
        val idx = ToyKit.fixtureLike().index()
        val m = KeyMapBuilder.build(idx, TuningSpec(415f, Temperament.EQUAL), ALL)
        for (layer in 0 until 2) {
            val i = sk(m, 0, layer, 61)
            assertEquals(60, idx.regions[m.region[i]].root)
            assertEquals(-1.27, cents(m.rate[i]), 0.01)
        }
    }

    @Test fun recordedAtA415PlaysOwnRootsAtA415() {
        val idx = ToyKit.grandHd(415f).index()
        val m = KeyMapBuilder.build(idx, TuningSpec(415f, Temperament.EQUAL), ALL)
        for (i in 0 until 30) { val k = 21 + 3 * i; assertEquals(0.0, cents(m.rate[k]), 0.01); assertEquals(k, idx.regions[m.region[k]].root) }
        val m440 = KeyMapBuilder.build(idx, TuningSpec.A440_EQUAL, ALL)
        // At A440 the root one key higher wins: root 63 (sounding ≈ 62) plays key 62.
        assertEquals(63, idx.regions[m440.region[62]].root)
    }

    @Test fun deterministic() {
        val idx = ToyKit.harpsichord().index()
        val a = KeyMapBuilder.build(idx, TuningSpec.A415_WERCKMEISTER, ALL)
        val b = KeyMapBuilder.build(idx, TuningSpec.A415_WERCKMEISTER, ALL)
        assertTrue(a.region.contentEquals(b.region) && a.rate.contentEquals(b.rate) && a.velGainA.contentEquals(b.velGainA))
    }

    @Test fun wp11FixtureWorkedExample() {
        val json = String(javaClass.classLoader.getResourceAsStream("wp11/map_fixture.json")!!.readBytes())
        val env = javaClass.classLoader.getResourceAsStream("wp11/env_fixture.bin")!!.readBytes()
        val idx = (KitMapCodec.decode(json, env) as KitMapCodec.Result.Ok).index
        val m = KeyMapBuilder.build(idx, TuningSpec(415f, Temperament.EQUAL), ALL)
        val i = 61
        assertEquals(60, idx.regions[m.region[i]].root)
        assertEquals(-1.27, cents(m.rate[i]), 0.01)
    }
}
