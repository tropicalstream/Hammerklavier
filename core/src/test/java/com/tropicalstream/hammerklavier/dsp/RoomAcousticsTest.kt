package com.tropicalstream.hammerklavier.dsp

import com.tropicalstream.hammerklavier.contract.AcousticMaterial
import com.tropicalstream.hammerklavier.contract.Conventions
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.KonzertzimmerAcoustics
import com.tropicalstream.hammerklavier.contract.ListenerPose
import com.tropicalstream.hammerklavier.contract.ReverbMode
import com.tropicalstream.hammerklavier.contract.Surface
import com.tropicalstream.hammerklavier.contract.VenueGeometry
import com.tropicalstream.hammerklavier.contract.stub.FixedRoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** T3.2: room design on KonzertzimmerAcoustics.GEOMETRY. */
class RoomAcousticsTest {
    private val g = KonzertzimmerAcoustics.GEOMETRY
    private val grand = g.placements.getValue(InstrumentId.GRAND)
    private val src = floatArrayOf(0f, 0.90f, -1.00f)
    private val playerEar = floatArrayOf(0f, 1.20f, 0.55f)

    private fun room(p: FloatArray): FloatArray { val o = FloatArray(3); grand.toRoom(p, o); return o }
    private fun dist(a: FloatArray, b: FloatArray) = sqrt(((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2])).toDouble())

    private fun player(mode: ReverbMode = ReverbMode.ROOM, emb: Float = 30f) = RoomAcoustics.design(g, grand, src,
        ListenerPose(room(playerEar), Conventions.forwardYaw(grand, playerEar, src), false, 1f), mode, dist(room(playerEar), room(src)).toFloat(), emb)

    @Test fun sabineMatchesTheFormulaAndTheArchitectsNumbers() {
        val t = RoomAcoustics.sabineT60(g)
        // Independent recomputation of Σ S·α + N·A + 4mV per band.
        for (b in 0 until 7) {
            var a = 0.0
            for (s in g.surfaces) a += s.areaM2 * s.material.alpha[b]
            a += 18 * g.personSabins[b] + 4 * g.airM[b] * 469.7
            assertEquals(0.161 * 469.7 / a, t[b], t[b] * 0.01)
        }
        val arch = doubleArrayOf(1.15, 1.43, 1.69, 1.61, 1.59, 1.43, 0.93)
        for (b in 0 until 7) assertEquals("band $b", arch[b], t[b], arch[b] * 0.01)
        assertTrue(t[2] in 1.5..1.9)
        assertEquals(1.36, RoomAcoustics.criticalDistance(g), 0.01)
    }

    @Test fun imageSourceDelaysAtTheRoomCentre() {
        val ear = floatArrayOf(0f, 1.2f, 0f)
        val d = RoomAcoustics.design(g, grand, src, ListenerPose(ear, 0f, false, 1f), ReverbMode.ROOM, 1.6f, 30f)
        val s = room(src).map { it.toDouble() }
        val e = ear.map { it.toDouble() }
        fun len(x: Double, y: Double, z: Double) = sqrt((x - e[0]) * (x - e[0]) + (y - e[1]) * (y - e[1]) + (z - e[2]) * (z - e[2]))
        val d0 = len(s[0], s[1], s[2])
        // Analytic images: floor y → −y, ceiling y → 10.6 − y, N z → −8 − z, S z → 8 − z, E x → 10.5 − x, W x → −10.5 − x.
        val img = listOf(
            doubleArrayOf(s[0], -s[1], s[2]), doubleArrayOf(s[0], 10.6 - s[1], s[2]),
            doubleArrayOf(s[0], s[1], -8 - s[2]), doubleArrayOf(s[0], s[1], 8 - s[2]),
            doubleArrayOf(10.5 - s[0], s[1], s[2]), doubleArrayOf(-10.5 - s[0], s[1], s[2]),
            doubleArrayOf(s[0], 10.6 + s[1], s[2]), doubleArrayOf(s[0], s[1] - 10.6, s[2]),     // floor then ceiling; ceiling then floor
            doubleArrayOf(s[0], s[1], 16 + s[2]), doubleArrayOf(s[0], s[1], s[2] - 16),
            doubleArrayOf(s[0] - 21, s[1], s[2]), doubleArrayOf(s[0] + 21, s[1], s[2]))
        for (i in 0 until 12) {
            val want = Math.round((len(img[i][0], img[i][1], img[i][2]) - d0) * 48000 / 343).toInt()
            assertEquals("tap $i", want, d.erDelay[i])
        }
        assertEquals(d.erDelay.min(), d.preDelayFrames)
    }

    @Test fun drrInTheHallIsMinus11() {
        val hall = floatArrayOf(0.4f, 1.2f, 3.0f)
        val r = dist(hall, room(src))
        assertEquals(4.93, r, 0.01)
        assertEquals(-11.2, RoomAcoustics.drrDb(g, r), 0.5)
    }

    @Test fun changingAMaterialChangesT60() {
        val carpet = AcousticMaterial("carpet", floatArrayOf(.08f, .24f, .57f, .69f, .71f, .73f, .73f))
        val g2 = VenueGeometry(g.widthM, g.depthM, g.corniceM, g.ceilingM, g.volumeM3,
            g.surfaces.map { if (it.plane == 0) Surface(it.name, 0, it.areaM2, carpet) else it }, g.erPlanes,
            g.people, g.personSabins, g.airM, g.placements, g.fixtureFootprints)
        val a = RoomAcoustics.sabineT60(g); val b = RoomAcoustics.sabineT60(g2)
        assertTrue(b[2] < a[2] * 0.8)
        val d2 = RoomAcoustics.design(g2, grand, src, ListenerPose(room(playerEar), 0f, false, 1f), ReverbMode.ROOM, 1.58f, 30f)
        assertTrue(d2.t60Mid < player().t60Mid)
    }

    @Test fun embeddedRoomLowersReverbGain() {
        val dry = player(emb = 30f); val wet = player(emb = 3f)
        val r = dist(room(playerEar), room(src)); val rc = RoomAcoustics.criticalDistance(g)
        val rt = (r / rc) * (r / rc)
        val rsim = maxOf(rt - Math.pow(10.0, -0.3), 0.1 * rt)
        assertTrue(wet.reverbGain < dry.reverbGain)
        assertEquals(sqrt(rsim / rt), (wet.reverbGain / (r / rc)).toDouble(), 1e-3)
        assertEquals(wet.erGain.toDouble(), sqrt(rsim / rt), 1e-3)
    }

    @Test fun modesScaleTheReverb() {
        val room = player(ReverbMode.ROOM); val dry = player(ReverbMode.DRY); val res = player(ReverbMode.RESONANT)
        assertEquals(-6.0, DspTestUtil.db((dry.reverbGain / room.reverbGain).toDouble()), 0.01)
        assertEquals(3.0, DspTestUtil.db((res.reverbGain / room.reverbGain).toDouble()), 0.01)
        assertEquals(1.2f * room.t60Mid, res.t60Mid, 1e-4f)
    }

    @Test fun playerDesignEqualsFixedRoom() {
        val d = player(); val f = FixedRoom.PLAYER
        for (i in 0 until 12) {
            assertEquals("delay $i", f.erDelay[i], d.erDelay[i])
            assertEquals("gL $i", f.erGainL[i], d.erGainL[i], 2e-4f)
            assertEquals("gR $i", f.erGainR[i], d.erGainR[i], 2e-4f)
            assertEquals("bright $i", f.erBright[i], d.erBright[i])
        }
        assertEquals(f.preDelayFrames, d.preDelayFrames)
        assertEquals(f.t60Low, d.t60Low, 2e-3f); assertEquals(f.t60Mid, d.t60Mid, 2e-3f); assertEquals(f.t60High, d.t60High, 2e-3f)
        assertEquals(f.reverbGain, d.reverbGain, 3e-3f)
        assertEquals(f.directGain, d.directGain, 1e-4f)
        assertEquals(f.airLpHz, d.airLpHz, 1f)
        assertEquals(f.sourceAzimuthRad, d.sourceAzimuthRad, 1e-4f)
    }

    @Test fun directGainAndAirFollowDistance() {
        val hall = floatArrayOf(0.4f, 1.2f, 3.0f)
        val bench = dist(room(playerEar), room(src)).toFloat()
        val d = RoomAcoustics.design(g, grand, src, ListenerPose(hall, Conventions.yawOf(floatArrayOf(-0.4f, 0f, -4.9f)), true, 0.4f), ReverbMode.ROOM, bench, 30f)
        assertEquals((bench / 4.925f).coerceAtLeast(0.25f), d.directGain, 0.01f)
        assertEquals(18000f - 1600f * (4.925f - 1.6f), d.airLpHz, 10f)
        assertTrue(d.worldLocked); assertEquals(0.4f, d.width)
        assertEquals(bench / 1.36f, d.reverbGain, 0.02f)          // the same at every seat
    }
}
