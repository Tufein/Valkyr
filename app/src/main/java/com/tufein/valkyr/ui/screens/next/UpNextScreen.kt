package com.tufein.valkyr.ui.screens.next

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.common.Gamepad
import com.tufein.valkyr.ui.common.GamepadButton
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.EmptyState
import com.tufein.valkyr.ui.components.GameCover
import com.tufein.valkyr.ui.components.LegendEntry
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.ui.components.stripExtension
import com.tufein.valkyr.ui.screens.tools.PublishLegend
import com.tufein.valkyr.ui.screens.tools.SectionHeader
import com.tufein.valkyr.ui.screens.tools.ToolRow
import com.tufein.valkyr.ui.screens.tools.ToolsActions
import com.tufein.valkyr.ui.screens.tools.ToolsTitle
import com.tufein.valkyr.ui.theme.consoleColor
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.ForYou
import com.tufein.valkyr.util.ToastUtil
import com.tufein.valkyr.util.UpNextState

/**
 * Up next (3.0.0): the player's own backlog and "Suggested for you", as two tabs (LB / RB on a
 * gamepad).
 *
 * Up next: the games still to play in the player's order, then the finished ones. A / tap opens
 * the options of a game (Play or Download, playing / finished, move, remove); X / Y move the
 * focused game up / down. Play opens the game page, whose Play button starts the game; Download
 * queues it like the game page does.
 *
 * For you: suggestions worked out on the device from the player's own data, each with the reasons
 * it was picked for. A / tap opens the game page, X (or the pill) puts it on Up next.
 *
 * @param onOpenGame opens the game page (console id, library file name).
 */
@Composable
fun UpNextScreen(onOpenGame: (String, String) -> Unit, viewModel: UpNextViewModel = hiltViewModel()) {
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val forYou by viewModel.forYou.collectAsStateWithLifecycle()
    val upNextKeys by viewModel.upNextKeys.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var focusedKey by remember { mutableStateOf<String?>(null) }
    var options by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    val emptyFocus = remember { FocusRequester() }

    val failedMessage = stringResource(R.string.next30_save_failed)
    val addedTemplate = stringResource(R.string.next30_added, "%s")
    val startedTemplate = stringResource(R.string.download_started, "%s")
    val alreadyTemplate = stringResource(R.string.download_already_active, "%s")
    val noFolderMessage = stringResource(R.string.error_download_dir_missing)
    val folderGoneMessage = stringResource(R.string.error_download_dir_inaccessible)
    val goneMessage = stringResource(R.string.next30_gone)
    val saved: (Boolean) -> Unit = { ok -> if (!ok) ToastUtil.showError(context, failedMessage) }

    fun add(s: ForYou.Suggestion) {
        val title = stripExtension(s.row.name)
        viewModel.add(s) { ok -> if (ok) ToastUtil.showSuccess(context, addedTemplate.format(title)) else ToastUtil.showError(context, failedMessage) }
    }
    fun download(row: UpNextRow) {
        val title = row.entry.title
        viewModel.download(row, context) { result ->
            when (result) {
                UpNextDownload.STARTED -> ToastUtil.showSuccess(context, startedTemplate.format(title))
                UpNextDownload.ALREADY -> ToastUtil.showInfo(context, alreadyTemplate.format(title))
                UpNextDownload.NO_FOLDER -> ToastUtil.showError(context, noFolderMessage)
                UpNextDownload.FOLDER_GONE -> ToastUtil.showError(context, folderGoneMessage)
                UpNextDownload.NOT_LISTED -> ToastUtil.showError(context, goneMessage)
            }
        }
    }

    // The first tab chip takes focus, so the D-pad always has a place to start.
    LaunchedEffect(Unit) { firstFocus.requestFocusWhenReady() }

    val focusedRow = focusedKey?.removePrefix(ROW)?.takeIf { focusedKey?.startsWith(ROW) == true }?.let { k -> rows?.firstOrNull { it.key == k } }
    val focusedSuggestion = focusedKey?.removePrefix(SUGGESTION)?.takeIf { focusedKey?.startsWith(SUGGESTION) == true }
        ?.let { k -> forYou.result?.suggestions?.firstOrNull { it.key == k } }
    val currentRow by rememberUpdatedState(focusedRow)
    val currentSuggestion by rememberUpdatedState(focusedSuggestion)
    val currentKeys by rememberUpdatedState(upNextKeys)
    LaunchedEffect(Unit) {
        Gamepad.presses.collect { button ->
            when (button) {
                GamepadButton.PREV_PANEL, GamepadButton.NEXT_PANEL -> {
                    viewModel.stepTab(if (button == GamepadButton.PREV_PANEL) -1 else 1)
                    // The focused row is not in the other tab: focus goes back to the chips.
                    focusedKey = null
                    withFrameNanos { }
                    runCatching { firstFocus.requestFocus() }
                }
                GamepadButton.X -> {
                    currentRow?.let { if (it.canMoveUp) viewModel.move(it.key, -1, saved) }
                    currentSuggestion?.let { if (it.key !in currentKeys) add(it) }
                }
                GamepadButton.Y -> currentRow?.let { if (it.canMoveDown) viewModel.move(it.key, 1, saved) }
                else -> Unit
            }
        }
    }

    PublishLegend(
        listOfNotNull(
            when {
                focusedRow != null -> LegendEntry("A", stringResource(R.string.next30_pad_options))
                focusedSuggestion != null -> LegendEntry("A", stringResource(R.string.pad_open))
                else -> null
            },
            if (focusedRow != null && (focusedRow.canMoveUp || focusedRow.canMoveDown)) LegendEntry("X · Y", stringResource(R.string.next30_pad_move)) else null,
            if (focusedSuggestion != null && focusedSuggestion.key !in upNextKeys) LegendEntry("X", stringResource(R.string.next30_add)) else null,
            LegendEntry("LB · RB", stringResource(R.string.next30_pad_tabs)),
            LegendEntry("B", stringResource(R.string.pad_back))
        )
    )

    val optionsRow = options?.let { key -> rows?.firstOrNull { it.key == key } }
    // The game left the list (removed elsewhere, or hidden by a profile switch): its options go too.
    LaunchedEffect(options, optionsRow) { if (options != null && optionsRow == null && rows != null) options = null }
    optionsRow?.let { row ->
        OptionsDialog(
            row,
            onDismiss = { options = null },
            onPlay = { options = null; onOpenGame(row.entry.consoleId, row.entry.fileName) },
            onDownload = { options = null; download(row) },
            onOpen = { options = null; onOpenGame(row.entry.consoleId, row.entry.fileName) },
            onState = { state -> options = null; viewModel.setState(row.key, state, saved) },
            onMove = { delta -> viewModel.move(row.key, delta, saved) },
            onRemove = { options = null; viewModel.remove(row.key, saved) }
        )
    }
    val finishedCount = rows?.count { it.entry.state == UpNextState.FINISHED } ?: 0
    if (confirmClear) ClearDialog(finishedCount, onConfirm = { confirmClear = false; viewModel.clearFinished(saved) }, onDismiss = { confirmClear = false })

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.next30_title), icon = R.drawable.ic_playlist_play, subtitle = stringResource(R.string.next30_subtitle))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "tabs") {
                ToolsActions {
                    TabChip(stringResource(R.string.next30_tab_up_next), tab == UpNextTab.UP_NEXT, Modifier.focusRequester(firstFocus)) { viewModel.setTab(UpNextTab.UP_NEXT) }
                    TabChip(stringResource(R.string.next30_tab_for_you), tab == UpNextTab.FOR_YOU, Modifier) { viewModel.setTab(UpNextTab.FOR_YOU) }
                }
            }
            if (tab == UpNextTab.UP_NEXT) {
                val list = rows
                when {
                    list == null -> item(key = "loading") { Progress() }
                    list.isEmpty() -> item(key = "empty") {
                        EmptyState(
                            title = stringResource(R.string.next30_empty_title),
                            message = stringResource(R.string.next30_empty_message),
                            illustration = R.drawable.brand_illustration,
                            actionLabel = stringResource(R.string.next30_empty_action),
                            onAction = { viewModel.setTab(UpNextTab.FOR_YOU) },
                            actionFocus = emptyFocus,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    else -> {
                        val open = list.filter { it.entry.state != UpNextState.FINISHED }
                        val done = list.filter { it.entry.state == UpNextState.FINISHED }
                        if (open.isNotEmpty()) item(key = "h-open") { SectionHeader(stringResource(R.string.next30_to_play_header)) }
                        items(open, key = { ROW + it.key }) { r ->
                            UpNextItem(r, focusedKey, { focusedKey = it }, onClick = { options = r.key }, onPlay = { onOpenGame(r.entry.consoleId, r.entry.fileName) }, onDownload = { download(r) })
                        }
                        if (done.isNotEmpty()) {
                            item(key = "h-done") { SectionHeader(stringResource(R.string.next30_finished_header)) }
                            items(done, key = { ROW + it.key }) { r ->
                                UpNextItem(r, focusedKey, { focusedKey = it }, onClick = { options = r.key }, onPlay = { onOpenGame(r.entry.consoleId, r.entry.fileName) }, onDownload = { download(r) })
                            }
                            item(key = "clear") {
                                ToolsActions(modifier = Modifier.padding(top = 8.dp)) {
                                    ActionPill(stringResource(R.string.next30_clear_finished), { confirmClear = true }, icon = R.drawable.ic_remove, tone = ActionTone.Danger)
                                }
                            }
                        }
                    }
                }
            } else {
                item(key = "fy-head") {
                    SectionHeader(stringResource(R.string.next30_for_you_title), subtitle = stringResource(R.string.next30_for_you_intro))
                    ToolsActions {
                        ActionPill(stringResource(R.string.tools_refresh), { viewModel.loadForYou(force = true) }, icon = R.drawable.ic_sync, enabled = !forYou.loading)
                    }
                }
                val result = forYou.result
                when {
                    forYou.loading -> item(key = "fy-loading") {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.next30_working), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    result == null -> Unit
                    result.suggestions.isEmpty() -> item(key = "fy-empty") {
                        EmptyState(
                            title = stringResource(R.string.next30_for_you_empty_title),
                            message = stringResource(R.string.next30_for_you_empty_message),
                            icon = R.drawable.ic_sparkle,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    else -> items(result.suggestions, key = { SUGGESTION + it.key }) { s ->
                        SuggestionItem(s, s.key in upNextKeys, focusedKey, { focusedKey = it }, onOpen = { onOpenGame(s.row.consoleId, s.row.fileName) }, onAdd = { add(s) })
                    }
                }
            }
        }
    }
}

private const val ROW = "u:"
private const val SUGGESTION = "s:"

@Composable
private fun TabChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    ActionPill(label, onClick = onClick, modifier = modifier, icon = if (selected) R.drawable.ic_check else null, tone = if (selected) ActionTone.Accent else ActionTone.Neutral)
}

@Composable
private fun Progress() {
    Column(Modifier.padding(16.dp)) { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
}

@Composable
internal fun stateText(state: UpNextState): String = stringResource(
    when (state) {
        UpNextState.QUEUED -> R.string.next30_state_queued
        UpNextState.STARTED -> R.string.next30_state_started
        UpNextState.FINISHED -> R.string.next30_state_finished
    }
)

/** Keeps [focusedKey] in step with the focus of the row keyed [key]. */
private fun Modifier.trackFocus(key: String, focusedKey: String?, onFocus: (String?) -> Unit): Modifier =
    onFocusChanged { state -> if (state.isFocused) onFocus(key) else if (focusedKey == key) onFocus(null) }

@Composable
private fun SmallCover(consoleId: String, fileName: String, title: String) {
    GameCover(consoleId = consoleId, fileName = fileName, title = title, modifier = Modifier.size(width = 40.dp, height = 52.dp), shape = RoundedCornerShape(6.dp), showLabel = false)
}

@Composable
private fun ConsoleLine(consoleId: String) {
    Row(modifier = Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill(ConsoleFormatter.getConsoleShortName(consoleId), tone = PillTone.Tint(consoleColor(consoleId)))
    }
}

/**
 * One game of the list: cover, title, where it is (on the device, downloading, not yet, gone from
 * the sources) and, for touch, Play or Download at the end. A / tap opens its options.
 */
@Composable
private fun UpNextItem(
    row: UpNextRow,
    focusedKey: String?,
    onFocus: (String?) -> Unit,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onDownload: () -> Unit
) {
    val key = ROW + row.key
    DisposableEffect(key) { onDispose { if (focusedKey == key) onFocus(null) } }
    val where = stringResource(
        when {
            row.file == null -> R.string.next30_gone
            row.owned -> R.string.next30_on_device
            row.downloading -> R.string.page8_downloading
            else -> R.string.next30_not_downloaded
        }
    )
    ToolRow(
        title = row.entry.title,
        lines = listOf(where),
        onClick = onClick,
        modifier = Modifier.trackFocus(key, focusedKey, onFocus),
        leading = { SmallCover(row.entry.consoleId, row.entry.fileName, row.entry.title) },
        badge = when (row.entry.state) {
            UpNextState.QUEUED -> null
            UpNextState.STARTED -> { { Pill(stateText(row.entry.state), tone = PillTone.Accent, icon = R.drawable.ic_play_arrow) } }
            UpNextState.FINISHED -> { { Pill(stateText(row.entry.state), tone = PillTone.Success, icon = R.drawable.ic_check) } }
        },
        below = { ConsoleLine(row.entry.consoleId) },
        trailing = {
            // For touch; on a gamepad the options (A) hold the same, so focus never sits on a button that goes away.
            val noFocus = Modifier.focusProperties { canFocus = false }
            when {
                row.owned && row.file != null -> ActionPill(stringResource(R.string.play_game), onPlay, noFocus, icon = R.drawable.ic_controller, tone = ActionTone.Accent)
                row.file != null && !row.downloading -> ActionPill(stringResource(R.string.details_download), onDownload, noFocus, icon = R.drawable.ic_download)
            }
        }
    )
}

/** One suggestion: cover, title, the reasons it was picked, and "Add to Up next" (X / the pill). A / tap opens its page. */
@Composable
private fun SuggestionItem(
    s: ForYou.Suggestion,
    onList: Boolean,
    focusedKey: String?,
    onFocus: (String?) -> Unit,
    onOpen: () -> Unit,
    onAdd: () -> Unit
) {
    val key = SUGGESTION + s.key
    DisposableEffect(key) { onDispose { if (focusedKey == key) onFocus(null) } }
    val title = stripExtension(s.row.name)
    ToolRow(
        title = title,
        lines = ForYou.shown(s.reasons).map { reasonText(it) },
        onClick = onOpen,
        modifier = Modifier.trackFocus(key, focusedKey, onFocus),
        leading = { SmallCover(s.row.consoleId, s.row.fileName, title) },
        below = { ConsoleLine(s.row.consoleId) },
        trailing = {
            val noFocus = Modifier.focusProperties { canFocus = false }
            if (onList) ActionPill(stringResource(R.string.next30_on_list), {}, noFocus, icon = R.drawable.ic_check, tone = ActionTone.Accent, enabled = false)
            else ActionPill(stringResource(R.string.next30_add), onAdd, noFocus, icon = R.drawable.ic_playlist_add)
        }
    )
}

/** A reason in words; every one names only facts the app has (see [ForYou]). */
@Composable
private fun reasonText(r: ForYou.Reason): String = when (r.kind) {
    ForYou.ReasonKind.BECAUSE -> stringResource(
        when (r.seedKind) {
            ForYou.SeedKind.PLAYED -> R.string.next30_why_played
            ForYou.SeedKind.FAVOURITE -> R.string.next30_why_favourite
            ForYou.SeedKind.UP_NEXT -> R.string.next30_why_up_next
            ForYou.SeedKind.COLLECTION -> R.string.next30_why_collection
            ForYou.SeedKind.DOWNLOADED -> R.string.next30_why_downloaded
            ForYou.SeedKind.OWNED, null -> R.string.next30_why_owned
        },
        r.title
    )
    ForYou.ReasonKind.SERIES -> stringResource(R.string.next30_why_series, r.title)
    ForYou.ReasonKind.POPULAR -> stringResource(
        if (r.byPlayers) R.string.next30_why_popular_players else R.string.next30_why_popular_points,
        ConsoleFormatter.getConsoleDisplayName(r.consoleId)
    )
    ForYou.ReasonKind.GENRE -> stringResource(R.string.next30_why_genre, r.genre)
    ForYou.ReasonKind.DECADE -> stringResource(R.string.next30_why_decade, r.decade, r.decade + 9)
    ForYou.ReasonKind.FEW_ON_CONSOLE -> pluralStringResource(R.plurals.next30_why_few, r.count, r.count, ConsoleFormatter.getConsoleDisplayName(r.consoleId))
}

/** What can be done with one game of the list; every choice is a focusable pill (D-pad and touch). */
@Composable
private fun OptionsDialog(
    row: UpNextRow,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onOpen: () -> Unit,
    onState: (UpNextState) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit
) {
    val first = rememberInitialFocus()
    val state = row.entry.state
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(row.entry.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stateText(state), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ToolsActions(horizontalPadding = 0.dp) {
                    val focus = Modifier.focusRequester(first)
                    when {
                        row.owned && row.file != null -> ActionPill(stringResource(R.string.play_game), onPlay, focus, icon = R.drawable.ic_controller, tone = ActionTone.Accent)
                        row.file != null -> {
                            if (!row.downloading) ActionPill(stringResource(R.string.details_download), onDownload, focus, icon = R.drawable.ic_download, tone = ActionTone.Accent)
                            ActionPill(stringResource(R.string.next30_open_page), onOpen, if (row.downloading) focus else Modifier, icon = R.drawable.ic_arrow_forward)
                        }
                        else -> Unit
                    }
                    // A gone game has no Play / Download / page: its first choice takes the focus.
                    val stateFocus = if (row.file == null) focus else Modifier
                    when (state) {
                        UpNextState.QUEUED -> {
                            ActionPill(stringResource(R.string.next30_mark_started), { onState(UpNextState.STARTED) }, stateFocus, icon = R.drawable.ic_play_arrow)
                            ActionPill(stringResource(R.string.next30_mark_finished), { onState(UpNextState.FINISHED) }, icon = R.drawable.ic_check)
                        }
                        UpNextState.STARTED -> {
                            ActionPill(stringResource(R.string.next30_mark_finished), { onState(UpNextState.FINISHED) }, stateFocus, icon = R.drawable.ic_check)
                            ActionPill(stringResource(R.string.next30_mark_queued), { onState(UpNextState.QUEUED) }, icon = R.drawable.ic_playlist_play)
                        }
                        UpNextState.FINISHED -> {
                            ActionPill(stringResource(R.string.next30_mark_started), { onState(UpNextState.STARTED) }, stateFocus, icon = R.drawable.ic_play_arrow)
                            ActionPill(stringResource(R.string.next30_mark_queued), { onState(UpNextState.QUEUED) }, icon = R.drawable.ic_playlist_play)
                        }
                    }
                    if (state != UpNextState.FINISHED) {
                        ActionPill(stringResource(R.string.next30_move_up), { onMove(-1) }, icon = R.drawable.ic_arrow_upward, enabled = row.canMoveUp)
                        ActionPill(stringResource(R.string.next30_move_down), { onMove(1) }, icon = R.drawable.ic_arrow_downward, enabled = row.canMoveDown)
                    }
                    ActionPill(stringResource(R.string.next30_remove), onRemove, icon = R.drawable.ic_remove, tone = ActionTone.Danger)
                }
            }
        },
        confirmButton = {},
        dismissButton = { DialogButton(stringResource(R.string.dialog_close), onDismiss) }
    )
}

@Composable
private fun ClearDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancelFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.next30_clear_title)) },
        text = { Text(pluralStringResource(R.plurals.next30_clear_message, count, count)) },
        confirmButton = { DialogButton(text = stringResource(R.string.next30_clear_finished), onClick = onConfirm, destructive = true) },
        dismissButton = { DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss, initialFocus = cancelFocus) }
    )
}
