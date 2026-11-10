package com.tufein.valkyr.ui.screens.cloud.saves

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.data.service.CloudSavesService
import com.tufein.valkyr.data.service.GameCloudSaves
import com.tufein.valkyr.data.service.ProfileService
import com.tufein.valkyr.util.JournalKey
import com.tufein.valkyr.util.CloudSaveResult
import com.tufein.valkyr.util.CloudSaveVersion
import com.tufein.valkyr.util.DeviceSave
import com.tufein.valkyr.util.LocalSaveFile
import com.tufein.valkyr.util.SafetyCopy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/** The outcome of the last restore / upload, shown under the section. */
sealed interface CloudSavesNotice {
    data class Restored(val path: String) : CloudSavesNotice
    data class Uploaded(val name: String) : CloudSavesNotice
    data class SlotCopied(val path: String) : CloudSavesNotice
    data class Failed(val name: String, val message: String) : CloudSavesNotice
    data object Busy : CloudSavesNotice
}

data class CloudSavesUi(
    val data: GameCloudSaves? = null,
    /** Id of the row whose action runs (see [CloudSavesViewModel.rowId]); null when idle. */
    val working: String? = null,
    val notice: CloudSavesNotice? = null
)

/**
 * The "Cloud saves" section of the details dialog. One instance follows whichever game the dialog
 * shows ([show]), so opening many games does not pile up state.
 */
@HiltViewModel
class CloudSavesViewModel @Inject constructor(
    private val service: CloudSavesService,
    private val profiles: ProfileService
) : ViewModel() {

    private val _ui = MutableStateFlow(CloudSavesUi())
    val ui: StateFlow<CloudSavesUi> = _ui.asStateFlow()

    val activeProfile = profiles.activeId
    private var game: JournalKey? = null
    private var job: Job? = null

    /** The dialog shows this game: read its cloud saves (unless they are the ones shown). */
    fun show(consoleId: String, fileName: String) {
        val g = JournalKey(profiles.currentIdNow(), consoleId, fileName)
        if (game == g) return
        game = g
        job?.cancel()
        _ui.value = CloudSavesUi()
        job = viewModelScope.launch { load(g, force = false) }
    }

    /** Read the server and the device again (Retry, after an action). */
    fun reload() {
        val g = game ?: return
        job?.cancel()
        job = viewModelScope.launch { load(g, force = true) }
    }

    private suspend fun load(g: JournalKey, force: Boolean) {
        // Quick part first (RomM id, safety copies): decides whether the section shows at all.
        val local = runCatching { service.loadLocal(g.consoleId, g.fileName) }.getOrNull() ?: return
        if (local.profileId != g.profileId) return
        if (game != g) return
        if (!local.visible) {
            _ui.update { it.copy(data = local) }
            return
        }
        _ui.update { ui ->
            // A reload keeps the lists on screen while the new ones come.
            val shown = ui.data?.takeIf { it.consoleId == g.consoleId && it.fileName == g.fileName && it.profileId == g.profileId }
            ui.copy(data = (shown ?: local).copy(loadingServer = local.romId != null, loadingDevice = true))
        }
        val full = runCatching { service.load(g.consoleId, g.fileName, force) }.getOrNull()?.takeIf { it.profileId == g.profileId }
        if (game != g) return
        _ui.update { it.copy(data = full ?: local) }
    }

    fun restore(version: CloudSaveVersion) =
        act(rowId(version), version.entry.fileName, upload = false) { service.restore(it, version) }

    /** The device path [version] would be restored to (null = unknown), for the confirm dialog. */
    suspend fun targetFor(version: CloudSaveVersion): String? {
        val data = _ui.value.data ?: return null
        return runCatching { service.restoreTargetPath(data, version) }.getOrNull()
    }

    fun restore(copy: SafetyCopy) =
        act(rowId(copy), copy.name, upload = false) { service.restore(it, copy) }

    fun upload(save: DeviceSave) =
        act(rowId(save), save.local.name, upload = true) { service.upload(it, save) }

    /** Copies the state [source] into [targetPath] (another slot of the game); [expectedTarget] is the replaced slot's time as the preview showed it. */
    fun copySlot(source: LocalSaveFile, targetPath: String, expectedTarget: Long?) =
        act(rowId(source), source.name, upload = false, slot = true) { service.copySlot(it, source.path, targetPath, expectedTarget) }

    fun dismissNotice() = _ui.update { it.copy(notice = null) }

    private fun act(id: String, name: String, upload: Boolean, slot: Boolean = false, block: suspend (GameCloudSaves) -> CloudSaveResult) {
        val data = _ui.value.data ?: return
        val g = game ?: return
        if (_ui.value.working != null) return
        _ui.update { it.copy(working = id, notice = null) }
        viewModelScope.launch {
            val result = try { block(data) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { CloudSaveResult.Failed(e.message ?: e.javaClass.simpleName) }
            if (game != g) return@launch
            val notice = when (result) {
                is CloudSaveResult.Done -> when {
                    slot -> CloudSavesNotice.SlotCopied(result.path)
                    upload -> CloudSavesNotice.Uploaded(name)
                    else -> CloudSavesNotice.Restored(result.path)
                }
                is CloudSaveResult.Failed -> CloudSavesNotice.Failed(name, result.message)
                CloudSaveResult.Busy -> CloudSavesNotice.Busy
            }
            _ui.update { it.copy(working = null, notice = notice) }
            if (result is CloudSaveResult.Done) reload()
        }
    }

    companion object {
        fun rowId(version: CloudSaveVersion) = "v:${version.entry.kind}:${version.entry.id}"
        fun rowId(copy: SafetyCopy) = "c:${copy.relative}"
        fun rowId(save: DeviceSave) = "d:${save.local.kind}:${save.local.path}"
        fun rowId(slot: LocalSaveFile) = "s:${slot.kind}:${slot.path}"
    }
}
