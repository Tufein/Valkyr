package com.tufein.valkyr

import android.content.Context
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.local.dao.ConsoleDao
import com.tufein.valkyr.data.local.dao.DownloadHistoryDao
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.data.service.*
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Debug-only reconstruction with isolated history and tracker; exercises the real startup path. */
class StorageRecoveryServiceFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val extractor: ArchiveExtractorService,
    private val speed: DownloadSpeedController,
    private val bandwidth: BandwidthLimiter,
    private val http: DownloadHttpClient,
    private val files: DownloadFileManager,
    private val torrents: TorrentDownloadService,
    private val handles: TorrentHandleRegistry,
    private val torbox: TorBoxClient,
    private val debrid: RealDebridClient,
    private val romm: RommClient,
    private val gate: DownloadGate,
    private val conditions: ItemConditionGate,
    private val consoles: ConsoleDao,
    private val app: AppSettings,
    private val partials: PartialDownloads,
    private val dat: DatService,
    private val sources: SourceTrackService,
    private val moveGate: StorageMoveGate,
    private val packages: GamePackageService,
    private val storage: StorageAvailabilityService,
    private val holds: StorageDownloadHolds,
    private val trackers: com.tufein.valkyr.data.local.TorrentTrackerSettings
) {
    fun create(history: DownloadHistoryDao): DownloadService = DownloadService(context, settings, extractor,
        speed, bandwidth, http, DownloadProgressTracker(history), files, torrents, handles, history, torbox,
        debrid, romm, gate, conditions, consoles, app, partials, dat, sources, moveGate, packages, storage, holds, trackers)
}
