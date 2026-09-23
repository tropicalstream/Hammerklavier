package com.tropicalstream.hammerklavier.contract.stub

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.PedalCurve
import com.tropicalstream.hammerklavier.contract.PedalMode
import com.tropicalstream.hammerklavier.contract.PedalMotion
import com.tropicalstream.hammerklavier.contract.PerfInfo
import com.tropicalstream.hammerklavier.contract.PerfWarning
import com.tropicalstream.hammerklavier.contract.Performance
import com.tropicalstream.hammerklavier.contract.ScoreSpec
import com.tropicalstream.hammerklavier.contract.SoftKind

/**
 * Fully valid Performances from raw note lists (PLAN §2.3 stub behaviour), with pedal curves used
 * as given (no shaping). Every input time is **file time** (µs from 0, as in [ScoreSpec]); build
 * adds [HK.PRE_ROLL_US] to notes and curves. The rules follow §4.3 where they need no shaping:
 * - notes outside the profile's compass fold by octaves (F_FOLDED); a folded note starting within
 *   20 ms of a sounding note on its new key is merged into it (higher velocity kept);
 * - a note-on for a key still down ends the previous note at newOn − 2 ms (never before its
 *   onset + 20 ms; F_RESTRIKE on the new note); zero-length notes get 30 ms;
 * - pedals the profile does not use become EMPTY; the harpsichord's CC64 becomes legato hold
 *   (each note-off while the pedal is ≥ 0.5 moves to the pedal's release, capped at +1.5 s and at
 *   the next onset on that key − 2 ms) and is then dropped (fingerPedalled);
 * - sostenuto latches at each 0.5 crossing of the sostenuto curve (rising: keys whose dampers are
 *   up, i.e. onUs ≤ t < offUs + damperLagMs, plus every key ≤ lastDamper if sustain ≥ 0.55;
 *   falling: mask 0);
 * - pedal noises at each 0.33 crossing of the sustain curve, ≥ 150 ms apart, speed class from the
 *   slope (a step = class 2);
 * - events sorted by (evUs, type, arg); EV_END at the last key-up; bars every 2 s from the
 *   pre-roll; durationUs = last key-up + 1.5 s.
 */
object PerfFixtures {
    fun build(notes: ScoreSpec, sustain: PedalCurve = PedalCurve.EMPTY, soft: PedalCurve = PedalCurve.EMPTY,
              sostenuto: PedalCurve = PedalCurve.EMPTY, profile: InstrumentProfile, generation: Int,
              id: String = "fixture", title: String? = null): Performance {
        val pre = HK.PRE_ROLL_US
        val sus0 = shift(sustain, pre)
        val sft = if (profile.softKind == SoftKind.NONE) PedalCurve.EMPTY else shift(soft, pre)
        val sos = if (profile.usesSostenuto) shift(sostenuto, pre) else PedalCurve.EMPTY

        // 1. Notes: shift, fold, fix zero lengths.
        val n0 = notes.onUs.size
        val on = LongArray(n0); val off = LongArray(n0); val key = IntArray(n0); val vel = IntArray(n0); val flg = IntArray(n0)
        var folded = 0
        for (i in 0 until n0) {
            var k = notes.key[i].toInt() and 0x7F
            var f = 0
            while (k < profile.lowKey) { k += 12; f = Performance.F_FOLDED }
            while (k > profile.highKey) { k -= 12; f = Performance.F_FOLDED }
            if (f != 0) folded++
            on[i] = notes.onUs[i] + pre
            off[i] = maxOf(notes.offUs[i] + pre, on[i] + if (notes.offUs[i] <= notes.onUs[i]) 30_000L else 0L)
            key[i] = k; vel[i] = (notes.vel[i].toInt() and 0x7F).coerceAtLeast(1); flg[i] = f
        }
        val order = (0 until n0).sortedWith(compareBy<Int>({ on[it] }, { key[it] }))
        val keep = BooleanArray(n0) { true }
        // 2. Merge folded collisions and serialise re-strikes, per key, in onset order.
        val lastOnKey = IntArray(128) { -1 }
        for (i in order) {
            val k = key[i]
            val p = lastOnKey[k]
            if (p >= 0 && off[p] > on[i]) {
                if (flg[i] and Performance.F_FOLDED != 0 && on[i] - on[p] <= 20_000L) {
                    vel[p] = maxOf(vel[p], vel[i]); off[p] = maxOf(off[p], off[i]); keep[i] = false; continue
                }
                off[p] = maxOf(on[i] - 2_000L, on[p] + 20_000L)
                if (off[p] > on[i]) { on[i] = off[p] + 1; if (off[i] <= on[i]) off[i] = on[i] + 30_000L }
                flg[i] = flg[i] or Performance.F_RESTRIKE
            }
            lastOnKey[k] = i
        }
        // 3. Harpsichord legato hold from CC64, then drop the curve.
        var fingerPedalled = false
        val sus = if (profile.legatoHold && !sus0.isEmpty) {
            val nextOnSameKey = LongArray(n0) { Long.MAX_VALUE }
            val lastSeen = IntArray(128) { -1 }
            for (i in order) { if (!keep[i]) continue; val p = lastSeen[key[i]]; if (p >= 0) nextOnSameKey[p] = on[i]; lastSeen[key[i]] = i }
            for (i in 0 until n0) {
                if (!keep[i] || sus0.valueAt(off[i]) < 0.5f) continue
                val release = sus0.nextCrossing(off[i], 0.5f, rising = false)
                val target = minOf(release, off[i] + 1_500_000L, nextOnSameKey[i] - 2_000L)
                if (target > off[i]) { off[i] = target; fingerPedalled = true }
            }
            PedalCurve.EMPTY
        } else if (profile.usesSustain) sus0 else PedalCurve.EMPTY

        val idx = order.filter { keep[it] }.sortedWith(compareBy<Int>({ on[it] }, { key[it] }))
        val n = idx.size
        val onUs = LongArray(n) { on[idx[it]] }; val offUs = LongArray(n) { off[idx[it]] }
        val keyB = ByteArray(n) { key[idx[it]].toByte() }; val velB = ByteArray(n) { vel[idx[it]].toByte() }
        val flags = ByteArray(n) { flg[idx[it]].toByte() }

        // 4. CSR per key.
        val keyFirst = IntArray(129)
        for (i in 0 until n) keyFirst[keyB[i].toInt() + 1]++
        for (k in 0 until 128) keyFirst[k + 1] += keyFirst[k]
        val fill = keyFirst.copyOf()
        val keyNotes = IntArray(n)
        for (i in 0 until n) keyNotes[fill[keyB[i].toInt()]++] = i

        // 5. Latches.
        val latchUs = ArrayList<Long>(); val latchLo = ArrayList<Long>(); val latchHi = ArrayList<Long>()
        if (!sos.isEmpty) {
            var t = Long.MIN_VALUE
            var rising = true
            while (true) {
                val c = sos.nextCrossing(if (t == Long.MIN_VALUE) 0L else t + 1, 0.5f, rising)
                if (c == Long.MAX_VALUE) break
                var lo = 0L; var hi = 0L
                if (rising) {
                    val lag = (profile.damperLagMs * 1000f).toLong()
                    for (i in 0 until n) if (onUs[i] <= c && c < offUs[i] + lag) {
                        val k = keyB[i].toInt(); if (k < 64) lo = lo or (1L shl k) else hi = hi or (1L shl (k - 64))
                    }
                    if (sus.valueAt(c) >= PedalMotion.CLEAR) for (k in 0..minOf(127, profile.lastDamper)) {
                        if (k < 64) lo = lo or (1L shl k) else hi = hi or (1L shl (k - 64))
                    }
                }
                latchUs.add(c); latchLo.add(lo); latchHi.add(hi)
                t = c; rising = !rising
            }
        }

        // 6. Events.
        val lastUp = if (n == 0) pre else offUs.maxOrNull()!!
        val evT = ArrayList<Long>(); val evE = ArrayList<Int>()
        for (i in 0 until n) {
            evT.add(onUs[i]); evE.add(Performance.pack(Performance.EV_NOTE_ON, i))
            evT.add(offUs[i]); evE.add(Performance.pack(Performance.EV_KEY_UP, i))
        }
        for (j in latchUs.indices) { evT.add(latchUs[j]); evE.add(Performance.pack(Performance.EV_LATCH, j)) }
        var lastNoise = Long.MIN_VALUE / 2
        var sustainEvents = 0
        if (!sus.isEmpty) {
            var t = 0L
            var rising = true
            while (true) {
                val c = sus.nextCrossing(t, PedalMotion.LIFT_START, rising)
                if (c == Long.MAX_VALUE) break
                sustainEvents++
                if (c - lastNoise >= (PedalMotion.NOISE_MIN_SPACING_MS * 1000f).toLong()) {
                    val arg = (if (rising) 1 else 0) or (speedClass(sus, c) shl 1)
                    evT.add(c); evE.add(Performance.pack(Performance.EV_PEDAL_NOISE, arg))
                    lastNoise = c
                }
                t = c + 1; rising = !rising
            }
        }
        evT.add(lastUp); evE.add(Performance.pack(Performance.EV_END, 0))
        val evOrder = evT.indices.sortedWith(compareBy<Int>({ evT[it] }, { Performance.type(evE[it]) }, { Performance.arg(evE[it]) }))
        val evUs = LongArray(evOrder.size) { evT[evOrder[it]] }
        val ev = IntArray(evOrder.size) { evE[evOrder[it]] }

        // 7. Bars, info.
        val durationUs = lastUp + 1_500_000L
        val barCount = ((durationUs - pre) / 2_000_000L + 1).toInt().coerceAtLeast(1)
        val barUs = LongArray(barCount) { pre + 2_000_000L * it }
        var maxPoly = 0
        run {
            val t = LongArray(2 * n); for (i in 0 until n) { t[2 * i] = onUs[i] * 2 + 1; t[2 * i + 1] = offUs[i] * 2 }
            t.sort(); var cur = 0
            for (x in t) { if (x and 1L == 1L) { cur++; if (cur > maxPoly) maxPoly = cur } else cur-- }
        }
        val warnings = ArrayList<PerfWarning>()
        if (folded > 0) warnings.add(PerfWarning.FOLDED)
        if (fingerPedalled) warnings.add(PerfWarning.FINGER_PEDALLED)
        val info = PerfInfo(title = title,
            lowKey = if (n == 0) 0 else keyB.minOf { it.toInt() }, highKey = if (n == 0) 0 else keyB.maxOf { it.toInt() },
            noteCount = n, maxPolyphony = maxPoly, pedalMode = modeOf(sus), sustainEvents = sustainEvents,
            softEvents = changes(sft), sostenutoEvents = latchUs.size, folded = folded, mergedChannels = 0,
            droppedDrumNotes = 0, fingerPedalled = fingerPedalled, voiceDemandP99 = maxPoly, voiceDemandMax = maxPoly,
            warnings = warnings)
        return Performance(id = id, generation = generation, instrument = profile.id, lastDamper = profile.lastDamper,
            durationUs = durationUs, onUs = onUs, offUs = offUs, key = keyB, vel = velB, flags = flags,
            keyFirst = keyFirst, keyNotes = keyNotes, sustain = sus, soft = sft, sostenuto = sos,
            latchUs = latchUs.toLongArray(), latchLo = latchLo.toLongArray(), latchHi = latchHi.toLongArray(),
            evUs = evUs, ev = ev, barUs = barUs, info = info)
    }

    private fun shift(c: PedalCurve, d: Long): PedalCurve =
        if (c.isEmpty) PedalCurve.EMPTY else PedalCurve(LongArray(c.us.size) { c.us[it] + d }, c.v.copyOf())

    /** Speed class 0..3 from the slope at t (full travel time < 50 / 100 / 300 ms → 3 / 2 / 1); a step → 2. */
    private fun speedClass(c: PedalCurve, t: Long): Int {
        val i = c.us.indexOfLast { it <= t }.coerceAtLeast(0)
        val j = (i + 1).coerceAtMost(c.us.size - 1)
        val dt = c.us[j] - c.us[maxOf(0, i)]
        val dv = kotlin.math.abs(c.v[j] - c.v[maxOf(0, i)])
        if (dt <= 0L || dv <= 0f) return 2
        val fullTravelMs = dt / 1000f / dv
        return when { fullTravelMs < 50f -> 3; fullTravelMs < 100f -> 2; fullTravelMs < 300f -> 1; else -> 0 }
    }

    private fun modeOf(c: PedalCurve): PedalMode {
        if (c.isEmpty) return PedalMode.NONE
        val distinct = c.v.filter { it > 0f && it < 1f }.toSet().size
        return if (distinct >= 8) PedalMode.CONTINUOUS else PedalMode.SWITCH
    }

    private fun changes(c: PedalCurve): Int {
        var k = 0
        for (i in 1 until c.v.size) if (c.v[i] != c.v[i - 1]) k++
        return k + if (c.v.isNotEmpty() && c.v[0] != 0f) 1 else 0
    }
}
