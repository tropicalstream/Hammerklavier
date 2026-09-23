package com.tropicalstream.hammerklavier.kit

import com.tropicalstream.hammerklavier.contract.InstrumentId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/** T4.3 SampleStore and T4.4 PcmCacheFormat. */
class CacheAndStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val index = ToyKit.fixtureLike().index()

    private fun sampleL(region: Int, frame: Int): Short = ((region * 997 + frame) % 30000 + 1).toShort()

    /** Writes every region with a known pattern and returns the file. */
    private fun writeCache(idx: KitIndex = index): File {
        val f = tmp.newFile("grand-${idx.sha8}.pcm")
        val l = PcmCacheFormat.layout(idx)
        PcmCacheFormat.create(f, l)
        RandomAccessFile(f, "rw").use { raf ->
            val ch = raf.channel
            for (r in 0 until l.regionCount) {
                val b = ByteBuffer.allocate(l.frames[r] * 4).order(ByteOrder.LITTLE_ENDIAN)
                for (t in 0 until l.frames[r]) { val s = sampleL(r, t); b.putShort(s); b.putShort((-s).toShort()) }
                b.flip()
                var pos = l.offsets[r]
                while (b.hasRemaining()) pos += ch.write(b, pos)
            }
        }
        return f
    }

    // ---- T4.4 ----

    @Test fun layoutAlignsRegionsTo4KiB() {
        val l = PcmCacheFormat.layout(index)
        assertEquals(0, l.headerBytes % 4096)
        for (r in 0 until l.regionCount) assertEquals(0L, l.offsets[r] % 4096)
        for (r in 1 until l.regionCount) assertTrue(l.offsets[r] >= l.offsets[r - 1] + l.byteLength(r - 1))
    }

    @Test fun headerRoundTrip() {
        val f = writeCache()
        RandomAccessFile(f, "r").use { raf ->
            val h = PcmCacheFormat.readHeader(raf.channel, index.sha1)
            assertTrue(h is PcmCacheFormat.Header.Ok)
            val l = (h as PcmCacheFormat.Header.Ok).layout
            assertEquals(index.regions.size, l.regionCount)
            assertEquals(index.sha1, l.sha1)
            assertArrayEquals(PcmCacheFormat.layout(index).offsets, l.offsets)
            assertEquals(l.totalBytes, f.length())
        }
    }

    @Test fun headerRejectsMismatchAndDamage() {
        val f = writeCache()
        RandomAccessFile(f, "r").use { raf ->
            val h = PcmCacheFormat.readHeader(raf.channel, "f".repeat(40))
            assertTrue(h is PcmCacheFormat.Header.Bad && h.reason.contains("sha1"))
        }
        RandomAccessFile(f, "rw").use { it.seek(0); it.write('X'.code) }
        RandomAccessFile(f, "r").use { raf -> assertTrue(PcmCacheFormat.readHeader(raf.channel, null) is PcmCacheFormat.Header.Bad) }
    }

    @Test fun readyRoundTripWithCrcsAndBootCount() {
        var s = PcmCacheFormat.ReadyState.EMPTY
        s = s.with(0, 0x12345678, 41).with(62, -5, 42).with(63, 7, 42)
        val bytes = PcmCacheFormat.encodeReady(s)
        assertEquals(PcmCacheFormat.READY_BYTES, bytes.size)
        val d = PcmCacheFormat.decodeReady(bytes)!!
        assertEquals(s.mask, d.mask); assertArrayEquals(s.crc, d.crc); assertEquals(42L, d.bootCount)
        assertTrue(d.has(0) && d.has(62) && d.has(63) && !d.has(1))
        assertNull(PcmCacheFormat.decodeReady(bytes.copyOf(100)))
        val rf = File(tmp.root, "grand-x.ready")
        PcmCacheFormat.writeReady(rf, d)
        val back = PcmCacheFormat.readReady(rf)!!
        assertEquals(d.mask, back.mask); assertArrayEquals(d.crc, back.crc); assertEquals(d.bootCount, back.bootCount)
        assertFalse(File(tmp.root, "grand-x.ready.tmp").exists())
        assertEquals(0L, d.without(62).mask and (1L shl 62))
    }

    @Test fun flippedByteInAReadyUnitIsDetected() {
        val f = writeCache()
        val l = PcmCacheFormat.layout(index)
        var s = PcmCacheFormat.ReadyState.EMPTY
        RandomAccessFile(f, "r").use { raf ->
            for (u in index.units.map { it.id }) s = s.with(u, PcmCacheFormat.unitCrc(raf.channel, l, PcmCacheFormat.regionsOf(index, u)), 1)
            for (u in index.units.map { it.id }) assertTrue(PcmCacheFormat.verifyUnit(raf.channel, l, index, s, u))
        }
        val r = PcmCacheFormat.regionsOf(index, 1)[1]
        RandomAccessFile(f, "rw").use { raf ->
            val at = l.offsets[r] + 1234
            raf.seek(at); val b = raf.read(); raf.seek(at); raf.write(b xor 0x10)
        }
        RandomAccessFile(f, "r").use { raf ->
            assertFalse(PcmCacheFormat.verifyUnit(raf.channel, l, index, s, 1))
            assertTrue(PcmCacheFormat.verifyUnit(raf.channel, l, index, s, 0))
            assertTrue(PcmCacheFormat.verifyUnit(raf.channel, l, index, s, 62))
        }
    }

    // ---- T4.3 ----

    @Test fun readsMatchAndZeroFillAcrossRegionEnds() {
        val store = SampleStore.open(writeCache(), index.sha1)
        val rd = store.newReader()
        val r = 2; val n = store.frames(r)
        val dst = ShortArray(2 * 200) { 99 }
        assertEquals(100, rd.read(r, n - 100, 200, dst, 0))
        for (t in 0 until 100) { assertEquals(sampleL(r, n - 100 + t), dst[2 * t]); assertEquals((-sampleL(r, n - 100 + t)).toShort(), dst[2 * t + 1]) }
        for (i in 200 until 400) assertEquals(0.toShort(), dst[i])
        // Before the start.
        java.util.Arrays.fill(dst, 99)
        assertEquals(150, rd.read(r, -50, 200, dst, 0))
        for (i in 0 until 100) assertEquals(0.toShort(), dst[i])
        assertEquals(sampleL(r, 0), dst[100])
        // Entirely outside, with an offset into dst.
        val d2 = ShortArray(20) { 7 }
        assertEquals(0, rd.read(r, n + 10, 5, d2, 4))
        for (i in 4 until 14) assertEquals(0.toShort(), d2[i]); assertEquals(7.toShort(), d2[3]); assertEquals(7.toShort(), d2[14])
        assertEquals(0, rd.slowReads)
    }

    @Test fun prefetchUsesPositionalReadsNotTheMapping() {
        val store = SampleStore.open(writeCache(), index.sha1)
        val rd = store.newReader()
        val before = store.mappedFramesRead
        rd.prefetch(0, 0, 3000)
        rd.prefetch(1, 4000, 10_000)                   // clipped at the region end
        assertEquals(before, store.mappedFramesRead)
        assertEquals((3000L + (store.frames(1) - 4000)) * 4, store.prefetchBytesRead)
        rd.read(0, 0, 10, ShortArray(20), 0)
        assertEquals(before + 10, store.mappedFramesRead)
    }

    @Test fun shaMismatchRejected() {
        val f = writeCache()
        try { SampleStore.open(f, "a".repeat(40)); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("sha1")) }
    }

    @Test fun readersOnTwoThreadsDoNotInterfere() {
        val store = SampleStore.open(writeCache(), index.sha1)
        val err = AtomicReference<Throwable?>(null)
        val go = CountDownLatch(1)
        val threads = (0 until 2).map { ti ->
            Thread {
                try {
                    val rd = store.newReader()
                    val dst = ShortArray(2 * 64)
                    go.await()
                    for (it in 0 until 20_000) {
                        val r = (it + ti) % store.regionCount
                        val from = (it * 37 + ti * 11) % (store.frames(r) - 64)
                        rd.read(r, from, 64, dst, 0)
                        for (t in 0 until 64) if (dst[2 * t] != sampleL(r, from + t)) throw AssertionError("thread $ti region $r frame ${from + t}")
                        if (it % 100 == 0) rd.prefetch(r, from, 512)
                    }
                } catch (t: Throwable) { err.set(t) }
            }.also { it.start() }
        }
        go.countDown()
        threads.forEach { it.join() }
        err.get()?.let { throw it }
    }

    @Test fun mappedBankOverTheStore() {
        val store = SampleStore.open(writeCache(), index.sha1)
        val bank = MappedBank(index, store, generation = 7, initialReadyMask = 5L)
        assertEquals(7, bank.generation); assertEquals(index.regions.size, bank.regionCount)
        assertEquals(96, bank.onsetFrame(0)); assertEquals(100, bank.thrFrame(0))
        assertEquals(index.envByte(3, 2), bank.envByte(3, 2)); assertEquals(255, bank.envByte(3, 1_000_000))
        assertEquals(InstrumentId.GRAND, bank.info.instrument); assertFalse(bank.info.isStub)
        assertEquals(5L, bank.readyMask)
        val d = ShortArray(4); bank.newReader().read(1, 0, 2, d, 0)
        assertEquals(sampleL(1, 1), d[2])
    }
}
