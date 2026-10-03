package com.milkdrop.visualizer.audio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log

/** Owns whichever [AudioSource] is currently active and swaps between them on user toggle. */
class AudioCaptureManager(
    private val context: Context,
    private val pcmSink: PcmSink,
) {
    /** The in-process source (mic or output mix); phone-audio capture runs in [AudioCaptureService]. */
    private var localSource: AudioSource? = null
    private var serviceConnection: ServiceConnection? = null
    private var boundService: AudioCaptureService? = null

    fun requestInternalCapture(resultCode: Int, data: Intent) {
        stopLocal()
        val intent = Intent(context, AudioCaptureService::class.java).apply {
            putExtra(AudioCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(AudioCaptureService.EXTRA_RESULT_DATA, data)
        }
        context.startForegroundService(intent)
        bindService()
    }

    fun startMic() {
        startLocal(MicAudioSource(context, pcmSink))
    }

    /** Returns false if this phone won't give access to the output mix. */
    fun startOutputMix(): Boolean =
        try {
            startLocal(OutputMixAudioSource(pcmSink))
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Output mix capture unavailable", e)
            false
        }

    fun stopAll() {
        stopLocal()
        stopInternal()
    }

    private fun startLocal(source: AudioSource) {
        stopAll()
        source.start()
        localSource = source
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

    private fun stopLocal() {
        localSource?.stop()
        localSource = null
    }

    private fun stopInternal() {
        boundService?.stopCapture()
        serviceConnection?.let { context.unbindService(it) }
        serviceConnection = null
        boundService = null
        context.stopService(Intent(context, AudioCaptureService::class.java))
    }

    private companion object {
        const val TAG = "AudioCaptureManager"
    }
}
