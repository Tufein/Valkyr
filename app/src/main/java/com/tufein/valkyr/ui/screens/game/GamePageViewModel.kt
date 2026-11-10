package com.tufein.valkyr.ui.screens.game

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.R
import com.tufein.valkyr.data.local.dao.CollectionWithCount
import com.tufein.valkyr.data.local.entity.DownloadableFileEntity
import com.tufein.valkyr.data.local.VersionPreferenceSettings
import com.tufein.valkyr.data.model.DownloadStatus
import com.tufein.valkyr.data.model.DownloadableFileWithTags
import com.tufein.valkyr.data.repository.CollectionsRepository
import com.tufein.valkyr.data.repository.DownloadableFileRepository
import com.tufein.valkyr.data.repository.FavouritesRepository
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.data.service.DownloadService
import com.tufein.valkyr.data.service.LibraryIndexService
import com.tufein.valkyr.data.service.RetroAchievementsService
import com.tufein.valkyr.data.service.RommLibraryService
import com.tufein.valkyr.data.service.SourceTrackService
import com.tufein.valkyr.ui.screens.home.DetailsState
import com.tufein.valkyr.ui.screens.home.GameDetailsLoader
import com.tufein.valkyr.util.DownloadCondition
import com.tufein.valkyr.util.RommMarks
import com.tufein.valkyr.util.StorageHelper
import com.tufein.valkyr.util.ToastUtil
import com.tufein.valkyr.util.VersionCompare
import com.tufein.valkyr.util.ConsoleOverride
import com.tufein.valkyr.util.VersionPicker
import com.tufein.valkyr.util.VersionPreference
import com.tufein.valkyr.util.VersionPreferences
import com.tufein.valkyr.util.VersionRanking
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import com.tufein.valkyr.data.service.DatService
import com.tufein.valkyr.data.service.GameLaunchService
import com.tufein.valkyr.data.service.ProfileService
import com.tufein.valkyr.data.service.RemovalFile
import com.tufein.valkyr.data.service.VersionPreferenceService

/** Where the page stands: finding the library row, showing it, or the row is gone (re-indexed away). */
enum class GamePagePhase { LOADING, READY, MISSING }

/**
 * 8.0: the full-screen game page. Finds the library row behind `consoleId` / `fileName` and fills a
 * [DetailsState] with the same [GameDetailsLoader] the library's details card uses; the actions
 * (download, favourite, collections, remove) go to the same services as in the library.
 */
@HiltViewModel
class GamePageViewModel @Inject constructor(
    private val repository: DownloadableFileRepository,
    private val loader: GameDetailsLoader,
    private val downloadService: DownloadService,
    private val settingsRepository: SettingsRepository,
    private val libraryIndex: LibraryIndexService,
    private val favourites: FavouritesRepository,
    private val collectionsRepository: CollectionsRepository,
    private val sourceTrack: SourceTrackService,
    rommLibrary: RommLibraryService,
    retroAchievements: RetroAchievementsService,
    val gameLauncher: GameLaunchService,
    private val versionPreference: VersionPreferenceService,
    datService: DatService,
    private val profiles: ProfileService,
    private val versionSettings: VersionPreferenceSettings
) : ViewModel() {

    private val _phase = MutableStateFlow(GamePagePhase.LOADING)
    val phase: StateFlow<GamePagePhase> = _phase.asStateFlow()

    private val _details = MutableStateFlow<DetailsState?>(null)
    val details: StateFlow<DetailsState?> = _details.asStateFlow()

    private var key: Triple<String, String, String>? = null
    private var job: Job? = null

    /** Shows [fileName] of [consoleId]; a repeat call for the same game does nothing (recomposition, rotation). */
    fun load(consoleId: String, fileName: String) {
        val wanted = Triple(consoleId, fileName, profiles.currentIdNow())
        if (key == wanted) return
        key = wanted
        console.value = consoleId
        preferredJob?.cancel()
        preferredJob = viewModelScope.launch { versionPreference.observe(consoleId, fileName).collect { _preferred.value = it } }
        job?.cancel()
        _phase.value = GamePagePhase.LOADING
        _details.value = null
        job = viewModelScope.launch {
            // findByFileNames may fall back to another console's row with the same name: only this console's counts.
            val item = runCatching { repository.findByFileNames(listOf(fileName)) { consoleId }[fileName] }.getOrNull()
                ?.takeIf { it.file.consoleId == consoleId && profiles.current().allows(it.file.consoleId, it.tags) }
            if (item == null) { _phase.value = GamePagePhase.MISSING; return@launch }
            _details.value = DetailsState(item, loading = true)
            _phase.value = GamePagePhase.READY
            loader.load(item, index = null) { change ->
                _details.value?.takeIf { it.item == item }?.let { _details.value = change(it) }
            }
        }
    }

    val ownedKeys: StateFlow<Set<String>> = libraryIndex.ownedKeys
    val activeProfile = profiles.activeId

    /** Live after a profile switch, a console settings change, or a full backup restore. */
    val emulatorChoice: StateFlow<GameEmulatorChoice?> = combine(_details, profiles.activeId, gameLauncher.preferenceRevision) { details, _, _ ->
        details?.item?.file?.let { file ->
            GameEmulatorChoice(gameLauncher.gamePreferred(file.consoleId, file.fileName), gameLauncher.preferred(file.consoleId))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    fun isOwned(file: DownloadableFileEntity, keys: Set<String>): Boolean = libraryIndex.isOwned(file, keys)

    /** File names with a download in flight (queued, downloading, copying or extracting). */
    val activeDownloads: StateFlow<Set<String>> = downloadService.downloads
        .map { list -> list.filter { !it.isFinished }.map { it.fileName }.toSet() }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** Status of every download that is not finished or that failed or is parked, by file name: the action bar's Resume / Retry. */
    val downloadStates: StateFlow<Map<String, DownloadStatus>> = downloadService.downloads
        .map { list -> list.filter { it.status != DownloadStatus.COMPLETED && it.status != DownloadStatus.STOPPED }.associate { it.fileName to it.status } }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Continues a parked download or starts a failed one again (the same call as the Downloads tab's Resume / Retry). */
    fun resumeOrRetry(fileName: String) = downloadService.retryDownload(fileName)

    val favouriteKeys: StateFlow<Set<String>> = favourites.keys
    fun isFavourite(file: DownloadableFileEntity, keys: Set<String>): Boolean = favourites.isFavourite(file, keys)
    suspend fun toggleFavourite(item: DownloadableFileWithTags): Boolean = favourites.toggle(item.file)

    val rommKeys: StateFlow<Set<String>> = rommLibrary.keys
    /** "Use RomM": off hides every RomM mark and section on the page. */
    val rommEnabled: StateFlow<Boolean> = settingsRepository.rommEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    private val rommBase: StateFlow<String> = settingsRepository.rommUrl.map { it.trim().trimEnd('/') }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun isOnRomm(file: DownloadableFileEntity, keys: Set<String>): Boolean {
        val base = rommBase.value
        return (keys.isNotEmpty() || base.isNotEmpty()) && RommMarks.isOnServer(keys, file.consoleId, file.fileName, file.downloadUrl, base)
    }

    /**
     * Whether the Progress tab has something to show: a RetroAchievements account or a RomM server
     * (play status, rating and cloud saves live there). The sections themselves still hide when the
     * game is unknown to them.
     */
    val progressAvailable: StateFlow<Boolean> = combine(retroAchievements.accountReady, rommBase) { ra, romm ->
        ra == true || romm.isNotEmpty()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val collections: StateFlow<List<CollectionWithCount>> = collectionsRepository.collections
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Puts the page's game in or out of collection [id]. */
    suspend fun toggleCollection(item: DownloadableFileWithTags, id: Long): Boolean {
        val inIt = collectionsRepository.toggle(id, item.file)
        _details.value?.takeIf { it.item == item }?.let { _details.value = it.copy(collectionIds = collectionsRepository.collectionsOf(item.file)) }
        return inIt
    }

    suspend fun createCollectionWith(item: DownloadableFileWithTags, name: String): Boolean {
        val id = collectionsRepository.create(name) ?: return false
        if (id !in collectionsRepository.collectionsOf(item.file)) toggleCollection(item, id)
        return true
    }

    /** Queues [file] (from the source with the best track record); false when the download folder is missing. */
    suspend fun download(file: DownloadableFileEntity, context: Context, condition: DownloadCondition? = null): Boolean {
        val directory = settingsRepository.downloadDirectory.first()
        if (directory.isEmpty()) {
            ToastUtil.showError(context, context.getString(R.string.error_download_dir_missing))
            return false
        }
        if (!StorageHelper.isValidUri(context, directory)) {
            ToastUtil.showError(context, context.getString(R.string.error_download_dir_inaccessible))
            return false
        }
        downloadService.startDownload(sourceTrack.pickBest(file, sameNameOnly = true), condition)
        return true
    }

    val datReports = datService.reports
    private val _preferred = MutableStateFlow<String?>(null)
    /** The version fixed for this game (VersionPreferenceService), by file name. */
    val preferred = _preferred.asStateFlow()
    private var preferredJob: Job? = null

    private val console = MutableStateFlow<String?>(null)

    /** The preference for all consoles and this console's own order, live (the pin dialog's base). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val versionPrefs: StateFlow<VersionPrefs?> = console.flatMapLatest { id ->
        if (id == null) flowOf(null)
        else combine(versionSettings.global, versionSettings.overrides) { global, overrides -> VersionPrefs(id, global, overrides[id]) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * The versions ranked by the live preference with the fixed version on top: the Versions tab and
     * the header's "Download best version" both read this, so a pin or a preference change shows at
     * once in both. The pick is [VersionPreference.pick], the same call every other picker makes.
     */
    val versionRanking: StateFlow<VersionRanking<DownloadableFileWithTags>?> = combine(
        _details.map { d -> d?.let { it.item to it.versions } }.distinctUntilChanged(),
        _preferred,
        versionPrefs
    ) { versions, fixed, prefs ->
        if (versions == null || prefs == null) null
        else VersionRanking.of(
            versions.second.ifEmpty { listOf(versions.first) },
            { VersionPicker.Candidate(it.file.fileName, it.file.fileName, it.tags, it.file.fileSize) },
            prefs.effective, fixed
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val prefWrites = Mutex()

    /**
     * "Prefer versions like this": stores what [VersionCompare.planPin] plans from the stored values
     * (the dialog's preview comes from the same function on the same values). Returns what now
     * applies to the chosen scope, null when the page has no console yet.
     */
    suspend fun preferLike(target: VersionCompare.Ranked, ranked: List<VersionCompare.Ranked>, forConsole: Boolean, followRevision: Boolean, clearOverride: Boolean): VersionPreference? {
        val consoleId = console.value ?: return null
        return prefWrites.withLock {
            val override = versionSettings.overrides.first()[consoleId]
            val plan = VersionCompare.planPin(
                target.facts, versionSettings.global.first(), override, forConsole, followRevision,
                VersionCompare.isNewestRevision(target, ranked), clearOverride
            )
            if (!forConsole) versionSettings.pin(plan.global)
            if (plan.override != override) versionSettings.setOverride(consoleId, plan.override)
            plan.shown
        }
    }

    suspend fun removalPlan(item: DownloadableFileWithTags) = libraryIndex.removalPlan(item.file)
    /** [consoleId] and [fileName] (the library row's) let the action history offer Download again. */
    suspend fun remove(plan: List<RemovalFile>, title: String, consoleId: String? = null, fileName: String? = null) =
        libraryIndex.deletePlan(plan, title, consoleId, fileName)
    suspend fun setPreferred(item: DownloadableFileWithTags, pinned: Boolean) {
        versionPreference.set(item.file.consoleId, item.file.fileName, if (pinned) item.file.fileName else null)
        // The ranking follows on its own (see versionRanking).
        _preferred.value = if (pinned) item.file.fileName else null
    }

    /** Removes the game from the download folder (the screen confirms first). */
    suspend fun deleteOwned(item: DownloadableFileWithTags): Boolean = libraryIndex.deleteOwned(item.file)
}

/** The version preference around one game page: for all consoles, and [consoleId]'s own order. */
data class VersionPrefs(val consoleId: String, val global: VersionPreference, val override: ConsoleOverride?) {
    val effective: VersionPreference get() = VersionPreferences.withOverride(global, override)
}

data class GameEmulatorChoice(val gameOverride: String?, val consoleDefault: String?)
