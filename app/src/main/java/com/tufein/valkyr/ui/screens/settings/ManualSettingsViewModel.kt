package com.tufein.valkyr.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.data.local.ManualSettings
import com.tufein.valkyr.data.service.ManualService
import com.tufein.valkyr.util.ManualSource
import com.tufein.valkyr.util.Manuals
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ManualSettingsUi(
    val withGame: Boolean = false,
    val capMb: Int = Manuals.DEFAULT_CAP_MB,
    val usedBytes: Long = 0L,
    val sources: Set<ManualSource> = emptySet()
)

/** The manual settings: sources, "fetch with the game", the space manuals use and "remove all". */
@HiltViewModel
class ManualSettingsViewModel @Inject constructor(
    private val settings: ManualSettings,
    private val service: ManualService
) : ViewModel() {

    val ui: StateFlow<ManualSettingsUi> = combine(settings.withGame, settings.capMb, settings.sources, service.changes) { w, c, s, _ ->
        ManualSettingsUi(w, c, service.usedBytes(), s)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ManualSettingsUi())

    fun setWithGame(on: Boolean) { viewModelScope.launch { settings.setWithGame(on) } }
    fun setSource(source: ManualSource, on: Boolean) { viewModelScope.launch { settings.setSource(source, on) } }
    fun stepCap(delta: Int) { viewModelScope.launch { settings.setCapMb(Manuals.stepCapMb(ui.value.capMb, delta)) } }
    fun removeAll() { viewModelScope.launch { service.deleteAll() } }
}
