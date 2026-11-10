package com.tufein.valkyr.data.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import com.tufein.valkyr.DogmatixApplication
import com.tufein.valkyr.MainActivity
import com.tufein.valkyr.R
import com.tufein.valkyr.data.local.SmartStorageSettings
import com.tufein.valkyr.data.local.dao.ConsoleDao
import com.tufein.valkyr.data.local.dao.FavouriteDao
import com.tufein.valkyr.data.model.ResolvedDownloadPath
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.util.ActionCount
import com.tufein.valkyr.util.ActionKind
import com.tufein.valkyr.util.ActionReason
import com.tufein.valkyr.util.ActionTopic
import com.tufein.valkyr.util.ApkAssets
import com.tufein.valkyr.util.ConsoleFolderAliases
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.DiskDir
import com.tufein.valkyr.util.DiskScanner
import com.tufein.valkyr.util.LibraryMove
import com.tufein.valkyr.util.SmartStorage
import com.tufein.valkyr.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import android.text.format.Formatter

private const val TAG = "SmartStorageService"
private const val NOTIFICATION_ID = 8412
private const val ESDE_BUNDLED_SYSTEMS = "assets/systems/android/es_systems.xml"

/** Why smart storage cannot plan right now. */
enum class SmartProblem { NO_SD, NO_LIBRARY, SAME_STORAGE, BUSY, NO_PLAY_DATA }

/** A dry run: what a run would do now. */
data class SmartPreview(val plan: SmartStorage.Plan? = null, val problem: SmartProblem? = null)

/** A run's progress and, when it is over, its outcome. */
data class SmartRunState(
    val running: Boolean = false,
    val consoleId: String? = null,
    val consoleIndex: Int = 0,
    val consoleCount: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val finished: Boolean = false,
    val moved: Int = 0,
    val failed: Int = 0,
    val problem: SmartProblem? = null,
    /** Consoles that moved while ES-DE is set up here but its path was left alone (to point there by hand). */
    val esdeUnchanged: List<String> = emptyList()
)

/**
 * 8.0 smart storage (see [SmartStorage] for why it moves whole consoles). Runs on demand from the
 * Storage tool and weekly while charging ([SmartStorageScheduler]).
 *
 * A console moves like this: every file is copied into a folder of the same name on the other side;
 * the copy is verified (every file there, every size equal); only then does the console switch to
 * the new folder (a folder of its own on the SD card, or back to its folder in the download folder),
 * and only then are the originals removed. Files that arrived in the old folder in the meantime are
 * moved after it the same way. A console whose copy cannot be verified stays where it was, untouched;
 * what was copied is kept on the other side, so the next run carries on instead of starting over.
 * When ES-DE is set up in Dogmatix+, its system path follows the console (custom_systems).
 */
@Singleton
class SmartStorageService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val settings: SmartStorageSettings,
    private val consoleDao: ConsoleDao,
    private val favouriteDao: FavouriteDao,
    private val pathResolver: ConsoleDownloadPathResolver,
    private val downloadService: DownloadService,
    private val libraryIndex: LibraryIndexService,
    private val moveGate: StorageMoveGate,
    private val frontendMetadata: FrontendMetadataService,
    private val esdePlay: EsdePlayService,
    private val copier: VerifiedDocumentCopy,
    private val history: OperationHistoryService,
    private val trash: TrashService,
    private val actionLog: ActionLogService,
    private val gamePackages: GamePackageService
) {
    enum class Trigger { MANUAL, AUTO }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow(SmartRunState())
    val state: StateFlow<SmartRunState> = _state.asStateFlow()

    /** One file of a console folder: [path] below the folder, with its size and last change as listed. */
    private data class FileItem(val dirPath: String, val name: String, val size: Long, val modified: Long, val uri: Uri) {
        val path: String get() = LibraryMove.join(dirPath, name)
        val stamp: SmartStorage.Stamp get() = SmartStorage.Stamp(size, modified)
    }

    private data class Folder(val dir: DiskDir, val files: List<FileItem>)

    private data class Snapshot(
        val consoles: List<SmartStorage.Console>,
        val folders: Map<String, Folder>,
        val root: String,
        val sdUri: String,
        val internalFree: Long?,
        val sdFree: Long?
    )

    /** What happened to ES-DE for a console that moved. */
    private sealed interface Esde {
        /** ES-DE is not set up in Dogmatix+: nothing to do. */
        data object NotSet : Esde
        /** Set up, but left alone (not clearly safe); the user points it to the new folder. */
        data object Left : Esde
        data object Restored : Esde
        data class Patched(val wrote: String, val previous: String, val inserted: Boolean) : Esde
    }

    /** What reading a file of ES-DE gave. */
    private sealed interface Read {
        data object Absent : Read
        data object Unreadable : Read
        data class Text(val text: String) : Read
    }

    // ---- Planning -------------------------------------------------------------------------------

    /** What a run would do now (nothing is changed). */
    suspend fun preview(): SmartPreview = withContext(Dispatchers.IO) {
        if (moveGate.lock.isLocked) return@withContext SmartPreview(problem = SmartProblem.BUSY)
        when (val s = snapshot()) {
            is Snapshot -> SmartPreview(plan(s, Trigger.MANUAL))
            is SmartProblem -> SmartPreview(problem = s)
            else -> SmartPreview(problem = SmartProblem.NO_LIBRARY)
        }
    }

    private suspend fun plan(s: Snapshot, trigger: Trigger): SmartStorage.Plan {
        val auto = trigger == Trigger.AUTO
        return SmartStorage.plan(
            s.consoles, System.currentTimeMillis(), settings.recentDays.first(), s.internalFree, s.sdFree,
            budgetBytes = if (auto) SmartStorage.AUTO_RUN_BUDGET_BYTES else null,
            maxFileBytes = if (auto) SmartStorage.AUTO_MAX_FILE_BYTES else null
        )
    }

    /** A [Snapshot], or the [SmartProblem] that stops planning. */
    private suspend fun snapshot(): Any {
        val sdUri = settings.sdUri.first()
        val sdRoot = sdUri.takeIf { it.isNotBlank() }?.let { DiskScanner.rootOf(it) } ?: return SmartProblem.NO_SD
        if (StorageHelper.getDocumentFile(context, sdUri)?.canWrite() != true) return SmartProblem.NO_SD
        val root = settingsRepository.downloadDirectory.first()
        val libRoot = root.takeIf { it.isNotBlank() }?.let { DiskScanner.rootOf(it) } ?: return SmartProblem.NO_LIBRARY
        val separate = settingsRepository.separateByConsole.first()
        if (!separate) return SmartProblem.NO_LIBRARY
        if (SmartStorage.volumeOf(sdRoot.documentId) == SmartStorage.volumeOf(libRoot.documentId) ||
            LibraryMove.overlaps(DiskScanner.canonicalKey(sdRoot), DiskScanner.canonicalKey(libRoot))
        ) return SmartProblem.SAME_STORAGE
        // Without play data every console would look unused: nothing is planned then.
        val plays = runCatching { esdePlay.plays() }.getOrNull()?.takeIf { it.isNotEmpty() }?.map { it.system to it.lastPlayed }
            ?: return SmartProblem.NO_PLAY_DATA

        val ids = consoleDao.getAllConsoles().first().map { it.id }
        val custom = settingsRepository.consoleDownloadDirectories.first()
        val resolved = pathResolver.resolveAll(ids, root, true, custom)
        val records = settings.recordsNow()
        // A console smart storage put on the SD card whose folder was changed by hand since is the user's again.
        settings.dropRecords(records.values.filter { it.place == SmartStorage.Place.SD && custom[it.consoleId] != it.uri }.map { it.consoleId })

        // Folders shared by two consoles stay put: one folder cannot be in two places.
        val detected = ids.mapNotNull { id ->
            resolved[id]?.takeIf { it.source == ResolvedDownloadPath.Source.DETECTED && it.alternatives.isEmpty() && custom[id] == null }?.let { id to it.subPath }
        }
        val shared = detected.groupBy { it.second.lowercase() }.filterValues { it.size > 1 }.keys
        val rootChildren = DiskScanner.list(context, libRoot).filter { it.isDirectory }.associateBy { it.name }

        val dirs = LinkedHashMap<String, DiskDir>()
        val located = ArrayList<Triple<String, String, SmartStorage.Place>>()
        for ((id, sub) in detected) {
            if (sub.lowercase() in shared) continue
            val entry = rootChildren[sub] ?: continue
            dirs[id] = DiskScanner.dirOf(libRoot, entry)
            located += Triple(id, sub, SmartStorage.Place.INTERNAL)
        }
        for (record in records.values) {
            if (record.place != SmartStorage.Place.SD || custom[record.consoleId] != record.uri || record.consoleId !in ids) continue
            dirs[record.consoleId] = DiskScanner.rootOf(record.uri) ?: continue
            located += Triple(record.consoleId, record.folder, SmartStorage.Place.SD)
        }

        val favourites = favouriteDao.getAll().groupBy({ it.consoleId }, { it.fileName })
        val busy = busyConsoles()
        val folders = LinkedHashMap<String, Folder>()
        val consoles = ArrayList<SmartStorage.Console>()
        for ((id, folderName, place) in located) {
            currentCoroutineContext().ensureActive()
            val dir = dirs.getValue(id)
            val files = ArrayList<FileItem>()
            walk(dir, "", files)
            folders[id] = Folder(dir, files)
            consoles += SmartStorage.Console(
                id = id, folder = folderName, place = place,
                bytes = files.sumOf { it.size.coerceAtLeast(0) }, files = files.size,
                lastPlayed = SmartStorage.lastPlayed(plays) { system -> system.equals(folderName, true) || ConsoleFolderAliases.matches(id, system) },
                favourite = SmartStorage.hasFavouriteOnDisk(favourites[id].orEmpty(), files.map { it.name }),
                busy = id in busy || ALL in busy,
                lastMovedAt = records[id]?.movedAt,
                lastChanged = files.maxOfOrNull { it.modified }?.takeIf { it > 0 },
                largestFile = files.maxOfOrNull { it.size } ?: 0
            )
        }
        return Snapshot(consoles, folders, root, sdUri, StorageHelper.getFreeBytes(context, root), StorageHelper.getFreeBytes(context, sdUri))
    }

    /** Consoles a download (queued or running) or a frontend metadata run is working on; "*" = all. */
    private fun busyConsoles(): Set<String> {
        val out = HashSet<String>()
        downloadService.downloads.value.filterNot { it.isFinished }.forEach { item ->
            downloadService.entityFor(item.fileName)?.consoleId?.let(out::add)
        }
        val meta = frontendMetadata.state.value
        if (meta.running) {
            // A run over every console makes every console busy.
            out += meta.scope ?: ALL
            meta.consoleId?.let(out::add)
        }
        return out
    }

    private fun isBusy(id: String): Boolean = busyConsoles().let { ALL in it || id in it }

    // ---- Running --------------------------------------------------------------------------------

    /**
     * Starts a run on the service's own scope (the screen may close meanwhile) for exactly the moves
     * the user confirmed ([confirmed]: console → where it goes). Moves that are no longer valid are
     * dropped; nothing is added.
     */
    fun start(confirmed: Map<String, SmartStorage.Place>) {
        if (job?.isActive == true || confirmed.isEmpty()) return
        job = scope.launch { run(Trigger.MANUAL, confirmed) }
    }

    fun cancel() { job?.cancel() }

    fun dismiss() { if (job?.isActive != true) _state.value = SmartRunState() }

    /** The weekly run: only while smart storage is on and its weekly run too. Waits until done. */
    suspend fun runIfEnabled() {
        trash.cleanExpired()
        if (!settings.enabled.first() || !settings.weekly.first()) return
        run(Trigger.AUTO, null)
    }

    private suspend fun run(trigger: Trigger, confirmed: Map<String, SmartStorage.Place>?) {
        // One mover at a time: this run, another one, or "Move the library".
        if (!moveGate.lock.tryLock()) {
            if (trigger == Trigger.MANUAL) _state.value = SmartRunState(finished = true, problem = SmartProblem.BUSY)
            return
        }
        try {
            _state.value = SmartRunState(running = true)
            val snap = snapshot()
            if (snap !is Snapshot) {
                _state.value = SmartRunState(finished = true, problem = snap as? SmartProblem ?: SmartProblem.NO_LIBRARY)
                return
            }
            val fresh = plan(snap, trigger)
            val plan = if (confirmed != null) SmartStorage.restrictTo(fresh, confirmed) else fresh
            val moves = if (ALL in busyConsoles()) emptyList() else plan.moves
            val records = settings.recordsNow()
            _state.value = SmartRunState(running = true, consoleCount = moves.size, bytesTotal = moves.sumOf { it.console.bytes })
            var moved = 0
            var failed = 0
            var bytes = 0L
            val esdeUnchanged = ArrayList<String>()
            moves.forEachIndexed { index, move ->
                currentCoroutineContext().ensureActive()
                _state.update { it.copy(consoleId = move.console.id, consoleIndex = index) }
                notifyProgress(move.console.id, index, moves.size)
                val outcome = try {
                    moveConsole(snap, move, records[move.console.id])
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Moving ${move.console.id} failed: ${e.message}")
                    null
                }
                if (outcome != null) { moved++; bytes += move.console.bytes; if (!outcome) esdeUnchanged += move.console.id } else failed++
                recordMove(move, outcome)
                _state.update { it.copy(moved = moved, failed = failed, esdeUnchanged = esdeUnchanged.toList()) }
            }
            if (moved > 0) libraryIndex.requestRefresh()
            if (moves.isNotEmpty()) settings.setLastRun(SmartStorage.RunInfo(System.currentTimeMillis(), moved, failed, bytes))
            _state.value = SmartRunState(
                finished = true, moved = moved, failed = failed, consoleCount = moves.size, bytesTotal = bytes, bytesDone = bytes,
                esdeUnchanged = esdeUnchanged.toList()
            )
            notifyDone(moved, failed, bytes)
        } catch (e: CancellationException) {
            _state.update { it.copy(running = false, finished = true) }
            cancelNotification()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Smart storage run failed: ${e.message}", e)
            _state.update { it.copy(running = false, finished = true, failed = it.failed.coerceAtLeast(1)) }
            cancelNotification()
        } finally {
            moveGate.lock.unlock()
        }
    }

    /** The action history line of one console's move ([outcome] as [moveConsole] returns it). */
    private fun recordMove(move: SmartStorage.Move, outcome: Boolean?) {
        val toSd = move.to == SmartStorage.Place.SD
        val reason = when (outcome) {
            null -> if (toSd) ActionReason.TO_SD else ActionReason.TO_INTERNAL
            true -> if (toSd) ActionReason.TO_SD else ActionReason.TO_INTERNAL
            false -> if (toSd) ActionReason.TO_SD_ESDE_LEFT else ActionReason.TO_INTERNAL_ESDE_LEFT
        }
        actionLog.record(
            if (outcome == null) ActionKind.MOVE_FAILED else ActionKind.MOVED, topic = ActionTopic.SMART_STORAGE,
            consoleId = move.console.id, reason = reason, counts = mapOf(ActionCount.FILES to move.console.files),
            bytes = if (outcome == null) 0L else move.console.bytes
        )
    }

    /**
     * Moves one console. Null when it stays where it was; otherwise whether ES-DE is in step
     * (false = ES-DE is set up here but its path was not changed, the user has to point it there).
     * Downloads for the console wait from before the copy until the old folder is cleaned up.
     */
    private suspend fun moveConsole(snap: Snapshot, move: SmartStorage.Move, previous: SmartStorage.Record?): Boolean? {
        val c = move.console
        val source = snap.folders[c.id] ?: return null
        moveGate.hold(c.id)
        try {
            if (isBusy(c.id)) return null
            val targetBase = if (move.to == SmartStorage.Place.SD) snap.sdUri else snap.root
            val target = StorageHelper.createDirectory(context, targetBase, c.folder) ?: return null
            val targetDir = DiskScanner.dirOf(target.uri) ?: return null
            val manifest = manifestFile(c.id, move.to)
            val copied = HashMap(readManifest(manifest))

            // 1. Copy everything. A file at the target counts as copied only when this feature wrote it
            // from this very original (the manifest); anything else is copied again.
            for (file in source.files) {
                currentCoroutineContext().ensureActive()
                if (copyOne(file, target, copied[file.path])) {
                    if (copied[file.path] != file.stamp) { copied[file.path] = file.stamp; appendManifest(manifest, file) }
                } else {
                    Log.w(TAG, "Could not copy ${file.path}")
                    return null
                }
            }
            // 2. Verify: every file there, every size equal. Otherwise nothing changes on the source side.
            val found = HashMap<String, Long>()
            sizes(targetDir, "", found)
            if (!SmartStorage.verified(source.files.associate { it.path to it.size }, found)) return null
            if (isBusy(c.id)) return null

            // 3. Switch, clean up, ES-DE. From here on the console is finished even when the run is
            // stopped, so no console is left with its games on both sides.
            return withContext(NonCancellable) { switchAndClean(c, move.to, source, target, copied, previous, manifest) }
        } finally {
            moveGate.release(c.id)
        }
    }

    private suspend fun switchAndClean(
        c: SmartStorage.Console,
        to: SmartStorage.Place,
        source: Folder,
        target: DocumentFile,
        copied: MutableMap<String, SmartStorage.Stamp>,
        previous: SmartStorage.Record?,
        manifest: File
    ): Boolean {
        val now = System.currentTimeMillis()
        val record = if (to == SmartStorage.Place.SD) SmartStorage.Record(c.id, SmartStorage.Place.SD, now, c.folder, target.uri.toString())
            else SmartStorage.Record(c.id, SmartStorage.Place.INTERNAL, now, c.folder, "")
        val receipts = source.files.map { file ->
            val destination = StorageHelper.findFile(target, file.path) ?: error("Missing target")
            val hash = copier.hash(file.uri)
            check(copier.mayRemove(file.uri, destination.uri, hash)) { "Copy changed" }
            OperationFile(file.uri.toString(), destination.uri.toString(), file.path, file.size, hash)
        }
        var operation = LibraryOperation(kind = "smart_move", title = c.id, source = DiskScanner.uriOf(source.dir).toString(), target = target.uri.toString(), consoleId = c.id, files = receipts, phase = "ready", destinationPlace = to.name)
        history.put(operation)
        val root = settingsRepository.downloadDirectory.first()
        val custom = settingsRepository.consoleDownloadDirectories.first()[c.id]
        gamePackages.rebaseMovedFiles(setOf(root, custom.orEmpty()), source.files.associate { it.uri.toString() to it.path },
            if (to == SmartStorage.Place.SD) target.uri.toString() else root,
            if (to == SmartStorage.Place.SD) "" else c.folder, c.id)
        // The folder setting and the record in one edit.
        settings.switchConsole(record, record.uri)
        operation = operation.copy(phase = "cleanup")
        history.put(operation)

        // Originals go only when, listed right before, they are exactly as they were copied; a file
        // that changed meanwhile (or arrived) is copied again and goes on the next pass.
        repeat(CLEAN_PASSES) {
            val current = ArrayList<FileItem>()
            walk(source.dir, "", current)
            if (current.isEmpty()) return@repeat
            for (file in current) {
                val destination = StorageHelper.findFile(target, file.path)
                val expected = operation.files.firstOrNull { it.source == file.uri.toString() }?.hash
                if (destination != null && expected != null && copier.mayRemove(file.uri, destination.uri, expected)) {
                    if (!DiskScanner.delete(context, file.uri)) Log.w(TAG, "Copied ${file.path} but could not delete the original")
                } else if (copyOne(file, target, null)) {
                    copied[file.path] = file.stamp
                    val destination = StorageHelper.findFile(target, file.path) ?: continue
                    val receipt = OperationFile(file.uri.toString(), destination.uri.toString(), file.path, file.size, copier.hash(file.uri))
                    operation = operation.copy(files = operation.files.filterNot { it.source == receipt.source } + receipt)
                    history.put(operation)
                }
            }
        }
        val remaining = ArrayList<FileItem>()
        walk(source.dir, "", remaining)
        operation = operation.copy(phase = if (remaining.isEmpty()) "done" else "cleanup")
        history.put(operation)
        pruneEmpty(source.dir)
        runCatching { manifest.delete() }

        // ES-DE follows when that is safe; otherwise the user points it to the new folder.
        val esde = runCatching {
            if (to == SmartStorage.Place.SD) esdeToSd(c.folder, target.uri) else esdeBack(c.folder, previous)
        }.onFailure { Log.w(TAG, "ES-DE path not updated: ${it.message}") }.getOrDefault(Esde.Left)
        if (esde is Esde.Patched) settings.putRecord(record.copy(esdeWrote = esde.wrote, esdePrevious = esde.previous, esdeInserted = esde.inserted))
        return esde != Esde.Left
    }

    private fun walk(dir: DiskDir, path: String, out: MutableList<FileItem>) {
        for (entry in (DiskScanner.listOrNull(context, dir, strict = true) ?: error("Incomplete directory listing"))) {
            if (entry.name.startsWith(".dogmatix-")) continue
            if (entry.isDirectory) walk(DiskScanner.dirOf(dir, entry), LibraryMove.join(path, entry.name), out)
            else out += FileItem(path, entry.name, entry.size, entry.lastModified, entry.uri)
        }
    }

    private fun sizes(dir: DiskDir, path: String, out: MutableMap<String, Long>) {
        for (entry in (DiskScanner.listOrNull(context, dir, strict = true) ?: error("Incomplete directory listing"))) {
            if (entry.name.startsWith(".dogmatix-")) continue
            if (entry.isDirectory) sizes(DiskScanner.dirOf(dir, entry), LibraryMove.join(path, entry.name), out)
            else out[LibraryMove.join(path, entry.name)] = entry.size
        }
    }

    /** Removes the folders left empty below [dir] (and [dir] itself), deepest first; only folders that list as empty. */
    private fun pruneEmpty(dir: DiskDir) {
        val children = DiskScanner.listOrNull(context, dir) ?: return
        for (child in children) if (child.isDirectory) pruneEmpty(DiskScanner.dirOf(dir, child))
        if (DiskScanner.listOrNull(context, dir)?.isEmpty() == true) DiskScanner.delete(context, DiskScanner.uriOf(dir))
    }

    // ---- The manifest of copied files -----------------------------------------------------------

    private fun manifestFile(consoleId: String, to: SmartStorage.Place): File {
        val dir = File(context.filesDir, "smart_storage").apply { mkdirs() }
        return File(dir, URLEncoder.encode(consoleId, "UTF-8") + "-" + to.name + ".txt")
    }

    private fun readManifest(file: File): Map<String, SmartStorage.Stamp> =
        runCatching { if (file.exists()) SmartStorage.Manifest.decode(file.readText()) else emptyMap() }.getOrDefault(emptyMap())

    private fun appendManifest(file: File, item: FileItem) {
        runCatching { file.appendText(SmartStorage.Manifest.encode(item.path, item.stamp) + "\n") }
    }

    /**
     * Copies one file below [target]. With [trusted] (the manifest's entry) a file already there of
     * the right size is kept when it was written from this very original; anything else at the
     * target is replaced. A half-written copy is removed again. True when the copy is complete.
     */
    private suspend fun copyOne(file: FileItem, target: DocumentFile, trusted: SmartStorage.Stamp?): Boolean {
        val dir = StorageHelper.createDirectory(target, file.dirPath) ?: return false
        return try {
            copier.copy(file.uri, dir, file.name) { bytes -> _state.update { it.copy(bytesDone = it.bytesDone + bytes) } }
            true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Log.w(TAG, "Copy refused for ${file.name}", e); false }
    }

    // ---- ES-DE ----------------------------------------------------------------------------------

    /**
     * Points ES-DE's system at the console's folder on the SD card. Only when it is clearly safe:
     * ES-DE's ROM folder is the library's download folder, the system still reads its plain ROM
     * folder path, and every file involved could be read. An existing file that cannot be read is
     * never replaced.
     */
    private suspend fun esdeToSd(folder: String, target: Uri): Esde {
        val (root, doc) = esdeRoot() ?: return Esde.NotSet
        val settingsXml = (readStrict(root, listOf("settings", "es_settings.xml")) as? Read.Text)?.text ?: return Esde.Left
        val libraryRoot = DiskScanner.rootOf(settingsRepository.downloadDirectory.first()) ?: return Esde.Left
        val libraryPath = DiskScanner.canonicalPath(libraryRoot.treeUri.authority, libraryRoot.documentId)
        if (!SmartStorage.samePath(SmartStorage.esdeRomDirectory(settingsXml), libraryPath)) return Esde.Left
        val existing = when (val r = readStrict(root, listOf("custom_systems", "es_systems.xml"))) {
            Read.Absent -> null
            Read.Unreadable -> return Esde.Left
            is Read.Text -> r.text
        }
        val sdPath = DiskScanner.canonicalPath(target.authority, DocumentsContract.getDocumentId(target)) ?: return Esde.Left
        val bundled = ApkAssets.readText(context, EsdeConfigService.ESDE_PACKAGE, ESDE_BUNDLED_SYSTEMS)
        val patch = SmartStorage.esdePatch(existing, bundled, folder, sdPath) ?: return Esde.Left
        StorageHelper.writeTextSafely(context, doc, "custom_systems", "es_systems.xml", patch.content)
        return Esde.Patched(sdPath, patch.previous, patch.inserted)
    }

    /** Undoes [esdeToSd] when the console comes back: the block it added goes, a changed path gets its old value. */
    private suspend fun esdeBack(folder: String, previous: SmartStorage.Record?): Esde {
        val (root, doc) = esdeRoot() ?: return Esde.NotSet
        if (previous == null || previous.esdeWrote.isEmpty()) return Esde.Left
        val existing = (readStrict(root, listOf("custom_systems", "es_systems.xml")) as? Read.Text)?.text ?: return Esde.Left
        val content = SmartStorage.esdeRestore(existing, folder, previous.esdeWrote, previous.esdePrevious, previous.esdeInserted) ?: return Esde.Left
        StorageHelper.writeTextSafely(context, doc, "custom_systems", "es_systems.xml", content)
        return Esde.Restored
    }

    /** ES-DE's folder as set in Dogmatix+, when it is reachable and writable. */
    private suspend fun esdeRoot(): Pair<DiskDir, DocumentFile>? {
        val uri = settingsRepository.esdeDirectory.first().takeIf { it.isNotBlank() } ?: return null
        val root = DiskScanner.rootOf(uri) ?: return null
        val doc = StorageHelper.getDocumentFile(context, uri)?.takeIf { it.isDirectory && it.canWrite() } ?: return null
        return root to doc
    }

    /**
     * Reads [path] below [root], telling a file that is not there from one that cannot be read:
     * every folder on the way must list completely (strict), and the file must open.
     */
    private fun readStrict(root: DiskDir, path: List<String>): Read {
        var dir = root
        path.forEachIndexed { i, name ->
            val entries = DiskScanner.listOrNull(context, dir, strict = true) ?: return Read.Unreadable
            val entry = entries.firstOrNull { it.name == name } ?: entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return Read.Absent
            if (i < path.lastIndex) {
                if (!entry.isDirectory) return Read.Unreadable
                dir = DiskScanner.dirOf(dir, entry)
            } else {
                if (entry.isDirectory) return Read.Unreadable
                val text = runCatching { context.contentResolver.openInputStream(entry.uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
                return if (text != null) Read.Text(text) else Read.Unreadable
            }
        }
        return Read.Unreadable
    }

    // ---- Notification ---------------------------------------------------------------------------

    private fun canNotify(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 8,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun notifyProgress(consoleId: String, index: Int, count: Int) {
        if (!canNotify()) return
        val notification = NotificationCompat.Builder(context, DogmatixApplication.SYNC_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_drive_file_move)
            .setContentTitle(context.getString(R.string.store8_notify_title))
            .setContentText(context.getString(R.string.store8_notify_progress, ConsoleFormatter.getConsoleDisplayName(consoleId), index + 1, count))
            .setProgress(count, index, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification) }
    }

    private fun notifyDone(moved: Int, failed: Int, bytes: Long) {
        if (!canNotify()) return
        if (moved == 0 && failed == 0) { cancelNotification(); return }
        val text = if (failed == 0) context.resources.getQuantityString(R.plurals.store8_notify_done, moved, moved, Formatter.formatShortFileSize(context, bytes))
            else context.resources.getQuantityString(R.plurals.store8_notify_failed, failed, failed)
        val notification = NotificationCompat.Builder(context, DogmatixApplication.SYNC_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_drive_file_move)
            .setContentTitle(context.getString(R.string.store8_notify_title))
            .setContentText(text)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification) }
    }

    private fun cancelNotification() { runCatching { context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) } }

    private companion object {
        const val ALL = "*"
        /** Clean-up passes over the old folder: originals, then what changed or arrived meanwhile. */
        const val CLEAN_PASSES = 3
    }
}
