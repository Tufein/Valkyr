package com.tufein.valkyr.data.service

import android.util.Log
import android.util.LruCache
import com.tufein.valkyr.data.local.dao.DownloadableFileDao
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.util.RommGameInfo
import com.tufein.valkyr.util.RommProps
import com.tufein.valkyr.util.RommSource
import com.tufein.valkyr.util.RommUserProps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RommGameService"
/** A game's RomM details are read again after this long (play status may change on another device). */
private const val FRESH_MS = 2L * 60 * 1000

/**
 * A library game as RomM knows it (5.0 details dialog): its metadata and screenshots
 * (`GET /api/roms/{id}`) and the account's play status and rating, written back with
 * `PUT /api/roms/{id}/props`. Answers are kept in memory for a short while.
 */
@Singleton
class RommGameService @Inject constructor(
    private val rommClient: RommClient,
    private val rommLibrary: RommLibraryService,
    private val fileDao: DownloadableFileDao,
    private val settingsRepository: SettingsRepository,
    private val offlineQueue: OfflineQueueService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = LruCache<Int, Pair<Long, RommGameInfo>>(48)

    /** RomM URL and token are set. */
    suspend fun isConfigured(): Boolean =
        rommClient.configuredBaseUrl().isNotEmpty() && settingsRepository.rommToken.first().isNotBlank()

    /**
     * The RomM id of a library game: from the server's game list (needs "Mark games in RomM"),
     * else from the row itself when it comes from this RomM server (a `romm://` source).
     */
    suspend fun romIdFor(consoleId: String, fileName: String): Int? = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext null
        rommLibrary.gameFor(consoleId, fileName)?.romId?.let { return@withContext it }
        val base = rommClient.configuredBaseUrl()
        runCatching { fileDao.filesByFileNames(listOf(fileName)) }.getOrDefault(emptyList())
            .firstOrNull { it.consoleId == consoleId && RommSource.isDownloadFrom(base, it.downloadUrl) }
            ?.let { RommSource.romIdOf(it.downloadUrl) }
    }

    /** What the cache holds for [romId] (any age), for an instant first draw. */
    fun cached(romId: Int): RommGameInfo? = cache.get(romId)?.second

    /** The game's details, from the cache when fresh (unless [refresh]). Throws when the server cannot be read. */
    suspend fun details(romId: Int, refresh: Boolean = false): RommGameInfo {
        cache.get(romId)?.let { (at, info) -> if (!refresh && System.currentTimeMillis() - at < FRESH_MS) return info }
        val info = rommClient.rom(romId)
        cache.put(romId, System.currentTimeMillis() to info)
        return info
    }

    /**
     * Writes [changes] (see [RommProps.changes]) and returns the play data the server now holds
     * ([expected] when its answer could not be read). Throws on failure; nothing is cached then.
     */
    suspend fun updateProps(romId: Int, changes: Map<String, Any?>, expected: RommUserProps): RommUserProps {
        if (changes.isEmpty()) return expected
        // Under the queue lock, and older waiting values of these fields are dropped once it succeeded.
        val saved = offlineQueue.writeThrough(romId, changes.keys) { rommClient.updateRomProps(romId, changes) } ?: expected
        cache.get(romId)?.let { (at, info) -> cache.put(romId, at to info.copy(props = saved)) }
        return saved
    }

    /** Keeps [changes] until the connection is back (see [OfflineQueueService]); the cached details show them meanwhile. */
    fun queueProps(romId: Int, changes: Map<String, Any?>, expected: RommUserProps) {
        offlineQueue.enqueueRommProps(romId, changes)
        cache.get(romId)?.let { (at, info) -> cache.put(romId, at to info.copy(props = expected)) }
    }

    /** [updateProps] that outlives the screen (the dialog closed before a pending change went out). */
    fun updatePropsLater(romId: Int, changes: Map<String, Any?>, expected: RommUserProps) {
        if (changes.isEmpty()) return
        scope.launch {
            runCatching { updateProps(romId, changes, expected) }
                .onFailure {
                    Log.w(TAG, "Could not save the play status of ROM $romId: ${it.javaClass.simpleName}")
                    if (rommErrorKind(it) == RommErrorKind.UNREACHABLE) offlineQueue.enqueueRommProps(romId, changes)
                }
        }
    }
}
