package com.tufein.valkyr.ui.screens.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.data.local.dao.DownloadableFileDao
import com.tufein.valkyr.data.local.dao.toSupportQuery
import com.tufein.valkyr.data.local.queries.LibraryQueryBuilder
import com.tufein.valkyr.data.model.SortOption
import com.tufein.valkyr.data.repository.DownloadableFileRepository
import com.tufein.valkyr.data.service.ActionLogService
import com.tufein.valkyr.data.service.ForYouService
import com.tufein.valkyr.data.service.LibraryIndexService
import com.tufein.valkyr.data.service.PlayHistoryService
import com.tufein.valkyr.data.service.ProfileService
import com.tufein.valkyr.data.service.UpNextService
import com.tufein.valkyr.data.state.RescanStateHolder
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.ConsoleHome
import com.tufein.valkyr.util.GameShortcuts
import com.tufein.valkyr.util.HistoryEvent
import com.tufein.valkyr.util.HistoryKind
import com.tufein.valkyr.util.LibraryKeys
import com.tufein.valkyr.util.LibraryRestrictions
import com.tufein.valkyr.util.UpNext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

private const val TAG = "HomeShelves"

/** How long the ES-DE play list may take to read before the shelves go on without it. */
private const val ESDE_TIMEOUT_MS = 5_000L

/** "For you" is worked out (a scan of the library) only after Home has been shown this long. */
private const val FOR_YOU_DELAY_MS = 4_000L

/** Names per IN (...) query, well below SQLite's 999 variables. */
private const val CHUNK = 400

/** Library rows a started file may stand for (as GameShortcutService). */
private const val MAX_MATCHES = 8

/**
 * Backs the 3.8.0 Home shelves ([HomeShelves]). Reads only what the app already keeps: the games
 * Dogmatix+ started (action history), ES-DE's last played games and finished downloads for the
 * hero; the Up next list and the cached "For you" suggestions; the library index for what is on
 * the device. Everything respects the active profile's hidden consoles and tags. The choice of
 * games is [ShelvesModel]'s; this only gathers the inputs, off the main thread.
 */
@HiltViewModel
class ShelvesViewModel @Inject constructor(
    private val fileDao: DownloadableFileDao,
    private val repository: DownloadableFileRepository,
    private val library: LibraryIndexService,
    private val profiles: ProfileService,
    private val actionLog: ActionLogService,
    private val playHistory: PlayHistoryService,
    private val upNext: UpNextService,
    private val forYouService: ForYouService,
    private val rescans: RescanStateHolder
) : ViewModel() {

    /**
     * The library's file names per console, read once per rescan (the only thing that changes them):
     * a burst of owned keys, a new history line or coming back to Home only filters these in memory.
     */
    private class NameCache(val rescanAt: Long, val consoles: List<Pair<String, Int>>, val names: Map<String, List<String>>)
    private val nameLock = Mutex()
    @Volatile private var nameCache: NameCache? = null

    private suspend fun names(): NameCache = nameLock.withLock {
        val at = rescans.lastRescanTime.value
        nameCache?.takeIf { it.rescanAt == at }?.let { return@withLock it }
        // Unfiltered: the profile's hidden consoles are applied by the callers, so a profile switch reuses this.
        val consoles = fileDao.rawConsolesWithFiles(LibraryQueryBuilder.consoles(LibraryQueryBuilder.Text.None, null).toSupportQuery()).map { it.id to it.fileCount }
        val names = consoles.associate { (id, _) -> id to fileDao.fileNamesFor(id) }
        NameCache(at, consoles, names).also { nameCache = it }
    }

    /** Suggestions of "For you", once known (kept by [ForYouService] for a while per profile). */
    private val suggestions = MutableStateFlow(forYouService.cached()?.suggestions.orEmpty())

    /** ES-DE's plays, read again after a rescan only (the files can be large). */
    private val esdePlays = MutableStateFlow<List<HistoryEvent>>(emptyList())

    init {
        viewModelScope.launch {
            rescans.lastRescanTime.collect {
                esdePlays.value = runCatching { withTimeoutOrNull(ESDE_TIMEOUT_MS) { playHistory.plays() } }
                    .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "ES-DE plays skipped: ${it.javaClass.simpleName}") }
                    .getOrNull().orEmpty()
                    .filter { it.openable && it.kind == HistoryKind.PLAYED }
            }
        }
        // The suggestions scan the library: not while the app is starting, and only once per profile and quarter of an hour.
        viewModelScope.launch {
            if (suggestions.value.isNotEmpty()) return@launch
            delay(FOR_YOU_DELAY_MS)
            suggestions.value = runCatching { forYouService.suggestions().suggestions }
                .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "For you skipped: ${it.javaClass.simpleName}") }
                .getOrNull().orEmpty()
        }
    }

    /** The console shelves: what is on the device per console (or, with nothing on it, the newest games). */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private val consoleGames: kotlinx.coroutines.flow.Flow<ConsoleGames> =
        combine(library.ownedKeys, profiles.restrictions, rescans.lastRescanTime) { keys, restrictions, _ -> keys to restrictions }
            // The index adds keys in bursts while it looks at the device.
            .debounce(600)
            .mapLatest { (keys, restrictions) -> safe { consoleGames(keys, restrictions) } ?: ConsoleGames(keys, emptyMap(), emptyList()) }

    /** The hero's candidates: started in Dogmatix+, played in ES-DE, downloaded. */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private val recents: kotlinx.coroutines.flow.Flow<List<ShelvesModel.Recent>> =
        combine(actionLog.entries, profiles.activeId, profiles.restrictions, library.ownedKeys, esdePlays) { entries, profile, restrictions, keys, plays ->
            Inputs(entries.orEmpty(), profile, restrictions, keys, plays)
        }.debounce(400)
            .mapLatest { safe { recents(it) } ?: emptyList() }

    /** Null until the first shelves are known (the screen shows placeholders meanwhile). */
    val state: StateFlow<ShelvesModel.State?> =
        combine(consoleGames, recents, upNext.entries, suggestions, profiles.restrictions) { consoles, recent, upNextList, suggested, restrictions ->
            val hero = ShelvesModel.hero(recent)
            val upNextGames = UpNext.open(upNextList.orEmpty().filter { it.consoleId !in restrictions.hiddenConsoles })
                .map { ShelvesModel.Game(it.consoleId, it.fileName, it.title, LibraryKeys.isOwned(it.consoleId, it.fileName, consoles.keys)) }
            val suggestedGames = suggested.filter { it.row.consoleId !in restrictions.hiddenConsoles }
                .map { ShelvesModel.Game(it.row.consoleId, it.row.fileName, it.row.name, it.row.owned) }
            val forYou = ShelvesModel.forYou(upNextGames, suggestedGames, setOfNotNull(hero?.game?.key))
            val shelves = if (consoles.owned.isNotEmpty()) {
                ShelvesModel.consoleShelves(consoles.owned, recent.associate { it.game.key to it.at }, ConsoleFormatter::getConsoleDisplayName)
            } else consoles.newest
            // The Consoles start page: totals from the per-rescan cache, "here" from the owned games just worked out.
            val tiles = ConsoleHome.tiles(consoles.totals, consoles.owned.mapValues { it.value.size }, ConsoleFormatter::getConsoleDisplayName)
            ShelvesModel.State(hero, withoutHiddenTags(forYou, restrictions), shelves, tiles)
        }.flowOn(Dispatchers.IO)
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Lazily, null)

    private class Console(val id: String, val fileCount: Int)

    private class ConsoleGames(
        val keys: Set<String>,
        val owned: Map<String, List<ShelvesModel.Game>>,
        val newest: List<ShelvesModel.Shelf>,
        /** Games per console (the profile's hidden consoles left out), for the Consoles tiles. */
        val totals: List<Pair<String, Int>> = emptyList()
    )

    private class Inputs(
        val entries: List<com.tufein.valkyr.util.ActionEntry>,
        val profile: String,
        val restrictions: LibraryRestrictions,
        val keys: Set<String>,
        val plays: List<HistoryEvent>
    )

    private suspend fun consoleGames(keys: Set<String>, restrictions: LibraryRestrictions): ConsoleGames {
        val cache = names()
        val consoles = cache.consoles.filter { it.first !in restrictions.hiddenConsoles }.map { Console(it.first, it.second) }
        val owned = HashMap<String, List<ShelvesModel.Game>>()
        if (keys.isNotEmpty()) for (console in consoles) {
            val names = cache.names[console.id].orEmpty().filter { LibraryKeys.isOwned(console.id, it, keys) }.distinct()
            if (names.isEmpty()) continue
            val rows = names.chunked(CHUNK).flatMap { fileDao.filesByFileNames(it) }
                .filter { it.consoleId == console.id }
                .distinctBy { it.fileName }
            val allowed = allowedIds(rows.map { it.id }, restrictions)
            val games = rows.filter { it.id in allowed }.map { ShelvesModel.Game(it.consoleId, it.fileName, it.name, owned = true) }
            if (games.isNotEmpty()) owned[console.id] = games
        }
        val totals = consoles.map { it.id to it.fileCount }
        if (owned.isNotEmpty()) return ConsoleGames(keys, owned, emptyList(), totals)
        // Nothing on the device yet: the newest games of the biggest consoles (the query applies the profile itself).
        val counts = consoles.associate { it.id to it.fileCount }
        val newest = ShelvesModel.newestConsoles(counts, ConsoleFormatter::getConsoleDisplayName).mapNotNull { id ->
            val rows = repository.searchFilesWithTags(query = "", consoleIds = setOf(id), sort = SortOption.NEWEST, limit = ShelvesModel.SHELF_CAP)
            ShelvesModel.newestShelf(id, rows.map { ShelvesModel.Game(it.file.consoleId, it.file.fileName, it.file.name) }, counts[id] ?: 0)
        }
        return ConsoleGames(keys, emptyMap(), newest, totals)
    }

    private suspend fun recents(inputs: Inputs): List<ShelvesModel.Recent> {
        val restrictions = inputs.restrictions
        val events = ArrayList<ShelvesModel.Recent>()
        fun owned(consoleId: String, fileName: String) = LibraryKeys.isOwned(consoleId, fileName, inputs.keys)
        // Started from Dogmatix+ (the same record the app icon's recent games come from).
        val plays = GameShortcuts.recentPlays(inputs.entries, inputs.profile, ShelvesModel.RECENT_LOOK_BACK)
        // The started file is matched to library rows the way the icon menu's recent games are, on the cached names.
        if (plays.isNotEmpty()) safe {
            val cache = names()
            plays.forEach { play ->
                val matches = GameShortcuts.libraryMatches(play.consoleId, play.fileName, cache.names[play.consoleId].orEmpty()).take(MAX_MATCHES)
                if (matches.isEmpty()) return@forEach
                val rows = fileDao.filesByFileNames(matches).filter { it.consoleId == play.consoleId }
                val row = rows.firstOrNull { owned(it.consoleId, it.fileName) } ?: rows.firstOrNull() ?: return@forEach
                events += ShelvesModel.Recent(ShelvesModel.Game(row.consoleId, row.fileName, row.name, owned(row.consoleId, row.fileName)), play.at, ShelvesModel.RecentKind.PLAYED)
            }
        }
        inputs.plays.take(ShelvesModel.RECENT_LOOK_BACK).forEach {
            events += ShelvesModel.Recent(ShelvesModel.Game(it.consoleId, it.fileName, it.title, owned(it.consoleId, it.fileName)), it.at, ShelvesModel.RecentKind.PLAYED)
        }
        safe { playHistory.downloads() }.orEmpty().take(ShelvesModel.RECENT_LOOK_BACK).forEach {
            events += ShelvesModel.Recent(ShelvesModel.Game(it.consoleId, it.fileName, it.title, owned(it.consoleId, it.fileName)), it.at, ShelvesModel.RecentKind.DOWNLOADED)
        }
        val visible = events.filter { it.game.consoleId !in restrictions.hiddenConsoles }
        val recent = ShelvesModel.recents(visible)
        // Only games the library still lists (the page needs the row), without the profile's hidden tags.
        val rows = recent.map { it.game.fileName }.distinct().chunked(CHUNK).flatMap { fileDao.filesByFileNames(it) }
        val known = rows.groupBy { UpNext.key(it.consoleId, it.fileName) }
        val allowed = allowedIds(rows.map { it.id }, restrictions)
        return recent.mapNotNull { r ->
            val row = known[r.game.key]?.firstOrNull { it.id in allowed } ?: return@mapNotNull null
            r.copy(game = r.game.copy(title = row.name.ifBlank { r.game.title }))
        }
    }

    /** Ids among [ids] without a tag the profile hides. */
    private suspend fun allowedIds(ids: List<Long>, restrictions: LibraryRestrictions): Set<Long> {
        if (restrictions.hiddenTags.isEmpty() || ids.isEmpty()) return ids.toHashSet()
        val tags = ids.chunked(CHUNK).flatMap { fileDao.tagsOfFiles(it) }.groupBy({ it.fileId }, { it.tag })
        return ids.filterTo(HashSet()) { restrictions.allows("", tags[it].orEmpty()) }
    }

    /** "For you" games with a hidden tag stay on the lists but are not shown here (the Up next shelf does the same). */
    private suspend fun withoutHiddenTags(games: List<ShelvesModel.Game>, restrictions: LibraryRestrictions): List<ShelvesModel.Game> {
        if (restrictions.hiddenTags.isEmpty() || games.isEmpty()) return games
        return safe {
            val rows = games.map { it.fileName }.distinct().chunked(CHUNK).flatMap { fileDao.filesByFileNames(it) }
            val allowed = allowedIds(rows.map { it.id }, restrictions)
            val hidden = rows.filter { it.id !in allowed }.mapTo(HashSet()) { UpNext.key(it.consoleId, it.fileName) }
            games.filter { it.key !in hidden }
        } ?: games
    }

    /** One unreadable source must not take the shelves down. */
    private suspend fun <T> safe(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Shelf source skipped: ${e.javaClass.simpleName}")
        null
    }
}
