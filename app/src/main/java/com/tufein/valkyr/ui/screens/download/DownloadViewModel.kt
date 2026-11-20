package com.tufein.valkyr.ui.screens.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.data.model.DownloadSourceChoices
import com.tufein.valkyr.data.model.SourceChangeResult
import com.tufein.valkyr.data.model.DownloadItemModel
import com.tufein.valkyr.data.model.DownloadProvenance
import com.tufein.valkyr.data.model.DownloadStatus
import com.tufein.valkyr.data.model.DownloadableFileWithTags
import com.tufein.valkyr.data.repository.DownloadRepository
import com.tufein.valkyr.data.repository.DownloadableFileRepository
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.data.service.RommUploadService
import com.tufein.valkyr.data.service.DownloadService
import com.tufein.valkyr.data.service.LibraryIndexService
import com.tufein.valkyr.util.DownloadCondition
import com.tufein.valkyr.util.DownloadQueueFilter
import com.tufein.valkyr.util.QueueFilter
import com.tufein.valkyr.util.QueueActions
import com.tufein.valkyr.util.WaitInfo
import com.tufein.valkyr.util.QueueEta
import com.tufein.valkyr.util.QueueProgress
import com.tufein.valkyr.util.StorageInsights
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import com.tufein.valkyr.util.ToastUtil
import com.tufein.valkyr.util.VerifyState
import com.tufein.valkyr.util.WaitReason
import android.content.Context
import com.tufein.valkyr.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import com.tufein.valkyr.data.service.UploadState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.stateIn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Statuses each action accepts, shared by the row buttons and the multi-selection bar. */
internal val DownloadStatus.canRetry: Boolean
    get() = this == DownloadStatus.COMPLETED || this == DownloadStatus.STOPPED ||
        this == DownloadStatus.FAILED || this == DownloadStatus.PAUSED

internal val DownloadStatus.canStop: Boolean
    get() = this == DownloadStatus.QUEUED || this == DownloadStatus.DOWNLOADING ||
        this == DownloadStatus.UNZIPPING

/** Same set as [canRetry]: a running download is stopped first, never deleted outright. */
internal val DownloadStatus.canDelete: Boolean get() = canRetry

/** Download names looked up in the library per batch (see [DownloadViewModel.downloadDetails]). */
private const val DETAILS_BATCH = 400

data class SourceChoiceUi(
    val fileName: String,
    val choices: DownloadSourceChoices? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val errorRes: Int? = null
)

@HiltViewModel
class DownloadViewModel @Inject constructor(
    private val repository: DownloadRepository,
    private val fileRepository: DownloadableFileRepository,
    private val rommUploadService: RommUploadService,
    private val downloadService: DownloadService,
    libraryIndexService: LibraryIndexService,
    settingsRepository: SettingsRepository
) : ViewModel() {

    /** Downloads held back by the schedule (Wi-Fi / charger / night) and why. */
    val waitingFiles: StateFlow<Set<String>> = downloadService.waitingFiles
    val waitingReasons: StateFlow<List<WaitReason>> = downloadService.gate.waiting
    /** Per-download conditions ("Download when...") that are not met yet, by file name. */
    val itemWaits: StateFlow<Map<String, WaitInfo>> = downloadService.itemWaits
    /** Every per-download condition that is set, by file name (to preselect it in the dialog). */
    val itemConditions: StateFlow<Map<String, DownloadCondition>> = downloadService.itemConditions

    /** Sets the condition of downloads that have not started; null lifts it and lets them start now. Off the UI thread. */
    fun setCondition(fileNames: List<String>, condition: DownloadCondition?) {
        viewModelScope.launch(Dispatchers.Default) { downloadService.setCondition(fileNames, condition) }
    }

    /** Checksum check per finished download. */
    val verification: StateFlow<Map<String, VerifyState>> = downloadService.verification
    /** 7.5: file name -> source a failed download moved to by itself ("Switched to <source>"). */
    val switchedSources: StateFlow<Map<String, String>> = downloadService.switchedSources
    /** Sources tracker: file name -> where this download came from. */
    val provenance: StateFlow<Map<String, DownloadProvenance>> = downloadService.provenance

    val pendingAutoRetries = downloadService.pendingAutoRetries

    fun retryNow(fileName: String) {
        viewModelScope.launch(Dispatchers.Default) { downloadService.retryNow(fileName) }
    }

    fun cancelAutomaticRetry(fileName: String) {
        viewModelScope.launch(Dispatchers.Default) { downloadService.cancelAutomaticRetry(fileName) }
    }

    private val _sourceChoice = MutableStateFlow<SourceChoiceUi?>(null)
    val sourceChoice: StateFlow<SourceChoiceUi?> = _sourceChoice.asStateFlow()
    private var sourceRequest = 0L

    fun dismissSourceChoice() {
        if (_sourceChoice.value?.busy == true) return
        sourceRequest++
        _sourceChoice.value = null
    }

    fun chooseSource(fileName: String) {
        if (_sourceChoice.value?.busy == true) return
        val request = ++sourceRequest
        _sourceChoice.value = SourceChoiceUi(fileName, loading = true)
        viewModelScope.launch {
            try {
                val choices = withContext(Dispatchers.IO) { downloadService.sourceChoices(fileName) }
                if (sourceRequest == request) _sourceChoice.value = SourceChoiceUi(
                    fileName, choices, errorRes = R.string.source26_unavailable.takeIf { choices == null }
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (sourceRequest == request) _sourceChoice.value = SourceChoiceUi(fileName, errorRes = R.string.source26_error)
            }
        }
    }

    fun confirmSource(optionId: String) {
        val state = _sourceChoice.value ?: return
        val choices = state.choices ?: return
        if (state.busy || state.loading) return
        val request = sourceRequest
        _sourceChoice.value = state.copy(busy = true, errorRes = null)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { downloadService.changeSource(choices, optionId) }
                if (request != sourceRequest) return@launch
                val message = when (result) {
                    SourceChangeResult.STARTED -> null
                    SourceChangeResult.STALE -> R.string.source26_stale
                    SourceChangeResult.UNAVAILABLE -> R.string.source26_unavailable
                    SourceChangeResult.RESTRICTED -> R.string.source26_restricted
                }
                _sourceChoice.value = if (result == SourceChangeResult.STARTED) null else state.copy(errorRes = message)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (request == sourceRequest) _sourceChoice.value = state.copy(errorRes = R.string.source26_error)
            }
        }
    }

    /** Lets everything that is waiting for the schedule start now. */
    fun startWaitingNow() = downloadService.gate.startNow()

    /** The user's hold on the queue: running downloads finish, nothing new starts. */
    val held: StateFlow<Boolean> = downloadService.gate.held
    fun setHeld(on: Boolean) = downloadService.gate.setHeld(on)

    /** What is left of the queue and how long it takes at the current speed. */
    val queueEta: StateFlow<QueueEta.Eta> = downloadService.downloads.map { list ->
        QueueEta.ofDownloads(list)
    }.distinctUntilChanged().flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), QueueEta.Eta(0, 0, null))

    /** Downloads waiting for a free slot, in the order they will start. */
    val queued: StateFlow<List<String>> = downloadService.queued
    /** Indexed off the UI thread: each visible row can read its place without scanning a bulk queue. */
    val queuePositions: StateFlow<Map<String, Int>> = queued.map { names ->
        names.mapIndexed { index, name -> name to index + 1 }.toMap()
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    fun moveUp(fileName: String) = downloadService.moveUp(fileName)
    fun moveDown(fileName: String) = downloadService.moveDown(fileName)
    fun moveToFront(fileName: String) = downloadService.moveToFront(fileName)

    /** What the downloads still in the queue need on disk: bytes left, plus room to unpack archives. */
    val queueNeed: StateFlow<StorageInsights.QueueNeed> = downloadService.downloads.map { list ->
        StorageInsights.queueNeed(
            list.filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED }
                .map { StorageInsights.QueueItem((it.fileSize - it.downloadedBytes).coerceAtLeast(0), StorageInsights.isExtractable(it.fileName.substringAfterLast('.', ""))) }
        )
    }.distinctUntilChanged().flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StorageInsights.QueueNeed(0, 0))

    /** Free bytes where the downloads land; null while unknown. */
    val freeBytes: StateFlow<Long?> = libraryIndexService.freeBytes

    /** Bytes the queue is short of the free space; 0 when it fits or the space is unknown. */
    val queueShortfall: StateFlow<Long> = combine(queueNeed, libraryIndexService.freeBytes) { need, free ->
        StorageInsights.shortfall(need, free) ?: 0L
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    /** Completed downloads out of the whole list (the header ring), with counts for each state. */
    val queueProgress: StateFlow<QueueProgress.Summary> = downloadService.downloads.map { QueueProgress.of(it) }
        .distinctUntilChanged().flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), QueueProgress.Summary())

    /** Opens a finished download in whichever app handles the file. */
    fun openDownload(context: Context, fileName: String) {
        viewModelScope.launch {
            val intent = downloadService.openIntentFor(fileName)
            val ok = intent != null && runCatching { context.startActivity(intent) }.isSuccess
            if (!ok) ToastUtil.showInfo(context, context.getString(R.string.download_open_none))
        }
    }

    /** Name of the debrid service shown on QUEUED rows ("TorBox 40%"). */
    val debridLabel: StateFlow<String> = settingsRepository.debridProvider.map { it.label }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val downloads: StateFlow<List<DownloadItemModel>> = repository.downloads

    private val _queueFilters = MutableStateFlow(DownloadQueueFilter.Criteria())
    val queueFilters: StateFlow<DownloadQueueFilter.Criteria> = _queueFilters.asStateFlow()

    /** Slot waiters and schedule waiters share DOWNLOADING with actual transfers. */
    private val queueWaiting = combine(queued, waitingFiles, itemWaits) { slots, schedule, conditions ->
        HashSet<String>(slots.size + schedule.size + conditions.size).apply {
            addAll(slots)
            addAll(schedule)
            addAll(conditions.keys)
        }
    }

    /** Search and state projection stay off Main even for a whole console's download history. */
    val queueView: StateFlow<DownloadQueueFilter.View> = combine(downloads, _queueFilters, queueWaiting) { list, criteria, waiting ->
        DownloadQueueFilter.project(list, criteria, waiting)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DownloadQueueFilter.View())

    fun setQueueSearch(query: String) = setQueueFilters(_queueFilters.value.copy(query = query.take(200)))
    fun setQueueFilter(status: QueueFilter) = setQueueFilters(_queueFilters.value.copy(status = status))
    fun clearQueueFilters() = setQueueFilters(DownloadQueueFilter.Criteria())

    private fun setQueueFilters(criteria: DownloadQueueFilter.Criteria) {
        if (criteria == _queueFilters.value) return
        // A filter change must never leave a hidden row selected for a destructive action.
        clearSelection()
        _queueFilters.value = criteria
    }

    /** Queued or downloading, for the tab badge: changes with the status, not with every progress tick. */
    val activeCount: StateFlow<Int> = downloads.map { list -> list.count { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED } }
        .distinctUntilChanged().flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)


    // The whole-queue actions touch hundreds of rows: off the UI thread, so a big queue never freezes the screen.
    fun stopAll() {
        viewModelScope.launch(Dispatchers.Default) {
            pendingAutoRetries.value.keys.toList().forEach(downloadService::cancelAutomaticRetry)
            QueueActions.stoppable(downloads.value).forEach { repository.cancelDownload(it) }
        }
    }

    /** Parks every download that is transferring or waiting for a slot (the service skips what cannot pause); Resume all continues them. */
    fun pauseAll() {
        viewModelScope.launch(Dispatchers.Default) { QueueActions.pausable(downloads.value) { d -> downloadDetails.value[d.fileName]?.let { QueueActions.canPause(d.status, it.file.isTorrent) } == true }.forEach { downloadService.pauseDownload(it) } }
    }

    /** Continues every parked download as one batch, from what is already on disk. */
    fun resumeAll() {
        viewModelScope.launch(Dispatchers.Default) { downloadService.retryDownloads(QueueActions.resumable(downloads.value)) }
    }

    /** Failed and stopped downloads go back in the queue as one batch (they continue from their partial file when they can). */
    fun retryFailed() {
        viewModelScope.launch(Dispatchers.Default) { downloadService.retryDownloads(QueueActions.retryable(downloads.value)) }
    }

    /** Removes the completed rows from the list; the files stay where they are. */
    fun clearFinished() {
        viewModelScope.launch(Dispatchers.Default) { QueueActions.clearable(downloads.value).forEach { repository.deleteDownload(it, false) } }
    }

    /** RomM upload state per download (see [RommUploadService]). */
    val uploads: StateFlow<Map<String, UploadState>> = rommUploadService.uploads

    fun retryUpload(fileName: String) = rommUploadService.retry(fileName)

    /** Looked-up details per file name; names the library does not know are in [detailsMissing]. */
    private val detailsCache = ConcurrentHashMap<String, DownloadableFileWithTags>()
    private val detailsMissing = ConcurrentHashMap.newKeySet<String>()

    /**
     * Indexed file + tags for each download, keyed by fileName, so the list can show what each one is.
     * New names are looked up a few hundred per query, off the UI thread, and the list fills in per
     * batch. One query per name (each a scan of the whole library table) after queueing a whole
     * console kept the database and the UI thread busy for minutes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val downloadDetails: StateFlow<Map<String, DownloadableFileWithTags>> = downloads
        .map { list -> list.map { it.fileName } }
        .distinctUntilChanged()
        .transformLatest { names ->
            fun known() = names.mapNotNull { name -> detailsCache[name]?.let { name to it } }.toMap()
            val unknown = names.filter { !detailsCache.containsKey(it) && it !in detailsMissing }
            emit(known())
            for (chunk in unknown.chunked(DETAILS_BATCH)) {
                val found = fileRepository.findByFileNames(chunk) { downloadService.entityFor(it)?.consoleId }
                chunk.forEach { name -> found[name]?.let { detailsCache[name] = it } ?: detailsMissing.add(name) }
                emit(known())
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** What the whole-queue buttons would act on. */
    val queueCounts: StateFlow<QueueActions.Counts> = combine(downloads, pendingAutoRetries, downloadDetails) { list, pending, details ->
        QueueActions.counts(list) { d -> details[d.fileName]?.let { QueueActions.canPause(d.status, it.file.isTorrent) } == true }.copy(stoppable = (QueueActions.stoppable(list).toSet() + pending.keys).size)
    }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), QueueActions.Counts())

    /** File names ticked for a bulk action; empty = no selection mode. */
    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    val selectedDownloads: StateFlow<List<DownloadItemModel>> = combine(queueView, _selection) { view, selected ->
        view.rows.filter { it.fileName in selected }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Deleted rows and rows that leave the current view must not linger in the selection.
        viewModelScope.launch {
            queueView.map { it.visibleNames }
                .distinctUntilChanged()
                .flowOn(Dispatchers.Default)
                .collect { alive ->
                    if (_selection.value.isNotEmpty()) {
                        _selection.update { it.intersect(alive) }
                    }
                }
        }
    }

    fun toggleSelection(fileName: String) {
        if (queueView.value.criteria != _queueFilters.value || fileName !in queueView.value.visibleNames) return
        _selection.value = _selection.value.let { if (fileName in it) it - fileName else it + fileName }
    }

    /** Y on the list: tick the shown matches, or clear them when they are already ticked. */
    fun toggleSelectAll() {
        val view = queueView.value
        if (view.criteria != _queueFilters.value) return
        _selection.value = DownloadQueueFilter.toggleVisibleSelection(_selection.value, view.visibleNames)
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    private fun visibleSelection(): Set<String> = queueView.value.let { view ->
        if (view.criteria == _queueFilters.value) _selection.value.intersect(view.visibleNames) else emptySet()
    }

    private fun selectedItems(): List<DownloadItemModel> {
        val selected = visibleSelection()
        return downloads.value.filter { it.fileName in selected }
    }

    /** Move the selected slot waiters in one operation, keeping their actual queue order. */
    fun moveSelectedToFront() {
        val selected = visibleSelection()
        viewModelScope.launch(Dispatchers.Default) {
            downloadService.moveToFront(queued.value.filter { it in selected })
        }
    }

    fun retrySelected() {
        val selected = visibleSelection()
        viewModelScope.launch(Dispatchers.Default) {
            val names = downloads.value.filter { it.fileName in selected && it.status.canRetry }.map { it.fileName }
            downloadService.retryDownloads(names)
        }
    }

    fun stopSelected() = runOnSelection({ it.status.canStop || it.fileName in pendingAutoRetries.value }) {
        if (it.status.canStop) repository.cancelDownload(it.fileName)
        else downloadService.cancelAutomaticRetry(it.fileName)
    }

    /** Torrents while they transfer, web downloads also while queued (they keep their partial file). */
    fun pauseSelected() = runOnSelection({ item ->
        downloadDetails.value[item.fileName]?.let { QueueActions.canPause(item.status, it.file.isTorrent) } == true
    }) { repository.pauseDownload(it.fileName) }

    /**
     * Runs [action] over the ticked rows that [accepts] takes. The selection stays: the rows are
     * still there, their new status is visible, and the bar keeps the focus for a follow-up action.
     */
    private fun runOnSelection(
        accepts: (DownloadItemModel) -> Boolean,
        action: suspend (DownloadItemModel) -> Unit
    ) {
        val selected = visibleSelection()
        viewModelScope.launch(Dispatchers.Default) {
            downloads.value.filter { it.fileName in selected && accepts(it) }.forEach { action(it) }
        }
    }

    /** Deleting the selection asks about the files only when some of them finished. */
    fun deleteSelected() {
        val targets = selectedItems().filter { it.status.canDelete }
        if (targets.isEmpty()) return
        if (targets.any { it.status == DownloadStatus.COMPLETED }) {
            _showDeleteConfirmation.value = targets.map { it.fileName }
        } else {
            clearSelection()
            deleteDownloads(targets.map { it.fileName }, deleteFile = false)
        }
    }

    /** File names awaiting the "delete the file too?" answer (one row, or a whole selection). */
    private val _showDeleteConfirmation = MutableStateFlow<List<String>?>(null)
    val showDeleteConfirmation: StateFlow<List<String>?> = _showDeleteConfirmation.asStateFlow()

    fun cancelDownload(fileName: String) {
        viewModelScope.launch(Dispatchers.Default) {
            repository.cancelDownload(fileName)
        }
    }

    fun pauseDownload(fileName: String) {
        viewModelScope.launch(Dispatchers.Default) { repository.pauseDownload(fileName) }
    }

    fun retryDownload(fileName: String) {
        viewModelScope.launch(Dispatchers.Default) {
            repository.retryDownload(fileName) 
        }
    }

    fun deleteDownload(fileName: String, deleteFile: Boolean = false) {
        deleteDownloads(listOf(fileName), deleteFile)
    }

    private fun deleteDownloads(fileNames: List<String>, deleteFile: Boolean) {
        viewModelScope.launch(Dispatchers.Default) {
            fileNames.forEach { repository.deleteDownload(it, deleteFile) }
        }
    }

    fun deleteDownloadWithConfirmation(fileName: String, isCompleted: Boolean) {
        if (isCompleted) {
            _showDeleteConfirmation.value = listOf(fileName)
        } else {
            deleteDownload(fileName, deleteFile = false)
        }
    }
    
    fun confirmDeleteKeepFile(fileNames: List<String>) {
        _showDeleteConfirmation.value = null
        clearSelection()
        deleteDownloads(fileNames, deleteFile = false)
    }
    
    fun confirmDeleteRemoveFile(fileNames: List<String>) {
        _showDeleteConfirmation.value = null
        clearSelection()
        deleteDownloads(fileNames, deleteFile = true)
    }
    
    fun cancelDeleteConfirmation() {
        _showDeleteConfirmation.value = null
    }
}
