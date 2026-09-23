package com.tropicalstream.hammerklavier.session

import com.tropicalstream.hammerklavier.contract.StatusCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T12.5: status items expire and are ordered by UiText's priorities (lower number first). */
class StatusBoardTest {
    @Test fun expiresAfterDuration() {
        val b = StatusBoard()
        b.post(StatusCode.IMPORTED, listOf("a.mid"), nowMs = 1_000)
        assertEquals(1, b.active(8_999).size)
        assertTrue(b.active(9_000).isEmpty())
    }

    @Test fun orderedByInjectedPriorityThenNewest() {
        // An injected table that reverses the default order proves the board uses it.
        val b = StatusBoard { c -> -StatusBoard.defaultPriority(c) }
        b.post(StatusCode.CRASH_LAST_SESSION, emptyList(), 0)
        b.post(StatusCode.IMPORTED, listOf("x"), 0)
        b.post(StatusCode.IMPORTED, listOf("y"), 0)
        assertEquals(listOf("y", "x", null), b.active(1).map { it.args.firstOrNull() })
        val d = StatusBoard()
        d.post(StatusCode.IMPORTED, listOf("x"), 0); d.post(StatusCode.FALLBACK, listOf("BANK_MISSING"), 0)
        d.post(StatusCode.CRASH_LAST_SESSION, listOf("npe"), 0); d.post(StatusCode.IMPORT_FAILED, listOf("f"), 0)
        assertEquals(listOf(StatusCode.CRASH_LAST_SESSION, StatusCode.IMPORT_FAILED, StatusCode.FALLBACK, StatusCode.IMPORTED),
            d.active(1).map { it.code })
    }

    @Test fun repostRefreshesAndPersistentStaysUntilCleared() {
        val b = StatusBoard()
        b.post(StatusCode.IMPORTED, listOf("a"), 0)
        b.post(StatusCode.IMPORTED, listOf("a"), 5_000)
        assertEquals(1, b.active(10_000).size)
        assertEquals(13_000L, b.active(10_000).single().untilMs)
        b.post(StatusCode.DISPLAY_REST, emptyList(), 0, StatusBoard.PERSISTENT)
        assertTrue(b.has(StatusCode.DISPLAY_REST, Long.MAX_VALUE - 1))
        b.clear(StatusCode.DISPLAY_REST)
        assertTrue(!b.has(StatusCode.DISPLAY_REST, 0))
    }

    @Test fun everyCodeHasADefaultPriority() {
        for (c in StatusCode.entries) assertTrue(StatusBoard.defaultPriority(c) in 1..5)
    }
}
