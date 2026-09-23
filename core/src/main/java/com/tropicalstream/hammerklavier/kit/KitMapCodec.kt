package com.tropicalstream.hammerklavier.kit

import com.tropicalstream.hammerklavier.contract.HK
import org.json.JSONArray
import org.json.JSONObject

/**
 * `map.json` (+ `env.bin`) → [KitIndex], validated against the normative field table
 * (`docs/contracts/map-json.md`, PLAN §6.6). Never throws: every broken rule is reported as a
 * human-readable reason, and any reason makes the kit invalid (the caller falls back, §3.19).
 * Runs on HKLoader.
 */
object KitMapCodec {
    sealed class Result {
        class Ok(val index: KitIndex) : Result()
        class Invalid(val reasons: List<String>) : Result() {
            override fun toString(): String = "Invalid(${reasons.joinToString("; ")})"
        }
    }

    private val INSTRUMENTS = setOf("grand", "upright", "harpsichord", "stub")
    private val KITS = setOf("grand-hd", "grand-std", "upright", "harpsichord", "stub")
    private val STOP_NAMES = setOf("main", "8'", "4'")
    private val VERSION = Regex("""\d{4}\.\d{2}\.\d+""")
    private val HEX40 = Regex("[0-9a-f]{40}")

    /**
     * @param unitExists whether `assets/instruments/<id>/<file>` exists (every unit file must).
     */
    fun decode(json: String, env: ByteArray, unitExists: (String) -> Boolean = { true }): Result {
        val bad = ArrayList<String>()
        val root = try { JSONObject(json) } catch (e: Exception) {
            return Result.Invalid(listOf("not JSON: ${e.message}"))
        }
        val p = P(root, bad)
        return try {
            val index = parse(p, root, env, unitExists)
            if (bad.isEmpty() && index != null) Result.Ok(index) else Result.Invalid(bad.ifEmpty { listOf("invalid") })
        } catch (e: Exception) {
            bad += "malformed: ${e.javaClass.simpleName} ${e.message}"
            Result.Invalid(bad)
        }
    }

    /** Field reader that records missing / mistyped fields instead of throwing. */
    private class P(val o: JSONObject, val bad: MutableList<String>, val where: String = "") {
        fun miss(k: String) { bad += "$where$k: missing or wrong type" }
        fun int(k: String): Int? {
            if (!o.has(k)) { miss(k); return null }
            val v = o.opt(k)
            if (v !is Number || v.toDouble() != Math.floor(v.toDouble())) { miss(k); return null }
            return v.toInt()
        }
        fun optInt(k: String, d: Int): Int = if (o.has(k)) int(k) ?: d else d
        fun float(k: String): Float? {
            val v = o.opt(k)
            if (v !is Number) { miss(k); return null }
            val f = v.toFloat()
            if (f.isNaN() || f.isInfinite()) { bad += "$where$k: not finite"; return null }
            return f
        }
        fun optFloat(k: String, d: Float): Float = if (o.has(k)) float(k) ?: d else d
        fun str(k: String): String? { val v = o.opt(k); if (v !is String) { miss(k); return null }; return v }
        fun bool(k: String): Boolean? { val v = o.opt(k); if (v !is Boolean) { miss(k); return null }; return v }
        fun optBool(k: String, d: Boolean): Boolean = if (o.has(k)) bool(k) ?: d else d
        fun arr(k: String): JSONArray? { val v = o.opt(k); if (v !is JSONArray) { miss(k); return null }; return v }
        fun obj(k: String): JSONObject? { val v = o.opt(k); if (v !is JSONObject) { miss(k); return null }; return v }
        fun floats(k: String, n: Int): FloatArray? {
            val a = arr(k) ?: return null
            if (a.length() != n) { bad += "$where$k: length ${a.length()} != $n"; return null }
            val out = FloatArray(n)
            for (i in 0 until n) {
                val v = a.opt(i)
                if (v !is Number || v.toDouble().isNaN() || v.toDouble().isInfinite()) { bad += "$where$k[$i]: not a number"; return null }
                out[i] = v.toFloat()
            }
            return out
        }
        fun sub(o2: JSONObject, w: String) = P(o2, bad, w)
    }

    private fun parse(p: P, root: JSONObject, env: ByteArray, unitExists: (String) -> Boolean): KitIndex? {
        val bad = p.bad
        val schema = p.int("schema")
        if (schema != null && schema != 2) bad += "schema: $schema != 2"
        val instrument = p.str("instrument")
        if (instrument != null && instrument !in INSTRUMENTS) bad += "instrument: '$instrument' unknown"
        val kit = p.str("kit")
        if (kit != null && kit !in KITS) bad += "kit: '$kit' unknown"
        val version = p.str("version")
        if (version != null && !VERSION.matches(version)) bad += "version: '$version' is not YYYY.MM.n"
        val sha1 = p.str("sha1")
        if (sha1 != null && !HEX40.matches(sha1)) bad += "sha1: not 40 lower-case hex digits"
        val mode = p.str("mode")
        if (mode != null && mode != "HARD" && mode != "XFADE") bad += "mode: '$mode' is not HARD or XFADE"
        val xfadeSteps = p.int("xfadeSteps")
        if (xfadeSteps != null && xfadeSteps < 0) bad += "xfadeSteps: negative"
        val lawArr = p.arr("xfadeLaw")
        val xfadeLaw = ArrayList<String>()
        if (lawArr != null) for (i in 0 until lawArr.length()) {
            val s = lawArr.opt(i)
            if (s != "gain" && s != "power") bad += "xfadeLaw[$i]: '$s' is not gain or power" else xfadeLaw += s as String
        }
        val lastDamper = p.int("lastDamper")
        if (lastDamper != null && lastDamper !in 0..127) bad += "lastDamper: $lastDamper not a MIDI key"
        val aOffsetCents = p.float("aOffsetCents")
        val recordedAHz = p.float("recordedAHz")
        if (recordedAHz != null && recordedAHz <= 0f) bad += "recordedAHz: not positive"
        val pedalGainDb = p.float("pedalGainDb")
        val releaseCarriesTail = p.bool("releaseCarriesTail")
        val embeddedRoomDb = p.float("embeddedRoomDb")
        val embeddedEdtS = p.float("embeddedEdtS")
        val credit = p.str("credit")
        val source = p.str("source")

        // Layers: dense indices, velocity splits covering 1..127 without gaps or overlaps.
        val layers = ArrayList<LayerDef>()
        p.arr("layers")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: run { bad += "layers[$i]: not an object"; null } ?: continue
                val q = p.sub(o, "layers[$i].")
                val idx = q.int("index"); val lo = q.int("velLo"); val hi = q.int("velHi"); val ref = q.int("velRef"); val u = q.int("unit")
                if (idx == null || lo == null || hi == null || ref == null || u == null) continue
                if (lo > hi) bad += "layers[$i]: velLo $lo > velHi $hi"
                if (ref !in lo..hi) bad += "layers[$i]: velRef $ref outside $lo..$hi"
                if (u !in 0..61) bad += "layers[$i]: unit $u is not a sustain unit"
                layers += LayerDef(idx, lo, hi, ref, u)
            }
            if (layers.isEmpty()) bad += "layers: empty"
            if (layers.map { it.index }.sorted() != layers.indices.toList()) bad += "layers: indices not dense from 0"
            val sorted = layers.sortedBy { it.velLo }
            var next = 1
            for (l in sorted) {
                if (l.velLo != next) bad += "layers: velocity ${if (l.velLo > next) "gap" else "overlap"} at $next (stop splits must cover 1-127)"
                next = l.velHi + 1
            }
            if (layers.isNotEmpty() && next != 128) bad += "layers: splits end at ${next - 1}, not 127"
            if (sorted.map { it.index } != sorted.map { it.index }.sorted()) bad += "layers: indices not in velocity order"
        }
        layers.sortBy { it.index }
        if (mode == "HARD" && xfadeLaw.isNotEmpty()) bad += "xfadeLaw: must be empty for HARD"
        if (mode == "XFADE" && layers.size > 1 && xfadeLaw.size != layers.size - 1)
            bad += "xfadeLaw: ${xfadeLaw.size} entries for ${layers.size - 1} boundaries"

        // Stops.
        val stops = ArrayList<StopDef>()
        p.arr("stops")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: run { bad += "stops[$i]: not an object"; null } ?: continue
                val q = p.sub(o, "stops[$i].")
                val idx = q.int("index"); val name = q.str("name")
                if (idx == null || name == null) continue
                if (name !in STOP_NAMES) bad += "stops[$i].name: '$name' is not main, 8' or 4'"
                stops += StopDef(idx, name)
            }
            if (stops.isEmpty() || stops.size > 2) bad += "stops: ${stops.size} stops (1 or 2 allowed)"
            if (stops.map { it.index }.sorted() != stops.indices.toList()) bad += "stops: indices not dense from 0"
        }
        stops.sortBy { it.index }
        val nStops = stops.size.coerceAtLeast(1)

        // Level curve: per stop, one point per layer, non-decreasing.
        val levelCurve = ArrayList<List<LevelPoint>>()
        p.arr("levelCurve")?.let { a ->
            if (a.length() != stops.size) bad += "levelCurve: ${a.length()} stops, expected ${stops.size}"
            for (s in 0 until a.length()) {
                val pts = ArrayList<LevelPoint>()
                val sa = a.optJSONArray(s) ?: run { bad += "levelCurve[$s]: not an array"; null } ?: continue
                for (i in 0 until sa.length()) {
                    val o = sa.optJSONObject(i) ?: run { bad += "levelCurve[$s][$i]: not an object"; null } ?: continue
                    val q = p.sub(o, "levelCurve[$s][$i].")
                    val v = q.int("vel"); val db = q.float("db")
                    if (v != null && db != null) pts += LevelPoint(v, db)
                }
                if (pts.size != layers.size) bad += "levelCurve[$s]: ${pts.size} points for ${layers.size} layers"
                for (i in 1 until pts.size) {
                    if (pts[i].vel <= pts[i - 1].vel) bad += "levelCurve[$s]: velocities not increasing at $i"
                    if (pts[i].db < pts[i - 1].db) bad += "levelCurve[$s]: level decreasing at vel ${pts[i].vel}"
                }
                for ((i, pt) in pts.withIndex()) if (i < layers.size && pt.vel != layers[i].velRef)
                    bad += "levelCurve[$s][$i]: vel ${pt.vel} is not layer $i's velRef ${layers[i].velRef}"
                levelCurve += pts
            }
        }

        // Units.
        val units = ArrayList<UnitDef>()
        p.arr("units")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: run { bad += "units[$i]: not an object"; null } ?: continue
                val q = p.sub(o, "units[$i].")
                val id = q.int("id"); val label = q.str("label"); val order = q.int("order")
                val file = q.str("file"); val frames = q.int("frames"); val sh = q.str("sha1")
                if (id == null || label == null || order == null || file == null || frames == null || sh == null) continue
                if (id !in 0..63) bad += "units[$i].id: $id outside 0..63"
                if (file != "u/$id.opus") bad += "units[$i].file: '$file' is not u/$id.opus"
                else if (!unitExists(file)) bad += "units[$i].file: '$file' not present in assets"
                if (frames <= 0) bad += "units[$i].frames: $frames not positive"
                if (!HEX40.matches(sh)) bad += "units[$i].sha1: not 40 hex digits"
                units += UnitDef(id, label, order, file, frames, sh)
            }
            if (units.isEmpty()) bad += "units: empty"
            if (units.map { it.id }.toSet().size != units.size) bad += "units: duplicate ids"
            if (units.map { it.order }.toSet().size != units.size) bad += "units: duplicate order"
        }
        val unitById = units.associateBy { it.id }

        // Regions.
        val regions = ArrayList<RegionDef>()
        p.arr("regions")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: run { bad += "regions[$i]: not an object"; null } ?: continue
                val w = "regions[$i]."
                val q = p.sub(o, w)
                val id = q.int("id"); val kindS = q.str("kind"); val unit = q.int("unit")
                val start = q.int("streamStart"); val frames = q.int("frames")
                val stop = q.int("stop"); val layer = q.int("layer"); val root = q.int("root")
                val lo = q.int("lo"); val hi = q.int("hi"); val rr = q.optInt("rr", 0)
                val onset = q.int("onsetFrame"); val thr = q.int("thrFrame")
                val gainDb = q.float("gainDb"); val envOff = q.int("envOffset"); val envCount = q.int("envCount")
                val kind = kindS?.let { RegionKind.of(it) }
                if (kindS != null && kind == null) bad += "${w}kind: '$kindS' unknown"
                val needsPitch = kind == RegionKind.SUSTAIN || kind == RegionKind.RELEASE
                val pitch = if (needsPitch) q.float("pitchCents") else q.optFloat("pitchCents", 0f)
                val borrowable = q.optBool("borrowable", false)
                val seamGainDb = q.optFloat("seamGainDb", 0f); val seamLpHz = q.optFloat("seamLpHz", 0f)
                if (id == null || kind == null || unit == null || start == null || frames == null || stop == null ||
                    layer == null || root == null || lo == null || hi == null || onset == null || thr == null ||
                    gainDb == null || envOff == null || envCount == null || pitch == null) continue
                if (frames <= 0) bad += "${w}frames: $frames not positive"
                val u = unitById[unit]
                if (u == null) bad += "${w}unit: $unit not in units[]"
                else if (start < 0 || start.toLong() + frames > u.frames) bad += "${w}streamStart + frames beyond unit $unit's ${u.frames} frames"
                if (envOff < 0 || envCount < 0 || envOff.toLong() + envCount > env.size) bad += "${w}env range $envOff+$envCount beyond env.bin (${env.size} bytes)"
                if (onset !in 0 until frames.coerceAtLeast(1)) bad += "${w}onsetFrame: $onset outside the region"
                if (thr < 0 || thr >= frames.coerceAtLeast(1)) bad += "${w}thrFrame: $thr outside the region"
                when (kind) {
                    RegionKind.SUSTAIN -> {
                        if (stop !in 0 until nStops) bad += "${w}stop: $stop invalid"
                        if (layer !in layers.indices) bad += "${w}layer: $layer invalid"
                        if (unit !in 0..61) bad += "${w}unit: sustain region in unit $unit"
                    }
                    RegionKind.RELEASE -> {
                        if (stop !in 0 until nStops) bad += "${w}stop: $stop invalid"
                        if (layer != -1) bad += "${w}layer: release must have layer -1"
                        if (unit != KitIndex.UNIT_RELEASES) bad += "${w}unit: release region outside unit 62"
                    }
                    RegionKind.PEDAL_DOWN, RegionKind.PEDAL_UP -> {
                        if (stop != -1 || layer != -1) bad += "${w}stop/layer: pedal regions use -1"
                        if (rr < 0) bad += "${w}rr: negative"
                        if (unit != KitIndex.UNIT_PEDALS) bad += "${w}unit: pedal region outside unit 63"
                    }
                }
                if (kind == RegionKind.SUSTAIN || kind == RegionKind.RELEASE) {
                    if (root !in 0..127 || lo !in 0..127 || hi !in 0..127 || lo > hi) bad += "${w}root/lo/hi: $root $lo..$hi invalid"
                }
                if (seamLpHz < 0f) bad += "${w}seamLpHz: negative"
                regions += RegionDef(id, kind, unit, start, frames, stop, layer, root, lo, hi, rr, onset, thr, pitch, gainDb,
                    envOff, envCount, borrowable, seamGainDb, seamLpHz)
            }
            if (regions.isEmpty()) bad += "regions: empty"
            val ids = regions.map { it.id }
            if (ids.toSet().size != ids.size) bad += "regions: duplicate ids"
            if (ids.sorted() != regions.indices.toList()) bad += "regions: ids not dense from 0"
        }
        regions.sortBy { it.id }
        // Every (stop, layer) has sustain regions.
        if (layers.isNotEmpty() && stops.isNotEmpty()) for (s in stops.indices) for (l in layers.indices)
            if (regions.none { it.kind == RegionKind.SUSTAIN && it.stop == s && it.layer == l }) bad += "regions: no sustain for stop $s layer $l"

        val stretch = p.floats("stretchCents", HK.KEYS)
        val inharm = p.floats("inharmB", HK.KEYS)
        val damper = p.floats("damperT60", HK.KEYS)
        val freeT60 = ArrayList<FloatArray>()
        p.arr("freeT60")?.let { a ->
            if (a.length() != stops.size) bad += "freeT60: ${a.length()} rows for ${stops.size} stops"
            for (s in 0 until a.length()) {
                val row = a.optJSONArray(s)
                if (row == null || row.length() != HK.KEYS) { bad += "freeT60[$s]: length ${row?.length()} != 128"; continue }
                freeT60 += FloatArray(HK.KEYS) { row.optDouble(it, Double.NaN).toFloat() }
                if (freeT60.last().any { it.isNaN() }) bad += "freeT60[$s]: not numbers"
            }
        }
        val rrule = p.obj("releaseRule")?.let { o ->
            val q = p.sub(o, "releaseRule.")
            val a = q.float("relGainDb"); val b = q.float("velExp"); val c = q.float("ageTauS"); val d = q.float("floor"); val e = q.float("heldDb")
            if (a == null || b == null || c == null || d == null || e == null) null else ReleaseRule(a, b, c, d, e)
        }
        if (bad.isNotEmpty()) return null
        return KitIndex(schema!!, instrument!!, kit!!, version!!, sha1!!, mode!!, xfadeSteps!!, xfadeLaw,
            lastDamper!!, aOffsetCents!!, recordedAHz!!, pedalGainDb!!, releaseCarriesTail!!, embeddedRoomDb!!, embeddedEdtS!!,
            layers, stops, levelCurve, units.sortedBy { it.id }, regions, stretch!!, inharm!!, damper!!, freeT60.toTypedArray(),
            rrule!!, credit!!, source!!, env)
    }
}
