package com.tufein.valkyr.ui.screens.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tufein.valkyr.R
import com.tufein.valkyr.data.service.TransferPlace
import com.tufein.valkyr.data.service.TransferPreview
import com.tufein.valkyr.data.service.TransferRun
import com.tufein.valkyr.data.service.TransferTarget
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.EmptyState
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.formatBytes
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.CopyConflictChoice
import com.tufein.valkyr.util.GamePackages
import com.tufein.valkyr.util.PackageCopyPlan
import com.tufein.valkyr.util.RemoteSend
import com.tufein.valkyr.util.RemoteSend.Outcome
import com.tufein.valkyr.util.RemoteSend.RommDecision
import com.tufein.valkyr.util.TargetFolderLayouts

/**
 * Tools → Copy or send games (3.1.0): whole game packages, every recorded file with its folders,
 * to a folder (memory card, USB stick), to RomM or to the NAS of Settings → Cloud. A preview
 * comes first (files, space, destination, what is there, saves left behind); then each file is
 * copied or sent and checked, with a result per file and a retry for what did not make it.
 */
@Composable
fun GameTransferScreen(viewModel: GameTransferViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val run by viewModel.run.collectAsStateWithLifecycle()
    val mainFocus = remember { FocusRequester() }
    var filesOf by remember { mutableStateOf<GamePackages.Manifest?>(null) }
    var resultsOf by remember { mutableStateOf<String?>(null) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.check(uri.toString())
    }
    val current = run
    val stateKey = when {
        current?.running == true -> "running"
        current != null -> "results"
        ui.checking -> "checking"
        ui.preview != null -> "preview"
        else -> "setup:${ui.snapshot != null}"
    }
    // Something focusable is always focused: the first choice, Stop, Copy / Send or Done.
    LaunchedEffect(stateKey) {
        repeat(5) {
            withFrameNanos { }
            if (runCatching { mainFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 4.dp), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item(key = "title") { ToolsTitle(stringResource(R.string.transfer31_title), icon = R.drawable.ic_file_export, subtitle = stringResource(R.string.transfer31_subtitle)) }
        when {
            current != null -> runItems(current, mainFocus, onOpen = { resultsOf = it }, onStop = viewModel::stop, onRetry = viewModel::retry, onDone = viewModel::finish)
            ui.checking -> item(key = "checking") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.transfer31_checking), style = MaterialTheme.typography.bodyLarge)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    ActionPill(stringResource(R.string.transfer31_cancel), viewModel::cancelCheck, modifier = Modifier.focusRequester(mainFocus), icon = R.drawable.ic_close)
                }
            }
            ui.preview != null -> previewItems(ui.preview!!, mainFocus, onStart = { viewModel.start() }, onBack = viewModel::back)
            else -> setupItems(ui, mainFocus, viewModel, onFiles = { filesOf = it }, onPreview = {
                if (ui.target == TransferTarget.FOLDER) folderPicker.launch(null) else viewModel.check()
            })
        }
    }

    filesOf?.let { record ->
        val closeFocus = rememberInitialFocus()
        AlertDialog(onDismissRequest = { filesOf = null }, modifier = Modifier.closeOnGamepadB { filesOf = null },
            title = { Text(record.fileName) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    record.parts.take(MAX_LINES).forEach { part ->
                        Text(PackageCopyPlan.join(record.subPath, part.path), style = MaterialTheme.typography.bodyMedium)
                        Text(formatBytes(part.bytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    More(record.parts.size)
                }
            },
            confirmButton = { DialogButton(stringResource(R.string.transfer31_close), onClick = { filesOf = null }, initialFocus = closeFocus) })
    }
    val resultRows = resultsOf?.let { key -> current?.results.orEmpty().filter { it.gameKey == key } }.orEmpty()
    // The results went (Done, a retry, a profile switch): the dialog closes with them.
    LaunchedEffect(resultsOf, resultRows.isEmpty()) { if (resultsOf != null && resultRows.isEmpty()) resultsOf = null }
    if (resultRows.isNotEmpty()) {
        val rows = resultRows
        val closeFocus = rememberInitialFocus()
        AlertDialog(onDismissRequest = { resultsOf = null }, modifier = Modifier.closeOnGamepadB { resultsOf = null },
            title = { Text(rows.first().game) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    rows.take(MAX_LINES).forEach { row ->
                        Column {
                            Text(row.path, style = MaterialTheme.typography.bodyMedium)
                            Text(listOfNotNull(formatBytes(row.bytes), row.container?.let { stringResource(R.string.transfer31_in_zip, it) })
                                .joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(outcomeText(row.outcome) + (row.detail?.let { " $it" } ?: ""), style = MaterialTheme.typography.bodySmall,
                                color = if (RemoteSend.isDone(row.outcome)) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                        }
                    }
                    More(rows.size)
                }
            },
            confirmButton = { DialogButton(stringResource(R.string.transfer31_close), onClick = { resultsOf = null }, initialFocus = closeFocus) })
    }
}

private const val MAX_LINES = 200

@Composable
private fun More(total: Int) {
    if (total > MAX_LINES) Text(pluralStringResource(R.plurals.transfer31_more, total - MAX_LINES, total - MAX_LINES), style = MaterialTheme.typography.bodySmall)
}

// ---- Choosing ------------------------------------------------------------------------------

private fun LazyListScope.setupItems(ui: GameTransferUi, mainFocus: FocusRequester, viewModel: GameTransferViewModel,
    onFiles: (GamePackages.Manifest) -> Unit, onPreview: () -> Unit) {
    item(key = "intro") { InfoCard(listOf(stringResource(R.string.transfer31_intro)), icon = R.drawable.ic_file_export, accent = true) }
    if (ui.loadFailed) item(key = "loadFailed") {
        InfoCard(listOf(stringResource(R.string.transfer31_load_failed)), icon = R.drawable.ic_error, danger = true)
        ToolsActions { ActionPill(stringResource(R.string.tools_refresh), viewModel::load, icon = R.drawable.ic_sync) }
    }
    if (ui.snapshot?.restrictions?.active == true) item(key = "restricted") { InfoCard(listOf(stringResource(R.string.transfer31_restricted))) }

    item(key = "whereHeader") { SectionHeader(stringResource(R.string.transfer31_where)) }
    item(key = "t:folder") {
        Choice(stringResource(R.string.transfer31_target_folder), listOf(stringResource(
            if (ui.layout == TargetFolderLayouts.Layout.LIBRARY) R.string.transfer31_target_folder_note else R.string.layouts35_target_folder_note)),
            ui.target == TransferTarget.FOLDER, { viewModel.setTarget(TransferTarget.FOLDER) }, Modifier.focusRequester(mainFocus))
    }
    item(key = "t:romm") {
        val ready = ui.availability?.romm == true
        Choice(stringResource(R.string.transfer31_target_romm), listOfNotNull(stringResource(R.string.transfer31_target_romm_note),
            stringResource(R.string.transfer31_target_romm_missing).takeIf { ui.availability != null && !ready }),
            ui.target == TransferTarget.ROMM, { viewModel.setTarget(TransferTarget.ROMM) })
    }
    item(key = "t:nas") {
        val ready = ui.availability?.nas == true
        Choice(stringResource(R.string.transfer31_target_nas), listOfNotNull(stringResource(R.string.transfer31_target_nas_note),
            stringResource(R.string.transfer31_target_nas_missing).takeIf { ui.availability != null && !ready }),
            ui.target == TransferTarget.NAS, { viewModel.setTarget(TransferTarget.NAS) })
    }
    if (ui.target == TransferTarget.NAS) item(key = "readBack") {
        ToolRow(stringResource(R.string.transfer31_read_back), listOf(stringResource(R.string.transfer31_read_back_hint)), onClick = viewModel::toggleReadBack,
            icon = if (ui.readBack) R.drawable.ic_checkbox_on else R.drawable.ic_checkbox_off,
            badge = { Pill(stringResource(if (ui.readBack) R.string.transfer31_on else R.string.transfer31_off), tone = if (ui.readBack) PillTone.Accent else PillTone.Neutral) })
    }

    if (ui.target == TransferTarget.FOLDER) layoutItems(ui, viewModel)

    item(key = "conflictHeader") { SectionHeader(stringResource(R.string.transfer31_conflict_title), subtitle = stringResource(R.string.transfer31_conflict_hint)) }
    item(key = "c:skip") {
        Choice(stringResource(R.string.transfer31_conflict_skip), emptyList(), ui.choice == CopyConflictChoice.SKIP, { viewModel.setChoice(CopyConflictChoice.SKIP) })
    }
    item(key = "c:rename") {
        Choice(stringResource(R.string.transfer31_conflict_rename), emptyList(), ui.choice == CopyConflictChoice.RENAME, { viewModel.setChoice(CopyConflictChoice.RENAME) })
    }

    val records = ui.snapshot?.records.orEmpty()
    item(key = "gamesHeader") {
        SectionHeader(stringResource(R.string.transfer31_games), subtitle = stringResource(R.string.transfer31_games_count, ui.selected.size, records.size))
    }
    ui.previewError?.let { error -> item(key = "previewError") {
        InfoCard(listOf(stringResource(R.string.transfer31_preview_failed, error)), icon = R.drawable.ic_error, danger = true)
    } }
    item(key = "gamesActions") {
        ToolsActions {
            ActionPill(stringResource(if (ui.target == TransferTarget.FOLDER) R.string.transfer31_preview_folder else R.string.transfer31_preview),
                onPreview, icon = if (ui.target == TransferTarget.FOLDER) R.drawable.ic_folder_open else R.drawable.ic_check,
                tone = ActionTone.Accent, enabled = ui.canPreview)
            if (records.isNotEmpty()) {
                ActionPill(stringResource(R.string.transfer31_select_all), viewModel::selectAll, icon = R.drawable.ic_checkbox_on)
                ActionPill(stringResource(R.string.transfer31_select_none), viewModel::selectNone, icon = R.drawable.ic_checkbox_off, enabled = ui.selected.isNotEmpty())
            }
        }
    }
    if (ui.snapshot != null && records.isEmpty()) item(key = "noGames") {
        EmptyState(title = stringResource(R.string.transfer31_no_games_title), message = stringResource(R.string.transfer31_no_games),
            icon = R.drawable.ic_stacks, modifier = Modifier.fillMaxWidth())
    }
    items(records, key = { "g:" + it.sourceIdentity }) { record ->
        val selected = record.sourceIdentity in ui.selected
        ToolRow(
            title = record.fileName,
            lines = listOf(ConsoleFormatter.getConsoleDisplayName(record.consoleId),
                pluralStringResource(R.plurals.transfer31_file_count, record.parts.size, record.parts.size, formatBytes(record.parts.sumOf { it.bytes }))),
            onClick = { viewModel.toggle(record.sourceIdentity) },
            icon = if (selected) R.drawable.ic_checkbox_on else R.drawable.ic_checkbox_off,
            trailing = { ToolAction(stringResource(R.string.transfer31_files)) { onFiles(record) } }
        )
    }
}

/**
 * Folder names for another system (3.5.0): one row per layout with its explanation and what the
 * console folders of the selected games (all games while none is picked) would become.
 */
private fun LazyListScope.layoutItems(ui: GameTransferUi, viewModel: GameTransferViewModel) {
    val records = ui.snapshot?.records.orEmpty()
    val picked = records.filter { it.sourceIdentity in ui.selected }.ifEmpty { records }
    val samples = picked.distinctBy { it.consoleId }.take(3)
    item(key = "layoutHeader") { SectionHeader(stringResource(R.string.layouts35_title), subtitle = stringResource(R.string.layouts35_hint)) }
    TargetFolderLayouts.Layout.entries.forEach { layout ->
        item(key = "layout:" + layout.key) {
            val examples = samples.mapNotNull { record ->
                val from = record.subPath.ifEmpty { ConsoleFormatter.getConsoleShortName(record.consoleId) }
                val to = TargetFolderLayouts.destinationFolder(layout, record.consoleId, record.subPath, null)
                when {
                    layout == TargetFolderLayouts.Layout.LIBRARY && record.subPath.isEmpty() -> null
                    to == null -> stringResource(R.string.layouts35_example_none, from)
                    else -> stringResource(R.string.layouts35_example_pair, from, to)
                }
            }
            Choice(stringResource(layoutTitle(layout)), listOfNotNull(stringResource(layoutHint(layout)),
                examples.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.layouts35_example, it.joinToString(", ")) }),
                ui.layout == layout, { viewModel.setLayout(layout) })
        }
    }
    if (ui.layout != TargetFolderLayouts.Layout.LIBRARY) item(key = "layoutNote") {
        InfoCard(listOf(stringResource(R.string.layouts35_note)), icon = R.drawable.ic_info)
    }
    exportNoteOf(ui.layout)?.let { note -> item(key = "exp60Note") { InfoCard(listOf(stringResource(note)), icon = R.drawable.ic_info) } }
}

/** 6.0: the BIOS folder and game list advice of systems whose documentation gives it (see [TargetFolderLayouts.biosFolder]). */
private fun exportNoteOf(layout: TargetFolderLayouts.Layout): Int? = when (layout) {
    TargetFolderLayouts.Layout.BATOCERA -> R.string.exp60_note_batocera
    TargetFolderLayouts.Layout.KNULLI -> R.string.exp60_note_knulli
    TargetFolderLayouts.Layout.ROCKNIX -> R.string.exp60_note_rocknix
    TargetFolderLayouts.Layout.MUOS -> R.string.exp60_note_muos
    else -> null
}

private fun layoutTitle(layout: TargetFolderLayouts.Layout): Int = when (layout) {
    TargetFolderLayouts.Layout.LIBRARY -> R.string.layouts35_library
    TargetFolderLayouts.Layout.ES_DE -> R.string.layouts35_esde
    TargetFolderLayouts.Layout.BATOCERA -> R.string.layouts35_batocera
    TargetFolderLayouts.Layout.KNULLI -> R.string.layouts35_knulli
    TargetFolderLayouts.Layout.ARKOS -> R.string.layouts35_arkos
    TargetFolderLayouts.Layout.ROCKNIX -> R.string.layouts35_rocknix
    TargetFolderLayouts.Layout.ONION -> R.string.layouts35_onion
    TargetFolderLayouts.Layout.MUOS -> R.string.layouts35_muos
}

private fun layoutHint(layout: TargetFolderLayouts.Layout): Int = when (layout) {
    TargetFolderLayouts.Layout.LIBRARY -> R.string.layouts35_library_hint
    TargetFolderLayouts.Layout.ES_DE -> R.string.layouts35_esde_hint
    TargetFolderLayouts.Layout.BATOCERA -> R.string.layouts35_batocera_hint
    TargetFolderLayouts.Layout.KNULLI -> R.string.layouts35_knulli_hint
    TargetFolderLayouts.Layout.ARKOS -> R.string.layouts35_arkos_hint
    TargetFolderLayouts.Layout.ROCKNIX -> R.string.layouts35_rocknix_hint
    TargetFolderLayouts.Layout.ONION -> R.string.layouts35_onion_hint
    TargetFolderLayouts.Layout.MUOS -> R.string.layouts35_muos_hint
}

/** A row that is one of several options (radio). */
@Composable
private fun Choice(title: String, lines: List<String>, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ToolRow(title, lines, onClick = onClick, modifier = modifier, icon = if (selected) R.drawable.ic_radio_on else R.drawable.ic_radio_off)
}

// ---- Preview -------------------------------------------------------------------------------

private fun LazyListScope.previewItems(preview: TransferPreview, mainFocus: FocusRequester, onStart: () -> Unit, onBack: () -> Unit) {
    val copy = preview.copy
    val romm = preview.romm
    item(key = "summary") {
        val lines = ArrayList<String>()
        lines += stringResource(R.string.transfer31_to, placeText(preview.place))
        if (preview.layout != TargetFolderLayouts.Layout.LIBRARY) {
            lines += stringResource(R.string.layouts35_preview_names, stringResource(layoutTitle(preview.layout)))
            if (preview.consoleFolders.isNotEmpty()) lines += stringResource(R.string.layouts35_preview_folders,
                preview.consoleFolders.take(8).joinToString(", ") + if (preview.consoleFolders.size > 8)
                    ", " + pluralStringResource(R.plurals.transfer31_more, preview.consoleFolders.size - 8, preview.consoleFolders.size - 8) else "")
        }
        lines += stringResource(if (preview.target == TransferTarget.FOLDER) R.string.transfer31_to_copy else R.string.transfer31_to_send, formatBytes(preview.bytesToSend))
        if (copy != null && copy.bytesPresent > 0) lines += stringResource(R.string.transfer31_present, formatBytes(copy.bytesPresent))
        if (preview.target == TransferTarget.FOLDER) copy?.let { plan ->
            lines += plan.freeBytes?.let { stringResource(R.string.transfer31_free, formatBytes(it)) } ?: stringResource(R.string.transfer31_free_unknown)
        }
        val noSpace = copy?.enoughSpace == false
        InfoCard(lines, icon = if (noSpace) R.drawable.ic_warning else R.drawable.ic_file_export, accent = !noSpace, danger = noSpace)
    }
    if (copy != null && copy.enoughSpace == false) item(key = "noSpace") {
        InfoCard(listOf(stringResource(R.string.transfer31_no_space, formatBytes(copy.bytesToCopy), formatBytes(copy.freeBytes ?: 0L),
            formatBytes(PackageCopyPlan.MARGIN_BYTES))), danger = true)
    }
    if (preview.place.appFolder) item(key = "appFolder") { InfoCard(listOf(stringResource(R.string.transfer31_app_folder)), icon = R.drawable.ic_info) }
    if (preview.layoutIgnored) item(key = "layoutIgnored") { InfoCard(listOf(stringResource(R.string.layouts35_app_folder)), icon = R.drawable.ic_info) }
    // What is left out or may be wrong comes before the buttons: Copy holds the focus.
    layoutPreviewItems(preview)
    item(key = "actions") {
        ToolsActions {
            ActionPill(stringResource(if (preview.target == TransferTarget.FOLDER) R.string.transfer31_start_copy else R.string.transfer31_start_send),
                onStart, modifier = Modifier.focusRequester(mainFocus), icon = R.drawable.ic_check, tone = ActionTone.Accent, enabled = preview.canStart)
            ActionPill(stringResource(R.string.transfer31_back), onBack, icon = R.drawable.ic_close)
        }
    }
    if (preview.unavailable.isNotEmpty()) item(key = "unavailable") {
        InfoCard(listOf(stringResource(R.string.transfer31_unavailable_title)) + preview.unavailable.map {
            stringResource(R.string.transfer31_unavailable_line, it.title, it.missing, it.changed)
        }, icon = R.drawable.ic_warning, danger = true)
    }
    val saves = copy?.saves ?: romm.orEmpty().flatMap { it.saves }
    if (saves.isNotEmpty()) item(key = "saves") {
        InfoCard(listOf(stringResource(R.string.transfer31_saves_title), pluralStringResource(R.plurals.transfer31_saves, saves.size, saves.size)) +
            saves.take(30) + listOfNotNull(if (saves.size > 30) pluralStringResource(R.plurals.transfer31_more, saves.size - 30, saves.size - 30) else null),
            icon = R.drawable.ic_save)
    }
    if (copy != null) {
        if (copy.sharedFiles > 0) item(key = "shared") { InfoCard(listOf(pluralStringResource(R.plurals.transfer31_shared, copy.sharedFiles, copy.sharedFiles))) }
        val special = copy.games.filter { it.decision != PackageCopyPlan.Decision.COPY }
        items(special, key = { "s:" + it.key }) { game ->
            val (text, tone) = when (game.decision) {
                PackageCopyPlan.Decision.SKIPPED -> stringResource(R.string.transfer31_skipped) to PillTone.Warning
                PackageCopyPlan.Decision.RENAMED -> stringResource(R.string.transfer31_renamed, game.root) to PillTone.Info
                else -> stringResource(R.string.transfer31_only_saves) to PillTone.Neutral
            }
            ToolRow(game.title, listOf(text) + game.conflicts.take(5).map { stringResource(R.string.transfer31_in_the_way, it) }, onClick = {},
                icon = R.drawable.ic_stacks, badge = { Pill(ConsoleFormatter.getConsoleShortName(game.consoleId), tone = tone) })
        }
        item(key = "filesHeader") { SectionHeader(stringResource(R.string.transfer31_files), subtitle = pluralStringResource(R.plurals.transfer31_file_count, copy.files.size, copy.files.size, formatBytes(copy.files.sumOf { it.bytes }))) }
        items(copy.files, key = { "f:" + it.target }) { file ->
            val state = stringResource(if (file.state == PackageCopyPlan.FileState.NEW) R.string.transfer31_state_new else R.string.transfer31_state_present)
            ToolRow(file.target, listOfNotNull("${formatBytes(file.bytes)} · $state",
                file.games.takeIf { file.shared }?.let { stringResource(R.string.transfer31_shared_by, it.joinToString(", ")) }), onClick = {})
        }
    }
    if (romm != null) items(romm, key = { "r:" + it.key }) { game ->
        val text = when (game.decision) {
            RommDecision.SEND -> if (game.zipped) pluralStringResource(R.plurals.transfer31_romm_zip, game.parts.size, game.parts.size) else stringResource(R.string.transfer31_romm_single)
            RommDecision.RENAMED -> stringResource(R.string.transfer31_romm_renamed, game.name)
            RommDecision.PRESENT -> stringResource(R.string.transfer31_romm_present)
            RommDecision.SKIPPED -> stringResource(R.string.transfer31_romm_skipped, game.name)
            RommDecision.NO_PLATFORM -> stringResource(R.string.transfer31_romm_no_platform)
            RommDecision.TOO_LARGE -> stringResource(R.string.transfer31_romm_too_large)
            RommDecision.ONLY_SAVES -> stringResource(R.string.transfer31_romm_only_saves)
        }
        val tone = when (game.decision) {
            RommDecision.SEND, RommDecision.RENAMED -> PillTone.Accent
            RommDecision.PRESENT -> PillTone.Success
            else -> PillTone.Warning
        }
        ToolRow(game.name.ifEmpty { game.title }, listOfNotNull(game.title.takeIf { it != game.name }, text,
            game.bytes.takeIf { it > 0 }?.let { formatBytes(it) }) + (if (game.zipped && game.sends) game.parts.take(10).map { it.path } else emptyList()),
            onClick = {}, icon = R.drawable.ic_cloud_upload, badge = { Pill(ConsoleFormatter.getConsoleShortName(game.consoleId), tone = tone) })
    }
}

/** What the chosen folder names leave out or may get wrong (3.5.0); nothing for the library's own names. */
private fun LazyListScope.layoutPreviewItems(preview: TransferPreview) {
    if (preview.layout == TargetFolderLayouts.Layout.LIBRARY) return
    if (preview.unsupported.isNotEmpty()) item(key = "layoutUnsupported") {
        InfoCard(listOf(stringResource(R.string.layouts35_unsupported_title, stringResource(layoutTitle(preview.layout)))) +
            preview.unsupported.take(MAX_LINES).map { stringResource(R.string.layouts35_unsupported_line, it.title, ConsoleFormatter.getConsoleDisplayName(it.consoleId).replace('\n', ' ')) } +
            listOfNotNull(if (preview.unsupported.size > MAX_LINES) pluralStringResource(R.plurals.transfer31_more, preview.unsupported.size - MAX_LINES, preview.unsupported.size - MAX_LINES) else null),
            icon = R.drawable.ic_warning)
    }
    if (preview.caseDifferences.isNotEmpty()) item(key = "layoutCase") {
        InfoCard(listOf(stringResource(R.string.layouts35_case_title, stringResource(layoutTitle(preview.layout)))) +
            preview.caseDifferences.map { stringResource(R.string.layouts35_case_line, it.actual, it.expected) },
            icon = R.drawable.ic_warning, danger = true)
    }
    if (preview.tooDeep.isNotEmpty()) item(key = "layoutDeep") {
        InfoCard(listOf(stringResource(R.string.layouts35_deep_title, stringResource(layoutTitle(preview.layout)))) +
            preview.tooDeep.take(30) + listOfNotNull(if (preview.tooDeep.size > 30) pluralStringResource(R.plurals.transfer31_more, preview.tooDeep.size - 30, preview.tooDeep.size - 30) else null),
            icon = R.drawable.ic_warning)
    }
    if (preview.apostrophes.isNotEmpty()) item(key = "layoutApostrophe") {
        InfoCard(listOf(stringResource(R.string.layouts36_apostrophe_title, stringResource(layoutTitle(preview.layout)))) +
            preview.apostrophes.take(30) + listOfNotNull(if (preview.apostrophes.size > 30) pluralStringResource(R.plurals.transfer31_more, preview.apostrophes.size - 30, preview.apostrophes.size - 30) else null),
            icon = R.drawable.ic_warning)
    }
    item(key = "layoutNote") { InfoCard(listOf(stringResource(R.string.layouts35_note)), icon = R.drawable.ic_info) }
}

@Composable
private fun placeText(place: TransferPlace): String = when (place.target) {
    TransferTarget.FOLDER -> {
        val volume = if (place.volume == null || place.volume == "primary") stringResource(R.string.transfer31_volume_internal)
            else stringResource(R.string.transfer31_volume_external, place.volume)
        if (place.path.isBlank()) volume else "$volume / ${place.path}"
    }
    TransferTarget.ROMM -> stringResource(R.string.transfer31_place_romm, place.path)
    TransferTarget.NAS -> stringResource(R.string.transfer31_place_nas, place.path)
}

// ---- Running and results -------------------------------------------------------------------

private fun LazyListScope.runItems(run: TransferRun, mainFocus: FocusRequester, onOpen: (String) -> Unit, onStop: () -> Unit,
    onRetry: () -> Unit, onDone: () -> Unit) {
    item(key = "place") {
        InfoCard(listOf(stringResource(R.string.transfer31_to, placeText(run.preview.place))) + listOfNotNull(
            run.preview.layout.takeIf { it != TargetFolderLayouts.Layout.LIBRARY }?.let { stringResource(R.string.layouts35_preview_names, stringResource(layoutTitle(it))) }))
    }
    if (run.running) {
        item(key = "progress") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val p = run.progress
                if (p != null) Text(stringResource(R.string.transfer31_progress, p.filesDone + 1, p.filesTotal, p.file), style = MaterialTheme.typography.bodyLarge)
                if (p != null && p.bytesTotal > 0) {
                    LinearProgressIndicator(progress = { (p.bytesDone.toFloat() / p.bytesTotal).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.transfer31_progress_bytes, formatBytes(p.bytesDone), formatBytes(p.bytesTotal)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        item(key = "stop") { ToolsActions { ActionPill(stringResource(R.string.transfer31_stop), onStop, modifier = Modifier.focusRequester(mainFocus), icon = R.drawable.ic_close) } }
    } else {
        val done = run.results.count { RemoteSend.isDone(it.outcome) }
        val ok = run.results.isNotEmpty() && done == run.results.size
        item(key = "outcome") {
            InfoCard(listOf(stringResource(if (ok) R.string.transfer31_result_ok else R.string.transfer31_result_some),
                stringResource(R.string.transfer31_result_counts, done, run.results.size)),
                icon = if (ok) R.drawable.ic_check_circle else R.drawable.ic_warning, accent = ok, danger = !ok)
        }
        if (run.stopped) item(key = "stopped") { InfoCard(listOf(stringResource(R.string.transfer31_stopped))) }
        run.error?.let { error -> item(key = "error") { InfoCard(listOf(stringResource(R.string.transfer31_run_error, error)), icon = R.drawable.ic_error, danger = true) } }
        item(key = "after") {
            ToolsActions {
                ActionPill(stringResource(R.string.transfer31_done), onDone, modifier = Modifier.focusRequester(mainFocus), icon = R.drawable.ic_check, tone = ActionTone.Accent)
                val retry = run.retryKeys.size
                if (retry > 0) ActionPill(pluralStringResource(R.plurals.transfer31_retry, retry, retry), onRetry, icon = R.drawable.ic_sync)
            }
        }
    }
    val games = run.results.groupBy { it.gameKey }.values.toList()
    items(games, key = { "res:" + it.first().gameKey }) { rows ->
        val worst = RemoteSend.gameOutcome(rows.map { it.outcome })
        ToolRow(rows.first().game, listOf(stringResource(R.string.transfer31_result_counts, rows.count { RemoteSend.isDone(it.outcome) }, rows.size),
            outcomeText(worst)), onClick = { onOpen(rows.first().gameKey) }, icon = R.drawable.ic_stacks,
            badge = { Pill(outcomeShort(worst), tone = outcomeTone(worst)) }, chevron = true)
    }
}

@Composable
private fun outcomeText(o: Outcome): String = stringResource(when (o) {
    Outcome.SENT_HASH -> R.string.transfer31_o_sent_hash
    Outcome.SENT_SIZE -> R.string.transfer31_o_sent_size
    Outcome.PRESENT_HASH -> R.string.transfer31_o_present_hash
    Outcome.PRESENT_SIZE -> R.string.transfer31_o_present_size
    Outcome.WAITING -> R.string.transfer31_o_waiting
    Outcome.CONFLICT -> R.string.transfer31_o_conflict
    Outcome.CHANGED -> R.string.transfer31_o_changed
    Outcome.FAILED -> R.string.transfer31_o_failed
    Outcome.NOT_SENT -> R.string.transfer31_o_not_sent
})

@Composable
private fun outcomeShort(o: Outcome): String = stringResource(when (o) {
    Outcome.SENT_HASH, Outcome.SENT_SIZE -> R.string.transfer31_s_ok
    Outcome.PRESENT_HASH, Outcome.PRESENT_SIZE -> R.string.transfer31_s_present
    Outcome.WAITING -> R.string.transfer31_s_waiting
    Outcome.CONFLICT -> R.string.transfer31_s_conflict
    Outcome.CHANGED -> R.string.transfer31_s_changed
    Outcome.FAILED -> R.string.transfer31_o_failed
    Outcome.NOT_SENT -> R.string.transfer31_o_not_sent
})

private fun outcomeTone(o: Outcome): PillTone = when (o) {
    Outcome.SENT_HASH, Outcome.SENT_SIZE, Outcome.PRESENT_HASH, Outcome.PRESENT_SIZE -> PillTone.Success
    Outcome.WAITING, Outcome.NOT_SENT -> PillTone.Info
    Outcome.CONFLICT -> PillTone.Warning
    Outcome.CHANGED, Outcome.FAILED -> PillTone.Danger
}
