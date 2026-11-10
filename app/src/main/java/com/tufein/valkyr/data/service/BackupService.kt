package com.tufein.valkyr.data.service

import android.content.Context
import androidx.core.net.toUri
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.tufein.valkyr.BuildConfig
import com.tufein.valkyr.data.local.SettingsKeys
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.local.dao.DownloadHistoryDao
import com.tufein.valkyr.data.local.dao.FavouriteDao
import com.tufein.valkyr.data.local.dao.WishlistDao
import com.tufein.valkyr.data.local.dataStore
import com.tufein.valkyr.data.repository.CollectionsRepository
import com.tufein.valkyr.data.repository.SourcesRepository
import com.tufein.valkyr.util.BackupJson
import com.tufein.valkyr.util.CloudSettingKeys
import com.tufein.valkyr.util.CoverApis
import com.tufein.valkyr.util.ScreenScraper
import com.tufein.valkyr.util.SourcesJson
import com.tufein.valkyr.util.SettingSchema
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import androidx.datastore.preferences.core.Preferences
import com.tufein.valkyr.util.OfflineCollections
import com.tufein.valkyr.util.SmartCollectionRules
import com.tufein.valkyr.util.SmartStorage

/**
 * One file with everything worth keeping: settings, sources, favourites and the downloads list.
 *
 * ```
 * { "format": "dogmatix-backup", "version": 1, "createdAt": 1759400000000, "appVersion": "1.2",
 *   "settings": { "theme_mode": { "t": "s", "v": "DARK" }, … },
 *   "sources": { …the sources export document… },
 *   "favourites": [ … ], "downloadHistory": [ … ] }
 * ```
 * Folder settings are SAF grants that belong to this installation: on restore they are only
 * applied when the app still holds the permission, otherwise the current value is kept and the
 * user is asked to pick the folder again. Sources that are local `.torrent` copies cannot travel
 * in a backup; a restore keeps the ones this install already has.
 */
@Singleton
class BackupService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sourcesRepository: SourcesRepository,
    private val favouriteDao: FavouriteDao,
    private val downloadHistoryDao: DownloadHistoryDao,
    private val wishlistDao: WishlistDao,
    private val collections: CollectionsRepository,
    private val journal: GameJournalService,
    private val profiles: AppSettings
) {
    data class Summary(
        val settings: Int,
        val consoles: Int,
        val favourites: Int,
        val downloads: Int,
        /** Folder settings that could not be restored because this install has no access to them. */
        val foldersToRepick: Int = 0,
        /** Malformed backed-up settings ignored while keeping the current valid value. */
        val skippedSettings: Int = 0
    )

    class InvalidBackupException : IllegalArgumentException("Not a Valkyr backup")

    /** The file was written by a newer Dogmatix whose format this version does not know. */
    class NewerBackupException(val version: Int) : IllegalArgumentException("Backup format $version is newer than ${BackupJson.VERSION}")

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /** An export never reads a half-restored state, and a restore never starts while one is being read. */
    private val stateLock = Mutex()

    suspend fun export(): Pair<String, Summary> = withContext(Dispatchers.IO) { stateLock.withLock { exportLocked() } }

    private suspend fun exportLocked(): Pair<String, Summary> {
        val prefs = context.dataStore.data.first().asMap()
        val settings = JsonObject()
        // The WebDAV password is the one secret that stays out of every backup file (CloudSettingKeys).
        prefs.forEach { (key, value) ->
            if (!CloudSettingKeys.isSecret(SettingSchema.localName(key.name))) BackupJson.encodeSetting(value)?.let { settings.add(key.name, it) }
        }

        val sources = JsonParser.parseString(sourcesRepository.exportDocument()).asJsonObject
        val favourites = favouriteDao.allProfiles()
        val history = downloadHistoryDao.getAll()

        val root = JsonObject().apply {
            addProperty("format", BackupJson.FORMAT)
            addProperty("version", BackupJson.VERSION)
            addProperty("createdAt", System.currentTimeMillis())
            addProperty("appVersion", BuildConfig.VERSION_NAME)
            add("settings", settings)
            // Console defaults and 2.6 per-game choices share this profile-scoped file.
            add("emulatorChoices", GameEmulatorPreferences.export(context.getSharedPreferences("game_launchers", Context.MODE_PRIVATE)))
            add("gameJournal", journal.export())
            add("sources", sources)
            add("favourites", BackupJson.favouritesToJson(favourites))
            add("collectionIdentities", JsonObject().apply { collections.identities().forEach { (id, name) -> addProperty(id.toString(), name) } })
            add("wishlist", BackupJson.wishlistToJson(wishlistDao.getAll()))
            collections.export().takeIf { it.isNotEmpty() }?.let { list ->
                com.google.gson.JsonParser.parseString(SourcesJson.serializeDocument(emptyList(), collections = list)).asJsonObject.get("_collections")
                    ?.let { add("collections", it) }
            }
            add("downloadHistory", BackupJson.historyToJson(history))
        }
        val text = gson.toJson(root)
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BACKUP_BYTES) { "Backup is too large" }
        return text to Summary(settings.size(), consoleCount(sources), favourites.size, history.size)
    }

    /**
     * Reads the document at [uri] and checks it is a backup this version understands; nothing is
     * changed yet. The picker accepts any file, and ROM folders hold multi-GB images: anything
     * larger than [MAX_BACKUP_BYTES] (far above any real backup) is rejected without reading it all.
     */
    suspend fun read(uri: String): JsonObject = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri.toUri())?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > MAX_BACKUP_BYTES) throw InvalidBackupException()
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        } ?: throw IllegalStateException("Cannot open $uri")
        parse(bytes.toString(Charsets.UTF_8))
    }

    /**
     * Checks that [text] is a backup this version understands and returns it; nothing is changed.
     * [read] uses it for a picked file, the cloud backup for the decrypted contents of a `.dgxb`.
     */
    fun parse(text: String): JsonObject {
        if (text.length > MAX_BACKUP_BYTES) throw InvalidBackupException()
        val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
        val format = runCatching { root?.get("format")?.asString }.getOrNull()
        if (root == null || format != BackupJson.FORMAT) throw InvalidBackupException()
        val version = runCatching { root.get("version").asInt }.getOrDefault(0)
        if (version > BackupJson.VERSION) throw NewerBackupException(version)
        return root
    }

    /**
     * Restores [backup] in two phases. First every section is read and checked, so a damaged
     * file fails before anything is touched. Then everything is applied in one go that cannot be
     * cancelled (leaving the screen half-way must not leave a half-restored app); the sources are
     * replaced in a single database transaction.
     */
    suspend fun restore(backup: JsonObject): Summary = withContext(Dispatchers.IO) { stateLock.withLock { restoreLocked(backup) } }

    private suspend fun restoreLocked(backup: JsonObject): Summary {
        // Null when the file has no settings section: then the current settings stay untouched.
        val rawSettings = (backup.get("settings") as? JsonObject)?.entrySet()
        val settings = rawSettings
            ?.mapNotNull { (name, element) -> BackupJson.decodeSetting(name, element)?.let { name to it } }
        val restoredNames = settings.orEmpty().map { it.first }.toSet()
        val skippedNames = rawSettings.orEmpty().map { it.key }.filterNot {
            it in restoredNames || CloudSettingKeys.isSecret(SettingSchema.localName(it))
        }.toSet()
        val sourcesText = (backup.get("sources") as? JsonObject)
            ?.takeIf { consoleCount(it) > 0 }
            ?.toString()
            ?.also { require(SourcesJson.parseDocument(it).isNotEmpty()) { "No sources found in file" } }
        val favourites = BackupJson.favouritesFromJson(backup.get("favourites"))
        val downloads = BackupJson.historyFromJson(backup.get("downloadHistory"))
        val wishlist = BackupJson.wishlistFromJson(backup.get("wishlist"))
        val savedCollections = backup.get("collections")?.let { SourcesJson.parseCollections(JsonObject().apply { add("_collections", it) }.toString()) }.orEmpty()
        val journalEntries = backup.get("gameJournal")?.let(journal::validateRestore)
        journalEntries?.let { journal.preflightRestore(it) }

        return withContext(NonCancellable) {
            // A missing journal section keeps the current notes; conflicting imported notes are retained.
            journalEntries?.let { journal.restore(it) }
            // Restore collection names before their settings: numeric ids differ on another install.
            collections.import(savedCollections)
            val identities = collections.identities().entries.associate { it.value to it.key }
            val idMap = (backup.get("collectionIdentities") as? JsonObject)?.entrySet()?.mapNotNull { (old, name) ->
                val oldId = old.toLongOrNull()
                if (oldId != null && name.isJsonPrimitive && name.asJsonPrimitive.isString) identities[name.asString]?.let { oldId to it } else null
            }?.toMap().orEmpty()
            val mapped = settings?.mapNotNull { (name, value) -> when (name) {
                "smart_collection_rules" -> {
                    val rules = SmartCollectionRules.decode(value as? String) ?: return@mapNotNull null
                    name to SmartCollectionRules.encode(rules.mapNotNull { (id, rule) -> idMap[id]?.let { it to rule } }.toMap())
                }
                "offline_collections_ids" -> name to (value as? Set<*>).orEmpty().mapNotNull { (it as? String)?.toLongOrNull()?.let(idMap::get)?.toString() }.toSet()
                "offline_collections_quotas" -> name to (value as? Set<*>).orEmpty().mapNotNull {
                    val text = it as? String ?: return@mapNotNull null
                    idMap[text.substringBefore(':').toLongOrNull()]?.let { id -> "$id:${text.substringAfter(':')}" }
                }.toSet()
                "offline_collections_fetched" -> name to (value as? Set<*>).orEmpty().mapNotNull {
                    val fetched = (it as? String)?.let(OfflineCollections::decodeFetched) ?: return@mapNotNull null
                    idMap[fetched.collectionId]?.let { id -> OfflineCollections.encode(fetched.copy(collectionId = id)) }
                }.toSet()
                else -> name to value
            } }
            val (restored, repick) = mapped?.let { restoreSettings(it, skippedNames) } ?: (0 to 0)
            (backup.get("emulatorChoices") as? JsonObject)?.let { choices ->
                GameEmulatorPreferences.restore(context.getSharedPreferences("game_launchers", Context.MODE_PRIVATE), choices)
            }
            val consoles = sourcesText?.let { sourcesRepository.importFromText(it, keepLocalTorrents = true) } ?: 0
            // The sources are committed by now: a failure of the smaller parts (a full disk…) must
            // not hide that, or the restored sources would never be scanned.
            val favouritesDone = runCatching { favourites.forEach { favouriteDao.insertRaw(it) } }.isSuccess
            val downloadsDone = runCatching { downloadHistoryDao.insertMissing(downloads) }.isSuccess
            // Only wanted games this install does not list yet (same title and console) are added.
            runCatching {
                val have = wishlistDao.getAll().map { it.key to it.consoleId }.toSet()
                wishlistDao.upsertAll(wishlist.filter { (it.key to it.consoleId) !in have })
            }
            Summary(restored, consoles, if (favouritesDone) favourites.size else 0, if (downloadsDone) downloads.size else 0, repick, skippedNames.size)
        }
    }

    /** Replaces the settings with the backed-up ones; returns (restored, folders the user must pick again). */
    private suspend fun restoreSettings(settings: List<Pair<String, Any>>, skippedNames: Set<String>): Pair<Int, Int> {
        val granted = context.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet()
        var restored = 0
        var repick = 0
        profiles.withProfileStateLock { context.dataStore.edit { prefs ->
            val currentSkipped = prefs.asMap().filter { (key, value) -> key.name in skippedNames && SettingSchema.compatible(key.name, value) }
            // A folder the backup cannot bring back keeps whatever this install already had.
            val currentFolders = FOLDER_KEYS.associateWith { prefs[stringPreferencesKey(it)] }
            val currentConsoleDirs = prefs[SettingsKeys.CONSOLE_DOWNLOAD_DIRECTORIES].orEmpty()
            // The WebDAV password is not in backups: keep it when the restore leaves the same server and user.
            val davPassword = stringPreferencesKey(CloudSettingKeys.PASSWORD)
            val davUrl = stringPreferencesKey(CloudSettingKeys.URL)
            val davUser = stringPreferencesKey(CloudSettingKeys.USER)
            val currentDavPassword = prefs[davPassword]
            val currentDavLogin = prefs[davUrl] to prefs[davUser]
            // A backup from before the cloud was set up has no cloud settings: those stay as they are.
            val currentDav = prefs.asMap().filter { it.key.name.startsWith("dav_") }
            val backupHasDav = settings.any { it.first.startsWith("dav_") }
            // 4.3.0: the ScreenScraper passwords are not in backups either (same rule as the WebDAV password).
            val currentScreenScraper = prefs.asMap().filter { it.key.name in ScreenScraper.SETTING_TYPES }
            // 4.4.0: the TheGamesDB / SteamGridDB keys and the Twitch client secret neither (the client id may).
            val currentCoverApis = prefs.asMap().filter { it.key.name in CoverApis.SETTING_TYPES }
            prefs.clear()
            currentSkipped.forEach { (key, value) -> prefs.put(key.name, value) }
            settings.forEach { (name, value) ->
                when (name) {
                    in FOLDER_KEYS -> {
                        val uri = value as String
                        if (uri.isEmpty() || uri in granted) {
                            prefs[stringPreferencesKey(name)] = uri
                            restored++
                        } else {
                            repick++
                        }
                    }
                    SettingsKeys.CONSOLE_DOWNLOAD_DIRECTORIES.name -> {
                        @Suppress("UNCHECKED_CAST")
                        val entries = value as Set<String>
                        // A folder inside a granted tree (smart storage's console folders on the SD card) is usable too.
                        val usable = entries.filter { entry ->
                            val uri = entry.substringAfter(':', "")
                            uri in granted || SmartStorage.treeOf(uri)?.let { it in granted } == true
                        }
                        repick += entries.size - usable.size
                        val usableIds = usable.map { it.substringBefore(':') }.toSet()
                        prefs[SettingsKeys.CONSOLE_DOWNLOAD_DIRECTORIES] =
                            usable.toSet() + currentConsoleDirs.filter { it.substringBefore(':') !in usableIds }
                        restored++
                    }
                    else -> {
                        prefs.put(name, value)
                        restored++
                    }
                }
            }
            currentFolders.forEach { (name, value) ->
                val key = stringPreferencesKey(name)
                if (prefs[key].isNullOrEmpty() && !value.isNullOrEmpty()) prefs[key] = value
            }
            if (prefs[SettingsKeys.CONSOLE_DOWNLOAD_DIRECTORIES] == null && currentConsoleDirs.isNotEmpty()) {
                prefs[SettingsKeys.CONSOLE_DOWNLOAD_DIRECTORIES] = currentConsoleDirs
            }
            if (!backupHasDav) currentDav.forEach { (key, value) -> @Suppress("UNCHECKED_CAST") prefs[key as Preferences.Key<Any>] = value }
            if (!currentDavPassword.isNullOrEmpty() &&
                CloudSettingKeys.keepsPassword(currentDavLogin.first, currentDavLogin.second, prefs[davUrl], prefs[davUser])
            ) prefs[davPassword] = currentDavPassword
            val backupHasScreenScraper = settings.any { it.first in ScreenScraper.SETTING_TYPES }
            if (!backupHasScreenScraper) currentScreenScraper.forEach { (key, value) -> @Suppress("UNCHECKED_CAST") prefs[key as Preferences.Key<Any>] = value }
            ScreenScraper.keptSecrets(
                current = currentScreenScraper.entries.associate { it.key.name to (it.value as? String).orEmpty() },
                restored = ScreenScraper.SETTING_TYPES.keys.associateWith { prefs[stringPreferencesKey(it)].orEmpty() },
                backupHasScreenScraper = backupHasScreenScraper
            ).forEach { (name, value) -> prefs[stringPreferencesKey(name)] = value }
            val backupHasIgdbId = settings.any { it.first == CoverApis.IGDB_CLIENT_ID_KEY }
            // A backup without a client id (older, or never set up) keeps the one this install has.
            if (!backupHasIgdbId) currentCoverApis[stringPreferencesKey(CoverApis.IGDB_CLIENT_ID_KEY)]?.let { prefs[stringPreferencesKey(CoverApis.IGDB_CLIENT_ID_KEY)] = it as String }
            CoverApis.keptSecrets(
                current = currentCoverApis.entries.associate { it.key.name to (it.value as? String).orEmpty() },
                restored = CoverApis.SETTING_TYPES.keys.associateWith { prefs[stringPreferencesKey(it)].orEmpty() },
                backupHasIgdbId = backupHasIgdbId
            ).forEach { (name, value) -> prefs[stringPreferencesKey(name)] = value }
            // Restoring must never send the user back through the first-run tour.
            prefs[SettingsKeys.ONBOARDING_DONE] = true
        } }
        return restored to repick
    }

    /** Writes a value decoded by [BackupJson.decodeSetting] under a key of its own type. */
    private fun MutablePreferences.put(name: String, value: Any) {
        @Suppress("UNCHECKED_CAST")
        when (value) {
            is Boolean -> this[booleanPreferencesKey(name)] = value
            is Int -> this[intPreferencesKey(name)] = value
            is Long -> this[longPreferencesKey(name)] = value
            is Float -> this[floatPreferencesKey(name)] = value
            is Double -> this[doublePreferencesKey(name)] = value
            is String -> this[stringPreferencesKey(name)] = value
            is Set<*> -> this[stringSetPreferencesKey(name)] = value as Set<String>
        }
    }

    /** Consoles in a sources document (manufacturer keys starting with `_` are metadata). */
    private fun consoleCount(sources: JsonObject): Int =
        sources.entrySet().sumOf { (_, m) -> if (m.isJsonObject) m.asJsonObject.entrySet().count { (k, v) -> !k.startsWith("_") && v.isJsonObject } else 0 }

    companion object {
        /** The largest backup text a restore accepts; a cloud backup above it is not uploaded. */
        const val MAX_BACKUP_BYTES = 8 * 1024 * 1024
        private val FOLDER_KEYS = setOf(
            SettingsKeys.DOWNLOAD_DIRECTORY.name,
            SettingsKeys.ESDE_DIRECTORY.name,
            SettingsKeys.IISU_DIRECTORY.name,
            SettingsKeys.SAVE_SYNC_SAVES_DIR.name,
            SettingsKeys.SAVE_SYNC_STATES_DIR.name,
            "bios_dir", "auto_backup_dir", "retroarch_thumbnails_dir", "smart_storage_sd_uri"
        )
    }
}
