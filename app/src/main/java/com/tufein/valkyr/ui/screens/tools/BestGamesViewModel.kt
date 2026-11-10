package com.tufein.valkyr.ui.screens.tools

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.R
import com.tufein.valkyr.data.local.VersionPreferenceSettings
import com.tufein.valkyr.data.local.dao.DownloadableFileDao
import com.tufein.valkyr.data.local.entity.DownloadableFileEntity
import com.tufein.valkyr.data.repository.ConsoleRepository
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.data.repository.WishlistRepository
import com.tufein.valkyr.data.service.BestGamesService
import com.tufein.valkyr.data.service.DownloadService
import com.tufein.valkyr.data.service.LibraryIndexService
import com.tufein.valkyr.data.service.RetroAchievementsService
import com.tufein.valkyr.data.service.VersionPreferenceService
import com.tufein.valkyr.data.state.LibraryFilterRequest
import com.tufein.valkyr.data.state.PendingLibraryFilters
import com.tufein.valkyr.data.state.RescanStateHolder
import com.tufein.valkyr.ui.components.formatBytes
import com.tufein.valkyr.util.BestGame
import com.tufein.valkyr.util.BestGames
import com.tufein.valkyr.util.BulkCandidate
import com.tufein.valkyr.util.BulkPlan
import com.tufein.valkyr.util.BulkPlanner
import com.tufein.valkyr.util.CollectionGoals
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.FileParsingUtils
import com.tufein.valkyr.util.GameMatch
import com.tufein.valkyr.util.GameTitleCleaner
import com.tufein.valkyr.util.LibraryKeys
import com.tufein.valkyr.util.OwnState
import com.tufein.valkyr.util.RaConsoleMap
import com.tufein.valkyr.util.RaErrorKind
import com.tufein.valkyr.util.RankBasis
import com.tufein.valkyr.util.SearchNormalizer
import com.tufein.valkyr.util.StorageHelper
import com.tufein.valkyr.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A library console RetroAchievements has games for: [raName] is RA's name of the system. */
data class BestConsole(val id: String, val name: String, val raId: Int, val raName: String)

/** One line of the list: a ranked game and where it is. */
data class BestRow(
    val rank: Int,
    val gameId: Int,
    val title: String,
    /** Distinct players RA counts, when they were asked. */
    val players: Int?,
    val points: Int,
    val state: OwnState,
    val iconUrl: String?,
    /** File name the cover is looked up by: a source's own file when there is one. */
    val coverFileName: String,
    /** Versions of the game the sources list. */
    val versions: Int,
    val wished: Boolean,
    val downloading: Boolean,
    /** What to search the library for to find the game, or null when no source lists it. */
    val libraryQuery: String?
)

/** The open console's list with what the screen says about how it was ranked. */
data class BestDetail(
    val console: BestConsole,
    val loading: Boolean,
    val error: RaErrorKind?,
    /** The list is an older copy because RA could not be reached. */
    val stale: Boolean,
    val rows: List<BestRow>,
    val basis: RankBasis,
    /** Games with a player count, of [candidates]. */
    val checked: Int,
    val candidates: Int,
    /** (done, total) while player counts are being fetched. */
    val refine: Pair<Int, Int>?,
    val refineError: RaErrorKind?,
    val onDevice: Int,
    val inSources: Int,
    val missing: Int,
    /** Games a "Download the ones I can get" would take. */
    val downloadable: Int,
    /** Missing games not on the wishlist yet. */
    val wishable: Int
)

/** [account] is null until the settings were read. */
data class BestGamesUi(
    val account: Boolean? = null,
    val consoles: List<BestConsole> = emptyList(),
    val detail: BestDetail? = null
)

@HiltViewModel
class BestGamesViewModel @Inject constructor(
    private val service: BestGamesService,
    consoleRepository: ConsoleRepository,
    private val libraryIndex: LibraryIndexService,
    private val fileDao: DownloadableFileDao,
    private val downloadService: DownloadService,
    private val wishlist: WishlistRepository,
    private val pendingFilters: PendingLibraryFilters,
    private val settingsRepository: SettingsRepository,
    private val rescan: RescanStateHolder,
    private val versionSettings: VersionPreferenceSettings,
    private val versionPreference: VersionPreferenceService
) : ViewModel() {

    private data class Loaded(val consoleId: String, val games: List<BestGame>, val players: Map<Int, Int>, val stale: Boolean)

    /** The ranking against the library, ready to show; [rawNames] are the sources' file names as stored. */
    private class Matched(
        val consoleId: String,
        val stale: Boolean,
        val basis: RankBasis,
        val checked: Int,
        val candidates: Int,
        val matches: List<GameMatch>,
        val rawNames: List<String>,
        val decodedNames: List<String>
    )

    private data class Status(
        val loading: Boolean = false,
        val error: RaErrorKind? = null,
        val refine: Pair<Int, Int>? = null,
        val refineError: RaErrorKind? = null
    )

    private val selectedId = MutableStateFlow<String?>(null)
    private val loaded = MutableStateFlow<Loaded?>(null)
    private val status = MutableStateFlow(Status())
    /** The open console's source file names (console id with them), read again after a rescan. */
    private val sourceNames = MutableStateFlow<Pair<String, List<String>>?>(null)

    private var loadJob: Job? = null
    private var refineJob: Job? = null

    private val account: StateFlow<Boolean?> = service.accountReady.map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val consoles: StateFlow<List<BestConsole>> = consoleRepository.getAllConsoles()
        .map { list ->
            val byId = list.associateBy { it.id }
            RaConsoleMap.mapAll(list.map { it.id to it.name }).map { (id, ra) ->
                val entity = byId[id]
                BestConsole(id, entity?.name?.takeIf { it.isNotBlank() } ?: ConsoleFormatter.getConsoleFolderName(id), ra.id, ra.name)
            }.sortedBy { it.name.lowercase() }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            combine(selectedId, rescan.lastRescanTime) { id, _ -> id }.collectLatest { id ->
                sourceNames.value = if (id == null) null else id to withContext(Dispatchers.IO) { runCatching { fileDao.fileNamesFor(id) }.getOrDefault(emptyList()) }
            }
        }
    }

    private val matched: StateFlow<Matched?> = combine(loaded, sourceNames, libraryIndex.ownedKeys) { l, sources, owned ->
        // Not ready until both the RA list and the sources of the same console are in: no flash of "missing".
        if (l == null || sources == null || sources.first != l.consoleId) return@combine null
        val ranking = BestGames.rank(l.games, l.players)
        val device = CollectionGoals.ownedNames(LibraryKeys.scopesFor(l.consoleId), CollectionGoals.namesByScope(owned))
        val decoded = sources.second.map { FileParsingUtils.decodeUrlEncodedFileName(it) }
        Matched(l.consoleId, l.stale, ranking.basis, ranking.checked, ranking.candidates, BestGames.match(ranking.games, device, decoded), sources.second, decoded)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** File names of the downloads that are queued or running. */
    private val activeNames: StateFlow<Set<String>> = downloadService.downloads
        .map { list -> list.filterNot { it.isFinished }.map { it.fileName }.toSet() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val extras = combine(wishlist.items, activeNames, status) { wishes, active, s -> Triple(wishes, active, s) }

    val ui: StateFlow<BestGamesUi> = combine(account, consoles, selectedId, matched, extras) { signedIn, all, selected, m, x ->
        val console = all.firstOrNull { it.id == selected }
        BestGamesUi(signedIn, all, console?.let { detailOf(it, m?.takeIf { match -> match.consoleId == it.id }, x.first.filter { w -> w.consoleId == it.id }.map { w -> w.key }.toSet(), x.second, x.third) })
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BestGamesUi())

    private fun detailOf(console: BestConsole, m: Matched?, wishKeys: Set<String>, active: Set<String>, s: Status): BestDetail {
        val rows = m?.matches.orEmpty().map { gm ->
            val game = gm.ranked.game
            val sourceName = gm.sourceIndices.firstOrNull()?.let { m!!.decodedNames[it] }
            BestRow(
                rank = gm.ranked.rank,
                gameId = game.id,
                title = game.title,
                players = gm.ranked.players,
                points = game.points,
                state = gm.state,
                iconUrl = BestGames.iconUrl(game),
                coverFileName = sourceName ?: titleCoverFileName(game.title),
                versions = gm.sourceIndices.size,
                wished = SearchNormalizer.key(game.title) in wishKeys,
                downloading = gm.sourceIndices.any { m!!.rawNames[it] in active },
                libraryQuery = sourceName?.let { GameTitleCleaner.clean(it).ifBlank { game.title } }
            )
        }
        val (onDevice, inSources, missing) = BestGames.tally(m?.matches.orEmpty())
        return BestDetail(
            console = console,
            loading = s.loading || (m == null && s.error == null),
            error = s.error,
            stale = m?.stale == true,
            rows = rows,
            basis = m?.basis ?: RankBasis.POINTS,
            checked = m?.checked ?: 0,
            candidates = m?.candidates ?: 0,
            refine = s.refine,
            refineError = s.refineError,
            onDevice = onDevice,
            inSources = inSources,
            missing = missing,
            downloadable = rows.count { it.state == OwnState.IN_SOURCE && !it.downloading },
            wishable = rows.count { it.state == OwnState.MISSING && !it.wished }
        )
    }

    // ---- Opening a console ----

    fun open(consoleId: String) {
        val console = consoles.value.firstOrNull { it.id == consoleId } ?: return
        selectedId.value = consoleId
        load(console, refresh = false)
    }

    fun close() {
        loadJob?.cancel()
        refineJob?.cancel()
        selectedId.value = null
        loaded.value = null
        status.value = Status()
    }

    /** Fetches RA's game list again (it is kept for a week). */
    fun refresh() {
        val console = consoles.value.firstOrNull { it.id == selectedId.value } ?: return
        load(console, refresh = true)
    }

    private fun load(console: BestConsole, refresh: Boolean) {
        loadJob?.cancel()
        refineJob?.cancel()
        loaded.value = null
        loadJob = viewModelScope.launch {
            status.value = Status(loading = true)
            try {
                // What is kept shows at once while a refresh runs.
                if (refresh) service.cachedGames(console.raId)?.let { cached ->
                    loaded.value = Loaded(console.id, cached.games, service.cachedPlayers(console.raId), cached.stale)
                }
                val list = service.games(console.raId, refresh)
                loaded.value = Loaded(console.id, list.games, service.cachedPlayers(console.raId), list.stale)
                status.value = Status()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.value = Status(error = kindOf(e))
            }
        }
    }

    // ---- Player counts ----

    /** Asks RA for the player counts of the top games (throttled; counts are kept for a week) and ranks by them. */
    fun refine() {
        val current = loaded.value ?: return
        val console = consoles.value.firstOrNull { it.id == current.consoleId } ?: return
        if (status.value.refine != null) return
        val pool = BestGames.candidates(current.games)
        refineJob = viewModelScope.launch {
            status.update { it.copy(refine = (pool.size - BestGames.pending(pool, current.players).size) to pool.size, refineError = null) }
            try {
                val players = service.fetchPlayers(console.raId, pool) { done, total -> status.update { it.copy(refine = done to total) } }
                loaded.update { cur -> if (cur != null && cur.consoleId == console.id) cur.copy(players = players) else cur }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.update { it.copy(refineError = kindOf(e)) }
            } finally {
                // Stopped or failed: what was counted so far is kept and shown.
                withContext(NonCancellable) {
                    val kept = service.cachedPlayers(console.raId)
                    loaded.update { cur -> if (cur != null && cur.consoleId == console.id) cur.copy(players = kept) else cur }
                    status.update { it.copy(refine = null) }
                }
            }
        }
    }

    fun stopRefine() { refineJob?.cancel() }

    // ---- Actions ----

    /** Opens the library on the game (the shell switches to the Library tab, as for the wishlist). */
    fun openInLibrary(row: BestRow) {
        val query = row.libraryQuery ?: return
        val console = selectedId.value ?: return
        pendingFilters.submit(LibraryFilterRequest(consoles = setOf(console), query = query))
    }

    fun wish(context: Context, row: BestRow) {
        val console = selectedId.value ?: return
        val app = context.applicationContext
        viewModelScope.launch {
            val added = runCatching { wishlist.add(row.title, console) }.getOrDefault(false)
            if (added) ToastUtil.showSuccess(app, app.getString(R.string.wishlist_added, row.title))
            else ToastUtil.showInfo(app, app.getString(R.string.top7_wish_none))
        }
    }

    /** Puts every missing game of the list on the wishlist for this console. */
    fun wishMissing(context: Context) {
        val m = matched.value ?: return
        val app = context.applicationContext
        viewModelScope.launch {
            var added = 0
            for (title in BestGames.wishTitles(m.matches)) {
                if (runCatching { wishlist.add(title, m.consoleId) }.getOrDefault(false)) added++
            }
            if (added == 0) ToastUtil.showInfo(app, app.getString(R.string.top7_wish_none))
            else ToastUtil.showSuccess(app, app.resources.getQuantityString(R.plurals.top7_wish_added, added, added))
        }
    }

    /** What the sources hold for the open list, by id of the library row, kept for [startDownload] (shared by quick successive plans). */
    private val plannedRows = ConcurrentHashMap<Long, DownloadableFileEntity>()

    /**
     * The download of the games found in a source and not on the device: one version of each (the best
     * for the user's languages), skipping what already downloads. Shown to the user before anything starts.
     */
    suspend fun planDownload(onlyGame: Int? = null): BulkPlan {
        val preferences = versionSettings.snapshot()
        val fixed = versionPreference.snapshot()
        val free = libraryIndex.freeBytes.value
        val m = matched.value ?: return BulkPlanner.plan(emptyList(), true, { preferences.of(it) }, free)
        val gameOfFile = HashMap<String, Int>()
        for (gm in BestGames.downloadable(m.matches)) if (onlyGame == null || gm.ranked.game.id == onlyGame) for (i in gm.sourceIndices) gameOfFile[m.rawNames[i]] = gm.ranked.game.id
        return withContext(Dispatchers.IO) {
            val rows = gameOfFile.keys.chunked(800).flatMap { fileDao.filesByFileNames(it) }.filter { it.consoleId == m.consoleId }
            val tags = rows.chunked(800).flatMap { fileDao.tagsOfFiles(it.map { r -> r.id }) }.groupBy({ it.fileId }, { it.tag })
            plannedRows.putAll(rows.associateBy { it.id })
            val candidates = rows.mapNotNull { row ->
                val game = gameOfFile[row.fileName] ?: return@mapNotNull null
                // The RA game stands in for the cleaned title: versions of one game are picked among.
                BulkCandidate(row.id, row.consoleId, "ra$game", row.fileName, row.fileSize, tags[row.id].orEmpty(), owned = false, downloading = downloadService.isActive(row.fileName))
            }
            BulkPlanner.plan(candidates, bestOnly = true, { preferences.of(it) }, free,
                preferredVersion = { fixed.preferred(it.consoleId, it.fileName) })
        }
    }

    /** Queues the games of [plan]; returns how many. */
    suspend fun startDownload(plan: BulkPlan, context: Context): Int {
        val rows = plan.chosen.mapNotNull { plannedRows[it.id] }
        if (rows.isEmpty()) return 0
        val directory = settingsRepository.downloadDirectory.first()
        if (!withContext(Dispatchers.IO) { StorageHelper.isValidUri(context, directory) }) {
            ToastUtil.showError(context, context.getString(R.string.error_download_dir_missing))
            return 0
        }
        withContext(Dispatchers.Default) { downloadService.startDownloads(rows) }
        return rows.size
    }

    /**
     * Queues the best version of one game found in a source and leaves the user on the list (the row
     * shows "downloading" by itself); a download that cannot start says why in a toast.
     */
    fun downloadOne(row: BestRow, context: Context) {
        val app = context.applicationContext
        viewModelScope.launch {
            val plan = planDownload(onlyGame = row.gameId)
            when {
                plan.chosen.isEmpty() -> ToastUtil.showInfo(app, app.getString(R.string.top7_download_none))
                !plan.fits -> ToastUtil.showError(app, app.getString(R.string.bulk_no_room_short, formatBytes(plan.shortBytes)))
                else -> {
                    val queued = startDownload(plan, app)
                    if (queued > 0) ToastUtil.showSuccess(app, app.resources.getQuantityString(R.plurals.top7_queued, queued, queued))
                }
            }
        }
    }

    private fun kindOf(e: Throwable): RaErrorKind = when (e) {
        is RetroAchievementsService.RaApiException -> e.kind
        is BestGamesService.NoCredentialsException -> RaErrorKind.NO_ACCOUNT
        is IOException -> RaErrorKind.OFFLINE
        else -> RaErrorKind.BAD_RESPONSE
    }
}
