package com.tufein.valkyr.ui.screens.tools

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.R
import com.tufein.valkyr.data.local.dao.DownloadableFileDao
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.service.*
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.rememberInitialFocus
import androidx.compose.ui.focus.focusRequester
import com.tufein.valkyr.ui.navigation.NavRoutes
import com.tufein.valkyr.util.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class ReadyRow(val label: Int, val message: Int, val ok: Boolean, val fix: String?)
data class ReadyUi(val loading: Boolean = true, val rows: List<ReadyRow> = emptyList(),
    val games: List<GameLaunch> = emptyList(), val result: Int? = null,
    val gameOverride: String? = null, val consoleDefault: String? = null,
    val packageFiles: Int? = null, val packageComplete: Boolean = false,
    val key: JournalKey? = null, val profileConfiguration: String? = null) {
    /** A path-based emulator may be available for one entry while another needs the SAF fallback. */
    fun effective(game: GameLaunch): GameEmulatorOverrides.Resolution<GameHandler> =
        GameEmulatorOverrides.resolve(gameOverride, consoleDefault, game.handlers, { it.key }, { it.packageName })
}

@HiltViewModel
class GameReadinessViewModel @Inject constructor(
    private val files: DownloadableFileDao,
    private val library: LibraryIndexService,
    private val launcher: GameLaunchService,
    private val readiness: GameReadinessService,
    private val profiles: ProfileService,
    private val settings: AppSettings,
    private val access: JournalGameAccess
) : ViewModel() {
    private val _ui = MutableStateFlow(ReadyUi())
    val ui = _ui.asStateFlow()
    val activeProfile = profiles.activeId
    val visibility = profiles.restrictions
    private var job: Job? = null
    init {
        viewModelScope.launch {
            combine(settings.activeProfile, settings.profiles) { id, restrictions -> id to restrictions }
                .distinctUntilChanged().collect { (id, restrictions) ->
                    val shown = _ui.value
                    if (shown.key != null && (shown.key.profileId != id || shown.profileConfiguration != restrictions)) _ui.value = ReadyUi()
                }
        }
    }
    fun check(console: String, name: String, result: Int? = null) {
        job?.cancel()
        _ui.value = ReadyUi()
        job = viewModelScope.launch {
            val key = JournalKey(settings.activeProfile.first(), console, name)
            val profileConfiguration = settings.profiles.first()
            try {
                settings.withActiveProfile(key.profileId) { access.check(key) }
                val next = withContext(Dispatchers.IO) {
                    val profile = profiles.current()
                    val candidates = files.filesOf(console).filter { it.fileName == name }
                        .filter { profile.allows(console, files.tagsOf(it.id)) }
                    val file = candidates.firstOrNull { library.isOwned(it) } ?: candidates.firstOrNull()
                    if (file == null) {
                        return@withContext ReadyUi(false, listOf(ReadyRow(R.string.ready25_files, R.string.ready25_unavailable, false, null)))
                    }
                    val report = readiness.check(file, verifyPackageHashes = true)
                    ReadyUi(false, listOf(
                        ReadyRow(R.string.ready25_files, if (report.filesOk) R.string.ready25_ok else R.string.ready25_missing, report.filesOk, NavRoutes.Files.route),
                        ReadyRow(R.string.ready25_discs, if (report.discsOk) R.string.ready25_ok else R.string.ready25_disc_problem, report.discsOk, NavRoutes.Sets.route),
                        ReadyRow(R.string.ready25_bios, if (report.biosOk) R.string.ready25_ok else R.string.ready25_bios_problem, report.biosOk, NavRoutes.Bios.route),
                        ReadyRow(R.string.ready25_emulator, if (report.needsExtract) R.string.ready25_extract else if (report.formatUnsupported) R.string.duke55_format_unsupported else if (report.emulatorOk || report.emulatorChoiceNeeded) R.string.ready25_choose else R.string.ready25_no_emulator,
                            report.emulatorOk && !report.needsExtract && !report.formatUnsupported, if (report.needsExtract) NavRoutes.Files.route else NavRoutes.Settings.route)
                    ), report.games, gameOverride = report.gameOverride, consoleDefault = report.consoleDefault,
                        packageFiles = report.packageInspection?.manifest?.parts?.size,
                        packageComplete = report.packageInspection?.complete == true)
                }
                settings.withActiveProfile(key.profileId) {
                    access.check(key)
                    kotlin.check(settings.profiles.first() == profileConfiguration)
                    _ui.value = next.copy(result = result, key = key, profileConfiguration = profileConfiguration)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _ui.value = ReadyUi(false, listOf(ReadyRow(R.string.ready25_files, R.string.ready25_missing, false, NavRoutes.Files.route)), key = key, profileConfiguration = profileConfiguration) }
        }
    }
    fun select(console: String, name: String, key: String, forGame: Boolean) {
        val shown = _ui.value
        val owner = shown.key?.takeIf { it.consoleId == console && it.fileName == name } ?: return
        if (shown.loading || shown.games.none { game -> game.handlers.any { it.key == key } }) return
        act(owner, shown) {
            if (forGame) launcher.setGamePreferred(console, name, key) else launcher.setPreferred(console, key)
        }
    }
    fun useConsole(console: String, name: String) {
        val shown = _ui.value
        val owner = shown.key?.takeIf { it.consoleId == console && it.fileName == name } ?: return
        act(owner, shown) { launcher.setGamePreferred(console, name, null) }
    }
    fun test(context: Context, console: String, game: GameLaunch, handler: GameHandler) {
        val shown = _ui.value
        val owner = shown.key?.takeIf { it.consoleId == console } ?: return
        if (shown.loading || shown.games.none { it == game } || game.handlers.none { it == handler } || shown.rows.any { !it.ok && it.label != R.string.ready25_bios }) return
        viewModelScope.launch {
            try { settings.withActiveProfile(owner.profileId) {
                access.check(owner)
                kotlin.check(_ui.value == shown && settings.profiles.first() == shown.profileConfiguration)
                // The indexed game is revalidated under the same lock as the external launch.
                val outcome = launcher.launch(context, console, game, handler, false, trackPlay = false)
                _ui.update { it.copy(result = when(outcome) {
                    LaunchOutcome.STARTED -> R.string.ready25_opened
                    LaunchOutcome.NEEDS_EXTRACT -> R.string.ready25_extract
                    LaunchOutcome.FAILED -> R.string.ready25_launch_failed
                }) }
            } } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (_ui.value.key == owner) _ui.value = ReadyUi() }
        }
    }
    private fun act(owner: JournalKey, shown: ReadyUi, mutation: () -> Unit) {
        viewModelScope.launch {
            try {
                settings.withActiveProfile(owner.profileId) {
                    access.check(owner)
                    kotlin.check(_ui.value == shown && settings.profiles.first() == shown.profileConfiguration)
                    mutation()
                }
                if (_ui.value.key == owner) check(owner.consoleId, owner.fileName, R.string.ready25_saved)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (_ui.value.key == owner) _ui.value = ReadyUi() }
        }
    }
}

object ReadinessRoute {
    const val route = "game_ready/{consoleId}/{fileName}"
    fun of(console: String, name: String) = "game_ready/" + Uri.encode(console) + "/" + Uri.encode(name)
}

@Composable
fun GameReadinessScreen(console: String, name: String, onNavigate: (String) -> Unit, vm: GameReadinessViewModel = hiltViewModel()) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val first = rememberInitialFocus()
    val active by vm.activeProfile.collectAsStateWithLifecycle()
    val visibility by vm.visibility.collectAsStateWithLifecycle()
    val ui = state.takeIf { it.key == null || it.key == JournalKey(active, console, name) } ?: ReadyUi()
    var rememberForGame by remember(console, name, active) { mutableStateOf(false) }
    LaunchedEffect(console, name, active, visibility) { vm.check(console, name) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        ToolsTitle(stringResource(R.string.ready25_title), icon = R.drawable.ic_controller, subtitle = name)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            item { ActionPill(stringResource(R.string.tools_refresh), { vm.check(console, name) }, modifier = Modifier.focusRequester(first), icon = R.drawable.ic_retry) }
            if (ui.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            ui.packageFiles?.let { count -> item {
                Text(stringResource(if (ui.packageComplete) R.string.pkg28_registered else R.string.pkg28_incomplete, count),
                    style = MaterialTheme.typography.bodySmall)
            } }
            ui.rows.forEach { row -> item {
                ToolRow(stringResource(row.label), listOf(stringResource(row.message)),
                    onClick = { row.fix?.let(onNavigate) }, icon = if (row.ok) R.drawable.ic_check_circle else R.drawable.ic_warning)
            } }
            if (!ui.loading) item {
                Text(stringResource(R.string.ready25_wizard), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.ready25_core_hint), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(rememberForGame, { rememberForGame = it })
                    Text(stringResource(R.string.play26_remember_game), style = MaterialTheme.typography.bodySmall)
                }
                if (!rememberForGame) Text(stringResource(R.string.play_remember), style = MaterialTheme.typography.bodySmall)
                if (ui.gameOverride != null) ActionPill(stringResource(R.string.play26_use_console), { vm.useConsole(console, name) }, icon = R.drawable.ic_settings)
            }
            ui.games.forEach { game -> item {
                ReadinessGameEntry(game, ui.effective(game),
                    enabled = !ui.loading && ui.rows.filter { it.label != R.string.ready25_bios }.all { it.ok },
                    onSelect = { handler -> vm.select(console, name, handler.key, rememberForGame) },
                    onTest = { handler -> vm.test(context, console, game, handler) })
            } }
            ui.result?.let { message -> item { Text(stringResource(message)) } }
        }
    }
}

/** The displayed choice, warning and effective test all come from this entry's one resolution. */
@Composable
internal fun ReadinessGameEntry(
    game: GameLaunch,
    resolution: GameEmulatorOverrides.Resolution<GameHandler>,
    enabled: Boolean,
    onSelect: (GameHandler) -> Unit,
    onTest: (GameHandler) -> Unit
) {
    Text(game.name, style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.play26_effective,
        resolution.handler?.label ?: stringResource(R.string.play24_settings_ask)), style = MaterialTheme.typography.bodySmall)
    if (resolution.missingGameOverride) Text(stringResource(R.string.play26_missing), color = MaterialTheme.colorScheme.error)
    game.handlers.forEach { handler ->
        val effective = resolution.handler?.key == handler.key
        ToolRow(handler.label, emptyList(), onClick = { onSelect(handler) },
            modifier = Modifier.semantics { selected = effective },
            icon = if (effective) R.drawable.ic_check else R.drawable.ic_controller) {
            ActionPill(stringResource(R.string.ready25_test), { onTest(handler) },
                icon = R.drawable.ic_controller, enabled = enabled)
        }
    }
    resolution.handler?.let { handler ->
        ActionPill(stringResource(R.string.play26_test_effective), { onTest(handler) },
            icon = R.drawable.ic_controller, enabled = enabled)
    }
}
