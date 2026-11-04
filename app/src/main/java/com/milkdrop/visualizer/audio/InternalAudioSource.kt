package com.milkdrop.visualizer.audio

import android.media.audiofx.Visualizer
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Internal playback audio through Android's Visualizer effect on the output mix.
 *
 * Polls the latest 8-bit snapshot on an audio-priority thread rather than waiting for rate-limited
 * callbacks on the UI looper. Normalized scaling keeps it independent of the volume setting.
 * Apps that block capture (DRM video, some streaming apps) come through as silence.
 */
class InternalAudioSource(private val sink: PcmSink) : AudioSource {

    private var capture: Capture? = null

    override val isRunning: Boolean
        get() = capture?.running?.get() == true

    /** Throws if the platform refuses (no output-mix access on this device, or it's in use). */
    override fun start() {
        if (capture != null) return
        val captureSize = Visualizer.getCaptureSizeRange()[1]
        val effect = Visualizer(OUTPUT_MIX_SESSION)
        try {
            check(effect.setCaptureSize(captureSize) == Visualizer.SUCCESS)
            check(effect.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED) == Visualizer.SUCCESS)
            check(effect.setEnabled(true) == Visualizer.SUCCESS)
            val session = Capture(effect, captureSize, sink)
            session.thread.start()
            capture = session
        } catch (e: RuntimeException) {
            effect.release()
            throw e
        }
    }

    override fun stop() {
        val session = capture ?: return
        capture = null
        session.running.set(false)
        session.thread.interrupt()
        session.thread.join(500)
        // The worker releases its effect, even if a platform call takes longer than the join.
    }

    private class Capture(effect: Visualizer, captureSize: Int, sink: PcmSink) {
        // Visualizer reports milliHertz. Shader Hz lookups must use this device's actual rate.
        val running = AtomicBoolean(true)
        val thread = Thread({
            val waveform = ByteArray(captureSize)
            val samples = ShortArray(captureSize)
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                while (running.get()) {
                    val status = effect.getWaveForm(waveform)
                    if (status != Visualizer.SUCCESS) {
                        Log.w(TAG, "Output mix capture failed: $status")
                        break
                    }
                    for (i in waveform.indices) {
                        // Unsigned 8-bit, 128 = silence, to signed 16-bit.
                        samples[i] = (((waveform[i].toInt() and 0xFF) - 128) shl 8).toShort()
                    }
                    // Bluetooth codec/route changes can replace the mixer rate while
                    // this effect remains alive. Never retain the startup rate.
                    val sampleRateHz = effect.samplingRate / 1000
                    if (sampleRateHz > 0 && running.get()) sink(samples, captureSize, MONO, sampleRateHz, true)
                    // Poll fresh data after a stall; never queue snapshots to replay later.
                    Thread.sleep(POLL_INTERVAL_MS)
                }
            } catch (_: InterruptedException) {
                // Normal stop: wake the sleeping worker immediately.
            } catch (e: RuntimeException) {
                Log.w(TAG, "Output mix capture stopped", e)
            } finally {
                running.set(false)
                effect.release()
            }
        }, "milkdrop-output-mix")
    }

    private companion object {
        const val OUTPUT_MIX_SESSION = 0
        const val MONO = 1
        const val POLL_INTERVAL_MS = 5L
        const val TAG = "InternalAudioSource"
    }
}
