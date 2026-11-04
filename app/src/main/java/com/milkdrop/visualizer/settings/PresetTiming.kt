package com.milkdrop.visualizer.settings

internal object PresetTiming {
    val durations = intArrayOf(5, 10, 15, 20, 25, 30, 45, 60)
    val additionalRandom = intArrayOf(0, 5, 10, 15, 20, 25, 30)
    // -1 means choose one of the fixed durations afresh at each native preset transition.
    val transitions = floatArrayOf(-1f, 0f, 0.5f, 1f, 1.5f, 1.7f, 2f, 2.5f, 3f, 5f)
    val legacyDurations = intArrayOf(10, 15, 30, 60)
}
