package com.tropicalstream.hammerklavier.ui.model

import com.tropicalstream.hammerklavier.contract.*
import com.tropicalstream.hammerklavier.ui.model.UiFixtures.facts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T10.5: swatches 8–68 step 4; A/V lead ±5 ms within 0–400, per route key. */
class CardsTest {
    @Test fun floorSwatches() {
        assertEquals((8..68 step 4).toList(), UiStateMachineImpl.FLOOR_LEVELS)
        assertEquals(16, UiStateMachineImpl.FLOOR_LEVELS.size)
        val f = facts()
        val ui = UiFixtures.entered(f)
        assertEquals(emptyList<UiAction>(), ui.openCard(CardKind.FLOOR, f))
        val s = ui.render(f, 0)
        assertTrue(s.stageHidden); assertEquals(22, s.floor!!.selected)
        ui.onGesture(Gesture.FORWARD, f, 0); assertEquals(24, ui.cardLevel)
        ui.onGesture(Gesture.BACK, f, 0); ui.onGesture(Gesture.DOWN, f, 0); assertEquals(16, ui.cardLevel)
        repeat(30) { ui.onGesture(Gesture.BACK, f, 0) }; assertEquals(8, ui.cardLevel)
        repeat(30) { ui.onGesture(Gesture.UP, f, 0) }; assertEquals(68, ui.cardLevel)
        assertEquals(listOf<UiAction>(UiAction.SetPresenceFloor(68)), ui.onGesture(Gesture.TAP, f, 0))
        assertFalse(ui.render(f, 0).stageHidden)
        ui.openCard(CardKind.FLOOR, f); ui.onGesture(Gesture.FORWARD, f, 0)
        assertEquals(emptyList<UiAction>(), ui.onGesture(Gesture.DOUBLE, f, 0))     // cancel stores nothing
    }

    @Test fun syncLeadClampsAndIsPerRoute() {
        val f = facts(route = UiFixtures.BT, settings = UiFixtures.settings(avLeadMs = mapOf("speaker" to 30, "bt:AA:BB" to 390)))
        val ui = UiFixtures.entered(f)
        assertEquals(listOf<UiAction>(UiAction.SyncTest(true)), ui.openCard(CardKind.SYNC, f))
        assertEquals(390, ui.render(f, 0).sync!!.leadMs)
        assertEquals("Sony WH", ui.render(f, 0).sync!!.route)
        assertEquals(emptyList<UiAction>(), ui.onGesture(Gesture.FORWARD, f, 0))      // no storing preview
        ui.onGesture(Gesture.UP, f, 0); ui.onGesture(Gesture.UP, f, 0)
        assertEquals(400, ui.cardLevel)                                                // clamped at 400
        assertEquals(listOf<UiAction>(UiAction.SyncTest(false)), ui.onGesture(Gesture.DOUBLE, f, 0))   // cancel stores nothing

        val s = facts(settings = UiFixtures.settings(avLeadMs = mapOf("bt:AA:BB" to 390)))       // speaker: default 30
        ui.openCard(CardKind.SYNC, s)
        assertEquals(30, ui.cardLevel)
        repeat(10) { ui.onGesture(Gesture.BACK, s, 0) }
        assertEquals(0, ui.cardLevel)
        assertEquals(listOf(UiAction.SetAvLead(0), UiAction.SyncTest(false)), ui.onGesture(Gesture.TAP, s, 0))
    }

    @Test fun bluetoothWithNoStoredKeyStepAndCancelEmitsNoSetAvLead() {
        val f = facts(route = UiFixtures.BT)
        val ui = UiFixtures.entered(f)
        ui.openCard(CardKind.SYNC, f)
        val acts = ArrayList<UiAction>()
        repeat(3) { acts += ui.onGesture(Gesture.FORWARD, f, 0) }
        acts += ui.onGesture(Gesture.DOUBLE, f, 0)
        assertFalse(acts.any { it is UiAction.SetAvLead })
    }
}
