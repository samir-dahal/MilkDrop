package com.milkdrop.visualizer.settings

internal object FrameRates {
    val options = intArrayOf(24, 30, 45, 50, 60, 120, 0)
    val legacy = intArrayOf(30, 45, 60, 0)
    fun indexFor(value: Int) = options.indexOf(value).takeIf { it >= 0 } ?: options.indexOf(30)
}
