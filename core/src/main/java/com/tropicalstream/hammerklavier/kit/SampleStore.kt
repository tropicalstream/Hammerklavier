package com.tropicalstream.hammerklavier.kit

import com.tropicalstream.hammerklavier.contract.SampleReader
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * The PCM cache opened for playback (PLAN §3.2, §3.4): one read-only `FileChannel.map` of the
 * whole file for HKAudio reads, and positional `FileChannel.read` for HKPrefetch, which never
 * touches the mapping (the thread stays in Native state during a slow eMMC read). java.nio only.
 *
 * The mapping is never unmapped (Java cannot do it safely); it lives until the store is garbage
 * collected. Regions still being voiced read whatever the file holds; the KeyMap never points at
 * them.
 */
class SampleStore private constructor(val file: File, val layout: PcmCacheFormat.Layout,
                                      private val channel: FileChannel, private val map: MappedByteBuffer) {

    /** Frames served from the mapping by every reader (tests: prefetch leaves it unchanged). */
    val mappedFramesRead: Long get() = mapped.get()
    private val mapped = java.util.concurrent.atomic.AtomicLong()
    /** Bytes read positionally by every reader's prefetch. */
    val prefetchBytesRead: Long get() = prefetched.get()
    private val prefetched = java.util.concurrent.atomic.AtomicLong()

    val regionCount: Int get() = layout.regionCount
    fun frames(region: Int): Int = layout.frames[region]

    /** One per thread. */
    fun newReader(): SampleReader = Reader()

    private inner class Reader : SampleReader {
        private val shorts = (map as ByteBuffer).duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        private val buf: ByteBuffer by lazy { ByteBuffer.allocateDirect(CHUNK) }   // prefetch thread only
        @Volatile private var slow = 0

        override fun read(region: Int, fromFrame: Int, frames: Int, dst: ShortArray, dstOff: Int): Int {
            if (frames <= 0) return 0
            val total = layout.frames[region]
            val a = if (fromFrame < 0) 0 else fromFrame
            val end = fromFrame.toLong() + frames
            val b = if (end > total) total else end.toInt()
            if (b <= a) { java.util.Arrays.fill(dst, dstOff, dstOff + 2 * frames, 0); return 0 }
            if (a > fromFrame) java.util.Arrays.fill(dst, dstOff, dstOff + 2 * (a - fromFrame), 0)
            val t0 = System.nanoTime()
            (shorts as Buffer).position(((layout.offsets[region] shr 1) + 2L * a).toInt())
            shorts.get(dst, dstOff + 2 * (a - fromFrame), 2 * (b - a))
            if (System.nanoTime() - t0 > SLOW_NS) slow++
            if (end > b) java.util.Arrays.fill(dst, dstOff + 2 * (b - fromFrame), dstOff + 2 * frames, 0)
            mapped.addAndGet((b - a).toLong())   // lock-free, allocation-free
            return b - a
        }

        override fun prefetch(region: Int, fromFrame: Int, frames: Int) {
            val total = layout.frames[region]
            val a = fromFrame.coerceIn(0, total)
            val b = (fromFrame.toLong() + frames).coerceIn(a.toLong(), total.toLong()).toInt()
            if (b <= a) return
            var pos = layout.offsets[region] + a.toLong() * PcmCacheFormat.BYTES_PER_FRAME
            val stop = layout.offsets[region] + b.toLong() * PcmCacheFormat.BYTES_PER_FRAME
            var got = 0L
            try {
                while (pos < stop) {
                    (buf as Buffer).clear()
                    val want = stop - pos
                    if (want < CHUNK) (buf as Buffer).limit(want.toInt())
                    val n = channel.read(buf, pos)
                    if (n <= 0) break
                    pos += n; got += n
                }
            } catch (e: IOException) {
                // A failed prefetch only costs a later page fault.
            }
            prefetched.addAndGet(got)
        }

        override val slowReads: Int get() = slow
    }

    companion object {
        const val CHUNK = 64 * 1024
        const val SLOW_NS = 1_000_000L

        /** Opens and checks [file]; throws IOException on a bad header or a SHA mismatch. */
        fun open(file: File, expectedSha1: String?): SampleStore {
            val raf = RandomAccessFile(file, "r")
            val ch = raf.channel
            try {
                when (val h = PcmCacheFormat.readHeader(ch, expectedSha1)) {
                    is PcmCacheFormat.Header.Bad -> throw IOException("${file.name}: ${h.reason}")
                    is PcmCacheFormat.Header.Ok -> {
                        val map = ch.map(FileChannel.MapMode.READ_ONLY, 0, h.layout.totalBytes)
                        return SampleStore(file, h.layout, ch, map)
                    }
                }
            } catch (e: Exception) {
                runCatching { raf.close() }
                throw if (e is IOException) e else IOException(e)
            }
        }
    }
}
