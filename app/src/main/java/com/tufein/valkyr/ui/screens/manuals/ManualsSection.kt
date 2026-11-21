package com.tufein.valkyr.ui.screens.manuals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tufein.valkyr.R
import com.tufein.valkyr.data.service.ManualResult
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.IconTile
import com.tufein.valkyr.ui.components.Panel
import com.tufein.valkyr.ui.components.SectionTitle
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.util.ManualHit
import com.tufein.valkyr.util.ManualSource
import com.tufein.valkyr.util.Manuals

/**
 * The manuals of a game: the ones kept on the device (read in the in-app reader), what RomM,
 * ScreenScraper and Internet Archive offer, and a link the user can paste. Draws nothing when
 * either there is a manual or a source was asked and had nothing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ManualsSection(consoleId: String, fileName: String, title: String = "", modifier: Modifier = Modifier) {
    val vm: ManualsViewModel = hiltViewModel(key = "man60|$consoleId|$fileName")
    LaunchedEffect(consoleId, fileName, title) { vm.load(consoleId, fileName, title) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var reading by remember { mutableStateOf<Manuals.Doc?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showLink by remember { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }
    if (!ui.loaded || (ui.docs.isEmpty() && ui.hits.isEmpty() && ui.sources.isEmpty() && ui.result == null)) return

    reading?.let { doc -> ManualReaderDialog(consoleId, fileName, doc, vm) { reading = null } }
    if (confirmDelete) {
        val focus = rememberInitialFocus()
        AlertDialog(
            modifier = Modifier.closeOnGamepadB { confirmDelete = false },
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.man60_delete_title)) },
            text = { Text(stringResource(R.string.man60_delete_body)) },
            confirmButton = { DialogButton(stringResource(R.string.pad_delete), { confirmDelete = false; vm.deleteAll(consoleId, fileName, title) }, destructive = true) },
            dismissButton = { DialogButton(stringResource(R.string.pad_cancel), { confirmDelete = false }, initialFocus = focus) }
        )
    }
    if (showSources) SourcePicker(ui.hits, vm, consoleId, fileName, title) { showSources = false }
    if (showLink) LinkDialog(vm, consoleId, fileName, title) { showLink = false }

    Panel(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        IconTile(R.drawable.ic_description, size = 30.dp)
        SectionTitle(stringResource(R.string.man60_title))
        if (ui.docs.isNotEmpty()) {
            Text(stringResource(R.string.man60_on_device), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ui.docs.forEach { doc ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        ActionPill(
                            label = if (doc.kind == Manuals.Kind.IMAGE) stringResource(R.string.man60_read_images, doc.files.size) else stringResource(R.string.man60_read, doc.title),
                            onClick = { reading = doc }, icon = R.drawable.ic_description, tone = ActionTone.Accent
                        )
                        ui.attribution[doc.files.first()]?.let { source ->
                            Text(stringResource(R.string.man77_source, sourceName(source)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                ActionPill(stringResource(R.string.man60_delete), { confirmDelete = true }, icon = R.drawable.ic_trash)
            }
        } else {
            val hit = ui.hits.firstOrNull()
            if (hit != null) {
                Text(stringResource(R.string.man60_available), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ActionPill(
                    label = stringResource(R.string.man77_download, sourceName(hit.source)),
                    onClick = { vm.download(consoleId, fileName, title, hit) }, icon = R.drawable.ic_download, enabled = !ui.busy, tone = ActionTone.Accent
                )
                if (ui.hits.size > 1) ActionPill(stringResource(R.string.man77_other_source), { showSources = true }, icon = R.drawable.ic_swap_horiz)
            } else if (ui.sources.isNotEmpty()) {
                val labels = ui.sources.map { sourceName(it) }
                Text(
                    stringResource(R.string.man77_none_asked, labels.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ActionPill(stringResource(R.string.man77_link), { showLink = true }, icon = R.drawable.ic_link)
        }
        ui.result?.let { r ->
            Text(
                stringResource(resultText(r)),
                style = MaterialTheme.typography.bodySmall,
                color = if (r == ManualResult.SAVED || r == ManualResult.EXISTS) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun sourceName(source: ManualSource): String = stringResource(when (source) {
    ManualSource.ROMM -> R.string.man77_romm
    ManualSource.SCREENSCRAPER -> R.string.man77_screenscraper
    ManualSource.ARCHIVE -> R.string.man77_archive
    ManualSource.LINK -> R.string.man77_link_source
})

@Composable
private fun SourcePicker(hits: List<ManualHit>, vm: ManualsViewModel, consoleId: String, fileName: String, title: String, onClose: () -> Unit) {
    val focus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onClose),
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.man77_other_source)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                hits.forEach { hit ->
                    ActionPill(
                        label = listOfNotNull(sourceName(hit.source), hit.label).joinToString(" — "),
                        onClick = { onClose(); vm.download(consoleId, fileName, title, hit) },
                        icon = R.drawable.ic_download
                    )
                }
            }
        },
        confirmButton = { DialogButton(stringResource(R.string.pad_cancel), onClose, initialFocus = focus) }
    )
}

@Composable
private fun LinkDialog(vm: ManualsViewModel, consoleId: String, fileName: String, title: String, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onClose),
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.man77_link_title)) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                label = { Text(stringResource(R.string.man77_link_hint)) }
            )
        },
        confirmButton = {
            DialogButton(stringResource(R.string.man60_download), {
                onClose()
                vm.downloadLink(consoleId, fileName, title, text)
            })
        },
        dismissButton = { DialogButton(stringResource(R.string.pad_cancel), onClose, initialFocus = focus) }
    )
}

private fun resultText(r: ManualResult): Int = when (r) {
    ManualResult.SAVED -> R.string.man60_result_saved
    ManualResult.EXISTS -> R.string.man60_result_exists
    ManualResult.NO_MANUAL -> R.string.man60_result_none
    ManualResult.TOO_BIG -> R.string.man60_result_too_big
    ManualResult.OFFLINE -> R.string.man60_result_offline
    ManualResult.FAILED -> R.string.man60_result_failed
    ManualResult.BAD_LINK -> R.string.man77_link_bad
}
