package com.tufein.valkyr.ui.screens.manuals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.tufein.valkyr.util.Manuals

/**
 * The manuals of a game (6.0): the ones kept on the device (read in the in-app reader), and a download
 * button when the user's RomM has one. Draws nothing when there is neither.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ManualsSection(consoleId: String, fileName: String, modifier: Modifier = Modifier) {
    val vm: ManualsViewModel = hiltViewModel(key = "man60|$consoleId|$fileName")
    LaunchedEffect(consoleId, fileName) { vm.load(consoleId, fileName) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var reading by remember { mutableStateOf<Manuals.Doc?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (!ui.loaded || (ui.docs.isEmpty() && ui.remote == null && ui.result == null)) return

    reading?.let { doc -> ManualReaderDialog(consoleId, fileName, doc, vm) { reading = null } }
    if (confirmDelete) {
        val focus = rememberInitialFocus()
        AlertDialog(
            modifier = Modifier.closeOnGamepadB { confirmDelete = false },
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.man60_delete_title)) },
            text = { Text(stringResource(R.string.man60_delete_body)) },
            confirmButton = { DialogButton(stringResource(R.string.pad_delete), { confirmDelete = false; vm.deleteAll(consoleId, fileName) }, destructive = true) },
            dismissButton = { DialogButton(stringResource(R.string.pad_cancel), { confirmDelete = false }, initialFocus = focus) }
        )
    }

    Panel(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconTile(R.drawable.ic_description, size = 30.dp)
            SectionTitle(stringResource(R.string.man60_title))
        }
        if (ui.docs.isNotEmpty()) {
            Text(stringResource(R.string.man60_on_device), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ui.docs.forEach { doc ->
                    ActionPill(
                        label = if (doc.kind == Manuals.Kind.IMAGE) stringResource(R.string.man60_read_images, doc.files.size) else stringResource(R.string.man60_read, doc.title),
                        onClick = { reading = doc }, icon = R.drawable.ic_description, tone = ActionTone.Accent
                    )
                }
                ActionPill(stringResource(R.string.man60_delete), { confirmDelete = true }, icon = R.drawable.ic_trash)
            }
        } else if (ui.remote != null) {
            Text(stringResource(R.string.man60_available), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ActionPill(
                label = stringResource(if (ui.busy) R.string.man60_downloading else R.string.man60_download),
                onClick = { vm.download(consoleId, fileName) }, icon = R.drawable.ic_download, enabled = !ui.busy, modifier = Modifier.padding(top = 2.dp)
            )
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

private fun resultText(r: ManualResult): Int = when (r) {
    ManualResult.SAVED -> R.string.man60_result_saved
    ManualResult.EXISTS -> R.string.man60_result_exists
    ManualResult.NO_MANUAL -> R.string.man60_result_none
    ManualResult.TOO_BIG -> R.string.man60_result_too_big
    ManualResult.OFFLINE -> R.string.man60_result_offline
    ManualResult.FAILED -> R.string.man60_result_failed
}
