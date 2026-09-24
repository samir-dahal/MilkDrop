package com.milkdrop.visualizer.audio

/** Interleaved PCM16 frames captured off the audio thread: (samples, frameCount, channelCount). */
typealias PcmSink = (samples: ShortArray, frameCount: Int, channels: Int) -> Unit

interface AudioSource {
    fun start()
    fun stop()
}
