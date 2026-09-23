package com.tropicalstream.hammerklavier.contract.stub

import com.tropicalstream.hammerklavier.contract.Gesture
import com.tropicalstream.hammerklavier.contract.OverlayState
import com.tropicalstream.hammerklavier.contract.UiAction
import com.tropicalstream.hammerklavier.contract.UiContext
import com.tropicalstream.hammerklavier.contract.UiEvent
import com.tropicalstream.hammerklavier.contract.UiFacts
import com.tropicalstream.hammerklavier.contract.UiStateMachine
import com.tropicalstream.hammerklavier.contract.ViewId

/** What StubOverlay draws: a title and one status line, in both eyes. */
class StubOverlayState(val title: String, val line: String) : OverlayState()

/**
 * UiStateMachine before WP10 (PLAN §2.3): tap → PlayPause; forward/back swipes → the next/previous
 * view on the ring (framing 0); up/down → the other framing; double → ShowHud; system back → Leave.
 */
class StubUi : UiStateMachine {
    override val context: UiContext get() = UiContext.PLAYING

    override fun onGesture(g: Gesture, facts: UiFacts, nowMs: Long): List<UiAction> {
        val views = ViewId.entries
        val i = facts.view.ordinal
        return when (g) {
            Gesture.TAP -> listOf(UiAction.PlayPause)
            Gesture.FORWARD -> listOf(UiAction.SetView(views[(i + 1) % views.size], 0))
            Gesture.BACK -> listOf(UiAction.SetView(views[(i + views.size - 1) % views.size], 0))
            Gesture.UP, Gesture.DOWN -> listOf(UiAction.SetView(facts.view, 1 - facts.framing))
            Gesture.DOUBLE -> listOf(UiAction.ShowHud)
            Gesture.TRIPLE -> emptyList()
            Gesture.SYSTEM_BACK -> listOf(UiAction.Leave)
        }
    }

    override fun onEvent(e: UiEvent, facts: UiFacts, nowMs: Long) {}

    override fun render(facts: UiFacts, nowMs: Long): OverlayState =
        StubOverlayState(title = "Hammerklavier", line = "${facts.view.name.lowercase()} · ${if (facts.playing) "playing" else "paused"} · ${facts.version}" +
            (facts.debug?.let { "\n$it" } ?: ""))
}
