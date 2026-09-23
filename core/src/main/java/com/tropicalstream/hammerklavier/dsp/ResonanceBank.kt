package com.tropicalstream.hammerklavier.dsp

import com.tropicalstream.hammerklavier.contract.BankInfo
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.KeyMap
import com.tropicalstream.hammerklavier.contract.ResonanceMode
import com.tropicalstream.hammerklavier.contract.ResonanceProcessor
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Prepared comb tables (PLAN §3.11), built off the audio thread by [ResonanceBank.prepare] and
 * swapped in whole by [ResonanceBank.apply]. Immutable after construction. Variant 0 = with
 * dispersion (keys ≤ 59 with B > 0), variant 1 = without (Q2, or `dispersion = false`).
 * Every comb runs the same loop: M integer samples → fraction allpass η → two identical
 * dispersion allpasses (coefficient 0 = two unit delays, accounted for in the fit) → one-pole
 * low-pass → gain. M and η are fitted so that the loop's exact phase at the first partial
 * equals 2π (the comb peaks on f₁ = f0·√(1 + B)); the dispersion coefficient minimises the
 * cent errors of partials 2–10 against n·f0·√(1 + B·n²).
 */
class ResonanceTables internal constructor(
    @JvmField val instrument: InstrumentId,
    @JvmField val enabled: BooleanArray,
    @JvmField val multiString: BooleanArray,
    @JvmField val f0: FloatArray,
    @JvmField val b: FloatArray,
    @JvmField val m: Array<IntArray>,
    @JvmField val eta: Array<FloatArray>,
    @JvmField val ap: Array<FloatArray>,
    @JvmField val lp: Array<FloatArray>,
    @JvmField val gain: Array<FloatArray>,           // [variant][comb * 33 + round(32·D)]
) {
    /** Total nominal loop length M + d (d from η) of a comb, for glides. */
    fun loopLength(v: Int, c: Int): Float { val e = eta[v][c]; return m[v][c] + (1f - e) / (1f + e) }
}

/**
 * 88 comb string resonators (PLAN §3.11), keys 21–108 (the harpsichord 29–89). Comb k hears
 * `SEND·gate[k]·(mix − self[k]) + UNA_CORDA_SEND·softFeed[k]·self[k]` (una corda only on keys
 * with ≥ 2 strings) and rings at its string's partials; its output goes to one of 4 register pan
 * groups (−0.6, −0.2, +0.2, +0.6, constant power) added to the dry bus. Only combs with an open
 * gate, a soft feed or a last-block peak above −90 dBFS run, at most `maxActive` (held keys first,
 * then the loudest); an inactive comb's buffer is zeroed once. Four combs run per loop iteration
 * with their state in locals ([processFour]); [scalarReference] runs them one at a time
 * ([processOne]) for T3.1. All delay lines live in one packed FloatArray of power-of-two segments.
 * `process` and `energy` are allocation-free and transcendental-free.
 */
class ResonanceBank(sampleRate: Int = HK.SR) : ResonanceProcessor {
    private val fs = sampleRate
    private val fsD = sampleRate.toDouble()

    // ── Packed delay lines ──
    private val segOff = IntArray(COMBS)
    private val segMask = IntArray(COMBS)
    private val lines: FloatArray
    private var pos = 0

    init {
        var total = 0
        for (c in 0 until COMBS) {
            val key = FIRST_KEY + c
            // Room for A392 with a −100 cent stretch, plus a block of glide headroom.
            val f0min = 392.0 * 2.0.pow((key - 69) / 12.0) * 2.0.pow(-100.0 / 1200.0)
            val need = (fsD / f0min).toInt() + HK.BLOCK + 8
            var size = 256
            while (size < need) size = size shl 1
            segOff[c] = total; segMask[c] = size - 1; total += size
        }
        lines = FloatArray(total)
    }

    /** Floats in the packed delay array (88,576 = 346 KiB at 48 kHz). */
    val delayFloats: Int get() = lines.size

    // ── Per-comb run-time state ──
    private val cm = IntArray(COMBS)
    private val ceta = FloatArray(COMBS)
    private val cap = FloatArray(COMBS)
    private val clp = FloatArray(COMBS)
    private val cg = FloatArray(COMBS)
    private val sFx = FloatArray(COMBS); private val sFy = FloatArray(COMBS)
    private val sA1x = FloatArray(COMBS); private val sA1y = FloatArray(COMBS)
    private val sA2x = FloatArray(COMBS); private val sA2y = FloatArray(COMBS)
    private val sLp = FloatArray(COMBS)
    private val peak = FloatArray(COMBS)
    private val ms = FloatArray(COMBS)
    private val isActive = BooleanArray(COMBS)
    private val isZero = BooleanArray(COMBS) { true }
    private val runList = IntArray(COMBS)
    private val score = FloatArray(COMBS)
    private val candidate = BooleanArray(COMBS)
    private val cMix = FloatArray(COMBS)
    private val cSelf = FloatArray(COMBS)
    private val gTarget = FloatArray(COMBS)

    // glide
    private val glN0 = FloatArray(COMBS); private val glAp0 = FloatArray(COMBS); private val glLp0 = FloatArray(COMBS)
    private var glideLeft = 0
    private var glideTotal = 0

    private val group = FloatArray(4 * HK.BLOCK)
    private val panL = FloatArray(4); private val panR = FloatArray(4)

    private var tables: ResonanceTables? = null
    private var variant = 0
    private var send = 0f
    private var ucs = 0f
    private var maxActive = COMBS
    private var nActive = 0

    /** Test hook: run the one-comb-at-a-time reference kernel instead of the 4-way kernel. */
    @JvmField var scalarReference = false
    /** 4 or 2 combs per kernel iteration (M2 A55 measurement; results are identical within 1e-6). */
    @JvmField var kernelWidth = 2

    init {
        val pans = floatArrayOf(-0.6f, -0.2f, 0.2f, 0.6f)
        for (g in 0 until 4) {
            val phi = (pans[g] + 1.0) * PI / 4
            panL[g] = cos(phi).toFloat(); panR[g] = sin(phi).toFloat()
        }
    }

    override val active: Int get() = nActive

    override fun prepare(keyMap: KeyMap, info: BankInfo, profile: InstrumentProfile): Any =
        design(keyMap.f0Hz, keyMap.inharmB, info, profile, keyMap.strings)

    /** The table builder behind [prepare] (tests call it with synthetic f0 and B). */
    fun design(f0Hz: FloatArray, inharmB: FloatArray, info: BankInfo?, profile: InstrumentProfile, strings: ByteArray? = null): ResonanceTables {
        val enabled = BooleanArray(COMBS); val multi = BooleanArray(COMBS)
        val f0s = FloatArray(COMBS); val bs = FloatArray(COMBS)
        val mm = Array(2) { IntArray(COMBS) }; val et = Array(2) { FloatArray(COMBS) }
        val ap = Array(2) { FloatArray(COMBS) }; val lp = Array(2) { FloatArray(COMBS) }
        val gain = Array(2) { FloatArray(COMBS * D_STEPS) }
        for (c in 0 until COMBS) {
            val key = FIRST_KEY + c
            val f0 = f0Hz[key].toDouble()
            if (key < profile.lowKey || key > profile.highKey || !(f0 > 0.0)) continue
            val bb = inharmB[key].toDouble().coerceIn(0.0, 0.01)
            if (fsD / f0 + HK.BLOCK >= segMask[c]) continue
            enabled[c] = true
            multi[c] = (strings?.get(key)?.toInt() ?: profile.stringsPerKey(key)) >= 2 && profile.stringsPerKey(key) >= 2
            f0s[c] = f0.toFloat(); bs[c] = bb.toFloat()
            val tf = info?.freeT60?.let { if (it.size > key) it[key] else null } ?: profile.defaultFreeT60(0, key)
            val td = info?.damperT60?.let { if (it.size > key) it[key] else null } ?: profile.defaultDamperT60(key)
            for (v in 0..1) {
                val wantDisp = v == 0 && key <= DISPERSION_MAX_KEY && bb > 0.0
                var fc = (40.0 * f0).coerceIn(2500.0, 12000.0)
                if (v == 1 && bb > 0.0) fc = minOf(fc, sqrt(2.0 * (2.0.pow(5.0 / 1200.0) - 1.0) / bb) * f0)
                val a = exp(-2 * PI * fc / fsD)
                val fit = if (wantDisp) fitDispersion(f0, bb, a) else fitLoop(f0 * sqrt(1 + bb), 0.0, a)
                mm[v][c] = fit.m; et[v][c] = fit.eta.toFloat(); ap[v][c] = fit.ap.toFloat(); lp[v][c] = a.toFloat()
                // Loop gain per D step: the round-trip loss for T60, compensated for the low-pass at f1.
                val f1 = f0 * sqrt(1 + bb)
                val n = fsD / f1
                val lpMag = lpMag(a, 2 * PI * f1 / fsD)
                for (j in 0 until D_STEPS) {
                    val d = j / 32.0
                    val t60 = (0.8 * tf).pow(1 - d) * (0.5 * td).pow(d)
                    val g = 10.0.pow(-3.0 * n / (fsD * t60.coerceAtLeast(0.01))) / lpMag
                    gain[v][c * D_STEPS + j] = minOf(0.9995, g).toFloat()
                }
            }
        }
        return ResonanceTables(profile.id, enabled, multi, f0s, bs, mm, et, ap, lp, gain)
    }

    override fun apply(prepared: Any, glideMs: Int) {
        val t = prepared as ResonanceTables
        val old = tables
        tables = t
        retarget(if (old == null) 0 else glideMs, old)
    }

    override fun setMode(mode: ResonanceMode, instrument: InstrumentId, maxActive: Int, dispersion: Boolean) {
        send = DspTables.send(instrument, mode)
        ucs = if (mode == ResonanceMode.OFF || instrument != InstrumentId.GRAND) 0f else DspTables.UNA_CORDA_SEND
        this.maxActive = maxActive.coerceIn(0, COMBS)
        val v = if (dispersion) 0 else 1
        if (v != variant) { variant = v; val t = tables; if (t != null) retarget(DEFAULT_GLIDE_MS, t) }
    }

    /** Starts a glide of every enabled comb from its current loop toward the current tables' variant. */
    private fun retarget(glideMs: Int, old: ResonanceTables?) {
        val t = tables ?: return
        val v = variant
        val blocks = ((glideMs.toLong() * fs / 1000 + HK.BLOCK - 1) / HK.BLOCK).toInt()
        for (c in 0 until COMBS) {
            if (!t.enabled[c]) { deactivate(c); continue }
            val wasOn = old != null && old.enabled[c]
            if (blocks > 0 && wasOn) {
                val e = ceta[c]
                glN0[c] = cm[c] + (1f - e) / (1f + e); glAp0[c] = cap[c]; glLp0[c] = clp[c]
            } else {
                cm[c] = t.m[v][c]; ceta[c] = t.eta[v][c]; cap[c] = t.ap[v][c]; clp[c] = t.lp[v][c]
                glN0[c] = t.loopLength(v, c); glAp0[c] = cap[c]; glLp0[c] = clp[c]
            }
        }
        glideTotal = blocks; glideLeft = blocks
    }

    private fun stepGlide() {
        val t = tables ?: return
        val v = variant
        glideLeft--
        if (glideLeft <= 0) {
            glideLeft = 0
            for (c in 0 until COMBS) if (t.enabled[c]) { cm[c] = t.m[v][c]; ceta[c] = t.eta[v][c]; cap[c] = t.ap[v][c]; clp[c] = t.lp[v][c] }
            return
        }
        val x = 1f - glideLeft.toFloat() / glideTotal
        for (c in 0 until COMBS) {
            if (!t.enabled[c]) continue
            val nT = t.loopLength(v, c)
            val nn = glN0[c] + (nT - glN0[c]) * x
            var mi = floor(nn - 0.5f).toInt()
            if (mi < 1) mi = 1
            val lim = segMask[c] - HK.BLOCK
            if (mi > lim) mi = lim
            val d = nn - mi
            cm[c] = mi; ceta[c] = (1f - d) / (1f + d)
            cap[c] = glAp0[c] + (t.ap[v][c] - glAp0[c]) * x
            clp[c] = glLp0[c] + (t.lp[v][c] - glLp0[c]) * x
        }
    }

    private fun deactivate(c: Int) {
        isActive[c] = false
        if (!isZero[c]) {
            java.util.Arrays.fill(lines, segOff[c], segOff[c] + segMask[c] + 1, 0f)
            sFx[c] = 0f; sFy[c] = 0f; sA1x[c] = 0f; sA1y[c] = 0f; sA2x[c] = 0f; sA2y[c] = 0f; sLp[c] = 0f
            isZero[c] = true
        }
        cg[c] = 0f; peak[c] = 0f; ms[c] = 0f
    }

    override fun process(mix: FloatArray, self: FloatArray, selfRows: BooleanArray, gate: FloatArray,
                         softFeed: BooleanArray, damping: FloatArray, outL: FloatArray, outR: FloatArray, n: Int) {
        val t = tables
        if (t == null || n <= 0) { nActive = 0; return }
        if (glideLeft > 0) stepGlide()
        val v = variant
        val gt = t.gain[v]
        // 1. Candidates and priorities.
        var count = 0
        for (c in 0 until COMBS) {
            candidate[c] = false
            if (!t.enabled[c]) continue
            val key = FIRST_KEY + c
            val gk = gate[key]
            val fed = (gk > 0f && send > 0f) || (softFeed[key] && t.multiString[c] && ucs > 0f)
            if (fed || peak[c] > PEAK_FLOOR) {
                candidate[c] = true; count++
                score[c] = (if (fed) 1e6f else 0f) + peak[c]
            }
        }
        if (count > maxActive) {
            // Keep the maxActive best scores (held keys first, then the loudest).
            var keep = 0
            while (keep < maxActive) {
                var best = -1; var bs = -1f
                for (c in 0 until COMBS) if (candidate[c] && score[c] >= 0f && score[c] > bs) { bs = score[c]; best = c }
                if (best < 0) break
                score[best] = -1f - score[best]      // mark as kept (negative)
                keep++
            }
            for (c in 0 until COMBS) if (candidate[c]) { if (score[c] >= 0f) candidate[c] = false }
        }
        // 2. Per-comb coefficients; deactivate the rest.
        var nr = 0
        for (c in 0 until COMBS) {
            if (!candidate[c]) { if (isActive[c] || !isZero[c]) deactivate(c); continue }
            val key = FIRST_KEY + c
            var dIdx = (damping[key] * 32f + 0.5f).toInt()
            if (dIdx < 0) dIdx = 0 else if (dIdx > 32) dIdx = 32
            gTarget[c] = gt[c * D_STEPS + dIdx]
            val gk = gate[key]
            val a = send * DspTables.COMB_TILT[c] * (if (gk > 0f) gk else 0f)
            cMix[c] = a
            cSelf[c] = if (selfRows[c]) -a + (if (softFeed[key] && t.multiString[c]) ucs else 0f) else 0f
            if (!isActive[c]) { isActive[c] = true; cg[c] = gTarget[c] }
            isZero[c] = false
            runList[nr++] = c
        }
        nActive = nr
        java.util.Arrays.fill(group, 0, 4 * HK.BLOCK, 0f)
        if (nr > 0) {
            val m = if (n > HK.BLOCK) HK.BLOCK else n
            if (scalarReference) {
                for (i in 0 until nr) processOne(runList[i], mix, self, m)
            } else {
                var i = 0
                if (kernelWidth == 4) while (i + 4 <= nr) { processFour(runList[i], runList[i + 1], runList[i + 2], runList[i + 3], mix, self, m); i += 4 }
                else if (kernelWidth == 2) while (i + 2 <= nr) { processTwo(runList[i], runList[i + 1], mix, self, m); i += 2 }
                while (i < nr) { processOne(runList[i], mix, self, m); i++ }
            }
            // 3. Pan the 4 register groups into the dry bus.
            for (g in 0 until 4) {
                val o = g * HK.BLOCK; val l = panL[g]; val r = panR[g]
                for (k in 0 until m) { val y = group[o + k]; outL[k] += l * y; outR[k] += r * y }
            }
        }
        pos += if (n > HK.BLOCK) HK.BLOCK else n
    }

    override fun energy(outMeanSquare: FloatArray) {
        for (c in 0 until COMBS) if (isActive[c]) outMeanSquare[c] += ms[c]
    }

    override fun reset() {
        java.util.Arrays.fill(lines, 0f)
        for (c in 0 until COMBS) {
            sFx[c] = 0f; sFy[c] = 0f; sA1x[c] = 0f; sA1y[c] = 0f; sA2x[c] = 0f; sA2y[c] = 0f; sLp[c] = 0f
            isActive[c] = false; isZero[c] = true; cg[c] = 0f; peak[c] = 0f; ms[c] = 0f
        }
        nActive = 0
    }

    /** Test hook: the comb's current integer delay and fraction coefficient. */
    fun loopState(c: Int, out: FloatArray) { out[0] = cm[c].toFloat(); out[1] = ceta[c]; out[2] = cap[c]; out[3] = clp[c]; out[4] = cg[c] }

    // ── Kernels ──

    /** One comb, one block (reference for the 4-way kernel; identical arithmetic). */
    private fun processOne(c: Int, mix: FloatArray, self: FloatArray, n: Int) {
        val buf = lines
        val off = segOff[c]; val mask = segMask[c]; val dm = cm[c]
        val eta = ceta[c]; val ap = cap[c]; val lpa = clp[c]
        var g = cg[c]; val dg = (gTarget[c] - g) / n
        var fx = sFx[c]; var fy = sFy[c]; var a1x = sA1x[c]; var a1y = sA1y[c]; var a2x = sA2x[c]; var a2y = sA2y[c]; var lp = sLp[c]
        val km = cMix[c]; val ks = cSelf[c]; val so = c * HK.BLOCK
        val go = groupOf(c) * HK.BLOCK
        var pk = 0f; var e = 0f
        var p = pos
        for (i in 0 until n) {
            val r = buf[off + ((p - dm) and mask)]
            val v0 = eta * (r - fy) + fx; fx = r; fy = v0
            val v1 = ap * (v0 - a1y) + a1x; a1x = v0; a1y = v1
            val v2 = ap * (v1 - a2y) + a2x; a2x = v1; a2y = v2
            lp = v2 + lpa * (lp - v2)
            g += dg
            val y = g * lp
            val x = km * mix[i] + ks * self[so + i]
            buf[off + (p and mask)] = x + y
            group[go + i] += y
            if ((i and 7) == 0) { e += y * y; val ay = if (y < 0f) -y else y; if (ay > pk) pk = ay }   // peak and energy every 8th frame (M2: A55 cost)
            p++
        }
        store(c, g, fx, fy, a1x, a1y, a2x, a2y, lp, pk, e, n)
    }

    private fun store(c: Int, g: Float, fx: Float, fy: Float, a1x: Float, a1y: Float, a2x: Float, a2y: Float, lp: Float, pk: Float, e: Float, n: Int) {
        cg[c] = gTarget[c]
        sFx[c] = flush(fx); sFy[c] = flush(fy); sA1x[c] = flush(a1x); sA1y[c] = flush(a1y)
        sA2x[c] = flush(a2x); sA2y[c] = flush(a2y); sLp[c] = flush(lp)
        peak[c] = pk
        val cnt = (n + 7) / 8
        ms[c] = 0.5f * e / cnt
        if (g != g) cg[c] = 0f          // NaN guard (never expected)
    }

    private fun flush(x: Float): Float = if (x > -1e-25f && x < 1e-25f) 0f else x

    private fun groupOf(c: Int): Int = c / 22

    /** Four combs per iteration, all state in locals, so their loop-carried chains interleave (§3.11). */
    private fun processFour(c0: Int, c1: Int, c2: Int, c3: Int, mix: FloatArray, self: FloatArray, n: Int) {
        val buf = lines; val grp = group
        val off0 = segOff[c0]; val mask0 = segMask[c0]; val dm0 = cm[c0]
        val eta0 = ceta[c0]; val ap0 = cap[c0]; val lpa0 = clp[c0]
        var g0 = cg[c0]; val dg0 = (gTarget[c0] - g0) / n
        var fx0 = sFx[c0]; var fy0 = sFy[c0]; var a1x0 = sA1x[c0]; var a1y0 = sA1y[c0]; var a2x0 = sA2x[c0]; var a2y0 = sA2y[c0]; var lp0 = sLp[c0]
        val km0 = cMix[c0]; val ks0 = cSelf[c0]; val so0 = c0 * HK.BLOCK; val go0 = groupOf(c0) * HK.BLOCK
        var pk0 = 0f; var e0 = 0f
        val off1 = segOff[c1]; val mask1 = segMask[c1]; val dm1 = cm[c1]
        val eta1 = ceta[c1]; val ap1 = cap[c1]; val lpa1 = clp[c1]
        var g1 = cg[c1]; val dg1 = (gTarget[c1] - g1) / n
        var fx1 = sFx[c1]; var fy1 = sFy[c1]; var a1x1 = sA1x[c1]; var a1y1 = sA1y[c1]; var a2x1 = sA2x[c1]; var a2y1 = sA2y[c1]; var lp1 = sLp[c1]
        val km1 = cMix[c1]; val ks1 = cSelf[c1]; val so1 = c1 * HK.BLOCK; val go1 = groupOf(c1) * HK.BLOCK
        var pk1 = 0f; var e1 = 0f
        val off2 = segOff[c2]; val mask2 = segMask[c2]; val dm2 = cm[c2]
        val eta2 = ceta[c2]; val ap2 = cap[c2]; val lpa2 = clp[c2]
        var g2 = cg[c2]; val dg2 = (gTarget[c2] - g2) / n
        var fx2 = sFx[c2]; var fy2 = sFy[c2]; var a1x2 = sA1x[c2]; var a1y2 = sA1y[c2]; var a2x2 = sA2x[c2]; var a2y2 = sA2y[c2]; var lp2 = sLp[c2]
        val km2 = cMix[c2]; val ks2 = cSelf[c2]; val so2 = c2 * HK.BLOCK; val go2 = groupOf(c2) * HK.BLOCK
        var pk2 = 0f; var e2 = 0f
        val off3 = segOff[c3]; val mask3 = segMask[c3]; val dm3 = cm[c3]
        val eta3 = ceta[c3]; val ap3 = cap[c3]; val lpa3 = clp[c3]
        var g3 = cg[c3]; val dg3 = (gTarget[c3] - g3) / n
        var fx3 = sFx[c3]; var fy3 = sFy[c3]; var a1x3 = sA1x[c3]; var a1y3 = sA1y[c3]; var a2x3 = sA2x[c3]; var a2y3 = sA2y[c3]; var lp3 = sLp[c3]
        val km3 = cMix[c3]; val ks3 = cSelf[c3]; val so3 = c3 * HK.BLOCK; val go3 = groupOf(c3) * HK.BLOCK
        var pk3 = 0f; var e3 = 0f
        val useSelf = ks0 != 0f || ks1 != 0f || ks2 != 0f || ks3 != 0f
        val oneGroup = go0 == go1 && go1 == go2 && go2 == go3
        var p = pos
        for (i in 0 until n) {
            val mx = mix[i]
            val r0 = buf[off0 + ((p - dm0) and mask0)]
            val r1 = buf[off1 + ((p - dm1) and mask1)]
            val r2 = buf[off2 + ((p - dm2) and mask2)]
            val r3 = buf[off3 + ((p - dm3) and mask3)]
            val u0 = eta0 * (r0 - fy0) + fx0; fx0 = r0; fy0 = u0
            val u1 = eta1 * (r1 - fy1) + fx1; fx1 = r1; fy1 = u1
            val u2 = eta2 * (r2 - fy2) + fx2; fx2 = r2; fy2 = u2
            val u3 = eta3 * (r3 - fy3) + fx3; fx3 = r3; fy3 = u3
            val v0 = ap0 * (u0 - a1y0) + a1x0; a1x0 = u0; a1y0 = v0
            val v1 = ap1 * (u1 - a1y1) + a1x1; a1x1 = u1; a1y1 = v1
            val v2 = ap2 * (u2 - a1y2) + a1x2; a1x2 = u2; a1y2 = v2
            val v3 = ap3 * (u3 - a1y3) + a1x3; a1x3 = u3; a1y3 = v3
            val w0 = ap0 * (v0 - a2y0) + a2x0; a2x0 = v0; a2y0 = w0
            val w1 = ap1 * (v1 - a2y1) + a2x1; a2x1 = v1; a2y1 = w1
            val w2 = ap2 * (v2 - a2y2) + a2x2; a2x2 = v2; a2y2 = w2
            val w3 = ap3 * (v3 - a2y3) + a2x3; a2x3 = v3; a2y3 = w3
            lp0 = w0 + lpa0 * (lp0 - w0); g0 += dg0
            lp1 = w1 + lpa1 * (lp1 - w1); g1 += dg1
            lp2 = w2 + lpa2 * (lp2 - w2); g2 += dg2
            lp3 = w3 + lpa3 * (lp3 - w3); g3 += dg3
            val y0 = g0 * lp0
            val y1 = g1 * lp1
            val y2 = g2 * lp2
            val y3 = g3 * lp3
            if (useSelf) {
                buf[off0 + (p and mask0)] = (km0 * mx + ks0 * self[so0 + i]) + y0
                buf[off1 + (p and mask1)] = (km1 * mx + ks1 * self[so1 + i]) + y1
                buf[off2 + (p and mask2)] = (km2 * mx + ks2 * self[so2 + i]) + y2
                buf[off3 + (p and mask3)] = (km3 * mx + ks3 * self[so3 + i]) + y3
            } else {
                buf[off0 + (p and mask0)] = km0 * mx + y0
                buf[off1 + (p and mask1)] = km1 * mx + y1
                buf[off2 + (p and mask2)] = km2 * mx + y2
                buf[off3 + (p and mask3)] = km3 * mx + y3
            }
            if (oneGroup) grp[go0 + i] += y0 + y1 + y2 + y3
            else { grp[go0 + i] += y0; grp[go1 + i] += y1; grp[go2 + i] += y2; grp[go3 + i] += y3 }
            if ((i and 7) == 0) {                          // peak and energy every 8th frame (M2: A55 cost)
                e0 += y0 * y0; e1 += y1 * y1; e2 += y2 * y2; e3 += y3 * y3
                val q0 = if (y0 < 0f) -y0 else y0; if (q0 > pk0) pk0 = q0
                val q1 = if (y1 < 0f) -y1 else y1; if (q1 > pk1) pk1 = q1
                val q2 = if (y2 < 0f) -y2 else y2; if (q2 > pk2) pk2 = q2
                val q3 = if (y3 < 0f) -y3 else y3; if (q3 > pk3) pk3 = q3
            }
            p++
        }
        store(c0, g0, fx0, fy0, a1x0, a1y0, a2x0, a2y0, lp0, pk0, e0, n)
        store(c1, g1, fx1, fy1, a1x1, a1y1, a2x1, a2y1, lp1, pk1, e1, n)
        store(c2, g2, fx2, fy2, a1x2, a1y2, a2x2, a2y2, lp2, pk2, e2, n)
        store(c3, g3, fx3, fy3, a1x3, a1y3, a2x3, a2y3, lp3, pk3, e3, n)
    }

    /** Two combs per iteration (M2: fewer live values than [processFour]; ART on the A55 spills the 4-way state). */
    private fun processTwo(c0: Int, c1: Int, mix: FloatArray, self: FloatArray, n: Int) {
        val buf = lines; val grp = group
        val off0 = segOff[c0]; val mask0 = segMask[c0]; val dm0 = cm[c0]
        val eta0 = ceta[c0]; val ap0 = cap[c0]; val lpa0 = clp[c0]
        var g0 = cg[c0]; val dg0 = (gTarget[c0] - g0) / n
        var fx0 = sFx[c0]; var fy0 = sFy[c0]; var a1x0 = sA1x[c0]; var a1y0 = sA1y[c0]; var a2x0 = sA2x[c0]; var a2y0 = sA2y[c0]; var lp0 = sLp[c0]
        val km0 = cMix[c0]; val ks0 = cSelf[c0]; val so0 = c0 * HK.BLOCK; val go0 = groupOf(c0) * HK.BLOCK
        var pk0 = 0f; var e0 = 0f
        val off1 = segOff[c1]; val mask1 = segMask[c1]; val dm1 = cm[c1]
        val eta1 = ceta[c1]; val ap1 = cap[c1]; val lpa1 = clp[c1]
        var g1 = cg[c1]; val dg1 = (gTarget[c1] - g1) / n
        var fx1 = sFx[c1]; var fy1 = sFy[c1]; var a1x1 = sA1x[c1]; var a1y1 = sA1y[c1]; var a2x1 = sA2x[c1]; var a2y1 = sA2y[c1]; var lp1 = sLp[c1]
        val km1 = cMix[c1]; val ks1 = cSelf[c1]; val so1 = c1 * HK.BLOCK; val go1 = groupOf(c1) * HK.BLOCK
        var pk1 = 0f; var e1 = 0f
        val useSelf = ks0 != 0f || ks1 != 0f
        val oneGroup = go0 == go1
        var p = pos
        for (i in 0 until n) {
            val mx = mix[i]
            val r0 = buf[off0 + ((p - dm0) and mask0)]
            val r1 = buf[off1 + ((p - dm1) and mask1)]
            val u0 = eta0 * (r0 - fy0) + fx0; fx0 = r0; fy0 = u0
            val u1 = eta1 * (r1 - fy1) + fx1; fx1 = r1; fy1 = u1
            val v0 = ap0 * (u0 - a1y0) + a1x0; a1x0 = u0; a1y0 = v0
            val v1 = ap1 * (u1 - a1y1) + a1x1; a1x1 = u1; a1y1 = v1
            val w0 = ap0 * (v0 - a2y0) + a2x0; a2x0 = v0; a2y0 = w0
            val w1 = ap1 * (v1 - a2y1) + a2x1; a2x1 = v1; a2y1 = w1
            lp0 = w0 + lpa0 * (lp0 - w0); g0 += dg0
            lp1 = w1 + lpa1 * (lp1 - w1); g1 += dg1
            val y0 = g0 * lp0
            val y1 = g1 * lp1
            if (useSelf) {
                buf[off0 + (p and mask0)] = (km0 * mx + ks0 * self[so0 + i]) + y0
                buf[off1 + (p and mask1)] = (km1 * mx + ks1 * self[so1 + i]) + y1
            } else {
                buf[off0 + (p and mask0)] = km0 * mx + y0
                buf[off1 + (p and mask1)] = km1 * mx + y1
            }
            if (oneGroup) grp[go0 + i] += y0 + y1
            else { grp[go0 + i] += y0; grp[go1 + i] += y1 }
            if ((i and 7) == 0) {                          // peak and energy every 8th frame (M2: A55 cost)
                e0 += y0 * y0; e1 += y1 * y1
                val q0 = if (y0 < 0f) -y0 else y0; if (q0 > pk0) pk0 = q0
                val q1 = if (y1 < 0f) -y1 else y1; if (q1 > pk1) pk1 = q1
            }
            p++
        }
        store(c0, g0, fx0, fy0, a1x0, a1y0, a2x0, a2y0, lp0, pk0, e0, n)
        store(c1, g1, fx1, fy1, a1x1, a1y1, a2x1, a2y1, lp1, pk1, e1, n)
    }

    companion object {
        const val COMBS = 88
        const val FIRST_KEY = 21
        const val D_STEPS = 33
        const val DISPERSION_MAX_KEY = 59
        const val DEFAULT_GLIDE_MS = 200
        const val PEAK_FLOOR = 3.1623e-5f            // −90 dBFS
        const val FIT_PARTIALS = 10

        class Fit(val m: Int, val eta: Double, val ap: Double, val costCents: Double)

        /** Phase delay (samples) of the first-order allpass (a + z⁻¹)/(1 + a·z⁻¹) at ω. */
        fun apDelay(a: Double, w: Double): Double {
            var ph = atan2(-sin(w), a + cos(w)) - atan2(-a * sin(w), 1 + a * cos(w))
            while (ph > 1e-12) ph -= 2 * PI
            while (ph <= -2 * PI) ph += 2 * PI
            return -ph / w
        }

        /** Phase delay (samples) of the one-pole low-pass (1 − a)/(1 − a·z⁻¹) at ω. */
        fun lpDelay(a: Double, w: Double): Double = atan2(a * sin(w), 1 - a * cos(w)) / w

        fun lpMag(a: Double, w: Double): Double = (1 - a) / sqrt(1 - 2 * a * cos(w) + a * a)

        /** The fraction allpass η with phase delay [target] (0.3 … 2.5 samples) at ω, by bisection. */
        fun solveEta(target: Double, w: Double): Double {
            var lo = -0.6; var hi = 0.99          // delay decreases with η
            repeat(60) {
                val mid = 0.5 * (lo + hi)
                if (apDelay(mid, w) > target) lo = mid else hi = mid
            }
            return 0.5 * (lo + hi)
        }

        /** M and η so that the loop (with dispersion coefficient [ap] and low-pass [lpA]) has phase 2π at f1. */
        fun fitLoop(f1: Double, ap: Double, lpA: Double, fs: Double = HK.SR.toDouble()): Fit {
            val w = 2 * PI * f1 / fs
            val t = fs / f1 - 2 * apDelay(ap, w) - lpDelay(lpA, w)
            val m = floor(t - 0.5).toInt().coerceAtLeast(1)
            val eta = solveEta(t - m, w)
            return Fit(m, eta, ap, 0.0)
        }

        /** Cent error of partial n (n·f0·√(1 + B·n²)) for a fitted loop, from the loop phase. */
        fun partialCents(fit: Fit, lpA: Double, f0: Double, b: Double, n: Int, fs: Double = HK.SR.toDouble()): Double {
            val fn = n * f0 * sqrt(1 + b * n * n)
            val w = 2 * PI * fn / fs
            val tau = fit.m + apDelay(fit.eta, w) + 2 * apDelay(fit.ap, w) + lpDelay(lpA, w)
            val cycles = w * tau / (2 * PI)
            // The loop resonates where cycles = n: a surplus of cycles puts the peak below fn.
            return -1200.0 / ln(2.0) * ln(cycles / n)
        }

        /** Grid + golden refinement of the dispersion coefficient over partials 2..FIT_PARTIALS. */
        fun fitDispersion(f0: Double, b: Double, lpA: Double, fs: Double = HK.SR.toDouble()): Fit {
            val f1 = f0 * sqrt(1 + b)
            fun cost(a: Double): Double {
                val fit = fitLoop(f1, a, lpA, fs)
                var s = 0.0
                for (n in 2..FIT_PARTIALS) {
                    if (n * f0 * sqrt(1 + b * n * n) > 0.45 * fs) break
                    val e = partialCents(fit, lpA, f0, b, n, fs); s += e * e
                }
                return s
            }
            var bestA = 0.0; var bestC = cost(0.0)
            var a = -0.0025
            while (a >= -0.95) { val c = cost(a); if (c < bestC) { bestC = c; bestA = a }; a -= 0.0025 }
            var lo = (bestA - 0.0025).coerceAtLeast(-0.95); var hi = (bestA + 0.0025).coerceAtMost(0.0)
            repeat(40) {
                val m1 = lo + (hi - lo) * 0.382; val m2 = lo + (hi - lo) * 0.618
                if (cost(m1) < cost(m2)) hi = m2 else lo = m1
            }
            val aa = 0.5 * (lo + hi)
            val fit = fitLoop(f1, aa, lpA, fs)
            return Fit(fit.m, fit.eta, aa, sqrt(cost(aa)))
        }
    }
}
