package com.tufein.valkyr.ui.screens.sources.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.tufein.valkyr.R
import com.tufein.valkyr.data.service.FolderMergeService
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.IconTile
import com.tufein.valkyr.ui.components.PrimaryButton
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.rememberFocusSource
import com.tufein.valkyr.ui.components.rememberInitialFocus

/**
 * Offered when several folders in the download directory match the same console.
 * The user picks the folder to keep (the rest are merged into it) or leaves everything as is.
 */
@Composable
fun MergeFoldersDialog(
    consoleName: String,
    folders: List<String>,
    inProgress: Boolean,
    result: FolderMergeService.Result?,
    onMerge: (String) -> Unit,
    onKeep: () -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember(folders) { mutableStateOf(folders.firstOrNull()) }
    val primaryFocus = rememberInitialFocus()

    AlertDialog(
        modifier = Modifier.closeOnGamepadB { if (!inProgress) onDismiss() },
        onDismissRequest = { if (!inProgress) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile(R.drawable.ic_merge, size = 36.dp)
                Text(stringResource(R.string.merge_folders_title))
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    result != null -> Text(
                        stringResource(R.string.merge_folders_result, result.moved, result.duplicates, result.skipped, result.failed, result.removedFolders),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    inProgress -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.merge_folders_working), style = MaterialTheme.typography.bodyMedium)
                    }
                    else -> {
                        Text(
                            stringResource(R.string.merge_folders_hint, consoleName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        folders.forEach { folder ->
                            FolderRow(folder, selected == folder) { selected = folder }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                result != null -> PrimaryButton(
                    stringResource(R.string.merge_folders_done),
                    onDismiss,
                    modifier = Modifier.focusRequester(primaryFocus),
                    icon = R.drawable.ic_check
                )
                else -> PrimaryButton(
                    stringResource(R.string.merge_folders_confirm),
                    { selected?.let(onMerge) },
                    modifier = Modifier.focusRequester(primaryFocus),
                    icon = R.drawable.ic_merge,
                    enabled = !inProgress && selected != null
                )
            }
        },
        dismissButton = {
            if (result == null) {
                ActionPill(stringResource(R.string.merge_folders_keep), onKeep, enabled = !inProgress)
            }
        }
    )
}

@Composable
private fun FolderRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh)
            .focusRing(source, 10.dp)
            .clickable(interactionSource = source, indication = null, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            painterResource(if (selected) R.drawable.ic_radio_on else R.drawable.ic_radio_off),
            contentDescription = null,
            tint = if (selected) scheme.primary else scheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Text(
            label,
            style = if (selected) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
            color = if (selected) scheme.onPrimaryContainer else scheme.onSurface
        )
    }
}
