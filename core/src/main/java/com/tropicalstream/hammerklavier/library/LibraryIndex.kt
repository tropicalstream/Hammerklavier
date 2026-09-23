package com.tropicalstream.hammerklavier.library

import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.LibraryModel
import com.tropicalstream.hammerklavier.contract.Movement
import com.tropicalstream.hammerklavier.contract.PedalMode
import com.tropicalstream.hammerklavier.contract.RejectReason
import com.tropicalstream.hammerklavier.contract.Shelf
import com.tropicalstream.hammerklavier.contract.Source
import com.tropicalstream.hammerklavier.contract.Work
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One imported movement in `filesDir/imports/index.json` (PLAN §4.6, schema 2). */
data class ImportItem(
    val movementId: String, val workId: String, val file: String, val source: String,
    val sourceSize: Long, val sourceMtime: Long, val originalName: String, val sha1Hex: String, val bytes: Long,
    val addedAt: Long, val folder: String?, val title: String, val composer: String, val durationSec: Float,
    val lowKey: Int, val highKey: Int, val hasSustain: Boolean, val hasSoft: Boolean, val hasSostenuto: Boolean,
    val pedalMode: PedalMode, val defaultInstrument: InstrumentId, val lastInstrument: InstrumentId?,
    /** Extra to the §4.6 list: the note count the companion reply reports. */
    val notes: Int = 0,
    /** Extra: re-indexed from its copy after index.json was lost; a rescan may adopt its source. */
    val recovered: Boolean = false)

/** A rejected source, recorded so it is not inspected again until (size, mtime) change. */
data class RejectRecord(val source: String, val size: Long, val mtime: Long, val reason: RejectReason, val detail: String)

/** A pushed file the user deleted from the library; the next rescan does not import it again. */
data class DeletedRecord(val source: String, val size: Long, val mtime: Long)

data class ImportIndex(val items: List<ImportItem>, val rejected: List<RejectRecord>, val deleted: List<DeletedRecord>) {
    companion object { val EMPTY = ImportIndex(emptyList(), emptyList(), emptyList()) }
}

/** `index.json` ↔ [ImportIndex], and the merge of bundled catalogue + imports into one [LibraryModel]. */
object LibraryIndex {
    const val SCHEMA = 2
    const val SOURCE_UPLOAD = "upload"
    const val USER_SOURCE_ID = "user"
    const val SHELF_START = "start"
    const val SHELF_IMPORTED = "imported"

    val USER_SOURCE = Source(id = USER_SOURCE_ID, credit = "", licence = "", licenceUrl = "", sourceUrl = "",
        licenceFile = null, performanceType = "user", tier = "user", exportAllowed = false)

    fun encode(ix: ImportIndex): String {
        val items = JSONArray()
        for (it in ix.items) items.put(JSONObject().apply {
            put("movementId", it.movementId); put("workId", it.workId); put("file", it.file); put("source", it.source)
            put("sourceSize", it.sourceSize); put("sourceMtime", it.sourceMtime); put("originalName", it.originalName)
            put("sha1Hex", it.sha1Hex); put("bytes", it.bytes); put("addedAt", it.addedAt)
            put("folder", it.folder ?: JSONObject.NULL); put("title", it.title); put("composer", it.composer)
            put("durationSec", it.durationSec.toDouble()); put("lowKey", it.lowKey); put("highKey", it.highKey)
            put("hasSustain", it.hasSustain); put("hasSoft", it.hasSoft); put("hasSostenuto", it.hasSostenuto)
            put("pedalMode", it.pedalMode.name.lowercase()); put("defaultInstrument", it.defaultInstrument.key)
            put("lastInstrument", it.lastInstrument?.key ?: JSONObject.NULL); put("notes", it.notes)
            if (it.recovered) put("recovered", true)
        })
        val rejected = JSONArray()
        for (r in ix.rejected) rejected.put(JSONObject().apply {
            put("source", r.source); put("size", r.size); put("mtime", r.mtime); put("reason", r.reason.name); put("detail", r.detail)
        })
        val deleted = JSONArray()
        for (d in ix.deleted) deleted.put(JSONObject().apply { put("source", d.source); put("size", d.size); put("mtime", d.mtime) })
        return JSONObject().put("schema", SCHEMA).put("items", items).put("rejected", rejected).put("deleted", deleted).toString(1)
    }

    /** Parses index.json; throws on a malformed or foreign document (the caller then rebuilds it). */
    fun parse(json: String): ImportIndex {
        val o = JSONObject(json)
        require(o.optInt("schema", -1) == SCHEMA) { "index.json schema ${o.opt("schema")}" }
        val items = ArrayList<ImportItem>()
        val ia = o.optJSONArray("items") ?: JSONArray()
        for (i in 0 until ia.length()) {
            val j = ia.getJSONObject(i)
            items += ImportItem(movementId = j.getString("movementId"), workId = j.getString("workId"), file = j.getString("file"),
                source = j.getString("source"), sourceSize = j.optLong("sourceSize"), sourceMtime = j.optLong("sourceMtime"),
                originalName = j.optString("originalName"), sha1Hex = j.getString("sha1Hex"), bytes = j.optLong("bytes"),
                addedAt = j.optLong("addedAt"), folder = if (j.isNull("folder")) null else j.optString("folder"),
                title = j.optString("title"), composer = j.optString("composer", "Imported"),
                durationSec = j.optDouble("durationSec", 0.0).toFloat(), lowKey = j.optInt("lowKey", 21),
                highKey = j.optInt("highKey", 108), hasSustain = j.optBoolean("hasSustain"), hasSoft = j.optBoolean("hasSoft"),
                hasSostenuto = j.optBoolean("hasSostenuto"), pedalMode = pedalModeOf(j.optString("pedalMode")),
                defaultInstrument = InstrumentId.of(j.optString("defaultInstrument")) ?: InstrumentId.GRAND,
                lastInstrument = if (j.isNull("lastInstrument")) null else InstrumentId.of(j.optString("lastInstrument")),
                notes = j.optInt("notes"), recovered = j.optBoolean("recovered"))
        }
        val rejected = ArrayList<RejectRecord>()
        val ra = o.optJSONArray("rejected") ?: JSONArray()
        for (i in 0 until ra.length()) {
            val j = ra.getJSONObject(i)
            val reason = runCatching { RejectReason.valueOf(j.getString("reason")) }.getOrDefault(RejectReason.IO_ERROR)
            rejected += RejectRecord(j.getString("source"), j.optLong("size"), j.optLong("mtime"), reason, j.optString("detail"))
        }
        val deleted = ArrayList<DeletedRecord>()
        val da = o.optJSONArray("deleted") ?: JSONArray()
        for (i in 0 until da.length()) {
            val j = da.getJSONObject(i)
            deleted += DeletedRecord(j.getString("source"), j.optLong("size"), j.optLong("mtime"))
        }
        return ImportIndex(items, rejected, deleted)
    }

    fun pedalModeOf(s: String?): PedalMode = when (s?.lowercase()) {
        "switch" -> PedalMode.SWITCH; "continuous" -> PedalMode.CONTINUOUS; else -> PedalMode.NONE
    }

    /** The movement of one imported item; [importsDir] resolves its copy. */
    fun movementOf(it: ImportItem, importsDir: File): Movement = Movement(id = it.movementId, workId = it.workId,
        title = it.title, asset = null, file = File(importsDir, it.file).path, sha1 = it.sha1Hex, durationSec = it.durationSec,
        lowKey = it.lowKey, highKey = it.highKey, hasSustain = it.hasSustain, hasSoft = it.hasSoft,
        hasSostenuto = it.hasSostenuto, pedalMode = it.pedalMode)

    /**
     * Bundled + imported (§1.5): the bundled shelves keep their order; the Imported shelf (only when
     * not empty) follows Start here. Imported works list their movements in filename order.
     */
    fun merge(bundled: LibraryModel, ix: ImportIndex, importsDir: File): LibraryModel {
        if (ix.items.isEmpty()) return bundled
        val works = LinkedHashMap(bundled.works)
        val movements = LinkedHashMap(bundled.movements)
        val byWork = LinkedHashMap<String, MutableList<ImportItem>>()
        for (it in ix.items) byWork.getOrPut(it.workId) { ArrayList() }.add(it)
        val importedWorkIds = ArrayList<String>()
        for ((workId, list) in byWork) {
            if (works.containsKey(workId) && works[workId]?.imported != true) continue      // never shadow a bundled id
            list.sortWith(Comparator { a, b -> ImportRules.NATURAL.compare(sortName(a), sortName(b)) })
            val first = list[0]
            val title = if (first.folder != null) first.folder.substringAfterLast('/') else first.title
            val def = first.lastInstrument ?: first.defaultInstrument
            works[workId] = Work(id = workId, composer = first.composer, composerShort = first.composer, title = title,
                shortTitle = title, catalogue = null, year = null, era = "imported", defaultInstrument = def,
                altInstruments = InstrumentId.entries.filter { it != def }, sourceId = USER_SOURCE_ID, tier = "user",
                velocityPolicy = "as-is", tuning = null, movementIds = list.map { it.movementId }, imported = true)
            for (it in list) movements[it.movementId] = movementOf(it, importsDir)
            importedWorkIds += workId
        }
        importedWorkIds.sortWith(Comparator { a, b ->
            val c = String.CASE_INSENSITIVE_ORDER.compare(works.getValue(a).title, works.getValue(b).title)
            if (c != 0) c else a.compareTo(b)
        })
        val shelves = ArrayList<Shelf>(bundled.shelves.size + 1)
        val imported = Shelf(SHELF_IMPORTED, "Imported", importedWorkIds)
        var placed = false
        for (s in bundled.shelves) {
            if (s.id == SHELF_IMPORTED) continue
            shelves += s
            if (s.id == SHELF_START) { shelves += imported; placed = true }
        }
        if (!placed) shelves.add(0, imported)
        val sources = LinkedHashMap(bundled.sources); sources[USER_SOURCE_ID] = USER_SOURCE
        return LibraryModel(shelves, works, movements, sources, bundled.startHere)
    }

    private fun sortName(it: ImportItem): String = it.originalName.substringAfterLast('/')
}
