package com.tufein.valkyr.ui.screens.sources

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tufein.valkyr.R
import com.tufein.valkyr.data.state.SourceScanResults
import com.tufein.valkyr.ui.screens.sources.components.QrShowDialog
import com.tufein.valkyr.ui.screens.sources.components.ShareSourcesDialog
import com.tufein.valkyr.util.FileParsingUtils
import com.tufein.valkyr.ui.screens.sources.components.AddConsoleDialog
import com.tufein.valkyr.ui.screens.sources.components.AddUrlDialog
import com.tufein.valkyr.ui.screens.sources.components.ConfirmDialog
import com.tufein.valkyr.ui.screens.sources.components.ConsoleCard
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.ui.screens.sources.SourcesViewModel.Dialog as SourcesDialog
import com.tufein.valkyr.ui.screens.sources.components.MergeFoldersDialog
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.EmptyState
import com.tufein.valkyr.ui.components.IconTile
import com.tufein.valkyr.ui.components.Panel
import com.tufein.valkyr.ui.screens.settings.SettingRow
import com.tufein.valkyr.ui.screens.settings.ThemedSwitch
import com.tufein.valkyr.ui.components.PanelTone
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.ScreenTitle
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.rememberFocusSource
import java.io.File

@Composable
fun SourcesScreen(
    viewModel: SourcesViewModel = hiltViewModel(),
    onOpenTracker: () -> Unit = {}
) {
    val manufacturers by viewModel.manufacturers.collectAsStateWithLifecycle(initialValue = emptyList())
    val isRescanning by viewModel.isRescanning.collectAsStateWithLifecycle()
    val sourceResults by viewModel.sourceResults.collectAsStateWithLifecycle()
    val sourceTrack by viewModel.sourceTrack.collectAsStateWithLifecycle()
    val consoleDownloadPaths by viewModel.consoleDownloadPaths.collectAsStateWithLifecycle()
    val downloadDirectory by viewModel.downloadDirectory.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val directoryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri ->
            uri?.let {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                viewModel.finishPickingDownloadPath(uri.toString())
            }
        }
    )

    val rootDirectoryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri ->
            uri?.let {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                viewModel.updateDownloadDirectory(it.toString())
            }
        }
    )

    val importPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let { viewModel.importSources(it.toString()) } }
    )

    var showShare by remember { mutableStateOf(false) }
    var showRomsets by remember { mutableStateOf(false) }
    if (showRomsets) RomsetCatalogDialog(onDismiss = { showRomsets = false })
    val qrParts by viewModel.qrParts.collectAsStateWithLifecycle()
    val qrImport by viewModel.qrImport.collectAsStateWithLifecycle()
    val qrPictures = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> viewModel.readQr(uris) }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    val qrCamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> photoUri?.takeIf { ok }?.let { viewModel.readQr(listOf(it)) } }
    fun takeQrPhoto() {
        val file = File(File(context.cacheDir, "qr").apply { mkdirs() }, "qr-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        photoUri = uri
        runCatching { qrCamera.launch(uri) }.onFailure { qrPictures.launch("image/*") }
    }
    qrParts?.let { QrShowDialog(it, onDismiss = viewModel::hideQr) }
    qrImport?.let {
        ConfirmDialog(
            title = stringResource(R.string.qr_import_title),
            message = stringResource(R.string.qr_import_message),
            confirmText = stringResource(R.string.sources_import),
            onConfirm = viewModel::confirmQrImport,
            onDismiss = viewModel::dismissQrImport,
            icon = R.drawable.ic_qr_code
        )
    }

    fun shareExport(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.sources_export_share_title)))
    }

    // Every console is one card; the counts under the title come from the same list.
    val consoleCount = remember(manufacturers) { manufacturers.sumOf { it.consoles.size } }
    val sourceCount = remember(manufacturers) { manufacturers.sumOf { m -> m.consoles.sumOf { it.urls.size } } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // Padding inside the list: cards scroll edge to edge instead of being clipped 16dp early.
        // Cards sit 20dp from the edge, the same x as the app title and the Settings rows' text.
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SourcesHeader(
                isRescanning = isRescanning,
                consoleCount = consoleCount,
                sourceCount = sourceCount,
                onAddConsole = { viewModel.showAddConsoleDialog() },
                onRescan = { viewModel.rescanAllSources() },
                onExport = { showShare = true },
                onImport = { viewModel.confirmImport() },
                onOpenTracker = onOpenTracker
            )
        }

        item {
            DownloadDirectoryCard(
                downloadDirectory = downloadDirectory,
                onChange = { rootDirectoryPicker.launch(null) }
            )
        }

        item {
            val rommOn by viewModel.rommEnabled.collectAsStateWithLifecycle()
            Panel(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(4.dp)) {
                SettingRow(
                    icon = R.drawable.ic_server,
                    title = stringResource(R.string.fix54_romm_use),
                    hint = stringResource(if (rommOn) R.string.fix54_romm_sources_on_hint else R.string.fix54_romm_sources_off_hint),
                    onClick = { viewModel.setRommEnabled(!rommOn) },
                    onAdjust = { viewModel.setRommEnabled(it > 0) },
                ) { ThemedSwitch(rommOn) { viewModel.setRommEnabled(it) } }
            }
        }

        item {
            Panel(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.romset28_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.romset28_short_hint), style = MaterialTheme.typography.bodySmall)
                ActionPill(stringResource(R.string.romset28_browse), { showRomsets = true }, icon = R.drawable.ic_globe)
            }
        }

        if (manufacturers.isEmpty()) {
            item {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(
                        title = stringResource(R.string.sources_empty_title),
                        message = stringResource(R.string.sources_empty_hint),
                        icon = R.drawable.ic_globe,
                        actionLabel = stringResource(R.string.sources_add_console),
                        onAction = { viewModel.showAddConsoleDialog() }
                    )
                    ActionPill(
                        stringResource(R.string.sources_import),
                        onClick = { viewModel.confirmImport() },
                        icon = R.drawable.ic_import
                    )
                }
            }
        } else {
            // Flat, alphabetical console list; the manufacturer is only a subtitle on each card.
            val consoles = manufacturers
                .flatMap { m -> m.consoles.map { m to it } }
                .sortedBy { (_, c) -> c.name.lowercase() }
            items(consoles, key = { (_, c) -> c.id }) { (manufacturer, console) ->
                ConsoleCard(
                    console = console,
                    subtitle = manufacturer.name,
                    downloadPath = consoleDownloadPaths[console.id],
                    onAddUrl = { viewModel.showAddUrlDialog(console.id) },
                    onEditConsole = { viewModel.showEditConsoleDialog(console) },
                    onDeleteConsole = { viewModel.confirmDeleteConsole(console) },
                    onEditUrl = { index, entry -> viewModel.showEditUrlDialog(console.id, index, entry) },
                    onDeleteUrl = { index, entry -> viewModel.confirmDeleteUrl(console.id, index, entry) },
                    onToggleUrl = { index, enabled -> viewModel.setUrlEnabled(console.id, index, enabled) },
                    onSetCustomDownloadPath = {
                        viewModel.beginPickingDownloadPath(console.id)
                        directoryPicker.launch(null)
                    },
                    onRefreshConsole = { viewModel.refreshConsole(console.id) },
                    onMergeFolders = { viewModel.showMergeDialog(console.id) },
                    resultFor = { sourceResults[SourceScanResults.key(console.id, it.url)] },
                    trackFor = { sourceTrack[it.url] }
                )
            }
        }
    }

    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    if (showShare) {
        ShareSourcesDialog(
            onFile = { showShare = false; viewModel.exportSources(::shareExport) },
            onShowQr = { showShare = false; viewModel.showQr() },
            onReadQrCamera = { showShare = false; takeQrPhoto() },
            onReadQrPictures = { showShare = false; qrPictures.launch("image/*") },
            onDismiss = { showShare = false }
        )
    }

    val rommPlatforms by viewModel.rommPlatforms.collectAsStateWithLifecycle()
    val importMessage by viewModel.importMessage.collectAsStateWithLifecycle()

    val mergeConsoleId by viewModel.mergeConsoleId.collectAsStateWithLifecycle()
    val mergeInProgress by viewModel.mergeInProgress.collectAsStateWithLifecycle()
    val mergeResult by viewModel.mergeResult.collectAsStateWithLifecycle()
    mergeConsoleId?.let { consoleId ->
        val path = consoleDownloadPaths[consoleId]
        val consoleName = manufacturers.flatMap { it.consoles }.firstOrNull { it.id == consoleId }?.name ?: consoleId
        if (path != null || mergeResult != null) {
            MergeFoldersDialog(
                consoleName = consoleName,
                folders = path?.let { listOf(it.subPath) + it.alternatives }.orEmpty(),
                inProgress = mergeInProgress,
                result = mergeResult,
                onMerge = { target -> viewModel.mergeFolders(consoleId, target) },
                onKeep = { viewModel.keepFoldersAsIs(consoleId) },
                onDismiss = { viewModel.hideMergeDialog() }
            )
        }
    }

    when (val d = dialog) {
        null -> Unit
        SourcesDialog.AddConsole -> AddConsoleDialog(
            manufacturers = manufacturers,
            onDismiss = { viewModel.dismissDialog() },
            onConfirmWithManufacturer = { manufacturerName, values -> viewModel.addConsoleUnderNewManufacturer(manufacturerName, values.name, values.shortName, values.aliases) },
            onConfirmExisting = { manufacturerId, values -> viewModel.addConsole(manufacturerId, values.name, values.shortName, values.aliases) }
        )
        is SourcesDialog.EditConsole -> AddConsoleDialog(
            existing = d.console,
            onDismiss = { viewModel.dismissDialog() },
            onConfirm = { values -> viewModel.updateConsole(d.console.id, values.name, values.shortName, values.aliases) }
        )
        is SourcesDialog.AddUrl -> AddUrlDialog(
            rommPlatforms = rommPlatforms,
            onDismiss = { viewModel.dismissDialog() },
            onConfirm = { url, contentType, mirrors -> viewModel.addUrl(d.consoleId, url, contentType, mirrors) }
        )
        is SourcesDialog.EditUrl -> AddUrlDialog(
            existing = d.entry,
            rommPlatforms = rommPlatforms,
            onDismiss = { viewModel.dismissDialog() },
            onConfirm = { url, contentType, mirrors -> viewModel.updateUrl(d.consoleId, d.index, url, contentType, mirrors) }
        )
        is SourcesDialog.ConfirmDeleteConsole -> ConfirmDialog(
            title = stringResource(R.string.sources_delete_console_title, d.name),
            message = stringResource(R.string.sources_delete_console_message),
            confirmText = stringResource(R.string.dialog_delete),
            onConfirm = { viewModel.deleteConsole(d.consoleId) },
            onDismiss = { viewModel.dismissDialog() },
            icon = R.drawable.ic_trash,
            destructive = true
        )
        is SourcesDialog.ConfirmDeleteUrl -> ConfirmDialog(
            title = stringResource(R.string.sources_delete_url_title),
            message = stringResource(R.string.sources_delete_url_message, d.url.take(80)),
            confirmText = stringResource(R.string.dialog_delete),
            onConfirm = { viewModel.deleteUrl(d.consoleId, d.index) },
            onDismiss = { viewModel.dismissDialog() },
            icon = R.drawable.ic_trash,
            destructive = true
        )
        SourcesDialog.ConfirmImport -> ConfirmDialog(
            title = stringResource(R.string.sources_import_confirm_title),
            message = stringResource(R.string.sources_import_confirm_message),
            confirmText = stringResource(R.string.sources_import_pick),
            onConfirm = { importPicker.launch(arrayOf("application/json", "application/octet-stream", "text/*")) },
            onDismiss = { viewModel.dismissDialog() },
            icon = R.drawable.ic_import
        )
    }

    importMessage?.let { message ->
        val okFocus = rememberInitialFocus()
        AlertDialog(
            modifier = Modifier.closeOnGamepadB { viewModel.clearImportMessage() },
            onDismissRequest = { viewModel.clearImportMessage() },
            title = { Text(stringResource(R.string.sources_import_result_title)) },
            text = { Text(message) },
            confirmButton = {
                DialogButton(stringResource(R.string.dialog_ok), onClick = { viewModel.clearImportMessage() }, initialFocus = okFocus)
            }
        )
    }
}

/**
 * Root download directory. Per-console folders (see each console's "Download path")
 * are detected inside — or created under — this directory.
 */
@Composable
private fun DownloadDirectoryCard(
    downloadDirectory: String,
    onChange: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    val unset = downloadDirectory.isBlank()
    Panel(modifier = Modifier.fillMaxWidth(), tone = if (unset) PanelTone.Danger else PanelTone.Normal) {
        // The whole row is the one control (one focus stop); the pill at its end only says what it does.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusRing(source, cornerRadius = 12.dp)
                .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onChange)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            IconTile(R.drawable.ic_folder_open)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_download_directory),
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface
                )
                Text(
                    text = if (unset) stringResource(R.string.sources_directory_unset)
                           else FileParsingUtils.toUserReadablePath(downloadDirectory),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (unset) scheme.onErrorContainer else scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Pill(
                stringResource(if (unset) R.string.sources_choose else R.string.settings_change),
                tone = if (unset) PillTone.Strong else PillTone.Accent,
                icon = R.drawable.ic_edit
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourcesHeader(
    isRescanning: Boolean,
    consoleCount: Int,
    sourceCount: Int,
    onAddConsole: () -> Unit,
    onRescan: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onOpenTracker: () -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScreenTitle(
            text = stringResource(R.string.nav_sources),
            icon = R.drawable.ic_globe,
            subtitle = if (consoleCount == 0) null else
                pluralStringResource(R.plurals.q5_consoles_count, consoleCount, consoleCount) + " · " +
                    pluralStringResource(R.plurals.q5_sources_count, sourceCount, sourceCount)
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically
        ) {
            ActionPill(stringResource(R.string.sources_add_console), onAddConsole, icon = R.drawable.ic_add, tone = ActionTone.Accent)
            if (isRescanning) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 6.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.sources_rescanning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                ActionPill(stringResource(R.string.sources_rescan_all), onRescan, icon = R.drawable.ic_retry)
            }
            ActionPill(stringResource(R.string.src91_tracker_title), onOpenTracker, icon = R.drawable.ic_dashboard)
            ActionPill(stringResource(R.string.sources_export), onExport, icon = R.drawable.ic_share)
            ActionPill(stringResource(R.string.sources_import), onImport, icon = R.drawable.ic_import, enabled = !isRescanning)
        }
    }
}
