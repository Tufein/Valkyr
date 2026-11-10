package com.tufein.valkyr.data.service

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.tufein.valkyr.data.local.dao.ConsoleDao
import com.tufein.valkyr.data.local.dao.DownloadableFileDao
import com.tufein.valkyr.data.local.entity.DownloadableFileEntity
import com.tufein.valkyr.data.model.DownloadStatus
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.util.ActionKind
import com.tufein.valkyr.util.ActionReason
import com.tufein.valkyr.util.ActionTopic
import com.tufein.valkyr.util.BoundedStreams
import com.tufein.valkyr.util.ConsoleFolderAliases
import com.tufein.valkyr.util.DiskDir
import com.tufein.valkyr.util.DiskScanner
import com.tufein.valkyr.util.FileParsingUtils
import com.tufein.valkyr.util.LibraryRef
import com.tufein.valkyr.util.LibraryRepair
import com.tufein.valkyr.util.LibraryRestrictions
import com.tufein.valkyr.util.RepairFile
import com.tufein.valkyr.util.RepairFinding
import com.tufein.valkyr.util.RepairFix
import com.tufein.valkyr.util.RepairFixKind
import com.tufein.valkyr.util.RepairInput
import com.tufein.valkyr.util.SetChecker
import com.tufein.valkyr.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import com.tufein.valkyr.util.RepairKind

/** Where a check is; [done] / [total] count folders or sheets (total 0 = not known yet). */
data class RepairProgress(val phase: Phase, val done: Int = 0, val total: Int = 0) {
    enum class Phase { SCANNING, LEFTOVERS, SHEETS, LIBRARY }
}

/**
 * One finished check, for the profile it ran under. [incomplete]: a folder could not be listed
 * completely or a bound was reached, so some problems may be missing (the screen says so);
 * [indexCompared] false when the "on this device" marks could not be compared at all.
 */
data class RepairReportData(
    val profileId: String,
    val restrictions: LibraryRestrictions,
    val findings: List<RepairFinding>,
    val filesChecked: Int,
    val truncated: Boolean,
    val incomplete: Boolean,
    val indexCompared: Boolean,
    val folderSet: Boolean
)

/** How a fix ended. [STALE]: the files changed since the check (nothing was done). */
enum class RepairOutcome { DONE, STALE, FAILED }

/**
 * Tools -> Library repair (3.0.0). Reads the library through the existing services (the disk walk of
 * [LibraryScanService], the owned marks of [LibraryIndexService], the download queue, the library
 * table) plus one bounded walk of its own for the app's hidden staging files, and lets the pure
 * [LibraryRepair] rules judge them. Fixes reuse the existing ways: removals go through
 * [TrashService.move] (restorable, logged with [ActionReason.REPAIR]), playlists are written with
 * [StorageHelper.writeTextSafely], downloads are queued like Download again in the action history,
 * and a rescan is [LibraryIndexService.refresh]. Every fix rechecks its files first.
 */
@Singleton
class LibraryRepairService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val scanService: LibraryScanService,
    private val libraryIndex: LibraryIndexService,
    private val settingsRepository: SettingsRepository,
    private val consoleDao: ConsoleDao,
    private val fileDao: DownloadableFileDao,
    private val downloadService: DownloadService,
    private val downloadFiles: DownloadFileManager,
    private val sourceTrack: SourceTrackService,
    private val trash: TrashService,
    private val actionLog: ActionLogService,
    private val profiles: ProfileService
) {

    suspend fun check(progress: (RepairProgress) -> Unit = {}): RepairReportData = withContext(Dispatchers.IO) {
        val profileId = profiles.currentIdNow()
        val restrictions = profiles.current()
        val folderSet = settingsRepository.downloadDirectory.first().isNotBlank() || settingsRepository.consoleDownloadDirectories.first().isNotEmpty()

        downloadService.awaitRestored()
        progress(RepairProgress(RepairProgress.Phase.SCANNING))
        val snapshot = scanService.scan()
        currentCoroutineContext().ensureActive()

        val walk = walk { progress(RepairProgress(RepairProgress.Phase.LEFTOVERS, it)) }

        val sheetFiles = snapshot.files.filter {
            it.name.substringAfterLast('.', "").lowercase() in SHEETS && it.size in 1..SetChecker.MAX_SHEET_BYTES
        }
        val toRead = sheetFiles.take(MAX_SHEETS)
        val sheets = HashMap<String, String?>(toRead.size * 2)
        toRead.forEachIndexed { i, file ->
            currentCoroutineContext().ensureActive()
            if (i % 25 == 0) progress(RepairProgress(RepairProgress.Phase.SHEETS, i, toRead.size))
            sheets[file.fileId] = readSheet(file.uri)
        }

        progress(RepairProgress(RepairProgress.Phase.LIBRARY))
        val library = libraryRows(LibraryRepair.lookupNames(snapshot.files))
        val queue = downloadService.downloads.value
        val ownedStaging = queue.filter { it.status != DownloadStatus.COMPLETED }
            .mapNotNull { item -> downloadService.entityFor(item.fileName)?.let { runCatching { downloadFiles.stagingName(it) }.getOrNull() } }.toSet()
        val busy = queue.any { it.status in ACTIVE }

        val result = LibraryRepair.check(
            RepairInput(
                files = snapshot.files,
                sheets = sheets,
                hidden = walk.hidden,
                ownedStaging = ownedStaging,
                unmatchedFolders = snapshot.unmatchedFolders,
                indexKeys = libraryIndex.ownedKeys.value.takeIf { walk.complete },
                diskNames = walk.names.takeIf { walk.complete },
                library = library,
                busy = busy
            )
        )
        // A provider that reports no size looks like 0 bytes: "empty" only counts when the file really has no content.
        val confirmed = result.findings.filter { f ->
            currentCoroutineContext().ensureActive()
            f.kind != RepairKind.EMPTY_FILE || f.files.all { isReallyEmpty(it.uri) }
        }
        val findings = LibraryRepair.visible(confirmed, restrictions, if (restrictions.active) hiddenByTag(confirmed, library, restrictions) else emptySet())
            .map { finding -> finding.copy(fixes = finding.fixes.filter { fix -> fix.download == null || allowedRow(fix.download, restrictions) }) }
        // A profile switched during the walk: these results belong to the other one.
        kotlin.check(profiles.currentIdNow() == profileId) { "Profile changed" }
        RepairReportData(
            profileId, restrictions, findings, snapshot.files.size, result.truncated,
            incomplete = !walk.complete || sheetFiles.size > toRead.size, indexCompared = walk.complete, folderSet = folderSet
        )
    }

    /** True while [report] still belongs to the active profile. */
    fun isCurrent(report: RepairReportData): Boolean = profiles.currentIdNow() == report.profileId

    /**
     * Runs one fix of [finding]. Runs to the end once started (a half-moved set would be worse than
     * either state) and never throws: the outcome says what happened.
     */
    suspend fun apply(report: RepairReportData, finding: RepairFinding, fix: RepairFix): RepairOutcome = withContext(NonCancellable + Dispatchers.IO) {
        try {
            if (!isCurrent(report) || finding.fixes.none { it == fix }) return@withContext RepairOutcome.STALE
            if (!unchanged(fix.trash)) return@withContext RepairOutcome.STALE
            when (fix.kind) {
                RepairFixKind.TRASH -> {
                    if (finding.kind == RepairKind.LEFTOVER_PART && fix.trash.any { it.name in ownedNow() }) return@withContext RepairOutcome.STALE
                    moveToTrash(finding, fix.trash)
                }
                RepairFixKind.CREATE_PLAYLIST -> {
                    val plan = fix.playlist ?: return@withContext RepairOutcome.FAILED
                    val dir = StorageHelper.getDocumentFile(context, plan.dirUri) ?: return@withContext RepairOutcome.FAILED
                    // Made since the check, or a disc went away: leave it to the next check.
                    if (dir.findFile(plan.fileName) != null || plan.discs.any { dir.findFile(it) == null }) return@withContext RepairOutcome.STALE
                    StorageHelper.writeTextSafely(context, dir, "", plan.fileName, plan.content)
                    actionLog.record(ActionKind.OTHER, title = plan.fileName, consoleId = plan.consoleId, fileName = plan.fileName, reason = ActionReason.REPAIR, count = 1)
                    libraryIndex.requestRefresh()
                }
                RepairFixKind.REWRITE_PLAYLIST -> {
                    val plan = fix.playlist ?: return@withContext RepairOutcome.FAILED
                    val dir = StorageHelper.getDocumentFile(context, plan.dirUri) ?: return@withContext RepairOutcome.FAILED
                    if (plan.discs.filter { '/' !in it && '\\' !in it }.any { dir.findFile(it) == null }) return@withContext RepairOutcome.STALE
                    // The old playlist goes to the trash first, so the rewrite can be undone there.
                    moveToTrash(finding, fix.trash)
                    StorageHelper.writeTextSafely(context, dir, "", plan.fileName, plan.content)
                    actionLog.record(ActionKind.OTHER, title = plan.fileName, consoleId = plan.consoleId, fileName = plan.fileName, reason = ActionReason.REPAIR, count = 1)
                }
                RepairFixKind.DOWNLOAD_AGAIN -> {
                    val ref = fix.download ?: return@withContext RepairOutcome.FAILED
                    val row = fileDao.filesByFileNames(listOf(ref.fileName)).firstOrNull { it.id == ref.rowId } ?: return@withContext RepairOutcome.STALE
                    if (!allowedRow(ref, profiles.current()) || queued(row.fileName)) return@withContext RepairOutcome.STALE
                    val selected = sourceTrack.pickBest(row, sameNameOnly = true)
                    if (queued(selected.fileName)) return@withContext RepairOutcome.STALE
                    // A cut-off copy goes to the trash first: the new download never lands on top of it.
                    if (fix.trash.isNotEmpty()) moveToTrash(finding, fix.trash)
                    downloadService.startDownload(selected)
                }
                RepairFixKind.RESCAN -> {
                    libraryIndex.refresh()
                    actionLog.record(ActionKind.OTHER, topic = ActionTopic.LIBRARY_REPAIR, reason = ActionReason.REPAIR)
                }
            }
            RepairOutcome.DONE
        } catch (_: Exception) {
            RepairOutcome.FAILED
        }
    }

    private suspend fun moveToTrash(finding: RepairFinding, files: List<RepairFile>) {
        val moved = trash.move(
            files.map { RemovalFile(it.uri, it.dirUri, it.name, it.size, "${it.folder}/${it.name}") },
            title = finding.subject.ifBlank { files.first().name },
            consoleId = finding.consoleId, fileName = files.firstOrNull()?.name, reason = ActionReason.REPAIR
        )
        kotlin.check(moved == files.distinctBy { it.uri }.size) { "Not every file was moved" }
        libraryIndex.requestRefresh()
    }

    /** Every file is still there with the size the check saw (the provider's size is trusted only when it gave one). */
    private fun unchanged(files: List<RepairFile>): Boolean = files.all { file ->
        val doc = runCatching { DocumentFile.fromSingleUri(context, file.uri.toUri()) }.getOrNull() ?: return@all false
        runCatching { doc.exists() && doc.isFile && (file.size <= 0L || doc.length() == file.size) && (file.size != 0L || doc.length() == 0L) }.getOrDefault(false) &&
            (file.size != 0L || isReallyEmpty(file.uri))
    }

    /** Opens the file and reads one byte: true only when it opens and has no content (an unreadable file is not confirmed). */
    private fun isReallyEmpty(uri: String): Boolean = runCatching {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.read() == -1 } ?: false
    }.getOrDefault(false)

    private fun ownedNow(): Set<String> = downloadService.downloads.value.filter { it.status != DownloadStatus.COMPLETED }
        .mapNotNull { item -> downloadService.entityFor(item.fileName)?.let { runCatching { downloadFiles.stagingName(it) }.getOrNull() } }.toSet()

    private fun queued(fileName: String): Boolean = downloadService.downloads.value.any { it.fileName == fileName && it.status in ACTIVE + DownloadStatus.PAUSED }

    private suspend fun allowedRow(ref: LibraryRef, restrictions: LibraryRestrictions): Boolean =
        !restrictions.active || restrictions.allows(ref.consoleId, fileDao.getTagsForFile(ref.rowId))

    /** Findings whose game carries a tag the profile hides (looked up through the library rows of their files). */
    private suspend fun hiddenByTag(findings: List<RepairFinding>, library: Map<String, LibraryRef>, restrictions: LibraryRestrictions): Set<String> {
        if (restrictions.hiddenTags.isEmpty()) return emptySet()
        val rowsByFinding = findings.associate { f ->
            val console = f.consoleId
            f.id to if (console == null) emptyList() else f.files.mapNotNull { library[LibraryRepair.libraryKey(console, it.name)]?.rowId } +
                f.fixes.mapNotNull { it.download?.rowId }
        }
        val ids = rowsByFinding.values.flatten().distinct()
        val tags = HashMap<Long, MutableList<String>>()
        ids.chunked(500).forEach { chunk -> fileDao.tagsOfFiles(chunk).forEach { tags.getOrPut(it.fileId) { mutableListOf() } += it.tag } }
        return rowsByFinding.filter { (_, rows) ->
            rows.any { id -> tags[id].orEmpty().any { t -> restrictions.hiddenTags.any { it.equals(t, ignoreCase = true) } } }
        }.keys
    }

    /** Library rows for (console, stored name) pairs, keyed by [LibraryRepair.libraryKey]; bounded batches. */
    private suspend fun libraryRows(names: Set<Pair<String, String>>): Map<String, LibraryRef> {
        val wanted = names.map { it.second }.distinct()
        val out = HashMap<String, LibraryRef>()
        val consoles = names.groupBy({ it.first }, { it.second.lowercase() }).mapValues { it.value.toSet() }
        for (chunk in wanted.chunked(LOOKUP_CHUNK)) {
            currentCoroutineContext().ensureActive()
            // Listings often keep the URL spelling of a name (`Game%20(USA).zip`).
            val query = (chunk + chunk.map { it.replace(" ", "%20") }).distinct()
            for (row in fileDao.filesByFileNames(query)) add(row, consoles, out)
        }
        return out
    }

    private fun add(row: DownloadableFileEntity, consoles: Map<String, Set<String>>, out: MutableMap<String, LibraryRef>) {
        val stored = runCatching { FileParsingUtils.storageFileName(row.fileName) }.getOrNull() ?: return
        if (stored.lowercase() !in consoles[row.consoleId].orEmpty()) return
        val key = LibraryRepair.libraryKey(row.consoleId, stored)
        // Two rows of one console with the same name (two sources): the smaller known size is the
        // "expected" one, so a valid copy from the smaller source is never called cut off.
        val previous = out[key]
        if (previous == null || (row.fileSize > 0L && (previous.size <= 0L || row.fileSize < previous.size))) out[key] = LibraryRef(row.id, row.consoleId, row.fileName, row.fileSize)
    }

    private fun readSheet(uri: String): String? = runCatching {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { BoundedStreams.read(it, SetChecker.MAX_SHEET_BYTES.toInt()) }?.toString(Charsets.UTF_8)
    }.getOrNull()

    private class Walk(val hidden: List<RepairFile>, val names: Set<String>, val complete: Boolean)

    /**
     * Walks the same folders as the library index (download folder and per-console folders, as deep
     * as it goes) for the names on disk and the app's own `.dogmatix-` staging files, which every
     * other listing hides. Bounded in folders and files; never enters the trash.
     */
    private suspend fun walk(onFolder: (Int) -> Unit): Walk {
        val root = settingsRepository.downloadDirectory.first()
        val custom = settingsRepository.consoleDownloadDirectories.first()
        val consoleIds = consoleDao.getAllConsoles().first().map { it.id }
        val hidden = ArrayList<RepairFile>()
        val names = HashSet<String>()
        val visited = HashSet<String>()
        var folders = 0
        var complete = true

        suspend fun visit(dir: DiskDir, display: String, consoleId: String?, depth: Int, skip: Set<String>) {
            currentCoroutineContext().ensureActive()
            if (!visited.add(DiskScanner.canonicalKey(dir))) return
            if (++folders > MAX_FOLDERS || names.size > MAX_NAMES) { complete = false; return }
            if (folders % 20 == 0) onFolder(folders)
            val entries = DiskScanner.listOrNull(context, dir, strict = false)
            if (entries == null) { complete = false; return }
            val dirUri = DiskScanner.uriOf(dir).toString()
            for (entry in entries) {
                if (entry.isDirectory) {
                    if (entry.name.startsWith(".dogmatix-") || depth >= MAX_DEPTH) continue
                    val child = DiskScanner.dirOf(dir, entry)
                    if (DiskScanner.canonicalKey(child) in skip) continue
                    val childConsole = consoleId ?: if (depth == 0) consoleIds.firstOrNull { ConsoleFolderAliases.matches(it, entry.name) } else null
                    visit(child, "$display/${entry.name}", childConsole, depth + 1, skip)
                } else if (entry.name.startsWith(".dogmatix-")) {
                    if (LibraryRepair.isOwnStaging(entry.name) && hidden.size < MAX_HIDDEN)
                        hidden += RepairFile(entry.name, entry.size, entry.uri.toString(), dirUri, display, consoleId, entry.lastModified, DiskScanner.canonicalKey(dir))
                } else names += entry.name.lowercase()
            }
        }

        val customDirs = custom.mapNotNull { (id, uri) -> DiskScanner.rootOf(uri)?.let { Triple(id, uri, it) } }
        val customKeys = customDirs.map { DiskScanner.canonicalKey(it.third) }.toSet()
        for ((id, uri, dir) in customDirs) visit(dir, FileParsingUtils.toUserReadablePath(uri), id, 0, emptySet())
        DiskScanner.rootOf(root)?.let { visit(it, FileParsingUtils.toUserReadablePath(root), null, 0, customKeys) }
        return Walk(hidden, names, complete)
    }

    private companion object {
        val SHEETS = setOf("cue", "gdi", "m3u")
        val ACTIVE = setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.COPYING, DownloadStatus.UNZIPPING)
        /** The same limits as the library index's own walk. */
        const val MAX_FOLDERS = 10_000
        const val MAX_DEPTH = 65
        const val MAX_NAMES = 300_000
        const val MAX_HIDDEN = 2_000
        const val MAX_SHEETS = 5_000
        /** Names per query (each also looked up URL-encoded; stays under SQLite's 999 variables). */
        const val LOOKUP_CHUNK = 400
    }
}
