package com.tropicalstream.hammerklavier.session

import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.ReverbMode
import com.tropicalstream.hammerklavier.contract.UprightFinish
import com.tropicalstream.hammerklavier.contract.stub.MemSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionSettingsTest {
    @Test fun resumePointRoundTripsThroughTheStore() {
        val s = MemSettings()
        assertNull(ResumePoint.load(s))
        ResumePoint("w1.b", InstrumentId.HARPSICHORD, 12_345_678_901L, "bach", listOf("w1.b", "w1.c", "w2.a"), 1).save(s)
        val r = ResumePoint.load(s)!!
        assertEquals("w1.b", r.movementId); assertEquals(InstrumentId.HARPSICHORD, r.instrument)
        assertEquals(12_345_678_901L, r.songUs); assertEquals("bach", r.shelfId)
        assertEquals(listOf("w1.b", "w1.c", "w2.a"), r.playlist); assertEquals(1, r.index)
        ResumePoint("x", InstrumentId.GRAND, 0, null, emptyList(), 0).save(s)
        val n = ResumePoint.load(s)!!
        assertNull(n.shelfId); assertEquals(listOf("x"), n.playlist)
        ResumePoint.clear(s); assertNull(ResumePoint.load(s))
    }

    @Test fun defaultsAndClamps() {
        val p = SessionSettings(MemSettings())
        assertEquals(InstrumentProfile.HARPSICHORD.defaultTuning, p.tuning(InstrumentId.HARPSICHORD))
        assertEquals(-8f, p.masterDb, 0f); assertEquals(ReverbMode.ROOM, p.reverb); assertEquals(3, p.registration)
        p.tempoPct = 400; assertEquals(150, p.tempoPct)
        p.setAvLead("speaker", 999); assertEquals(400, p.avLead("speaker"))
        p.recent = (0 until 30).map { "m$it" }; assertEquals(15, p.recent.size)
        p.edgeOverlay = false; assertEquals(false, p.edgeOverlay); p.edgeOverlay = null; assertNull(p.edgeOverlay)
    }

    @Test fun uprightFinishSwitchesToEbonyOnce() {
        assertEquals(UprightFinish.EBONY, SessionSettings(MemSettings()).finish)
        val m = MemSettings(); m.putString(SessionKeys.FINISH, UprightFinish.WALNUT.name)   // saved before the switch
        val p = SessionSettings(m)
        assertEquals(UprightFinish.EBONY, p.finish)
        p.finish = UprightFinish.WALNUT
        assertEquals(UprightFinish.WALNUT, SessionSettings(m).finish)                        // a later choice sticks
    }
}
