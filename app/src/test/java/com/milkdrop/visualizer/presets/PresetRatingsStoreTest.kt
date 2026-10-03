package com.milkdrop.visualizer.presets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PresetRatingsStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val root by lazy { temp.root }
    private val presetsDir by lazy { File(root, "presets") }
    private val favouritesFile by lazy { File(root, "favourites.txt") }
    private val hiddenFile by lazy { File(root, "hidden.txt") }

    /** Runs writes immediately, so tests can read the files right after saving. */
    private val store by lazy { PresetRatingsStore(presetsDir, favouritesFile, hiddenFile) { it.run() } }

    private fun preset(relative: String) = File(presetsDir, relative).path

    @Test
    fun `missing files mean nothing is rated`() {
        assertEquals(emptySet<String>(), store.loadFavourites())
        assertEquals(emptySet<String>(), store.loadHidden())
    }

    @Test
    fun `saved sets load back as the same absolute paths`() {
        val favourites = setOf(preset("A/one.milk"), preset("B/two words (2).milk"))
        val hidden = setOf(preset("A/three.milk"))
        store.save(favourites, hidden)

        assertEquals(favourites, store.loadFavourites())
        assertEquals(hidden, store.loadHidden())
    }

    @Test
    fun `files hold paths relative to the presets folder, after a header comment`() {
        store.save(setOf(preset("B/b.milk"), preset("A/a.milk")), emptySet())

        val lines = favouritesFile.readLines()
        assertTrue(lines.first().startsWith("#"))
        assertEquals(listOf("A/a.milk", "B/b.milk"), lines.drop(1))
        assertEquals(1, hiddenFile.readLines().size)
    }

    @Test
    fun `blank lines, comments and surrounding spaces in a hand-edited file are ignored`() {
        favouritesFile.writeText("# mine\n\n  A/a.milk  \n# B/b.milk\n")
        assertEquals(setOf(preset("A/a.milk")), store.loadFavourites())
    }

    @Test
    fun `saving replaces the previous list and leaves no temporary file`() {
        store.save(setOf(preset("A/a.milk")), emptySet())
        store.save(emptySet(), emptySet())

        assertEquals(emptySet<String>(), store.loadFavourites())
        assertFalse(File(root, "favourites.txt.tmp").exists())
    }
}
