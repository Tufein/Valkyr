package com.tufein.valkyr.data.service

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.tufein.valkyr.R
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.util.CloudSaveEntry
import com.tufein.valkyr.util.CloudSaveResult
import com.tufein.valkyr.util.CloudSaves
import com.tufein.valkyr.util.EmulatorSaveFolder
import com.tufein.valkyr.util.EmulatorSaveFolders
import com.tufein.valkyr.util.LocalSaveFile
import com.tufein.valkyr.util.RemoteSaveFile
import com.tufein.valkyr.util.RestoreTarget
import com.tufein.valkyr.util.SafetyCopy
import com.tufein.valkyr.util.SaveBackups
import com.tufein.valkyr.util.SaveConflict
import com.tufein.valkyr.util.SafetyCopyTrash
import com.tufein.valkyr.util.SaveMerge
import com.tufein.valkyr.util.SaveSlots
import com.tufein.valkyr.util.SaveSyncOptions
import com.tufein.valkyr.util.SaveKind
import com.tufein.valkyr.util.SaveServer
import com.tufein.valkyr.util.SaveStore
import com.tufein.valkyr.util.SaveSyncEngine
import com.tufein.valkyr.util.SaveSyncPlanner
import com.tufein.valkyr.util.SaveSyncRecord
import com.tufein.valkyr.util.SaveSyncResult
import com.tufein.valkyr.util.StorageHelper
import com.tufein.valkyr.util.ToastUtil
import com.tufein.valkyr.util.VerifiedSafetyCopies
import com.tufein.valkyr.util.JournalKey
import com.tufein.valkyr.util.SaveHandoff
import com.tufein.valkyr.util.SaveHandoffPreview
import com.tufein.valkyr.util.SaveHandoffDirection
import com.tufein.valkyr.util.Profiles
import com.tufein.valkyr.util.EmulatorSaveLayouts
import com.tufein.valkyr.util.MemoryCard
import com.tufein.valkyr.util.SaveFolderLayout
import com.tufein.valkyr.util.SavePack
import com.tufein.valkyr.util.BoundedStreams
import com.tufein.valkyr.util.DolphinBundle
import com.tufein.valkyr.util.DolphinBundleKind
import com.tufein.valkyr.util.DolphinSaveBundles
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SaveSyncService"

/**
 * Bigger files are not saves (or not ones worth moving over the network on every sync). Memory card
 * images may be larger, up to [EmulatorSaveLayouts.MAX_CARD_BYTES] (a 64 MB PS2 card is 66 MiB with
 * its error-correction bytes): see [maxBytesOf]. A file is held in memory whole while it moves, read
 * into one array of its exact size ([BoundedStreams.readAll]), never copied while a buffer grows.
 */
private const val MAX_FILE_BYTES = 64L * 1024 * 1024

/** Server lookups one hand-off may make to tie memory cards and save folders to the game. */
private const val MAX_HANDOFF_LOOKUPS = 40

/** Automatic syncs (app opened / back in front) run at most this often. */
private const val AUTO_INTERVAL_MS = 2 * 60 * 1000L

/** The size limit of the save or safety copy [path]: memory cards may be larger than other saves. */
private fun maxBytesOf(path: String): Long = EmulatorSaveLayouts.sizeLimit(path.substringAfterLast('/'), MAX_FILE_BYTES)

data class SaveSyncState(
    val running: Boolean = false,
    /** "3 / 12" while files are being transferred. */
    val progress: String? = null,
    val last: SaveSyncResult? = null,
    /** Error that stopped the whole sync (server unreachable, folder gone) or a failed choice. */
    val error: String? = null,
    val conflicts: List<SaveConflict> = emptyList()
)

data class LocalSafetyRestorePreview(
    val copy: SafetyCopy,
    val profileId: String,
    val backupSha256: String,
    val currentSha256: String?,
    val currentBytes: Long?,
    val configuration: String
)

/**
 * Keeps the emulator saves and save states on this device and on the RomM server the same,
 * in both directions: RomM becomes the place games save to and load from, so a game can be
 * continued on another device (or in RomM's web player).
 *
 * [SaveSyncEngine] does the work; this class gives it the picked folders (Storage Access
 * Framework) and the server ([RommClient]), runs it one sync at a time and keeps its records
 * in the app's private storage. A device file overwritten by a download is first copied to
 * `files/save-backups/` (and so is the server save an upload replaces); the newest copies of each
 * save stay, older ones move to a recovery folder first ([SaveBackups], [SafetyCopyTrash]).
 */
@Singleton
class SaveSyncService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val rommClient: RommClient,
    private val appSettings: AppSettings,
    private val history: OperationHistoryService,
    private val actionLog: ActionLogService,
    private val gameAccess: JournalGameAccess
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val storeFile = File(context.filesDir, "save_sync.json")
    private val backupDir = File(context.filesDir, "save-backups")
    private val trashDir = File(context.filesDir, "save-backups-trash")
    private val trash = SafetyCopyTrash(backupDir, trashDir)
    @Volatile private var lastAutoSync = 0L

    private val _state = MutableStateFlow(SaveSyncState())
    val state: StateFlow<SaveSyncState> = _state.asStateFlow()

    private val engine = SaveSyncEngine(
        server = RommSaveServer(rommClient),
        store = SafSaveStore(),
        onProgress = { done, total -> _state.update { it.copy(progress = "$done / $total") } },
        syncDeletions = { settingsRepository.saveSyncDeletions.first() },
        options = { SaveSyncOptions(appSettings.saveSyncPreferServer.first(), appSettings.saveSyncNonDestructive.first()) }
    )

    suspend fun isConfigured(): Boolean =
        rommClient.configuredBaseUrl().isNotEmpty() && settingsRepository.rommToken.first().isNotBlank() &&
            (settingsRepository.saveSyncSavesDir.first().isNotBlank() || settingsRepository.saveSyncStatesDir.first().isNotBlank() ||
                appSettings.saveSyncEmulatorFolders.first().isNotEmpty())

    /**
     * 6.0 Folder mode: runs [block] with the device side of the sync (the same picked folders, the same
     * safety copies) under this service's lock, so it never overlaps a RomM sync. Null when a sync runs.
     * [block] gets the store and the folder the safety copies live in.
     */
    suspend fun <T> withDeviceStore(block: suspend (SaveStore, File) -> T): T? {
        if (!lock.tryLock()) return null
        try { return withContext(Dispatchers.IO) { block(SafSaveStore(), backupDir) } } finally { lock.unlock() }
    }

    /** Whether any device folder is picked (saves, states or an emulator's own). */
    suspend fun hasAnyDeviceFolder(): Boolean = hasDeviceFolder(SaveKind.SAVE) || hasDeviceFolder(SaveKind.STATE)

    /** Sync now (Save sync screen); ignored while one runs. [confirmDeletions] lets held-back deletions through. */
    fun syncNow(confirmDeletions: Boolean = false) {
        scope.launch { sync(confirmDeletions) }
    }

    /** App opened or back in front: sync when switched on, set up and not done a moment ago. */
    fun autoSync() {
        val now = System.currentTimeMillis()
        if (now - lastAutoSync < AUTO_INTERVAL_MS) return
        scope.launch {
            if (!settingsRepository.saveSyncAuto.first() || !isConfigured()) return@launch
            lastAutoSync = now
            val result = sync() ?: return@launch
            // Only worth a word when something moved or needs the user.
            val message = when {
                result.conflicts > 0 -> context.getString(R.string.save_sync_toast_conflicts, result.conflicts)
                result.uploaded + result.downloaded > 0 -> context.getString(R.string.save_sync_toast, result.uploaded, result.downloaded)
                else -> null
            }
            message?.let { withContext(Dispatchers.Main) { ToastUtil.showInfo(context, it) } }
        }
    }

    /** Keeps [keepDevice] ? the device copy (uploads it) : the server copy (downloads it); what is replaced is backed up first. */
    fun resolve(conflict: SaveConflict, keepDevice: Boolean) = resolveWith(conflict) { records -> engine.resolve(conflict, keepDevice, records) }

    /** Keeps both: the server version is set aside as a safety copy on the device and nothing is replaced. */
    fun resolveKeepBoth(conflict: SaveConflict) = resolveWith(conflict) { records -> engine.resolveKeepBoth(conflict, records) }

    private fun resolveWith(conflict: SaveConflict, block: suspend (MutableMap<String, SaveSyncRecord>) -> Unit) {
        scope.launch {
            lock.withLock {
                val records = loadRecords().toMutableMap()
                runCatching { block(records) }
                    .onSuccess {
                        saveRecords(records)
                        _state.update { s -> s.copy(error = null, conflicts = s.conflicts.filterNot { it.local.kind == conflict.local.kind && it.local.path == conflict.local.path }) }
                    }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        Log.w(TAG, "Resolving ${conflict.local.path} failed", e)
                        _state.update { it.copy(error = "${conflict.local.name}: ${e.message}") }
                    }
            }
        }
    }

    /** One full sync; null when one was already running, nothing is set up or it failed as a whole. */
    suspend fun sync(confirmDeletions: Boolean = false): SaveSyncResult? {
        if (!lock.tryLock()) return null
        try {
            if (!isConfigured()) return null
            _state.update { it.copy(running = true, progress = null, error = null) }
            return withContext(Dispatchers.IO) {
                runCatching {
                    pruneBackups()
                    val records = loadRecords().toMutableMap()
                    val (result, conflicts) = engine.sync(records, confirmDeletions)
                    saveRecords(records)
                    history.event("save_sync", "${result.uploaded} / ${result.downloaded} / ${result.conflicts}", if (result.conflicts > 0) "conflict" else if (result.failed > 0) "failed" else "done")
                    actionLog.saveSync(result.uploaded, result.downloaded, result.conflicts, result.failed, result.deletedOnDevice, result.deletedOnServer)
                    _state.update { it.copy(last = result, conflicts = conflicts) }
                    result
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Save sync failed", e)
                    actionLog.saveSyncFailed()
                    _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
                }.getOrNull()
            }
        } finally {
            _state.update { it.copy(running = false, progress = null) }
            lock.unlock()
        }
    }

    /**
     * Housekeeping at the start of a sync: copies older than 30 days *and* beyond the newest N of their
     * save move to the recovery folder (never deleted outright); the recovery folder is emptied of
     * moves older than 14 days. Failures only cost a log line.
     */
    private suspend fun pruneBackups() {
        runCatching {
            val keep = appSettings.saveSyncBackupKeep.first()
            val expired = SaveBackups.expired(allSafetyCopies(), keep, System.currentTimeMillis())
            if (expired.isNotEmpty()) trash.move(expired)
            trash.purge()
        }.onFailure { if (it is CancellationException) throw it; Log.w(TAG, "Safety copy housekeeping failed", it) }
    }

    /** Every safety copy on disk, whoever's it is (housekeeping only; the screens use [localSafetyCopies]). */
    private fun allSafetyCopies(): List<SafetyCopy> {
        if (!backupDir.isDirectory) return emptyList()
        val zone = ZoneId.systemDefault()
        return backupDir.walkTopDown().maxDepth(8).filter { it.isFile }.take(20_001).mapNotNull { file ->
            val relative = file.relativeToOrNull(backupDir)?.invariantSeparatorsPath ?: return@mapNotNull null
            CloudSaves.safetyCopy(relative, file.length(), file.lastModified(), zone)
        }.toList()
    }

    /** What "Prune now" would move to the recovery folder: the copies of [profileId]'s games beyond the newest N of each save. */
    suspend fun previewPrune(profileId: String): List<SafetyCopy> = withContext(Dispatchers.IO) {
        SaveBackups.excess(localSafetyCopies(profileId), appSettings.saveSyncBackupKeep.first())
    }

    /** Moves the previewed copies to the recovery folder (kept 14 days); returns how many moved. Refused while a sync runs. */
    suspend fun pruneNow(copies: List<SafetyCopy>): Int? {
        if (!lock.tryLock()) return null
        try {
            return withContext(Dispatchers.IO) {
                // Only what is still an excess copy right now: a copy the sync just made is never in the list.
                val moved = trash.move(copies)
                history.event("save_backup_prune", "$moved", "done")
                moved
            }
        } finally { lock.unlock() }
    }

    /** Copies waiting in the recovery folder from the last prune. */
    suspend fun prunedCount(): Int = withContext(Dispatchers.IO) { trash.lastCount() }

    /** Brings the last prune back; returns how many copies returned. */
    suspend fun undoPrune(): Int? {
        if (!lock.tryLock()) return null
        try {
            return withContext(Dispatchers.IO) {
                val restored = trash.undoLast()
                history.event("save_backup_prune_undo", "$restored", "done")
                restored
            }
        } finally { lock.unlock() }
    }

    // ---- Server -------------------------------------------------------------------------------

    private class RommSaveServer(private val client: RommClient) : SaveServer {
        override suspend fun list(kind: SaveKind) = client.saves(kind)
        override suspend fun download(save: RemoteSaveFile) = client.downloadSave(save, maxBytesOf(save.fileName))
        override suspend fun upload(kind: SaveKind, romId: Int, fileName: String, emulator: String?, bytes: ByteArray) =
            client.uploadSave(kind, romId, fileName, emulator, bytes)
        override suspend fun searchRoms(term: String) = client.searchRoms(term)
        override suspend fun delete(save: RemoteSaveFile) = client.deleteSave(save)
    }

    // ---- Device folders -----------------------------------------------------------------------

    /**
     * The picked folders through the Storage Access Framework: every save / state three folder
     * levels deep (RetroArch sorts by core and by content folder). One folder picked for both
     * holds both kinds; the name tells which one a file is.
     */
    private inner class SafSaveStore(private val readLimit: Long = MAX_FILE_BYTES) : SaveStore {
        /** Memory card images may be larger than other saves, except in a hand-off (which never takes a shared card). */
        private val cardLimit = if (readLimit == SaveHandoff.MAX_SAVE_BYTES) readLimit else maxOf(readLimit, EmulatorSaveLayouts.MAX_CARD_BYTES)
        /** Document of every file of the last listing (and of later writes), by [SaveSyncEngine.key]. */
        private var documents: Map<String, Uri> = emptyMap()
        /** The files of every PPSSPP save folder of the last listing (and of later writes), by [SaveSyncEngine.key]. */
        private var packs: Map<String, List<PackFile>> = emptyMap()
        /** Hashes of save folders already kept as safety copies by this store, so a replacement does not copy them twice. */
        private val packCopies = mutableSetOf<String>()

        override suspend fun list(): SaveStore.Listing = withContext(Dispatchers.IO) {
            val savesUri = settingsRepository.saveSyncSavesDir.first()
            val statesUri = settingsRepository.saveSyncStatesDir.first()
            val files = mutableListOf<Pair<LocalSaveFile, Uri>>()
            val topFolders = mutableMapOf<SaveKind, Set<String>>()
            var tooLarge = 0
            val emulatorFolders = appSettings.saveSyncEmulatorFolders.first()
            val holdSharedCards = mutableSetOf<String>()
            val packFiles = mutableMapOf<String, List<PackFile>>()
            val syncedBundles = mutableSetOf<String>()
            val problems = mutableListOf<String>()
            // Memory cards (DuckStation, AetherSX2 / NetherSX2, Dolphin's GameCube cards): one level, only
            // cards; shared cards only when the user chose a game for them. Folders (Dolphin's GCI
            // folders, PCSX2's folder cards) are never looked into.
            fun scanCards(folder: EmulatorSaveFolder) {
                val (tree, rootId) = layoutRoot(folder)
                val children = query(tree, rootId) ?: throw IOException("${folder.label} cannot be read; pick it again")
                topFolders[SaveKind.SAVE] = topFolders[SaveKind.SAVE].orEmpty() + folder.label
                if (folder.sharedCardsRomId == null) holdSharedCards += folder.label
                children.filter { !it.isDir }.forEach { child ->
                    val card = EmulatorSaveLayouts.memoryCard(folder.layout, child.name) ?: return@forEach
                    val shared = card is MemoryCard.Shared
                    if (shared && folder.sharedCardsRomId == null) return@forEach
                    if (child.size > EmulatorSaveLayouts.MAX_CARD_BYTES) { tooLarge++; return@forEach }
                    files += LocalSaveFile(
                        SaveKind.SAVE, "${folder.label}/${child.name}", child.size, child.modified, folder.platforms,
                        folder.layout, if (shared) folder.sharedCardsRomId else null
                    ) to DocumentsContract.buildDocumentUriUsingTree(tree, child.documentId)
                }
            }
            // PPSSPP's SAVEDATA: every save folder becomes one file, `<folder>.savedata.zip`.
            fun scanPacks(folder: EmulatorSaveFolder) {
                val (tree, rootId) = layoutRoot(folder)
                val children = query(tree, rootId) ?: throw IOException("${folder.label} cannot be read; pick it again")
                topFolders[SaveKind.SAVE] = topFolders[SaveKind.SAVE].orEmpty() + folder.label
                children.filter { it.isDir && !it.name.startsWith(".") }.forEach { dir ->
                    // An unreadable save folder fails the listing: left out, it would look deleted.
                    val inside = packContents(tree, dir.documentId) ?: throw IOException("${folder.label}/${dir.name} cannot be read; pick the folder again")
                    if (inside.isEmpty()) return@forEach
                    val size = runCatching { SavePack.packedSize(inside.map { it.path to it.size }) }.getOrNull()
                    if (size == null || size > MAX_FILE_BYTES || inside.size > SavePack.MAX_ENTRIES) { tooLarge++; return@forEach }
                    val file = LocalSaveFile(
                        SaveKind.SAVE, "${folder.label}/${EmulatorSaveLayouts.packName(dir.name)}", size, inside.maxOf { it.modified },
                        folder.platforms, SaveFolderLayout.SAVEDATA
                    )
                    packFiles[SaveSyncEngine.key(file)] = inside
                    files += file to DocumentsContract.buildDocumentUriUsingTree(tree, dir.documentId)
                }
            }
            // Dolphin's GCI folders and Wii saves: one bundle each, only those the user chose a game for.
            fun scanBundles(folder: EmulatorSaveFolder) {
                if (folder.dolphinBundles.isEmpty()) return
                val (tree, gcId) = layoutRoot(folder)
                val userId = dolphinUserFolder(folder, tree)
                for (bundle in folder.dolphinBundles) {
                    val path = "${folder.label}/${bundle.fileName}"
                    if (bundle.kind == DolphinBundleKind.WII && userId == null) {
                        problems += context.getString(R.string.saves37_wii_unreachable, folder.label)
                        continue
                    }
                    val rootId = bundleRoot(tree, if (bundle.kind == DolphinBundleKind.GCI) gcId else requireNotNull(userId), bundle)
                        ?: throw IOException("${folder.label}/${bundle.devicePath} cannot be read; pick the folder again")
                    val inside = if (rootId.isEmpty()) emptyList() else bundleContents(tree, rootId, bundle)
                        ?: throw IOException("${folder.label}/${bundle.devicePath} cannot be read; pick the folder again")
                    val checked = runCatching {
                        DolphinSaveBundles.checkEntries(bundle, inside.map { it.path })
                        SavePack.packedSize(inside.map { it.path to it.size })
                    }
                    val size = checked.getOrNull()
                    if (size == null) {
                        val e = checked.exceptionOrNull()
                        problems += context.getString(R.string.saves37_bundle_skipped, bundle.devicePath, e?.message ?: e?.javaClass?.simpleName.orEmpty())
                        tooLarge++
                        continue
                    }
                    if (size > DolphinSaveBundles.MAX_BYTES) {
                        problems += context.getString(R.string.saves37_bundle_too_large, bundle.devicePath, (DolphinSaveBundles.MAX_BYTES shr 20).toInt())
                        tooLarge++
                        continue
                    }
                    // Taken from now on, also when empty: a bundle on the server then comes down into the empty folder.
                    syncedBundles += path
                    if (inside.isEmpty()) continue
                    val file = LocalSaveFile(
                        SaveKind.SAVE, path, size, inside.maxOf { it.modified }, folder.platforms,
                        SaveFolderLayout.GC_MEMCARDS, folder.romIdOf(bundle)
                    )
                    packFiles[SaveSyncEngine.key(file)] = inside
                    files += file to DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
                }
            }
            fun scan(treeUri: String, kindOf: (String) -> SaveKind, kinds: List<SaveKind>, under: EmulatorSaveFolder? = null) {
                val tree = treeUri.toUri()
                val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
                    ?: throw IOException("Pick the folder again (no access)")
                val children = query(tree, rootId) ?: throw IOException("${folderName(tree)} cannot be read; pick it again")
                // An emulator's own folder shows up as one folder named after the emulator.
                if (under == null) kinds.forEach { topFolders[it] = topFolders[it].orEmpty() + children.filter { c -> c.isDir }.map { c -> c.name } }
                else kinds.forEach { topFolders[it] = topFolders[it].orEmpty() + under.label }
                fun walk(prefix: String, depth: Int, list: List<Child>) {
                    list.forEach { child ->
                        val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                        when {
                            child.isDir -> if (depth < 3 && !child.name.startsWith(".")) {
                                query(tree, child.documentId)?.let { walk(path, depth + 1, it) }
                            }
                            !SaveSyncPlanner.isSyncable(child.name) -> Unit
                            child.size > MAX_FILE_BYTES -> tooLarge++
                            else -> files += LocalSaveFile(kindOf(child.name), path, child.size, child.modified, under?.platforms.orEmpty()) to
                                DocumentsContract.buildDocumentUriUsingTree(tree, child.documentId)
                        }
                    }
                }
                walk(under?.label.orEmpty(), if (under == null) 1 else 2, children)
            }
            if (savesUri.isNotBlank() && savesUri == statesUri) {
                scan(savesUri, SaveSyncPlanner::kindOf, SaveKind.entries)
            } else {
                if (savesUri.isNotBlank()) scan(savesUri, { SaveKind.SAVE }, listOf(SaveKind.SAVE))
                if (statesUri.isNotBlank()) scan(statesUri, { SaveKind.STATE }, listOf(SaveKind.STATE))
            }
            // A folder of the same name in the saves folder would mix with it; stop rather than guess
            // (skipping it would make its files look deleted).
            emulatorFolders.forEach { folder ->
                if (topFolders[SaveKind.SAVE].orEmpty().any { it.equals(folder.label, ignoreCase = true) })
                    throw IOException(context.getString(R.string.save_sync_emulator_clash, folder.label))
                when (folder.layout) {
                    SaveFolderLayout.FILES -> scan(folder.uri, { SaveKind.SAVE }, listOf(SaveKind.SAVE), folder)
                    SaveFolderLayout.MEMCARDS, SaveFolderLayout.PS2_MEMCARDS -> scanCards(folder)
                    SaveFolderLayout.GC_MEMCARDS -> { scanCards(folder); scanBundles(folder) }
                    SaveFolderLayout.SAVEDATA -> scanPacks(folder)
                }
            }
            documents = files.associate { (file, uri) -> SaveSyncEngine.key(file) to uri }
            packs = packFiles
            val rooted = buildSet {
                if (savesUri.isNotBlank()) add(if (savesUri == statesUri) SaveKind.entries else listOf(SaveKind.SAVE))
                if (statesUri.isNotBlank()) add(listOf(SaveKind.STATE))
            }.flatten().toSet()
            SaveStore.Listing(files.map { it.first }, topFolders, tooLarge, topFolders.keys - rooted, holdSharedCards, syncedBundles, problems)
        }

        override suspend fun read(file: LocalSaveFile): ByteArray = withContext(Dispatchers.IO) {
            if (file.layout == SaveFolderLayout.SAVEDATA) return@withContext readPack(file)
            if (DolphinSaveBundles.isBundle(file)) return@withContext readPack(file, DolphinSaveBundles.MAX_ENTRIES)
            val uri = documents[SaveSyncEngine.key(file)] ?: throw IOException("${file.name} is no longer on the device")
            val limit = if (file.layout.holdsSharedCards) cardLimit else readLimit
            val coroutine = currentCoroutineContext()
            // Some document providers report 0 when the size is unknown: the file is then read to its end.
            context.contentResolver.openInputStream(uri)?.use { input ->
                BoundedStreams.readAll(
                    input, file.size, limit,
                    tooLarge = { if (limit == SaveHandoff.MAX_SAVE_BYTES) SaveHandoff.SaveTooLargeException() else IOException("Save file is too large") },
                    changed = { IOException("${file.name} changed while it was being read; try again") },
                    check = { coroutine.ensureActive() }
                )
            } ?: throw IOException("Could not read ${file.name}")
        }

        override suspend fun write(kind: SaveKind, path: String, bytes: ByteArray): LocalSaveFile = withContext(Dispatchers.IO) {
            // "DraStic (standalone)/Game.dsv" goes into DraStic's own folder when one is picked for it.
            val emulator = if (kind == SaveKind.SAVE) EmulatorSaveFolders.locate(path, appSettings.saveSyncEmulatorFolders.first()) else null
            when (emulator?.first?.layout) {
                SaveFolderLayout.GC_MEMCARDS -> return@withContext DolphinSaveBundles.parse(emulator.second)
                    ?.let { writeBundle(emulator.first, it, emulator.second, path, bytes) }
                    ?: writeCard(emulator.first, emulator.second, path, bytes)
                SaveFolderLayout.MEMCARDS, SaveFolderLayout.PS2_MEMCARDS ->
                    return@withContext writeCard(emulator.first, emulator.second, path, bytes)
                SaveFolderLayout.SAVEDATA -> return@withContext writePack(emulator.first, emulator.second, path, bytes)
                else -> Unit
            }
            val root = (if (emulator != null) StorageHelper.getDocumentFile(context, emulator.first.uri) else rootFor(kind))
                ?: throw IOException("No folder picked for ${kind.apiPath}")
            val inside = emulator?.second ?: path
            val written = StorageHelper.writeBytesSafely(context, root, inside.substringBeforeLast('/', ""), inside.substringAfterLast('/'), bytes)
            val file = LocalSaveFile(kind, path, written.length(), written.lastModified(), emulator?.first?.platforms.orEmpty())
            documents = documents + (SaveSyncEngine.key(file) to written.uri)
            file
        }

        override suspend fun delete(file: LocalSaveFile) = withContext(Dispatchers.IO) {
            if (file.layout != SaveFolderLayout.FILES) throw IOException("Memory cards and save folders are never removed by a sync")
            val uri = documents[SaveSyncEngine.key(file)] ?: throw IOException("${file.name} is no longer on the device")
            if (!DocumentsContract.deleteDocument(context.contentResolver, uri)) throw IOException("Could not delete ${file.name}")
            documents = documents - SaveSyncEngine.key(file)
        }

        override suspend fun backup(file: LocalSaveFile) {
            val bytes = read(file)
            keepSafetyCopy(file.kind, file.path, bytes)
            if (file.layout == SaveFolderLayout.SAVEDATA || DolphinSaveBundles.isBundle(file)) packCopies += SaveHandoff.sha256(bytes)
        }

        override suspend fun keepCopy(kind: SaveKind, path: String, bytes: ByteArray) {
            withContext(Dispatchers.IO) { keepSafetyCopy(kind, path, bytes) }
        }

        /** A PPSSPP save folder as its pack; the files must still be what the listing saw. */
        private suspend fun readPack(file: LocalSaveFile, maxEntries: Int = SavePack.MAX_ENTRIES): ByteArray {
            val inside = packs[SaveSyncEngine.key(file)] ?: throw IOException("${file.name} is no longer on the device")
            var total = 0L
            val contents = inside.map { part ->
                currentCoroutineContext().ensureActive()
                val bytes = context.contentResolver.openInputStream(part.uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > readLimit) {
                            if (readLimit == SaveHandoff.MAX_SAVE_BYTES) throw SaveHandoff.SaveTooLargeException()
                            throw IOException("Save folder is too large")
                        }
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                } ?: throw IOException("Could not read ${file.name}")
                if (part.size > 0L && bytes.size.toLong() != part.size) throw IOException("${file.name} changed while it was being read; try again")
                part.path to bytes
            }
            return SavePack.pack(contents, maxEntries)
        }

        /** A memory card goes straight into the folder of cards; a shared one only when the user chose to sync them. */
        private suspend fun writeCard(folder: EmulatorSaveFolder, inside: String, path: String, bytes: ByteArray): LocalSaveFile {
            val card = EmulatorSaveLayouts.memoryCard(folder.layout, inside)
            if ('/' in inside || card == null) throw IOException("$inside is not a memory card")
            if (card is MemoryCard.Shared && folder.sharedCardsRomId == null) throw IOException("Shared memory cards are not synced for ${folder.label}")
            val root = layoutDocument(folder)
            val written = StorageHelper.writeBytesSafely(context, root, "", inside, bytes)
            val file = LocalSaveFile(
                SaveKind.SAVE, path, written.length(), written.lastModified(), folder.platforms,
                folder.layout, if (card is MemoryCard.Shared) folder.sharedCardsRomId else null
            )
            documents = documents + (SaveSyncEngine.key(file) to written.uri)
            return file
        }

        /**
         * Unpacks a PPSSPP save into its folder. What the folder held is kept as a safety copy first
         * (unless this store copied exactly that already), so files the new save no longer has can go.
         */
        private suspend fun writePack(folder: EmulatorSaveFolder, inside: String, path: String, bytes: ByteArray): LocalSaveFile {
            val name = EmulatorSaveLayouts.folderOfPack(inside)?.takeIf { '/' !in inside } ?: throw IOException("$inside is not a PPSSPP save")
            val entries = SavePack.unpack(bytes, MAX_FILE_BYTES)
            val root = layoutDocument(folder)
            val existing = StorageHelper.findFile(root, name)
            if (existing != null && !existing.isDirectory) throw IOException("$name is not a folder")
            val dir = existing ?: StorageHelper.createDirectory(root, name) ?: throw IOException("Could not create $name")
            val before = documentFiles(dir)
            var oldFiles: List<Pair<String, ByteArray>> = emptyList()
            if (before.isNotEmpty()) {
                if (before.sumOf { it.second.length() } > MAX_FILE_BYTES) throw IOException("$name is too large to replace safely")
                oldFiles = before.map { (p, doc) ->
                    p to (context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes() } ?: throw IOException("Could not read $name/$p"))
                }
                val old = SavePack.pack(oldFiles)
                if (SaveHandoff.sha256(old) !in packCopies) keepSafetyCopy(SaveKind.SAVE, path, old)
            }
            currentCoroutineContext().ensureActive()
            // A save folder is replaced as a whole or not at all: once writing starts it is not
            // stopped half-way, and a failure puts the old files back (a mix of old and new files
            // is a broken save for PPSSPP).
            withContext(NonCancellable) {
                try {
                    entries.forEach { (entryPath, data) ->
                        StorageHelper.writeBytesSafely(context, dir, entryPath.substringBeforeLast('/', ""), entryPath.substringAfterLast('/'), data)
                    }
                    val keep = entries.map { ArchivePlanKey.of(it.first) }.toSet()
                    before.filter { (p, _) -> ArchivePlanKey.of(p) !in keep }.forEach { (p, doc) ->
                        if (!doc.delete()) throw IOException("Could not replace $name/$p")
                    }
                } catch (e: Exception) {
                    restorePack(dir, oldFiles)
                    throw e
                }
            }
            val after = documentFiles(dir)
            val file = LocalSaveFile(
                SaveKind.SAVE, path, SavePack.packedSize(after.map { it.first to it.second.length() }),
                after.maxOfOrNull { it.second.lastModified() } ?: 0L, folder.platforms, SaveFolderLayout.SAVEDATA
            )
            packs = packs + (SaveSyncEngine.key(file) to after.map { (p, doc) -> PackFile(p, doc.uri, doc.length(), doc.lastModified()) })
            documents = documents + (SaveSyncEngine.key(file) to dir.uri)
            return file
        }

        /**
         * Unpacks a Dolphin bundle into its folder (`GC/USA/Card A`, `Wii/title/00010000`), only when
         * the user chose to sync it. Every file is checked first (names, shape, count, size); what the
         * folder held is kept as a safety copy (unless this store copied exactly that already); then
         * each file is written atomically and the bundle's files the new one no longer has are
         * removed, all based on a listing that must succeed. A failure puts the old files back.
         */
        private suspend fun writeBundle(folder: EmulatorSaveFolder, bundle: DolphinBundle, inside: String, path: String, bytes: ByteArray): LocalSaveFile {
            if ('/' in inside) throw IOException("$inside is not a Dolphin bundle")
            val romId = folder.romIdOf(bundle) ?: throw IOException("${bundle.devicePath} is not synced for ${folder.label}")
            val entries = DolphinSaveBundles.unpack(bundle, bytes)
            val tree = folder.uri.toUri()
            val (_, gcId) = layoutRoot(folder)
            val baseId = if (bundle.kind == DolphinBundleKind.GCI) gcId
            else dolphinUserFolder(folder, tree) ?: throw IOException(context.getString(R.string.saves37_wii_unreachable, folder.label))
            // Wii: Dolphin makes its NAND (`Wii`) itself; only `title/<type>` below it is made here.
            if (bundle.kind == DolphinBundleKind.WII && query(tree, baseId)?.any { it.isDir && it.name.equals("Wii", ignoreCase = true) } != true) {
                throw IOException(context.getString(R.string.saves37_wii_no_nand, folder.label))
            }
            val rootDoc = bundleDirectory(tree, baseId, bundle)
            val rootId = DocumentsContract.getDocumentId(rootDoc.uri)
            // Strict: a listing that fails stops here, nothing is removed on its word.
            val before = bundleContents(tree, rootId, bundle) ?: throw IOException("${bundle.devicePath} cannot be read; pick the folder again")
            var oldFiles: List<Pair<String, ByteArray>> = emptyList()
            if (before.isNotEmpty()) {
                if (before.size > DolphinSaveBundles.MAX_ENTRIES || before.sumOf { it.size } > DolphinSaveBundles.MAX_BYTES) {
                    throw IOException("${bundle.devicePath} is too large to replace safely")
                }
                var total = 0L
                oldFiles = before.map { part ->
                    currentCoroutineContext().ensureActive()
                    val data = context.contentResolver.openInputStream(part.uri)?.use { input ->
                        BoundedStreams.readAll(
                            input, part.size, DolphinSaveBundles.MAX_BYTES - total,
                            tooLarge = { IOException("${bundle.devicePath} is too large to replace safely") },
                            changed = { IOException("${bundle.devicePath}/${part.path} changed while it was being read; try again") }
                        )
                    } ?: throw IOException("Could not read ${bundle.devicePath}/${part.path}")
                    total += data.size
                    part.path to data
                }
                val old = SavePack.pack(oldFiles, DolphinSaveBundles.MAX_ENTRIES)
                if (SaveHandoff.sha256(old) !in packCopies) keepSafetyCopy(SaveKind.SAVE, path, old)
            }
            currentCoroutineContext().ensureActive()
            // Replaced as a whole or not at all: once writing starts it is not stopped half-way.
            withContext(NonCancellable) {
                try {
                    val dirs = HashMap<String, DocumentFile>()
                    entries.forEach { (entryPath, data) ->
                        val sub = entryPath.substringBeforeLast('/', "")
                        val dir = if (sub.isEmpty()) rootDoc else dirs.getOrPut(sub) {
                            StorageHelper.createDirectory(rootDoc, sub) ?: throw IOException("Could not create ${bundle.devicePath}/$sub")
                        }
                        StorageHelper.writeBytesSafely(context, dir, "", entryPath.substringAfterLast('/'), data)
                    }
                    val keep = entries.map { ArchivePlanKey.of(it.first) }.toSet()
                    before.filter { ArchivePlanKey.of(it.path) !in keep }.forEach { part ->
                        if (!DocumentsContract.deleteDocument(context.contentResolver, part.uri)) throw IOException("Could not replace ${bundle.devicePath}/${part.path}")
                    }
                } catch (e: Exception) {
                    restoreBundle(tree, rootDoc, rootId, bundle, oldFiles)
                    throw e
                }
            }
            val after = bundleContents(tree, rootId, bundle) ?: throw IOException("${bundle.devicePath} cannot be read; pick the folder again")
            val file = LocalSaveFile(
                SaveKind.SAVE, path, SavePack.packedSize(after.map { it.path to it.size }),
                after.maxOfOrNull { it.modified } ?: 0L, folder.platforms, SaveFolderLayout.GC_MEMCARDS, romId
            )
            packs = packs + (SaveSyncEngine.key(file) to after)
            documents = documents + (SaveSyncEngine.key(file) to rootDoc.uri)
            return file
        }

        /** Puts a bundle's folder back to exactly [files] after a failed replace (best effort; the safety copy stays). */
        private fun restoreBundle(tree: Uri, rootDoc: DocumentFile, rootId: String, bundle: DolphinBundle, files: List<Pair<String, ByteArray>>) {
            runCatching {
                val wanted = files.map { ArchivePlanKey.of(it.first) }.toSet()
                // Only removes files a successful listing shows; a failed one leaves everything as it is.
                bundleContents(tree, rootId, bundle)?.filter { ArchivePlanKey.of(it.path) !in wanted }?.forEach {
                    DocumentsContract.deleteDocument(context.contentResolver, it.uri)
                }
                files.forEach { (p, data) ->
                    StorageHelper.writeBytesSafely(context, rootDoc, p.substringBeforeLast('/', ""), p.substringAfterLast('/'), data)
                }
            }.onFailure { Log.w(TAG, "Could not restore ${bundle.devicePath} after a failed replace", it) }
        }

        /** The folder of [bundle] below [baseId] (Dolphin's `GC` or user folder), made when missing; existing folders are found whatever their case. */
        private fun bundleDirectory(tree: Uri, baseId: String, bundle: DolphinBundle): DocumentFile {
            var id = baseId
            var doc = DocumentFile.fromTreeUri(context, DocumentsContract.buildDocumentUriUsingTree(tree, baseId))
                ?: throw IOException("${bundle.devicePath} cannot be reached; pick the folder again")
            for (part in bundleSegments(bundle)) {
                val children = query(tree, id) ?: throw IOException("${bundle.devicePath} cannot be read; pick the folder again")
                val existing = DolphinSaveBundles.pick(children.filter { it.isDir }.map { it.name }, part)?.let { name -> children.first { it.isDir && it.name == name } }
                if (existing != null) {
                    id = existing.documentId
                    doc = DocumentFile.fromTreeUri(context, DocumentsContract.buildDocumentUriUsingTree(tree, id))
                        ?: throw IOException("${bundle.devicePath} cannot be reached; pick the folder again")
                } else {
                    if (children.any { it.name.equals(part, ignoreCase = true) }) throw IOException("${bundle.devicePath}: $part is not a folder")
                    doc = StorageHelper.createDirectory(doc, part) ?: throw IOException("Could not create ${bundle.devicePath}")
                    id = DocumentsContract.getDocumentId(doc.uri)
                }
            }
            return doc
        }

        /** Puts a save folder back to exactly [files] after a failed replace (best effort; the safety copy stays). */
        private fun restorePack(dir: DocumentFile, files: List<Pair<String, ByteArray>>) {
            runCatching {
                val wanted = files.map { ArchivePlanKey.of(it.first) }.toSet()
                documentFiles(dir).filter { (p, _) -> ArchivePlanKey.of(p) !in wanted }.forEach { (_, doc) -> doc.delete() }
                files.forEach { (p, data) ->
                    StorageHelper.writeBytesSafely(context, dir, p.substringBeforeLast('/', ""), p.substringAfterLast('/'), data)
                }
            }.onFailure { Log.w(TAG, "Could not restore ${dir.name} after a failed replace", it) }
        }

        /** The files below [dir] (three levels), by path; our own staging files are left out. */
        private fun documentFiles(dir: DocumentFile): List<Pair<String, DocumentFile>> {
            val out = mutableListOf<Pair<String, DocumentFile>>()
            fun walk(prefix: String, current: DocumentFile, depth: Int) {
                current.listFiles().forEach { doc ->
                    val docName = doc.name ?: return@forEach
                    if (docName.startsWith(".")) return@forEach
                    val p = if (prefix.isEmpty()) docName else "$prefix/$docName"
                    if (doc.isDirectory) { if (depth < 3) walk(p, doc, depth + 1) } else out += p to doc
                }
            }
            walk("", dir, 1)
            return out
        }

        /** The folder inside the picked one that holds the cards / save folders (see [EmulatorSaveLayouts.innerPath]). */
        private fun layoutDocument(folder: EmulatorSaveFolder): DocumentFile {
            val picked = StorageHelper.getDocumentFile(context, folder.uri) ?: throw IOException("No folder picked for ${folder.label}")
            val inner = innerPath(folder)
            return if (inner.isEmpty()) picked else StorageHelper.findFile(picked, inner)?.takeIf { it.isDirectory }
                ?: throw IOException("${folder.label} changed; pick it again")
        }
        private suspend fun rootFor(kind: SaveKind): DocumentFile? {
            val uri = when (kind) {
                SaveKind.SAVE -> settingsRepository.saveSyncSavesDir.first()
                SaveKind.STATE -> settingsRepository.saveSyncStatesDir.first()
            }
            return uri.takeIf { it.isNotBlank() }?.let { StorageHelper.getDocumentFile(context, it) }
        }

        private fun folderName(tree: Uri): String =
            runCatching { DocumentsContract.getTreeDocumentId(tree).substringAfter(':') }.getOrDefault(tree.toString())
    }

    private data class Child(val documentId: String, val name: String, val isDir: Boolean, val size: Long, val modified: Long)

    /** One file of a PPSSPP save folder: its path inside the folder and its document. */
    private data class PackFile(val path: String, val uri: Uri, val size: Long, val modified: Long)

    private object ArchivePlanKey {
        fun of(path: String): String = com.tufein.valkyr.util.ArchivePlan.portableKey(com.tufein.valkyr.util.ArchivePlan.safeRelativePath(path))
    }

    /** Where below the picked folder of [folder] its cards / save folders are ("memcards", "GC", "PSP/SAVEDATA" or ""). */
    private fun innerPath(folder: EmulatorSaveFolder): String {
        if (folder.layout == SaveFolderLayout.FILES) return ""
        val tree = folder.uri.toUri()
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: throw IOException("Pick the folder again (no access)")
        fun idAt(path: String): String? {
            var id = rootId
            for (part in path.split('/').filter { it.isNotEmpty() }) {
                id = query(tree, id)?.firstOrNull { it.isDir && it.name == part }?.documentId ?: return null
            }
            return id
        }
        return EmulatorSaveLayouts.innerPath(folder.layout) { path -> idAt(path)?.let { id -> query(tree, id)?.filter { it.isDir }?.map { it.name }?.toSet() } }
            ?: throw IOException(
                when (folder.layout) {
                    SaveFolderLayout.SAVEDATA -> "${folder.label} has no SAVEDATA folder yet; start the emulator once or pick its SAVEDATA folder"
                    SaveFolderLayout.GC_MEMCARDS -> "${folder.label} has no GC folder yet; start Dolphin once or pick its GC folder"
                    else -> "${folder.label} has no memcards folder yet; start the emulator once or pick its memcards folder"
                }
            )
    }

    /** The tree and the document of the folder that holds [folder]'s cards / save folders. */
    private fun layoutRoot(folder: EmulatorSaveFolder): Pair<Uri, String> {
        val tree = folder.uri.toUri()
        var id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: throw IOException("Pick the folder again (no access)")
        for (part in innerPath(folder).split('/').filter { it.isNotEmpty() }) {
            id = query(tree, id)?.firstOrNull { it.isDir && it.name == part }?.documentId ?: throw IOException("${folder.label} changed; pick it again")
        }
        return tree to id
    }

    /** The files of one save folder (three levels), or null when part of it cannot be read (it is then left alone). */
    private fun packContents(tree: Uri, folderId: String): List<PackFile>? {
        val out = mutableListOf<PackFile>()
        fun walk(prefix: String, id: String, depth: Int): Boolean {
            val children = query(tree, id) ?: return false
            for (child in children) {
                if (child.name.startsWith(".")) continue
                val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (child.isDir) { if (depth < 3 && !walk(path, child.documentId, depth + 1)) return false }
                else out += PackFile(path, DocumentsContract.buildDocumentUriUsingTree(tree, child.documentId), child.size, child.modified)
            }
            return true
        }
        return if (walk("", folderId, 1)) out else null
    }

    /**
     * Dolphin's user folder (holding `GC` and `Wii`) of a Dolphin folder, as a document of its tree:
     * the picked folder when `GC` sits inside it; null when the user picked `GC` itself (the Wii
     * folder next to it is then out of reach).
     */
    private fun dolphinUserFolder(folder: EmulatorSaveFolder, tree: Uri): String? {
        if (innerPath(folder).isEmpty()) return null
        return runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: throw IOException("Pick the folder again (no access)")
    }

    /** The folders from the base (`GC`, or Dolphin's user folder for Wii) down to [bundle]'s folder. */
    private fun bundleSegments(bundle: DolphinBundle): List<String> = when (bundle.kind) {
        DolphinBundleKind.GCI -> listOf(bundle.region, "Card ${bundle.slot}")
        DolphinBundleKind.WII -> listOf("Wii", "title", bundle.titleType)
    }

    /** The document of [bundle]'s folder below [baseId]: "" when it does not exist (yet), null when a listing failed. */
    private fun bundleRoot(tree: Uri, baseId: String, bundle: DolphinBundle): String? {
        var id = baseId
        for (part in bundleSegments(bundle)) {
            val dirs = query(tree, id)?.filter { it.isDir } ?: return null
            val name = DolphinSaveBundles.pick(dirs.map { it.name }, part) ?: return ""
            id = dirs.first { it.name == name }.documentId
        }
        return id
    }

    /**
     * The files of a bundle's folder, by their path in the bundle; null when any listing fails (the
     * bundle is then neither sent nor replaced). GCI: the `.gci` files of the folder. Wii: the files
     * below `<title>/data/` of every title folder (8 hex digits); `content/` and everything else of
     * a title is never looked into. Hidden files (the app's own staging files) are left out.
     */
    private fun bundleContents(tree: Uri, rootId: String, bundle: DolphinBundle): List<PackFile>? {
        val out = mutableListOf<PackFile>()
        val top = query(tree, rootId) ?: return null
        fun file(path: String, child: Child) { out += PackFile(path, DocumentsContract.buildDocumentUriUsingTree(tree, child.documentId), child.size, child.modified) }
        when (bundle.kind) {
            DolphinBundleKind.GCI -> top.filter { !it.isDir && DolphinSaveBundles.isGciFile(it.name) }.forEach { file(it.name, it) }
            DolphinBundleKind.WII -> {
                // Deeper than a Wii save goes or more files than a bundle takes: listed anyway, so the bundle's check refuses it.
                fun walk(prefix: String, id: String, depth: Int): Boolean {
                    val children = query(tree, id) ?: return false
                    for (child in children) {
                        if (child.name.startsWith(".")) continue
                        val path = "$prefix/${child.name}"
                        if (child.isDir) { if (depth < 12 && !walk(path, child.documentId, depth + 1)) return false }
                        else file(path, child)
                        if (out.size > DolphinSaveBundles.MAX_ENTRIES) return true
                    }
                    return true
                }
                for (title in top.filter { it.isDir && DolphinSaveBundles.isWiiTitle(it.name) }) {
                    val inside = query(tree, title.documentId) ?: return null
                    val data = inside.firstOrNull { it.isDir && it.name == "data" } ?: continue
                    if (!walk("${title.name}/data", data.documentId, 3)) return null
                    if (out.size > DolphinSaveBundles.MAX_ENTRIES) break
                }
            }
        }
        return out
    }

    /** One provider query per folder; null when it cannot be read. */
    private fun query(tree: Uri, documentId: String): List<Child>? = runCatching {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        context.contentResolver.query(uri, columns, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    add(Child(
                        documentId = id,
                        name = name,
                        isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                        size = if (c.isNull(3)) 0L else c.getLong(3),
                        modified = if (c.isNull(4)) 0L else c.getLong(4)
                    ))
                }
            }
        }
    }.getOrNull()

    // ---- Records ------------------------------------------------------------------------------

    /** Fixed field names (not Gson reflection), so minified builds read what debug builds wrote. */
    private fun loadRecords(): Map<String, SaveSyncRecord> = runCatching {
        if (!storeFile.exists()) return emptyMap()
        val root = JsonParser.parseString(storeFile.readText()).asJsonObject
        root.getAsJsonArray("records").mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            runCatching {
                SaveSyncRecord(
                    kind = SaveKind.valueOf(o.get("kind").asString),
                    path = o.get("path").asString,
                    romId = o.get("romId").asInt,
                    remoteId = o.get("remoteId").asInt,
                    remoteUpdatedAt = o.get("remoteUpdatedAt").asString,
                    localSize = o.get("localSize").asLong,
                    localModified = o.get("localModified").asLong,
                    remoteSize = o.get("remoteSize")?.takeUnless { it.isJsonNull }?.asLong ?: -1,
                    remoteHash = o.get("remoteHash")?.takeUnless { it.isJsonNull }?.asString,
                    baseHash = o.get("baseHash")?.takeUnless { it.isJsonNull }?.asString
                )
            }.getOrNull()
        }.associateBy { SaveSyncPlanner.key(it.kind, it.path) }
    }.getOrElse { e -> Log.w(TAG, "Unreadable sync records; starting over", e); emptyMap() }

    private fun saveRecords(records: Map<String, SaveSyncRecord>) {
        val array = JsonArray()
        records.values.forEach { r ->
            array.add(JsonObject().apply {
                addProperty("kind", r.kind.name)
                addProperty("path", r.path)
                addProperty("romId", r.romId)
                addProperty("remoteId", r.remoteId)
                addProperty("remoteUpdatedAt", r.remoteUpdatedAt)
                addProperty("localSize", r.localSize)
                addProperty("localModified", r.localModified)
                addProperty("remoteSize", r.remoteSize)
                r.remoteHash?.let { addProperty("remoteHash", it) }
                r.baseHash?.let { addProperty("baseHash", it) }
            })
        }
        val tmp = File(storeFile.parentFile, storeFile.name + ".tmp")
        val bytes = JsonObject().apply { addProperty("version", 1); add("records", array) }.toString().toByteArray(Charsets.UTF_8)
        try {
            FileOutputStream(tmp).use { output -> output.write(bytes); output.flush(); output.fd.sync() }
            if (!tmp.readBytes().contentEquals(bytes) || !tmp.renameTo(storeFile)) throw IOException("Could not keep save sync records")
        } finally { tmp.delete() }
    }

    // ---- 5.0: cloud saves per game (used by CloudSavesService; additions only) ----------------

    /** Where the safety copies live: `save-backups/<yyyyMMdd-HHmmss>/<saves|states>/<path>` (kept 30 days). */
    val safetyCopiesDir: File get() = backupDir

    /** Whether a device folder is picked for [kind] (for saves, an emulator's own folder counts too). */
    suspend fun hasDeviceFolder(kind: SaveKind): Boolean = when (kind) {
        SaveKind.SAVE -> settingsRepository.saveSyncSavesDir.first().isNotBlank() || appSettings.saveSyncEmulatorFolders.first().isNotEmpty()
        SaveKind.STATE -> settingsRepository.saveSyncStatesDir.first().isNotBlank()
    }

    /** The device's saves and states as a sync lists them (nothing is transferred); null when no folder is picked. */
    suspend fun deviceListing(): SaveStore.Listing? = withContext(Dispatchers.IO) {
        if (!hasDeviceFolder(SaveKind.SAVE) && !hasDeviceFolder(SaveKind.STATE)) null else SafSaveStore().list()
    }

    /** RomM games for a DuckStation folder's shared memory cards to be kept with (Settings → Save sync). */
    suspend fun searchGames(term: String): List<SaveSyncPlanner.RomCandidate> = withContext(Dispatchers.IO) {
        if (term.isBlank()) emptyList() else rommClient.searchRoms(term.trim(), limit = 30)
    }

    /** What the last sync recorded per device file, by [SaveSyncPlanner.key] (a copy). */
    suspend fun syncRecords(): Map<String, SaveSyncRecord> = withContext(Dispatchers.IO) { loadRecords() }

    // ---- Local recovery / guided device handoff -----------------------------------------------

    private suspend fun configurationSignature(): String = SaveHandoff.sha256(listOf(
        rommClient.configuredBaseUrl(), settingsRepository.rommToken.first(),
        settingsRepository.saveSyncSavesDir.first(), settingsRepository.saveSyncStatesDir.first(),
        settingsRepository.rommPlatformMap.first().entries.sortedBy { it.key }.joinToString("") { "${it.key.length}:${it.key}:${it.value}" },
        com.tufein.valkyr.util.EmulatorSaveFolders.toJson(appSettings.saveSyncEmulatorFolders.first())
    ).joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))

    private suspend fun guardGame(key: JournalKey, configuration: String? = null) = appSettings.withActiveProfile(key.profileId) {
        gameAccess.check(key)
        if (configuration != null) check(configurationSignature() == configuration) { "Save folders or server changed; check them again" }
    }

    /** Owner metadata is optional only for legacy/global copies; malformed metadata fails closed. */
    private fun copyOwner(copy: SafetyCopy): JournalKey? {
        val file = File(backupDir, copy.relative)
        require(file.canonicalPath.startsWith(backupDir.canonicalPath + File.separator))
        val parsed = CloudSaves.safetyCopy(copy.relative, file.length(), file.lastModified(), ZoneId.systemDefault())
        require(parsed != null && parsed.relative == copy.relative && parsed.kind == copy.kind && parsed.path == copy.path) { "Unexpected safety copy path" }
        val batch = copy.relative.substringBefore('/')
        val owner = File(File(backupDir, batch), "owner.json")
        if (!owner.exists()) return null
        require(owner.canonicalPath.startsWith(backupDir.canonicalPath + File.separator) && owner.isFile && owner.length() in 1..8192)
        val json = JsonParser.parseString(owner.readText(Charsets.UTF_8)).asJsonObject
        fun string(name: String) = json.get(name).let { require(it != null && it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString }
        return JournalKey(string("profile"), string("console"), string("file")).also {
            require(it.profileId.length <= 128 && it.consoleId.length in 1..128 && it.fileName.length in 1..1024)
        }
    }

    private suspend fun checkCopyAccess(profileId: String, copy: SafetyCopy) = appSettings.withActiveProfile(profileId) {
        val owner = copyOwner(copy)
        if (owner == null) check(profileId.isEmpty()) { "This older copy belongs to the unrestricted profile" }
        else { check(owner.profileId == profileId) { "This copy belongs to another profile" }; gameAccess.check(owner) }
    }

    suspend fun localSafetyCopies(profileId: String): List<SafetyCopy> = withContext(Dispatchers.IO) {
        appSettings.withActiveProfile(profileId) { Unit }
        if (!backupDir.isDirectory) return@withContext emptyList()
        val copies = backupDir.walkTopDown().maxDepth(8).filter { it.isFile }.take(5001).toList()
        require(copies.size <= 5000) { "Too many safety copies; review them before restoring" }
        copies.mapNotNull { file ->
            val relative = file.relativeToOrNull(backupDir)?.invariantSeparatorsPath ?: return@mapNotNull null
            CloudSaves.safetyCopy(relative, file.length(), file.lastModified(), ZoneId.systemDefault())
        }.filter { copy ->
            try { checkCopyAccess(profileId, copy); true }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { false }
        }.sortedByDescending { it.takenAt }
    }

    suspend fun previewLocalSafetyRestore(profileId: String, copy: SafetyCopy): LocalSafetyRestorePreview = withContext(Dispatchers.IO) {
        checkCopyAccess(profileId, copy)
        check(hasDeviceFolder(copy.kind)) { "Choose the save folder again before restoring" }
        val bytes = VerifiedSafetyCopies.read(backupDir, copy.relative, maxBytesOf(copy.path))
        val store = SafSaveStore()
        val listing = store.list()
        require(listing.tooLarge == 0) { "A save is too large to verify safely" }
        val matches = listing.files.filter { it.kind == copy.kind && it.path.equals(copy.path, ignoreCase = true) }
        require(matches.size <= 1) { "Several save files match this path" }
        val current = matches.singleOrNull()?.let { store.read(it) }
        checkCopyAccess(profileId, copy)
        LocalSafetyRestorePreview(copy, profileId, SaveHandoff.sha256(bytes), current?.let(SaveHandoff::sha256), current?.size?.toLong(), configurationSignature())
    }

    suspend fun restoreLocalSafetyCopy(preview: LocalSafetyRestorePreview): CloudSaveResult {
        if (!lock.tryLock()) return CloudSaveResult.Busy
        try { return withContext(Dispatchers.IO) {
            try {
                val fresh = previewLocalSafetyRestore(preview.profileId, preview.copy)
                check(fresh == preview) { "The save or folder changed; check the restore again" }
                val bytes = VerifiedSafetyCopies.read(backupDir, preview.copy.relative, maxBytesOf(preview.copy.path))
                appSettings.withActiveProfile(preview.profileId) {
                    checkCopyAccessUnlocked(preview.profileId, preview.copy)
                    check(configurationSignature() == preview.configuration) { "Save folder changed; check the restore again" }
                    val store = SafSaveStore()
                    val current = store.list().files.filter { it.kind == preview.copy.kind && it.path.equals(preview.copy.path, ignoreCase = true) }
                    require(current.size <= 1)
                    val old = current.singleOrNull()?.let { store.read(it) }
                    check(old?.let(SaveHandoff::sha256) == preview.currentSha256) { "Save changed; check the restore again" }
                    check(SaveHandoff.sha256(bytes) == preview.backupSha256)
                    currentCoroutineContext().ensureActive()
                    if (old != null) {
                        val owner = copyOwner(preview.copy)
                        if (owner == null) keepSafetyCopy(preview.copy.kind, current.single().path, old)
                        else keepGameSafetyCopy(owner, preview.copy.kind, current.single().path, old)
                    }
                    val written = store.write(preview.copy.kind, current.singleOrNull()?.path ?: preview.copy.path, bytes)
                    check(store.read(written).contentEquals(bytes)) { "Restored save could not be verified" }
                    // Reset the baseline; the next sync compares instead of trusting stale timestamps.
                    saveRecords(loadRecords().filterKeys { it != SaveSyncEngine.key(written) })
                    CloudSaveResult.Done(written.path)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { CloudSaveResult.Failed(if (e is VerifiedSafetyCopies.IntegrityException) context.getString(R.string.csave_error_integrity) else e.message ?: "Restore failed") }
        } } finally { lock.unlock() }
    }

    private suspend fun checkCopyAccessUnlocked(profileId: String, copy: SafetyCopy) {
        val owner = copyOwner(copy)
        if (owner == null) check(profileId.isEmpty()) else { check(owner.profileId == profileId); gameAccess.check(owner) }
    }

    private fun keepGameSafetyCopy(key: JournalKey, kind: SaveKind, path: String, bytes: ByteArray) {
        val target = VerifiedSafetyCopies.keep(backupDir, kind, path, bytes)
        val batch = File(backupDir, target.relativeTo(backupDir).invariantSeparatorsPath.substringBefore('/'))
        val owner = File(batch, "owner.json")
        val metadata = JsonObject().apply { addProperty("profile", key.profileId); addProperty("console", key.consoleId); addProperty("file", key.fileName) }.toString().toByteArray(Charsets.UTF_8)
        try {
            FileOutputStream(owner).use { it.write(metadata); it.flush(); it.fd.sync() }
            check(owner.readBytes().contentEquals(metadata))
            check(VerifiedSafetyCopies.read(backupDir, target.relativeTo(backupDir).invariantSeparatorsPath, maxBytesOf(path)).contentEquals(bytes))
        } catch (e: Exception) { batch.deleteRecursively(); throw e }
    }

    /** Fresh ordinary saves only. No emulator state is offered as portable between devices. */
    suspend fun previewHandoff(key: JournalKey, romId: Int): SaveHandoffPreview = withContext(Dispatchers.IO) {
        val configuration = configurationSignature()
        val endpoint = rommClient.saveEndpoint()
        guardGame(key, configuration)
        previewHandoffAt(key, romId, endpoint, configuration)
    }

    private suspend fun previewHandoffAt(key: JournalKey, romId: Int, endpoint: RommSaveEndpoint, configuration: String): SaveHandoffPreview {
        guardGame(key)
        check(isConfigured() && hasDeviceFolder(SaveKind.SAVE)) { "Set up RomM and an in-game save folder first" }
        val platform = settingsRepository.rommPlatformMap.first()[key.consoleId]
            ?: error("Map this console to RomM before transferring saves")
        val detail = JsonHttp.requireOk(JsonHttp.request("GET", "${endpoint.base}/api/roms/$romId", endpoint.auth, readTimeoutMs = 60_000))
        val identity = CloudSaves.romFileAndPlatform(detail.json) ?: error("Server game identity could not be verified")
        check(identity.second == platform && com.tufein.valkyr.util.RommMarks.key(key.consoleId, identity.first) == com.tufein.valkyr.util.RommMarks.key(key.consoleId, key.fileName)) {
            "Server game changed; refresh the RomM library before transferring"
        }
        val store = SafSaveStore(SaveHandoff.MAX_SAVE_BYTES)
        val listing = store.list()
        require(listing.tooLarge == 0) { "A save is too large to verify safely" }
        val records = loadRecords()
        val stem = CloudSaves.gameStem(key.fileName)
        // Cards and save folders are often named by a serial or product id the ROM's file name
        // lacks: those are tied to a game through RomM, as the save sync does (bounded lookups).
        val layoutMatches = HashMap<String, SaveSyncPlanner.RomMatch>()
        var layoutLookups = 0
        suspend fun layoutMatch(local: LocalSaveFile): SaveSyncPlanner.RomMatch = layoutMatches.getOrPut(SaveSyncEngine.key(local)) {
            var found: SaveSyncPlanner.RomMatch = SaveSyncPlanner.RomMatch.NotFound
            for (term in EmulatorSaveLayouts.searchTerms(local)) {
                found = EmulatorSaveLayouts.matchRom(local, rommClient.searchRoms(term, endpoint = endpoint))
                if (found !is SaveSyncPlanner.RomMatch.NotFound) break
            }
            found
        }
        val locals = listing.files.filter { local ->
            val record = records[SaveSyncEngine.key(local)]
            if (record != null) record.romId == romId
            else if (local.platformHints.isNotEmpty() && !EmulatorSaveFolders.platformFits(key.consoleId, local.platformHints)) false
            else if (local.layout == SaveFolderLayout.FILES) CloudSaves.belongsToGame(local.name, stem)
            else EmulatorSaveLayouts.belongsToGame(local, key.fileName) || (
                local.kind == SaveKind.SAVE && !EmulatorSaveLayouts.isSharedCard(local.name) && !DolphinSaveBundles.isBundle(local) && layoutLookups++ < MAX_HANDOFF_LOOKUPS &&
                    layoutMatch(local).let { it is SaveSyncPlanner.RomMatch.Found && it.romId == romId })
        }
        val ordinary = locals.filter { it.kind == SaveKind.SAVE && SaveHandoff.isInGameSave(it.name) }
        ordinary.forEach { SaveHandoff.requireBoundedSave(it.size) }
        require(ordinary.map { it.path.lowercase() }.distinct().size == ordinary.size) { "Several save folders have the same path" }
        // A familiar stem alone cannot distinguish (for example) NES Tetris from Game Boy Tetris.
        // Require an established record or a unique platform-aware server match before an upload.
        ordinary.filter { records[SaveSyncEngine.key(it)] == null }.forEach { local ->
            val match = if (local.layout == SaveFolderLayout.FILES) SaveSyncPlanner.matchRom(local, rommClient.searchRoms(SaveSyncPlanner.searchTerm(local.name), endpoint = endpoint))
            else layoutMatch(local)
            check(match is SaveSyncPlanner.RomMatch.Found && match.romId == romId) { "Save cannot be tied to this console safely; configure its emulator folder or synchronize it first" }
        }
        val remotes = SaveSyncPlanner.latestPerName(rommClient.saves(SaveKind.SAVE, endpoint).filter { it.romId == romId && SaveHandoff.isInGameSave(it.fileName) })
        remotes.forEach { SaveHandoff.requireBoundedSave(it.size) }
        require(ordinary.size + remotes.size <= SaveHandoff.MAX_FILES * 2) { "Too many saves for one game" }
        val localHashes = ordinary.associate { it.path to SaveHandoff.sha256(store.read(it)) }
        val remoteHashes = remotes.associate { it.id to SaveHandoff.sha256(handoffServerBytes(it, endpoint)) }
        guardGame(key, configuration)
        return SaveHandoffPreview(key, romId, configuration, System.currentTimeMillis(), SaveHandoff.plan(
            listing.copy(files = ordinary), remotes, records.values.filter { it.romId == romId }, localHashes, remoteHashes
        ), omittedStates = locals.size - ordinary.size)
    }

    /** Re-read both sides before approval is used, back up bytes, transfer and verify both sides again. */
    suspend fun transferHandoff(preview: SaveHandoffPreview): SaveHandoffPreview {
        check(lock.tryLock()) { "A save synchronization is already running" }
        try { return withContext(Dispatchers.IO) {
            guardGame(preview.key, preview.configuration)
            val endpoint = rommClient.saveEndpoint()
            guardGame(preview.key, preview.configuration)
            val fresh = previewHandoffAt(preview.key, preview.romId, endpoint, preview.configuration)
            SaveHandoff.requireFresh(preview, fresh, System.currentTimeMillis())
            val records = loadRecords().toMutableMap()
            for (row in fresh.files) {
                currentCoroutineContext().ensureActive()
                guardGame(preview.key, preview.configuration)
                val store = SafSaveStore(SaveHandoff.MAX_SAVE_BYTES)
                val local = store.list().files.singleOrNull { it.kind == SaveKind.SAVE && it.path == row.path }
                val bytes = local?.let { store.read(it) }
                check(bytes?.let(SaveHandoff::sha256) == row.localSha256) { "Device save changed during transfer; check it again" }
                val actualRemote = SaveSyncPlanner.latestPerName(rommClient.saves(SaveKind.SAVE, endpoint).filter { it.romId == preview.romId })
                    .singleOrNull { it.fileName.equals(row.remote?.fileName ?: local?.name ?: row.path.substringAfterLast('/'), ignoreCase = true) }
                check(actualRemote?.id == row.remote?.id) { "Server save changed during transfer; check it again" }
                val remote = actualRemote
                val serverBytes = remote?.let { handoffServerBytes(it, endpoint) }
                check(serverBytes?.let(SaveHandoff::sha256) == row.remoteSha256) { "Server save changed during transfer; check it again" }
                if (bytes != null) appSettings.withActiveProfile(preview.key.profileId) {
                    gameAccess.check(preview.key)
                    keepGameSafetyCopy(preview.key, SaveKind.SAVE, row.path, bytes)
                }
                if (serverBytes != null && row.direction == SaveHandoffDirection.UPLOAD) appSettings.withActiveProfile(preview.key.profileId) {
                    gameAccess.check(preview.key)
                    keepGameSafetyCopy(preview.key, SaveKind.SAVE, row.path, serverBytes)
                }
                when (row.direction) {
                    SaveHandoffDirection.UPLOAD -> appSettings.withActiveProfile(preview.key.profileId) {
                        // Only this final remote mutation holds the profile lock. Preview/downloads
                        // release it, so switching cancels a delayed action before it writes anything.
                        gameAccess.check(preview.key)
                        check(configurationSignature() == preview.configuration) { "Server or folders changed; check saves again" }
                        val stored = rommClient.uploadSave(SaveKind.SAVE, preview.romId, requireNotNull(local).name, SaveSyncPlanner.emulatorFor(local), requireNotNull(bytes), endpoint)
                            ?: throw IOException("Server did not confirm the save upload")
                        check(SaveHandoff.sha256(handoffServerBytes(stored, endpoint)) == row.localSha256) { "Uploaded save could not be verified" }
                        check(configurationSignature() == preview.configuration) { "Server settings changed during transfer; check it again" }
                        records[SaveSyncEngine.key(local)] = SaveSyncEngine.record(local, stored)
                    }
                    SaveHandoffDirection.DOWNLOAD -> appSettings.withActiveProfile(preview.key.profileId) {
                        gameAccess.check(preview.key)
                        check(configurationSignature() == preview.configuration)
                        val latestLocal = store.list().files.singleOrNull { it.kind == SaveKind.SAVE && it.path == row.path }
                        check(latestLocal?.let { SaveHandoff.sha256(store.read(it)) } == row.localSha256) { "Save changed during transfer" }
                        val written = store.write(SaveKind.SAVE, row.path, requireNotNull(serverBytes))
                        check(store.read(written).contentEquals(serverBytes))
                        records[SaveSyncEngine.key(written)] = SaveSyncEngine.record(written, requireNotNull(remote))
                    }
                    SaveHandoffDirection.IDENTICAL -> records[SaveSyncEngine.key(requireNotNull(local))] = SaveSyncEngine.record(local, requireNotNull(remote))
                    else -> error("Resolve save conflicts first")
                }
                appSettings.withActiveProfile(preview.key.profileId) { gameAccess.check(preview.key); saveRecords(records) }
            }
            val checked = previewHandoffAt(preview.key, preview.romId, endpoint, preview.configuration)
            check(checked.files.isNotEmpty() && checked.files.all { it.direction == SaveHandoffDirection.IDENTICAL }) { "Some saves still differ; check them again" }
            // Read back the backup for every current save, including files that were just downloaded.
            val store = SafSaveStore(SaveHandoff.MAX_SAVE_BYTES)
            val current = store.list().files
            checked.files.forEach { row ->
                val local = current.single { it.kind == SaveKind.SAVE && it.path == row.path }
                val bytes = store.read(local)
                check(SaveHandoff.sha256(bytes) == row.localSha256)
                appSettings.withActiveProfile(preview.key.profileId) { gameAccess.check(preview.key); keepGameSafetyCopy(preview.key, SaveKind.SAVE, row.path, bytes) }
            }
            guardGame(preview.key, preview.configuration)
            checked.copy(backupVerified = true)
        } } finally { lock.unlock() }
    }

    private suspend fun handoffServerBytes(remote: RemoteSaveFile, endpoint: RommSaveEndpoint): ByteArray {
        SaveHandoff.requireBoundedSave(remote.size)
        return try {
            rommClient.downloadSave(remote, SaveHandoff.MAX_SAVE_BYTES, endpoint).also { SaveHandoff.requireBoundedSave(it.size.toLong()) }
        } catch (e: IOException) {
            if (e.message?.contains("too large", ignoreCase = true) == true || e.message?.contains("exceed", ignoreCase = true) == true) throw SaveHandoff.SaveTooLargeException()
            throw e
        }
    }

    /**
     * The screenshot RetroArch keeps next to a save state (`Game.state1.png`): its document URI and
     * date; null when there is none or the folder cannot be read.
     */
    suspend fun deviceStateShot(local: LocalSaveFile): Pair<String, Long>? = withContext(Dispatchers.IO) {
        if (local.kind != SaveKind.STATE) return@withContext null
        runCatching {
            val treeUri = settingsRepository.saveSyncStatesDir.first()
            val root = if (treeUri.isBlank()) null else StorageHelper.getDocumentFile(context, treeUri)
            if (root == null) null else listOf("${local.path}.png", "${local.path.substringBeforeLast('.')}.png").distinct()
                .firstNotNullOfOrNull { candidate -> StorageHelper.findFile(root, candidate)?.takeIf { it.isFile } }
                ?.let { it.uri.toString() to it.lastModified() }
        }.getOrNull()
    }

    /**
     * "Restore this version": writes [version] (one of the game's server saves or states) into its
     * device folder. The device file it replaces is kept as a safety copy first, and so is the
     * server's current version when the device does not hold it already: nothing is lost, nothing is
     * deleted. The next sync then sends the restored version up as the current one.
     * [serverFiles] are the game's server entries; [gameStem] is the game's file name without extension.
     */
    suspend fun restoreServerVersion(version: CloudSaveEntry, serverFiles: List<CloudSaveEntry>, gameStem: String, profileKey: JournalKey? = null): CloudSaveResult =
        restoreInto(version.kind, serverFiles, restored = version, profileKey = profileKey, target = { listing, records ->
            CloudSaves.restoreTarget(version, gameStem, listing.files, records.values, listing.topFolders, listing.noRootFolder)
        }) { rommClient.downloadSave(version.toRemote(), maxBytesOf(version.fileName)) }

    /** Puts a safety copy back in its place; the device file there now becomes a safety copy itself first. */
    suspend fun restoreSafetyCopy(copy: SafetyCopy, serverFiles: List<CloudSaveEntry>): CloudSaveResult =
        restoreInto(copy.kind, serverFiles, restored = null, target = { _, _ -> RestoreTarget.Path(copy.path) }) {
            val file = File(backupDir, copy.relative)
            if (!file.isFile || !file.canonicalPath.startsWith(backupDir.canonicalPath + File.separator)) {
                throw IOException(context.getString(R.string.csave_error_gone, copy.name))
            }
            VerifiedSafetyCopies.read(backupDir, copy.relative, maxBytesOf(copy.path))
        }

    private suspend fun restoreInto(
        kind: SaveKind,
        serverFiles: List<CloudSaveEntry>,
        restored: CloudSaveEntry?,
        profileKey: JournalKey? = null,
        target: (SaveStore.Listing, Map<String, SaveSyncRecord>) -> RestoreTarget,
        bytesOf: suspend () -> ByteArray
    ): CloudSaveResult {
        if (!lock.tryLock()) return CloudSaveResult.Busy
        try {
            return withContext(Dispatchers.IO) {
                runCatching<CloudSaveResult> {
                    profileKey?.let { guardGame(it) }
                    val configuration = configurationSignature()
                    if (!hasDeviceFolder(kind)) return@runCatching CloudSaveResult.Failed(context.getString(R.string.csave_error_no_folder))
                    val store = SafSaveStore()
                    val listing = store.list()
                    val records = loadRecords().toMutableMap()
                    val path = when (val t = target(listing, records)) {
                        is RestoreTarget.Path -> t.path
                        RestoreTarget.Ambiguous -> return@runCatching CloudSaveResult.Failed(context.getString(R.string.csave_error_ambiguous))
                        RestoreTarget.NoFolder -> return@runCatching CloudSaveResult.Failed(context.getString(R.string.csave_error_no_folder))
                    }
                    val bytes = bytesOf()
                    val current = listing.files.firstOrNull { it.kind == kind && it.path.equals(path, ignoreCase = true) }
                    val targetPath = current?.path ?: path
                    val latest = CloudSaves.latest(serverFiles, kind, targetPath.substringAfterLast('/'))
                    val record = current?.let { records[SaveSyncEngine.key(it)] }
                    // 1. What the device has there now becomes a safety copy (if that fails, nothing is touched).
                    val original = current?.let { store.read(it) }
                    if (original != null) {
                        if (profileKey == null) keepSafetyCopy(kind, requireNotNull(current).path, original)
                        else appSettings.withActiveProfile(profileKey.profileId) { gameAccess.check(profileKey); keepGameSafetyCopy(profileKey, kind, requireNotNull(current).path, original) }
                    }
                    // 2. So does the server's current version, unless the device held it or it is what comes back.
                    val restoringLatest = restored != null && latest != null && restored.kind == latest.kind && restored.id == latest.id
                    if (latest != null && !restoringLatest && !CloudSaves.deviceHolds(latest, record, current)) {
                        val remoteBytes = rommClient.downloadSave(latest.toRemote(), maxBytesOf(latest.fileName))
                        if (profileKey == null) keepSafetyCopy(kind, targetPath, remoteBytes)
                        else appSettings.withActiveProfile(profileKey.profileId) { gameAccess.check(profileKey); keepGameSafetyCopy(profileKey, kind, targetPath, remoteBytes) }
                    }
                    // 3. In place; the record tells the next sync what to do with it.
                    val commit: suspend () -> CloudSaveResult = {
                        check(configurationSignature() == configuration) { "Save folders or server changed; check the restore again" }
                        val actual = store.list().files.filter { it.kind == kind && it.path.equals(targetPath, ignoreCase = true) }
                        require(actual.size <= 1)
                        check(actual.singleOrNull()?.let { SaveHandoff.sha256(store.read(it)) } == original?.let(SaveHandoff::sha256)) { "Save changed; check the restore again" }
                        val written = store.write(kind, targetPath, bytes)
                        val key = SaveSyncEngine.key(written)
                        val next = CloudSaves.recordAfterRestore(written, restored, latest, records[key])
                        if (next != null) records[key] = next else records.remove(key)
                        saveRecords(records)
                        dropConflict(kind, written.path)
                        CloudSaveResult.Done(written.path)
                    }
                    if (profileKey == null) commit() else appSettings.withActiveProfile(profileKey.profileId) { gameAccess.check(profileKey); commit() }
                }.getOrElse { e ->
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Restore failed", e)
                    CloudSaveResult.Failed(if (e is VerifiedSafetyCopies.IntegrityException) context.getString(R.string.csave_error_integrity) else e.message ?: e.javaClass.simpleName)
                }
            }
        } finally {
            lock.unlock()
        }
    }

    /**
     * "Upload now": sends the device file [path] of [kind] to RomM as a save of ROM [romId], the way a
     * sync would. When the server's copy of that name is not what the last sync saw (another device
     * saved since), it is kept as a safety copy first, so nothing is lost.
     */
    suspend fun uploadDeviceSave(kind: SaveKind, path: String, romId: Int, serverFiles: List<CloudSaveEntry>, profileKey: JournalKey? = null): CloudSaveResult {
        if (!lock.tryLock()) return CloudSaveResult.Busy
        try {
            return withContext(Dispatchers.IO) {
                runCatching<CloudSaveResult> {
                    profileKey?.let { guardGame(it) }
                    val configuration = configurationSignature()
                    val store = SafSaveStore()
                    val local = store.list().files.firstOrNull { it.kind == kind && it.path.equals(path, ignoreCase = true) }
                        ?: return@runCatching CloudSaveResult.Failed(context.getString(R.string.csave_error_gone, path.substringAfterLast('/')))
                    val records = loadRecords().toMutableMap()
                    val record = records[SaveSyncEngine.key(local)]
                    val paired = record?.let { r -> serverFiles.firstOrNull { it.kind == kind && it.id == r.remoteId && it.syncable } }
                    val latest = paired ?: CloudSaves.latest(serverFiles, kind, local.name, romId)
                    if (latest != null && !CloudSaves.serverUnchangedSinceSync(latest, record)) {
                        val remoteBytes = rommClient.downloadSave(latest.toRemote(), maxBytesOf(latest.fileName))
                        if (profileKey == null) keepSafetyCopy(kind, local.path, remoteBytes)
                        else appSettings.withActiveProfile(profileKey.profileId) { gameAccess.check(profileKey); keepGameSafetyCopy(profileKey, kind, local.path, remoteBytes) }
                    }
                    val bytes = store.read(local)
                    profileKey?.let { guardGame(it, configuration) }
                    val upload: suspend () -> CloudSaveResult = {
                        check(configurationSignature() == configuration) { "Server or save folders changed" }
                        val stored = rommClient.uploadSave(kind, romId, local.name, SaveSyncPlanner.emulatorFor(local), bytes)
                            ?: rommClient.saves(kind).filter { it.romId == romId && it.fileName.equals(local.name, ignoreCase = true) }
                                .maxByOrNull { SaveSyncPlanner.epochMillis(it.updatedAt) ?: Long.MIN_VALUE }
                            ?: throw IOException("Server did not confirm the save upload")
                        records[SaveSyncEngine.key(local)] = SaveSyncEngine.record(local, stored, SaveSyncEngine.md5(bytes))
                        saveRecords(records)
                        dropConflict(kind, local.path)
                        CloudSaveResult.Done(local.name)
                    }
                    if (profileKey == null) upload() else appSettings.withActiveProfile(profileKey.profileId) { gameAccess.check(profileKey); upload() }
                }.getOrElse { e ->
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Upload of $path failed", e)
                    CloudSaveResult.Failed(e.message ?: e.javaClass.simpleName)
                }
            }
        } finally {
            lock.unlock()
        }
    }

    /**
     * "Copy to slot": copies the save state [sourcePath] into [targetPath], another slot of the same game
     * (see [SaveSlots]). What the target slot held is kept as a safety copy first (and the write is read
     * back and compared); the next sync sends the new slot up like any local change. Nothing is written
     * when a sync runs, when the folders or the server changed since the preview, or the slot changed.
     */
    suspend fun copyIntoSlot(sourcePath: String, targetPath: String, expectedTarget: Long?, profileKey: JournalKey? = null): CloudSaveResult {
        if (!lock.tryLock()) return CloudSaveResult.Busy
        try {
            return withContext(Dispatchers.IO) {
                runCatching<CloudSaveResult> {
                    profileKey?.let { guardGame(it) }
                    require(SaveSlots.sameGame(sourcePath, targetPath) && !sourcePath.equals(targetPath, ignoreCase = true)) { "Not a slot of the same game" }
                    val configuration = configurationSignature()
                    val store = SafSaveStore()
                    val listing = store.list()
                    val source = listing.files.firstOrNull { it.kind == SaveKind.STATE && it.path.equals(sourcePath, ignoreCase = true) }
                        ?: return@runCatching CloudSaveResult.Failed(context.getString(R.string.csave_error_gone, sourcePath.substringAfterLast('/')))
                    val existing = listing.files.filter { it.kind == SaveKind.STATE && it.path.equals(targetPath, ignoreCase = true) }
                    require(existing.size <= 1) { "Several save files match this slot" }
                    val target = existing.singleOrNull()
                    // The preview showed this slot as it was; a slot that changed since is looked at again first.
                    check(target?.modified == expectedTarget) { "The slot changed; check the copy again" }
                    val bytes = store.read(source)
                    val old = target?.let { store.read(it) }
                    if (old != null && target != null) {
                        if (profileKey == null) keepSafetyCopy(SaveKind.STATE, target.path, old)
                        else appSettings.withActiveProfile(profileKey.profileId) { gameAccess.check(profileKey); keepGameSafetyCopy(profileKey, SaveKind.STATE, target.path, old) }
                    }
                    val commit: suspend () -> CloudSaveResult = {
                        check(configurationSignature() == configuration) { "Save folders or server changed; check the copy again" }
                        val actual = store.list().files.filter { it.kind == SaveKind.STATE && it.path.equals(targetPath, ignoreCase = true) }
                        require(actual.size <= 1)
                        check(actual.singleOrNull()?.let { SaveHandoff.sha256(store.read(it)) } == old?.let(SaveHandoff::sha256)) { "Slot changed; check the copy again" }
                        val written = store.write(SaveKind.STATE, target?.path ?: targetPath, bytes)
                        check(store.read(written).contentEquals(bytes)) { "The copied slot could not be verified" }
                        // Paired with the server's file but marked as changed here, so the next sync sends it (the safety copy keeps what it replaces).
                        val records = loadRecords().toMutableMap()
                        val key = SaveSyncEngine.key(written)
                        records[key]?.let { records[key] = it.copy(localSize = -1, localModified = -1) }
                        saveRecords(records)
                        dropConflict(SaveKind.STATE, written.path)
                        history.event("save_slot_copy", written.name, "done")
                        CloudSaveResult.Done(written.path)
                    }
                    if (profileKey == null) commit() else appSettings.withActiveProfile(profileKey.profileId) { gameAccess.check(profileKey); commit() }
                }.getOrElse { e ->
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Slot copy failed", e)
                    CloudSaveResult.Failed(e.message ?: e.javaClass.simpleName)
                }
            }
        } finally {
            lock.unlock()
        }
    }

    /** A conflict the user settled from the game's view leaves the Save sync screen's list. */
    private fun dropConflict(kind: SaveKind, path: String) {
        _state.update { s -> s.copy(conflicts = s.conflicts.filterNot { it.local.kind == kind && it.local.path.equals(path, ignoreCase = true) }) }
    }

    /**
     * Keeps [bytes] as a safety copy of the device file [path], in the layout the sync's own copies use.
     * Throws when it cannot (the caller then changes nothing).
     */
    private fun keepSafetyCopy(kind: SaveKind, path: String, bytes: ByteArray) {
        VerifiedSafetyCopies.keep(backupDir, kind, path, bytes)
    }
}
