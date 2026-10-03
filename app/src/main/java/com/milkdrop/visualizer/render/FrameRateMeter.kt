package com.milkdrop.visualizer.render

import kotlin.math.roundToInt

/**
 * Measures the frame rate actually rendered, averaged over about a second.
 *
 * projectM passes this to presets as `fps`; many scale their motion by it, so a wrong value makes
 * them run too fast or too slow. A gap between frames (pause, a long preset load) restarts the
 * window instead of dragging the average down.
 */
class FrameRateMeter {

    private var windowStartNanos = 0L
    private var lastFrameNanos = 0L
    private var framesInWindow = 0

    /** Call once per rendered frame; returns the new average when a window completes, else null. */
    fun onFrame(nowNanos: Long): Int? {
        if (lastFrameNanos == 0L || nowNanos - lastFrameNanos > MAX_FRAME_GAP_NANOS) {
            restart(nowNanos)
            return null
        }
        lastFrameNanos = nowNanos
        framesInWindow++
        val elapsed = nowNanos - windowStartNanos
        if (elapsed < WINDOW_NANOS) return null

        val fps = (framesInWindow * 1_000_000_000.0 / elapsed).roundToInt()
        windowStartNanos = nowNanos
        framesInWindow = 0
        return fps
    }

    fun reset() {
        lastFrameNanos = 0L
    }

    private fun restart(nowNanos: Long) {
        windowStartNanos = nowNanos
        lastFrameNanos = nowNanos
        framesInWindow = 0
    }

    private companion object {
        const val WINDOW_NANOS = 1_000_000_000L
        const val MAX_FRAME_GAP_NANOS = 500_000_000L
    }
}
