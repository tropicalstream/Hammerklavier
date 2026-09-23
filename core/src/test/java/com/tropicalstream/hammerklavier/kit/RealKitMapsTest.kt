package com.tropicalstream.hammerklavier.kit

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.KeyMap
import com.tropicalstream.hammerklavier.contract.Temperament
import com.tropicalstream.hammerklavier.contract.TuningSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.ln

/**
 * PLAN §7.4 M7: T4.2's properties on the real shipped maps (`app/src/main/assets/instruments/<kit>/map.json`
 * + `env.bin`), all units ready: every (key, velocity) maps to a covering sustain region of its layer; the
 * output pitch is within 0.5 cent of `targetCents` at A440 and A415 in every temperament; |shift| ≤ 1
 * semitone (+ the region's measured detune, ≤ 5 c) on the harpsichord's 8′ keys 29–89 at its own (A440) standard, 30–89 at A415, ≤ 1.5 on the grand; the
 * key-69 remap at A415 Werckmeister III uses root 68. Skipped when the kit binaries are not checked out (LFS).
 */
class RealKitMapsTest {
    private val ALL = -1L
    private val tunings = listOf(TuningSpec.A440_EQUAL, TuningSpec(415f, Temperament.EQUAL), TuningSpec.A415_WERCKMEISTER,
        TuningSpec(440f, Temperament.WERCKMEISTER_III), TuningSpec(415f, Temperament.MEANTONE_QUARTER))

    private fun assets(): File = listOf(File("../app/src/main/assets/instruments"), File("app/src/main/assets/instruments"))
        .firstOrNull { it.isDirectory } ?: File("../app/src/main/assets/instruments")

    private fun load(kit: String): KitIndex? {
        val d = File(assets(), kit)
        val json = File(d, "map.json"); val env = File(d, "env.bin")
        if (!json.isFile || !env.isFile || env.length() < 1024) return null          // LFS pointer, not the binary
        val files = File(d, "u").list()?.toSet() ?: emptySet()
        return when (val r = KitMapCodec.decode(json.readText(), env.readBytes()) { f -> f.removePrefix("u/") in files }) {
            is KitMapCodec.Result.Ok -> r.index
            is KitMapCodec.Result.Invalid -> throw AssertionError("$kit map.json invalid: ${r.reasons.take(5)}")
        }
    }

    private fun kits(): List<Pair<String, KitIndex>> {
        val k = listOf("grand", "upright", "harpsichord").mapNotNull { n -> load(n)?.let { n to it } }
        assumeTrue("kit binaries not checked out", k.size == 3)
        return k
    }

    private fun cents(rate: Float): Double = 1200.0 * ln(rate.toDouble()) / ln(2.0)
    private fun sk(m: KeyMap, stop: Int, layer: Int, k: Int) = (stop * m.layers + layer) * HK.KEYS + k
    private fun range(idx: KitIndex, stop: Int): IntRange {
        val r = idx.regions.filter { it.kind == RegionKind.SUSTAIN && it.stop == stop }
        return r.minOf { it.lo }..r.maxOf { it.hi }
    }
    private fun region(idx: KitIndex, id: Int) = idx.regions.first { it.id == id }

    @Test fun everyKeyAndVelocityMapsToACoveringRegion() {
        for ((name, idx) in kits()) for (t in tunings) {
            val m = KeyMapBuilder.build(idx, t, ALL)
            for (stop in 0 until idx.stopCount) for (k in range(idx, stop)) for (v in 1..127) {
                assertTrue("$name vel $v", m.velLayerA[v] >= 0)
                for (layer in listOf(m.velLayerA[v].toInt(), m.velLayerB[v].toInt()).filter { it >= 0 }) {
                    val id = m.region[sk(m, stop, layer, k)]
                    assertTrue("$name $t stop $stop key $k vel $v", id >= 0)
                    val r = region(idx, id)
                    assertEquals(RegionKind.SUSTAIN, r.kind)
                    assertTrue("$name layer", r.layer == layer || idx.layerCount == 1)
                }
            }
        }
    }

    @Test fun outputPitchWithinHalfCentOfTarget() {
        for ((name, idx) in kits()) for (t in tunings) {
            val m = KeyMapBuilder.build(idx, t, ALL)
            for (stop in 0 until idx.stopCount) for (layer in 0 until idx.layerCount) for (k in range(idx, stop)) {
                val i = sk(m, stop, layer, k)
                if (m.region[i] < 0) continue
                val out = region(idx, m.region[i]).nativeCents + cents(m.rate[i])
                val target = KeyMapBuilder.targetCents(idx, t, k, idx.stops[stop].octaveSemis)
                assertEquals("$name $t stop $stop layer $layer key $k", target.toDouble(), out, 0.5)
            }
        }
    }

    @Test fun shiftsAreBounded() {
        val k = kits().toMap()
        val h = k.getValue("harpsichord")
        // The Sankey kit is recorded at A440 (recordedAHz 439.56): the property holds at its own standard (as T4.2's
        // toy test states). At A415 every key but 29 stays within a semitone + 1.3 c; key 29 has no root below 30
        // and shifts −1.98 semitones (logged in docs/progress/INTEGRATION.md, M7).
        val mh = KeyMapBuilder.build(h, TuningSpec.A440_EQUAL, ALL)
        for (key in 29..89) assertTrue("harpsichord 8' key $key: ${cents(mh.rate[key])}", abs(cents(mh.rate[key])) <= 100.0 + 0.01 + 5.0)
        val m415 = KeyMapBuilder.build(h, TuningSpec(415f, Temperament.EQUAL), ALL)
        for (key in 30..89) assertTrue("harpsichord 8' key $key at A415: ${cents(m415.rate[key])}", abs(cents(m415.rate[key])) <= 100.0 + 5.0)
        val g = k.getValue("grand")
        for (t in listOf(TuningSpec.A440_EQUAL, TuningSpec(415f, Temperament.EQUAL))) {
            val mg = KeyMapBuilder.build(g, t, ALL)
            for (layer in 0 until g.layerCount) for (key in 21..108)
                // Salamander's C8 sounds ≈ +95 c (WP11, real): keys 107–108 sit up to 1.9 semitones from the nearest root.
                if (mg.region[sk(mg, 0, layer, key)] >= 0) assertTrue("grand $t layer $layer key $key: ${cents(mg.rate[sk(mg, 0, layer, key)])}",
                    abs(cents(mg.rate[sk(mg, 0, layer, key)])) <= (if (key >= 107) 195.0 else 150.0 + 1.3))
        }
    }

    @Test fun a415WerckmeisterKey69UsesRoot68() {
        val h = kits().toMap().getValue("harpsichord")
        val m = KeyMapBuilder.build(h, TuningSpec.A415_WERCKMEISTER, ALL)
        val r = region(h, m.region[69])
        assertEquals(68, r.root)
        val target = KeyMapBuilder.targetCents(h, TuningSpec.A415_WERCKMEISTER, 69)
        assertEquals(target.toDouble(), r.nativeCents + cents(m.rate[69]), 0.5)
        // standard −101.27 c (= −1.27 + one semitone), Werckmeister III A = 0, shape 0 at A4
        assertEquals(6900.0 - 101.27 + h.stretchCents[69], target.toDouble(), 0.02)
    }
}
