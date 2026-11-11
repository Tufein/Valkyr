package com.tufein.valkyr.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tufein.valkyr.util.ConsoleSortMemory
import com.tufein.valkyr.util.LibraryBrowse
import com.tufein.valkyr.util.VariantGroups
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/** 5.1 Library tab settings, in the same preferences file as the rest (so backups carry them). */
@Singleton
class LibrarySettings @Inject constructor(@param:ApplicationContext private val context: Context) {

    private val viewKey = stringPreferencesKey(LibraryBrowse.VIEW_KEY)
    private val homeMigratedKey = booleanPreferencesKey(LibraryBrowse.HOME_MIGRATED_KEY)

    /** The view the Library tab was left in; the overview (today's look) until the user picks another. */
    val view: Flow<LibraryBrowse.ViewMode> = context.dataStore.data
        .map { LibraryBrowse.parseView(it[viewKey]) }
        .distinctUntilChanged()
        .onEach { remember(it) }

    /**
     * 7.0: the console tiles became the home. Once, a Library that was still on the shelves (or never
     * chose a view) moves to the tiles; a list or covers choice is kept, and so is a later choice of the shelves.
     */
    suspend fun migrateToConsoleHome() {
        context.dataStore.edit {
            if (it[homeMigratedKey] == true) return@edit
            val stored = it[viewKey]
            if (stored == null || stored == LibraryBrowse.ViewMode.OVERVIEW.key) {
                it[viewKey] = LibraryBrowse.ViewMode.CONSOLES.key
                remember(LibraryBrowse.ViewMode.CONSOLES)
            }
            it[homeMigratedKey] = true
        }
    }

    suspend fun setView(mode: LibraryBrowse.ViewMode) {
        context.dataStore.edit { it[viewKey] = mode.key }
        remember(mode)
    }

    /**
     * The last view seen, from a tiny SharedPreferences mirror, so the Library's first frame is the right
     * one without blocking on DataStore. The console tiles until a view has been seen once (one start after the
     * update to this version a saved list/covers view may show the overview for a moment).
     */
    fun cachedView(): LibraryBrowse.ViewMode =
        LibraryBrowse.parseView(runCatching { mirror.getString(MIRROR_KEY, null) }.getOrNull())

    private val mirror get() = context.getSharedPreferences(MIRROR_FILE, Context.MODE_PRIVATE)

    private fun remember(mode: LibraryBrowse.ViewMode) {
        runCatching { mirror.edit().putString(MIRROR_KEY, mode.key).apply() }
    }

    private val groupKey = booleanPreferencesKey(VariantGroups.GROUP_KEY)

    /** 6.0: regional / revision copies of one game listed as one entry ("Group versions" in the Library filters). */
    val groupVersions: Flow<Boolean> = context.dataStore.data
        .map { it[groupKey] ?: VariantGroups.DEFAULT_ON }
        .distinctUntilChanged()

    suspend fun setGroupVersions(on: Boolean) = context.dataStore.edit { it[groupKey] = on }

    private val consoleSortsKey = stringPreferencesKey(ConsoleSortMemory.KEY)

    /** 6.3: the sort order remembered per console (see [ConsoleSortMemory]); empty until the user sorts a single console. */
    val consoleSorts: Flow<String> = context.dataStore.data
        .map { it[consoleSortsKey].orEmpty() }
        .distinctUntilChanged()

    suspend fun setConsoleSorts(encoded: String) = context.dataStore.edit {
        if (encoded.isEmpty()) it.remove(consoleSortsKey) else it[consoleSortsKey] = encoded
    }

    private companion object {
        const val MIRROR_FILE = "library_view_cache"
        const val MIRROR_KEY = "view"
    }
}
