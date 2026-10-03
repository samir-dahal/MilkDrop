package com.milkdrop.visualizer.presets

import java.io.File
import java.util.concurrent.Executor

/**
 * Keeps favourite and hidden presets as plain text files next to the presets (MilkDropApp/), so
 * they survive reinstalling the app and can be copied to another phone or edited by hand.
 *
 * One preset per line, relative to [presetsDir]; blank lines and lines starting with '#' are
 * ignored. In memory, paths are absolute, matching the preset scan.
 */
class PresetRatingsStore(
    private val presetsDir: File,
    private val favouritesFile: File,
    private val hiddenFile: File,
    /** Runs writes off the UI thread; shared storage can be slow. */
    private val writeExecutor: Executor,
) {
    fun loadFavourites(): Set<String> = read(favouritesFile)

    fun loadHidden(): Set<String> = read(hiddenFile)

    fun save(favourites: Set<String>, hidden: Set<String>) {
        writeExecutor.execute {
            write(favouritesFile, FAVOURITES_HEADER, favourites)
            write(hiddenFile, HIDDEN_HEADER, hidden)
        }
    }

    private fun read(file: File): Set<String> {
        if (!file.isFile) return emptySet()
        return file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { File(presetsDir, it).path }
            .toSet()
    }

    /** Writes a temporary file first, then renames it, so a crash can't leave a half-written list. */
    private fun write(file: File, header: String, paths: Set<String>) {
        val prefix = presetsDir.path + File.separator
        // Always '/' in the file, whatever the platform, so it stays portable.
        val lines = paths.map { it.removePrefix(prefix).replace(File.separatorChar, '/') }.sorted()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText((listOf(header) + lines).joinToString("\n", postfix = "\n"))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    private companion object {
        const val FAVOURITES_HEADER = "# Favourite presets, one per line, relative to MilkDropApp/presets"
        const val HIDDEN_HEADER = "# Hidden presets (skipped by Next, Shuffle and Auto-advance), relative to MilkDropApp/presets"
    }
}
