package com.tufein.valkyr.data.service

import android.content.Context
import androidx.core.net.toUri
import com.tufein.valkyr.data.local.dao.ConsoleDao
import com.tufein.valkyr.data.local.dao.DownloadableFileDao
import com.tufein.valkyr.data.model.ResolvedDownloadPath
import com.tufein.valkyr.util.ActionReason
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.DuplicateFinder
import com.tufein.valkyr.util.LibraryKeys
import com.tufein.valkyr.util.SourcesJson
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.util.DiskDir
import com.tufein.valkyr.util.DiskEntry
import com.tufein.valkyr.util.DiskFile
import com.tufein.valkyr.util.DiskScanner
import com.tufein.valkyr.util.FileParsingUtils
import com.tufein.valkyr.util.GameEntry
import com.tufein.valkyr.util.ConsoleFolderAliases
import com.tufein.valkyr.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** What the download folders hold right now, attributed to consoles where possible. */
data class DiskSnapshot(
    val files: List<DiskFile>,
    /** Top-level folders of the download directory that match no console. */
    val unmatchedFolders: List<String>,
    /** Readable path of the download directory, empty when none is set. */
    val rootDisplay: String,
    val freeBytes: Long?,
    val scannedAt: Long = System.currentTimeMillis()
)

/** One console in the library overview: what its sources indexed against what is on disk. */
data class ConsoleOverview(
    val id: String,
    val name: String,
    val shortName: String,
    val sources: Int,
    val enabledSources: Int,
    /** Games the last source scan indexed for this console. */
    val indexed: Int,
    /** Indexed games that are already on disk (the "owned" mark in the library). */
    val owned: Int,
    /** Games on disk in this console's folders, whether the library knows them or not. */
    val onDisk: Int,
    val onDiskBytes: Long,
    val path: ResolvedDownloadPath?,
    /** Epoch millis of this console's last source scan; null if not recorded yet. */
    val scannedAt: Long?
) {
    enum class Status { OK, NO_SOURCES, NOTHING_FOUND, NOT_SCANNED }

    val status: Status get() = when {
        enabledSources == 0 -> Status.NO_SOURCES
        indexed == 0 && scannedAt == null -> Status.NOT_SCANNED
        indexed == 0 -> Status.NOTHING_FOUND
        else -> Status.OK
    }
}

data class LibraryOverview(
    val consoles: List<ConsoleOverview>,
    val disk: DiskSnapshot,
    /** Games on disk outside every known console folder (loose in the root or unknown folders). */
    val unassignedGames: Int
) {
    val indexed: Int get() = consoles.sumOf { it.indexed }
    val owned: Int get() = consoles.sumOf { it.owned }
    val onDisk: Int get() = consoles.sumOf { it.onDisk } + unassignedGames
    val onDiskBytes: Long get() = disk.files.filter { DuplicateFinder.isGameFile(it) }.sumOf { it.size }
}

/**
 * Walks the download directory and the per-console directories (the same places and depth as
 * [LibraryIndexService]) and returns every file with its size and console. Feeds the duplicate
 * finder and the library overview; deleting goes through here too so the owned-index is refreshed.
 */
@Singleton
class LibraryScanService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val consoleDao: ConsoleDao,
    private val downloadableFileDao: DownloadableFileDao,
    private val libraryIndexService: LibraryIndexService,
    private val pathResolver: ConsoleDownloadPathResolver,
    private val trash: TrashService
) {

    /** Per-console numbers for the library overview; walks the disk once. */
    suspend fun overview(): LibraryOverview = withContext(Dispatchers.IO) {
        val disk = scan()
        val consoles = consoleDao.getAllConsoles().first()
        val counts = downloadableFileDao.countsByConsole().associate { it.consoleId to it.count }
        val scannedAt = settingsRepository.consoleScannedAt.first()
        val paths = runCatching { pathResolver.resolveAll(settingsRepository, consoles.map { it.id }) }.getOrDefault(emptyMap())
        val ownedKeys = libraryIndexService.ownedKeys.value
        val entries = DuplicateFinder.entries(disk.files)
        val entriesByConsole = entries.groupBy { it.consoleId }

        val rows = consoles.map { console ->
            val urls = SourcesJson.parseUrlEntries(console.urls)
            val indexed = counts[console.id] ?: 0
            val owned = if (indexed == 0 || ownedKeys.isEmpty()) 0 else
                downloadableFileDao.fileNamesFor(console.id).count { LibraryKeys.isOwned(console.id, it, ownedKeys) }
            val onDisk = entriesByConsole[console.id].orEmpty()
            ConsoleOverview(
                id = console.id,
                name = console.name,
                shortName = ConsoleFormatter.getConsoleShortName(console.id),
                sources = urls.size,
                enabledSources = urls.count { it.enabled },
                indexed = indexed,
                owned = owned,
                onDisk = onDisk.size,
                onDiskBytes = onDisk.sumOf { it.size },
                path = paths[console.id],
                scannedAt = scannedAt[console.id]
            )
        }.sortedBy { it.name.lowercase() }
        LibraryOverview(rows, disk, entriesByConsole[null].orEmpty().size)
    }

    /** Changes whenever the index sees different files; caches compare it to know their copy is stale. */
    fun libraryStamp(): Any = libraryIndexService.ownedKeys.value

    suspend fun scan(): DiskSnapshot = withContext(Dispatchers.IO) {
        val root = settingsRepository.downloadDirectory.first()
        val custom = settingsRepository.consoleDownloadDirectories.first()
        val consoleIds = consoleDao.getAllConsoles().first().map { it.id }
        val rootDisplay = FileParsingUtils.toUserReadablePath(root)
        val rootDir = DiskScanner.rootOf(root)
        val rootKey = rootDir?.let { DiskScanner.canonicalKey(it) }
        val walk = Walk()

        // Per-console directories first: they say explicitly which console their files belong
        // to. They may also sit inside the download directory (or be the same folder reached
        // through another provider), so the root walk below skips them. A per-console directory
        // that is the download directory itself only claims its loose files (its sub-folders are
        // still matched by name), and one that contains the download directory never walks into it.
        val customDirs = custom.mapNotNull { (consoleId, uri) -> DiskScanner.rootOf(uri)?.let { Triple(consoleId, uri, it) } }
        val rootConsole = customDirs.firstOrNull { DiskScanner.canonicalKey(it.third) == rootKey }?.first
        walk.customKeys += customDirs.map { DiskScanner.canonicalKey(it.third) }.filter { it != rootKey }
        rootKey?.let { walk.walkedDirs += it }
        customDirs.forEach { (consoleId, uri, dir) ->
            // The same folder picked for two consoles is listed once, for the first of them.
            if (walk.walkedDirs.add(DiskScanner.canonicalKey(dir))) {
                walk.rootId = "custom:" + DiskScanner.canonicalKey(dir)
                walk.dir(dir, consoleId, consoleId, FileParsingUtils.toUserReadablePath(uri), depth = 0, top = 0)
            }
        }
        walk.rootId = "root:" + rootKey

        val unmatched = ArrayList<String>()
        if (rootDir != null && rootKey != null) {
            for (child in DiskScanner.list(context, rootDir)) {
                if (child.name.startsWith(".")) continue
                if (child.isDirectory) {
                    val childDir = DiskScanner.dirOf(rootDir, child)
                    if (DiskScanner.canonicalKey(childDir) in walk.customKeys) continue
                    val consoleId = consoleIds.firstOrNull { ConsoleFolderAliases.matches(it, child.name) }
                    if (consoleId == null) unmatched += child.name
                    val scope = consoleId ?: "folder:${child.name}"
                    walk.dir(childDir, scope, consoleId, "$rootDisplay/${child.name}", depth = 1, top = 1)
                } else {
                    walk.file(rootDir, rootKey, child, rootConsole ?: "", rootConsole, rootDisplay, level = 0)
                }
            }
        }

        val freeBytes = (listOf(root) + custom.values).firstOrNull { it.isNotBlank() }
            ?.let { StorageHelper.getFreeBytes(context, it) }
        DiskSnapshot(walk.files, unmatched.sortedBy { it.lowercase() }, rootDisplay, freeBytes)
    }

    /**
     * Deletes every file of [entry] (and the folder of a per-game folder once it is empty);
     * returns how many files were removed. Runs to the end even when the caller is cancelled
     * (leaving the screen), so a game is never left half deleted. The owned-index refresh is
     * only requested: it walks the whole library and must not hold up the caller.
     */
    suspend fun delete(entry: GameEntry, reason: String = ActionReason.BY_USER): Int = withContext(NonCancellable + Dispatchers.IO) {
        val removed = removeFiles(entry, reason)
        if (removed > 0) libraryIndexService.requestRefresh()
        removed
    }

    /**
     * [delete] for several games: the same removal, one owned-index refresh at the end instead of
     * one per game. Returns how many files each entry lost, in the order of [entries].
     */
    suspend fun deleteAll(entries: List<GameEntry>, reason: String = ActionReason.BY_USER): List<Int> = withContext(NonCancellable + Dispatchers.IO) {
        val removed = entries.map { removeFiles(it, reason) }
        if (removed.any { it > 0 }) libraryIndexService.requestRefresh()
        removed
    }

    /** [reason]: an [ActionReason] code for the action history. */
    private suspend fun removeFiles(entry: GameEntry, reason: String): Int {
        val removed = trash.move(
            entry.files.map { RemovalFile(it.uri, it.dirUri, it.name, it.size, "${it.folder}/${it.name}") }, entry.baseName,
            consoleId = entry.consoleId, reason = reason
        )
        if (entry.isFolderGame && removed == entry.files.size) {
            entry.files.first().dirUri.takeIf { it.isNotEmpty() }?.toUri()?.let { dirUri ->
                // Providers delete folders recursively: only when a complete listing says it is empty
                // (saves, covers and other files the scan ignores keep the folder in place).
                val dir = DiskScanner.dirOf(dirUri)
                if (dir != null && DiskScanner.listOrNull(context, dir)?.isEmpty() == true) DiskScanner.delete(context, dirUri)
            }
        }
        return removed
    }

    /** State of one disk walk: every folder and file is emitted once, whatever path led to it. */
    private inner class Walk {
        val files = ArrayList<DiskFile>()
        /** Which walk is running (download folder or one per-console folder); stored on each file. */
        var rootId = ""
        val customKeys = HashSet<String>()
        val walkedDirs = HashSet<String>()
        private val seenFiles = HashSet<String>()

        /** Lists [dir]; files deeper than [top] sit in a sub-folder of their console folder. */
        fun dir(dir: DiskDir, scope: String, consoleId: String?, display: String, depth: Int, top: Int) {
            val dirKey = DiskScanner.canonicalKey(dir)
            for (child in DiskScanner.list(context, dir)) {
                if (child.name.startsWith(".")) continue
                if (child.isDirectory) {
                    if (depth >= 2) continue
                    val childDir = DiskScanner.dirOf(dir, child)
                    val childKey = DiskScanner.canonicalKey(childDir)
                    // A nested per-console directory is walked as its own console.
                    if (childKey in customKeys || !walkedDirs.add(childKey)) continue
                    dir(childDir, scope, consoleId, "$display/${child.name}", depth + 1, top)
                } else {
                    file(dir, dirKey, child, scope, consoleId, display, level = (depth - top).coerceAtLeast(0))
                }
            }
        }

        fun file(dir: DiskDir, dirKey: String, child: DiskEntry, scope: String, consoleId: String?, display: String, level: Int) {
            val inSubfolder = level > 0
            val fileKey = DiskScanner.canonicalKey(dir.treeUri, child.documentId)
            if (!seenFiles.add(fileKey)) return
            files += DiskFile(
                scope = scope,
                consoleId = consoleId,
                folder = display,
                name = child.name,
                size = child.size,
                uri = child.uri.toString(),
                dirId = dirKey,
                fileId = fileKey,
                inSubfolder = inSubfolder,
                level = level,
                rootId = rootId,
                dirUri = DiskScanner.uriOf(dir).toString(),
                lastModified = child.lastModified
            )
        }
    }
}
