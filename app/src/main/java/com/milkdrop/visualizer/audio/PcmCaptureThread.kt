package com.milkdrop.visualizer.audio

import android.annotation.SuppressLint
import android.media.AudioRecord
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads PCM16 frames from an already-built, unstarted [AudioRecord] on a dedicated thread.
 *
 * Small reads deliver about 5 ms of continuous audio per block. The engine's rolling
 * analysis buffers retain the latest window without dropping blocks here. Android's
 * capture buffers and the render cadence still contribute to end-to-end latency.
 */
internal class PcmCaptureThread(
    private val audioRecord: AudioRecord,
    private val channels: Int,
    private val sink: PcmSink,
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    val isRunning: Boolean
        get() = running.get()

    @SuppressLint("MissingPermission")
    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            audioRecord.startRecording()
            check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Audio recorder did not start"
            }
        } catch (e: RuntimeException) {
            running.set(false)
            audioRecord.release()
            throw e
        }
        val readThread = Thread({
            val buffer = ShortArray(audioRecord.sampleRate / READS_PER_SECOND * channels)
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                while (running.get()) {
                    val read = audioRecord.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (read < 0) {
                        if (running.get()) Log.w(TAG, "PCM capture failed: $read")
                        break
                    }
                    if (read > 0 && running.get()) {
                        // Feed every block in order. The engine keeps the newest
                        // analysis window itself; skipping capture blocks creates
                        // discontinuities and corrupts the waveform and FFT.
                        val frameCount = read / channels
                        if (running.get()) sink(buffer, frameCount, channels, audioRecord.sampleRate, false)
                    }
                }
            } catch (e: RuntimeException) {
                if (running.get()) Log.w(TAG, "PCM capture stopped", e)
            } finally {
                running.set(false)
                audioRecord.release()
            }
        }, "milkdrop-pcm-capture")
        thread = readThread
        readThread.start()
    }

    fun stop() {
        val wasRunning = running.getAndSet(false)
        // Stop first to unblock an in-flight read, then wait for the worker to release the recorder.
        if (wasRunning) {
            try {
                audioRecord.stop()
            } catch (_: IllegalStateException) {
                // The worker may already have exited and released the recorder.
            }
        }
        thread?.join(500)
        thread = null
    }

    private companion object {
        const val READS_PER_SECOND = 200
        const val TAG = "PcmCaptureThread"
    }
}
