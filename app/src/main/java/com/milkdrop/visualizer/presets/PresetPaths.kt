package com.milkdrop.visualizer.presets

import android.os.Environment
import java.io.File

/**
 * Fixed, well-known external-storage locations for presets and the shared texture pack.
 * Not user-configurable via a folder picker — this is a personal, sideloaded app, so a
 * documented fixed path (see README) is simpler than building Storage Access Framework UI.
 */
object PresetPaths {

    private val rootDir: File
        get() = File(Environment.getExternalStorageDirectory(), "MilkDropApp")

    val presetsDir: File
        get() = File(rootDir, "presets")

    val texturesDir: File
        get() = File(rootDir, "textures")

    fun ensureDirsExist() {
        presetsDir.mkdirs()
        texturesDir.mkdirs()
    }
}
