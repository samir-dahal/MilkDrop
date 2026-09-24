package com.milkdrop.visualizer.audio

import android.annotation.SuppressLint
import android.media.AudioRecord
import java.util.concurrent.atomic.AtomicBoolean

/** Reads PCM16 frames from an already-built, unstarted [AudioRecord] on a dedicated thread. */
internal class PcmCaptureThread(
    private val audioRecord: AudioRecord,
    private val channels: Int,
    private val bufferSizeInShorts: Int,
    private val sink: PcmSink,
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (!running.compareAndSet(false, true)) return
        audioRecord.startRecording()
        val readThread = Thread({
            val buffer = ShortArray(bufferSizeInShorts)
            while (running.get()) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val frameCount = read / channels
                    sink(buffer, frameCount, channels)
                }
            }
        }, "milkdrop-pcm-capture")
        thread = readThread
        readThread.start()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.join(500)
        thread = null
        audioRecord.stop()
        audioRecord.release()
    }
}
