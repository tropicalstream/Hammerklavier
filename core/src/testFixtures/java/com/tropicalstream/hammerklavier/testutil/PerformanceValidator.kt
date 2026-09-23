package com.tropicalstream.hammerklavier.testutil

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.Performance

/**
 * Structural checks every Performance must pass (PLAN §2.3, §4.3; StubContractTest, WP1's
 * builder tests): notes sorted by (onUs, key) and inside the profile's compass, offUs > onUs,
 * the first onset at or after the pre-roll, CSR arrays consistent with non-overlapping notes per
 * key sorted by onUs, events sorted by (evUs, type) with valid args, one EV_END at the last
 * key-up, latch arrays of equal length, durationUs = last key-up + 1.5 s, bars non-decreasing.
 */
object PerformanceValidator {
    fun problems(p: Performance, profile: InstrumentProfile? = null): List<String> {
        val out = ArrayList<String>()
        val n = p.noteCount
        fun bad(s: String) { if (out.size < 20) out.add(s) }
        if (p.offUs.size != n || p.key.size != n || p.vel.size != n || p.flags.size != n) bad("note arrays differ in length")
        for (i in 0 until n) {
            val k = p.key[i].toInt() and 0xFF
            if (p.offUs[i] <= p.onUs[i]) bad("note $i: offUs ${p.offUs[i]} <= onUs ${p.onUs[i]}")
            if (p.onUs[i] < HK.PRE_ROLL_US) bad("note $i: onset ${p.onUs[i]} inside the pre-roll")
            if (profile != null && (k < profile.lowKey || k > profile.highKey)) bad("note $i: key $k outside ${profile.lowKey}..${profile.highKey}")
            if (i > 0) {
                val a = p.onUs[i - 1]; val b = p.onUs[i]
                if (b < a || (b == a && (p.key[i - 1].toInt() and 0xFF) > k)) bad("notes $i-1, $i not sorted by (onUs, key)")
            }
            val v = p.vel[i].toInt() and 0xFF
            if (v !in 1..127) bad("note $i: velocity $v")
        }
        if (p.keyFirst.size != 129) bad("keyFirst size ${p.keyFirst.size}")
        else {
            if (p.keyFirst[0] != 0 || p.keyFirst[128] != n) bad("keyFirst bounds ${p.keyFirst[0]}..${p.keyFirst[128]}")
            if (p.keyNotes.size != n) bad("keyNotes size ${p.keyNotes.size} != $n")
            val seen = BooleanArray(n)
            for (k in 0 until 128) {
                if (p.keyFirst[k + 1] < p.keyFirst[k]) bad("keyFirst not monotone at $k")
                for (j in p.keyFirst[k] until minOf(p.keyFirst[k + 1], p.keyNotes.size)) {
                    val note = p.keyNotes[j]
                    if (note !in 0 until n) { bad("keyNotes[$j] = $note"); continue }
                    if (seen[note]) bad("note $note listed twice")
                    seen[note] = true
                    if ((p.key[note].toInt() and 0xFF) != k) bad("keyNotes[$j] = note $note is key ${p.key[note]}, listed under $k")
                    if (j > p.keyFirst[k]) {
                        val prev = p.keyNotes[j - 1]
                        if (p.onUs[note] < p.onUs[prev]) bad("key $k notes not sorted by onUs")
                        if (p.onUs[note] < p.offUs[prev]) bad("key $k: note $note overlaps note $prev")
                    }
                }
            }
            if (seen.any { !it }) bad("some notes missing from keyNotes")
        }
        if (p.evUs.size != p.ev.size) bad("event arrays differ in length")
        var ends = 0
        for (i in p.ev.indices) {
            val t = Performance.type(p.ev[i]); val a = Performance.arg(p.ev[i])
            if (i > 0) {
                val t0 = Performance.type(p.ev[i - 1])
                if (p.evUs[i] < p.evUs[i - 1] || (p.evUs[i] == p.evUs[i - 1] && t < t0)) bad("events $i-1, $i not sorted by (evUs, type)")
            }
            when (t) {
                Performance.EV_NOTE_ON -> if (a !in 0 until n || p.onUs[a] != p.evUs[i]) bad("event $i: NOTE_ON arg $a")
                Performance.EV_KEY_UP -> if (a !in 0 until n || p.offUs[a] != p.evUs[i]) bad("event $i: KEY_UP arg $a")
                Performance.EV_LATCH -> if (a !in p.latchUs.indices || p.latchUs[a] != p.evUs[i]) bad("event $i: LATCH arg $a")
                Performance.EV_PEDAL_NOISE -> if (a !in 0..7) bad("event $i: PEDAL_NOISE arg $a")
                Performance.EV_END -> ends++
                else -> bad("event $i: type $t")
            }
        }
        val onCount = p.ev.count { Performance.type(it) == Performance.EV_NOTE_ON }
        val upCount = p.ev.count { Performance.type(it) == Performance.EV_KEY_UP }
        if (onCount != n || upCount != n) bad("events: $onCount NOTE_ON and $upCount KEY_UP for $n notes")
        if (ends != 1) bad("$ends EV_END events")
        val lastUp = if (n == 0) HK.PRE_ROLL_US else p.offUs.max()
        if (p.durationUs != lastUp + 1_500_000L) bad("durationUs ${p.durationUs} != last key-up $lastUp + 1.5 s")
        if (p.latchLo.size != p.latchUs.size || p.latchHi.size != p.latchUs.size) bad("latch arrays differ in length")
        for (i in 1 until p.latchUs.size) if (p.latchUs[i] < p.latchUs[i - 1]) bad("latchUs not sorted")
        for (i in 1 until p.barUs.size) if (p.barUs[i] < p.barUs[i - 1]) bad("barUs not sorted")
        if (p.info.noteCount != n) bad("info.noteCount ${p.info.noteCount} != $n")
        return out
    }

    fun assertValid(p: Performance, profile: InstrumentProfile? = null) {
        val pr = problems(p, profile)
        if (pr.isNotEmpty()) throw AssertionError("Performance '${p.id}' invalid:\n  " + pr.joinToString("\n  "))
    }
}
