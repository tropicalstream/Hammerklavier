package com.tropicalstream.hammerklavier.session

import com.tropicalstream.hammerklavier.contract.StatusCode
import com.tropicalstream.hammerklavier.contract.StatusItem

/**
 * The active status-line entries (PLAN §1.8, §2.3 Status). Producers post codes with arguments;
 * each entry lives until its `untilMs` (8 s by default, [PERSISTENT] for Display rest) or until it
 * is cleared. [active] drops expired entries and orders the rest by priority (lower number first,
 * from WP10's UiText, injected as [priority]), newest first within a priority. One entry per
 * (code, args): posting it again refreshes its expiry. Main thread only.
 */
class StatusBoard(private val priority: (StatusCode) -> Int = ::defaultPriority) {
    private val items = ArrayList<StatusItem>()
    private val postedAt = HashMap<StatusItem, Long>()
    private var seq = 0L

    /** Adds or refreshes an entry. [durationMs] = [PERSISTENT] keeps it until [clear]. */
    fun post(code: StatusCode, args: List<String> = emptyList(), nowMs: Long, durationMs: Long = DEFAULT_MS) {
        val until = if (durationMs == PERSISTENT) Long.MAX_VALUE else nowMs + durationMs
        val it = items.iterator()
        while (it.hasNext()) {
            val s = it.next()
            if (s.code == code && s.args == args) { it.remove(); postedAt.remove(s) }
        }
        val item = StatusItem(code, args.toList(), until)
        items.add(item)
        postedAt[item] = ++seq
    }

    /** Removes every entry with [code]. */
    fun clear(code: StatusCode) {
        val it = items.iterator()
        while (it.hasNext()) { val s = it.next(); if (s.code == code) { it.remove(); postedAt.remove(s) } }
    }

    fun has(code: StatusCode, nowMs: Long): Boolean { expire(nowMs); return items.any { it.code == code } }

    /** Unexpired entries, most important first. */
    fun active(nowMs: Long): List<StatusItem> {
        expire(nowMs)
        return items.sortedWith(compareBy<StatusItem>({ priority(it.code) }, { -(postedAt[it] ?: 0L) }))
    }

    private fun expire(nowMs: Long) {
        val it = items.iterator()
        while (it.hasNext()) { val s = it.next(); if (s.untilMs <= nowMs) { it.remove(); postedAt.remove(s) } }
    }

    companion object {
        const val DEFAULT_MS = 8_000L
        const val PERSISTENT = -1L

        /**
         * The §1.8 order, used until WP10's UiText priority table is wired in (AppController passes
         * `UiText::priority`): 1 crash, 2 import failures, 3 fallbacks and audio, 4 display rest,
         * 5 import results and new headphones.
         */
        fun defaultPriority(c: StatusCode): Int = when (c) {
            StatusCode.CRASH_LAST_SESSION -> 1
            StatusCode.IMPORT_FAILED, StatusCode.IMPORT_PERMISSION, StatusCode.SCORES_DIR_FOREIGN -> 2
            StatusCode.FALLBACK, StatusCode.AUDIO_UNAVAILABLE, StatusCode.AUDIO_STOPPED -> 3
            StatusCode.DISPLAY_REST -> 4
            StatusCode.IMPORTED, StatusCode.NEW_BT_DEVICE -> 5
        }
    }
}
