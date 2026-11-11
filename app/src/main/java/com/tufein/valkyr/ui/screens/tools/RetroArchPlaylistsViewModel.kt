package com.tufein.valkyr.ui.screens.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.data.local.RetroArchPlaylistsSettings
import com.tufein.valkyr.data.service.PlaylistsPreview
import com.tufein.valkyr.data.service.PlaylistsRunState
import com.tufein.valkyr.data.service.ProfileService
import com.tufein.valkyr.data.service.RetroArchPlaylistsService
import com.tufein.valkyr.util.RetroArchPlaylists
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The route of the RetroArch playlists screen (NavRoutes, Tools, search). */
const val RETROARCH_PLAYLISTS_ROUTE = "retroarch_playlists"

data class RetroArchPlaylistsUi(
    val settings: RetroArchPlaylistsSettings.Values? = null,
    val previewLoading: Boolean = true,
    val previewFailed: Boolean = false,
    /** Null while it is worked out or when it failed. */
    val preview: PlaylistsPreview? = null,
    val run: PlaylistsRunState = PlaylistsRunState()
) {
    val excluded: Set<String> get() = settings?.excluded.orEmpty()
    /** The plans of the playlists switched on; null without a usable folder. */
    val chosenPlans: List<RetroArchPlaylists.Plan>? get() = preview?.plans?.filter { it.system !in excluded }
}

private sealed interface PlaylistsPreviewState {
    data object Loading : PlaylistsPreviewState
    data object Failed : PlaylistsPreviewState
    data class Ready(val preview: PlaylistsPreview) : PlaylistsPreviewState
}

/**
 * Tools -> RetroArch playlists: the playlists folder, which playlists to write, the preview of
 * what a run would do (worked out again when the folder or the profile changes, on Refresh and
 * when a run ends; switching a playlist on or off only changes the totals) and the run itself in
 * [RetroArchPlaylistsService].
 */
@HiltViewModel
class RetroArchPlaylistsViewModel @Inject constructor(
    private val service: RetroArchPlaylistsService,
    private val settings: RetroArchPlaylistsSettings,
    profiles: ProfileService
) : ViewModel() {
    private val refresh = MutableStateFlow(0)
    private val preview = MutableStateFlow<PlaylistsPreviewState>(PlaylistsPreviewState.Loading)

    val ui: StateFlow<RetroArchPlaylistsUi> = combine(settings.values, preview, service.state) { s, p, run ->
        RetroArchPlaylistsUi(
            settings = s,
            previewLoading = p is PlaylistsPreviewState.Loading,
            previewFailed = p is PlaylistsPreviewState.Failed,
            preview = (p as? PlaylistsPreviewState.Ready)?.preview,
            run = run
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RetroArchPlaylistsUi())

    init {
        // An outcome from an earlier visit is not shown again.
        service.clear()
        viewModelScope.launch {
            val folder = settings.values.map { it.folder }.distinctUntilChanged()
            val runEnded = service.state.map { !it.running }.distinctUntilChanged()
            combine(folder, profiles.activeId, refresh, runEnded) { _, _, _, ended -> ended }
                .collectLatest { ended ->
                    // While playlists are being written the preview would only count them twice.
                    if (!ended) return@collectLatest
                    preview.value = PlaylistsPreviewState.Loading
                    preview.value = try {
                        PlaylistsPreviewState.Ready(service.preview())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        PlaylistsPreviewState.Failed
                    }
                }
        }
    }

    fun setFolder(uri: String) { viewModelScope.launch { settings.setFolder(uri) } }
    fun setIncluded(system: String, on: Boolean) { if (!service.state.value.running) viewModelScope.launch { settings.setIncluded(system, on) } }
    fun setAll(on: Boolean) {
        val systems = ui.value.preview?.systems?.map { it.system } ?: return
        if (!service.state.value.running) viewModelScope.launch { settings.setAll(systems, on) }
    }
    fun setAuto(on: Boolean) { viewModelScope.launch { settings.setAuto(on) } }
    /** 6.5: tapping a system's core row steps through none (RetroArch asks) and the cores the catalogue knows for it. */
    fun nextCore(system: String, options: List<String>) {
        if (service.state.value.running) return
        val next = RetroArchPlaylists.nextCore(options, ui.value.settings?.cores?.get(system))
        viewModelScope.launch { settings.setCore(system, next); refresh() }
    }
    fun refresh() { refresh.value++ }

    fun write() = service.start()
    fun stop() = service.cancel()
    fun dismiss() = service.clear()
}
