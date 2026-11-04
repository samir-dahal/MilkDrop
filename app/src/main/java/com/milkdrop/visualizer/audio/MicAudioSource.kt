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

    override val isRunning: Boolean
        get() = captureThread?.isRunning == true

    @SuppressLint("MissingPermission")
    override fun start() {
        if (captureThread != null) return
        var failure: RuntimeException? = null
        // Request real interleaved stereo first. Some microphones expose only
        // mono; retain that fallback rather than inventing a second channel.
        for (channelConfig in intArrayOf(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO)) {
            val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, channelConfig, ENCODING)
            if (minimum <= 0) continue
            val record = try {
                AudioRecord(micInput(), SAMPLE_RATE_HZ, channelConfig, ENCODING, minimum)
            } catch (e: RuntimeException) {
                failure = e
                continue
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                continue
            }
            val worker = PcmCaptureThread(record, record.channelCount, sink)
            try {
                worker.start() // Releases the recorder itself if starting fails.
                captureThread = worker
                return
            } catch (e: RuntimeException) {
                failure = e
            }
        }
        throw failure ?: IllegalStateException("No supported microphone capture format")
    }

    /**
     * The plain MIC source is tuned for voice: auto-gain and noise suppression flatten music and
     * smear its beats. UNPROCESSED skips that, where the phone supports it; voice recognition
     * is the fallback recommended for capture without automatic gain or noise suppression.
     */
    private fun micInput(): Int {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val unprocessed = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
        return if (unprocessed == "true") {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
    }

    override fun stop() {
        captureThread?.stop()
        captureThread = null
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 44100
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
