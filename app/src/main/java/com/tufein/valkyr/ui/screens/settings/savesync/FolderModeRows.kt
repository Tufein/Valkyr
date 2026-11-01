package com.tufein.valkyr.ui.screens.settings.savesync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.R
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.service.SyncthingFolderService
import com.tufein.valkyr.data.service.SyncthingState
import com.tufein.valkyr.ui.common.executeWithToast
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.screens.settings.SettingRow
import com.tufein.valkyr.ui.screens.settings.ThemedSwitch
import com.tufein.valkyr.util.FolderChoice
import com.tufein.valkyr.util.FolderConflict
import com.tufein.valkyr.util.FolderSyncResult
import com.tufein.valkyr.util.SaveKind
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class FolderModeUi(val on: Boolean = false, val dir: String = "")

/** 6.0 Settings → Save sync → Folder mode (Syncthing). */
@HiltViewModel
class FolderModeViewModel @Inject constructor(
    private val appSettings: AppSettings,
    private val service: SyncthingFolderService
) : ViewModel() {
    val ui: StateFlow<FolderModeUi> = combine(appSettings.syncthingMode, appSettings.syncthingDir) { on, dir -> FolderModeUi(on, dir) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FolderModeUi())
    val state: StateFlow<SyncthingState> = service.state

    fun setOn(context: Context, on: Boolean) = executeWithToast(context, TAG) { appSettings.setSyncthingMode(on) }
    fun setDir(context: Context, uri: String) = executeWithToast(context, TAG) { appSettings.setSyncthingDir(uri) }
    fun syncNow() = service.syncNow()
    fun resolve(conflict: FolderConflict, choice: FolderChoice) = service.resolve(conflict, choice)

    private companion object { const val TAG = "FolderModeViewModel" }
}

/** The rows of the Folder mode card: switch, folder, "Sync", the outcome and the conflicts. */
@Composable
internal fun folderModeRows(viewModel: FolderModeViewModel = hiltViewModel()): List<@Composable () -> Unit> {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            viewModel.setDir(context, it.toString())
        }
    }
    val ready = ui.on && ui.dir.isNotBlank()
    return buildList {
        add {
            SettingRow(
                icon = R.drawable.ic_sync,
                title = stringResource(R.string.exp60_mode_title),
                hint = stringResource(R.string.exp60_mode_hint),
                onClick = { viewModel.setOn(context, !ui.on) },
                onAdjust = { viewModel.setOn(context, it > 0) },
            ) { ThemedSwitch(ui.on) { viewModel.setOn(context, it) } }
        }
        add {
            SettingRow(
                icon = R.drawable.ic_folder,
                title = stringResource(R.string.exp60_dir_title),
                hint = treeLabel(ui.dir) ?: stringResource(R.string.exp60_dir_hint),
                onClick = { picker.launch(null) },
            ) { ActionPill(stringResource(R.string.settings_change), { picker.launch(null) }, icon = R.drawable.ic_folder_open) }
        }
        add {
            val hint = when {
                !ready -> stringResource(R.string.exp60_needs_setup)
                state.running -> stringResource(R.string.exp60_running)
                state.error == "busy" -> stringResource(R.string.exp60_busy)
                state.error != null -> stringResource(R.string.exp60_failed, state.error ?: "")
                state.last != null -> summary(state.last!!)
                else -> stringResource(R.string.exp60_never)
            }
            SettingRow(
                icon = R.drawable.ic_cloud_sync,
                title = stringResource(R.string.exp60_sync_title),
                hint = hint,
                onClick = { if (ready) viewModel.syncNow() },
            ) {
                ActionPill(
                    stringResource(R.string.exp60_sync_action), { if (ready) viewModel.syncNow() },
                    icon = R.drawable.ic_sync, tone = if (ready && !state.running) ActionTone.Accent else ActionTone.Neutral
                )
            }
        }
        state.last?.errors?.forEach { error ->
            add { SettingRow(icon = R.drawable.ic_error_circle, title = stringResource(R.string.save_sync_error_title), hint = error, onClick = {}) {} }
        }
        state.conflicts.forEach { conflict -> add { FolderConflictRow(conflict, viewModel) } }
    }
}

/** A save the device and the folder both changed, or that Syncthing kept two versions of; three choices, nothing is deleted. */
@Composable
private fun FolderConflictRow(conflict: FolderConflict, viewModel: FolderModeViewModel) {
    val kind = stringResource(if (conflict.kind == SaveKind.STATE) R.string.save_sync_kind_state else R.string.save_sync_kind_save)
    val hint = when (conflict.type) {
        FolderConflict.Type.DEVICE_VS_FOLDER -> stringResource(R.string.exp60_conflict_device, kind)
        FolderConflict.Type.SYNCTHING_COPY -> stringResource(R.string.exp60_conflict_copy, kind, conflict.copyStamp.orEmpty(), conflict.copy?.path.orEmpty())
    }
    SettingRow(
        icon = R.drawable.ic_warning,
        title = conflict.path,
        hint = hint,
        onClick = {},
        below = {
            Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionPill(stringResource(R.string.exp60_keep_this), { viewModel.resolve(conflict, FolderChoice.KEEP_THIS) }, icon = R.drawable.ic_check)
                ActionPill(stringResource(R.string.exp60_keep_other), { viewModel.resolve(conflict, FolderChoice.KEEP_OTHER) }, icon = R.drawable.ic_check)
                ActionPill(stringResource(R.string.exp60_keep_both), { viewModel.resolve(conflict, FolderChoice.KEEP_BOTH) }, icon = R.drawable.ic_content_copy)
            }
        }
    ) {}
}

@Composable
private fun summary(r: FolderSyncResult): String {
    val parts = buildList {
        add(stringResource(R.string.exp60_summary, r.toFolder, r.toDevice, r.unchanged))
        if (r.conflicts > 0) add(stringResource(R.string.save_sync_summary_conflicts, r.conflicts))
        if (r.keptAsIs > 0) add(stringResource(R.string.sv55_summary_kept_as_is, r.keptAsIs))
        if (r.failed > 0) add(stringResource(R.string.save_sync_summary_failed, r.failed))
        relative(r.finishedAt)?.let { add(it) }
    }
    return parts.joinToString(" · ")
}

private fun treeLabel(uri: String): String? {
    if (uri.isBlank()) return null
    return runCatching { DocumentsContract.getTreeDocumentId(uri.toUri()).substringAfter(':').ifBlank { "/" } }.getOrDefault(uri)
}
