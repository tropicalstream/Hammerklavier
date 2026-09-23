package com.tropicalstream.hammerklavier.midi

import com.tropicalstream.hammerklavier.contract.CompileOptions
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.PedalCurve
import com.tropicalstream.hammerklavier.contract.PedalMode
import com.tropicalstream.hammerklavier.contract.PedalMotion
import com.tropicalstream.hammerklavier.contract.PerfInfo
import com.tropicalstream.hammerklavier.contract.PerfWarning
import com.tropicalstream.hammerklavier.contract.Performance

/**
 * Builds an immutable [Performance] from merged channel events (PLAN §4.3), once per (file,
 * instrument), on HKLoader. Steps: pair notes → fold → serialise re-strikes (and merge folded
 * collisions) → shape pedals → instrument policy (legato hold, pedal removal, flat velocity) →
 * sostenuto latches → events → CSR, bars, [PerfInfo] with the [VoiceDemand] figures.
 */
object PerformanceBuilder {
    const val TAIL_US = 1_500_000L
    /** Channel-10-only files whose median note is shorter than this are drum parts. */
    const val DRUM_MEDIAN_US = 100_000L

    /** What the builder needs besides the events. [barsFileUs] gives bar starts in file µs up to a limit. */
    class Input(val events: MergedEvents, val barsFileUs: (Long) -> LongArray, val title: String?,
                val warnings: Set<PerfWarning>)

    /** Raw facts the catalogue measures (before folding; pedals as the file has them). */
    class RawFacts(val lowKey: Int, val highKey: Int, val noteCount: Int, val hasSustain: Boolean, val hasSoft: Boolean,
                   val hasSostenuto: Boolean, val sustainMode: PedalMode, val channels: Int)

    sealed class Result {
        class Ok(val perf: Performance, val raw: RawFacts) : Result()
        class Failed(val error: SmfError, val detail: String) : Result()
    }

    fun build(input: Input, id: String, generation: Int, profile: InstrumentProfile, opts: CompileOptions = CompileOptions()): Result {
        val ev = input.events
        val warnings = LinkedHashSet<PerfWarning>(input.warnings)
        val pre = HK.PRE_ROLL_US

        // 3. Pair notes.
        val paired = NotePairing.pair(ev)
        val notes = paired.notes
        if (notes.size == 0) return Result.Failed(SmfError.NO_KEYBOARD_NOTES, "no notes")
        if (ev.onlyDrumChannel && looksLikeDrums(notes)) return Result.Failed(SmfError.NO_KEYBOARD_NOTES, "only General MIDI drums")
        if (paired.hanging > 0) warnings.add(PerfWarning.HANGING_NOTES)
        if (ev.droppedDrumNotes > 0) warnings.add(PerfWarning.DRUMS_DROPPED)
        if (ev.mergedChannels > 1) warnings.add(PerfWarning.CHANNELS_MERGED)
        var rawLo = 127; var rawHi = 0
        for (i in 0 until notes.size) { rawLo = minOf(rawLo, notes.key[i]); rawHi = maxOf(rawHi, notes.key[i]) }

        // 4. Range; then one keyboard.
        val folded = InstrumentAdapter.fold(notes, profile)
        NotePairing.serialise(notes)

        // 2. Pedals.
        val (s64t, s64v) = PedalShaper.steps(ev, PedalShaper.SUSTAIN)
        val (s66t, s66v) = PedalShaper.steps(ev, PedalShaper.SOSTENUTO)
        val (s67t, s67v) = PedalShaper.steps(ev, PedalShaper.SOFT)
        val sus0 = PedalShaper.shape(s64t, s64v, PedalShaper.SUSTAIN)
        val sos0 = PedalShaper.shape(s66t, s66v, PedalShaper.SOSTENUTO)
        val soft0 = PedalShaper.shape(s67t, s67v, PedalShaper.SOFT)
        val raw = RawFacts(rawLo, rawHi, notes.size, hasSustain = PedalShaper.crossings(sus0.curve, PedalMotion.LIFT_START) > 0,
            hasSoft = PedalShaper.crossings(soft0.curve, 0.5f) > 0, hasSostenuto = PedalShaper.crossings(sos0.curve, 0.5f) > 0,
            sustainMode = sus0.mode, channels = ev.mergedChannels)

        // 5. Instrument policy.
        var fingerPedalled = false
        val sustain: PedalCurve = if (profile.legatoHold) {
            if (opts.legatoHold) fingerPedalled = InstrumentAdapter.legatoHold(notes, InstrumentAdapter.rawCurve(s64t, s64v), opts.legatoCapMs)
            PedalCurve.EMPTY
        } else if (profile.usesSustain) sus0.curve else PedalCurve.EMPTY
        val sostenuto = if (profile.usesSostenuto) sos0.curve else PedalCurve.EMPTY
        val soft = if (InstrumentAdapter.usesSoft(profile)) soft0.curve else PedalCurve.EMPTY
        if (folded > 0) warnings.add(PerfWarning.FOLDED)
        if (fingerPedalled) warnings.add(PerfWarning.FINGER_PEDALLED)

        // Final note arrays, sorted by (onUs, key).
        val idx = notes.order()
        val n = idx.size
        val onUs = LongArray(n) { notes.on[idx[it]] }
        val offUs = LongArray(n) { notes.off[idx[it]] }
        val key = ByteArray(n) { notes.key[idx[it]].toByte() }
        val vel = ByteArray(n) { InstrumentAdapter.velocity(notes.vel[idx[it]], profile, opts).toByte() }
        val flags = ByteArray(n) { notes.flags[idx[it]].toByte() }

        // 8. CSR.
        val keyFirst = IntArray(129)
        for (i in 0 until n) keyFirst[key[i].toInt() + 1]++
        for (k in 0 until 128) keyFirst[k + 1] += keyFirst[k]
        val fill = keyFirst.copyOf()
        val keyNotes = IntArray(n)
        for (i in 0 until n) keyNotes[fill[key[i].toInt()]++] = i

        // 6. Sostenuto latches at the 0.5 crossings.
        val lt = ArrayList<Long>(); val llo = ArrayList<Long>(); val lhi = ArrayList<Long>()
        if (!sostenuto.isEmpty) {
            val lag = (profile.damperLagMs * 1000f).toLong()
            var from = 0L
            var rising = true
            while (true) {
                val c = sostenuto.nextCrossing(from, 0.5f, rising)
                if (c == Long.MAX_VALUE) break
                var lo = 0L; var hi = 0L
                if (rising) {
                    for (i in 0 until n) {
                        if (onUs[i] > c) break
                        if (c < offUs[i] + lag) { val k = key[i].toInt(); if (k < 64) lo = lo or (1L shl k) else hi = hi or (1L shl (k - 64)) }
                    }
                    if (sustain.valueAt(c) >= PedalMotion.CLEAR) for (k in 0..minOf(127, profile.lastDamper)) {
                        if (k < 64) lo = lo or (1L shl k) else hi = hi or (1L shl (k - 64))
                    }
                }
                lt.add(c); llo.add(lo); lhi.add(hi)
                from = c + 1; rising = !rising
            }
        }
        val latchUs = lt.toLongArray(); val latchLo = llo.toLongArray(); val latchHi = lhi.toLongArray()

        // 7. Events, sorted by (evUs, type, arg).
        var lastUp = pre
        for (i in 0 until n) if (offUs[i] > lastUp) lastUp = offUs[i]
        val (noiseT, noiseA) = PedalShaper.noises(sustain, sus0.mode)
        val m = 2 * n + latchUs.size + noiseT.size + 1
        val evT = LongArray(m); val evE = IntArray(m)
        var o = 0
        for (i in 0 until n) {
            evT[o] = onUs[i]; evE[o++] = Performance.pack(Performance.EV_NOTE_ON, i)
            evT[o] = offUs[i]; evE[o++] = Performance.pack(Performance.EV_KEY_UP, i)
        }
        for (j in latchUs.indices) { evT[o] = latchUs[j]; evE[o++] = Performance.pack(Performance.EV_LATCH, j) }
        for (j in noiseT.indices) { evT[o] = noiseT[j]; evE[o++] = Performance.pack(Performance.EV_PEDAL_NOISE, noiseA[j]) }
        evT[o] = lastUp; evE[o++] = Performance.pack(Performance.EV_END, 0)
        val eo = IdxSort.sort(IntArray(m) { it }) { a, b ->
            val c = evT[a].compareTo(evT[b])
            if (c != 0) c else {
                val ta = Performance.type(evE[a]); val tb = Performance.type(evE[b])
                if (ta != tb) ta.compareTo(tb) else Performance.arg(evE[a]).compareTo(Performance.arg(evE[b]))
            }
        }
        val evUs = LongArray(m) { evT[eo[it]] }
        val evs = IntArray(m) { evE[eo[it]] }

        // Bars, info.
        val durationUs = lastUp + TAIL_US
        val fileBars = input.barsFileUs(durationUs - pre)
        val barUs = LongArray(fileBars.size) { fileBars[it] + pre }
        var maxPoly = 0
        run {
            val t = LongArray(2 * n)
            for (i in 0 until n) { t[2 * i] = onUs[i] * 2 + 1; t[2 * i + 1] = offUs[i] * 2 }
            t.sort()
            var cur = 0
            for (x in t) { if (x and 1L == 1L) { cur++; if (cur > maxPoly) maxPoly = cur } else cur-- }
        }
        val demand = VoiceDemand.simulate(onUs, offUs, key, vel, keyFirst, keyNotes, sustain, latchUs, latchLo, latchHi, profile)
        var lo = 127; var hi = 0
        for (i in 0 until n) { lo = minOf(lo, key[i].toInt()); hi = maxOf(hi, key[i].toInt()) }
        val info = PerfInfo(title = input.title, lowKey = lo, highKey = hi, noteCount = n, maxPolyphony = maxPoly,
            pedalMode = sus0.mode, sustainEvents = PedalShaper.crossings(sus0.curve, PedalMotion.LIFT_START),
            softEvents = PedalShaper.crossings(soft, 0.5f), sostenutoEvents = latchUs.size, folded = folded,
            mergedChannels = ev.mergedChannels, droppedDrumNotes = ev.droppedDrumNotes, fingerPedalled = fingerPedalled,
            voiceDemandP99 = demand.p99, voiceDemandMax = demand.max, warnings = warnings.toList())
        val perf = Performance(id = id, generation = generation, instrument = profile.id, lastDamper = profile.lastDamper,
            durationUs = durationUs, onUs = onUs, offUs = offUs, key = key, vel = vel, flags = flags,
            keyFirst = keyFirst, keyNotes = keyNotes, sustain = sustain, soft = soft, sostenuto = sostenuto,
            latchUs = latchUs, latchLo = latchLo, latchHi = latchHi, evUs = evUs, ev = evs, barUs = barUs, info = info)
        return Result.Ok(perf, raw)
    }

    private fun looksLikeDrums(notes: NoteList): Boolean {
        val d = LongArray(notes.size) { notes.off[it] - notes.on[it] }
        d.sort()
        return d[d.size / 2] < DRUM_MEDIAN_US
    }
}
