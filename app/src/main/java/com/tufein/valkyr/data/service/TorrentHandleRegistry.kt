package com.tufein.valkyr.data.service

import android.content.Context
import android.util.Log
import com.tufein.valkyr.data.local.TorrentTrackerSettings
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.util.FileParsingUtils
import com.tufein.valkyr.util.TorrentConstants
import com.tufein.valkyr.util.TorrentTrackers
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AnnounceEntry
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TorrentHandleRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val progressBridge: TorrentProgressBridge,
    private val trackerSettings: TorrentTrackerSettings
) {

    private val session = SessionManager()
    private val sessionLock = Any()
    private var listenerRegistered = false
    private val handles = ConcurrentHashMap<String, TorrentHandle>()
    private val fetchMutexes = ConcurrentHashMap<String, Mutex>()

    var handleCount: Int = 0
        private set

    private fun start() = synchronized(sessionLock) {
        if (!session.isRunning) {
            if (!listenerRegistered) {
                session.addListener(progressBridge)
                listenerRegistered = true
            }
            session.start()
            // libtorrent rejects info dicts above 3 MiB by default (peers get disconnected with
            // "metadata too large" and re-tried forever); multi-TB collection torrents need more.
            session.applySettings(
                SettingsPack().apply { setMaxMetadataSize(TorrentConstants.MAX_METADATA_SIZE_BYTES) }
                    .downloadRateLimit(rateLimit.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
            )
            TorrentConstants.DHT_BOOTSTRAP_NODES.forEach { (host, port) ->
                try { session.swig().add_dht_node(org.libtorrent4j.swig.string_int_pair(host, port)) }
                catch (e: Exception) { Log.w(TAG, "DHT node $host:$port failed: ${e.message}") }
            }
            Log.i(TAG, "libtorrent4j session started")
        }
    }

    /** Download limit of the whole torrent session in bytes per second; 0 = none. Kept for a session started later. */
    @Volatile private var rateLimit: Long = 0

    fun setDownloadRateLimit(bytesPerSecond: Long) = synchronized(sessionLock) {
        rateLimit = bytesPerSecond
        if (session.isRunning) {
            session.applySettings(SettingsPack().downloadRateLimit(bytesPerSecond.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()))
        }
    }

    fun stop() = synchronized(sessionLock) {
        if (session.isRunning) {
            handles.values.forEach { if (it.isValid) it.pause() }
            handles.clear()
            fetchMutexes.clear()
            handleCount = 0
            session.stop()
            Log.i(TAG, "libtorrent4j session stopped")
        }
    }

    val isRunning: Boolean get() = session.isRunning

    suspend fun getOrFetch(uri: String): TorrentHandle = withContext(Dispatchers.IO) {
        if (!session.isRunning) {
            start()
        }
        val optimizedUri = if (uri.startsWith("magnet:")) FileParsingUtils.optimizeMagnetUri(uri) else uri
        handles[optimizedUri]?.takeIf { it.isValid }?.let { return@withContext it }
        val mutex = fetchMutexes.getOrPut(optimizedUri) { Mutex() }
        mutex.withLock {
            handles[optimizedUri]?.takeIf { it.isValid }?.let { return@withLock it }
            Log.d(TAG, "Fetching metadata for $optimizedUri")
            val handle = fetchMetadata(optimizedUri)
            handles[optimizedUri] = handle
            handleCount = handles.size
            Log.i(TAG, "Metadata cached (${handles.size} total handles)")
            handle
        }
    }

    fun getCachedHandle(magnet: String): TorrentHandle? {
        val optimizedMagnet = if (magnet.startsWith("magnet:")) FileParsingUtils.optimizeMagnetUri(magnet) else magnet
        return handles[optimizedMagnet]?.takeIf { it.isValid }
    }

    fun getCachedInfo(magnet: String): TorrentInfo? {
        val optimizedMagnet = if (magnet.startsWith("magnet:")) FileParsingUtils.optimizeMagnetUri(magnet) else magnet
        return handles[optimizedMagnet]?.takeIf { it.isValid }?.torrentFile()
    }

    fun releaseHandle(magnet: String) {
        val optimizedMagnet = if (magnet.startsWith("magnet:")) FileParsingUtils.optimizeMagnetUri(magnet) else magnet
        handles.remove(optimizedMagnet)?.let { handle ->
            if (handle.isValid) {
                try {
                    // Nothing else tracks this torrent any more: drop whatever it left in
                    // cacheDir/torrent_data (cancelled or failed partials would pile up otherwise).
                    // The files are deleted here, synchronously, NOT via remove(DELETE_FILES):
                    // libtorrent runs that deletion asynchronously, and when the next queued
                    // download re-adds the same torrent right after this release, the pending
                    // deletion wipes the fresh download's files (FILE_ERROR → failed downloads).
                    val partials = partialFilePaths(handle)
                    // libtorrent keeps pieces of IGNOREd sibling files in ".<infohash>.parts";
                    // stale partfiles must not be reused by a later re-add of the same magnet.
                    val partfile = try { ".${handle.infoHash()}.parts" } catch (e: Exception) { null }
                    session.remove(handle)
                    deleteFromCache(partials + listOfNotNull(partfile))
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing torrent: ${e.message}")
                }
            }
        }
        fetchMutexes.remove(optimizedMagnet)
        handleCount = handles.size
        Log.i(TAG, "Released handle for $optimizedMagnet (${handles.size} remaining)")
    }

    /** Relative paths (within torrent_data) of this torrent's files that have any bytes on disk. */
    private fun partialFilePaths(handle: TorrentHandle): List<String> = try {
        val info = handle.torrentFile() ?: return emptyList()
        val progress = handle.fileProgress()
        (0 until info.numFiles())
            .filter { it < progress.size && progress[it] > 0L }
            .map { info.files().filePath(it) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun deleteFromCache(relativePaths: List<String>) {
        if (relativePaths.isEmpty()) return
        val root = File(context.cacheDir, "torrent_data")
        var deleted = 0
        relativePaths.forEach { path ->
            val f = File(root, path)
            if (f.exists() && f.delete()) deleted++
            // Drop now-empty parents up to (but not including) torrent_data itself.
            var dir = f.parentFile
            while (dir != null && dir != root && dir.list()?.isEmpty() == true) {
                if (!dir.delete()) break
                dir = dir.parentFile
            }
        }
        if (deleted > 0) Log.i(TAG, "Deleted $deleted partial file(s) from torrent cache")
    }

    /** Metadata of magnets fetched before, so a rescan does not ask the swarm again (a magnet's content never changes). */
    private val metadataCache = File(context.filesDir, "torrent_meta")

    private fun cacheFileFor(uri: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(metadataCache, "$digest.torrent")
    }

    private fun cachedInfo(uri: String): TorrentInfo? {
        val file = cacheFileFor(uri)
        if (!file.isFile) return null
        return try {
            TorrentInfo(file)
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable cached metadata, fetching again: ${e.message}")
            file.delete()
            null
        }
    }

    private fun storeInfo(uri: String, info: TorrentInfo) {
        try {
            metadataCache.mkdirs()
            val target = cacheFileFor(uri)
            val tmp = File(metadataCache, target.name + ".tmp")
            tmp.writeBytes(torrentFileBytes(info))
            if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not cache metadata: ${e.message}")
        }
    }

    /** A minimal .torrent (`d4:info…e`) around the info dictionary, which is all [TorrentInfo] needs. */
    private fun torrentFileBytes(info: TorrentInfo): ByteArray {
        val span = info.swig().get_info_section()
        val size = span.size().toInt()
        val head = "d4:info".toByteArray(Charsets.US_ASCII)
        val out = ByteArray(head.size + size + 1)
        head.copyInto(out)
        for (i in 0 until size) out[head.size + i] = span.at(i.toLong())
        out[out.lastIndex] = 'e'.code.toByte()
        return out
    }

    private suspend fun fetchMetadata(uri: String): TorrentHandle =
        withContext(Dispatchers.IO) {
            val cached = if (uri.startsWith("magnet:")) cachedInfo(uri) else null
            val params = if (uri.startsWith("magnet:")) {
                // The magnet keeps its trackers; known metadata makes the handle complete at once.
                AddTorrentParams.parseMagnetUri(uri).also { p -> cached?.let { p.torrentInfo = it } }
            } else {
                val torrentFile = File(uri)
                val ti = TorrentInfo(torrentFile)
                val p = AddTorrentParams()
                p.torrentInfo = ti
                p
            }

            // Use cache directory for libtorrent metadata and downloads
            val torrentDataDir = File(context.cacheDir, "torrent_data").apply { mkdirs() }
            
            val swigParams = params.swig()
            swigParams.save_path = torrentDataDir.absolutePath

            // add_torrent may return an existing native handle for another magnet spelling.
            // Ownership must be decided together with the add, rather than removing a sibling's
            // active handle when this metadata request is canceled.
            val (handle, newlyAdded) = synchronized(sessionLock) {
                val infoHash = swigParams.info_hashes.v1
                val existingBefore = session.swig().find_torrent(infoHash)
                val existed = existingBefore != null && existingBefore.is_valid
                val ec = org.libtorrent4j.swig.error_code()
                val swigHandle = try {
                    session.swig().add_torrent(swigParams, ec)
                } catch (e: Exception) {
                    null
                }
                val resolved = if (swigHandle != null && swigHandle.is_valid) {
                    TorrentHandle(swigHandle)
                } else {
                    val existingSwig = session.swig().find_torrent(infoHash)
                    if (existingSwig != null && existingSwig.is_valid) {
                        TorrentHandle(existingSwig)
                    } else {
                        val errorMsg = if (ec.value() != 0) ec.message() else "unknown error"
                        throw Exception("Failed to add torrent: $errorMsg [$uri]")
                    }
                }
                resolved to !existed
            }

            // Sources tracker: the user's announce addresses join the torrent's own list. Doing this
            // on the handle (not only in the magnet URI) covers .torrent sources and metadata-cache
            // hits, whose cached files carry no announce list at all.
            TorrentTrackers.enabled(trackerSettings.entries.first()).forEach { url ->
                runCatching { handle.addTracker(AnnounceEntry(url)) }
                    .onFailure { Log.w(TAG, "Could not add tracker $url: ${it.message}") }
            }

            withMetadataPreflight(handle, newlyAdded, ::discardUncachedMetadataHandle) { pending ->
                // Immediately pause to prevent background downloading.
                try {
                    pending.pause()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to pause handle: ${e.message}")
                }

                // Upload mode still exchanges metadata but never requests pieces.
                try { pending.setFlags(TorrentFlags.UPLOAD_MODE) } catch (e: Exception) { Log.w(TAG, "upload_mode: ${e.message}") }
                pending.resume()
                val ready = waitForMetadata(pending, uri)
                if (cached != null) Log.i(TAG, "Metadata from cache for $uri")
                else if (uri.startsWith("magnet:")) ready.torrentFile()?.let { storeInfo(uri, it) }
                // Cancellation while writing a metadata cache must not publish an orphan.
                currentCoroutineContext().ensureActive()
                ready
            }
        }

    /** Only an unpublished handle created by this fetch can be removed; backing files stay. */
    private fun discardUncachedMetadataHandle(handle: TorrentHandle) = synchronized(sessionLock) {
        if (!handle.isValid) return
        val torrentId = handle.infoHash().toString()
        val cachedElsewhere = handles.values.any { it.isValid && it.infoHash().toString() == torrentId }
        if (cachedElsewhere || progressBridge.countTrackedForHandle(handle) > 0) return
        // Upload mode fetched no pieces. DELETE_FILES would race another fetch and could erase
        // siblings' cached data, so cancellation only removes this unowned native handle.
        session.swig().remove_torrent(handle.swig())
    }

    /**
     * Waits for the torrent's metadata. The configured timeout is an *inactivity* timeout: it is
     * re-armed every time the swarm shows signs of life (bytes received — ut_metadata pieces count
     * as protocol traffic in [TorrentStatus.totalDownload] — or new peers connected), so huge
     * info dicts (e.g. whole-archive torrents with hundreds of thousands of files) can take
     * minutes as long as they keep progressing.
     */
    private suspend fun waitForMetadata(handle: TorrentHandle, uri: String): TorrentHandle {
        val timeoutS = settingsRepository.metadataTimeoutSeconds.first()
        val timeoutMs = timeoutS * 1000L
        var lastDownloaded = -1L
        var lastPeers = -1
        var deadline = System.currentTimeMillis() + timeoutMs
        val result = withTimeoutOrNull(timeoutMs * MAX_INACTIVITY_RESETS) {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    if (handle.isValid && handle.torrentFile() != null) {
                        // Once we have metadata, stop everything
                        handle.pause()
                        val info = handle.torrentFile()
                        if (info != null) {
                            val priorities = Array(info.numFiles()) { Priority.IGNORE }
                            handle.prioritizeFiles(priorities)
                        }
                        return@withTimeoutOrNull handle
                    }
                    if (handle.isValid) {
                        val status = handle.status()
                        val downloaded = status.totalDownload()
                        val peers = status.numPeers()
                        if (downloaded > lastDownloaded || peers > lastPeers) {
                            if (lastDownloaded >= 0 && downloaded > lastDownloaded) {
                                Log.d(TAG, "Metadata progress: $downloaded bytes, $peers peers")
                            }
                            deadline = System.currentTimeMillis() + timeoutMs
                        }
                        lastDownloaded = maxOf(lastDownloaded, downloaded)
                        lastPeers = maxOf(lastPeers, peers)
                    }
                } catch (e: Exception) {
                    return@withTimeoutOrNull null
                }
                if (System.currentTimeMillis() >= deadline) return@withTimeoutOrNull null
                delay(TorrentConstants.METADATA_POLL_INTERVAL_MS)
            }
            null
        }

        if (result == null) {
            throw TorrentMetadataTimeoutException(
                "Metadata fetch timed out after ${timeoutS}s without progress for: $uri"
            )
        }
        handle.pause()
        return handle
    }

    companion object {
        private const val TAG = "TorrentHandleRegistry"
        /** Hard cap: a fetch can never last more than this many inactivity windows. */
        private const val MAX_INACTIVITY_RESETS = 30
    }
}

class TorrentMetadataTimeoutException(message: String) : Exception(message)

/** The native preflight owns its newly created resource until it succeeds and can be cached. */
internal suspend fun <T> withMetadataPreflight(
    handle: T,
    newlyAdded: Boolean,
    discard: (T) -> Unit,
    fetch: suspend (T) -> T
): T = try {
    fetch(handle)
} catch (failure: Throwable) {
    if (newlyAdded) {
        try {
            discard(handle)
        } catch (cleanupFailure: Exception) {
            failure.addSuppressed(cleanupFailure)
        }
    }
    throw failure
}
