package com.tufein.valkyr.ui.screens.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.data.local.PerfNoticeSettings
import com.tufein.valkyr.data.service.PerfCheckService
import com.tufein.valkyr.data.service.PerfNoticeService
import com.tufein.valkyr.util.PerfNotices
import com.tufein.valkyr.util.PerfCheck
import com.tufein.valkyr.util.PerfCheck.Outcome
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The part of the check that is running now; [FRAMES] is run by the screen (it needs the window and a list). */
enum class PerfStep { DATABASE, COVERS, STORAGE, FRAMES }

data class PerfCheckUi(
    /** Null while idle (before a run, or after Stop). */
    val step: PerfStep? = null,
    /** Results so far, in the order of [PerfCheck.Metric]. */
    val outcomes: List<Outcome> = emptyList(),
    val frames: PerfCheck.FrameSummary? = null,
    val device: PerfCheck.DeviceFacts? = null,
    val games: Int? = null,
    /** The hidden test file's name when it could not be deleted again. */
    val leftover: String? = null,
    /** The player wants the storage speed test in the run (default), with its preview. */
    val includeStorage: Boolean = true,
    /** The preview of the storage test, loaded when the screen opens. */
    val storage: PerfCheckService.StoragePreview? = null,
    /** A run has finished (results below are complete for what was asked). */
    val finished: Boolean = false
) {
    val running: Boolean get() = step != null
}

/**
 * Runs the Performance check one part at a time and keeps the numbers; the frame sample is taken
 * by the screen (a scrolling list and the window's frame metrics) and handed back through
 * [finishFrames]. Nothing is stored beyond this screen's lifetime.
 */
@HiltViewModel
class PerfCheckViewModel @Inject constructor(
    private val service: PerfCheckService,
    private val noticeService: PerfNoticeService,
    private val noticeSettings: PerfNoticeSettings
) : ViewModel() {
    private val _ui = MutableStateFlow(PerfCheckUi())
    val ui: StateFlow<PerfCheckUi> = _ui.asStateFlow()

    private var job: Job? = null

    /** 6.3: the "Performance notices" switch and the notices shown since the app started (newest first). */
    val noticesOn: StateFlow<Boolean> = noticeSettings.enabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, PerfNotices.DEFAULT_ON)
    val noticeHistory: StateFlow<List<PerfNotices.Notice>> = noticeService.history

    fun setNoticesOn(on: Boolean) {
        viewModelScope.launch { noticeSettings.setEnabled(on) }
    }

    init { refreshStorage() }

    fun setIncludeStorage(on: Boolean) = _ui.update { it.copy(includeStorage = on) }

    /** Reads whether the download folder can take the test file (also after the player fixed the folder). */
    fun refreshStorage() {
        viewModelScope.launch { _ui.update { it.copy(storage = service.storagePreview()) } }
    }

    /** Starts a run (the caller showed the preview and the player agreed). Results of an earlier run are cleared. */
    fun start(refreshRate: Float) {
        if (_ui.value.running) return
        val storage = _ui.value.includeStorage
        _ui.update { it.copy(outcomes = emptyList(), frames = null, leftover = null, finished = false, device = service.deviceFacts(refreshRate), step = PerfStep.DATABASE) }
        job = viewModelScope.launch {
            try {
                add(listOfNotNull(service.startup()))
                val games = service.libraryGames()
                _ui.update { it.copy(games = games) }
                add(service.databaseChecks())
                _ui.update { it.copy(step = PerfStep.COVERS) }
                add(listOf(service.coverCheck()))
                add(listOfNotNull(service.freeSpace()))
                if (storage) {
                    _ui.update { it.copy(step = PerfStep.STORAGE) }
                    val run = service.storageCheck()
                    add(run.outcomes)
                    _ui.update { it.copy(leftover = run.leftoverName) }
                }
                // The screen takes over: it scrolls a list and calls finishFrames.
                _ui.update { it.copy(step = PerfStep.FRAMES) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _ui.update { it.copy(step = null, finished = true) }
            }
        }
    }

    /** The frame sample is in (or could not be taken: empty list). */
    fun finishFrames(durationsMs: List<Double>, refreshRate: Float) {
        val summary = if (durationsMs.isEmpty()) null else PerfCheck.summarizeFrames(durationsMs, PerfCheck.frameBudgetMs(refreshRate))
        add(PerfCheck.frameOutcomes(summary))
        _ui.update { it.copy(frames = summary, step = null, finished = true) }
    }

    /** Stops a run in progress; what was measured so far stays. */
    fun stop() {
        job?.cancel()
        _ui.update { it.copy(step = null, finished = it.outcomes.isNotEmpty()) }
    }

    /** The text to share, or null before anything was measured. */
    fun report(): String? {
        val s = _ui.value
        val device = s.device ?: return null
        if (s.outcomes.isEmpty()) return null
        return service.report(device, s.outcomes, s.frames, s.games, s.leftover)
    }

    suspend fun share(text: String, title: String) = service.share(text, title)

    private fun add(more: List<Outcome>) {
        if (more.isEmpty()) return
        _ui.update { state ->
            val replaced = more.map { it.metric }.toSet()
            state.copy(outcomes = (state.outcomes.filterNot { it.metric in replaced } + more).sortedBy { it.metric.ordinal })
        }
    }
}
