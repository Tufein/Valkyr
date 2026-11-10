package com.tufein.valkyr.ui.screens.settings.romm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.R
import com.tufein.valkyr.data.local.RommDeviceSettings
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.data.service.RommClient
import com.tufein.valkyr.data.service.RommRemoteInstallService
import com.tufein.valkyr.util.RommPairing
import com.tufein.valkyr.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/** 5.6.0: what the "Pair with code" dialog says after an attempt. */
enum class CodeProblem { NOT_COMPLETE, NO_SERVER, UNKNOWN_CODE, TOO_MANY, OWNER_DISABLED, OFFLINE }

/** What the remote install rows show. */
data class RemoteInstallUi(
    val enabled: Boolean = false,
    val paired: Boolean = false,
    val lastPollAt: Long = 0,
    val lastQueued: Int = 0,
    val problem: RommRemoteInstallService.PollProblem? = null
)

/**
 * Settings → RomM, 5.6.0: pairing by a short code (the old token field stays) and the remote install switch
 * with its device pairing. The work is in [RommClient.exchangePairCode] and [RommRemoteInstallService].
 */
@HiltViewModel
class RommPairingViewModel @Inject constructor(
    private val client: RommClient,
    private val settingsRepository: SettingsRepository,
    private val remote: RommRemoteInstallService,
    private val deviceSettings: RommDeviceSettings
) : ViewModel() {

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _problem = MutableStateFlow<CodeProblem?>(null)
    val problem: StateFlow<CodeProblem?> = _problem.asStateFlow()

    fun clearProblem() { _problem.value = null }

    /** Trades [code] for a token and saves it as the RomM token; [onPaired] runs on success (the dialog closes). */
    fun pairWithCode(context: Context, code: String, onPaired: () -> Unit) {
        if (_busy.value) return
        if (!RommPairing.isComplete(code)) { _problem.value = CodeProblem.NOT_COMPLETE; return }
        val app = context.applicationContext
        _busy.value = true
        _problem.value = null
        viewModelScope.launch {
            val result = try {
                if (client.configuredBaseUrl().isEmpty()) null else client.exchangePairCode(code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RommPairing.Exchange.Failed(0)
            }
            when (result) {
                null -> _problem.value = CodeProblem.NO_SERVER
                is RommPairing.Exchange.Paired -> {
                    settingsRepository.setRommToken(result.token)
                    ToastUtil.showSuccess(app, app.getString(R.string.romm55_pair_done))
                    onPaired()
                }
                RommPairing.Exchange.UnknownCode -> _problem.value = CodeProblem.UNKNOWN_CODE
                RommPairing.Exchange.TooManyTries -> _problem.value = CodeProblem.TOO_MANY
                RommPairing.Exchange.OwnerDisabled -> _problem.value = CodeProblem.OWNER_DISABLED
                is RommPairing.Exchange.Failed -> _problem.value = CodeProblem.OFFLINE
            }
            _busy.value = false
        }
    }

    // ---- remote install ----------------------------------------------------------------------

    val remoteUi: StateFlow<RemoteInstallUi> = combine(deviceSettings.enabled, deviceSettings.pairing, remote.summary) { on, pairing, summary ->
        RemoteInstallUi(on && pairing.paired, pairing.paired, summary?.at ?: 0, summary?.queued ?: 0, summary?.problem)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RemoteInstallUi())

    val pairState: StateFlow<RommRemoteInstallService.PairState> = remote.pairState

    /** Switch on: pairs first when this handheld is not paired yet; off: stops polling (the pairing is kept). */
    fun setRemoteEnabled(on: Boolean) {
        viewModelScope.launch {
            if (!on) { deviceSettings.setEnabled(false); return@launch }
            if (remoteUi.value.paired) deviceSettings.setEnabled(true) else remote.startPairing()
        }
    }

    fun startDevicePairing() = remote.startPairing()
    fun cancelDevicePairing() = remote.cancelPairing()
    fun unpairDevice() { viewModelScope.launch { remote.unpair() } }

    /** A poll right now (the row's button). */
    fun checkNow() { viewModelScope.launch { remote.poll() } }
}
