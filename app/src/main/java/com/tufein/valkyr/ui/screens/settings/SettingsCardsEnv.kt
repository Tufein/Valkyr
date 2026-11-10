package com.tufein.valkyr.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.os.LocaleListCompat
import androidx.navigation.NavController
import com.tufein.valkyr.data.model.DebridProvider
import com.tufein.valkyr.ui.common.GamepadLayout
import com.tufein.valkyr.ui.screens.sources.SourcesViewModel
import com.tufein.valkyr.ui.theme.AccentPresets
import com.tufein.valkyr.ui.theme.ThemeMode
import com.tufein.valkyr.util.Constants
import com.tufein.valkyr.util.DownloadPolicy
import com.tufein.valkyr.util.SettingsLayout
import com.tufein.valkyr.util.SettingsMasters
import com.tufein.valkyr.util.ToastUtil
import com.tufein.valkyr.util.TorrentConstants
import java.time.LocalDate
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.tufein.valkyr.util.PowerSettings

/*
 * What the rows of the Settings cards read and call (4.9: split out of SettingsScreen, no change in
 * behaviour). SettingsScreen builds one [SettingsEnv] per composition; each card's rows are in an
 * extension function of it ([toolsCardRows], [lookCardRows], …, in SettingsCards*.kt) and read the
 * state through it, so a row's code is the same as when it was inline in the screen.
 */

/** How faded a visible row is while its master leaves it without effect (see [SettingsLayout.inactiveReason]). */
internal const val INACTIVE_ROW_ALPHA = 0.6f

internal const val SPEED_STEP = 250
internal const val SPEED_MAX = 5000

/** The state the rows show, as [State]s: a row reads them while it composes, as before. */
internal class SettingsStates(
    val ui: State<SettingsUiState>,
    val schedule: State<ScheduleSettings>,
    val queue: State<QueueSettings>,
    val autoScan: State<AutoScanSettings>,
    val afterDownload: State<AfterDownloadSettings>,
    val digestOn: State<Boolean>,
    val metaAuto: State<Boolean>,
    val power: State<PowerSettings?>,
    val pickBest: State<Boolean>,
    val appPrefs: State<AppPrefs>,
    val activeProfileName: State<String?>,
    val updateProgress: State<Float?>,
    val look: State<LookPrefs>
)

/** The activity-result launchers the rows start (folder pickers, backup file pickers, notification permission). */
internal class SettingsLaunchers(
    val downloadDir: ManagedActivityResultLauncher<Uri?, Uri?>,
    val esdeDir: ManagedActivityResultLauncher<Uri?, Uri?>,
    val iisuDir: ManagedActivityResultLauncher<Uri?, Uri?>,
    val backupDir: ManagedActivityResultLauncher<Uri?, Uri?>,
    val retroArchThumbs: ManagedActivityResultLauncher<Uri?, Uri?>,
    val backupExport: ManagedActivityResultLauncher<String, Uri?>,
    val backupImport: ManagedActivityResultLauncher<Array<String>, Uri?>,
    val digestPermission: ManagedActivityResultLauncher<String, Boolean>
)

/** Dialog switches and the app language the rows change; remembered by the screen. */
internal class SettingsLocalState {
    var appLanguage by mutableStateOf(AppLanguage.current())
    var showAccentDialog by mutableStateOf(false)
    var showThemePreviews by mutableStateOf(false)
    var showDownloadPresets by mutableStateOf(false)
    var showLanguagesDialog by mutableStateOf(false)
    var showCocoonHelp by mutableStateOf(false)
    var showDebridKeyDialog by mutableStateOf(false)
}

internal class SettingsEnv(
    val context: Context,
    val navController: NavController,
    val viewModel: SettingsViewModel,
    val extra: ExtraSettingsViewModel,
    private val states: SettingsStates,
    private val launchers: SettingsLaunchers,
    val dialogs: SettingsLocalState,
    private val sourcesViewModel: SourcesViewModel,
    val isLandscape: Boolean,
    val themeLabel: String,
    val debridKeyTitle: String,
    private val backupBusyMessage: String
) {
    val ui: SettingsUiState get() = states.ui.value
    val schedule: ScheduleSettings get() = states.schedule.value
    val queue: QueueSettings get() = states.queue.value
    val autoScan: AutoScanSettings get() = states.autoScan.value
    val afterDownload: AfterDownloadSettings get() = states.afterDownload.value
    val digestOn: Boolean get() = states.digestOn.value
    val metaAuto: Boolean get() = states.metaAuto.value
    val power: PowerSettings get() = states.power.value ?: PowerSettings()
    val pickBest: Boolean get() = states.pickBest.value
    val appPrefs: AppPrefs get() = states.appPrefs.value
    val activeProfileName: String? get() = states.activeProfileName.value
    val updateProgress: Float? get() = states.updateProgress.value
    val look: LookPrefs get() = states.look.value

    val launcher get() = launchers.downloadDir
    val backupDirLauncher get() = launchers.backupDir
    val retroArchThumbsLauncher get() = launchers.retroArchThumbs

    val appLanguage: AppLanguage get() = dialogs.appLanguage
    val limitKb: Int get() = if (ui.limitSpeed == Float.POSITIVE_INFINITY) 0 else ui.limitSpeed.toInt()
    val debrid: DebridProvider get() = ui.debridProvider
    val debridKey: String
        get() = when (debrid) {
            DebridProvider.TORBOX -> ui.torboxApiKey
            DebridProvider.REAL_DEBRID -> ui.realDebridApiKey
            DebridProvider.NONE -> ""
        }

    /** The switches that show or hide other rows (one rule for all of them, see SettingsLayout). */
    val masters: SettingsMasters
        get() = SettingsMasters(
            speedLimitOn = limitKb > 0,
            speedLimitDayOnly = schedule.speedLimitDayOnly,
            lowBatteryOn = power.lowBatteryOn,
            nightOnly = schedule.nightOnly,
            autoScanOn = autoScan.on,
            autoScanNightOnly = autoScan.nightOnly,
            debridOn = debrid != DebridProvider.NONE,
            autoBackupOn = appPrefs.autoBackup
        )

    // Android 13+ asks before the weekly digest may be shown.
    fun setDigest(on: Boolean) {
        extra.setDigest(context, on)
        if (on && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) launchers.digestPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    // First run asks for the ES-DE folder; later runs reuse it and reconfigure directly.
    fun runEsdeSetup() {
        if (ui.esdeDirectory.isBlank()) launchers.esdeDir.launch(null) else viewModel.onConfigureEsde(context)
    }

    fun runIisuSetup() {
        if (ui.iisuDirectory.isBlank()) launchers.iisuDir.launch(null) else viewModel.onConfigureIisu(context)
    }

    fun exportBackup() = launchers.backupExport.launch("valkyr-backup-${LocalDate.now()}.json")

    fun importBackup() {
        // A restore replaces every source: not while they are being scanned (the ViewModel checks again).
        if (sourcesViewModel.isRescanning.value) {
            ToastUtil.showInfo(context, backupBusyMessage)
            return
        }
        launchers.backupImport.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*"))
    }

    fun cycleTheme(delta: Int) {
        val modes = ThemeMode.entries
        viewModel.onThemeModeChanged(context, modes[((ui.themeMode.ordinal + delta) % modes.size + modes.size) % modes.size])
    }
    fun cycleAccent(delta: Int) {
        val presets = AccentPresets.choices
        val current = presets.indexOf(ui.accent).coerceAtLeast(0)
        viewModel.onAccentChanged(context, presets[((current + delta) % presets.size + presets.size) % presets.size])
    }
    fun cycleGamepadLayout(delta: Int) {
        val layouts = GamepadLayout.entries
        viewModel.onGamepadLayoutChanged(context, layouts[((ui.gamepadLayout.ordinal + delta) % layouts.size + layouts.size) % layouts.size])
    }
    fun adjustConcurrent(delta: Int) =
        viewModel.onConcurrentDownloadsChanged(context, (ui.concurrentDownloads + delta).coerceIn(1, 10))
    // Fixed choices instead of free steps: the useful jumps are coarse (50 → 100 → … → no limit).
    fun adjustMaxSearchResults(delta: Int) {
        val choices = Constants.MAX_SEARCH_RESULTS_CHOICES
        val current = choices.indexOf(ui.maxSearchResults).takeIf { it >= 0 }
            ?: choices.indexOf(Constants.DEFAULT_MAX_SEARCH_RESULTS)
        viewModel.onMaxSearchResultsChanged(context, choices[(current + delta).coerceIn(0, choices.lastIndex)])
    }
    fun adjustMetadataTimeout(delta: Int) = viewModel.onMetadataTimeoutChanged(
        context, (ui.metadataTimeoutSeconds + delta * 10).coerceIn(TorrentConstants.MIN_METADATA_TIMEOUT_S, TorrentConstants.MAX_METADATA_TIMEOUT_S)
    )
    fun shiftNightStart(delta: Int) = extra.setNightWindow(context, DownloadPolicy.shift(schedule.nightStart, delta), schedule.nightEnd)
    fun shiftNightEnd(delta: Int) = extra.setNightWindow(context, schedule.nightStart, DownloadPolicy.shift(schedule.nightEnd, delta))
    fun adjustLimit(delta: Int) {
        val next = (limitKb + delta * SPEED_STEP).coerceIn(0, SPEED_MAX)
        viewModel.onLimitSpeedChanged(context, if (next == 0) Float.POSITIVE_INFINITY else next.toFloat())
    }

    // App language: persisted by AppCompat (autoStoreLocales) and applied by recreating the activity.
    fun cycleLanguage(delta: Int) {
        val entries = AppLanguage.entries
        dialogs.appLanguage = entries[(dialogs.appLanguage.ordinal + delta + entries.size) % entries.size]
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(dialogs.appLanguage.tag))
    }

    fun cycleDebrid(delta: Int) {
        val providers = DebridProvider.entries
        viewModel.onDebridProviderChanged(context, providers[((debrid.ordinal + delta) % providers.size + providers.size) % providers.size])
    }

    /** Every row of every card; [SettingsScreen] sorts them by their place in [SettingsLayout]. */
    fun allRows(): List<SettingsRow> =
        toolsCardRows() + lookCardRows() + controlsCardRows() + libraryCardRows() + sourcesCardRows() +
            storageCardRows() + downloadsCardRows() + scheduleCardRows() + afterCardRows() + torrentsCardRows() +
            frontendsCardRows() + cloudCardRows() + backupCardRows() + appCardRows()
}
