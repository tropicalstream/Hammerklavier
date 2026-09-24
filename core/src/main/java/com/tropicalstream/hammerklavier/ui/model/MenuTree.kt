package com.tropicalstream.hammerklavier.ui.model

import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.KitState
import com.tropicalstream.hammerklavier.contract.LibraryModel
import com.tropicalstream.hammerklavier.contract.Movement
import com.tropicalstream.hammerklavier.contract.Palette
import com.tropicalstream.hammerklavier.contract.ResonanceMode
import com.tropicalstream.hammerklavier.contract.ReverbMode
import com.tropicalstream.hammerklavier.contract.RoomLevel
import com.tropicalstream.hammerklavier.contract.SpeakerBass
import com.tropicalstream.hammerklavier.contract.Temperament
import com.tropicalstream.hammerklavier.contract.TuningSpec
import com.tropicalstream.hammerklavier.contract.UiAction
import com.tropicalstream.hammerklavier.contract.UiFacts
import com.tropicalstream.hammerklavier.contract.UprightFinish
import com.tropicalstream.hammerklavier.contract.Work

/** Every menu of PLAN §1.4 and §1.5. */
enum class MenuId {
    TRANSPORT, INSTRUMENT, LIBRARY, SHELF, WORK, MORE, SOUND, TEMPERAMENT, PITCH, REGISTRATION, RESONANCE, REVERB,
    SPEAKER_BASS, SIGHT, ROOM, PALETTE, FINISH, STEREO_DEPTH, EDGE, CALIBRATE
}
enum class AdjustKind { POSITION, TEMPO }
enum class CardKind { FLOOR, SYNC }
enum class PanelKind { CREDITS, ABOUT, IMPORT }

/** What to do with the menu after a row's actions: stay on it, go back one level, or close every menu. */
enum class After { STAY, BACK, CLOSE }

/** One level of the menu stack; [cursor] is the highlighted row. [shelf]/[work] parameterise SHELF and WORK. */
class MenuLevel(val id: MenuId, val shelf: String? = null, val work: String? = null) { var cursor: Int = 0 }

sealed class Choice {
    /** Push a sub-menu, emitting [actions] as it opens (e.g. Rescan on Library). */
    class Open(val level: MenuLevel, val actions: List<UiAction> = emptyList()) : Choice()
    class Do(val actions: List<UiAction>, val after: After) : Choice()
    class OpenAdjust(val kind: AdjustKind) : Choice()
    class OpenCard(val kind: CardKind) : Choice()
    class OpenPanel(val kind: PanelKind) : Choice()
    /** A row that does nothing (e.g. an empty list). */
    data object None : Choice()
}

class MenuRow(val label: String, val choice: Choice, val current: Boolean = false)

/**
 * Menu rows and titles, rebuilt from [UiFacts] every time (pure; main thread). Shelf ids
 * [SHELF_START] and [SHELF_RECENT] are virtual: they are what `UiAction.Play.shelfId` carries for
 * Start here and Recently played; every other shelf id is the catalogue's.
 */
object MenuTree {
    const val PAGE = 7
    const val SHELF_START = "start-here"
    const val SHELF_RECENT = "recent"
    const val SHELF_IMPORTED = "imported"
    private const val TICK = "  ✓"

    val TEMPOS = 50..150
    val PITCHES = floatArrayOf(440f, 430f, 415f, 392f)
    val DEPTHS = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f)

    fun title(level: MenuLevel, f: UiFacts): String = when (level.id) {
        MenuId.TRANSPORT -> "Transport"
        MenuId.INSTRUMENT -> "Instrument"
        MenuId.LIBRARY -> "Library"
        MenuId.SHELF -> shelfTitle(level.shelf, f)
        MenuId.WORK -> f.library?.works?.get(level.work)?.let { it.composerShort + " · " + it.shortTitle } ?: "Work"
        MenuId.MORE -> "More"
        MenuId.SOUND -> "Sound"
        MenuId.TEMPERAMENT -> "Temperament · " + UiText.instrumentShort(f.instrument)
        MenuId.PITCH -> "Pitch · " + UiText.instrumentShort(f.instrument)
        MenuId.REGISTRATION -> "Registration"
        MenuId.RESONANCE -> "Resonance"
        MenuId.REVERB -> "Reverb"
        MenuId.SPEAKER_BASS -> "Speaker bass"
        MenuId.SIGHT -> "Sight"
        MenuId.ROOM -> "Room"
        MenuId.PALETTE -> "Palette"
        MenuId.FINISH -> "Upright finish"
        MenuId.STEREO_DEPTH -> "Stereo depth"
        MenuId.EDGE -> "Edge overlay"
        MenuId.CALIBRATE -> "Calibrate"
    }

    fun rows(level: MenuLevel, f: UiFacts): List<MenuRow> = when (level.id) {
        MenuId.TRANSPORT -> transport(f)
        MenuId.INSTRUMENT -> instruments(f)
        MenuId.LIBRARY -> shelves(f)
        MenuId.SHELF -> shelf(level.shelf ?: "", f)
        MenuId.WORK -> work(level.shelf, level.work ?: "", f)
        MenuId.MORE -> listOf(
            MenuRow("Sound ›", Choice.Open(MenuLevel(MenuId.SOUND))),
            MenuRow("Sight ›", Choice.Open(MenuLevel(MenuId.SIGHT))),
            MenuRow("Calibrate ›", Choice.Open(MenuLevel(MenuId.CALIBRATE))),
            MenuRow("Import from phone", Choice.OpenPanel(PanelKind.IMPORT)),
            MenuRow("Credits", Choice.OpenPanel(PanelKind.CREDITS)),
            MenuRow("About", Choice.OpenPanel(PanelKind.ABOUT)))
        MenuId.SOUND -> sound(f)
        MenuId.TEMPERAMENT -> {
            val t = tuningOf(f)
            Temperament.entries.map { tm ->
                MenuRow(tm.label + if (tm == t.temperament) TICK else "",
                    Choice.Do(listOf(UiAction.SetTuning(f.instrument, TuningSpec(t.aHz, tm))), After.BACK), tm == t.temperament)
            }
        }
        MenuId.PITCH -> {
            val t = tuningOf(f)
            PITCHES.map { hz ->
                val cur = Math.round(hz) == Math.round(t.aHz)
                MenuRow(UiText.pitch(hz) + if (cur) TICK else "",
                    Choice.Do(listOf(UiAction.SetTuning(f.instrument, TuningSpec(hz, t.temperament))), After.BACK), cur)
            }
        }
        MenuId.REGISTRATION -> intArrayOf(HK.REG_8 or HK.REG_4, HK.REG_8, HK.REG_4).map { m ->
            val cur = m == (f.settings.registration and (HK.REG_8 or HK.REG_4)).let { if (it == 0) HK.REG_8 or HK.REG_4 else it }
            MenuRow(UiText.registration(m) + if (cur) TICK else "", Choice.Do(listOf(UiAction.SetRegistration(m)), After.BACK), cur)
        }
        MenuId.RESONANCE -> ResonanceMode.entries.map { m ->
            option(UiText.resonance(m), m == f.settings.resonance, UiAction.SetMix(resonance = m))
        }
        MenuId.REVERB -> ReverbMode.entries.map { m -> option(UiText.reverb(m), m == f.settings.reverb, UiAction.SetMix(reverb = m)) }
        MenuId.SPEAKER_BASS -> SpeakerBass.entries.map { m ->
            option(UiText.speakerBass(m), m == f.settings.speakerBass, UiAction.SetMix(speakerBass = m))
        }
        MenuId.SIGHT -> sight(f)
        MenuId.ROOM -> listOf<RoomLevel?>(null, RoomLevel.SALON, RoomLevel.STAGE, RoomLevel.INSTRUMENT, RoomLevel.PASSTHROUGH).map { r ->
            option(UiText.room(r), r == f.settings.roomOverride,
                if (r == null) UiAction.SetSight(autoRoom = true) else UiAction.SetSight(room = r))
        }
        MenuId.PALETTE -> Palette.entries.map { p -> option(UiText.palette(p), p == f.settings.palette, UiAction.SetSight(palette = p)) }
        MenuId.FINISH -> UprightFinish.entries.map { u -> option(UiText.finish(u), u == f.settings.finish, UiAction.SetSight(finish = u)) }
        MenuId.STEREO_DEPTH -> DEPTHS.map { d ->
            option("${Math.round(d * 100)} %", Math.abs(d - f.settings.stereoDepth) < 0.01f, UiAction.SetSight(stereoDepth = d))
        }
        MenuId.EDGE -> listOf<Boolean?>(null, true, false).map { e ->
            if (e == null) option(UiText.autoOnOff(null), f.settings.edgeOverlay == null, UiAction.SetSight(autoEdgeOverlay = true))
            else option(UiText.autoOnOff(e), f.settings.edgeOverlay == e, UiAction.SetSight(edgeOverlay = e))
        }
        MenuId.CALIBRATE -> listOf(
            MenuRow("Display floor", Choice.OpenCard(CardKind.FLOOR)),
            MenuRow("A/V sync · " + UiText.routeName(f.route), Choice.OpenCard(CardKind.SYNC)))
    }

    private fun option(label: String, current: Boolean, a: UiAction) =
        MenuRow(label + if (current) TICK else "", Choice.Do(listOf(a), After.BACK), current)

    fun tuningOf(f: UiFacts): TuningSpec = f.settings.tuning[f.instrument] ?: UiText.defaultTuning(f.instrument)

    private fun transport(f: UiFacts): List<MenuRow> = listOf(
        MenuRow(if (f.playing) "❚❚ Pause" else "▶ Play", Choice.Do(listOf(UiAction.PlayPause), After.STAY)),
        MenuRow(if (f.nextTitle != null) "Next: «${f.nextTitle}»" else "Next", Choice.Do(listOf(UiAction.Next), After.STAY)),
        MenuRow("Previous", Choice.Do(listOf(UiAction.Previous), After.STAY)),
        MenuRow("Position " + UiText.positionLine(f.positionUs, f.durationUs) + " ›", Choice.OpenAdjust(AdjustKind.POSITION)),
        MenuRow("Instrument: " + UiText.instrument(f.instrument) + " ›", Choice.Open(MenuLevel(MenuId.INSTRUMENT))),
        MenuRow("Library ›", Choice.Open(MenuLevel(MenuId.LIBRARY), listOf(UiAction.Rescan))),
        MenuRow("More ›", Choice.Open(MenuLevel(MenuId.MORE))))

    /** Voicing percentage while a kit is not complete, else null. */
    fun voicingPct(s: KitState?): Int? = if (s is KitState.Voicing) Math.round(s.fraction.coerceIn(0f, 1f) * 100f) else null

    private fun instruments(f: UiFacts): List<MenuRow> {
        val work = currentWork(f)
        return InstrumentId.entries.map { id ->
            val sb = StringBuilder(UiText.instrument(id))
            if (work != null && work.defaultInstrument == id) sb.append(" (piece default)")
            voicingPct(f.kitStates[id])?.let { sb.append(" · voicing ").append(it).append('%') }
            if (id == f.instrument) sb.append(TICK)
            MenuRow(sb.toString(), Choice.Do(listOf(UiAction.SetInstrument(id)), After.CLOSE), id == f.instrument)
        }
    }

    fun currentWork(f: UiFacts): Work? {
        val lib = f.library ?: return null
        val m = lib.movements[f.movementId ?: return null] ?: return null
        return lib.works[m.workId]
    }

    private fun sound(f: UiFacts): List<MenuRow> {
        val out = ArrayList<MenuRow>()
        out.add(MenuRow("Tempo ${f.settings.tempoPct}% ›", Choice.OpenAdjust(AdjustKind.TEMPO)))
        out.add(MenuRow("Temperament ›", Choice.Open(MenuLevel(MenuId.TEMPERAMENT))))
        out.add(MenuRow("Pitch ›", Choice.Open(MenuLevel(MenuId.PITCH))))
        if (f.instrument == InstrumentId.HARPSICHORD) out.add(MenuRow("Registration ›", Choice.Open(MenuLevel(MenuId.REGISTRATION))))
        out.add(MenuRow("Resonance ›", Choice.Open(MenuLevel(MenuId.RESONANCE))))
        out.add(MenuRow("Reverb ›", Choice.Open(MenuLevel(MenuId.REVERB))))
        out.add(MenuRow("Speaker bass ›", Choice.Open(MenuLevel(MenuId.SPEAKER_BASS))))
        val s = f.settings
        out.add(MenuRow("Key release noise: " + UiText.onOff(s.releaseNoises), Choice.Do(listOf(UiAction.SetMix(releaseNoises = !s.releaseNoises)), After.STAY)))
        out.add(MenuRow("Pedal noise: " + UiText.onOff(s.pedalNoises), Choice.Do(listOf(UiAction.SetMix(pedalNoises = !s.pedalNoises)), After.STAY)))
        return out
    }

    private fun sight(f: UiFacts): List<MenuRow> {
        val s = f.settings
        return listOf(
            MenuRow("Room ›", Choice.Open(MenuLevel(MenuId.ROOM))),
            MenuRow("Palette ›", Choice.Open(MenuLevel(MenuId.PALETTE))),
            MenuRow("Upright finish ›", Choice.Open(MenuLevel(MenuId.FINISH))),
            MenuRow("Stereo depth ›", Choice.Open(MenuLevel(MenuId.STEREO_DEPTH))),
            MenuRow("Look-around: " + UiText.onOff(s.lookAround), Choice.Do(listOf(UiAction.SetSight(lookAround = !s.lookAround)), After.STAY)),
            MenuRow("Edge overlay ›", Choice.Open(MenuLevel(MenuId.EDGE))),
            MenuRow("Reverse swipe: " + UiText.onOff(s.reverseSwipe), Choice.Do(listOf(UiAction.SetSight(reverseSwipe = !s.reverseSwipe)), After.STAY)),
            MenuRow("Antialiasing: " + UiText.onOff(s.msaa) + " · next launch", Choice.Do(listOf(UiAction.SetSight(msaa = !s.msaa)), After.STAY)))
    }

    // ─── library ───

    private fun shelfTitle(id: String?, f: UiFacts): String = when (id) {
        SHELF_START -> "Start here"
        SHELF_RECENT -> "Recently played"
        else -> f.library?.shelves?.firstOrNull { it.id == id }?.title ?: "Shelf"
    }

    /** The 15 shelves of §1.5 in order; Imported and Recently played only when not empty. */
    private fun shelves(f: UiFacts): List<MenuRow> {
        val lib = f.library ?: return listOf(MenuRow("Library loading…", Choice.None))
        val out = ArrayList<MenuRow>()
        if (lib.startHere.isNotEmpty()) out.add(MenuRow("Start here (${lib.startHere.size})", Choice.Open(MenuLevel(MenuId.SHELF, SHELF_START))))
        val recent = f.recent.filter { lib.movements.containsKey(it) }
        fun count(sh: com.tropicalstream.hammerklavier.contract.Shelf) = sh.workIds.count { lib.works.containsKey(it) }
        lib.shelves.firstOrNull { it.id == SHELF_IMPORTED }?.let { s ->
            val works = count(s)
            if (works > 0) out.add(MenuRow("${s.title} ($works)", Choice.Open(MenuLevel(MenuId.SHELF, s.id))))
        }
        if (recent.isNotEmpty()) out.add(recentRow())
        for (s in lib.shelves) {
            if (s.id == SHELF_START || s.id == SHELF_RECENT || s.id == SHELF_IMPORTED) continue
            if (count(s) == 0) continue
            out.add(MenuRow(s.title, Choice.Open(MenuLevel(MenuId.SHELF, s.id))))
        }
        return out
    }

    private fun recentRow() = MenuRow("Recently played", Choice.Open(MenuLevel(MenuId.SHELF, SHELF_RECENT)))

    private fun shelf(id: String, f: UiFacts): List<MenuRow> {
        val lib = f.library ?: return emptyList()
        if (id == SHELF_START || id == SHELF_RECENT) {
            val ids = if (id == SHELF_START) lib.startHere else f.recent
            return ids.mapNotNull { mid -> lib.movements[mid]?.let { m -> movementRow(lib, m, id) } }
        }
        val s = lib.shelves.firstOrNull { it.id == id } ?: return emptyList()
        return s.workIds.mapNotNull { wid ->
            val w = lib.works[wid] ?: return@mapNotNull null
            val label = w.composerShort + " · " + w.shortTitle + "   " + UiText.clock(workSeconds(lib, w)) + "  " + UiText.instrumentLetter(w.defaultInstrument)
            val mids = w.movementIds.filter { lib.movements.containsKey(it) }
            when {
                mids.isEmpty() -> null
                mids.size == 1 -> MenuRow(label, Choice.Do(listOf(UiAction.Play(mids[0], id)), After.CLOSE))
                else -> MenuRow(label, Choice.Open(MenuLevel(MenuId.WORK, id, w.id)))
            }
        }
    }

    private fun movementRow(lib: LibraryModel, m: Movement, shelfId: String): MenuRow {
        val w = lib.works[m.workId]
        val name = when {
            w == null -> m.title
            w.movementIds.size <= 1 -> w.composerShort + " · " + w.shortTitle
            else -> w.composerShort + " · " + w.shortTitle + " · " + m.title
        }
        val letter = w?.let { "  " + UiText.instrumentLetter(it.defaultInstrument) } ?: ""
        return MenuRow(name + "   " + UiText.clock(Math.round(m.durationSec).toLong()) + letter,
            Choice.Do(listOf(UiAction.Play(m.id, shelfId)), After.CLOSE))
    }

    private fun work(shelfId: String?, workId: String, f: UiFacts): List<MenuRow> {
        val lib = f.library ?: return emptyList()
        val w = lib.works[workId] ?: return emptyList()
        val ms = w.movementIds.mapNotNull { lib.movements[it] }
        if (ms.isEmpty()) return emptyList()
        val out = ArrayList<MenuRow>()
        out.add(MenuRow("Play all ▶", Choice.Do(listOf(UiAction.Play(ms[0].id, shelfId)), After.CLOSE)))
        for (m in ms) out.add(MenuRow(m.title + "   " + UiText.clock(Math.round(m.durationSec).toLong()),
            Choice.Do(listOf(UiAction.Play(m.id, shelfId)), After.CLOSE)))
        return out
    }

    fun workSeconds(lib: LibraryModel, w: Work): Long {
        var s = 0.0
        for (id in w.movementIds) s += lib.movements[id]?.durationSec ?: 0f
        return Math.round(s)
    }
}
