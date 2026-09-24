package com.milkdrop.visualizer.presets

import java.io.File

/**
 * Remembers the last preset scan in app-private storage, one path per line.
 *
 * Scanning shared storage takes ~5 s for ~15k presets (see [PresetPaths.scanPresets]), so a launch
 * starts from this list instead, then rescans in the background and only replaces the playlist if
 * the folder actually changed. Blocking I/O; call off the main thread.
 */
class PresetListCache(private val file: File) {

    fun read(): List<String> =
        runCatching { file.readLines().filter { it.isNotEmpty() } }.getOrDefault(emptyList())

    fun write(paths: List<String>) {
        // Write-then-rename so a crash mid-write can't leave a truncated list behind.
        val temp = File(file.parentFile, "${file.name}.tmp")
        runCatching {
            temp.writeText(paths.joinToString("\n"))
            temp.renameTo(file)
        }
    }
}
