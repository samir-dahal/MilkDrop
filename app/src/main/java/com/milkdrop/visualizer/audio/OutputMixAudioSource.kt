package com.milkdrop.visualizer.audio

import android.media.audiofx.Visualizer

/**
 * Phone audio without the screen-capture prompt, via the Visualizer effect on the output mix.
 *
 * Coarser than playback capture: 8-bit snapshots of the latest ~1024 samples, ~20 times a second,
 * rather than a continuous stream. Normalized scaling keeps it independent of the volume setting.
 * Apps that block capture (DRM video, some streaming apps) come through as silence.
 */
class OutputMixAudioSource(private val sink: PcmSink) : AudioSource {

    private var visualizer: Visualizer? = null

    /** Throws if the platform refuses (no output-mix access on this device, or it's in use). */
    override fun start() {
        val captureSize = Visualizer.getCaptureSizeRange()[1]
        val samples = ShortArray(captureSize)
        val effect = Visualizer(OUTPUT_MIX_SESSION)
        try {
            effect.captureSize = captureSize
            effect.scalingMode = Visualizer.SCALING_MODE_NORMALIZED
            effect.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(visualizer: Visualizer, waveform: ByteArray, samplingRate: Int) {
                    val count = minOf(waveform.size, samples.size)
                    for (i in 0 until count) {
                        // Unsigned 8-bit, 128 = silence, to signed 16-bit.
                        samples[i] = (((waveform[i].toInt() and 0xFF) - 128) shl 8).toShort()
                    }
                    sink(samples, count, MONO)
                }

                override fun onFftDataCapture(visualizer: Visualizer, fft: ByteArray, samplingRate: Int) = Unit
            }, Visualizer.getMaxCaptureRate(), true, false)
            effect.enabled = true
        } catch (e: RuntimeException) {
            effect.release()
            throw e
        }
        visualizer = effect
    }

    override fun stop() {
        visualizer?.run {
            enabled = false
            release()
        }
        visualizer = null
    }

    private companion object {
        const val OUTPUT_MIX_SESSION = 0
        const val MONO = 1
    }
}
