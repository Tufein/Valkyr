package com.tufein.valkyr.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.tufein.valkyr.util.RetroArchPlaylists
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RetroArch playlists (3.5.0): RetroArch's playlists folder the user picked (a SAF tree) and the
 * playlists switched off. Off ones are stored rather than chosen ones, so a console that gets its
 * first games later is written too.
 */
@Singleton
class RetroArchPlaylistsSettings @Inject constructor(@param:ApplicationContext private val context: Context) {

    /** [excluded]: libretro system names ("Nintendo - Game Boy Advance") not to write. */
    data class Values(val folder: String = "", val excluded: Set<String> = emptySet(), val auto: Boolean = false, val cores: Map<String, String> = emptyMap())

    private val folderKey = stringPreferencesKey(RetroArchPlaylists.FOLDER_KEY)
    private val excludedKey = stringSetPreferencesKey(RetroArchPlaylists.EXCLUDED_KEY)
    private val autoKey = booleanPreferencesKey(RetroArchPlaylists.AUTO_KEY)
    private val coresKey = stringSetPreferencesKey(RetroArchPlaylists.CORES_KEY)

    val values: Flow<Values> = context.dataStore.data.map { Values(it[folderKey].orEmpty(), it[excludedKey].orEmpty(), it[autoKey] ?: false, RetroArchPlaylists.parseCores(it[coresKey].orEmpty())) }

    /** 6.2: after a rescan or a finished download, games that are new are added to playlists that exist. */
    suspend fun setAuto(on: Boolean) = context.dataStore.edit { it[autoKey] = on }

    /** 6.5: the core a system's playlist opens with ([core] null = RetroArch asks). */
    suspend fun setCore(system: String, core: String?) = context.dataStore.edit {
        val now = RetroArchPlaylists.parseCores(it[coresKey].orEmpty())
        it[coresKey] = RetroArchPlaylists.encodeCores(if (core == null) now - system else now + (system to core))
    }

    suspend fun setFolder(uri: String) = context.dataStore.edit { it[folderKey] = uri }

    /** Switches the playlist of [system] on or off. */
    suspend fun setIncluded(system: String, included: Boolean) = context.dataStore.edit {
        val now = it[excludedKey].orEmpty()
        it[excludedKey] = if (included) now - system else now + system
    }

    /** All of [systems] on ([included]) or off; the others keep their choice. */
    suspend fun setAll(systems: Collection<String>, included: Boolean) = context.dataStore.edit {
        val now = it[excludedKey].orEmpty()
        it[excludedKey] = if (included) now - systems.toSet() else now + systems
    }
}
