package com.tufein.valkyr.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tufein.valkyr.util.Manuals
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 6.0 game manuals: whether a game's manual is fetched together with the game (off by default), the
 * most space manuals may take, and the last page read of each. In the shared preferences file, so a
 * settings backup carries it (types in [Manuals.SETTING_TYPES]).
 */
@Singleton
class ManualSettings @Inject constructor(@param:ApplicationContext private val context: Context) {

    private object Keys {
        val WITH_GAME = booleanPreferencesKey(Manuals.KEY_WITH_GAME)
        val CAP_MB = intPreferencesKey(Manuals.KEY_CAP_MB)
        val LAST_PAGES = stringPreferencesKey(Manuals.KEY_LAST_PAGES)
    }

    val withGame: Flow<Boolean> = context.dataStore.data.map { it[Keys.WITH_GAME] ?: false }
    val capMb: Flow<Int> = context.dataStore.data.map { Manuals.clampCapMb(it[Keys.CAP_MB]) }

    suspend fun setWithGame(on: Boolean) { context.dataStore.edit { it[Keys.WITH_GAME] = on } }
    suspend fun setCapMb(mb: Int) { context.dataStore.edit { it[Keys.CAP_MB] = Manuals.clampCapMb(mb) } }

    suspend fun lastPage(key: String): Int = Manuals.LastPages.get(context.dataStore.data.first()[Keys.LAST_PAGES], key)
    suspend fun setLastPage(key: String, page: Int) {
        context.dataStore.edit { it[Keys.LAST_PAGES] = Manuals.LastPages.put(it[Keys.LAST_PAGES], key, page) }
    }
}
