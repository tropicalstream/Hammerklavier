package com.tropicalstream.hammerklavier.companion

import com.tropicalstream.hammerklavier.contract.CompanionCommands
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.ImportResult
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.LibraryModel
import com.tropicalstream.hammerklavier.contract.LibraryService
import com.tropicalstream.hammerklavier.contract.RejectReason
import com.tropicalstream.hammerklavier.contract.ViewId
import com.tropicalstream.hammerklavier.library.CatalogCodec
import com.tropicalstream.hammerklavier.library.ImportRules
import com.tropicalstream.hammerklavier.library.ImportedFacts
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * The phone companion (PLAN §1.6): NanoHTTPD on [port], plain HTTP. `/` and `/index.html` serve
 * [page] unchanged (no token inside, `Cache-Control: no-store`); every `/api/…` route needs the
 * token (`x-hk-token` header or `?token=`). Ten wrong tokens from one address within 60 s → 429 for
 * 60 s. Uploads are raw bodies: `content-length` is checked before any byte is read, then exactly
 * that many bytes go to `<tmpDir>/upload-<n>.tmp` (NanoHTTPD's parseBody is never used). Every
 * command is posted to main through [post]; a handler exception answers JSON 500.
 *
 * [tmpDir] defaults to `java.io.tmpdir`, which ART points at the app's cacheDir; [nowMs] is the
 * flood-window clock (tests).
 */
class CompanionServer(
    private val port: Int,
    private val token: () -> String,
    private val library: LibraryService,
    private val model: () -> LibraryModel?,
    private val commands: CompanionCommands,
    private val page: () -> String,
    private val post: (Runnable) -> Unit,
    private val tmpDir: File = File(System.getProperty("java.io.tmpdir") ?: "."),
    private val nowMs: () -> Long = System::currentTimeMillis,
) : NanoHTTPD(port) {

    private val uploads = AtomicInteger()
    private val failures = HashMap<String, ArrayDeque<Long>>()
    private val blockedUntil = HashMap<String, Long>()

    /** `http://<site-local IPv4>:<port>`, or null (no Wi-Fi: use push_scores.sh). */
    fun url(): String? = NetInfo.siteLocalIpv4()?.let { "http://$it:$port" }

    override fun serve(session: IHTTPSession): Response {
        val r = try { route(session) } catch (e: Throwable) {
            json(ST_500, JSONObject().put("error", "internal").put("detail", e.toString()).toString())
        }
        r.addHeader("Cache-Control", "no-store")
        return r
    }

    private fun route(s: IHTTPSession): Response {
        val uri = s.uri ?: "/"
        if (uri == "/" || uri == "/index.html") {
            if (s.method != Method.GET && s.method != Method.HEAD) return error(ST_405, "method")
            val body = page().toByteArray(Charsets.UTF_8)
            if (s.method == Method.HEAD) {
                return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", ByteArrayInputStream(ByteArray(0)), 0L)
                    .also { it.addHeader("Content-Length", body.size.toString()) }
            }
            return bytes(Response.Status.OK, "text/html; charset=utf-8", body)
        }
        if (!uri.startsWith("/api/")) return error(Response.Status.NOT_FOUND, "not found")
        authorise(s)?.let { return it }
        if (uri != "/api/upload") drainSmallBody(s)?.let { return it }
        val p = params(s)
        return when (uri) {
            "/api/library" -> get(s) { json(Response.Status.OK, CatalogCodec.encodeLibrary(model() ?: library.load())) }
            "/api/state" -> get(s) { json(Response.Status.OK, commands.nowPlayingJson()) }
            "/api/upload" -> postOnly(s) { upload(s) }
            "/api/delete" -> postOnly(s) {
                val id = p["id"] ?: return@postOnly error(ST_400, "id")
                if (!library.delete(id)) error(Response.Status.NOT_FOUND, "no imported movement $id")
                else { post(Runnable { commands.importsChanged() }); ok() }
            }
            "/api/play" -> postOnly(s) {
                val id = p["id"]?.takeIf { it.isNotEmpty() } ?: return@postOnly error(ST_400, "id")
                val inst = p["instrument"]?.takeIf { it.isNotEmpty() }?.let { InstrumentId.of(it) ?: return@postOnly error(ST_400, "instrument") }
                post(Runnable { commands.play(id, inst) }); ok()
            }
            "/api/transport" -> postOnly(s) {
                when (p["cmd"]) {
                    "toggle" -> post(Runnable { commands.toggle() })
                    "next" -> post(Runnable { commands.next() })
                    "prev" -> post(Runnable { commands.previous() })
                    "seek" -> {
                        val ms = p["ms"]?.toLongOrNull()?.takeIf { it >= 0 } ?: return@postOnly error(ST_400, "ms")
                        val us = ms * 1000L + HK.PRE_ROLL_US                      // display ms → song µs incl. pre-roll
                        post(Runnable { commands.seek(us) })
                    }
                    else -> return@postOnly error(ST_400, "cmd")
                }
                ok()
            }
            "/api/instrument" -> postOnly(s) {
                val id = InstrumentId.of(p["id"] ?: "") ?: return@postOnly error(ST_400, "id")
                post(Runnable { commands.instrument(id) }); ok()
            }
            "/api/view" -> postOnly(s) {
                val v = ViewId.entries.firstOrNull { it.name.equals(p["id"], ignoreCase = true) } ?: return@postOnly error(ST_400, "id")
                val framing = (p["framing"] ?: "0").toIntOrNull()?.takeIf { it == 0 || it == 1 } ?: return@postOnly error(ST_400, "framing")
                post(Runnable { commands.view(v, framing) }); ok()
            }
            else -> error(Response.Status.NOT_FOUND, "not found")
        }
    }

    /** Query parameters, first value of each (decoded as UTF-8 by NanoHTTPD). */
    private fun params(s: IHTTPSession): Map<String, String> {
        val out = HashMap<String, String>()
        s.parameters?.forEach { (k, v) -> if (k != null && !v.isNullOrEmpty()) v[0]?.let { out[k] = it } }
        return out
    }

    // ─────────────────────────────── token ───────────────────────────────

    private fun authorise(s: IHTTPSession): Response? {
        val addr = s.remoteIpAddress ?: "?"
        val now = nowMs()
        synchronized(failures) {
            val until = blockedUntil[addr]
            if (until != null) { if (now < until) return error(ST_429, "too many wrong tokens"); blockedUntil.remove(addr) }
        }
        val given = s.headers?.get("x-hk-token")?.trim()?.takeIf { it.isNotEmpty() } ?: params(s)["token"]?.trim()
        if (given.isNullOrEmpty()) return error(ST_403, "token required")
        if (constantTimeEquals(given, token())) return null
        synchronized(failures) {
            val q = failures.getOrPut(addr) { ArrayDeque() }
            q.addLast(now)
            while (q.isNotEmpty() && now - q.first() >= FLOOD_WINDOW_MS) q.removeFirst()
            if (q.size >= FLOOD_LIMIT) { q.clear(); blockedUntil[addr] = now + FLOOD_BLOCK_MS; return error(ST_429, "too many wrong tokens") }
        }
        return error(ST_403, "wrong token")
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var d = 0
        for (i in a.indices) d = d or (a[i].code xor b[i].code)
        return d == 0
    }

    // ─────────────────────────────── upload ───────────────────────────────

    private fun upload(s: IHTTPSession): Response {
        val h = s.headers ?: emptyMap()
        val len = h["content-length"]?.trim()?.toLongOrNull() ?: return closing(error(ST_411, "content-length required"))
        val rawName = h["x-hk-name"] ?: return closing(error(ST_400, "x-hk-name required"))
        val name = try { URLDecoder.decode(rawName.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { return closing(error(ST_400, "x-hk-name")) }
        val zip = ImportRules.isZipName(name)
        val limit = if (zip) ImportRules.MAX_ZIP_BYTES.toLong() else ImportRules.MAX_UPLOAD_MIDI_BYTES.toLong()
        if (len > limit || len < 0) return closing(error(ST_413, "$len bytes; the limit is $limit"))   // before any body byte
        val n = uploads.incrementAndGet()
        tmpDir.mkdirs()
        val tmp = File(tmpDir, "upload-$n.tmp")
        var dir: File? = null
        try {
            if (!readExactly(s.inputStream, len, tmp)) return closing(error(ST_400, "body shorter than content-length"))
            val results: List<ImportResult> = if (zip) {
                val d = File(tmpDir, "upload-$n").also { it.mkdirs() }; dir = d
                val named = File(d, ImportRules.fileNameOf(name))
                if (!tmp.renameTo(named)) tmp.copyTo(named, overwrite = true)
                library.importZip(named)
            } else listOf(library.importFile(tmp, name))
            val saved = JSONArray(); val rejected = JSONArray()
            val saves = results.filter { it.ok && it.movementId != null }
            val facts = library as? ImportedFacts
            for (r in results) {
                val mid = r.movementId
                if (r.ok && mid != null) {
                    saved.put(JSONObject().put("id", mid).put("title", facts?.importedTitle(mid) ?: r.name)
                        .put("durationSec", Math.round((facts?.importedDurationSec(mid) ?: 0f) * 10.0) / 10.0)
                        .put("notes", facts?.importedNotes(mid) ?: 0))
                } else rejected.put(JSONObject().put("name", r.name).put("reason", r.reason?.name ?: "IO_ERROR")
                    .put("reasonText", reasonText(r.reason ?: RejectReason.IO_ERROR))
                    .put("detail", r.detail ?: ""))
            }
            if (saves.isNotEmpty()) post(Runnable { commands.importsChanged() })
            return json(Response.Status.OK, JSONObject().put("saved", saved).put("rejected", rejected).toString())
        } finally {
            tmp.delete()
            dir?.let { d -> d.listFiles()?.forEach { it.delete() }; d.delete() }
        }
    }

    private fun readExactly(input: InputStream, len: Long, to: File): Boolean {
        val buf = ByteArray(64 * 1024)
        var left = len
        FileOutputStream(to).use { out ->
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) return false
                out.write(buf, 0, n)
                left -= n
            }
        }
        return true
    }

    /** Non-upload routes carry their data in the query; a small body is drained so keep-alive stays in step. */
    private fun drainSmallBody(s: IHTTPSession): Response? {
        val len = s.headers?.get("content-length")?.trim()?.toLongOrNull() ?: return null
        if (len <= 0) return null
        if (len > MAX_SMALL_BODY) return closing(error(ST_413, "body too large"))
        val buf = ByteArray(len.toInt())
        var off = 0
        while (off < buf.size) { val n = s.inputStream.read(buf, off, buf.size - off); if (n < 0) break; off += n }
        return null
    }

    // ─────────────────────────────── responses ───────────────────────────────

    private inline fun get(s: IHTTPSession, f: () -> Response): Response = if (s.method == Method.GET) f() else error(ST_405, "use GET")
    private inline fun postOnly(s: IHTTPSession, f: () -> Response): Response = if (s.method == Method.POST) f() else error(ST_405, "use POST")

    private fun ok() = json(Response.Status.OK, """{"ok":true}""")

    private fun error(st: Response.IStatus, msg: String) =
        json(st, JSONObject().put("error", st.requestStatus).put("detail", msg).toString())

    private fun json(st: Response.IStatus, body: String) = bytes(st, "application/json; charset=utf-8", body.toByteArray(Charsets.UTF_8))

    /** Human-readable rejection text for the page (§1.6); the enum code is sent alongside. */
    private fun reasonText(r: RejectReason): String = when (r) {
        RejectReason.NOT_MIDI -> "Not a MIDI file"
        RejectReason.TRUNCATED -> "File is truncated"
        RejectReason.BAD_HEADER -> "Damaged MIDI header"
        RejectReason.TOO_LARGE -> "File is too large"
        RejectReason.TOO_MANY_EVENTS -> "Too many events"
        RejectReason.NO_KEYBOARD_NOTES -> "No keyboard notes"
        RejectReason.DUPLICATE -> "Already imported"
        RejectReason.ZIP_LIMIT -> "Zip exceeds the import limits"
        RejectReason.ZIP_TRAVERSAL -> "Unsafe path inside zip"
        RejectReason.PERMISSION_DENIED -> "Cannot read file"
        RejectReason.IO_ERROR -> "Read or write error"
    }

    private fun bytes(st: Response.IStatus, mime: String, b: ByteArray): Response =
        newFixedLengthResponse(st, mime, ByteArrayInputStream(b), b.size.toLong())

    private fun closing(r: Response): Response { r.closeConnection(true); return r }

    private class St(private val code: Int, private val text: String) : Response.IStatus {
        override fun getDescription() = "$code $text"
        override fun getRequestStatus() = code
    }

    companion object {
        const val PORT = 19112
        const val TOKEN_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
        const val TOKEN_LENGTH = 8
        const val FLOOD_LIMIT = 10
        const val FLOOD_WINDOW_MS = 60_000L
        const val FLOOD_BLOCK_MS = 60_000L
        const val MAX_SMALL_BODY = 64L * 1024

        private val ST_400: Response.IStatus = St(400, "Bad Request")
        private val ST_403: Response.IStatus = St(403, "Forbidden")
        private val ST_405: Response.IStatus = St(405, "Method Not Allowed")
        private val ST_411: Response.IStatus = St(411, "Length Required")
        private val ST_413: Response.IStatus = St(413, "Payload Too Large")
        private val ST_429: Response.IStatus = St(429, "Too Many Requests")
        private val ST_500: Response.IStatus = St(500, "Internal Server Error")

        private val random = SecureRandom()

        /** A fresh 8-character token from the §1.6 alphabet (no 0/1/I/L/O). */
        fun newToken(): String = String(CharArray(TOKEN_LENGTH) { TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)] })

        fun isToken(s: String): Boolean = s.length == TOKEN_LENGTH && s.all { it in TOKEN_ALPHABET }
    }
}
