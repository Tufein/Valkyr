package com.tufein.valkyr.ui.screens.tools

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tufein.valkyr.ui.components.requestFocusWhenReady
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.ColumnChart
import com.tufein.valkyr.ui.components.EmptyState
import com.tufein.valkyr.ui.components.GameCover
import com.tufein.valkyr.ui.components.Sparkline
import com.tufein.valkyr.ui.theme.consoleColor
import com.tufein.valkyr.ui.theme.tabular
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.DayLabel
import com.tufein.valkyr.util.HistoryEvent
import com.tufein.valkyr.util.HistoryFilter
import com.tufein.valkyr.util.HistoryKind
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.ZoneId
import java.util.Locale
import java.time.Instant

/**
 * Play history: a timeline of what happened to the library (finished downloads, games played in
 * ES-DE, saves on RomM), grouped by day, with a weekly activity header and Downloads / Played
 * filters. Pages of 50 load as the list is scrolled.
 *
 * @param onOpenGame opens the game's details (console id, file name of the library row); only
 *   called for rows that belong to a library game.
 */
@Composable
fun HistoryScreen(
    onOpenGame: (String, String) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(ui.loading, ui.hasAny) {
        if (!ui.loading && ui.hasAny) { firstFocus.requestFocusWhenReady() }
    }
    // Next page when the end of the list comes into view.
    LaunchedEffect(listState, ui.hasMore, ui.shown) {
        if (!ui.hasMore) return@LaunchedEffect
        snapshotFlow { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) to listState.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (total > 0 && last >= total - 6) viewModel.loadMore() }
    }
    val locale = Locale.getDefault()
    val timeFormat = remember(locale) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale) }
    val dateFormat = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    val zone = remember { ZoneId.systemDefault() }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.coll6_history_title), icon = R.drawable.ic_history, subtitle = stringResource(R.string.coll6_history_subtitle))
        when {
            ui.loading -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.coll6_history_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            !ui.hasAny -> EmptyState(
                title = stringResource(R.string.coll6_history_empty_title),
                message = stringResource(R.string.coll6_history_empty_message),
                illustration = R.drawable.brand_illustration,
                modifier = Modifier.fillMaxWidth()
            )
            else -> LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                item(key = "filters") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChipPill(R.string.coll6_filter_all, ui.filter == HistoryFilter.ALL, Modifier.focusRequester(firstFocus)) { viewModel.setFilter(HistoryFilter.ALL) }
                        FilterChipPill(R.string.coll6_filter_downloads, ui.filter == HistoryFilter.DOWNLOADS, Modifier) { viewModel.setFilter(HistoryFilter.DOWNLOADS) }
                        FilterChipPill(R.string.coll6_filter_played, ui.filter == HistoryFilter.PLAYED, Modifier) { viewModel.setFilter(HistoryFilter.PLAYED) }
                    }
                }
                item(key = "chart") { ActivityHeader(ui) }
                if (ui.total == 0) {
                    item(key = "empty-filter") {
                        EmptyState(
                            title = stringResource(R.string.coll6_history_empty_filter_title),
                            message = stringResource(R.string.coll6_history_empty_filter_message),
                            icon = R.drawable.ic_history,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                ui.groups.forEach { group ->
                    item(key = "d" + group.date) {
                        SectionHeader(dayText(group.label, locale, dateFormat))
                    }
                    items(group.events, key = { "e${it.kind.ordinal}|${it.at}|${it.consoleId}|${it.fileName}" }) { event ->
                        HistoryRow(event, timeFormat, zone, onOpenGame)
                    }
                }
                if (ui.hasMore) item(key = "more") {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                }
                if (ui.hasPlays && !ui.hasMore) item(key = "plays-hint") {
                    Text(
                        stringResource(R.string.coll6_history_plays_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterChipPill(label: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    ActionPill(
        stringResource(label),
        onClick = onClick,
        modifier = modifier,
        icon = if (selected) R.drawable.ic_check else null,
        tone = if (selected) ActionTone.Accent else ActionTone.Neutral
    )
}

/** Week header: this week's count, the last 8 weeks as a line and the last 7 days as columns. */
@Composable
private fun ActivityHeader(ui: HistoryUi) {
    val scheme = MaterialTheme.colorScheme
    val locale = Locale.getDefault()
    SectionPanel(stringResource(R.string.coll6_chart_title), icon = R.drawable.ic_bar_chart) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    pluralStringResource(R.plurals.coll6_week_events, ui.thisWeek, ui.thisWeek),
                    style = MaterialTheme.typography.titleMedium.tabular(),
                    color = scheme.onSurface
                )
                Text(stringResource(R.string.coll6_chart_weeks), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            Sparkline(ui.weeklyCounts.map { it.toFloat() }, modifier = Modifier.weight(1f).height(40.dp))
        }
        Text(
            stringResource(R.string.coll6_chart_days),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp)
        )
        ColumnChart(
            values = ui.dailyCounts.map { it.toFloat() },
            labels = ui.dailyDates.map { it.dayOfWeek.getDisplayName(TextStyle.NARROW, locale) },
            chartHeight = 72.dp,
            valueLabels = ui.dailyCounts.map { if (it > 0) it.toString() else "" }
        )
    }
}

@Composable
private fun dayText(label: DayLabel, locale: Locale, dateFormat: DateTimeFormatter): String = when (label) {
    DayLabel.Today -> stringResource(R.string.coll6_day_today)
    DayLabel.Yesterday -> stringResource(R.string.coll6_day_yesterday)
    is DayLabel.Weekday -> label.date.dayOfWeek.getDisplayName(TextStyle.FULL, locale).replaceFirstChar { it.titlecase(locale) }
    is DayLabel.Date -> label.date.format(dateFormat)
}

@Composable
private fun HistoryRow(event: HistoryEvent, timeFormat: DateTimeFormatter, zone: ZoneId, onOpenGame: (String, String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val what = when (event.kind) {
        HistoryKind.DOWNLOADED -> stringResource(R.string.coll6_kind_downloaded)
        HistoryKind.PLAYED -> stringResource(R.string.coll6_kind_played)
        HistoryKind.SAVED -> event.via?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.coll6_kind_saved_via, it) }
            ?: stringResource(R.string.coll6_kind_saved)
    }
    val time = remember(event.at, timeFormat, zone) { timeFormat.format(Instant.ofEpochMilli(event.at).atZone(zone)) }
    ToolRow(
        title = event.title,
        lines = emptyList(),
        onClick = { if (event.openable) onOpenGame(event.consoleId, event.fileName) },
        leading = {
            GameCover(event.consoleId, event.fileName, event.title, modifier = Modifier.size(46.dp))
        },
        below = {
            Row(
                modifier = Modifier.padding(top = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(consoleColor(event.consoleId)))
                Text(
                    what + "  ·  " + ConsoleFormatter.getConsoleShortName(event.consoleId) + "  ·  " + time,
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = scheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }
    )
}
