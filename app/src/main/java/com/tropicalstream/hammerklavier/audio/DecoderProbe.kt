package com.tropicalstream.hammerklavier.audio

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.tropicalstream.hammerklavier.contract.HK
import kotlin.math.abs

/**
 * The decoder's start offset (PLAN §3.3): once per `Build.FINGERPRINT`, decode
 * `assets/instruments/probe.opus` (0.5 s of silence with a one-sample click at frame 4800, kit
 * encoding) and record `offset = argmax|x| − 4800` (0 if the decoder drops the 312-frame
 * pre-skip, 312 if not). |offset| > 960 is a probe failure (→ SynthBank). A missing probe asset
 * (early builds) assumes 0 and says so in [note]. HKVoicer.
 */
class DecoderProbe(private val ctx: Context, private val prefs: SharedPreferences) {
    sealed class Result {
        class Ok(val offset: Int, val note: String) : Result()
        class Failed(val reason: String, val decoderMissing: Boolean) : Result()
    }

    @Volatile var last: Result? = null; private set

    fun run(decoder: KitDecoder): Result {
        last?.let { return it }
        val fp = Build.FINGERPRINT ?: "unknown"
        if (prefs.getString(KEY_FP, null) == fp && prefs.contains(KEY_OFFSET)) {
            return Result.Ok(prefs.getInt(KEY_OFFSET, 0), "cached").also { last = it }
        }
        val afd = try { ctx.assets.openFd(ASSET) } catch (e: Exception) { null }
        if (afd == null) return Result.Ok(0, "no probe asset; offset 0 assumed").also { last = it; Log.w(HK.TAG_KIT, "DecoderProbe: $ASSET missing") }
        var frames = 0
        var peak = -1
        var peakFrame = -1
        val err = afd.use { fd ->
            decoder.decodeStream(fd, { false }) { buf, n, channels ->
                for (i in 0 until n) {
                    val v = abs(buf.getShort(i * 2 * channels).toInt())
                    if (v > peak) { peak = v; peakFrame = frames + i }
                }
                frames += n
            }
        }
        val r = when {
            err != null -> Result.Failed("probe decode: $err", decoderMissing = true)
            peakFrame < 0 || peak <= 0 -> Result.Failed("probe decoded silence", decoderMissing = false)
            else -> {
                val off = peakFrame - CLICK_FRAME
                if (abs(off) > MAX_OFFSET) Result.Failed("probe offset $off", decoderMissing = false)
                else { prefs.edit().putString(KEY_FP, fp).putInt(KEY_OFFSET, off).apply(); Result.Ok(off, "measured") }
            }
        }
        Log.i(HK.TAG_KIT, "DecoderProbe codec=${decoder.codecName} frames=$frames " +
            (if (r is Result.Ok) "offset=${r.offset}" else "failed: ${(r as Result.Failed).reason}"))
        last = r
        return r
    }

    companion object {
        const val ASSET = "instruments/probe.opus"
        const val CLICK_FRAME = 4800
        const val MAX_OFFSET = 960
        const val KEY_FP = "probe.fingerprint"
        const val KEY_OFFSET = "probe.offset"
    }
}
