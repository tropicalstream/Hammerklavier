package com.tropicalstream.hammerklavier.system

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.util.Log
import com.tropicalstream.hammerklavier.contract.HK
import com.tropicalstream.hammerklavier.contract.UiAction

/**
 * Media buttons (PLAN §2.2): the framework `android.media.session.MediaSession`, active while a
 * movement is loaded ([setLoaded]); play/pause, next and previous (a Bluetooth headset, a wired
 * remote) become [UiAction]s through [onAction]; the title is published as metadata. Lives as
 * long as the engine. Main thread.
 */
class MediaButtons(ctx: Context, private val onAction: (UiAction) -> Unit) {
    private val session = MediaSession(ctx, "Hammerklavier")
    private var playing = false
    private var released = false

    init {
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { if (!playing) fire(UiAction.PlayPause, "play") }
            override fun onPause() { if (playing) fire(UiAction.PlayPause, "pause") }
            override fun onStop() { if (playing) fire(UiAction.PlayPause, "stop") }
            override fun onSkipToNext() = fire(UiAction.Next, "next")
            override fun onSkipToPrevious() = fire(UiAction.Previous, "previous")
        })
        publishState(0L)
    }

    private fun fire(a: UiAction, what: String) { Log.i(HK.TAG_INPUT, "media $what"); onAction(a) }

    /** A movement is loaded (title) or not (null): the session is active only while one is. */
    fun setLoaded(title: String?, durationMs: Long = -1L) {
        if (released) return
        if (title == null) { session.isActive = false; return }
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs).build())
        session.isActive = true
    }

    fun setPlaying(on: Boolean, positionMs: Long) {
        if (released) return
        playing = on
        publishState(positionMs)
    }

    fun release() { if (!released) { released = true; session.isActive = false; session.release() } }

    private fun publishState(positionMs: Long) {
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, positionMs, if (playing) 1f else 0f)
            .build())
    }
}
