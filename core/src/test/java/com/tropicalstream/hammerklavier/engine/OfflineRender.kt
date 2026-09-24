package com.tropicalstream.hammerklavier.engine

import com.tropicalstream.hammerklavier.contract.EngineCoreApi
import com.tropicalstream.hammerklavier.contract.HK
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Offline rendering for listening and for other WPs' JVM calibration renders (PLAN §7.2 WP2):
 * drives any [EngineCoreApi] block by block and writes 16-bit stereo WAVs to
 * `core/build/renders/<name>.wav` (the test task's working directory is the module).
 */
object OfflineRender {
    /** Renders [frames] frames (rounded up to whole blocks) of [core] into an interleaved stereo array. */
    fun render(core: EngineCoreApi, frames: Int, startFrame: Long = 0L): FloatArray {
        val blocks = (frames + HK.BLOCK - 1) / HK.BLOCK
        val out = FloatArray(2 * blocks * HK.BLOCK)
        val b = FloatArray(2 * HK.BLOCK)
        for (i in 0 until blocks) {
            core.render(b, startFrame + i.toLong() * HK.BLOCK)
            System.arraycopy(b, 0, out, 2 * i * HK.BLOCK, 2 * HK.BLOCK)
        }
        return out
    }

    fun dir(): File = File("build/renders").also { it.mkdirs() }

    /** Writes [interleaved] (stereo, ±1 full scale, clipped) as 16-bit PCM; returns the file. */
    fun writeWav(name: String, interleaved: FloatArray, sampleRate: Int = HK.SR, frames: Int = interleaved.size / 2): File =
        writeWavTo(File(dir(), "$name.wav"), interleaved, sampleRate, frames)

    /** [writeWav] to an explicit file. */
    fun writeWavTo(f: File, interleaved: FloatArray, sampleRate: Int = HK.SR, frames: Int = interleaved.size / 2): File {
        f.parentFile?.mkdirs()
        val n = frames.coerceAtMost(interleaved.size / 2)
        val data = 4 * n
        val bb = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + data); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(2); bb.putInt(sampleRate)
        bb.putInt(sampleRate * 4); bb.putShort(4); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(data)
        for (i in 0 until 2 * n) bb.putShort((interleaved[i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
        FileOutputStream(f).use { it.write(bb.array()) }
        return f
    }
}
