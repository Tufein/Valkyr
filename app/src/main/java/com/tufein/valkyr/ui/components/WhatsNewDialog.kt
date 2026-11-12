package com.tufein.valkyr.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tufein.valkyr.R

/** Once after an update: the highlights of this version (see [com.tufein.valkyr.util.WhatsNew]). */
@Composable
fun WhatsNewDialog(version: String, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.whats_new_title, version)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                stringArrayResource(R.array.whats_new_items).forEach { line ->
                    Text("• $line", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { DialogButton(stringResource(R.string.dialog_ok), onClick = onDismiss, initialFocus = rememberInitialFocus()) }
    )
}
