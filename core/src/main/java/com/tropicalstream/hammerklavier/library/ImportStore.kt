package com.tropicalstream.hammerklavier.library

import com.tropicalstream.hammerklavier.contract.CompileResult
import com.tropicalstream.hammerklavier.contract.ImportResult
import com.tropicalstream.hammerklavier.contract.ImportScan
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.RejectReason
import com.tropicalstream.hammerklavier.contract.ScoreCompiler
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Import facts that the contract's Movement does not carry (the companion's upload reply). */
interface ImportedFacts {
    /** The note count of an imported movement, or null when it is not an import. */
    fun importedNotes(movementId: String): Int?
    /** The title of an imported movement, or null when it is not an import. */
    fun importedTitle(movementId: String): String? = null
    /** The duration in seconds of an imported movement, or null when it is not an import. */
    fun importedDurationSec(movementId: String): Float? = null
}

/**
 * The app-owned import store (PLAN §1.7, §4.6, §4.8): byte-identical copies in
 * `importsDir/<sha1>.mid`, SHA-1 dedupe, rejection records by (source, size, mtime), and
 * `importsDir/index.json` written to a temp file and renamed. Pure java.io; every public method
 * runs under one lock, so an upload during a rescan leaves a consistent index (T9.2).
 *
 * [scoresDir] is read-only input: nothing inside it is ever moved, renamed, written or deleted.
 * Rejections for PERMISSION_DENIED and IO_ERROR are not recorded: `chmod` does not change mtime, so
 * a recorded permission failure would never be retried after `push_scores.sh` fixes the mode.
 */
class ImportStore(val importsDir: File, val scoresDir: File, private val compiler: ScoreCompiler,
                  private val nowMs: () -> Long = System::currentTimeMillis) : ImportedFacts {
    private val lock = Any()
    private var items = ArrayList<ImportItem>()
    private var rejected = ArrayList<RejectRecord>()
    private var deleted = ArrayList<DeletedRecord>()
    private var loaded = false

    val indexFile: File get() = File(importsDir, INDEX)

    /** A snapshot of the index (loads it, or rebuilds it from the copies when it is missing). */
    fun index(): ImportIndex = synchronized(lock) { ensureLoaded(); snapshot() }

    override fun importedNotes(movementId: String): Int? = synchronized(lock) {
        ensureLoaded(); items.firstOrNull { it.movementId == movementId }?.notes
    }

    override fun importedTitle(movementId: String): String? = synchronized(lock) {
        ensureLoaded(); items.firstOrNull { it.movementId == movementId }?.title
    }

    override fun importedDurationSec(movementId: String): Float? = synchronized(lock) {
        ensureLoaded(); items.firstOrNull { it.movementId == movementId }?.durationSec
    }

    /** Forgets the in-memory index so the next call re-reads index.json (tests; after an external change). */
    fun reload() = synchronized(lock) { loaded = false }

    /** A companion upload of one MIDI file already written to [tmp]; [relativeName] may carry a folder. */
    fun save(tmp: File, relativeName: String): ImportResult = synchronized(lock) {
        ensureLoaded()
        val name = displayName(relativeName)
        val len = tmp.length()
        val r = when {
            len > ImportRules.MAX_UPLOAD_MIDI_BYTES -> reject(name, RejectReason.TOO_LARGE, "$len bytes")
            else -> {
                val bytes = try { tmp.readBytes() } catch (e: IOException) { null }
                if (bytes == null) reject(name, RejectReason.IO_ERROR, "cannot read the upload")
                else ingest(bytes, relativeName, LibraryIndex.SOURCE_UPLOAD, len, nowMs())
            }
        }
        if (r.ok) persist()
        r
    }

    /** A zip (uploaded, in [tmp]): every MIDI entry imported; [zipName] names the work of loose entries. */
    fun saveZip(tmp: File, zipName: String): List<ImportResult> = synchronized(lock) {
        ensureLoaded()
        val out = processZip(tmp, zipName, null, tmp.length(), nowMs())
        persist()
        out
    }

    /** New or changed files in [scoresDir] are inspected once and copied (§4.8). */
    fun rescan(): ImportScan = synchronized(lock) {
        ensureLoaded()
        val newRejects = ArrayList<ImportResult>()
        var added = 0
        if (!scoresDir.isDirectory) scoresDir.mkdirs()
        val files = ArrayList<Pair<File, String>>()
        if (scoresDir.isDirectory && scoresDir.listFiles() == null) {
            newRejects += reject("Scores", RejectReason.PERMISSION_DENIED, scoresDir.path)
        } else if (scoresDir.isDirectory) {
            walk(scoresDir, "", 0, files, newRejects)
        }
        files.sortWith(Comparator { a, b -> ImportRules.NATURAL.compare(a.second, b.second) })
        var changed = false
        for ((f, rel) in files) {
            val size = f.length(); val mtime = f.lastModified()
            if (known(rel, size, mtime)) continue
            changed = true
            rejected.removeAll { it.source == rel || it.source.startsWith("$rel!") }
            val results: List<ImportResult> = when {
                ImportRules.isZipName(rel) -> {
                    if (!f.canRead()) listOf(reject(displayName(rel), RejectReason.PERMISSION_DENIED, rel))
                    else processZip(f, rel, rel, size, mtime)
                }
                !ImportRules.isMidiName(rel) -> listOf(record(rel, size, mtime,
                    reject(displayName(rel), RejectReason.NOT_MIDI, "not a .mid, .midi, .kar, .rmi or .zip file")))
                size > ImportRules.MAX_MIDI_BYTES -> listOf(record(rel, size, mtime,
                    reject(displayName(rel), RejectReason.TOO_LARGE, "$size bytes")))
                else -> {
                    val bytes = readSource(f)
                    when (bytes) {
                        is ByteArray -> listOf(recordIfRejected(rel, size, mtime, ingest(bytes, rel, rel, size, mtime)))
                        else -> listOf(reject(displayName(rel), bytes as RejectReason, rel))
                    }
                }
            }
            for (r in results) if (r.ok) added++ else newRejects += r
        }
        if (changed || !indexFile.isFile) persist()
        ImportScan(added = added, rejected = newRejects, scoresDirForeign = false)
    }

    /** Removes the copy and its entry; a pushed file is remembered as deleted by (path, size, mtime). */
    fun delete(movementId: String): Boolean = synchronized(lock) {
        ensureLoaded()
        val it = items.firstOrNull { it.movementId == movementId } ?: return@synchronized false
        items.remove(it)
        if (items.none { o -> o.file == it.file }) File(importsDir, it.file).delete()
        if (it.source != LibraryIndex.SOURCE_UPLOAD) deleted += DeletedRecord(it.source, it.sourceSize, it.sourceMtime)
        persist()
        true
    }

    /** Remembers the instrument last chosen for an imported work's movement (index `lastInstrument`). */
    fun setLastInstrument(movementId: String, id: InstrumentId): Boolean = synchronized(lock) {
        ensureLoaded()
        val i = items.indexOfFirst { it.movementId == movementId }
        if (i < 0) return@synchronized false
        items[i] = items[i].copy(lastInstrument = id)
        persist()
        true
    }

    // ───────────────────────────── internals (all under the lock) ─────────────────────────────

    private fun snapshot() = ImportIndex(items.toList(), rejected.toList(), deleted.toList())

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        items = ArrayList(); rejected = ArrayList(); deleted = ArrayList()
        val parsed = try {
            if (indexFile.isFile) LibraryIndex.parse(indexFile.readText(Charsets.UTF_8)) else null
        } catch (e: Exception) { null }
        if (parsed != null) {
            items.addAll(parsed.items); rejected.addAll(parsed.rejected); deleted.addAll(parsed.deleted)
            return
        }
        recoverFromCopies()
        if (items.isNotEmpty()) persist()
    }

    /** index.json lost: every `<sha1>.mid` copy is re-indexed as an upload (a rescan re-adopts pushed ones). */
    private fun recoverFromCopies() {
        val copies = importsDir.listFiles() ?: return
        copies.sortBy { it.name }
        for (f in copies) {
            val n = f.name
            if (!n.endsWith(".mid") || !Sha1.isHex40(n.removeSuffix(".mid"))) continue
            val bytes = try { f.readBytes() } catch (e: IOException) { continue }
            val facts = compiler.inspect(bytes)
            if (!facts.ok || facts.noteCount < 1) continue
            val sha = Sha1.hex(bytes)
            if (sha != n.removeSuffix(".mid") || items.any { it.sha1Hex == sha }) continue
            val title = ImportRules.titleOf(facts.title, "Imported ${sha.take(6)}")
            items += newItem(sha, bytes, facts, title, n, LibraryIndex.SOURCE_UPLOAD, f.length(), f.lastModified(), null,
                recovered = true)
        }
    }

    private fun known(rel: String, size: Long, mtime: Long): Boolean {
        val zipPrefix = "$rel!"
        return items.any { (it.source == rel || it.source.startsWith(zipPrefix)) && it.sourceSize == size && it.sourceMtime == mtime } ||
            rejected.any { (it.source == rel || it.source.startsWith(zipPrefix)) && it.size == size && it.mtime == mtime } ||
            deleted.any { (it.source == rel || it.source.startsWith(zipPrefix)) && it.size == size && it.mtime == mtime }
    }

    private fun walk(dir: File, prefix: String, depth: Int, out: MutableList<Pair<File, String>>, rejects: MutableList<ImportResult>) {
        val list = dir.listFiles()
        if (list == null) { rejects += reject(prefix.ifEmpty { "Scores" }, RejectReason.PERMISSION_DENIED, dir.path); return }
        for (f in list) {
            if (f.name.startsWith(".")) continue
            val rel = if (prefix.isEmpty()) f.name else "$prefix/${f.name}"
            if (f.isDirectory) { if (depth < MAX_DEPTH) walk(f, rel, depth + 1, out, rejects) }
            else if (f.isFile || !f.canRead()) out += f to rel
        }
    }

    /** The bytes of a pushed file, or PERMISSION_DENIED / IO_ERROR. */
    private fun readSource(f: File): Any {
        if (!f.canRead()) return RejectReason.PERMISSION_DENIED
        return try { f.readBytes() } catch (e: FileNotFoundException) { RejectReason.PERMISSION_DENIED } catch (e: IOException) { RejectReason.IO_ERROR }
    }

    private fun processZip(zip: File, displayRel: String, sourcePrefix: String?, size: Long, mtime: Long): List<ImportResult> {
        val zipDisplay = displayName(displayRel)
        fun whole(reason: RejectReason, detail: String): List<ImportResult> {
            val r = reject(zipDisplay, reason, detail)
            return listOf(if (sourcePrefix != null) record(sourcePrefix, size, mtime, r) else r)
        }
        if (zip.length() > ImportRules.MAX_ZIP_BYTES) return whole(RejectReason.ZIP_LIMIT, "${zip.length()} bytes")
        val zf = try { ZipFile(zip) } catch (e: FileNotFoundException) {
            return listOf(reject(zipDisplay, RejectReason.PERMISSION_DENIED, displayRel))
        } catch (e: IOException) { return whole(RejectReason.NOT_MIDI, "not a zip file") }
        zf.use { z ->
            val entries = ArrayList<ZipEntry>()
            val en = z.entries()
            while (en.hasMoreElements()) {
                entries += en.nextElement()
                if (entries.size > ImportRules.MAX_ZIP_ENTRIES) return whole(RejectReason.ZIP_LIMIT, "more than ${ImportRules.MAX_ZIP_ENTRIES} entries")
            }
            for (e in entries) if (isTraversal(e.name)) return whole(RejectReason.ZIP_TRAVERSAL, e.name)
            val declared = entries.sumOf { if (it.size > 0) it.size else 0L }
            if (declared > ImportRules.MAX_ZIP_TOTAL_BYTES) return whole(RejectReason.ZIP_LIMIT, "$declared bytes uncompressed")
            val midi = entries.filter { !it.isDirectory && ImportRules.isMidiName(it.name) && !skippedEntry(it.name) }
                .sortedWith(Comparator { a, b -> ImportRules.NATURAL.compare(a.name, b.name) })
            if (midi.isEmpty()) return whole(RejectReason.NOT_MIDI, "no MIDI files in the zip")
            val base = ImportRules.stripExtension(ImportRules.fileNameOf(displayRel))
            val out = ArrayList<ImportResult>()
            var total = 0L
            for (e in midi) {
                val parts = ImportRules.components(e.name)
                val rel = if (parts.size >= 2) parts.joinToString("/") else "$base/${parts.last()}"
                val source = if (sourcePrefix != null) "$sourcePrefix!${e.name}" else LibraryIndex.SOURCE_UPLOAD
                val bytes = try { z.getInputStream(e).use { readBounded(it, ImportRules.MAX_MIDI_BYTES) } } catch (x: IOException) { null }
                val r = when {
                    bytes == null -> reject(displayName(rel), RejectReason.IO_ERROR, e.name)
                    bytes.isEmpty() && e.size != 0L -> reject(displayName(rel), RejectReason.TOO_LARGE, e.name)
                    total + bytes.size > ImportRules.MAX_ZIP_TOTAL_BYTES -> reject(displayName(rel), RejectReason.ZIP_LIMIT, e.name)
                    else -> { total += bytes.size; ingest(bytes, rel, source, size, mtime) }
                }
                out += if (sourcePrefix != null) recordIfRejected(source, size, mtime, r) else r
            }
            return out
        }
    }

    private fun skippedEntry(name: String): Boolean =
        name.startsWith("__MACOSX/") || ImportRules.components(name).any { it.startsWith(".") }

    private fun isTraversal(name: String): Boolean {
        if (name.startsWith("/") || name.startsWith("\\")) return true
        if (name.length >= 2 && name[1] == ':') return true
        return name.split('/', '\\').any { it == ".." }
    }

    /** Reads at most [limit] bytes; returns an empty array when the stream is longer (the caller rejects it). */
    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > limit) return ByteArray(0)
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }

    /** sniff → inspect → ≥ 1 keyboard note → SHA-1 dedupe → byte-identical copy → index entry (§4.8). */
    private fun ingest(bytes: ByteArray, relativeName: String, source: String, size: Long, mtime: Long): ImportResult {
        val name = displayName(relativeName)
        if (bytes.size > ImportRules.MAX_MIDI_BYTES) return reject(name, RejectReason.TOO_LARGE, "${bytes.size} bytes")
        if (!compiler.sniff(bytes.copyOf(minOf(bytes.size, 16)))) return reject(name, RejectReason.NOT_MIDI, "no MThd or RIFF RMID header")
        val facts = try { compiler.inspect(bytes) } catch (e: Throwable) { return reject(name, RejectReason.IO_ERROR, e.toString()) }
        if (!facts.ok) {
            // §4.8: keep the parser's detail and byte offset; inspect() carries neither, compile() does.
            val c = try { compiler.compile(bytes, "user.reject", 0, InstrumentProfile.HARPSICHORD) } catch (e: Throwable) { null }
            if (c is CompileResult.Failed) return reject(name, facts.error ?: c.reason, "${c.detail} @${c.byteOffset}")
            return reject(name, facts.error ?: RejectReason.BAD_HEADER, "")
        }
        if (facts.noteCount < 1) return reject(name, RejectReason.NO_KEYBOARD_NOTES, "")
        val sha = Sha1.hex(bytes)
        val existingIdx = items.indexOfFirst { it.sha1Hex == sha }
        val folder = ImportRules.folderOf(relativeName)
        val title = ImportRules.titleOf(facts.title, relativeName)
        if (existingIdx >= 0) {
            val ex = items[existingIdx]
            if (ex.recovered && source != LibraryIndex.SOURCE_UPLOAD) {
                val adopted = newItem(sha, bytes, facts, title, ImportRules.fileNameOf(relativeName), source, size, mtime, folder,
                    keepId = ex.movementId)
                items[existingIdx] = adopted.copy(addedAt = ex.addedAt, lastInstrument = ex.lastInstrument)
                return ImportResult(true, name, ex.movementId, null, null)
            }
            return ImportResult(false, name, ex.movementId, RejectReason.DUPLICATE, "already imported as «${ex.title}»")
        }
        importsDir.mkdirs()
        val copy = File(importsDir, "$sha.mid")
        try {
            if (!(copy.isFile && copy.length() == bytes.size.toLong() && Sha1.hex(copy) == sha)) {
                val part = File(importsDir, "$sha.mid.part")
                part.writeBytes(bytes)
                move(part, copy)
            }
        } catch (e: IOException) { return reject(name, RejectReason.IO_ERROR, "cannot write the copy: ${e.message}") }
        val item = newItem(sha, bytes, facts, title, ImportRules.fileNameOf(relativeName), source, size, mtime, folder,
            relativeForNames = relativeName)
        items += item
        return ImportResult(true, name, item.movementId, null, null)
    }

    private fun newItem(sha: String, bytes: ByteArray, facts: com.tropicalstream.hammerklavier.contract.ScoreFacts, title: String,
                        originalName: String, source: String, size: Long, mtime: Long, folder: String?,
                        recovered: Boolean = false, keepId: String? = null, relativeForNames: String = originalName): ImportItem {
        val names = (folder ?: "") + "/" + relativeForNames + "/" + originalName
        val folded = if (ImportRules.needsFoldCount(facts.hasSustain, facts.lowKey, facts.highKey, names)) {
            when (val c = compiler.compile(bytes, "user.${sha.take(10)}", 0, InstrumentProfile.HARPSICHORD)) {
                is CompileResult.Ok -> c.perf.info.folded
                else -> Int.MAX_VALUE
            }
        } else 0
        val def = ImportRules.defaultInstrument(facts.hasSustain, facts.lowKey, facts.highKey, facts.noteCount, folded, names)
        val movementId = keepId ?: "user.${sha.take(10)}"
        return ImportItem(movementId = movementId, workId = workIdFor(folder, title, movementId), file = "$sha.mid",
            source = source, sourceSize = size, sourceMtime = mtime, originalName = originalName, sha1Hex = sha,
            bytes = bytes.size.toLong(), addedAt = nowMs(), folder = folder, title = title,
            composer = ImportRules.composerOf(folder), durationSec = facts.durationSec, lowKey = facts.lowKey,
            highKey = facts.highKey, hasSustain = facts.hasSustain, hasSoft = facts.hasSoft, hasSostenuto = facts.hasSostenuto,
            pedalMode = facts.pedalMode, defaultInstrument = def, lastInstrument = null, notes = facts.noteCount,
            recovered = recovered)
    }

    /** `user.<folder-or-file-slug>`: a folder keeps one work; a clash with another folder or file gets `-2`, `-3`… */
    private fun workIdFor(folder: String?, title: String, movementId: String): String {
        val others = items.filter { it.movementId != movementId }
        if (folder != null) others.firstOrNull { it.folder == folder }?.let { return it.workId }
        val base = "user." + ImportRules.slug(folder ?: title)
        var id = base
        var n = 2
        while (others.any { it.workId == id }) id = "$base-${n++}"
        return id
    }

    private fun record(source: String, size: Long, mtime: Long, r: ImportResult): ImportResult {
        val reason = r.reason ?: return r
        // DUPLICATE is not recorded either: once the original is deleted the file is no longer a duplicate.
        if (reason == RejectReason.PERMISSION_DENIED || reason == RejectReason.IO_ERROR || reason == RejectReason.DUPLICATE) return r
        rejected.removeAll { it.source == source }
        rejected += RejectRecord(source, size, mtime, reason, r.detail ?: "")
        return r
    }

    private fun recordIfRejected(source: String, size: Long, mtime: Long, r: ImportResult): ImportResult =
        if (r.ok) r else record(source, size, mtime, r)

    private fun reject(name: String, reason: RejectReason, detail: String) = ImportResult(false, name, null, reason, detail)

    private fun displayName(relativeName: String): String =
        ImportRules.components(relativeName).joinToString("/") { ImportRules.sanitizeDisplayName(it) }.ifEmpty { "untitled" }

    private fun persist() {
        importsDir.mkdirs()
        val tmp = File(importsDir, "$INDEX.tmp")
        tmp.writeText(LibraryIndex.encode(snapshot()), Charsets.UTF_8)
        move(tmp, indexFile)
    }

    private fun move(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val INDEX = "index.json"
        const val MAX_DEPTH = 6
    }
}
