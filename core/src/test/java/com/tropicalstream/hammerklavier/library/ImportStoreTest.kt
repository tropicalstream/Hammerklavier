package com.tropicalstream.hammerklavier.library

import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.LibraryModel
import com.tropicalstream.hammerklavier.contract.RejectReason
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** T9.2: ImportStore. */
class ImportStoreTest {
    private lateinit var root: File
    private lateinit var imports: File
    private lateinit var scores: File
    private lateinit var store: ImportStore
    private var clock = 1_000L

    @Before fun setUp() {
        root = Files.createTempDirectory("wp9").toFile()
        imports = File(root, "files/imports"); scores = File(root, "Scores"); scores.mkdirs()
        store = ImportStore(imports, scores, MiniCompiler()) { clock++ }
    }

    @After fun tearDown() { root.walkBottomUp().forEach { it.setReadable(true); it.setWritable(true); it.delete() } }

    private fun tmp(bytes: ByteArray): File = File.createTempFile("upload-", ".tmp", root).apply { writeBytes(bytes) }
    private fun push(rel: String, bytes: ByteArray): File = File(scores, rel).apply { parentFile.mkdirs(); writeBytes(bytes) }
    private fun freshIndex(): ImportIndex = LibraryIndex.parse(File(imports, "index.json").readText())

    @Test fun uploadIsCopiedByteForByteUnderItsSha1() {
        val bytes = TestMidi.scale("Minuet")
        val r = store.save(tmp(bytes), "Händel Suite.mid")
        assertTrue(r.detail, r.ok); assertEquals("Händel Suite.mid", r.name)
        val sha = Sha1.hex(bytes)
        assertEquals("user.${sha.take(10)}", r.movementId)
        assertArrayEquals(bytes, File(imports, "$sha.mid").readBytes())
        val it = freshIndex().items.single()
        assertEquals("upload", it.source); assertEquals("Minuet", it.title); assertEquals(8, it.notes)
        assertEquals("user.minuet", it.workId); assertEquals(InstrumentId.HARPSICHORD, it.defaultInstrument)   // "Händel" in the name
        assertEquals(8, store.importedNotes(r.movementId!!))
    }

    @Test fun nonMidiIsRejectedAndRecordedWithoutTouchingTheSource() {
        val src = push("notes.mid", "hello, not a midi file".toByteArray())
        val mtime = src.lastModified(); val before = src.readBytes()
        val scan = store.rescan()
        assertEquals(0, scan.added); assertEquals(RejectReason.NOT_MIDI, scan.rejected.single().reason)
        val rec = freshIndex().rejected.single()
        assertEquals("notes.mid", rec.source); assertEquals(src.length(), rec.size); assertEquals(mtime, rec.mtime)
        assertArrayEquals(before, src.readBytes()); assertEquals(mtime, src.lastModified())
        assertTrue(store.rescan().rejected.isEmpty())                       // not inspected again
        src.writeBytes(TestMidi.scale("Now valid")); src.setLastModified(mtime + 5000)
        val again = store.rescan()
        assertEquals(1, again.added); assertTrue(freshIndex().rejected.isEmpty())
        assertTrue(src.isFile)                                               // Scores/ is never modified
    }

    @Test fun otherExtensionsAndEmptyFilesAreRejected() {
        push("readme.txt", "x".toByteArray())
        push("empty.mid", TestMidi.smf("Nothing", emptyList()))
        val scan = store.rescan()
        assertEquals(setOf(RejectReason.NOT_MIDI, RejectReason.NO_KEYBOARD_NOTES), scan.rejected.map { it.reason }.toSet())
    }

    @Test fun sha1DuplicatesAreRejected() {
        val bytes = TestMidi.scale("Twice")
        assertTrue(store.save(tmp(bytes), "a.mid").ok)
        val r = store.save(tmp(bytes), "b.mid")
        assertFalse(r.ok); assertEquals(RejectReason.DUPLICATE, r.reason); assertEquals("already imported as «Twice»", r.detail)
        assertEquals(1, freshIndex().items.size)
    }

    @Test fun uploadLimits() {
        val big = TestMidi.scale("Big") + ByteArray(ImportRules.MAX_UPLOAD_MIDI_BYTES)
        assertEquals(RejectReason.TOO_LARGE, store.save(tmp(big), "big.mid").reason)
        val pushed = push("huge.mid", TestMidi.scale("Huge") + ByteArray(ImportRules.MAX_MIDI_BYTES))
        assertEquals(RejectReason.TOO_LARGE, store.rescan().rejected.single().reason)
        assertTrue(pushed.isFile)
    }

    private fun zip(entries: List<Pair<String, ByteArray>>): File {
        val f = File.createTempFile("upload-", ".zip", root)
        ZipOutputStream(f.outputStream()).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return f
    }

    @Test fun zipOf201EntriesIsRejected() {
        val entries = (1..201).map { "m$it.mid" to TestMidi.scale("m$it", salt = it % 100 + 1) }
        val r = store.saveZip(zip(entries), "many.zip")
        assertEquals(RejectReason.ZIP_LIMIT, r.single().reason); assertTrue(freshIndex().items.isEmpty())
        val ok = store.saveZip(zip(entries.take(200)), "two-hundred.zip")
        assertEquals(200, ok.count { it.ok })
    }

    @Test fun zipOver20MiBIsRejected() {
        val f = File.createTempFile("upload-", ".zip", root)
        java.io.RandomAccessFile(f, "rw").use { it.setLength(ImportRules.MAX_ZIP_BYTES + 1L) }
        assertEquals(RejectReason.ZIP_LIMIT, store.saveZip(f, "big.zip").single().reason)
    }

    @Test fun zipTraversalIsRejected() {
        val r = store.saveZip(zip(listOf("ok.mid" to TestMidi.scale("ok"), "../evil.mid" to TestMidi.scale("evil", salt = 3))), "t.zip")
        assertEquals(RejectReason.ZIP_TRAVERSAL, r.single().reason)
        assertTrue(freshIndex().items.isEmpty())
        assertFalse(File(imports.parentFile, "evil.mid").exists())
        assertEquals(RejectReason.ZIP_TRAVERSAL, store.saveZip(zip(listOf("/abs.mid" to TestMidi.scale("a"))), "a.zip").single().reason)
    }

    @Test fun zipBecomesOneWorkInFilenameOrder() {
        val r = store.saveZip(zip(listOf("10.mid" to TestMidi.scale(null, 70), "2.mid" to TestMidi.scale(null, 62),
            "readme.txt" to "x".toByteArray(), "1.mid" to TestMidi.scale(null, 60))), "Partita.zip")
        assertEquals(3, r.count { it.ok })
        val model = LibraryIndex.merge(empty(), store.index(), imports)
        val work = model.works.values.single()
        assertEquals("Partita", work.title)
        assertEquals(listOf("1", "2", "10"), work.movementIds.map { model.movements.getValue(it).title })
    }

    @Test fun subfolderIsOneWorkInFilenameOrder() {
        push("Beethoven/Op 109/3.mid", TestMidi.scale(null, 64))
        push("Beethoven/Op 109/1.mid", TestMidi.scale(null, 60))
        push("Beethoven/Op 109/2.mid", TestMidi.scale(null, 62))
        push("loose.mid", TestMidi.scale("Loose", 50))
        assertEquals(4, store.rescan().added)
        val model = LibraryIndex.merge(empty(), store.index(), imports)
        val op109 = model.works.getValue("user.beethoven-op-109")
        assertEquals("Op 109", op109.title); assertEquals("Beethoven", op109.composer); assertTrue(op109.imported)
        assertEquals(listOf("1", "2", "3"), op109.movementIds.map { model.movements.getValue(it).title })
        assertEquals("Imported", model.works.getValue("user.loose").composer)
        assertEquals("imported", model.shelves.first().id)
        assertEquals(2, model.shelves.first().workIds.size)
        // the copies are byte-identical to the pushed files
        for (m in model.movements.values) assertTrue(File(m.file!!).isFile)
        assertArrayEquals(File(scores, "Beethoven/Op 109/1.mid").readBytes(),
            File(model.movements.getValue(op109.movementIds[0]).file!!).readBytes())
    }

    @Test fun harpsichordDefaultFromFolderName() {
        push("scarlatti/k141.mid", TestMidi.scale(null, 40))
        push("scarlatti/k9.mid", TestMidi.scale(null, 40, sustain = true))
        store.rescan()
        val byName = store.index().items.associateBy { it.originalName }
        assertEquals(InstrumentId.HARPSICHORD, byName.getValue("k141.mid").defaultInstrument)
        assertEquals(InstrumentId.GRAND, byName.getValue("k9.mid").defaultInstrument)
    }

    @Test fun deleteRemovesCopyAndIsRemembered() {
        push("a.mid", TestMidi.scale("A"))
        store.rescan()
        val item = store.index().items.single()
        assertTrue(store.delete(item.movementId))
        assertFalse(File(imports, item.file).exists())
        assertTrue(freshIndex().items.isEmpty()); assertEquals("a.mid", freshIndex().deleted.single().source)
        assertEquals(0, store.rescan().added)                     // not imported again
        assertTrue(File(scores, "a.mid").isFile)
        assertFalse(store.delete("user.nothing"))
    }

    @Test fun indexIsRebuiltAfterDeletion() {
        assertTrue(store.save(tmp(TestMidi.scale("Uploaded")), "u.mid").ok)
        push("Op 1/1.mid", TestMidi.scale(null, 55))
        store.rescan()
        assertEquals(2, freshIndex().items.size)
        File(imports, "index.json").delete()
        val fresh = ImportStore(imports, scores, MiniCompiler())
        assertEquals(2, fresh.index().items.size)                   // both re-indexed from their copies
        assertTrue(File(imports, "index.json").isFile)
        fresh.rescan()
        val ix = freshIndex()
        assertEquals(2, ix.items.size)
        val pushed = ix.items.first { it.source == "Op 1/1.mid" }  // the pushed file is re-adopted with its folder
        assertEquals("Op 1", pushed.folder); assertFalse(pushed.recovered)
        assertEquals("Uploaded", ix.items.first { it.source == "upload" }.title)
    }

    @Test fun corruptIndexIsRebuilt() {
        assertTrue(store.save(tmp(TestMidi.scale("Kept")), "k.mid").ok)
        File(imports, "index.json").writeText("{ not json")
        assertEquals("Kept", ImportStore(imports, scores, MiniCompiler()).index().items.single().title)
    }

    @Test fun unreadableSourceIsPermissionDenied() {
        val f = push("locked.mid", TestMidi.scale("Locked"))
        assumeTrue(f.setReadable(false, false) && !f.canRead())       // skipped when running as root
        val scan = store.rescan()
        assertEquals(RejectReason.PERMISSION_DENIED, scan.rejected.single().reason)
        assertTrue(freshIndex().rejected.isEmpty())                 // not recorded: chmod does not change mtime
        f.setReadable(true, false)
        assertEquals(1, store.rescan().added)
    }

    @Test fun unreadableSubfolderIsPermissionDenied() {
        push("Private/1.mid", TestMidi.scale(null))
        val d = File(scores, "Private")
        assumeTrue(d.setReadable(false, false) && d.listFiles() == null)
        val scan = store.rescan()
        assertEquals(RejectReason.PERMISSION_DENIED, scan.rejected.single().reason)
        d.setReadable(true, false)
    }

    @Test fun pushedZipIsExtractedOnce() {
        val z = zip(listOf("1.mid" to TestMidi.scale(null, 60), "2.mid" to TestMidi.scale(null, 61)))
        z.copyTo(File(scores, "Suite.zip"))
        assertEquals(2, store.rescan().added)
        assertEquals(0, store.rescan().added)
        assertEquals(setOf("Suite.zip!1.mid", "Suite.zip!2.mid"), store.index().items.map { it.source }.toSet())
    }

    @Test fun uploadDuringRescanLeavesConsistentIndex() {
        for (i in 0 until 60) push("bulk/$i.mid", TestMidi.scale(null, 40 + i % 30, salt = i + 1))
        val start = CountDownLatch(1)
        val err = AtomicReference<Throwable?>()
        val uploads = (0 until 20).map { TestMidi.scale("up$it", 30 + it, salt = 101 + it) }
        val t1 = Thread { try { start.await(); store.rescan() } catch (e: Throwable) { err.set(e) } }
        val t2 = Thread { try { start.await(); uploads.forEachIndexed { i, b -> store.save(tmp(b), "up$i.mid") } } catch (e: Throwable) { err.set(e) } }
        t1.start(); t2.start(); start.countDown(); t1.join(); t2.join()
        err.get()?.let { throw it }
        val ix = freshIndex()
        assertEquals(80, ix.items.size)
        assertEquals(80, ix.items.map { it.sha1Hex }.toSet().size)
        for (it in ix.items) assertEquals(it.sha1Hex, Sha1.hex(File(imports, it.file)))
        assertFalse(File(imports, "index.json.tmp").exists())
        assertNotNull(ix)
    }

    private fun empty() = LibraryModel(emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyList())
}
