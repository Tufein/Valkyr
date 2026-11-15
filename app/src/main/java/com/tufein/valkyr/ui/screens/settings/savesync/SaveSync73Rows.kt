package com.tufein.valkyr.ui.screens.settings.savesync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tufein.valkyr.R
import com.tufein.valkyr.data.service.SaveSyncService
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.rememberFocusSource
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.ui.screens.settings.settingsInset
import com.tufein.valkyr.util.BatchChoice
import com.tufein.valkyr.util.SaveConflict
import com.tufein.valkyr.util.SaveConflictInfo

/**
 * 7.3: one choice for every open conflict, above the per-file list. It says how many saves differ
 * and which side is newer for how many, and each button settles the whole list at once with the
 * same safety as a single choice (the copy that is replaced is kept first).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConflictBatchRow(conflicts: List<SaveConflict>, onChoice: (BatchChoice) -> Unit) {
    val summary = remember(conflicts) { SaveConflictInfo.summary(conflicts) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = settingsInset(), vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.saves73_batch_title, conflicts.size), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.saves73_batch_summary, summary.deviceNewer, summary.serverNewer, summary.unclear),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (summary.deviceNewer + summary.serverNewer > 0) {
                ActionPill(stringResource(R.string.saves73_batch_newer), { onChoice(BatchChoice.NEWER) }, icon = R.drawable.ic_history, tone = ActionTone.Accent)
            }
            ActionPill(stringResource(R.string.saves73_batch_device), { onChoice(BatchChoice.DEVICE) }, icon = R.drawable.ic_controller)
            ActionPill(stringResource(R.string.saves73_batch_server), { onChoice(BatchChoice.SERVER) }, icon = R.drawable.ic_server)
            ActionPill(stringResource(R.string.saves73_batch_both), { onChoice(BatchChoice.KEEP_BOTH) }, icon = R.drawable.ic_content_copy)
        }
        Text(stringResource(R.string.saves73_batch_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 7.3: the emulator folders found below a picked folder. Every found folder is ticked; the user
 * unticks what should stay out and adds the rest. An empty result says why (Android keeps
 * `Android/data` closed to other apps) and points back to picking by hand.
 */
@Composable
fun FoundFoldersDialog(found: List<SaveSyncService.FoundFolder>, onAdd: (List<SaveSyncService.FoundFolder>) -> Unit, onDismiss: () -> Unit) {
    var off by remember(found) { mutableStateOf(emptySet<String>()) }
    val focus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.saves73_find_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())
            ) {
                if (found.isEmpty()) {
                    Text(stringResource(R.string.saves73_find_none), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(stringResource(R.string.saves73_find_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    found.forEach { folder ->
                        val on = folder.uri !in off
                        val source = rememberFocusSource()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .focusRing(source)
                                .clickable(interactionSource = source, indication = null) { off = if (on) off + folder.uri else off - folder.uri }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                painterResource(if (on) R.drawable.ic_checkbox_on else R.drawable.ic_checkbox_off),
                                contentDescription = stringResource(if (on) R.string.saves73_find_on else R.string.saves73_find_off),
                                modifier = Modifier.size(24.dp),
                                tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Column(Modifier.weight(1f)) {
                                Text(folder.preset.label, style = MaterialTheme.typography.bodyLarge)
                                Text(folder.path.ifEmpty { "/" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val picked = found.filter { it.uri !in off }
            if (found.isEmpty()) DialogButton(text = stringResource(R.string.dialog_close), onClick = onDismiss, initialFocus = focus)
            else DialogButton(
                text = stringResource(R.string.saves73_find_add, picked.size),
                onClick = { onAdd(picked) },
                enabled = picked.isNotEmpty(),
                initialFocus = focus
            )
        },
        dismissButton = { if (found.isNotEmpty()) DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss) }
    )
}
