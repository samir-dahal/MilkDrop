package com.milkdrop.visualizer.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import androidx.annotation.RequiresApi

/** Genuine playback PCM. Never synthesizes a second channel from mono. */
@RequiresApi(29)
internal class PlaybackAudioSource(
    private val projection: MediaProjection,
    private val sink: PcmSink,
) : AudioSource {
    private var worker: PcmCaptureThread? = null
    override val isRunning get() = worker?.isRunning == true

    @SuppressLint("MissingPermission")
    override fun start() {
        if (worker != null) return
        val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        var failure: RuntimeException? = null
        for (mask in intArrayOf(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO)) {
            for (rate in intArrayOf(44100, 48000)) {
                val minimum = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
                if (minimum <= 0) continue
                val record = try {
                    AudioRecord.Builder()
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                            .setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(minimum)
                        .setAudioPlaybackCaptureConfig(capture).build()
                } catch (e: RuntimeException) { failure = e; continue }
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    continue
                }
                val reader = PcmCaptureThread(record, record.channelCount, sink)
                try {
                    reader.start()
                    worker = reader
                    return
                } catch (e: RuntimeException) { failure = e } // Reader releases on failure.
            }
        }
        throw failure ?: IllegalStateException("Playback PCM capture unavailable")
    }

    override fun stop() { worker?.stop(); worker = null }
}
