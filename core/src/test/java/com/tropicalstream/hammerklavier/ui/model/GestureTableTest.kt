package com.tropicalstream.hammerklavier.ui.model

import com.tropicalstream.hammerklavier.contract.*
import com.tropicalstream.hammerklavier.ui.model.UiFixtures.facts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** T10.1: the complete §1.3 table, context × gesture → actions and the context afterwards. */
@RunWith(Parameterized::class)
class GestureTableTest(private val ctx: UiContext, private val g: Gesture, private val expected: List<UiAction>,
                       private val after: UiContext, private val kind: String) {

    companion object {
        private val PRE = HK.PRE_ROLL_US
        private val SEEK = UiAction.Seek(192_000L * 1000 + PRE)

        private fun row(c: UiContext, vararg cells: Pair<Gesture, Pair<List<UiAction>, UiContext>>) = rowK(c, "", *cells)
        private fun rowK(c: UiContext, k: String, vararg cells: Pair<Gesture, Pair<List<UiAction>, UiContext>>) =
            cells.map { (g, r) -> arrayOf<Any>(c, g, r.first, r.second, k) }

        private infix fun List<UiAction>.to(c: UiContext) = Pair(this, c)
        private val none = emptyList<UiAction>()

        @JvmStatic @Parameterized.Parameters(name = "{0}{4} × {1}")
        fun table(): List<Array<Any>> {
            val t = UiContext.TITLE; val p = UiContext.PLAYING; val m = UiContext.MENU; val a = UiContext.ADJUST
            val c = UiContext.CARD; val pa = UiContext.PANEL; val r = UiContext.REST
            val out = ArrayList<Array<Any>>()
            out += row(t, Gesture.FORWARD to (none to t), Gesture.BACK to (none to t), Gesture.UP to (none to t),
                Gesture.DOWN to (none to t), Gesture.TAP to (listOf<UiAction>(UiAction.Enter) to p), Gesture.DOUBLE to (none to t),
                Gesture.TRIPLE to (listOf<UiAction>(UiAction.Recenter) to t), Gesture.SYSTEM_BACK to (listOf<UiAction>(UiAction.Leave) to t))
            out += row(p, Gesture.FORWARD to (listOf<UiAction>(UiAction.SetView(ViewId.ACTION, 0)) to p),
                Gesture.BACK to (listOf<UiAction>(UiAction.SetView(ViewId.HALL, 0)) to p),
                Gesture.UP to (listOf<UiAction>(UiAction.SetView(ViewId.PLAYER, 1)) to p),
                Gesture.DOWN to (listOf<UiAction>(UiAction.SetView(ViewId.PLAYER, 1)) to p),
                Gesture.TAP to (listOf<UiAction>(UiAction.PlayPause) to p), Gesture.DOUBLE to (none to m),
                Gesture.TRIPLE to (listOf(UiAction.Recenter, UiAction.ShowHud) to p),
                Gesture.SYSTEM_BACK to (listOf<UiAction>(UiAction.Leave) to p))
            out += row(m, Gesture.FORWARD to (none to m), Gesture.BACK to (none to m), Gesture.UP to (none to m),
                Gesture.DOWN to (none to m), Gesture.TAP to (listOf<UiAction>(UiAction.PlayPause) to m), Gesture.DOUBLE to (none to p),
                Gesture.TRIPLE to (listOf<UiAction>(UiAction.Recenter) to m), Gesture.SYSTEM_BACK to (none to p))
            out += row(a, Gesture.FORWARD to (none to a), Gesture.BACK to (none to a), Gesture.UP to (none to a),
                Gesture.DOWN to (none to a), Gesture.TAP to (listOf<UiAction>(SEEK) to m), Gesture.DOUBLE to (none to m),
                Gesture.TRIPLE to (none to a), Gesture.SYSTEM_BACK to (none to m))
            out += rowK(c, "(SYNC)", Gesture.FORWARD to (none to c), Gesture.BACK to (none to c),
                Gesture.UP to (none to c), Gesture.DOWN to (none to c),
                Gesture.TAP to (listOf(UiAction.SetAvLead(30), UiAction.SyncTest(false)) to m),
                Gesture.DOUBLE to (listOf<UiAction>(UiAction.SyncTest(false)) to m), Gesture.TRIPLE to (none to c),
                Gesture.SYSTEM_BACK to (listOf<UiAction>(UiAction.SyncTest(false)) to m))
            out += rowK(c, "(FLOOR)", Gesture.FORWARD to (none to c), Gesture.BACK to (none to c),
                Gesture.UP to (none to c), Gesture.DOWN to (none to c),
                Gesture.TAP to (listOf<UiAction>(UiAction.SetPresenceFloor(22)) to m),
                Gesture.DOUBLE to (none to m), Gesture.TRIPLE to (none to c), Gesture.SYSTEM_BACK to (none to m))
            out += rowK(pa, "(IMPORT)", Gesture.FORWARD to (none to pa), Gesture.BACK to (none to pa), Gesture.UP to (none to pa),
                Gesture.DOWN to (none to pa), Gesture.TAP to (none to m), Gesture.DOUBLE to (none to m),
                Gesture.TRIPLE to (none to pa), Gesture.SYSTEM_BACK to (none to m))
            out += rowK(pa, "(CREDITS)", Gesture.FORWARD to (none to pa), Gesture.BACK to (none to pa), Gesture.UP to (none to pa),
                Gesture.DOWN to (none to pa), Gesture.TAP to (none to m), Gesture.DOUBLE to (none to m),
                Gesture.TRIPLE to (none to pa), Gesture.SYSTEM_BACK to (none to m))
            out += row(r, Gesture.FORWARD to (listOf<UiAction>(UiAction.SetView(ViewId.ACTION, 0)) to r),
                Gesture.BACK to (listOf<UiAction>(UiAction.SetView(ViewId.HALL, 0)) to r), Gesture.UP to (none to r),
                Gesture.DOWN to (none to r), Gesture.TAP to (listOf<UiAction>(UiAction.PlayPause) to r), Gesture.DOUBLE to (none to m),
                Gesture.TRIPLE to (listOf<UiAction>(UiAction.Recenter) to r), Gesture.SYSTEM_BACK to (listOf<UiAction>(UiAction.Leave) to r))
            return out
        }

        /** A machine standing in [c]: menus, cards and panels are reached the way a user reaches them. */
        fun machineIn(c: UiContext, f: UiFacts, kind: String = ""): UiStateMachineImpl {
            val ui = UiStateMachineImpl()
            if (c == UiContext.TITLE) return ui
            ui.onEvent(UiEvent.ENTERED, f, 0)
            fun g(x: Gesture) = ui.onGesture(x, f, 0)
            when (c) {
                UiContext.MENU -> g(Gesture.DOUBLE)
                UiContext.ADJUST -> { g(Gesture.DOUBLE); repeat(3) { g(Gesture.FORWARD) }; g(Gesture.TAP) }        // Position ›
                UiContext.CARD -> { g(Gesture.DOUBLE); repeat(6) { g(Gesture.FORWARD) }; g(Gesture.TAP)              // More ›
                                    g(Gesture.FORWARD); g(Gesture.FORWARD); g(Gesture.TAP)                             // Calibrate ›
                                    if (kind != "(FLOOR)") g(Gesture.FORWARD); g(Gesture.TAP) }                      // A/V sync or Display floor
                UiContext.PANEL -> { g(Gesture.DOUBLE); repeat(6) { g(Gesture.FORWARD) }; g(Gesture.TAP)
                                     repeat(if (kind == "(IMPORT)") 3 else 4) { g(Gesture.FORWARD) }; g(Gesture.TAP) } // Import or Credits
                UiContext.REST -> ui.onEvent(UiEvent.REST_ON, f, 0)
                else -> {}
            }
            assertEquals(c, ui.context)
            return ui
        }
    }

    @Test fun cell() {
        val f = facts()
        val ui = machineIn(ctx, f, kind)
        assertEquals("$ctx × $g", expected, ui.onGesture(g, f, 1_000))
        assertEquals("$ctx × $g → context", after, ui.context)
    }
}

class GestureRulesTest {
    @Test fun doubleTapNeverAlsoYieldsATapAction() {
        val f = facts()
        for (c in UiContext.entries) {
            val acts = GestureTableTest.machineIn(c, f).onGesture(Gesture.DOUBLE, f, 1_000)
            assertFalse("$c", acts.any { it == UiAction.PlayPause || it == UiAction.Enter || it is UiAction.Seek })
            assertFalse("$c", acts.any { it is UiAction.SetPresenceFloor || it == UiAction.RotateToken })
        }
    }

    @Test fun reverseSwipeFlipsForwardAndBack() {
        val f = facts(settings = UiFixtures.settings(reverseSwipe = true))
        val ui = UiFixtures.entered(f)
        assertEquals(listOf<UiAction>(UiAction.SetView(ViewId.HALL, 0)), ui.onGesture(Gesture.FORWARD, f, 0))
        assertEquals(listOf<UiAction>(UiAction.SetView(ViewId.ACTION, 0)), ui.onGesture(Gesture.BACK, f, 0))
        ui.onGesture(Gesture.DOUBLE, f, 0)
        ui.onGesture(Gesture.BACK, f, 0)             // reversed: next row
        assertEquals(1, ui.cursor)
    }

    @Test fun eachViewRemembersItsFraming() {
        val ui = UiFixtures.entered()
        // Action shown in its overhead framing, then back to Player, then forward again returns to overhead.
        ui.onEvent(UiEvent.VIEW_CHANGED, facts(view = ViewId.ACTION, framing = 1), 0)
        val acts = ui.onGesture(Gesture.FORWARD, facts(view = ViewId.PLAYER), 0)
        assertEquals(listOf<UiAction>(UiAction.SetView(ViewId.ACTION, 1)), acts)
    }

    @Test fun anyGestureWhilePlayingShowsTheHudForSixSeconds() {
        val f = facts()
        val ui = UiFixtures.entered(f)
        ui.render(f, 0)                                        // the movement starts being seen at 0
        assertTrue(ui.render(f, 5_000).hud != null)
        assertEquals(null, ui.render(f, 7_000).hud)
        ui.onGesture(Gesture.FORWARD, f, 10_000)
        assertTrue(ui.render(f, 15_999).hud != null)
        assertEquals(null, ui.render(f, 16_001).hud)
        assertTrue("paused keeps the HUD", ui.render(facts(playing = false), 60_000).hud != null)
    }

    @Test fun restSwipeQueuesAViewWithAToast() {
        val f = facts()
        val ui = UiFixtures.entered(f)
        ui.onEvent(UiEvent.REST_ON, f, 0)
        ui.onGesture(Gesture.FORWARD, f, 100)
        assertEquals(UiText.DISPLAY_RESTING_TOAST, ui.render(f, 200).toast)
        ui.onEvent(UiEvent.REST_OFF, f, 300)
        assertEquals(UiContext.PLAYING, ui.context)
    }

    @Test fun backKeyAtRootLeavesAndInMenusActsAsDoubleTap() {
        val f = facts()
        val ui = UiFixtures.entered(f)
        ui.onGesture(Gesture.DOUBLE, f, 0); ui.onGesture(Gesture.DOWN, f, 0); ui.onGesture(Gesture.TAP, f, 0)  // → More
        assertEquals(listOf(MenuId.TRANSPORT, MenuId.MORE), ui.menuPath)
        assertEquals(emptyList<UiAction>(), ui.onGesture(Gesture.SYSTEM_BACK, f, 0))
        assertEquals(listOf(MenuId.TRANSPORT), ui.menuPath)
        ui.onGesture(Gesture.SYSTEM_BACK, f, 0)
        assertEquals(listOf<UiAction>(UiAction.Leave), ui.onGesture(Gesture.SYSTEM_BACK, f, 0))
    }
}
