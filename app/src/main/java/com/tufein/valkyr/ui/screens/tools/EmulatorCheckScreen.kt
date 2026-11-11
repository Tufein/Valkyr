package com.tufein.valkyr.ui.screens.tools

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.R
import com.tufein.valkyr.data.repository.DownloadableFileRepository
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.requestFocusWhenReady
import com.tufein.valkyr.util.ApkAssets
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.EmulatorCatalog
import com.tufein.valkyr.util.EmulatorCheck
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class EmulatorCheckUi(
    val loading: Boolean = true,
    val installed: List<EmulatorCheck.Installed> = emptyList(),
    val coverage: List<EmulatorCheck.Coverage> = emptyList()
)

@HiltViewModel
class EmulatorCheckViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val files: DownloadableFileRepository
) : ViewModel() {
    private val _ui = MutableStateFlow(EmulatorCheckUi())
    val ui: StateFlow<EmulatorCheckUi> = _ui

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val ui = withContext(Dispatchers.IO) {
                val packages = EmulatorCatalog.allPackages().filterTo(HashSet()) { ApkAssets.isInstalled(context, it) }
                val consoles = files.getConsolesWithFiles("*").map { it.id }
                EmulatorCheckUi(
                    loading = false,
                    installed = EmulatorCheck.installed(packages) { pkg -> runCatching { context.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull() },
                    coverage = EmulatorCheck.coverages(consoles, packages) { ConsoleFormatter.getConsoleDisplayName(it) }
                )
            }
            _ui.value = ui
        }
    }
}

/**
 * 6.7: Tools → Emulator check. Which known emulators are installed and in which version, and for each
 * console of your library whether something installed can start its games, with what is missing. It
 * only reads which apps are installed; it never installs or downloads anything.
 */
@Composable
fun EmulatorCheckScreen(viewModel: EmulatorCheckViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocusWhenReady() }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.emucheck67_title), icon = R.drawable.ic_controller, subtitle = stringResource(R.string.emucheck67_subtitle))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "what") {
                InfoCard(listOf(stringResource(R.string.emucheck67_what)), modifier = Modifier.focusRequester(firstFocus).focusableCard(), icon = R.drawable.ic_info)
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
                ToolRow(
                    title = ConsoleFormatter.getConsoleDisplayName(c.consoleId),
                    lines = listOf(
                        when (c.status) {
                            EmulatorCheck.Status.STANDALONE, EmulatorCheck.Status.RETROARCH -> stringResource(R.string.emucheck67_can_start, c.installed.joinToString(", "))
                            EmulatorCheck.Status.MISSING -> stringResource(R.string.emucheck67_missing, c.suggestions.joinToString(", "))
                            EmulatorCheck.Status.UNSUPPORTED -> stringResource(R.string.emucheck67_unsupported)
                        }
                    ),
                    onClick = {},
                    badge = {
                        when (c.status) {
                            EmulatorCheck.Status.STANDALONE -> Pill(stringResource(R.string.emucheck67_pill_ready), tone = PillTone.Success, icon = R.drawable.ic_check_circle)
                            EmulatorCheck.Status.RETROARCH -> Pill(stringResource(R.string.emucheck67_pill_retroarch), tone = PillTone.Success, icon = R.drawable.ic_check_circle)
                            EmulatorCheck.Status.MISSING -> Pill(stringResource(R.string.emucheck67_pill_missing), tone = PillTone.Warning, icon = R.drawable.ic_warning)
                            EmulatorCheck.Status.UNSUPPORTED -> Pill(stringResource(R.string.emucheck67_pill_unsupported), tone = PillTone.Warning, icon = R.drawable.ic_info)
                        }
                    }
                )
            }
        }
    }
}
