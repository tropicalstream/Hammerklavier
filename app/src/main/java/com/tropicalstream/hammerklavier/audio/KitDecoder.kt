package com.tropicalstream.hammerklavier.audio

import android.content.res.AssetFileDescriptor
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.tropicalstream.hammerklavier.kit.PcmCacheFormat
import com.tropicalstream.hammerklavier.kit.RegionDef
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** The result of decoding one unit stream (PLAN §3.19 `DecodeResult`). */
sealed class DecodeResult {
    class Done(val crc: Int, val decodedFrames: Long, val wallMs: Long) : DecodeResult()
    /** Playback started: stopped within one output buffer, what was written is forced; redo later. */
    object Yielded : DecodeResult()
    class Failed(val reason: String) : DecodeResult()
}

/**
 * Opus unit streams → PCM cache (PLAN §3.3): one `MediaExtractor` per stream opened on the
 * uncompressed asset's fd range, and **one codec reused across streams** with
 * `stop()`/`configure()`/`start()` (`c2.android.opus.decoder` by name, else by type). The
 * DecoderProbe's [offsetFrames] are removed from the start of every stream (zeros prepended if
 * negative); each region is cut at its `streamStart` for exactly `frames`. Output goes through a
 * 64 KiB direct buffer with positional `FileChannel.write`; after every ≤ 4 MiB, `force(false)`
 * and a sleep of ≥ 2× the write time, so dirty pages never pile up on the eMMC. HKVoicer only.
 */
class KitDecoder {
    private var codec: MediaCodec? = null
    private val info = MediaCodec.BufferInfo()
    private val out = ByteBuffer.allocateDirect(CHUNK).order(ByteOrder.LITTLE_ENDIAN)
    var codecName: String = ""; private set

    private fun codec(): MediaCodec {
        codec?.let { return it }
        val c = try { MediaCodec.createByCodecName(PREFERRED) } catch (e: Exception) { MediaCodec.createDecoderByType(MIME) }
        codecName = runCatching { c.name }.getOrDefault("?")
        codec = c
        return c
    }

    fun release() {
        codec?.let { runCatching { it.release() } }
        codec = null
    }

    /**
     * Decodes one unit stream into the cache. [regions] are the unit's regions (any order).
     * [shouldYield] is polled between output buffers (≤ 60 ms of audio each).
     */
    fun decodeUnit(afd: AssetFileDescriptor, regions: List<RegionDef>, layout: PcmCacheFormat.Layout, ch: FileChannel,
                   offsetFrames: Int, shouldYield: () -> Boolean): DecodeResult {
        val t0 = System.nanoTime()
        val sorted = regions.sortedBy { it.streamStart }
        val sink = RegionWriter(sorted, layout, ch)
        if (offsetFrames < 0) sink.zeros(0L, (-offsetFrames).toLong())
        var decoded = 0L
        val r = decodeStream(afd, shouldYield) { buf, frames, channels ->
            // Stream frame of this buffer's first frame after the probe offset.
            val streamPos = decoded - offsetFrames
            sink.write(buf, streamPos, frames, channels)
            decoded += frames
        }
        return when (r) {
            null -> {
                try { sink.flush(); ch.force(false) } catch (e: IOException) { return DecodeResult.Failed("write: ${e.message}") }
                val crc = PcmCacheFormat.unitCrc(ch, layout, sorted.map { it.id }.sortedBy { it }.toIntArray())
                DecodeResult.Done(crc, decoded, (System.nanoTime() - t0) / 1_000_000)
            }
            YIELD -> { runCatching { sink.flush(); ch.force(false) }; DecodeResult.Yielded }
            else -> DecodeResult.Failed(r)
        }
    }

    /**
     * Drives extractor + codec over one stream, handing every output buffer (16-bit PCM) to
     * [onPcm]. Returns null when done, [YIELD] when [shouldYield] fired, else a failure reason.
     */
    fun decodeStream(afd: AssetFileDescriptor, shouldYield: () -> Boolean, onPcm: (ByteBuffer, Int, Int) -> Unit): String? {
        val ex = MediaExtractor()
        var started = false
        val c: MediaCodec
        try {
            ex.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            var track = -1
            for (i in 0 until ex.trackCount) {
                if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; break }
            }
            if (track < 0) { ex.release(); return "no audio track" }
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            c = codec()
            c.configure(fmt, null, null, 0)
            c.start(); started = true
            var channels = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val ii = c.dequeueInputBuffer(TIMEOUT_US)
                    if (ii >= 0) {
                        val ib = c.getInputBuffer(ii)!!
                        val n = ex.readSampleData(ib, 0)
                        if (n < 0) { c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { c.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, TIMEOUT_US)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) != android.media.AudioFormat.ENCODING_PCM_16BIT)
                        return "decoder output is not 16-bit PCM"
                } else if (oi >= 0) {
                    val ob = c.getOutputBuffer(oi)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        onPcm(ob.slice().order(ByteOrder.LITTLE_ENDIAN), info.size / (2 * channels), channels)
                    }
                    c.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    if (shouldYield()) return YIELD
                }
            }
            return null
        } catch (e: Exception) {
            release()                                     // a broken codec is not reused
            started = false
            return "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            if (started) runCatching { codec?.stop() }
            runCatching { ex.release() }
        }
    }

    /** Writes stream frames into the regions that contain them, through one 64 KiB buffer. */
    private inner class RegionWriter(private val regions: List<RegionDef>, private val layout: PcmCacheFormat.Layout,
                                     private val ch: FileChannel) {
        private var first = 0
        private var pendingPos = -1L
        private var sinceForce = 0L
        private var writeNs = 0L

        /** [frames] frames of [channels]-channel PCM at stream frame [streamPos]. */
        fun write(src: ByteBuffer, streamPos: Long, frames: Int, channels: Int) {
            var f = 0
            while (f < frames) {
                val sp = streamPos + f
                if (sp < 0) { f = minOf(frames.toLong(), -streamPos).toInt(); continue }
                while (first < regions.size && regions[first].streamStart + regions[first].frames <= sp) first++
                if (first >= regions.size) return
                val r = regions[first]
                if (sp < r.streamStart) { f += minOf((r.streamStart - sp), (frames - f).toLong()).toInt(); continue }
                val inRegion = (sp - r.streamStart).toInt()
                val n = minOf(frames - f, r.frames - inRegion)
                put(src, f, n, channels, layout.offsets[r.id] + inRegion.toLong() * PcmCacheFormat.BYTES_PER_FRAME)
                f += n
            }
        }

        fun zeros(streamPos: Long, frames: Long) {
            val z = ByteBuffer.allocate(4 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            var left = frames; var pos = streamPos
            while (left > 0) {
                val n = minOf(left, 1024L).toInt()
                z.clear(); z.limit(n * 4)
                write(z, pos, n, 2)
                left -= n; pos += n
            }
        }

        private fun put(src: ByteBuffer, frame: Int, n: Int, channels: Int, filePos: Long) {
            var i = 0
            while (i < n) {
                if (out.remaining() < 4 || (pendingPos >= 0 && pendingPos + out.position() != filePos + i.toLong() * 4)) flush()
                if (pendingPos < 0) pendingPos = filePos + i.toLong() * 4
                val fi = frame + i
                val l: Short; val r: Short
                if (channels >= 2) { l = src.getShort(fi * 2 * channels); r = src.getShort(fi * 2 * channels + 2) }
                else { l = src.getShort(fi * 2); r = l }
                out.putShort(l); out.putShort(r)
                i++
            }
        }

        fun flush() {
            if (pendingPos < 0 || out.position() == 0) { out.clear(); pendingPos = -1; return }
            out.flip()
            val t0 = System.nanoTime()
            var p = pendingPos
            while (out.hasRemaining()) p += ch.write(out, p)
            writeNs += System.nanoTime() - t0
            sinceForce += p - pendingPos
            out.clear(); pendingPos = -1
            if (sinceForce >= FORCE_BYTES) {
                val t1 = System.nanoTime()
                ch.force(false)
                val spent = writeNs + (System.nanoTime() - t1)
                sinceForce = 0; writeNs = 0
                val sleepMs = 2 * spent / 1_000_000
                if (sleepMs > 0) runCatching { Thread.sleep(sleepMs.coerceAtMost(2_000)) }
            }
        }
    }

    companion object {
        const val PREFERRED = "c2.android.opus.decoder"
        const val MIME = MediaFormat.MIMETYPE_AUDIO_OPUS
        const val CHUNK = 64 * 1024
        const val FORCE_BYTES = 4L * 1024 * 1024
        const val TIMEOUT_US = 10_000L
        const val YIELD = "yield"
    }
}
