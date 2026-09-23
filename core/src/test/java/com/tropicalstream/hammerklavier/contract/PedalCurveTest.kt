package com.tropicalstream.hammerklavier.contract

import com.tropicalstream.hammerklavier.testutil.AllocProbe
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

class PedalCurveTest {
    private fun randomCurve(r: Random, n: Int): PedalCurve {
        val us = LongArray(n); val v = FloatArray(n)
        var t = 1000L
        for (i in 0 until n) {
            t += if (r.nextInt(5) == 0) 0 else r.nextInt(50_000).toLong()     // some steps
            us[i] = t; v[i] = r.nextFloat()
        }
        return PedalCurve(us, v)
    }

    @Test fun valueAtBasics() {
        val c = PedalCurve(longArrayOf(100, 200, 200, 300), floatArrayOf(0f, 1f, 0.5f, 0.5f))
        assertEquals(0f, c.valueAt(50), 0f)
        assertEquals(0.5f, c.valueAt(150), 1e-6f)
        assertEquals(0.5f, c.valueAt(200), 1e-6f)          // step: the later point wins at its time
        assertEquals(0.5f, c.valueAt(10_000), 0f)
        assertEquals(0f, PedalCurve.EMPTY.valueAt(5), 0f)
    }

    @Test fun cursorEqualsBinarySearch() {
        val r = Random(7)
        val c = randomCurve(r, 500)
        val cur = PedalCurve.Cursor()
        cur.bind(c)
        var t = 0L
        for (i in 0 until 100_000) {
            t += r.nextInt(40_000) - 8_000                  // mostly forward, sometimes back
            if (t < 0) t = 0
            assertEquals(c.valueAt(t), cur.advanceTo(t), 1e-6f)
        }
    }

    @Test fun bindAndAdvanceAllocateNothing() {
        val c = randomCurve(Random(3), 200)
        val cur = PedalCurve.Cursor()
        AllocProbe.assertNoAllocation("PedalCurve.Cursor", warmUp = { cur.bind(c); cur.advanceTo(5000) }) {
            for (i in 0 until 10_000) { cur.bind(c); cur.seek(i * 100L); cur.advanceTo(i * 100L + 50) }
        }
    }

    @Test fun nextCrossing() {
        val c = PedalCurve(longArrayOf(1000, 2000, 3000, 4000), floatArrayOf(0f, 1f, 1f, 0f))
        assertEquals(1330L, c.nextCrossing(0, 0.33f, rising = true))
        assertEquals(3670L, c.nextCrossing(0, 0.33f, rising = false))
        assertEquals(Long.MAX_VALUE, c.nextCrossing(2000, 0.33f, rising = true))
        val step = PedalCurve(longArrayOf(500), floatArrayOf(1f))
        assertEquals(500L, step.nextCrossing(0, 0.5f, rising = true))
        assertEquals(Long.MAX_VALUE, PedalCurve.EMPTY.nextCrossing(0, 0.5f, true))
    }
}
