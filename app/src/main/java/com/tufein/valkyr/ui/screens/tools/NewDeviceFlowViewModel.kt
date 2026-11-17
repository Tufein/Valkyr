package com.tufein.valkyr.ui.screens.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.data.local.NewDeviceFlowSettings
import com.tufein.valkyr.data.service.DavStatusService
import com.tufein.valkyr.util.NewDeviceFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 9.0: turns the marks ticked off on this handheld and the cloud target's state into the ordered
 * plan. A check that was never run is never reported as done — that rule lives in NewDeviceFlow.
 */
@HiltViewModel
class NewDeviceFlowViewModel @Inject constructor(
    private val settings: NewDeviceFlowSettings,
    dav: DavStatusService
) : ViewModel() {
    val steps: StateFlow<List<NewDeviceFlow.Step>> = combine(settings.marks, dav.status) { marks, status ->
        val configured = status.configured
        NewDeviceFlow.plan(
            NewDeviceFlow.Input(
                backupTargetConfigured = configured,
                // The user confirms the backup while doing the restore step; the app does not guess.
                backupPresent = configured,
                backupPasswordOk = NewDeviceFlow.Kind.RESTORE.marked(marks),
                foldersOk = NewDeviceFlow.Kind.FOLDERS.marked(marks),
                emulatorsOk = NewDeviceFlow.Kind.EMULATORS.marked(marks),
                biosChecked = NewDeviceFlow.Kind.BIOS in marks,
                biosMissing = emptyList(),
                librarySampleOk = NewDeviceFlow.Kind.LIBRARY_SAMPLE.marked(marks)
            )
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun mark(kind: NewDeviceFlow.Kind, done: Boolean) { viewModelScope.launch { settings.setDone(kind, done) } }
    fun startOver() { viewModelScope.launch { settings.clear() } }

    private fun NewDeviceFlow.Kind.marked(marks: Set<NewDeviceFlow.Kind>): Boolean? =
        if (this in marks) true else null
}
