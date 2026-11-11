package com.tufein.valkyr.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.R
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.local.LookSettings
import com.tufein.valkyr.data.service.CoverRepository
import com.tufein.valkyr.data.service.AutoBackupScheduler
import com.tufein.valkyr.data.service.DiagnosticsService
import com.tufein.valkyr.data.service.ProfileService
import com.tufein.valkyr.data.service.UpdateInstaller
import com.tufein.valkyr.data.service.VersionCheckerService
import com.tufein.valkyr.ui.common.executeWithToast
import com.tufein.valkyr.util.AutoScanPolicy
import com.tufein.valkyr.util.TextSize
import com.tufein.valkyr.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.tufein.valkyr.data.local.FrontendMetadataSettings
import com.tufein.valkyr.data.local.LibrarySettings
import com.tufein.valkyr.data.local.PowerRuleSettings
import com.tufein.valkyr.data.local.SourcePickSettings
import com.tufein.valkyr.data.local.WeeklyDigestSettings
import com.tufein.valkyr.util.LibraryBrowse
import com.tufein.valkyr.util.PowerSettings

/** When downloads may run, and whether the speed limit is lifted at night. */
data class ScheduleSettings(
    val wifiOnly: Boolean = false,
    val chargingOnly: Boolean = false,
    val nightOnly: Boolean = false,
    val nightStart: Int = 23 * 60,
    val nightEnd: Int = 7 * 60,
    val speedLimitDayOnly: Boolean = false
)

/** How the download queue behaves. */
data class QueueSettings(
    val resume: Boolean = true,
    val requeue: Boolean = true,
    val perServer: Int = 0,
    val autoRetry: Boolean = true,
    val queueSummary: Boolean = true,
    val minFreeGb: Int = 0
)

/** Scanning the sources in the background. */
data class AutoScanSettings(
    val on: Boolean = false,
    val hours: Int = 24,
    val wifiOnly: Boolean = true,
    val charging: Boolean = true,
    val nightOnly: Boolean = true,
    val last: Long = 0L
)

/** What happens after a download: covers for the frontends, playlists and the wishlist. */
data class AfterDownloadSettings(
    val esdeArtwork: Boolean = false,
    val pegasusArtwork: Boolean = false,
    val retroArchThumbnailsDir: String = "",
    val autoM3u: Boolean = true,
    val wishlistAuto: Boolean = false
)

/** 5.0 look: animations, the background glow and covers in the library list. */
data class LookPrefs(
    val animations: Boolean = true,
    val glow: Boolean = true,
    val listCovers: Boolean = true,
    val compactLists: Boolean = false,
    /** 6.0: the view the Library tab opens in (the same value its view switch remembers). */
    val startView: LibraryBrowse.ViewMode = LibraryBrowse.ViewMode.CONSOLES
)

/** Look, backup and update settings. */
data class AppPrefs(
    val preReleases: Boolean = false,
    val boldFocus: Boolean = false,
    val secondScreen: Boolean = true,
    val autoBackup: Boolean = false,
    val autoBackupDir: String = "",
    val autoBackupLast: Long = 0L,
    val textSize: Int = TextSize.DEFAULT
)

@HiltViewModel
class ExtraSettingsViewModel @Inject constructor(
    private val autoBackupScheduler: AutoBackupScheduler,
    private val settings: SettingsRepository,
    private val appSettings: AppSettings,
    private val updateInstaller: UpdateInstaller,
    private val versionChecker: VersionCheckerService,
    private val diagnostics: DiagnosticsService,
    private val profiles: ProfileService,
    private val lookSettings: LookSettings,
    private val librarySettings: LibrarySettings,
    private val covers: CoverRepository,
    private val weeklyDigest: WeeklyDigestSettings,
    private val frontendMetadata: FrontendMetadataSettings,
    private val powerRules: PowerRuleSettings,
    private val sourcePick: SourcePickSettings
) : ViewModel() {

    /** 7.5: battery and heat rules for downloads, and picking the best source. */
    val power: StateFlow<PowerSettings?> = powerRules.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val pickBest: StateFlow<Boolean> = sourcePick.pickBest.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    fun setLowBattery(context: Context, on: Boolean) = executeWithToast(context, TAG) { powerRules.setLowBatteryOn(on) }
    fun shiftBatteryPercent(context: Context, delta: Int) = executeWithToast(context, TAG) { powerRules.shiftBatteryPercent(delta) }
    fun setHeat(context: Context, on: Boolean) = executeWithToast(context, TAG) { powerRules.setHeatOn(on) }
    fun setPickBest(context: Context, on: Boolean) = executeWithToast(context, TAG) { sourcePick.setPickBest(on) }

    /** 7.0: the weekly digest notification and writing descriptions after every download. */
    val digestOn: StateFlow<Boolean> = weeklyDigest.enabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val metaAuto: StateFlow<Boolean> = frontendMetadata.autoWrite.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    fun setDigest(context: Context, on: Boolean) = executeWithToast(context, TAG) { weeklyDigest.setEnabled(on) }
    fun setMetaAuto(context: Context, on: Boolean) = executeWithToast(context, TAG) { frontendMetadata.setAutoWrite(on) }

    val look: StateFlow<LookPrefs> = combine(lookSettings.animations, lookSettings.glow, lookSettings.listCovers, lookSettings.compactLists, librarySettings.view) { animations, glow, listCovers, compact, startView ->
        LookPrefs(animations, glow, listCovers, compact, startView)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LookPrefs())

    fun setAnimations(context: Context, on: Boolean) = executeWithToast(context, TAG) { lookSettings.setAnimations(on) }
    fun setGlow(context: Context, on: Boolean) = executeWithToast(context, TAG) { lookSettings.setGlow(on) }
    fun setCompactLists(context: Context, on: Boolean) = executeWithToast(context, TAG) { lookSettings.setCompactLists(on) }

    fun setStartView(context: Context, mode: LibraryBrowse.ViewMode) = executeWithToast(context, TAG) { librarySettings.setView(mode) }

    fun setListCovers(context: Context, on: Boolean) = executeWithToast(context, TAG) { lookSettings.setListCovers(on) }

    private val _coversReset = MutableStateFlow<Int?>(null)
    /** How many games without a cover the last "find missing covers again" sent back to the lookup. */
    val coversReset: StateFlow<Int?> = _coversReset

    /** Forgets the covers that were not found, so they are looked up again; says how many. */
    fun findMissingCovers(context: Context) {
        val app = context.applicationContext
        val texts = context.resources
        viewModelScope.launch {
            val count = runCatching { covers.retryMisses() }.getOrNull()
            if (count == null) {
                ToastUtil.showError(app, texts.getString(R.string.settings_v5_covers_retry_failed))
            } else {
                _coversReset.value = count
                ToastUtil.showSuccess(app, texts.getQuantityString(R.plurals.settings_v5_covers_reset_done, count, count))
            }
        }
    }

    /** Name of the active profile, or null when everything is shown. */
    val activeProfileName: StateFlow<String?> = combine(profiles.profiles, profiles.activeId) { list, id -> list.firstOrNull { it.id == id }?.name }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)


    val schedule: StateFlow<ScheduleSettings> = combine(
        combine(settings.downloadWifiOnly, settings.downloadChargingOnly, settings.downloadNightOnly) { w, c, n -> Triple(w, c, n) },
        settings.downloadNightStart, settings.downloadNightEnd, appSettings.speedLimitDayOnly
    ) { (wifi, charging, night), start, end, dayOnly -> ScheduleSettings(wifi, charging, night, start, end, dayOnly) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ScheduleSettings())

    val queue: StateFlow<QueueSettings> = combine(
        combine(appSettings.resumeDownloads, appSettings.requeueAfterRestart, appSettings.perServerLimit) { r, q, p -> Triple(r, q, p) },
        appSettings.autoRetryFailed, appSettings.queueSummary, appSettings.minFreeGb
    ) { (resume, requeue, perServer), retry, summary, minFree -> QueueSettings(resume, requeue, perServer, retry, summary, minFree) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), QueueSettings())

    val autoScan: StateFlow<AutoScanSettings> = combine(
        combine(appSettings.autoScan, appSettings.autoScanHours) { on, hours -> on to hours },
        appSettings.autoScanWifiOnly, appSettings.autoScanCharging, appSettings.autoScanNightOnly, appSettings.autoScanLast
    ) { (on, hours), wifi, charging, night, last -> AutoScanSettings(on, hours, wifi, charging, night, last) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AutoScanSettings())

    val afterDownload: StateFlow<AfterDownloadSettings> = combine(
        appSettings.esdeArtwork, appSettings.pegasusArtwork, appSettings.retroArchThumbnailsDir, appSettings.autoM3u, appSettings.wishlistAutoDownload
    ) { esde, pegasus, retroArch, m3u, wish -> AfterDownloadSettings(esde, pegasus, retroArch, m3u, wish) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AfterDownloadSettings())

    val appPrefs: StateFlow<AppPrefs> = combine(
        combine(settings.updatePreReleases, appSettings.boldFocus, appSettings.secondScreen) { pre, bold, second -> Triple(pre, bold, second) },
        combine(appSettings.autoBackup, appSettings.autoBackupDir) { backup, dir -> backup to dir },
        appSettings.autoBackupLast, appSettings.textSizePercent
    ) { (pre, bold, second), (backup, dir), last, textSize -> AppPrefs(pre, bold, second, backup, dir, last, textSize) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPrefs())

    fun setPegasusArtwork(context: Context, on: Boolean) = executeWithToast(context, TAG) { appSettings.setPegasusArtwork(on) }
    fun setRetroArchThumbnailsDir(context: Context, uri: String) = executeWithToast(context, TAG) { appSettings.setRetroArchThumbnailsDir(uri) }

    fun setResume(context: Context, on: Boolean) = executeWithToast(context, TAG) { appSettings.setResumeDownloads(on) }
    fun setRequeue(context: Context, on: Boolean) = executeWithToast(context, TAG) { appSettings.setRequeueAfterRestart(on) }
    fun shiftPerServer(context: Context, delta: Int) = executeWithToast(context, TAG) {
        val choices = listOf(0, 1, 2, 3, 4, 6)
        val i = choices.indexOf(queue.value.perServer).takeIf { it >= 0 } ?: 0
        appSettings.setPerServerLimit(choices[(i + delta).coerceIn(0, choices.lastIndex)])
    }
    fun setEsdeArtwork(context: Context, on: Boolean) = executeWithToast(context, TAG) { appSettings.setEsdeArtwork(on) }
    fun setWishlistAuto(context: Context, on: Boolean) = executeWithToast(context, TAG) { appSettings.setWishlistAutoDownload(on) }
    fun setAutoM3u(context: Context, on: Boolean) = executeWithToast(context, TAG) { appSettings.setAutoM3u(on) }
    fun setAutoRetry(context: Context, on: Boolean) = executeWithToast(context, TAG) { appSettings.setAutoRetryFailed(on) }
    fun setQueueSummary(context: Context, on: Boolean) = executeWithToast(context, TAG) { appSettings.setQueueSummary(on) }

    fun shiftMinFree(context: Context, delta: Int) = executeWithToast(context, TAG) {
        val choices = listOf(0, 1, 2, 5, 10, 20, 50)
        val i = choices.indexOf(queue.value.minFreeGb).takeIf { it >= 0 } ?: 0
        appSettings.setMinFreeGb(choices[(i + delta).coerceIn(0, choices.lastIndex)])
    }
    fun setAutoBackup(context: Context, on: Boolean) = executeWithToast(context, TAG) { appSettings.setAutoBackup(on) }
    fun setAutoBackupDir(context: Context, uri: String) = executeWithToast(context, TAG) { appSettings.setAutoBackupDir(uri) }
    fun setSecondScreen(context: Context, on: Boolean) = executeWithToast(context, TAG) { appSettings.setSecondScreen(on) }

    /** Writes an automatic backup right away (to check the folder works). */
    fun backupNow(context: Context) {
        val app = context.applicationContext
        viewModelScope.launch {
            val name = runCatching { autoBackupScheduler.runIfDue(force = true) }.getOrNull()
            if (name != null) ToastUtil.showSuccess(app, app.getString(R.string.auto_backup_done, name))
            else ToastUtil.showError(app, app.getString(R.string.auto_backup_failed))
        }
    }

    fun setAutoScan(context: Context, on: Boolean) = executeWithToast(context, TAG) { appSettings.setAutoScan(on) }
    fun setAutoScanWifi(context: Context, on: Boolean) = executeWithToast(context, TAG) { appSettings.setAutoScanWifiOnly(on) }
    fun setAutoScanCharging(context: Context, on: Boolean) = executeWithToast(context, TAG) { appSettings.setAutoScanCharging(on) }
    fun setAutoScanNight(context: Context, on: Boolean) = executeWithToast(context, TAG) { appSettings.setAutoScanNightOnly(on) }
    fun shiftAutoScanHours(context: Context, delta: Int) = executeWithToast(context, TAG) {
        val choices = AutoScanPolicy.INTERVALS
        val i = choices.indexOf(autoScan.value.hours).takeIf { it >= 0 } ?: 1
        appSettings.setAutoScanHours(choices[(i + delta).coerceIn(0, choices.lastIndex)])
    }
    fun setSpeedLimitDayOnly(context: Context, on: Boolean) = executeWithToast(context, TAG) { appSettings.setSpeedLimitDayOnly(on) }
    fun shiftTextSize(context: Context, delta: Int) = executeWithToast(context, TAG) {
        appSettings.setTextSizePercent(TextSize.shift(appPrefs.value.textSize, delta))
    }
    fun setBoldFocus(context: Context, on: Boolean) = executeWithToast(context, TAG) { appSettings.setBoldFocus(on) }

    /** Progress of an update download (0..1), or null when none runs. */
    val updateProgress: StateFlow<Float?> = updateInstaller.progress

    /** The newer release found by the last check, offered for installation; null when none. */
    private val _offer = MutableStateFlow<String?>(null)
    val updateOffer: StateFlow<String?> = _offer

    fun dismissUpdateOffer() { _offer.value = null }

    /** Downloads the release APK, checks it and hands it to the system installer. */
    fun installUpdate(context: Context) {
        val tag = _offer.value ?: return
        _offer.value = null
        val app = context.applicationContext
        viewModelScope.launch {
            val error = updateInstaller.downloadAndInstall(tag)
            if (error != null) ToastUtil.showError(app, app.getString(R.string.update_install_failed, error))
        }
    }

    fun setWifiOnly(context: Context, on: Boolean) = executeWithToast(context, TAG) { settings.setDownloadWifiOnly(on) }
    fun setChargingOnly(context: Context, on: Boolean) = executeWithToast(context, TAG) { settings.setDownloadChargingOnly(on) }
    fun setNightOnly(context: Context, on: Boolean) = executeWithToast(context, TAG) { settings.setDownloadNightOnly(on) }
    fun setNightWindow(context: Context, start: Int, end: Int) = executeWithToast(context, TAG) { settings.setDownloadNightWindow(start, end) }
    fun setPreReleases(context: Context, on: Boolean) = executeWithToast(context, TAG) { settings.setUpdatePreReleases(on) }

    /** Looks at the releases now and says what it found. */
    fun checkForUpdates(context: Context) {
        val app = context.applicationContext
        viewModelScope.launch {
            when (val result = versionChecker.check(app)) {
                is VersionCheckerService.Result.Available -> _offer.value = result.tag
                is VersionCheckerService.Result.UpToDate -> ToastUtil.showSuccess(app, app.getString(R.string.update_none, result.tag))
                VersionCheckerService.Result.Failed -> ToastUtil.showError(app, app.getString(R.string.update_failed))
            }
        }
    }

    /** Builds the diagnostics report and opens the share sheet. */
    fun shareDiagnostics(context: Context) {
        val app = context.applicationContext
        val title = context.getString(R.string.diagnostics_share_title)
        viewModelScope.launch {
            runCatching { diagnostics.share(title) }.onFailure { ToastUtil.showError(app, app.getString(R.string.diagnostics_failed)) }
        }
    }

    private companion object { const val TAG = "ExtraSettingsViewModel" }
}
