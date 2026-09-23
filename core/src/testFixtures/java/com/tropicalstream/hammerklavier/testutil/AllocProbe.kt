package com.tropicalstream.hammerklavier.testutil

import java.lang.management.ManagementFactory

/**
 * Per-thread allocation assertions for JVM tests (PLAN §2.1 rule 1, T2.10). Uses HotSpot's
 * `com.sun.management.ThreadMXBean.getThreadAllocatedBytes`; tests run with escape analysis off
 * (`-XX:-DoEscapeAnalysis -XX:-EliminateAllocations`, core/build.gradle.kts), so scalar
 * replacement cannot hide what ART would allocate.
 *
 *     AllocProbe.assertNoAllocation("render") { repeat(1000) { core.render(out, 0L) } }
 */
object AllocProbe {
    private val bean: com.sun.management.ThreadMXBean =
        ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    init { if (!bean.isThreadAllocatedMemoryEnabled) bean.isThreadAllocatedMemoryEnabled = true }

    /** Bytes allocated so far by the calling thread. */
    fun allocatedBytes(): Long = bean.currentThreadAllocatedBytes

    /** Bytes allocated by the calling thread while [block] runs (the probe's own overhead removed). */
    inline fun measure(block: () -> Unit): Long {
        val overhead = run { val a = allocatedBytes(); val b = allocatedBytes(); b - a }
        val start = allocatedBytes()
        block()
        val end = allocatedBytes()
        return (end - start - overhead).coerceAtLeast(0L)
    }

    /**
     * Runs [warmUp] and then [block] once (class loading, linkage and first-call costs), then fails
     * with an AssertionError if a second run of [block] allocates more than [allowBytes]. A steady
     * per-call allocation shows in every run, so the measurement is retried up to [attempts] times
     * and fails only if every attempt allocates: a single JVM-side blip (seen once in full CI as
     * 904 bytes in MechanicsEvaluatorTest) no longer fails the build.
     */
    inline fun assertNoAllocation(what: String, allowBytes: Long = 0L, attempts: Int = 3, warmUp: () -> Unit = {}, block: () -> Unit) {
        warmUp()
        block()
        var bytes = measure(block)
        var n = 1
        while (bytes > allowBytes && n < attempts) { bytes = measure(block); n++ }
        if (bytes > allowBytes) throw AssertionError("$what allocated $bytes bytes (allowed $allowBytes)")
    }
}
