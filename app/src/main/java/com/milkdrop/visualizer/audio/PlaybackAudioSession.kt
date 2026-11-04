package com.milkdrop.visualizer.audio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjection
import android.os.IBinder
import androidx.core.content.ContextCompat

/** Activity-owned binding; no static Activity references or persisted consent tokens. */
class PlaybackAudioSession(private val context: Context) {
    private var connection: ServiceConnection? = null
    private var binder: PlaybackProjectionService.SessionBinder? = null
    var active = false
        private set

    fun open(code: Int, data: Intent, ready: (MediaProjection) -> Unit, ended: () -> Unit) {
        close()
        val service = Intent(context, PlaybackProjectionService::class.java)
        val link = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, value: IBinder) {
                if (connection !== this) return
                val session = value as PlaybackProjectionService.SessionBinder
                binder = session
                try {
                    val projection = session.open(code, data) {
                        active = false
                        ended()
                        close()
                    }
                    active = true
                    ready(projection)
                } catch (_: RuntimeException) { close(); ended() }
            }
            override fun onServiceDisconnected(name: ComponentName) { active = false; ended(); close() }
            override fun onNullBinding(name: ComponentName) { close(); ended() }
            override fun onBindingDied(name: ComponentName) { close(); ended() }
        }
        connection = link
        try {
            ContextCompat.startForegroundService(context, service)
            check(context.bindService(service, link, Context.BIND_AUTO_CREATE)) { "Unable to bind capture service" }
        } catch (_: RuntimeException) { close(); ended() }
    }

    fun close() {
        binder?.clearListener(); binder = null
        val link = connection
        connection = null
        active = false
        if (link != null) {
            try { context.unbindService(link) } catch (_: IllegalArgumentException) { }
        }
        context.stopService(Intent(context, PlaybackProjectionService::class.java))
    }
}
