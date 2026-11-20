package com.tufein.valkyr.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.tufein.valkyr.util.TrackerEntry
import com.tufein.valkyr.util.TorrentTrackers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Sources tracker: the user's personal torrent trackers, kept in `user_settings` like other settings. */
@Singleton
class TorrentTrackerSettings @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val orderKey = stringPreferencesKey(TorrentTrackers.ORDER_KEY)
    private val disabledKey = stringSetPreferencesKey(TorrentTrackers.DISABLED_KEY)

    val entries: Flow<List<TrackerEntry>> = context.dataStore.data.map { p ->
        TorrentTrackers.decode(p[orderKey], p[disabledKey])
    }.distinctUntilChanged()

    /** false when the address is invalid, a duplicate, or the list is full. */
    suspend fun add(raw: String): Boolean {
        val next = TorrentTrackers.add(current(), raw) ?: return false
        persist(next)
        return true
    }

    suspend fun remove(url: String) = persist(TorrentTrackers.remove(current(), url))

    suspend fun setEnabled(url: String, on: Boolean) = persist(TorrentTrackers.setEnabled(current(), url, on))

    private suspend fun current(): List<TrackerEntry> {
        val p = context.dataStore.data.first()
        return TorrentTrackers.decode(p[orderKey], p[disabledKey])
    }

    private suspend fun persist(list: List<TrackerEntry>) = context.dataStore.edit { p ->
        p[orderKey] = TorrentTrackers.encode(list)
        p[disabledKey] = list.filterNot { it.enabled }.map { it.url }.toSet()
    }
}
