package com.tropicalstream.hammerklavier.midi

import com.tropicalstream.hammerklavier.contract.PerfWarning
import com.tropicalstream.hammerklavier.contract.RejectReason

/** Parser bounds (PLAN §4.1). Exceeding one returns [SmfResult.Failed]. */
class SmfLimits(val maxBytes: Int = 8 shl 20, val maxEvents: Int = 2_000_000, val maxTracks: Int = 256) {
    companion object { val DEFAULT = SmfLimits() }
}

/** Why a file was refused; each maps to the library's [RejectReason]. */
enum class SmfError(val reason: RejectReason) {
    NOT_MIDI(RejectReason.NOT_MIDI), BAD_HEADER(RejectReason.BAD_HEADER), TRUNCATED(RejectReason.TRUNCATED),
    TOO_LARGE(RejectReason.TOO_LARGE), TOO_MANY_EVENTS(RejectReason.TOO_MANY_EVENTS),
    NO_KEYBOARD_NOTES(RejectReason.NO_KEYBOARD_NOTES)
}

/**
 * One track as parallel primitive arrays (no event objects). Only the events the builder uses
 * are kept: notes, CC64/66/67, all-notes-off (CC120/CC123), tempo, time and key signatures.
 * [tick] is absolute (format 2 tracks are already offset to play one after another).
 * [value]: tempo µs per quarter, time signature `num shl 8 or denPow`, key signature
 * `sf shl 8 or mi` (sf signed); 0 for channel events.
 */
class RawTrack(
    @JvmField val tick: LongArray, @JvmField val kind: ByteArray, @JvmField val ch: ByteArray,
    @JvmField val d1: ByteArray, @JvmField val d2: ByteArray, @JvmField val value: IntArray,
    val endTick: Long, val name: String?) {
    val size: Int get() = tick.size

    companion object {
        const val K_NOTE_OFF = 0      // d1 key, d2 release velocity (a note-on with velocity 0 is stored as this)
        const val K_NOTE_ON = 1       // d1 key, d2 velocity 1..127
        const val K_CC = 2            // d1 controller (64, 66, 67), d2 value
        const val K_ALL_OFF = 3       // CC120 / CC123 on channel ch
        const val K_TEMPO = 4
        const val K_TIMESIG = 5
        const val K_KEYSIG = 6
    }
}

/** A parsed file. SMPTE files have [ppq] = 0 and a constant [smpteUsPerTick]. */
class RawSmf(
    val format: Int, val ppq: Int, val smpteUsPerTick: Double, val tracks: List<RawTrack>,
    val title: String?, val copyright: String?, val texts: List<String>,
    val warnings: Set<PerfWarning>, val eventCount: Int, val byteCount: Int)

sealed class SmfResult {
    class Ok(val smf: RawSmf) : SmfResult()
    class Failed(val error: SmfError, val detail: String, val byteOffset: Int) : SmfResult()
}
