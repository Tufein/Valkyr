package com.tufein.valkyr.ui.screens.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.SmartCollectionRule

@Composable
internal fun SmartRuleDialog(initialName: String, initial: SmartCollectionRule?, consoles: List<String>,
    onSave: (String, SmartCollectionRule?) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    var selected by remember { mutableStateOf(initial?.consoles.orEmpty()) }
    var languages by remember { mutableStateOf(initial?.languages?.sorted()?.joinToString(", ").orEmpty()) }
    var genre by remember { mutableStateOf(initial?.genre.orEmpty()) }
    var from by remember { mutableStateOf(initial?.fromYear?.toString().orEmpty()) }
    var to by remember { mutableStateOf(initial?.toYear?.toString().orEmpty()) }
    var played by remember { mutableStateOf(initial?.played ?: "ANY") }
    val rule = SmartCollectionRule(selected, languages.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        genre.trim(), from.toIntOrNull(), to.toIntOrNull(), played)
    val valid = name.isNotBlank() && rule.valid && (from.isBlank() || from.toIntOrNull() != null) &&
        (to.isBlank() || to.toIntOrNull() != null)
    val cancelFocus = rememberInitialFocus()
    AlertDialog(modifier = Modifier.closeOnGamepadB(onDismiss), onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.smart25_rules)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (initialName.isEmpty()) OutlinedTextField(name, { name = it.take(60) }, label = { Text(stringResource(R.string.smart25_name)) }, singleLine = true)
            Text(stringResource(R.string.smart25_hint))
            Text(stringResource(R.string.smart25_consoles), style = MaterialTheme.typography.titleSmall)
            (consoles + selected).distinct().forEach { console ->
                ToolRow(ConsoleFormatter.getConsoleDisplayName(console), emptyList(),
                    onClick = { selected = if (console in selected) selected - console else selected + console },
                    icon = if (console in selected) R.drawable.ic_check else R.drawable.ic_collections)
            }
            OutlinedTextField(languages, { languages = it.take(120) }, label = { Text(stringResource(R.string.smart25_languages)) }, singleLine = true)
            OutlinedTextField(genre, { genre = it.take(80) }, label = { Text(stringResource(R.string.smart25_genre)) }, singleLine = true)
            OutlinedTextField(from, { from = it.take(4) }, label = { Text(stringResource(R.string.smart25_from)) }, singleLine = true)
            OutlinedTextField(to, { to = it.take(4) }, label = { Text(stringResource(R.string.smart25_to)) }, singleLine = true)
            ToolsActions(horizontalPadding = 0.dp) {
                listOf("ANY" to R.string.smart25_any, "PLAYED" to R.string.smart25_played, "NEVER" to R.string.smart25_never,
                    "STALE_90" to R.string.smart65_stale_90, "STALE_180" to R.string.smart65_stale_180, "STALE_365" to R.string.smart65_stale_365).forEach { (value, label) ->
                    ToolAction(stringResource(label), icon = if (played == value) R.drawable.ic_check else null) { played = value }
                }
            }
            if (!valid) Text(stringResource(R.string.smart25_invalid), color = MaterialTheme.colorScheme.error)
            if (initial != null) ToolAction(stringResource(R.string.smart25_manual)) { onSave(initialName, null) }
        } },
        confirmButton = { DialogButton(stringResource(R.string.dialog_save), { onSave(if (initialName.isEmpty()) name else initialName, rule) }, enabled = valid) },
        dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), onDismiss, initialFocus = cancelFocus) })
}
