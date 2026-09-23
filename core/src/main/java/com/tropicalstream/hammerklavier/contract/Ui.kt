package com.tropicalstream.hammerklavier.contract

enum class UiContext { TITLE, PLAYING, MENU, ADJUST, CARD, PANEL, REST }
enum class UiEvent { KIT_PLAYABLE, ENTERED, MOVEMENT_STARTED, VIEW_CHANGED, PAUSED, RESUMED, REST_ON, REST_OFF, IMPORTED }

class SettingsSnapshot(val tuning: Map<InstrumentId, TuningSpec>, val registration: Int, val reverb: ReverbMode,
    val resonance: ResonanceMode, val speakerBass: SpeakerBass, val roomOverride: RoomLevel?, val palette: Palette,
    val finish: UprightFinish, val stereoDepth: Float, val lookAround: Boolean, val edgeOverlay: Boolean?,
    val reverseSwipe: Boolean, val presenceFloor: Int, val avLeadMs: Map<String, Int> /* route key → ms */, val tempoPct: Int, val msaa: Boolean)

class UiFacts(val playing: Boolean, val positionUs: Long, val durationUs: Long, val movementId: String?, val bar: Int,
    val instrument: InstrumentId, val view: ViewId, val framing: Int, val kitStates: Map<InstrumentId, KitState>,
    val library: LibraryModel?, val settings: SettingsSnapshot, val nextTitle: String?, val quality: Int,
    val companionUrl: String?, val companionToken: String, val route: RouteInfo, val status: List<StatusItem>,
    val perfInfo: PerfInfo?, val firstRun: Boolean, val sessions: Int, val resumeTitle: String?,
    val recent: List<String> /* movement ids, newest first, ≤ 15 */, val shelfId: String?, val version: String, val debug: String?)

sealed interface UiAction {
    data object Enter : UiAction; data object PlayPause : UiAction; data object Next : UiAction; data object Previous : UiAction
    /** The playlist is built from that shelf. */
    data class Play(val movementId: String, val shelfId: String? = null) : UiAction
    /** Song µs incl. pre-roll. */
    data class Seek(val us: Long) : UiAction
    data class SetView(val view: ViewId, val framing: Int) : UiAction
    data object Recenter : UiAction; data object ShowHud : UiAction; data object Leave : UiAction
    data class SetInstrument(val id: InstrumentId) : UiAction
    data class SetTempo(val pct: Int) : UiAction
    data class SetTuning(val instrument: InstrumentId, val tuning: TuningSpec) : UiAction
    data class SetRegistration(val mask: Int) : UiAction
    data class SetMix(val reverb: ReverbMode? = null, val resonance: ResonanceMode? = null, val speakerBass: SpeakerBass? = null) : UiAction
    data class SetSight(val room: RoomLevel? = null, val autoRoom: Boolean = false, val palette: Palette? = null,
        val finish: UprightFinish? = null, val stereoDepth: Float? = null, val lookAround: Boolean? = null,
        val edgeOverlay: Boolean? = null, val reverseSwipe: Boolean? = null, val msaa: Boolean? = null,
        /** M8 (WP10 request 1): true sets the edge overlay back to Auto (null); wins over [edgeOverlay]. */
        val autoEdgeOverlay: Boolean = false) : UiAction
    data class SetPresenceFloor(val level: Int) : UiAction
    /** Stored for the current route key. */
    data class SetAvLead(val ms: Int) : UiAction
    /** Plays SYNC_CLICK through setPerformance + render.setSyncFlash. */
    data class SyncTest(val on: Boolean) : UiAction
    data object Rescan : UiAction; data object RotateToken : UiAction
}

/** The concrete class is WP10's; opaque to WP0/WP12. */
abstract class OverlayState

/** WP10, pure. */
interface UiStateMachine {
    val context: UiContext
    fun onGesture(g: Gesture, facts: UiFacts, nowMs: Long): List<UiAction>
    fun onEvent(e: UiEvent, facts: UiFacts, nowMs: Long)
    fun render(facts: UiFacts, nowMs: Long): OverlayState
}
