package com.milkdrop.visualizer.presets

import android.os.Environment
import java.io.File

/**
 * Fixed, well-known external-storage locations for presets and the shared texture pack.
 * Not user-configurable via a folder picker — this is a personal, sideloaded app, so a
 * documented fixed path (see README) is simpler than building Storage Access Framework UI.
 */
object PresetPaths {

    private val library: PresetLibrary
        get() = PresetLibrary(File(Environment.getExternalStorageDirectory(), "MilkDropApp"))

    val presetsDir: File
        get() = library.presetsDir

    val texturesDir: File
        get() = library.texturesDir

    val favouritesFile: File
        get() = library.favouritesFile

    val hiddenFile: File
        get() = library.hiddenFile

    fun ensureDirsExist() {
        library.ensureDirsExist()
    }

    /**
     * Blocking recursive filesystem walk — call from a background thread, not the GL or UI thread.
     *
     * Deliberately avoids a per-file stat (`isFile`/`isDirectory`): shared storage goes through
     * FUSE, where each stat is a slow round trip, and asking for all ~15k presets took ~8 s on
     * the Galaxy F15. Names ending in `.milk` are taken as presets from the directory listing
     * alone; only other names are probed, by trying to list them as directories.
     */
    fun scanPresets(onFirstPreset: (String) -> Unit = {}): List<String> =
        library.scanPresets(onFirstPreset)
}
