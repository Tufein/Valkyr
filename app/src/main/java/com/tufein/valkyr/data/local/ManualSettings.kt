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
        val current = sources.first()
        val next = if (on) current + source else current - source
        context.dataStore.edit { it[Keys.SOURCES] = ManualSources.encodeEnabled(next) }
    }

    suspend fun misses(): Map<String, Long> = Manuals.TextMap.decode(context.dataStore.data.first()[Keys.MISSES])
        .mapValues { it.value.toLongOrNull() ?: 0L }

    suspend fun rememberMiss(gameKey: String, source: ManualSource, now: Long) {
        val next = ManualSources.rememberMiss(misses(), gameKey, source, now)
        context.dataStore.edit { it[Keys.MISSES] = Manuals.TextMap.encode(next.mapValues { (_, at) -> at.toString() }) }
    }

    suspend fun forgetMisses(gameKey: String) {
        val next = misses().filterKeys { !it.startsWith("$gameKey|") }
        context.dataStore.edit { it[Keys.MISSES] = Manuals.TextMap.encode(next.mapValues { (_, at) -> at.toString() }) }
    }

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
