package com.tropicalstream.hammerklavier.kit

import com.tropicalstream.hammerklavier.contract.FallbackReason
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.TuningSpec
import com.tropicalstream.hammerklavier.testutil.AllocProbe
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** T4.5 SynthBank and T4.6 DecodePlan. */
class SynthBankAndPlanTest {

    // ---- T4.5 ----

    @Test fun synthBankIsDeterministic() {
        for (id in InstrumentId.entries) {
            val a = SynthBank(id); val b = SynthBank(id, generation = 9)
            assertEquals(a.regionCount, b.regionCount)
            for (r in 0 until a.regionCount) assertArrayEquals("$id region $r", a.pcmOf(r), b.pcmOf(r))
            val ka = a.keyMap(TuningSpec.A415_WERCKMEISTER); val kb = b.keyMap(TuningSpec.A415_WERCKMEISTER)
            assertArrayEquals(ka.region, kb.region); assertArrayEquals(ka.rate, kb.rate, 0f)
        }
    }

    @Test fun synthBankShapeAndKeyMap() {
        val g = SynthBank(InstrumentId.GRAND)
        assertTrue(g.info.isStub); assertEquals(FallbackReason.DECODER_UNAVAILABLE, g.info.fallback)
        assertEquals(60, g.regionCount); assertEquals(2, g.info.layers)
        val m = g.keyMap(TuningSpec.A440_EQUAL)
        for (k in 21..108) for (v in 1..127) {
            val l = m.velLayerA[v].toInt()
            assertTrue(m.region[l * HK.KEYS + k] >= 0)
        }
        // Peak −3 dBFS, silent before the onset, onset sharp.
        for (r in 0 until g.regionCount) {
            val p = g.pcmOf(r)
            var peak = 0; for (s in p) peak = maxOf(peak, abs(s.toInt()))
            assertEquals(23197.0, peak.toDouble(), 40.0)
            for (t in 0 until SynthBank.ONSET) assertEquals(0, p[t].toInt())
            assertTrue(g.thrFrame(r) in SynthBank.ONSET..SynthBank.ONSET + 48)
            assertTrue(g.envByte(r, 0) >= 0 && g.envByte(r, 10_000) == 255)
        }
        val h = SynthBank(InstrumentId.HARPSICHORD)
        assertEquals(2, h.info.stops)
        val hm = h.keyMap(TuningSpec.A415_WERCKMEISTER)
        for (k in 29..89) { assertTrue(hm.region[k] >= 0); assertTrue(hm.region[HK.KEYS + k] >= 0) }
    }

    @Test fun synthReaderIsStereoAndAllocationFree() {
        val g = SynthBank(InstrumentId.UPRIGHT)
        val rd = g.newReader()
        val dst = ShortArray(2 * 256)
        AllocProbe.assertNoAllocation("SynthBank read", warmUp = { rd.read(3, 0, 256, dst, 0) }) {
            for (i in 0 until 100) rd.read(3, i * 256, 256, dst, 0)
        }
        rd.read(3, 1000, 256, dst, 0)
        for (t in 0 until 256) { assertEquals(g.pcmOf(3)[1000 + t], dst[2 * t]); assertEquals(dst[2 * t], dst[2 * t + 1]) }
        assertEquals(0, rd.read(3, g.frames(3), 10, dst, 0))
    }

    // ---- T4.6 ----

    private val grand = ToyKit.grandStd().index()      // units 0..5, 62, 63; order u0, 62, 63, u1..u5
    private fun mask(vararg u: Int): Long = u.fold(0L) { m, x -> m or (1L shl x) }
    private val big = Long.MAX_VALUE / 4

    @Test fun resumesAtTheFirstMissingUnit() {
        val fresh = DecodePlan.plan(grand, null, okPresent = false, bootCount = 3, freeBytes = big, cacheAllocated = false)
        assertArrayEquals(intArrayOf(0, 62, 63, 1, 2, 3, 4, 5), fresh.order)
        assertEquals(0, fresh.next); assertEquals(0, fresh.verify.size)
        assertFalse(fresh.playable(0))
        val s = PcmCacheFormat.ReadyState(mask(0, 62, 63, 1), IntArray(64), 3)
        val p = DecodePlan.plan(grand, s, okPresent = false, bootCount = 3, freeBytes = big, cacheAllocated = true)
        assertEquals(2, p.next)
        assertArrayEquals(intArrayOf(2, 3, 4, 5), p.pending)
        assertTrue(p.playable(s.mask)); assertFalse(p.playable(mask(0, 62)))
        assertTrue(p.fraction(grand, s.mask) in 0.3f..0.7f)
        // A hole in the middle is voiced first.
        val hole = PcmCacheFormat.ReadyState(mask(0, 62, 63, 2, 3), IntArray(64), 3)
        assertEquals(1, DecodePlan.plan(grand, hole, true, 3, big, true).next)
    }

    @Test fun schedulesCrcChecksAfterABootCountChange() {
        val s = PcmCacheFormat.ReadyState(mask(0, 62, 63, 1, 2), IntArray(64), 7)
        val same = DecodePlan.plan(grand, s, okPresent = true, bootCount = 7, freeBytes = big, cacheAllocated = true)
        assertEquals(0, same.verify.size)
        val rebooted = DecodePlan.plan(grand, s, okPresent = true, bootCount = 8, freeBytes = big, cacheAllocated = true)
        assertArrayEquals(intArrayOf(1, 2), rebooted.verify)                // the newest two in decode order
        val noOk = DecodePlan.plan(grand, s, okPresent = false, bootCount = 7, freeBytes = big, cacheAllocated = true)
        assertArrayEquals(intArrayOf(1, 2), noOk.verify)
        val complete = PcmCacheFormat.ReadyState(mask(0, 1, 2, 3, 4, 5, 62, 63), IntArray(64), 7)
        val c = DecodePlan.plan(grand, complete, okPresent = true, bootCount = 7, freeBytes = 0, cacheAllocated = true)
        assertTrue(c.complete); assertEquals(-1, c.next); assertEquals(0, c.verify.size)
        assertTrue(c.storageOk); assertEquals(0L, c.requiredBytes)
    }

    @Test fun storageCheck() {
        val total = grand.units.sumOf { DecodePlan.unitBytes(grand, it.id) }
        val ok = DecodePlan.plan(grand, null, false, 1, freeBytes = total + DecodePlan.MARGIN_BYTES, cacheAllocated = false)
        assertTrue(ok.storageOk); assertFalse(ok.reduced)
        assertEquals(total + DecodePlan.MARGIN_BYTES, ok.requiredBytes)
        val short = DecodePlan.plan(grand, null, false, 1, freeBytes = total + DecodePlan.MARGIN_BYTES - 1, cacheAllocated = false)
        assertFalse(short.storageOk)                                      // toy labels are u<n>: no reduced grand
        // With real labels, the reduced grand (v4 + v13 + releases + pedals) is chosen.
        val labelled = ToyKit.grandStd().index { o ->
            val u = o.getJSONArray("units")
            val names = mapOf(0 to "v1", 1 to "v4", 2 to "v7", 3 to "v10", 4 to "v13", 5 to "v16")
            for (i in 0 until u.length()) { val x = u.getJSONObject(i); names[x.getInt("id")]?.let { x.put("label", it) } }
        }
        val reducedBytes = listOf(1, 4, 62, 63).sumOf { DecodePlan.unitBytes(labelled, it) }
        val r = DecodePlan.plan(labelled, null, false, 1, freeBytes = reducedBytes + DecodePlan.MARGIN_BYTES, cacheAllocated = false)
        assertTrue(r.storageOk); assertTrue(r.reduced)
        assertEquals(setOf(1, 4, 62, 63), r.order.toSet())
        assertEquals(1, r.playableSet[0])
        val none = DecodePlan.plan(labelled, null, false, 1, freeBytes = 1000, cacheAllocated = false)
        assertFalse(none.storageOk)
        // A pre-sized cache already holds its space.
        // A pre-sized .pcm is sparse: its remaining bytes are still required.
        val alloc = DecodePlan.plan(labelled, null, false, 1, freeBytes = DecodePlan.MARGIN_BYTES, cacheAllocated = true)
        assertFalse(alloc.storageOk)
        assertEquals(alloc.remainingBytes + DecodePlan.MARGIN_BYTES, alloc.requiredBytes)
    }

    @Test fun staleFilesForAnotherSha() {
        val names = listOf("grand-01234567.pcm", "grand-01234567.ready", "grand-deadbeef.pcm", "grand-deadbeef.ok",
            "upright-deadbeef.pcm", "grand-deadbeef.ready.tmp")
        assertEquals(listOf("grand-deadbeef.pcm", "grand-deadbeef.ok", "grand-deadbeef.ready.tmp"),
            DecodePlan.staleFiles(names, "grand", "01234567"))
    }
}
