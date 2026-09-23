package com.tropicalstream.hammerklavier.ui.model

import com.tropicalstream.hammerklavier.contract.Gesture
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.OutputRoute
import com.tropicalstream.hammerklavier.contract.UiAction
import com.tropicalstream.hammerklavier.contract.UiContext
import com.tropicalstream.hammerklavier.contract.UiEvent
import com.tropicalstream.hammerklavier.contract.UiFacts
import com.tropicalstream.hammerklavier.contract.UiStateMachine
import com.tropicalstream.hammerklavier.contract.ViewId

/**
 * Contexts and gesture routing (PLAN §1.3 table), menus (§1.4, §1.5), cards, panels, the title
 * card and the HUD timers (§1.8, §1.9). Pure; main thread only. Overlays stack: a menu stack, and
 * on top of it at most one Adjust, card or panel; closing one returns to the menu beneath.
 */
class UiStateMachineImpl : UiStateMachine {

    /** The §6.11 credits text (assets/credits.txt), set by WP0's wiring; a summary from the library is used until then. */
    var creditsText: String? = null

    private var entered = false
    private var resting = false
    private var kitPlayableEvent = false
    private val menus = ArrayList<MenuLevel>()

    private var adjust: AdjustKind? = null
    private var adjustValue = 0L          // POSITION: display ms; TEMPO: percent
    private var adjustOrig = 0L

    private var card: CardKind? = null
    private var cardValue = 0             // FLOOR: level; SYNC: ms
    private var cardOrig = 0

    private var panel: PanelKind? = null
    private var panelPage = 0

    private val framingOf = IntArray(ViewId.entries.size)
    private var hudUntilMs = Long.MIN_VALUE
    private var titlePillUntilMs = Long.MIN_VALUE
    private var toastText: String? = null
    private var toastUntilMs = Long.MIN_VALUE
    private var creditMovement: String? = null
    private var creditUntilMs = Long.MIN_VALUE
    private var lastMovement: String? = null
    private var lastView: ViewId? = null
    private var lastFraming = -1
    private var lastImportText: String? = null

    override val context: UiContext
        get() = when {
            adjust != null -> UiContext.ADJUST
            card != null -> UiContext.CARD
            panel != null -> UiContext.PANEL
            menus.isNotEmpty() -> UiContext.MENU
            !entered -> UiContext.TITLE
            resting -> UiContext.REST
            else -> UiContext.PLAYING
        }

    // ─── gestures ───

    override fun onGesture(g0: Gesture, facts: UiFacts, nowMs: Long): List<UiAction> {
        val g = if (facts.settings.reverseSwipe) when (g0) {
            Gesture.FORWARD -> Gesture.BACK; Gesture.BACK -> Gesture.FORWARD; else -> g0
        } else g0
        framingOf[facts.view.ordinal] = facts.framing.coerceIn(0, 1)
        return when (context) {
            UiContext.TITLE -> title(g, facts, nowMs)
            UiContext.PLAYING -> { hudUntilMs = nowMs + HudModel.HUD_MS; playing(g, facts, nowMs) }
            UiContext.REST -> { hudUntilMs = nowMs + HudModel.HUD_MS; rest(g, facts, nowMs) }
            UiContext.MENU -> menu(g, facts)
            UiContext.ADJUST -> adjust(g, facts)
            UiContext.CARD -> card(g)
            UiContext.PANEL -> panel(g, facts)
        }
    }

    fun playable(facts: UiFacts): Boolean = kitPlayableEvent || HudModel.kitPlayable(facts.kitStates[facts.instrument])

    private fun title(g: Gesture, f: UiFacts, now: Long): List<UiAction> = when (g) {
        Gesture.TAP -> if (playable(f)) {
            entered = true; hudUntilMs = now + HudModel.HUD_MS
            listOf(UiAction.Enter)
        } else { titlePillUntilMs = now + 3_000; emptyList() }
        Gesture.TRIPLE -> listOf(UiAction.Recenter)
        Gesture.SYSTEM_BACK -> listOf(UiAction.Leave)
        else -> emptyList()
    }

    private fun ring(f: UiFacts, step: Int): UiAction.SetView {
        val n = ViewId.entries.size
        val v = ViewId.entries[(f.view.ordinal + step + n) % n]
        return UiAction.SetView(v, framingOf[v.ordinal])
    }

    private fun otherFraming(f: UiFacts): UiAction.SetView {
        val fr = 1 - framingOf[f.view.ordinal]
        framingOf[f.view.ordinal] = fr
        return UiAction.SetView(f.view, fr)
    }

    private fun playing(g: Gesture, f: UiFacts, now: Long): List<UiAction> = when (g) {
        Gesture.FORWARD -> listOf(ring(f, 1))
        Gesture.BACK -> listOf(ring(f, -1))
        Gesture.UP, Gesture.DOWN -> listOf(otherFraming(f))
        Gesture.TAP -> listOf(UiAction.PlayPause)
        Gesture.DOUBLE -> { openTransport(); emptyList() }
        Gesture.TRIPLE -> listOf(UiAction.Recenter, UiAction.ShowHud)
        Gesture.SYSTEM_BACK -> listOf(UiAction.Leave)
    }

    private fun rest(g: Gesture, f: UiFacts, now: Long): List<UiAction> = when (g) {
        Gesture.FORWARD, Gesture.BACK -> {
            toastText = UiText.DISPLAY_RESTING_TOAST; toastUntilMs = now + HudModel.TOAST_MS
            listOf(ring(f, if (g == Gesture.FORWARD) 1 else -1))
        }
        Gesture.UP, Gesture.DOWN -> emptyList()
        Gesture.TAP -> listOf(UiAction.PlayPause)
        Gesture.DOUBLE -> { openTransport(); emptyList() }
        Gesture.TRIPLE -> listOf(UiAction.Recenter)
        Gesture.SYSTEM_BACK -> listOf(UiAction.Leave)
    }

    private fun menu(g: Gesture, f: UiFacts): List<UiAction> {
        val level = menus.last()
        val rows = MenuTree.rows(level, f)
        val last = (rows.size - 1).coerceAtLeast(0)
        level.cursor = level.cursor.coerceIn(0, last)
        return when (g) {
            Gesture.FORWARD -> { level.cursor = (level.cursor + 1).coerceAtMost(last); emptyList() }
            Gesture.BACK -> { level.cursor = (level.cursor - 1).coerceAtLeast(0); emptyList() }
            Gesture.UP -> { level.cursor = (level.cursor - MenuTree.PAGE).coerceAtLeast(0); emptyList() }
            Gesture.DOWN -> { level.cursor = (level.cursor + MenuTree.PAGE).coerceAtMost(last); emptyList() }
            Gesture.TAP -> if (rows.isEmpty()) emptyList() else select(rows[level.cursor].choice, f)
            Gesture.DOUBLE, Gesture.SYSTEM_BACK -> { menus.removeAt(menus.size - 1); emptyList() }
            Gesture.TRIPLE -> listOf(UiAction.Recenter)
        }
    }

    private fun select(c: Choice, f: UiFacts): List<UiAction> = when (c) {
        is Choice.Open -> { push(c.level, f); c.actions }
        is Choice.Do -> {
            when (c.after) {
                After.STAY -> {}
                After.BACK -> if (menus.isNotEmpty()) menus.removeAt(menus.size - 1)
                After.CLOSE -> menus.clear()
            }
            c.actions
        }
        is Choice.OpenAdjust -> openAdjust(c.kind, f)
        is Choice.OpenCard -> openCard(c.kind, f)
        is Choice.OpenPanel -> { openPanel(c.kind); emptyList() }
        Choice.None -> emptyList()
    }

    private fun push(level: MenuLevel, f: UiFacts) {
        val rows = MenuTree.rows(level, f)
        level.cursor = rows.indexOfFirst { it.current }.coerceAtLeast(0)
        menus.add(level)
    }

    // ─── entry points also used by CONTROL (`--ez menu true`, `--ez library true`, `--es card floor|sync`) ───

    fun openTransport() { menus.clear(); menus.add(MenuLevel(MenuId.TRANSPORT)); entered = true }

    /** Opens Transport › Library; returns the Rescan the Library asks for when it opens (§1.7). */
    fun openLibrary(): List<UiAction> {
        openTransport(); menus.add(MenuLevel(MenuId.LIBRARY)); return listOf(UiAction.Rescan)
    }

    fun openCard(kind: CardKind, f: UiFacts): List<UiAction> {
        adjust = null; panel = null; card = kind; entered = true
        return when (kind) {
            CardKind.FLOOR -> {
                cardOrig = f.settings.presenceFloor
                cardValue = cardOrig.coerceIn(FLOOR_MIN, FLOOR_MAX); emptyList()
            }
            CardKind.SYNC -> {
                cardOrig = leadFor(f); cardValue = cardOrig
                listOf(UiAction.SyncTest(true))
            }
        }
    }

    fun openPanel(kind: PanelKind) { adjust = null; card = null; panel = kind; panelPage = 0; entered = true }

    private fun openAdjust(kind: AdjustKind, f: UiFacts): List<UiAction> {
        adjust = kind
        adjustOrig = when (kind) {
            AdjustKind.POSITION -> displayMs(f.positionUs).coerceAtMost(displayMs(f.durationUs))
            AdjustKind.TEMPO -> f.settings.tempoPct.coerceIn(TEMPO_MIN, TEMPO_MAX).toLong()
        }
        adjustValue = adjustOrig
        return emptyList()
    }

    // ─── adjust (Position, Tempo) ───

    private fun adjust(g: Gesture, f: UiFacts): List<UiAction> {
        val kind = adjust!!
        val step = when (g) {
            Gesture.FORWARD -> 1; Gesture.BACK -> -1; Gesture.UP -> 6; Gesture.DOWN -> -6; else -> 0
        }
        if (step != 0) {
            return when (kind) {
                AdjustKind.POSITION -> {
                    adjustValue = (adjustValue + step * 10_000L).coerceIn(0L, displayMs(f.durationUs)); emptyList()
                }
                AdjustKind.TEMPO -> {
                    val d = if (step == 1 || step == -1) step * 5 else (step / 6) * 20
                    adjustValue = (adjustValue + d).coerceIn(TEMPO_MIN.toLong(), TEMPO_MAX.toLong())
                    listOf(UiAction.SetTempo(adjustValue.toInt()))            // live: the tempo is heard while adjusting
                }
            }
        }
        return when (g) {
            Gesture.TAP -> {
                adjust = null
                when (kind) {
                    AdjustKind.POSITION -> listOf(UiAction.Seek(adjustValue * 1000L + HK.PRE_ROLL_US))
                    AdjustKind.TEMPO -> listOf(UiAction.SetTempo(adjustValue.toInt()))
                }
            }
            Gesture.DOUBLE, Gesture.SYSTEM_BACK -> {
                adjust = null
                if (kind == AdjustKind.TEMPO && adjustValue != adjustOrig) listOf(UiAction.SetTempo(adjustOrig.toInt())) else emptyList()
            }
            else -> emptyList()
        }
    }

    // ─── cards (Display floor, A/V sync) ───

    private fun card(g: Gesture): List<UiAction> {
        val kind = card!!
        val dir = when (g) { Gesture.FORWARD, Gesture.UP -> 1; Gesture.BACK, Gesture.DOWN -> -1; else -> 0 }
        if (dir != 0) {
            return when (kind) {
                CardKind.FLOOR -> { cardValue = stepFloor(cardValue, dir); emptyList() }
                CardKind.SYNC -> {
                    val v = (cardValue + dir * LEAD_STEP).coerceIn(LEAD_MIN, LEAD_MAX)
                    if (v == cardValue) emptyList() else { cardValue = v; listOf(UiAction.SetAvLead(v)) }   // live preview
                }
            }
        }
        return when (g) {
            Gesture.TAP -> {
                card = null
                when (kind) {
                    CardKind.FLOOR -> listOf(UiAction.SetPresenceFloor(cardValue))
                    CardKind.SYNC -> listOf(UiAction.SetAvLead(cardValue), UiAction.SyncTest(false))
                }
            }
            Gesture.DOUBLE, Gesture.SYSTEM_BACK -> {
                card = null
                when (kind) {
                    CardKind.FLOOR -> emptyList()
                    CardKind.SYNC -> if (cardValue != cardOrig) listOf(UiAction.SetAvLead(cardOrig), UiAction.SyncTest(false))
                                     else listOf(UiAction.SyncTest(false))
                }
            }
            else -> emptyList()
        }
    }

    // ─── panels ───

    private fun panel(g: Gesture, f: UiFacts): List<UiAction> {
        val pages = panelLines(panel!!, f).let { pageCount(it.size) }
        return when (g) {
            Gesture.FORWARD, Gesture.DOWN -> { panelPage = (panelPage + 1).coerceAtMost(pages - 1); emptyList() }
            Gesture.BACK, Gesture.UP -> { panelPage = (panelPage - 1).coerceAtLeast(0); emptyList() }
            Gesture.TAP -> if (panel == PanelKind.IMPORT) listOf(UiAction.RotateToken) else { panel = null; emptyList() }
            Gesture.DOUBLE, Gesture.SYSTEM_BACK -> { panel = null; emptyList() }
            Gesture.TRIPLE -> emptyList()
        }
    }

    fun panelLines(kind: PanelKind, f: UiFacts): List<String> = when (kind) {
        PanelKind.CREDITS -> UiText.wrap(creditsText ?: defaultCredits(f))
        PanelKind.ABOUT -> {
            val out = ArrayList<String>()
            out.add("Hammerklavier " + f.version)
            out.add("")
            out.addAll(UiText.wrap(UiText.ABOUT_LINE))
            val urls = f.library?.sources?.values?.map { it.sourceUrl }?.filter { it.isNotBlank() }?.distinct() ?: emptyList()
            if (urls.isNotEmpty()) { out.add(""); out.add("Sources:"); urls.forEach { out.addAll(UiText.wrap(it)) } }
            out
        }
        PanelKind.IMPORT -> {
            val out = ArrayList<String>()
            out.add(if (f.companionUrl != null) "Phone: " + f.companionUrl else UiText.NO_WIFI)
            out.add("Token: " + f.companionToken)
            out.add("Tap: Rotate token")
            out.add("adb: " + UiText.PUSH_COMMAND)
            out.add("Last import: " + (lastImportText ?: "none"))
            val n = f.library?.works?.values?.count { it.imported } ?: 0
            out.add("Imported: " + UiText.plural(n, "work"))
            out.flatMap { UiText.wrap(it) }
        }
    }

    private fun defaultCredits(f: UiFacts): String {
        val sb = StringBuilder()
        sb.append("Grand: Salamander Grand Piano V3 by Alexander Holm · CC-BY 3.0\n")
        sb.append("Upright: VCSL Knight upright and VSCO-2 CE · CC0 1.0\n")
        sb.append("Harpsichord: VCSL Flemish harpsichord · CC0 1.0\n")
        f.library?.sources?.values?.map { it.credit }?.filter { it.isNotBlank() }?.distinct()?.forEach { sb.append(it).append('\n') }
        return sb.toString().trimEnd()
    }

    // ─── events ───

    override fun onEvent(e: UiEvent, facts: UiFacts, nowMs: Long) {
        when (e) {
            UiEvent.KIT_PLAYABLE -> kitPlayableEvent = true
            UiEvent.ENTERED -> { entered = true; hudUntilMs = nowMs + HudModel.HUD_MS }
            UiEvent.MOVEMENT_STARTED -> startMovement(facts.movementId, nowMs)
            UiEvent.VIEW_CHANGED -> viewChanged(facts, nowMs)
            UiEvent.PAUSED, UiEvent.RESUMED -> hudUntilMs = nowMs + HudModel.HUD_MS
            UiEvent.REST_ON -> resting = true
            UiEvent.REST_OFF -> resting = false
            UiEvent.IMPORTED -> {}
        }
    }

    private fun startMovement(id: String?, now: Long) {
        lastMovement = id; creditMovement = id; creditUntilMs = now + HudModel.CREDIT_MS; hudUntilMs = now + HudModel.HUD_MS
    }

    private fun viewChanged(f: UiFacts, now: Long) {
        lastView = f.view; lastFraming = f.framing
        framingOf[f.view.ordinal] = f.framing.coerceIn(0, 1)
        toastText = UiText.viewToast(f.view, f.framing); toastUntilMs = now + HudModel.TOAST_MS
    }

    // ─── render ───

    override fun render(facts: UiFacts, nowMs: Long): UiOverlayState {
        if (facts.movementId != lastMovement) {
            if (facts.movementId != null) startMovement(facts.movementId, nowMs) else lastMovement = null
        }
        if (lastView == null) { lastView = facts.view; lastFraming = facts.framing }
        else if (lastView != facts.view || lastFraming != facts.framing) viewChanged(facts, nowMs)
        for (s in facts.status) {
            if (s.code == com.tropicalstream.hammerklavier.contract.StatusCode.IMPORTED ||
                s.code == com.tropicalstream.hammerklavier.contract.StatusCode.IMPORT_FAILED) lastImportText = UiText.status(s)
        }

        val ctx = context
        val title = if (ctx == UiContext.TITLE)
            HudModel.titleCard(facts, playable(facts), if (nowMs < titlePillUntilMs) HudModel.voicingPill(facts) ?: UiText.voicingPill(facts.instrument, 0) else null)
            else null
        val showStage = entered
        val hudVisible = ctx == UiContext.PLAYING && (!facts.playing || nowMs < hudUntilMs)
        val hud = if (hudVisible) HudModel.hud(facts) else null

        val menuCard = if (ctx == UiContext.MENU) menuCard(facts) else null
        val adjustCard = adjust?.let { adjustCard(it, facts) }
        val floor = if (card == CardKind.FLOOR) FloorCard(FLOOR_LEVELS, cardValue, "⇄ dimmest swatch you can see · tap store · double-tap cancel") else null
        val sync = if (card == CardKind.SYNC) SyncCard(cardValue, UiText.routeName(facts.route), "⇄ ±5 ms · tap store · double-tap cancel") else null
        val panelCard = panel?.let { k ->
            val lines = panelLines(k, facts)
            val pages = pageCount(lines.size)
            val p = panelPage.coerceIn(0, pages - 1)
            PanelCard(title = when (k) { PanelKind.CREDITS -> "Credits"; PanelKind.ABOUT -> "About"; PanelKind.IMPORT -> "Import" },
                lines = lines.drop(p * MenuTree.PAGE).take(MenuTree.PAGE), page = p, pages = pages,
                footer = if (k == PanelKind.IMPORT) "⇄ page · tap rotate token · double-tap close" else "⇄ page · tap close")
        }

        val toast = if (showStage && nowMs < toastUntilMs) toastText else null
        val credit = if (showStage && nowMs < creditUntilMs && creditMovement == facts.movementId) HudModel.credit(facts) else null
        val status = if (showStage || ctx == UiContext.TITLE) UiText.pickStatus(facts.status, nowMs)?.let { UiText.status(it) } else null
        val hint = if (hudVisible && facts.sessions in 1..HudModel.HINT_SESSIONS) UiText.HINT else null
        return UiOverlayState(context = ctx, title = title, hud = hud, menu = menuCard, adjust = adjustCard, floor = floor,
            sync = sync, panel = panelCard, toast = toast, credit = credit, status = status, hint = hint, debug = facts.debug,
            stageHidden = card == CardKind.FLOOR, resting = resting)
    }

    private fun menuCard(f: UiFacts): MenuCard {
        val level = menus.last()
        val rows = MenuTree.rows(level, f)
        val cur = level.cursor.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
        val page = cur / MenuTree.PAGE
        val pages = pageCount(rows.size)
        val slice = rows.drop(page * MenuTree.PAGE).take(MenuTree.PAGE).map { it.label }
        return MenuCard(MenuTree.title(level, f), slice, cur - page * MenuTree.PAGE, page, pages, UiText.MENU_FOOTER)
    }

    private fun adjustCard(k: AdjustKind, f: UiFacts): AdjustCard = when (k) {
        AdjustKind.POSITION -> AdjustCard("Position",
            UiText.clock(adjustValue / 1000) + " / " + UiText.clock(UiText.displaySec(f.durationUs)),
            "⇄ ±10 s · ⇅ ±60 s · tap seek · double-tap cancel")
        AdjustKind.TEMPO -> AdjustCard("Tempo", "$adjustValue%", "⇄ ±5 % · ⇅ ±20 % · tap confirm · double-tap cancel")
    }

    /** Test and CONTROL view of the menu cursor: (menu, absolute row). */
    val menuPath: List<MenuId> get() = menus.map { it.id }
    val cursor: Int get() = menus.lastOrNull()?.cursor ?: -1
    val adjustDisplayMs: Long get() = adjustValue
    val cardLevel: Int get() = cardValue

    private fun leadFor(f: UiFacts): Int = (f.settings.avLeadMs[f.route.key] ?: defaultLead(f.route.route)).coerceIn(LEAD_MIN, LEAD_MAX)

    companion object {
        const val TEMPO_MIN = 50; const val TEMPO_MAX = 150
        const val FLOOR_MIN = 8; const val FLOOR_MAX = 68; const val FLOOR_STEP = 4
        const val LEAD_MIN = 0; const val LEAD_MAX = 400; const val LEAD_STEP = 5
        val FLOOR_LEVELS: List<Int> = (FLOOR_MIN..FLOOR_MAX step FLOOR_STEP).toList()

        fun defaultLead(r: OutputRoute): Int = 30
        fun displayMs(songUs: Long): Long = ((songUs - HK.PRE_ROLL_US).coerceAtLeast(0L)) / 1000L
        /** The next swatch brighter (dir > 0) or dimmer; an off-grid level (the default 22) moves to its neighbour. */
        fun stepFloor(level: Int, dir: Int): Int {
            val c = level.coerceIn(FLOOR_MIN, FLOOR_MAX)
            return if (dir > 0) FLOOR_LEVELS.firstOrNull { it > c } ?: FLOOR_MAX else FLOOR_LEVELS.lastOrNull { it < c } ?: FLOOR_MIN
        }
        fun pageCount(n: Int): Int = ((n + MenuTree.PAGE - 1) / MenuTree.PAGE).coerceAtLeast(1)
    }
}
