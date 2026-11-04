package com.milkdrop.visualizer.audio

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps Internal monitoring active and opens the microphone only when it is needed. */
internal class AutomaticAudioSource(
    private val sink: PcmSink,
    private val onSourceChanged: () -> Unit,
    private val createMic: (PcmSink) -> AudioSource,
    private val createInternal: (PcmSink) -> AudioSource,
    private val now: () -> Long = System::nanoTime,
) : AudioSource {
    constructor(context: Context, sink: PcmSink, onSourceChanged: () -> Unit) : this(
        sink, onSourceChanged,
        { MicAudioSource(context.applicationContext, it) },
        { InternalAudioSource(it) },
    )

    private val running = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "milkdrop-auto-audio")
    }
    private val outputLock = Any()
    private val activity = InternalAudioActivity()
    private var mic: AudioSource? = null
    private var internalSource: AudioSource? = null
    private var lastMicAttempt = -RETRY_NANOS
    private var lastInternalAttempt = -RETRY_NANOS
    private var statusReported = false
    private var reportedInput: AudioInput? = null

    @Volatile
    var activeInput: AudioInput? = null
        private set

    override val isRunning: Boolean
        get() = running.get()

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        worker.scheduleWithFixedDelay({
            if (running.get()) {
                try {
                    update()
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Unable to update automatic audio", e)
                }
            }
        }, 0, POLL_MILLIS, TimeUnit.MILLISECONDS)
    }

    private fun forward(input: AudioInput, samples: ShortArray, frames: Int, channels: Int, rate: Int, snapshot: Boolean) {
        synchronized(outputLock) {
            if (running.get() && activeInput == input) sink(samples, frames, channels, rate, snapshot)
        }
    }

    private fun update() {
        val time = now()
        if (internalSource?.isRunning != true && time - lastInternalAttempt >= RETRY_NANOS) {
            lastInternalAttempt = time
            stopSource(internalSource)
            internalSource = null
            activity.reset()
            val source = createInternal { samples, frames, channels, rate, snapshot ->
                if (running.get()) activity.observe(samples, frames, channels, now())
                forward(AudioInput.INTERNAL, samples, frames, channels, rate, snapshot)
            }
            if (startSource(source)) internalSource = source
        }

        val preferInternal = internalSource?.isRunning == true && activity.prefersInternal(time)
        if (preferInternal) {
            select(AudioInput.INTERNAL)
            stopSource(mic)
            mic = null
            // A fresh fallback should not wait for an earlier microphone retry timer.
            lastMicAttempt = -RETRY_NANOS
        } else {
            if (mic?.isRunning != true && time - lastMicAttempt >= RETRY_NANOS) {
                lastMicAttempt = time
                stopSource(mic)
                mic = null
                val source = createMic { samples, frames, channels, rate, snapshot ->
                    forward(AudioInput.MIC, samples, frames, channels, rate, snapshot)
                }
                if (startSource(source)) mic = source
            }
            select(if (mic?.isRunning == true) AudioInput.MIC else null)
        }
        if (!statusReported || reportedInput != activeInput) {
            reportedInput = activeInput
            statusReported = true
            if (running.get()) onSourceChanged()
        }
    }

    private fun select(input: AudioInput?) {
        synchronized(outputLock) { activeInput = if (running.get()) input else null }
    }

    private fun startSource(source: AudioSource): Boolean = try {
        source.start()
        if (!source.isRunning) {
            stopSource(source)
            false
        } else true
    } catch (e: RuntimeException) {
        Log.w(TAG, "Automatic capture source unavailable", e)
        stopSource(source)
        false
    }

    private fun stopSource(source: AudioSource?) {
        try {
            source?.stop()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to stop automatic capture source", e)
        }
    }

    override fun stop() {
        if (!running.getAndSet(false)) return
        select(null)
        val cleanup = worker.submit {
            stopSource(mic)
            stopSource(internalSource)
            mic = null
            internalSource = null
        }
        worker.shutdown() // Cancels future periodic checks; lets cleanup finish.
        try {
            cleanup.get(2, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.w(TAG, "Audio cleanup is still finishing", e)
        }
    }

    private companion object {
        const val POLL_MILLIS = 50L
        const val RETRY_NANOS = 2_000_000_000L
        const val TAG = "AutomaticAudioSource"
    }
}
