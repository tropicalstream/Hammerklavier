package com.tropicalstream.hammerklavier.contract

import com.tropicalstream.hammerklavier.testutil.AllocProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class RingsTest {
    @Test fun commandRingSpscOrderAndDrops() {
        val ring = CommandRing(256)
        val n = 2_000_000
        val done = AtomicBoolean(false)
        var expected = 0L
        var bad = 0
        val handler = CommandHandler { code, l, f, ref ->
            if (l != expected || code != (l and 0xFFFF).toInt() || f != (l % 1000).toFloat() || (ref != null) != (l % 7 == 0L)) bad++
            expected++
        }
        val consumer = Thread {
            while (!done.get() || expected < n) { if (ring.drain(64, handler) == 0) Thread.onSpinWait() }
        }
        consumer.start()
        var i = 0L
        while (i < n) {
            if (ring.offer((i and 0xFFFF).toInt(), i, (i % 1000).toFloat(), if (i % 7 == 0L) this else null)) i++ else Thread.onSpinWait()
        }
        done.set(true)
        consumer.join(30_000)
        assertEquals(0, bad); assertEquals(n.toLong(), expected)
        val small = CommandRing(4)
        repeat(4) { assertTrue(small.offer(1)) }
        assertFalse(small.offer(1)); assertEquals(1, small.dropped)
    }

    @Test fun commandDrainAllocatesNothing() {
        val ring = CommandRing(256)
        var sum = 0L
        val h = CommandHandler { _, l, _, _ -> sum += l }
        AllocProbe.assertNoAllocation("CommandRing", warmUp = { ring.offer(1, 1L); ring.drain(8, h) }) {
            for (i in 0 until 10_000) { ring.offer(Cmd.SEEK, i.toLong()); ring.drain(8, h) }
        }
        assertTrue(sum > 0)
    }

    @Test fun energyRingSelection() {
        val r = EnergyRing(8)
        val lanes = FloatArray(HK.LANES); val out = FloatArray(HK.LANES)
        assertFalse(r.read(0, 0, out))
        for (b in 0 until 12) { lanes.fill(b.toFloat()); r.write(b * 256L, if (b < 10) 1 else 2, lanes) }
        assertTrue(r.read(11 * 256L + 10, 2, out)); assertEquals(11f, out[0], 0f)
        assertTrue(r.read(10 * 256L + 10, 2, out)); assertEquals(10f, out[87], 0f)
        assertTrue(r.read(9 * 256L, 1, out)); assertEquals(9f, out[5], 0f)
        assertEquals(0, r.misses)
        assertTrue(r.read(0L, 1, out)); assertEquals(4f, out[0], 0f)      // older than the oldest (slot of block 4)
        assertEquals(1, r.misses)
        assertFalse(r.read(0L, 3, out))                                    // no slot of that epoch
        r.reset(); assertFalse(r.read(11 * 256L, 2, out))
    }

    @Test fun energyRingNoTornReads() {
        val r = EnergyRing()
        val stop = AtomicBoolean(false)
        val writer = Thread {
            val lanes = FloatArray(HK.LANES)
            var b = 0L
            while (!stop.get()) { lanes.fill(b.toFloat()); r.write(b * 256, 0, lanes); b++ }
        }
        writer.start()
        val out = FloatArray(HK.LANES)
        var torn = 0; var reads = 0
        val t0 = System.nanoTime()
        while (System.nanoTime() - t0 < 300_000_000L) {
            if (r.read(Long.MAX_VALUE, 0, out)) { reads++; for (i in 1 until HK.LANES) if (out[i] != out[0]) { torn++; break } }
        }
        stop.set(true); writer.join()
        assertEquals(0, torn); assertTrue(reads > 0)
    }

    @Test fun voiceCursorBoard() {
        val b = VoiceCursorBoard()
        assertEquals(208, b.size)
        assertEquals(-1L, b.get(3))
        b.set(3, 17, 123_456)
        assertEquals((17L shl 32) or 123_456L, b.get(3))
        b.set(4, 2, -1)                                    // a negative frame keeps the region
        assertEquals(2, (b.get(4) ushr 32).toInt())
        b.clear(3); assertEquals(-1L, b.get(3))
        b.clearAll(); assertEquals(-1L, b.get(4))
    }
}
