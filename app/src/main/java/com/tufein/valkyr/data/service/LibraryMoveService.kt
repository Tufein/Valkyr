package com.tufein.valkyr.data.service

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tufein.valkyr.R
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.util.ActionCount
import com.tufein.valkyr.util.ActionKind
import com.tufein.valkyr.util.ActionReason
import com.tufein.valkyr.util.ActionTopic
import com.tufein.valkyr.util.DiskDir
import com.tufein.valkyr.util.DiskScanner
import com.tufein.valkyr.util.LibraryMove
import com.tufein.valkyr.util.StorageHelper
import com.tufein.valkyr.util.ToastUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import javax.inject.Inject
import javax.inject.Singleton
import androidx.documentfile.provider.DocumentFile

private const val TAG = "LibraryMoveService"

/** [ANOTHER_MOVE]: smart storage (8.0) is moving console folders; one mover at a time. */
enum class MoveProblem { NO_SOURCE, CANNOT_OPEN, OVERLAP, DOWNLOADS_ACTIVE, NO_ROOM, ANOTHER_MOVE }

data class MoveState(
    val running: Boolean = false,
    val scanning: Boolean = false,
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val current: String = "",
    val failed: Int = 0,
    /** Set when the run ended: the library is where it was meant to go (all files moved and the setting switched). */
    val finished: Boolean = false,
    val problem: MoveProblem? = null,
    /** For [MoveProblem.NO_ROOM]: how much is needed and how much is free. */
    val needBytes: Long = 0,
    val freeBytes: Long = 0
)

/**
 * Moves the download folder to another storage: every file is copied to the same place below the
 * new folder, the copy is checked against the original's size, and only then is the original
 * deleted. A file that cannot be copied stays where it is. Run it again after an interruption and
 * it carries on (files already there are not copied twice). When everything is moved, Dogmatix
 * switches to the new folder; folders given to a console of its own are not touched.
 */
@Singleton
class LibraryMoveService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val downloadService: DownloadService,
    private val libraryIndex: LibraryIndexService,
    private val moveGate: StorageMoveGate,
    private val copier: VerifiedDocumentCopy,
    private val history: OperationHistoryService,
    private val actionLog: ActionLogService,
    private val gamePackages: GamePackageService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow(MoveState())
    val state: StateFlow<MoveState> = _state.asStateFlow()

    fun start(destinationUri: String) {
        if (job?.isActive == true) return
        job = scope.launch { run(destinationUri) }
    }

    fun cancel() { job?.cancel() }

    fun dismiss() { if (job?.isActive != true) _state.value = MoveState() }

    private suspend fun run(destinationUri: String) {
        if (!moveGate.lock.tryLock()) return stop(MoveProblem.ANOTHER_MOVE)
        try { runLocked(destinationUri) } finally { moveGate.lock.unlock() }
    }

    private suspend fun runLocked(destinationUri: String) {
        _state.value = MoveState(running = true, scanning = true)
        moveGate.hold("*")
        var operation: LibraryOperation? = null
        try {
            if (downloadService.hasActiveDownloads()) return stop(MoveProblem.DOWNLOADS_ACTIVE)
            val root = settingsRepository.downloadDirectory.first()
            val pending = history.entries.value.firstOrNull { it.kind == "library_move" && it.phase != "done" && it.target == destinationUri && (it.source == root || it.target == root) }
            val sourceUri = pending?.source ?: root
            val source = sourceUri.takeIf { it.isNotBlank() }?.let { DiskScanner.rootOf(it) } ?: return stop(MoveProblem.NO_SOURCE)
            val destination = DiskScanner.rootOf(destinationUri) ?: return stop(MoveProblem.CANNOT_OPEN)
            if (LibraryMove.overlaps(DiskScanner.canonicalKey(source), DiskScanner.canonicalKey(destination))) return stop(MoveProblem.OVERLAP)
            operation = pending ?: LibraryOperation(kind = "library_move", title = "", source = sourceUri, target = destinationUri)
            history.put(operation)
            if (operation.phase == "copying" || operation.phase == "interrupted") {
                val files = ArrayList<Pair<LibraryMove.Item, Uri>>()
                walk(source, "", files)
                val total = LibraryMove.totalBytes(files.map { it.first })
                val free = StorageHelper.getFreeBytes(context, destinationUri)
                // Existing verified copies need no additional space.
                val remaining = files.sumOf { (item, uri) ->
                    val existing = StorageHelper.findFile(StorageHelper.getDocumentFile(context, destinationUri) ?: return stop(MoveProblem.CANNOT_OPEN), LibraryMove.join(item.dirPath, item.name))
                    if (existing != null && runCatching { copier.hash(uri) == copier.hash(existing.uri) }.getOrDefault(false)) 0L else item.size
                }
                if (LibraryMove.fits(remaining, free) == false) {
                    _state.value = MoveState(problem = MoveProblem.NO_ROOM, needBytes = remaining, freeBytes = free ?: 0)
                    return
                }
                _state.value = MoveState(running = true, filesTotal = files.size, bytesTotal = total)
                val currentUris = files.map { it.second.toString() }.toSet()
                operation = operation.copy(files = operation.files.filter { it.source in currentUris })
                history.put(operation)
                files.forEachIndexed { index, (item, uri) ->
                    currentCoroutineContext().ensureActive()
                    _state.update { it.copy(current = item.name, filesDone = index) }
                    val directory = StorageHelper.createDirectory(context, destinationUri, item.dirPath) ?: error("Cannot open destination")
                    val target = copier.copy(uri, directory, item.name) { bytes -> _state.update { it.copy(bytesDone = it.bytesDone + bytes) } }
                    val hash = copier.hash(uri)
                    check(copier.mayRemove(uri, target.uri, hash)) { "Source changed during copy" }
                    val receipt = OperationFile(uri.toString(), target.uri.toString(), item.name, item.size, hash,
                        relativePath = LibraryMove.join(item.dirPath, item.name))
                    val currentOperation = requireNotNull(operation)
                    operation = currentOperation.copy(files = currentOperation.files.filterNot { it.source == receipt.source } + receipt)
                    history.put(operation)
                }
                operation = requireNotNull(operation).copy(phase = "ready")
                history.put(operation)
            }
            // All games are still in the source until every copy has passed its content check.
            for (receipt in operation.files) {
                if (copier.hash(Uri.parse(receipt.target)) != receipt.hash) error("Destination changed; originals kept")
            }
            val legacyPaths = if (operation.files.any { it.relativePath == null }) {
                val found = ArrayList<Pair<LibraryMove.Item, Uri>>()
                walk(destination, "", found)
                found.associate { (item, uri) -> uri.toString() to LibraryMove.join(item.dirPath, item.name) }
            } else emptyMap()
            gamePackages.rebaseMovedFiles(setOf(sourceUri), operation.files.associate { receipt ->
                receipt.source to (receipt.relativePath ?: legacyPaths[receipt.target] ?: error("Moved path unavailable"))
            }, destinationUri)
            settingsRepository.updateDownloadDirectory(destinationUri)
            operation = operation.copy(phase = "cleanup")
            history.put(operation)
            var failed = 0
            val cleaned = operation.files.map { receipt ->
                if (receipt.removed) receipt else {
                    val original = DocumentFile.fromSingleUri(context, Uri.parse(receipt.source))
                    val absent = original?.exists() == false
                    val removed = absent || (copier.mayRemove(Uri.parse(receipt.source), Uri.parse(receipt.target), receipt.hash) && DiskScanner.delete(context, Uri.parse(receipt.source)))
                    if (!removed) failed++
                    receipt.copy(removed = removed)
                }
            }
            operation = operation.copy(files = cleaned, phase = if (failed == 0) "done" else "cleanup")
            history.put(operation)
            libraryIndex.requestRefresh()
            val total = cleaned.sumOf { it.bytes }
            actionLog.record(
                ActionKind.MOVED, topic = ActionTopic.LIBRARY_FOLDER, reason = if (failed > 0) ActionReason.ORIGINALS_LEFT else null,
                counts = mapOf(ActionCount.FILES to cleaned.size, ActionCount.LEFT to failed), bytes = total
            )
            _state.value = MoveState(filesDone = cleaned.size, filesTotal = cleaned.size, bytesDone = total, bytesTotal = total, failed = failed, finished = true)
        } catch (e: CancellationException) {
            operation?.takeIf { it.phase == "copying" }?.let { history.put(it.copy(phase = "interrupted")) }
            _state.update { it.copy(running = false, scanning = false) }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Move failed", e)
            actionLog.record(ActionKind.MOVE_FAILED, topic = ActionTopic.LIBRARY_FOLDER)
            operation?.takeIf { it.phase == "copying" }?.let { history.put(it.copy(phase = "interrupted")) }
            _state.update { it.copy(running = false, scanning = false, finished = true, failed = it.failed.coerceAtLeast(1)) }
        } finally { moveGate.release("*") }
    }

    private fun stop(problem: MoveProblem) { _state.value = MoveState(problem = problem) }

    private fun walk(dir: DiskDir, path: String, out: MutableList<Pair<LibraryMove.Item, Uri>>) {
        val entries = DiskScanner.listOrNull(context, dir, strict = true) ?: error("Directory cannot be read completely")
        for (entry in entries) {
            if (entry.name.startsWith(".dogmatix-")) continue
            if (entry.isDirectory) walk(DiskScanner.dirOf(dir, entry), LibraryMove.join(path, entry.name), out)
            else out += LibraryMove.Item(path, entry.name, entry.size) to entry.uri
        }
    }
}
