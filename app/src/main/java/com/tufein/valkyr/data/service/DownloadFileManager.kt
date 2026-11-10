package com.tufein.valkyr.data.service

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.tufein.valkyr.data.local.entity.DownloadableFileEntity
import com.tufein.valkyr.data.model.DownloadItemModel
import com.tufein.valkyr.data.model.DownloadStatus
import com.tufein.valkyr.data.repository.ConsoleRepository
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.util.FileParsingUtils
import com.tufein.valkyr.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.OutputStream
import java.security.MessageDigest
import com.tufein.valkyr.util.StorageException
import com.tufein.valkyr.util.VerifiedCopy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import android.annotation.SuppressLint
import com.tufein.valkyr.util.GameRemoval
import java.io.IOException

@Singleton
class DownloadFileManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val consoleRepository: ConsoleRepository,
    private val pathResolver: ConsoleDownloadPathResolver,
    private val trash: TrashService
) {
    private val storageOwners = context.getSharedPreferences("download_storage_owners", Context.MODE_PRIVATE)

    private fun identity(file: DownloadableFileEntity): String = "${file.consoleId}\n${file.fileName}"
    private fun hashName(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    fun stagingName(file: DownloadableFileEntity): String = ".dogmatix-download-${hashName(identity(file))}.part"
    fun isStagingFile(document: DocumentFile): Boolean = document.name?.let { it.startsWith(".dogmatix-download-") && it.endsWith(".part") } == true

    fun createDownloadItem(file: DownloadableFileEntity): DownloadItemModel {
        return DownloadItemModel(
            name = file.name,
            fileName = file.fileName,
            downloadSpeed = 0f,
            progress = 0f,
            status = DownloadStatus.DOWNLOADING,
            downloadedBytes = 0L,
            fileSize = file.fileSize,
            startedAt = System.currentTimeMillis()
        )
    }

    fun createDocumentFile(
        file: DownloadableFileEntity,
        downloadDirectoryUri: String,
        subPath: String
    ): DocumentFile? {
        // Validate the eventual basename first, but write only into this download's own part.
        if (runCatching { FileParsingUtils.storageFileName(file.fileName) }.isFailure) return null
        val directory = StorageHelper.createDirectory(context, downloadDirectoryUri, subPath) ?: return null
        val name = stagingName(file)
        val previous = runCatching { directory.findFile(name) }.getOrElse { return null }
        if (previous != null && (!previous.isFile || !runCatching { previous.delete() }.getOrDefault(false))) return null
        return StorageHelper.createFile(
            context = context,
            uriString = downloadDirectoryUri,
            subPath = subPath,
            fileName = name,
            mimeType = "application/octet-stream",
            overwrite = true
        )
    }

    /** Only the app's separately named part is resumed; completed/legacy games are never appended. */
    fun findExistingFile(file: DownloadableFileEntity, downloadDirectoryUri: String, subPath: String): DocumentFile? {
        val name = stagingName(file)
        return runCatching { StorageHelper.createDirectory(context, downloadDirectoryUri, subPath)?.findFile(name)?.takeIf { it.isFile } }.getOrNull()
    }

    /** Publishes only after stream close/readback; indexed links sharing a basename cannot silently replace each other. */
    // Publication requires the durable write result; asynchronous/KTX edits cannot confirm it.
    @SuppressLint("ApplySharedPref", "UseKtx")
    @Synchronized
    fun commitDocumentFile(file: DownloadableFileEntity, downloadDirectoryUri: String, subPath: String,
        staged: DocumentFile, expectedSha256: String, check: () -> Unit = {}): DocumentFile {
        check()
        val directory = StorageHelper.createDirectory(context, downloadDirectoryUri, subPath)
            ?: throw StorageException("Destination directory is unavailable")
        val name = FileParsingUtils.storageFileName(file.fileName)
        val key = hashName("${directory.uri}\n$name")
        val owner = storageOwners.getString(key, null)
        val current = directory.findFile(name)
        if (current != null && owner != null && owner != identity(file)) {
            val actual = context.contentResolver.openInputStream(current.uri)?.let { VerifiedCopy.hash(it, check) }
            if (actual != expectedSha256) throw StorageException("Different indexed files have the same storage name")
        }
        val reserveOwner = current == null || owner == null || owner == identity(file)
        // Persist before any original can be replaced, including process death during publish.
        if (reserveOwner && !storageOwners.edit().putString(key, identity(file)).commit())
            throw StorageException("Could not reserve the downloaded file name")
        try {
            return StorageHelper.publishStagedFile(context, directory, staged, name, expectedSha256, check)
        } catch (e: Exception) {
            if (reserveOwner && owner != identity(file)) {
                val rollback = storageOwners.edit()
                if (owner == null) rollback.remove(key) else rollback.putString(key, owner)
                // A failed rollback keeps the conservative reservation; never publish on the
                // assumption that an unsuccessful preference write was durable.
                if (!rollback.commit()) {
                    // Restore the in-memory fence too: SharedPreferences changes memory even
                    // when its disk write fails. The pre-publish reservation remains durable.
                    storageOwners.edit().putString(key, identity(file)).commit()
                }
            }
            throw if (e is IOException) StorageException("Could not verify or publish downloaded file", e) else e
        }
    }

    fun getAppendOutputStream(documentFile: DocumentFile): OutputStream? =
        runCatching { context.contentResolver.openOutputStream(documentFile.uri, "wa") }.getOrNull()

    fun getOutputStream(documentFile: DocumentFile): OutputStream? {
        return StorageHelper.getOutputStream(context, documentFile)
    }

    suspend fun deleteFile(documentFile: DocumentFile): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                StorageHelper.deleteFile(documentFile)
            } catch (_: Exception) {
                false
            }
        }
    }

    suspend fun deleteFileByName(
        file: DownloadableFileEntity,
        deleteFile: Boolean = false,
        extractedFiles: List<String> = emptyList(),
        recordedRootUri: String? = null,
        recordedSubPath: String? = null
    ): Boolean {
        if (!deleteFile) return true

        return try {
            val downloadDirectoryUri = recordedRootUri?.toUri() ?: getDownloadDirectoryUri(file)
            val subPath = recordedSubPath ?: getSubPath(file)
            val decodedFileName = FileParsingUtils.storageFileName(file.fileName)

            val directory = StorageHelper.createDirectory(
                context = context,
                uriString = downloadDirectoryUri.toString(),
                subPath = subPath
            ) ?: return false

            val names = (extractedFiles + decodedFileName).distinct()
            val plan = names.filter { StorageHelper.safeRelativePath(it) && GameRemoval.safeReference(it.substringAfterLast('/')) }.mapNotNull { name ->
                val owner = storageOwners.getString(hashName("${directory.uri}\n$name"), null)
                if (name == decodedFileName && owner != null && owner != identity(file)) return@mapNotNull null
                StorageHelper.findFile(directory, name)?.takeIf { it.isFile }?.let {
                    RemovalFile(it.uri.toString(), directory.uri.toString(), it.name ?: name.substringAfterLast('/'), it.length(), path = name, relativePath = name.takeIf { '/' in it })
                }
            }
            trash.move(plan, file.name, file.consoleId, file.fileName) > 0
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getDownloadDirectoryUri(file: DownloadableFileEntity): Uri {
        val uriString = settingsRepository.consoleDownloadDirectories.first()[file.consoleId]
            ?: settingsRepository.downloadDirectory.first()

        if (uriString.isEmpty()) return Uri.EMPTY

        val uri = uriString.toUri()
        try {
            val df = DocumentFile.fromTreeUri(context, uri)
            if (df == null || !df.exists() || !df.canWrite()) {
                Log.e("DownloadFileManager", "Tree URI is no longer valid: $uriString")
                return Uri.EMPTY
            }
        } catch (e: Exception) {
            Log.e("DownloadFileManager", "Error validating Tree URI: ${e.message}")
            return Uri.EMPTY
        }

        return uri
    }

    suspend fun getSubPath(file: DownloadableFileEntity): String {
        if (consoleRepository.getConsoleById(file.consoleId) == null) {
            val separateByConsole = settingsRepository.separateByConsole.first()
            val hasCustomDir = settingsRepository.consoleDownloadDirectories.first().containsKey(file.consoleId)
            return if (separateByConsole && !hasCustomDir) "Unknown" else ""
        }
        return pathResolver.resolve(settingsRepository, file.consoleId).subPath
    }
}
