package com.milkdrop.visualizer.settings

/** Slider limits and defaults shared by persistence and rendering. */
object AudioTuning {
    const val SENSITIVITY_DEFAULT = 1.0f
    const val SENSITIVITY_MAX = 25.0f
    const val FFT_SCALING_DEFAULT = 0.45f
    const val FFT_SCALING_MAX = 2.0f
    const val FFT_NOISE_FLOOR_DEFAULT = 0.10f
    const val FFT_NOISE_FLOOR_MAX = 1.0f

    fun bounded(value: Float, maximum: Float, default: Float): Float =
        if (value.isFinite()) value.coerceIn(0.0f, maximum) else default
}
