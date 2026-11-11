package com.tufein.valkyr.ui.screens.tools

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tufein.valkyr.ui.components.requestFocusWhenReady
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tufein.valkyr.R
import com.tufein.valkyr.data.service.PlaylistsPreview
import com.tufein.valkyr.data.service.PlaylistsRunState
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.LegendEntry
import com.tufein.valkyr.ui.components.MeterBar
import com.tufein.valkyr.ui.components.Panel
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.screens.settings.ThemedSwitch
import com.tufein.valkyr.ui.theme.tabular
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.FileParsingUtils
import com.tufein.valkyr.util.RetroArchPlaylists
import java.text.NumberFormat

private fun count(n: Int): String = NumberFormat.getIntegerInstance().format(n)

/**
 * Tools -> RetroArch playlists (3.5.0): pick RetroArch's playlists folder, switch off the
 * playlists you do not want, check the preview (per playlist: what is added, what is in it
 * already, what is left alone and why) and write. What RetroArch needs afterwards (a restart,
 * a core at the first start, covers from Covers for RetroArch) is said on the screen. The run can
 * be stopped between playlists; what was written stays.
 */
@Composable
fun RetroArchPlaylistsScreen(viewModel: RetroArchPlaylistsViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val run = ui.run
    val preview = ui.preview
    val firstFocus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { firstFocus.requestFocusWhenReady() }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            viewModel.setFolder(it.toString())
        }
    }

    PublishLegend(
        if (focused) listOf(
            LegendEntry("A", stringResource(R.string.pad_change)),
            LegendEntry("B", stringResource(R.string.pad_back))
        ) else null
    )
    val track = Modifier.onFocusChanged { focused = it.isFocused }
    val chosen = ui.chosenPlans
    val canWrite = !run.running && !ui.previewLoading && preview != null && preview.writable && chosen?.any { it.writes } == true

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.playlists35_title), icon = R.drawable.ic_playlist_add, subtitle = stringResource(R.string.playlists35_subtitle))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "what") {
                InfoCard(
                    listOf(stringResource(R.string.playlists35_what_title), stringResource(R.string.playlists35_what_body, stringResource(R.string.recovery_title))),
                    modifier = Modifier.focusRequester(firstFocus).focusableCard(),
                    icon = R.drawable.ic_info
                )
            }

            item(key = "h-folder") { SectionHeader(stringResource(R.string.playlists35_section_folder), icon = R.drawable.ic_folder) }
            item(key = "folder") {
                val folder = ui.settings?.folder.orEmpty()
                val notWritable = preview != null && preview.folderSet && !preview.writable
                val notListed = preview != null && preview.listingFailed
                ToolRow(
                    title = stringResource(R.string.playlists35_folder_title),
                    lines = listOfNotNull(
                        folder.takeIf { it.isNotBlank() }?.let { FileParsingUtils.toUserReadablePath(it) } ?: stringResource(R.string.playlists35_folder_none),
                        if (notWritable) stringResource(R.string.playlists35_folder_not_writable) else null,
                        if (notListed) stringResource(R.string.playlists35_folder_not_listed) else null,
                        stringResource(R.string.playlists35_folder_hint)
                    ),
                    onClick = { if (!run.running) folderPicker.launch(null) },
                    modifier = track,
                    badge = if (notWritable) ({ Pill(stringResource(R.string.covers31_needs_folder), tone = PillTone.Warning, icon = R.drawable.ic_warning) }) else null,
                    icon = R.drawable.ic_folder_open,
                    trailing = {
                        ActionPill(
                            stringResource(if (folder.isBlank()) R.string.covers31_pick else R.string.covers31_change),
                            { folderPicker.launch(null) },
                            icon = R.drawable.ic_folder_open,
                            enabled = !run.running
                        )
                    }
                )
            }

            item(key = "h-playlists") {
                SectionHeader(stringResource(R.string.playlists35_section_consoles), subtitle = stringResource(R.string.playlists35_consoles_hint), icon = R.drawable.ic_playlist_add)
            }
            val systems = preview?.systems.orEmpty()
            if (preview != null && systems.isEmpty()) {
                item(key = "no-systems") {
                    InfoCard(listOf(stringResource(R.string.playlists35_none_found)), modifier = Modifier.focusableCard(), icon = R.drawable.ic_info)
                }
            }
            items(systems, key = { "s-" + it.system }) { system ->
                val plan = preview?.plans?.firstOrNull { it.system == system.system }
                val on = system.system !in ui.excluded
                ToolRow(
                    title = system.system,
                    lines = systemLines(system, plan),
                    onClick = { viewModel.setIncluded(system.system, !on) },
                    modifier = track,
                    badge = if (plan?.state == RetroArchPlaylists.State.LEFT_ALONE) ({ Pill(stringResource(R.string.playlists35_badge_left_alone), tone = PillTone.Warning) }) else null,
                    trailing = { ThemedSwitch(on) { if (!run.running) viewModel.setIncluded(system.system, it) } }
                )
            }
            if (systems.size > 1) {
                item(key = "all") {
                    ToolsActions {
                        ActionPill(stringResource(R.string.playlists35_all_on), { viewModel.setAll(true) }, icon = R.drawable.ic_checkbox_on, enabled = !run.running)
                        ActionPill(stringResource(R.string.playlists35_all_off), { viewModel.setAll(false) }, icon = R.drawable.ic_checkbox_off, enabled = !run.running)
                    }
                }
            }

            item(key = "h-preview") { SectionHeader(stringResource(R.string.playlists35_section_preview), icon = R.drawable.ic_visibility) }
            item(key = "preview") { PreviewCard(ui) }
            item(key = "run") {
                if (run.running) RunningCard(run, viewModel::stop)
                else ToolsActions {
                    ActionPill(
                        stringResource(R.string.playlists35_write),
                        onClick = viewModel::write,
                        icon = R.drawable.ic_play_arrow,
                        tone = ActionTone.Accent,
                        enabled = canWrite
                    )
                    ActionPill(stringResource(R.string.playlists35_refresh), viewModel::refresh, icon = R.drawable.ic_retry, enabled = !ui.previewLoading)
                }
            }
            if (!run.running && (run.finished || run.problem != null || run.done > 0)) {
                item(key = "result") { ResultCard(run) }
            }

            item(key = "h-auto") { SectionHeader(stringResource(R.string.playlists62_section_auto), icon = R.drawable.ic_retry) }
            item(key = "auto") {
                val on = ui.settings?.auto == true
                ToolRow(
                    title = stringResource(R.string.playlists62_auto_title),
                    lines = listOf(stringResource(R.string.playlists62_auto_hint)),
                    onClick = { viewModel.setAuto(!on) },
                    modifier = track,
                    icon = R.drawable.ic_playlist_add,
                    trailing = { ThemedSwitch(on) { viewModel.setAuto(it) } }
                )
            }

            val withCores = systems.map { it to RetroArchPlaylists.coresOf(it.consoleIds) }.filter { it.second.isNotEmpty() }
            if (withCores.isNotEmpty()) {
                item(key = "h-cores") { SectionHeader(stringResource(R.string.playlists65_section_cores), icon = R.drawable.ic_playlist_play) }
                item(key = "cores-hint") { InfoCard(listOf(stringResource(R.string.playlists65_cores_hint)), modifier = Modifier.focusableCard(), icon = R.drawable.ic_info) }
                items(withCores, key = { "c-" + it.first.system }) { (system, options) ->
                    val chosen = ui.settings?.cores?.get(system.system)?.takeIf { it in options }
                    ToolRow(
                        title = system.system,
                        lines = listOf(chosen?.let { stringResource(R.string.playlists65_core_value, it) } ?: stringResource(R.string.playlists65_core_ask)),
                        onClick = { viewModel.nextCore(system.system, options) },
                        modifier = track,
                        icon = R.drawable.ic_playlist_play
                    )
                }
            }

            item(key = "h-after") { SectionHeader(stringResource(R.string.playlists35_section_after), icon = R.drawable.ic_info) }
            item(key = "after") {
                InfoCard(
                    listOf(
                        stringResource(R.string.playlists35_after_restart),
                        stringResource(R.string.playlists35_after_cores),
                        stringResource(R.string.playlists35_after_covers, stringResource(R.string.settings_retroarch_artwork)),
                        stringResource(R.string.playlists35_after_access)
                    ),
                    modifier = Modifier.focusableCard(),
                    icon = R.drawable.ic_playlist_play
                )
            }
        }
    }
}

/** Where a playlist's games come from and what writing it would do. */
@Composable
private fun systemLines(system: RetroArchPlaylists.SystemGames, plan: RetroArchPlaylists.Plan?): List<String> = buildList {
    add(stringResource(R.string.playlists35_from, system.consoleIds.joinToString(", ") { ConsoleFormatter.getConsoleDisplayName(it) }))
    when (plan?.state) {
        null -> add(stringResource(R.string.playlists35_row_games, count(system.games.size)))
        RetroArchPlaylists.State.NEW -> add(stringResource(R.string.playlists35_row_new, count(plan.add.size)))
        RetroArchPlaylists.State.ADD -> add(stringResource(R.string.playlists35_row_add, count(plan.add.size), count(plan.already)))
        RetroArchPlaylists.State.NOTHING_TO_ADD -> add(stringResource(R.string.playlists35_row_nothing, count(plan.already)))
        RetroArchPlaylists.State.CORE -> add(stringResource(R.string.playlists65_row_core, count(plan.already)))
        RetroArchPlaylists.State.LEFT_ALONE -> add(stringResource(R.string.playlists35_row_left_alone, plan.fileName, leftAloneText(plan.leftAlone)))
    }
    if (plan != null && plan.state != RetroArchPlaylists.State.LEFT_ALONE && plan.noPath > 0) add(stringResource(R.string.playlists35_row_no_path, count(plan.noPath)))
    if (system.discSetsWithoutM3u > 0) add(stringResource(R.string.playlists35_row_discs, count(system.discSetsWithoutM3u)))
}

/** Why a playlist that is there is not changed, in words. */
@Composable
private fun leftAloneText(why: RetroArchPlaylists.LeftAlone?): String = stringResource(
    when (why) {
        RetroArchPlaylists.LeftAlone.OLD_FORMAT -> R.string.playlists35_why_old
        RetroArchPlaylists.LeftAlone.COMPRESSED -> R.string.playlists35_why_compressed
        RetroArchPlaylists.LeftAlone.TOO_BIG -> R.string.playlists35_why_too_big
        RetroArchPlaylists.LeftAlone.FOLDER -> R.string.playlists35_why_folder
        RetroArchPlaylists.LeftAlone.UNREADABLE, null -> R.string.playlists35_why_unreadable
    }
)

/** The totals of the playlists switched on: entries to add, files, what is left alone and why games are missing. */
@Composable
private fun PreviewCard(ui: RetroArchPlaylistsUi) {
    val preview = ui.preview
    val chosen = ui.chosenPlans
    val lines: List<String> = when {
        ui.previewLoading -> listOf(stringResource(R.string.playlists35_preview_loading))
        ui.previewFailed || preview == null -> listOf(stringResource(R.string.playlists35_preview_failed))
        !preview.folderSet -> listOf(stringResource(R.string.playlists35_preview_no_folder))
        !preview.writable -> listOf(stringResource(R.string.playlists35_folder_not_writable))
        preview.listingFailed || chosen == null -> listOf(stringResource(R.string.playlists35_folder_not_listed))
        preview.games == 0 -> listOf(stringResource(R.string.playlists35_preview_no_games))
        preview.systems.isNotEmpty() && chosen.isEmpty() -> listOf(stringResource(R.string.playlists35_preview_none_chosen))
        else -> previewLines(preview, chosen)
    }
    val ready = preview != null && preview.writable && chosen?.any { it.writes } == true
    InfoCard(
        lines,
        modifier = Modifier.focusableCard(),
        accent = ready && !ui.previewLoading,
        icon = R.drawable.ic_playlist_add,
        danger = !ui.previewLoading && (ui.previewFailed || (preview != null && preview.folderSet && (!preview.writable || preview.listingFailed)))
    )
}

@Composable
private fun previewLines(preview: PlaylistsPreview, chosen: List<RetroArchPlaylists.Plan>): List<String> = buildList {
    val writing = chosen.filter { it.writes }
    val entries = writing.sumOf { it.add.size }
    val coreOnly = writing.count { it.state == RetroArchPlaylists.State.CORE }
    if (entries == 0 && coreOnly > 0) {
        add(stringResource(R.string.playlists65_preview_core, count(coreOnly), preview.folderDisplay))
        add(stringResource(R.string.playlists35_preview_backup, stringResource(R.string.recovery_title), stringResource(R.string.hist24_title)))
    } else if (entries == 0) add(stringResource(R.string.playlists35_preview_nothing))
    else {
        add(stringResource(R.string.playlists35_preview_count, count(entries), preview.folderDisplay))
        val created = writing.count { it.state == RetroArchPlaylists.State.NEW }
        val updated = writing.count { it.state == RetroArchPlaylists.State.ADD || it.state == RetroArchPlaylists.State.CORE }
        add(stringResource(R.string.playlists35_preview_files, count(created), count(updated)))
        if (updated > 0) add(stringResource(R.string.playlists35_preview_backup, stringResource(R.string.recovery_title), stringResource(R.string.hist24_title)))
        writing.firstNotNullOfOrNull { p -> p.add.firstOrNull { it.path != null } }?.let { game ->
            add(stringResource(R.string.playlists35_preview_example, game.label, game.path.orEmpty()))
        }
    }
    val kept = chosen.filter { it.state == RetroArchPlaylists.State.LEFT_ALONE }
    if (kept.isNotEmpty()) add(stringResource(R.string.playlists35_preview_left_alone, kept.joinToString(", ") { it.fileName }))
    val already = chosen.sumOf { it.already }
    if (already > 0) add(stringResource(R.string.playlists35_preview_already, count(already)))
    val noPath = chosen.filter { it.state != RetroArchPlaylists.State.LEFT_ALONE }.sumOf { it.noPath }
    if (noPath > 0) add(stringResource(R.string.playlists35_preview_no_path, count(noPath)))
    val discs = chosen.sumOf { it.discSetsWithoutM3u }
    if (discs > 0) add(stringResource(R.string.playlists35_preview_discs, count(discs), stringResource(R.string.nav_sets)))
    if (preview.noSystem > 0) {
        val names = preview.noSystemConsoles.take(MAX_NAMED).joinToString(", ") { ConsoleFormatter.getConsoleDisplayName(it) } +
            if (preview.noSystemConsoles.size > MAX_NAMED) ", …" else ""
        add(stringResource(R.string.playlists35_preview_no_system, count(preview.noSystem), names))
    }
    if (preview.profileLimited) add(stringResource(R.string.playlists35_preview_profile))
}

/** Consoles named in the "no playlist name" line before it says "…". */
private const val MAX_NAMED = 6

/** Progress of a run and a way to stop it (the playlist being written is finished first). */
@Composable
private fun RunningCard(run: PlaylistsRunState, onStop: () -> Unit) {
    Panel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (run.total == 0) stringResource(R.string.playlists35_preparing)
                    else stringResource(R.string.playlists35_running, count(minOf(run.done + 1, run.total)), count(run.total)),
                    style = MaterialTheme.typography.bodyMedium.tabular(),
                    color = MaterialTheme.colorScheme.onSurface
                )
                MeterBar(if (run.total == 0) 0f else run.done.toFloat() / run.total)
            }
            ActionPill(stringResource(R.string.playlists35_stop), onStop, icon = R.drawable.ic_close, tone = ActionTone.Danger)
        }
    }
}

/** The outcome of the last run, or why it could not run. */
@Composable
private fun ResultCard(run: PlaylistsRunState) {
    val problem = run.problem
    if (problem != null) {
        InfoCard(
            listOf(
                stringResource(
                    when (problem) {
                        PlaylistsRunState.Problem.NO_FOLDER -> R.string.playlists35_preview_no_folder
                        PlaylistsRunState.Problem.NOT_WRITABLE -> R.string.playlists35_folder_not_writable
                        PlaylistsRunState.Problem.NOT_READABLE -> R.string.playlists35_folder_not_listed
                        PlaylistsRunState.Problem.NOTHING_ON_DEVICE -> R.string.playlists35_preview_no_games
                        PlaylistsRunState.Problem.NOTHING_CHOSEN -> R.string.playlists35_preview_none_chosen
                        PlaylistsRunState.Problem.FAILED -> R.string.playlists35_problem_failed
                    }
                )
            ),
            modifier = Modifier.focusableCard(),
            icon = R.drawable.ic_warning,
            danger = problem == PlaylistsRunState.Problem.FAILED || problem == PlaylistsRunState.Problem.NOT_READABLE
        )
        return
    }
    val lines = buildList {
        add(stringResource(if (run.finished) R.string.playlists35_result_title else R.string.playlists35_result_stopped))
        add(stringResource(R.string.playlists35_result_entries, count(run.entries)))
        if (run.created > 0) add(stringResource(R.string.playlists35_result_created, count(run.created)))
        if (run.updated > 0) add(stringResource(R.string.playlists35_result_updated, count(run.updated), stringResource(R.string.recovery_title)))
        if (run.leftAlone > 0) add(stringResource(R.string.playlists35_result_left_alone, count(run.leftAlone)))
        if (run.failed > 0) add(stringResource(R.string.playlists35_result_failed, count(run.failed)))
        if (run.created + run.updated > 0) add(stringResource(R.string.playlists35_result_next))
    }
    InfoCard(
        lines,
        modifier = Modifier.focusableCard(),
        accent = run.finished && run.entries > 0,
        icon = if (run.finished) R.drawable.ic_check_circle else R.drawable.ic_info,
        danger = run.failed > 0
    )
}
