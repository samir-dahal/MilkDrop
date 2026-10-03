package com.milkdrop.visualizer.presets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetRatingsTest {

    private var saved: Pair<Set<String>, Set<String>>? = null
    private val all = listOf("/p/a.milk", "/p/b.milk", "/p/c.milk")

    private fun ratings(favourites: Set<String> = emptySet(), hidden: Set<String> = emptySet()) =
        PresetRatings(favourites, hidden) { f, h -> saved = f to h }

    @Test
    fun `toggling favourite adds then removes it and saves each time`() {
        val ratings = ratings()
        assertTrue(ratings.toggleFavourite("/p/a.milk"))
        assertTrue(ratings.isFavourite("/p/a.milk"))
        assertEquals(setOf("/p/a.milk") to emptySet<String>(), saved)

        assertFalse(ratings.toggleFavourite("/p/a.milk"))
        assertFalse(ratings.isFavourite("/p/a.milk"))
        assertEquals(emptySet<String>() to emptySet<String>(), saved)
    }

    @Test
    fun `hidden presets are left out of the playable list, keeping order`() {
        val ratings = ratings(hidden = setOf("/p/b.milk"))
        assertEquals(listOf("/p/a.milk", "/p/c.milk"), ratings.playable(all))
    }

    @Test
    fun `un-hiding puts a preset back in the playable list`() {
        val ratings = ratings(hidden = setOf("/p/b.milk"))
        assertFalse(ratings.toggleHidden("/p/b.milk"))
        assertEquals(all, ratings.playable(all))
    }

    @Test
    fun `with nothing hidden the same list comes back`() {
        assertSame(all, ratings().playable(all))
    }

    @Test
    fun `favourite and hidden are independent`() {
        val ratings = ratings()
        ratings.toggleFavourite("/p/a.milk")
        ratings.toggleHidden("/p/a.milk")
        assertTrue(ratings.isFavourite("/p/a.milk"))
        assertTrue(ratings.isHidden("/p/a.milk"))
        assertEquals(setOf("/p/a.milk") to setOf("/p/a.milk"), saved)
    }

    @Test
    fun `the caller's initial sets are copied, not kept`() {
        val initial = mutableSetOf("/p/a.milk")
        val ratings = ratings(favourites = initial)
        initial.clear()
        assertTrue(ratings.isFavourite("/p/a.milk"))
        assertTrue(ratings.hasFavourites())
    }
}
