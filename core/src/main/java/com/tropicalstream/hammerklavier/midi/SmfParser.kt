package com.tropicalstream.hammerklavier.midi

import com.tropicalstream.hammerklavier.contract.PerfWarning

/**
 * Standard MIDI File parser (PLAN §4.1): pure, tolerant, bounded, never throws.
 *
 * - Container: `MThd` (header length ≥ 6, extra bytes skipped), RIFF `RMID` unwrapped, `.kar`
 *   accepted; non-`MTrk` chunks skipped by length; a chunk running past EOF is clamped (warning);
 *   bytes after the last chunk that do not form a chunk → warning.
 * - Formats 0 and 1; format 2 tracks are laid one after another (warning).
 * - Division: PPQ, or SMPTE (−24, −25, −29 = 29.97, −30 fps × ticks per frame).
 * - Events: VLQ ≤ 4 bytes (a longer one ends the track, warning); running status, cancelled by
 *   meta and sysex; a data byte where a status was expected reuses the last channel status
 *   (tolerant, warning); sysex skipped by length; F8–FE skipped; F1–F6 resync to the next status.
 */
object SmfParser {

    fun parse(bytes: ByteArray, limits: SmfLimits = SmfLimits.DEFAULT): SmfResult =
        try { Parse(bytes, limits).run() } catch (e: RuntimeException) {
            // Defensive only: every read is bounds-checked; the fuzz test (T1.6) asserts this never fires.
            SmfResult.Failed(SmfError.TRUNCATED, "parser: " + e.javaClass.simpleName, 0)
        }

    /** "MThd" at 0, or RIFF…RMID. */
    fun sniff(head: ByteArray): Boolean = tag(head, 0, "MThd") || (tag(head, 0, "RIFF") && tag(head, 8, "RMID"))

    internal fun tag(b: ByteArray, o: Int, s: String): Boolean {
        if (o < 0 || o + 4 > b.size) return false
        for (i in 0 until 4) if (b[o + i] != s[i].code.toByte()) return false
        return true
    }

    private class Fail(val error: SmfError, val detail: String, val at: Int) : Exception(null, null, false, false)

    private class Parse(val b: ByteArray, val limits: SmfLimits) {
        val warnings = LinkedHashSet<PerfWarning>()
        val texts = ArrayList<String>()
        var copyright: String? = null
        var events = 0

        fun u8(i: Int) = b[i].toInt() and 0xFF
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        fun u32(i: Int): Long = (u8(i).toLong() shl 24) or (u8(i + 1).toLong() shl 16) or (u8(i + 2).toLong() shl 8) or u8(i + 3).toLong()
        fun u32le(i: Int): Long = (u8(i + 3).toLong() shl 24) or (u8(i + 2).toLong() shl 16) or (u8(i + 1).toLong() shl 8) or u8(i).toLong()

        fun run(): SmfResult {
            try {
                if (b.size > limits.maxBytes) throw Fail(SmfError.TOO_LARGE, "file is ${b.size} bytes (limit ${limits.maxBytes})", 0)
                var start = 0
                var end = b.size
                if (tag(b, 0, "RIFF")) {
                    if (!tag(b, 8, "RMID")) throw Fail(SmfError.NOT_MIDI, "RIFF but not RMID", 8)
                    var p = 12
                    var found = false
                    while (p + 8 <= b.size) {
                        val len = u32le(p + 4)
                        if (tag(b, p, "data")) {
                            start = p + 8
                            end = if (start + len > b.size) b.size else (start + len).toInt()
                            found = true; break
                        }
                        val next = p + 8L + len + (len and 1L)
                        if (next > b.size) break
                        p = next.toInt()
                    }
                    if (!found) throw Fail(SmfError.NOT_MIDI, "RMID without a data chunk", 12)
                }
                return parseSmf(start, end)
            } catch (f: Fail) {
                return SmfResult.Failed(f.error, f.detail, f.at)
            }
        }

        fun parseSmf(start: Int, end: Int): SmfResult {
            if (!tag(b, start, "MThd")) throw Fail(SmfError.NOT_MIDI, "no MThd", start)
            if (start + 8 > end) throw Fail(SmfError.TRUNCATED, "header cut short", start)
            val hlen = u32(start + 4)
            if (hlen < 6) throw Fail(SmfError.BAD_HEADER, "header length $hlen", start + 4)
            if (start + 14 > end) throw Fail(SmfError.TRUNCATED, "header cut short", start)
            val format = u16(start + 8)
            val division = u16(start + 12)
            if (format > 2) throw Fail(SmfError.BAD_HEADER, "format $format", start + 8)
            var ppq = 0
            var smpte = 0.0
            if (division and 0x8000 == 0) {
                if (division == 0) throw Fail(SmfError.BAD_HEADER, "division 0", start + 12)
                ppq = division
            } else {
                val fps = -((division shr 8).toByte().toInt())
                val tpf = division and 0xFF
                val rate = when (fps) { 24 -> 24.0; 25 -> 25.0; 29 -> 29.97; 30 -> 30.0; else -> 0.0 }
                if (rate == 0.0 || tpf == 0) throw Fail(SmfError.BAD_HEADER, "SMPTE division $fps/$tpf", start + 12)
                smpte = 1e6 / (rate * tpf)
            }
            val tracks = ArrayList<RawTrack>()
            var p = if (start + 8 + hlen > end) end.toLong() else start + 8 + hlen
            var offsetTick = 0L
            while (p < end) {
                val pi = p.toInt()
                if (pi + 8 > end || !printable(pi)) { warnings.add(PerfWarning.TRAILING_JUNK); break }
                var len = u32(pi + 4)
                val body = pi + 8
                if (body + len > end) { len = (end - body).toLong(); warnings.add(PerfWarning.TRUNCATED_CHUNK) }
                if (tag(b, pi, "MTrk")) {
                    if (tracks.size >= limits.maxTracks) throw Fail(SmfError.TOO_LARGE, "more than ${limits.maxTracks} tracks", pi)
                    val t = parseTrack(body, body + len.toInt(), offsetTick)
                    tracks.add(t)
                    if (format == 2) offsetTick = t.endTick
                }
                p = body + len
            }
            if (tracks.isEmpty()) throw Fail(SmfError.TRUNCATED, "no MTrk chunk", start)
            if (format == 2 && tracks.size > 1) warnings.add(PerfWarning.FORMAT2_SEQUENTIAL)
            val title = tracks[0].name?.takeIf { it.isNotBlank() } ?: texts.firstOrNull { it.isNotBlank() }
            return SmfResult.Ok(RawSmf(format, ppq, smpte, tracks, title?.trim(), copyright, texts, warnings, events, b.size))
        }

        fun printable(i: Int): Boolean {
            for (k in 0 until 4) { val c = u8(i + k); if (c < 0x20 || c > 0x7E) return false }
            return true
        }

        // Growable per-track arrays.
        var n = 0
        var tk = LongArray(256); var kd = ByteArray(256); var chn = ByteArray(256)
        var a1 = ByteArray(256); var a2 = ByteArray(256); var vl = IntArray(256)

        fun add(tick: Long, kind: Int, ch: Int, d1: Int, d2: Int, value: Int) {
            if (n == tk.size) {
                val c = n * 2
                tk = tk.copyOf(c); kd = kd.copyOf(c); chn = chn.copyOf(c); a1 = a1.copyOf(c); a2 = a2.copyOf(c); vl = vl.copyOf(c)
            }
            tk[n] = tick; kd[n] = kind.toByte(); chn[n] = ch.toByte(); a1[n] = d1.toByte(); a2[n] = d2.toByte(); vl[n] = value
            n++
        }

        fun count(at: Int) {
            if (++events > limits.maxEvents) throw Fail(SmfError.TOO_MANY_EVENTS, "more than ${limits.maxEvents} events", at)
        }

        fun text(o: Int, len: Int): String = String(b, o, len, Charsets.ISO_8859_1)

        fun parseTrack(from: Int, to: Int, offset: Long): RawTrack {
            n = 0
            var p = from
            var tick = offset
            var running = -1
            var lastChannel = -1
            var name: String? = null
            var endTick = -1L
            var resync = false                                  // the next byte is a status with no delta before it
            loop@ while (p < to) {
                // Delta time: at most 4 bytes.
                var delta = 0L
                var k = 0
                while (!resync) {
                    if (p >= to) break@loop
                    val c = u8(p++)
                    delta = (delta shl 7) or (c and 0x7F).toLong()
                    if (c and 0x80 == 0) break
                    if (++k == 4) { warnings.add(PerfWarning.TRUNCATED_CHUNK); break@loop }
                }
                resync = false
                tick += delta
                if (p >= to) break
                var status = u8(p)
                if (status < 0x80) {
                    if (running >= 0) status = running
                    else if (lastChannel >= 0) { status = lastChannel; running = status; warnings.add(PerfWarning.RUNNING_STATUS_REPAIRED) }
                    else { p++; continue@loop }                 // resync: a stray data byte
                } else p++
                when {
                    status < 0xF0 -> {
                        running = status; lastChannel = status
                        val type = status shr 4
                        val ch = status and 0x0F
                        val need = if (type == 0xC || type == 0xD) 1 else 2
                        if (p + need > to) break@loop
                        val d1 = u8(p)
                        val d2 = if (need == 2) u8(p + 1) else 0
                        if (d1 >= 0x80 || d2 >= 0x80) {                // a status inside the message: drop it and resync
                            if (d1 < 0x80) p++
                            resync = true
                            continue@loop
                        }
                        p += need
                        count(p)
                        when (type) {
                            0x8 -> add(tick, RawTrack.K_NOTE_OFF, ch, d1, d2, 0)
                            0x9 -> if (d2 == 0) add(tick, RawTrack.K_NOTE_OFF, ch, d1, 0, 0) else add(tick, RawTrack.K_NOTE_ON, ch, d1, d2, 0)
                            0xB -> when (d1) {
                                64, 66, 67 -> add(tick, RawTrack.K_CC, ch, d1, d2, 0)
                                120, 123 -> add(tick, RawTrack.K_ALL_OFF, ch, d1, 0, 0)
                            }
                        }
                    }
                    status == 0xFF -> {
                        running = -1
                        if (p >= to) break@loop
                        val type = u8(p++)
                        val len = vlq(p, to)
                        if (len < 0) { warnings.add(PerfWarning.TRUNCATED_CHUNK); break@loop }
                        p = vlqEnd
                        val l = if (p + len > to) { warnings.add(PerfWarning.TRUNCATED_CHUNK); to - p } else len.toInt()
                        count(p)
                        when (type) {
                            0x51 -> if (l >= 3) {
                                val us = (u8(p) shl 16) or (u8(p + 1) shl 8) or u8(p + 2)
                                if (us > 0) add(tick, RawTrack.K_TEMPO, 0, 0, 0, us)
                            }
                            0x58 -> if (l >= 2) {
                                val num = u8(p); val den = u8(p + 1)
                                if (num in 1..64 && den <= 6) add(tick, RawTrack.K_TIMESIG, 0, 0, 0, (num shl 8) or den)
                            }
                            0x59 -> if (l >= 2) add(tick, RawTrack.K_KEYSIG, 0, 0, 0, (b[p].toInt() shl 8) or (u8(p + 1) and 1))
                            0x03 -> if (name == null) name = text(p, l)
                            0x02 -> if (copyright == null) copyright = text(p, l)
                            0x01 -> if (texts.size < 8) texts.add(text(p, l))
                            0x2F -> { endTick = tick; p += l; break@loop }
                        }
                        p += l
                    }
                    status == 0xF0 || status == 0xF7 -> {
                        running = -1
                        val len = vlq(p, to)
                        if (len < 0) { warnings.add(PerfWarning.TRUNCATED_CHUNK); break@loop }
                        p = vlqEnd
                        count(p)
                        if (p + len > to) { warnings.add(PerfWarning.TRUNCATED_CHUNK); break@loop }
                        p += len.toInt()
                    }
                    status >= 0xF8 -> { /* real-time byte: skipped, running status kept */ }
                    else -> {                                   // F1–F6: unknown here; resync to the next status byte
                        running = -1
                        while (p < to && u8(p) < 0x80) p++
                        resync = true
                    }
                }
            }
            if (endTick < 0) endTick = tick
            return RawTrack(tk.copyOf(n), kd.copyOf(n), chn.copyOf(n), a1.copyOf(n), a2.copyOf(n), vl.copyOf(n), endTick, name)
        }

        var vlqEnd = 0

        /** A ≤ 4-byte VLQ at [p]; −1 if it runs past [to] or is longer. Sets [vlqEnd]. */
        fun vlq(p0: Int, to: Int): Long {
            var p = p0
            var v = 0L
            for (k in 0 until 4) {
                if (p >= to) return -1
                val c = u8(p++)
                v = (v shl 7) or (c and 0x7F).toLong()
                if (c and 0x80 == 0) { vlqEnd = p; return v }
            }
            return -1
        }
    }
}
