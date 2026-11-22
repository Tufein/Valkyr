package com.tufein.valkyr.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tufein.valkyr.util.ManualSource
import com.tufein.valkyr.util.ManualSources
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
        val SOURCES = stringPreferencesKey(Manuals.KEY_SOURCES)
        val MISSES = stringPreferencesKey(Manuals.KEY_MISSES)
        val ATTRIBUTION = stringPreferencesKey(Manuals.KEY_ATTRIBUTION)
    }

    val withGame: Flow<Boolean> = context.dataStore.data.map { it[Keys.WITH_GAME] ?: false }
    val capMb: Flow<Int> = context.dataStore.data.map { Manuals.clampCapMb(it[Keys.CAP_MB]) }
    /** Never null on disk: an empty value means every automatic source was switched off. */
    val sources: Flow<Set<ManualSource>> = context.dataStore.data.map { prefs ->
        prefs[Keys.SOURCES]?.let { ManualSources.decodeEnabled(it) } ?: ManualSources.DEFAULT_ENABLED
    }

    suspend fun setWithGame(on: Boolean) { context.dataStore.edit { it[Keys.WITH_GAME] = on } }
    suspend fun setCapMb(mb: Int) { context.dataStore.edit { it[Keys.CAP_MB] = Manuals.clampCapMb(mb) } }
    suspend fun setSource(source: ManualSource, on: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.SOURCES]?.let { ManualSources.decodeEnabled(it) } ?: ManualSources.DEFAULT_ENABLED
            val next = if (on) current + source else current - source
            prefs[Keys.SOURCES] = ManualSources.encodeEnabled(next)
        }
    }

    suspend fun misses(): Map<String, Long> = Manuals.TextMap.decode(context.dataStore.data.first()[Keys.MISSES])
        .mapValues { it.value.toLongOrNull() ?: 0L }

    suspend fun rememberMiss(gameKey: String, source: ManualSource, now: Long) {
        context.dataStore.edit { prefs ->
            // Decode and write inside the transform so two finished downloads cannot overwrite each other.
            val next = ManualSources.rememberMiss(decodeMisses(prefs[Keys.MISSES]), gameKey, source, now)
            prefs[Keys.MISSES] = encodeMisses(next)
        }
    }

    suspend fun forgetMisses(gameKey: String) {
        context.dataStore.edit { prefs ->
            val next = decodeMisses(prefs[Keys.MISSES]).filterKeys { !it.startsWith("$gameKey|") }
            prefs[Keys.MISSES] = encodeMisses(next)
        }
    }

    private fun decodeMisses(value: String?): Map<String, Long> =
        Manuals.TextMap.decode(value).mapValues { it.value.toLongOrNull() ?: 0L }

    private fun encodeMisses(misses: Map<String, Long>): String =
        Manuals.TextMap.encode(misses.mapValues { (_, at) -> at.toString() })

    suspend fun sourceOf(key: String): ManualSource? =
        Manuals.TextMap.get(context.dataStore.data.first()[Keys.ATTRIBUTION], key)?.let { ManualSource.entries.firstOrNull { s -> s.key == it } }

    suspend fun sourcesOf(keys: List<String>): Map<String, ManualSource> {
        val all = Manuals.TextMap.decode(context.dataStore.data.first()[Keys.ATTRIBUTION])
        return keys.mapNotNull { key -> all[key]?.let { value -> key to ManualSource.entries.firstOrNull { it.key == value } } }
            .mapNotNull { (key, source) -> source?.let { key to it } }.toMap()
    }

    suspend fun setSourceOf(key: String, source: ManualSource) {
        context.dataStore.edit { it[Keys.ATTRIBUTION] = Manuals.TextMap.put(it[Keys.ATTRIBUTION], key, source.key) }
    }

    suspend fun removeSourceOf(key: String) {
        context.dataStore.edit { it[Keys.ATTRIBUTION] = Manuals.TextMap.remove(it[Keys.ATTRIBUTION], key) }
    }

    suspend fun lastPage(key: String): Int = Manuals.LastPages.get(context.dataStore.data.first()[Keys.LAST_PAGES], key)
    suspend fun setLastPage(key: String, page: Int) {
        context.dataStore.edit { it[Keys.LAST_PAGES] = Manuals.LastPages.put(it[Keys.LAST_PAGES], key, page) }
    }
}
