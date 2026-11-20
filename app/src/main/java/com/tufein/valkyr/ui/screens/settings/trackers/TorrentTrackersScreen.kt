package com.tufein.valkyr.ui.screens.settings.trackers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tufein.valkyr.R
import com.tufein.valkyr.data.service.TorrentTrackerCheck
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.LegendEntry
import com.tufein.valkyr.ui.components.NavChevron
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.requestFocusWhenReady
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.ui.screens.settings.SettingRow
import com.tufein.valkyr.ui.screens.settings.SettingsSurface
import com.tufein.valkyr.ui.screens.settings.ThemedSwitch
import com.tufein.valkyr.ui.screens.tools.PublishLegend
import com.tufein.valkyr.ui.screens.tools.ToolRow
import com.tufein.valkyr.ui.screens.tools.ToolsTitle
import com.tufein.valkyr.util.TorrentTrackers

/** Settings → Torrent trackers: a small personal announce list, added to every torrent Valkyr fetches. */
@Composable
fun TorrentTrackersScreen(viewModel: TorrentTrackersViewModel = hiltViewModel()) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val testing by viewModel.testing.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()

    var adding by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    val confirmFocus = rememberInitialFocus()
    val firstRow = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstRow.requestFocusWhenReady() }
    PublishLegend(listOf(LegendEntry("A", stringResource(R.string.pad_change)), LegendEntry("B", stringResource(R.string.pad_back))))

    SettingsSurface {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            item(key = "title") {
                ToolsTitle(
                    stringResource(R.string.src91_trackers_title),
                    icon = R.drawable.ic_hub,
                    subtitle = stringResource(R.string.src91_trackers_hint)
                )
            }
            item(key = "add") {
                SettingRow(
                    icon = R.drawable.ic_add,
                    title = stringResource(R.string.src91_add),
                    hint = stringResource(R.string.src91_add_hint),
                    onClick = { invalid = false; adding = true },
                    modifier = Modifier.focusRequester(firstRow)
                ) { NavChevron() }
            }
            items(entries, key = { it.url }) { entry ->
                val result = results[entry.url]
                SettingRow(
                    icon = R.drawable.ic_hub,
                    title = entry.url,
                    hint = if (entry.enabled) stringResource(R.string.src91_on) else stringResource(R.string.src91_off),
                    onClick = { viewModel.setEnabled(entry.url, !entry.enabled) },
                    below = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionPill(
                                label = if (testing == entry.url) stringResource(R.string.src91_testing)
                                        else stringResource(R.string.src91_test),
                                onClick = { viewModel.test(entry.url) },
                                enabled = testing != entry.url
                            )
                            ActionPill(
                                label = stringResource(R.string.src91_remove),
                                onClick = { viewModel.remove(entry.url) }
                            )
                        }
                        result?.let { Text(resultText(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                ) { ThemedSwitch(entry.enabled) { viewModel.setEnabled(entry.url, it) } }
            }
            if (entries.isEmpty()) {
                item(key = "empty") {
                    ToolRow(
                        title = stringResource(R.string.src91_no_trackers),
                        lines = listOf(stringResource(R.string.src91_no_trackers_msg)),
                        onClick = { adding = true },
                        icon = R.drawable.ic_info
                    )
                }
            }
        }
    }

    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.src91_add)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it; invalid = false },
                        singleLine = true,
                        isError = invalid,
                        label = { Text(stringResource(R.string.src91_add_hint)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (invalid) Text(stringResource(R.string.src91_invalid), color = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.src91_test_udp), style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                DialogButton(stringResource(R.string.src91_add), initialFocus = confirmFocus, onClick = {
                    val entry = TorrentTrackers.validate(typed)
                    if (entry == null) invalid = true
                    else {
                        viewModel.add(entry.url)
                        typed = ""
                        adding = false
                    }
                })
            },
            dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), onClick = { adding = false }) },
            modifier = Modifier.closeOnGamepadB { adding = false }
        )
    }
}

@Composable
private fun resultText(result: TorrentTrackerCheck.Result): String = when (result) {
    is TorrentTrackerCheck.Result.Http -> if (result.code in 200..399)
        stringResource(R.string.src91_test_http_ok, result.code)
    else stringResource(R.string.src91_test_http_fail, result.code.toString())
    is TorrentTrackerCheck.Result.Failed -> stringResource(R.string.src91_test_http_fail, result.kind)
    TorrentTrackerCheck.Result.NotTestable -> stringResource(R.string.src91_test_udp)
}
