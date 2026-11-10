package com.tufein.valkyr.ui.screens.tools

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tufein.valkyr.ui.components.requestFocusWhenReady
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.CoverImage
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.EmptyState
import com.tufein.valkyr.ui.components.LegendEntry
import com.tufein.valkyr.ui.components.MeterBar
import com.tufein.valkyr.ui.components.Panel
import com.tufein.valkyr.ui.components.PanelTone
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.formatBytes
import com.tufein.valkyr.ui.components.rememberCoverRepository
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.ui.screens.cloud.sections.formatCount
import com.tufein.valkyr.ui.screens.cloud.sections.raErrorText
import com.tufein.valkyr.ui.theme.accentInk
import com.tufein.valkyr.ui.theme.inkOf
import com.tufein.valkyr.ui.theme.tabular
import com.tufein.valkyr.util.BulkPlan
import com.tufein.valkyr.util.OwnState
import com.tufein.valkyr.util.RankBasis
import com.tufein.valkyr.util.ToastUtil
import kotlinx.coroutines.launch

/**
 * Best games per console (7.0): pick a console and see the games RetroAchievements has achievement
 * sets for, most-loved first, each with its cover, its rank and where it is: on the device, in one
 * of your sources, or missing. RetroAchievements has no "most played" list, so the list is ranked by
 * achievement points (a stand-in for popularity) and, on request, by player counts of the top games
 * (one throttled call per game, kept for a week); the screen says which of the two it is showing.
 * "Download the ones I can get" queues one version of each game found in a source (after a size
 * check), "Put the missing ones on the wishlist" wishes the rest for this console, and a row opens
 * the game in the library when it is on the device, downloads it when a source has it (the list stays
 * open) and wishes it when it is missing.
 *
 * @param onOpenRetroAchievements opens the RetroAchievements screen (`NavRoutes.RetroAchievements`),
 *   offered when no RA name and web API key are set.
 * @param onOpenLibrary called right after a game was handed to the library; the shell already
 *   switches to the Library tab when a filter is submitted (as for the wishlist), so it may stay a no-op.
 */
@Composable
fun BestGamesScreen(
    onOpenRetroAchievements: () -> Unit,
    onOpenLibrary: () -> Unit = {},
    viewModel: BestGamesViewModel = hiltViewModel()
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val detail = ui.detail
    BackHandler(enabled = detail != null) { viewModel.close() }
    when {
        ui.account == null -> Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
            ToolsTitle(stringResource(R.string.top7_title), icon = R.drawable.ic_military_tech)
        }
        ui.account == false -> NoAccount(onOpenRetroAchievements)
        detail != null -> BestList(detail, viewModel, onOpenLibrary)
        ui.consoles.isEmpty() -> Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
            ToolsTitle(stringResource(R.string.top7_title), icon = R.drawable.ic_military_tech, subtitle = stringResource(R.string.top7_subtitle))
            EmptyState(
                title = stringResource(R.string.top7_no_consoles_title),
                message = stringResource(R.string.top7_no_consoles_message),
                illustration = R.drawable.brand_illustration,
                modifier = Modifier.fillMaxWidth()
            )
        }
        else -> ConsoleList(ui.consoles, viewModel::open)
    }
}

@Composable
private fun NoAccount(onOpenRetroAchievements: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocusWhenReady() }
    Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.top7_title), icon = R.drawable.ic_military_tech, subtitle = stringResource(R.string.top7_subtitle))
        EmptyState(
            title = stringResource(R.string.top7_no_key_title),
            message = stringResource(R.string.top7_no_key_message),
            icon = R.drawable.ic_trophy,
            actionLabel = stringResource(R.string.top7_no_key_action),
            onAction = onOpenRetroAchievements,
            actionFocus = focus,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ConsoleList(consoles: List<BestConsole>, onOpen: (String) -> Unit) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(consoles.isNotEmpty()) { firstFocus.requestFocusWhenReady() }
    Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.top7_title), icon = R.drawable.ic_military_tech, subtitle = stringResource(R.string.top7_subtitle))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            item(key = "intro") { InfoCard(listOf(stringResource(R.string.top7_intro)), icon = R.drawable.ic_trophy) }
            item(key = "pick") { SectionHeader(stringResource(R.string.top7_pick), icon = R.drawable.ic_library) }
            items(consoles, key = { it.id }) { console ->
                ToolRow(
                    title = console.name,
                    lines = listOf(stringResource(R.string.top7_console_line, console.raName)),
                    onClick = { onOpen(console.id) },
                    modifier = if (console === consoles.first()) Modifier.focusRequester(firstFocus) else Modifier,
                    leading = { ConsoleTile(console.id) },
                    chevron = true
                )
            }
        }
    }
}

/** What a row does when it is pressed. */
private enum class RowAction { OPEN, DOWNLOAD, WISH, NONE }

private fun BestRow.action(): RowAction = when {
    state == OwnState.IN_SOURCE -> if (downloading) RowAction.NONE else RowAction.DOWNLOAD
    libraryQuery != null -> RowAction.OPEN
    state == OwnState.MISSING && !wished -> RowAction.WISH
    else -> RowAction.NONE
}

@Composable
private fun BestList(detail: BestDetail, viewModel: BestGamesViewModel, onOpenLibrary: () -> Unit) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var confirm by remember { mutableStateOf(false) }
    if (confirm) DownloadDialog(viewModel, detail.console.name, onDismiss = { confirm = false })

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(detail.console.id, detail.rows.isNotEmpty()) {
        if (detail.rows.isNotEmpty()) { firstFocus.requestFocusWhenReady() }
    }
    // A opens the game in the library, downloads it from a source or wishes it when it is missing; the legend says which.
    var focusedGame by remember { mutableStateOf<Int?>(null) }
    val focused = detail.rows.firstOrNull { it.gameId == focusedGame }
    PublishLegend(
        focused?.let { row ->
            listOfNotNull(
                when (row.action()) {
                    RowAction.OPEN -> LegendEntry("A", stringResource(R.string.pad_open))
                    RowAction.DOWNLOAD -> LegendEntry("A", stringResource(R.string.top7_download_one))
                    RowAction.WISH -> LegendEntry("A", stringResource(R.string.top7_wish_one))
                    RowAction.NONE -> null
                },
                LegendEntry("B", stringResource(R.string.pad_back)),
                LegendEntry("ZL · ZR", stringResource(R.string.pad_section))
            )
        }
    )

    Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.top7_game_title, detail.console.name), icon = R.drawable.ic_military_tech)
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            if (detail.candidates > 0) item(key = "basis") {
                val players = detail.basis == RankBasis.PLAYERS
                InfoCard(
                    lines = buildList {
                        add(stringResource(if (players) R.string.top7_basis_players else R.string.top7_basis_points, detail.candidates))
                        if (!players && detail.checked > 0) add(stringResource(R.string.top7_basis_partial, detail.checked, detail.candidates))
                        if (detail.stale) add(stringResource(R.string.top7_stale))
                    },
                    accent = players,
                    icon = if (players) R.drawable.ic_group else R.drawable.ic_military_tech
                )
            }
            detail.refine?.let { (done, total) ->
                item(key = "refining") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MeterBar(if (total > 0) done.toFloat() / total else 0f)
                        Text(
                            stringResource(R.string.top7_refining, done, total),
                            style = MaterialTheme.typography.bodySmall.tabular(),
                            color = scheme.onSurfaceVariant
                        )
                        ToolsActions(horizontalPadding = 0.dp) {
                            ActionPill(stringResource(R.string.top7_stop), onClick = viewModel::stopRefine, icon = R.drawable.ic_stop)
                        }
                    }
                }
            }
            detail.refineError?.let { kind ->
                item(key = "refine-error") { InfoCard(listOf(raErrorText(kind)), danger = true, icon = R.drawable.ic_error) }
            }
            if (detail.refine == null && detail.basis == RankBasis.POINTS && detail.candidates > 0 && !detail.loading) item(key = "refine") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ToolsActions(horizontalPadding = 0.dp) {
                        ActionPill(
                            stringResource(if (detail.checked > 0) R.string.top7_refine_continue else R.string.top7_refine),
                            onClick = viewModel::refine,
                            icon = R.drawable.ic_group,
                            tone = ActionTone.Accent
                        )
                    }
                    Text(
                        stringResource(R.string.top7_refine_hint, detail.candidates),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
            when {
                detail.loading && detail.rows.isEmpty() -> item(key = "loading") {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.top7_loading), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
                detail.error != null && detail.rows.isEmpty() -> item(key = "error") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        InfoCard(listOf(raErrorText(detail.error)), danger = true, icon = R.drawable.ic_error)
                        ToolsActions {
                            ActionPill(stringResource(R.string.top7_retry), onClick = viewModel::refresh, icon = R.drawable.ic_retry, modifier = Modifier.focusRequester(firstFocus))
                        }
                    }
                }
                detail.rows.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        title = stringResource(R.string.top7_empty_title),
                        message = stringResource(R.string.top7_empty_message),
                        icon = R.drawable.ic_search_off,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                else -> {
                    item(key = "summary") {
                        ToolsActions {
                            Pill(stringResource(R.string.top7_count_device, detail.onDevice), tone = PillTone.Success, icon = R.drawable.ic_check_circle)
                            Pill(stringResource(R.string.top7_count_source, detail.inSources), tone = PillTone.Accent, icon = R.drawable.ic_download)
                            Pill(stringResource(R.string.top7_count_missing, detail.missing), tone = PillTone.Neutral)
                        }
                    }
                    item(key = "actions") {
                        ToolsActions {
                            ActionPill(
                                stringResource(R.string.top7_download_all),
                                onClick = { confirm = true },
                                icon = R.drawable.ic_download,
                                tone = ActionTone.Accent,
                                enabled = detail.downloadable > 0
                            )
                            ActionPill(
                                stringResource(R.string.top7_wish_missing),
                                onClick = { viewModel.wishMissing(context) },
                                icon = R.drawable.ic_wishlist,
                                enabled = detail.wishable > 0
                            )
                            ActionPill(stringResource(R.string.top7_refresh), onClick = viewModel::refresh, icon = R.drawable.ic_retry, enabled = !detail.loading && detail.refine == null)
                        }
                    }
                    items(detail.rows, key = { it.gameId }) { row ->
                        BestGameRow(
                            row = row,
                            consoleId = detail.console.id,
                            modifier = (if (row === detail.rows.first()) Modifier.focusRequester(firstFocus) else Modifier).onFocusChanged {
                                if (it.isFocused) focusedGame = row.gameId else if (focusedGame == row.gameId) focusedGame = null
                            },
                            onClick = {
                                when (row.action()) {
                                    RowAction.OPEN -> { viewModel.openInLibrary(row); onOpenLibrary() }
                                    RowAction.DOWNLOAD -> viewModel.downloadOne(row, context)
                                    RowAction.WISH -> viewModel.wish(context, row)
                                    RowAction.NONE -> Unit
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BestGameRow(row: BestRow, consoleId: String, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val stats = buildList {
        row.players?.let { add(pluralStringResource(R.plurals.top7_row_players, it, formatCount(it))) }
        add(pluralStringResource(R.plurals.top7_row_points, row.points, formatCount(row.points)))
    }.joinToString("  ·  ")
    val extra = buildList {
        if (row.downloading) add(stringResource(R.string.top7_downloading))
        if (row.versions > 1) add(pluralStringResource(R.plurals.top7_versions, row.versions, row.versions))
        if (row.wished && row.state == OwnState.MISSING) add(stringResource(R.string.top7_wished))
    }.joinToString("  ·  ")
    val action = row.action()
    ToolRow(
        title = row.title,
        lines = listOfNotNull(stats, extra.ifEmpty { null }),
        onClick = onClick,
        modifier = modifier,
        badge = {
            when (row.state) {
                OwnState.ON_DEVICE -> Badge(stringResource(R.string.top7_state_device), warning = false, tone = PillTone.Success, icon = R.drawable.ic_check_circle)
                OwnState.IN_SOURCE -> Badge(stringResource(R.string.top7_state_source), warning = false, tone = PillTone.Accent, icon = R.drawable.ic_download)
                OwnState.MISSING -> Badge(stringResource(R.string.top7_state_missing), warning = false, tone = PillTone.Neutral)
            }
        },
        leading = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.top7_rank, row.rank),
                    style = MaterialTheme.typography.labelLarge.tabular(),
                    color = if (row.rank <= 3) accentInk() else scheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.widthIn(min = 30.dp)
                )
                BestCover(consoleId, row.coverFileName, row.title, row.iconUrl, Modifier.size(width = 42.dp, height = 56.dp))
            }
        },
        trailing = {
            when {
                action == RowAction.OPEN -> Icon(
                    painterResource(R.drawable.ic_library),
                    contentDescription = stringResource(R.string.top7_open_library),
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
                action == RowAction.DOWNLOAD -> Icon(
                    painterResource(R.drawable.ic_download),
                    contentDescription = stringResource(R.string.top7_download_one),
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
                action == RowAction.WISH -> Icon(
                    painterResource(R.drawable.ic_wishlist),
                    contentDescription = stringResource(R.string.top7_wish_one),
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
                row.wished && row.state == OwnState.MISSING -> Icon(
                    painterResource(R.drawable.ic_check_circle),
                    contentDescription = stringResource(R.string.top7_wished),
                    tint = inkOf(scheme.tertiary),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    )
}

/**
 * The game's cover from the library's usual sources; until one is found (or when there is none) RA's
 * own icon of the game, then the console-coloured placeholder.
 */
@Composable
private fun BestCover(consoleId: String, fileName: String, title: String, iconUrl: String?, modifier: Modifier) {
    val covers = rememberCoverRepository()
    var url by remember(consoleId, fileName) { mutableStateOf(covers.cached(consoleId, fileName)) }
    LaunchedEffect(consoleId, fileName) {
        if (url == null) url = runCatching { covers.coverUrl(consoleId, fileName, title) }.getOrNull()
    }
    CoverImage(url ?: iconUrl, consoleId, modifier)
}

/** What "Download the ones I can get" would queue (one version of each game), with its size, before it starts. */
@Composable
private fun DownloadDialog(viewModel: BestGamesViewModel, consoleName: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    var plan by remember { mutableStateOf<BulkPlan?>(null) }
    var starting by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { plan = viewModel.planDownload() }
    val cancelFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_download), contentDescription = null, tint = scheme.primary) },
        title = { Text(stringResource(R.string.top7_confirm_title, consoleName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                val p = plan
                if (p == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.bulk_counting), style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Panel(
                        tone = if (p.fits) PanelTone.Normal else PanelTone.Danger,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            pluralStringResource(R.plurals.bulk_summary, p.chosen.size, p.chosen.size, formatBytes(p.totalBytes)),
                            style = MaterialTheme.typography.titleLarge.tabular(),
                            color = scheme.onSurface
                        )
                        p.freeBytes?.takeIf { it > 0L }?.let { free ->
                            MeterBar(
                                fraction = (p.totalBytes.toFloat() / free.toFloat()).coerceIn(0f, 1f),
                                color = if (p.fits) scheme.primary else scheme.error
                            )
                        }
                        p.freeBytes?.let {
                            Text(stringResource(R.string.bulk_free, formatBytes(it)), style = MaterialTheme.typography.bodySmall.tabular(), color = scheme.onSurfaceVariant)
                        }
                    }
                    Text(stringResource(R.string.top7_confirm_hint), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    if (p.skippedActive > 0) Text(
                        pluralStringResource(R.plurals.bulk_skipped_active, p.skippedActive, p.skippedActive),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                    if (!p.fits) Text(stringResource(R.string.bulk_no_room_short, formatBytes(p.shortBytes)), style = MaterialTheme.typography.bodyMedium, color = scheme.error)
                }
            }
        },
        confirmButton = {
            val p = plan
            DialogButton(
                text = stringResource(R.string.bulk_start, p?.chosen?.size ?: 0),
                enabled = p != null && p.chosen.isNotEmpty() && p.fits && !starting,
                onClick = {
                    if (p == null) return@DialogButton
                    starting = true
                    scope.launch {
                        val queued = viewModel.startDownload(p, context)
                        if (queued > 0) ToastUtil.showSuccess(context, context.resources.getQuantityString(R.plurals.top7_queued, queued, queued))
                        onDismiss()
                    }
                }
            )
        },
        dismissButton = { DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss, initialFocus = cancelFocus) }
    )
}
