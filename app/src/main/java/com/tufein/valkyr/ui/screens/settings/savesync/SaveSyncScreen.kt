package com.tufein.valkyr.ui.screens.settings.savesync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tufein.valkyr.R
import com.tufein.valkyr.data.service.SaveSyncState
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.MeterBar
import com.tufein.valkyr.ui.components.Panel
import com.tufein.valkyr.ui.components.PanelTone
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.ScreenTitle
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.formatBytes
import com.tufein.valkyr.ui.components.rememberFocusSource
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.ui.screens.cloud.saves.StateShotPair
import com.tufein.valkyr.ui.screens.settings.SettingsCardsGrid
import com.tufein.valkyr.ui.screens.settings.SettingsStepper
import com.tufein.valkyr.ui.screens.settings.SettingsSurface
import com.tufein.valkyr.ui.screens.settings.SettingRow
import com.tufein.valkyr.ui.screens.settings.SettingsCardHeader
import com.tufein.valkyr.ui.screens.settings.SettingsIconTile
import com.tufein.valkyr.ui.screens.settings.SettingsTileGap
import com.tufein.valkyr.ui.screens.settings.settingsInset
import com.tufein.valkyr.ui.screens.settings.ThemedSwitch
import com.tufein.valkyr.ui.theme.LocalReduceMotion
import com.tufein.valkyr.ui.theme.tabular
import com.tufein.valkyr.util.BackgroundSyncPolicy
import com.tufein.valkyr.util.CardGrid
import com.tufein.valkyr.util.EmulatorSaveFolder
import com.tufein.valkyr.util.EmulatorSaveFolders
import com.tufein.valkyr.util.SaveFolderLayout
import androidx.compose.material3.OutlinedTextField
import com.tufein.valkyr.util.NewerSide
import com.tufein.valkyr.util.ProgressText
import com.tufein.valkyr.util.SaveConflict
import com.tufein.valkyr.util.SaveConflictInfo
import com.tufein.valkyr.util.SaveKind
import com.tufein.valkyr.util.SaveSyncResult
import com.tufein.valkyr.util.DolphinBundleKind

/**
 * Settings → Save sync: which emulator folders hold the saves and the save states, "Sync now",
 * automatic sync, the result of the last sync and the files changed on both sides, where the
 * user keeps one copy (◀ the device's, ▶ the server's).
 */
@Composable
fun SaveSyncScreen(viewModel: SaveSyncViewModel = hiltViewModel()) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val sync by viewModel.syncState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    fun persist(uri: Uri) = context.contentResolver.takePersistableUriPermission(
        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    )
    val savesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { persist(it); viewModel.setSavesDir(context, it.toString()) }
    }
    val statesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { persist(it); viewModel.setStatesDir(context, it.toString()) }
    }

    // Android 13+ asks before an app may show notifications; the background sync needs them for conflicts.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun setBackground(on: Boolean) {
        viewModel.setBackground(context, on)
        if (on && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    fun adjustInterval(delta: Int) = viewModel.setInterval(context, BackgroundSyncPolicy.cycle(ui.intervalHours, delta))

    val emulatorFolders by viewModel.emulatorFolders.collectAsStateWithLifecycle()
    // An emulator's folder is picked first, then which emulator it is.
    var pickedEmulatorFolder by remember { mutableStateOf<String?>(null) }
    val emulatorPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { persist(it); pickedEmulatorFolder = it.toString() }
    }
    // Shared memory cards (DuckStation, AetherSX2 / NetherSX2, Dolphin): the game they are kept with on RomM is chosen here.
    var sharedCardsFor by remember { mutableStateOf<String?>(null) }
    sharedCardsFor?.let { label ->
        emulatorFolders.firstOrNull { it.label == label }?.let { folder ->
            SharedCardsDialog(
                folder = folder,
                viewModel = viewModel,
                onDone = { sharedCardsFor = null; viewModel.clearGameResults() }
            )
        }
    }
    // Dolphin's GCI folders and Wii saves: the game their bundles are kept with on RomM is chosen here.
    var bundleFor by remember { mutableStateOf<Pair<String, DolphinBundleKind>?>(null) }
    bundleFor?.let { (label, kind) ->
        emulatorFolders.firstOrNull { it.label == label }?.let { folder ->
            DolphinBundleDialog(
                folder = folder,
                kind = kind,
                viewModel = viewModel,
                onDone = { bundleFor = null; viewModel.clearGameResults() }
            )
        }
    }
    pickedEmulatorFolder?.let { uri ->
        EmulatorPresetDialog(
            onPick = { preset -> viewModel.addEmulatorFolder(context, preset, uri); pickedEmulatorFolder = null },
            onDismiss = { pickedEmulatorFolder = null }
        )
    }

    val notSet = stringResource(R.string.settings_not_set)
    val ready = ui.rommUrl.isNotBlank() && (ui.savesDir.isNotBlank() || ui.statesDir.isNotBlank() || emulatorFolders.isNotEmpty())
    // Four cards: the folders, the automatic sync, "Sync now" with its outcome, the conflicts.
    val folderRows: List<@Composable () -> Unit> = buildList {
        add {
            SettingRow(
                icon = R.drawable.ic_save,
                title = stringResource(R.string.save_sync_saves_folder),
                hint = folderLabel(ui.savesDir) ?: stringResource(R.string.save_sync_saves_folder_hint),
                onClick = { savesPicker.launch(null) },
            ) { ActionPill(stringResource(R.string.settings_change), { savesPicker.launch(null) }, icon = R.drawable.ic_folder_open) }
        }
        add {
            SettingRow(
                icon = R.drawable.ic_history,
                title = stringResource(R.string.save_sync_states_folder),
                hint = folderLabel(ui.statesDir) ?: stringResource(R.string.save_sync_states_folder_hint),
                onClick = { statesPicker.launch(null) },
            ) { ActionPill(stringResource(R.string.settings_change), { statesPicker.launch(null) }, icon = R.drawable.ic_folder_open) }
        }
        emulatorFolders.forEach { folder ->
            add {
                val note = layoutHint(folder.layout)
                SettingRow(
                    icon = R.drawable.ic_folder,
                    title = folder.label,
                    hint = listOfNotNull(folderLabel(folder.uri) ?: folder.uri, note).joinToString("\n"),
                    onClick = { viewModel.removeEmulatorFolder(context, folder.label) },
                ) {
                    ActionPill(
                        stringResource(R.string.save_sync_emulator_remove),
                        { viewModel.removeEmulatorFolder(context, folder.label) },
                        icon = R.drawable.ic_trash,
                        tone = ActionTone.Danger
                    )
                }
            }
            if (folder.layout.holdsSharedCards) add {
                val on = folder.sharedCardsRomId != null
                SettingRow(
                    icon = R.drawable.ic_save,
                    title = stringResource(R.string.saves31_shared_title),
                    hint = if (on) stringResource(R.string.saves31_shared_on, folder.sharedCardsRomName) else stringResource(R.string.saves31_shared_off),
                    onClick = { sharedCardsFor = folder.label },
                ) {
                    ActionPill(
                        stringResource(if (on) R.string.saves31_shared_change else R.string.saves31_shared_choose),
                        { sharedCardsFor = folder.label },
                        icon = R.drawable.ic_settings
                    )
                }
            }
            dolphinBundleRows(
                folder,
                onChoose = { kind -> bundleFor = folder.label to kind },
                onChannels = { on -> viewModel.setWiiChannels(context, folder.label, on) },
                onWiiWare = { on -> viewModel.setWiiWare(context, folder.label, on) }
            ).forEach { add(it) }
        }
        add {
            SettingRow(
                icon = R.drawable.ic_add,
                title = stringResource(R.string.save_sync_emulator_add),
                hint = stringResource(R.string.save_sync_emulator_add_hint),
                onClick = { emulatorPicker.launch(null) },
            ) { ActionPill(stringResource(R.string.save_sync_emulator_add_action), { emulatorPicker.launch(null) }, icon = R.drawable.ic_folder_open) }
        }
    }
    val autoRows: List<@Composable () -> Unit> = buildList {
        add {
            SettingRow(
                icon = R.drawable.ic_sync,
                title = stringResource(R.string.save_sync_auto),
                hint = stringResource(R.string.save_sync_auto_hint),
                onClick = { viewModel.setAuto(context, !ui.auto) },
                onAdjust = { viewModel.setAuto(context, it > 0) },
            ) { ThemedSwitch(ui.auto) { viewModel.setAuto(context, it) } }
        }
        add {
            SettingRow(
                icon = R.drawable.ic_trash,
                title = stringResource(R.string.save_sync_deletions),
                hint = stringResource(R.string.save_sync_deletions_hint),
                onClick = { viewModel.setDeletions(context, !ui.deletions) },
                onAdjust = { viewModel.setDeletions(context, it > 0) },
            ) { ThemedSwitch(ui.deletions) { viewModel.setDeletions(context, it) } }
        }
        add {
            SettingRow(
                icon = R.drawable.ic_schedule,
                title = stringResource(R.string.save_sync_bg),
                hint = stringResource(R.string.save_sync_bg_hint),
                onClick = { setBackground(!ui.background) },
                onAdjust = { setBackground(it > 0) },
            ) { ThemedSwitch(ui.background) { setBackground(it) } }
        }
        if (ui.background) {
            add {
                SettingRow(
                    icon = R.drawable.ic_timer,
                    title = stringResource(R.string.save_sync_bg_interval),
                    hint = null,
                    onClick = { adjustInterval(1) },
                    onAdjust = ::adjustInterval,
                ) {
                    SettingsStepper(stringResource(R.string.save_sync_hours_short, ui.intervalHours), onDecrement = { adjustInterval(-1) }, onIncrement = { adjustInterval(1) })
                }
            }
            add {
                SettingRow(
                    icon = R.drawable.ic_wifi,
                    title = stringResource(R.string.save_sync_bg_wifi), hint = null,
                    onClick = { viewModel.setWifiOnly(context, !ui.wifiOnly) }, onAdjust = { viewModel.setWifiOnly(context, it > 0) },
                ) { ThemedSwitch(ui.wifiOnly) { viewModel.setWifiOnly(context, it) } }
            }
            add {
                SettingRow(
                    icon = R.drawable.ic_charging,
                    title = stringResource(R.string.save_sync_bg_charging), hint = null,
                    onClick = { viewModel.setCharging(context, !ui.charging) }, onAdjust = { viewModel.setCharging(context, it > 0) },
                ) { ThemedSwitch(ui.charging) { viewModel.setCharging(context, it) } }
            }
        }
    }
    val syncRows: List<@Composable () -> Unit> = buildList {
        add {
            val hint = when {
                ui.rommUrl.isBlank() -> stringResource(R.string.save_sync_needs_romm)
                !ready -> stringResource(R.string.save_sync_needs_folder)
                sync.running -> stringResource(R.string.save_sync_running, sync.progress ?: "")
                sync.error != null -> stringResource(R.string.save_sync_failed, sync.error ?: "")
                sync.last != null -> summary(sync.last!!)
                else -> stringResource(R.string.save_sync_never)
            }
            SettingRow(
                icon = R.drawable.ic_cloud_sync,
                title = stringResource(R.string.save_sync_now),
                hint = hint,
                onClick = { if (ready) viewModel.syncNow() },
                hintColor = if (sync.error != null && !sync.running) MaterialTheme.colorScheme.error else null,
                below = if (sync.running) {
                    { SyncMeter(ProgressText.fraction(sync.progress), modifier = Modifier.padding(top = 6.dp)) }
                } else null
            ) {
                ActionPill(
                    stringResource(if (sync.running) R.string.save_sync_busy else R.string.save_sync_action),
                    { if (ready) viewModel.syncNow() },
                    icon = R.drawable.ic_sync,
                    tone = if (ready && !sync.running) ActionTone.Accent else ActionTone.Neutral
                )
            }
        }
        sync.last?.takeIf { it.deletionsHeld > 0 }?.let { held ->
            add {
                SettingRow(
                    icon = R.drawable.ic_warning,
                    title = pluralStringResource(R.plurals.save_sync_held_title, held.deletionsHeld, held.deletionsHeld),
                    hint = stringResource(R.string.save_sync_held_hint),
                    onClick = viewModel::applyHeldDeletions,
                ) { ActionPill(stringResource(R.string.save_sync_held_apply), viewModel::applyHeldDeletions, tone = ActionTone.Danger) }
            }
        }
        sync.last?.errors?.forEach { error ->
            add { SyncErrorRow(error) }
        }
        sync.last?.unmatched?.takeIf { it.isNotEmpty() }?.let { paths ->
            add {
                SettingRow(
                    icon = R.drawable.ic_warning,
                    title = stringResource(R.string.saves31_unmatched_title),
                    hint = stringResource(R.string.saves31_unmatched_hint, paths.joinToString("\n")),
                    onClick = {}
                ) {}
            }
        }
    }
    val pruned by viewModel.pruned.collectAsStateWithLifecycle()
    val prune by viewModel.prune.collectAsStateWithLifecycle()
    val safetyRows: List<@Composable () -> Unit> = buildList {
        add {
            SettingRow(
                icon = R.drawable.ic_history,
                title = stringResource(R.string.sv55_keep_title),
                hint = stringResource(R.string.sv55_keep_hint),
                onClick = { viewModel.adjustBackupKeep(context, 1) },
                onAdjust = { viewModel.adjustBackupKeep(context, it) },
            ) {
                SettingsStepper(ui.backupKeep.toString(), onDecrement = { viewModel.adjustBackupKeep(context, -1) }, onIncrement = { viewModel.adjustBackupKeep(context, 1) })
            }
        }
        add {
            SettingRow(
                icon = R.drawable.ic_lock,
                title = stringResource(R.string.sv55_nondestructive_title),
                hint = stringResource(R.string.sv55_nondestructive_hint),
                onClick = { viewModel.setNonDestructive(context, !ui.nonDestructive) },
                onAdjust = { viewModel.setNonDestructive(context, it > 0) },
            ) { ThemedSwitch(ui.nonDestructive) { viewModel.setNonDestructive(context, it) } }
        }
        add {
            SettingRow(
                icon = R.drawable.ic_cloud_download,
                title = stringResource(R.string.sv55_prefer_title),
                hint = stringResource(R.string.sv55_prefer_hint),
                onClick = { viewModel.setPreferServer(context, !ui.preferServer) },
                onAdjust = { viewModel.setPreferServer(context, it > 0) },
            ) { ThemedSwitch(ui.preferServer) { viewModel.setPreferServer(context, it) } }
        }
        add {
            SettingRow(
                icon = R.drawable.ic_trash,
                title = stringResource(R.string.sv55_prune_title),
                hint = stringResource(R.string.sv55_prune_hint),
                onClick = viewModel::openPrune,
            ) { ActionPill(stringResource(R.string.sv55_prune_action), viewModel::openPrune, icon = R.drawable.ic_trash) }
        }
        if (pruned > 0) {
            add {
                SettingRow(
                    icon = R.drawable.ic_restore,
                    title = stringResource(R.string.sv55_prune_undo_title),
                    hint = stringResource(R.string.sv55_prune_undo_hint, pruned),
                    onClick = { viewModel.undoPrune(context) },
                ) { ActionPill(stringResource(R.string.sv55_prune_undo_action), { viewModel.undoPrune(context) }, icon = R.drawable.ic_restore) }
            }
        }
    }
    val folderModeRows = folderModeRows()
    prune?.let { PruneDialog(it, onConfirm = { viewModel.confirmPrune(context) }, onDismiss = viewModel::closePrune) }
    val conflictRows: List<@Composable () -> Unit> = buildList {
        sync.conflicts.forEach { conflict -> add { ConflictRow(conflict, notSet, viewModel) } }
    }

    val cells = CardGrid.layout(
        listOf(
            CardGrid.Section(header = true, items = folderRows.size),
            CardGrid.Section(header = true, items = autoRows.size),
            CardGrid.Section(header = true, items = syncRows.size),
            CardGrid.Section(header = true, items = conflictRows.size),
            CardGrid.Section(header = true, items = safetyRows.size),
            CardGrid.Section(header = true, items = folderModeRows.size)
        ),
        columns = 1
    )
    val rowLists = listOf(folderRows, autoRows, syncRows, conflictRows, safetyRows, folderModeRows)

    val rowFocus = remember(cells.size) { List(cells.size) { FocusRequester() } }
    var focusedIndex by remember { mutableIntStateOf(-1) }
    LaunchedEffect(cells.size) {
        val first = cells.indexOfFirst { it.kind == CardGrid.Kind.ITEM }
        if (focusedIndex < 0 && first >= 0) runCatching { rowFocus[first].requestFocus() }
    }

    SettingsSurface {
        SettingsCardsGrid(
            cells = cells,
            columns = 1,
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScreenTitle(
                        text = stringResource(R.string.settings_save_sync),
                        icon = R.drawable.ic_cloud_sync,
                        trailing = { SyncStatusPills(ui.rommUrl.isNotBlank(), sync) }
                    )
                    Text(
                        stringResource(R.string.save_sync_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        ) { index, cell ->
            if (cell.kind == CardGrid.Kind.HEADER) {
                SyncCardHeader(cell.section, sync.conflicts.size)
            } else {
                Box(
                    modifier = Modifier
                        .focusRequester(rowFocus[index])
                        .onFocusChanged { if (it.hasFocus) focusedIndex = index },
                    propagateMinConstraints = true
                ) { rowLists[cell.section][cell.item]() }
            }
        }
    }
}

/** The header of each Save sync card (0 folders, 1 automatic, 2 sync, 3 conflicts, 4 backups and safety). */
@Composable
private fun SyncCardHeader(section: Int, conflicts: Int) {
    when (section) {
        0 -> SettingsCardHeader(stringResource(R.string.save_sync_v5_section_folders), R.drawable.ic_folder)
        1 -> SettingsCardHeader(stringResource(R.string.save_sync_v5_section_auto), R.drawable.ic_schedule)
        2 -> SettingsCardHeader(stringResource(R.string.save_sync_v5_section_sync), R.drawable.ic_cloud_sync)
        4 -> SettingsCardHeader(stringResource(R.string.sv55_section_safety), R.drawable.ic_history)
        5 -> SettingsCardHeader(stringResource(R.string.exp60_section), R.drawable.ic_sync)
        else -> Column {
            SettingsCardHeader(stringResource(R.string.save_sync_v5_section_conflicts), R.drawable.ic_warning) {
                Pill(conflicts.toString(), tone = PillTone.Warning)
            }
            Text(
                stringResource(R.string.save_sync_conflicts_header, conflicts),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = settingsInset(), end = settingsInset(), bottom = 8.dp)
            )
        }
    }
}

/** Where the sync stands, next to the title: not set up, running, failed or when it last ran. */
@Composable
private fun SyncStatusPills(configured: Boolean, sync: SaveSyncState) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val last = sync.last
        when {
            !configured -> Pill(stringResource(R.string.settings_v5_pill_not_set_up), tone = PillTone.Neutral, icon = R.drawable.ic_cloud_off)
            sync.running -> Pill(sync.progress ?: stringResource(R.string.save_sync_busy), tone = PillTone.Info, icon = R.drawable.ic_sync)
            sync.error != null -> Pill(stringResource(R.string.save_sync_v5_pill_failed), tone = PillTone.Danger, icon = R.drawable.ic_error_circle)
            last != null -> Pill(relative(last.finishedAt) ?: stringResource(R.string.save_sync_v5_pill_synced), tone = PillTone.Success, icon = R.drawable.ic_check_circle)
            else -> Pill(stringResource(R.string.save_sync_never), tone = PillTone.Neutral)
        }
        if (sync.conflicts.isNotEmpty()) {
            Pill(
                pluralStringResource(R.plurals.save_sync_v5_conflicts, sync.conflicts.size, sync.conflicts.size),
                tone = PillTone.Warning,
                icon = R.drawable.ic_warning
            )
        }
    }
}

/** The progress of a running sync: a bar when the file count is known, a gliding segment before. */
@Composable
private fun SyncMeter(fraction: Float?, modifier: Modifier = Modifier) {
    if (fraction != null) {
        MeterBar(fraction, modifier = modifier, height = 6.dp)
        return
    }
    val reduce = LocalReduceMotion.current
    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    // Read only while drawing: the segment glides without recomposing anything.
    val phase: State<Float>? = if (reduce) null else rememberInfiniteTransition(label = "syncMeter").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "syncPhase"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .drawBehind {
                val radius = CornerRadius(size.height / 2)
                drawRoundRect(track, cornerRadius = radius)
                val segment = size.width * 0.3f
                val p = phase?.value ?: 0.35f
                val x = (size.width + segment) * p - segment
                clipRect {
                    drawRoundRect(color, topLeft = Offset(x, 0f), size = Size(segment, size.height), cornerRadius = radius)
                }
            }
    )
}

/** A file that did not sync, with the whole reason. */
@Composable
private fun SyncErrorRow(error: String) {
    SettingRow(
        icon = R.drawable.ic_error_circle,
        title = stringResource(R.string.save_sync_error_title),
        hint = error,
        onClick = {}
    ) {}
}

/**
 * A save changed on both sides: the device's copy and RomM's side by side, each with its size,
 * time and a "newer" mark, and the button that keeps it. ◀ keeps the device's, ▶ the server's.
 */
@Composable
private fun ConflictRow(conflict: SaveConflict, notSet: String, viewModel: SaveSyncViewModel) {
    val info = SaveConflictInfo.of(conflict)
    val kind = stringResource(if (conflict.local.kind == SaveKind.STATE) R.string.save_sync_kind_state else R.string.save_sync_kind_save)
    val newer = when (info.newer) {
        NewerSide.DEVICE -> stringResource(R.string.save_sync_newer_device)
        NewerSide.SERVER -> stringResource(R.string.save_sync_newer_server)
        NewerSide.SAME -> stringResource(R.string.save_sync_newer_same)
        NewerSide.UNKNOWN -> null
    }
    val sizeNote = when {
        info.sameSize -> stringResource(R.string.save_sync_size_same)
        info.sizeDelta < 0 -> stringResource(R.string.save_sync_size_smaller, formatBytes(-info.sizeDelta))
        else -> stringResource(R.string.save_sync_size_bigger, formatBytes(info.sizeDelta))
    }
    val source = rememberFocusSource()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .focusRing(source)
            .clip(RoundedCornerShape(8.dp))
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { viewModel.resolve(conflict, keepDevice = true); true }
                    Key.DirectionRight -> { viewModel.resolve(conflict, keepDevice = false); true }
                    else -> false
                }
            }
            .clickable(interactionSource = source, indication = null, onClick = {})
            .padding(horizontal = settingsInset(), vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SettingsTileGap)) {
            SettingsIconTile(if (conflict.local.kind == SaveKind.STATE) R.drawable.ic_history else R.drawable.ic_save)
            Column(modifier = Modifier.weight(1f)) {
                Text(kind, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(conflict.local.path, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConflictSide(
                label = stringResource(R.string.save_sync_v5_side_device),
                icon = R.drawable.ic_controller,
                sizeText = formatBytes(info.deviceSize),
                time = relative(info.deviceModified) ?: notSet,
                newer = info.newer == NewerSide.DEVICE,
                keepLabel = stringResource(R.string.save_sync_keep_device),
                onKeep = { viewModel.resolve(conflict, keepDevice = true) },
                modifier = Modifier.weight(1f)
            )
            ConflictSide(
                label = stringResource(R.string.save_sync_v5_side_server),
                icon = R.drawable.ic_server,
                sizeText = formatBytes(info.serverSize),
                time = relative(info.serverModified) ?: notSet,
                newer = info.newer == NewerSide.SERVER,
                keepLabel = stringResource(R.string.save_sync_keep_server),
                onKeep = { viewModel.resolve(conflict, keepDevice = false) },
                modifier = Modifier.weight(1f)
            )
        }
        StateShotPair(conflict)
        ActionPill(stringResource(R.string.sv55_keep_both), { viewModel.resolveKeepBoth(conflict) }, icon = R.drawable.ic_content_copy)
        Text(stringResource(R.string.sv55_keep_both_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            listOfNotNull(newer, sizeNote).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** One side of a conflict: where the copy lives, its size and time, and the button that keeps it. */
@Composable
private fun ConflictSide(
    label: String,
    icon: Int,
    sizeText: String,
    time: String,
    newer: Boolean,
    keepLabel: String,
    onKeep: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Panel(
        modifier = modifier,
        tone = if (newer) PanelTone.Accent else PanelTone.Raised,
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(icon), contentDescription = null, tint = if (newer) scheme.primary else scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface, modifier = Modifier.weight(1f, fill = false))
            if (newer) Pill(stringResource(R.string.save_sync_v5_newer), tone = PillTone.Success)
        }
        Text(
            "$sizeText · $time",
            style = MaterialTheme.typography.bodySmall.tabular(),
            color = scheme.onSurfaceVariant
        )
        ActionPill(keepLabel, onKeep, tone = if (newer) ActionTone.Accent else ActionTone.Neutral)
    }
}

@Composable
private fun summary(result: SaveSyncResult): String {
    val parts = buildList {
        add(stringResource(R.string.save_sync_summary, result.uploaded, result.downloaded, result.unchanged))
        if (result.conflicts > 0) add(stringResource(R.string.save_sync_summary_conflicts, result.conflicts))
        if (result.notMatched > 0) add(stringResource(R.string.save_sync_summary_not_matched, result.notMatched))
        if (result.keptAsIs > 0) add(stringResource(R.string.sv55_summary_kept_as_is, result.keptAsIs))
        if (result.serverCopies > 0) add(stringResource(R.string.sv55_summary_server_copies, result.serverCopies))
        if (result.failed > 0) add(stringResource(R.string.save_sync_summary_failed, result.failed))
        if (result.deletedOnDevice + result.deletedOnServer > 0) add(stringResource(R.string.save_sync_summary_deleted, result.deletedOnDevice, result.deletedOnServer))
        relative(result.finishedAt)?.let { add(it) }
    }
    return parts.joinToString(" · ")
}

internal fun relative(millis: Long?): String? =
    millis?.takeIf { it > 0 }?.let { DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString() }

/** `content://…/tree/primary%3ARetroArch%2Fsaves` → `RetroArch/saves`; null when nothing is picked. */
private fun folderLabel(uri: String): String? {
    if (uri.isBlank()) return null
    return runCatching { DocumentsContract.getTreeDocumentId(uri.toUri()).substringAfter(':').ifBlank { "/" } }.getOrDefault(uri)
}

/** What a folder of [layout] syncs, for the folder's row and the emulator picker; null for plain save files. */
@Composable
private fun layoutHint(layout: SaveFolderLayout): String? = when (layout) {
    SaveFolderLayout.MEMCARDS -> stringResource(R.string.saves31_layout_memcards)
    SaveFolderLayout.SAVEDATA -> stringResource(R.string.saves31_layout_savedata)
    SaveFolderLayout.PS2_MEMCARDS -> stringResource(R.string.saves32_layout_ps2)
    SaveFolderLayout.GC_MEMCARDS -> stringResource(R.string.saves37_layout_gc)
    SaveFolderLayout.FILES -> null
}

/** Which emulator a picked folder belongs to: its saves are synced under that emulator's name. */
@Composable
private fun EmulatorPresetDialog(onPick: (EmulatorSaveFolders.Preset) -> Unit, onDismiss: () -> Unit) {
    val closeFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.save_sync_emulator_pick)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())
            ) {
                Text(stringResource(R.string.save_sync_emulator_pick_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                EmulatorSaveFolders.presets.forEach { preset ->
                    val source = rememberFocusSource()
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .focusRing(source)
                            .clickable(interactionSource = source, indication = null) { onPick(preset) }
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Text(preset.label, style = MaterialTheme.typography.bodyLarge)
                        Text(preset.folderHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        // What syncs from a folder of cards or save folders (and what does not), in full.
                        layoutHint(preset.layout)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        },
        confirmButton = { DialogButton(text = stringResource(R.string.dialog_close), onClick = onDismiss, initialFocus = closeFocus) }
    )
}

/**
 * Turns syncing of a folder's shared memory cards (DuckStation's shared cards, every AetherSX2 /
 * NetherSX2 or Dolphin GameCube card) on by choosing the RomM game they are kept with, changes that
 * game, or turns it off. Nothing on the device is touched here.
 */
@Composable
private fun SharedCardsDialog(folder: EmulatorSaveFolder, viewModel: SaveSyncViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val results by viewModel.gameResults.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    var term by remember { mutableStateOf("") }
    val fieldFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDone),
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.saves31_shared_dialog_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())
            ) {
                Text(
                    stringResource(
                        when (folder.layout) {
                            SaveFolderLayout.PS2_MEMCARDS -> R.string.saves32_shared_dialog_hint_ps2
                            SaveFolderLayout.GC_MEMCARDS -> R.string.saves32_shared_dialog_hint_gc
                            else -> R.string.saves31_shared_dialog_hint
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (folder.sharedCardsRomId != null) {
                    Text(stringResource(R.string.saves31_shared_on, folder.sharedCardsRomName), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = term,
                    onValueChange = { term = it },
                    label = { Text(stringResource(R.string.saves31_shared_search_label)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus)
                )
                ActionPill(stringResource(R.string.saves31_shared_search), { if (term.isNotBlank()) viewModel.searchGames(context, term) }, icon = R.drawable.ic_search, enabled = !searching)
                results?.let { found ->
                    if (found.isEmpty() && !searching) Text(stringResource(R.string.saves31_shared_none), style = MaterialTheme.typography.bodySmall)
                    found.forEach { rom ->
                        val source = rememberFocusSource()
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .focusRing(source)
                                .clickable(interactionSource = source, indication = null) { viewModel.setSharedCards(context, folder.label, rom); onDone() }
                                .padding(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Text(rom.name.ifBlank { rom.fsName }, style = MaterialTheme.typography.bodyLarge)
                            Text(listOf(rom.fsName, rom.platformSlug).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { DialogButton(text = stringResource(R.string.dialog_close), onClick = onDone) },
        dismissButton = {
            if (folder.sharedCardsRomId != null) DialogButton(text = stringResource(R.string.saves31_shared_stop), onClick = { viewModel.setSharedCards(context, folder.label, null); onDone() })
        }
    )
}

/**
 * "Prune now": what would move to the recovery folder, listed in full before anything moves. The
 * copies are not deleted: they wait there for 14 days and "Bring back" returns them.
 */
@Composable
private fun PruneDialog(state: PruneUi, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancelFocus = rememberInitialFocus()
    val preview = state as? PruneUi.Preview
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_trash), contentDescription = null) },
        title = { Text(stringResource(R.string.sv55_prune_dialog_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())
            ) {
                when (state) {
                    PruneUi.Checking -> Text(stringResource(R.string.sv55_prune_checking))
                    is PruneUi.Failed -> Text(stringResource(R.string.sv55_prune_error, state.message))
                    is PruneUi.Preview -> if (state.copies.isEmpty()) {
                        Text(stringResource(R.string.sv55_prune_dialog_none, state.keep))
                    } else {
                        Text(stringResource(R.string.sv55_prune_dialog_body, state.copies.size, formatBytes(state.bytes), state.keep))
                        state.copies.forEach { copy ->
                            Text(
                                "${copy.path} · ${relative(copy.takenAt) ?: ""} · ${formatBytes(copy.size)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (preview != null && preview.copies.isNotEmpty()) DialogButton(stringResource(R.string.sv55_prune_confirm), onClick = onConfirm)
            else DialogButton(stringResource(R.string.dialog_close), onClick = onDismiss, initialFocus = cancelFocus)
        },
        // Cancel has the first focus: two quick presses of A never move anything by accident.
        dismissButton = {
            if (preview != null && preview.copies.isNotEmpty()) DialogButton(stringResource(R.string.dialog_cancel), onClick = onDismiss, initialFocus = cancelFocus)
        }
    )
}
