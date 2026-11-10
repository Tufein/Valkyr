package com.tufein.valkyr.ui.screens.tools

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tufein.valkyr.ui.components.requestFocusWhenReady
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.R
import com.tufein.valkyr.data.local.dao.CollectionDao
import com.tufein.valkyr.data.local.dao.DownloadableFileDao
import com.tufein.valkyr.data.local.dao.FavouriteDao
import com.tufein.valkyr.data.repository.ConsoleRepository
import com.tufein.valkyr.data.service.DownloadLog
import com.tufein.valkyr.data.service.EsdePlayService
import com.tufein.valkyr.ui.components.ColumnChart
import com.tufein.valkyr.ui.components.GameCover
import com.tufein.valkyr.ui.components.MeterBar
import com.tufein.valkyr.ui.components.formatBytes
import com.tufein.valkyr.ui.navigation.NavRoutes
import com.tufein.valkyr.ui.theme.consoleColor
import com.tufein.valkyr.ui.theme.tabular
import com.tufein.valkyr.util.ConsoleFolderAliases
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.DownloadStats
import com.tufein.valkyr.util.DownloadStatsSummary
import com.tufein.valkyr.util.EsdePlay
import com.tufein.valkyr.util.EsdePlayStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.util.Locale
import javax.inject.Inject
import com.tufein.valkyr.data.service.CompletedService
import kotlinx.coroutines.flow.SharingStarted

data class StatsUiState(
    val loading: Boolean = true,
    val downloads: DownloadStatsSummary? = null,
    val libraryGames: Int = 0,
    val favourites: Int = 0,
    val collections: Int = 0,
    val newPerWeek: List<Int> = emptyList(),
    /** Plays recorded by ES-DE; null when ES-DE is not set up. */
    val plays: List<EsdePlay>? = null,
    /** ES-DE system folder → the library console it belongs to (the cover and the colour follow it). */
    val systemConsole: Map<String, String> = emptyMap()
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val log: DownloadLog,
    private val fileDao: DownloadableFileDao,
    private val favouriteDao: FavouriteDao,
    private val collectionDao: CollectionDao,
    private val esdePlays: EsdePlayService,
    private val consoleRepository: ConsoleRepository,
    completedGames: CompletedService
) : ViewModel() {
    /** 5.5: games the active profile marked as completed. */
    val completedCount: StateFlow<Int> = completedGames.keys
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _ui = MutableStateFlow(StatsUiState())
    val ui: StateFlow<StatsUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            _ui.value = StatsUiState(
                loading = false,
                downloads = DownloadStats.summarize(log.entries(), now),
                libraryGames = fileDao.getFilesCount(),
                favourites = favouriteDao.getAll().size,
                collections = collectionDao.observeAll().first().size,
                newPerWeek = DownloadStats.newPerWeek(fileDao.firstSeenSince(now - 8 * 7 * 24 * 3_600_000L), now)
            )
            val plays = runCatching { esdePlays.plays() }.getOrNull()
            val consoleIds = runCatching { consoleRepository.getAllConsoles().first().map { it.id } }.getOrDefault(emptyList())
            val systems = plays.orEmpty().map { it.system }.distinct().associateWith { system ->
                consoleIds.firstOrNull { ConsoleFolderAliases.matches(it, system) } ?: system
            }
            _ui.value = _ui.value.copy(plays = plays, systemConsole = systems)
        }
    }
}

/** Statistics: what was downloaded per month and per console, and how the library grows. */
@Composable
fun StatsScreen(viewModel: StatsViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val completedCount by viewModel.completedCount.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.nav_stats), icon = NavRoutes.Stats.icon)
        val d = ui.downloads
        if (ui.loading || d == null) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        // Everything on this screen is a card the D-pad can stop on, so a pad can scroll through it.
        val firstFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { firstFocus.requestFocusWhenReady() }
        val now = remember { System.currentTimeMillis() }
        val thisMonth = d.perMonth.lastOrNull()
        val topConsole = d.perConsole.firstOrNull()
        val monthLabels = remember(d.perMonth) { d.perMonth.map { monthLabel(it.first) } }
        val weekLabels = remember(ui.newPerWeek.size) {
            ui.newPerWeek.indices.map { i ->
                val end = now - (ui.newPerWeek.lastIndex - i) * WEEK_MILLIS
                DateUtils.formatDateTime(context, end, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NUMERIC_DATE or DateUtils.FORMAT_NO_YEAR)
            }
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "kpi") {
                val monthDownloads = thisMonth?.second ?: 0
                StatGrid(
                    listOf(
                        ToolStat(
                            stringResource(R.string.tools5_stat_downloads), d.total.toString(), R.drawable.ic_download,
                            stringResource(R.string.tools5_in_total, formatBytes(d.totalBytes))
                        ),
                        ToolStat(
                            stringResource(R.string.tools5_stat_month), formatBytes(thisMonth?.third ?: 0L), R.drawable.ic_calendar_month,
                            pluralStringResource(R.plurals.tools5_downloads_count, monthDownloads, monthDownloads)
                        ),
                        ToolStat(
                            stringResource(R.string.tools5_stat_new_week), (ui.newPerWeek.lastOrNull() ?: 0).toString(), R.drawable.ic_new,
                            stringResource(R.string.stats_new_per_week_hint)
                        ),
                        ToolStat(
                            stringResource(R.string.tools5_stat_top_console),
                            topConsole?.let { ConsoleFormatter.getConsoleShortName(it.first) } ?: "—",
                            R.drawable.ic_trophy,
                            topConsole?.let { stringResource(R.string.stats_downloads_total, it.second, formatBytes(it.third)) },
                            topConsole?.let { consoleColor(it.first) }
                        )
                    ),
                    focusable = true,
                    firstFocus = firstFocus
                )
            }
            item(key = "library") {
                InfoCard(
                    listOf(stringResource(R.string.stats_library, ui.libraryGames, ui.favourites, ui.collections)) +
                        (if (completedCount > 0) listOf(stringResource(R.string.done55_stats_completed, completedCount)) else emptyList()),
                    icon = R.drawable.ic_library
                )
            }
            item(key = "months") {
                SectionPanel(stringResource(R.string.stats_per_month), icon = R.drawable.ic_calendar_month, focusable = true) {
                    ColumnChart(
                        values = d.perMonth.map { it.third.toFloat() },
                        labels = monthLabels,
                        valueLabels = d.perMonth.map { if (it.second == 0) "" else it.second.toString() }
                    )
                }
            }
            if (d.perConsole.isNotEmpty()) item(key = "consoles") {
                val maxConsole = d.perConsole.first().third.coerceAtLeast(1)
                SectionPanel(stringResource(R.string.stats_per_console), icon = R.drawable.ic_controller, focusable = true) {
                    d.perConsole.take(10).forEach { (console, count, bytes) ->
                        BarRow(
                            ConsoleFormatter.getConsoleShortName(console), bytes.toFloat() / maxConsole,
                            "$count · ${formatBytes(bytes)}", consoleColor(console)
                        )
                    }
                }
            }
            item(key = "weeks") {
                SectionPanel(
                    stringResource(R.string.stats_new_per_week), icon = R.drawable.ic_new,
                    subtitle = stringResource(R.string.stats_new_per_week_hint), focusable = true
                ) {
                    ColumnChart(
                        values = ui.newPerWeek.map { it.toFloat() },
                        labels = weekLabels,
                        valueLabels = ui.newPerWeek.map { if (it == 0) "" else it.toString() }
                    )
                }
            }
            val plays = ui.plays
            when {
                plays == null -> item(key = "noEsde") { InfoCard(listOf(stringResource(R.string.stats_played_no_esde)), icon = R.drawable.ic_play_circle) }
                plays.isEmpty() -> item(key = "noPlays") { InfoCard(listOf(stringResource(R.string.stats_played_none)), icon = R.drawable.ic_play_circle) }
                else -> {
                    val top = EsdePlayStats.top(plays)
                    val maxPlays = top.first().playCount.coerceAtLeast(1)
                    item(key = "played") {
                        SectionPanel(
                            stringResource(R.string.stats_played), icon = R.drawable.ic_play_circle,
                            subtitle = stringResource(R.string.stats_played_hint), focusable = true
                        ) {
                            top.forEach { p ->
                                PlayLine(
                                    p, ui.systemConsole[p.system] ?: p.system, p.playCount.toFloat() / maxPlays,
                                    pluralStringResource(R.plurals.stats_times_played, p.playCount, p.playCount)
                                )
                            }
                        }
                    }
                    val recent = EsdePlayStats.recent(plays, 5)
                    if (recent.isNotEmpty()) item(key = "recent") {
                        SectionPanel(stringResource(R.string.stats_recent), icon = R.drawable.ic_history, focusable = true) {
                            recent.forEach { p ->
                                PlayLine(
                                    p, ui.systemConsole[p.system] ?: p.system, null,
                                    DateUtils.getRelativeTimeSpanString(p.lastPlayed ?: 0L).toString()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val WEEK_MILLIS = 7L * 24 * 3_600_000

/** `2026-03` → "Mar" (as short as the language allows). */
private fun monthLabel(key: String): String = runCatching {
    YearMonth.parse(key).month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()).trimEnd('.')
}.getOrDefault(key.takeLast(2))

/** A played game: its cover, name and either a bar for its play count or the time it was last played. */
@Composable
private fun PlayLine(play: EsdePlay, consoleId: String, fraction: Float?, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val fileName = play.path.substringAfterLast('/').ifBlank { play.name + ".rom" }
        GameCover(consoleId, fileName, play.name, Modifier.size(width = 36.dp, height = 48.dp), showLabel = false)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    play.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(value, style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (fraction != null) {
                Spacer(Modifier.height(4.dp))
                MeterBar(fraction, height = 6.dp, color = consoleColor(consoleId))
            }
        }
    }
}
