package com.tropicalstream.hammerklavier.ui.model

import com.tropicalstream.hammerklavier.contract.*
import com.tropicalstream.hammerklavier.ui.model.UiFixtures.facts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T10.4: HUD strings, credit for 8 s, pills, status priority order, every StatusCode has a text. */
class HudTest {
    @Test fun hudStringsExcludeThePreRoll() {
        val f = facts()
        val h = UiFixtures.entered(f).render(f, 0).hud!!
        assertEquals("Beethoven · Sonata op. 106 \"Hammerklavier\"", h.work)
        assertEquals("I. Allegro · bar 112", h.movement)
        assertEquals("Grand · A440 Equal", h.tuning)
        assertEquals("3:12 / 9:53", h.time)
        assertEquals(0.325f, h.progress, 0.003f)
        assertEquals("0:00 / 0:00", UiText.positionLine(HK.PRE_ROLL_US - 1, HK.PRE_ROLL_US))
        assertEquals("0:00", UiText.clock(UiText.displaySec(0)))
        assertEquals("1:02:03", UiText.clock(3723))
    }

    @Test fun harpsichordTuningLine() {
        val f = facts(instrument = InstrumentId.HARPSICHORD)
        assertEquals("Harpsichord · A415 Werckmeister III · 8′+4′", HudModel.hud(f).tuning)
        assertEquals("Harpsichord · A415 Werckmeister III · 8′", HudModel.hud(facts(instrument = InstrumentId.HARPSICHORD,
            settings = UiFixtures.settings(registration = HK.REG_8))).tuning)
    }

    @Test fun creditShowsForTheFirstEightSecondsOfEveryMovement() {
        val f = facts()
        val ui = UiFixtures.entered(f)
        ui.render(f, 0)
        assertEquals("Performance: Bernd Krueger · piano-midi.de · CC BY-SA 3.0 DE", ui.render(f, 1_000).credit)
        assertEquals("Performance: Bernd Krueger · piano-midi.de · CC BY-SA 3.0 DE", ui.render(f, 7_999).credit)
        assertNull(ui.render(f, 8_001).credit)
        val g = facts(movementId = "scarlatti.k141.1")
        assertEquals("Harpsichord: John Sankey", ui.render(g, 20_000).credit)
        assertNull(ui.render(g, 28_001).credit)
        ui.onEvent(UiEvent.MOVEMENT_STARTED, g, 30_000)
        assertEquals("Harpsichord: John Sankey", ui.render(g, 31_000).credit)
    }

    @Test fun pillsAndTheirPlace() {
        val f = facts(playing = false, quality = 2, kitStates = mapOf(InstrumentId.GRAND to KitState.Fallback(FallbackReason.BANK_MISSING),
            InstrumentId.UPRIGHT to KitState.Voicing(0.42f, false)), perfInfo = UiFixtures.perf(folded = 3, merged = 7, finger = true))
        val h = HudModel.hud(f)
        assertEquals(listOf("voicing upright 42%", "paused", "▲ warm", "stand-in tones", "3 notes folded", "finger-pedalled",
            "7 channels merged"), h.pills)
        assertFalse(h.pillsTopRight)
        assertTrue(HudModel.hud(facts(view = ViewId.PLAYER, framing = 1)).pillsTopRight)
        assertFalse(HudModel.hud(facts(view = ViewId.HALL, framing = 1)).pillsTopRight)
        assertEquals(emptyList<String>(), HudModel.hud(facts(quality = 1)).pills)
    }

    @Test fun viewToastForOneAndAHalfSeconds() {
        val ui = UiFixtures.entered()
        ui.render(facts(), 0)
        val g = facts(view = ViewId.ACTION, framing = 1)
        assertEquals("Action · overhead", ui.render(g, 100).toast)
        assertEquals("Action · overhead", ui.render(g, 1_599).toast)
        assertNull(ui.render(g, 1_601).toast)
        val names = ViewId.entries.flatMap { v -> listOf(0, 1).map { UiText.viewToast(v, it) } }
        assertEquals(listOf("Player", "Player · follow", "Action · hammers", "Action · overhead", "Hall · Konzertzimmer",
            "Hall · life-size"), names)
    }

    @Test fun statusPriorityLowerNumberWins() {
        val items = listOf(StatusItem(StatusCode.IMPORTED, listOf("bwv1006.mid", "2311", "221"), 10_000),
            StatusItem(StatusCode.FALLBACK, listOf("BANK_MISSING"), 10_000),
            StatusItem(StatusCode.DISPLAY_REST, emptyList(), 10_000),
            StatusItem(StatusCode.CRASH_LAST_SESSION, listOf("java.lang.IllegalStateException: boom"), 500))
        val f = facts(status = items)
        val ui = UiFixtures.entered(f)
        assertEquals("Last session ended unexpectedly: java.lang.IllegalStateException: boom", ui.render(f, 0).status)
        assertEquals("Stand-in tones: sample bank not installed", ui.render(f, 600).status)
        assertEquals("Imported \"bwv1006.mid\" · 2,311 notes · 3:41", UiText.status(items[0]))
        assertNull(ui.render(f, 10_000).status)
        val order = listOf(StatusCode.CRASH_LAST_SESSION, StatusCode.IMPORT_FAILED, StatusCode.FALLBACK, StatusCode.DISPLAY_REST,
            StatusCode.IMPORTED).map { UiText.priority(it) }
        assertEquals(listOf(1, 2, 3, 4, 5), order)
    }

    @Test fun everyCodeHasAText() {
        for (c in StatusCode.entries) {
            assertTrue(c.name, UiText.status(c, emptyList()).isNotBlank())
            assertTrue(c.name, UiText.priority(c) in 1..5)
        }
        for (r in RejectReason.entries) assertTrue(r.name, UiText.reject(r, null).isNotBlank())
        for (r in FallbackReason.entries) assertTrue(r.name, UiText.fallback(r).isNotBlank())
        for (w in PerfWarning.entries) assertTrue(w.name, UiText.perfWarning(w, 2, 2).isNotBlank())
        assertEquals("Couldn't read \"x.mid\": truncated track",
            UiText.status(StatusCode.IMPORT_FAILED, listOf("x.mid", "TRUNCATED")))
        assertEquals("Permission denied: run push_scores.sh",
            UiText.status(StatusCode.IMPORT_FAILED, listOf("x.mid", "PERMISSION_DENIED")))
        assertEquals("Audio stopped: output lost", UiText.status(StatusCode.AUDIO_STOPPED, listOf("output lost")))
        assertEquals("Resting the display to cool · music continues", UiText.status(StatusCode.DISPLAY_REST, emptyList()))
        assertEquals("New headphones: More › Calibrate › A/V sync", UiText.status(StatusCode.NEW_BT_DEVICE, emptyList()))
    }

    @Test fun importResultReachesTheImportPanel() {
        val f = facts(status = listOf(StatusItem(StatusCode.IMPORTED, listOf("a.mid", "12", "61"), 9_000)))
        val ui = UiFixtures.entered(f)
        ui.render(f, 0)
        ui.openPanel(PanelKind.IMPORT)
        assertTrue(ui.render(facts(), 20_000).panel!!.lines.any { it.contains("Imported \"a.mid\"") })
    }

    @Test fun thousandsAndWrap() {
        assertEquals("2,311", UiText.thousands(2311)); assertEquals("999", UiText.thousands(999))
        assertEquals("1,000,000", UiText.thousands(1_000_000))
        for (l in UiText.wrap(UiText.ABOUT_LINE)) assertTrue(l.length <= UiText.PANEL_COLUMNS)
    }
}
