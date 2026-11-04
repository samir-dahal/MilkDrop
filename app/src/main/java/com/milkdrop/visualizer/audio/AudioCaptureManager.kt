package com.milkdrop.visualizer.audio

import android.content.Context
import android.util.Log
import android.os.Build
import android.media.projection.MediaProjection

/** Owns the active local audio source and swaps it when the user changes input. */
class AudioCaptureManager(
    context: Context,
    private val pcmSink: PcmSink,
) {
    private val appContext = context.applicationContext
    /** All capture sources run in process; Automatic owns its own monitoring worker. */
    @Volatile
    private var localSource: AudioSource? = null
    @Volatile private var playbackProjection: MediaProjection? = null

    fun setPlaybackProjection(projection: MediaProjection?) {
        stopAll()
        playbackProjection = projection
    }

    private fun internalSource(sink: PcmSink): AudioSource = object : AudioSource {
        private var source: AudioSource? = null
        override val isRunning get() = source?.isRunning == true
        override fun start() {
            val token = playbackProjection
            if (Build.VERSION.SDK_INT >= 29 && token != null) {
                val playback = PlaybackAudioSource(token, sink)
                try {
                    playback.start()
                    source = playback
                    return
                } catch (e: RuntimeException) {
                    playback.stop()
                    Log.w(TAG, "Playback PCM unavailable; using legacy output mix", e)
                }
            }
            val legacy = InternalAudioSource(sink)
            legacy.start()
            source = legacy
        }
        override fun stop() { source?.stop(); source = null }
    }

    var onAutomaticSourceChanged: (() -> Unit)? = null

    val automaticInput: AudioInput?
        get() = (localSource as? AutomaticAudioSource)?.activeInput

    fun startAutomatic(): Boolean = try {
        startLocal(AutomaticAudioSource(pcmSink, { onAutomaticSourceChanged?.invoke() },
            { MicAudioSource(appContext, it) }, { internalSource(it) }))
        true
    } catch (e: RuntimeException) {
        Log.w(TAG, "Automatic audio unavailable", e)
        false
    }

    fun startMic(): Boolean =
        try {
            startLocal(MicAudioSource(appContext, pcmSink))
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Microphone capture unavailable", e)
            false
        }

    /** Returns false if this phone won't give access to the output mix. */
    fun startInternal(): Boolean =
        try {
            startLocal(internalSource(pcmSink))
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Output mix capture unavailable", e)
            false
        }

    fun stopAll() {
        stopLocal()
    }

    private fun startLocal(source: AudioSource) {
        stopAll()
        source.start()
        localSource = source
    }

    private fun stopLocal() {
        localSource?.stop()
        localSource = null
    }

    private companion object {
        const val TAG = "AudioCaptureManager"
    }
}
