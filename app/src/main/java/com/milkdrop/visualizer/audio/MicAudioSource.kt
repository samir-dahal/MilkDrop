package com.milkdrop.visualizer.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder

/** Fallback source for apps that block playback capture, or for ambient/room audio. */
class MicAudioSource(
    private val context: Context,
    private val sink: PcmSink,
) : AudioSource {

    private var captureThread: PcmCaptureThread? = null

    @SuppressLint("MissingPermission")
    override fun start() {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_CONFIG, ENCODING)
        val audioRecord = AudioRecord(
            micInput(),
            SAMPLE_RATE_HZ,
            CHANNEL_CONFIG,
            ENCODING,
            minBufferSize * 2,
        )
        val thread = PcmCaptureThread(audioRecord, CHANNELS, sink)
        captureThread = thread
        thread.start()
    }

    /**
     * The plain MIC source is tuned for voice: auto-gain and noise suppression flatten music and
     * smear its beats. UNPROCESSED skips that, where the phone supports it.
     */
    private fun micInput(): Int {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val unprocessed = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
        return if (unprocessed == "true") MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC
    }

    override fun stop() {
        captureThread?.stop()
        captureThread = null
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val CHANNELS = 1
    }
}
