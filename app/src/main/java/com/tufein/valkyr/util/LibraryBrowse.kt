package com.tufein.valkyr.util

import com.tufein.valkyr.data.model.SortOption

/**
 * 5.1 "Library handier": the pure parts of the Library tab's view switch and quick filters.
 *
 * The tab has four views of the same games. [ViewMode.OVERVIEW] is the shelves page that exists
 * since 3.8 (and stays the default, so an update changes nothing by itself); [ViewMode.LIST] is a
 * compact row per game with one big action button; [ViewMode.COVERS] is a grid of covers; 6.0 adds [ViewMode.CONSOLES], a start page of console tiles (see [ConsoleHome]). The last
 * view is remembered under [VIEW_KEY] and comes back on the next start.
 */
object LibraryBrowse {

    /** DataStore key of the remembered view (a [ViewMode.key]). */
    const val VIEW_KEY = "lib52_view"

    /** Their backup types, for [SettingSchema]. */
    /** 7.0: set once the Library has been moved to the console tiles (see `LibrarySettings.migrateToConsoleHome`). */
    const val HOME_MIGRATED_KEY = "home70_migrated"

    val SETTING_TYPES: Map<String, String> = mapOf(VIEW_KEY to "s", HOME_MIGRATED_KEY to "b")

    enum class ViewMode(val key: String) {
        /** 7.0: the console tiles are the home of the Library (concept C), so they come first. */
        CONSOLES("consoles"),
        OVERVIEW("overview"),
        LIST("list"),
        COVERS("covers");

        /** The views that show the games themselves (not a start page): A / tap runs the game's main action. */
        val isBrowse: Boolean get() = this == LIST || this == COVERS

        /** The views that are a start page (shelves, console tiles) while nothing narrows the library. */
        val isStartPage: Boolean get() = this == OVERVIEW || this == CONSOLES
    }

    /** The view stored under [key]; anything unknown (or nothing stored yet) is the overview, i.e. today's look. */
    fun parseView(key: String?): ViewMode = ViewMode.entries.firstOrNull { it.key == key } ?: ViewMode.CONSOLES

    /** The views offered: without a game page to open from the shelves ([hasShelves] false) there is no overview. */
    fun availableViews(hasShelves: Boolean): List<ViewMode> =
        if (hasShelves) ViewMode.entries else listOf(ViewMode.LIST, ViewMode.COVERS)

    /** Which of the three shows when nothing narrows the list: the shelves only in the overview. */
    fun showShelves(mode: ViewMode, hasShelves: Boolean, startState: Boolean, forceList: Boolean, libraryEmpty: Boolean): Boolean =
        hasShelves && startState && mode == ViewMode.OVERVIEW && !forceList && !libraryEmpty

    /** Whether the console tiles show: the same conditions as the shelves, in the [ViewMode.CONSOLES] view. */
    fun showConsoles(mode: ViewMode, hasShelves: Boolean, startState: Boolean, forceList: Boolean, libraryEmpty: Boolean): Boolean =
        hasShelves && startState && mode == ViewMode.CONSOLES && !forceList && !libraryEmpty

    /** Whether the found games are drawn as a cover grid. */
    fun showGrid(mode: ViewMode): Boolean = mode == ViewMode.COVERS

    /** Whether the rows carry the action button (the list view; the overview keeps today's rows). */
    fun showActionRows(mode: ViewMode): Boolean = mode == ViewMode.LIST

    /** Downloaded / not downloaded, one at a time; [ALL] is no filter. */
    enum class Ownership {
        ALL, DOWNLOADED, NOT_DOWNLOADED;

        val isActive: Boolean get() = this != ALL

        fun matches(owned: Boolean): Boolean = when (this) {
            ALL -> true
            DOWNLOADED -> owned
            NOT_DOWNLOADED -> !owned
        }

        /** A chip press: the same chip again switches it off, the other one switches over. */
        fun toggled(chip: Ownership): Ownership = if (this == chip) ALL else chip
    }

    /** What the button on a game says and does. */
    enum class PrimaryAction { DOWNLOAD, PLAY, DOWNLOADING }

    fun primaryAction(owned: Boolean, downloading: Boolean): PrimaryAction = when {
        downloading -> PrimaryAction.DOWNLOADING
        owned -> PrimaryAction.PLAY
        else -> PrimaryAction.DOWNLOAD
    }

    /** The order the sort chip walks through, one press at a time. */
    val SORT_CYCLE: List<SortOption> =
        listOf(SortOption.NAME_ASC, SortOption.NAME_DESC, SortOption.NEWEST, SortOption.SIZE_DESC, SortOption.SIZE_ASC)

    fun nextSort(current: SortOption): SortOption {
        val at = SORT_CYCLE.indexOf(current)
        return SORT_CYCLE[(at + 1) % SORT_CYCLE.size]
    }
}
