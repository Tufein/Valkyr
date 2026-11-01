package com.tufein.valkyr.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tufein.valkyr.util.EmulatorSaveFolder
import com.tufein.valkyr.util.EmulatorSaveFolders
import com.tufein.valkyr.util.RecentSearches
import com.tufein.valkyr.util.SaveBackups
import com.tufein.valkyr.util.SyncthingFolderSettings
import com.tufein.valkyr.util.SaveMerge
import com.tufein.valkyr.util.SaveSyncOptions
import com.tufein.valkyr.util.TextSize
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Settings stored next to [SettingsDataStore], in the same preferences file. */
@Singleton
class AppSettings @Inject constructor(@param:ApplicationContext private val context: Context) {

    private val profileLock = Mutex()
    @Volatile var activeProfileSnapshot: String? = null
        private set
    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            context.dataStore.data.collect {
                profileLock.withLock { activeProfileSnapshot = context.dataStore.data.first()[Keys.ACTIVE_PROFILE].orEmpty() }
            }
        }
    }

    private object Keys {
        val AUTO_SCAN = booleanPreferencesKey("auto_scan")
        val AUTO_SCAN_HOURS = intPreferencesKey("auto_scan_hours")
        val AUTO_SCAN_WIFI = booleanPreferencesKey("auto_scan_wifi")
        val AUTO_SCAN_CHARGING = booleanPreferencesKey("auto_scan_charging")
        val AUTO_SCAN_NIGHT = booleanPreferencesKey("auto_scan_night")
        val AUTO_SCAN_LAST = longPreferencesKey("auto_scan_last")
        val SPEED_LIMIT_DAY_ONLY = booleanPreferencesKey("speed_limit_day_only")
        val BOLD_FOCUS = booleanPreferencesKey("bold_focus")
        val BIOS_DIR = stringPreferencesKey("bios_dir")
        val MIN_FREE_GB = intPreferencesKey("min_free_gb")
        val AUTO_BACKUP = booleanPreferencesKey("auto_backup")
        val AUTO_BACKUP_DIR = stringPreferencesKey("auto_backup_dir")
        val AUTO_BACKUP_LAST = longPreferencesKey("auto_backup_last")
        val LIBRARY_VIEWS = stringPreferencesKey("library_views")
        // 5.5 safer save sync (names and types: SaveMerge.SETTING_TYPES)
        val SAVE_SYNC_BACKUP_KEEP = intPreferencesKey(SaveMerge.KEY_BACKUP_KEEP)
        val SAVE_SYNC_NON_DESTRUCTIVE = booleanPreferencesKey(SaveMerge.KEY_NON_DESTRUCTIVE)
        val SAVE_SYNC_PREFER_SERVER = booleanPreferencesKey(SaveMerge.KEY_PREFER_SERVER)
        // 6.0 Syncthing folder mode (names and types: SyncthingFolderSettings.TYPES)
        val SYNCTHING_MODE = booleanPreferencesKey(SyncthingFolderSettings.KEY_MODE)
        val SYNCTHING_DIR = stringPreferencesKey(SyncthingFolderSettings.KEY_DIR)
        val SECOND_SCREEN = booleanPreferencesKey("second_screen")
        // 3.0
        val RESUME_DOWNLOADS = booleanPreferencesKey("resume_downloads")
        val REQUEUE_AFTER_RESTART = booleanPreferencesKey("requeue_after_restart")
        val PER_SERVER_LIMIT = intPreferencesKey("per_server_limit")
        val ESDE_ARTWORK = booleanPreferencesKey("esde_artwork")
        val PEGASUS_ARTWORK = booleanPreferencesKey("pegasus_artwork")
        val RETROARCH_THUMBNAILS_DIR = stringPreferencesKey("retroarch_thumbnails_dir")
        val WISHLIST_AUTO_DOWNLOAD = booleanPreferencesKey("wishlist_auto_download")
        val RA_USER = stringPreferencesKey("ra_user")
        val RA_KEY = stringPreferencesKey("ra_api_key")
        val AUTO_M3U = booleanPreferencesKey("auto_m3u")
        val QUEUE_SUMMARY = booleanPreferencesKey("queue_summary")
        val AUTO_RETRY = booleanPreferencesKey("auto_retry_failed")
        val QUEUE_HELD = booleanPreferencesKey("queue_held")
        val LAST_SEEN_VERSION = intPreferencesKey("last_seen_version_code")
        val TOUR_SEEN_VERSION = intPreferencesKey("tour_seen_version")
        val TEXT_SIZE = intPreferencesKey("text_size_percent")
        val RECENT_SEARCHES = stringPreferencesKey("recent_searches")
        val SAVE_SYNC_EMULATOR_FOLDERS = stringPreferencesKey("save_sync_emulator_folders")
        val PROFILES = stringPreferencesKey("profiles")
        val ACTIVE_PROFILE = stringPreferencesKey("active_profile")
        val PROFILE_PIN = stringPreferencesKey("profile_pin_hash")
    }

    /** Scan the sources by itself now and then (see [com.tufein.valkyr.data.service.AutoScanScheduler]). */
    val autoScan: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_SCAN] ?: false }
    val autoScanHours: Flow<Int> = context.dataStore.data.map { it[Keys.AUTO_SCAN_HOURS] ?: 24 }
    val autoScanWifiOnly: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_SCAN_WIFI] ?: true }
    val autoScanCharging: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_SCAN_CHARGING] ?: true }
    /** Only inside the night window of Settings → Downloads. */
    val autoScanNightOnly: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_SCAN_NIGHT] ?: true }
    val autoScanLast: Flow<Long> = context.dataStore.data.map { it[Keys.AUTO_SCAN_LAST] ?: 0L }
    /** The speed limit does not apply inside the night window. */
    val speedLimitDayOnly: Flow<Boolean> = context.dataStore.data.map { it[Keys.SPEED_LIMIT_DAY_ONLY] ?: false }
    /** A thicker, high-contrast focus ring for TV / handheld use. */
    val boldFocus: Flow<Boolean> = context.dataStore.data.map { it[Keys.BOLD_FOCUS] ?: false }

    /** SAF tree of the emulator's BIOS / system folder (RetroArch: `system`). */
    val biosDir: Flow<String> = context.dataStore.data.map { it[Keys.BIOS_DIR] ?: "" }
    /** Downloads wait (and running ones stop) below this much free space, in GB; 0 = off. */
    val minFreeGb: Flow<Int> = context.dataStore.data.map { it[Keys.MIN_FREE_GB] ?: 0 }
    val autoBackup: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_BACKUP] ?: false }
    val autoBackupDir: Flow<String> = context.dataStore.data.map { it[Keys.AUTO_BACKUP_DIR] ?: "" }
    val autoBackupLast: Flow<Long> = context.dataStore.data.map { it[Keys.AUTO_BACKUP_LAST] ?: 0L }
    /** Saved library filters as JSON (see [com.tufein.valkyr.util.LibraryViews]). */
    val libraryViews: Flow<String> = context.dataStore.data.map { it[Keys.LIBRARY_VIEWS] ?: "[]" }
    /** Show game details / downloads on a second display when one is connected. */
    val secondScreen: Flow<Boolean> = context.dataStore.data.map { it[Keys.SECOND_SCREEN] ?: true }

    // ---- 3.0 ----------------------------------------------------------------------------------
    /** Continue an interrupted web download from where it stopped (when the server allows it). */
    val resumeDownloads: Flow<Boolean> = context.dataStore.data.map { it[Keys.RESUME_DOWNLOADS] ?: true }
    /** Downloads that were queued or running when the app closed start again by themselves. */
    val requeueAfterRestart: Flow<Boolean> = context.dataStore.data.map { it[Keys.REQUEUE_AFTER_RESTART] ?: true }
    /** At most this many downloads at once from one server; 0 = no limit. */
    val perServerLimit: Flow<Int> = context.dataStore.data.map { it[Keys.PER_SERVER_LIMIT] ?: 0 }
    /** After a download, save cover and description where ES-DE (and Cocoon's ES-DE link) read them. */
    val esdeArtwork: Flow<Boolean> = context.dataStore.data.map { it[Keys.ESDE_ARTWORK] ?: false }
    /** After a download, the cover goes to `media/<game>/boxFront.<ext>` next to the game, where Pegasus looks. */
    val pegasusArtwork: Flow<Boolean> = context.dataStore.data.map { it[Keys.PEGASUS_ARTWORK] ?: false }
    /** RetroArch's thumbnails folder; covers for its playlists go there after a download. Empty = off. */
    val retroArchThumbnailsDir: Flow<String> = context.dataStore.data.map { it[Keys.RETROARCH_THUMBNAILS_DIR] ?: "" }
    /** A wanted game that turns up in a source is downloaded straight away (best version). */
    val wishlistAutoDownload: Flow<Boolean> = context.dataStore.data.map { it[Keys.WISHLIST_AUTO_DOWNLOAD] ?: false }
    val raUser: Flow<String> = context.dataStore.data.map { it[Keys.RA_USER] ?: "" }
    /** RetroAchievements web API key (the user's own, from their RA control panel). */
    val raKey: Flow<String> = context.dataStore.data.map { it[Keys.RA_KEY] ?: "" }
    /** Write a .m3u playlist when a finished download completes a multi-disc game. */
    val autoM3u: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_M3U] ?: true }
    /** One notification when a run of downloads is done (see [com.tufein.valkyr.data.service.QueueSummaryService]). */
    val queueSummary: Flow<Boolean> = context.dataStore.data.map { it[Keys.QUEUE_SUMMARY] ?: true }
    /** Saves folders of standalone emulators synced with RomM (see [com.tufein.valkyr.util.EmulatorSaveFolder]). */
    val saveSyncEmulatorFolders: Flow<List<EmulatorSaveFolder>> =
        context.dataStore.data.map { EmulatorSaveFolders.fromJson(it[Keys.SAVE_SYNC_EMULATOR_FOLDERS]) }
    /** Safety copies kept per save file before a sync replaces it (see [SaveBackups]). */
    val saveSyncBackupKeep: Flow<Int> = context.dataStore.data.map { SaveBackups.clampKeep(it[Keys.SAVE_SYNC_BACKUP_KEEP] ?: SaveBackups.DEFAULT_KEEP) }
    /** A sync never replaces or deletes a save, it only adds (see [SaveSyncOptions.nonDestructive]). */
    val saveSyncNonDestructive: Flow<Boolean> = context.dataStore.data.map { it[Keys.SAVE_SYNC_NON_DESTRUCTIVE] ?: false }
    /** 6.0 Folder mode (Syncthing): saves are mirrored to a folder Syncthing syncs, instead of RomM. */
    val syncthingMode: Flow<Boolean> = context.dataStore.data.map { it[Keys.SYNCTHING_MODE] ?: false }
    val syncthingDir: Flow<String> = context.dataStore.data.map { it[Keys.SYNCTHING_DIR] ?: "" }
    /** A save that differs on both sides is taken from the server when the server's is newer (see [SaveSyncOptions.preferNewerServer]). */
    val saveSyncPreferServer: Flow<Boolean> = context.dataStore.data.map { it[Keys.SAVE_SYNC_PREFER_SERVER] ?: false }
    /** Start downloads that failed for a passing reason again by themselves (see [com.tufein.valkyr.util.AutoRetry]). */
    val autoRetryFailed: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_RETRY] ?: true }
    /** The queue is on hold: running downloads finish, nothing new starts (see [com.tufein.valkyr.data.service.DownloadGate]). */
    val queueHeld: Flow<Boolean> = context.dataStore.data.map { it[Keys.QUEUE_HELD] ?: false }
    /** Version code whose "what's new" the user has seen; 0 = none yet (see [com.tufein.valkyr.util.WhatsNew]). */
    val lastSeenVersion: Flow<Int> = context.dataStore.data.map { it[Keys.LAST_SEEN_VERSION] ?: 0 }
    /** Version of the first-run tour the user has seen or skipped; 0 = never (see [com.tufein.valkyr.util.AppTour]). */
    val tourSeenVersion: Flow<Int> = context.dataStore.data.map { it[Keys.TOUR_SEEN_VERSION] ?: 0 }
    /** Text size in percent on top of Android's font size (see [TextSize]). */
    val textSizePercent: Flow<Int> = context.dataStore.data.map { it[Keys.TEXT_SIZE] ?: TextSize.DEFAULT }
    /** The last library searches, newest first (see [RecentSearches]). */
    val recentSearches: Flow<List<String>> = context.dataStore.data.map { RecentSearches.decode(it[Keys.RECENT_SEARCHES] ?: "") }
    /** Profiles as JSON (see [com.tufein.valkyr.util.Profiles]). */
    val profiles: Flow<String> = context.dataStore.data.map { it[Keys.PROFILES] ?: "[]" }
    /** Id of the active profile; empty = everything visible. */
    val activeProfile: Flow<String> = context.dataStore.data.map { it[Keys.ACTIVE_PROFILE] ?: "" }
    /** SHA-256 of the PIN that unlocks leaving a restricted profile; empty = no PIN. */
    val profilePinHash: Flow<String> = context.dataStore.data.map { it[Keys.PROFILE_PIN] ?: "" }

    suspend fun setResumeDownloads(on: Boolean) = context.dataStore.edit { it[Keys.RESUME_DOWNLOADS] = on }
    suspend fun setRequeueAfterRestart(on: Boolean) = context.dataStore.edit { it[Keys.REQUEUE_AFTER_RESTART] = on }
    suspend fun setPerServerLimit(n: Int) = context.dataStore.edit { it[Keys.PER_SERVER_LIMIT] = n.coerceIn(0, 10) }
    suspend fun setEsdeArtwork(on: Boolean) = context.dataStore.edit { it[Keys.ESDE_ARTWORK] = on }
    suspend fun setPegasusArtwork(on: Boolean) = context.dataStore.edit { it[Keys.PEGASUS_ARTWORK] = on }
    suspend fun setRetroArchThumbnailsDir(uri: String) = context.dataStore.edit { it[Keys.RETROARCH_THUMBNAILS_DIR] = uri }
    suspend fun setWishlistAutoDownload(on: Boolean) = context.dataStore.edit { it[Keys.WISHLIST_AUTO_DOWNLOAD] = on }
    suspend fun setRetroAchievements(user: String, key: String) = context.dataStore.edit { it[Keys.RA_USER] = user.trim(); it[Keys.RA_KEY] = key.trim() }
    suspend fun setAutoM3u(on: Boolean) = context.dataStore.edit { it[Keys.AUTO_M3U] = on }
    suspend fun setQueueSummary(on: Boolean) = context.dataStore.edit { it[Keys.QUEUE_SUMMARY] = on }
    suspend fun setAutoRetryFailed(on: Boolean) = context.dataStore.edit { it[Keys.AUTO_RETRY] = on }
    suspend fun setQueueHeld(on: Boolean) = context.dataStore.edit { it[Keys.QUEUE_HELD] = on }
    suspend fun setLastSeenVersion(code: Int) = context.dataStore.edit { it[Keys.LAST_SEEN_VERSION] = code }
    suspend fun setTourSeenVersion(version: Int) = context.dataStore.edit { it[Keys.TOUR_SEEN_VERSION] = version }
    suspend fun setTextSizePercent(percent: Int) = context.dataStore.edit { it[Keys.TEXT_SIZE] = percent }
    suspend fun addRecentSearch(query: String) = context.dataStore.edit {
        it[Keys.RECENT_SEARCHES] = RecentSearches.encode(RecentSearches.add(RecentSearches.decode(it[Keys.RECENT_SEARCHES] ?: ""), query))
    }
    suspend fun clearRecentSearches() = context.dataStore.edit { it.remove(Keys.RECENT_SEARCHES) }
    suspend fun setSaveSyncBackupKeep(n: Int) = context.dataStore.edit { it[Keys.SAVE_SYNC_BACKUP_KEEP] = SaveBackups.clampKeep(n) }
    suspend fun setSaveSyncNonDestructive(on: Boolean) = context.dataStore.edit { it[Keys.SAVE_SYNC_NON_DESTRUCTIVE] = on }
    suspend fun setSyncthingMode(on: Boolean) = context.dataStore.edit { it[Keys.SYNCTHING_MODE] = on }
    suspend fun setSyncthingDir(uri: String) = context.dataStore.edit { it[Keys.SYNCTHING_DIR] = uri }
    suspend fun setSaveSyncPreferServer(on: Boolean) = context.dataStore.edit { it[Keys.SAVE_SYNC_PREFER_SERVER] = on }
    suspend fun setSaveSyncEmulatorFolders(folders: List<EmulatorSaveFolder>) =
        context.dataStore.edit { it[Keys.SAVE_SYNC_EMULATOR_FOLDERS] = EmulatorSaveFolders.toJson(folders) }
    suspend fun setProfiles(json: String) = profileLock.withLock { context.dataStore.edit { it[Keys.PROFILES] = json } }
    suspend fun setActiveProfile(id: String) = profileLock.withLock {
        context.dataStore.edit { it[Keys.ACTIVE_PROFILE] = id }.also { activeProfileSnapshot = id }
    }
    /** A delayed picker/dialog must never mutate the profile that replaced the one it opened in. */
    suspend fun <T> withActiveProfile(expected: String, block: suspend () -> T): T = profileLock.withLock {
        check(context.dataStore.data.first()[Keys.ACTIVE_PROFILE].orEmpty() == expected) { "Profile changed; open the game again" }
        block()
    }
    /** Backup restoration changes profile fields as one guarded local mutation. */
    suspend fun <T> withProfileStateLock(block: suspend () -> T): T = profileLock.withLock {
        try { block() }
        finally { activeProfileSnapshot = context.dataStore.data.first()[Keys.ACTIVE_PROFILE].orEmpty() }
    }
    suspend fun setProfilePinHash(hash: String) = context.dataStore.edit { it[Keys.PROFILE_PIN] = hash }

    suspend fun setBiosDir(uri: String) = context.dataStore.edit { it[Keys.BIOS_DIR] = uri }
    suspend fun setMinFreeGb(gb: Int) = context.dataStore.edit { it[Keys.MIN_FREE_GB] = gb.coerceAtLeast(0) }
    suspend fun setAutoBackup(on: Boolean) = context.dataStore.edit { it[Keys.AUTO_BACKUP] = on }
    suspend fun setAutoBackupDir(uri: String) = context.dataStore.edit { it[Keys.AUTO_BACKUP_DIR] = uri }
    suspend fun setAutoBackupLast(at: Long) = context.dataStore.edit { it[Keys.AUTO_BACKUP_LAST] = at }
    suspend fun setLibraryViews(json: String) = context.dataStore.edit { it[Keys.LIBRARY_VIEWS] = json }
    suspend fun setSecondScreen(on: Boolean) = context.dataStore.edit { it[Keys.SECOND_SCREEN] = on }

    suspend fun setAutoScan(on: Boolean) = context.dataStore.edit { it[Keys.AUTO_SCAN] = on }
    suspend fun setAutoScanHours(hours: Int) = context.dataStore.edit { it[Keys.AUTO_SCAN_HOURS] = hours }
    suspend fun setAutoScanWifiOnly(on: Boolean) = context.dataStore.edit { it[Keys.AUTO_SCAN_WIFI] = on }
    suspend fun setAutoScanCharging(on: Boolean) = context.dataStore.edit { it[Keys.AUTO_SCAN_CHARGING] = on }
    suspend fun setAutoScanNightOnly(on: Boolean) = context.dataStore.edit { it[Keys.AUTO_SCAN_NIGHT] = on }
    suspend fun setAutoScanLast(at: Long) = context.dataStore.edit { it[Keys.AUTO_SCAN_LAST] = at }
    suspend fun setSpeedLimitDayOnly(on: Boolean) = context.dataStore.edit { it[Keys.SPEED_LIMIT_DAY_ONLY] = on }
    suspend fun setBoldFocus(on: Boolean) = context.dataStore.edit { it[Keys.BOLD_FOCUS] = on }
}
