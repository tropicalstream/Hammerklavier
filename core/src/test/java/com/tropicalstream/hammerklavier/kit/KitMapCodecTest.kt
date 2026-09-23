package com.tropicalstream.hammerklavier.kit

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Ignore
import org.junit.Test

/** T4.1: map.json parses; every §6.6 rule rejects a broken kit with a reason. */
class KitMapCodecTest {
    private val toy = ToyKit.fixtureLike()

    private fun reasons(mutate: (JSONObject) -> Unit, exists: (String) -> Boolean = { true }): List<String> {
        val j = toy.json(mutate)
        return when (val r = KitMapCodec.decode(j, toy.env(), exists)) {
            is KitMapCodec.Result.Ok -> { fail("expected rejection"); emptyList() }
            is KitMapCodec.Result.Invalid -> r.reasons
        }
    }

    private fun rejects(fragment: String, mutate: (JSONObject) -> Unit) {
        val rs = reasons(mutate)
        assertTrue("expected a reason containing '$fragment', got $rs", rs.any { it.contains(fragment) })
    }

    private fun regions(o: JSONObject) = o.getJSONArray("regions")

    @Test fun toyFixtureParses() {
        val idx = toy.index()
        assertEquals(2, idx.layerCount); assertEquals(1, idx.stopCount)
        assertEquals(listOf(58, 60, 62), idx.regions.filter { it.kind == RegionKind.SUSTAIN && it.layer == 0 }.map { it.root })
        assertEquals("01234567", idx.sha8)
        assertEquals(0, idx.decodeOrder[0]); assertEquals(62, idx.decodeOrder[1]); assertEquals(63, idx.decodeOrder[2])
        val info = idx.toBankInfo()
        assertEquals(128, info.damperT60.size); assertEquals(128, info.freeT60.size)
        assertEquals(255, idx.envByte(0, 10_000)); assertEquals(0, idx.envByte(0, 0))
    }

    @Test fun rejectsNonJson() {
        val r = KitMapCodec.decode("{nope", ByteArray(0))
        assertTrue(r is KitMapCodec.Result.Invalid)
    }

    @Test fun schema() = rejects("schema") { it.put("schema", 3) }
    @Test fun unknownInstrument() = rejects("instrument") { it.put("instrument", "fortepiano") }
    @Test fun unknownKit() = rejects("kit") { it.put("kit", "grand-xl") }
    @Test fun version() = rejects("version") { it.put("version", "1.0") }
    @Test fun sha1() = rejects("sha1") { it.put("sha1", "xyz") }
    @Test fun mode() = rejects("mode") { it.put("mode", "SOFT") }
    @Test fun missingField() = rejects("pedalGainDb") { it.remove("pedalGainDb") }
    @Test fun lawForHard() = rejects("xfadeLaw") { it.put("xfadeLaw", JSONArray(listOf("gain"))) }
    @Test fun lawValue() = rejects("xfadeLaw[0]") { it.put("mode", "XFADE"); it.put("xfadeLaw", JSONArray(listOf("loud"))) }
    @Test fun lawCount() = rejects("boundaries") { it.put("mode", "XFADE"); it.put("xfadeLaw", JSONArray(listOf("gain", "power"))) }

    @Test fun duplicateRegionIds() = rejects("regions: duplicate ids") { regions(it).getJSONObject(1).put("id", 0) }
    @Test fun regionIdsNotDense() = rejects("not dense") { val a = regions(it); a.getJSONObject(a.length() - 1).put("id", 999) }
    @Test fun unitFileMissing() {
        val rs = reasons({}, exists = { it != "u/1.opus" })
        assertTrue(rs.toString(), rs.any { it.contains("u/1.opus") && it.contains("not present") })
    }
    @Test fun unitFileName() = rejects("is not u/0.opus") { it.getJSONArray("units").getJSONObject(0).put("file", "u/zero.opus") }
    @Test fun streamBeyondUnit() = rejects("beyond unit") { regions(it).getJSONObject(0).put("streamStart", 10_000_000) }
    @Test fun framesPositive() = rejects("frames: 0 not positive") { regions(it).getJSONObject(0).put("frames", 0) }
    @Test fun envOutOfBounds() = rejects("env range") { regions(it).getJSONObject(0).put("envOffset", 1_000_000) }
    @Test fun velocityGap() = rejects("gap") { it.getJSONArray("layers").getJSONObject(1).put("velLo", 70) }
    @Test fun velocityOverlap() = rejects("overlap") { it.getJSONArray("layers").getJSONObject(1).put("velLo", 60) }
    @Test fun velocityEnd() = rejects("not 127") { it.getJSONArray("layers").getJSONObject(1).put("velHi", 120) }
    @Test fun velRefOutside() = rejects("velRef") { it.getJSONArray("layers").getJSONObject(0).put("velRef", 90) }
    @Test fun stretchLength() = rejects("stretchCents: length") { it.put("stretchCents", JSONArray(List(127) { 0 })) }
    @Test fun inharmLength() = rejects("inharmB") { it.put("inharmB", JSONArray(List(12) { 0 })) }
    @Test fun damperLength() = rejects("damperT60") { it.put("damperT60", JSONArray(List(129) { 0 })) }
    @Test fun freeT60Rows() = rejects("freeT60") { it.put("freeT60", JSONArray()) }
    @Test fun levelCurveDecreasing() = rejects("decreasing") {
        it.getJSONArray("levelCurve").getJSONArray(0).getJSONObject(1).put("db", -40.0)
    }
    @Test fun levelCurveCount() = rejects("points") { it.getJSONArray("levelCurve").getJSONArray(0).remove(1) }
    @Test fun badKind() = rejects("kind") { regions(it).getJSONObject(0).put("kind", "drone") }
    @Test fun sustainLayer() = rejects("layer") { regions(it).getJSONObject(0).put("layer", 5) }
    @Test fun releaseLayer() = rejects("release must have layer -1") {
        val a = regions(it)
        for (i in 0 until a.length()) if (a.getJSONObject(i).getString("kind") == "release") { a.getJSONObject(i).put("layer", 0); break }
    }
    @Test fun pedalStop() = rejects("pedal regions use -1") {
        val a = regions(it)
        for (i in 0 until a.length()) if (a.getJSONObject(i).getString("kind") == "pedalDown") { a.getJSONObject(i).put("stop", 0); break }
    }
    @Test fun sustainPitchRequired() = rejects("pitchCents") { regions(it).getJSONObject(0).remove("pitchCents") }
    @Test fun stopName() = rejects("stops[0].name") { it.getJSONArray("stops").getJSONObject(0).put("name", "16'") }
    @Test fun releaseRuleField() = rejects("releaseRule.velExp") { it.getJSONObject("releaseRule").remove("velExp") }
    @Test fun duplicateUnits() = rejects("duplicate ids") { it.getJSONArray("units").getJSONObject(1).put("id", 0) }
    @Test fun onsetOutside() = rejects("onsetFrame") { regions(it).getJSONObject(0).put("onsetFrame", 1_000_000) }

    @Test fun realKitsParse() {
        for (k in listOf(ToyKit.grandHd(), ToyKit.grandStd(), ToyKit.harpsichord())) k.index()
    }

    // ---- the WP11 fixture ----

    private fun res(name: String): ByteArray? = javaClass.classLoader.getResourceAsStream("wp11/$name")?.use { it.readBytes() }

    @Ignore("needs wp11 fixture")
    @Test fun wp11FixtureParses() {
        val json = String(res("map_fixture.json")!!, Charsets.UTF_8)
        val env = res("env_fixture.bin")!!
        val r = KitMapCodec.decode(json, env)
        assertTrue(r.toString(), r is KitMapCodec.Result.Ok)
        val idx = (r as KitMapCodec.Result.Ok).index
        assertEquals(2, idx.layerCount)
        assertEquals(setOf(58, 60, 62), idx.regions.filter { it.kind == RegionKind.SUSTAIN }.map { it.root }.toSet())
    }

    @Ignore("needs wp11 fixture")
    @Test fun wp11BadFixturesRejected() {
        val env = res("env_fixture.bin")!!
        val dir = javaClass.classLoader.getResource("wp11/bad") ?: error("no wp11/bad")
        val files = java.io.File(dir.toURI()).listFiles { f -> f.name.endsWith(".json") }!!
        assertTrue(files.isNotEmpty())
        for (f in files) {
            val r = KitMapCodec.decode(f.readText(), env)
            assertTrue("${f.name} must be rejected", r is KitMapCodec.Result.Invalid)
        }
    }
}
