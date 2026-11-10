package com.tufein.valkyr.data.service

import android.content.Context
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.DiskFile
import com.tufein.valkyr.util.DuplicateFinder
import com.tufein.valkyr.util.DuplicateGroup
import com.tufein.valkyr.util.ExportGame
import com.tufein.valkyr.util.GameEntry
import com.tufein.valkyr.util.PlaylistPlan
import com.tufein.valkyr.util.PlaylistPlanner
import com.tufein.valkyr.util.SetChecker
import com.tufein.valkyr.util.SetProblem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import com.tufein.valkyr.util.BoundedStreams

/** The result of looking through the collection for broken sets and games that lack a playlist. */
data class SetsReport(
    val problems: List<SetProblem>,
    val playlists: List<PlaylistPlan>,
    val filesChecked: Int
)

/** What the storage screen shows about the collection on disk. */
data class DiskOverview(val entries: List<GameEntry>, val freeBytes: Long?, val folderSet: Boolean)

/**
 * The library tools that work on the files on disk: the check for broken disc sets, creating
 * `.m3u` playlists for multi-disc games, the disk usage overview and the export of the collection.
 * The disk is read through [LibraryScanService], the same walk the duplicate finder uses.
 */
@Singleton
class LibraryToolsService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val scanService: LibraryScanService
) {

    suspend fun checkSets(): SetsReport = withContext(Dispatchers.IO) {
        val snapshot = scanService.scan()
        val problems = SetChecker.check(snapshot.files) { readSheet(it) }
        SetsReport(problems, PlaylistPlanner.plan(snapshot.files), snapshot.files.size)
    }

    private fun readSheet(file: DiskFile): String? = runCatching {
        context.contentResolver.openInputStream(file.uri.toUri())?.use { BoundedStreams.read(it, SetChecker.MAX_SHEET_BYTES.toInt()) }?.toString(Charsets.UTF_8)
    }.getOrNull()

    /** Writes the playlist next to the discs; false when the folder refuses. */
    suspend fun createPlaylist(plan: PlaylistPlan): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        runCatching {
            val created = DocumentsContract.createDocument(context.contentResolver, plan.dirUri.toUri(), "application/octet-stream", plan.fileName)
                ?: return@runCatching false
            context.contentResolver.openOutputStream(created)?.use { it.write(plan.content.toByteArray(Charsets.UTF_8)) } ?: return@runCatching false
            true
        }.getOrDefault(false)
    }

    /** Duplicate games on disk (same game more than once), for the space a bulk download could get back. */
    suspend fun duplicateGroups(): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        DuplicateFinder.find(scanService.scan().files)
    }

    suspend fun disk(): DiskOverview = withContext(Dispatchers.IO) {
        val snapshot = scanService.scan()
        DiskOverview(DuplicateFinder.entries(snapshot.files), snapshot.freeBytes, snapshot.rootDisplay.isNotBlank() || snapshot.files.isNotEmpty())
    }

    /** Every game on disk as an export row (console name, title, files, size, folder). */
    suspend fun exportGames(unknownFolder: (String) -> String, rootFolder: String): List<ExportGame> = withContext(Dispatchers.IO) {
        DuplicateFinder.entries(scanService.scan().files).map { entry ->
            val console = entry.consoleId?.let { ConsoleFormatter.getConsoleDisplayName(it) }
                ?: if (entry.scope.isEmpty()) rootFolder else unknownFolder(entry.scope.removePrefix("folder:"))
            ExportGame(console, entry.baseName, entry.files.size, entry.size, entry.folder)
        }
    }
}
