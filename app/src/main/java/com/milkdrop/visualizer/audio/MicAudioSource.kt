package com.milkdrop.visualizer.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/** Fallback source for apps that block playback capture, or for ambient/room audio. */
class MicAudioSource(private val sink: PcmSink) : AudioSource {

    private var captureThread: PcmCaptureThread? = null

    @SuppressLint("MissingPermission")
    override fun start() {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_CONFIG, ENCODING)
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE_HZ,
            CHANNEL_CONFIG,
            ENCODING,
            minBufferSize * 2,
        )
        val thread = PcmCaptureThread(audioRecord, CHANNELS, minBufferSize, sink)
        captureThread = thread
        thread.start()
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
