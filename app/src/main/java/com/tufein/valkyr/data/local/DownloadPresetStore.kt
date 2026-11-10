package com.tufein.valkyr.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tufein.valkyr.util.DownloadPreset
import com.tufein.valkyr.util.DownloadPresetOptions
import com.tufein.valkyr.util.DownloadPresets
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import androidx.datastore.preferences.core.MutablePreferences
import com.tufein.valkyr.util.Constants

data class DownloadPresetSnapshot(val presets: List<DownloadPreset>, val current: DownloadPresetOptions)

/** Presets are device-wide, including when a personal profile is active. */
@Singleton
class DownloadPresetStore internal constructor(private val dataStore: DataStore<Preferences>) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.dataStore)

    private object Keys {
        val PRESETS = stringPreferencesKey(DownloadPresets.KEY)
        // Existing AppSettings keys: updating these here keeps applying the whole preset atomic.
        val PER_SERVER = intPreferencesKey("per_server_limit")
        val DAY_LIMIT = booleanPreferencesKey("speed_limit_day_only")
    }

    val snapshot: Flow<DownloadPresetSnapshot> = dataStore.data.map { preferences ->
        DownloadPresetSnapshot(DownloadPresets.all(DownloadPresets.decode(preferences[Keys.PRESETS])), current(preferences))
    }.distinctUntilChanged()

    /** Copies the settings from the same transaction that stores the new named preset. */
    suspend fun saveCurrent(name: String) {
        requireValidName(name)
        dataStore.edit { preferences ->
            saveInto(preferences, DownloadPreset(UUID.randomUUID().toString(), name.trim(), current(preferences)))
        }
    }

    suspend fun save(preset: DownloadPreset) {
        require(preset.id.isNotBlank() && preset.id.length <= 80)
        if (!preset.builtIn) requireValidName(preset.name)
        dataStore.edit { preferences -> saveInto(preferences, preset.copy(
            name = if (preset.builtIn) "" else preset.name.trim(), options = preset.options.bounded())) }
    }

    /** Resolves the stored preset inside edit, so a concurrent edit/delete cannot apply stale settings. */
    suspend fun apply(id: String) {
        dataStore.edit { preferences ->
            val preset = DownloadPresets.all(DownloadPresets.decode(preferences[Keys.PRESETS])).firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Preset no longer exists")
            val options = preset.options.bounded()
            preferences[SettingsKeys.LIMIT_SPEED] = options.limitSpeed
            preferences[SettingsKeys.CONCURRENT_DOWNLOADS] = options.concurrentDownloads
            preferences[Keys.PER_SERVER] = options.perServerLimit
            preferences[SettingsKeys.DOWNLOAD_WIFI_ONLY] = options.wifiOnly
            preferences[SettingsKeys.DOWNLOAD_CHARGING_ONLY] = options.chargingOnly
            preferences[SettingsKeys.DOWNLOAD_NIGHT_ONLY] = options.nightOnly
            preferences[SettingsKeys.DOWNLOAD_NIGHT_START] = options.nightStart
            preferences[SettingsKeys.DOWNLOAD_NIGHT_END] = options.nightEnd
            preferences[Keys.DAY_LIMIT] = options.speedLimitDayOnly
        }
    }

    suspend fun delete(id: String) {
        require(id != DownloadPresets.DAYTIME_ID && id != DownloadPresets.NIGHT_ID)
        dataStore.edit { preferences ->
            preferences[Keys.PRESETS] = DownloadPresets.encode(DownloadPresets.decode(preferences[Keys.PRESETS]).filterNot { it.id == id })
        }
    }

    private fun saveInto(preferences: MutablePreferences, preset: DownloadPreset) {
        val saved = DownloadPresets.decode(preferences[Keys.PRESETS])
        require(preset.builtIn || saved.any { it.id == preset.id } || saved.count { !it.builtIn } < DownloadPresets.MAX_CUSTOM)
        preferences[Keys.PRESETS] = DownloadPresets.encode(saved.filterNot { it.id == preset.id } + preset)
    }

    private fun requireValidName(name: String) { require(name.trim().length in 1..DownloadPresets.MAX_NAME) }

    private fun current(preferences: Preferences) = DownloadPresetOptions(
        limitSpeed = preferences[SettingsKeys.LIMIT_SPEED] ?: Float.POSITIVE_INFINITY,
        concurrentDownloads = preferences[SettingsKeys.CONCURRENT_DOWNLOADS] ?: Constants.DEFAULT_CONCURRENT_DOWNLOADS,
        perServerLimit = preferences[Keys.PER_SERVER] ?: 0,
        wifiOnly = preferences[SettingsKeys.DOWNLOAD_WIFI_ONLY] ?: false,
        chargingOnly = preferences[SettingsKeys.DOWNLOAD_CHARGING_ONLY] ?: false,
        nightOnly = preferences[SettingsKeys.DOWNLOAD_NIGHT_ONLY] ?: false,
        nightStart = preferences[SettingsKeys.DOWNLOAD_NIGHT_START] ?: 1380,
        nightEnd = preferences[SettingsKeys.DOWNLOAD_NIGHT_END] ?: 420,
        speedLimitDayOnly = preferences[Keys.DAY_LIMIT] ?: false
    ).bounded()
}
