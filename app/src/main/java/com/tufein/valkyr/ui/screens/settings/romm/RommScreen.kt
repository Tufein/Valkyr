package com.tufein.valkyr.ui.screens.settings.romm

import android.content.res.Configuration
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tufein.valkyr.R
import com.tufein.valkyr.data.service.CoverRunState
import com.tufein.valkyr.ui.common.Gamepad
import com.tufein.valkyr.ui.common.GamepadButton
import com.tufein.valkyr.ui.common.Legend
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.LegendEntry
import com.tufein.valkyr.ui.components.MeterBar
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.ScreenTitle
import com.tufein.valkyr.ui.components.Stepper
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.legendFor
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.ui.navigation.NavRoutes
import com.tufein.valkyr.ui.screens.settings.SettingsCardsGrid
import com.tufein.valkyr.ui.screens.settings.SettingsSurface
import com.tufein.valkyr.ui.screens.settings.settingsColumns
import com.tufein.valkyr.ui.screens.settings.PillButton
import com.tufein.valkyr.ui.screens.settings.SettingRow
import com.tufein.valkyr.ui.screens.cloud.sections.RommFavouritesViewModel
import com.tufein.valkyr.ui.screens.cloud.sections.rommFavouritesHint
import com.tufein.valkyr.ui.screens.settings.ThemedSwitch
import com.tufein.valkyr.ui.screens.settings.components.ApiKeyDialog
import com.tufein.valkyr.ui.screens.settings.components.maskedSecret
import com.tufein.valkyr.ui.theme.consoleColor
import com.tufein.valkyr.util.CardGrid
import com.tufein.valkyr.util.CertTrust
import com.tufein.valkyr.util.ConsoleFormatter
import java.text.DateFormat
import java.util.Date

/**
 * RomM server settings: URL, token, auto-upload and one stepper per console to pick the RomM
 * platform it uploads to (◀ ▶ cycles "Not mapped" + the server's platforms; suggestions are
 * pre-filled from the console's folder aliases). Same grid and LB/RB hop as Settings.
 */
@Composable
fun RommScreen(viewModel: RommViewModel = hiltViewModel()) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val platforms by viewModel.platforms.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(ui.url, ui.token, ui.enabled) { if (ui.enabled && ui.url.isNotBlank() && ui.token.isNotBlank()) viewModel.loadPlatforms(null) }

    var showUrlDialog by remember { mutableStateOf(false) }
    var showPairDialog by remember { mutableStateOf(false) }
    val pairing: RommPairingViewModel = hiltViewModel()
    val remoteRows = remoteInstallRows(pairing)
    DevicePairingDialog(pairing)
    if (showPairDialog) PairWithCodeDialog(pairing) { showPairDialog = false }
    var showTokenDialog by remember { mutableStateOf(false) }
    if (showUrlDialog) {
        ApiKeyDialog(
            title = stringResource(R.string.romm_server_url),
            hint = stringResource(R.string.romm_url_dialog_hint),
            value = ui.url,
            label = stringResource(R.string.romm_server_url),
            icon = R.drawable.ic_link,
            onTest = { viewModel.testConnection(context, it, ui.token) },
            onSave = { viewModel.setUrl(context, it) },
            onDismiss = { showUrlDialog = false }
        )
    }
    if (showTokenDialog) {
        ApiKeyDialog(
            title = stringResource(R.string.romm_token),
            hint = stringResource(R.string.romm_token_dialog_hint),
            value = ui.token,
            onTest = { viewModel.testConnection(context, ui.url, it) },
            onSave = { viewModel.setToken(context, it) },
            onDismiss = { showTokenDialog = false }
        )
    }

    val libraryState by viewModel.libraryState.collectAsStateWithLifecycle()
    val coverState by viewModel.coverState.collectAsStateWithLifecycle()
    val trustPrompt by viewModel.trustPrompt.collectAsStateWithLifecycle()
    trustPrompt?.let { prompt ->
        TrustCertificateDialog(prompt, onTrust = { viewModel.confirmTrust(context) }, onDismiss = viewModel::dismissTrustPrompt)
    }

    val notMapped = stringResource(R.string.romm_not_mapped)
    // The server card: address, token, connection and what Dogmatix does with the server.
    val masterRow: @Composable () -> Unit = {
        SettingRow(
            icon = R.drawable.ic_server,
            title = stringResource(R.string.fix54_romm_use),
            hint = stringResource(if (ui.enabled) R.string.fix54_romm_use_on_hint else R.string.fix54_romm_use_off_hint),
            onClick = { viewModel.setEnabled(context, !ui.enabled) },
            onAdjust = { viewModel.setEnabled(context, it > 0) },
        ) { ThemedSwitch(ui.enabled) { viewModel.setEnabled(context, it) } }
    }
    val serverRows: List<@Composable () -> Unit> = if (!ui.enabled) listOf(masterRow) else buildList {
        add(masterRow)
        add {
            SettingRow(
                icon = R.drawable.ic_link,
                title = stringResource(R.string.romm_server_url),
                hint = ui.url.ifBlank { stringResource(R.string.settings_not_set) },
                onClick = { showUrlDialog = true },
            ) {
                ActionPill(stringResource(R.string.settings_change), { showUrlDialog = true }, icon = R.drawable.ic_edit)
            }
        }
        add {
            SettingRow(
                icon = R.drawable.ic_key,
                title = stringResource(R.string.romm_token),
                hint = maskedSecret(ui.token),
                onClick = { showTokenDialog = true },
            ) {
                ActionPill(stringResource(R.string.settings_change), { showTokenDialog = true }, icon = R.drawable.ic_edit)
            }
        }
        add { PairWithCodeRow { showPairDialog = true } }
        add {
            SettingRow(
                icon = R.drawable.ic_network_check,
                title = stringResource(R.string.romm_test_connection),
                hint = if (platforms.isEmpty()) stringResource(R.string.romm_platforms_none) else stringResource(R.string.romm_platforms_count, platforms.size),
                onClick = { viewModel.loadPlatforms(context, announce = true) },
            ) {
                ActionPill(
                    stringResource(if (loading) R.string.romm_testing else R.string.settings_test),
                    { viewModel.loadPlatforms(context, announce = true) },
                    icon = R.drawable.ic_sync,
                    tone = if (platforms.isEmpty() && ui.url.isNotBlank()) ActionTone.Accent else ActionTone.Neutral
                )
            }
        }
        add {
            SettingRow(
                icon = R.drawable.ic_cloud_upload,
                title = stringResource(R.string.romm_auto_upload),
                hint = stringResource(R.string.romm_auto_upload_hint),
                onClick = { viewModel.setAutoUpload(context, !ui.autoUpload) },
                onAdjust = { viewModel.setAutoUpload(context, it > 0) },
            ) {
                ThemedSwitch(ui.autoUpload) { viewModel.setAutoUpload(context, it) }
            }
        }
        add {
            SettingRow(
                icon = R.drawable.ic_cloud_sync,
                title = stringResource(R.string.romm_upload_missing),
                hint = stringResource(R.string.romm_upload_missing_hint),
                onClick = { viewModel.uploadMissing(context) },
            ) { PillButton(stringResource(R.string.romm_upload_missing_action)) { viewModel.uploadMissing(context) } }
        }
        if (ui.url.startsWith("https://", ignoreCase = true)) add {
            val pinned = ui.trustFingerprint.isNotBlank()
            SettingRow(
                icon = if (pinned) R.drawable.ic_verified else R.drawable.ic_lock,
                title = stringResource(R.string.romm_cert),
                hint = if (pinned) stringResource(R.string.romm_cert_trusted_hint, CertTrust.format(ui.trustFingerprint).take(23) + "…") else stringResource(R.string.romm_cert_hint),
                onClick = { if (pinned) viewModel.forgetTrust(context) else viewModel.checkCertificate(context, ui.url) },
            ) {
                ActionPill(
                    stringResource(if (pinned) R.string.romm_cert_forget else R.string.romm_cert_check),
                    { if (pinned) viewModel.forgetTrust(context) else viewModel.checkCertificate(context, ui.url) },
                    tone = if (pinned) ActionTone.Danger else ActionTone.Neutral
                )
            }
        }
        add {
            SettingRow(
                icon = R.drawable.ic_label,
                title = stringResource(R.string.romm_mark_games),
                hint = stringResource(R.string.romm_mark_games_hint),
                onClick = { viewModel.setMarkGames(context, !ui.markGames) },
                onAdjust = { viewModel.setMarkGames(context, it > 0) },
            ) { ThemedSwitch(ui.markGames) { viewModel.setMarkGames(context, it) } }
        }
        if (ui.markGames) add {
            val hint = when {
                libraryState.refreshing -> stringResource(R.string.romm_marks_refreshing)
                libraryState.error != null -> stringResource(R.string.romm_marks_error, libraryState.error ?: "")
                libraryState.updatedAt > 0 -> stringResource(R.string.romm_marks_known, libraryState.games, DateUtils.getRelativeTimeSpanString(libraryState.updatedAt).toString())
                else -> stringResource(R.string.romm_marks_never)
            }
            SettingRow(
                icon = R.drawable.ic_library,
                title = stringResource(R.string.romm_marks),
                hint = hint,
                onClick = viewModel::refreshMarks,
                hintColor = if (libraryState.error != null) MaterialTheme.colorScheme.error else null
            ) {
                ActionPill(stringResource(R.string.romm_marks_refresh), viewModel::refreshMarks, icon = R.drawable.ic_sync)
            }
        }
        remoteRows.forEach { add(it) }
        add {
            val fav: RommFavouritesViewModel = hiltViewModel()
            val favOn by fav.enabled.collectAsStateWithLifecycle()
            val favState by fav.state.collectAsStateWithLifecycle()
            SettingRow(
                icon = R.drawable.ic_star,
                title = stringResource(R.string.romm5_fav_two_way),
                hint = rommFavouritesHint(favOn, favState),
                onClick = { fav.setEnabled(!favOn) },
                onAdjust = { fav.setEnabled(it > 0) },
            ) { ThemedSwitch(favOn) { fav.setEnabled(it) } }
        }
        add {
            val hint = when {
                coverState.running -> stringResource(R.string.romm_covers_running, coverState.done, coverState.total)
                coverState.problem == CoverRunState.Problem.NO_ESDE_FOLDER || (coverState.problem == null && coverState.fetched == null && ui.esdeDirectory.isBlank()) -> stringResource(R.string.romm_covers_needs_esde)
                coverState.problem == CoverRunState.Problem.NO_PLATFORMS -> stringResource(R.string.romm_covers_needs_platforms)
                coverState.problem == CoverRunState.Problem.ESDE_NOT_WRITABLE -> stringResource(R.string.romm_covers_not_writable)
                coverState.problem == CoverRunState.Problem.FAILED -> stringResource(R.string.romm_covers_failed)
                coverState.fetched != null -> pluralStringResource(R.plurals.romm_covers_done, coverState.fetched ?: 0, coverState.fetched ?: 0) + if (coverState.failed > 0) " · " + stringResource(R.string.romm_covers_some_failed, coverState.failed) else ""
                else -> stringResource(R.string.romm_covers_hint)
            }
            SettingRow(
                icon = R.drawable.ic_image,
                title = stringResource(R.string.romm_covers),
                hint = hint,
                onClick = viewModel::startCovers,
                below = if (coverState.running && coverState.total > 0) {
                    { MeterBar(coverState.done.toFloat() / coverState.total.coerceAtLeast(1), modifier = Modifier.padding(top = 6.dp), height = 6.dp) }
                } else null
            ) {
                PillButton(stringResource(if (coverState.running) R.string.romm_covers_busy else R.string.romm_covers_action), viewModel::startCovers)
            }
        }
    }
    // The mapping card: its header (with "Apply suggestions"), then one stepper per console.
    val platformHeader: @Composable () -> Unit = {
        SettingRow(
            icon = R.drawable.ic_tune,
            title = stringResource(R.string.romm_platforms_header),
            hint = stringResource(R.string.romm_platforms_hint),
            onClick = { viewModel.applySuggestions(context) },
        ) {
            ActionPill(stringResource(R.string.romm_apply_suggestions), { viewModel.applySuggestions(context) }, icon = R.drawable.ic_sparkle, tone = ActionTone.Accent)
        }
    }
    val consoleRows: List<@Composable () -> Unit> = buildList {
        if (ui.enabled) ui.consoles.forEach { console -> add {
            val mappedId = ui.platformMap[console.id]
            val mapped = platforms.firstOrNull { it.id == mappedId }
            val suggestion = if (mappedId == null) viewModel.suggestionFor(console.id) else null
            // Stepper positions: 0 = not mapped, 1..n = platforms (in the server's order).
            val index = if (mapped != null) platforms.indexOf(mapped) + 1 else 0
            fun step(delta: Int) {
                if (platforms.isEmpty()) return
                val next = ((index + delta) % (platforms.size + 1) + platforms.size + 1) % (platforms.size + 1)
                viewModel.setPlatform(context, console.id, if (next == 0) null else platforms[next - 1].id)
            }
            val value = when {
                mapped != null -> mapped.label
                mappedId != null -> "#$mappedId"
                suggestion != null -> suggestion.label
                else -> notMapped
            }
            SettingRow(
                icon = R.drawable.ic_controller,
                title = ConsoleFormatter.getConsoleDisplayName(console.id),
                hint = null,
                onClick = { if (mappedId == null && suggestion != null) viewModel.setPlatform(context, console.id, suggestion.id) else step(1) },
                onAdjust = ::step,
                below = {
                    Row(
                        modifier = Modifier.padding(top = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(consoleColor(console.id))
                        )
                        Text(
                            ConsoleFormatter.getConsoleShortName(console.id),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        when {
                            mappedId != null -> Pill(stringResource(R.string.romm_v5_mapped), tone = PillTone.Success, icon = R.drawable.ic_check)
                            suggestion != null -> Pill(stringResource(R.string.romm_v5_suggested), tone = PillTone.Info, icon = R.drawable.ic_sparkle)
                            else -> Pill(notMapped, tone = PillTone.Neutral)
                        }
                    }
                }
            ) {
                Stepper(value, onDecrement = { step(-1) }, onIncrement = { step(1) }, valueWidth = 140.dp)
            }
        } }
    }

    // Two cards on a grid: two columns in landscape. The mapping card keeps its header even
    // before the consoles are known.
    val columns = settingsColumns()
    val twoColumns = columns == 2
    val cells = CardGrid.layout(
        listOf(
            CardGrid.Section(header = false, items = serverRows.size),
            CardGrid.Section(header = true, items = consoleRows.size, keepIfEmpty = ui.enabled)
        ),
        columns
    )
    fun contentOf(cell: CardGrid.Cell): @Composable () -> Unit = when {
        cell.kind == CardGrid.Kind.HEADER -> platformHeader
        cell.section == 0 -> serverRows[cell.item]
        else -> consoleRows[cell.item]
    }
    val currentCells by rememberUpdatedState(cells)

    // LB / RB (landscape): hop between the two columns, staying on the same line of the card.
    val rowFocus = remember(cells.size) { List(cells.size) { FocusRequester() } }
    val currentFocus by rememberUpdatedState(rowFocus)
    var focusedIndex by remember { mutableIntStateOf(-1) }
    LaunchedEffect(twoColumns, cells.size) {
        if (!twoColumns) return@LaunchedEffect
        Gamepad.presses.collect { button ->
            if (button != GamepadButton.PREV_PANEL && button != GamepadButton.NEXT_PANEL) return@collect
            val list = currentCells
            val current = focusedIndex.takeIf { it in list.indices }
            val target = when {
                current == null -> 0                                  // nothing yet: the first row
                list[current].kind == CardGrid.Kind.HEADER -> current // the header: stay
                else -> CardGrid.hop(list, current) ?: current
            }
            runCatching { currentFocus[target.coerceIn(0, currentFocus.lastIndex)].requestFocus() }
        }
    }
    // Land on the first row so the screen is usable from the D-pad without a "wake-up" press.
    LaunchedEffect(cells.size) { if (focusedIndex < 0 && cells.isNotEmpty()) runCatching { rowFocus[0].requestFocus() } }
    if (twoColumns) {
        val base = legendFor(NavRoutes.Romm.route)
        val column = LegendEntry("LB · RB", stringResource(R.string.pad_column))
        val legend = remember(base, column) { Legend(base.toMutableList().also { it.add(it.lastIndex, column) }) }
        LaunchedEffect(legend) { Gamepad.legendOverride.value = legend }
        // Only clear our own legend: the previous screen's onDispose can run after ours is set.
        DisposableEffect(legend) { onDispose { if (Gamepad.legendOverride.value === legend) Gamepad.legendOverride.value = null } }
    }

    SettingsSurface {
        SettingsCardsGrid(
            cells = cells,
            columns = columns,
            title = {
                RommHeader(
                    url = ui.url,
                    loading = loading,
                    platformCount = platforms.size,
                    games = if (ui.markGames) libraryState.games else 0,
                    trusted = ui.trustFingerprint.isNotBlank(),
                    enabled = ui.enabled
                )
            }
        ) { index, cell ->
            Box(
                modifier = Modifier
                    .focusRequester(rowFocus[index])
                    .onFocusChanged { if (it.hasFocus) focusedIndex = index },
                propagateMinConstraints = true
            ) { contentOf(cell)() }
        }
    }
}

/** The screen title with the server's address and its state at a glance. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RommHeader(url: String, loading: Boolean, platformCount: Int, games: Int, trusted: Boolean, enabled: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenTitle(
            text = stringResource(R.string.settings_romm),
            subtitle = url.ifBlank { stringResource(R.string.settings_romm_hint) },
            icon = R.drawable.ic_server
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            when {
                !enabled -> Pill(stringResource(R.string.fix54_romm_off_pill), tone = PillTone.Warning, icon = R.drawable.ic_cloud_off)
                url.isBlank() -> Pill(stringResource(R.string.settings_v5_pill_not_set_up), tone = PillTone.Neutral, icon = R.drawable.ic_cloud_off)
                loading -> Pill(stringResource(R.string.romm_testing), tone = PillTone.Info, icon = R.drawable.ic_sync)
                platformCount > 0 -> {
                    Pill(stringResource(R.string.romm_v5_connected), tone = PillTone.Success, icon = R.drawable.ic_cloud_done)
                    Pill(pluralStringResource(R.plurals.romm_v5_platforms, platformCount, platformCount), tone = PillTone.Neutral, icon = R.drawable.ic_grid)
                }
                else -> Pill(stringResource(R.string.romm_v5_not_connected), tone = PillTone.Warning, icon = R.drawable.ic_cloud_off)
            }
            if (games > 0) Pill(pluralStringResource(R.plurals.romm_v5_games, games, games), tone = PillTone.Info, icon = R.drawable.ic_library)
            if (trusted) Pill(stringResource(R.string.romm_v5_cert_trusted), tone = PillTone.Accent, icon = R.drawable.ic_lock)
        }
    }
}

@Composable
private fun TrustCertificateDialog(prompt: TrustPrompt, onTrust: () -> Unit, onDismiss: () -> Unit) {
    val cert = prompt.certificate
    val until = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(cert.validUntil))
    val cancelFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.romm_cert_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.romm_cert_dialog_message, prompt.url))
                Text(CertTrust.format(cert.fingerprint), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text(stringResource(R.string.romm_cert_dialog_details, cert.subject, until), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { DialogButton(text = stringResource(R.string.romm_cert_trust), onClick = onTrust) },
        dismissButton = { DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss, initialFocus = cancelFocus) }
    )
}
