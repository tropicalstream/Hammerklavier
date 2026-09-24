package com.tropicalstream.hammerklavier.dsp

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.KonzertzimmerAcoustics
import com.tropicalstream.hammerklavier.contract.ReverbMode
import com.tropicalstream.hammerklavier.contract.RoomDesign
import com.tropicalstream.hammerklavier.contract.ViewId
import com.tropicalstream.hammerklavier.session.ListenerRooms
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.pow

/**
 * Loudness consistency across views (INTEGRATION.md, loudness across instruments and views), on a
 * synthetic piano (decaying harmonic notes, ~7 per second over five octaves, stereo) through the
 * real [RoomChain] with each instrument's view designs as SessionController sends them:
 * - a view change must not dip or bump the momentary loudness (BS.1770, 400 ms) more than 1 LU
 *   outside the two views' own while the design glides;
 * - every view stays within [SYNTH_SPREAD_LU] of the Player. The ±1 LU acceptance is on the real
 *   kits ([com.tropicalstream.hammerklavier.engine.LoudnessTableTest]); the synthetic notes are
 *   flatter and sparser than a piano, so the Hall reads louder here (before the seat compensation
 *   the real kits spread 6–10 LU).
 */
class ViewLoudnessTest {
    private class V(val view: ViewId, val framing: Int)
    private val views = listOf(V(ViewId.PLAYER, 0), V(ViewId.PLAYER, 1), V(ViewId.ACTION, 0), V(ViewId.ACTION, 1), V(ViewId.HALL, 0))

    private fun design(id: InstrumentId, v: V, embeddedDb: Float, mode: ReverbMode = ReverbMode.ROOM): RoomDesign {
        val placement = KonzertzimmerAcoustics.PLACEMENTS.getValue(id)
        val pose = ListenerRooms.resolve(id, null, placement, v.view, v.framing).pose
        return ListenerRooms.leveled(RoomAcoustics.design(KonzertzimmerAcoustics.GEOMETRY, placement, ListenerRooms.SOURCE.getValue(id), pose, mode,
            ListenerRooms.benchDistance(id, null), embeddedDb), id, v.view, v.framing)
    }

    /** The kits' embedded-room levels (map.json embeddedRoomDb). */
    private val embedded = mapOf(InstrumentId.GRAND to -9.67f, InstrumentId.UPRIGHT to -7.71f, InstrumentId.HARPSICHORD to 3.12f)

    /** Decaying harmonic notes at random keys (seeded), stereo with a small L/R difference. */
    private fun music(seconds: Int, seed: Long = 7): Pair<FloatArray, FloatArray> {
        val n = seconds * HK.SR
        val l = FloatArray(n); val r = FloatArray(n)
        val rnd = java.util.Random(seed)
        var t = 0
        while (t < n) {
            val key = 36 + rnd.nextInt(60)
            val f0 = 440.0 * 2.0.pow((key - 69) / 12.0)
            val amp = 0.05 + 0.1 * rnd.nextDouble()
            val tau = 0.3 + 1.2 * rnd.nextDouble()
            val len = minOf(n - t, (tau * 5 * HK.SR).toInt())
            val pan = 0.4 + 0.2 * rnd.nextDouble()
            for (p in 1..8) {
                val f = p * f0; if (f > 18_000) break
                val w = 2 * Math.PI * f / HK.SR; val a = amp / p
                val d = exp(-1.0 / (tau / p.toDouble().pow(0.5) * HK.SR))
                var e = a
                for (i in 0 until len) {
                    val s = (e * kotlin.math.sin(w * i)).toFloat()
                    l[t + i] += s * (1 - pan).toFloat() * 2f; r[t + i] += s * pan.toFloat() * 2f
                    e *= d
                }
            }
            t += (HK.SR * (0.08 + 0.2 * rnd.nextDouble())).toInt()
        }
        return l to r
    }

    /** Renders [l]/[r] through a fresh chain; [switchAt] ≥ 0 changes to [d2] there (500 ms glide). */
    private fun render(l: FloatArray, r: FloatArray, d1: RoomDesign, d2: RoomDesign? = null, switchAt: Int = -1): FloatArray {
        val room = RoomChain(HK.SR)
        room.setDesign(d1, 0)
        val n = l.size / HK.BLOCK * HK.BLOCK
        val out = FloatArray(2 * n)
        val oL = FloatArray(HK.BLOCK); val oR = FloatArray(HK.BLOCK)
        val iL = FloatArray(HK.BLOCK); val iR = FloatArray(HK.BLOCK)
        var off = 0
        while (off < n) {
            if (d2 != null && off == switchAt) room.setDesign(d2, 500)
            System.arraycopy(l, off, iL, 0, HK.BLOCK); System.arraycopy(r, off, iR, 0, HK.BLOCK)
            room.process(iL, iR, oL, oR, HK.BLOCK, 0f)
            for (i in 0 until HK.BLOCK) { out[2 * (off + i)] = oL[i]; out[2 * (off + i) + 1] = oR[i] }
            off += HK.BLOCK
        }
        return out
    }

    @Test fun everyViewNearThePlayer() {
        val (l, r) = music(40)
        val report = StringBuilder()
        var worst = 0.0
        for (id in InstrumentId.entries) {
            val ref = Lufs.integrated(render(l, r, design(id, views[0], embedded.getValue(id))))
            report.append("$id player %.2f LUFS:".format(ref))
            for (v in views.drop(1)) {
                val dl = Lufs.integrated(render(l, r, design(id, v, embedded.getValue(id)))) - ref
                report.append(" ${v.view}${v.framing} %+.2f".format(dl))
                worst = maxOf(worst, kotlin.math.abs(dl))
            }
            report.append('\n')
        }
        println(report)
        assertTrue("view loudness spread (LU re the Player) worst %.2f:\n$report".format(worst), worst <= SYNTH_SPREAD_LU)
    }

    @Test fun viewChangeHasNoDipOrBump() {
        // Dense notes (20 s) so the momentary loudness is nearly flat; switch at 10 s.
        val (l, r) = music(20, seed = 11)
        val sw = 10 * HK.SR / HK.BLOCK * HK.BLOCK
        val report = StringBuilder()
        var worst = 0.0
        val distinct = views.filter { !(it.view == ViewId.PLAYER && it.framing == 1) }
        for (id in InstrumentId.entries) {
            val emb = embedded.getValue(id)
            val ds = distinct.map { design(id, it, emb) }
            val fixed = ds.map { Lufs.momentary(render(l, r, it)) }
            for (a in ds.indices) for (b in ds.indices) {
                if (a == b) continue
                val y = Lufs.momentary(render(l, r, ds[a], ds[b], sw))
                // Around the switch (−0.5 … +2.5 s) the switched render must stay inside the envelope
                // of the two fixed renders, ± 1 LU.
                val h0 = (sw / (HK.SR / 10)) - 5
                for (i in h0 until minOf(y.size, h0 + 30)) {
                    val lo = minOf(fixed[a][i], fixed[b][i]); val hi = maxOf(fixed[a][i], fixed[b][i])
                    val dev = if (y[i] < lo) lo - y[i] else if (y[i] > hi) y[i] - hi else 0.0
                    if (dev > worst) {
                        worst = dev; report.setLength(0)
                        report.append("$id ${distinct[a].view}${distinct[a].framing}→${distinct[b].view}${distinct[b].framing} at hop $i: %.2f LU (%s; fixed %.2f / %.2f, switched %.2f)".format(dev, if (y[i] < lo) "dip" else "bump", fixed[a][i], fixed[b][i], y[i]))
                    }
                }
            }
        }
        println("worst transition deviation: $report")
        assertTrue("transition dip/bump: $report", worst <= 1.0)
    }

    companion object { const val SYNTH_SPREAD_LU = 2.5 }
}
