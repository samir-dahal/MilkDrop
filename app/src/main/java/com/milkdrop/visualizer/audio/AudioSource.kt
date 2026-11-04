package com.milkdrop.visualizer.audio

/** PCM16 data and its real sample rate; snapshots may overlap, streaming blocks do not. */
typealias PcmSink = (samples: ShortArray, frameCount: Int, channels: Int, sampleRateHz: Int, snapshot: Boolean) -> Unit

interface AudioSource {
    val isRunning: Boolean
    fun start()
    fun stop()
}
