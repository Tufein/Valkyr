package com.tufein.valkyr.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.R
import com.tufein.valkyr.data.repository.ConsoleRepository
import com.tufein.valkyr.data.service.GameLaunchService
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.rememberFocusSource
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.GameLaunchKeys
import com.tufein.valkyr.util.PlayTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.tufein.valkyr.data.local.dao.DownloadableFileDao
import com.tufein.valkyr.data.service.LibraryIndexService
import com.tufein.valkyr.ui.screens.tools.ReadinessRoute
import com.tufein.valkyr.util.LibraryKeys

/** One console of the setting: the emulators installed for it and what Play remembers ([GameLaunchKeys]). */
data class ConsoleEmulator(
    val consoleId: String,
    val name: String,
    val targets: List<PlayTarget>,
    val stored: String?,
    /** Readable [stored] (an app chosen on a game page, an emulator no longer installed); null when unknown. */
    val storedLabel: String?
)

@HiltViewModel
class EmulatorSettingsViewModel @Inject constructor(
    private val consoles: ConsoleRepository,
    private val launcher: GameLaunchService,
    private val files: DownloadableFileDao,
    private val library: LibraryIndexService
) : ViewModel() {

    private val _rows = MutableStateFlow<List<ConsoleEmulator>?>(null)

    /** Consoles with a known emulator installed or an app remembered; null while it is worked out. */
    val rows: StateFlow<List<ConsoleEmulator>?> = _rows.asStateFlow()

    /** Reads the consoles and the installed emulators again (the user may have installed one since). */
    fun load() {
        viewModelScope.launch {
            val ids = consoles.getAllConsoles().first().map { it.id }.distinct()
            val targets = launcher.catalogueTargets(ids)
            _rows.value = ids.mapNotNull { id ->
                val stored = launcher.preferred(id)
                val installed = targets[id].orEmpty()
                if (installed.isEmpty() && stored == null) null
                else ConsoleEmulator(id, ConsoleFormatter.getConsoleDisplayName(id).replace('\n', ' '), installed, stored, launcher.labelOf(stored))
            }.sortedBy { it.name.lowercase() }
        }
    }

    fun wizard(consoleId: String, onReady: (String) -> Unit) {
        viewModelScope.launch {
            val rows = files.fileRefsOf(consoleId)
            val file = rows.firstOrNull { LibraryKeys.isOwned(it.consoleId, it.fileName, library.ownedKeys.value) } ?: rows.firstOrNull()
            if (file != null) onReady(ReadinessRoute.of(consoleId, file.fileName))
        }
    }

    /** Stores [key] for [consoleId]; null = ask on the game page. */
    fun set(consoleId: String, key: String?) {
        launcher.setPreferred(consoleId, key)
        load()
    }
}

/**
 * 2.4.0 Settings -> "Emulator per console": what Play starts for each console that has a known
 * emulator installed (or an app remembered from a game page): ask each time, automatic (the
 * catalogue's preferred installed emulator) or one emulator, RetroArch per core.
 */
@Composable
fun EmulatorChoiceSettingsRow(viewModel: EmulatorSettingsViewModel = hiltViewModel(), onWizard: (String) -> Unit = {}) {
    var open by remember { mutableStateOf(false) }
    val show = { viewModel.load(); open = true }
    SettingRow(
        icon = R.drawable.ic_controller,
        title = stringResource(R.string.play24_settings_title),
        hint = stringResource(R.string.play24_settings_hint),
        onClick = show
    ) {
        PillButton(stringResource(R.string.settings_change), show)
    }
    if (open) EmulatorsDialog(viewModel, onWizard = { route -> open = false; onWizard(route) }) { open = false }
}

@Composable
private fun EmulatorsDialog(viewModel: EmulatorSettingsViewModel, onWizard: (String) -> Unit, onDismiss: () -> Unit) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<String?>(null) }
    val list = rows
    val edit = editing?.let { id -> list?.firstOrNull { it.consoleId == id } }
    if (edit != null) {
        ConsoleDialog(edit, onPick = { key -> viewModel.set(edit.consoleId, key); editing = null }, onDismiss = { editing = null }, onWizard = { viewModel.wizard(edit.consoleId, onWizard) })
        return
    }
    val closeFocus = rememberInitialFocus()
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_controller), contentDescription = null, tint = scheme.primary) },
        title = { Text(stringResource(R.string.play24_settings_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                when {
                    list == null -> Text(stringResource(R.string.play24_settings_loading), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    list.isEmpty() -> Text(stringResource(R.string.play24_settings_empty), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    else -> list.forEach { row -> OptionRow(row.name, currentText(row), selected = false, chevron = true) { editing = row.consoleId } }
                }
            }
        },
        confirmButton = { DialogButton(text = stringResource(R.string.dialog_close), onClick = onDismiss, initialFocus = closeFocus) }
    )
}

@Composable
private fun currentText(row: ConsoleEmulator): String = when (row.stored) {
    null -> stringResource(R.string.play24_settings_ask)
    GameLaunchKeys.AUTOMATIC -> stringResource(R.string.play24_settings_automatic)
    else -> {
        val installed = if (GameLaunchKeys.parseCatalogue(row.stored) != null)
            GameLaunchKeys.resolve(row.stored, row.targets, { it.key }, { it.packageName }) != null else row.storedLabel != null
        val label = row.storedLabel ?: row.stored
        if (installed) label else stringResource(R.string.play24_settings_not_installed, label)
    }
}

@Composable
private fun ConsoleDialog(row: ConsoleEmulator, onPick: (String?) -> Unit, onDismiss: () -> Unit, onWizard: () -> Unit) {
    val cancelFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(row.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                OptionRow(stringResource(R.string.ready25_title), stringResource(R.string.ready25_wizard), false, chevron = true, onClick = onWizard)
                OptionRow(stringResource(R.string.play24_settings_ask), stringResource(R.string.play24_settings_ask_hint), row.stored == null) { onPick(null) }
                OptionRow(
                    stringResource(R.string.play24_settings_automatic),
                    row.targets.firstOrNull()?.label?.let { stringResource(R.string.play24_settings_automatic_hint, it) },
                    row.stored == GameLaunchKeys.AUTOMATIC
                ) { onPick(GameLaunchKeys.AUTOMATIC) }
                val resolved = GameLaunchKeys.resolve(row.stored?.takeUnless { it == GameLaunchKeys.AUTOMATIC }, row.targets, { it.key }, { it.packageName })
                row.targets.forEach { target -> OptionRow(target.label, null, resolved?.key == target.key) { onPick(target.key) } }
                // An app picked on a game page that the catalogue does not know stays as it is.
                val other = row.stored?.takeIf { s -> s != GameLaunchKeys.AUTOMATIC && resolved == null }
                if (other != null) OptionRow(currentText(row), null, true) { onDismiss() }
            }
        },
        confirmButton = {},
        dismissButton = { DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss, initialFocus = cancelFocus) }
    )
}

@Composable
private fun OptionRow(title: String, note: String?, selected: Boolean, chevron: Boolean = false, onClick: () -> Unit) {
    val source = rememberFocusSource()
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .focusRing(source, 10.dp)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!note.isNullOrEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        if (selected) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
        if (chevron) Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}
