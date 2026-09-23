package com.tropicalstream.hammerklavier.ui.model

import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.KitState
import com.tropicalstream.hammerklavier.contract.OverlayState
import com.tropicalstream.hammerklavier.contract.UiContext
import com.tropicalstream.hammerklavier.contract.UiFacts
import com.tropicalstream.hammerklavier.contract.ViewId

// ─── The concrete OverlayState (opaque to WP0/WP12; read by ui.OverlayViews) ───

/** Title card (§1.4): 22 px title, 18 px subtitle, the voicing / enter line, 14 px phone and route lines. */
data class TitleCard(val title: String, val subtitle: String, val line: String, val phone: String,
                     val headphones: String?, val pill: String?)

/** The stage HUD (§1.8). [progress] is 0..1, quantised to 1/200 (one pixel of the 200 px rule). */
data class Hud(val work: String, val movement: String, val tuning: String, val time: String, val progress: Float,
               val pills: List<String>, val pillsTopRight: Boolean)

/** Menu card: [rows] is the current page (≤ 7), [highlight] the cursor within it. */
data class MenuCard(val title: String, val rows: List<String>, val highlight: Int, val page: Int, val pages: Int, val footer: String)

data class AdjustCard(val title: String, val value: String, val hint: String)

/** Display floor card: swatch levels 8…68, the selected one, and the neutral row. */
data class FloorCard(val levels: List<Int>, val selected: Int, val hint: String)

data class SyncCard(val leadMs: Int, val route: String, val hint: String)

data class PanelCard(val title: String, val lines: List<String>, val page: Int, val pages: Int, val footer: String)

/**
 * Everything the overlay shows this frame. Views compare fields and redraw only what changed, so
 * a 1 Hz tick with unchanged text costs nothing (§1.8: invalidations ≤ 1 Hz).
 */
data class UiOverlayState(
    val context: UiContext,
    val title: TitleCard?,
    val hud: Hud?,
    val menu: MenuCard?,
    val adjust: AdjustCard?,
    val floor: FloorCard?,
    val sync: SyncCard?,
    val panel: PanelCard?,
    val toast: String?,
    val credit: String?,
    val status: String?,
    val hint: String?,
    val debug: String?,
    /** RenderControl.setStageHidden: true while the Display floor card shows. */
    val stageHidden: Boolean,
    val resting: Boolean,
) : OverlayState()

/** HUD strings from UiFacts (pure). */
object HudModel {
    const val HUD_MS = 6_000L
    const val CREDIT_MS = 8_000L
    const val TOAST_MS = 1_500L
    const val HINT_SESSIONS = 3

    fun hud(f: UiFacts): Hud {
        val lib = f.library
        val m = f.movementId?.let { lib?.movements?.get(it) }
        val w = m?.let { lib?.works?.get(it.workId) }
        val work = when {
            w != null -> w.composerShort + " · " + w.shortTitle
            f.perfInfo?.title != null -> f.perfInfo.title
            else -> f.movementId ?: ""
        }
        val mvTitle = m?.title ?: ""
        val movement = when {
            f.bar > 0 && mvTitle.isNotEmpty() -> "$mvTitle · bar ${f.bar}"
            f.bar > 0 -> "bar ${f.bar}"
            else -> mvTitle
        }
        val tuning = UiText.tuningLine(f.instrument, MenuTree.tuningOf(f), f.settings.registration)
        return Hud(work = work, movement = movement, tuning = tuning,
            time = UiText.positionLine(f.positionUs, f.durationUs), progress = progress(f.positionUs, f.durationUs),
            pills = pills(f), pillsTopRight = f.view == ViewId.PLAYER && f.framing == 1)
    }

    fun progress(positionUs: Long, durationUs: Long): Float {
        val d = UiText.displaySec(durationUs)
        if (d <= 0) return 0f
        val p = UiText.displaySec(positionUs).toFloat() / d
        return Math.round(p.coerceIn(0f, 1f) * 200f) / 200f
    }

    /** §1.8 pills in order: voicing, paused, warm, stand-in tones, folded, finger-pedalled, merged. */
    fun pills(f: UiFacts): List<String> {
        val out = ArrayList<String>()
        voicingPill(f)?.let { out.add(it) }
        if (!f.playing) out.add(UiText.PILL_PAUSED)
        if (f.quality >= 2) out.add(UiText.PILL_WARM)
        if (f.kitStates[f.instrument] is KitState.Fallback) out.add(UiText.PILL_STAND_IN)
        val p = f.perfInfo
        if (p != null) {
            if (p.folded > 0) out.add(UiText.plural(p.folded, "note") + " folded")
            if (p.fingerPedalled) out.add("finger-pedalled")
            if (p.mergedChannels > 0) out.add(UiText.plural(p.mergedChannels, "channel") + " merged")
        }
        return out
    }

    /** The first kit still voicing (current instrument first): `voicing upright 42%`. */
    fun voicingPill(f: UiFacts): String? {
        val order = ArrayList<InstrumentId>(3)
        order.add(f.instrument)
        for (id in InstrumentId.entries) if (id != f.instrument) order.add(id)
        for (id in order) MenuTree.voicingPct(f.kitStates[id])?.let { return UiText.voicingPill(id, it) }
        return null
    }

    /** The source credit line of the current movement, or null (imported files carry none). */
    fun credit(f: UiFacts): String? {
        val lib = f.library ?: return null
        val m = lib.movements[f.movementId ?: return null] ?: return null
        val w = lib.works[m.workId] ?: return null
        if (w.imported) return null
        val s = lib.sources[w.sourceId] ?: return null
        return s.credit.ifBlank { null }
    }

    /** Title card text (§1.4, §1.9). [playable]: the current instrument's kit can play. */
    fun titleCard(f: UiFacts, playable: Boolean, pill: String?): TitleCard {
        val pct = MenuTree.voicingPct(f.kitStates[f.instrument])
        val line = when {
            !playable -> UiText.voicingGrand(pct ?: 0)
            f.resumeTitle != null -> UiText.tapToContinue(f.resumeTitle)
            else -> UiText.tapToEnter(pct)
        }
        val phone = if (f.companionUrl != null) UiText.phone(f.companionUrl, f.companionToken) else UiText.NO_WIFI
        val headphones = if (f.route.route == com.tropicalstream.hammerklavier.contract.OutputRoute.SPEAKER) UiText.HEADPHONES else null
        return TitleCard(UiText.APP_TITLE, UiText.SUBTITLE, line, phone, headphones, pill)
    }

    fun kitPlayable(s: KitState?): Boolean = when (s) {
        is KitState.Voicing -> s.playable
        KitState.Complete -> true
        is KitState.Fallback -> true
        else -> false
    }
}
