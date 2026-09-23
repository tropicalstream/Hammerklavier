package com.tropicalstream.hammerklavier.companion

import com.tropicalstream.hammerklavier.contract.CompanionCommands
import com.tropicalstream.hammerklavier.contract.CompileOptions
import com.tropicalstream.hammerklavier.contract.CompileResult
import com.tropicalstream.hammerklavier.contract.ImportResult
import com.tropicalstream.hammerklavier.contract.ImportScan
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.LibraryModel
import com.tropicalstream.hammerklavier.contract.LibraryService
import com.tropicalstream.hammerklavier.contract.Movement
import com.tropicalstream.hammerklavier.contract.PedalMode
import com.tropicalstream.hammerklavier.contract.Performance
import com.tropicalstream.hammerklavier.contract.RejectReason
import com.tropicalstream.hammerklavier.contract.ScoreCompiler
import com.tropicalstream.hammerklavier.contract.ScoreFacts
import com.tropicalstream.hammerklavier.contract.Shelf
import com.tropicalstream.hammerklavier.contract.SyntheticScore
import com.tropicalstream.hammerklavier.contract.ViewId
import com.tropicalstream.hammerklavier.contract.Work
import com.tropicalstream.hammerklavier.library.ImportStore
import com.tropicalstream.hammerklavier.library.ImportedFacts
import com.tropicalstream.hammerklavier.library.LibraryIndex
import com.tropicalstream.hammerklavier.library.Sha1
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import java.util.Collections

/** T9.4: CompanionServer on 127.0.0.1. */
class CompanionServerTest {
    private val TOKEN = "K7QM4TZP"
    private val PAGE = "<!DOCTYPE html><html><body>Hammerklavier companion</body></html>"
    private lateinit var root: File
    private lateinit var lib: TestLibrary
    private lateinit var server: CompanionServer
    private val posted = Collections.synchronizedList(ArrayList<String>())
    private var now = 0L
    private var explode = false

    /** Accepts anything starting with MThd; noteCount = byte count; title from none. */
    private class FakeCompiler : ScoreCompiler {
        override fun sniff(head: ByteArray) = head.size >= 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "MThd"
        override fun inspect(bytes: ByteArray) = ScoreFacts(sniff(bytes), if (sniff(bytes)) null else RejectReason.NOT_MIDI, null,
            12.5f, 40, 80, bytes.size, false, false, false, PedalMode.NONE, 1, emptyList())
        override fun compile(bytes: ByteArray, id: String, generation: Int, profile: InstrumentProfile, opts: CompileOptions) =
            CompileResult.Failed(RejectReason.NOT_MIDI, "", 0)
        override fun synthetic(kind: SyntheticScore, profile: InstrumentProfile, generation: Int): Performance = throw UnsupportedOperationException()
    }

    private inner class TestLibrary(val store: ImportStore) : LibraryService, ImportedFacts {
        private val bundled = LibraryModel(listOf(Shelf("beethoven", "Beethoven", listOf("beethoven.woo59"))),
            mapOf("beethoven.woo59" to Work("beethoven.woo59", "Ludwig van Beethoven", "Beethoven", "Für Elise, WoO 59", "Für Elise",
                "WoO 59", 1810, "classical", InstrumentId.GRAND, listOf(InstrumentId.UPRIGHT), "krueger", "A", "as-is", null,
                listOf("beethoven.woo59.1"), false)),
            mapOf("beethoven.woo59.1" to Movement("beethoven.woo59.1", "beethoven.woo59", "Für Elise", "midi/elise.mid", null, "x",
                180f, 33, 88, true, false, false, PedalMode.SWITCH)), emptyMap(), listOf("beethoven.woo59.1"))
        override fun load(): LibraryModel { if (explode) error("boom"); return LibraryIndex.merge(bundled, store.index(), store.importsDir) }
        override fun readBytes(m: Movement) = ByteArray(0)
        override fun rescan(): ImportScan = store.rescan()
        override fun importFile(tmp: File, relativeName: String): ImportResult = store.save(tmp, relativeName)
        override fun importZip(tmp: File): List<ImportResult> = store.saveZip(tmp, tmp.name)
        override fun delete(movementId: String) = store.delete(movementId)
        override val scoresDir: File get() = store.scoresDir
        override fun importedNotes(movementId: String) = store.importedNotes(movementId)
    }

    private val commands = object : CompanionCommands {
        override fun play(movementId: String, instrument: InstrumentId?) { posted += "play $movementId ${instrument?.key}" }
        override fun toggle() { posted += "toggle" }
        override fun next() { posted += "next" }
        override fun previous() { posted += "prev" }
        override fun seek(us: Long) { posted += "seek $us" }
        override fun instrument(id: InstrumentId) { posted += "instrument ${id.key}" }
        override fun view(v: ViewId, framing: Int) { posted += "view $v $framing" }
        override fun importsChanged() { posted += "importsChanged" }
        override fun nowPlayingJson() = """{"movementId":"beethoven.woo59.1","title":"Für Elise","composer":"Beethoven","playing":true}"""
    }

    @Before fun setUp() {
        root = Files.createTempDirectory("wp9-companion").toFile()
        lib = TestLibrary(ImportStore(File(root, "imports"), File(root, "Scores"), FakeCompiler()))
        server = CompanionServer(0, { TOKEN }, lib, { null }, commands, { PAGE }, { r -> r.run() },
            tmpDir = File(root, "cache"), nowMs = { now })
        server.start(5000, true)
    }

    @After fun tearDown() { server.stop(); root.deleteRecursively() }

    private val base get() = "http://127.0.0.1:${server.listeningPort}"

    private class Reply(val code: Int, val body: String, val headers: Map<String, List<String>>)

    private fun req(method: String, path: String, token: String? = TOKEN, body: ByteArray? = null,
                    headers: Map<String, String> = emptyMap()): Reply {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        if (token != null) c.setRequestProperty("x-hk-token", token)
        for ((k, v) in headers) c.setRequestProperty(k, v)
        if (body != null) { c.doOutput = true; c.setFixedLengthStreamingMode(body.size); c.outputStream.use { it.write(body) } }
        val code = c.responseCode
        val stream = if (code < 400) c.inputStream else c.errorStream
        val text = stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
        return Reply(code, text, c.headerFields.filterKeys { it != null })
    }

    @Test fun pageHasNoTokenAndIsNotCached() {
        val r = req("GET", "/", token = null)
        assertEquals(200, r.code); assertFalse(r.body.contains(TOKEN)); assertEquals(PAGE, r.body)
        assertEquals("no-store", r.headers["Cache-Control"]?.single())
        assertEquals(200, req("GET", "/index.html", token = null).code)
    }

    @Test fun realPageHasNoTokenOrDollar() {
        val page = File("src/main/assets/companion.html").readText()
        assertFalse(Regex("x-hk-token\"?\\s*[:,]\\s*\"[2-9A-Z]{8}\"").containsMatchIn(page))   // no token baked in
        assertTrue(page.contains("x-hk-token")); assertTrue(page.contains("webkitGetAsEntry"))
    }

    @Test fun everyApiRouteNeedsTheToken() {
        val routes = listOf("GET" to "/api/library", "GET" to "/api/state", "POST" to "/api/delete?id=x", "POST" to "/api/play?id=x",
            "POST" to "/api/transport?cmd=toggle", "POST" to "/api/instrument?id=grand", "POST" to "/api/view?id=hall&framing=1")
        for ((m, p) in routes) assertEquals(p, 403, req(m, p, token = null).code)
        for ((m, p) in routes.filter { !it.second.startsWith("/api/delete") }) assertEquals(p, 200, req(m, p).code)
        assertEquals(200, req("GET", "/api/state?token=$TOKEN", token = null).code)
        assertEquals(403, req("POST", "/api/upload", token = null, body = ByteArray(4), headers = mapOf("x-hk-name" to "a.mid")).code)
        assertTrue(posted.containsAll(listOf("play x null", "toggle", "instrument grand", "view HALL 1")))
    }

    @Test fun tenBadTokensGive429ForAMinute() {
        for (i in 1..9) assertEquals(403, req("GET", "/api/state", token = "WRONG$i").code)
        assertEquals(429, req("GET", "/api/state", token = "WRONG10").code)
        assertEquals(429, req("GET", "/api/state").code)                      // even the right token, for 60 s
        now += 60_000
        assertEquals(200, req("GET", "/api/state").code)
    }

    @Test fun badTokensOutsideTheWindowDoNotBlock() {
        for (i in 1..9) assertEquals(403, req("GET", "/api/state", token = "W").code)
        now += 61_000
        assertEquals(403, req("GET", "/api/state", token = "W").code)
        assertEquals(200, req("GET", "/api/state").code)
    }

    @Test fun libraryIsUtf8Json() {
        val r = req("GET", "/api/library")
        assertEquals(200, r.code)
        assertTrue(r.headers["Content-Type"]!!.single().contains("utf-8"))
        assertTrue(r.body.contains("Für Elise"))
        val up = req("POST", "/api/upload", body = midi(1), headers = mapOf("x-hk-name" to "H%C3%A4ndel%20Suite.mid"))
        assertEquals(200, up.code)
        val lib2 = JSONObject(req("GET", "/api/library").body)
        assertTrue(lib2.toString().contains("Händel Suite"))
    }

    private fun midi(salt: Int) = "MThd".toByteArray() + byteArrayOf(0, 0, 0, 6, 0, 0, 0, 1, 1, 0xE0.toByte()) +
        ByteArray(300) { (it * 7 + salt).toByte() }

    @Test fun rawUploadKeepsUnicodeNameAndBytes() {
        val bytes = midi(3)
        val r = req("POST", "/api/upload", body = bytes, headers = mapOf("x-hk-name" to "H%C3%A4ndel%20Suite.mid",
            "Content-Type" to "application/octet-stream"))
        assertEquals(r.body, 200, r.code)
        val o = JSONObject(r.body)
        val saved = o.getJSONArray("saved").getJSONObject(0)
        assertEquals("Händel Suite", saved.getString("title")); assertEquals(bytes.size, saved.getInt("notes"))
        assertEquals(12.5, saved.getDouble("durationSec"), 1e-9)
        val item = lib.store.index().items.single()
        assertEquals("Händel Suite.mid", item.originalName)
        assertArrayEquals(bytes, File(lib.store.importsDir, "${Sha1.hex(bytes)}.mid").readBytes())
        assertTrue(posted.contains("importsChanged"))
        assertTrue(File(root, "cache").listFiles()!!.isEmpty())             // the temp file is gone
        val dup = JSONObject(req("POST", "/api/upload", body = bytes, headers = mapOf("x-hk-name" to "again.mid")).body)
        assertEquals("DUPLICATE", dup.getJSONArray("rejected").getJSONObject(0).getString("reason"))
        val bad = JSONObject(req("POST", "/api/upload", body = "hello".toByteArray(), headers = mapOf("x-hk-name" to "x.mid")).body)
        assertEquals("NOT_MIDI", bad.getJSONArray("rejected").getJSONObject(0).getString("reason"))
        // delete the import through the API
        assertEquals(200, req("POST", "/api/delete?id=${item.movementId}").code)
        assertTrue(lib.store.index().items.isEmpty())
        assertEquals(404, req("POST", "/api/delete?id=${item.movementId}").code)
    }

    @Test fun oversizeIs413BeforeAnyBodyIsRead() {
        Socket("127.0.0.1", server.listeningPort).use { s ->
            s.soTimeout = 5000
            val head = "POST /api/upload HTTP/1.1\r\nHost: x\r\nx-hk-token: $TOKEN\r\nx-hk-name: big.mid\r\n" +
                "content-length: ${5 * 1024 * 1024}\r\n\r\n"
            s.getOutputStream().write(head.toByteArray()); s.getOutputStream().flush()
            // no body byte is sent: the answer must come anyway
            val line = s.getInputStream().bufferedReader().readLine()
            assertTrue(line, line.startsWith("HTTP/1.1 413"))
        }
        assertTrue(lib.store.index().items.isEmpty())
    }

    @Test fun zipOver20MiBIs413() {
        Socket("127.0.0.1", server.listeningPort).use { s ->
            s.soTimeout = 5000
            s.getOutputStream().write(("POST /api/upload HTTP/1.1\r\nHost: x\r\nx-hk-token: $TOKEN\r\nx-hk-name: a.zip\r\n" +
                "content-length: ${20 * 1024 * 1024 + 1}\r\n\r\n").toByteArray())
            assertTrue(s.getInputStream().bufferedReader().readLine().startsWith("HTTP/1.1 413"))
        }
    }

    @Test fun unknownPathIs404() {
        assertEquals(404, req("GET", "/nope", token = null).code)
        assertEquals(404, req("GET", "/api/nope").code)
    }

    @Test fun handlerExceptionIsJson500() {
        explode = true
        val r = req("GET", "/api/library")
        assertEquals(500, r.code); assertEquals("internal", JSONObject(r.body).getString("error"))
    }

    @Test fun seekConvertsDisplayMsToSongUs() {
        assertEquals(200, req("POST", "/api/transport?cmd=seek&ms=1500").code)
        assertTrue(posted.contains("seek 1900000"))
        assertEquals(400, req("POST", "/api/transport?cmd=warp").code)
        assertEquals(400, req("POST", "/api/instrument?id=fortepiano").code)
        assertEquals(400, req("POST", "/api/view?id=hall&framing=2").code)
    }

    @Test fun stateIsTheCommandsSnapshot() {
        val r = req("GET", "/api/state")
        assertEquals("Für Elise", JSONObject(r.body).getString("title"))
    }

    @Test fun tokens() {
        repeat(50) { assertTrue(CompanionServer.isToken(CompanionServer.newToken())) }
        assertFalse(CompanionServer.isToken("K7QM4TZ0"))
    }
}
