package com.tufein.valkyr.data.repository

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tufein.valkyr.DogmatixApplication
import com.tufein.valkyr.MainActivity
import com.tufein.valkyr.R
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.local.WishlistAlertSettings
import com.tufein.valkyr.data.local.dao.DownloadableFileDao
import com.tufein.valkyr.data.local.dao.WishlistDao
import com.tufein.valkyr.data.local.entity.WishlistEntity
import com.tufein.valkyr.data.service.DownloadService
import com.tufein.valkyr.data.service.LibraryIndexService
import com.tufein.valkyr.data.service.RommLibraryService
import com.tufein.valkyr.data.state.RescanStateHolder
import com.tufein.valkyr.util.GameTitleCleaner
import com.tufein.valkyr.util.LibraryKeys
import com.tufein.valkyr.util.VersionPicker
import com.tufein.valkyr.util.WishlistMatch
import com.tufein.valkyr.util.WishlistRommAlerts
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import com.tufein.valkyr.data.local.VersionPreferenceSettings
import com.tufein.valkyr.data.service.VersionPreferenceService
import com.tufein.valkyr.util.VersionPreference
import com.tufein.valkyr.util.WishlistShare

/** A wishlist entry, how many library rows match it right now, and whether the game is already had. */
data class WishlistStatus(
    val item: WishlistEntity,
    val matches: Int,
    val state: WishlistMatch.State = WishlistMatch.state(false, false, matches)
)

/**
 * Games the user wants that no source lists yet. After every source scan the library is searched
 * for each wanted title; the first time one turns up, the user is told (once) with a notification.
 */
@Singleton
class WishlistRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: WishlistDao,
    private val fileDao: DownloadableFileDao,
    rescanStateHolder: RescanStateHolder,
    private val appSettings: AppSettings,
    private val versionPreference: VersionPreferenceService,
    private val downloadService: DownloadService,
    private val libraryIndex: dagger.Lazy<LibraryIndexService>,
    private val rommLibrary: dagger.Lazy<RommLibraryService>,
    private val alertSettings: WishlistAlertSettings,
    private val versionSettings: VersionPreferenceSettings
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val items: Flow<List<WishlistEntity>> = dao.observeAll()

    /** Moves when what the device or the RomM server has changes (the "have" part of [status]). */
    val haveChanges: Flow<Any> = kotlinx.coroutines.flow.flow {
        emitAll(merge(libraryIndex.get().ownedKeys, rommLibrary.get().keys))
    }

    init {
        // A finished source scan (the time moves) may have brought a wanted game into the library.
        scope.launch { rescanStateHolder.lastRescanTime.drop(1).collect { checkAndNotify() } }
        // 6.0: the RomM game list changed (a library refresh): a wish may be on the server now.
        scope.launch {
            runCatching {
                rommLibrary.get().keys.filter { it.isNotEmpty() }.collect { checkRomm(it) }
            }
        }
    }

    private val rommLock = Mutex()

    /**
     * Announces (once) the wishes that are on the RomM server now and not on the device. The
     * first check only records what the server already has, so nobody gets a burst of old news.
     */
    suspend fun checkRomm(keys: Set<String> = rommLibrary.get().keys.value): List<WishlistEntity> = rommLock.withLock {
        if (keys.isEmpty()) return emptyList()
        val wishes = dao.getAll()
        val announced = alertSettings.announced()
        val owned = libraryIndex.get().ownedKeys.value
        val pending = WishlistRommAlerts.pending(wishes, keys, announced.orEmpty()) { wish ->
            WishlistMatch.onDevice(wish.title, wish.consoleId?.let { LibraryKeys.scopesFor(it) }, owned)
        }
        if (announced == null) {
            alertSettings.add(pending.map { WishlistRommAlerts.announceKey(it) } + "")
            return emptyList()
        }
        if (pending.isEmpty()) return emptyList()
        alertSettings.add(pending.map { WishlistRommAlerts.announceKey(it) })
        notifyRomm(pending)
        pending
    }

    suspend fun add(title: String, consoleId: String?): Boolean {
        val clean = title.trim()
        if (clean.length < 2) return false
        val entry = WishlistEntity(title = clean, consoleId = consoleId?.takeIf { it.isNotBlank() })
        if (dao.getAll().any { it.key == entry.key && it.consoleId == entry.consoleId }) return false
        dao.upsert(entry)
        // Already on the server: the list shows it, no alert for it later.
        runCatching {
            if (WishlistMatch.inRomm(entry.title, entry.consoleId, rommLibrary.get().keys.value)) {
                alertSettings.add(listOf(WishlistRommAlerts.announceKey(entry)))
            }
        }
        return true
    }

    suspend fun remove(id: Long) = dao.delete(id)

    /** The wishlist as a file's text (see [WishlistShare]). */
    suspend fun exportText(): String = WishlistShare.export(dao.getAll())

    /** Adds the wishes in [text] that are not on the list yet; null when it is not a wishlist file. */
    suspend fun importText(text: String): Int? {
        val wishes = WishlistShare.parse(text) ?: return null
        return wishes.count { add(it.title, it.consoleId) }
    }

    suspend fun statuses(): List<WishlistStatus> =
        dao.getAll().sortedByDescending { it.addedAt }.map { status(it) }

    /** Library matches plus whether the device or the RomM server already has the game. */
    suspend fun status(item: WishlistEntity): WishlistStatus {
        val matches = matches(item)
        val owned = libraryIndex.get().ownedKeys.value
        val scopes = item.consoleId?.let { LibraryKeys.scopesFor(it) }
        val onDevice = WishlistMatch.onDevice(item.title, scopes, owned)
        val inRomm = !onDevice && WishlistMatch.inRomm(item.title, item.consoleId, rommLibrary.get().keys.value)
        return WishlistStatus(item, matches, WishlistMatch.state(onDevice, inRomm, matches))
    }

    suspend fun matches(item: WishlistEntity): Int = if (item.key.isEmpty()) 0 else fileDao.countMatching(item.key, item.consoleId)

    /** Marks and announces wanted games that are in the library now; returns them. */
    suspend fun checkAndNotify(): List<WishlistEntity> {
        // A game already on the device or on the RomM server is not announced nor downloaded again.
        val found = dao.getAll().filter { it.notifiedAt == null && matches(it) > 0 }
            .filter { WishlistMatch.stillWanted(status(it).state) }
        if (found.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        found.forEach { dao.markNotified(it.id, now) }
        notify(found)
        if (appSettings.wishlistAutoDownload.first()) autoDownload(found)
        return found
    }

    /**
     * Downloads the best version of each newly found wanted game (by the user's favourite
     * languages), unless a version is already on the device or downloading. One per console.
     */
    suspend fun autoDownload(found: List<WishlistEntity>): Int {
        val versionPrefs = versionSettings.snapshot()
        val preferences = versionPreference.snapshot()
        val index = libraryIndex.get()
        val picks = found.flatMap { item ->
            fileDao.filesMatching(item.key, item.consoleId)
                .filter { GameTitleCleaner.containsAllWords(item.title, it.fileName) }
                .groupBy { it.consoleId }.mapNotNull { (_, files) ->
                if (files.any { index.isOwned(it) || downloadService.isActive(it.fileName) }) return@mapNotNull null
                val best = VersionPreference.pick(
                    files.map { VersionPicker.Candidate(it.fileName, it.fileName, fileDao.tagsOf(it.id), it.fileSize) },
                    versionPrefs.of(files.first().consoleId), preferences.preferred(files.first().consoleId, files.first().fileName)
                ) ?: return@mapNotNull null
                files.first { it.fileName == best.id }
            }
        }.distinctBy { it.fileName }
        if (picks.isNotEmpty()) downloadService.startDownloads(picks)
        return picks.size
    }

    private fun notify(found: List<WishlistEntity>) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val titles = found.take(3).joinToString(", ") { it.title } + if (found.size > 3) " …" else ""
        val notification = NotificationCompat.Builder(context, DogmatixApplication.WISHLIST_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_star)
            .setContentTitle(context.resources.getQuantityString(R.plurals.wishlist_found_title, found.size, found.size))
            .setContentText(titles)
            .setStyle(NotificationCompat.BigTextStyle().bigText(titles))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(WISHLIST_NOTIFICATION_ID, notification)
    }

    private fun notifyRomm(found: List<WishlistEntity>) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val titles = found.take(3).joinToString(", ") { it.title } + if (found.size > 3) " …" else ""
        val notification = NotificationCompat.Builder(context, DogmatixApplication.WISHLIST_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_star)
            .setContentTitle(context.getString(R.string.share6_romm_now_title))
            .setContentText(titles)
            .setStyle(NotificationCompat.BigTextStyle().bigText(titles))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(WISHLIST_ROMM_NOTIFICATION_ID, notification)
    }

    private companion object {
        const val WISHLIST_NOTIFICATION_ID = 4203
        const val WISHLIST_ROMM_NOTIFICATION_ID = 4204
    }
}
