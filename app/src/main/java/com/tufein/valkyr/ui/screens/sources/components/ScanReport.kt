package com.tufein.valkyr.ui.screens.sources.components

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tufein.valkyr.R
import com.tufein.valkyr.data.state.SourceScanResult
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.ui.theme.consoleColor
import com.tufein.valkyr.util.FailureKind
import com.tufein.valkyr.util.ScanFailure

/** Why a source failed, in a few words. */
@Composable
fun failureText(kind: FailureKind, code: Int?): String = when (kind) {
    FailureKind.RATE_LIMITED -> stringResource(R.string.scan_fail_rate_limited)
    FailureKind.SERVER_ERROR -> stringResource(R.string.scan_fail_server, code ?: 500)
    FailureKind.NOT_FOUND -> stringResource(R.string.scan_fail_not_found, code ?: 404)
    FailureKind.FORBIDDEN -> stringResource(R.string.scan_fail_forbidden, code ?: 403)
    FailureKind.TIMEOUT -> stringResource(R.string.scan_fail_timeout)
    FailureKind.NETWORK -> stringResource(R.string.scan_fail_network)
    FailureKind.NO_TABLE -> stringResource(R.string.scan_fail_no_table)
    FailureKind.TORRENT_METADATA -> stringResource(R.string.scan_fail_torrent)
    FailureKind.OTHER -> stringResource(R.string.scan_fail_other)
}

/** The line under a source: how many games its last scan gave, or why it failed. */
@Composable
fun sourceResultText(result: SourceScanResult): String {
    val ago = DateUtils.getRelativeTimeSpanString(result.at).toString()
    result.failure?.let { return stringResource(R.string.source_result_failed, failureText(it, result.httpCode), ago) }
    val extras = buildList {
        if (result.unchanged) add(stringResource(R.string.source_result_unchanged))
        if (result.newFiles > 0) add(stringResource(R.string.source_result_new, result.newFiles))
        if (result.servedBy != null) add(stringResource(R.string.source_result_mirror))
    }
    return stringResource(R.string.source_result_ok, result.files ?: 0, ago) + extras.joinToString("") { " · $it" }
}

/**
 * After a scan in which sources failed: one dialog listing them (console and reason), with the
 * option to scan just those again.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScanReportDialog(failures: List<ScanFailure>, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val retryFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.scan_report_title, failures.size, failures.size)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(stringResource(R.string.scan_report_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                failures.sortedWith(compareBy({ it.consoleName.lowercase() }, { it.url })).forEach { f ->
                    // One card per source: the console in its colour, why it failed, and the address.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Pill(f.consoleName, tone = PillTone.Tint(consoleColor(f.consoleId)))
                            Pill(failureText(f.kind, f.httpCode), tone = PillTone.Danger, icon = R.drawable.ic_error_circle)
                        }
                        Text(
                            f.url.take(120),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        confirmButton = { DialogButton(text = stringResource(R.string.scan_report_retry), onClick = onRetry, initialFocus = retryFocus) },
        dismissButton = { DialogButton(text = stringResource(R.string.dialog_close), onClick = onDismiss) }
    )
}

/** 7.8: "failed 3 scans in a row, last: rate limited", or null while the source looks healthy. */
@Composable
fun sourceTrendText(result: SourceScanResult): String? {
    val trend = com.tufein.valkyr.util.SourceHealth.trend(result.history, System.currentTimeMillis()) ?: return null
    val why = failureText(trend.last, result.httpCode.takeIf { result.failure == trend.last })
    return if (trend.streak >= 2) stringResource(R.string.src78_streak, trend.streak, why)
    else stringResource(R.string.src78_often, trend.failures, trend.scans, why)
}
