package com.tufein.valkyr.ui.screens.cloud

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.R
import com.tufein.valkyr.data.local.CloudConfig
import com.tufein.valkyr.data.local.CloudRecords
import com.tufein.valkyr.data.local.CloudSettings
import com.tufein.valkyr.data.service.CloudBackupService
import com.tufein.valkyr.data.service.CloudConnection
import com.tufein.valkyr.data.service.CloudMessages
import com.tufein.valkyr.data.service.CloudResult
import com.tufein.valkyr.data.service.DeviceSyncService
import com.tufein.valkyr.data.service.SharedWishlistService
import com.tufein.valkyr.data.service.SourceScanService
import com.tufein.valkyr.data.service.TlsTrust
import com.tufein.valkyr.data.state.RescanStateHolder
import com.tufein.valkyr.util.BackupCrypto
import com.tufein.valkyr.util.CertTrust
import com.tufein.valkyr.util.CloudBackupNames
import com.tufein.valkyr.util.ToastUtil
import com.tufein.valkyr.util.WebDavPaths
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Whether the saved connection settings work, as far as the last test says. */
enum class DavConnectionState { NOT_SET, UNTESTED, OK, FAILED }

/** Everything the cloud backup screen shows about the settings (never the password or passphrase themselves). */
data class CloudBackupUi(
    val server: String = "",
    val user: String = "",
    val hasPassword: Boolean = false,
    val folder: String = WebDavPaths.DEFAULT_FOLDER,
    val trustFingerprint: String = "",
    val hasPassphrase: Boolean = false,
    val autoBackup: Boolean = false,
    val keep: Int = CloudBackupNames.DEFAULT_KEEP,
    val deviceSync: Boolean = false,
    val deviceName: String = "",
    /** 6.0 shared wishlist: the list's name (empty = off) and this person's name (empty = the device name). */
    val sharedList: String = "",
    val sharedName: String = "",
    val records: CloudRecords = CloudRecords(),
    val connection: DavConnectionState = DavConnectionState.NOT_SET
)

/** The list of backups in the cloud. */
/** 9.5: the second cloud as the screen shows it. */
data class MirrorUi(
    val server: String = "",
    val user: String = "",
    val hasPassword: Boolean = false,
    val folder: String = "",
    val records: com.tufein.valkyr.data.local.MirrorRecords = com.tufein.valkyr.data.local.MirrorRecords()
) {
    val configured: Boolean get() = server.isNotBlank()
}

sealed class DavListState {
    object Idle : DavListState()
    object Loading : DavListState()
    data class Loaded(val items: List<CloudBackupNames.Listed>) : DavListState()
    data class Failed(val error: Throwable) : DavListState()
}

/** A server certificate the user is asked to trust. */
data class DavCertPrompt(val serverUrl: String, val fingerprint: String, val subject: String, val validUntil: Long)

/**
 * The state and actions of the cloud backup screen: connection settings, encrypted backup, the list
 * of backups with restore, and device sync. Texts and toasts use the [Context] the screen passes
 * (it carries the in-app language); toasts go to the application context because the screen may be
 * gone when they show.
 */
@HiltViewModel
class CloudBackupViewModel @Inject constructor(
    private val settings: CloudSettings,
    private val connection: CloudConnection,
    private val backup: CloudBackupService,
    private val sync: DeviceSyncService,
    private val shared: SharedWishlistService,
    private val rescanState: RescanStateHolder,
    private val scanService: SourceScanService,
    private val mirrorService: com.tufein.valkyr.data.service.CloudMirrorService,
    private val mirrorSettings: com.tufein.valkyr.data.local.CloudMirrorSettings
) : ViewModel() {

    private data class Basics(val config: CloudConfig, val hasPassphrase: Boolean, val autoBackup: Boolean, val keep: Int)

    private val basics = combine(settings.config, settings.hasPassphrase, settings.autoBackup, settings.keep) { config, passphrase, auto, keep ->
        Basics(config, passphrase, auto, keep)
    }
    private val device = combine(settings.deviceSync, settings.deviceName, settings.records) { sync, name, records -> Triple(sync, name, records) }

    private val sharedSettings = combine(settings.sharedList, settings.sharedName) { list, name -> list to name }

    val ui: StateFlow<CloudBackupUi> = combine(basics, device, sharedSettings) { b, d, sh ->
        val config = b.config
        val records = d.third
        CloudBackupUi(
            server = config.server,
            user = config.user,
            hasPassword = config.password.isNotEmpty(),
            folder = config.folder,
            trustFingerprint = config.trustFingerprint,
            hasPassphrase = b.hasPassphrase,
            autoBackup = b.autoBackup,
            keep = b.keep,
            deviceSync = d.first,
            deviceName = d.second,
            sharedList = sh.first,
            sharedName = sh.second,
            records = records,
            connection = when {
                !config.isConfigured -> DavConnectionState.NOT_SET
                records.lastTestAt == 0L || records.lastTestKey != connection.keyOf(config) -> DavConnectionState.UNTESTED
                records.lastTestError.isEmpty() -> DavConnectionState.OK
                else -> DavConnectionState.FAILED
            }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CloudBackupUi())

    val mirror: StateFlow<MirrorUi> = combine(mirrorService.config, mirrorService.records) { c, r ->
        MirrorUi(c.server, c.user, c.password.isNotEmpty(), c.folder, r)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MirrorUi())

    private val _mirrorSelected = MutableStateFlow(false)
    /** The list of backups shows the second cloud instead of the main one. */
    val mirrorSelected: StateFlow<Boolean> = _mirrorSelected.asStateFlow()

    private val _mirrorTesting = MutableStateFlow(false)
    val mirrorTesting: StateFlow<Boolean> = _mirrorTesting.asStateFlow()

    fun selectSource(mirror: Boolean) {
        if (_mirrorSelected.value == mirror) return
        _mirrorSelected.value = mirror
        _list.value = DavListState.Idle
        refreshList()
    }

    fun setMirrorServer(value: String) = viewModelScope.launch { mirrorSettings.setServer(value) }
    fun setMirrorUser(value: String) = viewModelScope.launch { mirrorSettings.setUser(value) }
    fun setMirrorPassword(value: String) = viewModelScope.launch { mirrorSettings.setPassword(value) }
    fun setMirrorFolder(value: String) = viewModelScope.launch { mirrorSettings.setFolder(value) }
    fun clearMirror() = viewModelScope.launch {
        mirrorSettings.clear()
        if (_mirrorSelected.value) { _mirrorSelected.value = false; _list.value = DavListState.Idle }
    }

    fun testMirror(context: Context) {
        if (_mirrorTesting.value) return
        val app = context.applicationContext
        viewModelScope.launch {
            _mirrorTesting.value = true
            try {
                mirrorService.test()
                    .onSuccess { ToastUtil.showSuccess(app, context.getString(R.string.dav_test_done)) }
                    .onFailure { ToastUtil.showError(app, context.getString(R.string.dav_test_failed, CloudMessages.of(context, it))) }
            } finally {
                _mirrorTesting.value = false
            }
        }
    }

    /** A connection test is running. */
    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    /** A backup, download or restore is running. */
    val backingUp: StateFlow<Boolean> = backup.running.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** A device sync is running. */
    val syncing: StateFlow<Boolean> = sync.running

    /** A shared wishlist sync is running. */
    val sharedSyncing: StateFlow<Boolean> = shared.running

    private val _list = MutableStateFlow<DavListState>(DavListState.Idle)
    val list: StateFlow<DavListState> = _list.asStateFlow()

    /** The backup whose download is running (so its row shows it), or null. */
    private val _reading = MutableStateFlow<String?>(null)
    val reading: StateFlow<String?> = _reading.asStateFlow()

    /** A downloaded and checked backup waiting for the user's confirmation to restore. */
    private val _pendingRestore = MutableStateFlow<CloudBackupService.Prepared?>(null)
    val pendingRestore: StateFlow<CloudBackupService.Prepared?> = _pendingRestore.asStateFlow()

    private val _certPrompt = MutableStateFlow<DavCertPrompt?>(null)
    val certPrompt: StateFlow<DavCertPrompt?> = _certPrompt.asStateFlow()

    // ---- Settings ------------------------------------------------------------------------------

    fun setServer(value: String) = viewModelScope.launch { settings.setServer(value); _list.value = DavListState.Idle }
    fun setUser(value: String) = viewModelScope.launch { settings.setUser(value) }
    fun setPassword(value: String) = viewModelScope.launch { settings.setPassword(value) }
    fun setFolder(value: String) = viewModelScope.launch { settings.setFolder(value); _list.value = DavListState.Idle }
    fun setDeviceName(value: String) = viewModelScope.launch { settings.setDeviceName(value) }
    fun setPassphrase(value: String) = viewModelScope.launch { settings.setPassphrase(value.trim()) }
    fun setSharedName(value: String) = viewModelScope.launch { settings.setSharedName(value) }

    /** Setting a list name (empty = off) also runs the first sync of the shared wishlist right away. */
    fun setSharedList(context: Context, value: String) {
        viewModelScope.launch {
            settings.setSharedList(value)
            if (value.isNotBlank() && settings.configured.first()) syncShared(context, announce = false)
        }
    }

    fun setKeep(value: Int) = viewModelScope.launch { settings.setKeep(value) }

    /** Automatic backup needs a passphrase: without one the switch stays off and the user is told. */
    fun setAutoBackup(context: Context, on: Boolean) {
        viewModelScope.launch {
            if (on && !BackupCrypto.isAcceptable(settings.passphrase.first())) {
                ToastUtil.showInfo(context.applicationContext, context.getString(R.string.dav_auto_needs_passphrase))
                return@launch
            }
            settings.setAutoBackup(on)
        }
    }

    /** Switching device sync on also runs the first sync right away. */
    fun setDeviceSync(context: Context, on: Boolean) {
        viewModelScope.launch {
            settings.setDeviceSync(on)
            if (on && settings.configured.first()) syncNow(context, allowMassRemoval = false, announce = false)
        }
    }

    // ---- Connection ----------------------------------------------------------------------------

    fun testConnection(context: Context) {
        if (_testing.value) return
        val app = context.applicationContext
        viewModelScope.launch {
            _testing.value = true
            try {
                connection.test()
                ToastUtil.showSuccess(app, context.getString(R.string.dav_test_done))
                refreshList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ToastUtil.showError(app, context.getString(R.string.dav_test_failed, CloudMessages.of(context, e)))
            } finally {
                _testing.value = false
            }
        }
    }

    /** Reads the certificate the server shows, to ask whether to trust it (self-signed servers). */
    fun checkCertificate(context: Context) {
        val app = context.applicationContext
        viewModelScope.launch {
            val server = WebDavPaths.normalizeServer(ui.value.server)
            if (server == null || !CertTrust.isHttps(server)) {
                ToastUtil.showInfo(app, context.getString(R.string.dav_cert_none))
                return@launch
            }
            val cert = withContext(Dispatchers.IO) { TlsTrust.probe(WebDavPaths.originOf(server)) }
            when {
                cert == null -> ToastUtil.showError(app, context.getString(R.string.dav_cert_none))
                cert.trustedBySystem -> ToastUtil.showInfo(app, context.getString(R.string.dav_cert_system))
                else -> _certPrompt.value = DavCertPrompt(WebDavPaths.originOf(server), cert.fingerprint, cert.subject, cert.validUntil)
            }
        }
    }

    fun dismissCertPrompt() { _certPrompt.value = null }

    /** Trusts the shown certificate for this server and tests the connection again. */
    fun confirmTrust(context: Context) {
        val prompt = _certPrompt.value ?: return
        _certPrompt.value = null
        viewModelScope.launch {
            settings.setTrustFingerprint(prompt.fingerprint)
            testConnection(context)
        }
    }

    fun forgetTrust() = viewModelScope.launch { settings.setTrustFingerprint("") }

    // ---- Backup --------------------------------------------------------------------------------

    fun backupNow(context: Context) {
        val app = context.applicationContext
        viewModelScope.launch {
            // Not cancelled by leaving the screen half-way through an upload.
            val result = withContext(NonCancellable) { backup.backupNow() }
            when (result) {
                is CloudResult.Ok -> {
                    ToastUtil.showSuccess(app, context.getString(R.string.dav_backup_done, result.value.name))
                    refreshList()
                }
                is CloudResult.Failed -> ToastUtil.showError(app, context.getString(R.string.dav_backup_failed, CloudMessages.of(context, result.error)))
            }
        }
    }

    fun refreshList() {
        if (_list.value is DavListState.Loading) return
        viewModelScope.launch {
            _list.value = DavListState.Loading
            _list.value = when (val result = backup.list(_mirrorSelected.value)) {
                is CloudResult.Ok -> DavListState.Loaded(result.value)
                is CloudResult.Failed -> DavListState.Failed(result.error)
            }
        }
    }

    /** Downloads and decrypts [item]; the confirmation dialog follows when it is a good backup. */
    fun readBackup(context: Context, item: CloudBackupNames.Listed) {
        if (_reading.value != null || refuseWhileScanning(context)) return
        val app = context.applicationContext
        viewModelScope.launch {
            _reading.value = item.name
            try {
                when (val result = backup.prepareRestore(item, _mirrorSelected.value)) {
                    is CloudResult.Ok -> _pendingRestore.value = result.value
                    is CloudResult.Failed -> ToastUtil.showError(app, context.getString(R.string.dav_restore_failed, CloudMessages.of(context, result.error)))
                }
            } finally {
                _reading.value = null
            }
        }
    }

    fun dismissRestore() { _pendingRestore.value = null }

    /**
     * Restores the confirmed backup, like the Settings restore: the sources are replaced, so no scan
     * runs meanwhile and every source is scanned again afterwards. Not cancelled by leaving the screen.
     */
    fun restore(context: Context) {
        val pending = _pendingRestore.value ?: return
        _pendingRestore.value = null
        if (refuseWhileScanning(context)) return
        val app = context.applicationContext
        viewModelScope.launch {
            withContext(NonCancellable) {
                rescanState.setRescanning(true)
                val result = try {
                    backup.restore(pending)
                } finally {
                    rescanState.setRescanning(false)
                }
                when (result) {
                    is CloudResult.Ok -> {
                        val summary = result.value
                        ToastUtil.showSuccess(app, context.getString(R.string.backup_import_done, summary.settings, summary.consoles, summary.favourites, summary.downloads))
                        if (summary.foldersToRepick > 0) {
                            ToastUtil.showInfo(app, context.resources.getQuantityString(R.plurals.backup_import_repick, summary.foldersToRepick, summary.foldersToRepick))
                        }
                        if (summary.skippedSettings > 0) {
                            ToastUtil.showInfo(app, context.resources.getQuantityString(R.plurals.backup_import_skipped_settings, summary.skippedSettings, summary.skippedSettings))
                        }
                        if (summary.downloads > 0) ToastUtil.showInfo(app, context.getString(R.string.backup_import_restart))
                        if (summary.consoles > 0) scanService.scanAll()
                    }
                    is CloudResult.Failed -> ToastUtil.showError(app, context.getString(R.string.dav_restore_failed, CloudMessages.of(context, result.error)))
                }
            }
        }
    }

    private fun refuseWhileScanning(context: Context): Boolean {
        if (!rescanState.isRescanning.value) return false
        ToastUtil.showInfo(context.applicationContext, context.getString(R.string.backup_import_busy))
        return true
    }

    // ---- Device sync ---------------------------------------------------------------------------

    /** The user's "Sync now"; [allowMassRemoval] confirms removals that were held back. */
    fun syncNow(context: Context, allowMassRemoval: Boolean = false, announce: Boolean = true) {
        val app = context.applicationContext
        viewModelScope.launch {
            val result = withContext(NonCancellable) { sync.syncNow(allowMassRemoval) }
            when (result) {
                is DeviceSyncService.Result.Synced -> {
                    if (announce || result.added + result.removed > 0) {
                        ToastUtil.showSuccess(
                            app,
                            if (result.added + result.removed == 0 && !result.sent) context.getString(R.string.dav_sync_nothing)
                            else context.getString(R.string.dav_sync_done, result.added, result.removed)
                        )
                    }
                }
                is DeviceSyncService.Result.HeldBack -> ToastUtil.showInfo(app, context.resources.getQuantityString(R.plurals.dav_sync_held_title, result.removals, result.removals))
                is DeviceSyncService.Result.Failed -> ToastUtil.showError(app, context.getString(R.string.dav_sync_failed, CloudMessages.of(context, result.error)))
                DeviceSyncService.Result.Skipped -> Unit
            }
        }
    }

    // ---- Shared wishlist -----------------------------------------------------------------------

    /** "Sync wishlist": merges the shared family wishlist with this device's wishlist. */
    fun syncShared(context: Context, announce: Boolean = true) {
        val app = context.applicationContext
        viewModelScope.launch {
            val result = withContext(NonCancellable) { shared.syncNow() }
            when (result) {
                is SharedWishlistService.Result.Synced -> {
                    if (announce || result.added + result.removed > 0) {
                        ToastUtil.showSuccess(
                            app,
                            if (result.added + result.removed == 0 && !result.sent) context.getString(R.string.sync6_shared_nothing)
                            else context.getString(R.string.sync6_shared_done, result.added, result.removed)
                        )
                    }
                }
                is SharedWishlistService.Result.Failed -> ToastUtil.showError(app, context.getString(R.string.sync6_shared_failed, CloudMessages.of(context, result.error)))
                is SharedWishlistService.Result.HeldBack, SharedWishlistService.Result.Skipped -> Unit
            }
        }
    }
}
