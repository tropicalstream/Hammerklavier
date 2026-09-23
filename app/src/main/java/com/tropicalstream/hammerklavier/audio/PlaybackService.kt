package com.tropicalstream.hammerklavier.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.tropicalstream.hammerklavier.contract.HK

/**
 * The `mediaPlayback` foreground service (PLAN §3.1, §8.4): **built but disabled**
 * (`HK.USE_FG_SERVICE = false`, `android:enabled="false"` in the manifest). If the display-off
 * T-UND/T-PF tests fail, WP0 flips both; then it is started while the activity is resumed
 * (Android 12 forbids starting it from the background) and stopped on pause or at the end of the
 * playlist item. It only holds the process in the foreground group; audio stays in AudioOutput.
 */
class PlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW))
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Hammerklavier"
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(ID, n)
        return START_NOT_STICKY
    }

    companion object {
        const val CHANNEL = "hk_playback"
        const val ID = 1747
        const val EXTRA_TITLE = "title"

        /** Call while the activity is resumed; a no-op unless HK.USE_FG_SERVICE. */
        fun start(ctx: Context, title: String?) {
            if (!HK.USE_FG_SERVICE) return
            runCatching { ctx.startForegroundService(Intent(ctx, PlaybackService::class.java).putExtra(EXTRA_TITLE, title)) }
        }

        fun stop(ctx: Context) {
            if (!HK.USE_FG_SERVICE) return
            runCatching { ctx.stopService(Intent(ctx, PlaybackService::class.java)) }
        }
    }
}
