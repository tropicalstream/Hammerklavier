package com.tropicalstream.hammerklavier.contract

enum class SyntheticScore { SYNC_CLICK, SCALE, CHORD_STORM_64, PEDAL_HALF, SOSTENUTO, UNA_CORDA, REPEAT_15, FOLD, CRESCENDO_C4 }

/**
 * The raw note and controller lists of the §4.5 synthetic scores (shared by PerfFixtures, WP1's
 * SyntheticScores and WP11's `.mid` twins).
 *
 * Normative details (frozen with contracts-v1; the twins must encode exactly these):
 * - Times are **file time** in µs from 0, **without** the pre-roll (PerfFixtures and the real
 *   builder add [HK.PRE_ROLL_US]). Every time is a whole millisecond, so a twin written at
 *   PPQ 500 and tempo 500,000 µs per quarter (1 tick = 1 ms) is exact.
 * - Notes are sorted by (onUs, key); one channel; no key sounds twice at once.
 * - Controller curves are **raw steps** (value = cc / 127, held until the next event):
 *   an event at t with value c is the pair of points (t, previous), (t, c / 127).
 *
 * | Kind | Content |
 * |---|---|
 * | SYNC_CLICK | key 69 v118, 100 clicks, click i (0..99) at 600·i + h(i) ms, 50 ms long; h(i) = mix(i) mod 34 (see [clickOffsetMs]) |
 * | SCALE | keys 21..108 in order, one every 250 ms, 200 ms long, velocity 20 + round(107·j/87) |
 * | CHORD_STORM_64 | 720 chords, one every 250 ms (180 s); chord c: keys 21 + ((29c + 27j) mod 88) for j 0..63, velocity 40 + ((7c + 13j) mod 80), 200 ms long; CC64 127 at 0, 0 at 180,000 ms |
 * | PEDAL_HALF | 16 chords {48, 60, 64, 67} v80 every 1 s, 800 ms long; CC64 ramps 0 → 127 over 4 s and 127 → 0 over 4 s, twice (one event per value) |
 * | SOSTENUTO | key 36 v80 0–6000; CC66 127 at 500, 0 at 5000; chords {60, 64, 67} v70 every 500 ms from 1000 to 4500, 100 ms long; CC64 51 (0.40) at 6500, 0 at 10,000; key 43 v80 7000–9500; CC66 127 at 7500, 0 at 9000; chords {72, 76, 79} v70 at 8000 and 8500, 100 ms long |
 * | UNA_CORDA | key 60 v64 0–3000; CC67 127 at 4000; key 60 v64 4500–7500; CC67 0 at 8000 |
 * | REPEAT_15 | key 60, 15 notes per second for 2 s at v20 (from 0), v64 (from 3000 ms) and v110 (from 6000 ms); note j of a block at round(j·1000/15) ms, 33 ms long |
 * | FOLD | keys 12..120 in order, one every 150 ms, 120 ms long, v70 |
 * | CRESCENDO_C4 | key 60, velocities 10..127, one every 400 ms, 300 ms long |
 */
object SyntheticSpecs {
    fun notes(kind: SyntheticScore): ScoreSpec = when (kind) {
        SyntheticScore.SYNC_CLICK -> Gen().apply {
            for (i in 0 until 100) note(600L * i + clickOffsetMs(i), 50, 69, 118)
        }.spec()
        SyntheticScore.SCALE -> Gen().apply {
            for (j in 0 until 88) note(250L * j, 200, 21 + j, 20 + Math.round(107.0 * j / 87.0).toInt())
        }.spec()
        SyntheticScore.CHORD_STORM_64 -> Gen().apply {
            for (c in 0 until 720) for (j in 0 until 64)
                note(250L * c, 200, 21 + ((29 * c + 27 * j) % 88), 40 + ((7 * c + 13 * j) % 80))
            cc64(0, 127); cc64(180_000, 0)
        }.spec()
        SyntheticScore.PEDAL_HALF -> Gen().apply {
            for (c in 0 until 16) for (k in intArrayOf(48, 60, 64, 67)) note(1000L * c, 800, k, 80)
            for (rep in 0 until 2) {
                val base = 8000L * rep
                for (v in 0..127) cc64(base + Math.round(v * 4000.0 / 127.0), v)
                for (v in 1..127) cc64(base + 4000 + Math.round(v * 4000.0 / 127.0), 127 - v)
            }
        }.spec()
        SyntheticScore.SOSTENUTO -> Gen().apply {
            note(0, 6000, 36, 80)
            cc66(500, 127); cc66(5000, 0)
            for (c in 0 until 8) for (k in intArrayOf(60, 64, 67)) note(1000L + 500L * c, 100, k, 70)
            cc64(6500, 51); cc64(10_000, 0)
            note(7000, 2500, 43, 80)
            cc66(7500, 127); cc66(9000, 0)
            for (t in longArrayOf(8000, 8500)) for (k in intArrayOf(72, 76, 79)) note(t, 100, k, 70)
        }.spec()
        SyntheticScore.UNA_CORDA -> Gen().apply {
            note(0, 3000, 60, 64)
            cc67(4000, 127)
            note(4500, 3000, 60, 64)
            cc67(8000, 0)
        }.spec()
        SyntheticScore.REPEAT_15 -> Gen().apply {
            val vels = intArrayOf(20, 64, 110)
            for (b in 0 until 3) for (j in 0 until 30) note(3000L * b + Math.round(j * 1000.0 / 15.0), 33, 60, vels[b])
        }.spec()
        SyntheticScore.FOLD -> Gen().apply {
            for (j in 0..108) note(150L * j, 120, 12 + j, 70)
        }.spec()
        SyntheticScore.CRESCENDO_C4 -> Gen().apply {
            for (j in 0..117) note(400L * j, 300, 60, 10 + j)
        }.spec()
    }

    /**
     * The §4.5 name of each kind, as used by CONTROL (`--es play synth:<name>`), the smoke scripts
     * and the test twins (`assets/midi/test/<name>.mid`, `test:<name>`).
     */
    val NAMES: Map<SyntheticScore, String> = linkedMapOf(
        SyntheticScore.SYNC_CLICK to "sync", SyntheticScore.SCALE to "scale", SyntheticScore.CHORD_STORM_64 to "storm64",
        SyntheticScore.PEDAL_HALF to "pedalhalf", SyntheticScore.SOSTENUTO to "sostenuto", SyntheticScore.UNA_CORDA to "unacorda",
        SyntheticScore.REPEAT_15 to "repeat15", SyntheticScore.FOLD to "fold", SyntheticScore.CRESCENDO_C4 to "crescendo")

    /** The kind named `<name>`, `synth:<name>` or `test:<name>`; null if none. */
    fun kindOf(name: String): SyntheticScore? {
        val bare = name.substringAfter(':')
        return NAMES.entries.firstOrNull { it.value == bare }?.key
    }

    /** SYNC_CLICK's pseudo-random 0–33 ms offset for click i (a fixed integer mix, mod 34). */
    fun clickOffsetMs(i: Int): Int {
        var h = i * -0x61c88647            // 0x9E3779B9, golden-ratio multiply (wrapping)
        h = h xor (h ushr 16)
        h *= 0x45d9f3b
        h = h xor (h ushr 16)
        return (h and 0x7FFFFFFF) % 34
    }

    /** Accumulates notes (ms) and raw controller steps, then sorts and packs a ScoreSpec. */
    private class Gen {
        private val on = ArrayList<Long>(); private val off = ArrayList<Long>()
        private val k = ArrayList<Int>(); private val v = ArrayList<Int>()
        private val c64 = ArrayList<Long>(); private val c66 = ArrayList<Long>(); private val c67 = ArrayList<Long>()

        fun note(onMs: Long, lenMs: Int, key: Int, vel: Int) {
            on.add(onMs * 1000); off.add((onMs + lenMs) * 1000); k.add(key); v.add(vel.coerceIn(1, 127))
        }
        fun cc64(ms: Long, value: Int) { c64.add(ms * 1000); c64.add(value.toLong()) }
        fun cc66(ms: Long, value: Int) { c66.add(ms * 1000); c66.add(value.toLong()) }
        fun cc67(ms: Long, value: Int) { c67.add(ms * 1000); c67.add(value.toLong()) }

        fun spec(): ScoreSpec {
            val n = on.size
            val order = (0 until n).sortedWith(compareBy<Int>({ on[it] }, { k[it] }))
            return ScoreSpec(
                onUs = LongArray(n) { on[order[it]] }, offUs = LongArray(n) { off[order[it]] },
                key = ByteArray(n) { k[order[it]].toByte() }, vel = ByteArray(n) { v[order[it]].toByte() },
                cc64 = steps(c64), cc66 = steps(c66), cc67 = steps(c67))
        }

        private fun steps(pairs: ArrayList<Long>): PedalCurve {
            if (pairs.isEmpty()) return PedalCurve.EMPTY
            val us = ArrayList<Long>(); val vs = ArrayList<Float>()
            var prev = 0f
            var i = 0
            while (i < pairs.size) {
                val t = pairs[i]; val value = pairs[i + 1].toFloat() / 127f
                if (us.isNotEmpty()) { us.add(t); vs.add(prev) }
                us.add(t); vs.add(value)
                prev = value
                i += 2
            }
            return PedalCurve(LongArray(us.size) { us[it] }, FloatArray(vs.size) { vs[it] })
        }
    }
}

/** Raw notes (file time µs, no pre-roll) and raw controller curves (unshaped). */
class ScoreSpec(val onUs: LongArray, val offUs: LongArray, val key: ByteArray, val vel: ByteArray,
                val cc64: PedalCurve, val cc66: PedalCurve, val cc67: PedalCurve)

/** @param flatVelocity > 0 replaces every velocity. */
class CompileOptions(val legatoHold: Boolean = true, val legatoCapMs: Int = 1500, val flatVelocity: Int = 0)

sealed class CompileResult {
    class Ok(val perf: Performance) : CompileResult()
    class Failed(val reason: RejectReason, val detail: String, val byteOffset: Int) : CompileResult()
}

class ScoreFacts(val ok: Boolean, val error: RejectReason?, val title: String?, val durationSec: Float,
    val lowKey: Int, val highKey: Int, val noteCount: Int, val hasSustain: Boolean, val hasSoft: Boolean,
    val hasSostenuto: Boolean, val pedalMode: PedalMode, val channels: Int, val warnings: List<PerfWarning>)

/** Pure, any thread, never throws. */
interface ScoreCompiler {
    /** "MThd" at 0, or RIFF…RMID. */
    fun sniff(head: ByteArray): Boolean
    fun inspect(bytes: ByteArray): ScoreFacts
    fun compile(bytes: ByteArray, id: String, generation: Int, profile: InstrumentProfile,
                opts: CompileOptions = CompileOptions()): CompileResult
    fun synthetic(kind: SyntheticScore, profile: InstrumentProfile, generation: Int): Performance
}
