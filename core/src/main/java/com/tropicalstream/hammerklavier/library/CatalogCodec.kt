package com.tropicalstream.hammerklavier.library

import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.LibraryModel
import com.tropicalstream.hammerklavier.contract.Movement
import com.tropicalstream.hammerklavier.contract.Shelf
import com.tropicalstream.hammerklavier.contract.Source
import com.tropicalstream.hammerklavier.contract.Temperament
import com.tropicalstream.hammerklavier.contract.TuningSpec
import com.tropicalstream.hammerklavier.contract.Work
import org.json.JSONArray
import org.json.JSONObject

/**
 * `assets/catalog.json` (PLAN §4.6, schema 1) → [LibraryModel], and the library → the companion's
 * `/api/library` JSON. Pure; any thread (HKLoader in practice).
 *
 * The model's shelves are "Start here" (id `start`, the works of `startHere` in order) followed by
 * the catalogue's shelves in file order. A movement whose asset is missing ([assetExists] false,
 * e.g. the IMSLP Handel when the user skipped it) is dropped with its empty work, from shelves and
 * from Start here ("skipped if absent", §4.7).
 */
object CatalogCodec {
    const val SCHEMA = 1
    const val START_TITLE = "Start here"

    fun parse(json: String, assetExists: (String) -> Boolean = { true }): LibraryModel {
        val o = JSONObject(json)
        require(o.optInt("schema", -1) == SCHEMA) { "catalog.json schema ${o.opt("schema")}" }

        val sources = LinkedHashMap<String, Source>()
        o.optJSONObject("sources")?.let { so ->
            for (id in so.keys().asSequence().toList().sorted()) {
                val s = so.getJSONObject(id)
                sources[id] = Source(id = id, credit = s.optString("credit"), licence = s.optString("licence"),
                    licenceUrl = s.optString("licenceUrl"), sourceUrl = s.optString("sourceUrl"),
                    licenceFile = str(s, "licenceFile"), performanceType = s.optString("performanceType"),
                    tier = s.optString("tier"), exportAllowed = s.optBoolean("exportAllowed", false))
            }
        }

        val works = LinkedHashMap<String, Work>()
        val movements = LinkedHashMap<String, Movement>()
        val wa = o.optJSONArray("works") ?: JSONArray()
        for (i in 0 until wa.length()) {
            val w = wa.getJSONObject(i)
            val workId = w.getString("id")
            val mIds = ArrayList<String>()
            val ma = w.optJSONArray("movements") ?: JSONArray()
            for (k in 0 until ma.length()) {
                val m = ma.getJSONObject(k)
                val asset = str(m, "asset")
                if (asset != null && !assetExists(asset)) continue
                val mv = Movement(id = m.getString("id"), workId = workId, title = m.optString("title"), asset = asset,
                    file = null, sha1 = m.optString("sha1Hex"), durationSec = m.optDouble("durationSec", 0.0).toFloat(),
                    lowKey = m.optInt("lowKey", 21), highKey = m.optInt("highKey", 108),
                    hasSustain = m.optBoolean("hasSustain"), hasSoft = m.optBoolean("hasSoft"),
                    hasSostenuto = m.optBoolean("hasSostenuto"), pedalMode = LibraryIndex.pedalModeOf(m.optString("pedalMode")))
                movements[mv.id] = mv
                mIds += mv.id
            }
            if (mIds.isEmpty()) continue
            val alt = ArrayList<InstrumentId>()
            w.optJSONArray("altInstruments")?.let { for (k in 0 until it.length()) InstrumentId.of(it.getString(k))?.let(alt::add) }
            works[workId] = Work(id = workId, composer = w.optString("composer"), composerShort = w.optString("composerShort"),
                title = w.optString("title"), shortTitle = w.optString("shortTitle", w.optString("title")),
                catalogue = str(w, "catalogue"), year = if (w.has("year") && !w.isNull("year")) w.getInt("year") else null,
                era = w.optString("era"), defaultInstrument = InstrumentId.of(w.optString("defaultInstrument")) ?: InstrumentId.GRAND,
                altInstruments = alt, sourceId = w.optString("source"), tier = w.optString("tier"),
                velocityPolicy = w.optString("velocityPolicy", "as-is"), tuning = tuningOf(w.opt("tuning")),
                movementIds = mIds, imported = false)
        }

        val startHere = ArrayList<String>()
        o.optJSONArray("startHere")?.let { for (k in 0 until it.length()) it.getString(k).takeIf(movements::containsKey)?.let(startHere::add) }

        val shelves = ArrayList<Shelf>()
        if (startHere.isNotEmpty()) shelves += Shelf(LibraryIndex.SHELF_START, START_TITLE,
            startHere.map { movements.getValue(it).workId }.distinct())
        val sa = o.optJSONArray("shelves") ?: JSONArray()
        for (i in 0 until sa.length()) {
            val s = sa.getJSONObject(i)
            val ids = ArrayList<String>()
            s.optJSONArray("works")?.let { for (k in 0 until it.length()) it.getString(k).takeIf(works::containsKey)?.let(ids::add) }
            shelves += Shelf(s.getString("id"), s.optString("title"), ids)
        }
        return LibraryModel(shelves, works, movements, sources, startHere)
    }

    /** `null`, or `{"aHz": 415, "temperament": "WERCKMEISTER_III" | "Werckmeister III"}`. */
    fun tuningOf(v: Any?): TuningSpec? {
        if (v !is JSONObject) return null
        val a = v.optDouble("aHz", 440.0).toFloat()
        val t = v.optString("temperament", "EQUAL")
        val temp = Temperament.entries.firstOrNull { it.name.equals(t, true) || it.label.equals(t, true) } ?: Temperament.EQUAL
        return TuningSpec(a, temp)
    }

    private fun str(o: JSONObject, k: String): String? = if (o.has(k) && !o.isNull(k)) o.getString(k) else null

    val LibraryModel.assets: List<String> get() = movements.values.mapNotNull { it.asset }

    /** `/api/library` (§1.6): shelves, works and movements, bundled + imported. UTF-8 is the caller's encoding. */
    fun encodeLibrary(model: LibraryModel): String {
        val shelves = JSONArray()
        for (s in model.shelves) shelves.put(JSONObject().put("id", s.id).put("title", s.title).put("works", JSONArray(s.workIds)))
        val works = JSONArray()
        for (w in model.works.values) works.put(JSONObject().put("id", w.id).put("composer", w.composer)
            .put("composerShort", w.composerShort).put("title", w.title).put("shortTitle", w.shortTitle)
            .put("defaultInstrument", w.defaultInstrument.key).put("imported", w.imported)
            .put("movements", JSONArray(w.movementIds)))
        val movements = JSONArray()
        for (m in model.movements.values) movements.put(JSONObject().put("id", m.id).put("workId", m.workId)
            .put("title", m.title).put("durationSec", Math.round(m.durationSec * 10.0) / 10.0)
            .put("imported", model.works[m.workId]?.imported == true))
        return JSONObject().put("shelves", shelves).put("works", works).put("movements", movements)
            .put("startHere", JSONArray(model.startHere)).toString()
    }
}
