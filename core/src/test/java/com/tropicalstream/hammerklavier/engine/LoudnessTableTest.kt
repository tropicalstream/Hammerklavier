package com.tropicalstream.hammerklavier.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Loudness consistency on the REAL kits (INTEGRATION.md, loudness across instruments and views).
 * The table is LoudnessProbeTest's offline render (six pieces × grand, upright, harpsichord ×
 * Player, Action cutaway / overhead, Hall row 3; full chain, Room mode, master −8 dB), checked in
 * because the kits' Opus units cannot be decoded on the JVM. The fingerprint line ties it to the
 * trims and the room model in the code, so a change to either fails here until the probe is re-run.
 */
class LoudnessTableTest {
    private class Row(val inst: String, val piece: String, val view: String, val lufs: Double, val limMeanGrDb: Double)

    private val lines by lazy { javaClass.getResource("/loudness/table_after.csv")!!.readText().trim().lines() }
    private val rows by lazy {
        val h = lines[1].split(",")
        lines.drop(2).map { l ->
            val c = l.split(",")
            Row(c[h.indexOf("instrument")], c[h.indexOf("piece")], c[h.indexOf("view")], c[h.indexOf("lufs")].toDouble(),
                c[h.indexOf("lim_mean_gr_db")].toDouble())
        }
    }
    private fun lufs(i: String, p: String, v: String) = rows.first { it.inst == i && it.piece == p && it.view == v }.lufs
    private val instruments get() = rows.map { it.inst }.distinct()
    private val pieces get() = rows.map { it.piece }.distinct()
    private val views get() = rows.map { it.view }.distinct()

    @Test fun tableMatchesTheCode() {
        val kits = LoudnessFingerprint.kitsFromAssets()
        for ((_, kit) in kits) assertTrue("kit ${kit.first} has a KitLoudness trim", kit.first in KitLoudness.TRIM_DB)
        assertEquals("re-run LoudnessProbeTest (tag=after) and copy build/loudness/table_after.csv",
            LoudnessFingerprint.of(kits.mapValues { it.value.second }), lines[0])
        assertEquals(3, instruments.size); assertTrue(pieces.size >= 3); assertEquals(4, views.size)
    }

    /** Every view's loudness, averaged over the pieces, within 0.5 LU of the Player; each piece within 1.25 LU on the pianos. */
    @Test fun viewsMatchThePlayer() {
        for (i in instruments) for (v in views) {
            val d = pieces.map { lufs(i, it, v) - lufs(i, it, "player") }
            assertEquals("$i $v mean re player", 0.0, d.average(), 0.5)
            // The harpsichord's Hall is programme-dependent (the room sustains its plucks: −1.7 … +3.0 LU
            // by piece); the pianos stay within ±1.25 LU on every piece.
            val tol = if (i == "harpsichord") 3.5 else 1.25
            for ((k, x) in d.withIndex()) assertTrue("$i ${pieces[k]} $v: %+.2f LU re player".format(x), abs(x) <= tol)
        }
    }

    /** The instruments play the same repertoire at the same loudness (median over pieces of the difference to the grand). */
    @Test fun instrumentsMatchTheGrand() {
        for (i in instruments) {
            val d = pieces.map { p -> views.map { lufs(i, p, it) }.average() - views.map { lufs("grand", p, it) }.average() }.sorted()
            val median = if (d.size % 2 == 1) d[d.size / 2] else 0.5 * (d[d.size / 2 - 1] + d[d.size / 2])
            assertEquals("$i median re grand", 0.0, median, 0.5)
        }
    }

    /**
     * The limiter works the same in every view: at the default master level its mean gain reduction
     * stays under 0.5 dB, and between the views of one piece it differs by at most 0.25 dB (with the
     * level equalised only the crest factor differs: the Hall's reverberant sound has fewer peaks).
     */
    @Test fun limiterAlikeInEveryView() {
        for (i in instruments) for (p in pieces) {
            val gr = rows.filter { it.inst == i && it.piece == p }.map { it.limMeanGrDb }
            assertTrue("$i $p mean GR $gr dB", gr.all { it < 0.5 })
            assertTrue("$i $p mean GR across views $gr dB", gr.max() - gr.min() <= 0.25)
        }
    }
}
