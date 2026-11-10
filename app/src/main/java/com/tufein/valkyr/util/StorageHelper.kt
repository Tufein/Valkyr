package com.tufein.valkyr.util

import android.content.Context
import java.io.File
import android.os.storage.StorageManager
import android.os.StatFs
import android.os.Environment
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException

object StorageHelper {
    private const val TAG = "StorageHelper"

    fun getDocumentFile(context: Context, uriString: String): DocumentFile? {
        return try {
            val uri = uriString.toUri()
            if (uri.scheme == "content") {
                if (DocumentsContract.isTreeUri(uri)) {
                    DocumentFile.fromTreeUri(context, uri)
                } else {
                    DocumentFile.fromSingleUri(context, uri)
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting document file for $uriString: ${e.message}")
            null
        }
    }

    fun createDirectory(context: Context, uriString: String, subPath: String): DocumentFile? {
        val baseDocument = getDocumentFile(context, uriString) ?: run {
            Log.e(TAG, "Could not get base document for URI: $uriString")
            return null
        }
        
        try {
            if (!baseDocument.exists()) {
                Log.e(TAG, "Base directory does not exist: ${baseDocument.uri}")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking existence of base directory: ${e.message}")
            return null
        }

        // Extra check: try to read the directory to ensure it's physically present
        try {
            if (!baseDocument.canRead()) {
                Log.e(TAG, "Base directory is inaccessible (might be physically deleted): ${baseDocument.uri}")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Base directory is inaccessible (might be physically deleted): ${baseDocument.uri}. Error: ${e.message}")
            return null
        }

        return createDirectory(baseDocument, subPath)
    }

    /** The [createDirectory] walk starting from an already-resolved [DocumentFile]. */
    fun createDirectory(base: DocumentFile, subPath: String): DocumentFile? {
        if (subPath.isNotEmpty() && !safeRelativePath(subPath)) return null
        var currentDir = base
        for (part in subPath.split("/").filter { it.isNotEmpty() }) {
            val existingDir = try {
                currentDir.findFile(part)
            } catch (e: Exception) {
                Log.w(TAG, "Error finding sub-directory '$part' in '${currentDir.uri}': ${e.message}")
                null
            }

            currentDir = if (existingDir != null) {
                if (!existingDir.isDirectory) return null
                existingDir
            } else {
                try {
                    currentDir.createDirectory(part)?.takeIf { it.name == part && it.isDirectory } ?: run {
                        Log.e(TAG, "Failed to create sub-directory '$part' in '${currentDir.uri}' (returned null)")
                        return null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception creating sub-directory '$part' in '${currentDir.uri}': ${e.message}")
                    return null
                }
            }
        }
        return currentDir
    }

    /** The document at [path] under [root] ("a/b/c"); null when any segment is missing. */
    fun findFile(root: DocumentFile, path: String): DocumentFile? {
        if (!safeRelativePath(path)) return null
        var current = root
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            current = try {
                current.findFile(part)
            } catch (e: Exception) {
                Log.w(TAG, "Error finding '$part' under ${current.uri}: ${e.message}")
                null
            } ?: return null
        }
        return current
    }

    /**
     * Reads [file] as UTF-8 text. Throws when it cannot be read: callers that treat a missing
     * file specially must locate it with [findFile] first, so a transient read failure is never
     * mistaken for "no file yet".
     */
    fun readText(context: Context, file: DocumentFile): String =
        context.contentResolver.openInputStream(file.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IOException("Could not open ${file.uri}")

    /** Stages and verifies content before replacing a file, with a verified recovery copy. */
    fun writeTextSafely(context: Context, dir: DocumentFile, subPath: String, fileName: String, content: String) =
        writeBytesSafely(context, dir, subPath, fileName, content.toByteArray(Charsets.UTF_8))

    /** [writeTextSafely] for binary content (save files). Returns the written document. */
    fun writeBytesSafely(context: Context, dir: DocumentFile, subPath: String, fileName: String, bytes: ByteArray): DocumentFile {
        requireSafeName(fileName)
        val directory = if (subPath.isEmpty()) dir else createDirectory(dir, subPath)
            ?: throw IOException("Could not create directory $subPath")
        val tmp = stageBytes(context, directory, bytes)
        val tmpName = tmp.name
        try {
            return publishStagedFile(context, directory, tmp, fileName, VerifiedCopy.hash(bytes.inputStream()))
        } finally { if (tmp.name == tmpName) runCatching { tmp.delete() } }
    }

    /**
     * 3.6: writes [bytes] to a hidden staging file in [directory] without touching anything else, so
     * a caller can do its other work (moving the old file to the trash) only once the new content
     * is safely on the storage; [publishStagedFile] then gives it its name. Delete it when done.
     */
    fun stageBytes(context: Context, directory: DocumentFile, bytes: ByteArray): DocumentFile {
        val tmpName = ".dogmatix-write-${UUID.randomUUID()}.part"
        val tmp = directory.createFile("application/octet-stream", tmpName)
            ?: throw IOException("Could not create $tmpName")
        if (tmp.name != tmpName) { runCatching { tmp.delete() }; throw IOException("Storage changed the staging name") }
        try {
            context.contentResolver.openOutputStream(tmp.uri, "wt")?.use { it.write(bytes) }
                ?: throw IOException("Could not write $tmpName")
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            throw e
        }
        return tmp
    }

    data class Recovery(val id: String, val fileName: String, val backupName: String, val sha256: String) {
        val receiptName get() = ".dogmatix-recovery-$id.json"
    }

    private fun recoveryJson(recovery: Recovery): String = JsonObject().apply {
        addProperty("id", recovery.id); addProperty("fileName", recovery.fileName)
        addProperty("backupName", recovery.backupName); addProperty("sha256", recovery.sha256)
    }.toString()

    private fun readRecovery(context: Context, receipt: DocumentFile): String {
        val bytes = context.contentResolver.openInputStream(receipt.uri)?.use { BoundedStreams.read(it, 4097) }
            ?: throw IOException("Recovery receipt cannot be read")
        require(bytes.size <= 4096) { "Recovery receipt exceeds limit" }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun hash(context: Context, file: DocumentFile, check: () -> Unit = {}): String = VerifiedCopy.hash(
        context.contentResolver.openInputStream(file.uri) ?: throw IOException("File cannot be read"), check)

    private fun verifiedCopy(context: Context, source: DocumentFile, target: DocumentFile, expected: String, check: () -> Unit = {}) {
        val input = context.contentResolver.openInputStream(source.uri) ?: throw IOException("Source cannot be read")
        val output = try { context.contentResolver.openOutputStream(target.uri, "wt") ?: throw IOException("Copy cannot be written") }
            catch (e: Exception) { input.close(); throw e }
        VerifiedCopy.requireSame(expected, VerifiedCopy.transfer(input, output, check))
        VerifiedCopy.requireSame(expected, hash(context, target, check))
    }

    /**
     * Publishes a finished sibling, never trusting a provider's successful write or size alone.
     * A different original is copied and verified before replacement. Its recovery receipt and
     * bytes survive failed commits, failed rollback and process death. SAF cannot promise an
     * atomic overwrite; this journal deliberately keeps recovery possible across that gap.
     */
    @Synchronized
    fun publishStagedFile(context: Context, directory: DocumentFile, staged: DocumentFile,
        fileName: String, expectedSha256: String, check: () -> Unit = {}): DocumentFile {
        check()
        requireSafeName(fileName)
        VerifiedCopy.requireSame(expectedSha256, hash(context, staged, check))
        val existing = directory.findFile(fileName)
        if (existing != null && !existing.isFile) throw IOException("A folder already has this name")
        if (existing?.uri == staged.uri) throw IOException("A staged file must have a separate name")
        var recovery: Recovery? = null
        var backup: DocumentFile? = null
        var receipt: DocumentFile? = null
        var published: DocumentFile? = null
        if (existing != null) {
            val originalHash = hash(context, existing, check)
            if (originalHash == expectedSha256) { runCatching { staged.delete() }; return existing }
            val id = UUID.randomUUID().toString()
            recovery = Recovery(id, fileName, ".dogmatix-recovery-$id.bak", originalHash)
            backup = directory.createFile("application/octet-stream", recovery.backupName)
                ?: throw IOException("Cannot protect existing file")
            try {
                if (backup.name != recovery.backupName) throw IOException("Storage changed the recovery name")
                verifiedCopy(context, existing, backup, originalHash, check)
                VerifiedCopy.requireSame(originalHash, hash(context, existing, check))
                receipt = directory.createFile("application/octet-stream", recovery.receiptName)
                    ?: throw IOException("Cannot record recovery copy")
                if (receipt.name != recovery.receiptName) throw IOException("Storage changed the receipt name")
                val body = recoveryJson(recovery)
                context.contentResolver.openOutputStream(receipt.uri, "wt")?.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    ?: throw IOException("Cannot record recovery copy")
                if (readRecovery(context, receipt) != body) throw IOException("Recovery receipt could not be verified")
            } catch (e: Exception) {
                // The original has not been touched. Incomplete backup artifacts can be removed.
                runCatching { receipt?.delete() }; runCatching { backup.delete() }
                throw e
            }
        }
        try {
            check()
            // Recheck after staging/backup: never replace a file changed by another writer.
            val current = directory.findFile(fileName)
            if (existing == null && current != null) throw IOException("Target appeared during download")
            if (existing != null) {
                if (current?.uri != existing.uri || hash(context, existing, check) != recovery?.sha256)
                    throw IOException("Existing file changed during replacement")
                // Last cancellation point: from unlink through publish/rollback, finish this
                // transaction even when the worker is canceled so its protected bytes stay usable.
                check()
                if (!existing.delete()) throw IOException("Could not replace existing file")
            } else check()
            if (runCatching { staged.renameTo(fileName) }.getOrDefault(false)) {
                published = staged
                if (staged.name != fileName) throw IOException("Storage changed the target name")
            } else {
                // Providers without rename support may publish a new file; the complete stage
                // and verified original backup remain intact throughout this fallback.
                if (directory.findFile(fileName) != null) throw IOException("Target conflict")
                published = directory.createFile("application/octet-stream", fileName)
                    ?: throw IOException("Could not publish finished file")
                if (published.name != fileName) throw IOException("Storage changed the target name")
                verifiedCopy(context, staged, published, expectedSha256)
            }
            VerifiedCopy.requireSame(expectedSha256, hash(context, published))
            // Delete the receipt only after the old bytes have actually been removed. A provider
            // refusing cleanup leaves a discoverable recovery instead of silently orphaning it.
            if (backup == null || runCatching { backup.delete() }.getOrDefault(false)) runCatching { receipt?.delete() }
            if (published.uri != staged.uri) runCatching { staged.delete() }
            return published
        } catch (e: Exception) {
            if (backup != null && recovery != null) {
                // Remove only the replacement we created, never an external writer's file.
                if (published != null && runCatching { hash(context, published) == expectedSha256 }.getOrDefault(false)) runCatching { published.delete() }
                val restored = runCatching {
                    val target = directory.findFile(fileName)
                    if (target != null) hash(context, target) == recovery.sha256
                    else {
                        val targetFile = directory.createFile("application/octet-stream", fileName)
                            ?: throw IOException("Rollback cannot create target")
                        try {
                            if (targetFile.name != fileName) throw IOException("Rollback name changed")
                            verifiedCopy(context, backup, targetFile, recovery.sha256)
                            true
                        } catch (failure: Exception) { runCatching { targetFile.delete() }; throw failure }
                    }
                }.getOrDefault(false)
                if (restored && runCatching { backup.delete() }.getOrDefault(false)) runCatching { receipt?.delete() }
            } else if (published != null && runCatching { hash(context, published) == expectedSha256 }.getOrDefault(false)) runCatching { published.delete() }
            if (e is CancellationException) throw e
            throw StorageException("Could not publish finished file; recovery copies are preserved", e)
        }
    }

    /** Receipts are bounded, validated and linked to a readable verified backup at restore time. */
    fun pendingRecoveries(context: Context, directory: DocumentFile): List<Recovery> = directory.listFiles()
        .filter { it.name?.startsWith(".dogmatix-recovery-") == true && it.name?.endsWith(".json") == true && it.length() <= 4096 }
        .mapNotNull { receipt -> runCatching {
            val json = JsonParser.parseString(readRecovery(context, receipt)).asJsonObject
            fun text(key: String): String = json.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                ?: throw IOException("Invalid recovery receipt field")
            val recovery = Recovery(text("id"), text("fileName"), text("backupName"), text("sha256"))
            require(UUID.fromString(recovery.id).toString() == recovery.id)
            requireSafeName(recovery.fileName)
            require(recovery.receiptName == receipt.name && recovery.backupName == ".dogmatix-recovery-${recovery.id}.bak" && recovery.sha256.matches(Regex("[0-9a-f]{64}")))
            recovery.takeIf { directory.findFile(it.backupName)?.isFile == true }
        }.getOrNull() }

    /** Restores only into an empty target (or reuses an identical original); never overwrites a newer save. */
    @Synchronized
    fun restoreRecovery(context: Context, directory: DocumentFile, recovery: Recovery): DocumentFile {
        require(pendingRecoveries(context, directory).contains(recovery)) { "Unknown recovery copy" }
        val source = directory.findFile(recovery.backupName) ?: throw IOException("Recovery copy missing")
        VerifiedCopy.requireSame(recovery.sha256, hash(context, source))
        directory.findFile(recovery.fileName)?.let {
            VerifiedCopy.requireSame(recovery.sha256, hash(context, it))
            if (source.delete()) runCatching { directory.findFile(recovery.receiptName)?.delete() }
            return it
        }
        val staged = directory.createFile("application/octet-stream", ".dogmatix-write-${UUID.randomUUID()}.part")
            ?: throw IOException("Cannot stage recovery")
        try {
            verifiedCopy(context, source, staged, recovery.sha256)
            val restored = publishStagedFile(context, directory, staged, recovery.fileName, recovery.sha256)
            if (source.delete()) runCatching { directory.findFile(recovery.receiptName)?.delete() }
            return restored
        } finally { if (staged.name?.endsWith(".part") == true) runCatching { staged.delete() } }
    }

    fun safeRelativePath(path: String): Boolean = path.isNotBlank() && !path.startsWith('/') &&
        path.split('/').all { part -> part.isNotBlank() && part != "." && part != ".." &&
            part.none { it == '\\' || it == ':' || it == '\u0000' || it.isISOControl() } }

    private fun requireSafeName(name: String) { require(safeRelativePath(name) && !name.contains('/')) { "Invalid file name" } }

    fun createFile(
        context: Context,
        uriString: String,
        subPath: String,
        fileName: String,
        mimeType: String = "application/octet-stream",
        overwrite: Boolean = true
    ): DocumentFile? {
        val directory = createDirectory(context, uriString, subPath) ?: run {
            Log.e(TAG, "Failed to create/access directory for $fileName in $uriString")
            return null
        }
        // A newly created document cannot safely overwrite an old one before the caller has
        // supplied its bytes. Use writeBytesSafely/publishStagedFile for replacement instead.
        if (!safeRelativePath(fileName) || fileName.contains('/')) return null
        try {
            val existing = directory.findFile(fileName)
            if (existing != null) return if (!overwrite && existing.isFile) existing else null
        } catch (e: Exception) { Log.w(TAG, "Could not check existing file $fileName: ${e.message}"); return null }
        return try {
            directory.createFile(mimeType, fileName)?.let { created ->
                if (created.name == fileName) created else { runCatching { created.delete() }; null }
            } ?: run {
                Log.e(TAG, "Failed to create file $fileName in ${directory.uri} (returned null)")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating file $fileName in ${directory.uri}: ${e.message}")
            null
        }
    }

    fun getOutputStream(context: Context, documentFile: DocumentFile): OutputStream? {
        return try {
            context.contentResolver.openOutputStream(documentFile.uri)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening output stream: ${e.message}")
            null
        }
    }

    fun deleteFile(documentFile: DocumentFile?): Boolean {
        return documentFile?.delete() == true
    }

    fun isValidUri(context: Context, uriString: String): Boolean {
        if (uriString.isEmpty()) return false
        val documentFile = getDocumentFile(context, uriString)
        return documentFile != null && documentFile.exists() && documentFile.canWrite()
    }

    /**
     * Free bytes on the volume behind a SAF tree URI (e.g. "primary:ROMs" → shared storage),
     * or null when the volume cannot be resolved to a path.
     */
    fun getFreeBytes(context: Context, uriString: String): Long? {
        return try {
            val uri = uriString.toUri()
            val docId = if (DocumentsContract.isTreeUri(uri)) DocumentsContract.getTreeDocumentId(uri) else return null
            val volumeId = docId.substringBefore(':')
            val dir: File? = if (volumeId == "primary") {
                Environment.getExternalStorageDirectory()
            } else {
                val manager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
                manager.storageVolumes.firstOrNull { it.uuid.equals(volumeId, ignoreCase = true) }
                    ?.let { volume -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) volume.directory else null }
            }
            dir?.let { StatFs(it.path).availableBytes }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading free space for $uriString: ${e.message}")
            null
        }
    }
}
