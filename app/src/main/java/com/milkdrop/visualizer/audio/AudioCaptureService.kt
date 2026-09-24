package com.milkdrop.visualizer.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Foreground service (type mediaProjection) required by Android to keep capturing internal
 * playback audio while the app is briefly backgrounded. Bind to it to receive PCM frames.
 */
class AudioCaptureService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): AudioCaptureService = this@AudioCaptureService
    }

    private val binder = LocalBinder()

    /** Set by the binder; receives captured PCM frames. */
    var pcmSink: PcmSink? = null

    private var mediaProjection: MediaProjection? = null
    private var audioSource: InternalPlaybackAudioSource? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, RESULT_CANCELED) ?: RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode == RESULT_OK && data != null) {
            startCapture(resultCode, data)
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val projection = projectionManager.getMediaProjection(resultCode, data)
        mediaProjection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopCapture()
            }
        }, Handler(Looper.getMainLooper()))

        val source = InternalPlaybackAudioSource(projection) { samples, frameCount, channels ->
            pcmSink?.invoke(samples, frameCount, channels)
        }
        audioSource = source
        source.start()
    }

    fun stopCapture() {
        audioSource?.stop()
        audioSource = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Audio capture", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MilkDrop")
            .setContentText("Capturing audio for visuals")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val RESULT_OK = android.app.Activity.RESULT_OK
        private const val RESULT_CANCELED = android.app.Activity.RESULT_CANCELED
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "milkdrop_capture"
    }
}
