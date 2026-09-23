package com.tropicalstream.hammerklavier.kit

import org.json.JSONArray
import org.json.JSONObject

/**
 * WP4's private map.json generator for tests (the WP11 fixtures replace it where they exist).
 * Writes schema-2 JSON exactly per docs/contracts/map-json.md.
 */
class ToyKit(
    val instrument: String = "grand", val kit: String = "grand-std",
    val mode: String = "HARD", val xfadeSteps: Int = 0, val law: String = "gain",
    /** (velLo, velHi, velRef) per layer. */
    val layers: List<Triple<Int, Int, Int>> = listOf(Triple(1, 64, 40), Triple(65, 127, 100)),
    val layerDb: (Int) -> Float = { l -> -20f + 20f * l / maxOf(1, layers.size - 1) },
    val stops: List<String> = listOf("main"),
    /** Played-key roots per stop. */
    val roots: (Int) -> List<Int> = { listOf(58, 60, 62) },
    /** Range each stop serves. */
    val range: (Int) -> IntRange = { s -> roots(s).first() - 1..roots(s).last() + 1 },
    /** Measured pitch re 100·root for (stop, root): includes the octave of a 4′ (+1200). */
    val pitchCents: (Int, Int) -> Float = { s, _ -> if (stops[s] == "4'") 1200f else 0f },
    val borrowable: (Int, Int) -> Boolean = { _, _ -> false },
    val seamGainDb: Float = -1.5f, val seamLpHz: Float = 6000f,
    val releases: Boolean = true, val pedals: Int = 2,
    val order: List<Int>? = null,
    val framesPerRegion: Int = 4800,
    val stretch: (Int) -> Float = { 0f },
) {
    val sha1 = "0123456789abcdef0123456789abcdef01234567"

    fun sustainUnit(stop: Int, layer: Int) = stop * layers.size + layer

    fun json(mutate: (JSONObject) -> Unit = {}): String {
        val o = JSONObject()
        o.put("schema", 2); o.put("instrument", instrument); o.put("kit", kit); o.put("version", "2026.09.0")
        o.put("sha1", sha1); o.put("mode", mode); o.put("xfadeSteps", xfadeSteps)
        o.put("xfadeLaw", JSONArray(if (mode == "HARD") emptyList<String>() else List(layers.size - 1) { law }))
        o.put("lastDamper", 88); o.put("aOffsetCents", 0.0); o.put("recordedAHz", 440.0); o.put("pedalGainDb", -20.0)
        o.put("releaseCarriesTail", false); o.put("embeddedRoomDb", 12.0); o.put("embeddedEdtS", 0.4)
        o.put("layers", JSONArray(layers.mapIndexed { i, (lo, hi, ref) ->
            JSONObject().put("index", i).put("velLo", lo).put("velHi", hi).put("velRef", ref).put("unit", sustainUnit(0, i)) }))
        o.put("stops", JSONArray(stops.mapIndexed { i, n -> JSONObject().put("index", i).put("name", n) }))
        o.put("levelCurve", JSONArray(stops.map { JSONArray(layers.mapIndexed { i, t -> JSONObject().put("vel", t.third).put("db", layerDb(i).toDouble()) }) }))

        val regions = JSONArray()
        val unitFrames = HashMap<Int, Int>()
        var env = 0
        val envCount = framesPerRegion / 480
        fun region(kind: String, unit: Int, stop: Int, layer: Int, root: Int, lo: Int, hi: Int, rr: Int, pitch: Float, gainDb: Float,
                   borrow: Boolean = false) {
            val start = unitFrames.getOrDefault(unit, 0)
            val r = JSONObject().put("id", regions.length()).put("kind", kind).put("unit", unit).put("streamStart", start)
                .put("frames", framesPerRegion).put("stop", stop).put("layer", layer).put("root", root).put("lo", lo).put("hi", hi)
                .put("rr", rr).put("onsetFrame", 96).put("thrFrame", 100).put("pitchCents", pitch.toDouble()).put("gainDb", gainDb.toDouble())
                .put("envOffset", env).put("envCount", envCount)
            if (borrow) r.put("borrowable", true).put("seamGainDb", seamGainDb.toDouble()).put("seamLpHz", seamLpHz.toDouble())
            regions.put(r)
            env += envCount
            unitFrames[unit] = start + framesPerRegion + 1920
        }
        fun lohi(rs: List<Int>, i: Int, s: Int): Pair<Int, Int> {
            val lo = if (i == 0) range(s).first else (rs[i - 1] + rs[i]) / 2 + 1
            val hi = if (i == rs.size - 1) range(s).last else (rs[i] + rs[i + 1]) / 2
            return lo to hi
        }
        for (s in stops.indices) for (l in layers.indices) {
            val rs = roots(s)
            for ((i, root) in rs.withIndex()) {
                val (lo, hi) = lohi(rs, i, s)
                region("sustain", sustainUnit(s, l), s, l, root, lo, hi, 0, pitchCents(s, root), layerDb(l), borrowable(s, root))
            }
        }
        if (releases) for (s in stops.indices) {
            val rs = roots(s)
            for ((i, root) in rs.withIndex()) { val (lo, hi) = lohi(rs, i, s); region("release", 62, s, -1, root, lo, hi, 0, pitchCents(s, root), -10f) }
        }
        for (i in 0 until pedals) region("pedalDown", 63, -1, -1, -1, -1, -1, i, 0f, 0f)
        for (i in 0 until pedals) region("pedalUp", 63, -1, -1, -1, -1, -1, i, 0f, 0f)
        o.put("regions", regions)

        val unitIds = unitFrames.keys.sorted()
        val ord = order ?: (unitIds.filter { it < 62 }.take(1) + unitIds.filter { it >= 62 } + unitIds.filter { it < 62 }.drop(1))
        o.put("units", JSONArray(unitIds.map { u ->
            JSONObject().put("id", u).put("label", if (u == 62) "releases" else if (u == 63) "pedals" else "u$u")
                .put("order", ord.indexOf(u)).put("file", "u/$u.opus").put("frames", unitFrames[u]).put("sha1", sha1) }))
        o.put("stretchCents", JSONArray(List(128) { stretch(it).toDouble() }))
        o.put("inharmB", JSONArray(List(128) { 0.0 }))
        o.put("damperT60", JSONArray(List(128) { 0.5 }))
        o.put("freeT60", JSONArray(stops.map { JSONArray(List(128) { 10.0 }) }))
        o.put("releaseRule", JSONObject().put("relGainDb", -12).put("velExp", 0.7).put("ageTauS", 3).put("floor", 0.05).put("heldDb", -6))
        o.put("credit", "toy"); o.put("source", "ToyKit")
        envBytes = env
        mutate(o)
        return o.toString()
    }

    var envBytes = 0; private set

    fun env(): ByteArray = ByteArray(envBytes) { (it % 200).toByte() }

    fun index(mutate: (JSONObject) -> Unit = {}): KitIndex {
        val j = json(mutate)
        return when (val r = KitMapCodec.decode(j, env())) {
            is KitMapCodec.Result.Ok -> r.index
            is KitMapCodec.Result.Invalid -> error("toy kit invalid: $r")
        }
    }

    companion object {
        val SALAMANDER_SPLITS = listOf(1 to 26, 27 to 34, 35 to 36, 37 to 43, 44 to 46, 47 to 50, 51 to 56, 57 to 64,
            65 to 72, 73 to 80, 81 to 88, 89 to 96, 97 to 104, 105 to 112, 113 to 120, 121 to 127)
        val SALAMANDER_REFS = listOf(14, 31, 36, 40, 45, 49, 54, 61, 69, 77, 85, 93, 101, 109, 117, 124)

        /** The WP11 fixture's shape: 3 roots 58/60/62, 2 layers, ET, zero shape. */
        fun fixtureLike() = ToyKit()

        /** A 16-layer HARD grand, roots every 3 from 21 to 108, recorded at [aHz]. */
        fun grandHd(aHz: Float = 440f) = ToyKit(kit = "grand-hd", mode = "HARD",
            layers = SALAMANDER_SPLITS.mapIndexed { i, (lo, hi) -> Triple(lo, hi, SALAMANDER_REFS[i]) },
            layerDb = { l -> -30f + 2f * l }, roots = { List(30) { 21 + 3 * it } }, range = { 21..108 },
            pitchCents = { _, _ -> cents(aHz) }, releases = true)

        /** Standard grand: 6 layers, XFADE ±4. */
        fun grandStd(law: String = "gain", aHz: Float = 440f) = ToyKit(kit = "grand-std", mode = "XFADE", xfadeSteps = 4, law = law,
            layers = listOf(Triple(1, 30, 14), Triple(31, 46, 40), Triple(47, 64, 54), Triple(65, 88, 77), Triple(89, 112, 101), Triple(113, 127, 124)),
            layerDb = { l -> -24f + 5f * l }, roots = { List(30) { 21 + 3 * it } }, range = { 21..108 },
            pitchCents = { _, _ -> cents(aHz) })

        /**
         * Harpsichord: 8′ roots every 3 from 29 to 83 plus 84 (keys 86–89 must borrow),
         * 4′ with 26 irregular roots including 74 and 76 (borrowable, sounding 86 and 88).
         */
        fun harpsichord(aHz: Float = 440f) = ToyKit(instrument = "harpsichord", kit = "harpsichord", mode = "HARD",
            layers = listOf(Triple(1, 127, 64)), layerDb = { 0f }, stops = listOf("8'", "4'"),
            roots = { s -> if (s == 0) List(19) { 29 + 3 * it } + listOf(84) else HARPSI_4FT },
            range = { 29..89 },
            pitchCents = { s, _ -> (if (s == 1) 1200f else 0f) + cents(aHz) },
            borrowable = { s, root -> s == 1 && (root == 74 || root == 76) })

        val HARPSI_4FT = listOf(29, 31, 34, 36, 39, 41, 43, 46, 48, 51, 53, 55, 58, 60, 62, 65, 67, 69, 70, 72, 74, 76, 79, 81, 84, 88)

        fun cents(aHz: Float): Float = (1200.0 * Math.log(aHz / 440.0) / Math.log(2.0)).toFloat()
    }
}
