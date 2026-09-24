package com.tropicalstream.hammerklavier.ui.model

import com.tropicalstream.hammerklavier.contract.*
import com.tropicalstream.hammerklavier.ui.model.UiFixtures.facts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T10.2: rows, paging by 7, back, Adjust clamps, the instrument sub-list with voicing %. */
class MenuTest {
    private fun open(f: UiFacts = facts()) = UiFixtures.entered(f).also { it.onGesture(Gesture.DOUBLE, f, 0) }
    private fun UiStateMachineImpl.go(f: UiFacts, vararg gs: Gesture): List<UiAction> {
        var last = emptyList<UiAction>(); for (g in gs) last = onGesture(g, f, 0); return last
    }
    private fun UiStateMachineImpl.to(f: UiFacts, row: Int) { repeat(row) { onGesture(Gesture.FORWARD, f, 0) } }

    @Test fun transportRowsInOrder() {
        val f = facts()
        val m = open(f).render(f, 0).menu!!
        assertEquals("Transport", m.title)
        assertEquals(listOf("❚❚ Pause", "Next: «II. Scherzo»", "Previous", "Position 3:12 / 9:53 ›", "Instrument: Grand ›",
            "Library ›", "More ›"), m.rows)
        assertEquals(0, m.highlight)
        assertEquals(UiText.MENU_FOOTER, m.footer)
        assertEquals("▶ Play", open(facts(playing = false)).render(facts(playing = false), 0).menu!!.rows[0])
    }

    @Test fun cursorClampsAndBackClosesAtRoot() {
        val f = facts()
        val ui = open(f)
        ui.go(f, Gesture.BACK); assertEquals(0, ui.cursor)
        repeat(20) { ui.onGesture(Gesture.FORWARD, f, 0) }; assertEquals(6, ui.cursor)
        ui.go(f, Gesture.DOUBLE); assertEquals(UiContext.PLAYING, ui.context)
    }

    @Test fun pagingBySeven() {
        // Temperament has 8 rows: two pages.
        val f = facts()
        val ui = open(f)
        ui.to(f, 6); ui.go(f, Gesture.TAP)                      // More
        ui.go(f, Gesture.TAP)                                    // Sound
        ui.go(f, Gesture.FORWARD, Gesture.TAP)                   // Temperament
        assertEquals(MenuId.TEMPERAMENT, ui.menuPath.last())
        val p1 = ui.render(f, 0).menu!!
        assertEquals(7, p1.rows.size); assertEquals(0, p1.page); assertEquals(2, p1.pages)
        assertTrue(p1.rows[0].endsWith("✓"))                    // Equal is current and highlighted
        ui.go(f, Gesture.DOWN)
        val p2 = ui.render(f, 0).menu!!
        assertEquals(1, p2.page); assertEquals(listOf(Temperament.MEANTONE_QUARTER.label), p2.rows); assertEquals(0, p2.highlight)
        ui.go(f, Gesture.UP); assertEquals(0, ui.cursor)
        ui.go(f, Gesture.FORWARD)
        val acts = ui.go(f, Gesture.TAP)
        assertEquals(listOf<UiAction>(UiAction.SetTuning(InstrumentId.GRAND, TuningSpec(440f, Temperament.WERCKMEISTER_III))), acts)
        assertEquals(MenuId.SOUND, ui.menuPath.last())           // an option goes back one level
    }

    @Test fun libraryShelvesWorksAndMovements() {
        val f = facts(recent = listOf("scarlatti.k141.1"))
        val ui = open(f)
        ui.to(f, 5)
        assertEquals(listOf<UiAction>(UiAction.Rescan), ui.go(f, Gesture.TAP))
        val shelves = ui.render(f, 0).menu!!.rows
        // Imported is empty (hidden); Recently played follows Start here.
        assertEquals(listOf("Start here (3)", "Recently played", "Bach · teaching the keyboard", "Scarlatti", "Beethoven"), shelves)
        ui.to(f, 4); ui.go(f, Gesture.TAP)
        assertEquals(listOf("Beethoven · Sonata op. 106 \"Hammerklavier\"   35:35  G"), ui.render(f, 0).menu!!.rows)
        ui.go(f, Gesture.TAP)
        val mv = ui.render(f, 0).menu!!.rows
        assertEquals("Play all ▶", mv[0]); assertEquals("I. Allegro   9:53", mv[1]); assertEquals(5, mv.size)
        assertEquals(listOf<UiAction>(UiAction.Play("beethoven.op106.1", "beethoven")), ui.go(f, Gesture.TAP))
        assertEquals(UiContext.PLAYING, ui.context)
    }

    @Test fun importedFirstEvenWhenLastInTheLibrary() {
        val lib0 = UiFixtures.library
        val imp = Shelf(MenuTree.SHELF_IMPORTED, "Imported", listOf(lib0.works.keys.first()))
        val lib = LibraryModel(shelves = lib0.shelves.filter { it.id != MenuTree.SHELF_IMPORTED } + imp, works = lib0.works,
            movements = lib0.movements, sources = lib0.sources, startHere = emptyList())
        val f = facts(library = lib, recent = listOf("scarlatti.k141.1"))
        val ui = open(f)
        ui.to(f, 5); ui.go(f, Gesture.TAP)
        val rows = ui.render(f, 0).menu!!.rows
        assertEquals(listOf("Imported (1)", "Recently played"), rows.take(2))
    }

    @Test fun startHereAndRecentCarryTheirShelfIds() {
        val f = facts(recent = listOf("scarlatti.k141.1"))
        val ui = open(f); ui.to(f, 5); ui.go(f, Gesture.TAP)
        ui.go(f, Gesture.TAP)                                   // Start here
        ui.to(f, 1)
        assertEquals(listOf<UiAction>(UiAction.Play("scarlatti.k141.1", MenuTree.SHELF_START)), ui.go(f, Gesture.TAP))
        val ui2 = open(f); ui2.to(f, 5); ui2.go(f, Gesture.TAP); ui2.go(f, Gesture.FORWARD, Gesture.TAP)
        assertEquals(listOf<UiAction>(UiAction.Play("scarlatti.k141.1", MenuTree.SHELF_RECENT)), ui2.go(f, Gesture.TAP))
        // A single-movement work plays on tap.
        val ui3 = open(f); ui3.to(f, 5); ui3.go(f, Gesture.TAP); ui3.to(f, 3); ui3.go(f, Gesture.TAP)
        assertEquals(listOf<UiAction>(UiAction.Play("scarlatti.k141.1", "scarlatti")), ui3.go(f, Gesture.TAP))
    }

    @Test fun instrumentSubListWithPieceDefaultAndVoicing() {
        val f = facts(kitStates = mapOf(InstrumentId.GRAND to KitState.Complete,
            InstrumentId.UPRIGHT to KitState.Voicing(0.42f, false), InstrumentId.HARPSICHORD to KitState.Missing))
        val ui = open(f); ui.to(f, 4); ui.go(f, Gesture.TAP)
        assertEquals(listOf("Grand (piece default)  ✓", "Upright · voicing 42%", "Harpsichord · Bach era"), ui.render(f, 0).menu!!.rows)
        assertEquals(0, ui.cursor)
        ui.go(f, Gesture.FORWARD)
        assertEquals(listOf<UiAction>(UiAction.SetInstrument(InstrumentId.UPRIGHT)), ui.go(f, Gesture.TAP))
        assertEquals(UiContext.PLAYING, ui.context)
    }

    @Test fun registrationOnlyOnTheHarpsichord() {
        val g = facts(); val h = facts(instrument = InstrumentId.HARPSICHORD)
        val soundG = MenuTree.rows(MenuLevel(MenuId.SOUND), g).map { it.label }
        val soundH = MenuTree.rows(MenuLevel(MenuId.SOUND), h).map { it.label }
        assertTrue("Registration ›" !in soundG); assertTrue("Registration ›" in soundH)
        assertEquals("Tempo 100% ›", soundG[0])
        val pitch = MenuTree.rows(MenuLevel(MenuId.PITCH), h)
        assertEquals(listOf("A440", "A430", "A415  ✓", "A392"), pitch.map { it.label })
    }

    @Test fun tempoAdjustClampsFiftyToOneFifty() {
        val f = facts()
        val ui = open(f); ui.to(f, 6); ui.go(f, Gesture.TAP); ui.go(f, Gesture.TAP); ui.go(f, Gesture.TAP)   // More › Sound › Tempo
        assertEquals(UiContext.ADJUST, ui.context)
        assertEquals(listOf<UiAction>(UiAction.SetTempo(105)), ui.go(f, Gesture.FORWARD))
        assertEquals(listOf<UiAction>(UiAction.SetTempo(125)), ui.go(f, Gesture.UP))
        repeat(10) { ui.onGesture(Gesture.UP, f, 0) }
        assertEquals(150L, ui.adjustDisplayMs)
        repeat(30) { ui.onGesture(Gesture.DOWN, f, 0) }
        assertEquals(50L, ui.adjustDisplayMs)
        assertEquals("50%", ui.render(f, 0).adjust!!.value)
        // Cancel restores the original tempo.
        assertEquals(listOf<UiAction>(UiAction.SetTempo(100)), ui.go(f, Gesture.DOUBLE))
        assertEquals(UiContext.MENU, ui.context)
    }

    @Test fun positionAdjustSeeksWithinTheMovement() {
        val f = facts()                                          // 3:12 of 9:53
        val ui = open(f); ui.to(f, 3); ui.go(f, Gesture.TAP)
        ui.go(f, Gesture.FORWARD); assertEquals(202_000L, ui.adjustDisplayMs)
        ui.go(f, Gesture.DOWN); assertEquals(142_000L, ui.adjustDisplayMs)
        repeat(20) { ui.onGesture(Gesture.UP, f, 0) }
        assertEquals(593_000L, ui.adjustDisplayMs)               // clamped to the end
        assertEquals("9:53 / 9:53", ui.render(f, 0).adjust!!.value)
        repeat(20) { ui.onGesture(Gesture.DOWN, f, 0) }
        assertEquals(0L, ui.adjustDisplayMs)
        ui.go(f, Gesture.FORWARD)
        assertEquals(listOf<UiAction>(UiAction.Seek(10_000_000L + HK.PRE_ROLL_US)), ui.go(f, Gesture.TAP))
        // Cancel emits nothing.
        ui.go(f, Gesture.TAP); ui.go(f, Gesture.FORWARD)
        assertEquals(emptyList<UiAction>(), ui.go(f, Gesture.DOUBLE))
    }

    @Test fun sightTogglesStay() {
        val f = facts()
        val rows = MenuTree.rows(MenuLevel(MenuId.SIGHT), f)
        assertEquals("Look-around: On", rows[4].label)
        val c = rows[4].choice as Choice.Do
        assertEquals(listOf<UiAction>(UiAction.SetSight(lookAround = false)), c.actions); assertEquals(After.STAY, c.after)
        val room = MenuTree.rows(MenuLevel(MenuId.ROOM), f)
        assertEquals("Auto  ✓", room[0].label)
        assertEquals(listOf<UiAction>(UiAction.SetSight(autoRoom = true)), (room[0].choice as Choice.Do).actions)
    }

    @Test fun soundNoiseTogglesStay() {
        val rows = MenuTree.rows(MenuLevel(MenuId.SOUND), facts())
        val rel = rows.first { it.label.startsWith("Key release noise") }
        val ped = rows.first { it.label.startsWith("Pedal noise") }
        assertEquals("Key release noise: On", rel.label); assertEquals("Pedal noise: On", ped.label)
        assertEquals(listOf<UiAction>(UiAction.SetMix(releaseNoises = false)), (rel.choice as Choice.Do).actions)
        assertEquals(listOf<UiAction>(UiAction.SetMix(pedalNoises = false)), (ped.choice as Choice.Do).actions)
        assertEquals(After.STAY, (rel.choice as Choice.Do).after)
    }

    @Test fun panelsPageBySevenAndImportRotatesOnlyWhenArmed() {
        val f = facts()
        val ui = UiFixtures.entered(f)
        ui.creditsText = (1..20).joinToString("\n") { "line $it" }
        ui.openPanel(PanelKind.CREDITS)
        var p = ui.render(f, 0).panel!!
        assertEquals(3, p.pages); assertEquals("line 1", p.lines[0]); assertEquals(7, p.lines.size)
        ui.onGesture(Gesture.FORWARD, f, 0); ui.onGesture(Gesture.DOWN, f, 0); ui.onGesture(Gesture.DOWN, f, 0)
        p = ui.render(f, 0).panel!!
        assertEquals(2, p.page); assertEquals(listOf("line 15", "line 16", "line 17", "line 18", "line 19", "line 20"), p.lines)
        ui.onGesture(Gesture.UP, f, 0); assertEquals(1, ui.render(f, 0).panel!!.page)
        ui.openPanel(PanelKind.IMPORT)
        val lines = ui.render(f, 0).panel!!.lines
        assertTrue(lines.contains("Token: K7QM4TZP")); assertTrue(lines.contains("adb: tools/device/push_scores.sh"))
        assertEquals(emptyList<UiAction>(), ui.onGesture(Gesture.TRIPLE, f, 0))     // arm
        assertTrue(ui.render(f, 0).panel!!.lines.contains("Tap now: rotate token"))
        assertEquals(listOf<UiAction>(UiAction.RotateToken), ui.onGesture(Gesture.TAP, f, 0))
        assertEquals(UiContext.PANEL, ui.context)
        assertEquals(emptyList<UiAction>(), ui.onGesture(Gesture.TAP, f, 0))        // disarmed: tap closes
        assertTrue(ui.context != UiContext.PANEL)
        ui.openPanel(PanelKind.ABOUT)
        assertTrue(ui.render(f, 0).panel!!.lines[0].startsWith("Hammerklavier 1.0"))
    }
}
