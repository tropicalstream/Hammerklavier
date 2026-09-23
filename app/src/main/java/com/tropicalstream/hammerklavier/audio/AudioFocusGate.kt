package com.tropicalstream.hammerklavier.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Audio focus (PLAN §3.1): `AUDIOFOCUS_GAIN` requested on play; CAN_DUCK → duck to 0.3, GAIN →
 * duck 1; transient loss → pause; permanent loss → pause (the idle engine then parks). Main thread.
 */
class AudioFocusGate(ctx: Context, private val target: Target) {
    interface Target { fun focusDuck(gain: Float); fun focusPause(permanent: Boolean) }

    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager?
    private var held = false
    private val listener = AudioManager.OnAudioFocusChangeListener { change -> onChange(change) }
    private val request: AudioFocusRequest? = runCatching {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(listener)
            .build()
    }.getOrNull()

    /** true = granted (or no AudioManager: play anyway). */
    fun request(): Boolean {
        if (held) return true
        val r = request ?: return true
        val res = runCatching { am?.requestAudioFocus(r) }.getOrNull() ?: return true
        held = res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return held || res == AudioManager.AUDIOFOCUS_REQUEST_DELAYED
    }

    fun abandon() {
        if (!held) return
        request?.let { r -> runCatching { am?.abandonAudioFocusRequest(r) } }
        held = false
    }

    fun onChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> { held = true; target.focusDuck(1f) }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> target.focusDuck(DUCK)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> target.focusPause(permanent = false)
            AudioManager.AUDIOFOCUS_LOSS -> { held = false; target.focusPause(permanent = true) }
        }
    }

    companion object { const val DUCK = 0.3f }
}
