package com.milkdrop.visualizer.audio

/** Debounces output-mix activity before Automatic chooses a capture source. */
internal class InternalAudioActivity {
    private var audibleSince: Long? = null
    private var lastAudible: Long? = null
    private var active = false

    @Synchronized
    fun observe(samples: ShortArray, frames: Int, channels: Int, now: Long) {
        val count = if (channels > 0) minOf(frames.coerceAtLeast(0).toLong() * channels, samples.size.toLong()).toInt() else 0
        var sum = 0.0
        var squareSum = 0.0
        var low = Short.MAX_VALUE.toInt()
        var high = Short.MIN_VALUE.toInt()
        for (i in 0 until count) {
            val sample = samples[i].toInt()
            sum += sample
            squareSum += sample.toDouble() * sample
            low = minOf(low, sample)
            high = maxOf(high, sample)
        }
        val mean = if (count > 0) sum / count else 0.0
        val variance = if (count > 0) (squareSum / count - mean * mean).coerceAtLeast(0.0) else 0.0
        // Visualizer's 8-bit waveform is shifted into PCM16. Ignore DC offsets and
        // changes smaller than its quantization, without requiring a volume setting.
        val audible = count > 1 && high - low >= 2 * QUANTUM && variance >= QUANTUM * QUANTUM / 4.0
        if (audible) {
            lastAudible = now
            val start = audibleSince ?: now.also { audibleSince = it }
            if (now - start >= ATTACK_NANOS) active = true
        } else {
            audibleSince = null
        }
    }

    @Synchronized
    fun prefersInternal(now: Long): Boolean {
        val last = lastAudible
        if (last == null || now - last >= SILENCE_NANOS) {
            active = false
            audibleSince = null
        }
        return active
    }

    @Synchronized
    fun reset() {
        audibleSince = null
        lastAudible = null
        active = false
    }

    private companion object {
        const val QUANTUM = 256
        const val ATTACK_NANOS = 100_000_000L
        const val SILENCE_NANOS = 1_200_000_000L
    }
}
