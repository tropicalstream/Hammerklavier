package com.tropicalstream.hammerklavier.library

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** SHA-1 of imported and bundled MIDI (PLAN §4.6 `sha1Hex`, §4.8 dedupe). Lower-case hex, 40 chars. */
object Sha1 {
    private val HEX = "0123456789abcdef".toCharArray()

    fun hex(bytes: ByteArray): String = toHex(MessageDigest.getInstance("SHA-1").digest(bytes))

    fun hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return toHex(md.digest())
    }

    fun toHex(d: ByteArray): String {
        val out = CharArray(d.size * 2)
        for (i in d.indices) {
            val v = d[i].toInt() and 0xFF
            out[2 * i] = HEX[v ushr 4]; out[2 * i + 1] = HEX[v and 15]
        }
        return String(out)
    }

    fun isHex40(s: String): Boolean = s.length == 40 && s.all { it in '0'..'9' || it in 'a'..'f' }
}
