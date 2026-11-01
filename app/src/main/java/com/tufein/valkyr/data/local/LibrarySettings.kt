package com.tufein.valkyr.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tufein.valkyr.util.LibraryBrowse
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

    /** The view the Library tab was left in; the overview (today's look) until the user picks another. */
    val view: Flow<LibraryBrowse.ViewMode> = context.dataStore.data
        .map { LibraryBrowse.parseView(it[viewKey]) }
        .distinctUntilChanged()
        .onEach { remember(it) }

    suspend fun setView(mode: LibraryBrowse.ViewMode) {
        context.dataStore.edit { it[viewKey] = mode.key }
        remember(mode)
    }

    /**
     * The last view seen, from a tiny SharedPreferences mirror, so the Library's first frame is the right
     * one without blocking on DataStore. The overview until a view has been seen once (one start after the
     * update to this version a saved list/covers view may show the overview for a moment).
     */
    fun cachedView(): LibraryBrowse.ViewMode =
        LibraryBrowse.parseView(runCatching { mirror.getString(MIRROR_KEY, null) }.getOrNull())

    private val mirror get() = context.getSharedPreferences(MIRROR_FILE, Context.MODE_PRIVATE)

    private fun remember(mode: LibraryBrowse.ViewMode) {
        runCatching { mirror.edit().putString(MIRROR_KEY, mode.key).apply() }
    }

    private companion object {
        const val MIRROR_FILE = "library_view_cache"
        const val MIRROR_KEY = "view"
    }
}
