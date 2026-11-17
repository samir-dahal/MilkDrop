package com.milkdrop.visualizer.settings

internal object FrameRates {
    val options = intArrayOf(15, 24, 30, 45, 50, 60, 120, 0)
    fun effective(value: Int, casting: Boolean) = if (casting && (value == 0 || value > 60)) 60 else value
    fun available(casting: Boolean) = options.filter { !casting || it in 1..60 }
    val legacy = intArrayOf(30, 45, 60, 0)
    fun indexFor(value: Int) = options.indexOf(value).takeIf { it >= 0 } ?: options.indexOf(30)
}
