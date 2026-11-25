package com.tufein.valkyr.ui.screens.cloud

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tufein.valkyr.R
import com.tufein.valkyr.data.service.CloudMessages
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.util.WebDavPaths

private enum class MirrorEditor { SERVER, USER, PASSWORD, FOLDER }

/**
 * 9.5: the second cloud, a WebDAV server or an S3 bucket that gets a copy of every backup. It uses
 * the same login rows as the main cloud, its own test, and says plainly when the last copy failed.
 */
@Composable
internal fun MirrorSection(viewModel: CloudBackupViewModel) {
    val ui by viewModel.mirror.collectAsStateWithLifecycle()
    val testing by viewModel.mirrorTesting.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editor by remember { mutableStateOf<MirrorEditor?>(null) }
    val notSet = stringResource(R.string.settings_not_set)
    val change = stringResource(R.string.settings_change)
    val errorColor = MaterialTheme.colorScheme.error

    when (editor) {
        MirrorEditor.SERVER -> DavTextDialog(
            title = stringResource(R.string.dav_server),
            hint = stringResource(R.string.dav_server_dialog_hint),
            label = stringResource(R.string.dav_server),
            value = ui.server,
            keyboard = KeyboardType.Uri,
            checkServerText = true,
            onSave = viewModel::setMirrorServer,
            onDismiss = { editor = null }
        )
        MirrorEditor.USER -> DavTextDialog(
            title = stringResource(R.string.dav_user),
            hint = stringResource(R.string.dav_user_dialog_hint),
            label = stringResource(R.string.dav_user),
            value = ui.user,
            onSave = viewModel::setMirrorUser,
            onDismiss = { editor = null }
        )
        MirrorEditor.PASSWORD -> DavTextDialog(
            title = stringResource(R.string.dav_password),
            hint = stringResource(R.string.dav_password_dialog_hint),
            label = stringResource(R.string.dav_password),
            value = "",
            masked = true,
            allowEmpty = true,
            onSave = viewModel::setMirrorPassword,
            onDismiss = { editor = null }
        )
        MirrorEditor.FOLDER -> DavTextDialog(
            title = stringResource(R.string.dav_folder),
            hint = stringResource(R.string.dav_folder_dialog_hint),
            label = stringResource(R.string.dav_folder),
            value = ui.folder,
            onSave = viewModel::setMirrorFolder,
            onDismiss = { editor = null }
        )
        null -> Unit
    }

    DavSection(stringResource(R.string.v95_mirror_section), R.drawable.ic_backup) {
        DavNote(stringResource(R.string.v95_mirror_note), R.drawable.ic_info)
        DavRow(
            icon = R.drawable.ic_link,
            title = stringResource(R.string.dav_server),
            hint = ui.server.ifBlank { notSet },
            onClick = { editor = MirrorEditor.SERVER }
        ) { ActionPill(change, { editor = MirrorEditor.SERVER }) }
        if (WebDavPaths.isCleartextRisk(ui.server)) {
            DavNote(stringResource(R.string.sync6_cleartext_warning), R.drawable.ic_warning, tone = PillTone.Danger)
        }
        DavRow(
            icon = R.drawable.ic_account,
            title = stringResource(R.string.dav_user),
            hint = ui.user.ifBlank { notSet },
            onClick = { editor = MirrorEditor.USER }
        ) { ActionPill(change, { editor = MirrorEditor.USER }) }
        DavRow(
            icon = R.drawable.ic_key,
            title = stringResource(R.string.dav_password),
            hint = if (ui.hasPassword) "••••••••" else notSet,
            onClick = { editor = MirrorEditor.PASSWORD }
        ) { ActionPill(change, { editor = MirrorEditor.PASSWORD }) }
        DavRow(
            icon = R.drawable.ic_folder,
            title = stringResource(R.string.dav_folder),
            hint = ui.folder.ifBlank { WebDavPaths.DEFAULT_FOLDER },
            onClick = { editor = MirrorEditor.FOLDER }
        ) { ActionPill(change, { editor = MirrorEditor.FOLDER }) }

        val failed = ui.records.lastError.isNotEmpty()
        val hint = when {
            testing -> stringResource(R.string.dav_test_busy)
            !ui.configured -> stringResource(R.string.dav_test_needs_server)
            failed -> CloudMessages.render(context, ui.records.lastError)
            ui.records.lastCopyAt > 0 -> stringResource(R.string.v95_mirror_last_copy, relative(ui.records.lastCopyAt))
            ui.records.lastTestAt > 0 -> stringResource(R.string.dav_test_ok, relative(ui.records.lastTestAt))
            else -> stringResource(R.string.dav_test_never)
        }
        DavRow(
            icon = R.drawable.ic_network_check,
            title = stringResource(R.string.dav_test),
            hint = hint,
            hintColor = if (failed && !testing) errorColor else Color.Unspecified,
            onClick = { if (ui.configured && !testing) viewModel.testMirror(context) }
        ) {
            ActionPill(
                stringResource(if (testing) R.string.dav_test_busy else R.string.dav_test_action),
                { viewModel.testMirror(context) },
                icon = R.drawable.ic_sync,
                enabled = ui.configured && !testing
            )
        }
        if (ui.configured) {
            DavRow(
                icon = R.drawable.ic_cloud_off,
                title = stringResource(R.string.v95_mirror_stop),
                hint = stringResource(R.string.v95_mirror_stop_hint),
                onClick = { viewModel.clearMirror() }
            ) { ActionPill(stringResource(R.string.v95_mirror_stop_action), { viewModel.clearMirror() }, tone = ActionTone.Danger) }
        }
    }
}
