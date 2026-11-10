package com.tufein.valkyr.ui.screens.tools

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.R
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.data.service.LibraryScanService
import com.tufein.valkyr.util.DuplicateFinder
import com.tufein.valkyr.util.DuplicateGroup
import com.tufein.valkyr.util.GameEntry
import com.tufein.valkyr.util.KeepSuggester
import com.tufein.valkyr.util.KeepSuggestion
import com.tufein.valkyr.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.tufein.valkyr.data.local.VersionPreferenceSettings
import com.tufein.valkyr.data.service.VersionPreferenceService
import com.tufein.valkyr.util.ActionReason

data class DuplicatesUiState(
    val scanning: Boolean = true,
    val folderSet: Boolean = true,
    val filesChecked: Int = 0,
    val groups: List<DuplicateGroup> = emptyList(),
    /** Which copy of each group to keep, by the user's regions and languages; groups without a clear winner are left out. */
    val suggestions: List<KeepSuggestion> = emptyList()
) {
    val reclaimable: Long get() = groups.sumOf { it.reclaimable }
    val suggestedCopies: Int get() = suggestions.sumOf { it.remove.size }
    val suggestedBytes: Long get() = suggestions.sumOf { it.reclaimable }
}

@HiltViewModel
class DuplicatesViewModel @Inject constructor(
    private val scanService: LibraryScanService,
    private val settingsRepository: SettingsRepository,
    private val versionSettings: VersionPreferenceSettings,
    private val versionPreference: VersionPreferenceService
) : ViewModel() {

    private val _uiState = MutableStateFlow(DuplicatesUiState())
    val uiState: StateFlow<DuplicatesUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null

    init { rescan() }

    fun rescan() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _uiState.update { it.copy(scanning = true) }
            val folderSet = settingsRepository.downloadDirectory.first().isNotBlank() ||
                settingsRepository.consoleDownloadDirectories.first().isNotEmpty()
            // A newer rescan cancels this one: stop here instead of publishing an empty result.
            val snapshot = try {
                scanService.scan()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val files = snapshot?.files.orEmpty()
            val groups = DuplicateFinder.find(files)
            _uiState.value = DuplicatesUiState(
                scanning = false,
                folderSet = folderSet,
                filesChecked = files.count { DuplicateFinder.isGameFile(it) },
                groups = groups,
                suggestions = suggest(groups)
            )
        }
    }

    /**
     * Deletes [entry] from disk. The row leaves the list right away (so it cannot be confirmed
     * twice); the delete itself finishes even if the screen is closed meanwhile. If nothing
     * could be removed the list is read again from disk.
     */
    fun delete(context: Context, entry: GameEntry) {
        // Messages are resolved now, with the screen's context (it carries the in-app language);
        // the toasts use the application context because the screen may be gone by then.
        val appContext = context.applicationContext
        val failed = context.getString(R.string.duplicates_delete_failed, entry.baseName)
        val deleted = context.getString(R.string.duplicates_deleted, entry.baseName)
        _uiState.update { state -> state.without(entry) }
        viewModelScope.launch {
            val removed = try {
                scanService.delete(entry, ActionReason.DUPLICATE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                0
            }
            if (removed == 0) {
                ToastUtil.showError(appContext, failed)
                rescan()
                return@launch
            }
            ToastUtil.showSuccess(appContext, deleted)
            _uiState.update { it.copy(filesChecked = (it.filesChecked - removed).coerceAtLeast(0)) }
        }
    }

    private suspend fun suggest(groups: List<DuplicateGroup>): List<KeepSuggestion> {
        val preferences = versionSettings.snapshot()
        val fixed = versionPreference.snapshot()
        return KeepSuggester.suggest(groups, { preferences.of(it.consoleId) }, {
            it.consoleId?.let { console -> fixed.preferred(console, it.baseName) }
        })
    }

    private fun DuplicatesUiState.without(entry: GameEntry): DuplicatesUiState {
        val left = groups.without(entry)
        val keptIds = left.flatMap { g -> g.entries.map { it.id } }.toSet()
        // A suggestion stays valid while its keeper and the copies still listed are there.
        val suggestions = suggestions.mapNotNull { s ->
            val remove = s.remove.filter { it.id in keptIds }
            if (s.keep.id in keptIds && remove.isNotEmpty()) s.copy(remove = remove) else null
        }
        return copy(groups = left, suggestions = suggestions)
    }

    /** Deletes every copy the suggestions mark for removal; one toast with the result, then the list is read again. */
    fun removeSuggested(context: Context) {
        val app = context.applicationContext
        val targets = _uiState.value.suggestions.flatMap { it.remove }
        if (targets.isEmpty()) return
        _uiState.update { state -> targets.fold(state) { acc, entry -> acc.without(entry) } }
        viewModelScope.launch {
            var removed = 0
            targets.forEach { entry ->
                try { if (scanService.delete(entry, ActionReason.DUPLICATE) > 0) removed++ } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
            ToastUtil.showSuccess(app, app.resources.getQuantityString(R.plurals.duplicates_suggest_done, removed, removed))
            rescan()
        }
    }

    /** The groups without [entry]; a group left with a single copy is no longer a duplicate. */
    private fun List<DuplicateGroup>.without(entry: GameEntry): List<DuplicateGroup> = mapNotNull { group ->
        val left = group.entries.filterNot { it.id == entry.id }
        if (left.size < 2) null else group.copy(entries = left)
    }
}
