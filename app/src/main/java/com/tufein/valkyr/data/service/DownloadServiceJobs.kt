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

private const val TAG = "DownloadService"

internal fun DownloadService.registerDownloads(files: List<DownloadableFileEntity>, conditions: Map<String, DownloadCondition?>) {
    lateinit var sources: List<CompletableDeferred<DownloadableFileEntity>>
    lateinit var jobs: List<Job>
    lateinit var priorCleanups: List<Job>
    val fresh = synchronized(startLock) {
        // One set of the active names instead of a walk over the whole list per file.
        val active = downloadProgressTracker.activeNames()
        val items = files.distinctBy { it.fileName }
            .filterNot { it.fileName in active }
            .map { it to downloadFileManager.createDownloadItem(it) }
        if (items.isEmpty()) return
        val names = items.mapTo(HashSet()) { it.first.fileName }
        _verification.update { it - names }
        _switchedTo.update { it - names }
        switchedOnce.removeAll(names)
        items.forEach { (file, _) -> downloadEntities[file.fileName] = file }
        names.groupBy { conditions[it] }.forEach { (condition, group) ->
            conditionGate.setAll(group, condition)
        }
        priorCleanups = names.mapNotNull { cleanupJobs[it] }.distinct()
        sources = items.map { CompletableDeferred<DownloadableFileEntity>() }
        items.forEachIndexed { i, (file, _) -> sourceSelections[file.fileName] = sources[i] }
        queue.reserveOrder(items.map { it.first.fileName })
        jobs = items.mapIndexed { i, (file, _) -> launchJob(file, sources[i], startImmediately = false) }
        // Publish only after every accepted row has an entity and a cancellable job. Stop/
        // pause uses this same lock, so it cannot miss the later rows in a bulk registration.
        downloadProgressTracker.addDownloads(items.map { it.second })
        items
    }
    launchBackground("Storing new downloads") {
        // Insert history before any transfer starts: a fast download's status UPDATE used
        // to arrive before its row existed, then the late batch insert restored DOWNLOADING.
        val chosen = try {
            priorCleanups.forEach { it.join() }
            fresh.mapIndexed { i, (file, _) ->
                try {
                    chooseSource(file, sources[i])
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Could not choose a source for ${file.fileName}: ${e.message}")
                    file
                }
            }
        } catch (e: CancellationException) {
            sources.forEach { it.cancel(e) }
            throw e
        }
        try {
            // A pause/stop/delete during source selection wins over the original batch row.
            val latest = downloadProgressTracker.getDownloads().associateBy { it.fileName }
            val rows = chosen.mapIndexedNotNull { i, entity ->
                latest[entity.fileName]?.takeIf { downloadEntities[entity.fileName] == entity && sourceSelections[entity.fileName] === sources[i] }
                    ?.let { DownloadHistoryEntity.from(entity, it) }
            }
            historyDao.upsertAll(rows)
            // Changes while Room was inserting may already have been updated against a
            // nonexistent row. Re-persist the canonical statuses through the tracker actor.
            downloadProgressTracker.persistCurrentStatuses(rows.map { it.fileName })
            chosen.filter { !downloadEntities.containsKey(it.fileName) }.forEach { historyDao.delete(it.fileName) }
        } catch (e: CancellationException) {
            sources.forEach { it.cancel(e) }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not store the new downloads: ${e.message}", e)
        } finally {
            // A recoverable persistence failure must not strand every download in the batch.
            chosen.forEachIndexed { i, file ->
                sources[i].complete(file)
                sourceSelections.remove(file.fileName, sources[i])
            }
        }
    }
    startForegroundService()
    jobs.forEach { it.start() }
}

/** [source]: a new download waits for the source picked for it (see [chooseSource]). */
internal fun DownloadService.launchJob(file: DownloadableFileEntity, source: CompletableDeferred<DownloadableFileEntity>? = null, startImmediately: Boolean = true): Job = synchronized(startLock) {
    val previous = downloadJobs[file.fileName]
    val cleanup = cleanupJobs[file.fileName]
    val selection = source ?: sourceSelections[file.fileName]
    val attempt = attempts.begin(file.fileName)
    clearPendingRetry(file.fileName)
    invalidateSourceChoices(file.fileName)
    // Registered before it starts, so a download that ends at once cannot leave a stale entry.
    val job = serviceScope.launch(start = CoroutineStart.LAZY) {
        // The row actually downloaded: the same file name, possibly from another source.
        var current = file
        val owner = coroutineContext[Job]!!
        try {
            previous?.join()
            cleanup?.join()
            selection?.await()
            currentCoroutineContext().ensureActive()
            current = downloadEntities[file.fileName] ?: return@launch
            val storageRoot = downloadFileManager.getDownloadDirectoryUri(current).toString()
            if (storageHolds.snapshot()[gamePackages.identity(current)] == StorageAvailabilityService.key(storageRoot)) {
                synchronized(startLock) {
                    if (downloadJobs[file.fileName] === owner) downloadProgressTracker.updateDownloadStatus(file.fileName, DownloadStatus.PAUSED)
                }
                return@launch
            }
            synchronized(startLock) {
                if (downloadJobs[file.fileName] !== owner || !owner.isActive) return@launch
                // A previous native preflight may have reported failure while this retry
                // waited for its cleanup. This generation now owns the row and its state.
                downloadProgressTracker.updateDownloadStatus(file.fileName, DownloadStatus.DOWNLOADING)
            }
            val chosen = current
            var ran = false
            while (!ran) {
                // The download's own condition is waited for outside a slot: it never blocks others.
                conditionGate.awaitReady(file.fileName)
                withSlot(chosen) {
                    // A condition set while it stood in line: give the slot back and wait outside.
                    if (conditionGate.isBlocked(file.fileName)) return@withSlot
                    proceeding += file.fileName
                    conditionGate.clear(file.fileName)
                    awaitSchedule(file.fileName)
                    // Brief delay to allow the foreground service and initial UI state to settle
                    // before network/torrent activity begins.
                    delay(1000L)
                    currentCoroutineContext().ensureActive()
                    if (downloadJobs[file.fileName] !== owner) return@withSlot
                    transferring += file.fileName
                    perform(chosen)
                    ran = true
                }
            }
            autoRetries.remove(file.fileName)
            // Torrents end on COMPLETED or FAILED without throwing; web downloads throw on failure.
            when (downloadProgressTracker.getDownloads().firstOrNull { it.fileName == file.fileName }?.status) {
                DownloadStatus.COMPLETED -> transferSamples.remove(file.fileName).let { s ->
                    sourceTrack.recordSuccess(chosen, s?.first ?: 0L, s?.second ?: 0L)
                }
                // A torrent that could not be moved into place failed here, not at its source.
                DownloadStatus.FAILED -> if (!localFailures.remove(file.fileName) &&
                    downloadProgressTracker.getDownloads().firstOrNull { it.fileName == file.fileName }?.failure?.category !in DEVICE_FAILURES) {
                    sourceTrack.recordFailure(chosen, null)
                    launchRecovery(file.fileName, attempt, "Switching source for ${file.fileName}") { switchSourceOnce(file.fileName, attempt) }
                }
                else -> Unit
            }
        } catch (e: CancellationException) {
            synchronized(startLock) {
                if (downloadJobs[file.fileName] === owner) {
                    downloadProgressTracker.updateDownloadStatus(file.fileName, if (file.fileName in pausingFiles) DownloadStatus.PAUSED else DownloadStatus.STOPPED)
                }
            }
            throw e
        } catch (e: LowStorageException) {
            synchronized(startLock) { if (downloadJobs[file.fileName] === owner) downloadProgressTracker.updateDownloadStatus(file.fileName, DownloadStatus.STOPPED, failure = DownloadFailures.classify(e)) }
            notifyLowStorage()
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for ${file.fileName}: ${e.message}")
            val failedHere = synchronized(startLock) {
                if (downloadJobs[file.fileName] !== owner || !owner.isActive) false
                else {
                    downloadProgressTracker.updateDownloadStatus(file.fileName, DownloadStatus.FAILED, failure = DownloadFailures.classify(e))
                    true
                }
            }
            if (failedHere) {
                // A missing folder or a full disk says nothing about the source.
                if (SourceFailures.isSourceSide(e)) sourceTrack.recordFailure(current, e)
                scheduleAutoRetry(file.fileName, e, attempt)
            }
        } finally {
            finishJob(file.fileName, owner)
        }
    }
    job.invokeOnCompletion {
        // A LAZY job may be canceled before its body/finally is entered.
        finishJob(file.fileName, job)
    }
    downloadJobs[file.fileName] = job
    if (startImmediately) job.start()
    job
}

