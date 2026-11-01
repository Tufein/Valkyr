package com.tufein.valkyr.data.service

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.util.FolderChoice
import com.tufein.valkyr.util.FolderConflict
import com.tufein.valkyr.util.FolderFile
import com.tufein.valkyr.util.FolderSaveSync
import com.tufein.valkyr.util.FolderSyncResult
import com.tufein.valkyr.util.SaveKind
import com.tufein.valkyr.util.SaveSyncOptions
import com.tufein.valkyr.util.SaveSyncPlanner
import com.tufein.valkyr.util.SaveSyncRecord
import com.tufein.valkyr.util.StorageHelper
import com.tufein.valkyr.util.SyncFolder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SyncthingState(
    val running: Boolean = false,
    val last: FolderSyncResult? = null,
    val error: String? = null,
    val conflicts: List<FolderConflict> = emptyList()
)

/**
 * 6.0 Folder mode (Syncthing): [FolderSaveSync] over the device side of [SaveSyncService] and the
 * picked Syncthing folder (Storage Access Framework). It keeps its own records
 * (`files/save_sync_folder.json`), independent of the RomM sync's. No network code.
 */
@Singleton
class SyncthingFolderService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appSettings: AppSettings,
    private val saveSync: SaveSyncService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storeFile = File(context.filesDir, "save_sync_folder.json")
    private val _state = MutableStateFlow(SyncthingState())
    val state: StateFlow<SyncthingState> = _state.asStateFlow()

    suspend fun isConfigured(): Boolean = appSettings.syncthingMode.first() && appSettings.syncthingDir.first().isNotBlank()

    fun syncNow() { scope.launch { sync() } }

    fun resolve(conflict: FolderConflict, choice: FolderChoice) {
        scope.launch {
            val outcome = withDevice { sync, records, handled ->
                sync.resolve(conflict, choice, records)
                if (conflict.type == FolderConflict.Type.SYNCTHING_COPY) handled += conflict.id
            }
            if (outcome == null) { _state.update { it.copy(error = "busy") }; return@launch }
            outcome.onSuccess { _state.update { s -> s.copy(error = null, conflicts = s.conflicts.filterNot { it.id == conflict.id }) } }
                .onFailure { e -> if (e is CancellationException) throw e; Log.w(TAG, "Resolving failed", e); _state.update { it.copy(error = "${conflict.path}: ${e.message ?: e.javaClass.simpleName}") } }
        }
    }

    suspend fun sync(): FolderSyncResult? {
        if (!isConfigured()) return null
        _state.update { it.copy(running = true, error = null) }
        var result: FolderSyncResult? = null
        val outcome = withDevice { sync, records, handled ->
            val (r, conflicts) = sync.sync(records, handled)
            result = r
            _state.update { it.copy(last = r, conflicts = conflicts) }
        }
        when {
            outcome == null -> _state.update { it.copy(running = false, error = "busy") }
            else -> outcome.onFailure { e ->
                if (e is CancellationException) throw e
                Log.w(TAG, "Folder sync failed", e)
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            }
        }
        _state.update { it.copy(running = false) }
        return result
    }

    /** Runs [block] with the lock of the RomM sync held; null when a sync is running. Records are saved when [block] succeeds. */
    private suspend fun withDevice(block: suspend (FolderSaveSync, MutableMap<String, SaveSyncRecord>, MutableSet<String>) -> Unit): Result<Unit>? {
        val dir = appSettings.syncthingDir.first()
        return saveSync.withDeviceStore { store, _ ->
            runCatching {
                val root = StorageHelper.getDocumentFile(context, dir) ?: throw IOException("The folder cannot be opened")
                if (!root.canWrite()) throw IOException("The folder is not writable")
                val engine = FolderSaveSync(store, SafFolder(Uri.parse(dir)), options = { SaveSyncOptions(false, appSettings.saveSyncNonDestructive.first()) })
                val (records, handled) = load()
                block(engine, records, handled)
                save(records, handled)
            }
        }
    }

    // ---- The Syncthing folder -------------------------------------------------------------------

    private inner class SafFolder(private val tree: Uri) : SyncFolder {
        private var documents = mapOf<String, Uri>()

        override suspend fun list(): List<FolderFile> = withContext(Dispatchers.IO) {
            val out = mutableListOf<FolderFile>()
            val uris = HashMap<String, Uri>()
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            // Only saves/ and states/ matter; a folder that is not there yet is simply empty (a failed listing throws).
            for (top in query(rootId)) {
                if (!top.isDir || com.tufein.valkyr.util.SyncthingNames.kindOfFolder(top.name) == null) continue
                fun walk(prefix: String, id: String, depth: Int) {
                    if (depth > MAX_DEPTH) return
                    for (child in query(id)) {
                        val path = "$prefix/${child.name}"
                        if (child.isDir) walk(path, child.id, depth + 1)
                        else { out += FolderFile(path, child.size, child.modified); uris[path] = DocumentsContract.buildDocumentUriUsingTree(tree, child.id) }
                    }
                }
                walk(top.name, top.id, 1)
            }
            documents = uris
            out
        }

        override suspend fun read(file: FolderFile): ByteArray = withContext(Dispatchers.IO) {
            val uri = documents[file.path] ?: throw IOException("${file.path} is no longer in the folder")
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("Could not read ${file.path}")
        }

        override suspend fun write(path: String, bytes: ByteArray): FolderFile = withContext(Dispatchers.IO) {
            val root = StorageHelper.getDocumentFile(context, tree.toString()) ?: throw IOException("The folder cannot be opened")
            val written = StorageHelper.writeBytesSafely(context, root, path.substringBeforeLast('/', ""), path.substringAfterLast('/'), bytes)
            documents = documents + (path to written.uri)
            FolderFile(path, written.length(), written.lastModified())
        }

        /** One provider query; throws when the folder cannot be read (a failed listing is never taken for an empty one). */
        private fun query(documentId: String): List<Child> {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )
            val cursor = context.contentResolver.query(uri, columns, null, null, null) ?: throw IOException("The folder cannot be listed")
            return cursor.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        add(Child(id, name, c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR, if (c.isNull(3)) 0L else c.getLong(3), if (c.isNull(4)) 0L else c.getLong(4)))
                    }
                }
            }
        }
    }

    private data class Child(val id: String, val name: String, val isDir: Boolean, val size: Long, val modified: Long)

    // ---- Records --------------------------------------------------------------------------------

    private fun load(): Pair<MutableMap<String, SaveSyncRecord>, MutableSet<String>> = runCatching {
        if (!storeFile.exists()) return@runCatching mutableMapOf<String, SaveSyncRecord>() to mutableSetOf<String>()
        val root = JsonParser.parseString(storeFile.readText()).asJsonObject
        val records = root.getAsJsonArray("records").mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            runCatching {
                SaveSyncRecord(
                    kind = SaveKind.valueOf(o.get("kind").asString), path = o.get("path").asString, romId = 0, remoteId = 0,
                    remoteUpdatedAt = o.get("remoteUpdatedAt").asString, localSize = o.get("localSize").asLong, localModified = o.get("localModified").asLong,
                    remoteSize = o.get("remoteSize").asLong, remoteHash = o.get("baseHash")?.takeUnless { it.isJsonNull }?.asString,
                    baseHash = o.get("baseHash")?.takeUnless { it.isJsonNull }?.asString
                )
            }.getOrNull()
        }.associateBy { SaveSyncPlanner.key(it.kind, it.path) }.toMutableMap()
        val handled = root.getAsJsonArray("handled")?.map { it.asString }?.toMutableSet() ?: mutableSetOf()
        records to handled
    }.getOrElse { e -> Log.w(TAG, "Unreadable folder sync records; starting over", e); mutableMapOf<String, SaveSyncRecord>() to mutableSetOf<String>() }

    private fun save(records: Map<String, SaveSyncRecord>, handled: Set<String>) {
        val array = JsonArray()
        records.values.forEach { r ->
            array.add(JsonObject().apply {
                addProperty("kind", r.kind.name); addProperty("path", r.path); addProperty("remoteUpdatedAt", r.remoteUpdatedAt)
                addProperty("localSize", r.localSize); addProperty("localModified", r.localModified); addProperty("remoteSize", r.remoteSize)
                r.baseHash?.let { addProperty("baseHash", it) }
            })
        }
        val decided = JsonArray().apply { handled.take(2000).forEach { add(it) } }
        val bytes = JsonObject().apply { addProperty("version", 1); add("records", array); add("handled", decided) }.toString().toByteArray(Charsets.UTF_8)
        val tmp = File(storeFile.parentFile, storeFile.name + ".tmp")
        try {
            FileOutputStream(tmp).use { it.write(bytes); it.flush(); it.fd.sync() }
            if (!tmp.readBytes().contentEquals(bytes) || !tmp.renameTo(storeFile)) throw IOException("Could not keep folder sync records")
        } finally { tmp.delete() }
    }

    private companion object { const val TAG = "SyncthingFolderService"; const val MAX_DEPTH = 6 }
}
