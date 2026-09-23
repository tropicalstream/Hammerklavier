package com.tropicalstream.hammerklavier.session

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.KonzertzimmerAcoustics
import com.tropicalstream.hammerklavier.contract.QualityLadder
import com.tropicalstream.hammerklavier.contract.RouteInfo
import com.tropicalstream.hammerklavier.contract.OutputRoute
import com.tropicalstream.hammerklavier.contract.StatusCode
import com.tropicalstream.hammerklavier.contract.UiAction
import com.tropicalstream.hammerklavier.contract.UiEvent
import com.tropicalstream.hammerklavier.contract.ViewId
import com.tropicalstream.hammerklavier.contract.stub.MemSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionControllerTest {

    // ─── T12.1 playlists ───

    @Test fun t12_1_shelfQueuesMovementRestOfWorkThenRestOfShelf() {
        val r = SessionRig().start()
        r.c.onAction(UiAction.Play("w1.b", "bach"))
        assertEquals(listOf("w1.b", "w1.c", "w2.a", "w3.a", "w3.b"), r.c.playlistIds)
        assertEquals("w1.b", r.c.movementId)
        r.c.onAction(UiAction.Next); assertEquals("w1.c", r.c.movementId)
        r.c.onAction(UiAction.Next); assertEquals("w2.a", r.c.movementId)
        r.c.onAction(UiAction.Play("w2.a", "bach"))
        assertEquals(listOf("w2.a", "w3.a", "w3.b"), r.c.playlistIds)
    }

    @Test fun t12_1_noShelfPlaysRestOfWorkOnly() {
        val r = SessionRig().start()
        r.c.onAction(UiAction.Play("w1.a"))
        assertEquals(listOf("w1.a", "w1.b", "w1.c"), r.c.playlistIds)
    }

    @Test fun t12_1_startHereAndRecentlyPlayedPlayTheirLists() {
        val r = SessionRig().start()
        r.c.onAction(UiAction.Play("w1.a", SessionController.SHELF_START_HERE))
        assertEquals(listOf("w3.a", "w1.a", "w4.a"), r.c.playlistIds)
        assertEquals(1, r.c.playlistIndex)
        r.c.onAction(UiAction.Play("w2.a", "bach"))
        r.c.onAction(UiAction.Play("w3.b", "bach"))
        assertEquals(listOf("w3.b", "w2.a", "w1.a"), r.c.recent)
        r.c.onAction(UiAction.Play("w2.a", SessionController.SHELF_RECENT))
        assertEquals(listOf("w3.b", "w2.a", "w1.a"), r.c.playlistIds)
        assertEquals(1, r.c.playlistIndex)
        assertEquals(listOf("w2.a", "w3.b", "w1.a"), r.c.recent)
    }

    @Test fun t12_1_recentIsCappedAt15() {
        val r = SessionRig().start()
        repeat(20) { r.c.controlPlay("synthless.$it") }
        assertEquals(15, r.c.recent.size)
        assertEquals("synthless.19", r.c.recent.first())
    }

    @Test fun t12_1_previousRestartsAfter3sElseGoesBack() {
        val r = SessionRig().start()
        r.c.onAction(UiAction.Play("w1.a", "bach"))
        r.c.onAction(UiAction.Next)
        assertEquals("w1.b", r.c.movementId)
        r.advanceMs(5_000)                                   // 5 s of song time (pre-roll 0.4 s) → 4.6 s display
        r.c.onAction(UiAction.Previous)
        assertEquals("w1.b", r.c.movementId)
        assertEquals(0L, r.audio.seeks.last())
        r.advanceMs(1_000)
        r.c.onAction(UiAction.Previous)
        assertEquals("w1.a", r.c.movementId)
    }

    @Test fun t12_1_endOfMovementPlaysNext() {
        val r = SessionRig().start()
        r.c.onAction(UiAction.Play("w1.a", "bach"))
        val g = r.c.current!!.generation
        r.c.onEnded(g)
        assertEquals("w1.b", r.c.movementId)
        r.c.onEnded(g)                                      // a stale end is ignored
        assertEquals("w1.b", r.c.movementId)
    }

    // ─── T12.2 instrument switch ───

    @Test fun t12_2_switchSendsPauseThenBankThenPerformanceKeepingPosition() {
        val r = SessionRig().start()
        r.c.onAction(UiAction.Play("w1.a", "bach"))
        r.advanceMs(2_000)
        r.log.clear()
        r.c.onAction(UiAction.SetInstrument(InstrumentId.UPRIGHT))
        val pause = r.log.indexOf("audio.pause 30")
        val bank = r.log.indexOf("audio.setBank UPRIGHT")
        val perf = r.log.indexOf("audio.setPerformance")
        assertTrue(r.log.calls.toString(), pause in 0 until bank)
        assertTrue(r.log.calls.toString(), bank < perf)
        assertTrue(r.log.calls[perf], r.log.calls[perf].contains("start=-1 auto=true"))
        assertTrue(r.log.indexOf("render.setInstrument UPRIGHT") >= 0)
        assertTrue(r.log.calls.toString(), r.log.indexOf("audio.setRoom", perf) > perf)
        assertEquals(-1, r.log.indexOf("audio.setRoom", bank).let { if (it in bank until perf) it else -1 })
        assertEquals(InstrumentId.UPRIGHT, r.c.instrument)
        assertEquals(InstrumentId.UPRIGHT, r.c.current!!.instrument)
    }

    @Test fun t12_2_switchNeverWaitsForRendererAndCompilesWhileBankIsLate() {
        // Queued loader: the bank's KeyMap and the compile come back in either order; the renderer never answers anything.
        val r = SessionRig(queued = true).start()
        r.c.onAction(UiAction.Play("w1.a", "bach")); r.drain()
        r.c.onAction(UiAction.PlayPause)                    // paused before the switch → autoPlay false
        r.log.clear()
        r.c.onAction(UiAction.SetInstrument(InstrumentId.HARPSICHORD))
        assertEquals(-1, r.log.indexOf("audio.setPerformance"))
        r.drain()
        val bank = r.log.indexOf("audio.setBank HARPSICHORD")
        val perf = r.log.indexOf("audio.setPerformance")
        assertTrue(r.log.calls.toString(), bank in 0 until perf)
        assertTrue(r.log.calls[perf].contains("start=-1 auto=false"))
    }

    @Test fun t12_2_switchBackToACachedKitReportedOnlyByOnCompleteDoesNotHang() {
        val r = SessionRig(kitsOverride = { CachedCompleteOnlyKits(it) }).start()
        r.c.onAction(UiAction.Play("w1.a", "bach"))
        r.c.onAction(UiAction.SetInstrument(InstrumentId.HARPSICHORD))
        r.log.clear()
        r.c.onAction(UiAction.SetInstrument(InstrumentId.GRAND))
        val bank = r.log.indexOf("audio.setBank GRAND")
        val perf = r.log.indexOf("audio.setPerformance")
        assertTrue(r.log.calls.toString(), bank in 0 until perf)
        assertTrue(r.log.calls[perf].contains("start=-1 auto=true"))
        assertEquals(InstrumentId.GRAND, r.c.current!!.instrument)
    }

    @Test fun t12_2_switchRightAfterPlayKeepsPlayingBeforeTheClockCatchesUp() {
        val r = SessionRig(queued = true).start()
        r.c.onAction(UiAction.Play("w1.a", "bach")); r.drain()
        r.audio.inner.pause(0)                               // the clock still shows "not playing"
        r.c.onAction(UiAction.SetInstrument(InstrumentId.UPRIGHT)); r.drain()
        assertTrue(r.audio.perfs.last().third)
    }

    @Test fun endOfLastItemResumesFromTheStartAndPlayRestarts() {
        val r = SessionRig().start()
        r.c.onAction(UiAction.Play("w3.b", "bach"))
        r.advanceMs(3_000)
        r.c.onEnded(r.c.current!!.generation)
        assertEquals(0L, r.c.resume!!.songUs)
    }

    @Test fun switchDuringSyncTestResendsTheSyncClick() {
        val r = SessionRig().start()
        r.c.onAction(UiAction.Play("w1.a", "bach"))
        r.c.onAction(UiAction.SyncTest(true))
        r.log.clear()
        r.c.onAction(UiAction.SetInstrument(InstrumentId.HARPSICHORD))
        assertEquals("synth:sync", r.c.current!!.id)
        assertEquals(InstrumentId.HARPSICHORD, r.c.current!!.instrument)
        assertTrue(r.audio.perfs.last().third)
    }

    @Test fun instrumentChoiceIsRememberedPerWork() {
        val r = SessionRig().start()
        r.c.onAction(UiAction.Play("w1.a", "bach"))
        r.c.onAction(UiAction.SetInstrument(InstrumentId.HARPSICHORD))
        r.c.onAction(UiAction.Play("w2.a", "bach"))
        assertEquals(InstrumentId.GRAND, r.c.instrument)
        r.c.onAction(UiAction.Play("w1.c", "bach"))
        assertEquals(InstrumentId.HARPSICHORD, r.c.instrument)
        r.c.onAction(UiAction.Play("w4.a", "other"))         // default harpsichord
        assertEquals(InstrumentId.HARPSICHORD, r.c.current!!.instrument)
    }

    @Test fun flatVelocityPolicyAppliesOnPianosOnly() {
        val r = SessionRig().start()
        r.c.onAction(UiAction.Play("w5.a", "other"))
        assertTrue(r.log.calls.any { it.startsWith("compile w5.a") && it.endsWith("flat=72") })
        r.c.onAction(UiAction.SetInstrument(InstrumentId.HARPSICHORD))
        assertTrue(r.log.calls.last { it.startsWith("compile w5.a") }.endsWith("flat=0"))
    }

    // ─── T12.3 generations and pre-compilation ───

    @Test fun t12_3_generationIncreasesOnEveryPerformanceAndNextIsPrecompiled() {
        val r = SessionRig(queued = true).start()
        r.c.onAction(UiAction.Play("w1.a", "bach")); r.drain()
        assertTrue(r.log.calls.toString(), r.log.calls.any { it.startsWith("compile w1.b") })
        val compilesBefore = r.log.count("compile")
        r.c.onAction(UiAction.Next)
        assertEquals("w1.b", r.c.movementId)
        assertEquals("the pre-compiled Performance is used immediately", compilesBefore, r.log.count("compile"))
        r.drain()
        r.c.onAction(UiAction.SetInstrument(InstrumentId.UPRIGHT)); r.drain()
        r.c.onAction(UiAction.SyncTest(true)); r.drain()
        r.c.onAction(UiAction.SyncTest(false)); r.drain()
        r.c.onAction(UiAction.Play("w3.a", "bach")); r.drain()
        val gens = r.audio.perfs.mapNotNull { it.first?.generation }
        assertTrue(gens.toString(), gens.size >= 6)
        for (i in 1 until gens.size) assertTrue(gens.toString(), gens[i] > gens[i - 1])
    }

    @Test fun t12_3_nextUsesPrecompiledWithoutRecompiling() {
        val r = SessionRig(queued = true).start()
        r.c.onAction(UiAction.Play("w1.a", "bach")); r.drain()
        val nB = r.log.calls.count { it.startsWith("compile w1.b") }
        assertEquals(1, nB)
        r.c.onAction(UiAction.Next)
        assertEquals("sent at once, no loader round trip", "w1.b", r.c.movementId)
        assertEquals(1, r.log.calls.count { it.startsWith("compile w1.b") })
        assertEquals(r.c.current!!.generation, r.c.sentGeneration)
    }

    @Test fun staleCompileResultIsDropped() {
        val r = SessionRig(queued = true).start()
        r.c.onAction(UiAction.Play("w1.a", "bach"))
        r.c.onAction(UiAction.Play("w3.a", "bach"))
        r.drain()
        assertEquals(1, r.audio.perfs.size)
        assertEquals("w3.a", r.c.movementId)
    }

    // ─── T12.4 view → listener → room ───

    @Test fun t12_4_viewChangeSendsExactlyOneRoomWithTheListenerAndEmbeddedRoomDb() {
        val r = SessionRig().start()
        for ((v, f) in listOf(ViewId.ACTION to 0, ViewId.ACTION to 1, ViewId.HALL to 0, ViewId.PLAYER to 1)) {
            val before = r.audio.rooms.size
            val dBefore = r.designer.calls.size
            r.c.onAction(UiAction.SetView(v, f))
            assertEquals(before + 1, r.audio.rooms.size)
            assertEquals(dBefore + 1, r.designer.calls.size)
            val call = r.designer.calls.last()
            assertEquals(r.kits.info(InstrumentId.GRAND).embeddedRoomDb, call.embeddedDb, 0f)
            val expect = ListenerRooms.table(InstrumentId.GRAND, v, f)
            val room = FloatArray(3)
            if (v == ViewId.HALL) expect.copyInto(room) else KonzertzimmerAcoustics.PLACEMENTS.getValue(InstrumentId.GRAND).toRoom(expect, room)
            for (k in 0..2) assertEquals("$v/$f[$k]", room[k], call.listener.earRoom[k], 1e-4f)
            assertEquals(v == ViewId.HALL, call.listener.worldLocked)
            assertEquals(ListenerRooms.directWidth(v), call.listener.directWidth, 0f)
            assertEquals(ListenerRooms.benchDistance(InstrumentId.GRAND, null), call.bench, 1e-4f)
        }
        assertTrue(r.log.calls.contains("render.setView HALL 0"))
    }

    // ─── T12.5 status (board-level cases in StatusBoardTest) ───

    @Test fun t12_5_factsCarryActiveStatusInPriorityOrder() {
        val r = SessionRig().start()
        r.c.postStatus(StatusCode.IMPORTED, listOf("a.mid"))
        r.c.postStatus(StatusCode.CRASH_LAST_SESSION, listOf("boom"))
        assertEquals(listOf(StatusCode.CRASH_LAST_SESSION, StatusCode.IMPORTED), r.c.facts().status.map { it.code })
        r.advanceMs(9_000)
        assertTrue(r.c.facts().status.isEmpty())
    }

    // ─── T12.6 seek units ───

    @Test fun t12_6_uiCompanionAndControlSeeksLandOnTheSameSongUs() {
        val r = SessionRig().start()
        r.c.onAction(UiAction.Play("w1.a", "bach"))
        val displayMs = 3_250L
        val songUs = displayMs * 1000 + HK.PRE_ROLL_US
        r.c.onAction(UiAction.Seek(songUs))
        r.c.seek(SeekUnits.displayMsToSongUs(displayMs))    // CompanionServer converts ?ms= the same way
        r.c.controlSeekDisplayMs(displayMs)
        assertEquals(listOf(songUs, songUs, songUs), r.audio.seeks.takeLast(3))
        r.c.controlSeekDisplayMs(10_000_000L)               // clamped to the Performance
        assertEquals(r.c.current!!.durationUs, r.audio.seeks.last())
        assertEquals(3.25f, SeekUnits.songUsToDisplaySec(songUs), 1e-6f)
    }

    // ─── T12.7 resume ───

    @Test fun t12_7_resumePointRoundTrip() {
        val settings = MemSettings()
        val r = SessionRig(settings).start()
        r.c.onAction(UiAction.Play("w1.b", "bach"))
        r.c.onAction(UiAction.SetInstrument(InstrumentId.UPRIGHT))
        r.advanceMs(7_000)
        r.c.onAction(UiAction.PlayPause)                    // pause saves
        val saved = ResumePoint.load(settings)!!
        assertEquals("w1.b", saved.movementId)
        assertEquals(InstrumentId.UPRIGHT, saved.instrument)
        assertEquals("bach", saved.shelfId)
        assertEquals(listOf("w1.b", "w1.c", "w2.a", "w3.a", "w3.b"), saved.playlist)
        assertEquals(0, saved.index)
        val pos = saved.songUs
        assertEquals(7_000_000L, pos)

        val r2 = SessionRig(settings).start()
        assertNull(r2.c.current)
        assertNotNull(r2.c.facts().resumeTitle)
        r2.c.onAction(UiAction.Enter)
        val sent = r2.audio.perfs.last()
        assertEquals(pos, sent.second)
        assertTrue(sent.third)
        assertEquals("w1.b", r2.c.movementId)
        assertEquals(InstrumentId.UPRIGHT, r2.c.instrument)
        assertEquals("bach", r2.c.shelfId)
        assertEquals(listOf("w1.b", "w1.c", "w2.a", "w3.a", "w3.b"), r2.c.playlistIds)
        r2.c.onAction(UiAction.Next)
        assertEquals("w1.c", r2.c.movementId)
        assertEquals(listOf("w1.c", "w1.b"), r2.c.recent.take(2))
    }

    @Test fun resumeIsSavedEvery5sWhilePlaying() {
        val settings = MemSettings()
        val r = SessionRig(settings).start()
        r.c.onAction(UiAction.Play("w1.a", "bach"))
        r.advanceMs(1_000); r.c.tick()
        val first = ResumePoint.load(settings)!!.songUs
        r.advanceMs(2_000); r.c.tick()
        assertEquals(first, ResumePoint.load(settings)!!.songUs)
        r.advanceMs(3_500); r.c.tick()
        assertTrue(ResumePoint.load(settings)!!.songUs > first)
    }

    @Test fun enterWithoutResumeStartsStartHere() {
        val r = SessionRig().start()
        r.c.onAction(UiAction.SetView(ViewId.HALL, 1))
        r.c.onAction(UiAction.Enter)
        assertEquals("w3.a", r.c.movementId)
        assertEquals(SessionController.SHELF_START_HERE, r.c.shelfId)
        assertEquals(ViewId.PLAYER, r.c.view)
        assertTrue(r.log.calls.contains("render.recenter"))
        assertTrue(r.events.contains(UiEvent.KIT_PLAYABLE))
        assertTrue(r.events.contains(UiEvent.MOVEMENT_STARTED))
    }

    // ─── thermal, sync test, now playing, routes ───

    @Test fun thermalLevelDrivesQualityAndDisplayRest() {
        val r = SessionRig().start()
        var brightness = 0f
        r.c.onBrightnessCap = { brightness = it }
        r.c.onThermalLevel(2)
        assertEquals(2, r.c.quality.level)
        assertTrue(r.log.calls.contains("audio.setQuality 2") && r.log.calls.contains("render.setQuality 2"))
        assertEquals(0.6f, brightness, 0f)
        r.c.onThermalLevel(3)
        assertTrue(r.events.contains(UiEvent.REST_ON))
        assertEquals(StatusCode.DISPLAY_REST, r.c.facts().status.single().code)
        r.advanceMs(60_000)
        assertEquals("persistent", 1, r.c.facts().status.size)
        r.c.onThermalLevel(2)
        assertTrue(r.events.contains(UiEvent.REST_OFF))
        assertTrue(r.c.facts().status.isEmpty())
        r.c.setQ0Cap(128)
        assertEquals(QualityLadder.of(2, 128).voiceCap, r.c.quality.voiceCap)
    }

    @Test fun syncTestPlaysSyncClickAndRestoresTheMovement() {
        val r = SessionRig().start()
        r.c.onAction(UiAction.Play("w1.a", "bach"))
        r.advanceMs(3_000)
        r.c.onAction(UiAction.SyncTest(true))
        assertTrue(r.render.flash)
        assertTrue(r.log.calls.any { it.startsWith("synthetic SYNC_CLICK") })
        assertEquals("synth:sync", r.c.current!!.id)
        r.c.onAction(UiAction.SyncTest(false))
        assertFalse(r.render.flash)
        assertEquals("w1.a", r.c.movementId)
        val last = r.audio.perfs.last()
        assertFalse(last.third)
        assertTrue(last.second > 0L)
        assertEquals("w1.a", r.c.recent.first())
    }

    @Test fun nowPlayingJsonIsDisplayTime() {
        val r = SessionRig().start()
        r.c.onAction(UiAction.Play("w1.a", "bach"))
        r.advanceMs(2_400)
        r.c.tick()
        val j = JSONObject(r.c.nowPlayingJson())
        assertEquals("w1.a", j.getString("movementId"))
        assertEquals("grand", j.getString("instrument"))
        assertEquals("player", j.getString("view"))
        assertEquals(2.0, j.getDouble("positionSec"), 0.05)
        assertTrue(j.getBoolean("playing"))
        assertEquals("Composer w1", j.getString("composer"))
    }

    @Test fun newBluetoothDeviceIsAnnouncedOnce() {
        val r = SessionRig().start()
        val bt = RouteInfo(OutputRoute.BLUETOOTH, "bt:AA", 8, "Buds", "")
        r.c.onRouteChanged(bt); r.c.onRouteChanged(bt)
        assertEquals(1, r.c.facts().status.count { it.code == StatusCode.NEW_BT_DEVICE })
        r.c.onAction(UiAction.SetAvLead(120))
        assertEquals(120, r.c.facts().settings.avLeadMs["bt:AA"])
    }

    @Test fun tuningChangeSendsOnlyANewKeyMap() {
        val r = SessionRig().start()
        r.c.onAction(UiAction.Play("w1.a", "bach"))
        r.log.clear()
        r.c.onAction(UiAction.SetTuning(InstrumentId.GRAND,
            com.tropicalstream.hammerklavier.contract.TuningSpec(415f, com.tropicalstream.hammerklavier.contract.Temperament.entries.last())))
        assertEquals(listOf("audio.setKeyMap"), r.log.calls)
        assertEquals(415f, r.c.facts().settings.tuning.getValue(InstrumentId.GRAND).aHz, 0f)
    }
}
