package com.milkdrop.visualizer.ui

internal object PresetSearch {
    private val whitespace = Regex("\\s+")

    /** Literal, case-insensitive words in any order; filenames and folder matches remain valid. */
    fun filter(entries: List<PresetEntry>, query: String): List<PresetEntry> {
        val terms = query.trim().split(whitespace).filter { it.isNotEmpty() }
        return if (terms.isEmpty()) entries else entries.filter { entry ->
            terms.all { entry.path.contains(it, ignoreCase = true) }
        }
    }
}
