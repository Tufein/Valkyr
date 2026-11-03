package com.tufein.valkyr.ui.screens.settings

import android.content.Intent
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.tufein.valkyr.R
import com.tufein.valkyr.data.model.DebridProvider
import com.tufein.valkyr.ui.common.Gamepad
import com.tufein.valkyr.ui.common.GamepadButton
import com.tufein.valkyr.ui.common.Legend
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.LegendEntry
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.legendFor
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.ui.navigation.NavRoutes
import com.tufein.valkyr.ui.screens.settings.components.ApiKeyDialog
import com.tufein.valkyr.ui.screens.settings.components.DaijishoSetupDialog
import com.tufein.valkyr.ui.screens.settings.components.FavoriteLanguagesDialog
import com.tufein.valkyr.ui.screens.sources.SourcesViewModel
import com.tufein.valkyr.ui.screens.sources.components.ConfirmDialog
import com.tufein.valkyr.ui.theme.LocalDogmatixTokens
import com.tufein.valkyr.ui.theme.LocalReduceMotion
import com.tufein.valkyr.ui.theme.Motion
import com.tufein.valkyr.ui.theme.ThemeMode
import com.tufein.valkyr.util.CardGrid
import com.tufein.valkyr.util.SettingsGroup
import com.tufein.valkyr.util.SettingsLayout
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** Languages offered in Settings; [tag] is empty for "follow the device". */
enum class AppLanguage(val tag: String, val label: Int) {
    SYSTEM("", R.string.language_system),
    EN("en", R.string.language_en),
    ES("es", R.string.language_es),
    NL("nl", R.string.language_nl),
    FR("fr", R.string.language_fr),
    DE("de", R.string.language_de),
    IT("it", R.string.language_it),
    PT("pt", R.string.language_pt);

    companion object {
        fun current(): AppLanguage {
            val tag = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-')
            return entries.firstOrNull { it.tag == tag && it.tag.isNotEmpty() } ?: SYSTEM
        }
    }
}

@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel(),
    extra: ExtraSettingsViewModel = hiltViewModel()
) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle()
    val ui by uiState
    val schedule = extra.schedule.collectAsStateWithLifecycle()
    val queue = extra.queue.collectAsStateWithLifecycle()
    val autoScan = extra.autoScan.collectAsStateWithLifecycle()
    val afterDownload = extra.afterDownload.collectAsStateWithLifecycle()
    val digestOn = extra.digestOn.collectAsStateWithLifecycle()
    val metaAuto = extra.metaAuto.collectAsStateWithLifecycle()
    val power = extra.power.collectAsStateWithLifecycle()
    val pickBest = extra.pickBest.collectAsStateWithLifecycle()
    val appPrefs = extra.appPrefs.collectAsStateWithLifecycle()
    val activeProfileName = extra.activeProfileName.collectAsStateWithLifecycle()
    val updateOffer by extra.updateOffer.collectAsStateWithLifecycle()
    val updateProgress = extra.updateProgress.collectAsStateWithLifecycle()
    val look = extra.look.collectAsStateWithLifecycle()
    val states = SettingsStates(
        ui = uiState, schedule = schedule, queue = queue, autoScan = autoScan, afterDownload = afterDownload,
        digestOn = digestOn, metaAuto = metaAuto, power = power, pickBest = pickBest, appPrefs = appPrefs,
        activeProfileName = activeProfileName, updateProgress = updateProgress, look = look
    )
    val context = LocalContext.current
    val digestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val backupDirLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            extra.setAutoBackupDir(context, it.toString())
        }
    }
    val retroArchThumbsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            extra.setRetroArchThumbnailsDir(context, it.toString())
        }
    }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    updateOffer?.let { tag ->
        ConfirmDialog(
            title = stringResource(R.string.update_offer_title, tag),
            message = stringResource(R.string.update_offer_message),
            confirmText = stringResource(R.string.update_offer_install),
            onConfirm = { extra.installUpdate(context) },
            onDismiss = extra::dismissUpdateOffer
        )
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.onDownloadDirChanged(context, it.toString())
        }
    }
    val esdeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.onEsdeDirPicked(context, it.toString())
        }
    }
    val iisuLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.onIisuDirPicked(context, it.toString())
        }
    }

    // Backup: export into a document the user creates, restore from one they pick (after a confirmation).
    val backupExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { viewModel.exportBackup(context, it.toString()) }
    }
    val backupImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.readBackup(context, it.toString()) }
    }
    // The shell's instance, so the rescan after a restore survives leaving Settings.
    val sourcesViewModel: SourcesViewModel = hiltViewModel(LocalActivity.current as ComponentActivity)
    val backupBusyMessage = stringResource(R.string.backup_import_busy)
    val pendingRestore by viewModel.pendingRestore.collectAsStateWithLifecycle()
    pendingRestore?.let { pending ->
        val date = if (pending.createdAt > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(pending.createdAt)) else "?"
        ConfirmDialog(
            title = stringResource(R.string.backup_import_title),
            message = stringResource(R.string.backup_import_message, date, pending.appVersion),
            confirmText = stringResource(R.string.backup_import_action),
            onConfirm = { viewModel.restoreBackup(context) { sourcesViewModel.rescanAllSources() } },
            onDismiss = viewModel::dismissRestore
        )
    }

    val themeLabel = stringResource(ui.themeMode.labelRes) + if (ui.themeMode == ThemeMode.SYSTEM) {
        " · " + stringResource(if (LocalDogmatixTokens.current.isDark) R.string.theme_suffix_dark else R.string.theme_suffix_light)
    } else ""
    val dialogs = remember { SettingsLocalState() }
    val debrid = ui.debridProvider
    val debridKeyTitle = stringResource(R.string.settings_debrid_key, debrid.label)
    val env = SettingsEnv(
        context = context,
        navController = navController,
        viewModel = viewModel,
        extra = extra,
        states = states,
        launchers = SettingsLaunchers(
            downloadDir = launcher, esdeDir = esdeLauncher, iisuDir = iisuLauncher, backupDir = backupDirLauncher,
            retroArchThumbs = retroArchThumbsLauncher, backupExport = backupExportLauncher,
            backupImport = backupImportLauncher, digestPermission = digestPermission
        ),
        dialogs = dialogs,
        sourcesViewModel = sourcesViewModel,
        isLandscape = isLandscape,
        themeLabel = themeLabel,
        debridKeyTitle = debridKeyTitle,
        backupBusyMessage = backupBusyMessage
    )

    if (dialogs.showThemePreviews) {
        ThemePreviewsDialog(
            modeName = ui.themeMode.name, accent = ui.accent,
            onPick = { look -> viewModel.onThemeModeChanged(context, ThemeMode.fromName(look.modeName)) },
            onDismiss = { dialogs.showThemePreviews = false }
        )
    }
    if (dialogs.showAccentDialog) {
        AccentDialog(selected = ui.accent, themeModeName = ui.themeMode.name, onPick = { viewModel.onAccentChanged(context, it); dialogs.showAccentDialog = false }, onDismiss = { dialogs.showAccentDialog = false })
    }

    if (dialogs.showDownloadPresets) DownloadPresetsDialog(onDismiss = { dialogs.showDownloadPresets = false })

    if (dialogs.showLanguagesDialog) {
        val available by viewModel.availableLanguages.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { viewModel.loadAvailableLanguages() }
        FavoriteLanguagesDialog(
            available = available,
            favorites = ui.favoriteLanguages,
            onChange = { viewModel.onFavoriteLanguagesChanged(context, it) },
            onDismiss = { dialogs.showLanguagesDialog = false }
        )
    }

    val daijishoSetup by viewModel.daijishoSetup.collectAsStateWithLifecycle()
    daijishoSetup?.let { setup ->
        DaijishoSetupDialog(setup = setup, onDismiss = viewModel::onDaijishoSetupDismissed)
    }

    if (dialogs.showCocoonHelp) {
        AlertDialog(
            modifier = Modifier.closeOnGamepadB { dialogs.showCocoonHelp = false },
            onDismissRequest = { dialogs.showCocoonHelp = false },
            title = { Text(stringResource(R.string.cocoon_dialog_title)) },
            text = { Text(stringResource(R.string.cocoon_dialog_body)) },
            confirmButton = { DialogButton(stringResource(R.string.dialog_ok), onClick = { dialogs.showCocoonHelp = false }, initialFocus = rememberInitialFocus()) }
        )
    }
    if (dialogs.showDebridKeyDialog && debrid != DebridProvider.NONE) {
        ApiKeyDialog(
            title = debridKeyTitle,
            hint = stringResource(if (debrid == DebridProvider.TORBOX) R.string.settings_torbox_key_dialog_hint else R.string.settings_realdebrid_key_dialog_hint),
            value = env.debridKey,
            onTest = { viewModel.testDebridApiKey(context, debrid, it) },
            onSave = { viewModel.onDebridApiKeyChanged(context, debrid, it) },
            onDismiss = { dialogs.showDebridKeyDialog = false }
        )
    }

    val masters = env.masters
    // Every row, in any order: [SettingsLayout] gives each its card and its place in that card.
    // The rows of each card are in SettingsCards*.kt.
    val ordered: List<SettingsRow> = env.allRows()
        .sortedWith(compareBy({ it.section.ordinal }, { SettingsLayout.placeOf(it.key) }))
    // Rows that do not apply right now are left out (sliding in and out as they change). Every
    // group is a card: its header over the full width, then its rows, in two columns in landscape.
    val columns = settingsColumns()
    val twoColumns = columns == 2
    // 5.0: each card shows its everyday rows, then one "Advanced (N)" row; the advanced rows follow
    // only while that card is open. Open cards are remembered in memory only (closed on every visit),
    // except that a jump to a hidden row (search, a tool) opens its card.
    val expandedCards = remember { mutableStateMapOf<SettingsGroup, Boolean>() }
    val shownBySection: List<List<ShownRow>> = SettingsGroup.entries.map { section ->
        val shown = ordered.withIndex().filter { (_, row) -> row.section == section && row.shown(masters) }.map { (i, row) -> ShownRow(i, row) }
        val (hidden, everyday) = shown.partition { SettingsLayout.isAdvanced(it.row.key) }
        if (hidden.isEmpty()) everyday else {
            val open = expandedCards[section] == true
            // The toggle's focus requester and grid key come after those of the real rows.
            val toggle = ShownRow(ordered.size + section.ordinal, SettingsRow(section = section) {
                AdvancedToggleRow(count = hidden.size, expanded = open, onToggle = { expandedCards[section] = !open })
            })
            everyday + toggle + if (open) hidden else emptyList()
        }
    }
    val cells = CardGrid.layout(
        SettingsGroup.entries.mapIndexed { i, section -> CardGrid.Section(header = section.title != null, items = shownBySection[i].size) },
        columns
    )
    val currentCells by rememberUpdatedState(cells)
    val currentShown by rememberUpdatedState(shownBySection)

    // LB / RB: in landscape hop between the two columns (same line of the card); in portrait
    // jump to the first row of the previous / next card. Focus requesters belong to the rows
    // (by their place in [ordered]), so a row appearing above does not move them.
    val rowFocus = remember(ordered.size) { List(ordered.size + SettingsGroup.entries.size) { FocusRequester() } }
    val currentFocus by rememberUpdatedState(rowFocus)
    var focusedRow by remember { mutableIntStateOf(-1) }
    val gridState = rememberLazyListState()
    val reduceMotionForJump = LocalReduceMotion.current
    LaunchedEffect(twoColumns) {
        Gamepad.presses.collect { button ->
            if (button != GamepadButton.PREV_PANEL && button != GamepadButton.NEXT_PANEL) return@collect
            val list = currentCells
            val shown = currentShown
            fun rowIndexOf(cell: Int): Int? = list.getOrNull(cell)
                ?.takeIf { it.kind == CardGrid.Kind.ITEM }
                ?.let { shown.getOrNull(it.section)?.getOrNull(it.item)?.index }
            val current = list.indices.firstOrNull { rowIndexOf(it) == focusedRow }
            if (twoColumns) {
                val target = CardGrid.hop(list, current) ?: return@collect
                rowIndexOf(target)?.let { row -> runCatching { currentFocus[row].requestFocus() } }
            } else {
                val (header, first) = CardGrid.groupJump(list, current, forward = button == GamepadButton.NEXT_PANEL) ?: return@collect
                val row = rowIndexOf(first) ?: return@collect
                // The row may be off screen (not composed yet): scroll the card in first.
                gridState.scrollToItem(settingsItemOf(list, header))
                withFrameNanos { }
                runCatching { currentFocus[row].requestFocus() }
            }
        }
    }
    // 8.0: a "search everything" result asked for one row: scroll its card in, focus the row and
    // light it up for a moment (a steady tint without animations).
    var flashRow by remember { mutableIntStateOf(-1) }
    val flash = remember { Animatable(0f) }
    val jumpKey by SettingsJump.pending.collectAsStateWithLifecycle()
    LaunchedEffect(jumpKey) {
        if (jumpKey == null) return@LaunchedEffect
        val key = SettingsJump.consume() ?: return@LaunchedEffect
        val row = ordered.indexOfFirst { it.key == key && it.shown(masters) }.takeIf { it >= 0 } ?: return@LaunchedEffect
        // A row behind "Advanced": open its card first and let the grid lay the new rows out.
        if (SettingsLayout.isAdvanced(key) && expandedCards[ordered[row].section] != true) {
            expandedCards[ordered[row].section] = true
            withFrameNanos { }
            withFrameNanos { }
        }
        val cell = currentCells.indices.firstOrNull { i ->
            val c = currentCells[i]
            c.kind == CardGrid.Kind.ITEM && currentShown.getOrNull(c.section)?.getOrNull(c.item)?.index == row
        } ?: return@LaunchedEffect
        // Let the grid lay out once (Settings may just have been opened), then bring the row in.
        withFrameNanos { }
        gridState.scrollToItem((settingsItemOf(currentCells, cell) - 1).coerceAtLeast(0))
        withFrameNanos { }
        runCatching { rowFocus[row].requestFocus() }
        flashRow = row
        if (reduceMotionForJump) {
            flash.snapTo(1f)
            delay(JUMP_HIGHLIGHT_MS)
        } else {
            flash.snapTo(0f)
            flash.animateTo(1f, tween(Motion.MEDIUM))
            delay(JUMP_HIGHLIGHT_MS)
            flash.animateTo(0f, tween(Motion.MEDIUM * 2))
        }
        flash.snapTo(0f)
        flashRow = -1
    }
    run {
        val base = legendFor(NavRoutes.Settings.route)
        val hop = LegendEntry("LB · RB", stringResource(if (twoColumns) R.string.pad_column else R.string.pad_group))
        val legend = remember(base, hop) { Legend(base.toMutableList().also { it.add(it.lastIndex, hop) }) }
        LaunchedEffect(legend) { Gamepad.legendOverride.value = legend }
        // Only clear our own legend: the previous screen's onDispose can run after ours is set.
        DisposableEffect(legend) { onDispose { if (Gamepad.legendOverride.value === legend) Gamepad.legendOverride.value = null } }
    }

    val pills = SectionPillState(
        limitKb = env.limitKb,
        schedule = env.schedule,
        rommConfigured = env.ui.rommEnabled && env.ui.rommUrl.isNotBlank(),
        profile = env.activeProfileName,
        autoBackup = env.appPrefs.autoBackup,
        autoBackupDirSet = env.appPrefs.autoBackupDir.isNotBlank(),
        autoBackupLast = env.appPrefs.autoBackupLast,
        updateProgress = env.updateProgress
    )
    val flashTint = MaterialTheme.colorScheme.primary
    SettingsSurface {
    SettingsCardsGrid(
        cells = cells,
        columns = columns,
        state = gridState,
        key = { _, cell ->
            if (cell.kind == CardGrid.Kind.HEADER) "section-${SettingsGroup.entries[cell.section].name}"
            else "row-${shownBySection[cell.section][cell.item].index}"
        },
        accent = { cell -> SettingsGroup.entries[cell.section] == SettingsGroup.TOOLS }
    ) { _, cell ->
        val section = SettingsGroup.entries[cell.section]
        if (cell.kind == CardGrid.Kind.HEADER) {
            Column {
                SettingsCardHeader(stringResource(section.title ?: R.string.settings_tools), section.icon, description = section.description?.let { stringResource(it) }) {
                    SectionPills(section, pills)
                }
                if (section == SettingsSection.LOOK) {
                    LookPreview(
                        listCovers = env.look.listCovers,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = settingsInset(), end = settingsInset(), bottom = 4.dp)
                    )
                }
            }
        } else {
            val shown = shownBySection[cell.section][cell.item]
            val flashing = shown.index == flashRow
            Box(
                modifier = Modifier
                    .then(if (flashing) Modifier.jumpHighlight(flashTint) { flash.value } else Modifier)
                    .focusRequester(rowFocus[shown.index])
                    .onFocusChanged { if (it.hasFocus) focusedRow = shown.index },
                // The row fills its cell, so beside a taller neighbour it centres its content.
                propagateMinConstraints = true
            ) { shown.row.content() }
        }
    }
    }
}
