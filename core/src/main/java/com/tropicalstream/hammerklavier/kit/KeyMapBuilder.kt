package com.tropicalstream.hammerklavier.kit

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.KeyMap
import com.tropicalstream.hammerklavier.contract.TuningSpec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * (index, tuning, readyMask) → [KeyMap] (PLAN §3.5). Pure; every `pow` runs here, once per tuning
 * or readiness change, on HKLoader.
 *
 * - **Pitch:** `targetCents(k) = 100·k + c(k) + stretchCents[k]` (for a 4′ stop the sounding key
 *   k + 12); the region of the (stop, layer) whose `nativeCents` is nearest wins (ties → smaller
 *   |k − root|, then the lower root); `rate = 2^((target − native)/1200)`. Where a stop's own best
 *   shift exceeds one semitone, the `borrowable` regions of the other stop compete too; a borrowed
 *   region carries its seam gain and low-pass.
 * - **Velocity:** HARD maps each velocity to its layer; XFADE blends the two layers within
 *   ±`xfadeSteps` of each split by the boundary's `xfadeLaw` (equal gain or equal power). Both
 *   weights are trimmed by the level curve, `target(v) − loudness(layer)`.
 * - **Readiness:** a layer whose unit is not in [readyMask] is replaced by the nearest ready layer
 *   by |velRef difference| (ties → louder) with its trim clamped to ±6 dB; a 4′ unit not ready →
 *   no 4′ region; releases need unit 62 and pedals unit 63. No region is ever unready.
 */
object KeyMapBuilder {
    /** Beyond the outer layer centres the target level climbs 0.39 dB per velocity step. */
    const val OUTER_SLOPE_DB = 0.39f
    /** A normalised pack (spread < 12 dB) follows [OUTER_SLOPE_DB] everywhere. */
    const val MIN_SPREAD_DB = 12f
    const val FALLBACK_CLAMP_DB = 6f
    /** Own shift above this (cents) lets borrowable regions compete. */
    const val BORROW_ABOVE_CENTS = 100f

    fun build(index: KitIndex, tuning: TuningSpec, readyMask: Long): KeyMap {
        val layers = index.layerCount
        val stops = index.stopCount
        fun ready(unit: Int) = unit in 0..63 && (readyMask ushr unit) and 1L == 1L
        val layerReady = BooleanArray(layers) { ready(index.unitOf(0, it)) }

        // ---- velocity → layers and weights (stop 0's level curve) ----
        val curve = index.levelCurve.getOrNull(0) ?: emptyList()
        val loud = FloatArray(layers) { l -> curve.getOrNull(l)?.db ?: 0f }
        val velLayerA = ByteArray(128) { -1 }; val velLayerB = ByteArray(128) { -1 }
        val velGainA = FloatArray(128); val velGainB = FloatArray(128)
        val sub = IntArray(layers) { substitute(index, layerReady, it) }
        for (v in 0 until 128) {
            val vv = v.coerceIn(1, 127)
            val target = targetDb(index, curve, vv.toFloat())
            val j = layerOfVel(index, vv)
            var a = j; var b = -1; var wb = 0f; var law = "gain"
            val h = index.xfadeSteps
            if (!index.isHard && h > 0 && layers > 1) {
                // Boundary between layer i and i + 1 sits at velHi_i + 0.5; the zone spans ±h.
                if (j + 1 < layers && vv > index.layers[j].velHi + 0.5f - h) {
                    b = j + 1; wb = (vv - (index.layers[j].velHi + 0.5f - h)) / (2f * h)
                    law = index.xfadeLaw.getOrElse(j) { "gain" }
                } else if (j > 0 && vv < index.layers[j - 1].velHi + 0.5f + h) {
                    a = j - 1; b = j; wb = (vv - (index.layers[j - 1].velHi + 0.5f - h)) / (2f * h)
                    law = index.xfadeLaw.getOrElse(j - 1) { "gain" }
                }
            }
            wb = wb.coerceIn(0f, 1f)
            val (wA, wB) = if (law == "power") Pair(cos(wb * PI / 2).toFloat(), sin(wb * PI / 2).toFloat()) else Pair(1f - wb, wb)
            val ra = sub[a]; val rb = if (b >= 0) sub[b] else -1
            if (ra < 0) continue                                    // nothing ready: no layer
            if (rb < 0 || rb == ra) {
                velLayerA[v] = ra.toByte()
                velGainA[v] = trim(target, loud[ra], ra != a || (b >= 0 && rb != b))
            } else {
                velLayerA[v] = ra.toByte(); velLayerB[v] = rb.toByte()
                velGainA[v] = wA * trim(target, loud[ra], ra != a)
                velGainB[v] = wB * trim(target, loud[rb], rb != b)
            }
        }

        // ---- per (stop, layer, key) regions ----
        val n = stops * layers * HK.KEYS
        val region = IntArray(n) { -1 }; val rate = FloatArray(n) { 1f }; val gain = FloatArray(n)
        val onsetOut = IntArray(n); val lpHz = FloatArray(n)
        val sustains = index.regions.filter { it.kind == RegionKind.SUSTAIN }
        for (stop in 0 until stops) {
            val octave = index.stops[stop].octaveSemis
            for (layer in 0 until layers) {
                if (!ready(index.unitOf(stop, layer))) continue
                val own = sustains.filter { it.stop == stop && it.layer == layer && ready(it.unit) }
                if (own.isEmpty()) continue
                val borrow = sustains.filter { it.stop != stop && it.borrowable && ready(it.unit) &&
                    (it.layer == layer || index.layerCount == 1) }
                val lo = own.minOf { it.lo }; val hi = own.maxOf { it.hi }
                for (k in lo..hi) {
                    val sounding = (k + octave).coerceIn(0, 127)
                    val target = targetCents(index, tuning, k, octave)
                    var best = nearest(own, target, k)
                    var borrowed = false
                    if (abs(target - best.nativeCents) > BORROW_ABOVE_CENTS && borrow.isNotEmpty()) {
                        val alt = nearest(borrow, target, sounding - index.stops[borrowStop(index, stop)].octaveSemis)
                        if (abs(target - alt.nativeCents) < abs(target - best.nativeCents)) { best = alt; borrowed = true }
                    }
                    val sk = (stop * layers + layer) * HK.KEYS + k
                    val r = 2.0.pow((target - best.nativeCents) / 1200.0)
                    region[sk] = best.id
                    rate[sk] = r.toFloat()
                    var g = db(best.gainDb)
                    if (borrowed) { g *= db(best.seamGainDb); lpHz[sk] = best.seamLpHz }
                    gain[sk] = g
                    onsetOut[sk] = (best.onsetFrame / r).roundToInt()
                }
            }
        }

        // ---- releases per (stop, key) ----
        val release = IntArray(stops * HK.KEYS) { -1 }; val releaseRate = FloatArray(stops * HK.KEYS) { 1f }
        val releaseGain = FloatArray(stops * HK.KEYS)
        // releaseRule.relGainDb: the kit's release level under its notes (the grand's Salamander rel<n>
        // are key-release noises recorded near note level; the SFZ plays them at volume=-37).
        val relTrim = index.releaseRule.relGainDb
        // VCSL kits (attackRelDb on every release): releaseGain is relative to the released note's attack,
        // so that the release's loudest 10 ms block lands attackRelDb under it (the SFZ's release volume).
        val allRel = index.regions.filter { it.kind == RegionKind.RELEASE }
        val attackRel = allRel.isNotEmpty() && allRel.all { !it.attackRelDb.isNaN() }
        if (ready(KitIndex.UNIT_RELEASES)) for (stop in 0 until stops) {
            val octave = index.stops[stop].octaveSemis
            val rel = index.regions.filter { it.kind == RegionKind.RELEASE && it.stop == stop }
            if (rel.isEmpty()) continue
            val exact = rel.filter { it.lo == it.hi }
            val lo = rel.minOf { it.lo }
            val hi = rel.maxOf { it.hi }
            for (k in lo..hi) {
                val target = targetCents(index, tuning, k, octave)
                // Exact per-key releases (the grand's rel<n>) are used for their own key.
                val own = exact.firstOrNull { it.lo == k }
                val best = own ?: nearest(rel, target, k)
                val r = 2.0.pow((target - best.nativeCents) / 1200.0)
                val i = stop * HK.KEYS + k
                release[i] = best.id; releaseRate[i] = r.toFloat(); releaseGain[i] =
                    if (attackRel) db(best.attackRelDb - envMaxDb(index, best)) else db(best.gainDb + relTrim)
            }
        }

        // ---- pedal noises (round robin by rr) ----
        val pedalsReady = ready(KitIndex.UNIT_PEDALS)
        val pedalDown = if (pedalsReady) index.regions.filter { it.kind == RegionKind.PEDAL_DOWN }.sortedBy { it.rr }.map { it.id }.toIntArray() else IntArray(0)
        val pedalUp = if (pedalsReady) index.regions.filter { it.kind == RegionKind.PEDAL_UP }.sortedBy { it.rr }.map { it.id }.toIntArray() else IntArray(0)

        val f0 = FloatArray(HK.KEYS) { k -> (440.0 * 2.0.pow((targetCents(index, tuning, k, 0) - 6900.0) / 1200.0)).toFloat() }
        val id = InstrumentId.of(index.instrument)
        val strings = ByteArray(HK.KEYS) { k ->
            (if (id != null) InstrumentProfile.of(id).stringsPerKey(k) else if (stops == 2 || k <= 28) 1 else if (k <= 48) 2 else 3).toByte()
        }
        return KeyMap(
            tuning = tuning, readyMask = readyMask, layers = layers, stops = stops,
            velLayerA = velLayerA, velLayerB = velLayerB, velGainA = velGainA, velGainB = velGainB,
            region = region, rate = rate, gain = gain, onsetOut = onsetOut, lpHz = lpHz,
            release = release, releaseRate = releaseRate, releaseGain = releaseGain,
            pedalDown = pedalDown, pedalUp = pedalUp, pedalGain = db(index.pedalGainDb),
            f0Hz = f0, inharmB = index.inharmB.copyOf(), strings = strings, releaseAttackRel = attackRel)
    }

    /** The loudest 10 ms env block of [r] in dBFS (env byte b = −b/2 dB). */
    private fun envMaxDb(index: KitIndex, r: RegionDef): Float {
        var m = 255
        for (t in 0 until r.envCount) { val b = index.envByte(r.id, t); if (b < m) m = b }
        return -m / 2f
    }

    /** `100·k + c(k) + stretch[k]` for the sounding key k + octave (cents re A440 ET). */
    fun targetCents(index: KitIndex, tuning: TuningSpec, key: Int, octaveSemis: Int = 0): Float {
        val s = (key + octaveSemis).coerceIn(0, 127)
        return 100f * (key + octaveSemis) + tuning.keyCents(s) + index.stretchCents[s]
    }

    /** Layer j whose split contains v. */
    fun layerOfVel(index: KitIndex, v: Int): Int {
        for (l in index.layers) if (v in l.velLo..l.velHi) return l.index
        return if (v < 1) 0 else index.layerCount - 1
    }

    /**
     * Target level (dB) at velocity v: piecewise linear through the layer centres (velRef, db),
     * extended at 0.39 dB/step; a pack with < 12 dB spread follows 0.39 dB/step from the lowest
     * centre throughout.
     */
    fun targetDb(index: KitIndex, curve: List<LevelPoint>, v: Float): Float {
        if (curve.isEmpty()) return 0f
        val first = curve.first(); val last = curve.last()
        if (curve.size == 1 || last.db - first.db < MIN_SPREAD_DB && index.layerCount > 1)
            return first.db + OUTER_SLOPE_DB * (v - first.vel)
        if (v <= first.vel) return first.db + OUTER_SLOPE_DB * (v - first.vel)
        if (v >= last.vel) return last.db + OUTER_SLOPE_DB * (v - last.vel)
        for (i in 1 until curve.size) {
            val a = curve[i - 1]; val b = curve[i]
            if (v <= b.vel) return a.db + (b.db - a.db) * (v - a.vel) / (b.vel - a.vel)
        }
        return last.db
    }

    /** Linear trim target − loudness, clamped to ±6 dB for a substituted layer. */
    private fun trim(target: Float, loudness: Float, substituted: Boolean): Float {
        var d = target - loudness
        if (substituted) d = d.coerceIn(-FALLBACK_CLAMP_DB, FALLBACK_CLAMP_DB)
        return db(d)
    }

    /** Nearest ready layer by |velRef difference|, ties → louder; -1 if none. */
    private fun substitute(index: KitIndex, ready: BooleanArray, layer: Int): Int {
        if (ready[layer]) return layer
        var best = -1; var bestD = Int.MAX_VALUE
        val ref = index.layers[layer].velRef
        for (l in index.layers) {
            if (!ready[l.index]) continue
            val d = abs(l.velRef - ref)
            if (d < bestD || d == bestD && l.velRef > index.layers[best].velRef) { best = l.index; bestD = d }
        }
        return best
    }

    private fun borrowStop(index: KitIndex, stop: Int): Int = if (index.stopCount > 1) 1 - stop else stop

    private fun nearest(cands: List<RegionDef>, target: Float, key: Int): RegionDef {
        var best = cands[0]
        var bd = abs(target - best.nativeCents)
        for (i in 1 until cands.size) {
            val r = cands[i]
            val d = abs(target - r.nativeCents)
            val better = when {
                d < bd - 1e-4f -> true
                d > bd + 1e-4f -> false
                abs(key - r.root) != abs(key - best.root) -> abs(key - r.root) < abs(key - best.root)
                else -> r.root < best.root
            }
            if (better) { best = r; bd = d }
        }
        return best
    }

    private fun db(d: Float): Float = 10.0.pow(d / 20.0).toFloat()
}
