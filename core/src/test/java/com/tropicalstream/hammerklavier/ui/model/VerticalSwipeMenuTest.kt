package com.tropicalstream.hammerklavier.ui.model

import com.tropicalstream.hammerklavier.contract.*
import com.tropicalstream.hammerklavier.ui.model.UiFixtures.facts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * T10.1 (vertical swipes in menus): in every menu and sub-menu, swipe down moves the cursor one row
 * down and swipe up one row up (clamped, the page of 7 following the cursor), Reverse swipe leaves
 * them alone, and forward/back keep next/previous row. Menus are reached with swipe down + tap only.
 */
@RunWith(Parameterized::class)
class VerticalSwipeMenuTest(private val name: String, private val path: List<String>, private val reverse: Boolean) {

    companion object {
        private val PATHS = listOf(
            "Transport" to listOf(),
            "Instrument" to listOf("Instrument"),
            "Library" to listOf("Library"),
            "Shelf (Start here)" to listOf("Library", "Start here"),
            "Work (movements)" to listOf("Library", "Beethoven", "Beethoven · Sonata op. 106"),
            "More" to listOf("More"),
            "Sound" to listOf("More", "Sound"),
            "Temperament (2 pages)" to listOf("More", "Sound", "Temperament"),
            "Pitch" to listOf("More", "Sound", "Pitch"),
            "Resonance" to listOf("More", "Sound", "Resonance"),
            "Reverb" to listOf("More", "Sound", "Reverb"),
            "Speaker bass" to listOf("More", "Sound", "Speaker bass"),
            "Sight" to listOf("More", "Sight"),
            "Room" to listOf("More", "Sight", "Room"),
            "Palette" to listOf("More", "Sight", "Palette"),
            "Upright finish" to listOf("More", "Sight", "Upright finish"),
            "Stereo depth" to listOf("More", "Sight", "Stereo depth"),
            "Edge overlay" to listOf("More", "Sight", "Edge"),
            "Calibrate" to listOf("More", "Calibrate"))

        @JvmStatic @Parameterized.Parameters(name = "{0} reverse={2}")
        fun table(): List<Array<Any>> = PATHS.flatMap { (n, p) -> listOf(arrayOf<Any>(n, p, false), arrayOf<Any>(n, p, true)) }

        fun highlighted(ui: UiStateMachineImpl, f: UiFacts): String = ui.render(f, 0).menu!!.let { it.rows[it.highlight] }

        /** Walks to the row whose label starts with [prefix] with swipe up / swipe down only, and taps it. */
        fun openBySwipes(ui: UiStateMachineImpl, f: UiFacts, prefix: String) {
            repeat(40) { ui.onGesture(Gesture.UP, f, 0) }
            var guard = 0
            while (!highlighted(ui, f).startsWith(prefix)) {
                val before = ui.cursor
                ui.onGesture(Gesture.DOWN, f, 0)
                assertTrue("no row '$prefix' in ${ui.menuPath.last()}", ui.cursor == before + 1 && guard++ < 40)
            }
            ui.onGesture(Gesture.TAP, f, 0)
        }
    }

    @Test fun upAndDownMoveOneRow() {
        val f = facts(settings = UiFixtures.settings(reverseSwipe = reverse))
        val ui = UiFixtures.entered(f)
        ui.onGesture(Gesture.DOUBLE, f, 0)
        for (step in path) openBySwipes(ui, f, step)
        assertEquals(UiContext.MENU, ui.context)
        val depth = ui.menuPath.size
        assertEquals(path.size + 1, depth)

        repeat(40) { ui.onGesture(Gesture.UP, f, 0) }
        assertEquals("up clamps at the first row", 0, ui.cursor)
        var last = 0
        while (true) {
            val before = ui.cursor
            assertEquals(emptyList<UiAction>(), ui.onGesture(Gesture.DOWN, f, 0))
            if (ui.cursor == before) break
            assertEquals("down moves exactly one row", before + 1, ui.cursor)
            val card = ui.render(f, 0).menu!!
            assertEquals(ui.cursor / MenuTree.PAGE, card.page)
            assertEquals(ui.cursor % MenuTree.PAGE, card.highlight)
            last = ui.cursor
        }
        assertTrue("$name has more than one row", last >= 1)
        while (ui.cursor > 0) {
            val before = ui.cursor
            ui.onGesture(Gesture.UP, f, 0)
            assertEquals("up moves exactly one row", before - 1, ui.cursor)
        }
        // forward/back keep next/previous row (swapped by Reverse swipe); vertical never changes level
        ui.onGesture(if (reverse) Gesture.BACK else Gesture.FORWARD, f, 0); assertEquals(1, ui.cursor)
        ui.onGesture(if (reverse) Gesture.FORWARD else Gesture.BACK, f, 0); assertEquals(0, ui.cursor)
        assertEquals(depth, ui.menuPath.size)
        ui.onGesture(Gesture.DOUBLE, f, 0); assertEquals(depth - 1, ui.menuPath.size)
    }
}

class VerticalSwipeOverlayTest {
    @Test fun creditsAndAboutScrollByPageWithVerticalSwipes() {
        val f = facts()
        val ui = UiFixtures.entered(f)
        ui.onGesture(Gesture.DOUBLE, f, 0)
        ui.creditsText = (1..20).joinToString("\n") { "line $it" }
        VerticalSwipeMenuTest.openBySwipes(ui, f, "More"); VerticalSwipeMenuTest.openBySwipes(ui, f, "Credits")
        assertEquals(UiContext.PANEL, ui.context)
        ui.onGesture(Gesture.DOWN, f, 0); assertEquals(1, ui.render(f, 0).panel!!.page)
        ui.onGesture(Gesture.DOWN, f, 0); ui.onGesture(Gesture.DOWN, f, 0); assertEquals(2, ui.render(f, 0).panel!!.page)
        ui.onGesture(Gesture.UP, f, 0); assertEquals(1, ui.render(f, 0).panel!!.page)
        ui.onGesture(Gesture.DOUBLE, f, 0)
        VerticalSwipeMenuTest.openBySwipes(ui, f, "About")
        assertEquals(UiContext.PANEL, ui.context)
        ui.onGesture(Gesture.DOWN, f, 0); ui.onGesture(Gesture.UP, f, 0); assertEquals(0, ui.render(f, 0).panel!!.page)
    }

    @Test fun adjustCardsTakeVerticalAsTheCoarseStep() {
        val f = facts()
        val ui = UiFixtures.entered(f)
        ui.onGesture(Gesture.DOUBLE, f, 0)
        VerticalSwipeMenuTest.openBySwipes(ui, f, "Position")
        assertEquals(UiContext.ADJUST, ui.context)
        ui.onGesture(Gesture.UP, f, 0); assertEquals(252_000L, ui.adjustDisplayMs)
        ui.onGesture(Gesture.DOWN, f, 0); ui.onGesture(Gesture.DOWN, f, 0); assertEquals(132_000L, ui.adjustDisplayMs)
        ui.onGesture(Gesture.DOUBLE, f, 0)
        VerticalSwipeMenuTest.openBySwipes(ui, f, "More"); VerticalSwipeMenuTest.openBySwipes(ui, f, "Sound")
        VerticalSwipeMenuTest.openBySwipes(ui, f, "Tempo")
        assertEquals(listOf<UiAction>(UiAction.SetTempo(120)), ui.onGesture(Gesture.UP, f, 0))
        assertEquals(listOf<UiAction>(UiAction.SetTempo(100)), ui.onGesture(Gesture.DOWN, f, 0))
    }
}
