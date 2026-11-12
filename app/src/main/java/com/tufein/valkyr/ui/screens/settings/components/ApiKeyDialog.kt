package com.tufein.valkyr.ui.screens.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.rememberInitialFocus

/**
 * Edits a secret (TorBox key, RomM token…). Opens with the field focused, B cancels;
 * [onTest] tries the value against the service without saving it.
 */
@Composable
fun ApiKeyDialog(
    title: String,
    hint: String,
    value: String,
    label: String = stringResource(R.string.api_key_label),
    /** The mark at the start of the field (a key for secrets, a link for addresses). */
    icon: Int = R.drawable.ic_key,
    onTest: ((String) -> Unit)? = null,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(value) }
    val fieldFocus = rememberInitialFocus()

    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(label) },
                    leadingIcon = { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus)
                )
            }
        },
        confirmButton = {
            DialogButton(text = stringResource(R.string.dialog_save), onClick = { onSave(text.trim()); onDismiss() })
        },
        dismissButton = {
            if (onTest != null) DialogButton(text = stringResource(R.string.settings_test), enabled = text.isNotBlank(), onClick = { onTest(text.trim()) })
            DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss)
        }
    )
}

/** `abcd…wxyz` for the settings hint, or "Not set". */
@Composable
fun maskedSecret(value: String): String =
    if (value.isBlank()) stringResource(R.string.settings_not_set)
    else if (value.length <= 8) "••••" else value.take(4) + "••••" + value.takeLast(4)
