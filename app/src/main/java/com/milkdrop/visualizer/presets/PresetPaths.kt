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

    /**
     * Blocking recursive filesystem walk — call from a background thread, not the GL or UI thread.
     *
     * Deliberately avoids a per-file stat (`isFile`/`isDirectory`): shared storage goes through
     * FUSE, where each stat is a slow round trip, and asking for all ~15k presets took ~8 s on
     * the Galaxy F15. Names ending in `.milk` are taken as presets from the directory listing
     * alone; only other names are probed, by trying to list them as directories.
     */
    fun scanPresets(): List<String> {
        val presets = ArrayList<String>()
        collectPresets(presetsDir, presets)
        return presets
    }

    private fun collectPresets(dir: File, into: MutableList<String>) {
        val names = dir.list() ?: return
        for (name in names) {
            val child = File(dir, name)
            if (name.endsWith(PRESET_EXTENSION, ignoreCase = true)) {
                into += child.absolutePath
            } else {
                collectPresets(child, into)
            }
        }
    }

    private const val PRESET_EXTENSION = ".milk"
}
