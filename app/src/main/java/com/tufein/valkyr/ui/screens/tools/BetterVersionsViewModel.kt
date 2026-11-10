package com.tufein.valkyr.ui.screens.tools

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.R
import com.tufein.valkyr.data.local.BetterVersionsSettings
import com.tufein.valkyr.data.model.DownloadStatus
import com.tufein.valkyr.data.service.BetterSuggestion
import com.tufein.valkyr.data.service.BetterVersionsService
import com.tufein.valkyr.data.service.DownloadService
import com.tufein.valkyr.data.service.ReplaceMessages
import com.tufein.valkyr.data.service.ReplaceState
import com.tufein.valkyr.util.BetterVersions
import com.tufein.valkyr.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.tufein.valkyr.data.service.ProfileService
import com.tufein.valkyr.data.service.UpgradePreview

/** Where the download of a suggested file stands. */
enum class BetterRowState { READY, DOWNLOADING, DOWNLOADED, FAILED }

/** One suggestion as the list shows it. */
data class BetterRow(
    val suggestion: BetterSuggestion,
    val state: BetterRowState,
    /** The "remove the old one afterwards" of this row, when it was asked for. */
    val replace: ReplaceState?,
    val selected: Boolean
) {
    val id: String get() = suggestion.id

    /** The download button (and selecting for a download) is still of use. */
    val canDownload: Boolean get() = (state == BetterRowState.READY || state == BetterRowState.FAILED) && replace != ReplaceState.WAITING
}

data class BetterUi(
    val scanning: Boolean = true,
    val folderSet: Boolean = true,
    val gamesChecked: Int = 0,
    val rows: List<BetterRow> = emptyList(),
    /** Suggestions the user dismissed earlier (they can be brought back). */
    val ignoredCount: Int = 0
) {
    val selected: List<BetterRow> get() = rows.filter { it.selected && it.canDownload }
}

@HiltViewModel
class BetterVersionsViewModel @Inject constructor(
    private val service: BetterVersionsService,
    private val settings: BetterVersionsSettings,
    downloadService: DownloadService,
    profiles: ProfileService
) : ViewModel() {

    private data class Base(
        val scanning: Boolean = true,
        val folderSet: Boolean = true,
        val checked: Int = 0,
        val suggestions: List<BetterSuggestion> = emptyList(),
        val selected: Set<String> = emptySet(),
        /** File names of the downloads this screen started (an older finished download of the same name says nothing). */
        val started: Set<String> = emptySet()
    )

    private val base = MutableStateFlow(Base())

    val ui: StateFlow<BetterUi> = combine(base, downloadService.downloads, service.replacing, settings.ignored) { b, downloads, replacing, ignored ->
        val status = downloads.associate { it.fileName to it.status }
        val rows = b.suggestions.mapNotNull { s ->
            val replace = replacing[s.id]
            if (replace == ReplaceState.REMOVED) return@mapNotNull null
            val name = s.candidate.fileName
            val state = if (name !in b.started) BetterRowState.READY else when (status[name]) {
                null, DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.COPYING, DownloadStatus.UNZIPPING, DownloadStatus.PAUSED -> BetterRowState.DOWNLOADING
                DownloadStatus.COMPLETED -> BetterRowState.DOWNLOADED
                DownloadStatus.FAILED, DownloadStatus.STOPPED -> BetterRowState.FAILED
            }
            BetterRow(s, state, replace, s.id in b.selected)
        }
        BetterUi(b.scanning, b.folderSet, b.checked, rows, ignored.size)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BetterUi())

    private var scanJob: Job? = null

    val preview = MutableStateFlow<UpgradePreview?>(null)
    private var previewJob: Job? = null
    fun preview(ids: List<String>, replacing: Boolean) {
        previewJob?.cancel()
        preview.value = null
        previewJob = viewModelScope.launch {
            try { preview.value = service.preview(startable(ids), replacing) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { preview.value = UpgradePreview(0, 0, 0, 0, true) }
        }
    }
    init {
        rescan()
        viewModelScope.launch { profiles.activeId.collect { rescan() } }
    }

    fun rescan() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            base.update { it.copy(scanning = true) }
            val scan = try {
                service.find()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            base.update { Base(scanning = false, folderSet = scan?.folderSet ?: it.folderSet, checked = scan?.gamesChecked ?: 0, suggestions = scan?.suggestions.orEmpty()) }
        }
    }

    fun toggle(id: String) {
        base.update { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) }
    }

    fun selectAll() {
        val ids = ui.value.rows.filter { it.canDownload }.map { it.id }.toSet()
        base.update { it.copy(selected = ids) }
    }

    fun clearSelection() {
        base.update { it.copy(selected = emptySet()) }
    }

    /** Downloads the better versions of [ids] and keeps the old files. */
    fun download(context: Context, ids: Collection<String>) {
        val items = startable(ids)
        if (items.isEmpty()) return
        started(items)
        viewModelScope.launch {
            if (!service.preview(items, false).fits) { ToastUtil.showError(context, context.getString(R.string.upg25_blocked)); return@launch }
            withContext(Dispatchers.Default) { service.download(items) }
            ToastUtil.showSuccess(context.applicationContext, context.resources.getQuantityString(R.plurals.upg7_download_started, items.size, items.size))
        }
    }

    /**
     * Downloads the better versions of [ids] and removes each old file once its new file is complete
     * and checked. The screen has asked for confirmation (with the list of files) before this.
     */
    fun downloadAndReplace(context: Context, ids: Collection<String>) {
        val items = startable(ids)
        if (items.isEmpty()) return
        // Resolved now, with the screen's context (it carries the in-app language): the work may outlive the screen.
        val messages = items.associate { s ->
            s.id to ReplaceMessages(
                removed = context.getString(R.string.upg7_removed_old, s.title),
                kept = context.getString(R.string.upg7_kept_old, s.title),
                failed = context.getString(R.string.upg7_remove_failed, s.title)
            )
        }
        started(items)
        viewModelScope.launch {
            if (!service.preview(items, true).fits) { ToastUtil.showError(context, context.getString(R.string.upg25_blocked)); return@launch }
            withContext(Dispatchers.Default) { service.downloadAndReplace(items, messages) }
            ToastUtil.showSuccess(context.applicationContext, context.resources.getQuantityString(R.plurals.upg7_download_started, items.size, items.size))
        }
    }

    /** Hides the suggestion for good (until the ignored ones are brought back). */
    fun ignore(context: Context, id: String) {
        val s = base.value.suggestions.firstOrNull { it.id == id } ?: return
        base.update { it.copy(suggestions = it.suggestions.filterNot { x -> x.id == id }, selected = it.selected - id) }
        viewModelScope.launch { settings.ignore(BetterVersions.ignoreKey(s.consoleId, s.owned.baseName, s.candidateName)) }
        ToastUtil.showInfo(context.applicationContext, context.getString(R.string.upg7_ignored_toast))
    }

    fun resetIgnored() {
        viewModelScope.launch { settings.clear() }
        rescan()
    }

    /** The suggestions of [ids] whose download can be started now. */
    private fun startable(ids: Collection<String>): List<BetterSuggestion> {
        val rows = ui.value.rows.filter { it.id in ids && it.canDownload }
        return rows.map { it.suggestion }
    }

    private fun started(items: List<BetterSuggestion>) {
        base.update { it.copy(started = it.started + items.map { s -> s.candidate.fileName }, selected = it.selected - items.map { s -> s.id }.toSet()) }
    }
}
