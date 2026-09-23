package com.tropicalstream.hammerklavier.ui.model

import com.tropicalstream.hammerklavier.contract.FallbackReason
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.Palette
import com.tropicalstream.hammerklavier.contract.PerfWarning
import com.tropicalstream.hammerklavier.contract.RejectReason
import com.tropicalstream.hammerklavier.contract.ResonanceMode
import com.tropicalstream.hammerklavier.contract.ReverbMode
import com.tropicalstream.hammerklavier.contract.RoomLevel
import com.tropicalstream.hammerklavier.contract.SpeakerBass
import com.tropicalstream.hammerklavier.contract.StatusCode
import com.tropicalstream.hammerklavier.contract.StatusItem
import com.tropicalstream.hammerklavier.contract.Temperament
import com.tropicalstream.hammerklavier.contract.TuningSpec
import com.tropicalstream.hammerklavier.contract.UprightFinish
import com.tropicalstream.hammerklavier.contract.ViewId
import com.tropicalstream.hammerklavier.contract.RouteInfo
import com.tropicalstream.hammerklavier.contract.OutputRoute

/**
 * Every user-facing word of Hammerklavier (PLAN §1.4, §1.8, R97). Producers emit codes; this
 * object maps them to text and priority. **Lower priority number = higher priority.**
 *
 * StatusItem argument conventions (producers: WP4, WP9, WP12, WP0):
 * - CRASH_LAST_SESSION: args[0] = the first line of crash.txt
 * - IMPORT_FAILED: args[0] = file name, args[1] = RejectReason name, args[2] (optional) = detail
 * - IMPORT_PERMISSION, SCORES_DIR_FOREIGN, AUDIO_UNAVAILABLE, DISPLAY_REST, NEW_BT_DEVICE: no args
 * - FALLBACK: args[0] = FallbackReason name
 * - AUDIO_STOPPED: args[0] = reason text (e.g. "output lost")
 * - IMPORTED: args[0] = file name, args[1] = note count, args[2] = duration in whole seconds
 * Missing or malformed args never throw; a neutral text is used instead.
 */
object UiText {
    const val APP_TITLE = "HAMMERKLAVIER"
    const val SUBTITLE = "Konzertzimmer · Sanssouci 1747"
    const val MENU_FOOTER = "⇄ move · tap choose · double-tap back"
    const val HINT = "⇄ views · tap pause · double-tap menu"
    const val NO_WIFI = "no Wi-Fi: use push_scores.sh"
    const val HEADPHONES = "Headphones recommended for the bass"
    const val DISPLAY_RESTING_TOAST = "display resting"
    const val PUSH_COMMAND = "tools/device/push_scores.sh"
    const val ABOUT_LINE = "Bach played Silbermann fortepianos at Potsdam in 1747; no recording of one may be shipped in " +
        "this app, so a harpsichord of the Flemish kind German builders grew from stands in for his keyboard."

    // ─── status line ───

    fun priority(code: StatusCode): Int = when (code) {
        StatusCode.CRASH_LAST_SESSION -> 1
        StatusCode.IMPORT_FAILED, StatusCode.IMPORT_PERMISSION, StatusCode.SCORES_DIR_FOREIGN -> 2
        StatusCode.FALLBACK, StatusCode.AUDIO_UNAVAILABLE, StatusCode.AUDIO_STOPPED -> 3
        StatusCode.DISPLAY_REST -> 4
        StatusCode.IMPORTED, StatusCode.NEW_BT_DEVICE -> 5
    }

    fun status(item: StatusItem): String = status(item.code, item.args)

    fun status(code: StatusCode, args: List<String>): String = when (code) {
        StatusCode.CRASH_LAST_SESSION -> "Last session ended unexpectedly: " + (args.getOrNull(0) ?: "unknown error")
        StatusCode.IMPORT_FAILED -> {
            val reason = args.getOrNull(1)?.let { n -> RejectReason.entries.firstOrNull { it.name == n } }
            if (reason == RejectReason.PERMISSION_DENIED) reject(reason, null)
            else "Couldn't read \"" + (args.getOrNull(0) ?: "file") + "\": " +
                (if (reason != null) reject(reason, args.getOrNull(2)) else (args.getOrNull(2) ?: "unreadable"))
        }
        StatusCode.IMPORT_PERMISSION -> "Permission denied: run push_scores.sh"
        StatusCode.SCORES_DIR_FOREIGN -> "Import folder owned by adb: run push_scores.sh"
        StatusCode.FALLBACK -> fallback(args.getOrNull(0)?.let { n -> FallbackReason.entries.firstOrNull { it.name == n } })
        StatusCode.AUDIO_UNAVAILABLE -> "Audio output unavailable"
        StatusCode.AUDIO_STOPPED -> "Audio stopped: " + (args.getOrNull(0) ?: "output lost")
        StatusCode.DISPLAY_REST -> "Resting the display to cool · music continues"
        StatusCode.IMPORTED -> {
            val name = args.getOrNull(0) ?: "file"
            val notes = args.getOrNull(1)?.toIntOrNull()
            val sec = args.getOrNull(2)?.toFloatOrNull()
            buildString {
                append("Imported \"").append(name).append('"')
                if (notes != null) append(" · ").append(thousands(notes)).append(" notes")
                if (sec != null) append(" · ").append(clock(sec.toLong()))
            }
        }
        StatusCode.NEW_BT_DEVICE -> "New headphones: More › Calibrate › A/V sync"
    }

    /** The winning status line: lowest priority number among items still active at [nowMs]; ties keep list order. */
    fun pickStatus(items: List<StatusItem>, nowMs: Long): StatusItem? {
        var best: StatusItem? = null
        for (it in items) {
            if (it.untilMs <= nowMs) continue
            if (best == null || priority(it.code) < priority(best.code)) best = it
        }
        return best
    }

    /** The detail after `Couldn't read "x.mid": ` (and the whole line for a permission problem). */
    fun reject(reason: RejectReason, detail: String?): String = when (reason) {
        RejectReason.NOT_MIDI -> "not a MIDI file"
        RejectReason.TRUNCATED -> "truncated track"
        RejectReason.BAD_HEADER -> "bad header"
        RejectReason.TOO_LARGE -> "file too large"
        RejectReason.TOO_MANY_EVENTS -> "too many events"
        RejectReason.NO_KEYBOARD_NOTES -> "no keyboard notes"
        RejectReason.DUPLICATE -> "already imported"
        RejectReason.ZIP_LIMIT -> "zip too large or too many entries"
        RejectReason.ZIP_TRAVERSAL -> "unsafe path in zip"
        RejectReason.PERMISSION_DENIED -> "Permission denied: run push_scores.sh"
        RejectReason.IO_ERROR -> if (detail.isNullOrBlank()) "read error" else "read error ($detail)"
    }

    fun fallback(reason: FallbackReason?): String = when (reason) {
        FallbackReason.BANK_MISSING -> "Stand-in tones: sample bank not installed"
        FallbackReason.DECODER_UNAVAILABLE -> "Stand-in tones: decoder unavailable"
        FallbackReason.PROBE_FAILED -> "Stand-in tones: decoder check failed"
        FallbackReason.LOW_STORAGE -> "Reduced grand: low storage"
        null -> "Stand-in tones"
    }

    /** Pill (or panel) text for a performance warning; counts come from PerfInfo. */
    fun perfWarning(w: PerfWarning, folded: Int = 0, merged: Int = 0): String = when (w) {
        PerfWarning.HANGING_NOTES -> "hanging notes closed"
        PerfWarning.FORMAT2_SEQUENTIAL -> "format 2 played in order"
        PerfWarning.TRUNCATED_CHUNK -> "truncated file"
        PerfWarning.TRAILING_JUNK -> "trailing data ignored"
        PerfWarning.RUNNING_STATUS_REPAIRED -> "running status repaired"
        PerfWarning.DRUMS_DROPPED -> "drums dropped"
        PerfWarning.CHANNELS_MERGED -> plural(merged, "channel") + " merged"
        PerfWarning.FOLDED -> plural(folded, "note") + " folded"
        PerfWarning.FINGER_PEDALLED -> "finger-pedalled"
    }

    // ─── names ───

    fun instrument(id: InstrumentId): String = when (id) {
        InstrumentId.GRAND -> "Grand"; InstrumentId.UPRIGHT -> "Upright"; InstrumentId.HARPSICHORD -> "Harpsichord · Bach era"
    }
    /** Short name for the tuning line and the pills. */
    fun instrumentShort(id: InstrumentId): String = when (id) {
        InstrumentId.GRAND -> "Grand"; InstrumentId.UPRIGHT -> "Upright"; InstrumentId.HARPSICHORD -> "Harpsichord"
    }
    fun instrumentLetter(id: InstrumentId): String = when (id) {
        InstrumentId.GRAND -> "G"; InstrumentId.UPRIGHT -> "U"; InstrumentId.HARPSICHORD -> "H"
    }

    fun defaultTuning(id: InstrumentId): TuningSpec =
        if (id == InstrumentId.HARPSICHORD) TuningSpec.A415_WERCKMEISTER else TuningSpec.A440_EQUAL

    fun pitch(aHz: Float): String = "A" + Math.round(aHz)
    fun temperament(t: Temperament): String = t.label

    fun registration(mask: Int): String = when (mask and (HK.REG_8 or HK.REG_4)) {
        HK.REG_8 -> "8′"; HK.REG_4 -> "4′"; else -> "8′+4′"
    }

    /** `Grand · A440 Equal` / `Harpsichord · A415 Werckmeister III · 8′+4′`. */
    fun tuningLine(id: InstrumentId, t: TuningSpec, registration: Int): String {
        val base = instrumentShort(id) + " · " + pitch(t.aHz) + " " + t.temperament.label
        return if (id == InstrumentId.HARPSICHORD) base + " · " + registration(registration) else base
    }

    fun viewToast(view: ViewId, framing: Int): String = when (view) {
        ViewId.PLAYER -> if (framing == 0) "Player" else "Player · follow"
        ViewId.ACTION -> if (framing == 0) "Action · hammers" else "Action · overhead"
        ViewId.HALL -> if (framing == 0) "Hall · Konzertzimmer" else "Hall · life-size"
    }

    fun room(r: RoomLevel?): String = when (r) {
        null -> "Auto"; RoomLevel.SALON -> "Salon"; RoomLevel.STAGE -> "Stage"
        RoomLevel.INSTRUMENT -> "Instrument"; RoomLevel.PASSTHROUGH -> "Passthrough"
    }
    fun palette(p: Palette): String = when (p) { Palette.SANSSOUCI_1747 -> "Sanssouci 1747"; Palette.STADTSCHLOSS_1747 -> "Stadtschloss 1747" }
    fun finish(f: UprightFinish): String = when (f) { UprightFinish.WALNUT -> "Walnut"; UprightFinish.MAHOGANY -> "Mahogany"; UprightFinish.EBONY -> "Ebony" }
    fun resonance(m: ResonanceMode): String = when (m) { ResonanceMode.OFF -> "Off"; ResonanceMode.NATURAL -> "Natural"; ResonanceMode.RICH -> "Rich" }
    fun reverb(m: ReverbMode): String = when (m) { ReverbMode.DRY -> "Dry"; ReverbMode.ROOM -> "Room"; ReverbMode.RESONANT -> "Resonant" }
    fun speakerBass(m: SpeakerBass): String = when (m) { SpeakerBass.AUTO -> "Auto"; SpeakerBass.ON -> "On"; SpeakerBass.OFF -> "Off" }
    fun onOff(b: Boolean): String = if (b) "On" else "Off"
    fun autoOnOff(b: Boolean?): String = when (b) { null -> "Auto"; true -> "On"; false -> "Off" }

    /** `speaker`, `wired` or the Bluetooth device's name (§1.4). */
    fun routeName(r: RouteInfo): String = when (r.route) {
        OutputRoute.SPEAKER -> "speaker"; OutputRoute.WIRED -> "wired"
        OutputRoute.BLUETOOTH -> r.name.ifBlank { "Bluetooth" }
    }

    // ─── title card ───

    fun voicingGrand(pct: Int) = "Voicing the grand… $pct%"
    fun tapToEnter(pct: Int?) = if (pct == null) "Tap to enter the Konzertzimmer" else "Tap to enter the Konzertzimmer · voicing $pct%"
    fun tapToContinue(title: String) = "Tap to continue: «$title»"
    fun phone(url: String, token: String) = "Phone: $url · token $token"
    fun voicingPill(id: InstrumentId, pct: Int) = "voicing " + instrumentShort(id).lowercase() + " $pct%"

    // ─── pills ───
    const val PILL_PAUSED = "paused"
    const val PILL_WARM = "▲ warm"
    const val PILL_STAND_IN = "stand-in tones"

    // ─── formatting ───

    /** Display time: song µs minus the 400 ms pre-roll, never negative. */
    fun displaySec(songUs: Long): Long = ((songUs - HK.PRE_ROLL_US).coerceAtLeast(0L)) / 1_000_000L

    /** m:ss, or h:mm:ss from one hour. */
    fun clock(totalSec: Long): String {
        val s = totalSec.coerceAtLeast(0L)
        val h = s / 3600; val m = (s / 60) % 60; val sec = s % 60
        return if (h > 0) "$h:${two(m)}:${two(sec)}" else "${s / 60}:${two(sec)}"
    }

    /** `3:12 / 9:53` from song µs (pre-roll excluded). */
    fun positionLine(positionUs: Long, durationUs: Long): String =
        clock(displaySec(positionUs)) + " / " + clock(displaySec(durationUs))

    fun thousands(n: Int): String {
        val s = kotlin.math.abs(n).toString()
        val b = StringBuilder()
        for (i in s.indices) { if (i > 0 && (s.length - i) % 3 == 0) b.append(','); b.append(s[i]) }
        return if (n < 0) "-$b" else b.toString()
    }

    fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"

    private fun two(v: Long) = if (v < 10) "0$v" else v.toString()

    /** Greedy word wrap for the 18 px panels (≈ 46 characters in the 600 px safe area). */
    fun wrap(text: String, width: Int = PANEL_COLUMNS): List<String> {
        val out = ArrayList<String>()
        for (para in text.split('\n')) {
            if (para.isBlank()) { out.add(""); continue }
            val line = StringBuilder()
            for (word in para.trim().split(Regex("\\s+"))) {
                if (line.isNotEmpty() && line.length + 1 + word.length > width) { out.add(line.toString()); line.setLength(0) }
                if (line.isNotEmpty()) line.append(' ')
                line.append(word)
            }
            out.add(line.toString())
        }
        return out
    }

    const val PANEL_COLUMNS = 46
}
