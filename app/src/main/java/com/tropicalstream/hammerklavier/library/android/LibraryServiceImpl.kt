package com.tropicalstream.hammerklavier.library.android

import android.content.Context
import android.os.Process
import android.system.Os
import com.tropicalstream.hammerklavier.contract.ImportResult
import com.tropicalstream.hammerklavier.contract.ImportScan
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.LibraryModel
import com.tropicalstream.hammerklavier.contract.LibraryService
import com.tropicalstream.hammerklavier.contract.Movement
import com.tropicalstream.hammerklavier.contract.PedalMode
import com.tropicalstream.hammerklavier.contract.RejectReason
import com.tropicalstream.hammerklavier.contract.ScoreCompiler
import com.tropicalstream.hammerklavier.contract.Shelf
import com.tropicalstream.hammerklavier.contract.Source
import com.tropicalstream.hammerklavier.contract.Work
import com.tropicalstream.hammerklavier.library.CatalogCodec
import com.tropicalstream.hammerklavier.library.ImportRules
import com.tropicalstream.hammerklavier.library.ImportStore
import com.tropicalstream.hammerklavier.library.ImportedFacts
import com.tropicalstream.hammerklavier.library.LibraryIndex
import java.io.File

/**
 * LibraryService (PLAN §1.5–§1.7, §4.6, §4.8): the bundled `assets/catalog.json` (plus the
 * `assets/midi/test/` twins as a "Test scores" shelf when present) merged with the app-owned import
 * store in `filesDir/imports/`. `readBytes` resolves `asset:<path>`, `test:<name>`, `synth:<kind>`
 * (no bytes: compile with ScoreCompiler.synthetic) and catalogue / imported movements. Every method
 * runs under one lock.
 */
class LibraryServiceImpl(ctx: Context, private val compiler: ScoreCompiler) : LibraryService, ImportedFacts {
    private val app = ctx.applicationContext
    private val lock = Any()
    override val scoresDir: File = (app.getExternalFilesDir(SCORES) ?: File(app.filesDir, SCORES)).also { it.mkdirs() }
    private val store = ImportStore(File(app.filesDir, "imports"), scoresDir, compiler)
    private var bundled: LibraryModel? = null

    override fun load(): LibraryModel = synchronized(lock) { LibraryIndex.merge(bundled(), store.index(), store.importsDir) }

    override fun readBytes(m: Movement): ByteArray = synchronized(lock) {
        val id = m.id
        when {
            id.startsWith("synth:") -> ByteArray(0)
            id.startsWith("asset:") -> asset(id.removePrefix("asset:")) ?: ByteArray(0)
            id.startsWith("test:") -> asset("midi/test/${id.removePrefix("test:")}.mid") ?: ByteArray(0)
            m.asset != null -> asset(m.asset!!) ?: ByteArray(0)
            m.file != null -> File(m.file!!).takeIf { it.isFile }?.readBytes() ?: ByteArray(0)
            else -> ByteArray(0)
        }
    }

    override fun rescan(): ImportScan = synchronized(lock) {
        val s = store.rescan()
        ImportScan(s.added, s.rejected, scoresDirForeign())
    }

    override fun importFile(tmp: File, relativeName: String): ImportResult = synchronized(lock) {
        if (ImportRules.isZipName(relativeName)) {
            val all = store.saveZip(tmp, relativeName)
            val ok = all.firstOrNull { it.ok }
            ok ?: all.firstOrNull() ?: ImportResult(false, relativeName, null, RejectReason.NOT_MIDI, "empty zip")
        } else store.save(tmp, relativeName)
    }

    override fun importZip(tmp: File): List<ImportResult> = synchronized(lock) { store.saveZip(tmp, tmp.name) }

    /** A zip upload with its original name (the work title of loose entries). */
    fun importZip(tmp: File, name: String): List<ImportResult> = synchronized(lock) { store.saveZip(tmp, name) }

    override fun delete(movementId: String): Boolean = synchronized(lock) { store.delete(movementId) }

    override fun importedNotes(movementId: String): Int? = store.importedNotes(movementId)
    override fun importedTitle(movementId: String): String? = store.importedTitle(movementId)
    override fun importedDurationSec(movementId: String): Float? = store.importedDurationSec(movementId)

    fun setLastInstrument(movementId: String, id: InstrumentId): Boolean = synchronized(lock) { store.setLastInstrument(movementId, id) }

    /** `Import folder owned by adb`: the drop folder's owner is not this app (§1.7). */
    fun scoresDirForeign(): Boolean = try { Os.stat(scoresDir.path).st_uid != Process.myUid() } catch (e: Exception) { false }

    private fun asset(path: String): ByteArray? = try { app.assets.open(path).use { it.readBytes() } } catch (e: Exception) { null }

    private fun assetExists(path: String): Boolean = try { app.assets.open(path).close(); true } catch (e: Exception) { false }

    private fun bundled(): LibraryModel {
        bundled?.let { return it }
        val catalogue = asset(CATALOG)?.let { bytes ->
            try { CatalogCodec.parse(String(bytes, Charsets.UTF_8), ::assetExists) } catch (e: Exception) { null }
        }
        val base = catalogue ?: LibraryModel(emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyList())
        // The test shelf is an engineering aid: only in debug builds or when no catalogue parsed (M2 bundled-only).
        // test: ids still resolve in readBytes either way (§1.5 fixes the release library at 15 shelves).
        val model = if (com.tropicalstream.hammerklavier.BuildConfig.DEBUG || catalogue == null) withTestShelf(base) else base
        bundled = model
        return model
    }

    /** The WP11 test twins (`assets/midi/test/<name>.mid`) as a last shelf, ids `test:<name>`. */
    private fun withTestShelf(m: LibraryModel): LibraryModel {
        val names = (try { app.assets.list("midi/test") } catch (e: Exception) { null })
            ?.filter { it.endsWith(".mid") }?.map { it.removeSuffix(".mid") }?.sorted().orEmpty()
        if (names.isEmpty()) return m
        val movements = LinkedHashMap(m.movements)
        val ids = names.map { n ->
            val id = "test:$n"
            movements[id] = Movement(id = id, workId = TEST, title = n, asset = "midi/test/$n.mid", file = null, sha1 = "",
                durationSec = 0f, lowKey = 21, highKey = 108, hasSustain = false, hasSoft = false, hasSostenuto = false,
                pedalMode = PedalMode.NONE)
            id
        }
        val works = LinkedHashMap(m.works)
        works[TEST] = Work(id = TEST, composer = "Hammerklavier", composerShort = "HK", title = "Test scores", shortTitle = "Tests",
            catalogue = null, year = null, era = "test", defaultInstrument = InstrumentId.GRAND,
            altInstruments = InstrumentId.entries.toList(), sourceId = "hk", tier = "test", velocityPolicy = "as-is",
            tuning = null, movementIds = ids, imported = false)
        val sources = LinkedHashMap(m.sources)
        sources["hk"] = Source("hk", "Hammerklavier test scores", "CC0-1.0", "", "", null, "synthetic", "test", true)
        return LibraryModel(m.shelves + Shelf(TEST, "Test scores", listOf(TEST)), works, movements, sources,
            m.startHere)
    }

    companion object {
        const val SCORES = "Scores"
        const val CATALOG = "catalog.json"
        const val TEST = "test"
    }
}
