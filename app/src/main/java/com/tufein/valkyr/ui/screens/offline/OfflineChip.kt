package com.tufein.valkyr.ui.screens.offline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.R
import com.tufein.valkyr.data.service.NetworkMonitor
import com.tufein.valkyr.data.service.OfflineQueueService
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.rememberFocusSource
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.util.OfflineMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class OfflineChipViewModel @Inject constructor(network: NetworkMonitor, queue: OfflineQueueService) : ViewModel() {
    val online: StateFlow<Boolean> = network.online
    val pending: StateFlow<Int> = queue.pending.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
}

/**
 * A small "Offline" chip for Home and Downloads, shown only while there is no connection. Selecting it
 * opens a short list of what still works and what waits (6.0).
 */
@Composable
fun OfflineChip(screen: OfflineMode.Screen, modifier: Modifier = Modifier) {
    val vm: OfflineChipViewModel = hiltViewModel()
    val online by vm.online.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    if (!OfflineMode.chipVisible(online, screen)) return
    val source = rememberFocusSource()
    Pill(
        text = if (pending > 0) stringResource(R.string.man60_offline_chip_waiting, pending) else stringResource(R.string.man60_offline_chip),
        tone = PillTone.Warning, icon = R.drawable.ic_cloud_off,
        modifier = modifier.focusRing(source, cornerRadius = 20.dp).clickable(interactionSource = source, indication = null) { open = true }
    )
    if (open) OfflineDialog(pending) { open = false }
}

@Composable
private fun OfflineDialog(pending: Int, onDismiss: () -> Unit) {
    val focus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.man60_offline_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.man60_offline_works), style = MaterialTheme.typography.titleSmall)
                OfflineMode.worksOffline.forEach { w ->
                    Text("• " + stringResource(when (w) {
                        OfflineMode.Works.PLAY -> R.string.man60_offline_play
                        OfflineMode.Works.LIBRARY -> R.string.man60_offline_library
                        OfflineMode.Works.OFFLINE_COLLECTIONS -> R.string.man60_offline_collections
                        OfflineMode.Works.MANUALS -> R.string.man60_offline_manuals
                    }), style = MaterialTheme.typography.bodyMedium)
                }
                Text(stringResource(R.string.man60_offline_waits_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                Text(stringResource(R.string.man60_offline_waits), style = MaterialTheme.typography.bodyMedium)
                if (pending > 0) Text(stringResource(R.string.man60_offline_pending, pending), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { DialogButton(stringResource(R.string.dialog_close), onDismiss, initialFocus = focus) }
    )
}
