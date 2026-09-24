package com.milkdrop.visualizer.render

import android.util.Log
import java.io.File

/**
 * The GPU driver's own worker threads inside this process, and keeping them on the fast CPU cores.
 *
 * On Mali, `mali-cmar-backe` builds the GPU command stream for every draw call. Shape-heavy presets
 * issue ~900 draws a frame, and on the Galaxy F15 (Dimensity 6100+) that thread was 57–63% busy,
 * mostly on the little cores idling at ~650 MHz. The governor reads that as light load, so the GPU
 * sat starved: the witchcraft preset ran at ~8 fps at any resolution (0.5x through 1.0x), and only
 * the brief touch boost after a tap (raising all clocks) lifted it to ~35 fps. The Performance Hint
 * API didn't help on this device (MediaTek's implementation left every thread's uclamp at 0), so
 * the threads are pinned to the fast cores directly. Other GPU vendors name their threads
 * differently and simply aren't matched.
 */
object GpuDriverThreads {

    private const val TAG = "GpuDriverThreads"
    private const val DRIVER_THREAD_PREFIX = "mali-"

    fun threadIds(): IntArray =
        File("/proc/self/task").listFiles().orEmpty()
            .filter { task -> readTrimmed(File(task, "comm"))?.startsWith(DRIVER_THREAD_PREFIX) == true }
            .mapNotNull { it.name.toIntOrNull() }
            .toIntArray()

    /** Call from the GL thread once the EGL context exists, since that's when the driver spawns them. */
    fun pinToFastCores() {
        val fastCores = fastCores()
        val tids = threadIds()
        if (fastCores.isEmpty() || tids.isEmpty()) return
        val pinned = ProjectMBridge.nativeSetThreadAffinity(tids, fastCores)
        Log.d(TAG, "Pinned $pinned/${tids.size} GPU driver threads to cores ${fastCores.joinToString()}")
    }

    /** The highest-capacity cores, or empty if all cores are alike (nothing to gain from pinning). */
    private fun fastCores(): IntArray {
        val capacities = File("/sys/devices/system/cpu").listFiles().orEmpty()
            .mapNotNull { dir ->
                val core = dir.name.removePrefix("cpu").toIntOrNull() ?: return@mapNotNull null
                val capacity = readTrimmed(File(dir, "cpu_capacity"))?.toLongOrNull()
                    ?: readTrimmed(File(dir, "cpufreq/cpuinfo_max_freq"))?.toLongOrNull()
                    ?: return@mapNotNull null
                core to capacity
            }
        val maxCapacity = capacities.maxOfOrNull { it.second } ?: return IntArray(0)
        val fast = capacities.filter { it.second == maxCapacity }.map { it.first }
        return if (fast.size == capacities.size) IntArray(0) else fast.sorted().toIntArray()
    }

    private fun readTrimmed(file: File): String? = runCatching { file.readText().trim() }.getOrNull()
}
