package com.milkdrop.visualizer.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.milkdrop.visualizer.R
import com.milkdrop.visualizer.ui.MainActivity

/** Keeps the consent token in a foreground service, as required by Android. */
class PlaybackProjectionService : Service() {
    private var projection: MediaProjection? = null
    private var ended: (() -> Unit)? = null
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { closeProjection(); stopSelf() }
    }

    inner class SessionBinder : Binder() {
        fun open(code: Int, data: Intent, onEnded: () -> Unit): MediaProjection {
            check(projection == null) { "Capture grant already consumed" }
            val token = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
            token.registerCallback(callback, Handler(Looper.getMainLooper()))
            ended = onEnded
            projection = token
            return token
        }
        fun clearListener() { ended = null }
    }

    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL,
            getString(R.string.audio_capture_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(3401, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle(getString(R.string.audio_capture_title))
            .setContentText(getString(R.string.audio_capture_description)).setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.audio_capture_stop), stop)
            .setOngoing(true).build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { closeProjection(); stopSelf() }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent): IBinder = SessionBinder()
    override fun onDestroy() {
        closeProjection()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun closeProjection() {
        val token = projection ?: return
        projection = null
        val notify = ended
        ended = null
        // Stop PCM readers before invalidating the token.
        notify?.invoke()
        token.unregisterCallback(callback)
        token.stop()
    }
    private companion object {
        const val CHANNEL = "playback_audio"
        const val STOP = "com.milkdrop.visualizer.STOP_PLAYBACK_CAPTURE"
    }
}
