package com.milkdrop.visualizer.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Captures whatever the phone is playing through USAGE_MEDIA/USAGE_GAME/USAGE_UNKNOWN streams.
 * Some apps (Spotify, DRM-protected video) opt out of capture via AudioAttributes — that's an
 * expected platform limitation, not a bug; [MicAudioSource] is the user-facing fallback.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class InternalPlaybackAudioSource(
    private val mediaProjection: MediaProjection,
    private val sink: PcmSink,
) : AudioSource {

    private var captureThread: PcmCaptureThread? = null

    @SuppressLint("MissingPermission")
    override fun start() {
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(ENCODING)
            .setSampleRate(SAMPLE_RATE_HZ)
            .setChannelMask(CHANNEL_MASK)
            .build()

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_MASK, ENCODING)
        val audioRecord = AudioRecord.Builder()
            .setAudioFormat(format)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setBufferSizeInBytes(minBufferSize * 2)
            .build()

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
        const val CHANNEL_MASK = AudioFormat.CHANNEL_IN_STEREO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val CHANNELS = 2
    }
}
