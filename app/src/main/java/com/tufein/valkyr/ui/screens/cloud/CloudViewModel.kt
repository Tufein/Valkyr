package com.tufein.valkyr.ui.screens.cloud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.model.DebridProvider
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.data.service.CloudBackupService
import com.tufein.valkyr.data.service.CloudStatusService
import com.tufein.valkyr.data.service.DavStatus
import com.tufein.valkyr.data.service.DavStatusService
import com.tufein.valkyr.data.service.DeviceSyncService
import com.tufein.valkyr.data.service.RommServerInfo
import com.tufein.valkyr.data.service.RommServerService
import com.tufein.valkyr.data.service.SaveSyncService
import com.tufein.valkyr.data.service.SaveSyncState
import com.tufein.valkyr.util.CloudStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the Debrid card shows: which service resolves torrent links, and whether it has a key. */
data class DebridCard(val provider: DebridProvider = DebridProvider.NONE, val hasKey: Boolean = false)

/** Whether the save sync has what it needs (RomM plus at least one saves folder), as a flow. */
data class SaveSyncCard(val configured: Boolean = false, val rommConfigured: Boolean = false)

/**
 * The Cloud hub (Settings → Cloud): one place that shows each cloud feature's state and starts its
 * most common action. It only reads what the services already keep; nothing here talks to a server
 * on its own except through [RommServerService.refreshIfStale] when the screen opens.
 */
@HiltViewModel
class CloudViewModel @Inject constructor(
    private val romm: RommServerService,
    private val saveSync: SaveSyncService,
    private val cloudBackup: CloudBackupService,
    private val deviceSync: DeviceSyncService,
    private val progress: com.tufein.valkyr.data.service.GameProgressService,
    private val plays: com.tufein.valkyr.data.service.PlaySessionService,
    settingsRepository: SettingsRepository,
    appSettings: AppSettings,
    cloudStatus: CloudStatusService,
    davStatus: DavStatusService
) : ViewModel() {

    /** The WebDAV cloud: encrypted backup and device sync. */
    val dav: StateFlow<DavStatus> = davStatus.status


    /** 8.3: games whose status or rating was changed differently on two handhelds, waiting for a decision. */
    val syncConflicts: StateFlow<List<com.tufein.valkyr.util.ProgressConflict>> = progress.conflicts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 9.4: what the active profile has played, across the handhelds a sync has named; refreshed whenever a session is saved. */
    val playSummary: StateFlow<com.tufein.valkyr.util.PlaySessions.Summary> = plays.revision
        .map { plays.summary() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.tufein.valkyr.util.PlaySessions.Summary(0L, 0, 0))

    fun resolveConflict(key: String, keepMine: Boolean) {
        viewModelScope.launch { progress.resolve(key, keepMine) }
    }

    val rommInfo: StateFlow<RommServerInfo> = romm.info

    val saveSyncState: StateFlow<SaveSyncState> = saveSync.state

    val overall: StateFlow<CloudStatus> = cloudStatus.status

    val saveSyncCard: StateFlow<SaveSyncCard> = combine(
        settingsRepository.rommUrl,
        settingsRepository.rommToken,
        settingsRepository.saveSyncSavesDir,
        settingsRepository.saveSyncStatesDir,
        appSettings.saveSyncEmulatorFolders
    ) { url, token, saves, states, emulators ->
        val romm = url.isNotBlank() && token.isNotBlank()
        SaveSyncCard(
            configured = romm && (saves.isNotBlank() || states.isNotBlank() || emulators.isNotEmpty()),
            rommConfigured = romm
        )
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SaveSyncCard())

    val debrid: StateFlow<DebridCard> = combine(
        settingsRepository.debridProvider,
        settingsRepository.torboxApiKey,
        settingsRepository.realDebridApiKey
    ) { provider, torbox, realDebrid ->
        DebridCard(
            provider = provider,
            hasKey = when (provider) {
                DebridProvider.TORBOX -> torbox.isNotBlank()
                DebridProvider.REAL_DEBRID -> realDebrid.isNotBlank()
                DebridProvider.NONE -> false
            }
        )
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DebridCard())

    /** The screen opened: ask the server about itself unless that was done a moment ago. */
    fun onOpen() = romm.refreshIfStale()

    fun refreshRomm() = romm.refresh(force = true)

    fun syncSaves() = saveSync.syncNow()

    /** One encrypted backup to the WebDAV server, now. The result lands in [dav] (last backup or error). */
    fun backupNow() {
        viewModelScope.launch { cloudBackup.backupNow() }
    }

    /** One device sync, now. Removals that look like too many wait for the user in the backup screen. */
    fun syncDevices() {
        viewModelScope.launch { deviceSync.syncNow() }
    }
}
