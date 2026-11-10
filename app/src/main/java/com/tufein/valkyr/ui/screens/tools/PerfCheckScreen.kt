package com.tufein.valkyr.ui.screens.tools

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.common.Gamepad
import com.tufein.valkyr.ui.common.GamepadButton
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.IconTile
import com.tufein.valkyr.ui.components.LegendEntry
import com.tufein.valkyr.ui.components.Panel
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.util.FrameSampler
import com.tufein.valkyr.util.PerfCheck
import com.tufein.valkyr.util.PerfCheck.Metric
import com.tufein.valkyr.util.PerfCheck.Outcome
import com.tufein.valkyr.util.PerfCheck.Skip
import com.tufein.valkyr.util.PerfCheck.Verdict
import com.tufein.valkyr.util.ToastUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.text.format.DateUtils
import com.tufein.valkyr.data.service.PerfCheckService
import com.tufein.valkyr.util.PerfNotices

/** Rows of the synthetic list that scrolls during the frame sample. */
private const val TEST_ROWS = 300

/** One sweep of the test list (down or up) in ms, and how many sweeps. */
private const val SWEEP_MS = 1_200
private const val SWEEPS = 4

/**
 * 4.7.0: Tools → Performance check. One button runs a short self-test on the real library, the
 * image cache and (after a preview) the download folder, then scrolls a test list by itself while
 * the window's frame times are sampled ([FrameSampler]). Results come as numbers with a plain word
 * ("Fast / Fine / Slow", limits in [PerfCheck.verdictOf]); Share report hands numbers and fixed
 * labels to the share sheet (never a path, a title or a key).
 */
@Composable
fun PerfCheckScreen(viewModel: PerfCheckViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val noticesOn by viewModel.noticesOn.collectAsStateWithLifecycle()
    val noticeHistory by viewModel.noticeHistory.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val firstFocus = rememberInitialFocus()
    var confirm by remember { mutableStateOf(false) }
    val shareTitle = stringResource(R.string.measure47_share_title)
    val refreshRate = view.display?.refreshRate ?: 60f
    val latestRefresh by rememberUpdatedState(refreshRate)
    val shareFailed = stringResource(R.string.measure47_skip_failed)
    val canShare = !ui.running && ui.outcomes.isNotEmpty()

    fun share() {
        val text = viewModel.report() ?: return
        scope.launch {
            try { viewModel.share(text, shareTitle) } catch (e: CancellationException) { throw e } catch (_: Exception) {
                ToastUtil.showError(context, shareFailed)
            }
        }
    }

    // The frame sample: the view model hands over after the storage part, this scrolls and measures.
    val listState = rememberLazyListState()
    LaunchedEffect(ui.step) {
        if (ui.step != PerfStep.FRAMES) return@LaunchedEffect
        val window = context.findActivity()?.window
        if (window == null) { viewModel.finishFrames(emptyList(), latestRefresh); return@LaunchedEffect }
        val sampler = FrameSampler(window)
        sampler.start()
        try {
            delay(300)
            repeat(SWEEPS) { i ->
                listState.animateScrollBy(if (i % 2 == 0) 4_000f else -4_000f, tween(SWEEP_MS, easing = LinearEasing))
            }
            delay(150)
        } catch (e: CancellationException) {
            sampler.stop()
            throw e
        }
        viewModel.finishFrames(sampler.stop(), latestRefresh)
    }

    // Y shares, X starts or stops; neither while the preview dialog is open.
    val canAct by rememberUpdatedState(!confirm)
    val shareNow by rememberUpdatedState(::share)
    val runningNow by rememberUpdatedState(ui.running)
    val canShareNow by rememberUpdatedState(canShare)
    LaunchedEffect(Unit) {
        Gamepad.presses.collect { button ->
            if (!canAct) return@collect
            when (button) {
                GamepadButton.Y -> if (canShareNow) shareNow()
                GamepadButton.X -> if (runningNow) viewModel.stop() else confirm = true
                else -> Unit
            }
        }
    }
    PublishLegend(
        if (confirm) null else listOf(
            LegendEntry("A", stringResource(R.string.pad_select)),
            LegendEntry("X", stringResource(if (ui.running) R.string.measure47_pad_stop else R.string.measure47_pad_run)),
            if (canShare) LegendEntry("Y", stringResource(R.string.measure47_pad_share)) else null,
            LegendEntry("B", stringResource(R.string.pad_back))
        ).filterNotNull()
    )

    val storageText = when {
        !ui.includeStorage -> stringResource(R.string.measure47_dialog_storage_off)
        ui.storage?.allowed == true -> stringResource(R.string.measure47_dialog_storage, PerfCheck.formatSize(PerfCheck.TEST_FILE_BYTES + PerfCheck.TEST_FILE_HEADROOM_BYTES))
        else -> stringResource(R.string.measure47_dialog_storage_unavailable)
    }
    if (confirm) PerfPreviewDialog(
        storageText = storageText,
        onStart = { confirm = false; viewModel.start(refreshRate) },
        onDismiss = { confirm = false }
    )

    val storageLine = storageLine(ui.storage)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        item { ToolsTitle(stringResource(R.string.measure47_title), icon = R.drawable.ic_speed, subtitle = stringResource(R.string.measure47_subtitle)) }
        item { InfoCard(listOf(stringResource(R.string.measure47_intro), stringResource(R.string.measure47_share_note))) }
        item {
            ToolRow(
                stringResource(R.string.measure47_storage_toggle), listOf(storageLine),
                onClick = { viewModel.setIncludeStorage(!ui.includeStorage) },
                icon = R.drawable.ic_storage,
                trailing = { Switch(checked = ui.includeStorage, onCheckedChange = null) }
            )
        }
        // 6.3: the switch for the hints the app gives while open when the device struggles, and the ones shown so far.
        item {
            ToolRow(
                stringResource(R.string.perf63_toggle), listOf(stringResource(R.string.perf63_toggle_hint)),
                onClick = { viewModel.setNoticesOn(!noticesOn) },
                icon = R.drawable.ic_warning,
                trailing = { Switch(checked = noticesOn, onCheckedChange = null) }
            )
        }
        item { SectionHeader(stringResource(R.string.perf63_history_title)) }
        if (noticeHistory.isEmpty()) item { InfoCard(listOf(stringResource(R.string.perf63_history_empty))) }
        else items(noticeHistory.size, key = { "notice$it" }) { index ->
            val notice = noticeHistory[index]
            ToolRow(
                stringResource(noticeText(notice.kind)),
                listOf(DateUtils.formatDateTime(context, notice.at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)),
                onClick = {},
                icon = R.drawable.ic_warning
            )
        }
        item {
            ToolsActions {
                if (ui.running) ActionPill(stringResource(R.string.measure47_stop), viewModel::stop, modifier = Modifier.focusRequester(firstFocus), icon = R.drawable.ic_stop, tone = ActionTone.Danger)
                else ActionPill(
                    stringResource(if (ui.finished) R.string.measure47_run_again else R.string.measure47_run), { confirm = true },
                    modifier = Modifier.focusRequester(firstFocus), icon = R.drawable.ic_play_arrow, tone = ActionTone.Accent
                )
                ActionPill(stringResource(R.string.measure47_share), ::share, icon = R.drawable.ic_share, enabled = canShare)
            }
        }
        if (ui.running) item {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stepText(ui.step), style = MaterialTheme.typography.bodyMedium)
            }
        }
        ui.leftover?.let { name -> item { InfoCard(listOf(stringResource(R.string.measure47_leftover_warn, name)), danger = true, icon = R.drawable.ic_warning) } }
        if (ui.outcomes.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.measure47_results_title)) }
            if (!ui.running && ui.finished) item { OverallCard(PerfCheck.overall(ui.outcomes)) }
            items(ui.outcomes, key = { it.metric.key }) { outcome -> OutcomeRow(outcome, ui.frames) }
            ui.device?.let { device -> item { DeviceCard(device, ui.games) } }
        }
        item { SectionHeader(stringResource(R.string.measure47_list_title), subtitle = stringResource(R.string.measure47_list_note)) }
        item { TestList(listState) }
    }
}

@Composable
private fun PerfPreviewDialog(storageText: String, onStart: () -> Unit, onDismiss: () -> Unit) {
    val focus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.measure47_dialog_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.measure47_dialog_intro), style = MaterialTheme.typography.bodyMedium)
                Text("• " + stringResource(R.string.measure47_dialog_db), style = MaterialTheme.typography.bodyMedium)
                Text("• " + stringResource(R.string.measure47_dialog_covers), style = MaterialTheme.typography.bodyMedium)
                Text("• $storageText", style = MaterialTheme.typography.bodyMedium)
                Text("• " + stringResource(R.string.measure47_dialog_frames), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { DialogButton(stringResource(R.string.measure47_dialog_start), onStart, initialFocus = focus) },
        dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), onDismiss) }
    )
}

@Composable
private fun OverallCard(verdict: Verdict) {
    val text = when (verdict) {
        Verdict.GOOD -> R.string.measure47_overall_good
        Verdict.OK -> R.string.measure47_overall_ok
        Verdict.SLOW -> R.string.measure47_overall_slow
        Verdict.INFO -> R.string.measure47_overall_none
    }
    InfoCard(
        listOf(stringResource(R.string.measure47_overall_title), stringResource(text)),
        accent = verdict == Verdict.GOOD || verdict == Verdict.OK,
        danger = verdict == Verdict.SLOW,
        icon = R.drawable.ic_speed,
        modifier = Modifier.focusableCard()
    )
}

@Composable
private fun OutcomeRow(outcome: Outcome, frames: PerfCheck.FrameSummary?) {
    val metric = outcome.metric
    when (outcome) {
        is Outcome.Skipped -> ToolRow(
            stringResource(metricTitle(metric)), listOf(stringResource(skipText(outcome.reason))), onClick = {}, icon = metricIcon(metric)
        )
        is Outcome.Measured -> {
            val verdict = PerfCheck.verdictOf(metric, outcome.value)
            val lines = buildList {
                add(stringResource(metricHint(metric)))
                outcome.extra?.let { extra ->
                    add(stringResource(if (metric in PerfCheck.COLD_FIRST) R.string.measure47_detail_first_run else R.string.measure47_detail_slowest, PerfCheck.format(metric, extra)))
                }
                if (metric == Metric.FRAMES && frames != null) {
                    add(stringResource(R.string.measure47_detail_frames, frames.frames, PerfCheck.formatMs(frames.p99), PerfCheck.formatMs(frames.budgetMs)))
                } else if (metric == Metric.COVER_DECODE) add(stringResource(R.string.measure47_detail_covers, outcome.count))
                else if (outcome.count > 0 && metric in PerfCheck.COLD_FIRST) add(stringResource(R.string.measure47_detail_runs, outcome.count))
            }
            ToolRow(
                stringResource(metricTitle(metric)), listOf(PerfCheck.format(metric, outcome.value)) + lines, onClick = {},
                icon = metricIcon(metric),
                badge = if (verdict == Verdict.INFO) null else ({ Pill(stringResource(verdictLabel(verdict)), tone = verdictTone(verdict)) })
            )
        }
    }
}

@Composable
private fun DeviceCard(device: PerfCheck.DeviceFacts, games: Int?) {
    val lines = buildList {
        add(stringResource(R.string.measure47_device_title))
        add(stringResource(R.string.measure47_device_android, device.apiLevel, device.model, device.cores))
        add(stringResource(R.string.measure47_device_ram, PerfCheck.formatSize(device.totalRamBytes), PerfCheck.ramClass(device.totalRamBytes), device.appHeapClassMb, device.largeHeapClassMb))
        add(stringResource(R.string.measure47_device_display, Math.round(device.refreshRate)))
        if (device.lowRamDevice) add(stringResource(R.string.measure47_device_lowram))
        games?.let { add(stringResource(R.string.measure47_device_library, it)) }
    }
    InfoCard(lines, icon = R.drawable.ic_memory, modifier = Modifier.focusableCard())
}

/** Simple rows (not focusable, not clickable) that scroll by themselves while frames are counted. */
@Composable
private fun TestList(state: LazyListState) {
    Panel(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp), contentPadding = PaddingValues(8.dp)) {
        LazyColumn(Modifier.fillMaxWidth().height(240.dp), state = state, userScrollEnabled = false, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(TEST_ROWS, key = { it }) { index ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconTile(R.drawable.ic_gamepad, size = 32.dp)
                    Column(Modifier.weight(1f)) {
                        Text("#${index + 1}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        Text("· · ·", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Pill("${index % 7}", tone = PillTone.Neutral)
                }
            }
        }
    }
}

@Composable
private fun storageLine(preview: PerfCheckService.StoragePreview?): String = when {
    preview == null -> stringResource(R.string.measure47_storage_toggle_ready)
    !preview.folderSet -> stringResource(R.string.measure47_storage_no_folder)
    !preview.usable -> stringResource(R.string.measure47_storage_unusable)
    !PerfCheck.storageTestAllowed(preview.freeBytes) ->
        stringResource(R.string.measure47_storage_no_space, PerfCheck.formatSize(PerfCheck.TEST_FILE_BYTES + PerfCheck.TEST_FILE_HEADROOM_BYTES))
    else -> stringResource(R.string.measure47_storage_toggle_ready)
}

private fun noticeText(kind: PerfNotices.Kind): Int = when (kind) {
    PerfNotices.Kind.HOT -> R.string.perf63_notice_hot
    PerfNotices.Kind.LOW_MEMORY -> R.string.perf63_notice_memory
    PerfNotices.Kind.LOW_STORAGE -> R.string.perf63_notice_storage
}

@Composable
private fun stepText(step: PerfStep?): String = stringResource(
    when (step) {
        PerfStep.DATABASE, null -> R.string.measure47_status_database
        PerfStep.COVERS -> R.string.measure47_status_covers
        PerfStep.STORAGE -> R.string.measure47_status_storage
        PerfStep.FRAMES -> R.string.measure47_status_frames
    }
)

private fun metricTitle(m: Metric): Int = when (m) {
    Metric.STARTUP -> R.string.measure47_m_startup
    Metric.DB_SEARCH -> R.string.measure47_m_db_search
    Metric.DB_FIRST_PAGE -> R.string.measure47_m_db_first_page
    Metric.DB_COUNT -> R.string.measure47_m_db_count
    Metric.DB_SHELVES -> R.string.measure47_m_db_shelves
    Metric.COVER_DECODE -> R.string.measure47_m_cover
    Metric.STORAGE_WRITE -> R.string.measure47_m_write
    Metric.STORAGE_READ -> R.string.measure47_m_read
    Metric.FREE_SPACE -> R.string.measure47_m_free
    Metric.FRAMES -> R.string.measure47_m_frames
    Metric.JANK -> R.string.measure47_m_jank
}

private fun metricHint(m: Metric): Int = when (m) {
    Metric.STARTUP -> R.string.measure47_m_startup_hint
    Metric.DB_SEARCH -> R.string.measure47_m_db_search_hint
    Metric.DB_FIRST_PAGE -> R.string.measure47_m_db_first_page_hint
    Metric.DB_COUNT -> R.string.measure47_m_db_count_hint
    Metric.DB_SHELVES -> R.string.measure47_m_db_shelves_hint
    Metric.COVER_DECODE -> R.string.measure47_m_cover_hint
    Metric.STORAGE_WRITE -> R.string.measure47_m_write_hint
    Metric.STORAGE_READ -> R.string.measure47_m_read_hint
    Metric.FREE_SPACE -> R.string.measure47_m_free_hint
    Metric.FRAMES -> R.string.measure47_m_frames_hint
    Metric.JANK -> R.string.measure47_m_jank_hint
}

private fun metricIcon(m: Metric): Int = when (m) {
    Metric.STARTUP -> R.drawable.ic_timer
    Metric.DB_SEARCH, Metric.DB_FIRST_PAGE, Metric.DB_COUNT, Metric.DB_SHELVES -> R.drawable.ic_library
    Metric.COVER_DECODE -> R.drawable.ic_image
    Metric.STORAGE_WRITE, Metric.STORAGE_READ, Metric.FREE_SPACE -> R.drawable.ic_storage
    Metric.FRAMES, Metric.JANK -> R.drawable.ic_speed
}

private fun skipText(reason: Skip): Int = when (reason) {
    Skip.NO_LIBRARY -> R.string.measure47_skip_no_library
    Skip.NO_COVERS -> R.string.measure47_skip_no_covers
    Skip.NO_FOLDER -> R.string.measure47_skip_no_folder
    Skip.FOLDER_UNUSABLE -> R.string.measure47_skip_folder_unusable
    Skip.NOT_ENOUGH_SPACE -> R.string.measure47_skip_not_enough_space
    Skip.NO_FRAMES -> R.string.measure47_skip_no_frames
    Skip.FAILED -> R.string.measure47_skip_failed
    Skip.CANCELLED -> R.string.measure47_skip_cancelled
}

private fun verdictLabel(v: Verdict): Int = when (v) {
    Verdict.GOOD -> R.string.measure47_verdict_good
    Verdict.OK -> R.string.measure47_verdict_ok
    Verdict.SLOW, Verdict.INFO -> R.string.measure47_verdict_slow
}

private fun verdictTone(v: Verdict): PillTone = when (v) {
    Verdict.GOOD -> PillTone.Success
    Verdict.OK -> PillTone.Info
    Verdict.SLOW -> PillTone.Danger
    Verdict.INFO -> PillTone.Neutral
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
