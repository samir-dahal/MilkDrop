package com.milkdrop.visualizer.presets

/**
 * Favourite and hidden presets, by full path. Hidden presets are left out of the playlist, so
 * Next, Shuffle and Auto-advance all skip them; they stay listed (dimmed) so they can be un-hidden.
 *
 * [onChanged] gets both sets after every change, to persist them.
 */
class PresetRatings(
    favourites: Set<String>,
    hidden: Set<String>,
    private val onChanged: (favourites: Set<String>, hidden: Set<String>) -> Unit,
) {
    private val favourites = favourites.toMutableSet()
    private val hidden = hidden.toMutableSet()

    fun isFavourite(path: String): Boolean = path in favourites

    fun isHidden(path: String): Boolean = path in hidden

    fun hasFavourites(): Boolean = favourites.isNotEmpty()

    /** Returns the new state. */
    fun toggleFavourite(path: String): Boolean = toggle(favourites, path)

    /** Returns the new state. */
    fun toggleHidden(path: String): Boolean = toggle(hidden, path)

    /** [all] without the hidden presets, in the same order. */
    fun playable(all: List<String>): List<String> =
        if (hidden.isEmpty()) all else all.filterNot { it in hidden }

    private fun toggle(set: MutableSet<String>, path: String): Boolean {
        val nowIn = if (path in set) !set.remove(path) else set.add(path)
        onChanged(favourites.toSet(), hidden.toSet())
        return nowIn
    }
}
