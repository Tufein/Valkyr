package com.tufein.valkyr.ui.screens.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.MeterBar
import com.tufein.valkyr.ui.components.Panel
import com.tufein.valkyr.ui.components.PanelTone
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.formatBytes
import com.tufein.valkyr.ui.components.rememberFocusSource
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.ui.theme.consoleColor
import com.tufein.valkyr.ui.theme.tabular
import com.tufein.valkyr.util.BulkPlan
import com.tufein.valkyr.util.BulkPlanner
import com.tufein.valkyr.util.ConsoleFormatter

/**
 * "Download all": what the games the filters show would add up to (count, size, free space), what
 * is skipped, and the choice to take only the best version of each game.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BulkDownloadDialog(
    plan: suspend (bestOnly: Boolean) -> BulkPlan,
    onConfirm: (BulkPlan) -> Unit,
    onDismiss: () -> Unit,
    /** Space that removing duplicate games would free, or null when unknown (a scan of the folders). */
    reclaimable: suspend () -> Long? = { null },
    onFreeUp: () -> Unit = {}
) {
    var bestOnly by remember { mutableStateOf(true) }
    var current by remember { mutableStateOf<BulkPlan?>(null) }
    LaunchedEffect(bestOnly) {
        current = null
        current = plan(bestOnly)
    }
    val cancelFocus = rememberInitialFocus()
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_download), contentDescription = null, tint = scheme.primary) },
        title = { Text(stringResource(R.string.bulk_title)) },
        text = {
            // Scrolls on short landscape screens instead of cutting the summary off.
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                // "Best version only" as a focusable tick row (same look as the filter options).
                val toggleSource = rememberFocusSource()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (bestOnly) scheme.primary.copy(alpha = 0.10f) else scheme.surfaceContainerHigh,
                            RoundedCornerShape(10.dp)
                        )
                        .focusRing(toggleSource, 10.dp)
                        .toggleable(value = bestOnly, interactionSource = toggleSource, indication = null, onValueChange = { bestOnly = it })
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                ) {
                    Icon(
                        painterResource(if (bestOnly) R.drawable.ic_checkbox_on else R.drawable.ic_checkbox_off),
                        contentDescription = null,
                        tint = if (bestOnly) scheme.primary else scheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(stringResource(R.string.bulk_best_only), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                }
                val p = current
                if (p == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.bulk_counting), style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Panel(
                        tone = if (p.fits) PanelTone.Normal else PanelTone.Danger,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            pluralStringResource(R.plurals.bulk_summary, p.chosen.size, p.chosen.size, formatBytes(p.totalBytes)),
                            style = MaterialTheme.typography.titleLarge.tabular(),
                            color = scheme.onSurface
                        )
                        // How much of the free space this would take.
                        p.freeBytes?.takeIf { it > 0L }?.let { free ->
                            MeterBar(
                                fraction = (p.totalBytes.toFloat() / free.toFloat()).coerceIn(0f, 1f),
                                color = if (p.fits) scheme.primary else scheme.error
                            )
                        }
                        p.freeBytes?.let { Text(stringResource(R.string.bulk_free, formatBytes(it)), style = MaterialTheme.typography.bodySmall.tabular(), color = scheme.onSurfaceVariant) }
                    }
                    val skipped = buildList {
                        if (p.skippedOwned > 0) add(pluralStringResource(R.plurals.bulk_skipped_owned, p.skippedOwned, p.skippedOwned))
                        if (p.skippedActive > 0) add(pluralStringResource(R.plurals.bulk_skipped_active, p.skippedActive, p.skippedActive))
                        if (p.skippedVersions > 0) add(pluralStringResource(R.plurals.bulk_skipped_versions, p.skippedVersions, p.skippedVersions))
                    }
                    if (skipped.isNotEmpty()) Text(skipped.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    // Where the space goes, per console (when more than one), each in its console's colour.
                    if (p.perConsole.size > 1) FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        p.perConsole.take(6).forEach { (c, n, b) ->
                            Pill("${ConsoleFormatter.getConsoleShortName(c)} $n · ${formatBytes(b)}", tone = PillTone.Tint(consoleColor(c)))
                        }
                    }
                    if (!p.fits) {
                        Text(stringResource(R.string.bulk_no_room_short, formatBytes(p.shortBytes)), style = MaterialTheme.typography.bodyMedium, color = scheme.error)
                        var freeable by remember(p) { mutableStateOf<Long?>(-1L) }
                        LaunchedEffect(p) { freeable = reclaimable() }
                        when (val f = freeable) {
                            -1L -> Text(stringResource(R.string.bulk_checking_duplicates), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            null, 0L -> Unit
                            else -> {
                                Text(stringResource(R.string.bulk_duplicates_free, formatBytes(f)), style = MaterialTheme.typography.bodySmall)
                                DialogButton(stringResource(R.string.bulk_free_up), onClick = onFreeUp)
                            }
                        }
                    }
                    if (p.chosen.size >= BulkPlanner.MAX_FILES) Text(stringResource(R.string.bulk_capped, BulkPlanner.MAX_FILES), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            val p = current
            DialogButton(
                text = stringResource(R.string.bulk_start, p?.chosen?.size ?: 0),
                onClick = { if (p != null) onConfirm(p) },
                enabled = p != null && p.chosen.isNotEmpty() && p.fits
            )
        },
        dismissButton = { DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss, initialFocus = cancelFocus) }
    )
}

/** Name for the current filters, saved as a view. */
@Composable
fun SaveViewDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    val cancelFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_star), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(R.string.view_save)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.view_save_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, singleLine = true)
            }
        },
        confirmButton = { DialogButton(stringResource(R.string.dialog_save), onClick = { onSave(name) }, enabled = name.isNotBlank()) },
        dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), onClick = onDismiss, initialFocus = cancelFocus) }
    )
}
