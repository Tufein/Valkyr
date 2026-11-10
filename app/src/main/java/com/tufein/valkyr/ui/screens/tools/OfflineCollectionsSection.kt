package com.tufein.valkyr.ui.screens.tools

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tufein.valkyr.R
import com.tufein.valkyr.data.service.RemovalPlan
import com.tufein.valkyr.data.service.StaleGame
import com.tufein.valkyr.ui.navigation.NavRoutes
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.Stepper
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.formatBytes
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.ui.components.stripExtension
import com.tufein.valkyr.ui.screens.settings.ThemedSwitch
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.FileParsingUtils
import com.tufein.valkyr.util.OfflineCollections
import java.text.DateFormat

/*
 * 7.0 "Keep a collection on this device", as pieces the collections screen shows: a switch row under
 * each collection, a "Fetch now" action, a block with the last run and the settings of the feature,
 * and the review of fetched games that left their collection (removed only after a confirmation that
 * lists the files).
 */

/** What [rememberOfflineCollections] hands to the collections screen. */
@Stable
class OfflineCollectionsUi internal constructor(
    internal val vm: OfflineCollectionsViewModel,
    internal val data: OfflineData
) {
    val anyKept: Boolean get() = data.kept.isNotEmpty()

    /** The switch row (with its status line) for one collection; put it under the collection's own row. */
    @Composable
    fun SwitchRow(collectionId: Long, modifier: Modifier = Modifier) {
        val on = collectionId in data.kept
        val state = data.state
        val tally = state.tallies[collectionId]
        val line = when {
            !on -> stringResource(R.string.offline7_switch_hint)
            state.running -> stringResource(R.string.offline7_status_running)
            tally == null -> stringResource(R.string.offline7_status_checking)
            else -> tallyLine(tally)
        }
        ToolRow(
            title = stringResource(R.string.offline7_switch_title),
            lines = listOf(line),
            onClick = { vm.setKept(collectionId, !on) },
            modifier = modifier.padding(start = 20.dp),
            icon = R.drawable.ic_download
        ) { ThemedSwitch(on) { vm.setKept(collectionId, it) } }
        if (on) ToolRow(stringResource(R.string.off25_quota), listOf(stringResource(R.string.off25_quota_hint)),
            onClick = { vm.setQuota(collectionId, (data.quotas[collectionId] ?: 0) + 1) }, icon = R.drawable.ic_storage,
            modifier = modifier.padding(start = 20.dp)) {
            val gb = data.quotas[collectionId] ?: 0
            Stepper(if (gb == 0) stringResource(R.string.off25_unlimited) else "$gb GB",
                onDecrement = { vm.setQuota(collectionId, (gb - 1).coerceAtLeast(0)) },
                onIncrement = { vm.setQuota(collectionId, gb + 1) })
        }
        ToolRow(stringResource(R.string.off28_check), listOf(stringResource(R.string.off28_hint)),
            onClick = { vm.checkOffline(collectionId) }, icon = R.drawable.ic_controller,
            modifier = modifier.padding(start = 20.dp))
    }

    /** "Fetch now" for the actions strip; shown while at least one collection is on. */
    @Composable
    fun FetchNowAction() {
        if (!anyKept) return
        ToolAction(stringResource(R.string.offline7_fetch_now), icon = R.drawable.ic_download, tone = ActionTone.Accent) {
            if (!data.state.running) vm.askFetch()
        }
    }
}

/**
 * Connects the collections screen to the feature and shows its confirmation dialog when one is up.
 * Call once at the top of the screen.
 */
@Composable
fun rememberOfflineCollections(onNavigate: (String) -> Unit = {}): OfflineCollectionsUi {
    val vm: OfflineCollectionsViewModel = hiltViewModel()
    val data by vm.data.collectAsStateWithLifecycle()
    val removal by vm.removal.collectAsStateWithLifecycle()
    val fetch by vm.fetchPreview.collectAsStateWithLifecycle()
    val ready by vm.readiness.collectAsStateWithLifecycle()
    ready?.let { state -> OfflineReadinessDialog(state, vm::dismissReadiness,
        onRetry = { vm.checkOffline(state.collectionId) }, onNavigate = { route -> vm.dismissReadiness(); onNavigate(route) }) }
    fetch?.let { picks ->
        val cancelFocus = rememberInitialFocus()
        AlertDialog(modifier = Modifier.closeOnGamepadB(vm::dismissFetch), onDismissRequest = vm::dismissFetch,
            title = { Text(stringResource(R.string.off25_preview)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(pluralStringResource(R.plurals.off25_preview_hint, picks.size, picks.size, formatBytes(picks.sumOf { it.need })))
                if (picks.any { it.size <= 0 }) Text(stringResource(R.string.off25_unknown))
                picks.forEach { pick -> Text("• " + FileParsingUtils.decodeUrlEncodedFileName(pick.game.fileName) + " · " + formatBytes(pick.need), style = MaterialTheme.typography.bodySmall) }
            } },
            confirmButton = { DialogButton(stringResource(R.string.offline7_fetch_now), vm::confirmFetch, enabled = picks.isNotEmpty() && !data.state.noFolder && !data.state.running) },
            dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), vm::dismissFetch, initialFocus = cancelFocus) })
    }
    val context = LocalContext.current
    removal?.let { plan -> RemovalDialog(plan, onConfirm = { vm.confirmRemoval(context) }, onDismiss = vm::dismissRemoval) }
    return remember(vm, data) { OfflineCollectionsUi(vm, data) }
}

/** The block under the collections: last run, the settings, and the games that left a collection. */
fun LazyListScope.offlineCollectionsItems(offline: OfflineCollectionsUi) {
    val data = offline.data
    val review = data.state.review
    if (!offline.anyKept && review.isEmpty()) return
    if (offline.anyKept) {
        item(key = "offline-head") {
            SectionHeader(stringResource(R.string.offline7_section_title), stringResource(R.string.offline7_section_hint), icon = R.drawable.ic_download)
        }
        item(key = "offline-last") { LastRunCard(data) }
        item(key = "offline-cap") {
            ToolRow(
                title = stringResource(R.string.offline7_cap_title),
                lines = listOf(stringResource(R.string.offline7_cap_hint)),
                onClick = { offline.vm.setCap(data.cap + OfflineCollections.CAP_STEP) },
                icon = R.drawable.ic_collections
            ) {
                Stepper(
                    data.cap.toString(),
                    onDecrement = { offline.vm.setCap(data.cap - OfflineCollections.CAP_STEP) },
                    onIncrement = { offline.vm.setCap(data.cap + OfflineCollections.CAP_STEP) }
                )
            }
        }
        item(key = "offline-reserve") {
            ToolRow(stringResource(R.string.off25_reserve), listOf(stringResource(R.string.off25_reserve_hint)),
                onClick = { offline.vm.setReserveGb(data.reserveGb + 1) }, icon = R.drawable.ic_storage) {
                Stepper("${data.reserveGb} GB", onDecrement = { offline.vm.setReserveGb(data.reserveGb - 1) },
                    onIncrement = { offline.vm.setReserveGb(data.reserveGb + 1) })
            }
        }
        item(key = "offline-wifi") {
            ToolRow(
                title = stringResource(R.string.offline7_wifi_title),
                lines = listOf(stringResource(R.string.offline7_wifi_hint)),
                onClick = { offline.vm.setWifiOnly(!data.wifiOnly) },
                icon = R.drawable.ic_network_check
            ) { ThemedSwitch(data.wifiOnly) { offline.vm.setWifiOnly(it) } }
        }
    }
    if (review.isNotEmpty()) {
        item(key = "offline-review-head") {
            Column {
                SectionHeader(stringResource(R.string.offline7_review_title), stringResource(R.string.offline7_review_hint), icon = R.drawable.ic_trash)
                ToolsActions {
                    ToolAction(stringResource(R.string.offline7_review_remove_all, review.size), icon = R.drawable.ic_trash, tone = ActionTone.Danger) { offline.vm.askRemoval(review) }
                    ToolAction(stringResource(R.string.offline7_review_keep_all)) { offline.vm.keep(review) }
                }
            }
        }
        items(review, key = { "offline-r|" + it.game.consoleId + "|" + it.game.fileName }) { game ->
            ReviewRow(game, onRemove = { offline.vm.askRemoval(listOf(game)) }, onKeep = { offline.vm.keep(listOf(game)) })
        }
    }
}

@Composable
private fun tallyLine(t: OfflineCollections.Tally): String {
    val parts = listOfNotNull(
        stringResource(R.string.offline7_status_count, t.onDevice, t.total),
        t.queued.takeIf { it > 0 }?.let { stringResource(R.string.offline7_status_queued, it) },
        t.fetch.takeIf { it > 0 }?.let { stringResource(R.string.offline7_status_fetch, it) },
        t.noSpace.takeIf { it > 0 }?.let { stringResource(R.string.offline7_status_space, it) },
        t.quota.takeIf { it > 0 }?.let { stringResource(R.string.off25_quota_held, it) },
        t.overCap.takeIf { it > 0 }?.let { stringResource(R.string.offline7_status_cap, it) },
        t.notInLibrary.takeIf { it > 0 }?.let { stringResource(R.string.offline7_status_not_listed, it) }
    )
    return parts.joinToString(" · ")
}

@Composable
private fun LastRunCard(data: OfflineData) {
    val last = data.last
    val state = data.state
    val lines = buildList {
        add(
            when {
                state.running -> stringResource(R.string.offline7_status_running)
                last == null -> stringResource(R.string.offline7_last_none)
                else -> stringResource(
                    R.string.offline7_last_line,
                    DateUtils.getRelativeTimeSpanString(last.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                    pluralStringResource(R.plurals.offline7_last_queued, last.queued, last.queued)
                )
            }
        )
        if (state.noFolder) add(stringResource(R.string.offline7_no_folder))
        if (last != null && last.noSpace > 0) add(pluralStringResource(R.plurals.offline7_notify_space, last.noSpace, last.noSpace, formatBytes(last.missingBytes)))
        if (last != null && last.overCap > 0) add(pluralStringResource(R.plurals.offline7_notify_more, last.overCap, last.overCap))
    }
    val warn = state.noFolder || (last?.noSpace ?: 0) > 0
    InfoCard(lines, icon = if (warn) R.drawable.ic_error else R.drawable.ic_check_circle, danger = warn)
}

@Composable
private fun ReviewRow(game: StaleGame, onRemove: () -> Unit, onKeep: () -> Unit) {
    val title = stripExtension(FileParsingUtils.decodeUrlEncodedFileName(game.game.fileName))
    val lines = listOfNotNull(
        ConsoleFormatter.getConsoleDisplayName(game.game.consoleId),
        game.collectionNames.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.offline7_review_from, it.joinToString(", ")) }
    )
    ToolRow(title = title, lines = lines, onClick = onRemove, icon = R.drawable.ic_collections) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToolAction(stringResource(R.string.offline7_review_keep), onClick = onKeep)
            ToolAction(stringResource(R.string.offline7_review_remove), tone = ActionTone.Danger, onClick = onRemove)
        }
    }
}

/** The confirmation: every file that goes, by name (focus starts on Cancel; B cancels). */
@Composable
private fun RemovalDialog(plan: RemovalPlan, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancelFocus = rememberInitialFocus()
    val files = plan.files
    val absent = plan.items.count { it.entries.isEmpty() }
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.offline7_confirm_title, plan.items.size, plan.items.size)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (files.isEmpty()) {
                    Text(stringResource(R.string.offline7_confirm_nothing))
                } else {
                    Text(pluralStringResource(R.plurals.offline7_confirm_message, files.size, files.size, formatBytes(plan.bytes)))
                    Spacer(Modifier.height(8.dp))
                    files.take(MAX_LISTED).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    if (files.size > MAX_LISTED) Text(pluralStringResource(R.plurals.offline7_confirm_more, files.size - MAX_LISTED, files.size - MAX_LISTED), style = MaterialTheme.typography.bodySmall)
                    if (absent > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(pluralStringResource(R.plurals.offline7_confirm_absent, absent, absent), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            DialogButton(stringResource(if (files.isEmpty()) R.string.offline7_confirm_drop else R.string.offline7_confirm_delete), onClick = onConfirm, destructive = true)
        },
        dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), onClick = onDismiss, initialFocus = cancelFocus) }
    )
}

private const val MAX_LISTED = 30

/** The report is a dated local snapshot; every failed check links to the existing repair tool. */
@Composable
internal fun OfflineReadinessDialog(state: OfflineReadyUi, onDismiss: () -> Unit, onRetry: () -> Unit,
    onNavigate: (String) -> Unit) {
    val cancelFocus = rememberInitialFocus()
    AlertDialog(modifier = Modifier.closeOnGamepadB(onDismiss), onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.off28_report_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.loading) {
                Text(stringResource(R.string.off28_loading))
                LinearProgressIndicator()
            }
            if (state.failed) Text(stringResource(R.string.off28_failed), color = MaterialTheme.colorScheme.error)
            state.report?.let { report ->
                Text(report.name, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.off28_summary, report.readyCount, report.games.size + report.unchecked))
                Text(DateFormat.getDateTimeInstance().format(java.util.Date(report.checkedAt)), style = MaterialTheme.typography.bodySmall)
                if (report.unchecked > 0) Text(stringResource(if (state.loading) R.string.off28_remaining else R.string.off28_limited, report.unchecked))
                report.games.forEach { game ->
                    Text(game.title, style = MaterialTheme.typography.titleSmall)
                    val check = game.report
                    Text(stringResource(if (check?.ready == true) R.string.off28_ready else R.string.off28_attention),
                        color = if (check?.ready == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    if (game.unavailable) Text(stringResource(R.string.off28_unavailable), style = MaterialTheme.typography.bodySmall)
                    else if (check == null) {
                        Text(stringResource(R.string.off28_failed), style = MaterialTheme.typography.bodySmall)
                        ToolAction(stringResource(R.string.ready25_title), icon = R.drawable.ic_controller) {
                            onNavigate(ReadinessRoute.of(game.consoleId, game.fileName))
                        }
                    } else {
                        val receipt = check.packageInspection
                        if (receipt == null) Text(stringResource(R.string.off28_legacy), style = MaterialTheme.typography.bodySmall)
                        else {
                            if (receipt.complete && receipt.hashesChecked) Text(stringResource(R.string.off28_verified, receipt.files.size), style = MaterialTheme.typography.bodySmall)
                            val problems = receipt.missing + receipt.changed
                            if (problems.isNotEmpty()) {
                                Text(stringResource(R.string.off28_files_missing), style = MaterialTheme.typography.bodySmall)
                                problems.take(8).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                                if (problems.size > 8) Text(stringResource(R.string.off28_more, problems.size - 8), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (!check.filesOk) ToolAction(stringResource(R.string.ready25_missing), icon = R.drawable.ic_download) { onNavigate(NavRoutes.Downloads.route) }
                        if (!check.discsOk) ToolAction(stringResource(R.string.ready25_disc_problem), icon = R.drawable.ic_warning) { onNavigate(NavRoutes.Sets.route) }
                        if (!check.biosOk) ToolAction(stringResource(R.string.ready25_bios_problem), icon = R.drawable.ic_settings) { onNavigate(NavRoutes.Bios.route) }
                        if (check.needsExtract) ToolAction(stringResource(R.string.ready25_extract), icon = R.drawable.ic_folder) { onNavigate(NavRoutes.Files.route) }
                        ToolAction(stringResource(R.string.ready25_title), icon = R.drawable.ic_controller) {
                            onNavigate(ReadinessRoute.of(game.consoleId, game.fileName))
                        }
                    }
                }
            }
        } },
        confirmButton = { if (!state.loading) DialogButton(stringResource(R.string.tools_refresh), onRetry) },
        dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), onDismiss, initialFocus = cancelFocus) })
}
