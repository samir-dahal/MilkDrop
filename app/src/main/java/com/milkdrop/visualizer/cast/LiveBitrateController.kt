package com.milkdrop.visualizer.cast

/** Reduce network load without changing the user's render resolution or frame-rate setting. */
internal class LiveBitrateController(initial: Int, minimum: Int) {
    private val ceiling = initial.coerceAtLeast(1)
    private val floor = minimum.coerceIn(1, ceiling)
    private var rate = ceiling
    private var nextChange = 0L
    private var clearSince = -1L
    private var congestionPending = false

    /** Called on the encoder thread; returns a new bitrate only when it changes. */
    fun update(nowNs: Long, congested: Boolean): Int? {
        congestionPending = congestionPending || congested
        if (congested) clearSince = -1L
        if (nowNs < nextChange) return null
        val previous = rate
        if (congestionPending) {
            rate = (rate.toLong() * 3 / 4).toInt().coerceAtLeast(floor)
            congestionPending = false
            clearSince = -1L
            nextChange = nowNs + 1_000_000_000L
        } else {
            if (clearSince < 0) clearSince = nowNs
            if (nowNs - clearSince >= 5_000_000_000L) {
                rate = (rate.toLong() * 110 / 100).toInt().coerceAtMost(ceiling)
                clearSince = nowNs
            }
        }
        return rate.takeIf { it != previous }
    }
}
