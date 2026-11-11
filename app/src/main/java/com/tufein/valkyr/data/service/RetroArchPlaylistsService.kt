package com.tufein.valkyr.data.service

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.tufein.valkyr.data.local.RetroArchPlaylistsSettings
import com.tufein.valkyr.data.local.dao.DownloadableFileDao
import com.tufein.valkyr.util.ActionCount
import com.tufein.valkyr.util.ActionKind
import com.tufein.valkyr.util.ActionReason
import com.tufein.valkyr.util.BoundedStreams
import com.tufein.valkyr.util.DiskEntry
import com.tufein.valkyr.util.DiskFile
import com.tufein.valkyr.util.DiskScanner
import com.tufein.valkyr.util.FileParsingUtils
import com.tufein.valkyr.util.LibraryRestrictions
import com.tufein.valkyr.util.ApkAssets
import com.tufein.valkyr.util.EmulatorCatalog
import com.tufein.valkyr.util.RetroArchPlaylists
import com.tufein.valkyr.util.StorageHelper
import com.tufein.valkyr.util.VerifiedCopy
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RetroArchPlaylists"

/** What a run would do: the folder, every system with games on the device and its plan. */
data class PlaylistsPreview(
    val folderSet: Boolean,
    /** The folder is set, still reachable and writable. */
    val writable: Boolean,
    /** The folder is writable, but its listing could not be trusted to be complete: nothing is planned. */
    val listingFailed: Boolean,
    /** Readable path of the picked folder (empty when none). */
    val folderDisplay: String,
    /** The systems with games on the device, in name order. */
    val systems: List<RetroArchPlaylists.SystemGames>,
    /** One plan per system of [systems]; null without a usable folder. */
    val plans: List<RetroArchPlaylists.Plan>?,
    /** Games found on the device for this profile, with or without a playlist system. */
    val games: Int,
    /** Games of consoles that have no libretro playlist name, and those consoles. */
    val noSystem: Int,
    val noSystemConsoles: List<String>,
    /** The active profile hides some consoles or tags; their games are not counted. */
    val profileLimited: Boolean
)

/** A run's progress and, when it is over, its outcome. */
data class PlaylistsRunState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    /** New playlist files. */
    val created: Int = 0,
    /** Playlists that were there and got entries (their previous version is in the trash). */
    val updated: Int = 0,
    /** Entries added in all. */
    val entries: Int = 0,
    /** Playlists that changed after the preview into one that is left alone, or changed while being written. */
    val leftAlone: Int = 0,
    val failed: Int = 0,
    val finished: Boolean = false,
    val problem: Problem? = null
) {
    enum class Problem { NO_FOLDER, NOT_WRITABLE, NOT_READABLE, NOTHING_ON_DEVICE, NOTHING_CHOSEN, FAILED }
}

/**
 * Tools -> RetroArch playlists (3.5.0). Writes one RetroArch playlist per libretro system into
 * RetroArch's playlists folder the user picked, listing the games on the device
 * ([RetroArchPlaylists] holds the rules and the format). A playlist that is already there is only
 * added to; before it is changed its current version goes to the trash ([TrashService.move],
 * logged with [ActionReason.RETROARCH_PLAYLIST], so Restore in the action history or in Trash and
 * recovery brings it back), and the new version is published with [StorageHelper.writeBytesSafely].
 * Every playlist is read again right before it is written: one that changed meanwhile is planned
 * anew, and one that changed during the backup is put back untouched. Profiles: hidden consoles
 * and games with hidden tags are left out.
 */
@Singleton
class RetroArchPlaylistsService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: RetroArchPlaylistsSettings,
    private val libraryScanService: LibraryScanService,
    private val fileDao: DownloadableFileDao,
    private val profiles: ProfileService,
    private val trash: TrashService,
    private val journal: OperationHistoryService,
    private val actionLog: ActionLogService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(PlaylistsRunState())
    val state: StateFlow<PlaylistsRunState> = _state.asStateFlow()
    private var job: Job? = null
    /** Held while playlists are written, by a run or by the automatic refresh, so the two never overlap. */
    private val writing = Mutex()

    /** The plan as things are now: reads the library and the picked folder, writes nothing. */
    suspend fun preview(): PlaylistsPreview = withContext(Dispatchers.IO) { prepare().preview }

    fun start() {
        if (_state.value.running) return
        _state.value = PlaylistsRunState(running = true)
        job = scope.launch { run() }
    }

    /** Stops after the playlist being written (a playlist is never left half replaced). */
    fun cancel() { job?.cancel() }

    /** The last run's outcome is dismissed. */
    fun clear() { if (!_state.value.running) _state.value = PlaylistsRunState() }

    // ---- Planning ------------------------------------------------------------------------------

    private class Prepared(val preview: PlaylistsPreview, val root: DocumentFile?, val folder: String)

    private suspend fun prepare(): Prepared {
        val s = settings.values.first()
        val root = s.folder.takeIf { it.isNotBlank() }?.let { StorageHelper.getDocumentFile(context, it) }
            ?.takeIf { runCatching { it.isDirectory && it.canWrite() }.getOrDefault(false) }
        val display = s.folder.takeIf { it.isNotBlank() }?.let { FileParsingUtils.toUserReadablePath(it) }.orEmpty()
        val restrictions = profiles.current()
        val files = libraryScanService.scan().files
        currentCoroutineContext().ensureActive()
        val found = RetroArchPlaylists.gamesOf(files, restrictions.hiddenConsoles, m3uLines(files, restrictions), primaryRoot())
        val systems = withoutHiddenTags(found.systems, restrictions)
        currentCoroutineContext().ensureActive()
        val listing = root?.let { DiskScanner.rootOf(s.folder) }?.let { DiskScanner.listOrNull(context, it, strict = true) }
        val plans = listing?.let { children -> systems.map { RetroArchPlaylists.plan(it, readExisting(match(children, it.system)).read, coreFor(it, s.cores)) } }
        val games = systems.sumOf { it.games.size + it.discSetsWithoutM3u } + found.noSystem
        return Prepared(
            PlaylistsPreview(
                folderSet = s.folder.isNotBlank(),
                writable = root != null,
                listingFailed = root != null && listing == null,
                folderDisplay = display,
                systems = systems,
                plans = plans,
                games = games,
                noSystem = found.noSystem,
                noSystemConsoles = found.noSystemConsoles,
                profileLimited = restrictions.active
            ),
            root,
            s.folder
        )
    }

    /** 6.5: the core chosen for [system] as RetroArch's association; null when none is chosen or RetroArch is not installed. */
    private fun coreFor(system: RetroArchPlaylists.SystemGames, chosen: Map<String, String>): RetroArchPlaylists.DefaultCore? {
        val id = chosen[system.system]?.takeIf { it in RetroArchPlaylists.coresOf(system.consoleIds) } ?: return null
        val variant = EmulatorCatalog.byId("retroarch")?.variants?.firstOrNull { ApkAssets.isInstalled(context, it.packageName) } ?: return null
        return RetroArchPlaylists.defaultCore(id, variant.packageName)
    }

    /** Shared storage as RetroArch sees it on this device (`/storage/emulated/0` for the usual user). */
    private fun primaryRoot(): String =
        runCatching { @Suppress("DEPRECATION") Environment.getExternalStorageDirectory()?.absolutePath }.getOrNull()
            ?.takeIf { it.startsWith("/") } ?: RetroArchPlaylists.DEFAULT_PRIMARY_ROOT

    /** The lines of the `.m3u` files of visible consoles, by file id; an unreadable one is simply missing. */
    private suspend fun m3uLines(files: List<DiskFile>, restrictions: LibraryRestrictions): Map<String, List<String>> {
        val out = HashMap<String, List<String>>()
        val playlists = files.filter {
            it.name.endsWith(".m3u", ignoreCase = true) && it.size in 1..RetroArchPlaylists.MAX_M3U_BYTES &&
                it.consoleId != null && it.consoleId !in restrictions.hiddenConsoles
        }
        for (file in playlists.take(MAX_M3U_FILES)) {
            currentCoroutineContext().ensureActive()
            val bytes = runCatching {
                context.contentResolver.openInputStream(Uri.parse(file.uri))?.use { BoundedStreams.read(it, RetroArchPlaylists.MAX_M3U_BYTES + 1) }
            }.getOrNull() ?: continue
            RetroArchPlaylists.m3uLines(bytes)?.let { out[file.fileId] = it }
        }
        return out
    }

    /** The playlist file of [system] in a listing of the folder: its exact name first, else the same name in other case. */
    private fun match(children: List<DiskEntry>, system: String): DiskEntry? {
        val name = RetroArchPlaylists.fileName(system)
        return children.firstOrNull { it.name == name } ?: children.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    private class Existing(val entry: DiskEntry?, val read: RetroArchPlaylists.Read, val bytes: ByteArray?)

    /** What [entry] holds as a playlist; a file that cannot be read counts as unreadable (left alone). */
    private fun readExisting(entry: DiskEntry?): Existing {
        if (entry == null) return Existing(null, RetroArchPlaylists.Read.New, null)
        if (entry.isDirectory) return Existing(entry, RetroArchPlaylists.Read.Kept(RetroArchPlaylists.LeftAlone.FOLDER), null)
        if (entry.size > RetroArchPlaylists.MAX_PLAYLIST_BYTES) return Existing(entry, RetroArchPlaylists.Read.Kept(RetroArchPlaylists.LeftAlone.TOO_BIG), null)
        val bytes = try {
            context.contentResolver.openInputStream(entry.uri)?.use { BoundedStreams.read(it, RetroArchPlaylists.MAX_PLAYLIST_BYTES + 1) }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read a playlist: ${e.message}")
            null
        } ?: return Existing(entry, RetroArchPlaylists.Read.Kept(RetroArchPlaylists.LeftAlone.UNREADABLE), null)
        return Existing(entry, RetroArchPlaylists.read(bytes), bytes)
    }

    /** Games whose library row carries a tag the active profile hides are left out. */
    private suspend fun withoutHiddenTags(systems: List<RetroArchPlaylists.SystemGames>, restrictions: LibraryRestrictions): List<RetroArchPlaylists.SystemGames> {
        val games = systems.flatMap { it.games }
        if (restrictions.hiddenTags.isEmpty() || games.isEmpty()) return systems
        val wanted = games.groupBy({ it.consoleId }, { it.fileName.lowercase() }).mapValues { it.value.toSet() }
        val rowKeys = HashMap<Long, String>()
        for (chunk in games.map { it.fileName }.distinct().chunked(LOOKUP_CHUNK)) {
            currentCoroutineContext().ensureActive()
            // Listings often keep the URL spelling of a name (`Game%20(USA).zip`).
            val query = (chunk + chunk.map { it.replace(" ", "%20") }).distinct()
            for (row in fileDao.filesByFileNames(query)) {
                val stored = runCatching { FileParsingUtils.storageFileName(row.fileName) }.getOrNull()?.lowercase() ?: continue
                if (stored in wanted[row.consoleId].orEmpty()) rowKeys[row.id] = "${row.consoleId}|$stored"
            }
        }
        val hidden = HashSet<String>()
        for (chunk in rowKeys.keys.toList().chunked(TAG_CHUNK)) {
            for (t in fileDao.tagsOfFiles(chunk)) {
                if (restrictions.hiddenTags.any { it.equals(t.tag, ignoreCase = true) }) rowKeys[t.fileId]?.let(hidden::add)
            }
        }
        if (hidden.isEmpty()) return systems
        return systems.map { s -> s.copy(games = s.games.filterNot { "${it.consoleId}|${it.fileName.lowercase()}" in hidden }) }
            .filter { it.games.isNotEmpty() || it.discSetsWithoutM3u > 0 }
    }

    // ---- The run -------------------------------------------------------------------------------

    /**
     * 6.2, "Keep RetroArch playlists up to date": adds the new games to the playlists that are there
     * already, with the same rules as a run (the old file goes to the trash first, a strict
     * listing, a playlist that changed meanwhile is put back, nothing is removed). Never creates a
     * playlist. Does nothing when the setting is off, a run is going, or the folder is not usable;
     * every problem is ignored quietly (it is tried again after the next rescan or download).
     * Returns the number of playlists changed.
     */
    suspend fun refreshExisting(): Int = withContext(Dispatchers.IO) {
        if (!settings.values.first().auto || _state.value.running) return@withContext 0
        if (!writing.tryLock()) return@withContext 0
        try {
            val prepared = prepare()
            val root = prepared.root ?: return@withContext 0
            val excluded = settings.values.first().excluded
            val targets = RetroArchPlaylists.autoTargets(prepared.preview.plans, excluded)
            val games = prepared.preview.systems.associateBy { it.system }
            var changed = 0
            for (plan in targets) {
                currentCoroutineContext().ensureActive()
                val system = games[plan.system] ?: continue
                val outcome = withContext(NonCancellable) {
                    try {
                        writeOne(root, prepared.folder, system, createNew = false)
                    } catch (e: Exception) {
                        Log.w(TAG, "Automatic refresh of ${plan.fileName} failed: ${e.message}")
                        Outcome(failed = true)
                    }
                }
                if (outcome.updated) changed++
            }
            changed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Automatic refresh failed: ${e.javaClass.simpleName}")
            0
        } finally {
            writing.unlock()
        }
    }

    private suspend fun run() = writing.withLock { runLocked() }

    private suspend fun runLocked() {
        try {
            val prepared = prepare()
            val preview = prepared.preview
            if (!preview.folderSet) return finish(PlaylistsRunState.Problem.NO_FOLDER)
            val root = prepared.root ?: return finish(PlaylistsRunState.Problem.NOT_WRITABLE)
            val plans = preview.plans ?: return finish(PlaylistsRunState.Problem.NOT_READABLE)
            if (preview.games == 0) return finish(PlaylistsRunState.Problem.NOTHING_ON_DEVICE)
            val excluded = settings.values.first().excluded
            val chosen = plans.filter { it.system !in excluded }
            if (chosen.isEmpty()) return finish(PlaylistsRunState.Problem.NOTHING_CHOSEN)
            val todo = chosen.filter { it.writes }
            val games = preview.systems.associateBy { it.system }
            _state.update { it.copy(total = todo.size) }
            for (plan in todo) {
                currentCoroutineContext().ensureActive()
                val system = games[plan.system] ?: continue
                // Once a playlist is started it is finished (or put back): never left half replaced.
                val outcome = withContext(NonCancellable) {
                    try {
                        writeOne(root, prepared.folder, system)
                    } catch (e: Exception) {
                        Log.w(TAG, "Playlist ${plan.fileName} failed: ${e.message}")
                        Outcome(failed = true)
                    }
                }
                _state.update {
                    it.copy(
                        done = it.done + 1,
                        created = it.created + if (outcome.created) 1 else 0,
                        updated = it.updated + if (outcome.updated) 1 else 0,
                        entries = it.entries + outcome.entries,
                        leftAlone = it.leftAlone + if (outcome.leftAlone) 1 else 0,
                        failed = it.failed + if (outcome.failed) 1 else 0
                    )
                }
            }
            _state.update { it.copy(running = false, finished = true) }
        } catch (e: CancellationException) {
            // Stopped by the user: what was written stays, the counts so far are shown.
            _state.update { it.copy(running = false) }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Playlist run failed", e)
            finish(PlaylistsRunState.Problem.FAILED)
        }
    }

    private fun finish(problem: PlaylistsRunState.Problem) {
        _state.value = PlaylistsRunState(problem = problem)
    }

    private data class Outcome(
        val created: Boolean = false,
        val updated: Boolean = false,
        val entries: Int = 0,
        val leftAlone: Boolean = false,
        val failed: Boolean = false
    )

    /**
     * Writes the playlist of [system] as the folder holds it right now: a fresh strict listing, the
     * file read and planned again. A playlist that is there goes to the trash first (that copy is
     * the way back) and must still be the one that was read; otherwise it is put back as it is.
     */
    private suspend fun writeOne(root: DocumentFile, folder: String, system: RetroArchPlaylists.SystemGames, createNew: Boolean = true): Outcome {
        val dir = DiskScanner.rootOf(folder) ?: return Outcome(failed = true)
        val listing = DiskScanner.listOrNull(context, dir, strict = true) ?: return Outcome(failed = true)
        val existing = readExisting(match(listing, system.system))
        // The automatic refresh only adds to a playlist that is there: one that vanished is not made anew.
        if (existing.entry == null && !createNew) return Outcome()
        val core = if (createNew) coreFor(system, settings.values.first().cores) else null
        val plan = RetroArchPlaylists.plan(system, existing.read, core)
        if (plan.state == RetroArchPlaylists.State.LEFT_ALONE) return Outcome(leftAlone = true)
        if (!plan.writes) return Outcome()
        val bytes = RetroArchPlaylists.write(existing.read, system.system, plan.add, core)
        val entry = existing.entry
        if (entry == null) {
            StorageHelper.writeBytesSafely(context, root, "", plan.fileName, bytes)
            record(plan.fileName, plan.add.size)
            return Outcome(created = true, entries = plan.add.size)
        }
        if (!replaceFile(root, folder, entry, existing.bytes, bytes)) return Outcome(leftAlone = true)
        record(entry.name, plan.add.size)
        return Outcome(updated = true, entries = plan.add.size)
    }

    /**
     * Replaces [entry] (read as [original]) by [bytes]: the new content is staged first, the old file
     * goes to the trash (the way back) and must still be the one that was read, else it is put back.
     * False when the file changed meanwhile and was left as it was.
     */
    private suspend fun replaceFile(root: DocumentFile, folder: String, entry: DiskEntry, original: ByteArray?, bytes: ByteArray): Boolean {
        // The playlist keeps its own spelling ("nintendo - game boy.lpl").
        val name = entry.name
        // The new content is on the storage before the old file moves, so a failed write (full card,
        // unplugged stick) never leaves the folder without its playlist.
        val staged = StorageHelper.stageBytes(context, root, bytes)
        val stagedName = staged.name
        try {
            val before = journal.entries.value.map { it.id }.toSet()
            trash.move(
                listOf(RemovalFile(entry.uri.toString(), folder, name, entry.size, name)),
                title = name, fileName = name, reason = ActionReason.RETROARCH_PLAYLIST
            )
            val operation = journal.entries.value.firstOrNull { op ->
                op.id !in before && op.kind == "trash" && op.files.any { it.source == entry.uri.toString() }
            } ?: error("The previous version was not kept")
            // Changed between reading and the backup (RetroArch saved it meanwhile): put it back as it is.
            val read = original ?: ByteArray(0)
            if (operation.files.single().hash != VerifiedCopy.hash(read.inputStream())) {
                trash.restore(operation.id)
                return false
            }
            try {
                StorageHelper.publishStagedFile(context, root, staged, name, VerifiedCopy.hash(bytes.inputStream()))
            } catch (e: Exception) {
                // Nothing new was published: the previous version goes back where it was.
                runCatching { trash.restore(operation.id) }.onFailure { Log.w(TAG, "Could not put a playlist back: ${it.message}") }
                throw e
            }
        } finally {
            if (staged.name == stagedName) runCatching { staged.delete() }
        }
        return true
    }

    /** 6.6: what moving [from] to [to] in the playlists would change (nothing is written). */
    data class MovePreview(val playlists: Int, val entries: Int, val folderOk: Boolean)

    private data class MoveTarget(val entry: DiskEntry, val existing: Existing, val bytes: ByteArray, val entries: Int)

    private suspend fun moveTargets(from: String, to: String): Pair<DocumentFile?, List<MoveTarget>>? {
        val folder = settings.values.first().folder.takeIf { it.isNotBlank() } ?: return null
        val root = StorageHelper.getDocumentFile(context, folder)?.takeIf { runCatching { it.isDirectory && it.canWrite() }.getOrDefault(false) } ?: return null
        val dir = DiskScanner.rootOf(folder) ?: return null
        val listing = DiskScanner.listOrNull(context, dir, strict = true) ?: return null
        val targets = listing.filter { !it.isDirectory && it.name.endsWith(".${RetroArchPlaylists.EXTENSION}", ignoreCase = true) }.mapNotNull { entry ->
            val existing = readExisting(entry)
            RetroArchPlaylists.movePaths(existing.read, from, to)?.let { (bytes, n) -> MoveTarget(entry, existing, bytes, n) }
        }
        return root to targets
    }

    suspend fun previewMove(from: String, to: String): MovePreview = withContext(Dispatchers.IO) {
        val ok = RetroArchPlaylists.cleanFolder(from) != null && RetroArchPlaylists.cleanFolder(to) != null && RetroArchPlaylists.cleanFolder(from) != RetroArchPlaylists.cleanFolder(to)
        if (!ok) return@withContext MovePreview(0, 0, false)
        val t = moveTargets(from, to)?.second.orEmpty()
        MovePreview(t.size, t.sumOf { it.entries }, true)
    }

    /** Rewrites the paths in every playlist that has some under [from]; each old file goes to Trash and recovery first. Returns the entries changed. */
    suspend fun applyMove(from: String, to: String): Int = withContext(Dispatchers.IO) {
        writing.withLock {
            val (root, targets) = moveTargets(from, to) ?: return@withLock 0
            val folder = settings.values.first().folder
            var total = 0
            for (t in targets) {
                currentCoroutineContext().ensureActive()
                if (root != null && replaceFile(root, folder, t.entry, t.existing.bytes, t.bytes)) { total += t.entries; record(t.entry.name, t.entries) }
            }
            total
        }
    }

    /** One line in the action history per playlist written. */
    private fun record(fileName: String, added: Int) = actionLog.record(
        ActionKind.OTHER, title = fileName, fileName = fileName, reason = ActionReason.RETROARCH_PLAYLIST,
        counts = mapOf(ActionCount.ADDED to added)
    )

    private companion object {
        const val LOOKUP_CHUNK = 400
        const val TAG_CHUNK = 500
        /** More `.m3u` files than this are not read; the rest claim their discs by name. */
        const val MAX_M3U_FILES = 3000
    }
}
