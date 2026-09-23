package com.tropicalstream.hammerklavier.session

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.KitState
import com.tropicalstream.hammerklavier.contract.LibraryModel
import com.tropicalstream.hammerklavier.contract.PerfInfo
import com.tropicalstream.hammerklavier.contract.RouteInfo
import com.tropicalstream.hammerklavier.contract.SettingsSnapshot
import com.tropicalstream.hammerklavier.contract.StatusItem
import com.tropicalstream.hammerklavier.contract.UiFacts
import com.tropicalstream.hammerklavier.contract.ViewId

/**
 * Seek and position units (PLAN §2.3, R107): every API carries song µs including the 400 ms
 * pre-roll; only WP10's text and the companion's JSON and query values (`positionSec`, `ms`) and
 * CONTROL's `--el seek` are display time.
 */
object SeekUnits {
    /** Display ms (pre-roll excluded) → song µs (pre-roll included). */
    fun displayMsToSongUs(ms: Long): Long = ms.coerceAtLeast(0L) * 1000L + HK.PRE_ROLL_US
    /** Song µs → display seconds (never negative). */
    fun songUsToDisplaySec(us: Long): Float = ((us - HK.PRE_ROLL_US).coerceAtLeast(0L) / 1e6).toFloat()
    /** Clamps a song µs seek target into a Performance of [durationUs]. */
    fun clampSongUs(us: Long, durationUs: Long): Long = us.coerceIn(0L, maxOf(0L, durationUs))
}

/** Everything [FactsAssembler.assemble] needs, gathered by SessionController on main. */
class FactsInput(
    val playing: Boolean, val positionUs: Long, val durationUs: Long, val movementId: String?, val bar: Int,
    val instrument: InstrumentId, val view: ViewId, val framing: Int, val kitStates: Map<InstrumentId, KitState>,
    val library: LibraryModel?, val settings: SettingsSnapshot, val nextId: String?, val quality: Int,
    val companionUrl: String?, val companionToken: String, val route: RouteInfo, val status: List<StatusItem>,
    val perfInfo: PerfInfo?, val sessions: Int, val resumeId: String?, val recent: List<String>,
    val shelfId: String?, val version: String, val debug: String?)

/** Builds the immutable [UiFacts] WP10 renders (PLAN §2.3). Pure. */
object FactsAssembler {
    fun assemble(i: FactsInput): UiFacts = UiFacts(
        playing = i.playing, positionUs = i.positionUs.coerceAtLeast(0L), durationUs = i.durationUs.coerceAtLeast(0L),
        movementId = i.movementId, bar = maxOf(1, i.bar), instrument = i.instrument, view = i.view, framing = i.framing,
        kitStates = i.kitStates, library = i.library, settings = i.settings,
        nextTitle = i.nextId?.let { titleOf(i.library, it) }, quality = i.quality, companionUrl = i.companionUrl,
        companionToken = i.companionToken, route = i.route, status = i.status, perfInfo = i.perfInfo,
        firstRun = i.sessions <= 1, sessions = i.sessions, resumeTitle = i.resumeId?.let { titleOf(i.library, it) },
        recent = i.recent.take(SessionSettings.RECENT_MAX), shelfId = i.shelfId, version = i.version, debug = i.debug)

    /** "Composer short · work short title · movement title", or the id's tail when the library does not know it. */
    fun titleOf(lib: LibraryModel?, movementId: String): String {
        val m = lib?.movements?.get(movementId) ?: return movementId.substringAfter(':')
        val w = lib.works[m.workId] ?: return m.title
        return if (w.movementIds.size <= 1 || m.title == w.shortTitle) "${w.composerShort} · ${w.shortTitle}"
        else "${w.composerShort} · ${w.shortTitle} · ${m.title}"
    }
}
