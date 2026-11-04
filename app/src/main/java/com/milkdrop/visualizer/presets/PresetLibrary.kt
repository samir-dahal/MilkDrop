package com.milkdrop.visualizer.presets

import java.io.File
import java.io.IOException

/** Shared folders are independent: creating one never replaces or clears the other. */
class PresetLibrary(rootDir: File) {
    val presetsDir = File(rootDir, "presets")
    val texturesDir = File(rootDir, "textures")
    val favouritesFile = File(rootDir, "favourites.txt")
    val hiddenFile = File(rootDir, "hidden.txt")

    fun ensureDirsExist() {
        for (dir in listOf(presetsDir, texturesDir)) {
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
                throw IOException("Unable to create directory: ${dir.path}")
            }
        }
    }

    /** Announces the first preset before walking the rest of a potentially large library. */
    fun scanPresets(onFirstPreset: (String) -> Unit = {}): List<String> {
        val presets = ArrayList<String>()
        fun collect(dir: File) {
            val names = dir.list() ?: return
            for (name in names) {
                val child = File(dir, name)
                if (name.endsWith(".milk", ignoreCase = true)) {
                    if (presets.isEmpty()) onFirstPreset(child.absolutePath)
                    presets += child.absolutePath
                } else {
                    collect(child)
                }
            }
        }
        collect(presetsDir)
        return presets
    }
}
