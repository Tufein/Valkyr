package com.tufein.valkyr.data.service

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.tufein.valkyr.data.local.FrontendMetadataSettings
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.util.ConsoleFolderAliases
import com.tufein.valkyr.util.DuplicateFinder
import com.tufein.valkyr.util.FrontendMetadata
import com.tufein.valkyr.util.FrontendMetadata.Field
import com.tufein.valkyr.util.FrontendMetadata.GameMeta
import com.tufein.valkyr.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
import com.tufein.valkyr.data.model.GameDetails

private const val TAG = "FrontendMetadata"

/** What one frontend got in a run. Files, not games: a gamelist or metadata file holds many games. */
data class TargetCounts(
    /** Files written. */
    val files: Int = 0,
    /** Games that had no entry and got one. */
    val added: Int = 0,
    /** Games whose entry had empty fields that are filled now. */
    val filled: Int = 0,
    /** Files that exist but cannot be merged with certainty; they were left alone. */
    val unreadable: Int = 0,
    /** Files that could not be written. */
    val failed: Int = 0
) {
    operator fun plus(o: TargetCounts) = TargetCounts(files + o.files, added + o.added, filled + o.filled, unreadable + o.unreadable, failed + o.failed)
    val written: Int get() = added + filled
}

/** The outcome for one console, or for the whole run when summed. */
data class MetaCounts(
    /** Games looked at. */
    val games: Int = 0,
    /** Games whose entries already hold everything the sources can give. */
    val complete: Int = 0,
    /** Games no source knew anything about. */
    val noData: Int = 0,
    /** Games left for another run: the lookups of one run are limited. */
    val deferred: Int = 0,
    /** Games in folders ES-DE cannot match to a system (nothing written for them there). */
    val outside: Int = 0,
    val esde: TargetCounts = TargetCounts(),
    val pegasus: TargetCounts = TargetCounts()
) {
    operator fun plus(o: MetaCounts) = MetaCounts(
        games + o.games, complete + o.complete, noData + o.noData, deferred + o.deferred, outside + o.outside, esde + o.esde, pegasus + o.pegasus
    )
    /** Games that got something written, in the frontend that got most. */
    val updated: Int get() = maxOf(esde.written, pegasus.written)
    val filesWritten: Int get() = esde.files + pegasus.files
}

/** A run's progress and, when it is over, its outcome. */
data class MetaRunState(
    val running: Boolean = false,
    /** The console being worked on right now. */
    val consoleId: String? = null,
    val done: Int = 0,
    val total: Int = 0,
    /** The console the run was asked for; null = every console. */
    val scope: String? = null,
    /** Counts per console id, filled while the run goes on. */
    val results: Map<String, MetaCounts> = emptyMap(),
    val finished: Boolean = false,
    val problem: Problem? = null
) {
    enum class Problem { NO_TARGET, NOTHING_ON_DEVICE, FAILED }
    /** The counts of all consoles so far, summed. */
    val sum: MetaCounts get() = results.values.fold(MetaCounts()) { a, b -> a + b }
}

/** Which frontends a run would write to right now. */
data class MetaTargets(
    val esdeOn: Boolean,
    val esdeFolderSet: Boolean,
    /** The ES-DE folder is set, still reachable and writable. */
    val esdeWritable: Boolean,
    val pegasusOn: Boolean
) {
    val esde: Boolean get() = esdeOn && esdeWritable
    val any: Boolean get() = esde || pegasusOn
}

/**
 * Writes game information (name, description, genre, year, developer, publisher, rating) into the
 * frontends the app already writes covers for (7.0): ES-DE's `gamelist.xml` and Pegasus'
 * `metadata.txt`. The merge itself is [FrontendMetadata]: only empty fields are filled, nothing
 * is removed or reordered, and a file that cannot be merged with certainty is left alone. Here:
 *  - where the files are: the same way the cover code finds them (the console folder of the scanned
 *    library = ES-DE's system; Pegasus next to the games);
 *  - where the details come from: what Dogmatix+ already knows (the cache of earlier lookups),
 *    then the RomM server, then the online databases, each only for what is still missing, and
 *    only for games whose files lack something. Lookups are throttled, limited per run and
 *    cancellable; whatever is left is done by the next run;
 *  - safety: the first time a file is changed a copy is kept next to it (`.dogmatix-bak`), every
 *    write replaces the file in one step, and the file is read again right before it is merged.
 *
 * A run is for one console or for all; it reports counts per console. ES-DE should be closed
 * while it runs (it writes its gamelists when it exits).
 */
@Singleton
class FrontendMetadataService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val settings: FrontendMetadataSettings,
    private val libraryScanService: LibraryScanService,
    private val metadata: GameMetadataService,
    private val rommGames: RommGameService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(MetaRunState())
    val state: StateFlow<MetaRunState> = _state.asStateFlow()

    /** One read-merge-write of a file at a time, so a run and the automatic write never cross. */
    private val writeLock = Mutex()
    private var job: Job? = null

    /** Which frontends are on and reachable. */
    suspend fun targets(): MetaTargets = withContext(Dispatchers.IO) {
        val uri = settingsRepository.esdeDirectory.first()
        val writable = uri.isNotBlank() && StorageHelper.getDocumentFile(context, uri)?.let { it.isDirectory && it.canWrite() } == true
        MetaTargets(settings.esde.first(), uri.isNotBlank(), writable, settings.pegasus.first())
    }

    /** Starts a run for [consoleId], or for every console with games on the device when null. */
    fun start(consoleId: String? = null) {
        if (_state.value.running) return
        _state.value = MetaRunState(running = true, scope = consoleId)
        job = scope.launch { run(consoleId) }
    }

    fun cancel() { job?.cancel() }

    /** The last run's outcome is dismissed (the screen shows it until then). */
    fun clear() { if (!_state.value.running) _state.value = MetaRunState() }

    // ---- The run -------------------------------------------------------------------------------

    private suspend fun run(only: String?) {
        try {
            val resolved = resolve(targets())
            if (!resolved.any) return finish(MetaRunState.Problem.NO_TARGET, only)

            val byConsole = LinkedHashMap<String, MutableList<Item>>()
            for (entry in DuplicateFinder.entries(libraryScanService.scan().files)) {
                val consoleId = entry.consoleId ?: continue
                if (only != null && consoleId != only) continue
                if (!entry.comparable) continue
                val name = FrontendMetadata.mainFile(entry.files.map { it.name }) ?: continue
                val file = entry.files.first { it.name == name }
                byConsole.getOrPut(consoleId) { ArrayList() } += Item(
                    consoleId, entry.baseName, name, FrontendMetadata.locate(consoleId, file.folder), file.dirUri
                )
            }
            if (byConsole.isEmpty()) return finish(MetaRunState.Problem.NOTHING_ON_DEVICE, only)

            val total = byConsole.values.sumOf { it.size }
            _state.update { it.copy(total = total) }
            val budget = Budget(romm = RUN_ROMM_CALLS, online = RUN_ONLINE_LOOKUPS)
            val results = LinkedHashMap<String, MetaCounts>()
            for ((consoleId, items) in byConsole) {
                currentCoroutineContext().ensureActive()
                _state.update { it.copy(consoleId = consoleId) }
                results[consoleId] = process(items, resolved, budget) { _state.update { s -> s.copy(done = s.done + 1) } }
                _state.update { it.copy(results = LinkedHashMap(results)) }
            }
            _state.value = MetaRunState(finished = true, scope = only, results = results, done = total, total = total)
        } catch (e: CancellationException) {
            // Stopped by the user: what was written stays, the counts so far are shown.
            _state.update { it.copy(running = false, consoleId = null) }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Metadata run failed", e)
            finish(MetaRunState.Problem.FAILED, only)
        }
    }

    private fun finish(problem: MetaRunState.Problem, only: String?) {
        _state.value = MetaRunState(scope = only, problem = problem)
    }

    /**
     * After a finished download: the game's details go to the frontends that are switched on, when
     * "after every download" is on. [dir] is the folder the game was saved in, [folderName] its
     * name (ES-DE's system, as for the covers) and [romName] the file a frontend lists. Never throws.
     */
    suspend fun writeAfterDownload(title: String, consoleId: String, romName: String, dir: DocumentFile, folderName: String) {
        try {
            if (!settings.autoWrite.first()) return
            val resolved = resolve(targets())
            if (!resolved.any) return
            // Same rule as the batch run: only a folder that names the console is an ES-DE system.
            val location = folderName.takeIf { it.isNotBlank() && ConsoleFolderAliases.matches(consoleId, it) }
                ?.let { FrontendMetadata.Location(it, "") }
            val item = Item(consoleId, title, romName, location, dir.uri.toString(), dir)
            process(listOf(item), resolved, Budget(romm = 1, online = 1, throttle = false)) {}
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Metadata after download failed for $title: ${e.message}")
        }
    }

    // ---- Planning ------------------------------------------------------------------------------

    /** A game as found on the device. */
    private class Item(
        val consoleId: String,
        /** What the game is looked up by (the file name without its extension, or the game folder's name). */
        val title: String,
        /** The file the frontends list. */
        val fileName: String,
        /** ES-DE's system and the file's path below its folder; null when the folder is not a console folder. */
        val location: FrontendMetadata.Location?,
        /** The folder holding the file (Pegasus' metadata sits there). */
        val dirUri: String,
        val dir: DocumentFile? = null
    )

    private class Resolved(val esdeDir: DocumentFile?, val pegasus: Boolean) {
        val any: Boolean get() = esdeDir != null || pegasus
    }

    private suspend fun resolve(t: MetaTargets): Resolved {
        val esdeDir = if (t.esde) StorageHelper.getDocumentFile(context, settingsRepository.esdeDirectory.first()) else null
        return Resolved(esdeDir, t.pegasusOn)
    }

    /** What a run may spend on lookups: one budget for the whole run, so a huge library cannot hammer a server. */
    private class Budget(var romm: Int, var online: Int, val throttle: Boolean = true) {
        var rommFailures = 0
        val rommUsable: Boolean get() = rommFailures < ROMM_FAILURES_STOP
    }

    private enum class Kind { ESDE, PEGASUS }

    /** The games that share one file: an ES-DE system's gamelist, or the metadata file of one folder. */
    private class Group(
        val kind: Kind,
        val items: List<Item>,
        /** ES-DE: ES-DE's data folder. Pegasus: the folder with the games. */
        val dir: DocumentFile,
        /** Folder of the file below [dir] (ES-DE: `gamelists/<system>`; Pegasus: none). */
        val subPath: String,
        val fileName: String,
        /** What the file holds now; null missing = no file yet. */
        val missing: Map<String, Set<Field>>?
    ) {
        fun key(item: Item): String = if (kind == Kind.ESDE) item.location!!.relative(item.fileName) else item.fileName
    }

    /** A file as read: its raw bytes (for the backup), its text, and whether it can be merged at all. */
    private class FileRead(val bytes: ByteArray?, val text: String?, val readable: Boolean)

    private fun read(dir: DocumentFile, subPath: String, fileName: String): FileRead {
        val path = if (subPath.isEmpty()) fileName else "$subPath/$fileName"
        val file = StorageHelper.findFile(dir, path)?.takeIf { it.isFile } ?: return FileRead(null, null, true)
        return try {
            if (file.length() > MAX_FILE_BYTES) return FileRead(null, null, false)
            val bytes = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() } ?: return FileRead(null, null, false)
            val text = FrontendMetadata.decodeUtf8Strict(bytes) ?: return FileRead(bytes, null, false)
            FileRead(bytes, text, true)
        } catch (e: Exception) {
            FileRead(null, null, false)
        }
    }

    /** Pegasus reads `metadata.txt` and `metadata.pegasus.txt`: an existing one is used, else `metadata.txt` is made. */
    private fun pegasusFileName(dir: DocumentFile): String = when {
        StorageHelper.findFile(dir, FrontendMetadata.PEGASUS_FILE) != null -> FrontendMetadata.PEGASUS_FILE
        StorageHelper.findFile(dir, FrontendMetadata.PEGASUS_ALT_FILE) != null -> FrontendMetadata.PEGASUS_ALT_FILE
        else -> FrontendMetadata.PEGASUS_FILE
    }

    /** Reads what each target already holds and works out what its games still lack. */
    private fun plan(items: List<Item>, t: Resolved): Pair<List<Group>, Pair<TargetCounts, TargetCounts>> {
        val groups = ArrayList<Group>()
        var esdeSkipped = TargetCounts()
        var pegasusSkipped = TargetCounts()
        if (t.esdeDir != null) {
            for ((system, games) in items.filter { it.location != null }.groupBy { it.location!!.system }) {
                val sub = FrontendMetadata.esdeGamelistDir(system)
                val r = read(t.esdeDir, sub, FrontendMetadata.ESDE_FILE)
                val missing = if (r.readable) FrontendMetadata.esdeMissing(r.text, games.map { it.location!!.relative(it.fileName) }) else null
                if (missing == null) esdeSkipped += TargetCounts(unreadable = 1)
                else groups += Group(Kind.ESDE, games, t.esdeDir, sub, FrontendMetadata.ESDE_FILE, missing)
            }
        }
        if (t.pegasus) {
            for ((_, games) in items.groupBy { it.dirUri }) {
                val dir = games.first().dir ?: StorageHelper.getDocumentFile(context, games.first().dirUri)
                if (dir == null || !dir.isDirectory || !dir.canWrite()) { pegasusSkipped += TargetCounts(failed = 1); continue }
                val name = pegasusFileName(dir)
                val r = read(dir, "", name)
                val missing = if (r.readable) FrontendMetadata.pegasusMissing(r.text, games.map { it.fileName }) else null
                if (missing == null) pegasusSkipped += TargetCounts(unreadable = 1)
                else groups += Group(Kind.PEGASUS, games, dir, "", name, missing)
            }
        }
        return groups to (esdeSkipped to pegasusSkipped)
    }

    // ---- Doing it ------------------------------------------------------------------------------

    private suspend fun process(items: List<Item>, t: Resolved, budget: Budget, onGame: () -> Unit): MetaCounts {
        val (groups, skipped) = withContext(Dispatchers.IO) { plan(items, t) }
        var esde = skipped.first
        var pegasus = skipped.second
        val outside = if (t.esdeDir != null) items.count { it.location == null } else 0

        // What each game's files lack; NAME alone is never a reason to ask anybody.
        val needs = HashMap<Item, Set<Field>>()
        for (g in groups) for (item in g.items) {
            needs[item] = needs[item].orEmpty() + g.missing?.get(g.key(item)).orEmpty()
        }

        val metas = HashMap<Item, GameMeta>()
        var complete = 0; var noData = 0; var deferred = 0
        for (item in items) {
            currentCoroutineContext().ensureActive()
            val wanted = needs[item].orEmpty() - Field.NAME
            if (wanted.isEmpty()) {
                // Every entry of this game is filled (or no file of it could be read: counted there).
                if (item in needs) complete++
            } else {
                val got = gather(item, wanted, budget)
                if (got.meta.hasData || got.meta.text(Field.NAME) != null) metas[item] = got.meta
                when {
                    got.nothingToAsk -> complete++
                    !got.meta.hasData && got.deferred -> deferred++
                    !got.meta.hasData -> noData++
                    got.deferred -> deferred++
                }
            }
            onGame()
        }

        for (g in groups) {
            currentCoroutineContext().ensureActive()
            val chosen = LinkedHashMap<String, GameMeta>()
            for (item in g.items) metas[item]?.let { chosen.putIfAbsent(g.key(item), it) }
            if (chosen.isEmpty()) continue
            val outcome = writeGroup(g, chosen)
            if (g.kind == Kind.ESDE) esde += outcome else pegasus += outcome
        }
        return MetaCounts(items.size, complete, noData, deferred, outside, esde, pegasus)
    }

    /** Reads the file again (it may have changed while the details were gathered), merges and replaces it in one step. */
    private suspend fun writeGroup(g: Group, items: Map<String, GameMeta>): TargetCounts = writeLock.withLock {
        withContext(Dispatchers.IO) {
            try {
                val r = read(g.dir, g.subPath, g.fileName)
                if (!r.readable) return@withContext TargetCounts(unreadable = 1)
                val merge = if (g.kind == Kind.ESDE) FrontendMetadata.esdeMerge(r.text, items) else FrontendMetadata.pegasusMerge(r.text, items)
                if (merge.unreadable) return@withContext TargetCounts(unreadable = 1)
                val content = merge.content ?: return@withContext TargetCounts()
                // The first time a file is changed, its original is kept next to it. No copy, no change.
                val original = r.bytes
                if (original != null && original.isNotEmpty()) {
                    val backup = FrontendMetadata.backupName(g.fileName)
                    val path = if (g.subPath.isEmpty()) backup else "${g.subPath}/$backup"
                    if (StorageHelper.findFile(g.dir, path) == null) StorageHelper.writeBytesSafely(context, g.dir, g.subPath, backup, original)
                }
                StorageHelper.writeTextSafely(context, g.dir, g.subPath, g.fileName, content)
                TargetCounts(files = 1, added = merge.added, filled = merge.filled)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not write ${g.fileName} in ${g.subPath.ifEmpty { g.dir.name }}: ${e.message}")
                TargetCounts(failed = 1)
            }
        }
    }

    // ---- Where the details come from ----------------------------------------------------------------

    private class Gathered(val meta: GameMeta, val deferred: Boolean, val nothingToAsk: Boolean)

    /** What the cache and the online databases can give; RomM adds the rating. */
    private val fromDatabases = setOf(Field.DESCRIPTION, Field.GENRE, Field.RELEASE, Field.DEVELOPER)
    private val fromRomm = setOf(Field.DESCRIPTION, Field.GENRE, Field.RELEASE, Field.RATING)

    private suspend fun gather(item: Item, wanted: Set<Field>, budget: Budget): Gathered {
        val romId = if (budget.rommUsable && rommGames.isConfigured()) runCatching { rommGames.romIdFor(item.consoleId, item.fileName) }.getOrNull() else null
        val providable = fromDatabases + if (romId != null) fromRomm else emptySet()
        if ((wanted intersect providable).isEmpty()) return Gathered(GameMeta(), deferred = false, nothingToAsk = true)

        var meta = GameMeta()
        var deferred = false
        fun missing() = wanted.filter { meta.text(it) == null }.toSet()

        // 1. What is already known: free.
        val cached = metadata.peek(item.title, item.consoleId)
        if (cached is GameMetadataService.Cached.Hit) meta = meta.orElse(fromDetails(cached.details))

        // 2. The user's own RomM server.
        if (romId != null && (missing() intersect fromRomm).isNotEmpty()) {
            if (budget.romm > 0) {
                budget.romm--
                if (budget.throttle) delay(ROMM_DELAY_MS)
                val info = try {
                    rommGames.details(romId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    budget.rommFailures++
                    null
                }
                if (info != null) {
                    budget.rommFailures = 0
                    meta = meta.orElse(
                        GameMeta(
                            name = info.name, description = info.summary, genre = info.genres.joinToString(", "),
                            released = info.releaseYear?.toString().orEmpty(), ratingPercent = info.ratingPercent
                        )
                    )
                }
            } else deferred = true
        }

        // 3. The online databases: one lookup per game, never repeated (misses are cached too).
        if (cached is GameMetadataService.Cached.Unknown && (missing() intersect fromDatabases).isNotEmpty()) {
            if (budget.online > 0) {
                budget.online--
                if (budget.throttle) delay(ONLINE_DELAY_MS)
                val found = try {
                    metadata.lookup(item.title, item.consoleId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (found != null) meta = meta.orElse(fromDetails(found))
            } else deferred = true
        }
        return Gathered(meta, deferred, nothingToAsk = false)
    }

    private fun fromDetails(d: GameDetails) = GameMeta(
        name = d.title, description = d.description, genre = d.genres.joinToString(", "), released = d.released, developer = d.developer
    )

    private companion object {
        /** RomM calls (the user's own server) per run. */
        const val RUN_ROMM_CALLS = 300
        /** Online database lookups per run (RAWG and TheGamesDB have quotas). */
        const val RUN_ONLINE_LOOKUPS = 40
        const val ROMM_DELAY_MS = 80L
        const val ONLINE_DELAY_MS = 400L
        /** RomM is not asked any more in a run once this many answers in a row failed. */
        const val ROMM_FAILURES_STOP = 3
        /** A gamelist or metadata file larger than this is not rewritten. */
        const val MAX_FILE_BYTES = 24L * 1024 * 1024
    }
}
