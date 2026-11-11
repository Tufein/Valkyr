package com.tufein.valkyr.ui.screens.home

import android.content.Context
import com.tufein.valkyr.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.BuildConfig
import com.tufein.valkyr.data.local.LibrarySettings
import com.tufein.valkyr.data.local.LookSettings
import com.tufein.valkyr.util.ConsoleSortMemory
import com.tufein.valkyr.util.LibraryBrowse
import com.tufein.valkyr.util.VariantGroups
import com.tufein.valkyr.util.VersionPicker
import com.tufein.valkyr.util.VersionPreference
import kotlinx.coroutines.flow.drop
import com.tufein.valkyr.data.local.dao.GameMetadataDao
import com.tufein.valkyr.util.GameTitleCleaner
import com.tufein.valkyr.util.LibraryDiscovery
import kotlinx.coroutines.delay
import com.tufein.valkyr.data.local.dao.CollectionWithCount
import com.tufein.valkyr.data.local.entity.ConsoleEntity
import com.tufein.valkyr.data.local.dao.ConsoleWithFileCount
import com.tufein.valkyr.data.model.DownloadableFileWithTags
import com.tufein.valkyr.data.model.CategorizedTags
import com.tufein.valkyr.data.model.SortOption
import com.tufein.valkyr.data.model.SourceFilter
import com.tufein.valkyr.data.repository.CollectionsRepository
import com.tufein.valkyr.data.repository.ConsoleRepository
import com.tufein.valkyr.data.repository.DownloadableFileRepository
import com.tufein.valkyr.data.repository.FavouritesRepository
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.data.service.LibraryIndexService
import com.tufein.valkyr.data.service.LibraryToolsService
import com.tufein.valkyr.data.service.ProfileService
import com.tufein.valkyr.data.service.CompletedService
import com.tufein.valkyr.data.service.RaMarks
import com.tufein.valkyr.data.service.RetroAchievementsService
import com.tufein.valkyr.data.service.RommLibraryService
import com.tufein.valkyr.data.repository.WishlistRepository
import com.tufein.valkyr.util.BulkCandidate
import com.tufein.valkyr.util.BulkPlan
import com.tufein.valkyr.util.BulkPlanner
import com.tufein.valkyr.util.SourceRanking
import com.tufein.valkyr.util.FileParsingUtils
import com.tufein.valkyr.util.LibraryView
import com.tufein.valkyr.util.LibraryViews
import com.tufein.valkyr.util.NewGames
import com.tufein.valkyr.util.CompletedFilter
import com.tufein.valkyr.util.CompletedGames
import com.tufein.valkyr.util.RaAwards
import com.tufein.valkyr.util.RaGame
import com.tufein.valkyr.util.RaStatus
import com.tufein.valkyr.util.RaStatusFilter
import com.tufein.valkyr.util.RommMarks
import com.tufein.valkyr.util.RommSwitch
import com.tufein.valkyr.util.RommSource
import com.tufein.valkyr.util.SwitchTitles
import com.tufein.valkyr.data.local.entity.DownloadableFileEntity
import com.tufein.valkyr.data.service.DownloadService
import com.tufein.valkyr.data.service.GameMetadataService
import com.tufein.valkyr.data.model.GameDetails
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import com.tufein.valkyr.data.state.LibraryFilterRequest
import com.tufein.valkyr.data.state.PendingLibraryFilters
import com.tufein.valkyr.util.QuickAction
import com.tufein.valkyr.data.state.RescanStateHolder
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.Constants
import com.tufein.valkyr.util.DeepLinkResolver
import com.tufein.valkyr.util.StorageHelper
import com.tufein.valkyr.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import com.tufein.valkyr.data.local.VersionPreferenceSettings
import com.tufein.valkyr.data.service.RemovalFile
import com.tufein.valkyr.data.service.SourceTrackService
import com.tufein.valkyr.data.service.VersionPreferenceService
import com.tufein.valkyr.util.DownloadCondition
import kotlinx.coroutines.sync.Mutex

/** How long a deep link waits for consoles / tag catalogue before applying what it has. */
private const val RESOLVE_TIMEOUT_MS = 5_000L

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: DownloadableFileRepository,
    private val consoleRepository: ConsoleRepository,
    private val downloadService: DownloadService,
    private val settingsRepository: SettingsRepository,
    private val rescanStateHolder: RescanStateHolder,
    private val libraryIndex: LibraryIndexService,
    private val metadataService: GameMetadataService,
    private val favourites: FavouritesRepository,
    private val pendingFilters: PendingLibraryFilters,
    private val rommLibrary: RommLibraryService,
    private val wishlist: WishlistRepository,
    private val collectionsRepository: CollectionsRepository,
    private val appSettings: AppSettings,
    private val profiles: ProfileService,
    private val versionPreference: VersionPreferenceService,
    private val versionSettings: VersionPreferenceSettings,
    private val retroAchievements: RetroAchievementsService,
    private val libraryTools: LibraryToolsService,
    private val metadataDao: GameMetadataDao,
    lookSettings: LookSettings,
    private val librarySettings: LibrarySettings,
    private val sourceTrack: SourceTrackService,
    private val detailsLoader: GameDetailsLoader,
    private val completed: CompletedService
) : ViewModel() {

    /** 5.0: small covers in front of the games in the list (Settings → Look). */
    val listCovers: StateFlow<Boolean> = lookSettings.listCovers
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** 6.0: tighter rows (Settings → Look → compact lists). */
    val compactLists: StateFlow<Boolean> = lookSettings.compactLists
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ---- 5.1: the Library tab's view and quick filters -------------------------------------------

    /** The view the tab was left in (overview until the user picks one); see [LibraryBrowse]. */
    val viewMode: StateFlow<LibraryBrowse.ViewMode> = librarySettings.view
        // The last view comes from a small SharedPreferences mirror so the first frame is already the right
        // one (starting on the overview would flash the shelves and hand the hero the gamepad focus),
        // without blocking the main thread on DataStore.
        .stateIn(viewModelScope, SharingStarted.Eagerly, librarySettings.cachedView())

    fun setViewMode(mode: LibraryBrowse.ViewMode) {
        viewModelScope.launch { librarySettings.setView(mode) }
    }

    private val _ownership = MutableStateFlow(LibraryBrowse.Ownership.ALL)
    /** Downloaded / not downloaded chips: matched against what is on the device, not the database. */
    val ownership: StateFlow<LibraryBrowse.Ownership> = _ownership.asStateFlow()
    fun setOwnership(value: LibraryBrowse.Ownership) { _ownership.value = value }

    // ---- 5.5: completed flag and RetroAchievements status -------------------------------------------

    private val _completedFilter = MutableStateFlow(CompletedFilter.ALL)
    /** Completed / not completed (the player's own flag, per profile); set from the filter panel only. */
    val completedFilter: StateFlow<CompletedFilter> = _completedFilter.asStateFlow()
    fun setCompletedFilter(value: CompletedFilter) { _completedFilter.value = value }

    private val _raFilter = MutableStateFlow(RaStatusFilter.ALL)
    /** Beaten / Mastered / In progress from the user's RetroAchievements awards. */
    val raFilter: StateFlow<RaStatusFilter> = _raFilter.asStateFlow()
    fun setRaFilter(value: RaStatusFilter) {
        _raFilter.value = value
        // A filter needs the list: asked once per RaStatuses.TTL_MS at most, never per row.
        if (value.isActive) retroAchievements.requestAwards()
    }

    /** `console|file` of the games the active profile marked as completed. */
    val completedKeys: StateFlow<Set<String>> = completed.keys
    /** The user's RA award per game id (empty without an account or before the first fetch). */
    val raAwards: StateFlow<RaAwards> = retroAchievements.awards
    /** True when an RA account is set up; null while the settings are being read. */
    val raAccount: StateFlow<Boolean?> = retroAchievements.accountReady

    /** The RA status of a row: its hash-verified RA game's award. No call is made; null when unknown or untouched. */
    fun raStatusOf(file: DownloadableFileEntity, marks: RaMarks, awards: RaAwards): RaStatus? =
        awards.statusOf(marks.gameFor(file.consoleId, file.fileName)?.id)

    /** What the search needs from the two status filters: it re-runs when the flags or the awards change while one is on. */
    private data class StatusInputs(val completed: CompletedFilter, val ra: RaStatusFilter, val keys: Set<String>, val awards: RaAwards, val marks: RaMarks)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val statusForSearch: Flow<StatusInputs> = combine(_completedFilter, _raFilter) { c, r -> c to r }.flatMapLatest { (c, r) ->
        combine(
            if (c.isActive) completed.keys else flowOf(emptySet()),
            if (r.isActive) retroAchievements.awards else flowOf(RaAwards()),
            if (r.isActive) retroAchievements.marks else flowOf(RaMarks())
        ) { keys, awards, marks -> StatusInputs(c, r, keys, awards, marks) }
    }

    // ---- 6.0: search by feel (genre / decade from the cached game details) ----------------------

    private val _genres = MutableStateFlow<Set<String>>(emptySet())
    val genres: StateFlow<Set<String>> = _genres.asStateFlow()
    private val _decades = MutableStateFlow<Set<Int>>(emptySet())
    val decades: StateFlow<Set<Int>> = _decades.asStateFlow()

    fun setGenres(selection: Set<String>) { _genres.value = selection }
    fun setDecades(selection: Set<Int>) { _decades.value = selection }

    /**
     * What the cached details know (genre / year per title), built off the main thread and rebuilt
     * when the cache grows. Each build is a new object, so a change always re-runs an active filter.
     */
    val discoverIndex: StateFlow<LibraryDiscovery.Index> = metadataDao.observeKnown()
        .map { rows -> LibraryDiscovery.buildIndex(rows.map { LibraryDiscovery.RawRow(it.lookupKey, it.genres, it.released, it.developer) }) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, LibraryDiscovery.Index.EMPTY)

    /** State of "Fetch details for the shown games". */
    sealed interface FetchState {
        object Idle : FetchState
        object NoKeys : FetchState
        data class Running(val done: Int, val total: Int) : FetchState
        data class Done(val found: Int, val total: Int) : FetchState
    }

    private val _fetchState = MutableStateFlow<FetchState>(FetchState.Idle)
    val fetchState: StateFlow<FetchState> = _fetchState.asStateFlow()
    private var fetchJob: Job? = null
    private val triedKeys = HashSet<String>()

    /** Whether any metadata database is configured in this build (RAWG / TheGamesDB keys). */
    private val hasMetadataKeys: Boolean get() = BuildConfig.RAWG_API_KEY.isNotEmpty() || BuildConfig.THEGAMESDB_API_KEY.isNotEmpty()

    /**
     * Opt-in: looks up the details of the games now in the list that are not cached yet, at most
     * [FETCH_CAP] titles, one every [FETCH_GAP_MS] ms (RAWG / TheGamesDB have quotas). Pressing it
     * again while it runs stops it. Nothing runs in the background by itself.
     */
    fun fetchDetailsForShown() {
        if (fetchJob?.isActive == true) { fetchJob?.cancel(); _fetchState.value = FetchState.Idle; return }
        if (!hasMetadataKeys) { _fetchState.value = FetchState.NoKeys; return }
        val shown = _results.value
        fetchJob = viewModelScope.launch {
            val targets = withContext(Dispatchers.Default) {
                LibraryDiscovery.fetchTargets(
                    shown.map { LibraryDiscovery.Target(LibraryDiscovery.lookupKey(it.file.consoleId, it.file.name), it.file.name, it.file.consoleId) },
                    discoverIndex.value.byKey.keys, triedKeys, FETCH_CAP
                )
            }
            if (targets.isEmpty()) { _fetchState.value = FetchState.Done(0, 0); return@launch }
            var found = 0
            targets.forEachIndexed { i, t ->
                _fetchState.value = FetchState.Running(i, targets.size)
                triedKeys.add(t.key)
                val details = runCatching { metadataService.lookup(t.name, t.consoleId) }.getOrNull()
                if (details != null && (details.genres.isNotEmpty() || details.released.isNotBlank())) found++
                if (i < targets.lastIndex) delay(FETCH_GAP_MS)
            }
            _fetchState.value = FetchState.Done(found, targets.size)
        }
    }

    /** Bytes that removing duplicate games (keeping the biggest copy of each) would free; null on failure. */
    suspend fun reclaimableBytes(): Long? = runCatching {
        withContext(Dispatchers.IO) { libraryTools.duplicateGroups().sumOf { it.reclaimable } }
    }.getOrNull()

    /** RetroAchievements marks for the "RA" badge. */
    val raMarks: StateFlow<RaMarks> = retroAchievements.marks

    /** RA game of a row for the details card: (game, true) by hash, (game, false) by title only. */
    suspend fun achievementsFor(file: DownloadableFileEntity) = detailsLoader.achievementsFor(file)

    // ---- 2.5: saved views ("smart collections") ------------------------------------------------

    val views: StateFlow<List<LibraryView>> = appSettings.libraryViews
        .map { LibraryViews.fromJson(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The current filters as a view (no id or name yet). */
    private fun currentView(id: String = "", name: String = "") = LibraryView(
        id = id, name = name, query = _searchQuery.value, consoles = _selectedConsoles.value, tags = _activeTags.value,
        favouritesOnly = _favouritesOnly.value, newOnly = _newOnly.value, collectionId = _collectionId.value,
        source = _source.value.name, sort = _sort.value.name, genres = _genres.value, decades = _decades.value
    )

    /** The saved view whose filters are exactly the current ones, if any. */
    fun matchingViewId(): String? = views.value.firstOrNull { it.copy(id = "", name = "") == currentView() }?.id

    suspend fun saveView(name: String): Boolean {
        val clean = name.trim().take(60)
        if (clean.isEmpty()) return false
        val others = views.value.filterNot { it.name.equals(clean, ignoreCase = true) }
        val view = currentView(UUID.randomUUID().toString(), clean)
        appSettings.setLibraryViews(LibraryViews.toJson(others + view))
        return true
    }

    fun applyView(view: LibraryView) {
        _searchQuery.value = view.query
        _selectedConsoles.value = view.consoles
        _activeTags.value = view.tags
        _favouritesOnly.value = view.favouritesOnly
        _newOnly.value = view.newOnly
        _collectionId.value = view.collectionId
        _source.value = runCatching { SourceFilter.valueOf(view.source) }.getOrDefault(SourceFilter.ALL)
        _sort.value = runCatching { SortOption.valueOf(view.sort) }.getOrDefault(SortOption.NAME_ASC)
        _genres.value = view.genres
        _decades.value = view.decades
    }

    // ---- 2.0: new games, collections, bulk download, Switch updates / DLC ----------------------

    private val _newOnly = MutableStateFlow(false)
    /** Only what rescans found in the last [com.tufein.valkyr.util.NewGames.DAYS] days. */
    val newOnly: StateFlow<Boolean> = _newOnly.asStateFlow()
    fun setNewOnly(on: Boolean) { _newOnly.value = on }

    private val _collectionId = MutableStateFlow(0L)
    /** The collection shown; 0 = all games. */
    val collectionId: StateFlow<Long> = _collectionId.asStateFlow()
    fun setCollection(id: Long) { _collectionId.value = id }

    val collections: StateFlow<List<CollectionWithCount>> = collectionsRepository.collections
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Puts the game of the open details card in or out of collection [id]; returns whether it is in it now. */
    suspend fun toggleCollection(item: DownloadableFileWithTags, id: Long): Boolean {
        val inIt = collectionsRepository.toggle(id, item.file)
        _details.value?.takeIf { it.item == item }?.let { _details.value = it.copy(collectionIds = collectionsRepository.collectionsOf(item.file)) }
        return inIt
    }

    /** Creates a collection and puts [item] in it. */
    suspend fun createCollectionWith(item: DownloadableFileWithTags, name: String): Boolean {
        val id = collectionsRepository.create(name) ?: return false
        if (id !in collectionsRepository.collectionsOf(item.file)) toggleCollection(item, id)
        return true
    }

    fun isNew(file: DownloadableFileEntity): Boolean = NewGames.isNew(file.firstSeenAt, System.currentTimeMillis())

    /** What "Download all" would queue for the current filters ([bestOnly]: one version per game). */
    suspend fun planBulk(bestOnly: Boolean): BulkPlan {
        val params = currentParams()
        // Query-result mapping, preference keys and source ranking all scale with the whole console.
        val (plan, byId) = withContext(Dispatchers.Default) {
            val rows = fetchFiltered(params, 0, BulkPlanner.MAX_FILES * 4).rows
            val owned = ownedKeys.value
            val active = activeDownloads.value
            val whitespace = Regex("\\s+")
            // 7.5: the same file from several sources is queued from the best one.
            val pickBest = sourceTrack.enabled.first()
            val records = sourceTrack.records.value
            val orders = if (pickBest) rows.map { it.file.consoleId }.distinct().associateWith { sourceTrack.sourceOrder(it) } else emptyMap()
            val now = System.currentTimeMillis()
            val preferences = versionPreference.snapshot()
            val versionPrefs = versionSettings.snapshot()
            val pinned = rows.associate { it.file.id to preferences.preferred(it.file.consoleId, it.file.fileName) }
            BulkPlanner.plan(
                rows.map {
                    BulkCandidate(
                        // The cleaned title itself (tags are already stripped from it): the search key folds
                        // repeated characters, so "Game 001" and "Game 011" would count as one game.
                        it.file.id, it.file.consoleId, it.file.name.lowercase().replace(whitespace, " ").trim(),
                        it.file.fileName, it.file.fileSize, it.tags, isOwned(it.file, owned), isDownloading(it.file, active),
                        it.file.sourceUrl
                    )
                },
                bestOnly, { versionPrefs.of(it) }, libraryIndex.freeBytes.value,
                pickSource = { group ->
                    if (!pickBest) group.first()
                    else SourceRanking.best(group, { it.sourceUrl }, records, orders[group.first().consoleId].orEmpty(), now) ?: group.first()
                },
                preferredVersion = { pinned[it.id] }
            ) to rows.associateBy { it.file.id }
        }
        // A cancelled older plan must not replace the rows belonging to the visible dialog.
        lastBulkRows = byId
        return plan
    }

    private var lastBulkRows: Map<Long, DownloadableFileWithTags> = emptyMap()

    /** Queues every game of [plan]; returns how many. */
    suspend fun startBulk(plan: BulkPlan, context: Context): Int {
        val rows = plan.chosen.mapNotNull { lastBulkRows[it.id] }
        if (rows.isEmpty()) return 0
        val downloadDirectory = settingsRepository.downloadDirectory.first()
        val valid = withContext(Dispatchers.IO) { StorageHelper.isValidUri(context, downloadDirectory) }
        if (!valid) {
            ToastUtil.showError(context, context.getString(R.string.error_download_dir_missing))
            return 0
        }
        withContext(Dispatchers.Default) { downloadService.startDownloads(rows.map { it.file }) }
        return rows.size
    }

    /** Starts the download of a library row found by the Switch section (an update or a DLC). */
    /** 6.0: queues [file] with a condition (Wi-Fi, charging, tonight, at a time); null = right away. */
    fun downloadWhen(file: DownloadableFileEntity, condition: DownloadCondition?) {
        viewModelScope.launch(Dispatchers.Default) { downloadService.startDownload(sourceTrack.pickBest(file, sameNameOnly = true), condition) }
    }

    fun downloadRow(file: DownloadableFileEntity) = downloadRows(listOf(file))

    /** Switch updates and DLC from the details sheet are enqueued as one batch too. */
    fun downloadRows(files: List<DownloadableFileEntity>) {
        viewModelScope.launch(Dispatchers.Default) {
            val chosen = files.map { sourceTrack.pickBest(it, sameNameOnly = true) }
            downloadService.startDownloads(chosen)
        }
    }

    private fun newSince(): Long = if (_newOnly.value) NewGames.since(System.currentTimeMillis()) else 0L

    /** `consoleId|name` keys of the games the RomM server has (empty when marking is off). */
    val rommKeys: StateFlow<Set<String>> = rommLibrary.keys
    /** The RomM server address, so rows that come from it count as "on RomM" too. */
    val rommBase: StateFlow<String> = settingsRepository.rommUrl.map { it.trim().trimEnd('/') }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /**
     * "Use RomM". Starts as on (the default) without blocking the main thread; the stored value arrives
     * within the first frames and re-runs the search (see the filter collector).
     */
    val rommEnabled: StateFlow<Boolean> = settingsRepository.rommEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun isOnRomm(file: DownloadableFileEntity, keys: Set<String>, base: String): Boolean =
        (keys.isNotEmpty() || base.isNotEmpty()) && RommMarks.isOnServer(keys, file.consoleId, file.fileName, file.downloadUrl, base)

    /** Puts [title] on the wishlist; false for a duplicate or a too short title. */
    suspend fun addToWishlist(title: String): Boolean =
        wishlist.add(title, _selectedConsoles.value.singleOrNull())

    /** `consoleId|fileName` keys of starred games; see [isFavourite]. */
    val favouriteKeys: StateFlow<Set<String>> = favourites.keys

    fun isFavourite(file: DownloadableFileEntity, keys: Set<String>): Boolean = favourites.isFavourite(file, keys)

    /** Star / un-star; returns the new state. */
    suspend fun toggleFavourite(item: DownloadableFileWithTags): Boolean = favourites.toggle(item.file)

    private val _favouritesOnly = MutableStateFlow(false)
    val favouritesOnly: StateFlow<Boolean> = _favouritesOnly.asStateFlow()

    fun setFavouritesOnly(only: Boolean) {
        _favouritesOnly.value = only
    }

    private val _source = MutableStateFlow(SourceFilter.ALL)
    val source: StateFlow<SourceFilter> = _source.asStateFlow()

    fun setSource(source: SourceFilter) {
        _source.value = source
    }

    /** 7.0: asks the screen to put the cursor in the search box (the Search shortcut); it keeps the ask until the screen collects it. */
    private val _searchRequests = Channel<Unit>(Channel.CONFLATED)
    val searchRequests: Flow<Unit> = _searchRequests.receiveAsFlow()

    /** 3.5.0: asks the screen to open "Download everything shown" for the filters a request just applied. */
    private val _bulkRequests = Channel<Unit>(Channel.CONFLATED)
    val bulkRequests: Flow<Unit> = _bulkRequests.receiveAsFlow()

    private fun onQuickAction(action: QuickAction) {
        when (action) {
            QuickAction.SEARCH -> _searchRequests.trySend(Unit)
            QuickAction.SURPRISE -> viewModelScope.launch {
                // A cold start: wait for the first list (the shortcut may be what launched the app).
                withTimeoutOrNull(15_000) { _isSearching.first { !it } }
                _results.value.randomOrNull()?.let(::openDetails)
            }
            QuickAction.DOWNLOADS -> Unit   // a section: PendingLibraryFilters hands it to the shell
        }
    }

    /** The game whose details card is open, if any, and what we know about it so far. */
    private val _details = MutableStateFlow<DetailsState?>(null)
    val details: StateFlow<DetailsState?> = _details.asStateFlow()
    private var detailsJob: Job? = null

    fun openDetails(item: DownloadableFileWithTags) {
        detailsJob?.cancel()
        _details.value = DetailsState(item, loading = true)
        detailsJob = viewModelScope.launch {
            // 8.0: the same steps as the game page (GameDetailsLoader); each one lands only while this game is still open.
            detailsLoader.load(item, discoverIndex.value) { change ->
                _details.value?.takeIf { it.item == item }?.let { _details.value = change(it) }
            }
        }
    }

    fun closeDetails() {
        detailsJob?.cancel()
        _details.value = null
    }

    /** Lower-cased names of files already on disk; see [isOwned]. */
    val ownedKeys: StateFlow<Set<String>> = libraryIndex.ownedKeys

    fun isOwned(file: DownloadableFileEntity, keys: Set<String>): Boolean = libraryIndex.isOwned(file, keys)

    /** File names with a download in flight (queued, downloading, copying or extracting). */
    val activeDownloads: StateFlow<Set<String>> = downloadService.downloads
        .map { list -> list.filter { !it.isFinished }.map { it.fileName }.toSet() }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /**
     * 5.0: progress (0..1) of every download in flight, by file name, sampled twice a second for
     * the ring on the row badge. The screen reads it only while drawing that ring, so a progress
     * tick never recomposes the list; [activeDownloads] (which changes rarely) decides which rows
     * show the badge at all.
     */
    @OptIn(FlowPreview::class)
    val downloadProgress: StateFlow<Map<String, Float>> = downloadService.downloads
        .sample(500L)
        .map { list -> list.filter { !it.isFinished }.associate { it.fileName to it.progress.coerceIn(0f, 1f) } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun isDownloading(file: DownloadableFileEntity, active: Set<String>): Boolean = file.fileName in active

    /** Remove an owned game from the download folder. Returns true if something was deleted. */
    suspend fun removalPlan(fileWithTags: DownloadableFileWithTags) = libraryIndex.removalPlan(fileWithTags.file)

    /** [consoleId] and [fileName] (the library row's) let the action history offer Download again. */
    suspend fun removePlan(plan: List<RemovalFile>, title: String, consoleId: String? = null, fileName: String? = null) =
        libraryIndex.deletePlan(plan, title, consoleId, fileName)

    suspend fun deleteOwned(fileWithTags: DownloadableFileWithTags): Boolean =
        libraryIndex.deleteOwned(fileWithTags.file)

    private val _selectedConsoles = MutableStateFlow<Set<String>>(emptySet())
    val selectedConsoles: StateFlow<Set<String>> = _selectedConsoles

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** The last searches, newest first (see [RecentSearches]). */
    val recentSearches: StateFlow<List<String>> = appSettings.recentSearches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun clearRecentSearches() {
        viewModelScope.launch { appSettings.clearRecentSearches() }
    }

    /** A search is remembered once the typing has stopped for a moment. */
    @OptIn(FlowPreview::class)
    private fun rememberSearches() {
        viewModelScope.launch {
            _searchQuery.debounce(1500L).collect { if (it.isNotBlank()) appSettings.addRecentSearch(it) }
        }
    }

    private val _activeTags = MutableStateFlow<Set<String>>(emptySet())
    val activeTags: StateFlow<Set<String>> = _activeTags.asStateFlow()

    private val _sort = MutableStateFlow(SortOption.NAME_ASC)
    val sort: StateFlow<SortOption> = _sort.asStateFlow()

    /** 6.3: the sort order remembered per console (see [ConsoleSortMemory]); filled from settings right after the start. */
    @Volatile private var consoleSorts: Map<String, SortOption> = emptyMap()

    init {
        viewModelScope.launch { librarySettings.migrateToConsoleHome() }
        viewModelScope.launch { librarySettings.consoleSorts.collect { consoleSorts = ConsoleSortMemory.parse(it) } }
    }

    /** With exactly one console filtered, show the order last chosen for it (nothing remembered leaves the order as it is). */
    private fun applyRememberedSort() {
        ConsoleSortMemory.sortFor(consoleSorts, _selectedConsoles.value)?.let { _sort.value = it }
    }

    private val _results = MutableStateFlow<List<DownloadableFileWithTags>>(emptyList())
    val results: StateFlow<List<DownloadableFileWithTags>> = _results

    /** 6.0: the list as shown: [results], with the copies of one game folded into one row while "Group versions" is on. */
    class Listing(val rows: List<DownloadableFileWithTags>, val versions: Map<Long, Int> = emptyMap()) {
        /** How many versions the row stands for (1 = a single game). */
        fun versionsOf(item: DownloadableFileWithTags): Int = versions[item.file.id] ?: 1
    }

    private val _listing = MutableStateFlow(Listing(emptyList()))
    val listing: StateFlow<Listing> = _listing.asStateFlow()

    val groupVersions: StateFlow<Boolean> = librarySettings.groupVersions
        .stateIn(viewModelScope, SharingStarted.Eagerly, VariantGroups.DEFAULT_ON)

    fun setGroupVersions(on: Boolean) {
        viewModelScope.launch { librarySettings.setGroupVersions(on) }
    }

    /**
     * Sets [rows] as the results and builds the list from them in the same step, so the screen never
     * sees results without their list. Grouping reads the preference once for all rows (no query per game).
     */
    private val publishLock = Mutex()
    private var publishSeq = 0L

    private suspend fun publish(rows: List<DownloadableFileWithTags>) {
        // Newest call wins: an older publish that finishes late can no longer overwrite a newer listing.
        val seq = ++publishSeq
        publishLock.withLock {
            if (seq != publishSeq) return
            _results.value = rows
            _listing.value = withContext(Dispatchers.Default) { listingOf(rows, groupVersions.value) }
        }
    }

    private suspend fun listingOf(rows: List<DownloadableFileWithTags>, group: Boolean): Listing {
        if (!group) return Listing(rows)
        val prefs = versionSettings.snapshot()
        val pins = versionPreference.snapshot()
        val entries = VariantGroups.group(
            rows,
            consoleId = { it.file.consoleId }, name = { it.file.name }, searchKey = { it.file.searchKey }, fileName = { it.file.fileName }
        ) { members ->
            val console = members.first().file.consoleId
            val candidates = members.map { VersionPicker.Candidate(it.file.fileName, it.file.fileName, it.tags, it.file.fileSize) }
            val best = VersionPreference.pick(candidates, prefs.of(console), pins.preferred(console, members.first().file.fileName))
            members.firstOrNull { it.file.fileName == best?.id } ?: members.first()
        }
        return Listing(entries.map { it.item }, entries.filter { it.versions > 1 }.associate { it.item.file.id to it.versions })
    }

    private val _consoles = MutableStateFlow<List<ConsoleEntity>>(emptyList())
    val consoles: StateFlow<List<ConsoleEntity>> = _consoles

    private val _consolesWithFiles = MutableStateFlow<List<ConsoleWithFileCount>>(emptyList())
    val consolesWithFiles: StateFlow<List<ConsoleWithFileCount>> = _consolesWithFiles

    private val _categorizedTags = MutableStateFlow<CategorizedTags?>(null)
    val categorizedTags: StateFlow<CategorizedTags?> = _categorizedTags

    /** Language tags listed first in the filter; editable in Settings. */
    val favoriteLanguages: StateFlow<Set<String>> = settingsRepository.favoriteLanguages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val _hasMoreResults = MutableStateFlow(true)
    val hasMoreResults: StateFlow<Boolean> = _hasMoreResults

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore

    /** 5.0: a new search (filters / query changed) is running; the list shows placeholder rows meanwhile. */
    private val _isSearching = MutableStateFlow(true)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    /**
     * 5.0: nothing is indexed at all (no sources yet, or none scanned): the empty list then points
     * to Sources instead of saying that nothing matches the filters.
     */
    private val _libraryEmpty = MutableStateFlow(false)
    val libraryEmpty: StateFlow<Boolean> = _libraryEmpty.asStateFlow()

    private var currentOffset = 0
    /** Rows per search ("Maximum search results" in Settings); [Int.MAX_VALUE] when unlimited. */
    private val pageSize: StateFlow<Int> = settingsRepository.maxSearchResults
        .map { if (it <= 0) Int.MAX_VALUE else it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Constants.DEFAULT_MAX_SEARCH_RESULTS)

    /** How many rows "Load more" adds (the label used to say 100 whatever the setting). */
    val loadMoreSize: StateFlow<Int> = pageSize

    /**
     * The device's owned keys as the search sees them: only while a downloaded / not downloaded chip is
     * on, and settled (a scan reports in chunks; re-running the search on each would jump the list).
     * The first value after the chip goes on is not delayed.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private val ownedForSearch: Flow<Set<String>> = combine(_ownership, rommEnabled) { own, on -> own.isActive || !on }.distinctUntilChanged().flatMapLatest { needed ->
        if (!needed) flowOf(emptySet())
        else {
            var first = true
            libraryIndex.ownedKeys.debounce { if (first) { first = false; 0L } else 500L }
        }
    }

    init {
        rememberSearches()
        // 6.0: the switch re-folds the rows already loaded (a search re-runs on its own when anything else changes).
        viewModelScope.launch { groupVersions.drop(1).collect { publish(_results.value) } }
        // "Use RomM" off: a RomM source filter would list nothing, so it goes back to All.
        viewModelScope.launch { rommEnabled.collect { on -> if (!on && _source.value == SourceFilter.ROMM) _source.value = SourceFilter.ALL } }
        // 5.5: with an RA account the Beaten / Mastered badges need the user's award list: one paged fetch, throttled in the service.
        viewModelScope.launch { retroAchievements.accountReady.collect { if (it == true) retroAchievements.requestAwards() } }
        // Deep links (valkyr://library?…): apply whatever is waiting, now and on every new link.
        viewModelScope.launch {
            pendingFilters.request.collect { if (it != null) pendingFilters.consume()?.let { request -> applyRequest(request) } }
        }
        // 7.0 quick access (launcher shortcuts): Surprise me and Search.
        viewModelScope.launch {
            pendingFilters.quick.collect { if (it != null) pendingFilters.consumeQuick()?.let(::onQuickAction) }
        }
        viewModelScope.launch {
            combine(
                combine(_searchQuery, _selectedConsoles, _activeTags) { q, c, t -> Triple(q, c, t) },
                combine(_sort, _favouritesOnly, _source, rescanStateHolder.lastRescanTime, combine(_newOnly, _collectionId) { n, c -> n to c }) { s, f, src, _, nc -> FilterExtra(s, f, src, nc.first, nc.second) },
                // Re-query when a star changes while "Favourites only" is on, else the row would linger.
                // …and when the active profile changes what is hidden.
                // 5.1: …and when the files on the device change while a downloaded / not downloaded chip is on.
                // 6.x: …and when "Use RomM" changes (RomM-only rows that are not on the device drop out while it is off).
                combine(_favouritesOnly, favourites.keys, profiles.restrictions, ownedForSearch, rommEnabled) { only, keys, r, owned, on ->
                    Triple(if (only) keys else emptySet(), r, owned to on)
                }.distinctUntilChanged(),
                pageSize,
                // 6.0: genre / decade; the index only matters (and re-runs the search) while one is active.
                combine(_genres, _decades, discoverIndex, statusForSearch) { g, d, idx, st -> Quad(g, d, if (g.isNotEmpty() || d.isNotEmpty()) idx else null, st) }
            ) { (query, consoles, tags), extra, _, limit, (genres, decades, _, status) ->
                FilterParams(
                    query = query, consoles = consoles, tags = tags, sort = extra.sort, favouritesOnly = extra.favouritesOnly, source = extra.source, limit = limit,
                    newSince = if (extra.newOnly) NewGames.since(System.currentTimeMillis()) else 0L, collectionId = extra.collectionId,
                    genres = genres, decades = decades, ownership = _ownership.value,
                    completed = status.completed, raStatus = status.ra
                )
            }.collect { params ->
                currentOffset = 0
                _isSearching.value = true
                try {
                    val page = fetchFiltered(params, 0, params.limit)
                    currentOffset = page.nextOffset
                    publish(page.rows)
                    _hasMoreResults.value = !page.exhausted
                    // Before the flag drops: an empty list must already know whether the library is empty.
                    loadConsoles()
                } finally {
                    _isSearching.value = false
                }
                loadAvailableTags(params.query, params.consoles)
            }
        }
    }

    /**
     * Deep-link values are loose (`console=snes`, `region=japan`): resolve them against the
     * console list and the tag catalogue, waiting briefly for both if the link arrived during a
     * cold start. Unknown consoles are dropped; unknown tags are applied verbatim.
     */
    private suspend fun applyRequest(request: LibraryFilterRequest) {
        val consoleIds = if (request.consoles.isEmpty()) emptySet() else {
            val consoles = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { _consoles.first { it.isNotEmpty() } } ?: _consoles.value
            DeepLinkResolver.resolveConsoles(request.consoles, consoles.map { it.id })
        }
        val tags = if (request.tags.isEmpty()) emptySet() else {
            val catalogue = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { _categorizedTags.first { it != null } } ?: _categorizedTags.value
            val known = catalogue?.let { it.regions.tags + it.languages.tags + it.videoStandards.tags + it.contentTypes.tags + it.fileTypes.tags }.orEmpty()
            DeepLinkResolver.resolveTags(request.tags, known)
        }
        _selectedConsoles.value = consoleIds
        _activeTags.value = tags
        _searchQuery.value = request.query.orEmpty()
        request.favouritesOnly?.let { _favouritesOnly.value = it }
        request.newOnly?.let {
            _newOnly.value = it
            if (it) _sort.value = SortOption.NEWEST
        }
        request.collectionId?.let { _collectionId.value = it }
        if (request.openBulk) {
            // A hand-off that counted the whole library (the new-handheld wizard): a source, genre or decade left on Home would shrink the preview.
            _source.value = SourceFilter.ALL
            _genres.value = emptySet()
            _decades.value = emptySet()
            _bulkRequests.trySend(Unit)
        }
    }

    fun toggleConsoleFilter(consoleId: String) {
        val currentConsoles = _selectedConsoles.value.toMutableSet()
        if (currentConsoles.contains(consoleId)) {
            currentConsoles.remove(consoleId)
        } else {
            currentConsoles.add(consoleId)
        }
        _selectedConsoles.value = currentConsoles
        applyRememberedSort()
    }

    fun clearConsoleFilters() {
        _selectedConsoles.value = emptySet()
    }

    fun setSearch(query: String) {
        _searchQuery.value = query
    }

    fun toggleTag(tag: String) {
        val currentTags = _activeTags.value.toMutableSet()
        if (currentTags.contains(tag)) {
            currentTags.remove(tag)
        } else {
            currentTags.add(tag)
        }
        _activeTags.value = currentTags
    }

    fun removeTag(tag: String) {
        _activeTags.value = _activeTags.value - tag
    }

    fun removeConsole(consoleId: String) {
        _selectedConsoles.value = _selectedConsoles.value - consoleId
        applyRememberedSort()
    }

    fun setSort(option: SortOption) {
        _sort.value = option
        val updated = ConsoleSortMemory.remember(consoleSorts, _selectedConsoles.value, option)
        if (updated != consoleSorts) {
            consoleSorts = updated
            viewModelScope.launch { librarySettings.setConsoleSorts(ConsoleSortMemory.encode(updated)) }
        }
    }

    /** Replace whichever of [categoryTags] are active with [selection] (a subset of them). */
    fun setTagsInCategory(categoryTags: Collection<String>, selection: Set<String>) {
        _activeTags.value = (_activeTags.value - categoryTags.toSet()) + selection
    }

    fun setConsoleSelection(consoleIds: Set<String>) {
        _selectedConsoles.value = consoleIds
        applyRememberedSort()
    }

    fun clearAllFilters() {
        _searchQuery.value = ""
        _activeTags.value = emptySet()
        _selectedConsoles.value = emptySet()
        _sort.value = SortOption.NAME_ASC
        _favouritesOnly.value = false
        _source.value = SourceFilter.ALL
        _newOnly.value = false
        _collectionId.value = 0L
        _genres.value = emptySet()
        _decades.value = emptySet()
        _ownership.value = LibraryBrowse.Ownership.ALL
        _completedFilter.value = CompletedFilter.ALL
        _raFilter.value = RaStatusFilter.ALL
    }

    private fun currentParams() = FilterParams(
        query = _searchQuery.value, consoles = _selectedConsoles.value, tags = _activeTags.value, sort = _sort.value,
        favouritesOnly = _favouritesOnly.value, source = _source.value, limit = pageSize.value, newSince = newSince(),
        collectionId = _collectionId.value, genres = _genres.value, decades = _decades.value, ownership = _ownership.value,
        completed = _completedFilter.value, raStatus = _raFilter.value
    )

    /** A page of results: where the next one starts (in database rows) and whether nothing is left. */
    private class Page(val rows: List<DownloadableFileWithTags>, val nextOffset: Int, val exhausted: Boolean)

    /**
     * [want] rows of the filtered list from database row [offset] on. Without a genre / decade
     * filter that is one query. With one, the database filters cannot know the cached details, so
     * rows are read in batches of [SCAN_BATCH] and matched against [discoverIndex] on a background
     * thread until [want] are found, the list ends, or [SCAN_CAP] rows were looked at (the next
     * page then simply continues from there).
     */
    private suspend fun fetchFiltered(params: FilterParams, offset: Int, want: Int): Page {
        val filter = LibraryDiscovery.Filter(params.genres, params.decades)
        suspend fun query(limit: Int, from: Int) = repository.searchFilesWithTags(
            query = params.query, consoleIds = params.consoles, tags = params.tags, favouritesOnly = params.favouritesOnly,
            newSince = params.newSince, collectionId = params.collectionId, source = params.source, sort = params.sort,
            limit = limit, offset = from
        )
        val rommOff = !rommEnabled.value
        val ownedNow = if (params.ownership.isActive || rommOff) libraryIndex.ownedKeys.value else emptySet()
        // 5.5: the completed flag and the RA status are matched here too (from memory, no call per row).
        val statusActive = params.completed.isActive || params.raStatus.isActive
        val completedNow = if (params.completed.isActive) completed.keys.value else emptySet()
        val awardsNow = if (params.raStatus.isActive) retroAchievements.awards.value else RaAwards()
        val marksNow = if (params.raStatus.isActive) retroAchievements.marks.value else RaMarks()
        val statusOk: (DownloadableFileWithTags) -> Boolean = {
            !statusActive || (
                params.completed.matches(CompletedGames.key(it.file.consoleId, it.file.fileName) in completedNow) &&
                    params.raStatus.matches(raStatusOf(it.file, marksNow, awardsNow))
                )
        }
        // "Use RomM" off: a row that only RomM serves is listed while its file is on the device (nothing is purged).
        val ownershipOnly: (DownloadableFileWithTags) -> Boolean = {
            val owned = libraryIndex.isOwned(it.file, ownedNow)
            params.ownership.matches(owned) && RommSwitch.listRow(!rommOff, it.file.isRomm, owned) && statusOk(it)
        }
        if (!filter.isActive && !params.ownership.isActive && !rommOff && !statusActive) {
            val rows = query(want, offset)
            return Page(rows, offset + rows.size, rows.size < want)
        }
        val index = discoverIndex.value
        val out = ArrayList<DownloadableFileWithTags>()
        var next = offset
        var scanned = 0
        var exhausted = false
        while (out.size < want && scanned < SCAN_CAP) {
            val batch = query(SCAN_BATCH, next)
            if (batch.isEmpty()) { exhausted = true; break }
            val room = want - out.size
            // Genre / decade first (it knows how far it read); then what is on the device.
            val matched = withContext(Dispatchers.Default) {
                if (filter.isActive) {
                    val m = LibraryDiscovery.match(batch, index, filter, room) { LibraryDiscovery.lookupKey(it.file.consoleId, it.file.name) }
                    LibraryDiscovery.Matched(m.rows.filter(ownershipOnly), m.consumed)
                } else {
                    // Stop reading at the row that fills the page, so the next page continues right after it.
                    val rows = ArrayList<DownloadableFileWithTags>()
                    var consumed = 0
                    for (row in batch) {
                        if (rows.size >= room) break
                        consumed++
                        if (ownershipOnly(row)) rows.add(row)
                    }
                    LibraryDiscovery.Matched(rows, consumed)
                }
            }
            out.addAll(matched.rows)
            next += matched.consumed
            scanned += matched.consumed
            if (matched.consumed == batch.size && batch.size < SCAN_BATCH) { exhausted = true; break }
        }
        return Page(out, next, exhausted)
    }

    private suspend fun loadConsoles() {
        val allConsoles = consoleRepository.getAllConsoles().first()
        _consoles.value = allConsoles.sortedBy { ConsoleFormatter.getConsoleDisplayName(it.id) }

        val consolesWithFiles = repository.getConsolesWithFiles(
            query = _searchQuery.value.ifBlank { "*" },
            manufacturer = null
        )
        _consolesWithFiles.value = consolesWithFiles.sortedBy { ConsoleFormatter.getConsoleDisplayName(it.id) }
        // The file counts follow the search text, so only an empty search can tell an empty library.
        _libraryEmpty.value = allConsoles.isEmpty() ||
            (consolesWithFiles.isEmpty() && (_searchQuery.value.isBlank() || _libraryEmpty.value))
    }

    private suspend fun loadAvailableTags(query: String, consoleIds: Set<String>) {
        _categorizedTags.value = repository.getCategorizedTags(
            query = query,
            consoleIds = consoleIds
        )
    }

    fun getConsoleName(consoleId: String): String {
        return ConsoleFormatter.formatConsoleField(consoleId)
    }

    suspend fun loadMore() {
        if (_isLoadingMore.value || !_hasMoreResults.value) return

        val limit = pageSize.value
        _isLoadingMore.value = true

        val page = fetchFiltered(currentParams(), currentOffset, limit)
        currentOffset = page.nextOffset
        if (page.rows.isNotEmpty()) publish(_results.value + page.rows)
        _hasMoreResults.value = !page.exhausted

        _isLoadingMore.value = false
    }

    suspend fun startDownload(fileWithTags: DownloadableFileWithTags, context: Context) {
        val downloadDirectory = settingsRepository.downloadDirectory.first()
        if (downloadDirectory.isEmpty()) {
            ToastUtil.showError(context, context.getString(R.string.error_download_dir_missing))
            return
        }

        if (!withContext(Dispatchers.IO) { StorageHelper.isValidUri(context, downloadDirectory) }) {
            ToastUtil.showError(context, context.getString(R.string.error_download_dir_inaccessible))
            return
        }

        // 7.5: the copy of the game from the source with the best track record (same game, version and region).
        withContext(Dispatchers.Default) {
            downloadService.startDownload(sourceTrack.pickBest(fileWithTags.file, sameNameOnly = true))
        }
    }
}

data class FilterParams(
    val query: String,
    val consoles: Set<String>,
    val tags: Set<String>,
    val sort: SortOption,
    val favouritesOnly: Boolean = false,
    val source: SourceFilter = SourceFilter.ALL,
    val limit: Int = Constants.DEFAULT_MAX_SEARCH_RESULTS,
    val newSince: Long = 0L,
    val collectionId: Long = 0L,
    val genres: Set<String> = emptySet(),
    val decades: Set<Int> = emptySet(),
    val ownership: LibraryBrowse.Ownership = LibraryBrowse.Ownership.ALL,
    val completed: CompletedFilter = CompletedFilter.ALL,
    val raStatus: RaStatusFilter = RaStatusFilter.ALL
)

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

private const val SCAN_BATCH = 1500
private const val SCAN_CAP = 60_000
internal const val FETCH_CAP = 40
private const val FETCH_GAP_MS = 800L

private data class FilterExtra(val sort: SortOption, val favouritesOnly: Boolean, val source: SourceFilter, val newOnly: Boolean, val collectionId: Long)

data class DetailsState(
    val item: DownloadableFileWithTags,
    val loading: Boolean,
    val details: GameDetails? = null,
    /** How many versions of this game the library lists (this one included). */
    val versionCount: Int = 1,
    /** The version that suits the user best when it is not simply this one; null otherwise. */
    val best: DownloadableFileWithTags? = null,
    /** Own collections this game is in. */
    val collectionIds: Set<Long> = emptySet(),
    /** For a Switch file with a title ID: what it is, and its game's updates / DLC. */
    val switchTitle: SwitchTitles.Title? = null,
    val switch: SwitchTitles.GameStatus<DownloadableFileEntity>? = null,
    /** RetroAchievements game and whether it was matched by hash (true) or only by title (false). */
    val achievements: Pair<RaGame, Boolean>? = null,
    /** 6.0: "More like this", ranked from local data; empty hides the section. */
    val similar: List<DownloadableFileWithTags> = emptyList(),
    /** 8.0: every version of the game the library lists (this one included), for the game page. */
    val versions: List<DownloadableFileWithTags> = emptyList(),
    /** 8.0: file name of the version that suits the user best (null with one version or no pick). */
    val bestFileName: String? = null
)
