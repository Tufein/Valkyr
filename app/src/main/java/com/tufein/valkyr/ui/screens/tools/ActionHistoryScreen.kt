package com.tufein.valkyr.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tufein.valkyr.R
import com.tufein.valkyr.data.model.DownloadFailure
import com.tufein.valkyr.data.model.DownloadFailureCategory
import com.tufein.valkyr.data.service.CloudMessages
import com.tufein.valkyr.data.service.UndoResult
import com.tufein.valkyr.ui.common.Gamepad
import com.tufein.valkyr.ui.common.GamepadButton
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.EmptyState
import com.tufein.valkyr.ui.components.IconTile
import com.tufein.valkyr.ui.components.LegendEntry
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.formatBytes
import com.tufein.valkyr.ui.components.pillColors
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.ui.navigation.NavRoutes
import com.tufein.valkyr.ui.screens.download.feedback
import com.tufein.valkyr.ui.screens.home.components.SearchField
import com.tufein.valkyr.ui.theme.consoleColor
import com.tufein.valkyr.ui.theme.tabular
import com.tufein.valkyr.util.ActionHelp
import com.tufein.valkyr.util.ActionCount
import com.tufein.valkyr.util.ActionEntry
import com.tufein.valkyr.util.ActionFilter
import com.tufein.valkyr.util.ActionHistory
import com.tufein.valkyr.util.ActionKind
import com.tufein.valkyr.util.ActionReason
import com.tufein.valkyr.util.ActionTopic
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.DayLabel
import com.tufein.valkyr.util.ToastUtil
import com.tufein.valkyr.util.UndoAction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Action history (2.4.0): what downloads, removals, restores, moves, syncs and backups did, newest
 * first and grouped by day, with filter chips (LB / RB on a gamepad), a title search (Y) and
 * "Clear history". A removal still in the trash offers *Restore* (through the recovery journal,
 * like Trash and recovery); a game whose trash was emptied, or whose download failed, offers
 * *Download again*. A line that names a library game opens its page.
 *
 * @param onOpenGame opens the game page (console id, library file name).
 * @param onNavigate opens another screen by route (Trash and recovery).
 */
@Composable
fun ActionHistoryScreen(
    onOpenGame: (String, String) -> Unit,
    onNavigate: (String) -> Unit,
    viewModel: ActionHistoryViewModel = hiltViewModel()
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clearFailureMessage = stringResource(R.string.hist24_clear_failed)
    val locale = Locale.getDefault()
    val restoreDoneTemplate = stringResource(R.string.hist24_restore_done)
    val requeuedTemplate = stringResource(R.string.hist24_requeued)
    val restorePartialTemplate = stringResource(R.string.hist24_restore_partial)
    val restoreFailedMessage = stringResource(R.string.hist24_restore_failed)
    val requeueFailedMessage = stringResource(R.string.hist24_requeue_failed)
    val timeFormat = remember(locale) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale) }
    val dateFormat = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    val zone = remember { ZoneId.systemDefault() }
    var help by remember { mutableStateOf<ActionRowUi?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var focusedKey by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }
    val emptyFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Something focusable is always focused: the first chip, or the empty state's button (also right after "Clear history").
    LaunchedEffect(ui.loading, ui.hasAny) {
        if (ui.loading) return@LaunchedEffect
        withFrameNanos { }
        runCatching { if (ui.hasAny) firstFocus.requestFocus() else emptyFocus.requestFocus() }
    }
    // LB / RB switch the chip, Y types in the search box.
    val filter by rememberUpdatedState(ui.filter)
    val hasAny by rememberUpdatedState(ui.hasAny)
    val rowFocused by rememberUpdatedState(focusedKey != null)
    LaunchedEffect(Unit) {
        Gamepad.presses.collect { button ->
            if (!hasAny) return@collect
            when (button) {
                GamepadButton.PREV_PANEL, GamepadButton.NEXT_PANEL -> {
                    viewModel.setFilter(filter.step(if (button == GamepadButton.PREV_PANEL) -1 else 1))
                    // The focused row may not be in the new list: focus goes back to the chips.
                    if (rowFocused) scope.launch {
                        listState.scrollToItem(0)
                        withFrameNanos { }
                        runCatching { firstFocus.requestFocus() }
                    }
                }
                GamepadButton.Y -> searching = true
                else -> Unit
            }
        }
    }
    // The legend names only a row that is on screen now: a line gone (cleared, filtered out) has no A.
    val row = focusedKey?.let { ui.rows[it] }
    val padAction = when {
        row == null -> null
        ActionHelp.of(row.entry) != null -> stringResource(R.string.help25_title)
        row.undo != null && ui.busyKey == null -> stringResource(if (row.undo == UndoAction.RESTORE) R.string.hist24_restore else R.string.hist24_download_again)
        row.undo == null && ActionHistory.openable(row.entry) -> stringResource(R.string.pad_open)
        else -> null
    }
    PublishLegend(
        if (!ui.hasAny) null else listOfNotNull(
            padAction?.let { LegendEntry("A", it) },
            LegendEntry("Y", stringResource(R.string.find8_pad_type)),
            LegendEntry("LB · RB", stringResource(R.string.hist24_pad_filter)),
            LegendEntry("B", stringResource(R.string.pad_back))
        )
    )

    if (confirmClear) ClearDialog(ui.all, onConfirm = {
        confirmClear = false
        focusedKey = null
        viewModel.clear { ToastUtil.showError(context, clearFailureMessage) }
    }, onDismiss = { confirmClear = false })

    fun undo(r: ActionRowUi) {
        val title = r.entry.title
        viewModel.undo(r) { action, result ->
            when (result) {
                UndoResult.DONE -> ToastUtil.showSuccess(
                    context,
                    String.format(locale, if (action == UndoAction.RESTORE) restoreDoneTemplate else requeuedTemplate, title)
                )
                UndoResult.PARTIAL -> ToastUtil.showError(context, String.format(locale, restorePartialTemplate, title))
                UndoResult.FAILED -> ToastUtil.showError(
                    context, if (action == UndoAction.RESTORE) restoreFailedMessage else requeueFailedMessage
                )
            }
        }
    }

    help?.let { selected ->
        val guidance = ActionHelp.of(selected.entry)
        val cancelFocus = rememberInitialFocus()
        AlertDialog(modifier = Modifier.closeOnGamepadB { help = null }, onDismissRequest = { help = null },
            title = { Text(stringResource(R.string.help25_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(selected.entry.title)
                Text(stringResource(when (guidance) {
                    ActionHelp.NETWORK -> R.string.help25_network
                    ActionHelp.SPACE -> R.string.help25_space
                    ActionHelp.PERMISSION -> R.string.help25_permission
                    ActionHelp.SOURCE -> R.string.help25_source
                    ActionHelp.AUTH -> R.string.help25_auth
                    ActionHelp.EXTRACT -> R.string.help25_extract
                    ActionHelp.VERIFY -> R.string.help25_verify
                    ActionHelp.MOVE -> R.string.help25_move
                    ActionHelp.SYNC -> R.string.help25_sync
                    ActionHelp.BACKUP -> R.string.help25_backup
                    else -> R.string.help25_general
                }))
                ToolAction(stringResource(R.string.help25_fix)) {
                    help = null
                    onNavigate(when (guidance) {
                        ActionHelp.SPACE -> NavRoutes.Storage.route
                        ActionHelp.PERMISSION, ActionHelp.AUTH, ActionHelp.NETWORK -> NavRoutes.Settings.route
                        ActionHelp.SOURCE -> NavRoutes.Sources.route
                        ActionHelp.EXTRACT -> NavRoutes.Files.route
                        ActionHelp.VERIFY -> NavRoutes.Dat.route
                        ActionHelp.MOVE -> NavRoutes.Recovery.route
                        ActionHelp.SYNC -> if (selected.entry.topic == ActionTopic.SAVE_SYNC) NavRoutes.SaveSync.route else NavRoutes.Cloud.route
                        ActionHelp.BACKUP -> NavRoutes.CloudBackup.route
                        else -> NavRoutes.Health.route
                    })
                }
                if (ActionHistory.openable(selected.entry)) ToolAction(stringResource(R.string.ready25_title)) {
                    help = null
                    onNavigate(ReadinessRoute.of(selected.entry.consoleId.orEmpty(), selected.entry.fileName.orEmpty()))
                }
            } },
            confirmButton = { if (selected.undo != null) DialogButton(stringResource(if (selected.undo == UndoAction.RESTORE) R.string.hist24_restore else R.string.hist24_download_again),
                onClick = { help = null; undo(selected) }, enabled = ui.busyKey == null) },
            dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), { help = null }, initialFocus = cancelFocus) })
    }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.hist24_title), icon = R.drawable.ic_manage_history, subtitle = stringResource(R.string.hist24_subtitle))
        when {
            ui.loading -> Column(Modifier.padding(16.dp)) { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            !ui.hasAny -> EmptyState(
                title = stringResource(R.string.hist24_empty_title),
                message = stringResource(R.string.hist24_empty_message),
                illustration = R.drawable.brand_illustration,
                actionLabel = stringResource(R.string.recovery_title),
                onAction = { onNavigate(NavRoutes.Recovery.route) },
                actionFocus = emptyFocus,
                modifier = Modifier.fillMaxWidth()
            )
            else -> {
                SearchField(
                    value = ui.query,
                    onValueChange = viewModel::setQuery,
                    active = searching,
                    onActivate = { searching = true },
                    // 3.9.0: B in the field hands the cursor to the filters below, not to nowhere.
                    onDismiss = { searching = false; runCatching { firstFocus.requestFocus() } },
                    placeholder = stringResource(R.string.hist24_search_hint),
                    focusRequester = fieldFocus,
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) { runCatching { firstFocus.requestFocus() }; true } else false
                        }
                )
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.fillMaxSize()) {
                    item(key = "filters") {
                        ToolsActions {
                            ActionFilter.entries.forEach { f ->
                                FilterChip(filterLabel(f), ui.filter == f, if (f == ActionFilter.ALL) Modifier.focusRequester(firstFocus) else Modifier) { viewModel.setFilter(f) }
                            }
                        }
                    }
                    if (ui.total == 0) item(key = "empty-filter") {
                        EmptyState(
                            title = stringResource(R.string.hist24_empty_filter_title),
                            message = stringResource(R.string.hist24_empty_filter_message),
                            icon = R.drawable.ic_manage_history,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    ui.groups.forEach { group ->
                        item(key = group.key) { SectionHeader(dayText(group.day.label, locale, dateFormat)) }
                        items(group.rows, key = { it.key }) { r ->
                            DisposableEffect(r.key) { onDispose { if (focusedKey == r.key) focusedKey = null } }
                            ActionRow(
                                r, timeFormat, zone,
                                busy = ui.busyKey != null,
                                modifier = Modifier.onFocusChanged { state ->
                                    if (state.isFocused) focusedKey = r.key else if (focusedKey == r.key) focusedKey = null
                                },
                                onHelp = { help = r },
                                onUndo = { undo(r) },
                                onOpen = { e -> onOpenGame(e.consoleId.orEmpty(), e.fileName.orEmpty()) }
                            )
                        }
                    }
                    item(key = "actions") {
                        ToolsActions(modifier = Modifier.padding(top = 8.dp)) {
                            ActionPill(stringResource(R.string.recovery_title), { onNavigate(NavRoutes.Recovery.route) }, icon = R.drawable.ic_history)
                            ActionPill(stringResource(R.string.hist24_clear), { confirmClear = true }, icon = R.drawable.ic_trash, tone = ActionTone.Danger)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun filterLabel(f: ActionFilter): String = stringResource(
    when (f) {
        ActionFilter.ALL -> R.string.hist24_filter_all
        ActionFilter.DOWNLOADS -> R.string.hist24_filter_downloads
        ActionFilter.TRASH -> R.string.hist24_filter_trash
        ActionFilter.MOVES -> R.string.hist24_filter_moves
        ActionFilter.SYNC -> R.string.hist24_filter_sync
        ActionFilter.GAMES -> R.string.hist24_filter_games
    }
)

@Composable
private fun FilterChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    ActionPill(
        label,
        onClick = onClick,
        modifier = modifier,
        icon = if (selected) R.drawable.ic_check else null,
        tone = if (selected) ActionTone.Accent else ActionTone.Neutral
    )
}

@Composable
private fun dayText(label: DayLabel, locale: Locale, dateFormat: DateTimeFormatter): String = when (label) {
    DayLabel.Today -> stringResource(R.string.coll6_day_today)
    DayLabel.Yesterday -> stringResource(R.string.coll6_day_yesterday)
    is DayLabel.Weekday -> label.date.dayOfWeek.getDisplayName(TextStyle.FULL, locale).replaceFirstChar { it.titlecase(locale) }
    is DayLabel.Date -> label.date.format(dateFormat)
}

/** The icon and colour of a kind of line. */
private fun look(kind: ActionKind): Pair<Int, PillTone> = when (kind) {
    ActionKind.DOWNLOADED -> R.drawable.ic_download to PillTone.Accent
    ActionKind.DOWNLOAD_FAILED, ActionKind.MOVE_FAILED, ActionKind.SYNC_FAILED, ActionKind.BACKUP_FAILED -> R.drawable.ic_error to PillTone.Danger
    ActionKind.REMOVED -> R.drawable.ic_trash to PillTone.Warning
    ActionKind.RESTORED, ActionKind.BACKUP_RESTORED -> R.drawable.ic_restore to PillTone.Success
    ActionKind.PURGED -> R.drawable.ic_trash to PillTone.Danger
    ActionKind.MOVED -> R.drawable.ic_drive_file_move to PillTone.Info
    ActionKind.SYNCED -> R.drawable.ic_sync to PillTone.Info
    ActionKind.BACKED_UP -> R.drawable.ic_backup to PillTone.Info
    ActionKind.PLAYED -> R.drawable.ic_play_arrow to PillTone.Success
    ActionKind.PINNED, ActionKind.UNPINNED -> R.drawable.ic_star to PillTone.Accent
    ActionKind.OTHER -> R.drawable.ic_manage_history to PillTone.Neutral
}

/** The sentence of a kind ("Removed to the trash"); a summary line says how many. */
@Composable
private fun kindText(e: ActionEntry): String = if (e.isSummary) when (e.kind) {
    ActionKind.DOWNLOADED -> pluralStringResource(R.plurals.hist24_more_downloaded, e.count, e.count)
    ActionKind.DOWNLOAD_FAILED -> pluralStringResource(R.plurals.hist24_more_download_failed, e.count, e.count)
    ActionKind.PURGED -> pluralStringResource(R.plurals.hist24_more_purged, e.count, e.count)
    else -> pluralStringResource(R.plurals.hist24_more_other, e.count, e.count)
} else stringResource(
    when (e.kind) {
        ActionKind.DOWNLOADED -> R.string.hist24_kind_downloaded
        ActionKind.DOWNLOAD_FAILED -> R.string.hist24_kind_download_failed
        ActionKind.REMOVED -> R.string.hist24_kind_removed
        ActionKind.RESTORED -> R.string.hist24_kind_restored
        ActionKind.PURGED -> R.string.hist24_kind_purged
        ActionKind.MOVED -> R.string.hist24_kind_moved
        ActionKind.MOVE_FAILED -> R.string.hist24_kind_move_failed
        ActionKind.SYNCED -> R.string.hist24_kind_synced
        ActionKind.SYNC_FAILED -> R.string.hist24_kind_sync_failed
        ActionKind.BACKED_UP -> R.string.hist24_kind_backed_up
        ActionKind.BACKUP_FAILED -> R.string.hist24_kind_backup_failed
        ActionKind.BACKUP_RESTORED -> R.string.hist24_kind_backup_restored
        ActionKind.PLAYED -> R.string.hist24_kind_played
        ActionKind.PINNED -> R.string.hist24_kind_pinned
        ActionKind.UNPINNED -> R.string.hist24_kind_unpinned
        ActionKind.OTHER -> R.string.hist24_kind_other
    }
)

/** What a line without a game is about: the feature, or the console smart storage moved. */
@Composable
private fun topicText(e: ActionEntry): String? = when (e.topic) {
    ActionTopic.SAVE_SYNC -> stringResource(R.string.hist24_t_save_sync)
    ActionTopic.DEVICE_SYNC -> stringResource(R.string.hist24_t_device_sync)
    ActionTopic.CLOUD_BACKUP -> stringResource(R.string.hist24_t_cloud_backup)
    ActionTopic.LIBRARY_FOLDER -> stringResource(R.string.hist24_t_library_folder)
    ActionTopic.LIBRARY_REPAIR -> stringResource(R.string.repair30_title)
    ActionTopic.SMART_STORAGE -> e.consoleId?.takeIf { it.isNotBlank() }?.let { ConsoleFormatter.getConsoleDisplayName(it) }
        ?: stringResource(R.string.hist24_t_smart_storage)
    else -> null
}

/**
 * Why it happened, in words. Only known codes are shown: a reason this version does not know
 * (written by a newer one) is left out rather than shown raw.
 */
@Composable
private fun reasonText(e: ActionEntry): String? {
    val reason = e.reason ?: return when {
        e.kind == ActionKind.MOVE_FAILED && e.topic == ActionTopic.LIBRARY_FOLDER -> stringResource(R.string.hist24_r_library_failed)
        e.kind == ActionKind.SYNC_FAILED && e.topic == ActionTopic.SAVE_SYNC -> stringResource(R.string.hist24_r_sync_failed)
        else -> null
    }
    val context = LocalContext.current
    if (reason.startsWith(ActionReason.DOWNLOAD_PREFIX)) {
        val category = DownloadFailureCategory.entries.firstOrNull { it.name == reason.removePrefix(ActionReason.DOWNLOAD_PREFIX) } ?: return null
        return stringResource(DownloadFailure(category).feedback().message)
    }
    if (reason.startsWith(ActionReason.CLOUD_PREFIX)) {
        val code = reason.removePrefix(ActionReason.CLOUD_PREFIX)
        return if (code == ActionReason.CLOUD_OTHER || code.isBlank()) stringResource(R.string.hist24_r_cloud_other)
        else runCatching { CloudMessages.render(context, code) }.getOrNull()?.takeIf { it.isNotBlank() }
    }
    val failedSmart = e.kind == ActionKind.MOVE_FAILED
    return when (reason) {
        ActionReason.BY_USER -> stringResource(if (e.kind == ActionKind.PURGED) R.string.hist24_r_user_purged else R.string.hist24_r_user_removed)
        ActionReason.DUPLICATE -> stringResource(R.string.hist24_r_duplicate)
        ActionReason.BETTER_VERSION -> stringResource(R.string.hist24_r_better_version)
        ActionReason.FREE_SPACE -> stringResource(R.string.hist24_r_free_space)
        ActionReason.OFFLINE_COLLECTION -> stringResource(R.string.hist24_r_offline_collection)
        ActionReason.REPAIR -> stringResource(R.string.repair30_hist_reason)
        ActionReason.RETROARCH_PLAYLIST -> stringResource(
            if (e.kind == ActionKind.REMOVED) R.string.playlists35_hist_replaced else R.string.playlists35_title
        )
        ActionReason.EXPIRED -> stringResource(R.string.hist24_r_expired)
        ActionReason.TO_SD, ActionReason.TO_INTERNAL -> stringResource(
            when {
                failedSmart -> R.string.hist24_r_smart_failed
                reason == ActionReason.TO_SD -> R.string.hist24_r_to_sd
                else -> R.string.hist24_r_to_internal
            }
        )
        ActionReason.TO_SD_ESDE_LEFT -> stringResource(R.string.hist24_r_to_sd_esde)
        ActionReason.TO_INTERNAL_ESDE_LEFT -> stringResource(R.string.hist24_r_to_internal_esde)
        ActionReason.ORIGINALS_LEFT -> stringResource(R.string.hist24_r_originals_left)
        ActionReason.HELD_BACK -> null // the count says it
        else -> null
    }
}

/** "3 uploaded · 1 conflict": the numbers of a sync or a move. */
@Composable
private fun countsText(e: ActionEntry): String? {
    val c = e.counts
    val parts = listOfNotNull(
        c[ActionCount.FILES]?.let { pluralStringResource(R.plurals.hist24_p_files, it, it) },
        c[ActionCount.ADDED]?.let { pluralStringResource(R.plurals.hist24_p_added, it, it) },
        c[ActionCount.REMOVED]?.let { pluralStringResource(R.plurals.hist24_p_removed, it, it) },
        c[ActionCount.UPLOADED]?.let { pluralStringResource(R.plurals.hist24_p_uploaded, it, it) },
        c[ActionCount.DOWNLOADED]?.let { pluralStringResource(R.plurals.hist24_p_downloaded, it, it) },
        c[ActionCount.DELETED_DEVICE]?.let { pluralStringResource(R.plurals.hist24_p_deleted_device, it, it) },
        c[ActionCount.DELETED_SERVER]?.let { pluralStringResource(R.plurals.hist24_p_deleted_server, it, it) },
        c[ActionCount.CONFLICTS]?.let { pluralStringResource(R.plurals.hist24_p_conflicts, it, it) },
        c[ActionCount.FAILED]?.let { pluralStringResource(R.plurals.hist24_p_failed, it, it) },
        c[ActionCount.HELD]?.let { pluralStringResource(R.plurals.hist24_p_held, it, it) }
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * One line: the kind's icon, the title (the game, the feature, or the sentence of a summary), the
 * sentence with the size, why, the numbers, then the console and the time. Raw codes, keys and
 * paths are never shown. A line with a way back runs it on A / tap; one naming a library game opens its page.
 */
@Composable
private fun ActionRow(
    row: ActionRowUi,
    timeFormat: DateTimeFormatter,
    zone: ZoneId,
    busy: Boolean,
    modifier: Modifier,
    onUndo: () -> Unit,
    onHelp: () -> Unit,
    onOpen: (ActionEntry) -> Unit
) {
    val e = row.entry
    val scheme = MaterialTheme.colorScheme
    val (icon, tone) = look(e.kind)
    val (container, tint) = pillColors(tone)
    val sentence = kindText(e)
    val size = if (e.bytes > 0) formatBytes(e.bytes) else null
    val topic = topicText(e)
    val title = when {
        e.isSummary -> sentence
        e.title.isNotBlank() -> e.title
        else -> topic ?: sentence
    }
    val lines = buildList {
        if (e.isSummary) size?.let(::add) else add(listOfNotNull(sentence, size).joinToString("  ·  "))
        reasonText(e)?.let(::add)
        countsText(e)?.let(::add)
    }
    val time = remember(e.at, timeFormat, zone) { timeFormat.format(Instant.ofEpochMilli(e.at).atZone(zone)) }
    val undo = row.undo
    ToolRow(
        title = title,
        lines = lines,
        onClick = {
            when {
                ActionHelp.of(e) != null -> onHelp()
                undo != null -> if (!busy) onUndo()
                ActionHistory.openable(e) -> onOpen(e)
            }
        },
        modifier = modifier,
        leading = { IconTile(icon, size = 40.dp, container = container, tint = tint) },
        below = {
            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                e.consoleId?.takeIf { it.isNotBlank() }?.let { id ->
                    Pill(ConsoleFormatter.getConsoleShortName(id), tone = PillTone.Tint(consoleColor(id)))
                }
                Text(time, style = MaterialTheme.typography.bodySmall.tabular(), color = scheme.onSurfaceVariant)
            }
        },
        trailing = {
            // For touch; on a gamepad A on the row does the same (see the legend), so focus never
            // sits on a button that goes away once the game is back.
            val noFocus = Modifier.focusProperties { canFocus = false }
            if (ActionHelp.of(e) != null) ActionPill(stringResource(R.string.help25_title), onHelp, noFocus, icon = R.drawable.ic_info)
            else when (undo) {
                UndoAction.RESTORE -> ActionPill(stringResource(R.string.hist24_restore), onUndo, noFocus, icon = R.drawable.ic_restore, tone = ActionTone.Accent, enabled = !busy)
                UndoAction.DOWNLOAD_AGAIN -> ActionPill(stringResource(R.string.hist24_download_again), onUndo, noFocus, icon = R.drawable.ic_download, tone = ActionTone.Accent, enabled = !busy)
                null -> Unit
            }
        }
    )
}

@Composable
private fun ClearDialog(lines: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancelFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hist24_clear_title)) },
        text = { Text(pluralStringResource(R.plurals.hist24_clear_message, lines, lines)) },
        confirmButton = { DialogButton(text = stringResource(R.string.hist24_clear_confirm), onClick = onConfirm, destructive = true) },
        dismissButton = { DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss, initialFocus = cancelFocus) }
    )
}
