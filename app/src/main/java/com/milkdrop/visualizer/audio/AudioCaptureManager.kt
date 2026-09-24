package com.milkdrop.visualizer.audio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder

/** Owns whichever [AudioSource] is currently active and swaps between them on user toggle. */
class AudioCaptureManager(
    private val context: Context,
    private val pcmSink: PcmSink,
) {
    enum class SourceType { INTERNAL, MIC }

    var currentSource: SourceType = SourceType.INTERNAL
        private set

    private var micSource: MicAudioSource? = null
    private var serviceConnection: ServiceConnection? = null
    private var boundService: AudioCaptureService? = null

    fun requestInternalCapture(resultCode: Int, data: Intent) {
        stopMic()
        currentSource = SourceType.INTERNAL
        val intent = Intent(context, AudioCaptureService::class.java).apply {
            putExtra(AudioCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(AudioCaptureService.EXTRA_RESULT_DATA, data)
        }
        context.startForegroundService(intent)
        bindService()
    }

    fun startMic() {
        stopInternal()
        currentSource = SourceType.MIC
        val source = MicAudioSource(pcmSink)
        micSource = source
        source.start()
    }

    fun stopAll() {
        stopMic()
        stopInternal()
    }

    private fun bindService() {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val local = (service as AudioCaptureService.LocalBinder).getService()
                local.pcmSink = pcmSink
                boundService = local
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                boundService = null
            }
        }
        serviceConnection = connection
        context.bindService(Intent(context, AudioCaptureService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    private fun stopMic() {
        micSource?.stop()
        micSource = null
    }

    private fun stopInternal() {
        boundService?.stopCapture()
        serviceConnection?.let { context.unbindService(it) }
        serviceConnection = null
        boundService = null
        context.stopService(Intent(context, AudioCaptureService::class.java))
    }
}
