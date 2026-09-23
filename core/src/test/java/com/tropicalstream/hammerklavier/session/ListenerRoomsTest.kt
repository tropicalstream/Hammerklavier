package com.tropicalstream.hammerklavier.session

import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.KonzertzimmerAcoustics
import com.tropicalstream.hammerklavier.contract.ViewId
import com.tropicalstream.hammerklavier.contract.stub.StubAnchors
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

class ListenerRoomsTest {
    private fun azimuthOfSource(id: InstrumentId, view: ViewId, framing: Int): Double {
        val pl = KonzertzimmerAcoustics.PLACEMENTS.getValue(id)
        val r = ListenerRooms.resolve(id, null, pl, view, framing)
        val s = FloatArray(3); pl.toRoom(ListenerRooms.SOURCE.getValue(id), s)
        val e = r.pose.earRoom
        val yawToSource = atan2((s[0] - e[0]).toDouble(), -(s[2] - e[2]).toDouble())
        var d = yawToSource - r.pose.forwardYawRad
        while (d > PI) d -= 2 * PI; while (d < -PI) d += 2 * PI
        return d
    }

    @Test fun listenerFacesTheSourceInEveryPose() {
        for (id in InstrumentId.entries) for (v in ViewId.entries) for (f in 0..1)
            assertEquals("$id $v $f", 0.0, azimuthOfSource(id, v, f), 1e-4)
    }

    @Test fun grandPlayerTrebleToTheRight() {
        val id = InstrumentId.GRAND
        val pl = KonzertzimmerAcoustics.PLACEMENTS.getValue(id)
        val pose = ListenerRooms.resolve(id, null, pl, ViewId.PLAYER, 0).pose
        val treble = FloatArray(3); pl.toRoom(floatArrayOf(0.6f, 0.9f, 0f), treble)
        val yaw = atan2((treble[0] - pose.earRoom[0]).toDouble(), -(treble[2] - pose.earRoom[2]).toDouble())
        var d = yaw - pose.forwardYawRad
        while (d > PI) d -= 2 * PI; while (d < -PI) d += 2 * PI
        assertTrue("treble azimuth $d", d > 0.1)             // positive = clockwise = to the right
    }

    @Test fun hallIsWorldLockedAndNarrow() {
        val pl = KonzertzimmerAcoustics.PLACEMENTS.getValue(InstrumentId.UPRIGHT)
        val h = ListenerRooms.resolve(InstrumentId.UPRIGHT, null, pl, ViewId.HALL, 1).pose
        assertTrue(h.worldLocked); assertEquals(0.4f, h.directWidth, 0f)
        for (k in 0..2) assertEquals(ListenerRooms.HALL_EAR[k], h.earRoom[k], 0f)
        val p = ListenerRooms.resolve(InstrumentId.UPRIGHT, null, pl, ViewId.PLAYER, 0).pose
        assertFalse(p.worldLocked); assertEquals(1.0f, p.directWidth, 0f)
        assertEquals(0.8f, ListenerRooms.directWidth(ViewId.ACTION), 0f)
    }

    @Test fun anchorsAgreeWithTableForTheGrand() {
        val a = StubAnchors(InstrumentProfile.GRAND, 0f)
        val pl = KonzertzimmerAcoustics.PLACEMENTS.getValue(InstrumentId.GRAND)
        for (v in ViewId.entries) for (f in 0..1) {
            val x = ListenerRooms.resolve(InstrumentId.GRAND, a, pl, v, f).pose
            val y = ListenerRooms.resolve(InstrumentId.GRAND, null, pl, v, f).pose
            for (k in 0..2) assertEquals("$v $f", y.earRoom[k], x.earRoom[k], 1e-5f)
        }
    }

    @Test fun benchDistanceIsPlayerEarToSource() {
        val d = ListenerRooms.benchDistance(InstrumentId.GRAND, null)
        assertEquals(sqrt(0.3 * 0.3 + 1.55 * 1.55).toFloat(), d, 1e-4f)
        assertTrue(abs(ListenerRooms.benchDistance(InstrumentId.HARPSICHORD, null) - sqrt(0.3f * 0.3f + 1.5f * 1.5f)) < 1e-4f)
    }
}
