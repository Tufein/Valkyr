package com.tufein.valkyr.ui.screens.cloud

import android.text.format.DateUtils
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tufein.valkyr.R
import com.tufein.valkyr.data.service.CloudMessages
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.ScreenTitle
import com.tufein.valkyr.ui.components.Stepper
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.formatBytes
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.util.BackupCrypto
import com.tufein.valkyr.util.CertTrust
import com.tufein.valkyr.util.CloudBackupNames
import androidx.compose.foundation.layout.Box
import com.tufein.valkyr.ui.screens.settings.SettingsTileGap
import com.tufein.valkyr.ui.screens.settings.SettingsTileSize
import com.tufein.valkyr.ui.screens.settings.settingsInset
import com.tufein.valkyr.util.CloudErrors
import com.tufein.valkyr.util.DavProblem
import com.tufein.valkyr.util.WebDavPaths
import java.text.DateFormat
import java.util.Date

/** Which text editor is open. */
private enum class DavEditor { SERVER, USER, PASSWORD, FOLDER, PASSPHRASE, DEVICE_NAME, SHARED_LIST, SHARED_NAME }

/**
 * The cloud backup screen: connection to the user's WebDAV server (Nextcloud, ownCloud, Synology,
 * Koofr, any WebDAV), encrypted backup with the list of backups in the cloud and restore, and device
 * sync. Meant to be called as `composable(NavRoutes.CloudBackup.route) { CloudBackupScreen() }`.
 */
@Composable
fun CloudBackupScreen(viewModel: CloudBackupViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val testing by viewModel.testing.collectAsStateWithLifecycle()
    val backingUp by viewModel.backingUp.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val sharedSyncing by viewModel.sharedSyncing.collectAsStateWithLifecycle()
    val list by viewModel.list.collectAsStateWithLifecycle()
    val reading by viewModel.reading.collectAsStateWithLifecycle()
    val pendingRestore by viewModel.pendingRestore.collectAsStateWithLifecycle()
    val certPrompt by viewModel.certPrompt.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editor by remember { mutableStateOf<DavEditor?>(null) }
    var shown by remember { mutableIntStateOf(PAGE) }

    // Read the list once when the connection is known to work; later changes refresh it themselves.
    LaunchedEffect(ui.connection) {
        if (ui.connection == DavConnectionState.OK && list is DavListState.Idle) viewModel.refreshList()
    }

    when (editor) {
        DavEditor.SERVER -> DavTextDialog(
            title = stringResource(R.string.dav_server),
            hint = stringResource(R.string.dav_server_dialog_hint),
            label = stringResource(R.string.dav_server),
            value = ui.server,
            keyboard = KeyboardType.Uri,
            checkServerText = true,
            onSave = viewModel::setServer,
            onDismiss = { editor = null }
        )
        DavEditor.USER -> DavTextDialog(
            title = stringResource(R.string.dav_user),
            hint = stringResource(R.string.dav_user_dialog_hint),
            label = stringResource(R.string.dav_user),
            value = ui.user,
            onSave = viewModel::setUser,
            onDismiss = { editor = null }
        )
        DavEditor.PASSWORD -> DavTextDialog(
            title = stringResource(R.string.dav_password),
            hint = stringResource(R.string.dav_password_dialog_hint),
            label = stringResource(R.string.dav_password),
            value = "",
            masked = true,
            allowEmpty = true,
            onSave = viewModel::setPassword,
            onDismiss = { editor = null }
        )
        DavEditor.FOLDER -> DavTextDialog(
            title = stringResource(R.string.dav_folder),
            hint = stringResource(R.string.dav_folder_dialog_hint),
            label = stringResource(R.string.dav_folder),
            value = ui.folder,
            onSave = viewModel::setFolder,
            onDismiss = { editor = null }
        )
        DavEditor.DEVICE_NAME -> DavTextDialog(
            title = stringResource(R.string.dav_device_name),
            hint = stringResource(R.string.dav_device_name_dialog_hint),
            label = stringResource(R.string.dav_device_name),
            value = ui.deviceName,
            onSave = viewModel::setDeviceName,
            onDismiss = { editor = null }
        )
        DavEditor.SHARED_LIST -> DavTextDialog(
            title = stringResource(R.string.sync6_shared_list),
            hint = stringResource(R.string.sync6_shared_list_dialog),
            label = stringResource(R.string.sync6_shared_list),
            value = ui.sharedList,
            allowEmpty = true,
            onSave = { viewModel.setSharedList(context, it) },
            onDismiss = { editor = null }
        )
        DavEditor.SHARED_NAME -> DavTextDialog(
            title = stringResource(R.string.sync6_shared_name),
            hint = stringResource(R.string.sync6_shared_name_dialog),
            label = stringResource(R.string.sync6_shared_name),
            value = ui.sharedName,
            allowEmpty = true,
            onSave = viewModel::setSharedName,
            onDismiss = { editor = null }
        )
        DavEditor.PASSPHRASE -> DavPassphraseDialog(
            hasPassphrase = ui.hasPassphrase,
            onSave = viewModel::setPassphrase,
            onDismiss = { editor = null }
        )
        null -> Unit
    }
    pendingRestore?.let { prepared ->
        val date = if (prepared.createdAt > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(prepared.createdAt)) else "?"
        DavRestoreDialog(
            message = stringResource(R.string.backup_import_message, date, prepared.appVersion),
            onConfirm = { viewModel.restore(context) },
            onDismiss = viewModel::dismissRestore
        )
    }
    certPrompt?.let { prompt ->
        DavCertificateDialog(prompt, onTrust = { viewModel.confirmTrust(context) }, onDismiss = viewModel::dismissCertPrompt)
    }

    val notSet = stringResource(R.string.settings_not_set)
    val change = stringResource(R.string.settings_change)
    val configured = ui.connection != DavConnectionState.NOT_SET
    val firstFocus = rememberInitialFocus()
    val errorColor = MaterialTheme.colorScheme.error

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp)) {
        ScreenTitle(
            text = stringResource(R.string.dav_title),
            subtitle = stringResource(R.string.dav_subtitle),
            icon = R.drawable.ic_cloud_upload,
            trailing = {
                when (ui.connection) {
                    DavConnectionState.OK -> Pill(stringResource(R.string.dav_status_ok), tone = PillTone.Success, icon = R.drawable.ic_check_circle)
                    DavConnectionState.FAILED -> Pill(stringResource(R.string.dav_status_problem), tone = PillTone.Danger, icon = R.drawable.ic_error_circle)
                    DavConnectionState.UNTESTED -> Pill(stringResource(R.string.dav_status_untested), tone = PillTone.Warning, icon = R.drawable.ic_warning)
                    DavConnectionState.NOT_SET -> Pill(stringResource(R.string.dav_status_not_set), tone = PillTone.Neutral)
                }
            }
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // ---- Server --------------------------------------------------------------------------
            item(key = "server") {
                DavSection(stringResource(R.string.dav_section_server), R.drawable.ic_server) {
                    DavRow(
                        icon = R.drawable.ic_link,
                        title = stringResource(R.string.dav_server),
                        hint = ui.server.ifBlank { notSet },
                        onClick = { editor = DavEditor.SERVER },
                        modifier = Modifier.focusRequester(firstFocus)
                    ) { ActionPill(change, { editor = DavEditor.SERVER }) }
                    // The password goes out in clear text: said plainly, not hidden in a hint.
                    if (WebDavPaths.isCleartextRisk(ui.server)) {
                        DavNote(stringResource(R.string.sync6_cleartext_warning), R.drawable.ic_warning, tone = PillTone.Danger)
                    }
                    DavRow(
                        icon = R.drawable.ic_account,
                        title = stringResource(R.string.dav_user),
                        hint = ui.user.ifBlank { notSet },
                        onClick = { editor = DavEditor.USER }
                    ) { ActionPill(change, { editor = DavEditor.USER }) }
                    DavRow(
                        icon = R.drawable.ic_key,
                        title = stringResource(R.string.dav_password),
                        hint = if (ui.hasPassword) PASSWORD_DOTS else notSet,
                        onClick = { editor = DavEditor.PASSWORD }
                    ) { ActionPill(change, { editor = DavEditor.PASSWORD }) }
                    DavRow(
                        icon = R.drawable.ic_folder,
                        title = stringResource(R.string.dav_folder),
                        hint = ui.folder,
                        onClick = { editor = DavEditor.FOLDER }
                    ) { ActionPill(change, { editor = DavEditor.FOLDER }) }

                    val failed = ui.connection == DavConnectionState.FAILED
                    val testHint = when {
                        testing -> stringResource(R.string.dav_test_busy)
                        ui.connection == DavConnectionState.NOT_SET -> stringResource(R.string.dav_test_needs_server)
                        failed -> CloudMessages.render(context, ui.records.lastTestError)
                        ui.connection == DavConnectionState.OK -> stringResource(R.string.dav_test_ok, relative(ui.records.lastTestAt))
                        else -> stringResource(R.string.dav_test_never)
                    }
                    DavRow(
                        icon = R.drawable.ic_network_check,
                        title = stringResource(R.string.dav_test),
                        hint = testHint,
                        hintColor = if (failed && !testing) errorColor else Color.Unspecified,
                        onClick = { if (configured && !testing) viewModel.testConnection(context) }
                    ) {
                        ActionPill(
                            stringResource(if (testing) R.string.dav_test_busy else R.string.dav_test_action),
                            { viewModel.testConnection(context) },
                            icon = R.drawable.ic_sync,
                            enabled = configured && !testing
                        )
                    }

                    // Only for a server whose certificate Android does not trust (self-signed, private CA).
                    val tlsProblem = failed && (CloudErrors.decode(ui.records.lastTestError) as? CloudErrors.Decoded.Dav)?.problem == DavProblem.TLS
                    val https = WebDavPaths.normalizeServer(ui.server)?.let { CertTrust.isHttps(it) } == true
                    if (https && (tlsProblem || ui.trustFingerprint.isNotEmpty())) {
                        val pinned = ui.trustFingerprint.isNotEmpty()
                        DavRow(
                            icon = if (pinned) R.drawable.ic_verified else R.drawable.ic_lock,
                            title = stringResource(R.string.dav_cert),
                            hint = if (pinned) stringResource(R.string.dav_cert_trusted_hint, CertTrust.format(ui.trustFingerprint).take(23) + "…") else stringResource(R.string.dav_cert_hint),
                            onClick = { if (pinned) viewModel.forgetTrust() else viewModel.checkCertificate(context) }
                        ) {
                            ActionPill(
                                stringResource(if (pinned) R.string.dav_cert_forget else R.string.dav_cert_check),
                                { if (pinned) viewModel.forgetTrust() else viewModel.checkCertificate(context) },
                                icon = R.drawable.ic_lock
                            )
                        }
                    }
                }
            }

            // ---- Encrypted backup ----------------------------------------------------------------
            item(key = "backup") {
                DavSection(stringResource(R.string.dav_section_backup), R.drawable.ic_backup) {
                    DavRow(
                        icon = R.drawable.ic_lock,
                        title = stringResource(R.string.dav_passphrase),
                        hint = stringResource(if (ui.hasPassphrase) R.string.dav_passphrase_set else R.string.dav_passphrase_unset),
                        onClick = { editor = DavEditor.PASSPHRASE }
                    ) { ActionPill(stringResource(if (ui.hasPassphrase) R.string.settings_change else R.string.dav_set), { editor = DavEditor.PASSPHRASE }) }
                    DavNote(stringResource(R.string.dav_passphrase_warning), R.drawable.ic_warning, tone = PillTone.Warning)
                    DavRow(
                        icon = R.drawable.ic_update,
                        title = stringResource(R.string.dav_auto),
                        hint = stringResource(R.string.dav_auto_hint),
                        onClick = { viewModel.setAutoBackup(context, !ui.autoBackup) },
                        onAdjust = { viewModel.setAutoBackup(context, it > 0) }
                    ) { DavSwitch(ui.autoBackup) { viewModel.setAutoBackup(context, it) } }
                    DavRow(
                        icon = R.drawable.ic_archive,
                        title = stringResource(R.string.dav_keep),
                        hint = stringResource(R.string.dav_keep_hint),
                        onClick = { viewModel.setKeep(CloudBackupNames.shiftKeep(ui.keep, 1)) },
                        onAdjust = { viewModel.setKeep(CloudBackupNames.shiftKeep(ui.keep, it)) }
                    ) {
                        Stepper(
                            ui.keep.toString(),
                            onDecrement = { viewModel.setKeep(CloudBackupNames.shiftKeep(ui.keep, -1)) },
                            onIncrement = { viewModel.setKeep(CloudBackupNames.shiftKeep(ui.keep, 1)) },
                            valueWidth = 48.dp
                        )
                    }
                    val records = ui.records
                    val backupFailed = records.lastBackupError.isNotEmpty()
                    val backupHint = when {
                        backingUp -> stringResource(R.string.dav_backup_busy)
                        backupFailed -> stringResource(R.string.dav_backup_failed_row, CloudMessages.render(context, records.lastBackupError))
                        records.lastBackupAt > 0 -> stringResource(R.string.dav_backup_last, relative(records.lastBackupAt), formatBytes(records.lastBackupBytes))
                        else -> stringResource(R.string.dav_backup_never)
                    }
                    val canBackUp = configured && ui.hasPassphrase && !backingUp
                    DavRow(
                        icon = R.drawable.ic_cloud_upload,
                        title = stringResource(R.string.dav_backup_now),
                        hint = if (!configured) stringResource(R.string.dav_test_needs_server) else if (!ui.hasPassphrase) stringResource(R.string.dav_err_no_passphrase) else backupHint,
                        hintColor = if (backupFailed && !backingUp && configured && ui.hasPassphrase) errorColor else Color.Unspecified,
                        onClick = { if (canBackUp) viewModel.backupNow(context) }
                    ) {
                        ActionPill(
                            stringResource(if (backingUp) R.string.dav_backup_busy else R.string.dav_backup_action),
                            { viewModel.backupNow(context) },
                            icon = R.drawable.ic_cloud_upload,
                            tone = ActionTone.Accent,
                            enabled = canBackUp
                        )
                    }
                }
            }

            // ---- The backups in the cloud --------------------------------------------------------
            item(key = "list") {
                DavSection(
                    title = stringResource(R.string.dav_section_list),
                    icon = R.drawable.ic_history,
                    action = {
                        if (configured) ActionPill(
                            stringResource(R.string.dav_list_refresh),
                            { viewModel.refreshList() },
                            icon = R.drawable.ic_sync,
                            enabled = list !is DavListState.Loading
                        )
                    }
                ) {
                    when (val state = list) {
                        DavListState.Idle -> DavRow(
                            icon = R.drawable.ic_cloud_download,
                            title = stringResource(if (configured) R.string.dav_list_idle else R.string.dav_list_needs_setup),
                            onClick = { if (configured) viewModel.refreshList() }
                        )
                        DavListState.Loading -> Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = settingsInset(), vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(SettingsTileGap)
                        ) {
                            // Where the tile of a row would be, so the text lines up with the rows.
                            Box(Modifier.size(SettingsTileSize), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            }
                            Text(stringResource(R.string.dav_list_loading), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        is DavListState.Failed -> DavRow(
                            icon = R.drawable.ic_cloud_off,
                            title = stringResource(R.string.dav_list_failed_title),
                            hint = CloudMessages.of(context, state.error),
                            hintColor = errorColor,
                            onClick = { viewModel.refreshList() }
                        ) { ActionPill(stringResource(R.string.dav_list_retry), { viewModel.refreshList() }) }
                        is DavListState.Loaded -> {
                            if (state.items.isEmpty()) {
                                DavNote(stringResource(R.string.dav_list_empty), R.drawable.ic_info)
                            } else {
                                state.items.take(shown).forEach { item ->
                                    val date = if (item.createdAt > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.createdAt)) else item.name
                                    val device = item.device.ifEmpty { stringResource(R.string.dav_list_unknown_device) }
                                    val hint = listOfNotNull(device, item.size?.let { formatBytes(it) }).joinToString(" · ")
                                    val readingThis = reading == item.name
                                    DavRow(
                                        icon = R.drawable.ic_backup,
                                        title = date,
                                        hint = hint,
                                        onClick = { viewModel.readBackup(context, item) }
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            if (item.isThisDevice) Pill(stringResource(R.string.dav_list_this_device), tone = PillTone.Accent)
                                            ActionPill(
                                                stringResource(if (readingThis) R.string.dav_restore_reading else R.string.dav_restore),
                                                { viewModel.readBackup(context, item) },
                                                icon = R.drawable.ic_restore,
                                                enabled = reading == null
                                            )
                                        }
                                    }
                                }
                                if (state.items.size > shown) {
                                    DavRow(
                                        icon = R.drawable.ic_expand_more,
                                        title = stringResource(R.string.dav_list_more, state.items.size - shown),
                                        onClick = { shown += PAGE }
                                    ) { ActionPill(stringResource(R.string.dav_list_show), { shown += PAGE }) }
                                }
                            }
                        }
                    }
                }
            }

            // ---- Device sync ---------------------------------------------------------------------
            item(key = "sync") {
                DavSection(stringResource(R.string.dav_section_sync), R.drawable.ic_devices) {
                    DavRow(
                        icon = R.drawable.ic_smartphone,
                        title = stringResource(R.string.dav_device_name),
                        hint = ui.deviceName.ifBlank { notSet },
                        onClick = { editor = DavEditor.DEVICE_NAME }
                    ) { ActionPill(change, { editor = DavEditor.DEVICE_NAME }) }
                    DavRow(
                        icon = R.drawable.ic_sync,
                        title = stringResource(R.string.dav_device_sync),
                        hint = stringResource(R.string.dav_device_sync_hint),
                        onClick = { viewModel.setDeviceSync(context, !ui.deviceSync) },
                        onAdjust = { viewModel.setDeviceSync(context, it > 0) }
                    ) { DavSwitch(ui.deviceSync) { viewModel.setDeviceSync(context, it) } }

                    val records = ui.records
                    val syncFailed = records.lastSyncError.isNotEmpty()
                    val syncHint = when {
                        syncing -> stringResource(R.string.dav_sync_busy)
                        syncFailed -> stringResource(R.string.dav_sync_failed_row, CloudMessages.render(context, records.lastSyncError))
                        records.lastSyncAt > 0 -> stringResource(R.string.dav_sync_last, relative(records.lastSyncAt), records.lastSyncAdded, records.lastSyncRemoved) +
                            if (records.lastSyncSent) " · " + stringResource(R.string.dav_sync_sent) else ""
                        else -> stringResource(R.string.dav_sync_never)
                    }
                    DavRow(
                        icon = R.drawable.ic_cloud_sync,
                        title = stringResource(R.string.dav_sync_now),
                        hint = if (!configured) stringResource(R.string.dav_test_needs_server) else syncHint,
                        hintColor = if (syncFailed && !syncing && configured) errorColor else Color.Unspecified,
                        onClick = { if (configured && !syncing) viewModel.syncNow(context) }
                    ) {
                        ActionPill(
                            stringResource(if (syncing) R.string.dav_sync_busy else R.string.dav_sync_action),
                            { viewModel.syncNow(context) },
                            icon = R.drawable.ic_sync,
                            tone = ActionTone.Accent,
                            enabled = configured && !syncing
                        )
                    }
                    if (records.syncHeldBack > 0) {
                        DavRow(
                            icon = R.drawable.ic_warning,
                            title = pluralStringResource(R.plurals.dav_sync_held_title, records.syncHeldBack, records.syncHeldBack),
                            hint = stringResource(R.string.dav_sync_held_hint),
                            onClick = { viewModel.syncNow(context, allowMassRemoval = true) }
                        ) { ActionPill(stringResource(R.string.dav_sync_held_apply), { viewModel.syncNow(context, allowMassRemoval = true) }, tone = ActionTone.Danger) }
                    }
                }
            }

            // ---- Shared wishlist -----------------------------------------------------------------
            item(key = "shared") {
                DavSection(stringResource(R.string.sync6_shared_section), R.drawable.ic_wishlist) {
                    DavRow(
                        icon = R.drawable.ic_share,
                        title = stringResource(R.string.sync6_shared_list),
                        hint = ui.sharedList.ifBlank { stringResource(R.string.sync6_shared_off) },
                        onClick = { editor = DavEditor.SHARED_LIST }
                    ) { ActionPill(change, { editor = DavEditor.SHARED_LIST }) }
                    DavRow(
                        icon = R.drawable.ic_account,
                        title = stringResource(R.string.sync6_shared_name),
                        hint = ui.sharedName.ifBlank { ui.deviceName.ifBlank { notSet } },
                        onClick = { editor = DavEditor.SHARED_NAME }
                    ) { ActionPill(change, { editor = DavEditor.SHARED_NAME }) }

                    val sharedOn = configured && ui.sharedList.isNotBlank()
                    val sharedRecords = ui.records
                    val sharedFailed = sharedRecords.lastSharedError.isNotEmpty()
                    val sharedHint = when {
                        sharedSyncing -> stringResource(R.string.sync6_shared_busy)
                        !configured -> stringResource(R.string.dav_test_needs_server)
                        ui.sharedList.isBlank() -> stringResource(R.string.sync6_shared_needs_list)
                        sharedFailed -> stringResource(R.string.sync6_shared_failed_row, CloudMessages.render(context, sharedRecords.lastSharedError))
                        sharedRecords.lastSharedAt > 0 -> stringResource(R.string.sync6_shared_last, relative(sharedRecords.lastSharedAt), sharedRecords.lastSharedAdded, sharedRecords.lastSharedRemoved) +
                            if (sharedRecords.lastSharedSent) " · " + stringResource(R.string.dav_sync_sent) else ""
                        else -> stringResource(R.string.sync6_shared_never)
                    }
                    DavRow(
                        icon = R.drawable.ic_cloud_sync,
                        title = stringResource(R.string.sync6_shared_now),
                        hint = sharedHint,
                        hintColor = if (sharedFailed && !sharedSyncing && sharedOn) errorColor else Color.Unspecified,
                        onClick = { if (sharedOn && !sharedSyncing) viewModel.syncShared(context) }
                    ) {
                        ActionPill(
                            stringResource(if (sharedSyncing) R.string.sync6_shared_busy else R.string.sync6_shared_action),
                            { viewModel.syncShared(context) },
                            icon = R.drawable.ic_sync,
                            tone = ActionTone.Accent,
                            enabled = sharedOn && !sharedSyncing
                        )
                    }
                    DavNote(stringResource(R.string.sync6_shared_hint), R.drawable.ic_info)
                }
            }
        }
    }
}

/** How many backups the list shows before "Show more". */
private const val PAGE = 12

/** What the app password looks like in the row: dots, never any part of the password. */
private const val PASSWORD_DOTS = "••••••••"

/** "5 minutes ago"; empty when [millis] is not a time. */
private fun relative(millis: Long): String =
    if (millis <= 0) "" else DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

/** Edits one text setting (address, user name, folder, device name, password); B cancels. */
@Composable
private fun DavTextDialog(
    title: String,
    hint: String,
    label: String,
    value: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    masked: Boolean = false,
    allowEmpty: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    /** The server address: warns while typing about plain http:// over the internet and about a login in the address. */
    checkServerText: Boolean = false
) {
    var text by remember { mutableStateOf(value) }
    val fieldFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(hint, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(label) },
                    singleLine = true,
                    visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = if (masked) KeyboardType.Password else keyboard),
                    modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus)
                )
                if (checkServerText && WebDavPaths.isCleartextRisk(text)) {
                    Text(stringResource(R.string.sync6_cleartext_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (checkServerText && WebDavPaths.stripCredentials(text) != text.trim()) {
                    Text(stringResource(R.string.sync6_credentials_removed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            DialogButton(
                text = stringResource(R.string.dialog_save),
                enabled = allowEmpty || text.isNotBlank(),
                onClick = { onSave(text.trim('\n', '\r').let { if (masked) it else it.trim() }); onDismiss() }
            )
        },
        dismissButton = { DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss) }
    )
}

/** Sets the backup passphrase (typed twice) with the warning that a lost one cannot be recovered. */
@Composable
private fun DavPassphraseDialog(hasPassphrase: Boolean, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val fieldFocus = rememberInitialFocus()
    val long = BackupCrypto.isAcceptable(first)
    val same = first.trim() == second.trim()
    val problem = when {
        first.isEmpty() -> null
        !long -> stringResource(R.string.dav_passphrase_short, BackupCrypto.MIN_PASSPHRASE)
        second.isNotEmpty() && !same -> stringResource(R.string.dav_passphrase_mismatch)
        else -> null
    }
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dav_passphrase_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.dav_passphrase_dialog_message), style = MaterialTheme.typography.bodyMedium)
                if (hasPassphrase) Text(stringResource(R.string.dav_passphrase_change_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = it },
                    label = { Text(stringResource(R.string.dav_passphrase_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus)
                )
                OutlinedTextField(
                    value = second,
                    onValueChange = { second = it },
                    label = { Text(stringResource(R.string.dav_passphrase_repeat)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            DialogButton(
                text = stringResource(R.string.dialog_save),
                enabled = long && same,
                onClick = { onSave(first.trim()); onDismiss() }
            )
        },
        dismissButton = { DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss) }
    )
}

/** The same confirmation a local restore asks, plus what stays as it is. */
@Composable
private fun DavRestoreDialog(message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancelFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_import_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.dav_restore_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { DialogButton(text = stringResource(R.string.backup_import_action), onClick = onConfirm) },
        dismissButton = { DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss, initialFocus = cancelFocus) }
    )
}

/** Shows the fingerprint of the certificate the server presents and asks whether to trust it. */
@Composable
private fun DavCertificateDialog(prompt: DavCertPrompt, onTrust: () -> Unit, onDismiss: () -> Unit) {
    val until = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(prompt.validUntil))
    val cancelFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dav_cert_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.dav_cert_dialog_message, prompt.serverUrl))
                Text(CertTrust.format(prompt.fingerprint), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text(stringResource(R.string.dav_cert_dialog_details, prompt.subject, until), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { DialogButton(text = stringResource(R.string.dav_cert_trust), onClick = onTrust) },
        dismissButton = { DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss, initialFocus = cancelFocus) }
    )
}
