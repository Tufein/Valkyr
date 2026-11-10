package com.tufein.valkyr.data.service

import com.tufein.valkyr.data.local.dao.DownloadableFileDao
import com.tufein.valkyr.data.local.entity.DownloadableFileEntity
import com.tufein.valkyr.data.model.DownloadStatus
import com.tufein.valkyr.util.ActionEntry
import com.tufein.valkyr.util.SearchNormalizer
import com.tufein.valkyr.util.UndoAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** How a way back ended. [PARTIAL]: some of the files are back, the rest is still in the trash. */
enum class UndoResult { DONE, PARTIAL, FAILED }

/**
 * The ways back of the action history ([UndoAction]). Restore goes through the recovery journal and
 * [TrashService.restore], exactly like Tools -> Trash and recovery; Download again queues the
 * library's own row of the game. Nothing here deletes anything.
 */
@Singleton
class ActionUndoService @Inject constructor(
    private val journal: OperationHistoryService,
    private val trash: TrashService,
    private val fileDao: DownloadableFileDao,
    private val downloadService: DownloadService,
    private val sourceTrack: SourceTrackService,
    private val actionLog: ActionLogService,
    private val libraryIndex: LibraryIndexService,
    private val profiles: ProfileService
) {
    /** Changes when games appear on or leave the device: Download again is checked again then. */
    val ownership: Flow<Set<String>> get() = libraryIndex.ownedKeys

    /** Whether [action] of [entry] works right now (the files are still in the trash, the game is still in the library). */
    suspend fun available(entry: ActionEntry, action: UndoAction): Boolean = withContext(Dispatchers.IO) {
        try {
            when (action) {
                UndoAction.RESTORE -> restorable(entry.opId)
                UndoAction.DOWNLOAD_AGAIN -> libraryRow(entry)?.let { !queued(it.fileName) && !onDevice(it, entry) } ?: false
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
    }

    /** Runs [action] of [entry]. [UndoResult.DONE] only when the whole game is back (or queued). */
    suspend fun undo(entry: ActionEntry, action: UndoAction): UndoResult = withContext(Dispatchers.IO) {
        when (action) {
            UndoAction.RESTORE -> restore(entry)
            UndoAction.DOWNLOAD_AGAIN -> {
                try {
                    val row = libraryRow(entry)
                    if (row == null || queued(row.fileName) || onDevice(row, entry)) UndoResult.FAILED else {
                        val selected = sourceTrack.pickBest(row, sameNameOnly = true)
                        if (!allowed(selected) || queued(selected.fileName)) return@withContext UndoResult.FAILED
                        downloadService.startDownload(selected)
                        // Queueing is the successful action; a history write cannot undo it.
                        try { actionLog.markUndone(entry.id) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                        UndoResult.DONE
                    }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { UndoResult.FAILED }
            }
        }
    }

    private suspend fun restore(entry: ActionEntry): UndoResult {
        val id = entry.opId ?: return UndoResult.FAILED
        if (!restorable(id)) return UndoResult.FAILED
        val failed = try {
            trash.restore(id)
            false
        } catch (e: CancellationException) { throw e } catch (_: Exception) { true }
        finally {
            // Partial restores change ownership too, including cancellation after one file.
            libraryIndex.requestRefresh()
        }
        val after = journal.get(id)
        // Back as a whole only when the journal says so; TrashService then wrote the "restored" line.
        if (!failed && after?.phase == "done") return UndoResult.DONE
        // A missing backup alone proves nothing: it may have been deleted outside the app.
        return if (trash.restoredFiles(id) > 0) UndoResult.PARTIAL else UndoResult.FAILED
    }

    private fun restorable(opId: String?): Boolean {
        val op = opId?.let(journal::get) ?: return false
        return op.kind == "trash" && op.phase !in setOf("done", "purging")
    }

    private fun queued(fileName: String): Boolean = downloadService.downloads.value.any {
        it.fileName == fileName && it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.COPYING, DownloadStatus.UNZIPPING, DownloadStatus.PAUSED)
    }

    /**
     * The game is back on the device already (a bulk download of it after [entry], say, whose lines
     * were folded into a summary): on disk per the library index, or its download finished since.
     */
    private fun onDevice(row: DownloadableFileEntity, entry: ActionEntry): Boolean =
        libraryIndex.isOwned(row) || downloadService.downloads.value.any {
            it.fileName == row.fileName && it.status == DownloadStatus.COMPLETED && (it.finishedAt ?: 0L) >= entry.at
        }

    /**
     * The library row of the game [entry] names: by its library file name; a removal only knows the
     * names on disk, so failing that, the one row of the same console whose name (or file name
     * without extension) is the same. Never a guess between several versions.
     */
    private suspend fun libraryRow(entry: ActionEntry): DownloadableFileEntity? {
        val file = entry.fileName?.takeIf { it.isNotBlank() } ?: return null
        val exact = fileDao.filesByFileNames(listOf(file)).filter { entry.consoleId.isNullOrBlank() || it.consoleId == entry.consoleId }
        val row = exact.takeIf { rows -> rows.map { it.consoleId }.distinct().size == 1 }?.firstOrNull()
        if (row != null) return row.takeIf { allowed(it) }
        val console = entry.consoleId?.takeIf { it.isNotBlank() } ?: return null
        val title = SearchNormalizer.key(entry.title.ifBlank { file.substringBeforeLast('.') })
        if (title.isEmpty()) return null
        val stem = file.substringBeforeLast('.')
        val same = fileDao.filesMatching(title, console, 20).filter {
            it.fileName.substringBeforeLast('.').equals(stem, ignoreCase = true) || SearchNormalizer.key(it.name) == title
        }
        return same.singleOrNull()?.takeIf { allowed(it) }
    }

    private suspend fun allowed(row: DownloadableFileEntity): Boolean =
        profiles.current().allows(row.consoleId, fileDao.getTagsForFile(row.id))
}
