package com.tufein.valkyr.ui.screens.download

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tufein.valkyr.R
import com.tufein.valkyr.data.model.PendingAutoRetry
import com.tufein.valkyr.data.model.DownloadItemModel
import com.tufein.valkyr.data.model.DownloadStatus
import com.tufein.valkyr.data.model.DownloadableFileWithTags
import com.tufein.valkyr.data.service.UploadState
import com.tufein.valkyr.data.service.UploadStatus
import com.tufein.valkyr.ui.components.CoverImage
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.tvSized
import com.tufein.valkyr.ui.components.GameCover
import com.tufein.valkyr.ui.components.Panel
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.TagRow
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.formatBytes
import com.tufein.valkyr.ui.components.rememberFocusSource
import com.tufein.valkyr.ui.components.stripExtension
import com.tufein.valkyr.ui.theme.LocalReduceMotion
import com.tufein.valkyr.ui.theme.tabular
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.DownloadMetrics
import com.tufein.valkyr.util.QueueActions
import com.tufein.valkyr.util.QueueEta
import com.tufein.valkyr.util.VerifyState
import com.tufein.valkyr.util.WaitInfo
import java.text.DateFormat
import java.util.Date

/** The state pill of a row: what the download is doing, in words, colour and an icon. */
private class StatePill(val tone: PillTone, val icon: Int)

/** What a row's buttons do; [DownloadItem] wires them to the view model, tests and previews to nothing. */
class DownloadRowActions(
    val pause: () -> Unit = {},
    val cancel: () -> Unit = {},
    val retry: () -> Unit = {},
    /** Asks (or not) about the file: true when the download finished and a file exists. */
    val delete: (hasFile: Boolean) -> Unit = {},
    val open: (Context) -> Unit = {},
    val retryUpload: () -> Unit = {},
    val moveUp: () -> Unit = {},
    val moveDown: () -> Unit = {},
    /** Opens "Download when..." for this row. */
    val waitFor: () -> Unit = {},
    /** Lifts the row's own condition: it starts now. */
    val startNow: () -> Unit = {},
    val openSettings: () -> Unit = {},
    val openSources: () -> Unit = {},
    val openStorage: () -> Unit = {},
    val retryNow: () -> Unit = {},
    val cancelAutomaticRetry: () -> Unit = {},
    val changeSource: () -> Unit = {}
)

/**
 * One download of the list: the row of [DownloadRow] wired to the view model.
 */
@Composable
fun DownloadItem(
    item: DownloadItemModel,
    details: DownloadableFileWithTags?,
    compact: Boolean,
    viewModel: DownloadViewModel,
    modifier: Modifier = Modifier,
    upload: UploadState? = null,
    waitingReason: String? = null,
    queuePosition: Int? = null,
    verify: VerifyState? = null,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    focusUp: FocusRequester? = null,
    sweep: State<Float>? = null,
    /** The row's own "Download when..." condition while it is not met yet. */
    condition: WaitInfo? = null,
    /** Not started yet, so a condition can still be set. */
    canSchedule: Boolean = false,
    onWaitFor: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenSources: () -> Unit = {},
    onOpenStorage: () -> Unit = {},
    onToggleSelection: () -> Unit = {},
    onRowFocused: (DownloadItemModel, Boolean) -> Unit = { _, _ -> },
    pendingRetry: PendingAutoRetry? = null,
    canChangeSource: Boolean = false,
    onChangeSource: () -> Unit = {}
) {
    val fileName = item.fileName
    val actions = remember(viewModel, fileName, onWaitFor, onOpenSettings, onOpenSources, onOpenStorage, onChangeSource) {
        DownloadRowActions(
            pause = { viewModel.pauseDownload(fileName) },
            cancel = { viewModel.cancelDownload(fileName) },
            retry = { viewModel.retryDownload(fileName) },
            delete = { hasFile -> viewModel.deleteDownloadWithConfirmation(fileName, hasFile) },
            open = { context -> viewModel.openDownload(context, fileName) },
            retryUpload = { viewModel.retryUpload(fileName) },
            moveUp = { viewModel.moveUp(fileName) },
            moveDown = { viewModel.moveDown(fileName) },
            waitFor = onWaitFor,
            startNow = { viewModel.setCondition(listOf(fileName), null) },
            openSettings = onOpenSettings,
            openSources = onOpenSources,
            openStorage = onOpenStorage,
            retryNow = { viewModel.retryNow(fileName) },
            cancelAutomaticRetry = { viewModel.cancelAutomaticRetry(fileName) },
            changeSource = onChangeSource
        )
    }
    // Only rows handed to a debrid service show its name.
    val debridLabel = if (item.status == DownloadStatus.QUEUED) viewModel.debridLabel.collectAsStateWithLifecycle().value else ""
    // 7.5: the source a failed download moved to by itself.
    val switchedTo = viewModel.switchedSources.collectAsStateWithLifecycle().value[fileName]
    // Sources tracker: where this download came from.
    val fromSource = viewModel.provenance.collectAsStateWithLifecycle().value[fileName]?.source
    DownloadRow(
        item, details, compact, actions, debridLabel, modifier, upload, waitingReason, queuePosition, verify,
        selectionMode, selected, focusUp, sweep, onToggleSelection, onRowFocused, condition, canSchedule, switchedTo, pendingRetry, canChangeSource, fromSource
    )
}

/**
 * One download. [details] is the indexed file behind it (console + tags) so two
 * versions of the same game can be told apart; null when it is no longer indexed.
 *
 * 5.0: a card with the game's cover (the status as a small badge on it), the title, pills for the
 * state / RomM upload / checksum, a progress bar in the state's colour and the detail line.
 *
 * While [selectionMode] is on the row is a checkbox: A / a tap ticks it instead of
 * running its action, and the per-row buttons step aside for the selection bar. A ticked row
 * shows a check over its cover and an accent ring around the whole card.
 *
 * @param debridLabel name of the debrid service on a row it is fetching for.
 * @param sweep the shared position (0..1) of the sliding segment of the busy bars; one animation
 *   for the whole list instead of one per row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadRow(
    item: DownloadItemModel,
    details: DownloadableFileWithTags?,
    compact: Boolean,
    actions: DownloadRowActions,
    debridLabel: String,
    modifier: Modifier = Modifier,
    upload: UploadState? = null,
    /** Why this download has not started (Wi-Fi / charger / night), or null. */
    waitingReason: String? = null,
    /** Place in the queue (1 = next to start) while waiting for a free slot; null otherwise. */
    queuePosition: Int? = null,
    /** Result of comparing the finished file with the hash its source published. */
    verify: VerifyState? = null,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    /** Where ▲ goes from this row: the selection bar sits off-centre, so focus search never picks it. */
    focusUp: FocusRequester? = null,
    sweep: State<Float>? = null,
    onToggleSelection: () -> Unit = {},
    onRowFocused: (DownloadItemModel, Boolean) -> Unit = { _, _ -> },
    /** The row's own "Download when..." condition while it is not met yet; its pill wins over [waitingReason]. */
    condition: WaitInfo? = null,
    /** Not started yet (in line, or waiting): the *Wait for...* button shows. */
    canSchedule: Boolean = false,
    /** Short name of the source this download moved to after failing on its first one, or null. */
    switchedTo: String? = null,
    pendingRetry: PendingAutoRetry? = null,
    canChangeSource: Boolean = false,
    /** Short name of the source this game was downloaded from, or null for older history rows. */
    fromSource: String? = null
) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    val status = item.status
    val statusColor = when (status) {
        DownloadStatus.COMPLETED -> scheme.tertiary
        DownloadStatus.FAILED -> scheme.error
        DownloadStatus.STOPPED, DownloadStatus.PAUSED -> scheme.onSurfaceVariant
        else -> scheme.primary
    }
    val statusIcon = when (status) {
        DownloadStatus.COMPLETED -> R.drawable.ic_check
        DownloadStatus.FAILED -> R.drawable.ic_error
        DownloadStatus.STOPPED -> R.drawable.ic_stop
        DownloadStatus.PAUSED -> R.drawable.ic_pause
        DownloadStatus.COPYING -> R.drawable.ic_folder
        DownloadStatus.UNZIPPING -> R.drawable.ic_extract
        DownloadStatus.DOWNLOADING -> R.drawable.ic_arrow_down
        DownloadStatus.QUEUED -> R.drawable.ic_cloud_download
    }
    val conditionText = condition?.let { conditionPillText(it) }
    val waiting = (waitingReason != null || condition != null) && status == DownloadStatus.DOWNLOADING
    val inQueue = queuePosition != null && condition == null && status == DownloadStatus.DOWNLOADING
    val transferring = status == DownloadStatus.DOWNLOADING && !waiting && !inQueue
    val metrics = remember(item) { DownloadMetrics.of(item) }
    val feedback = if (status == DownloadStatus.FAILED || (status == DownloadStatus.STOPPED && item.failure != null)) {
        item.failure.feedback()
    } else null
    val recover: () -> Unit = {
        when (feedback?.action) {
            DownloadRecoveryAction.SETTINGS -> actions.openSettings()
            DownloadRecoveryAction.STORAGE -> actions.openStorage()
            DownloadRecoveryAction.SOURCES -> actions.openSources()
            DownloadRecoveryAction.RETRY, null -> actions.retry()
        }
    }
    val statusLabel = if (waiting) conditionText ?: waitingReason.orEmpty()
    else if (inQueue) stringResource(R.string.status_in_queue, queuePosition)
    else when (status) {
        DownloadStatus.QUEUED -> stringResource(R.string.status_queued_debrid, debridLabel, (item.progress * 100).toInt())
        DownloadStatus.COMPLETED -> stringResource(R.string.status_completed)
        DownloadStatus.FAILED -> stringResource(R.string.status_failed)
        DownloadStatus.STOPPED -> stringResource(R.string.status_stopped)
        DownloadStatus.PAUSED -> stringResource(R.string.status_paused)
        DownloadStatus.COPYING -> stringResource(R.string.status_copying)
        DownloadStatus.UNZIPPING -> stringResource(R.string.status_extracting)
        DownloadStatus.DOWNLOADING -> stringResource(R.string.status_downloading, (item.progress * 100).toInt())
    }
    val statePill = when {
        waiting -> StatePill(PillTone.Warning, R.drawable.ic_hourglass)
        inQueue -> StatePill(PillTone.Neutral, R.drawable.ic_hourglass)
        status == DownloadStatus.COMPLETED -> StatePill(PillTone.Success, R.drawable.ic_check)
        status == DownloadStatus.FAILED -> StatePill(PillTone.Danger, R.drawable.ic_error)
        status == DownloadStatus.STOPPED || status == DownloadStatus.PAUSED -> StatePill(PillTone.Neutral, statusIcon)
        status == DownloadStatus.DOWNLOADING -> StatePill(PillTone.Accent, statusIcon)
        else -> StatePill(PillTone.Info, statusIcon)
    }
    val timestamp = remember(item.startedAt, item.finishedAt, status) {
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val at = if (item.isFinished) item.finishedAt ?: item.startedAt else item.startedAt
        if (at > 0L) fmt.format(Date(at)) else null
    }
    val timeLabel = timestamp?.let {
        if (item.isFinished) stringResource(R.string.download_finished_at, it)
        else stringResource(R.string.download_started_at, it)
    }
    val sizeText = if (item.fileSize > 0L) {
        if (status == DownloadStatus.DOWNLOADING || status == DownloadStatus.PAUSED || status == DownloadStatus.STOPPED || status == DownloadStatus.FAILED) {
            "${formatBytes(item.downloadedBytes.coerceAtLeast(0L))} / ${formatBytes(item.fileSize)}"
        } else formatBytes(item.fileSize)
    } else if (item.downloadedBytes > 0L) {
        stringResource(R.string.download_bytes_unknown_total, formatBytes(item.downloadedBytes))
    } else stringResource(R.string.download_size_unknown)
    val detail = sizeText + (timeLabel?.let { "  ·  $it" } ?: "") +
        (fromSource?.let { "  ·  " + stringResource(R.string.src91_from, it) } ?: "") +
        (switchedTo?.let { "  ·  " + stringResource(R.string.src75_switched_to, it) } ?: "")
    val busy = status == DownloadStatus.COPYING || status == DownloadStatus.UNZIPPING ||
        (status == DownloadStatus.QUEUED && item.progress <= 0f) || waiting || inQueue
    // 5.2: 48 dp targets everywhere (they were 34 / 40 dp), with a 24 dp icon.
    val actionSize: Dp = 48.dp

    // A (or a tap) on the row runs the primary action; the side buttons stay for touch and
    // are skipped by D-pad focus search (they sit inside the focused row's bounds).
    val isTorrent = details?.file?.isTorrent == true
    val primaryAction: () -> Unit = {
        when {
            feedback != null -> recover()
            details != null && QueueActions.canPause(status, isTorrent) -> actions.pause()
            status == DownloadStatus.QUEUED || status == DownloadStatus.DOWNLOADING || status == DownloadStatus.UNZIPPING ->
                actions.cancel()
            status == DownloadStatus.COMPLETED || status == DownloadStatus.STOPPED ||
            status == DownloadStatus.FAILED || status == DownloadStatus.PAUSED ->
                actions.retry()
            else -> Unit
        }
    }
    // The selection bar owns the actions while ticking rows.
    // 3.9.0: 8 dp apart (was 4): touch targets this small grow to 48 dp only into free space, so
    // with 4 dp gaps a thumb meant for Pause could land on Delete next to it.
    val actionButtons: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (queuePosition != null && !selectionMode) {
                ActionButton(R.drawable.ic_arrow_upward, stringResource(R.string.queue_move_up), actionSize, scheme.onSurface) { actions.moveUp() }
                ActionButton(R.drawable.ic_arrow_downward, stringResource(R.string.queue_move_down), actionSize, scheme.onSurface) { actions.moveDown() }
            }
            when (if (selectionMode) null else status) {
                DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.UNZIPPING -> {
                    if (condition != null) {
                        ActionButton(R.drawable.ic_play_arrow, stringResource(R.string.plan6_start_now), actionSize, scheme.primary) {
                            actions.startNow()
                        }
                    }
                    if (canSchedule) {
                        ActionButton(R.drawable.ic_schedule, stringResource(R.string.plan6_wait_for), actionSize, scheme.onSurface) {
                            actions.waitFor()
                        }
                    }
                    if (details != null && QueueActions.canPause(status, isTorrent)) {
                        ActionButton(R.drawable.ic_pause, stringResource(R.string.download_pause), actionSize, scheme.onSurface) {
                            actions.pause()
                        }
                    }
                    ActionButton(R.drawable.ic_stop, stringResource(R.string.download_cancel), actionSize, scheme.onSurface) {
                        actions.cancel()
                    }
                }
                null, DownloadStatus.COPYING -> Unit
                DownloadStatus.PAUSED -> {
                    ActionButton(R.drawable.ic_play_arrow, stringResource(R.string.download_resume), actionSize, scheme.primary) {
                        actions.retry()
                    }
                    ActionButton(R.drawable.ic_trash, stringResource(R.string.download_delete), actionSize, scheme.error) {
                        actions.delete(false)
                    }
                }
                DownloadStatus.COMPLETED, DownloadStatus.STOPPED, DownloadStatus.FAILED -> {
                    if (status == DownloadStatus.COMPLETED) {
                        val context = LocalContext.current
                        ActionButton(R.drawable.ic_play_arrow, stringResource(R.string.download_open), actionSize, scheme.primary) {
                            actions.open(context)
                        }
                    }
                    if (upload?.status == UploadStatus.FAILED) {
                        ActionButton(R.drawable.ic_cloud_upload, stringResource(R.string.romm_upload_retry), actionSize, scheme.primary) {
                            actions.retryUpload()
                        }
                    }
                    if (feedback == null || feedback.action != DownloadRecoveryAction.RETRY) {
                        ActionButton(R.drawable.ic_retry, stringResource(R.string.download_retry), actionSize, scheme.onSurface) {
                            actions.retry()
                        }
                    }
                    ActionButton(R.drawable.ic_trash, stringResource(R.string.download_delete), actionSize, scheme.error) {
                        actions.delete(status == DownloadStatus.COMPLETED)
                    }
                }
            }
        }
    }
    // In a narrow (portrait) card the buttons sit under the text, where the pills have room; in a
    // wide one they stay at the end of the row.
    val actionsBelow = !compact
    val hasActions = !selectionMode && status != DownloadStatus.COPYING
    val shape = RoundedCornerShape(12.dp)
    Panel(
        modifier = modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.border(2.dp, scheme.primary, shape) else Modifier),
        shape = shape
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (selected) scheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                .focusRing(source, cornerRadius = 12.dp)
                .focusProperties { focusUp?.let { up = it } }
                .onFocusChanged { onRowFocused(item, it.isFocused) }
                .combinedClickable(
                    interactionSource = source,
                    indication = null,
                    onClick = { if (selectionMode) onToggleSelection() else primaryAction() },
                    // Long press is how touch enters selection mode (the pad uses SELECT).
                    onLongClick = onToggleSelection
                )
                .defaultMinSize(minHeight = tvSized(if (compact) 66.dp else 82.dp))   // 8.0: taller in TV mode
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = if (actionsBelow) Alignment.Top else Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // The status also reads as a pill, so the badge on the cover doubles as the checkbox.
            Cover(
                item = item,
                details = details,
                compact = compact,
                selectionMode = selectionMode,
                selected = selected,
                statusIcon = statusIcon,
                statusColor = statusColor
            )

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    stripExtension(item.name),
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface,
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Pill(statusLabel, tone = statePill.tone, icon = statePill.icon)
                    upload?.let { up ->
                        when (up.status) {
                            UploadStatus.UPLOADING -> Pill(stringResource(R.string.q5_romm_uploading, (up.progress * 100).toInt()), tone = PillTone.Info, icon = R.drawable.ic_cloud_upload)
                            UploadStatus.DONE -> Pill(stringResource(R.string.q5_romm_done), tone = PillTone.Success, icon = R.drawable.ic_cloud_done)
                            UploadStatus.FAILED -> Pill(stringResource(R.string.q5_romm_failed), tone = PillTone.Danger, icon = R.drawable.ic_cloud_off)
                        }
                    }
                    when (verify) {
                        VerifyState.VERIFIED -> Pill(stringResource(R.string.q5_verified), tone = PillTone.Success, icon = R.drawable.ic_verified)
                        VerifyState.MISMATCH -> Pill(stringResource(R.string.q5_verify_mismatch), tone = PillTone.Danger, icon = R.drawable.ic_warning)
                        VerifyState.CHECKING -> Pill(stringResource(R.string.q5_verify_checking), tone = PillTone.Neutral, icon = R.drawable.ic_hourglass)
                        VerifyState.DAT_OK -> Pill(stringResource(R.string.q5_dat_ok), tone = PillTone.Success, icon = R.drawable.ic_verified)
                        VerifyState.DAT_UNKNOWN -> Pill(stringResource(R.string.q5_dat_unknown), tone = PillTone.Neutral, icon = R.drawable.ic_info)
                        null -> Unit
                    }
                }
                DownloadBar(
                    fraction = if (status == DownloadStatus.COMPLETED) 1f else item.progress,
                    color = statusColor,
                    indeterminate = busy,
                    sweep = sweep,
                    animateFraction = status == DownloadStatus.DOWNLOADING
                )
                if (transferring) {
                    Text(
                        transferLine(metrics),
                        style = MaterialTheme.typography.bodySmall.tabular(),
                        color = scheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                feedback?.let { failure ->
                    Text(
                        stringResource(failure.message) + (item.failure?.httpStatusCode?.let {
                            " " + stringResource(R.string.download_http_status, it)
                        } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.error
                    )
                    DownloadFailureTimestamp(item.failureAt)
                    if (!selectionMode && pendingRetry == null) {
                        ActionPill(
                            stringResource(failure.action.label),
                            onClick = recover,
                            icon = failure.action.icon,
                            tone = ActionTone.Accent
                        )
                    }
                }
                pendingRetry?.let { pending ->
                    DownloadRetryFeedback(pending, actions.retryNow, actions.cancelAutomaticRetry, actionsEnabled = !selectionMode)
                }
                if (canChangeSource && !selectionMode) {
                    ActionPill(stringResource(R.string.source26_choose), actions.changeSource, icon = R.drawable.ic_hub)
                }
                if (upload?.status == UploadStatus.FAILED && upload.message.isNotBlank()) {
                    Text(upload.message, style = MaterialTheme.typography.bodySmall, color = scheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                details?.let {
                    TagRow(
                        console = ConsoleFormatter.getConsoleShortName(it.file.consoleId),
                        tags = it.tags,
                        extension = it.file.fileExtension,
                        maxLines = if (compact) 1 else Int.MAX_VALUE
                    )
                }
                if (actionsBelow && hasActions) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actionButtons() }
                }
            }

            if (!actionsBelow && hasActions) actionButtons()
        }
    }
}

/** The estimate belongs to this file; a waiting/paused row never displays a stale transfer speed. */
@Composable
private fun transferLine(metrics: DownloadMetrics.Metrics): String {
    val speed = if (metrics.bytesPerSecond > 0L) {
        stringResource(R.string.q5_per_second, formatBytes(metrics.bytesPerSecond))
    } else stringResource(R.string.download_waiting_data)
    val eta = metrics.etaSeconds?.let { seconds ->
        val (hours, minutes) = QueueEta.hoursMinutes(seconds)
        val duration = if (hours > 0L) stringResource(R.string.downloads_eta_hours, hours, minutes)
        else stringResource(R.string.downloads_eta_minutes, minutes)
        stringResource(R.string.download_time_left, duration)
    } ?: stringResource(R.string.download_eta_unknown)
    return "$speed · $eta"
}

/**
 * The game's cover with the status as a badge on its corner. While rows are being ticked the cover
 * is the checkbox: a faint outline when open, an accent wash with a check when ticked.
 */
@Composable
private fun Cover(
    item: DownloadItemModel,
    details: DownloadableFileWithTags?,
    compact: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    statusIcon: Int,
    statusColor: Color
) {
    val scheme = MaterialTheme.colorScheme
    val coverShape = RoundedCornerShape(8.dp)
    val width = if (compact) 38.dp else 44.dp
    val height = if (compact) 50.dp else 58.dp
    Box(modifier = Modifier.size(width = width, height = height)) {
        val consoleId = details?.file?.consoleId
        if (consoleId != null) {
            GameCover(consoleId, item.fileName, stripExtension(item.name), Modifier.fillMaxSize(), coverShape)
        } else {
            CoverImage(null, "", Modifier.fillMaxSize(), coverShape, showLabel = false)
        }
        if (selectionMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(coverShape)
                    .then(
                        if (selected) Modifier
                            .background(scheme.primary.copy(alpha = 0.62f))
                            .border(2.dp, scheme.primary, coverShape)
                        else Modifier.border(1.5.dp, scheme.onSurface.copy(alpha = 0.45f), coverShape)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(
                        painterResource(R.drawable.ic_check),
                        contentDescription = stringResource(R.string.selection_selected),
                        tint = scheme.onPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        } else {
            val (badge, onBadge) = when (item.status) {
                DownloadStatus.COMPLETED -> scheme.tertiary to scheme.onTertiary
                DownloadStatus.FAILED -> scheme.error to scheme.onError
                DownloadStatus.STOPPED, DownloadStatus.PAUSED -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
                else -> statusColor to scheme.onPrimary
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 5.dp, y = 5.dp)
                    .size(20.dp)
                    .border(2.dp, scheme.surfaceContainer, CircleShape)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(badge),
                contentAlignment = Alignment.Center
            ) {
                Icon(painterResource(statusIcon), contentDescription = null, tint = onBadge, modifier = Modifier.size(11.dp))
            }
        }
    }
}

/**
 * The progress bar of a row. A finished or parked row draws its value as it is; a running one
 * follows its progress smoothly; a busy one (copying, unpacking, waiting at the debrid service or
 * in the queue) shows a calm segment sliding along the track. Everything is read in the draw phase,
 * so a progress tick or the sliding never recomposes the row.
 */
@Composable
private fun DownloadBar(
    fraction: Float,
    color: Color,
    indeterminate: Boolean,
    sweep: State<Float>?,
    animateFraction: Boolean,
    modifier: Modifier = Modifier
) {
    val reduce = LocalReduceMotion.current
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val target = fraction.coerceIn(0f, 1f)
    val shown = remember { Animatable(target) }
    LaunchedEffect(target, animateFraction, reduce) {
        if (reduce || !animateFraction) shown.snapTo(target) else shown.animateTo(target, tween(400, easing = LinearEasing))
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .drawBehind {
                val radius = CornerRadius(size.height / 2)
                drawRoundRect(track, cornerRadius = radius)
                if (indeterminate) {
                    val segment = size.width * 0.34f
                    // Without a running animation (reduced motion) the segment rests in the middle.
                    val position = sweep?.value ?: 0.5f
                    val x = (size.width + segment) * position - segment
                    clipRect(0f, 0f, size.width, size.height) {
                        drawRoundRect(color, topLeft = Offset(x, 0f), size = Size(segment, size.height), cornerRadius = radius)
                    }
                } else {
                    val w = size.width * shown.value
                    if (w > 0f) drawRoundRect(color, size = Size(maxOf(w, size.height), size.height), cornerRadius = radius)
                }
            }
    )
}

/** A small tonal icon button for the touch actions of a row and the bulk actions of the selection bar. */
@Composable
internal fun ActionButton(
    icon: Int,
    description: String,
    size: Dp,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val source = rememberFocusSource()
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .focusRing(source, cornerRadius = 10.dp)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(painterResource(icon), contentDescription = description, tint = tint, modifier = Modifier.size(24.dp))
    }
}
