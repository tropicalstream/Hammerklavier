package com.tropicalstream.hammerklavier.library

import com.tropicalstream.hammerklavier.contract.CompileOptions
import com.tropicalstream.hammerklavier.contract.CompileResult
import com.tropicalstream.hammerklavier.contract.InstrumentProfile
import com.tropicalstream.hammerklavier.contract.PedalMode
import com.tropicalstream.hammerklavier.contract.Performance
import com.tropicalstream.hammerklavier.contract.RejectReason
import com.tropicalstream.hammerklavier.contract.ScoreCompiler
import com.tropicalstream.hammerklavier.contract.ScoreFacts
import com.tropicalstream.hammerklavier.contract.SyntheticScore
import java.io.ByteArrayOutputStream

/** WP9's private test helpers: a tiny SMF writer and a minimal SMF reader standing in for WP1's parser. */
object TestMidi {
    /** Format 0, 480 ppq, 120 bpm; notes are (key, startTick, lengthTicks). */
    fun smf(name: String?, notes: List<Triple<Int, Int, Int>>, sustain: Boolean = false, salt: Int = 0): ByteArray {
        class Ev(val tick: Int, val bytes: ByteArray)
        val evs = ArrayList<Ev>()
        if (name != null) {
            val nb = name.toByteArray(Charsets.UTF_8)
            evs += Ev(0, byteArrayOf(0xFF.toByte(), 0x03, nb.size.toByte()) + nb)
        }
        if (salt != 0) evs += Ev(0, byteArrayOf(0xFF.toByte(), 0x01, 1, salt.toByte()))
        if (sustain) { evs += Ev(0, byteArrayOf(0xB0.toByte(), 64, 127)); evs += Ev(10, byteArrayOf(0xB0.toByte(), 64, 0)) }
        for ((k, s, l) in notes) {
            evs += Ev(s, byteArrayOf(0x90.toByte(), k.toByte(), 80))
            evs += Ev(s + l, byteArrayOf(0x80.toByte(), k.toByte(), 0))
        }
        evs.sortBy { it.tick }
        val trk = ByteArrayOutputStream()
        var last = 0
        for (e in evs) { vlq(trk, e.tick - last); trk.write(e.bytes); last = e.tick }
        vlq(trk, 0); trk.write(byteArrayOf(0xFF.toByte(), 0x2F, 0))
        val body = trk.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray()); out.write(be32(6)); out.write(byteArrayOf(0, 0, 0, 1, 1, 0xE0.toByte()))
        out.write("MTrk".toByteArray()); out.write(be32(body.size)); out.write(body)
        return out.toByteArray()
    }

    fun scale(name: String?, from: Int = 60, count: Int = 8, sustain: Boolean = false, salt: Int = 0) =
        smf(name, (0 until count).map { Triple(from + it, it * 240, 200) }, sustain, salt)

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun vlq(o: ByteArrayOutputStream, v0: Int) {
        var v = v0; var buf = v and 0x7F
        while (true) { v = v ushr 7; if (v == 0) break; buf = (buf shl 8) or ((v and 0x7F) or 0x80) }
        while (true) { o.write(buf and 0xFF); if (buf and 0x80 != 0) buf = buf ushr 8 else break }
    }
}

/** Reads format-0/1 SMF enough for import facts. Never throws. */
class MiniCompiler : ScoreCompiler {
    override fun sniff(head: ByteArray): Boolean {
        fun at(o: Int, s: String) = head.size >= o + 4 && (0..3).all { head[o + it] == s[it].code.toByte() }
        return at(0, "MThd") || (at(0, "RIFF") && at(8, "RMID"))
    }

    override fun inspect(bytes: ByteArray): ScoreFacts = try { read(bytes) } catch (e: Exception) { fail(RejectReason.TRUNCATED) }

    private fun fail(r: RejectReason) = ScoreFacts(false, r, null, 0f, 0, 0, 0, false, false, false, PedalMode.NONE, 0, emptyList())

    private fun read(b: ByteArray): ScoreFacts {
        if (!sniff(b)) return fail(RejectReason.NOT_MIDI)
        fun u8(i: Int) = b[i].toInt() and 0xFF
        fun u32(i: Int) = (u8(i) shl 24) or (u8(i + 1) shl 16) or (u8(i + 2) shl 8) or u8(i + 3)
        val ntrk = (u8(10) shl 8) or u8(11); val ppq = (u8(12) shl 8) or u8(13)
        var p = 14; var title: String? = null; var low = 127; var high = 0; var notes = 0; var sus = false; var maxTick = 0
        for (t in 0 until ntrk) {
            if (String(b, p, 4, Charsets.ISO_8859_1) != "MTrk") return fail(RejectReason.BAD_HEADER)
            val len = u32(p + 4); var i = p + 8; val end = i + len
            if (end > b.size) return fail(RejectReason.TRUNCATED)
            var tick = 0; var status = 0
            while (i < end) {
                var d = 0
                while (true) { val c = u8(i++); d = (d shl 7) or (c and 0x7F); if (c and 0x80 == 0) break }
                tick += d; maxTick = maxOf(maxTick, tick)
                var s = u8(i)
                if (s and 0x80 != 0) { i++; status = s } else s = status
                when {
                    s == 0xFF -> { val type = u8(i++); var l = 0
                        while (true) { val c = u8(i++); l = (l shl 7) or (c and 0x7F); if (c and 0x80 == 0) break }
                        if (type == 3 && t == 0 && title == null) title = String(b, i, l, Charsets.UTF_8)
                        i += l }
                    s and 0xF0 == 0x90 -> { val k = u8(i); val v = u8(i + 1); i += 2
                        if (v > 0 && s and 0x0F != 9) { notes++; low = minOf(low, k); high = maxOf(high, k) } }
                    s and 0xF0 == 0xB0 -> { if (u8(i) == 64) sus = true; i += 2 }
                    s and 0xF0 == 0xC0 || s and 0xF0 == 0xD0 -> i += 1
                    else -> i += 2
                }
            }
            p = end
        }
        if (notes == 0) { low = 0; high = 0 }
        return ScoreFacts(true, null, title, maxTick * 0.5f / ppq, low, high, notes, sus, false, false,
            if (sus) PedalMode.SWITCH else PedalMode.NONE, 1, emptyList())
    }

    override fun compile(bytes: ByteArray, id: String, generation: Int, profile: InstrumentProfile, opts: CompileOptions): CompileResult =
        CompileResult.Failed(RejectReason.NOT_MIDI, "MiniCompiler inspects only", 0)

    override fun synthetic(kind: SyntheticScore, profile: InstrumentProfile, generation: Int): Performance =
        throw UnsupportedOperationException()
}
