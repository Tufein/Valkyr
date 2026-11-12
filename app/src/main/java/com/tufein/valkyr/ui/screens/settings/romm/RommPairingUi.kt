package com.tufein.valkyr.ui.screens.settings.romm

import android.text.format.DateUtils
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tufein.valkyr.R
import com.tufein.valkyr.data.service.RommRemoteInstallService.PairState
import com.tufein.valkyr.data.service.RommRemoteInstallService.PollProblem
import com.tufein.valkyr.data.service.RommRemoteInstallService.Problem
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.ui.screens.settings.SettingRow
import com.tufein.valkyr.ui.screens.settings.ThemedSwitch
import com.tufein.valkyr.util.RommPairing
import com.tufein.valkyr.util.ToastUtil
import com.tufein.valkyr.util.RommRemoteInstall

/** Settings → RomM, "Pair with code": next to the token row; the manual token stays as it was. */
@Composable
fun PairWithCodeRow(onOpen: () -> Unit) {
    SettingRow(
        icon = R.drawable.ic_qr_code,
        title = stringResource(R.string.romm55_pair_title),
        hint = stringResource(R.string.romm55_pair_hint),
        onClick = onOpen
    ) { ActionPill(stringResource(R.string.romm55_pair_action), onOpen, icon = R.drawable.ic_key, tone = ActionTone.Accent) }
}

/** Types the 8-character code of RomM's "Pair device" and trades it for a token. Opens with the field focused, B cancels. */
@Composable
fun PairWithCodeDialog(viewModel: RommPairingViewModel, onDismiss: () -> Unit) {
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val problem by viewModel.problem.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    val context = LocalContext.current
    val fieldFocus = rememberInitialFocus()
    val complete = RommPairing.isComplete(text)
    val bad = RommPairing.invalidChars(text)
    fun submit() = viewModel.pairWithCode(context, text, onPaired = onDismiss)
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.romm55_pair_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.romm55_pair_dialog_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { new -> text = new.filter { it.isLetterOrDigit() || it == '-' || it == ' ' }.take(12); viewModel.clearProblem() },
                    label = { Text(stringResource(R.string.romm55_pair_code_label)) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus)
                )
                val line = when {
                    problem != null -> stringResource(
                        when (problem) {
                            CodeProblem.NOT_COMPLETE -> R.string.romm55_pair_err_incomplete
                            CodeProblem.NO_SERVER -> R.string.romm55_pair_err_no_server
                            CodeProblem.UNKNOWN_CODE -> R.string.romm55_pair_err_unknown
                            CodeProblem.TOO_MANY -> R.string.romm55_pair_err_too_many
                            CodeProblem.OWNER_DISABLED -> R.string.romm55_pair_err_disabled
                            else -> R.string.romm55_pair_err_offline
                        }
                    )
                    bad.isNotEmpty() -> stringResource(R.string.romm55_pair_err_chars, bad)
                    else -> null
                }
                if (line != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            DialogButton(text = stringResource(if (busy) R.string.romm55_pair_busy else R.string.romm55_pair_action), enabled = complete && !busy, onClick = ::submit)
        },
        dismissButton = { DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss) }
    )
}

/**
 * The remote install rows (5.6.0): the switch (turning it on pairs the handheld first), and, once paired, "Check now"
 * and "Unpair". [rows] builds them as the screen's list entries.
 */
@Composable
fun remoteInstallRows(viewModel: RommPairingViewModel): List<@Composable () -> Unit> {
    val ui by viewModel.remoteUi.collectAsStateWithLifecycle()
    return buildList {
        add {
            val last = if (ui.lastPollAt > 0) DateUtils.getRelativeTimeSpanString(ui.lastPollAt).toString() else null
            val hint = when {
                ui.problem == PollProblem.REJECTED -> stringResource(R.string.romm55_remote_problem_rejected)
                !ui.paired -> stringResource(R.string.romm55_remote_hint_unpaired)
                !ui.enabled -> stringResource(R.string.romm55_remote_hint_off)
                ui.problem == PollProblem.DISABLED -> stringResource(R.string.romm55_remote_problem_disabled)
                ui.problem == PollProblem.OFFLINE -> stringResource(R.string.romm55_remote_problem_offline)
                ui.problem == PollProblem.SWITCHED_OFF -> stringResource(R.string.romm55_remote_problem_off)
                last != null -> stringResource(R.string.romm55_remote_hint_last, last, ui.lastQueued)
                else -> stringResource(R.string.romm55_remote_hint_on)
            }
            SettingRow(
                icon = R.drawable.ic_cloud_download,
                title = stringResource(R.string.romm55_remote_title),
                hint = hint,
                hintColor = if (ui.enabled && ui.problem != null) MaterialTheme.colorScheme.error else null,
                onClick = { viewModel.setRemoteEnabled(!ui.enabled) },
                onAdjust = { viewModel.setRemoteEnabled(it > 0) }
            ) { ThemedSwitch(ui.enabled) { viewModel.setRemoteEnabled(it) } }
        }
        if (ui.paired) {
            add {
                SettingRow(
                    icon = R.drawable.ic_sync,
                    title = stringResource(R.string.romm55_remote_check),
                    hint = stringResource(R.string.romm55_remote_check_hint),
                    onClick = viewModel::checkNow
                ) { ActionPill(stringResource(R.string.romm55_remote_check_action), viewModel::checkNow, icon = R.drawable.ic_sync) }
            }
            add {
                SettingRow(
                    icon = R.drawable.ic_close,
                    title = stringResource(R.string.romm55_remote_unpair),
                    hint = stringResource(R.string.romm55_remote_unpair_hint),
                    onClick = viewModel::unpairDevice
                ) { ActionPill(stringResource(R.string.romm55_remote_unpair_action), viewModel::unpairDevice, tone = ActionTone.Danger) }
            }
        }
    }
}

/** The device pairing: the code to approve in RomM's web interface while it waits, or why it ended. Closes itself when paired. */
@Composable
fun DevicePairingDialog(viewModel: RommPairingViewModel) {
    val state by viewModel.pairState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pairedText = stringResource(R.string.romm55_remote_paired)
    LaunchedEffect(state) {
        if (state is PairState.Done) {
            ToastUtil.showSuccess(context, pairedText)
            viewModel.cancelDevicePairing()
        }
    }
    val current = state
    if (current is PairState.Idle || current is PairState.Done) return
    val dismiss = viewModel::cancelDevicePairing
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(dismiss),
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.romm55_remote_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (current) {
                    is PairState.Waiting -> {
                        Text(stringResource(R.string.romm55_remote_dialog_steps), style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            RommRemoteInstall.displayUserCode(current.userCode),
                            style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (current.url != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(current.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.romm55_remote_dialog_until, DateUtils.formatDateTime(context, current.expiresAt, DateUtils.FORMAT_SHOW_TIME)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is PairState.Ended -> Text(
                        stringResource(
                            when (current.problem) {
                                Problem.NOT_SET_UP -> R.string.romm55_remote_err_not_set_up
                                Problem.UNSUPPORTED -> R.string.romm55_remote_err_unsupported
                                Problem.DENIED -> R.string.romm55_remote_err_denied
                                Problem.EXPIRED -> R.string.romm55_remote_err_expired
                                Problem.SCOPES -> R.string.romm55_remote_err_scopes
                                Problem.CAPABILITY -> R.string.romm55_remote_err_capability
                                Problem.OFFLINE -> R.string.romm55_remote_err_offline
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error
                    )
                    else -> Unit
                }
            }
        },
        confirmButton = {
            if (current is PairState.Ended) DialogButton(text = stringResource(R.string.romm55_remote_retry), onClick = viewModel::startDevicePairing)
        },
        dismissButton = {
            DialogButton(text = stringResource(if (current is PairState.Waiting) R.string.dialog_cancel else R.string.romm55_remote_close), onClick = dismiss)
        }
    )
}
