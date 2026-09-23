package com.tropicalstream.hammerklavier.midi

import com.tropicalstream.hammerklavier.contract.PedalCurve
import com.tropicalstream.hammerklavier.contract.ScoreSpec
import java.io.ByteArrayOutputStream

/** Test-only SMF writer (PLAN §2.2 WP1): hand-built files for the parser and builder tests. */
class SmfWriter {
    class Track(val runningStatus: Boolean = false) {
        private val out = ByteArrayOutputStream()
        private var last = -1

        fun delta(d: Long): Track { out.write(vlq(d)); return this }
        fun bytes(vararg b: Int): Track { for (x in b) out.write(x and 0xFF); return this }

        /** A channel message; omits the status byte when running status applies. */
        fun chan(d: Long, status: Int, vararg data: Int): Track {
            delta(d)
            if (!(runningStatus && status == last)) out.write(status)
            last = status
            for (x in data) out.write(x)
            return this
        }
        fun on(d: Long, ch: Int, key: Int, vel: Int) = chan(d, 0x90 or ch, key, vel)
        fun off(d: Long, ch: Int, key: Int, vel: Int = 64) = chan(d, 0x80 or ch, key, vel)
        fun cc(d: Long, ch: Int, c: Int, v: Int) = chan(d, 0xB0 or ch, c, v)
        fun program(d: Long, ch: Int, p: Int) = chan(d, 0xC0 or ch, p)
        fun meta(d: Long, type: Int, data: ByteArray): Track {
            delta(d); out.write(0xFF); out.write(type); out.write(vlq(data.size.toLong())); out.write(data); last = -1; return this
        }
        fun tempo(d: Long, us: Int) = meta(d, 0x51, byteArrayOf((us shr 16).toByte(), (us shr 8).toByte(), us.toByte()))
        fun timeSig(d: Long, num: Int, denPow: Int) = meta(d, 0x58, byteArrayOf(num.toByte(), denPow.toByte(), 24, 8))
        fun name(d: Long, s: String) = meta(d, 0x03, s.toByteArray(Charsets.ISO_8859_1))
        fun sysex(d: Long, data: ByteArray): Track {
            delta(d); out.write(0xF0); out.write(vlq(data.size.toLong())); out.write(data); last = -1; return this
        }
        fun end(d: Long = 0) = meta(d, 0x2F, ByteArray(0))
        fun build(): ByteArray = out.toByteArray()
    }

    companion object {
        fun vlq(v0: Long): ByteArray {
            var v = v0
            val tmp = ArrayList<Int>()
            tmp.add((v and 0x7F).toInt()); v = v ushr 7
            while (v > 0) { tmp.add(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
            return ByteArray(tmp.size) { tmp[tmp.size - 1 - it].toByte() }
        }

        fun chunk(id: String, body: ByteArray): ByteArray {
            val o = ByteArrayOutputStream()
            o.write(id.toByteArray(Charsets.ISO_8859_1))
            o.write(byteArrayOf((body.size ushr 24).toByte(), (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte()))
            o.write(body)
            return o.toByteArray()
        }

        fun header(format: Int, tracks: Int, division: Int): ByteArray =
            chunk("MThd", byteArrayOf(0, format.toByte(), (tracks shr 8).toByte(), tracks.toByte(), (division shr 8).toByte(), division.toByte()))

        fun file(format: Int, division: Int, vararg tracks: ByteArray): ByteArray {
            val o = ByteArrayOutputStream()
            o.write(header(format, tracks.size, division))
            for (t in tracks) o.write(chunk("MTrk", t))
            return o.toByteArray()
        }

        fun rmid(smf: ByteArray): ByteArray {
            val o = ByteArrayOutputStream()
            val size = 4 + 8 + smf.size + (smf.size and 1)
            o.write("RIFF".toByteArray()); o.write(le32(size)); o.write("RMID".toByteArray())
            o.write("data".toByteArray()); o.write(le32(smf.size)); o.write(smf)
            if (smf.size and 1 == 1) o.write(0)
            return o.toByteArray()
        }

        private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

        /**
         * The §4.5 byte twin of a spec: format 0, PPQ 500, 500,000 µs per quarter (1 tick = 1 ms),
         * notes and raw controller steps on channel 1, events in (time, off < cc < on) order.
         */
        fun twin(spec: ScoreSpec, runningStatus: Boolean = true): ByteArray {
            class E(val ms: Long, val cls: Int, val st: Int, val a: Int, val b: Int)
            val es = ArrayList<E>()
            for (i in spec.onUs.indices) {
                es.add(E(spec.onUs[i] / 1000, 3, 0x90, spec.key[i].toInt(), spec.vel[i].toInt()))
                es.add(E(spec.offUs[i] / 1000, 1, 0x80, spec.key[i].toInt(), 64))
            }
            fun cc(c: PedalCurve, num: Int) {
                for (i in c.us.indices) {
                    if (i + 1 < c.us.size && c.us[i + 1] == c.us[i]) continue
                    es.add(E(c.us[i] / 1000, 2, 0xB0, num, Math.round(c.v[i] * 127f)))
                }
            }
            cc(spec.cc64, 64); cc(spec.cc66, 66); cc(spec.cc67, 67)
            val sorted = es.withIndex().sortedWith(compareBy({ it.value.ms }, { it.value.cls }, { it.index })).map { it.value }
            val t = Track(runningStatus).tempo(0, 500_000).timeSig(0, 4, 2)
            var now = 0L
            for (e in sorted) { t.chan(e.ms - now, e.st, e.a, e.b); now = e.ms }
            t.end(0)
            return file(0, 500, t.build())
        }
    }
}
