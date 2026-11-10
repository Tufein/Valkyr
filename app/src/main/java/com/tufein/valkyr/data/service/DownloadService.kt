package com.tufein.valkyr.data.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import com.tufein.valkyr.DogmatixApplication
import com.tufein.valkyr.R
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.local.dao.ConsoleDao
import com.tufein.valkyr.data.local.dao.DownloadHistoryDao
import com.tufein.valkyr.data.local.entity.DownloadHistoryEntity
import com.tufein.valkyr.data.local.entity.DownloadableFileEntity
import com.tufein.valkyr.data.model.DebridProvider
import com.tufein.valkyr.data.model.DownloadFailure
import com.tufein.valkyr.data.model.DownloadFailureCategory
import com.tufein.valkyr.data.model.DownloadItemModel
import com.tufein.valkyr.data.model.DownloadStatus
import com.tufein.valkyr.data.model.DownloadSourceChoices
import com.tufein.valkyr.data.model.DownloadSourceOption
import com.tufein.valkyr.data.model.SourceChangeResult
import com.tufein.valkyr.util.DownloadSourcePolicy
import com.tufein.valkyr.data.model.PendingAutoRetry
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.util.ArchiveUtils
import com.tufein.valkyr.util.AutoRetry
import com.tufein.valkyr.util.DownloadAttemptRegistry
import com.tufein.valkyr.util.DatStatus
import com.tufein.valkyr.util.ArchiveExtractionUtils
import com.tufein.valkyr.util.Constants
import com.tufein.valkyr.util.DownloadCondition
import com.tufein.valkyr.util.DownloadQueue
import com.tufein.valkyr.util.DownloadStreams
import com.tufein.valkyr.util.DownloadFailures
import com.tufein.valkyr.util.DownloadExtractionException
import com.tufein.valkyr.util.DownloadVerificationException
import com.tufein.valkyr.util.WaitInfo
import com.tufein.valkyr.util.FileParsingUtils
import com.tufein.valkyr.util.MirrorUrls
import com.tufein.valkyr.util.ResumePlan
import com.tufein.valkyr.util.RommSource
import com.tufein.valkyr.util.SourceRanking
import com.tufein.valkyr.util.DebridMatcher
import com.tufein.valkyr.util.Checksums
import com.tufein.valkyr.util.HoldParking
import com.tufein.valkyr.util.PartialOwner
import com.tufein.valkyr.util.SourceFailures
import com.tufein.valkyr.util.StorageException
import com.tufein.valkyr.util.StorageAccessException
import com.tufein.valkyr.util.ExpectedHash
import com.tufein.valkyr.util.SourcesJson
import com.tufein.valkyr.util.StorageHelper
import com.tufein.valkyr.util.VerifyState
import com.tufein.valkyr.util.VerifiedCopy
import java.security.MessageDigest
import java.security.DigestOutputStream
import java.net.URI
import java.net.HttpURLConnection
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import dagger.hilt.android.qualifiers.ApplicationContext
import com.tufein.valkyr.util.QueueActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow

private const val TAG = "DownloadService"
/** Upper bound for an uncached torrent to be fetched by the debrid service before we give up. */
internal const val DEBRID_MAX_WAIT_MS = 6 * 60 * 60 * 1000L
internal val DEVICE_FAILURES = setOf(
    DownloadFailureCategory.STORAGE_FULL,
    DownloadFailureCategory.STORAGE_PERMISSION,
    DownloadFailureCategory.STORAGE_WRITE,
    DownloadFailureCategory.ARCHIVE_UNSAFE,
    DownloadFailureCategory.ARCHIVE_CONFLICT,
    DownloadFailureCategory.EXTRACTION
)

/** A download stopped because free space fell below the limit set in Settings; it can be retried. */
class LowStorageException(fileName: String) : Exception("Not enough free space for $fileName")

data class DownloadedPackageLocation(val rootUri: String, val subPath: String, val paths: List<String>)

@Singleton
class DownloadService @Inject constructor(
    @param:ApplicationContext internal val context: Context,
    internal val settingsRepository: SettingsRepository,
    internal val archiveExtractorService: ArchiveExtractorService,
    internal val downloadSpeedController: DownloadSpeedController,
    internal val bandwidthLimiter: BandwidthLimiter,
    internal val downloadHttpClient: DownloadHttpClient,
    internal val downloadProgressTracker: DownloadProgressTracker,
    internal val downloadFileManager: DownloadFileManager,
    internal val torrentDownloadService: TorrentDownloadService,
    internal val torrentHandleRegistry: TorrentHandleRegistry,
    internal val historyDao: DownloadHistoryDao,
    internal val torBoxClient: TorBoxClient,
    internal val realDebridClient: RealDebridClient,
    internal val rommClient: RommClient,
    internal val downloadGate: DownloadGate,
    internal val conditionGate: ItemConditionGate,
    internal val consoleDao: ConsoleDao,
    internal val appSettings: AppSettings,
    internal val partials: PartialDownloads,
    internal val datService: DatService,
    internal val sourceTrack: SourceTrackService,
    internal val moveGate: StorageMoveGate,
    internal val gamePackages: GamePackageService,
    internal val storageAvailability: StorageAvailabilityService,
    internal val storageHolds: StorageDownloadHolds
) {
    val downloads: StateFlow<List<DownloadItemModel>> = downloadProgressTracker.downloads

    /**
     * File names whose download is really finished and on disk (after copy / extraction).
     * Torrents enter COPYING after their network transfer; COMPLETED is published only once
     * the copy or extraction worker has successfully put the files in their destination.
     */
    // Room for a whole batch: tryEmit drops the name when the buffer is full, and the after-download
    // steps (covers, playlists, RomM upload, the log) are slower than small games finish.
    internal val _finished = MutableSharedFlow<String>(extraBufferCapacity = 10_000)
    val finished: SharedFlow<String> = _finished.asSharedFlow()

    internal val _waiting = MutableStateFlow<Set<String>>(emptySet())
    /** Downloads held back by the schedule (Wi-Fi / charger / night); see [DownloadGate]. */
    val waitingFiles: StateFlow<Set<String>> = _waiting.asStateFlow()
    val gate: DownloadGate get() = downloadGate

    /** Per-download conditions ("Download when...") set right now, by file name. */
    val itemConditions: StateFlow<Map<String, DownloadCondition>> get() = conditionGate.conditions
    /** The per-download conditions that are not met yet and what is missing. */
    val itemWaits: StateFlow<Map<String, WaitInfo>> get() = conditionGate.unmet

    /** Downloads that passed their condition and are about to run or running: a new condition would be meaningless. */
    internal val proceeding = ConcurrentHashMap.newKeySet<String>()

    /**
     * Gives downloads that have not started yet (waiting for a slot, for the global rules or for a
     * condition) their own condition, or with null removes it ("Start now": a waiting download goes
     * on at once). Downloads already transferring are left alone. Cheap enough for the UI thread;
     * the persisting happens in the background.
     */
    fun setCondition(fileNames: Collection<String>, condition: DownloadCondition?) {
        val eligible = fileNames.filter { downloadEntities.containsKey(it) && it !in proceeding }
        conditionGate.setAll(eligible, condition)
    }

    internal val _verification = MutableStateFlow<Map<String, VerifyState>>(emptyMap())
    /** Result of checking a finished download against the hash its source published. */
    val verification: StateFlow<Map<String, VerifyState>> = _verification.asStateFlow()
    /** Hash the server announced in the response headers of the transfer in progress, per file. */
    internal val headerHashes = ConcurrentHashMap<String, String>()

    internal val downloadJobs = ConcurrentHashMap<String, Job>()
    internal val cleanupJobs = ConcurrentHashMap<String, Job>()
    internal val attempts = DownloadAttemptRegistry()
    internal val retryOwners = HashMap<String, DownloadAttemptRegistry.Attempt>()
    internal val _pendingAutoRetries = MutableStateFlow<Map<String, PendingAutoRetry>>(emptyMap())
    /** Timers waiting for another attempt. Deadlines use elapsed realtime and live only in this process. */
    val pendingAutoRetries: StateFlow<Map<String, PendingAutoRetry>> = _pendingAutoRetries.asStateFlow()
    internal val sourceSelections = ConcurrentHashMap<String, CompletableDeferred<DownloadableFileEntity>>()
    /** The download slots and the order of what waits for one (the user can reorder it). */
    internal val queue = DownloadQueue(3)

    /** Downloads waiting for a free slot, first to start first. */
    val queued: StateFlow<List<String>> = queue.waiting

    fun moveUp(fileName: String) = queue.moveUp(fileName)
    fun moveDown(fileName: String) = queue.moveDown(fileName)
    fun moveToFront(fileName: String) = queue.moveToFront(fileName)
    fun moveToFront(fileNames: Collection<String>) = queue.moveToFront(fileNames)
    internal val downloadEntities = ConcurrentHashMap<String, DownloadableFileEntity>()
    internal val extractedFilesMap = ConcurrentHashMap<String, DownloadedPackageLocation>()
    /** Debrid client + torrent id per file being fetched through the debrid route (see [performDebridDownload]). */
    internal val debridTorrents = ConcurrentHashMap<String, Pair<DebridClient, String>>()

    /** True while [fileName] goes through a debrid service (7.5 power rules let those finish instead of parking them). */
    fun isDebrid(fileName: String): Boolean = fileName in viaDebrid || debridTorrents.containsKey(fileName)
    /** Downloads that took the debrid route (set before the torrent is added there). */
    internal val viaDebrid = ConcurrentHashMap.newKeySet<String>()
    /** Files whose job is being cancelled by a pause (they land on PAUSED instead of STOPPED). */
    internal val pausingFiles = ConcurrentHashMap.newKeySet<String>()

    /** True while a pause of [fileName] has not landed yet. */
    fun isPausing(fileName: String): Boolean = fileName in pausingFiles

    /**
     * Downloads that are really moving data (or a torrent fetching it): past their slot, the schedule
     * and their condition. Rows that only wait are DOWNLOADING too, but are not in here.
     */
    internal val transferring = ConcurrentHashMap.newKeySet<String>()

    /** True while [fileName] is transferring (see [transferring]). */
    fun isTransferring(fileName: String): Boolean = fileName in transferring

    /** Web transfers whose partial file is kept and can be continued with a `Range` request (see [HoldParking.webResumable]). */
    internal val parkable = ConcurrentHashMap.newKeySet<String>()

    /**
     * True when pausing [fileName] now loses nothing: a torrent (cache and handle stay), or a web
     * download whose partial is noted and continued by its server. Debrid downloads are left to finish.
     */
    fun canParkSafely(fileName: String): Boolean {
        val entity = downloadEntities[fileName] ?: return false
        if (isDebrid(fileName)) return false
        return entity.isTorrent || fileName in parkable
    }

    /** Torrents whose files could not be moved into place: a local problem, not the source's. */
    internal val localFailures = ConcurrentHashMap.newKeySet<String>()

    internal val userActionListeners = CopyOnWriteArrayList<(String) -> Unit>()

    /** [listener] hears the file name whenever the user pauses, resumes, stops or removes a download. */
    fun addUserActionListener(listener: (String) -> Unit) { userActionListeners += listener }

    internal fun userActed(fileName: String) = synchronized(startLock) {
        attempts.invalidate(fileName)
        clearPendingRetry(fileName)
        queue.forgetOrder(fileName)
        userActionListeners.forEach { it(fileName) }
    }

    // Startup collectors can handle stored settings before the rest of the constructor finishes.
    internal val startLock = Any()

    // Single supervised scope for all internal coroutines — tied to this singleton's lifetime
    // so jobs are not orphaned if the service is destroyed.
    internal val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Downloads interrupted by process death, oldest first (filled by [restoreHistory]). */
    internal val interrupted = CopyOnWriteArrayList<String>()
    internal val restored = CompletableDeferred<Unit>()
    internal val requeueDone = AtomicBoolean(false)
    // Hashing must not grow without a bound as small files finish during a bulk download.
    internal val verificationSlots = Semaphore(1)

    /** Operational storage/network failures in housekeeping must not reach Android's crash handler. */
    internal fun launchBackground(operation: String, block: suspend () -> Unit): Job = serviceScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$operation failed: ${e.message}", e)
        }
    }

    init {
        launchBackground("Download slot settings") {
            settingsRepository.concurrentDownloads.collect { max -> queue.setSlots(max) }
        }
        launchBackground("Per-server settings") { appSettings.perServerLimit.collect { queue.setPerHost(it) } }
        launchBackground("Automatic retry settings") {
            appSettings.autoRetryFailed.distinctUntilChanged().collect { enabled ->
                if (!enabled) synchronized(startLock) {
                    // Turning the preference off cancels the timer itself: it cannot later switch
                    // sources, revive a row or clear a newer attempt's feedback.
                    retryOwners.keys.toList().forEach { name ->
                        attempts.invalidate(name)
                        clearPendingRetry(name)
                    }
                }
            }
        }
        launchBackground("Download history restore") { restoreHistory() }
    }

    /** Returns once the Downloads list of the previous run is back (or could not be read). */
    suspend fun awaitRestored() = restored.await()

    /**
     * Puts the downloads that were interrupted by the app closing back in the queue (once per
     * run, when the user opens the app and *Settings → Continue the queue after a restart* is on).
     * Returns how many.
     */
    suspend fun requeueInterrupted(): Int {
        restored.await()
        if (!requeueDone.compareAndSet(false, true) || !appSettings.requeueAfterRestart.first()) return 0
        val names = interrupted.toList().also { interrupted.clear() }
        // Off the caller's (UI) thread, and as one batch: hundreds of single retries on the main
        // thread made Android report the app as not responding, and the queue came back after
        // every forced close.
        return withContext(Dispatchers.Default) { retryDownloads(names) }
    }

    /**
     * Brings back the Downloads list from the previous run. Anything that was still in
     * flight when the process died comes back as STOPPED so the user can retry it.
     */
    internal suspend fun restoreHistory() {
        try {
            val rows = historyDao.getAll()
            rows.forEach { row -> downloadEntities.putIfAbsent(row.fileName, row.toEntity()) }
            val held = try { storageHolds.snapshot() } catch (_: Exception) {
                // A damaged hold journal must not hide the user's durable download history.
                // Transfers still fail closed when launch/resume tries to read that journal.
                Log.w(TAG, "Storage hold history could not be read")
                emptyMap()
            }
            val items = rows.map { row ->
                val restoredItem = row.toItem()
                val entity = row.toEntity()
                val storageHold = row.status in setOf(DownloadStatus.PAUSED.name, DownloadStatus.STOPPED.name) &&
                    held[gamePackages.identity(entity)] == StorageAvailabilityService.key(downloadFileManager.getDownloadDirectoryUri(entity).toString())
                if (storageHold) restoredItem.copy(status = DownloadStatus.PAUSED, finishedAt = null, failure = null, failureAt = null)
                else restoredItem
            }
            // Queued or running when the process ended (not paused: that was the user's choice).
            interrupted += rows.filter { it.status in setOf(DownloadStatus.QUEUED.name, DownloadStatus.DOWNLOADING.name, DownloadStatus.COPYING.name, DownloadStatus.UNZIPPING.name) }
                .sortedBy { it.startedAt }.map { it.fileName }
            downloadProgressTracker.restore(items)
            items.filter { it.status in setOf(DownloadStatus.STOPPED, DownloadStatus.PAUSED) }.forEach { item ->
                val row = rows.first { it.fileName == item.fileName }
                if (row.status != item.status.name || row.finishedAt != item.finishedAt) {
                    historyDao.updateStatus(item.fileName, item.status.name, item.finishedAt)
                }
            }
            // Conditions of downloads that are gone (removed while the app was closed) are dropped.
            conditionGate.prune { downloadEntities.containsKey(it) }
            Log.d(TAG, "Restored ${rows.size} download(s) from history")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Could not restore download history: ${e.message}")
        } finally {
            restored.complete(Unit)
        }
    }

    fun startDownload(file: DownloadableFileEntity, condition: DownloadCondition? = null) = startDownloads(listOf(file), condition)

    /**
     * Queues [files] in one go: one list update, one history write and one service start, then a
     * job per file. A bulk start of hundreds of games used to do all of that once per game on the
     * UI thread, long enough for Android to report the app as not responding. Files already
     * queued or running are skipped (repeated taps, or a bulk start racing a tap).
     *
     * [condition] (optional) is the "Download when..." of the whole batch: each file waits for it
     * without holding a slot, so the rest of the queue is never blocked. Example, tonight for a
     * whole console: `downloadService.startDownloads(files, DownloadCondition(ConditionKind.TONIGHT))`;
     * at 14:30: `DownloadConditions.atTime(14 * 60 + 30, System.currentTimeMillis())`.
     */
    fun startDownloads(files: List<DownloadableFileEntity>, condition: DownloadCondition? = null) {
        registerDownloads(files, files.associate { it.fileName to condition })
    }

    /** Import a plan as one batch; all per-item conditions exist before any job is published or started. */
    fun startDownloads(files: List<DownloadableFileEntity>, conditions: Map<String, DownloadCondition>) {
        registerDownloads(files, conditions)
    }

    internal fun finishJob(fileName: String, owner: Job) = synchronized(startLock) {
        if (downloadJobs.remove(fileName, owner)) {
            queue.forgetOrder(fileName)
            transferring.remove(fileName)
            viaDebrid.remove(fileName)
            parkable.remove(fileName)
            localFailures.remove(fileName)
            proceeding.remove(fileName)
            conditionGate.clear(fileName)
        }
    }

    /** Replacements wait for both the old IO worker and its serialized native/account cleanup. */
    internal fun launchCleanup(fileName: String, operation: String, transfer: Job?, block: suspend () -> Unit): Job {
        val previous = cleanupJobs[fileName]
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                previous?.join()
                transfer?.join()
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$operation failed: ${e.message}", e)
            } finally {
                synchronized(startLock) {
                    if (cleanupJobs.remove(fileName, coroutineContext[Job]!!)) pausingFiles.remove(fileName)
                }
            }
        }
        cleanupJobs[fileName] = job
        job.start()
        return job
    }

    /** Automatic retries done so far per download; a retry by the user starts the count again. */
    internal val autoRetries = ConcurrentHashMap<String, Int>()

    /**
     * A failure that may pass (see [AutoRetry]) is started again after a growing wait, unless the
     * user did something with the row in the meantime or switched this off.
     */
    internal fun scheduleAutoRetry(fileName: String, error: Exception, attempt: DownloadAttemptRegistry.Attempt) {
        val done = autoRetries[fileName] ?: 0
        val wait = if (AutoRetry.isTemporary(error)) AutoRetry.waitBeforeRetry(done) else null
        launchRecovery(fileName, attempt, "Automatic retry for $fileName") {
            // No (more) retries: another source may have the same file. Device failures do
            // not say anything about the source and must not trigger a source switch.
            if (wait == null || !appSettings.autoRetryFailed.first()) {
                if (SourceFailures.isSourceSide(error)) switchSourceOnce(fileName, attempt)
                return@launchRecovery
            }
            synchronized(startLock) {
                if (!attempts.isCurrent(fileName, attempt) ||
                    downloadProgressTracker.getDownloads().none { it.fileName == fileName && it.status == DownloadStatus.FAILED }) return@launchRecovery
                autoRetries[fileName] = done + 1
                retryOwners[fileName] = attempt
                _pendingAutoRetries.update { it + (fileName to PendingAutoRetry(done + 1, AutoRetry.WAITS_MS.size, SystemClock.elapsedRealtime() + wait)) }
            }
            Log.i(TAG, "Retrying $fileName by itself in ${wait / 1000}s (retry ${done + 1} of ${AutoRetry.WAITS_MS.size})")
            try {
                // Cover a preference change whose collector ran just before timer registration.
                if (!appSettings.autoRetryFailed.first()) return@launchRecovery
                delay(wait)
                // A setting changed during the wait is authoritative too. The token is checked
                // again atomically with replacement registration inside restartDownload.
                if (!appSettings.autoRetryFailed.first()) return@launchRecovery
                currentCoroutineContext().ensureActive()
                restartDownload(fileName, expectedAttempt = attempt)
            } finally {
                synchronized(startLock) { clearPendingRetry(fileName, attempt) }
            }
        }
    }

    /** Cancels just the pending retry, keeping the failed row and its stored explanation. */
    fun cancelAutomaticRetry(fileName: String) = synchronized(startLock) {
        if (fileName !in retryOwners) return@synchronized
        userActed(fileName)
    }

    /** Run a waiting retry immediately as a fresh user attempt. A stale UI click does nothing. */
    fun retryNow(fileName: String) = synchronized(startLock) {
        val owner = retryOwners[fileName] ?: return@synchronized
        if (!attempts.isCurrent(fileName, owner)) return@synchronized
        retryDownload(fileName)
    }

    /** [startLock] protects both the owner token and the public timer snapshot. */
    internal fun clearPendingRetry(fileName: String, expectedAttempt: DownloadAttemptRegistry.Attempt? = null) {
        if (expectedAttempt != null && retryOwners[fileName] !== expectedAttempt) return
        retryOwners.remove(fileName)
        _pendingAutoRetries.update { if (fileName in it) it - fileName else it }
    }

    /** Recovery is registered before execution and canceled by every superseding user action. */
    internal fun launchRecovery(
        fileName: String,
        attempt: DownloadAttemptRegistry.Attempt,
        operation: String,
        block: suspend () -> Unit
    ): Unit = synchronized(startLock) {
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (attempts.isCurrent(fileName, attempt)) block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$operation failed: ${e.message}", e)
            }
        }
        job.invokeOnCompletion { attempts.finishRecovery(fileName, attempt, job) }
        if (attempts.registerRecovery(fileName, attempt, job)) job.start()
        Unit
    }

    /** Bytes moved and milliseconds taken by the last finished web transfer per file, for the source's speed. */
    internal val transferSamples = ConcurrentHashMap<String, Pair<Long, Long>>()
    /** Downloads that already moved to another source after failing (once per download). */
    internal val switchedOnce = ConcurrentHashMap.newKeySet<String>()
    internal val _switchedTo = MutableStateFlow<Map<String, String>>(emptyMap())
    /** 7.5: file name -> short name of the source a failed download moved to ("Switched to <source>"). */
    val switchedSources: StateFlow<Map<String, String>> = _switchedTo.asStateFlow()

    private data class SourceChoiceSession(
        val choices: DownloadSourceChoices,
        val current: DownloadableFileEntity,
        val candidates: Map<String, DownloadableFileEntity>
    )
    private val sourceChoiceSessions = ConcurrentHashMap<String, SourceChoiceSession>()
    internal val sourceChoiceEpochs = ConcurrentHashMap<String, Any>()

    internal fun invalidateSourceChoices(fileName: String) {
        sourceChoiceEpochs[fileName] = Any()
        sourceChoiceSessions.remove(fileName)
    }

    init { addUserActionListener(::invalidateSourceChoices) }

    /** Reads the local index only. Opening this dialog never starts or contacts a source. */
    suspend fun sourceChoices(fileName: String): DownloadSourceChoices? = withContext(Dispatchers.IO) {
        val epoch = sourceChoiceEpochs[fileName]
        val original = synchronized(startLock) {
            val item = downloadProgressTracker.getDownloads().firstOrNull { it.fileName == fileName }
            if (item == null || !DownloadSourcePolicy.canChange(item.status)) return@withContext null
            downloadEntities[fileName] ?: return@withContext null
        }
        // A new batch may still be choosing its initial source. Display the actual source once ready.
        sourceSelections[fileName]?.await()
        val current = synchronized(startLock) {
            downloadEntities[fileName]?.takeIf { it.consoleId == original.consoleId && it.fileName == original.fileName }
                ?: return@withContext null
        }
        val copies = sourceTrack.manualCandidates(current)
        currentCoroutineContext().ensureActive()
        val currentWithSource = current.copy(sourceUrl = sourceTrack.sourceOf(current).orEmpty())
        val rows = listOf(currentWithSource) + copies.filter { it.downloadUrl != current.downloadUrl }
        val candidates = rows.associateBy { UUID.randomUUID().toString() }
        val choices = DownloadSourceChoices(
            token = UUID.randomUUID().toString(), fileName = fileName,
            options = candidates.map { (id, entity) ->
                DownloadSourceOption(id, DownloadSourcePolicy.host(entity), entity.downloadUrl == current.downloadUrl, DownloadSourcePolicy.kind(entity))
            }
        )
        synchronized(startLock) {
            val status = downloadProgressTracker.getDownloads().firstOrNull { it.fileName == fileName }?.status
            if (sourceChoiceEpochs[fileName] !== epoch || downloadEntities[fileName] != current || status == null || !DownloadSourcePolicy.canChange(status)) return@withContext null
            sourceChoiceSessions[fileName] = SourceChoiceSession(choices, current, candidates)
        }
        choices
    }

    /**
     * Pins the exact confirmed indexed source. The old worker and all native/account cleanup finish
     * before its partial is forgotten and the replacement is released; source ranking is bypassed.
     */
    suspend fun changeSource(choices: DownloadSourceChoices, optionId: String): SourceChangeResult = withContext(Dispatchers.IO) {
        val session = sourceChoiceSessions[choices.fileName]?.takeIf { it.choices.token == choices.token }
            ?: return@withContext SourceChangeResult.STALE
        val selected = session.candidates[optionId]?.takeIf { it.downloadUrl != session.current.downloadUrl }
            ?: return@withContext SourceChangeResult.UNAVAILABLE
        if (!sourceTrack.permitsManualChoice(session.current) || !sourceTrack.permitsManualChoice(selected)) return@withContext SourceChangeResult.RESTRICTED
        // A rescan, disabling a source or changing profile while the dialog was open is authoritative.
        val next = sourceTrack.manualCandidates(session.current).firstOrNull {
            it.downloadUrl == selected.downloadUrl && it.sourceUrl == selected.sourceUrl && DownloadSourcePolicy.sameFile(session.current, it)
        } ?: return@withContext SourceChangeResult.UNAVAILABLE
        currentCoroutineContext().ensureActive()
        val job = synchronized(startLock) {
            val fileName = choices.fileName
            val status = downloadProgressTracker.getDownloads().firstOrNull { it.fileName == fileName }?.status
            if (sourceChoiceSessions[fileName] !== session || downloadEntities[fileName] != session.current ||
                status == null || !DownloadSourcePolicy.canChange(status)) return@withContext SourceChangeResult.STALE
            userActed(fileName)
            pausingFiles.remove(fileName)
            autoRetries.remove(fileName)
            switchedOnce.remove(fileName)
            localFailures.remove(fileName)
            val transfer = downloadJobs[fileName]
            val debrid = debridTorrents.remove(fileName)
            sourceSelections.remove(fileName)?.cancel()
            val selection = CompletableDeferred<DownloadableFileEntity>()
            sourceSelections[fileName] = selection
            downloadEntities[fileName] = next
            downloadProgressTracker.resetDownloadForRetry(fileName, fileSize = next.fileSize)
            _verification.update { it - fileName }
            _switchedTo.update { it + (fileName to DownloadSourcePolicy.safeLabel(next)) }
            launchCleanup(fileName, "Changing the source of $fileName", transfer) {
                try {
                    if (debrid != null) {
                        historyDao.setDebrid(fileName, null, null, null)
                        debrid.first.delete(debrid.second)
                    } else if (session.current.isTorrent) {
                        torrentDownloadService.cancelDownload(session.current, reportStatus = false)
                    }
                    partials.remove(fileName)
                    headerHashes.remove(fileName)
                    transferSamples.remove(fileName)
                    val item = synchronized(startLock) {
                        downloadProgressTracker.getDownloads().firstOrNull { it.fileName == fileName }
                            ?.takeIf { downloadEntities[fileName] == next && sourceSelections[fileName] === selection }
                    }
                    if (item != null) {
                        historyDao.upsertAll(listOf(DownloadHistoryEntity.from(next, item)))
                        downloadProgressTracker.persistCurrentStatuses(listOf(fileName))
                    }
                    selection.complete(next)
                } catch (e: Exception) {
                    selection.completeExceptionally(e)
                    throw e
                } finally {
                    sourceSelections.remove(fileName, selection)
                }
            }
            transfer?.cancel()
            launchJob(next, selection, startImmediately = false)
        }
        startForegroundService()
        job.start()
        SourceChangeResult.STARTED
    }

    /**
     * With "Pick the best source" on, a new download takes the copy of the same file from the source
     * with the best track record (see [SourceTrackService.ranked]); [file] itself otherwise.
     */
    internal suspend fun chooseSource(file: DownloadableFileEntity, selection: CompletableDeferred<DownloadableFileEntity>): DownloadableFileEntity {
        val best = sourceTrack.pickBest(file)
        if (best.downloadUrl == file.downloadUrl || best.fileName != file.fileName) return file
        synchronized(startLock) {
            if (sourceSelections[file.fileName] !== selection || !downloadEntities.replace(file.fileName, file, best)) return file
        }
        Log.i(TAG, "Taking ${file.fileName} from ${SourceRanking.label(best.sourceUrl)}")
        // A part left by the other server is not continued (the caller stores the new history row).
        partials.remove(file.fileName)
        return best
    }

    /**
     * A download that failed for good (no automatic retry left) starts again once from the next-best
     * source of the same file, with "Switched to <source>" on its row.
     */
    internal suspend fun switchSourceOnce(fileName: String, attempt: DownloadAttemptRegistry.Attempt) {
        if (!attempts.isCurrent(fileName, attempt) || fileName in switchedOnce) return
        val current = downloadEntities[fileName] ?: return
        val next = sourceTrack.nextBest(current)?.takeIf { it.fileName == fileName } ?: return
        val recoveryContext = currentCoroutineContext()
        val cleanup = synchronized(startLock) {
            recoveryContext.ensureActive()
            val stillFailed = downloadProgressTracker.getDownloads().any { it.fileName == fileName && it.status == DownloadStatus.FAILED }
            if (!attempts.isCurrent(fileName, attempt) || !stillFailed ||
                !downloadEntities.replace(fileName, current, next) || !switchedOnce.add(fileName)) return
            // Register the source's disk/history changes before releasing ownership. A manual
            // restart can supersede this recovery, but its transfer and initial history write
            // must wait for these changes so an old source cannot erase the new partial file.
            launchCleanup(fileName, "Preparing the next source for $fileName", transfer = null) {
                partials.remove(fileName)
                rewriteHistory(next)
            }
        }
        cleanup.join()
        recoveryContext.ensureActive()
        synchronized(startLock) {
            if (!attempts.isCurrent(fileName, attempt)) return
            _switchedTo.update { it + (fileName to SourceRanking.label(next.sourceUrl)) }
            autoRetries.remove(fileName)
        }
        Log.i(TAG, "Switching $fileName to ${SourceRanking.label(next.sourceUrl)}")
        // begin() cancels this recovery when replacement is registered; restart registration
        // and service/job starts do not suspend, so the new attempt still starts in full.
        restartDownload(fileName, expectedAttempt = attempt)
    }

    /** The history row follows the source actually used, so a restart picks it up again. */
    internal suspend fun rewriteHistory(entity: DownloadableFileEntity) {
        val item = downloadProgressTracker.getDownloads().firstOrNull { it.fileName == entity.fileName } ?: return
        runCatching { historyDao.upsertAll(listOf(DownloadHistoryEntity.from(entity, item))) }
    }

    internal suspend fun withSlot(file: DownloadableFileEntity, block: suspend () -> Unit) {
        // Torrents have no single server; web downloads count against their host's limit.
        val host = if (file.isTorrent) "" else runCatching { URI(file.downloadUrl).host.orEmpty() }.getOrDefault("")
        queue.acquire(file.fileName, host)
        try { block() } finally { queue.release(host) }
    }

    /** Tells the user (once per minute at most) that downloads stopped for lack of space. */
    @Volatile private var lastLowStorageNotice = 0L
    internal fun notifyLowStorage() {
        val now = System.currentTimeMillis()
        if (now - lastLowStorageNotice < 60_000) return
        lastLowStorageNotice = now
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, DogmatixApplication.SCAN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_error)
            .setContentTitle(context.getString(R.string.storage_low_title))
            .setContentText(context.getString(R.string.storage_low_text))
            .setAutoCancel(true)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(4221, notification)
        } catch (_: SecurityException) { /* Notification permission changed after the check. */ }
    }

    /** Waits while the user's schedule (Wi-Fi only, charging only, night only) says no. */
    internal suspend fun awaitSchedule(fileName: String) {
        if (downloadGate.waiting.value.isEmpty()) return
        _waiting.update { it + fileName }
        try {
            downloadGate.awaitGo()
        } finally {
            _waiting.update { it - fileName }
        }
    }

    /** Routes a file to the debrid, torrent or plain HTTP path (decided at start and on every retry). */
    internal suspend fun perform(file: DownloadableFileEntity) {
        // 8.0: while smart storage moves this console's folder, wait; the file then goes to the new folder.
        moveGate.awaitFree(file.consoleId)
        val debrid = if (file.isTorrent) debridClient(settingsRepository.debridProvider.first()) else null
        if (debrid != null) viaDebrid += file.fileName
        when {
            debrid != null -> performDebridDownload(file, debrid)
            file.isTorrent -> performTorrentDownload(file)
            // Plain HTTP / RomM keep their partial and continue with a Range request on retry.
            else -> performHttpDownload(file, resumable = true)
        }
    }

    internal fun debridClient(provider: DebridProvider): DebridClient? = when (provider) {
        DebridProvider.NONE -> null
        DebridProvider.TORBOX -> torBoxClient
        DebridProvider.REAL_DEBRID -> realDebridClient
    }

    fun cancelDownload(fileName: String) {
        userActed(fileName)
        synchronized(startLock) {
            val entity = downloadEntities[fileName] ?: return
            // A user stop supersedes a storage hold permanently, including after process death.
            // Abort the state mutation if its durable revocation cannot be recorded.
            try { storageHolds.release(listOf(gamePackages.identity(entity))) }
            catch (_: Exception) { Log.w(TAG, "Could not persist storage hold cancellation"); return }
            pausingFiles.remove(fileName)
            // The old worker's finally may be superseded by an immediate retry. A stop must
            // still remove its time/charging condition before that replacement is registered.
            conditionGate.clear(fileName)
            val transfer = downloadJobs[fileName]
            val debrid = debridTorrents.remove(fileName)
            downloadProgressTracker.updateDownloadStatus(fileName, DownloadStatus.STOPPED)
            launchCleanup(fileName, "Stopping $fileName", transfer) {
                when {
                    debrid != null -> {
                        historyDao.setDebrid(fileName, null, null, null)
                        debrid.first.delete(debrid.second)
                    }
                    entity.isTorrent -> torrentDownloadService.cancelDownload(entity, reportStatus = false)
                }
            }
            transfer?.cancel()
        }
    }

    /**
     * Parks a download. A torrent keeps its cache and handle, so retry resumes from disk. A web
     * download (queued or running) keeps its partial file, and *Resume* continues it with a `Range`
     * request (see [PartialDownloads]); a paused download is not put back in the queue after a restart.
     *
     * [byUser]: false for the power rules' own parking (see [PowerHoldService]). A second pause before
     * the first landed is ignored, so the download still ends on PAUSED.
     */
    fun pauseDownload(fileName: String, byUser: Boolean = true) {
        if (byUser) userActed(fileName)
        synchronized(startLock) {
            val entity = downloadEntities[fileName] ?: return
            val status = downloadProgressTracker.getDownloads().firstOrNull { it.fileName == fileName }?.status ?: return
            if (!QueueActions.canPause(status, entity.isTorrent)) return
            pausingFiles.add(fileName)
            val transfer = downloadJobs[fileName]
            downloadProgressTracker.updateDownloadStatus(fileName, DownloadStatus.PAUSED)
            launchCleanup(fileName, "Pausing $fileName", transfer) {
                if (entity.isTorrent) torrentDownloadService.pauseDownload(entity)
            }
            transfer?.cancel()
        }
    }

    fun retryDownload(fileName: String) {
        userActed(fileName)
        autoRetries.remove(fileName)
        switchedOnce.remove(fileName)
        restartDownload(fileName)
    }

    internal fun restartDownload(fileName: String, expectedAttempt: DownloadAttemptRegistry.Attempt? = null) {
        val job = synchronized(startLock) {
            if (expectedAttempt != null && (!attempts.isCurrent(fileName, expectedAttempt) ||
                    downloadProgressTracker.getDownloads().none { it.fileName == fileName && it.status == DownloadStatus.FAILED })) return
            if (!downloadProgressTracker.canRetryDownload(fileName)) return
            val entity = downloadEntities[fileName] ?: return
            // Reset the existing list entry in place — calling startDownload would add a duplicate.
            downloadProgressTracker.resetDownloadForRetry(fileName)
            launchJob(entity, startImmediately = false)
        }
        _verification.update { it - fileName }
        launchBackground("Storing restart for $fileName") {
            historyDao.markRestarted(fileName, DownloadStatus.DOWNLOADING.name, System.currentTimeMillis())
            downloadProgressTracker.persistCurrentStatuses(listOf(fileName))
        }
        startForegroundService()
        job.start()
    }

    /**
     * [retryDownload] for many downloads at once: one list update, one database write, one service
     * start. Returns how many were restarted. [byUser]: false when the power rules requeue what they parked.
     */
    fun retryDownloads(fileNames: List<String>, byUser: Boolean = true): Int {
        if (byUser) fileNames.forEach(::userActed)
        fileNames.forEach { autoRetries.remove(it); switchedOnce.remove(it) }
        lateinit var jobs: List<Job>
        val entities = synchronized(startLock) {
            downloadProgressTracker.resetDownloadsForRetry(fileNames).mapNotNull { downloadEntities[it] }.also { entities ->
                jobs = entities.map { launchJob(it, startImmediately = false) }
            }
        }
        if (entities.isEmpty()) return 0
        _verification.update { it - entities.mapTo(HashSet()) { e -> e.fileName } }
        val now = System.currentTimeMillis()
        launchBackground("Storing bulk restart") {
            entities.map { it.fileName }.chunked(500).forEach { historyDao.markRestartedAll(it, DownloadStatus.DOWNLOADING.name, now) }
            downloadProgressTracker.persistCurrentStatuses(entities.map { it.fileName })
        }
        startForegroundService()
        jobs.forEach { it.start() }
        return entities.size
    }

    fun deleteDownload(fileName: String, deleteFile: Boolean = false) {
        userActed(fileName)
        synchronized(startLock) {
            downloadEntities[fileName]?.let { entity ->
                try { storageHolds.release(listOf(gamePackages.identity(entity))) }
                catch (_: Exception) { Log.w(TAG, "Could not persist storage hold removal"); return }
            }
            pausingFiles.remove(fileName)
            val transfer = downloadJobs[fileName]
            autoRetries.remove(fileName)
            switchedOnce.remove(fileName)
            _switchedTo.update { it - fileName }
            conditionGate.clear(fileName)
            val entity = downloadEntities.remove(fileName)
            val extracted = entity?.let { extractedFilesMap.remove(gamePackages.identity(it)) }
            val debrid = debridTorrents.remove(fileName)
            _verification.update { it - fileName }
            downloadProgressTracker.removeDownload(fileName)
            launchCleanup(fileName, "Removing $fileName", transfer) {
                if (debrid != null) debrid.first.delete(debrid.second)
                else if (entity?.isTorrent == true) torrentDownloadService.cancelDownload(entity, reportStatus = false)
                if (deleteFile && entity != null) {
                    val location = extracted ?: downloadedPackageLocation(entity)
                    if (location != null) downloadFileManager.deleteFileByName(entity, true, extracted?.paths.orEmpty(), location.rootUri, location.subPath)
                }
                historyDao.delete(fileName)
            }
            transfer?.cancel()
        }
    }

    fun cancelAllDownloads() {
        synchronized(startLock) { retryOwners.keys.toList().forEach(::cancelAutomaticRetry) }
        getDownloads().filterNot { it.isFinished }.forEach { cancelDownload(it.fileName) }
    }

    fun getDownloads(): List<DownloadItemModel> = downloadProgressTracker.getDownloads()

    /** The indexed file behind a download in this process (restored history included). */
    fun entityFor(fileName: String): DownloadableFileEntity? = downloadEntities[fileName]

    /** Names for queue display only. File access uses the captured package location below. */
    fun uploadCandidates(fileName: String): List<String> {
        val entity = downloadEntities[fileName] ?: return emptyList()
        return extractedFilesMap[gamePackages.identity(entity)]?.paths?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(runCatching { FileParsingUtils.storageFileName(entity.fileName) }.getOrNull())
    }

    /** Capture the original receipt subfolder once; changed settings cannot redirect its paths. */
    suspend fun downloadedPackageLocation(file: DownloadableFileEntity): DownloadedPackageLocation? {
        val root = downloadFileManager.getDownloadDirectoryUri(file).toString()
        if (root.isBlank()) return null
        val receipt = gamePackages.recordFor(file, setOf(root))
        if (receipt != null) return DownloadedPackageLocation(receipt.rootUri, receipt.subPath, receipt.parts.map { it.path })
        // A tracked package whose root moved or receipt is corrupt cannot inherit an unrelated basename.
        if (gamePackages.hasRecordedIdentity(file, setOf(root))) return null
        val name = runCatching { FileParsingUtils.storageFileName(file.fileName) }.getOrNull() ?: return null
        return DownloadedPackageLocation(root, downloadFileManager.getSubPath(file), listOf(name))
    }

    suspend fun uploadCandidates(file: DownloadableFileEntity): List<String> = downloadedPackageLocation(file)?.paths.orEmpty()

    /** Newly generated playlists become part of the same exact source/root receipt. */
    suspend fun recordPackageAfterPlaylist(file: DownloadableFileEntity, root: String, subPath: String, paths: List<String>) {
        val receipt = gamePackages.includeGenerated(file, root, subPath, paths.distinct())
        extractedFilesMap[gamePackages.identity(file)] = DownloadedPackageLocation(receipt.rootUri, receipt.subPath, receipt.parts.map { it.path })
    }

    /** Park only interruptible transfers using this exact selected folder, retaining partial bytes. */
    suspend fun holdStorageUnavailable(rootUri: String) = withContext(Dispatchers.IO) {
        val key = StorageAvailabilityService.key(rootUri)
        val entities = downloadEntities.values.toList().filter { entity ->
            StorageAvailabilityService.key(downloadFileManager.getDownloadDirectoryUri(entity).toString()) == key
        }
        for (entity in entities) {
            currentCoroutineContext().ensureActive()
            synchronized(startLock) {
                if (downloadEntities[entity.fileName] != entity) return@synchronized
                val row = downloads.value.firstOrNull { it.fileName == entity.fileName } ?: return@synchronized
                if (row.status !in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING) ||
                    !QueueActions.canPause(row.status, entity.isTorrent)) return@synchronized
                storageHolds.hold(listOf(gamePackages.identity(entity)), rootUri)
                pauseDownload(entity.fileName, byUser = true)
            }
        }
    }

    /** An unrelated user pause/stop or a changed game source is never offered as storage recovery. */
    suspend fun resumableStorageDownloads(rootUri: String): List<String> = withContext(Dispatchers.IO) {
        val rootKey = StorageAvailabilityService.key(rootUri)
        val held = storageHolds.snapshot()
        val rows = downloads.value.associateBy { it.fileName }
        downloadEntities.values.toList().filter { entity ->
            val row = rows[entity.fileName]
            (row?.status == DownloadStatus.PAUSED && held[gamePackages.identity(entity)] == rootKey ||
                row?.status == DownloadStatus.FAILED && row.failure?.category in setOf(
                    DownloadFailureCategory.STORAGE_PERMISSION, DownloadFailureCategory.STORAGE_WRITE)) &&
                StorageAvailabilityService.key(downloadFileManager.getDownloadDirectoryUri(entity).toString()) == rootKey &&
                sourceTrack.permitsManualChoice(entity)
        }.map { it.fileName }.sorted()
    }

    /** Explicit recovery rechecks create/write/read first, then revalidates the current queue identities. */
    suspend fun resumeStorageDownloads(rootUri: String, expectedProfileId: String? = null): Int = withContext(Dispatchers.IO) {
        val profile = expectedProfileId ?: appSettings.activeProfile.first()
        appSettings.withActiveProfile(profile) { Unit }
        if (!storageAvailability.verifyWritable(rootUri)) return@withContext 0
        val names = resumableStorageDownloads(rootUri)
        val entities = names.mapNotNull { downloadEntities[it] }
        val identities = entities.map { gamePackages.identity(it) }
        val rootKey = StorageAvailabilityService.key(rootUri)
        currentCoroutineContext().ensureActive()
        appSettings.withActiveProfile(profile) {
            val eligible = entities.filter { entity ->
                downloadEntities[entity.fileName] == entity &&
                    StorageAvailabilityService.key(downloadFileManager.getDownloadDirectoryUri(entity).toString()) == rootKey &&
                    sourceTrack.permitsManualChoice(entity)
            }
            synchronized(startLock) {
                val held = storageHolds.snapshot()
                val rows = downloads.value.associateBy { it.fileName }
                val fresh = eligible.filter { entity ->
                    val row = rows[entity.fileName]
                    downloadEntities[entity.fileName] == entity &&
                        (row?.status == DownloadStatus.PAUSED && held[gamePackages.identity(entity)] == rootKey ||
                            row?.status == DownloadStatus.FAILED && row.failure?.category in setOf(
                                DownloadFailureCategory.STORAGE_PERMISSION, DownloadFailureCategory.STORAGE_WRITE))
                }
                storageHolds.release(fresh.map { gamePackages.identity(it) }.filter { it in identities })
                retryDownloads(fresh.map { it.fileName }, byUser = true)
            }
        }
    }


    /**
     * An intent that opens the finished download in whichever app handles the file (an emulator,
     * a file manager), or null when the file cannot be found. For a download that was unpacked, the
     * game file is picked from what was extracted (a sheet or image rather than its tracks).
     */
    suspend fun openIntentFor(fileName: String): Intent? {
        val entity = downloadEntities[fileName] ?: return null
        val location = downloadedPackageLocation(entity) ?: return null
        val root = StorageHelper.getDocumentFile(context, location.rootUri) ?: return null
        val dir = if (location.subPath.isBlank()) root else StorageHelper.findFile(root, location.subPath)?.takeIf { it.isDirectory } ?: return null
        val priority = listOf("m3u", "cue", "gdi", "chd", "iso", "pbp", "ccd", "mds")
        val names = location.paths.sortedBy { n -> priority.indexOf(n.substringAfterLast('.').lowercase()).let { if (it < 0) priority.size else it } }
        val doc = names.firstNotNullOfOrNull { StorageHelper.findFile(dir, it)?.takeIf { f -> f.isFile } } ?: return null
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(doc.uri, "application/octet-stream")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    internal suspend fun updateStatus(fileName: String, status: DownloadStatus, failure: DownloadFailure? = null) {
        val owner = currentCoroutineContext()[Job]
        synchronized(startLock) {
            if (downloadJobs[fileName] === owner) downloadProgressTracker.updateDownloadStatus(fileName, status, failure = failure)
        }
    }

    /**
     * Starts the foreground service if it is not running; it stops itself once nothing is active
     * (see [DownloadForegroundService]). Android 12+ refuses this from the background; the download
     * then runs without the notification instead of taking the app down.
     */
    @Volatile private var lastServiceRequest = 0L

    internal fun startForegroundService() {
        if (DownloadForegroundService.running) return
        // A retry of many rows asks many times before the service is up; one request is enough.
        val now = SystemClock.elapsedRealtime()
        if (now - lastServiceRequest < 5_000) return
        lastServiceRequest = now
        try {
            context.startForegroundService(Intent(context, DownloadForegroundService::class.java).apply {
                action = DownloadForegroundService.ACTION_START_SERVICE
            })
        } catch (e: Exception) {
            Log.w(TAG, "Foreground service not started: ${e.message}")
        }
    }

    /** True while [fileName] is queued, downloading, copying or unpacking. */
    fun isActive(fileName: String): Boolean = downloadProgressTracker.isActive(fileName)

    /** True while any download is queued, running, copying or unpacking. */
    fun hasActiveDownloads(): Boolean = downloadProgressTracker.hasActiveDownloads()

    /** [hasActiveDownloads] as a flow, for the foreground service. */
    val anyActive: Flow<Boolean> = downloadProgressTracker.downloads.map { downloadProgressTracker.hasActiveDownloads() }
}
