package com.tufein.valkyr.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.navigation.NavRoutes
import com.tufein.valkyr.util.NewDeviceFlow

const val NEW_DEVICE_FLOW_ROUTE = "new_device_flow"

/** 9.0: one place that says what is left before a wiped handheld plays its first game. */
@Composable
fun NewDeviceFlowScreen(navController: NavController, viewModel: NewDeviceFlowViewModel = hiltViewModel()) {
    val steps by viewModel.steps.collectAsStateWithLifecycle()
    val done = steps.count { it.state == NewDeviceFlow.State.DONE || it.state == NewDeviceFlow.State.SKIPPED }
    val open: (String) -> Unit = { route -> navController.navigate(route) { launchSingleTop = true } }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        item(key = "title") {
            ToolsTitle(stringResource(R.string.v90_flow_title), icon = R.drawable.ic_devices, subtitle = stringResource(R.string.v90_flow_hint))
            InfoCard(listOf(stringResource(R.string.v90_flow_progress, done, steps.size)), icon = R.drawable.ic_info)
        }
        items(steps, key = { it.kind.name }) { step ->
            ToolRow(
                title = stringResource(stepLabel(step.kind)),
                lines = listOf(step.writes, stringResource(R.string.v90_flow_check, step.check)),
                onClick = { routeFor(step.kind)?.let(open) },
                trailing = {
                    if (step.state == NewDeviceFlow.State.DONE) {
                        TextButton(onClick = { viewModel.mark(step.kind, false) }) { Text(stringResource(R.string.v90_flow_undo)) }
                    } else if (step.state == NewDeviceFlow.State.TODO) {
                        TextButton(onClick = { viewModel.mark(step.kind, true) }) { Text(stringResource(R.string.v90_flow_mark)) }
                    } else {
                        Text(stringResource(R.string.v90_flow_skipped), style = MaterialTheme.typography.labelSmall)
                    }
                }
            )
        }
        item(key = "reset") {
            TextButton(onClick = { viewModel.startOver() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.v90_flow_reset))
            }
        }
    }
}

private fun stepLabel(kind: NewDeviceFlow.Kind): Int = when (kind) {
    NewDeviceFlow.Kind.RESTORE -> R.string.v90_step_restore
    NewDeviceFlow.Kind.FOLDERS -> R.string.v90_step_folders
    NewDeviceFlow.Kind.EMULATORS -> R.string.v90_step_emulators
    NewDeviceFlow.Kind.BIOS -> R.string.v90_step_bios
    NewDeviceFlow.Kind.LIBRARY_SAMPLE -> R.string.v90_step_library
    NewDeviceFlow.Kind.FIRST_LAUNCH -> R.string.v90_step_launch
}

private fun routeFor(kind: NewDeviceFlow.Kind): String? = when (kind) {
    NewDeviceFlow.Kind.RESTORE -> NavRoutes.CloudBackup.route
    NewDeviceFlow.Kind.FOLDERS -> NavRoutes.Settings.route
    NewDeviceFlow.Kind.EMULATORS -> NavRoutes.EmulatorCheck.route
    NewDeviceFlow.Kind.BIOS -> NavRoutes.Bios.route
    NewDeviceFlow.Kind.LIBRARY_SAMPLE -> NavRoutes.Sources.route
    NewDeviceFlow.Kind.FIRST_LAUNCH -> NavRoutes.Home.route
}
