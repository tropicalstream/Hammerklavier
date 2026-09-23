package com.tropicalstream.hammerklavier.contract

class Source(val id: String, val credit: String, val licence: String, val licenceUrl: String, val sourceUrl: String,
    val licenceFile: String?, val performanceType: String, val tier: String, val exportAllowed: Boolean)

class Movement(val id: String, val workId: String, val title: String, val asset: String?, val file: String?,
    val sha1: String, val durationSec: Float, val lowKey: Int, val highKey: Int,
    val hasSustain: Boolean, val hasSoft: Boolean, val hasSostenuto: Boolean, val pedalMode: PedalMode)

class Work(val id: String, val composer: String, val composerShort: String, val title: String, val shortTitle: String,
    val catalogue: String?, val year: Int?, val era: String, val defaultInstrument: InstrumentId,
    val altInstruments: List<InstrumentId>, val sourceId: String, val tier: String, val velocityPolicy: String /*as-is|flat*/,
    val tuning: TuningSpec?, val movementIds: List<String>, val imported: Boolean)

class Shelf(val id: String, val title: String, val workIds: List<String>)

class LibraryModel(val shelves: List<Shelf>, val works: Map<String, Work>, val movements: Map<String, Movement>,
    val sources: Map<String, Source>, val startHere: List<String> /*movement ids*/)

class ImportResult(val ok: Boolean, val name: String, val movementId: String?, val reason: RejectReason?, val detail: String?)

class ImportScan(val added: Int, val rejected: List<ImportResult>, val scoresDirForeign: Boolean)

/** Every method runs under one lock; index.json written to a temp file and renamed. */
interface LibraryService {
    /** HKLoader; bundled + imported. */
    fun load(): LibraryModel
    /** "asset:<path>" ids resolve directly (M2). */
    fun readBytes(m: Movement): ByteArray
    fun rescan(): ImportScan
    fun importFile(tmp: java.io.File, relativeName: String): ImportResult
    fun importZip(tmp: java.io.File): List<ImportResult>
    fun delete(movementId: String): Boolean
    /** The adb drop folder (read-only input). */
    val scoresDir: java.io.File
}

/**
 * The current playlist (pure; main thread). [next] at the end returns null and stays on the last
 * item; [previous] more than 3 s into an item (or on the first item) returns the same id: restart.
 */
class Playlist {
    private var ids: List<String> = emptyList()
    private var i = -1

    fun set(ids: List<String>, index: Int) {
        this.ids = ids.toList()
        i = if (this.ids.isEmpty()) -1 else index.coerceIn(0, this.ids.size - 1)
    }

    val current: String? get() = if (i in ids.indices) ids[i] else null
    val index: Int get() = i
    val size: Int get() = ids.size

    fun peekNext(): String? = if (i >= 0 && i + 1 < ids.size) ids[i + 1] else null

    fun next(): String? {
        if (i < 0 || i + 1 >= ids.size) return null
        i++
        return ids[i]
    }

    /** Same id = restart (> 3 s in). */
    fun previous(positionSec: Float): String? {
        if (i < 0) return null
        if (positionSec > 3f || i == 0) return ids[i]
        i--
        return ids[i]
    }
}

/** Pure view of Settings (WP0); MemSettings in tests. */
interface SettingsStore {
    fun getString(k: String, d: String): String; fun putString(k: String, v: String)
    fun getInt(k: String, d: Int): Int; fun putInt(k: String, v: Int)
    fun getFloat(k: String, d: Float): Float; fun putFloat(k: String, v: Float)
    fun getBool(k: String, d: Boolean): Boolean; fun putBool(k: String, v: Boolean)
}
