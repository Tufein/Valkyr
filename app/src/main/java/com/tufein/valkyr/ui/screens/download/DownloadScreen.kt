package com.tufein.valkyr.ui.screens.download

import com.tufein.valkyr.util.DownloadSourcePolicy
import com.tufein.valkyr.util.QueueActions
import com.tufein.valkyr.util.DownloadGroups
import com.tufein.valkyr.ui.navigation.OnTabReselect
import androidx.compose.foundation.lazy.rememberLazyListState
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import com.tufein.valkyr.R
import com.tufein.valkyr.data.model.DownloadItemModel
import com.tufein.valkyr.data.model.DownloadStatus
import com.tufein.valkyr.data.model.DownloadableFileWithTags
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.EmptyState
import com.tufein.valkyr.ui.components.Panel
import com.tufein.valkyr.ui.components.PanelTone
import com.tufein.valkyr.ui.navigation.NavRoutes
import com.tufein.valkyr.ui.theme.LocalReduceMotion
import com.tufein.valkyr.ui.theme.Motion
import com.tufein.valkyr.ui.components.formatBytes
import com.tufein.valkyr.util.WaitReason
import com.tufein.valkyr.ui.common.Gamepad
import com.tufein.valkyr.ui.common.GamepadButton
import com.tufein.valkyr.ui.common.Legend
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.LegendEntry
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.screens.offline.OfflineChip
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.ui.components.ContentEntry

@Composable
fun DownloadScreen(
    navController: NavController,
    viewModel: DownloadViewModel = hiltViewModel()
) {
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val queueView by viewModel.queueView.collectAsStateWithLifecycle()
    val filterCriteria by viewModel.queueFilters.collectAsStateWithLifecycle()
    val details by viewModel.downloadDetails.collectAsStateWithLifecycle()
    val uploads by viewModel.uploads.collectAsStateWithLifecycle()
    val waitingFiles by viewModel.waitingFiles.collectAsStateWithLifecycle()
    val waitingReasons by viewModel.waitingReasons.collectAsStateWithLifecycle()
    val queuePositions by viewModel.queuePositions.collectAsStateWithLifecycle()
    val itemWaits by viewModel.itemWaits.collectAsStateWithLifecycle()
    val itemConditions by viewModel.itemConditions.collectAsStateWithLifecycle()
    val pendingRetries by viewModel.pendingAutoRetries.collectAsStateWithLifecycle()
    val sourceChoice by viewModel.sourceChoice.collectAsStateWithLifecycle()
    // Names still in line, to offer "Wait for..." on the rows that have not started.
    val queuedSet = queuePositions.keys
    // Rows the "Download when..." dialog is open for (one row, or the ticked ones); null = closed.
    var whenTargets by remember { mutableStateOf<List<String>?>(null) }
    fun notStarted(row: DownloadItemModel) = row.status == DownloadStatus.DOWNLOADING &&
        (row.fileName in queuedSet || row.fileName in waitingFiles || row.fileName in itemWaits)
    val verification by viewModel.verification.collectAsStateWithLifecycle()
    val shortfall by viewModel.queueShortfall.collectAsStateWithLifecycle()
    val counts by viewModel.queueCounts.collectAsStateWithLifecycle()
    val eta by viewModel.queueEta.collectAsStateWithLifecycle()
    val held by viewModel.held.collectAsStateWithLifecycle()
    val progress by viewModel.queueProgress.collectAsStateWithLifecycle()
    val need by viewModel.queueNeed.collectAsStateWithLifecycle()
    val free by viewModel.freeBytes.collectAsStateWithLifecycle()
    val waitWifi = stringResource(R.string.wait_wifi)
    val waitCharger = stringResource(R.string.wait_charger)
    val waitNight = stringResource(R.string.wait_night)
    val waitStorage = stringResource(R.string.wait_storage)
    val waitHeld = stringResource(R.string.wait_held)
    val waitBattery = stringResource(R.string.power75_wait_battery)
    val waitHot = stringResource(R.string.power75_wait_hot)
    val waitingText = waitingReasons.joinToString(" · ") {
        when (it) {
            WaitReason.WIFI -> waitWifi
            WaitReason.CHARGER -> waitCharger
            WaitReason.NIGHT -> waitNight
            WaitReason.STORAGE -> waitStorage
            WaitReason.HELD -> waitHeld
            WaitReason.LOW_BATTERY -> waitBattery
            WaitReason.HOT -> waitHot
        }
    }
    // The same reasons in two or three words, for the pill on a waiting row.
    val shortWifi = stringResource(R.string.q5_wait_wifi)
    val shortCharger = stringResource(R.string.q5_wait_charger)
    val shortNight = stringResource(R.string.q5_wait_night)
    val shortStorage = stringResource(R.string.q5_wait_storage)
    val shortHeld = stringResource(R.string.q5_wait_held)
    val shortBattery = stringResource(R.string.power75_wait_battery_short)
    val shortHot = stringResource(R.string.power75_wait_hot_short)
    val waitingShort = waitingReasons.joinToString(" · ") {
        when (it) {
            WaitReason.WIFI -> shortWifi
            WaitReason.CHARGER -> shortCharger
            WaitReason.NIGHT -> shortNight
            WaitReason.STORAGE -> shortStorage
            WaitReason.HELD -> shortHeld
            WaitReason.LOW_BATTERY -> shortBattery
            WaitReason.HOT -> shortHot
        }
    }
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val showDeleteConfirmation by viewModel.showDeleteConfirmation.collectAsStateWithLifecycle()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val selectionMode = selection.isNotEmpty()
    val selected by viewModel.selectedDownloads.collectAsStateWithLifecycle()

    // Row under the D-pad cursor: SELECT ticks it, X deletes it (the in-row buttons are touch-only —
    // they sit inside the focused row's bounds, out of reach of directional focus search).
    var focusedRow by remember { mutableStateOf<DownloadItemModel?>(null) }
    LaunchedEffect(filterCriteria) { focusedRow = null }
    val barFocus = remember { FocusRequester() }
    var barFocused by remember { mutableStateOf(false) }
    // 3.9.0: the press that brings a hidden cursor back (after ZL / ZR) lands on the first download.
    val firstRowFocus = remember { FocusRequester() }
    ContentEntry(NavRoutes.Downloads.route, firstRowFocus)
    // Clearing the selection takes the bar away under the cursor (B clears it from a row instead,
    // and that row keeps the focus). Park focus on the first download (3.9.0; the section tab when
    // the list is empty) once the new tree is laid out, or the ring disappears.
    // 3.9.0: only on that change, not when the screen opens: opening Downloads used to pull the
    // cursor onto its tab, even after a ZL / ZR switch that hides it on purpose.
    val wasSelecting = remember { booleanArrayOf(false) }
    LaunchedEffect(selectionMode) {
        val cleared = wasSelecting[0] && !selectionMode
        wasSelecting[0] = selectionMode
        if (cleared && focusedRow == null) {
            withFrameNanos { }
            withFrameNanos { }
            if (!runCatching { firstRowFocus.requestFocus() }.getOrDefault(false)) runCatching { Gamepad.sectionFocus.requestFocus() }
        }
    }
    LaunchedEffect(selectionMode) {
        Gamepad.presses.collect { button ->
            when (button) {
                // Select ticks the row under the cursor; that is what opens selection mode with a pad.
                GamepadButton.FAVOURITE -> focusedRow?.let { viewModel.toggleSelection(it.fileName) }
                // Y: select all while ticking; otherwise a waiting download jumps to the front of the queue.
                GamepadButton.Y -> if (selectionMode) viewModel.toggleSelectAll() else focusedRow
                    ?.takeIf { it.fileName in queueView.visibleNames }?.let { viewModel.moveToFront(it.fileName) }
                GamepadButton.X -> if (selectionMode) {
                    viewModel.deleteSelected()
                } else if (queueView.criteria == filterCriteria) {
                    // Re-read the live status: it may have changed since the row took focus.
                    queueView.rows.find { it.fileName == focusedRow?.fileName }?.let { row ->
                        if (row.status.canDelete) {
                            viewModel.deleteDownloadWithConfirmation(row.fileName, row.status == DownloadStatus.COMPLETED)
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    // B drops the selection before it hands focus back to the tabs.
    BackHandler(enabled = selectionMode) { viewModel.clearSelection() }

    val section = LegendEntry("ZL · ZR", stringResource(R.string.pad_section))
    val selectionLegend = if (barFocused) listOf(
        LegendEntry("A", stringResource(R.string.pad_apply)),
        LegendEntry("◀ ▶", stringResource(R.string.pad_change)),
        LegendEntry("B", stringResource(R.string.pad_cancel)), section
    ) else listOf(
        LegendEntry("A", stringResource(R.string.pad_tick)),
        LegendEntry("Y", stringResource(R.string.pad_select_all)),
        LegendEntry("X", stringResource(R.string.pad_delete)),
        LegendEntry("B", stringResource(R.string.pad_cancel)), section
    )
    val published = remember(selectionMode, selectionLegend) { if (selectionMode) Legend(selectionLegend) else null }
    LaunchedEffect(published) { Gamepad.legendOverride.value = published }
    // Only clear our own legend: another screen may already have published its own during the transition.
    DisposableEffect(published) { onDispose { if (Gamepad.legendOverride.value === published) Gamepad.legendOverride.value = null } }

    // One animation for every busy bar of the list (a calm segment sliding along the track); it only
    // runs while something is actually busy and stands still when motion is reduced.
    val reduceMotion = LocalReduceMotion.current
    val anyBusy = downloads.any {
        it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING ||
            it.status == DownloadStatus.COPYING || it.status == DownloadStatus.UNZIPPING
    }
    val sweep: State<Float> = if (anyBusy && !reduceMotion) {
        rememberInfiniteTransition(label = "queue-sweep").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Restart),
            label = "queue-sweep"
        )
    } else {
        remember { mutableStateOf(0.5f) }
    }
    val listState = rememberLazyListState()
    // 5.2: a tap on the open Downloads tab brings the list back to its top.
    OnTabReselect(NavRoutes.Downloads.route) { listState.animateScrollToItem(0) }
    val goToLibrary: () -> Unit = {
        navController.navigate(NavRoutes.Home.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 12.dp)
    ) {
        // The section name already lives in the tabs; the queue summary heads the list (it scrolls
        // away on a short screen) and gives way to the bulk actions while rows are ticked.
        if (selectionMode) {
            SelectionBar(
                focusRequester = barFocus,
                onFocusChanged = { barFocused = it },
                selected = selected,
                details = details,
                compact = isLandscape,
                onRetry = viewModel::retrySelected,
                onPause = viewModel::pauseSelected,
                onStop = viewModel::stopSelected,
                canStop = selected.any { it.status.canStop || it.fileName in pendingRetries },
                canPrioritize = selected.any { it.fileName in queuedSet },
                onPrioritize = viewModel::moveSelectedToFront,
                canWait = selected.any(::notStarted),
                canStartNow = selected.any { it.fileName in itemWaits },
                onWaitFor = { whenTargets = selected.filter(::notStarted).map { it.fileName } },
                onStartNow = { viewModel.setCondition(selected.filter { it.fileName in itemWaits }.map { it.fileName }, null) },
                onDelete = viewModel::deleteSelected,
                onClear = viewModel::clearSelection
            )
        }
        if (downloads.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                DownloadPlanActions(downloads, selection, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                EmptyState(
                    title = stringResource(R.string.q5_empty_title),
                    message = stringResource(R.string.q5_empty_message),
                    icon = R.drawable.ic_download,
                    actionLabel = stringResource(R.string.q5_go_library),
                    onAction = goToLibrary
                )
            }
        } else {
            // 5.2: Active, Waiting, Failed, Done, each with a header; with a filter on, the chosen state is all that shows.
            val flatRows = remember(queueView.rows, queuedSet, waitingFiles, itemWaits) {
                val waiting = HashSet<String>(queuedSet.size + waitingFiles.size + itemWaits.size).apply {
                    addAll(queuedSet); addAll(waitingFiles); addAll(itemWaits.keys)
                }
                flattenSections(DownloadGroups.sections(queueView.rows, waiting))
            }
            val itemPlacement = if (reduceMotion) null else tween<IntOffset>(Motion.MEDIUM, easing = FastOutSlowInEasing)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!selectionMode) {
                    item(key = "offline-chip") {
                        OfflineChip(com.tufein.valkyr.util.OfflineMode.Screen.DOWNLOADS)
                    }
                    item(key = "queue-header") {
                        if (queueView.filtered) {
                            Text(
                                stringResource(R.string.queue23_whole_queue),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }
                        QueueHeader(
                            summary = progress,
                            eta = eta,
                            need = need,
                            free = free,
                            shortfall = shortfall,
                            waitingReasons = waitingReasons,
                            held = held,
                            counts = counts,
                            onHold = { viewModel.setHeld(!held) },
                            onStopAll = viewModel::stopAll,
                            onRetryFailed = viewModel::retryFailed,
                            onClearFinished = viewModel::clearFinished,
                            onPauseAll = viewModel::pauseAll,
                            onResumeAll = viewModel::resumeAll
                        )
                    }
                }
                item(key = "queue-plan") {
                    DownloadPlanActions(downloads, selection, Modifier.fillMaxWidth())
                }
                item(key = "queue-filters") {
                    QueueFilters(
                        view = queueView,
                        criteria = filterCriteria,
                        allShownSelected = queueView.visibleNames.isNotEmpty() && selection.containsAll(queueView.visibleNames),
                        onSearch = viewModel::setQueueSearch,
                        onFilter = viewModel::setQueueFilter,
                        onClear = viewModel::clearQueueFilters,
                        onSelectShown = viewModel::toggleSelectAll
                    )
                }
                if (waitingFiles.isNotEmpty() && waitingText.isNotEmpty()) {
                    item(key = "notice-waiting") {
                        // On hold by the user: the button lifts the hold; otherwise it starts the waiting ones anyway.
                        val onHold = WaitReason.HELD in waitingReasons
                        val release: () -> Unit = { if (onHold) viewModel.setHeld(false) else viewModel.startWaitingNow() }
                        NoticeRow(
                            text = pluralStringResource(R.plurals.downloads_waiting, waitingFiles.size, waitingFiles.size, waitingText),
                            icon = R.drawable.ic_hourglass,
                            action = stringResource(if (onHold) R.string.downloads_release else R.string.downloads_start_now),
                            onAction = release
                        )
                    }
                }
                if (shortfall > 0) {
                    item(key = "notice-space") {
                        NoticeRow(text = stringResource(R.string.downloads_low_space, formatBytes(shortfall)), icon = R.drawable.ic_warning, error = true)
                    }
                }
                if (queueView.rows.isEmpty()) {
                    item(key = "queue-no-matches") { QueueNoMatches(viewModel::clearQueueFilters) }
                }
                itemsIndexed(flatRows, key = { _, it -> it.key }, contentType = { _, it -> if (it.header != null) "download-header" else "download-row" }) { _, entry ->
                    val header = entry.header
                    if (header != null) {
                        GroupHeader(header.group, header.rows.size)
                        return@itemsIndexed
                    }
                    val item = entry.row!!
                    val index = entry.rowIndex
                    DownloadItem(
                        item = item,
                        details = details[item.fileName],
                        upload = uploads[item.fileName],
                        pendingRetry = pendingRetries[item.fileName],
                        canChangeSource = DownloadSourcePolicy.canChange(item.status),
                        onChangeSource = { viewModel.chooseSource(item.fileName) },
                        condition = itemWaits[item.fileName],
                        canSchedule = notStarted(item),
                        onWaitFor = { whenTargets = listOf(item.fileName) },
                        waitingReason = waitingShort.takeIf { it.isNotEmpty() && item.fileName in waitingFiles },
                        queuePosition = queuePositions[item.fileName],
                        verify = verification[item.fileName],
                        compact = isLandscape,
                        viewModel = viewModel,
                        onOpenSettings = { navController.navigate(NavRoutes.Settings.route) { launchSingleTop = true } },
                        onOpenSources = { navController.navigate(NavRoutes.Sources.route) { launchSingleTop = true } },
                        onOpenStorage = { navController.navigate(NavRoutes.Storage.route) { launchSingleTop = true } },
                        selectionMode = selectionMode,
                        selected = item.fileName in selection,
                        onToggleSelection = { viewModel.toggleSelection(item.fileName) },
                        focusUp = barFocus.takeIf { selectionMode && index == 0 },
                        sweep = sweep,
                        // Rows glide to their new place when the queue is reordered or a row goes;
                        // no fades (they would need an alpha layer per row).
                        modifier = Modifier
                            .animateItem(fadeInSpec = null, placementSpec = itemPlacement, fadeOutSpec = null)
                            .then(if (index == 0) Modifier.focusRequester(firstRowFocus) else Modifier),
                        onRowFocused = { row, focused ->
                            if (focused) focusedRow = row else if (focusedRow?.fileName == row.fileName) focusedRow = null
                        }
                    )
                }
            }
        }
    }

    sourceChoice?.let { state ->
        DownloadSourceChoiceDialog(
            choices = state.choices,
            loading = state.loading,
            busy = state.busy,
            error = state.errorRes?.let { stringResource(it) },
            onDismiss = viewModel::dismissSourceChoice,
            onConfirm = viewModel::confirmSource
        )
    }

    whenTargets?.let { names ->
        DownloadWhenDialog(
            count = names.size,
            initial = names.firstNotNullOfOrNull { itemConditions[it] },
            onDismiss = { whenTargets = null },
            onConfirm = { condition ->
                whenTargets = null
                viewModel.setCondition(names, condition)
            }
        )
    }

    showDeleteConfirmation?.let { fileNames ->
        AlertDialog(
            modifier = Modifier.closeOnGamepadB { viewModel.cancelDeleteConfirmation() },
            onDismissRequest = { viewModel.cancelDeleteConfirmation() },
            title = {
                Text(
                    text = if (fileNames.size > 1) stringResource(R.string.delete_downloads_title)
                    else stringResource(R.string.delete_download_title)
                )
            },
            text = {
                Text(
                    text = if (fileNames.size > 1) stringResource(R.string.delete_downloads_message, fileNames.size)
                    else stringResource(R.string.delete_download_message)
                )
            },
            confirmButton = {
                val delete = if (fileNames.size > 1) R.string.delete_files else R.string.delete_file
                DialogButton(stringResource(delete), onClick = { viewModel.confirmDeleteRemoveFile(fileNames) }, destructive = true)
            },
            dismissButton = {
                val keep = if (fileNames.size > 1) R.string.keep_files else R.string.keep_file
                DialogButton(stringResource(keep), onClick = { viewModel.confirmDeleteKeepFile(fileNames) }, initialFocus = rememberInitialFocus())
            }
        )
    }
}

/** A line of explanation in the list, with an optional button (e.g. "Start now"). */
@Composable
private fun NoticeRow(text: String, icon: Int, action: String? = null, onAction: () -> Unit = {}, error: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Panel(
        modifier = Modifier.fillMaxWidth(),
        tone = if (error) PanelTone.Danger else PanelTone.Normal,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = if (error) scheme.onErrorContainer else scheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = if (error) scheme.onErrorContainer else scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (action != null) ActionPill(action, onAction, tone = ActionTone.Accent)
        }
    }
}

/**
 * Bulk actions for the ticked rows, in the summary's place. Each button only shows up when
 * some row in the selection accepts it, so nothing focusable is ever a no-op.
 */
@Composable
private fun SelectionBar(
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    selected: List<DownloadItemModel>,
    details: Map<String, DownloadableFileWithTags>,
    compact: Boolean,
    onRetry: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    canStop: Boolean,
    canPrioritize: Boolean,
    onPrioritize: () -> Unit,
    canWait: Boolean,
    canStartNow: Boolean,
    onWaitFor: () -> Unit,
    onStartNow: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val size = 48.dp
    // Only the actions some ticked row accepts, so nothing focusable is ever a no-op.
    val actions = buildList {
        if (selected.any { it.status.canRetry }) {
            add(BulkAction(R.drawable.ic_retry, stringResource(R.string.download_retry), scheme.onSurface, onRetry))
        }
        if (selected.any { d -> details[d.fileName]?.let { QueueActions.canPause(d.status, it.file.isTorrent) } == true }) {
            add(BulkAction(R.drawable.ic_pause, stringResource(R.string.download_pause), scheme.onSurface, onPause))
        }
        if (canStop) {
            add(BulkAction(R.drawable.ic_stop, stringResource(R.string.download_cancel), scheme.onSurface, onStop))
        }
        if (canPrioritize) {
            add(BulkAction(R.drawable.ic_arrow_upward, stringResource(R.string.queue23_prioritize), scheme.primary, onPrioritize))
        }
        if (canStartNow) {
            add(BulkAction(R.drawable.ic_play_arrow, stringResource(R.string.plan6_start_now), scheme.primary, onStartNow))
        }
        if (canWait) {
            add(BulkAction(R.drawable.ic_schedule, stringResource(R.string.plan6_wait_for), scheme.onSurface, onWaitFor))
        }
        if (selected.any { it.status.canDelete }) {
            add(BulkAction(R.drawable.ic_trash, stringResource(R.string.download_delete), scheme.error, onDelete))
        }
        add(BulkAction(R.drawable.ic_close, stringResource(R.string.selection_clear), scheme.onSurfaceVariant, onClear))
    }
    Panel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .onFocusChanged { onFocusChanged(it.hasFocus) },
        tone = PanelTone.Accent,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                painterResource(R.drawable.ic_check_circle),
                contentDescription = null,
                tint = scheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp)
            )
            Text(
                pluralStringResource(R.plurals.downloads_selected, selected.size, selected.size),
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onPrimaryContainer,
                modifier = Modifier.widthIn(max = 100.dp).padding(start = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Controller focus scrolls its action into view instead of overflowing a small handheld.
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
            actions.forEachIndexed { index, action ->
                ActionButton(
                    action.icon, action.description, size, action.tint,
                    // ▲ from the list lands on the first action, never on "clear selection".
                    modifier = if (index == 0) Modifier.focusRequester(focusRequester) else Modifier,
                    onClick = action.onClick
                )
            }
            }
        }
    }
}

private class BulkAction(val icon: Int, val description: String, val tint: Color, val onClick: () -> Unit)


/** One entry of the grouped list: a section header or a download row ([rowIndex] counts rows only). */
private class QueueEntry(val key: String, val header: DownloadGroups.Section?, val row: DownloadItemModel?, val rowIndex: Int) {
}

private fun flattenSections(sections: List<DownloadGroups.Section>): List<QueueEntry> {
    val out = ArrayList<QueueEntry>(sections.sumOf { it.rows.size + 1 })
    var rowIndex = 0
    for (section in sections) {
        out += QueueEntry("group-${section.group.name}", section, null, -1)
        for (row in section.rows) out += QueueEntry(row.fileName, null, row, rowIndex++)
    }
    return out
}

/** The title of a section (Active, Waiting, Failed, Done) with how many rows it holds. */
@Composable
private fun GroupHeader(group: DownloadGroups.Group, count: Int) {
    val scheme = MaterialTheme.colorScheme
    val (label, icon, tint) = when (group) {
        DownloadGroups.Group.ACTIVE -> Triple(R.string.flow52_group_active, R.drawable.ic_arrow_down, scheme.primary)
        DownloadGroups.Group.WAITING -> Triple(R.string.flow52_group_waiting, R.drawable.ic_hourglass, scheme.onSurfaceVariant)
        DownloadGroups.Group.FAILED -> Triple(R.string.flow52_group_failed, R.drawable.ic_error, scheme.error)
        DownloadGroups.Group.DONE -> Triple(R.string.flow52_group_done, R.drawable.ic_check_circle, scheme.tertiary)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(stringResource(label), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
        Text(count.toString(), style = MaterialTheme.typography.titleSmall, color = scheme.onSurfaceVariant)
    }
}
