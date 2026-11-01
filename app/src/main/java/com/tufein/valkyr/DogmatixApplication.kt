package com.tufein.valkyr

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import com.tufein.valkyr.data.repository.DownloadableFileRepository
import com.tufein.valkyr.data.repository.SourcesRepository
import com.tufein.valkyr.data.repository.WishlistRepository
import com.tufein.valkyr.data.service.AppShortcutService
import com.tufein.valkyr.data.service.AutoBackupScheduler
import com.tufein.valkyr.data.service.AutoScanScheduler
import com.tufein.valkyr.data.service.OfflineCollectionsService
import com.tufein.valkyr.data.service.BandwidthLimiter
import com.tufein.valkyr.data.service.DownloadLog
import com.tufein.valkyr.data.service.PostDownloadService
import com.tufein.valkyr.data.service.QueueSummaryService
import com.tufein.valkyr.data.service.RommCollectionsService
import com.tufein.valkyr.data.service.RommLibraryService
import com.tufein.valkyr.data.service.RommTrustService
import com.tufein.valkyr.data.service.RommUploadService
import com.tufein.valkyr.data.service.SaveSyncScheduler
import com.tufein.valkyr.data.service.VersionCheckerService
import com.tufein.valkyr.data.service.WeeklyDigestScheduler
import com.tufein.valkyr.util.ConsoleAliasRegistry
import com.tufein.valkyr.util.CrashLog
import com.tufein.valkyr.util.PerfLog
import com.tufein.valkyr.widget.WidgetUpdater
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import net.sf.sevenzipjbinding.SevenZip
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class DogmatixApplication : Application(), ImageLoaderFactory {

    /** 5.0: the shared image loader (covers), handed to Coil for every AsyncImage. */
    @Inject
    lateinit var imageLoader: dagger.Lazy<ImageLoader>

    override fun newImageLoader(): ImageLoader = imageLoader.get()

    
    @Inject
    lateinit var versionCheckerService: VersionCheckerService

    @Inject
    lateinit var downloadableFileRepository: DownloadableFileRepository

    @Inject
    lateinit var sourcesRepository: SourcesRepository

    /** Injected so it starts watching finished downloads from the first one. */
    @Inject
    lateinit var rommUploadService: RommUploadService

    /** Built in [startDeferred] (it pulls the RomM, download and database services); manuals can follow a finished download from the first one (6.0). */
    @Inject
    lateinit var manualService: dagger.Lazy<com.tufein.valkyr.data.service.ManualService>

    /** Built in [startDeferred] on a background thread; changes made offline are sent as soon as the connection is back (6.0). */
    @Inject
    lateinit var offlineQueueService: dagger.Lazy<com.tufein.valkyr.data.service.OfflineQueueService>

    /** Injected so the pinned RomM certificate is active from the first request. */
    @Inject
    lateinit var rommTrustService: RommTrustService

    /** Injected so the marks of games on the RomM server are read from the first launch. */
    @Inject
    lateinit var rommLibraryService: dagger.Lazy<RommLibraryService>

    /** Injected so the favourites kept in step with RomM (when switched on) merge from the start. */
    @Inject
    lateinit var rommCollectionsService: dagger.Lazy<RommCollectionsService>

    /** Injected so the background save sync job follows its settings from the start. */
    @Inject
    lateinit var saveSyncScheduler: dagger.Lazy<SaveSyncScheduler>

    /** Injected so the background poll for installs pushed from RomM follows its settings from the start. */
    @Inject
    lateinit var rommRemoteInstallScheduler: dagger.Lazy<com.tufein.valkyr.data.service.RommRemoteInstallScheduler>

    /** Injected so finished scans are checked against the wishlist from the start. */
    @Inject
    lateinit var wishlistRepository: dagger.Lazy<WishlistRepository>

    /** Injected so the background source scan follows its settings from the start. */
    @Inject
    lateinit var autoScanScheduler: dagger.Lazy<AutoScanScheduler>

    /** Injected so collections kept on the device are looked at after every scan from the start. */
    @Inject
    lateinit var offlineCollectionsService: dagger.Lazy<OfflineCollectionsService>

    /** Injected so the weekly digest job follows its setting from the start. */
    @Inject
    lateinit var weeklyDigestScheduler: dagger.Lazy<WeeklyDigestScheduler>

    /** Injected so the smart storage job follows its setting from the start. */
    @Inject
    lateinit var smartStorageScheduler: dagger.Lazy<com.tufein.valkyr.data.service.SmartStorageScheduler>

    /** Injected so the speed limit (and its night exception) applies from the first download. */
    @Inject
    lateinit var bandwidthLimiter: BandwidthLimiter

    /** Injected so the home-screen widget follows downloads and scans from the start. */
    @Inject
    lateinit var widgetUpdater: dagger.Lazy<WidgetUpdater>

    /** Injected so finished downloads go into the statistics log from the first one. */
    @Inject
    lateinit var downloadLog: DownloadLog

    /** Injected so finished and failed downloads reach the action history from the first one. */
    @Inject
    lateinit var downloadActionRecorder: com.tufein.valkyr.data.service.DownloadActionRecorder

    /** Injected so the weekly automatic backup follows its settings from the start. */
    @Inject
    lateinit var autoBackupScheduler: dagger.Lazy<AutoBackupScheduler>

    /** App shortcuts (consoles, saved views, Downloads) for launchers and frontends such as Cocoon. */
    @Inject
    lateinit var appShortcutService: AppShortcutService

    /** After a download: the .m3u of a multi-disc game, cover and description for ES-DE. */
    @Inject
    lateinit var postDownloadService: PostDownloadService

    /** One notification with how a run of downloads ended. */
    @Inject
    lateinit var queueSummaryService: QueueSummaryService

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val deferredStarted = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        PerfLog.mark("app_create")
        CrashLog.install(this)
        if (BuildConfig.DEBUG) PerfLog.enableStrictMode()
        appShortcutService.start()
        postDownloadService.start()
        queueSummaryService.start()

        createNotificationChannel()

        applicationScope.launch {
            clearStaleCache()

            // Load the native 7-zip library (blocking — must run on IO thread)
            try {
                SevenZip.initSevenZipFromPlatformJAR()
                Log.d("DogmatixApplication", "7-Zip native library loaded")
            } catch (e: Exception) {
                Log.e("DogmatixApplication", "Failed to load 7-Zip native library: ${e.message}")
            }
        }

        // Check for updates on app startup
        applicationScope.launch {
            versionCheckerService.checkForUpdates(this@DogmatixApplication)
        }

        // Short names / folder aliases configured per console, kept in sync for the static helpers
        applicationScope.launch {
            sourcesRepository.aliasOverrides.collect { ConsoleAliasRegistry.overrides = it }
        }

        // Files indexed before the search key column existed need it computed once
        applicationScope.launch {
            try {
                downloadableFileRepository.backfillSearchKeys()
            } catch (e: Exception) {
                Log.e("DogmatixApplication", "Search key backfill failed: ${e.message}")
            }
        }

        // Services that only follow settings (schedulers, RomM marks, widget) are built after the
        // first frame; if no screen ever draws (a worker or the download service started the
        // process) they still start after a few seconds.
        applicationScope.launch {
            delay(DEFERRED_FALLBACK_MS)
            startDeferred()
        }
        PerfLog.mark("app_ready")
    }

    /**
     * Builds the services that were injected only so they start watching their settings: none of
     * them is needed for the first frame. Called by the activity after its first draw (or by the
     * fallback timer); runs once, on a background thread (Hilt singletons are thread safe).
     */
    fun startDeferred() {
        if (!deferredStarted.compareAndSet(false, true)) return
        applicationScope.launch {
            PerfLog.timed("startup.deferred") {
                runCatching {
                    // The order of the original eager injection.
                    rommLibraryService.get()
                    rommCollectionsService.get()
                    saveSyncScheduler.get()
                    rommRemoteInstallScheduler.get()
                    wishlistRepository.get()
                    autoScanScheduler.get()
                    offlineCollectionsService.get()
                    weeklyDigestScheduler.get()
                    smartStorageScheduler.get()
                    widgetUpdater.get()
                    autoBackupScheduler.get()
                    manualService.get()
                    // Building it starts the collectors and a first flush of what waits.
                    offlineQueueService.get()
                }.onFailure { Log.e("DogmatixApplication", "Deferred start failed: ${it.message}") }
            }
            PerfLog.mark("deferred_ready")
        }
    }

    /** The app came to the foreground: send the changes that wait for a connection (a no-op when none wait). */
    fun flushOfflineQueue() {
        applicationScope.launch { runCatching { offlineQueueService.get().flush() } }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            DOWNLOAD_CHANNEL_ID,
            getString(R.string.notification_channel_downloads),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_downloads_desc)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
        manager.createNotificationChannel(
            NotificationChannel(SYNC_CHANNEL_ID, getString(R.string.notification_channel_sync), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = getString(R.string.notification_channel_sync_desc)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(SCAN_CHANNEL_ID, getString(R.string.notification_channel_scan), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = getString(R.string.notification_channel_scan_desc)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(WISHLIST_CHANNEL_ID, getString(R.string.notification_channel_wishlist), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = getString(R.string.notification_channel_wishlist_desc)
            }
        )
    }

    /**
     * Deletes cache directories left over from downloads that were interrupted before
     * they could finish copying to the user's storage and clean up after themselves.
     * Safe to run at startup because no downloads are active yet.
     */
    private fun clearStaleCache() {
        listOf("extraction_temp", "torrent_data").forEach { dir ->
            val cacheDir = File(cacheDir, dir)
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
                Log.d("DogmatixApplication", "Cleared stale cache: $dir")
            }
        }
    }

    companion object {
        private const val DEFERRED_FALLBACK_MS = 4_000L
        const val DOWNLOAD_CHANNEL_ID = "download_channel"
        const val SYNC_CHANNEL_ID = "sync_channel"
        const val WISHLIST_CHANNEL_ID = "wishlist_channel"
        const val SCAN_CHANNEL_ID = "scan_channel"
    }
}