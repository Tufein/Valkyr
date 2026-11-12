package com.tufein.valkyr.ui.screens.sources.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tufein.valkyr.R
import com.tufein.valkyr.data.model.ContentType
import com.tufein.valkyr.data.model.UrlEntry
import com.tufein.valkyr.data.service.RommPlatform
import com.tufein.valkyr.util.RommSource
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.SectionTitle
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.ui.screens.download.ActionButton

/**
 * Adds a source to a console or, when [existing] is given, edits one in place.
 * Opens with the URL field focused; B cancels.
 */
@Composable
fun AddUrlDialog(
    existing: UrlEntry? = null,
    /** Platforms of the RomM server from Settings; one chip each sets the URL to `romm://<slug>`. */
    rommPlatforms: List<RommPlatform> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (String, ContentType, List<String>) -> Unit
) {
    var url by remember { mutableStateOf(existing?.url.orEmpty()) }
    // Reserve addresses, one per line (web directories only).
    var mirrors by remember { mutableStateOf(existing?.mirrors.orEmpty().joinToString("\n")) }
    var contentType by remember { mutableStateOf(existing?.contentType ?: ContentType.GAME) }
    val urlFocus = rememberInitialFocus()
    val editing = existing != null

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri -> uri?.let { url = it.toString() } }
    )

    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (editing) R.string.dialog_edit_source else R.string.dialog_add_source)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.dialog_add_source_hint))
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(stringResource(R.string.dialog_url_label)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f).focusRequester(urlFocus),
                        placeholder = { Text(stringResource(R.string.dialog_url_placeholder)) }
                    )

                    ActionButton(
                        R.drawable.ic_folder,
                        stringResource(R.string.dialog_upload_torrent),
                        48.dp,
                        MaterialTheme.colorScheme.onSurface,
                        onClick = { filePicker.launch("application/x-bittorrent") }
                    )
                }

                if (rommPlatforms.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    SectionTitle(stringResource(R.string.dialog_from_romm), icon = R.drawable.ic_server)
                    Spacer(modifier = Modifier.height(6.dp))
                    RommPlatformChips(platforms = rommPlatforms, selected = RommSource.slugOf(url)) { url = RommSource.sourceFor(it.slug) }
                }

                if (url.trim().startsWith("http", ignoreCase = true)) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = mirrors,
                        onValueChange = { mirrors = it },
                        label = { Text(stringResource(R.string.dialog_mirrors_label)) },
                        supportingText = { Text(stringResource(R.string.dialog_mirrors_hint)) },
                        minLines = 1,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                SectionTitle(stringResource(R.string.dialog_content_type), icon = R.drawable.ic_description)
                Spacer(modifier = Modifier.height(8.dp))
                ContentTypeSelection(selectedType = contentType, onTypeSelected = { contentType = it })
            }
        },
        confirmButton = {
            DialogButton(
                text = stringResource(if (editing) R.string.dialog_save else R.string.dialog_add),
                onClick = {
                    onConfirm(url.trim(), contentType, mirrors.lines().map { it.trim() }.filter { it.isNotEmpty() })
                    onDismiss()
                },
                enabled = url.isNotBlank()
            )
        },
        dismissButton = {
            DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss)
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RommPlatformChips(platforms: List<RommPlatform>, selected: String?, onPick: (RommPlatform) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        platforms.forEach { platform ->
            ChoiceChip(
                label = platform.label,
                selected = selected != null && (platform.slug.equals(selected, true) || platform.fsSlug.equals(selected, true)),
                onClick = { onPick(platform) }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContentTypeSelection(
    selectedType: ContentType,
    onTypeSelected: (ContentType) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ContentType.entries.forEach { type ->
            ChoiceChip(
                label = stringResource(
                    when (type) {
                        ContentType.GAME -> R.string.q5_type_game
                        ContentType.MISCELLANEOUS -> R.string.q5_type_misc
                        ContentType.RETROACHIEVEMENTS -> R.string.q5_type_ra
                    }
                ),
                selected = selectedType == type,
                onClick = { onTypeSelected(type) }
            )
        }
    }
}
