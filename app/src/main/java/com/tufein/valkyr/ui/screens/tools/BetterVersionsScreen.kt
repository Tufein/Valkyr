package com.tufein.valkyr.ui.screens.tools

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tufein.valkyr.ui.components.requestFocusWhenReady
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tufein.valkyr.R
import com.tufein.valkyr.data.service.ReplaceState
import com.tufein.valkyr.ui.common.Gamepad
import com.tufein.valkyr.ui.common.GamepadButton
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.EmptyState
import com.tufein.valkyr.ui.components.GameCover
import com.tufein.valkyr.ui.components.IconTile
import com.tufein.valkyr.ui.components.LegendEntry
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.formatBytes
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.util.BetterVersions
import com.tufein.valkyr.util.ConsoleFormatter
import androidx.compose.material3.MaterialTheme

/**
 * Better versions (7.0): games on the device for which a source lists a clearly better file (a
 * newer revision, a final release instead of a beta, a good dump instead of a bad one). A row
 * shows what you have, what is offered and why; tapping / A selects it, and each row can be
 * downloaded, downloaded with the old file removed afterwards (confirmed first, with the exact
 * files that go), or ignored. Gamepad: A selects, X downloads, Y ignores.
 */
@Composable
fun BetterVersionsScreen(viewModel: BetterVersionsViewModel = hiltViewModel(), onRecovery: () -> Unit = {}) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The files named in the confirmation are the ones this screen will ask to be removed.
    var confirmIds by remember { mutableStateOf<List<String>?>(null) }
    var replace by remember { mutableStateOf(true) }
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    fun ask(ids: List<String>, removeOld: Boolean) { replace = removeOld; confirmIds = ids; viewModel.preview(ids, removeOld) }
    confirmIds?.let { ids ->
        val rows = ui.rows.filter { it.id in ids && it.canDownload }
        if (rows.isEmpty()) confirmIds = null
        else ReplaceDialog(
            message = (if (replace) stringResource(R.string.upg7_replace_message) + "\n" + stringResource(R.string.upg25_recovery) else stringResource(R.string.upg25_keep)) +
                "\n\n" + removalList(rows) + "\n\n" + (preview?.let { p ->
                    stringResource(R.string.upg25_space, formatBytes(p.bytes)) +
                        (if (p.shortfall > 0) "\n" + stringResource(R.string.upg25_short, formatBytes(p.shortfall)) else "") +
                        (if (p.unknownSizes > 0 || p.unknownVolumes > 0) "\n" + stringResource(R.string.off25_unknown) else "") +
                        (if (p.unavailable) "\n" + stringResource(R.string.upg25_blocked) else "")
                } ?: stringResource(R.string.tools_scanning)),
            enabled = preview?.fits == true,
            onConfirm = { if (replace) viewModel.downloadAndReplace(context, rows.map { it.id }) else viewModel.download(context, rows.map { it.id }) },
            onDismiss = { confirmIds = null }
        )
    }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocusWhenReady() }

    // X downloads the row under the cursor, Y ignores it; A (or a tap) selects it.
    var focusedId by remember { mutableStateOf<String?>(null) }
    val current by rememberUpdatedState(ui)
    val focused by rememberUpdatedState(focusedId)
    LaunchedEffect(Unit) {
        Gamepad.presses.collect { button ->
            val row = current.rows.firstOrNull { it.id == focused } ?: return@collect
            if (!row.canDownload || confirmIds != null) return@collect
            when (button) {
                GamepadButton.X -> ask(listOf(row.id), false)
                GamepadButton.Y -> viewModel.ignore(context, row.id)
                else -> Unit
            }
        }
    }
    PublishLegend(
        if (focusedId == null) null else listOf(
            LegendEntry("A", stringResource(R.string.pad_select)),
            LegendEntry("X", stringResource(R.string.pad_download)),
            LegendEntry("Y", stringResource(R.string.upg7_pad_ignore)),
            LegendEntry("B", stringResource(R.string.pad_back)),
            LegendEntry("ZL · ZR", stringResource(R.string.pad_section))
        )
    )

    val summary = when {
        ui.scanning -> stringResource(R.string.tools_scanning)
        !ui.folderSet -> stringResource(R.string.tools_no_folder)
        ui.rows.isEmpty() -> stringResource(R.string.upg7_better_none, ui.gamesChecked)
        else -> pluralStringResource(R.plurals.upg7_better_summary, ui.rows.size, ui.rows.size, ui.gamesChecked)
    }
    val selected = ui.selected

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.upg7_better_title), icon = R.drawable.ic_better_version, subtitle = stringResource(R.string.upg7_better_subtitle))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "recovery") { ToolAction(stringResource(R.string.recovery_title), icon = R.drawable.ic_restore, onClick = onRecovery) }
            item(key = "summary") {
                ToolRow(
                    title = summary,
                    lines = emptyList(),
                    onClick = viewModel::rescan,
                    modifier = Modifier.focusRequester(firstFocus).onFocusChanged { if (it.isFocused) focusedId = null },
                    icon = if (!ui.scanning && ui.rows.isEmpty() && ui.folderSet) R.drawable.ic_check_circle else R.drawable.ic_better_version
                ) {
                    ToolAction(stringResource(R.string.tools_refresh), icon = R.drawable.ic_retry, onClick = viewModel::rescan)
                }
            }
            if (!ui.scanning && ui.ignoredCount > 0) {
                item(key = "ignored") {
                    ToolRow(
                        title = stringResource(R.string.upg7_ignored_reset),
                        lines = emptyList(),
                        onClick = viewModel::resetIgnored,
                        modifier = Modifier.onFocusChanged { if (it.isFocused) focusedId = null },
                        icon = R.drawable.ic_visibility
                    )
                }
            }
            if (!ui.scanning && selected.isNotEmpty()) {
                item(key = "selection") {
                    val ids = selected.map { it.id }
                    ToolRow(
                        title = pluralStringResource(R.plurals.upg7_sel_count, selected.size, selected.size),
                        lines = emptyList(),
                        onClick = viewModel::clearSelection,
                        modifier = Modifier.onFocusChanged { if (it.isFocused) focusedId = null },
                        icon = R.drawable.ic_checkbox_on,
                        below = {
                            ToolsActions(horizontalPadding = 0.dp) {
                                ToolAction(stringResource(R.string.upg7_sel_download), icon = R.drawable.ic_download, tone = ActionTone.Accent) { ask(ids, false) }
                                ToolAction(stringResource(R.string.upg7_sel_replace), icon = R.drawable.ic_trash, tone = ActionTone.Danger) { ask(ids, true) }
                                ToolAction(stringResource(R.string.upg7_sel_clear), onClick = viewModel::clearSelection)
                            }
                        }
                    )
                }
            }
            if (!ui.scanning && ui.folderSet && ui.rows.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = stringResource(R.string.upg7_empty_title),
                        message = stringResource(R.string.upg7_empty_message),
                        modifier = Modifier.fillMaxWidth(),
                        icon = R.drawable.ic_better_version
                    )
                }
            }
            if (!ui.scanning) {
                if (ui.rows.size > 1) {
                    item(key = "all") {
                        ToolsActions {
                            ToolAction(stringResource(R.string.upg7_sel_all), icon = R.drawable.ic_checkbox_on, onClick = viewModel::selectAll)
                        }
                    }
                }
                items(ui.rows, key = { it.id }) { row ->
                    val s = row.suggestion
                    val console = ConsoleFormatter.getConsoleDisplayName(s.consoleId)
                    ToolRow(
                        title = s.title,
                        lines = listOf(
                            console + " · " + reasonText(s.upgrade),
                            stringResource(R.string.upg7_row_current, s.owned.baseName + " · " + formatBytes(s.owned.size)),
                            stringResource(R.string.upg7_row_better, s.candidateName + sizeSuffix(s.candidate.fileSize))
                        ),
                        onClick = { viewModel.toggle(row.id) },
                        modifier = Modifier.onFocusChanged {
                            if (it.isFocused) focusedId = row.id else if (focusedId == row.id) focusedId = null
                        },
                        badge = { RowBadges(row) },
                        leading = { GameCover(s.consoleId, s.owned.coverFileName(), s.title, Modifier.size(width = 42.dp, height = 56.dp)) },
                        below = if (row.canDownload) ({
                            ToolsActions(horizontalPadding = 0.dp) {
                                ToolAction(stringResource(R.string.upg7_action_download), icon = R.drawable.ic_download, tone = ActionTone.Accent) { ask(listOf(row.id), false) }
                                ToolAction(stringResource(R.string.upg7_action_replace), icon = R.drawable.ic_trash, tone = ActionTone.Danger) { ask(listOf(row.id), true) }
                                ToolAction(stringResource(R.string.upg7_action_ignore), icon = R.drawable.ic_block) { viewModel.ignore(context, row.id) }
                            }
                        }) else null
                    )
                }
            }
        }
    }
}

private fun sizeSuffix(bytes: Long): String = if (bytes > 0) " · " + formatBytes(bytes) else ""

/** The pills after a row's title: selected, how its download stands, how the removal of the old file stands. */
@Composable
private fun RowBadges(row: BetterRow) {
    if (!row.selected && row.state == BetterRowState.READY && row.replace == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (row.selected && row.canDownload) Pill(stringResource(R.string.upg7_selected), tone = PillTone.Accent, icon = R.drawable.ic_check_circle)
        when (row.state) {
            BetterRowState.DOWNLOADING -> Pill(stringResource(R.string.upg7_state_downloading), tone = PillTone.Info, icon = R.drawable.ic_arrow_down)
            BetterRowState.DOWNLOADED -> Pill(stringResource(R.string.upg7_state_done), tone = PillTone.Success, icon = R.drawable.ic_check_circle)
            BetterRowState.FAILED -> Pill(stringResource(R.string.upg7_state_failed), tone = PillTone.Danger, icon = R.drawable.ic_warning)
            BetterRowState.READY -> Unit
        }
        when (row.replace) {
            ReplaceState.WAITING -> Pill(stringResource(R.string.upg7_state_waiting), tone = PillTone.Warning, icon = R.drawable.ic_hourglass)
            ReplaceState.KEPT, ReplaceState.FAILED -> Pill(stringResource(R.string.upg7_state_kept), tone = PillTone.Neutral, icon = R.drawable.ic_info)
            ReplaceState.REMOVED, null -> Unit
        }
    }
}

/** Why the suggested file is better, in words. */
@Composable
private fun reasonText(u: BetterVersions.Upgrade): String = when (u.reason) {
    BetterVersions.Reason.NEWER_REVISION ->
        if (u.revision != null) stringResource(R.string.upg7_reason_rev, u.revision) else stringResource(R.string.upg7_reason_rev_plain)
    BetterVersions.Reason.FINAL_RELEASE -> stringResource(
        when (u.pre) {
            BetterVersions.PreKind.BETA -> R.string.upg7_reason_final_beta
            BetterVersions.PreKind.PROTO -> R.string.upg7_reason_final_proto
            BetterVersions.PreKind.DEMO -> R.string.upg7_reason_final_demo
            BetterVersions.PreKind.SAMPLE -> R.string.upg7_reason_final_sample
            BetterVersions.PreKind.KIOSK -> R.string.upg7_reason_final_kiosk
            else -> R.string.upg7_reason_final_other
        }
    )
    BetterVersions.Reason.GOOD_DUMP -> stringResource(
        when (u.problem) {
            BetterVersions.Problem.OVERDUMP -> R.string.upg7_reason_dump_overdump
            BetterVersions.Problem.HACK -> R.string.upg7_reason_dump_hack
            BetterVersions.Problem.PIRATE -> R.string.upg7_reason_dump_pirate
            BetterVersions.Problem.MODIFIED -> R.string.upg7_reason_dump_modified
            BetterVersions.Problem.NOT_IN_DAT -> R.string.upg7_reason_dump_dat
            else -> R.string.upg7_reason_dump_bad
        }
    )
}

/**
 * Exactly what the removal will take off the device: every old game with its files, and what
 * replaces it. Nothing in the list is left out.
 */
@Composable
private fun removalList(rows: List<BetterRow>): String {
    val resources = LocalContext.current.resources
    return rows.joinToString("\n\n") { row ->
        val old = row.suggestion.owned
        val head = if (old.files.size == 1) "• " + old.files[0].name + " (" + formatBytes(old.size) + ")"
        else "• " + old.baseName + " (" + resources.getQuantityString(R.plurals.tools_files, old.files.size, old.files.size) + ", " + formatBytes(old.size) + ")" +
            old.files.joinToString("") { "\n      " + it.name }
        head + "\n   → " + row.suggestion.candidateName
    }
}

/** Confirmation that lists what goes; focus starts on Cancel and B cancels, like the other delete dialogs, and a long list scrolls. */
@Composable
private fun ReplaceDialog(message: String, enabled: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancelFocus = rememberInitialFocus()
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile(R.drawable.ic_trash, size = 36.dp, container = scheme.errorContainer, tint = scheme.onErrorContainer)
                Text(stringResource(R.string.upg25_title))
            }
        },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(message) } },
        confirmButton = { DialogButton(text = stringResource(R.string.upg7_action_download), onClick = { onConfirm(); onDismiss() }, enabled = enabled) },
        dismissButton = { DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss, initialFocus = cancelFocus) }
    )
}
