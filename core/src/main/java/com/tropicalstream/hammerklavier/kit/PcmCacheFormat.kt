package com.tropicalstream.hammerklavier.kit

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32

/**
 * The PCM cache on disk (PLAN §3.2, §3.3):
 *
 * - `<id>-<sha8>.pcm`: a header padded to a 4 KiB multiple (magic `HKPCM2`, schema, the SHA-1 of
 *   `map.json`, region count, per region its byte offset and frame count), then every region as
 *   16-bit LE interleaved stereo starting on a 4 KiB boundary. Pre-sized with `setLength`, so the
 *   mapping never changes.
 * - `<id>-<sha8>.ready`: 8-byte LE ready mask + 64 LE CRC32s (one per unit) + the 8-byte LE
 *   `Settings.Global.BOOT_COUNT` at the last write (272 bytes), replaced atomically (temp file,
 *   `force(true)`, rename).
 * - `<id>-<sha8>.ok`: written last, when every unit is ready.
 *
 * Header layout (little-endian): 0 magic "HKPCM2" + 2 zero bytes · 8 int schema · 12 20-byte SHA-1 ·
 * 32 int regionCount · 36 per region (long byteOffset, int frames) · zero padding.
 */
object PcmCacheFormat {
    const val MAGIC = "HKPCM2"
    const val SCHEMA = 1
    const val ALIGN = 4096
    const val BYTES_PER_FRAME = 4
    const val UNITS = 64
    const val READY_BYTES = 8 + UNITS * 4 + 8
    private const val FIXED = 36
    private const val PER_REGION = 12

    fun pcmName(id: String, sha8: String) = "$id-$sha8.pcm"
    fun readyName(id: String, sha8: String) = "$id-$sha8.ready"
    fun okName(id: String, sha8: String) = "$id-$sha8.ok"

    class Layout(val sha1: String, val frames: IntArray, val offsets: LongArray, val headerBytes: Int, val totalBytes: Long) {
        val regionCount: Int get() = frames.size
        fun byteLength(region: Int): Long = frames[region].toLong() * BYTES_PER_FRAME
    }

    fun align(x: Long): Long = (x + ALIGN - 1) / ALIGN * ALIGN

    fun layout(sha1: String, frames: IntArray): Layout {
        require(sha1.length == 40) { "sha1 must be 40 hex digits" }
        val header = align((FIXED + PER_REGION * frames.size).toLong())
        val offsets = LongArray(frames.size)
        var pos = header
        for (i in frames.indices) {
            require(frames[i] > 0) { "region $i: frames must be positive" }
            offsets[i] = pos
            pos = align(pos + frames[i].toLong() * BYTES_PER_FRAME)
        }
        return Layout(sha1, frames.copyOf(), offsets, header.toInt(), pos)
    }

    fun layout(index: KitIndex): Layout = layout(index.sha1, IntArray(index.regions.size) { index.regions[it].frames })

    fun encodeHeader(l: Layout): ByteBuffer {
        val b = ByteBuffer.allocate(l.headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        b.put(MAGIC.toByteArray(Charsets.US_ASCII)); b.put(0); b.put(0)
        b.putInt(SCHEMA)
        b.put(hexToBytes(l.sha1))
        b.putInt(l.regionCount)
        for (i in 0 until l.regionCount) { b.putLong(l.offsets[i]); b.putInt(l.frames[i]) }
        b.position(0)
        return b
    }

    sealed class Header {
        class Ok(val layout: Layout) : Header()
        class Bad(val reason: String) : Header()
    }

    /** Parses a header read from the file start; [expectedSha1] null = accept any. */
    fun decodeHeader(src: ByteBuffer, fileLength: Long, expectedSha1: String?): Header {
        val b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (b.remaining() < FIXED) return Header.Bad("header truncated")
        val m = ByteArray(6); b.get(m)
        if (String(m, Charsets.US_ASCII) != MAGIC) return Header.Bad("bad magic")
        b.get(); b.get()
        val schema = b.int
        if (schema != SCHEMA) return Header.Bad("schema $schema")
        val sh = ByteArray(20); b.get(sh)
        val sha1 = bytesToHex(sh)
        if (expectedSha1 != null && sha1 != expectedSha1.lowercase()) return Header.Bad("sha1 mismatch")
        val n = b.int
        if (n <= 0 || n > 100_000 || b.remaining() < n * PER_REGION) return Header.Bad("region count $n")
        val frames = IntArray(n); val offs = LongArray(n)
        for (i in 0 until n) { offs[i] = b.long; frames[i] = b.int }
        val expect = try { layout(sha1, frames) } catch (e: IllegalArgumentException) { return Header.Bad(e.message ?: "bad frames") }
        for (i in 0 until n) if (expect.offsets[i] != offs[i]) return Header.Bad("region $i offset")
        if (fileLength < expect.totalBytes) return Header.Bad("file shorter than its layout")
        return Header.Ok(expect)
    }

    /** Reads and checks the header of an open cache file. */
    fun readHeader(ch: FileChannel, expectedSha1: String?): Header {
        val first = ByteBuffer.allocate(FIXED).order(ByteOrder.LITTLE_ENDIAN)
        readFully(ch, first, 0)
        if (first.position() < FIXED) return Header.Bad("header truncated")
        val n = first.getInt(32)
        if (n <= 0 || n > 100_000) return Header.Bad("region count $n")
        val all = ByteBuffer.allocate(FIXED + n * PER_REGION)
        readFully(ch, all, 0)
        all.flip()
        return decodeHeader(all, ch.size(), expectedSha1)
    }

    /** Creates (or re-creates) the pre-sized cache file with its header. */
    fun create(file: File, l: Layout) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            raf.setLength(l.totalBytes)
            val ch = raf.channel
            val h = encodeHeader(l)
            var pos = 0L
            while (h.hasRemaining()) pos += ch.write(h, pos)
            ch.force(true)
        }
    }

    // ---- ready state ----

    class ReadyState(val mask: Long, val crc: IntArray /*64*/, val bootCount: Long) {
        init { require(crc.size == UNITS) }
        fun has(unit: Int): Boolean = (mask ushr unit) and 1L == 1L
        fun with(unit: Int, crc32: Int, bootCount: Long): ReadyState {
            val c = crc.copyOf(); c[unit] = crc32
            return ReadyState(mask or (1L shl unit), c, bootCount)
        }
        fun without(unit: Int): ReadyState = ReadyState(mask and (1L shl unit).inv(), crc.copyOf().also { it[unit] = 0 }, bootCount)
        companion object { val EMPTY = ReadyState(0L, IntArray(UNITS), -1L) }
    }

    fun encodeReady(s: ReadyState): ByteArray {
        val b = ByteBuffer.allocate(READY_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        b.putLong(s.mask)
        for (c in s.crc) b.putInt(c)
        b.putLong(s.bootCount)
        return b.array()
    }

    /** null = missing, short or malformed (treated as nothing ready). */
    fun decodeReady(bytes: ByteArray?): ReadyState? {
        if (bytes == null || bytes.size != READY_BYTES) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val mask = b.long
        val crc = IntArray(UNITS) { b.int }
        return ReadyState(mask, crc, b.long)
    }

    fun readReady(file: File): ReadyState? = if (file.isFile) decodeReady(runCatching { file.readBytes() }.getOrNull()) else null

    /** Temp file, `force(true)`, atomic rename. */
    fun writeReady(file: File, s: ReadyState) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        RandomAccessFile(tmp, "rw").use { raf ->
            raf.setLength(0)
            raf.write(encodeReady(s))
            raf.channel.force(true)
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            if (!tmp.renameTo(file)) throw IOException("rename ${tmp.name} failed", e)
        }
    }

    // ---- CRCs ----

    /** CRC32 over the bytes of [regions] (in the given order) with positional reads. */
    fun unitCrc(ch: FileChannel, l: Layout, regions: IntArray, scratch: ByteBuffer = ByteBuffer.allocateDirect(64 * 1024)): Int {
        val crc = CRC32()
        for (r in regions) {
            var pos = l.offsets[r]
            var left = l.byteLength(r)
            while (left > 0) {
                scratch.clear()
                if (left < scratch.capacity()) scratch.limit(left.toInt())
                val n = ch.read(scratch, pos)
                if (n <= 0) throw IOException("short read at $pos")
                scratch.flip()
                crc.update(scratch)
                pos += n; left -= n
            }
        }
        return crc.value.toInt()
    }

    /** Region ids of [unit] in id order. */
    fun regionsOf(index: KitIndex, unit: Int): IntArray = index.regions.filter { it.unit == unit }.map { it.id }.toIntArray()

    /** true when the stored CRC of a ready [unit] matches the file. */
    fun verifyUnit(ch: FileChannel, l: Layout, index: KitIndex, s: ReadyState, unit: Int): Boolean =
        s.has(unit) && runCatching { unitCrc(ch, l, regionsOf(index, unit)) == s.crc[unit] }.getOrDefault(false)

    private fun readFully(ch: FileChannel, b: ByteBuffer, at: Long) {
        var pos = at
        while (b.hasRemaining()) { val n = ch.read(b, pos); if (n <= 0) break; pos += n }
    }

    fun hexToBytes(h: String): ByteArray = ByteArray(h.length / 2) { ((Character.digit(h[2 * it], 16) shl 4) or Character.digit(h[2 * it + 1], 16)).toByte() }
    fun bytesToHex(b: ByteArray): String = buildString { for (x in b) append(String.format("%02x", x.toInt() and 0xFF)) }
}
