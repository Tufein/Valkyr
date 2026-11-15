package com.tufein.valkyr.ui.screens.tools

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.tufein.valkyr.R
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.repository.DownloadableFileRepository
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.data.service.BiosService
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.requestFocusWhenReady
import com.tufein.valkyr.ui.navigation.NavRoutes
import com.tufein.valkyr.util.ApkAssets
import com.tufein.valkyr.util.BiosCatalog
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.EmulatorCatalog
import com.tufein.valkyr.util.EmulatorCheck
import com.tufein.valkyr.util.EmulatorSetupPlan
import com.tufein.valkyr.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class EmulatorCheckUi(
    val loading: Boolean = true,
    val installed: List<EmulatorCheck.Installed> = emptyList(),
    val coverage: List<EmulatorCheck.Coverage> = emptyList(),
    /** 7.4: the setup steps of each console, by console id. */
    val plans: Map<String, EmulatorSetupPlan.Plan> = emptyMap()
)

@HiltViewModel
class EmulatorCheckViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val files: DownloadableFileRepository,
    private val biosService: BiosService,
    private val appSettings: AppSettings,
    private val settings: SettingsRepository
) : ViewModel() {
    private val _ui = MutableStateFlow(EmulatorCheckUi())
    val ui: StateFlow<EmulatorCheckUi> = _ui


    fun refresh() {
        viewModelScope.launch {
            val ui = withContext(Dispatchers.IO) {
                val packages = EmulatorCatalog.allPackages().filterTo(HashSet()) { ApkAssets.isInstalled(context, it) }
                val consoles = files.getConsolesWithFiles("*").map { it.id }
                // Only names are read and files of the picked BIOS folder are hashed; nothing is written.
                val bios = runCatching { biosService.check(allSystems = true) }.getOrNull()
                val folders = appSettings.saveSyncEmulatorFolders.first()
                val savesSet = settings.saveSyncSavesDir.first().isNotBlank()
                val statesSet = settings.saveSyncStatesDir.first().isNotBlank()
                val autoM3u = appSettings.autoM3u.first()
                EmulatorCheckUi(
                    loading = false,
                    installed = EmulatorCheck.installed(packages) { pkg -> runCatching { context.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull() },
                    coverage = EmulatorCheck.coverages(consoles, packages) { ConsoleFormatter.getConsoleDisplayName(it) },
                    plans = consoles.distinct().associateWith { id ->
                        val system = BiosCatalog.systemsFor(listOf(id)).firstOrNull()
                        EmulatorSetupPlan.plan(
                            EmulatorSetupPlan.Input(
                                id, packages, bios?.results?.firstOrNull { it.system == system }, bios?.folderSet == true,
                                savesSet, statesSet, folders, autoM3u
                            )
                        )
                    }
                )
            }
            _ui.value = ui
        }
    }
}

/**
 * 6.7: Tools → Emulator check. Which known emulators are installed and in which version, and for each
 * console of your library whether something installed can start its games, with what is missing.
 * 7.4: a console opens into its setup steps ([EmulatorSetupPlan]): what is still needed and what each
 * step changes. It only reads which apps are installed; it never installs or downloads anything, and
 * every step opens a screen that asks before it writes.
 */
@Composable
fun EmulatorCheckScreen(navController: NavController, viewModel: EmulatorCheckViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val firstFocus = remember { FocusRequester() }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { firstFocus.requestFocusWhenReady() }
    // Coming back from a step's screen: what was done there shows at once.
    LifecycleResumeEffect(Unit) { viewModel.refresh(); onPauseOrDispose { } }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.emucheck67_title), icon = R.drawable.ic_controller, subtitle = stringResource(R.string.emucheck67_subtitle))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "what") {
                InfoCard(listOf(stringResource(R.string.emucheck67_what), stringResource(R.string.setup74_tap_hint)), modifier = Modifier.focusRequester(firstFocus).focusableCard(), icon = R.drawable.ic_info)
            }
            item(key = "h-installed") { SectionHeader(stringResource(R.string.emucheck67_installed, ui.installed.size), icon = R.drawable.ic_controller) }
            if (!ui.loading && ui.installed.isEmpty()) item(key = "none") {
                InfoCard(listOf(stringResource(R.string.emucheck67_none_installed)), modifier = Modifier.focusableCard(), icon = R.drawable.ic_warning)
            }
            items(ui.installed, key = { "i-" + it.emulator.id }) { item ->
                ToolRow(
                    title = EmulatorCheck.label(item.emulator, item.variant),
                    lines = listOfNotNull(
                        item.version?.let { stringResource(R.string.emucheck67_version, it) },
                        stringResource(R.string.emucheck67_covers, item.emulator.systems.joinToString(", ") { it.label }.ifBlank { "—" })
                    ),
                    onClick = {},
                    icon = R.drawable.ic_controller
                )
            }
            item(key = "h-consoles") { SectionHeader(stringResource(R.string.emucheck67_consoles), subtitle = stringResource(R.string.emucheck67_consoles_hint), icon = R.drawable.ic_collections) }
            items(ui.coverage, key = { "c-" + it.consoleId }) { c ->
                val plan = ui.plans[c.consoleId]
                val expanded = open == c.consoleId && plan != null
                ToolRow(
                    title = ConsoleFormatter.getConsoleDisplayName(c.consoleId),
                    lines = listOf(
                        when (c.status) {
                            EmulatorCheck.Status.STANDALONE, EmulatorCheck.Status.RETROARCH -> stringResource(R.string.emucheck67_can_start, c.installed.joinToString(", "))
                            EmulatorCheck.Status.MISSING -> stringResource(R.string.emucheck67_missing, c.suggestions.joinToString(", "))
                            EmulatorCheck.Status.UNSUPPORTED -> stringResource(R.string.emucheck67_unsupported)
                        }
                    ),
                    onClick = { open = if (open == c.consoleId) null else c.consoleId },
                    chevron = !expanded,
                    badge = {
                        when (c.status) {
                            EmulatorCheck.Status.STANDALONE -> Pill(stringResource(R.string.emucheck67_pill_ready), tone = PillTone.Success, icon = R.drawable.ic_check_circle)
                            EmulatorCheck.Status.RETROARCH -> Pill(stringResource(R.string.emucheck67_pill_retroarch), tone = PillTone.Success, icon = R.drawable.ic_check_circle)
                            EmulatorCheck.Status.MISSING -> Pill(stringResource(R.string.emucheck67_pill_missing), tone = PillTone.Warning, icon = R.drawable.ic_warning)
                            EmulatorCheck.Status.UNSUPPORTED -> Pill(stringResource(R.string.emucheck67_pill_unsupported), tone = PillTone.Warning, icon = R.drawable.ic_info)
                        }
                    },
                    below = if (expanded && plan != null) { { SetupSteps(plan, navController) } } else null
                )
            }
        }
    }
}

/** 7.4: the steps of one console, each with its state, what it changes and the button that opens the right place. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupSteps(plan: EmulatorSetupPlan.Plan, navController: NavController) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            if (plan.ready && plan.open.isEmpty()) stringResource(R.string.setup74_ready) else stringResource(R.string.setup74_left, plan.open.size),
            style = MaterialTheme.typography.titleSmall
        )
        plan.steps.filter { it.state != EmulatorSetupPlan.State.NOT_NEEDED }.forEach { step ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (step.state) {
                    EmulatorSetupPlan.State.DONE -> Pill(stringResource(R.string.setup74_state_done), tone = PillTone.Success, icon = R.drawable.ic_check_circle)
                    EmulatorSetupPlan.State.TODO -> Pill(stringResource(R.string.setup74_state_todo), tone = PillTone.Warning, icon = R.drawable.ic_warning)
                    else -> Pill(stringResource(R.string.setup74_state_check), tone = PillTone.Info, icon = R.drawable.ic_info)
                }
                Text(stepText(step), style = MaterialTheme.typography.bodyMedium)
                writesText(step.writes)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (step.action) {
                        EmulatorSetupPlan.Action.INSTALL -> step.installs.forEach { app ->
                            val query = stringResource(R.string.setup74_search_query, app.label)
                            val none = stringResource(R.string.setup74_no_browser, app.label)
                            ActionPill(stringResource(R.string.setup74_action_find, app.label), { searchWeb(context, query, none) }, icon = R.drawable.ic_search)
                        }
                        EmulatorSetupPlan.Action.OPEN_PLAYLISTS -> ActionPill(stringResource(R.string.setup74_action_playlists), { navController.navigate(NavRoutes.RetroArchPlaylists.route) }, icon = R.drawable.ic_playlist_add)
                        EmulatorSetupPlan.Action.OPEN_BIOS -> ActionPill(stringResource(R.string.setup74_action_bios), { navController.navigate(NavRoutes.Bios.route) }, icon = R.drawable.ic_memory)
                        EmulatorSetupPlan.Action.OPEN_SAVE_SYNC -> ActionPill(stringResource(R.string.setup74_action_saves), { navController.navigate(NavRoutes.SaveSync.route) }, icon = R.drawable.ic_cloud_sync)
                        EmulatorSetupPlan.Action.OPEN_SETTINGS -> ActionPill(stringResource(R.string.setup74_action_settings), { navController.navigate(NavRoutes.Settings.route) }, icon = R.drawable.ic_settings)
                        null -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun stepText(step: EmulatorSetupPlan.Step): String {
    val names = step.args.joinToString(", ")
    return when (step.detail) {
        EmulatorSetupPlan.Detail.EMULATOR_STANDALONE -> stringResource(R.string.setup74_emulator_standalone, names)
        EmulatorSetupPlan.Detail.EMULATOR_RETROARCH -> stringResource(R.string.setup74_emulator_retroarch, names)
        EmulatorSetupPlan.Detail.EMULATOR_MISSING -> stringResource(R.string.setup74_emulator_missing, names)
        EmulatorSetupPlan.Detail.EMULATOR_UNKNOWN -> stringResource(R.string.setup74_emulator_unknown)
        EmulatorSetupPlan.Detail.CORE_NEEDED -> stringResource(R.string.setup74_core, names)
        EmulatorSetupPlan.Detail.BIOS_NO_FOLDER -> stringResource(R.string.setup74_bios_no_folder, names)
        EmulatorSetupPlan.Detail.BIOS_MISSING -> stringResource(R.string.setup74_bios_missing, names)
        EmulatorSetupPlan.Detail.BIOS_OPTIONAL_MISSING -> stringResource(R.string.setup74_bios_optional, names)
        EmulatorSetupPlan.Detail.BIOS_OK -> stringResource(R.string.setup74_bios_ok, names)
        EmulatorSetupPlan.Detail.SAVES_EMULATOR_FOLDER -> stringResource(R.string.setup74_saves_emulator, names)
        EmulatorSetupPlan.Detail.SAVES_RETROARCH_FOLDERS -> stringResource(R.string.setup74_saves_retroarch)
        EmulatorSetupPlan.Detail.SAVES_NOT_SET -> stringResource(R.string.setup74_saves_none)
        EmulatorSetupPlan.Detail.DISCS_AUTO -> stringResource(R.string.setup74_discs_auto)
        EmulatorSetupPlan.Detail.DISCS_OFF -> stringResource(R.string.setup74_discs_off)
        // Steps that are not needed are not shown.
        EmulatorSetupPlan.Detail.CORE_NOT_USED, EmulatorSetupPlan.Detail.BIOS_NONE_NEEDED, EmulatorSetupPlan.Detail.DISCS_NOT_DISC -> ""
    }
}

@Composable
private fun writesText(writes: EmulatorSetupPlan.Writes): String? = when (writes) {
    EmulatorSetupPlan.Writes.NOTHING_HERE -> stringResource(R.string.setup74_writes_nothing_here)
    EmulatorSetupPlan.Writes.IN_RETROARCH -> stringResource(R.string.setup74_writes_retroarch)
    EmulatorSetupPlan.Writes.BIOS_FOLDER_SETTING -> stringResource(R.string.setup74_writes_bios)
    EmulatorSetupPlan.Writes.SAVE_FOLDER_SETTING -> stringResource(R.string.setup74_writes_saves)
    EmulatorSetupPlan.Writes.M3U_SETTING -> stringResource(R.string.setup74_writes_m3u)
    EmulatorSetupPlan.Writes.NOTHING -> null
}

/** Opens a web search for [query] in whatever app answers; nothing is installed from here. */
private fun searchWeb(context: Context, query: String, noneMessage: String) {
    val intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure { ToastUtil.showInfo(context, noneMessage) }
}
