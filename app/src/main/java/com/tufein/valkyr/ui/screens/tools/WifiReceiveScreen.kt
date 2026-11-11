package com.tufein.valkyr.ui.screens.tools

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tufein.valkyr.R
import com.tufein.valkyr.data.service.WifiReceiveService
import com.tufein.valkyr.data.service.WifiReceiveState
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.requestFocusWhenReady
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import javax.inject.Inject

@HiltViewModel
class WifiReceiveViewModel @Inject constructor(private val service: WifiReceiveService) : ViewModel() {
    val state = service.state
    fun start() = service.start()
    fun stop() = service.stop()
}

/**
 * 6.6: Tools → Receive games over Wi-Fi. Off until you switch it on; then it shows the address and
 * the PIN to type into a browser on the same network, lists what arrived and switches itself off.
 */
@Composable
fun WifiReceiveScreen(viewModel: WifiReceiveViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocusWhenReady() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.running) { while (state.running) { now = System.currentTimeMillis(); delay(15_000) } }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.wifi66_title), icon = R.drawable.ic_wifi, subtitle = stringResource(R.string.wifi66_subtitle))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "what") {
                InfoCard(
                    listOf(stringResource(R.string.wifi66_what_title), stringResource(R.string.wifi66_what_body, WifiReceiveIdle, WifiReceiveTotal)),
                    modifier = Modifier.focusRequester(firstFocus).focusableCard(),
                    icon = R.drawable.ic_info
                )
            }
            state.problem?.let { problem ->
                item(key = "problem") {
                    Text(
                        stringResource(when (problem) {
                            WifiReceiveState.Problem.NO_WIFI -> R.string.wifi66_problem_no_wifi
                            WifiReceiveState.Problem.NO_FOLDER -> R.string.wifi66_problem_no_folder
                            WifiReceiveState.Problem.PIN_LOCKED -> R.string.wifi66_problem_pin
                            WifiReceiveState.Problem.TIMEOUT -> R.string.wifi66_problem_timeout
                            WifiReceiveState.Problem.FAILED -> R.string.wifi66_problem_failed
                        }),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
            if (state.running) {
                item(key = "address") {
                    InfoCard(
                        listOf(stringResource(R.string.wifi66_open_title), state.address.orEmpty(), stringResource(R.string.wifi66_pin, state.pin.orEmpty()),
                            stringResource(R.string.wifi66_stops, DateUtils.getRelativeTimeSpanString(state.stopsAt, now, DateUtils.MINUTE_IN_MILLIS).toString())),
                        modifier = Modifier.focusableCard(),
                        icon = R.drawable.ic_wifi
                    )
                }
            }
            item(key = "toggle") {
                ToolsActions {
                    if (state.running) ActionPill(stringResource(R.string.wifi66_stop), { viewModel.stop() }, icon = R.drawable.ic_close, tone = ActionTone.Danger)
                    else ActionPill(stringResource(R.string.wifi66_start), { viewModel.start() }, icon = R.drawable.ic_wifi)
                }
            }
            if (state.received.isNotEmpty()) {
                item(key = "h-received") { SectionHeader(stringResource(R.string.wifi66_received, state.received.size), icon = R.drawable.ic_download) }
                items(state.received.asReversed().take(30), key = { "r-" + it }) { name ->
                    Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                }
            }
        }
    }
}

private val WifiReceiveIdle = com.tufein.valkyr.util.WifiReceive.IDLE_MINUTES
private val WifiReceiveTotal = com.tufein.valkyr.util.WifiReceive.TOTAL_MINUTES
