package com.tropicalstream.hammerklavier.ui.model

import com.tropicalstream.hammerklavier.contract.*
import com.tropicalstream.hammerklavier.ui.model.UiFixtures.facts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T10.3: the title waits for KIT_PLAYABLE, tap → Enter, the hint for the first 3 sessions, Tap to continue. */
class FirstRunTest {
    private val voicing = mapOf<InstrumentId, KitState>(InstrumentId.GRAND to KitState.Voicing(0.42f, false))
    private val playable = mapOf<InstrumentId, KitState>(InstrumentId.GRAND to KitState.Voicing(0.55f, true))

    @Test fun titleWaitsForThePlayableKit() {
        val f = facts(kitStates = voicing, sessions = 1, movementId = null)
        val ui = UiStateMachineImpl()
        val t = ui.render(f, 0).title!!
        assertEquals("HAMMERKLAVIER", t.title); assertEquals("Konzertzimmer · Sanssouci 1747", t.subtitle)
        assertEquals("Voicing the grand… 42%", t.line)
        assertEquals("Phone: http://192.168.1.5:19112 · token K7QM4TZP", t.phone)
        assertEquals("Headphones recommended for the bass", t.headphones)
        assertEquals(emptyList<UiAction>(), ui.onGesture(Gesture.TAP, f, 100))
        assertEquals(UiContext.TITLE, ui.context)
        assertEquals("voicing grand 42%", ui.render(f, 200).title!!.pill)       // a tap before then shows the voicing pill
        ui.onEvent(UiEvent.KIT_PLAYABLE, f, 300)
        assertEquals("Voicing the grand… 42%", ui.render(f, 350).title!!.line)    // the event alone is not the truth
        val p = facts(kitStates = playable, sessions = 1, movementId = null)
        ui.onEvent(UiEvent.KIT_PLAYABLE, p, 300)
        assertEquals("Tap to enter the Konzertzimmer · voicing 55%", ui.render(p, 400).title!!.line)
        assertEquals(listOf<UiAction>(UiAction.Enter), ui.onGesture(Gesture.TAP, p, 500))
        assertEquals(UiContext.PLAYING, ui.context)
        assertNull(ui.render(p, 600).title)
    }

    @Test fun anotherInstrumentsPlayableKitDoesNotOpenTheDoor() {
        val f = facts(kitStates = voicing + (InstrumentId.HARPSICHORD to KitState.Complete))
        val ui = UiStateMachineImpl()
        ui.onEvent(UiEvent.KIT_PLAYABLE, f, 0)
        assertEquals(emptyList<UiAction>(), ui.onGesture(Gesture.TAP, f, 10))
    }

    @Test fun creditTimerStartsAtEnterNotOnTheTitle() {
        val f = facts()
        val ui = UiStateMachineImpl()
        ui.render(f, 0)                                            // movement preloaded on the title
        ui.onGesture(Gesture.TAP, f, 20_000)
        assertEquals(HudModel.credit(f), ui.render(f, 21_000).credit)
    }

    @Test fun hintOnFirstRunEvenWithSessionsZero() {
        val f = facts(sessions = 0)
        val ui = UiStateMachineImpl()
        ui.onGesture(Gesture.TAP, f, 0)
        assertEquals(UiText.HINT, ui.render(f, 1_000).hint)
    }

    @Test fun playableFactsAlsoOpenTheDoorAndCompleteDropsThePercent() {
        val ui = UiStateMachineImpl()
        assertEquals("Tap to enter the Konzertzimmer · voicing 55%", ui.render(facts(kitStates = playable), 0).title!!.line)
        val done = facts(kitStates = mapOf(InstrumentId.GRAND to KitState.Complete), companionUrl = null,
            route = UiFixtures.BT)
        val t = ui.render(done, 0).title!!
        assertEquals("Tap to enter the Konzertzimmer", t.line)
        assertEquals("no Wi-Fi: use push_scores.sh", t.phone)
        assertNull(t.headphones)
    }

    @Test fun resumePointOffersTapToContinue() {
        val ui = UiStateMachineImpl()
        val f = facts(resumeTitle = "Sonata op. 106 · I. Allegro")
        assertEquals("Tap to continue: «Sonata op. 106 · I. Allegro»", ui.render(f, 0).title!!.line)
        assertEquals(listOf<UiAction>(UiAction.Enter), ui.onGesture(Gesture.TAP, f, 0))
    }

    @Test fun hintOnlyForTheFirstThreeSessions() {
        for (s in 1..5) {
            val f = facts(sessions = s)
            val ui = UiStateMachineImpl()
            ui.onGesture(Gesture.TAP, f, 0)
            val h = ui.render(f, 1_000).hint
            if (s <= 3) assertEquals(UiText.HINT, h) else assertNull(h)
        }
    }

    @Test fun titleTripleRecentresAndBackLeaves() {
        val ui = UiStateMachineImpl(); val f = facts(kitStates = voicing)
        assertEquals(listOf<UiAction>(UiAction.Recenter), ui.onGesture(Gesture.TRIPLE, f, 0))
        assertEquals(listOf<UiAction>(UiAction.Leave), ui.onGesture(Gesture.SYSTEM_BACK, f, 0))
    }
}
