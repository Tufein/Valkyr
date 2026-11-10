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
import java.io.IOException
import java.net.SocketTimeoutException

private const val TAG = "DownloadService"

internal suspend fun DownloadService.performTorrentDownload(file: DownloadableFileEntity) {
    Log.d(TAG, "Starting torrent download for ${file.fileName}")
    torrentDownloadService.startDownload(file)

    // COPYING hands the verified network transfer to local processing, keeping the row
    // unfinished until it is on disk. Collect this file's status without busy-wait polling.
    val transferStatus = downloadProgressTracker.downloads
        .map { list -> list.find { it.fileName == file.fileName }?.status }
        .distinctUntilChanged()
        .first { it == DownloadStatus.COPYING || it == DownloadStatus.FAILED || it == DownloadStatus.STOPPED }

    when (transferStatus) {
        DownloadStatus.FAILED  -> { Log.e(TAG, "Torrent FAILED: ${file.fileName}"); return }
        DownloadStatus.STOPPED -> { Log.i(TAG, "Torrent STOPPED: ${file.fileName}"); return }
        else -> moveTorrentFile(file)
    }
}

internal suspend fun DownloadService.moveTorrentFile(file: DownloadableFileEntity) {
    moveGate.awaitFree(file.consoleId)
    try {
        val downloadDirUri = downloadFileManager.getDownloadDirectoryUri(file)
        if (downloadDirUri == Uri.EMPTY)
            throw StorageAccessException()

        // Use the info cached at download-start time so this works even if the handle was
        // invalidated (e.g. session stopped during app shutdown before the copy finishes).
        val fileInfo = torrentDownloadService.getFileInfo(file.fileName)
            ?: throw Exception("Could not get torrent file info for ${file.fileName}")
        val relativePath = fileInfo.relativePath
        val expectedSize = fileInfo.expectedSize
        val fileExtension = relativePath.substringAfterLast(".", "")

        val internalFile = File(context.cacheDir, "torrent_data/$relativePath")
        Log.d(TAG, "Internal torrent file: ${internalFile.absolutePath}, exists: ${internalFile.exists()}")

        if (!internalFile.exists())
            throw StorageException("Internal torrent file not found")

        // libtorrent marks a file complete (via fileProgress) after hash-verification,
        // but its disk thread flushes writes asynchronously. Poll until the OS-visible
        // file size matches the torrent metadata size before we copy.
        // Also bail early if the session has been stopped (e.g. app shutdown) — the file
        // will never grow any further and the job should fail fast rather than wait 15s.
        var waitedMs = 0
        while (internalFile.length() < expectedSize && waitedMs < 15_000 && torrentHandleRegistry.isRunning) {
            Log.d(TAG, "Waiting for disk flush for ${file.fileName}: ${internalFile.length()}/$expectedSize bytes")
            delay(500)
            waitedMs += 500
        }
        if (internalFile.length() < expectedSize) {
            // Copying a truncated file to the ROMs folder would mark a broken download as
            // completed (seen with ENOSPC on the cache partition: 0 bytes were "flushed").
            throw StorageException(
                "Incomplete torrent data for ${file.fileName}: " +
                "${internalFile.length()}/$expectedSize bytes (disk full or write error)"
            )
        }

        val subPath = downloadFileManager.getSubPath(file)
        requireSourceChecksum(file, DocumentFile.fromFile(internalFile))

        if (ArchiveUtils.isExtractable(fileExtension) && settingsRepository.autoUnzip.first()) {
            // Extract directly from cache — skips writing the compressed archive to SAF entirely.
            // Flow: cacheDir/torrent_data/ → extraction_temp/ → SAF destination
            Log.d(TAG, "Extracting torrent archive directly from cache: ${internalFile.name}")
            updateStatus(file.fileName, DownloadStatus.UNZIPPING)
            val extracted = archiveExtractorService.extractArchiveFile(
                context, internalFile, downloadDirUri, subPath, failOnError = true
            )
            if (extracted.isNotEmpty()) {
                gamePackages.record(file, downloadDirUri.toString(), subPath, extracted)
                extractedFilesMap[gamePackages.identity(file)] = DownloadedPackageLocation(downloadDirUri.toString(), subPath, extracted)
            } else {
                throw DownloadExtractionException()
            }
        } else {
            // Non-archive or auto-unzip disabled: copy directly from cache to SAF
            val documentFile = downloadFileManager.createDocumentFile(file, downloadDirUri.toString(), subPath)
                ?: throw StorageException("Failed to create destination file in storage.")
            Log.d(TAG, "Copying torrent file to SAF: ${documentFile.uri}")
            updateStatus(file.fileName, DownloadStatus.COPYING)
            try {
                val coroutine = currentCoroutineContext()
                val expectedSha256 = context.contentResolver.openOutputStream(documentFile.uri)?.use { out ->
                    BufferedOutputStream(out, Constants.EXTRACTION_BUFFER_SIZE).use { buffOut ->
                        VerifiedCopy.transfer(internalFile.inputStream(), buffOut, check = { coroutine.ensureActive() })
                    }
                }
                    ?: throw StorageException("Could not open destination output stream")
                currentCoroutineContext().ensureActive()
                requireSourceChecksum(file, documentFile)
                downloadFileManager.commitDocumentFile(file, downloadDirUri.toString(), subPath, documentFile, expectedSha256) { coroutine.ensureActive() }
            } catch (e: DownloadVerificationException) {
                _verification.update { it + (file.fileName to VerifyState.MISMATCH) }
                throw e
            } catch (e: IOException) {
                throw StorageException("Could not copy downloaded torrent to storage", e)
            } finally {
                if (downloadFileManager.isStagingFile(documentFile)) downloadFileManager.deleteFile(documentFile)
            }
            gamePackages.record(file, downloadDirUri.toString(), subPath, listOf(FileParsingUtils.storageFileName(file.fileName)))
        }
        // Siblings can share torrent pieces with this file. Keep the native session's
        // backing files until the last tracked sibling releases the handle and its cache.
        torrentDownloadService.finishDownload(file)

        Log.i(TAG, "Torrent processed successfully: ${file.fileName}")
        updateStatus(file.fileName, DownloadStatus.COMPLETED)
        _finished.tryEmit(file.fileName)

    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Error processing torrent file for ${file.fileName}: ${e.message}", e)
        if (e is DownloadVerificationException) _verification.update { it + (file.fileName to VerifyState.MISMATCH) }
        localFailures += file.fileName
        updateStatus(file.fileName, DownloadStatus.FAILED, DownloadFailures.classify(e))
        // Untrack and, if nothing else uses the torrent, release it (deletes the cached data);
        // a retry re-fetches the metadata and starts clean instead of leaving a partial behind.
        runCatching { torrentDownloadService.finishDownload(file) }
    }
}

/**
 * Debrid route: the service (TorBox, Real-Debrid) fetches the torrent server-side, then the
 * file comes down over plain HTTP through [performHttpDownload] (same throttling, SAF write
 * and extraction).
 */
internal suspend fun DownloadService.performDebridDownload(file: DownloadableFileEntity, client: DebridClient) {
    val magnet = file.torrentMagnet ?: throw Exception("Missing magnet for ${file.fileName}")
    Log.d(TAG, "Starting ${client.provider.label} download for ${file.fileName}")
    updateStatus(file.fileName, DownloadStatus.QUEUED)

    try {
        performDebridDownloadInner(file, client, magnet)
    } catch (e: Exception) {
        // Leave nothing behind on the account when the debrid route gives up.
        releaseDebrid(file.fileName)
        throw e
    }
}

/** Removes the torrent from the debrid account, detached from the (cancellable) download job. */
internal fun DownloadService.releaseDebrid(fileName: String) {
    val (client, id) = debridTorrents.remove(fileName) ?: return
    launchBackground("Removing remote torrent for $fileName") { client.delete(id) }
}

internal suspend fun DownloadService.performDebridDownloadInner(file: DownloadableFileEntity, client: DebridClient, magnet: String) {
    val label = client.provider.label
    // A previous run that died mid-transfer left the ids in the history: pick the same
    // torrent up again and resume the HTTP transfer from what is already on disk.
    val previous = historyDao.getByFileName(file.fileName)
    val resumed = previous?.debridTorrentId?.takeIf { previous.debridProvider == client.provider.name }?.let { id ->
        runCatching { client.getTorrent(id) }.getOrNull()
            ?.takeIf { it.downloadFinished }
            ?.let { t -> t.files.firstOrNull { it.id == previous.debridFileId }?.let { f -> t to f } }
    }
    if (resumed != null) {
        val (torrent, remote) = resumed
        debridTorrents[file.fileName] = client to torrent.id
        Log.d(TAG, "Resuming $label download of ${file.fileName} (torrent ${torrent.id}, file ${remote.id})")
        updateStatus(file.fileName, DownloadStatus.DOWNLOADING)
        performHttpDownload(file, resumable = true) { client.requestDownload(torrent.id, remote.id) }
        historyDao.setDebrid(file.fileName, null, null, null)
        releaseDebrid(file.fileName)
        return
    }

    val hash = DebridMatcher.infoHashFromMagnet(magnet)
    val cached = hash?.let { client.isCached(it) } == true
    var torrentId = client.createTorrent(magnet)
    debridTorrents[file.fileName] = client to torrentId

    /** Polls until [done] holds; cached torrents get there on the first check. */
    suspend fun await(id: String, done: (DebridTorrent) -> Boolean): DebridTorrent {
        val started = System.currentTimeMillis()
        var torrent = client.getTorrent(id)
        var pollErrors = 0
        while (!done(torrent)) {
            torrent.failure?.let { throw Exception(it) }
            if (System.currentTimeMillis() - started > DEBRID_MAX_WAIT_MS) throw SocketTimeoutException("Debrid fetch timed out")
            downloadProgressTracker.updateDownloadProgress(file.fileName, torrent.progress, 0f, (torrent.progress * file.fileSize).toLong())
            val elapsed = System.currentTimeMillis() - started
            delay(when { cached || elapsed < 30_000L -> 3_000L; elapsed < 5 * 60_000L -> 10_000L; else -> 30_000L })
            // Services answer 5xx now and then while a torrent is being fetched: keep polling.
            torrent = runCatching { client.getTorrent(id) }.getOrElse { e ->
                if (e is DebridAuthException || ++pollErrors > 5) throw e
                Log.w(TAG, "$label poll error (${pollErrors}/5): ${e.message}")
                torrent
            }
        }
        return torrent
    }
    suspend fun awaitListed(id: String) = await(id) { it.filesKnown || it.downloadFinished }
    suspend fun awaitFinished(id: String) = await(id) { it.downloadFinished }.also { t ->
        Log.d(TAG, "$label torrent ${t.id} '${t.name}' files=${t.files.size}: " + t.files.take(5).joinToString { "${it.id}:${it.name}(${it.size})" })
    }

    var torrent = awaitListed(torrentId)
    var remote = DebridMatcher.pickFile(torrent.files, file.fileName, file.fileSize)
    if (remote == null && torrent.files.size == 1 && torrent.files[0].name.endsWith(".zip", ignoreCase = true)) {
        // TorBox zipped the whole torrent (an earlier add without allow_zip=false); re-add it unzipped.
        Log.w(TAG, "$label holds ${torrent.name} as a single zip; re-adding it unzipped")
        client.delete(torrentId)
        torrentId = client.createTorrent(magnet)
        debridTorrents[file.fileName] = client to torrentId
        torrent = awaitListed(torrentId)
        remote = DebridMatcher.pickFile(torrent.files, file.fileName, file.fileSize)
    }
    if (remote == null) {
        // The service only holds a zip of the whole torrent (it cannot serve single files
        // from it): give the torrent back and fetch this file directly instead.
        Log.w(TAG, "$label lists ${torrent.files.size} file(s) for ${torrent.name} but not ${file.fileName}; falling back to direct torrent download")
        releaseDebrid(file.fileName)
        updateStatus(file.fileName, DownloadStatus.DOWNLOADING)
        performTorrentDownload(file)
        return
    }

    client.selectFile(torrentId, remote.id)
    historyDao.setDebrid(file.fileName, client.provider.name, torrentId, remote.id)
    awaitFinished(torrentId)

    updateStatus(file.fileName, DownloadStatus.DOWNLOADING)
    downloadProgressTracker.updateDownloadProgress(file.fileName, 0f, 0f, 0L)
    // Links expire, so each HTTP attempt asks for a fresh one; attempts resume from the partial file.
    performHttpDownload(file, resumable = true) { client.requestDownload(torrentId, remote.id) }
    historyDao.setDebrid(file.fileName, null, null, null)
    // COMPLETED may already have stopped the foreground service, whose onDestroy cancels every
    // download job: the remote clean-up must not run inside this job.
    releaseDebrid(file.fileName)
}

/**
 * [resumable]: keep the partial file when an attempt stops and continue it with a `Range`
 * request next time (debrid links serve ranges; plain HTTP sources are not trusted to).
 */
internal suspend fun DownloadService.performHttpDownload(
    file: DownloadableFileEntity,
    resumable: Boolean = false,
    urlProvider: (suspend () -> String)? = null
) {
    // Debrid links change on every request: their partial record belongs to the download, not to one address.
    val trustRecord = urlProvider != null
    var lastError: Exception? = null
    repeat(3) { attempt ->
        try {
            if (attempt > 0) delay(2000L * attempt)
            performHttpDownloadAttempt(file, urlProvider?.invoke() ?: file.downloadUrl, resumable, trustRecord)
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: LowStorageException) {
            throw e
        } catch (e: StorageException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Attempt ${attempt + 1} failed for ${file.fileName}: ${e.message}")
            lastError = e
        }
    }
    // A file of a web source with reserve addresses: the same file there, in their order.
    for (url in if (trustRecord) emptyList() else mirrorsOf(file)) {
        try {
            Log.i(TAG, "Trying ${file.fileName} from a reserve address")
            performHttpDownloadAttempt(file, url, resumable = false)
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: LowStorageException) {
            throw e
        } catch (e: StorageException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Reserve address failed for ${file.fileName}: ${e.message}")
            lastError = e
        }
    }
    throw lastError ?: Exception("Download failed")
}

/** The file under the other addresses of the web source it came from (none for torrents, RomM, debrid). */
internal suspend fun DownloadService.mirrorsOf(file: DownloadableFileEntity): List<String> = runCatching {
    if (file.isTorrent) return emptyList()
    val entries = SourcesJson.parseUrlEntries(consoleDao.getConsoleById(file.consoleId)?.urls ?: return emptyList())
    entries.filter { it.enabled && it.mirrors.isNotEmpty() }.firstNotNullOfOrNull { entry ->
        MirrorUrls.alternatives(file.downloadUrl, entry.url, entry.mirrors).takeIf { it.isNotEmpty() }
    }.orEmpty()
}.getOrDefault(emptyList())

/** [trustRecord]: continue a noted partial whatever address it came from (debrid links change every time). */
internal suspend fun DownloadService.performHttpDownloadAttempt(file: DownloadableFileEntity, downloadUrl: String, resumable: Boolean = false, trustRecord: Boolean = false) {
    val downloadDirUri = downloadFileManager.getDownloadDirectoryUri(file)
    if (downloadDirUri == Uri.EMPTY)
        throw StorageAccessException()
    updateStatus(file.fileName, DownloadStatus.DOWNLOADING)
    parkable.remove(file.fileName)

    var inputStream: InputStream? = null
    var outputStream: OutputStream? = null
    var documentFile: DocumentFile? = null
    var activeConnection: HttpURLConnection? = null
    var keepPartial = false

    try {
        val subPath = downloadFileManager.getSubPath(file)
        // Only a partial file this app wrote (and noted) is continued; see PartialDownloads.
        val resume = resumable && appSettings.resumeDownloads.first()
        keepPartial = resume
        // A part written from another source (the download moved) is not continued; a reserve address of the same source is.
        val record = if (resume) partials.get(file.fileName)?.takeIf { r ->
            trustRecord || PartialOwner.sameSource(r.url, downloadUrl, mirrorsOf(file) + file.downloadUrl)
        } else null
        val partial = if (record != null) downloadFileManager.findExistingFile(file, downloadDirUri.toString(), subPath) else null
        val partialBytes = partial?.length() ?: 0L
        // "Use RomM" off: a file of the RomM library is not fetched at all (no unsigned request to the server either).
        if (!settingsRepository.rommEnabled.first() &&
            RommSource.isDownloadFrom(settingsRepository.rommUrlSaved.first(), downloadUrl)
        ) throw RommSwitchedOffException()
        // Files served by the RomM library need the account's credentials.
        val auth = if (RommSource.isDownloadFrom(rommClient.configuredBaseUrl(), downloadUrl)) rommClient.downloadHeaders() else emptyMap()
        val headers = if (partialBytes > 0 && record?.validator != null) auth + ("If-Range" to record.validator) else auth
        currentCoroutineContext().ensureActive()
        val response = downloadHttpClient.openDownload(downloadUrl, rangeStart = partialBytes, headers = headers, expectedTotal = file.fileSize)
        val connection = response.connection.also { activeConnection = it }
        inputStream = connection.inputStream
        currentCoroutineContext().ensureActive()

        // The client has discarded invalid partial responses and obtained a full response
        // before a restart can replace the old file. Its validated offset owns this write.
        val startOffset = response.transfer.startOffset
        val appending = partial != null && startOffset > 0
        val transferHash = MessageDigest.getInstance("SHA-256")
        if (appending) {
            // The read prefix and the received suffix together define the expected stored
            // file. An unreadable saved part cannot be silently treated as an empty prefix.
            try {
                context.contentResolver.openInputStream(requireNotNull(partial).uri)?.use { prefix ->
                    val buffer = ByteArray(Constants.BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = prefix.read(buffer)
                        if (count < 0) break
                        transferHash.update(buffer, 0, count)
                    }
                } ?: throw StorageException("Could not read saved partial download")
            } catch (e: IOException) {
                throw StorageException("Could not read saved partial download", e)
            }
        }
        if (appending) {
            val continued = requireNotNull(partial)
            documentFile = continued
            outputStream = downloadFileManager.getAppendOutputStream(continued)
                ?: throw StorageException("Failed to open output stream for ${continued.uri}")
            Log.d(TAG, "Resuming ${file.fileName} from $partialBytes bytes")
        } else {
            documentFile = downloadFileManager.createDocumentFile(file, downloadDirUri.toString(), subPath)
                ?: throw StorageException("Failed to create file in storage.")
            outputStream = downloadFileManager.getOutputStream(documentFile)
                ?: throw StorageException("Failed to open output stream for ${documentFile.uri}")
            if (resume) partials.put(file.fileName, PartialDownloads.Record(downloadUrl,
                ResumePlan.validator(connection.getHeaderField("ETag"), connection.getHeaderField("Last-Modified"))))
        }
        outputStream = DigestOutputStream(requireNotNull(outputStream), transferHash)
        // A hold may park this transfer only when its part is kept and continued (7.5 power rules, Pause all).
        if (HoldParking.webResumable(resume, partials.get(file.fileName) != null, connection.getHeaderField("Accept-Ranges"),
                partialBytes, appending)) parkable += file.fileName
        val contentLength = response.transfer.totalBytes
        if (file.fileSize <= 0) downloadProgressTracker.learnFileSize(file.fileName, contentLength)
        // A server that announces the hash of the whole body lets the finished file be checked.
        if (startOffset == 0L) {
            (Checksums.fromContentMd5(connection.getHeaderField("Content-MD5")) ?: Checksums.fromDigestHeader(connection.getHeaderField("Digest")))
                ?.let { headerHashes[file.fileName] = it } ?: headerHashes.remove(file.fileName)
        }

        streamWithProgress(inputStream, outputStream, file, contentLength, startOffset, response.transfer.bodyBytes)
        // SAF providers can finalize a write only on close. Extraction/checksums must see a
        // finished file, and a canceled transfer must never run the completion steps.
        currentCoroutineContext().ensureActive()
        try { outputStream.close() } catch (e: IOException) { throw StorageException("Could not finish ${file.fileName}: ${e.message}", e) }
        outputStream = null
        currentCoroutineContext().ensureActive()
        requireSourceChecksum(file, documentFile)
        val expectedSha256 = transferHash.digest().joinToString("") { "%02x".format(it) }
        val commitContext = currentCoroutineContext()
        documentFile = downloadFileManager.commitDocumentFile(file, downloadDirUri.toString(), subPath, documentFile, expectedSha256) { commitContext.ensureActive() }
        partials.remove(file.fileName)
        handlePostDownload(file, documentFile, downloadDirUri, subPath)

    } catch (e: CancellationException) {
        if (!keepPartial) documentFile?.takeIf(downloadFileManager::isStagingFile)?.let { downloadFileManager.deleteFile(it) }
        updateStatus(file.fileName, DownloadStatus.STOPPED)
        throw e
    } catch (e: LowStorageException) {
        // Out of room: keep what is there (when resuming is on) so *Retry* carries on from it.
        if (!keepPartial || partials.get(file.fileName) == null) { documentFile?.takeIf(downloadFileManager::isStagingFile)?.let { downloadFileManager.deleteFile(it) }; partials.remove(file.fileName) }
        throw e
    } catch (e: DownloadVerificationException) {
        documentFile?.takeIf(downloadFileManager::isStagingFile)?.let { downloadFileManager.deleteFile(it) }
        partials.remove(file.fileName)
        _verification.update { it + (file.fileName to VerifyState.MISMATCH) }
        updateStatus(file.fileName, DownloadStatus.FAILED, DownloadFailures.classify(e))
        throw e
    } catch (e: Exception) {
        if (!keepPartial) documentFile?.takeIf(downloadFileManager::isStagingFile)?.let { downloadFileManager.deleteFile(it) }
        updateStatus(file.fileName, DownloadStatus.FAILED, DownloadFailures.classify(e))
        throw e
    } finally {
        parkable.remove(file.fileName)
        // Close independently: one provider error must not leak the other descriptor or
        // replace the CancellationException which makes a pause land on PAUSED.
        try { inputStream?.close() } catch (e: IOException) { Log.w(TAG, "Could not close input for ${file.fileName}: ${e.message}") }
        try { outputStream?.close() } catch (e: IOException) { Log.w(TAG, "Could not close output for ${file.fileName}: ${e.message}") }
        try { activeConnection?.disconnect() } catch (e: Exception) { Log.w(TAG, "Could not disconnect ${file.fileName}: ${e.message}") }
    }
}

/** A known source checksum must pass before an existing completed game can be replaced. */
internal suspend fun DownloadService.requireSourceChecksum(file: DownloadableFileEntity, documentFile: DocumentFile) {
    val expected = Checksums.parse(file.expectedHash) ?: Checksums.parse(headerHashes[file.fileName]) ?: return
    val coroutine = currentCoroutineContext()
    val actual = try {
        context.contentResolver.openInputStream(documentFile.uri)?.use { Checksums.hexOf(it, expected.algo) { coroutine.ensureActive() } }
            ?: throw StorageException("Could not verify downloaded file")
    } catch (e: IOException) { throw StorageException("Could not read downloaded file for verification", e) }
    if (!Checksums.matches(expected, actual)) throw DownloadVerificationException()
}

internal suspend fun DownloadService.streamWithProgress(
    input: InputStream,
    output: OutputStream,
    file: DownloadableFileEntity,
    contentLength: Long,
    startOffset: Long = 0L,
    expectedBodyBytes: Long? = null
) {
    var sinceSpaceCheck = 0L
    var downloaded = startOffset
    val startTime = System.nanoTime() / 1_000_000L
    var lastUpdateTime = startTime
    var lastDownloaded = startOffset

    DownloadStreams.copy(input, output, Constants.BUFFER_SIZE, expectedBytes = expectedBodyBytes) { bytesRead ->
        downloaded += bytesRead
        // One limit for all downloads together (and it follows the setting while downloading).
        bandwidthLimiter.acquire(bytesRead)
        // Every 32 MB: stop (keeping the part) when free space drops below the limit of Settings.
        sinceSpaceCheck += bytesRead
        if (sinceSpaceCheck >= 32L * 1024 * 1024) {
            sinceSpaceCheck = 0
            if (downloadGate.lowOnSpace()) throw LowStorageException(file.fileName)
        }

        val now = System.nanoTime() / 1_000_000L

        val progress = if (contentLength > 0)
            ArchiveExtractionUtils.calculateProgress(downloaded, contentLength) else 0f

        if (downloadProgressTracker.shouldUpdateProgress(progress, lastUpdateTime, now)) {
            val elapsed = (now - lastUpdateTime) / 1000f
            val speedMBs = downloadSpeedController.calculateSpeed(downloaded - lastDownloaded, elapsed)
            downloadProgressTracker.updateDownloadProgress(file.fileName, progress, speedMBs, downloaded)
            lastUpdateTime = now
            lastDownloaded = downloaded
        }
    }
    transferSamples[file.fileName] = (downloaded - startOffset) to (System.nanoTime() / 1_000_000L - startTime)
}

internal suspend fun DownloadService.handlePostDownload(
    file: DownloadableFileEntity,
    documentFile: DocumentFile,
    rootUri: Uri,
    subPath: String
) {
    currentCoroutineContext().ensureActive()
    if (!ArchiveUtils.isExtractable(file.fileExtension) || !settingsRepository.autoUnzip.first()) {
        gamePackages.record(file, rootUri.toString(), subPath,
            listOf(FileParsingUtils.storageFileName(file.fileName)))
        updateStatus(file.fileName, DownloadStatus.COMPLETED)
        _finished.tryEmit(file.fileName)
        verifyInBackground(file, documentFile)
        return
    }

    updateStatus(file.fileName, DownloadStatus.UNZIPPING)
    try {
        val extracted = archiveExtractorService.extractArchive(
            context, documentFile.uri, rootUri, subPath, failOnError = true)
        if (extracted.isNotEmpty()) {
            gamePackages.record(file, rootUri.toString(), subPath, extracted)
            downloadFileManager.deleteFile(documentFile)
            extractedFilesMap[gamePackages.identity(file)] = DownloadedPackageLocation(rootUri.toString(), subPath, extracted)
        } else {
            throw DownloadExtractionException()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Extraction failed for ${file.fileName}: ${e.message}")
        throw if (e is StorageException || e is DownloadExtractionException || e is SecurityException) e else DownloadExtractionException(e)
    }
    currentCoroutineContext().ensureActive()
    updateStatus(file.fileName, DownloadStatus.COMPLETED)
    _finished.tryEmit(file.fileName)
}

/**
 * Compares the finished file with the hash its source published (RomM lists one per game; some
 * servers send `Content-MD5` / `Digest`). Nothing to compare = nothing shown. Runs on its own so
 * the download is already usable while a big file is read through.
 */
internal fun DownloadService.verifyInBackground(file: DownloadableFileEntity, documentFile: DocumentFile) {
    val expected: ExpectedHash = Checksums.parse(file.expectedHash) ?: Checksums.parse(headerHashes.remove(file.fileName))
        ?: run { checkAgainstDat(file, documentFile); return }
    launchBackground("Checking ${file.fileName}") {
        verificationSlots.withPermit {
            _verification.update { it + (file.fileName to VerifyState.CHECKING) }
            val state = try {
                val actual = context.contentResolver.openInputStream(documentFile.uri)?.use { Checksums.hexOf(it, expected.algo) }
                if (actual != null && Checksums.matches(expected, actual)) VerifyState.VERIFIED else VerifyState.MISMATCH
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not check ${file.fileName}: ${e.message}")
                null
            }
            _verification.update { if (state == null) it - file.fileName else it + (file.fileName to state) }
            if (state == VerifyState.MISMATCH) Log.w(TAG, "Checksum of ${file.fileName} differs from the one the source published")
        }
    }
}

/**
 * No hash from the source: when the user imported a DAT for this console, the finished file is
 * looked up in it (see [DatService.checkDownloaded]). No DAT, or a format DATs do not cover = nothing shown.
 */
internal fun DownloadService.checkAgainstDat(file: DownloadableFileEntity, documentFile: DocumentFile) {
    launchBackground("Checking ${file.fileName} against the DAT") {
        verificationSlots.withPermit {
            val check = try {
                datService.checkDownloaded(file.consoleId, documentFile.name ?: file.fileName, documentFile.uri, documentFile.length(), documentFile.lastModified())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not check ${file.fileName} against the DAT: ${e.message}")
                null
            } ?: return@withPermit
            val state = if (check.status == DatStatus.UNKNOWN) VerifyState.DAT_UNKNOWN else VerifyState.DAT_OK
            _verification.update { it + (file.fileName to state) }
        }
    }
}
