package com.tufein.valkyr.data.service

import android.content.Context
import android.net.Uri
import android.util.Log
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import com.tufein.valkyr.util.ArchiveExtractionUtils
import com.tufein.valkyr.util.ArchivePlan
import com.tufein.valkyr.util.ArchiveSafetyException
import com.tufein.valkyr.util.VerifiedCopy
import com.tufein.valkyr.util.Constants
import com.tufein.valkyr.util.ListingCache
import com.tufein.valkyr.util.DownloadExtractionException
import com.tufein.valkyr.util.StorageException
import com.tufein.valkyr.util.StorageAccessException
import com.tufein.valkyr.util.StorageHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.IInStream
import net.sf.sevenzipjbinding.ISeekableStream
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.SevenZipException
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.IOException
import java.util.UUID
import java.nio.ByteBuffer
import java.nio.file.Files
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ArchiveExtractorService"

@Singleton
class ArchiveExtractorService @Inject constructor() {
    private val publishLock = Mutex()
    private data class CachedFile(val path: String, val file: File, val hash: String)

    /**
     * Extracts an archive from a SAF URI (used for HTTP downloads where the file
     * lands in SAF storage directly).
     */
    suspend fun extractArchive(
        context: Context,
        archiveUri: Uri,
        destinationUri: Uri,
        subPath: String = "",
        failOnError: Boolean = false,
        onProgress: (Float) -> Unit = {}
    ): List<String> = withContext(Dispatchers.IO) {
        var extractDir: File? = null
        val extractionContext = currentCoroutineContext()

        try {
            val currentExtractDir = createExtractionDirectory(context).also { extractDir = it }
            // Open the SAF archive as a seekable FileChannel — no need to copy the archive
            // to a temp file first, saving potentially gigabytes of cache space.
            val pfd = context.contentResolver.openFileDescriptor(archiveUri, "r")
                ?: throw StorageAccessException()

            val cachedFiles = pfd.use {
                FileInputStream(it.fileDescriptor).use { fis ->
                    extractToCache(fis, currentExtractDir, onProgress) { extractionContext.ensureActive() }
                }
            }
            if (cachedFiles.isEmpty()) return@withContext emptyList<String>()

            publishLock.withLock { copyToSaf(context, cachedFiles, destinationUri, subPath) { extractionContext.ensureActive() } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            extractionContext.ensureActive()
            Log.e(TAG, "Extraction failed: ${e.message}", e)
            if (failOnError) throw if (e is StorageException || e is SecurityException || e is DownloadExtractionException) e else DownloadExtractionException(e)
            emptyList()
        } finally {
            extractDir?.deleteRecursively()
        }
    }

    /**
     * Extracts an archive that is already on the local filesystem (used for torrent
     * downloads where the file lives in cacheDir). Avoids writing the compressed
     * archive to SAF at all — extracts straight to a temp dir then copies to SAF.
     */
    suspend fun extractArchiveFile(
        context: Context,
        archiveFile: File,
        destinationUri: Uri,
        subPath: String = "",
        failOnError: Boolean = false,
        onProgress: (Float) -> Unit = {}
    ): List<String> = withContext(Dispatchers.IO) {
        var extractDir: File? = null
        val extractionContext = currentCoroutineContext()

        try {
            val currentExtractDir = createExtractionDirectory(context).also { extractDir = it }
            val cachedFiles = FileInputStream(archiveFile).use { fis ->
                extractToCache(fis, currentExtractDir, onProgress) { extractionContext.ensureActive() }
            }
            if (cachedFiles.isEmpty()) return@withContext emptyList<String>()

            publishLock.withLock { copyToSaf(context, cachedFiles, destinationUri, subPath) { extractionContext.ensureActive() } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            extractionContext.ensureActive()
            Log.e(TAG, "Extraction of ${archiveFile.name} failed: ${e.message}", e)
            if (failOnError) throw if (e is StorageException || e is SecurityException || e is DownloadExtractionException) e else DownloadExtractionException(e)
            emptyList()
        } finally {
            extractDir?.deleteRecursively()
        }
    }

    private fun createExtractionDirectory(context: Context): File {
        // A timestamp is shared by simultaneous bulk completions; each extraction needs its
        // own directory so another worker cannot overwrite/delete its temporary files.
        val parent = File(context.cacheDir, "extraction_temp")
        if (!parent.mkdirs() && !parent.isDirectory) throw StorageException("Could not create extraction cache")
        return try {
            Files.createTempDirectory(parent.toPath(), "archive-").toFile()
        } catch (e: IOException) {
            throw StorageException("Could not create extraction cache", e)
        }
    }

    private fun extractToCache(
        fis: FileInputStream,
        extractDir: File,
        onProgress: (Float) -> Unit,
        checkActive: () -> Unit
    ): List<CachedFile> {
        val results = mutableListOf<CachedFile>()
        val channel = fis.channel
        val channelSize = channel.size()
        val inStream = object : IInStream {
            override fun read(data: ByteArray): Int {
                checkActive()
                val n = channel.read(ByteBuffer.wrap(data))
                return if (n == -1) 0 else n
            }
            override fun seek(offset: Long, seekOrigin: Int): Long {
                checkActive()
                val position = when (seekOrigin) {
                    ISeekableStream.SEEK_SET -> offset
                    ISeekableStream.SEEK_CUR -> channel.position() + offset
                    ISeekableStream.SEEK_END -> channelSize + offset
                    else -> throw SevenZipException("Unknown seek origin: $seekOrigin")
                }
                channel.position(position)
                return position
            }
            override fun close() = Unit
        }
        try {
            SevenZip.openInArchive(null, inStream).use { archive ->
                // Validate every path before extraction. A late duplicate or traversal must
                // never leave an apparently successful, incomplete collection behind.
                if (!ArchiveExtractionUtils.validateEntryCount(archive.numberOfItems)) {
                    throw ArchiveSafetyException(ArchiveSafetyException.Reason.TOO_MANY_ENTRIES)
                }
                val entries = (0 until archive.numberOfItems).map { index ->
                    checkActive()
                    if (archive.getProperty(index, PropID.IS_ANTI) == true ||
                        archive.getProperty(index, PropID.IS_ALT_STREAM) == true ||
                        !((archive.getProperty(index, PropID.SYM_LINK) as? String).isNullOrEmpty()) ||
                        !((archive.getProperty(index, PropID.HARD_LINK) as? String).isNullOrEmpty())) {
                        throw ArchiveSafetyException(ArchiveSafetyException.Reason.UNSAFE_PATH)
                    }
                    ArchivePlan.Entry(index,
                        (archive.getProperty(index, PropID.PATH) as? String) ?: "file_$index",
                        archive.getProperty(index, PropID.IS_FOLDER) as? Boolean ?: false,
                        (archive.getProperty(index, PropID.SIZE) as? Number)?.toLong())
                }
                val plan = ArchivePlan.check(entries)
                val budget = (StatFs(extractDir.path).availableBytes - CACHE_RESERVE_BYTES).coerceAtLeast(0L)
                if (plan.knownBytes > budget) throw ArchiveSafetyException(ArchiveSafetyException.Reason.INSUFFICIENT_SPACE)
                val planned = plan.files.associateBy { it.index }
                var writtenTotal = 0L
                val callback = object : IArchiveExtractCallback {
                    var out: OutputStream? = null
                    var current: ArchivePlan.FileEntry? = null
                    var dest: File? = null
                    var writtenEntry = 0L
                    override fun getStream(index: Int, mode: ExtractAskMode): ISequentialOutStream? {
                        checkActive()
                        val entry = planned[index] ?: return null
                        val file = File(extractDir, entry.path)
                        if (!file.parentFile!!.mkdirs() && !file.parentFile!!.isDirectory) {
                            throw StorageException("Could not create extracted cache folder")
                        }
                        val stream = try {
                            BufferedOutputStream(FileOutputStream(file), Constants.EXTRACTION_BUFFER_SIZE)
                        } catch (error: IOException) {
                            throw StorageException("Could not create extracted cache file", error)
                        }
                        out = stream
                        current = entry
                        dest = file
                        writtenEntry = 0L
                        return ISequentialOutStream { data ->
                            checkActive()
                            if (data.size.toLong() > budget - writtenTotal) {
                                throw ArchiveSafetyException(ArchiveSafetyException.Reason.INSUFFICIENT_SPACE)
                            }
                            if (entry.size != null && data.size.toLong() > entry.size - writtenEntry) throw DownloadExtractionException()
                            try { stream.write(data) } catch (e: IOException) {
                                throw StorageException("Could not write extracted cache file", e)
                            }
                            writtenTotal += data.size
                            writtenEntry += data.size
                            data.size
                        }
                    }
                    override fun prepareOperation(mode: ExtractAskMode) = Unit
                    override fun setOperationResult(result: ExtractOperationResult) {
                        checkActive()
                        try { out?.close() } catch (e: IOException) {
                            throw StorageException("Could not finish extracted cache file", e)
                        }
                        out = null
                        val entry = current
                        if (entry != null) {
                            if (result != ExtractOperationResult.OK || (entry.size != null && writtenEntry != entry.size)) {
                                throw DownloadExtractionException()
                            }
                            val file = requireNotNull(dest)
                            results += CachedFile(entry.path, file, VerifiedCopy.hash(file.inputStream(), checkActive))
                            onProgress(results.size.toFloat() / plan.files.size.coerceAtLeast(1))
                        }
                        current = null
                        dest = null
                    }
                    override fun setCompleted(complete: Long) = Unit
                    override fun setTotal(total: Long) = Unit
                }
                try { archive.extract(null, false, callback) }
                finally {
                    try { callback.out?.close() } catch (e: Exception) { Log.w(TAG, "Could not close interrupted extraction output", e) }
                    callback.out = null
                }
                if (results.size != plan.files.size) throw DownloadExtractionException()
            }
        } catch (e: SevenZipException) {
            checkActive()
            throw DownloadExtractionException(e)
        }
        return results
    }

    /** Checks the complete destination first, stages every new file, and verifies readback. */
    private fun copyToSaf(
        context: Context,
        files: List<CachedFile>,
        destinationUri: Uri,
        subPath: String,
        checkActive: () -> Unit
    ): List<String> {
        val destUri = ArchiveExtractionUtils.prepareExtractionDestination(context, destinationUri, subPath)
        val root = StorageHelper.getDocumentFile(context, destUri.toString())
            ?: throw ArchiveSafetyException(ArchiveSafetyException.Reason.DESTINATION_UNAVAILABLE)
        fun hash(document: DocumentFile): String = VerifiedCopy.hash(
            context.contentResolver.openInputStream(document.uri) ?: throw StorageException("Extracted destination cannot be read"), checkActive)
        // One listing per folder while the destination is only checked and staged (one provider call
        // instead of one per file); it is emptied before the publish step, which must see the folders as they are then.
        val listings = ListingCache<String, DocumentFile>()
        var cached = true
        fun child(directory: DocumentFile, name: String): DocumentFile? {
            val all = if (cached) listings.list(directory.uri.toString()) { directory.listFiles().toList() } else directory.listFiles().toList()
            val matches = all.filter { ArchivePlan.portableKey(it.name.orEmpty()) == ArchivePlan.portableKey(name) }
            if (matches.size > 1 || matches.any { it.name != name }) {
                throw ArchiveSafetyException(ArchiveSafetyException.Reason.DESTINATION_CONFLICT)
            }
            return matches.singleOrNull()
        }
        fun createFolder(inside: DocumentFile, name: String): DocumentFile {
            listings.invalidate(inside.uri.toString())
            return inside.createDirectory(name) ?: throw ArchiveSafetyException(ArchiveSafetyException.Reason.DESTINATION_UNAVAILABLE)
        }
        fun parent(path: String, create: Boolean): DocumentFile? {
            var directory = root
            for (name in path.split('/').dropLast(1)) {
                val existing = child(directory, name)
                if (existing != null && !existing.isDirectory) throw ArchiveSafetyException(ArchiveSafetyException.Reason.DESTINATION_CONFLICT)
                directory = existing ?: if (!create) return null else createFolder(directory, name)
                if (directory.name != name || !directory.isDirectory) throw ArchiveSafetyException(ArchiveSafetyException.Reason.DESTINATION_UNAVAILABLE)
            }
            return directory
        }
        var requiredBytes = 0L
        for (file in files) {
            checkActive()
            val existing = parent(file.path, create = false)?.let { child(it, file.path.substringAfterLast('/')) }
            if (existing != null) {
                if (!existing.isFile || hash(existing) != file.hash) throw ArchiveSafetyException(ArchiveSafetyException.Reason.DESTINATION_CONFLICT)
            } else {
                if (file.file.length() > Long.MAX_VALUE - requiredBytes) throw ArchiveSafetyException(ArchiveSafetyException.Reason.INSUFFICIENT_SPACE)
                requiredBytes += file.file.length()
            }
        }
        StorageHelper.getFreeBytes(context, destinationUri.toString())?.let {
            if (requiredBytes > it) throw ArchiveSafetyException(ArchiveSafetyException.Reason.INSUFFICIENT_SPACE)
        }
        data class Staged(val source: CachedFile, val directory: DocumentFile, val document: DocumentFile)
        val stages = mutableListOf<Staged>()
        val published = mutableListOf<Pair<DocumentFile, String>>()
        try {
            for (file in files) {
                checkActive()
                val directory = requireNotNull(parent(file.path, create = true))
                val name = file.path.substringAfterLast('/')
                child(directory, name)?.let {
                    if (!it.isFile || hash(it) != file.hash) throw ArchiveSafetyException(ArchiveSafetyException.Reason.DESTINATION_CONFLICT)
                    return@let
                } ?: run {
                    val stage = directory.createFile("application/octet-stream", ".dogmatix-extract-${UUID.randomUUID()}.part")
                        ?: throw StorageException("Could not stage extracted destination file")
                    stages += Staged(file, directory, stage)
                    val input = file.file.inputStream()
                    val output = try {
                        context.contentResolver.openOutputStream(stage.uri, "wt")
                            ?: throw StorageException("Could not write extracted destination file")
                    } catch (error: Exception) {
                        input.close()
                        throw error
                    }
                    val copiedHash = VerifiedCopy.transfer(input, output, checkActive)
                    VerifiedCopy.requireSame(file.hash, copiedHash)
                    VerifiedCopy.requireSame(file.hash, hash(stage))
                }
            }
            // No destination content has changed before all streams have passed readback.
            cached = false
            listings.clear()
            for (stage in stages) {
                checkActive()
                val name = stage.source.path.substringAfterLast('/')
                val existing = child(stage.directory, name)
                if (existing != null) {
                    if (!existing.isFile || hash(existing) != stage.source.hash) throw ArchiveSafetyException(ArchiveSafetyException.Reason.DESTINATION_CONFLICT)
                    if (!stage.document.delete()) throw StorageException("Could not remove extracted staging file")
                    continue
                }
                if (!stage.document.renameTo(name) || stage.document.name != name) throw StorageException("Storage does not support a safe extraction rename")
                published += stage.document to stage.source.hash
                VerifiedCopy.requireSame(stage.source.hash, hash(stage.document))
            }
            checkActive()
            // Re-check reused targets as well: a file edited during staging cannot justify
            // deleting the source archive after this method returns.
            for (file in files) {
                val target = parent(file.path, create = false)?.let { child(it, file.path.substringAfterLast('/')) }
                    ?: throw StorageException("Extracted file disappeared before verification")
                if (!target.isFile || hash(target) != file.hash) throw ArchiveSafetyException(ArchiveSafetyException.Reason.DESTINATION_CONFLICT)
            }
            return files.map { it.path }
        } catch (error: Throwable) {
            // Delete only our own unchanged publications. A concurrently edited file stays.
            for ((document, expected) in published.asReversed()) {
                runCatching {
                    val input = context.contentResolver.openInputStream(document.uri) ?: return@runCatching
                    if (VerifiedCopy.hash(input) == expected) document.delete()
                }.onFailure { Log.w(TAG, "Could not roll back extracted file", it) }
            }
            throw error
        } finally {
            stages.forEach { stage ->
                if (stage.document.name?.startsWith(".dogmatix-extract-") == true) runCatching { stage.document.delete() }
            }
        }
    }

    private companion object {
        const val CACHE_RESERVE_BYTES = 8L * 1024L * 1024L
    }
}
