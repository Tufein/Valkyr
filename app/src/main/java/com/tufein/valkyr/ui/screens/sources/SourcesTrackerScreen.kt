package com.tufein.valkyr.ui.screens.sources

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.LegendEntry
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.requestFocusWhenReady
import com.tufein.valkyr.ui.navigation.NavRoutes
import com.tufein.valkyr.ui.screens.sources.components.failureText
import com.tufein.valkyr.ui.screens.tools.PublishLegend
import com.tufein.valkyr.ui.screens.tools.StatGrid
import com.tufein.valkyr.ui.screens.tools.ToolAction
import com.tufein.valkyr.ui.screens.tools.ToolRow
import com.tufein.valkyr.ui.screens.tools.ToolsActions
import com.tufein.valkyr.ui.screens.tools.ToolStat
import com.tufein.valkyr.ui.screens.tools.ToolsTitle
import com.tufein.valkyr.util.SourceKind
import com.tufein.valkyr.util.SourceRanking
import com.tufein.valkyr.util.SourceTrackerRow
import com.tufein.valkyr.util.ToastUtil

/**
 * Sources tracker: all configured sources in one list, worst first, with their scan health and
 * their download track record, plus one-tap rescan and a shareable, redacted record.
 */
@Composable
fun SourcesTrackerScreen(
    navController: NavController,
    viewModel: SourcesTrackerViewModel = hiltViewModel()
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val rescanning by viewModel.rescanning.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocusWhenReady() }
    PublishLegend(listOf(LegendEntry("A", stringResource(R.string.pad_open)), LegendEntry("B", stringResource(R.string.pad_back))))

    val share = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val shareTitle = stringResource(R.string.src91_export_share)
    val copiedText = stringResource(R.string.src91_copied)
    fun share(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { share.launch(Intent.createChooser(intent, shareTitle)) }
    }

    val failing = rows.count { it.enabled && (it.scanFailure != null || SourceRanking.isDemoted(it.record, System.currentTimeMillis())) }
    val downloads = rows.sumOf { it.record?.total ?: 0 }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        item(key = "title") {
            ToolsTitle(
                stringResource(R.string.src91_tracker_title),
                icon = R.drawable.ic_globe,
                subtitle = stringResource(R.string.src91_tracker_hint)
            )
        }
        item(key = "stats") {
            StatGrid(
                firstFocus = firstFocus,
                stats = listOf(
                    ToolStat(stringResource(R.string.src91_sources), rows.size.toString(), icon = R.drawable.ic_globe),
                    ToolStat(stringResource(R.string.src91_failing), failing.toString(), icon = R.drawable.ic_warning),
                    ToolStat(stringResource(R.string.src91_downloads), downloads.toString(), icon = R.drawable.ic_download)
                )
            )
        }
        item(key = "actions") {
            ToolsActions {
                ActionPill(stringResource(R.string.src91_export), onClick = { viewModel.exportTrackRecord { uri -> share(uri) } }, icon = R.drawable.ic_share)
                ActionPill(stringResource(R.string.src91_trackers_title), onClick = { navController.navigate(NavRoutes.TorrentTrackers.route) }, icon = R.drawable.ic_hub)
                if (rescanning) CircularProgressIndicator()
            }
        }
        items(rows, key = { it.consoleId + "|" + it.url }) { row ->
            val now = System.currentTimeMillis()
            val lines = buildList {
                add("${row.manufacturerName} · ${row.consoleName}")
                row.games?.let { add(stringResource(R.string.src91_games, it)) }
                row.scanAt?.let { at ->
                    val ago = android.text.format.DateUtils.getRelativeTimeSpanString(at).toString()
                    row.scanFailure?.let { add(stringResource(R.string.source_result_failed, failureText(it, null), ago)) }
                        ?: add(stringResource(R.string.source_result_ok, row.games ?: 0, ago))
                } ?: add(stringResource(R.string.src91_never_scanned))
                row.record?.let { record ->
                    val ok = record.okPercent
                    val speed = SourceRanking.speedText(record.bytesPerSec)
                    if (ok != null) add(if (speed == null) stringResource(R.string.src75_track_ok, ok) else stringResource(R.string.src75_track, speed, ok))
                }
                row.scanTrend?.let { add(stringResource(R.string.src78_often, it.failures, it.scans, failureClassText(it.last.name))) }
                row.downloadTrend?.let { add(stringResource(R.string.src91_dl_trend, it.failures, it.downloads, failureClassText(it.last.name))) }
            }
            ToolRow(
                title = row.label,
                lines = lines,
                onClick = {
                    clipboard.setText(AnnotatedString(row.url))
                    ToastUtil.showSuccess(context, copiedText)
                },
                icon = kindIcon(row.kind),
                badge = {
                    Pill(
                        text = stringResource(healthLabel(row)),
                        tone = healthTone(row, now),
                        icon = if (row.scanFailure == null && !SourceRanking.isDemoted(row.record, now)) R.drawable.ic_check_circle else R.drawable.ic_error_circle
                    )
                },
                trailing = {
                    ToolAction(stringResource(R.string.pad_rescan), icon = R.drawable.ic_sync, onClick = { viewModel.rescan(row) })
                }
            )
        }
    }
}

@Composable
private fun kindIcon(kind: SourceKind) = when (kind) {
    SourceKind.ROMM -> R.drawable.ic_server
    SourceKind.TORRENT -> R.drawable.ic_hub
    SourceKind.WEB -> R.drawable.ic_globe
    SourceKind.OTHER -> R.drawable.ic_folder
}

@Composable
private fun healthLabel(row: SourceTrackerRow) = when {
    !row.enabled -> R.string.q5_disabled
    row.scanFailure != null -> R.string.q5_health_failed
    row.scanAt == null -> R.string.q5_health_not_scanned
    SourceRanking.isDemoted(row.record, System.currentTimeMillis()) -> R.string.q5_health_partial
    else -> R.string.q5_health_ok
}

@Composable
private fun healthTone(row: SourceTrackerRow, now: Long) = when {
    !row.enabled -> PillTone.Neutral
    row.scanFailure != null -> PillTone.Danger
    row.scanAt == null -> PillTone.Neutral
    SourceRanking.isDemoted(row.record, now) -> PillTone.Warning
    else -> PillTone.Success
}

/** FailureClass names are internal; the tracker shows a word, not an enum constant. */
private fun failureClassText(name: String) = name.lowercase()
