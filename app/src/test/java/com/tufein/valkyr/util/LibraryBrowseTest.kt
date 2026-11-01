package com.tufein.valkyr.util

import com.tufein.valkyr.data.model.SortOption
import com.tufein.valkyr.util.LibraryBrowse.Ownership
import com.tufein.valkyr.util.LibraryBrowse.PrimaryAction
import com.tufein.valkyr.util.LibraryBrowse.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryBrowseTest {

    @Test
    fun `nothing stored or an unknown value is the overview, today's look`() {
        assertEquals(ViewMode.OVERVIEW, LibraryBrowse.parseView(null))
        assertEquals(ViewMode.OVERVIEW, LibraryBrowse.parseView(""))
        assertEquals(ViewMode.OVERVIEW, LibraryBrowse.parseView("carousel"))
    }

    @Test
    fun `every view round-trips through its stored key`() {
        ViewMode.entries.forEach { assertEquals(it, LibraryBrowse.parseView(it.key)) }
        assertEquals(ViewMode.entries.size, ViewMode.entries.map { it.key }.toSet().size)
    }

    @Test
    fun `the setting key is registered as a string in the backup schema`() {
        assertEquals("lib52_view", LibraryBrowse.VIEW_KEY)
        assertEquals("s", SettingSchema.expectedType(LibraryBrowse.VIEW_KEY))
        assertTrue(SettingSchema.compatible(LibraryBrowse.VIEW_KEY, "list"))
        assertFalse(SettingSchema.compatible(LibraryBrowse.VIEW_KEY, true))
    }

    @Test
    fun `the overview is only offered when the shelves can open a game page`() {
        assertEquals(listOf(ViewMode.OVERVIEW, ViewMode.LIST, ViewMode.COVERS, ViewMode.CONSOLES), LibraryBrowse.availableViews(true))
        assertEquals(listOf(ViewMode.LIST, ViewMode.COVERS), LibraryBrowse.availableViews(false))
    }

    @Test
    fun `shelves show only in the overview, in the start state, with a library`() {
        fun shelves(mode: ViewMode, start: Boolean = true, force: Boolean = false, empty: Boolean = false, has: Boolean = true) =
            LibraryBrowse.showShelves(mode, has, start, force, empty)
        assertTrue(shelves(ViewMode.OVERVIEW))
        assertFalse(shelves(ViewMode.LIST))
        assertFalse(shelves(ViewMode.COVERS))
        assertFalse(shelves(ViewMode.CONSOLES))
        assertFalse(shelves(ViewMode.OVERVIEW, start = false))
        assertFalse(shelves(ViewMode.OVERVIEW, force = true))
        assertFalse(shelves(ViewMode.OVERVIEW, empty = true))
        assertFalse(shelves(ViewMode.OVERVIEW, has = false))
    }

    @Test
    fun `console tiles show only in the consoles view, in the start state, with a library`() {
        fun tiles(mode: ViewMode, start: Boolean = true, force: Boolean = false, empty: Boolean = false, has: Boolean = true) =
            LibraryBrowse.showConsoles(mode, has, start, force, empty)
        assertTrue(tiles(ViewMode.CONSOLES))
        assertFalse(tiles(ViewMode.OVERVIEW))
        assertFalse(tiles(ViewMode.LIST))
        assertFalse(tiles(ViewMode.CONSOLES, start = false))
        assertFalse(tiles(ViewMode.CONSOLES, force = true))
        assertFalse(tiles(ViewMode.CONSOLES, empty = true))
        assertFalse(tiles(ViewMode.CONSOLES, has = false))
    }

    @Test
    fun `the consoles view is stored as consoles and is a start page, not a browse view`() {
        assertEquals(ViewMode.CONSOLES, LibraryBrowse.parseView("consoles"))
        assertFalse(ViewMode.CONSOLES.isBrowse)
        assertTrue(ViewMode.CONSOLES.isStartPage && ViewMode.OVERVIEW.isStartPage)
        assertFalse(ViewMode.LIST.isStartPage || ViewMode.COVERS.isStartPage)
        assertTrue(SettingSchema.compatible(LibraryBrowse.VIEW_KEY, "consoles"))
    }

    @Test
    fun `only the list has action rows and only covers has the grid`() {
        assertTrue(LibraryBrowse.showActionRows(ViewMode.LIST))
        assertFalse(LibraryBrowse.showActionRows(ViewMode.OVERVIEW))
        assertFalse(LibraryBrowse.showActionRows(ViewMode.COVERS))
        assertTrue(LibraryBrowse.showGrid(ViewMode.COVERS))
        assertFalse(LibraryBrowse.showGrid(ViewMode.LIST))
        assertFalse(ViewMode.OVERVIEW.isBrowse)
        assertTrue(ViewMode.LIST.isBrowse && ViewMode.COVERS.isBrowse)
    }

    @Test
    fun `ownership chips filter and switch over`() {
        assertTrue(Ownership.ALL.matches(true) && Ownership.ALL.matches(false))
        assertTrue(Ownership.DOWNLOADED.matches(true))
        assertFalse(Ownership.DOWNLOADED.matches(false))
        assertTrue(Ownership.NOT_DOWNLOADED.matches(false))
        assertFalse(Ownership.NOT_DOWNLOADED.matches(true))
        assertFalse(Ownership.ALL.isActive)
        assertEquals(Ownership.DOWNLOADED, Ownership.ALL.toggled(Ownership.DOWNLOADED))
        assertEquals(Ownership.ALL, Ownership.DOWNLOADED.toggled(Ownership.DOWNLOADED))
        assertEquals(Ownership.NOT_DOWNLOADED, Ownership.DOWNLOADED.toggled(Ownership.NOT_DOWNLOADED))
    }

    @Test
    fun `the button says download, play or downloading`() {
        assertEquals(PrimaryAction.DOWNLOAD, LibraryBrowse.primaryAction(owned = false, downloading = false))
        assertEquals(PrimaryAction.PLAY, LibraryBrowse.primaryAction(owned = true, downloading = false))
        assertEquals(PrimaryAction.DOWNLOADING, LibraryBrowse.primaryAction(owned = false, downloading = true))
        // A re-download of a game already on disk still shows as running.
        assertEquals(PrimaryAction.DOWNLOADING, LibraryBrowse.primaryAction(owned = true, downloading = true))
    }

    @Test
    fun `the sort chip visits every order and comes back`() {
        assertEquals(SortOption.entries.toSet(), LibraryBrowse.SORT_CYCLE.toSet())
        var sort = SortOption.NAME_ASC
        val seen = mutableListOf(sort)
        repeat(LibraryBrowse.SORT_CYCLE.size) { sort = LibraryBrowse.nextSort(sort); seen += sort }
        assertEquals(SortOption.NAME_ASC, seen.last())
        assertEquals(SortOption.entries.size, seen.dropLast(1).toSet().size)
    }
}
