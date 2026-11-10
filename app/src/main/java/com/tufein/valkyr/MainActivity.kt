package com.tufein.valkyr

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.focusable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.tufein.valkyr.ui.screens.game.GamePage
import com.tufein.valkyr.util.GamePageModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import android.net.Uri
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.service.DownloadService
import com.tufein.valkyr.data.service.GameMetadataService
import com.tufein.valkyr.data.state.PendingLibraryFilters
import com.tufein.valkyr.ui.components.LocalBoldFocus
import com.tufein.valkyr.ui.components.TourDialog
import com.tufein.valkyr.ui.components.WhatsNewDialog
import com.tufein.valkyr.util.AppTour
import com.tufein.valkyr.ui.screens.share.ShareTargetDialog
import com.tufein.valkyr.ui.screens.sources.components.ScanReportDialog
import com.tufein.valkyr.ui.screens.tools.BiosScreen
import com.tufein.valkyr.ui.screens.tools.CollectionsScreen
import com.tufein.valkyr.ui.screens.tools.CollectionGoalsScreen
import com.tufein.valkyr.ui.screens.tools.HealthScreen
import com.tufein.valkyr.ui.screens.tools.RecapScreen
import com.tufein.valkyr.ui.screens.tools.FreeSpaceScreen
import com.tufein.valkyr.ui.screens.tools.BestGamesScreen
import com.tufein.valkyr.ui.screens.tools.FrontendMetadataScreen
import com.tufein.valkyr.ui.screens.tools.BetterVersionsScreen
import com.tufein.valkyr.util.PerfLog
import com.tufein.valkyr.util.QuickActions
import com.tufein.valkyr.ui.screens.tools.HistoryScreen
import com.tufein.valkyr.util.HealthFix
import com.tufein.valkyr.data.state.LibraryFilterRequest
import com.tufein.valkyr.ui.screens.tools.DatScreen
import com.tufein.valkyr.ui.screens.tools.ImportListScreen
import com.tufein.valkyr.ui.screens.tools.FileExplorerScreen
import com.tufein.valkyr.ui.screens.tools.FrontendCheckScreen
import com.tufein.valkyr.ui.screens.tools.ProfilesScreen
import com.tufein.valkyr.ui.screens.tools.RetroAchievementsScreen
import com.tufein.valkyr.ui.screens.tools.StatsScreen
import com.tufein.valkyr.ui.screens.tools.SwitchScreen
import com.tufein.valkyr.ui.secondscreen.SecondScreenPresenter
import com.tufein.valkyr.ui.secondscreen.SecondScreenState
import com.tufein.valkyr.util.DeepLinkParser
import com.tufein.valkyr.util.DgmtxFile
import com.tufein.valkyr.util.SharedLinks
import com.tufein.valkyr.util.TextSize
import com.tufein.valkyr.util.ToastUtil
import com.tufein.valkyr.util.WhatsNew
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.tufein.valkyr.ui.common.Gamepad
import com.tufein.valkyr.ui.common.GamepadButton
import com.tufein.valkyr.ui.common.StorageStatusViewModel
import com.tufein.valkyr.ui.components.FreeSpaceText
import com.tufein.valkyr.ui.components.BottomTabs
import com.tufein.valkyr.ui.components.GamepadLegend
import com.tufein.valkyr.ui.components.NoGamepadHint
import com.tufein.valkyr.ui.components.ShellHeader
import com.tufein.valkyr.ui.components.SideRail
import com.tufein.valkyr.ui.screens.more.MoreScreen
import androidx.compose.foundation.layout.Row
import com.tufein.valkyr.ui.components.legendFor
import com.tufein.valkyr.ui.navigation.NavRoutes
import com.tufein.valkyr.ui.navigation.switchToTab
import com.tufein.valkyr.ui.navigation.neighbourTab
import com.tufein.valkyr.ui.navigation.travelDirection
import com.tufein.valkyr.ui.screens.contact.ContactScreen
import com.tufein.valkyr.ui.screens.download.DownloadScreen
import com.tufein.valkyr.ui.screens.download.DownloadViewModel
import com.tufein.valkyr.ui.screens.home.HomeScreen
import com.tufein.valkyr.ui.screens.onboarding.OnboardingScreen
import com.tufein.valkyr.ui.screens.settings.SettingsScreen
import com.tufein.valkyr.ui.screens.settings.SettingsViewModel
import com.tufein.valkyr.ui.screens.settings.romm.RommScreen
import com.tufein.valkyr.ui.screens.sources.SourcesScreen
import com.tufein.valkyr.ui.screens.sources.SourcesViewModel
import com.tufein.valkyr.ui.screens.tools.DuplicatesScreen
import com.tufein.valkyr.ui.screens.tools.LibraryOverviewScreen
import com.tufein.valkyr.ui.screens.tools.SetsScreen
import com.tufein.valkyr.ui.screens.tools.StorageScreen
import com.tufein.valkyr.ui.screens.tools.ToolsHubScreen
import com.tufein.valkyr.ui.screens.tools.WishlistScreen
import com.tufein.valkyr.ui.theme.DogmatixTheme
import com.tufein.valkyr.ui.theme.LocalDogmatixTokens
import com.tufein.valkyr.data.service.SaveSyncService
import com.tufein.valkyr.ui.screens.settings.savesync.SaveSyncScreen
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import com.tufein.valkyr.data.local.LookSettings
import com.tufein.valkyr.ui.theme.LocalReduceMotion
import com.tufein.valkyr.ui.theme.Motion
import com.tufein.valkyr.ui.theme.dogmatixBackground
import com.tufein.valkyr.data.local.TvModeSettings
import com.tufein.valkyr.util.TvModeSetting
import com.tufein.valkyr.util.QuickAction
import com.tufein.valkyr.ui.components.ProvideTvMode
import com.tufein.valkyr.ui.components.LocalTvMode
import com.tufein.valkyr.ui.components.tvSafeArea
import com.tufein.valkyr.ui.navigation.tabRouteFor
import com.tufein.valkyr.ui.components.QuickMenuOverlay
import com.tufein.valkyr.ui.screens.search.SearchAllScreen
import com.tufein.valkyr.ui.screens.cloud.CloudBackupScreen
import com.tufein.valkyr.ui.screens.cloud.CloudScreen
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.ProvideSnappyScrolling
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.util.PadFocus
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import android.util.Log
import android.view.ViewTreeObserver
import com.tufein.valkyr.data.service.PerfNoticeService
import com.tufein.valkyr.data.service.RommRemoteInstallService
import com.tufein.valkyr.data.service.SmartCollectionsService
import com.tufein.valkyr.data.service.StorageAvailabilityService
import com.tufein.valkyr.ui.screens.game.GameHandoffRoute
import com.tufein.valkyr.ui.screens.game.GameHandoffScreen
import com.tufein.valkyr.ui.screens.game.PendingHandoff
import com.tufein.valkyr.ui.screens.next.UpNextScreen
import com.tufein.valkyr.ui.screens.settings.SettingsJump
import com.tufein.valkyr.ui.screens.settings.VersionPreferenceScreen
import com.tufein.valkyr.ui.screens.settings.covers.CoverSourcesScreen
import com.tufein.valkyr.ui.screens.tools.ActionHistoryScreen
import com.tufein.valkyr.ui.screens.tools.FrontendCoversScreen
import com.tufein.valkyr.ui.screens.tools.GameReadinessScreen
import com.tufein.valkyr.ui.screens.tools.GameTransferScreen
import com.tufein.valkyr.ui.screens.tools.LibraryRepairScreen
import com.tufein.valkyr.ui.screens.tools.NewHandheldScreen
import com.tufein.valkyr.ui.screens.tools.PackageTransferScreen
import com.tufein.valkyr.ui.screens.tools.PerfCheckScreen
import com.tufein.valkyr.ui.screens.tools.ReadinessRoute
import com.tufein.valkyr.ui.screens.tools.RecoveryScreen
import com.tufein.valkyr.ui.screens.tools.ReportProblemScreen
import com.tufein.valkyr.ui.screens.tools.RetroArchPlaylistsScreen
import com.tufein.valkyr.ui.shortcut.PendingGameShortcut
import com.tufein.valkyr.util.EmulatorSaveLayouts
import com.tufein.valkyr.util.HandoffLink
import com.tufein.valkyr.util.PerfNotices
import com.tufein.valkyr.util.SettingKeys
import com.tufein.valkyr.util.StorageAvailability
import kotlinx.coroutines.CancellationException

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var smartCollections: SmartCollectionsService
    @Inject lateinit var pendingFilters: PendingLibraryFilters
    @Inject lateinit var saveSyncService: SaveSyncService
    @Inject lateinit var appSettings: AppSettings
    @Inject lateinit var downloadService: DownloadService
    @Inject lateinit var rommRemoteInstall: RommRemoteInstallService
    @Inject lateinit var storageAvailability: StorageAvailabilityService
    @Inject lateinit var metadataService: GameMetadataService
    @Inject lateinit var lookSettings: LookSettings
    @Inject lateinit var tvModeSettings: TvModeSettings
    @Inject lateinit var perfNotices: PerfNoticeService
    private var secondScreen: SecondScreenPresenter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        PerfLog.mark("activity_create")
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        Gamepad.startWatching(this)
        handleDeepLink(intent)
        // While visible, report disconnected folders and park only their interruptible downloads.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                withContext(Dispatchers.IO) {
                    while (true) {
                        try {
                            storageAvailability.recheck().filter {
                                it.location.downloadRoot && !StorageAvailability.writable(it.status)
                            }.forEach { downloadService.holdStorageUnavailable(it.location.uri) }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { Log.w("StorageAvailability", "Storage check could not finish") }
                        kotlinx.coroutines.delay(15_000)
                    }
                }
            }
        }
        // 6.3: while visible, a short hint when the device is hot, short of memory or almost out of room (once per kind per 30 minutes).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    try {
                        perfNotices.check().forEach { kind ->
                            ToastUtil.showInfo(this@MainActivity, getString(when (kind) {
                                PerfNotices.Kind.HOT -> R.string.perf63_notice_hot
                                PerfNotices.Kind.LOW_MEMORY -> R.string.perf63_notice_memory
                                PerfNotices.Kind.LOW_STORAGE -> R.string.perf63_notice_storage
                            }))
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { Log.w("PerfNotices", "Check could not finish") }
                    kotlinx.coroutines.delay(60_000)
                }
            }
        }
        // While visible, installs pushed from the RomM web interface are picked up once a minute (only when paired and switched on).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                withContext(Dispatchers.IO) { rommRemoteInstall.runWhileOpen() }
            }
        }
        // Downloads that were cut off when the app closed join the queue again (once per run).
        lifecycleScope.launch {
            val n = downloadService.requeueInterrupted()
            if (n > 0) ToastUtil.showInfo(this@MainActivity, resources.getQuantityString(R.plurals.downloads_requeued, n, n))
        }
        // A second display (dual-screen handheld, TV) shows the game under the cursor and the downloads.
        lifecycleScope.launch {
            appSettings.secondScreen.collect { on ->
                secondScreen?.stop()
                secondScreen = if (!on) null else SecondScreenPresenter(
                    this@MainActivity, downloadService.downloads
                ) { item -> metadataService.lookup(item.file.name, item.file.consoleId, item.file.fileName) }.also { it.start() }
            }
        }
        // First draw: note the time and let the application build its settings-driven services.
        window.decorView.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                window.decorView.viewTreeObserver.removeOnPreDrawListener(this)
                PerfLog.mark("first_frame")
                (application as? DogmatixApplication)?.startDeferred()
                return true
            }
        })
        setContent {
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            val settings by settingsViewModel.uiState.collectAsStateWithLifecycle()
            val onboardingDone by settingsViewModel.onboardingDone.collectAsStateWithLifecycle()
            // Key handling runs outside the composition, so the pad settings are mirrored there.
            LaunchedEffect(settings.gamepadLayout, settings.swapFaceButtons) {
                Gamepad.layout.value = settings.gamepadLayout
                Gamepad.swapFaceButtons.value = settings.swapFaceButtons
            }
            val boldFocus by appSettings.boldFocus.collectAsStateWithLifecycle(initialValue = false)
            val textSize by appSettings.textSizePercent.collectAsStateWithLifecycle(initialValue = TextSize.DEFAULT)
            val animations by lookSettings.animations.collectAsStateWithLifecycle(initialValue = true)
            val glow by lookSettings.glow.collectAsStateWithLifecycle(initialValue = true)
            val tvSetting by tvModeSettings.mode.collectAsStateWithLifecycle(initialValue = TvModeSetting.AUTO)
            val systemAnimationsOff = remember {
                runCatching { Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
            }
            val density = LocalDensity.current
            DogmatixTheme(themeMode = settings.themeMode, accent = settings.accent, glow = glow) {
                CompositionLocalProvider(
                    LocalBoldFocus provides boldFocus,
                    LocalReduceMotion provides (!animations || systemAnimationsOff),
                    LocalDensity provides Density(density.density, density.fontScale * TextSize.factor(textSize))
                ) {
                    ProvideTvMode(tvSetting) {
                        // 3.9.0: D-pad focus moves scroll lists quickly and keep the next row in sight.
                        ProvideSnappyScrolling {
                        when (onboardingDone) {
                            null -> Unit                      // DataStore not read yet: avoid flashing the wrong screen
                            false -> OnboardingHost()
                            true -> DogmatixApp(pendingFilters)
                        }
                        WhatsNewAfterUpdate(onboardingDone)
                        }
                    }
                }
            }
        }
    }

    /** The highlights of this version, once after an update; a fresh install only records the version. */
    @Composable
    private fun WhatsNewAfterUpdate(onboardingDone: Boolean?) {
        val lastSeen by appSettings.lastSeenVersion.collectAsStateWithLifecycle(initialValue = -1)
        if (onboardingDone == null || lastSeen < 0) return
        val current = BuildConfig.VERSION_CODE
        if (onboardingDone == false) {
            LaunchedEffect(lastSeen) { if (lastSeen != current) appSettings.setLastSeenVersion(current) }
            return
        }
        // 6.0: the tour comes first (once); the notes follow on a later start rather than stacking on it.
        val tourSeen by appSettings.tourSeenVersion.collectAsStateWithLifecycle(initialValue = Int.MAX_VALUE)
        if (AppTour.shouldShow(tourSeen, onboarded = true)) {
            TourDialog { lifecycleScope.launch { appSettings.setTourSeenVersion(AppTour.VERSION) } }
            return
        }
        if (WhatsNew.shouldShow(lastSeen, current, onboarded = true)) {
            WhatsNewDialog(BuildConfig.VERSION_NAME) {
                lifecycleScope.launch { appSettings.setLastSeenVersion(current) }
            }
        }
    }

    /** Opened, or back from a game: bring the saves up to date with RomM (when switched on). */
    override fun onStart() {
        super.onStart()
        saveSyncService.autoSync()
        (application as? DogmatixApplication)?.flushOfflineQueue()
    }

    /** singleTask: a deep link while the app is running arrives here instead of a new instance. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        QuickActions.parse(intent?.action, intent?.getStringExtra(QuickActions.EXTRA_QUICK_ACTION))?.let { quick ->
            pendingFilters.submitQuick(quick)
            intent?.removeExtra(QuickActions.EXTRA_QUICK_ACTION)
            return
        }
        intent?.getStringExtra(PendingLibraryFilters.EXTRA_OPEN_ROUTE)?.let {
            pendingFilters.openSection(it)
            intent.removeExtra(PendingLibraryFilters.EXTRA_OPEN_ROUTE)
        }
        // Shared text (a link from a browser or chat) or a magnet link opened in the app.
        if (intent?.action == Intent.ACTION_SEND) {
            SharedLinks.parse(intent.getStringExtra(Intent.EXTRA_TEXT))?.let(pendingFilters::share)
                ?: ToastUtil.showError(this, getString(R.string.share_no_link))
            return
        }
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.data ?: return
        if (data.scheme.equals("magnet", ignoreCase = true)) {
            SharedLinks.parse(intent.dataString)?.let(pendingFilters::share)
            return
        }
        if (DeepLinkParser.isAppScheme(data.scheme)) {
            // valkyr://handoff?…: a game handed over from another handheld.
            HandoffLink.parse(intent.dataString)?.let {
                PendingHandoff.submit(it)
                return
            }
            DeepLinkParser.parse(intent.dataString)?.let(pendingFilters::submit)
            return
        }
        // A .dgmtx shortcut opened from a frontend (ES-DE…) or a file manager: the deep link is
        // inside the file. content:// URIs arrive with a read grant; file:// may not be readable.
        lifecycleScope.launch {
            val request = withContext(Dispatchers.IO) {
                runCatching { readShortcutFile(data) }.getOrNull()
                    ?.let { DeepLinkParser.parse(DgmtxFile.extractLink(it)) }
            }
            if (request != null) {
                pendingFilters.submit(request)
            } else {
                ToastUtil.showError(this@MainActivity, getString(R.string.dgmtx_invalid))
            }
        }
    }

    /** The file's text, or null when it is unreadable or larger than [DgmtxFile.MAX_BYTES]. */
    private fun readShortcutFile(uri: Uri): String? =
        contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(DgmtxFile.MAX_BYTES + 1)
            var read = 0
            while (read < buffer.size) {
                val n = stream.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            if (read > DgmtxFile.MAX_BYTES) null else String(buffer, 0, read, Charsets.UTF_8)
        }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The face-button swap is applied before anything else sees the key; dialogs and the
        // filter sheet are their own window and do it themselves (Modifier.swapFaceButtons).
        val swapped = Gamepad.remap(event)
        return Gamepad.interceptKey(swapped) || super.dispatchKeyEvent(swapped)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        Gamepad.onGenericMotionEvent(event) || super.onGenericMotionEvent(event)

    override fun onDestroy() {
        secondScreen?.stop()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars() else Gamepad.cancelSelectHold()
    }

    /** Full-screen: status and navigation bars stay hidden; a swipe from the edge shows them briefly. */
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/**
 * Insets the app content keeps clear of: the system bars (zero while immersive) plus the display
 * cutout, so on notched devices nothing sits under the notch in either orientation while the
 * background still paints edge to edge.
 */
@Composable
private fun contentInsets(): WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)

/** First-run tour on the same gradient ground as the shell; import reuses the Sources ViewModel. */
@Composable
private fun OnboardingHost() {
    val sourcesViewModel: SourcesViewModel = hiltViewModel()
    val isRescanning by sourcesViewModel.isRescanning.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalDogmatixTokens.current
    val view = LocalView.current
    SideEffect {
        (view.context as? ComponentActivity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !tokens.isDark
        }
    }
    CompositionLocalProvider(LocalContentColor provides scheme.onBackground) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .dogmatixBackground()
                .windowInsetsPadding(contentInsets())
        ) {
            OnboardingScreen(
                onImportSources = sourcesViewModel::importSources,
                isRescanning = isRescanning
            )
        }
    }
}

/** The screens built from the settings kit: they get the plain AMOLED ground (see SettingsSurface). */
private val SettingsKitRoutes = setOf(NavRoutes.Settings.route, NavRoutes.Romm.route, NavRoutes.SaveSync.route, NavRoutes.VersionPreference.route, NavRoutes.CoverSources.route)

@Composable
private fun DogmatixApp(pendingFilters: PendingLibraryFilters) {
    val context = LocalContext.current
    val sourcesViewModel: SourcesViewModel = hiltViewModel()
    val downloadViewModel: DownloadViewModel = hiltViewModel()
    val rescanErrorMessage by sourcesViewModel.rescanErrorMessage.collectAsStateWithLifecycle()
    // Only the count: the whole list changes with every progress tick and would redraw the app shell each time.
    val activeDownloadCount by downloadViewModel.activeCount.collectAsStateWithLifecycle()
    val gamepadConnected by Gamepad.connected.collectAsStateWithLifecycle()
    val legendOverride by Gamepad.legendOverride.collectAsStateWithLifecycle()
    val storageViewModel: StorageStatusViewModel = hiltViewModel()
    val freeBytes by storageViewModel.freeBytes.collectAsStateWithLifecycle()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(Unit) {
        sourcesViewModel.initializeSources()
    }

    // A link shared to the app: download it or add it as a source.
    val shared by pendingFilters.shared.collectAsStateWithLifecycle()
    shared?.let { link -> ShareTargetDialog(link, onDismiss = pendingFilters::dismissShare) }

    val scanReport by sourcesViewModel.scanReport.collectAsStateWithLifecycle()
    scanReport?.let { failed ->
        ScanReportDialog(
            failures = failed,
            onRetry = sourcesViewModel::retryFailedSources,
            onDismiss = sourcesViewModel::dismissScanReport
        )
    }

    if (rescanErrorMessage != null) {
        AlertDialog(
            modifier = Modifier.closeOnGamepadB { sourcesViewModel.clearRescanError() },
            onDismissRequest = { sourcesViewModel.clearRescanError() },
            title = { Text(stringResource(R.string.scrape_error_title)) },
            text = { Text(rescanErrorMessage ?: "") },
            confirmButton = {
                DialogButton(stringResource(android.R.string.ok), onClick = { sourcesViewModel.clearRescanError() }, initialFocus = rememberInitialFocus())
            }
        )
    }

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: NavRoutes.Home.route

    Gamepad.currentRoute = currentRoute
    SecondScreenState.setRoute(tabRouteFor(currentRoute))   // the second screen shows its downloads dashboard away from the library (a game page counts as library)
    val tvActive = LocalTvMode.current
    SideEffect { Gamepad.tvModeActive = tvActive }
    // A deep link lands on the Library tab; HomeViewModel picks the filters up from the holder.
    val pendingVersion by pendingFilters.version.collectAsStateWithLifecycle()
    LaunchedEffect(pendingVersion) {
        if (pendingVersion > 0 && navController.currentBackStackEntry?.destination?.route != NavRoutes.Home.route) {
            navController.switchTo(NavRoutes.Home)
        }
    }
    // The widget's download line: open that section.
    val openRoute by pendingFilters.openRoute.collectAsStateWithLifecycle()
    LaunchedEffect(openRoute) {
        val route = pendingFilters.consumeSection() ?: return@LaunchedEffect
        NavRoutes.allRoutes.firstOrNull { it.route == route }?.let { navController.switchTo(it) }
    }
    // A hand-off link from another handheld: that game's "Continue here".
    val handoff by PendingHandoff.request.collectAsStateWithLifecycle()
    LaunchedEffect(handoff) {
        val request = PendingHandoff.consume() ?: return@LaunchedEffect
        navController.navigate(GameHandoffRoute.of(request.consoleId, request.fileName, receive = true, code = request.code))
    }
    // 3.5: a game shortcut that could not start its game by itself: that game's page (unless it is already open).
    val gameShortcut by PendingGameShortcut.request.collectAsStateWithLifecycle()
    LaunchedEffect(gameShortcut) {
        val request = PendingGameShortcut.consumeOpen() ?: return@LaunchedEffect
        val top = navController.currentBackStackEntry
        val shown = top?.destination?.route == NavRoutes.Game.route &&
            top.arguments?.getString(GamePageModel.ARG_CONSOLE) == request.consoleId && top.arguments?.getString(GamePageModel.ARG_FILE) == request.fileName
        if (!shown) navController.navigate(GamePageModel.route(request.consoleId, request.fileName))
    }
    // After a gamepad section switch the focus ring goes away: focus is parked on an invisible
    // sink (clearing it would make Compose re-focus the first tab). The next D-pad press puts
    // it back inside the new screen, or on the rail for ◀ (see Gamepad.interceptKey).
    val focusSink = remember { FocusRequester() }
    // 3.9.0: the screen area, so "enter the screen" can be asked of it.
    val contentFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var contentHeightPx by remember { mutableIntStateOf(0) }
    val pageDensity = LocalDensity.current
    DisposableEffect(Unit) {
        Gamepad.shellHosted = true
        onDispose { Gamepad.shellHosted = false; Gamepad.shellFocused = false; Gamepad.contentFocused = false }
    }
    SideEffect { Gamepad.railOnLeft = isLandscape }
    LaunchedEffect(currentRoute) {
        if (Gamepad.pointerHidden.value) {
            withFrameNanos { }
            runCatching { focusSink.requestFocus() }
        }
    }
    LaunchedEffect(Unit) {
        Gamepad.presses.collect { button ->
            val route = navController.currentBackStackEntry?.destination?.route
            when (button) {
                GamepadButton.PREV_TAB, GamepadButton.NEXT_TAB -> {
                    val delta = if (button == GamepadButton.PREV_TAB) -1 else 1
                    Gamepad.pointerHidden.value = true
                    runCatching { focusSink.requestFocus() }
                    navController.switchTo(neighbourTab(route, delta))
                }
                GamepadButton.FOCUS_TAB -> runCatching { Gamepad.sectionFocus.requestFocus() }
                // 3.9.0: the press that brings the cursor back enters the screen: the item it
                // asked for, else its first focusable item, else (nothing focusable) the active tab.
                GamepadButton.FOCUS_CONTENT -> {
                    val entry = route?.let(Gamepad::contentEntry)
                    val entered = (entry != null && runCatching { entry.requestFocus() }.getOrDefault(false)) ||
                        runCatching { contentFocus.requestFocus() }.getOrDefault(false)
                    if (entered || runCatching { Gamepad.sectionFocus.requestFocus() }.getOrDefault(false)) Gamepad.shellFocused = true
                }
                // 3.9.0: L1 / R1 page through the list under the cursor, on screens where they
                // have no job of their own; a page is a run of ▲ / ▼ steps, so lists, grids and
                // lazy rows that are not composed yet all follow.
                GamepadButton.PREV_PANEL, GamepadButton.NEXT_PANEL -> if (PadFocus.shouldersPage(route) && Gamepad.contentFocused) {
                    val forward = button == GamepadButton.NEXT_PANEL
                    val viewportDp = with(pageDensity) { contentHeightPx.toDp().value.toInt() }
                    val direction = if (forward) FocusDirection.Down else FocusDirection.Up
                    for (step in 0 until PadFocus.pageSteps(viewportDp)) {
                        if (!focusManager.moveFocus(direction)) break
                    }
                    // Ran off the screen (into the header or the bottom bar): one step back, so a
                    // page jump stops at the list's edge. Focus events settle by the next frame.
                    withFrameNanos { }
                    if (!Gamepad.contentFocused) focusManager.moveFocus(if (forward) FocusDirection.Up else FocusDirection.Down)
                }
                else -> Unit
            }
        }
    }

    val scheme = MaterialTheme.colorScheme
    val tokens = LocalDogmatixTokens.current
    val view = LocalView.current
    SideEffect {
        (view.context as? ComponentActivity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !tokens.isDark
        }
    }
    CompositionLocalProvider(LocalContentColor provides scheme.onBackground) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            // 8.1: Settings and its sub-screens sit on a plain (AMOLED black in dark) ground.
            .dogmatixBackground(amoled = currentRoute in SettingsKitRoutes)
    ) {
        Box(
            modifier = Modifier
                .size(1.dp)
                .focusRequester(focusSink)
                .focusable()
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(contentInsets())
                .tvSafeArea()
                // 3.9.0: whether anything in the shell holds the focus (see Gamepad.interceptKey).
                .onFocusChanged { Gamepad.shellFocused = it.hasFocus }
        ) {
            // 3.8.0: a slim header in both orientations; the sections sit in a rail on the left in
            // landscape (no vertical space lost) and in the bottom bar upright.
            ShellHeader(
                slim = isLandscape,
                trailing = if (isLandscape) null else ({ FreeSpaceText(freeBytes) }),
                onOpenCloud = { navController.openCloud() }
            )

            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (isLandscape) {
                SideRail(
                    currentRoute = currentRoute,
                    activeDownloads = activeDownloadCount,
                    onSelect = navController::switchTo
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onSizeChanged { contentHeightPx = it.height }
                    .onFocusChanged { Gamepad.contentFocused = it.hasFocus }
                    // No focus group here (it would change where ◀ / ▶ lead): the requester hands
                    // the focus to the first focusable of the screen in composition order.
                    .focusRequester(contentFocus)
            ) {
                val reduceMotion = LocalReduceMotion.current
                NavHost(
                    navController = navController,
                    startDestination = NavRoutes.Home.route,
                    modifier = Modifier.fillMaxSize(),
                    // 5.0: a quick fade with a short slide in the direction of travel (tabs left/right,
                    // screens opened from More from the right). Short, because a fade costs frames
                    // on low-end handhelds; nothing at all with animations off.
                    // 3.9.0: a little quicker (fade 120 ms, slide 200 ms) so a ZL / ZR switch feels direct.
                    enterTransition = {
                        if (reduceMotion) EnterTransition.None
                        else fadeIn(tween(Motion.FAST)) + slideInHorizontally(tween(Motion.MEDIUM)) { full ->
                            travelDirection(initialState.destination.route, targetState.destination.route) * full / 24
                        }
                    },
                    exitTransition = { if (reduceMotion) ExitTransition.None else fadeOut(tween(Motion.FAST)) },
                    popEnterTransition = {
                        if (reduceMotion) EnterTransition.None
                        else fadeIn(tween(Motion.FAST)) + slideInHorizontally(tween(Motion.MEDIUM)) { full ->
                            -travelDirection(targetState.destination.route, initialState.destination.route) * full / 24
                        }
                    },
                    popExitTransition = { if (reduceMotion) ExitTransition.None else fadeOut(tween(Motion.FAST)) }
                ) {
                    composable(NavRoutes.Home.route) {
                        HomeScreen(navController, onOpenGame = { c, f -> navController.navigate(GamePageModel.route(c, f)) })
                    }
                    composable(
                        NavRoutes.Game.route,
                        arguments = listOf(
                            navArgument(GamePageModel.ARG_CONSOLE) { type = NavType.StringType },
                            navArgument(GamePageModel.ARG_FILE) { type = NavType.StringType }
                        )
                    ) { entry ->
                        // Navigation already URL-decodes path arguments; do not decode them again.
                        GamePage(
                            consoleId = entry.arguments?.getString(GamePageModel.ARG_CONSOLE).orEmpty(),
                            fileName = entry.arguments?.getString(GamePageModel.ARG_FILE).orEmpty(),
                            onBack = { navController.popBackStack() },
                            onOpenGame = { c, f -> navController.navigate(GamePageModel.route(c, f)) },
                            onOpenVersionPreference = { navController.navigate(NavRoutes.VersionPreference.route) },
                            onReadiness = { c, f -> navController.navigate(ReadinessRoute.of(c, f)) },
                            onHandoff = { c, f -> navController.navigate(GameHandoffRoute.of(c, f)) }
                        )
                    }
                    composable(
                        GameHandoffRoute.route,
                        arguments = listOf(
                            navArgument("consoleId") { type = NavType.StringType },
                            navArgument("fileName") { type = NavType.StringType },
                            navArgument("mode") { type = NavType.StringType; nullable = true; defaultValue = null },
                            navArgument("code") { type = NavType.StringType; nullable = true; defaultValue = null }
                        )
                    ) { entry ->
                        GameHandoffScreen(
                            consoleId = entry.arguments?.getString("consoleId").orEmpty(),
                            fileName = entry.arguments?.getString("fileName").orEmpty(),
                            receive = entry.arguments?.getString("mode") == GameHandoffRoute.RECEIVE,
                            code = entry.arguments?.getString("code"),
                            onNavigate = { navController.navigate(it) },
                            onSearchLibrary = { c, f ->
                                pendingFilters.submit(LibraryFilterRequest(consoles = setOf(c), query = EmulatorSaveLayouts.cleanTitle(f).ifBlank { f.substringBeforeLast('.') }))
                            }
                        )
                    }
                    composable(ReadinessRoute.route,
                        arguments = listOf(navArgument("consoleId") { type = NavType.StringType }, navArgument("fileName") { type = NavType.StringType })) { entry ->
                        GameReadinessScreen(
                            entry.arguments?.getString("consoleId").orEmpty(), entry.arguments?.getString("fileName").orEmpty(),
                            onNavigate = { navController.navigate(it) })
                    }
                    composable(NavRoutes.VersionPreference.route) { VersionPreferenceScreen() }
                    composable(NavRoutes.CoverSources.route) { CoverSourcesScreen() }
                    composable(NavRoutes.Downloads.route) { DownloadScreen(navController) }
                    composable(NavRoutes.Sources.route) { SourcesScreen() }
                    composable(NavRoutes.Settings.route) { SettingsScreen(navController) }
                    composable(NavRoutes.Contact.route) { ContactScreen(navController) }
                    composable(NavRoutes.Romm.route) { RommScreen() }
                    composable(NavRoutes.SaveSync.route) { SaveSyncScreen() }
                    composable(NavRoutes.Overview.route) { LibraryOverviewScreen() }
                    composable(NavRoutes.Duplicates.route) { DuplicatesScreen() }
                    composable(NavRoutes.Recovery.route) { RecoveryScreen(onNavigate = { navController.navigate(it) }) }
                    composable(NavRoutes.PackageTransfer.route) { PackageTransferScreen(onNavigate = { navController.navigate(it) }) }
                    composable(NavRoutes.GameTransfer.route) { GameTransferScreen() }
                    composable(NavRoutes.ActionHistory.route) {
                        ActionHistoryScreen(
                            onOpenGame = { c, f -> navController.navigate(GamePageModel.route(c, f)) },
                            onNavigate = { navController.navigate(it) })
                    }
                    composable(NavRoutes.UpNext.route) {
                        UpNextScreen(onOpenGame = { c, f -> navController.navigate(GamePageModel.route(c, f)) })
                    }
                    composable(NavRoutes.LibraryRepair.route) { LibraryRepairScreen(onNavigate = { navController.navigate(it) }) }
                    composable(NavRoutes.ReportProblem.route) { ReportProblemScreen() }
                    composable(NavRoutes.PerfCheck.route) { PerfCheckScreen() }
                    composable(NavRoutes.FrontendCovers.route) { FrontendCoversScreen() }
                    composable(NavRoutes.RetroArchPlaylists.route) { RetroArchPlaylistsScreen() }
                    composable(NavRoutes.NewHandheld.route) { NewHandheldScreen(navController) }
                    composable(NavRoutes.Tools.route) { ToolsHubScreen(navController) }
                    composable(NavRoutes.Sets.route) { SetsScreen() }
                    composable(NavRoutes.Storage.route) { StorageScreen() }
                    composable(NavRoutes.Wishlist.route) { WishlistScreen(navController) }
                    composable(NavRoutes.Frontends.route) { FrontendCheckScreen(navController) }
                    composable(NavRoutes.Files.route) { FileExplorerScreen(onNavigate = { navController.navigate(it) }) }
                    composable(NavRoutes.Collections.route) { CollectionsScreen(navController) }
                    composable(NavRoutes.Switch.route) { SwitchScreen() }
                    composable(NavRoutes.Dat.route) { DatScreen(navController) }
                    composable(NavRoutes.ImportList.route) { ImportListScreen() }
                    composable(NavRoutes.Bios.route) { BiosScreen() }
                    composable(NavRoutes.Stats.route) { StatsScreen() }
                    composable(NavRoutes.Profiles.route) { ProfilesScreen() }
                    composable(NavRoutes.RetroAchievements.route) { RetroAchievementsScreen() }
                    composable(NavRoutes.Cloud.route) { CloudScreen(navController) }
                    composable(NavRoutes.CloudBackup.route) { CloudBackupScreen() }
                    composable(NavRoutes.CollectionGoals.route) { CollectionGoalsScreen(onOpenImportList = { navController.navigate(NavRoutes.ImportList.route) }) }
                    composable(NavRoutes.History.route) {
                        HistoryScreen(onOpenGame = { consoleId, fileName ->
                            pendingFilters.submit(LibraryFilterRequest(consoles = setOf(consoleId), query = fileName.substringBeforeLast('.')))
                            navController.switchTo(NavRoutes.Home)
                        })
                    }
                    composable(NavRoutes.Health.route) { HealthScreen(onFix = { fix -> navController.healthFix(fix) }, onRecovery = { navController.navigate(NavRoutes.Recovery.route) }) }
                    composable(NavRoutes.FreeSpace.route) { FreeSpaceScreen(onOpenWishlist = { navController.navigate(NavRoutes.Wishlist.route) }) }
                    composable(NavRoutes.Recap.route) { RecapScreen() }
                    composable(NavRoutes.BestGames.route) {
                        BestGamesScreen(
                            onOpenRetroAchievements = { navController.navigate(NavRoutes.RetroAchievements.route) },
                            onOpenLibrary = { navController.switchTo(NavRoutes.Home) }
                        )
                    }
                    composable(NavRoutes.FrontendMetadata.route) {
                        FrontendMetadataScreen(onOpenSettings = {
                            SettingsJump.request(SettingKeys.ESDE)
                            navController.navigate(NavRoutes.Settings.route)
                        })
                    }
                    composable(NavRoutes.BetterVersions.route) { BetterVersionsScreen(onRecovery = { navController.navigate(NavRoutes.Recovery.route) }) }
                    composable(NavRoutes.SearchAll.route) { SearchAllScreen(navController) }
                    composable(NavRoutes.More.route) {
                        MoreScreen(onOpen = { route -> navController.navigate(route.route) { launchSingleTop = true } })
                    }
                }
            }
            }

            // On a TV the remote is the controller: show the legend there too.
            if (gamepadConnected || LocalTvMode.current) {
                GamepadLegend(entries = legendOverride?.entries ?: legendFor(currentRoute), trailing = if (isLandscape) ({ FreeSpaceText(freeBytes) }) else null)
            } else if (isLandscape) {
                NoGamepadHint(trailing = { FreeSpaceText(freeBytes) })
            }

            if (!isLandscape) {
                BottomTabs(
                    currentRoute = currentRoute,
                    activeDownloads = activeDownloadCount,
                    onSelect = navController::switchTo
                )
            }
        }
        // 8.0: hold SELECT for the quick menu, over every screen.
        val queueHeld by downloadViewModel.held.collectAsStateWithLifecycle()
        QuickMenuOverlay(
            queueHeld = queueHeld,
            onSearch = { pendingFilters.submitQuick(QuickAction.SEARCH) },
            onSearchAll = { navController.navigate(NavRoutes.SearchAll.route) { launchSingleTop = true } },
            onSurprise = { pendingFilters.submitQuick(QuickAction.SURPRISE) },
            onDownloads = { navController.switchTo(NavRoutes.Downloads) },
            onSetQueueHeld = downloadViewModel::setHeld,
            onTools = { navController.navigate(NavRoutes.Tools.route) { launchSingleTop = true } },
            onSettings = { navController.navigate(NavRoutes.Settings.route) { launchSingleTop = true } }
        )
    }
    }
}

/** The Cloud hub on top of whatever is open, so back returns to it. */
private fun NavController.openCloud() {
    if (currentDestination?.route != NavRoutes.Cloud.route) navigate(NavRoutes.Cloud.route) { launchSingleTop = true }
}

/** Where a health row's fix goes; the inline fixes never reach here. */
private fun NavController.healthFix(fix: HealthFix) {
    val route = when (fix) {
        HealthFix.OPEN_SOURCES -> NavRoutes.Sources
        HealthFix.OPEN_BIOS -> NavRoutes.Bios
        HealthFix.OPEN_SAVE_SYNC -> NavRoutes.SaveSync
        HealthFix.OPEN_ROMM -> NavRoutes.Romm
        HealthFix.OPEN_CLOUD_BACKUP -> NavRoutes.CloudBackup
        HealthFix.OPEN_STORAGE -> NavRoutes.Storage
        HealthFix.OPEN_FRONTENDS -> NavRoutes.Frontends
        HealthFix.OPEN_RETROACHIEVEMENTS -> NavRoutes.RetroAchievements
        HealthFix.OPEN_UPDATES -> NavRoutes.Settings
        else -> return
    }
    navigate(route.route) { launchSingleTop = true }
}

private fun NavController.switchTo(route: NavRoutes) = switchToTab(route.route)
