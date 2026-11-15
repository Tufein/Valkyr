package com.tufein.valkyr.ui.screens.sources.components

import com.tufein.valkyr.ui.theme.inkOf
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tufein.valkyr.R
import com.tufein.valkyr.data.model.Console
import com.tufein.valkyr.data.model.ContentType
import com.tufein.valkyr.data.model.ResolvedDownloadPath
import com.tufein.valkyr.data.model.UrlEntry
import com.tufein.valkyr.data.state.SourceScanResult
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.Panel
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.pillColors
import com.tufein.valkyr.ui.components.rememberFocusSource
import com.tufein.valkyr.ui.screens.download.ActionButton
import com.tufein.valkyr.ui.screens.settings.ThemedSwitch
import com.tufein.valkyr.ui.theme.LocalReduceMotion
import com.tufein.valkyr.ui.theme.Motion
import com.tufein.valkyr.ui.theme.consoleColor
import com.tufein.valkyr.ui.theme.motionSpec
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.SourceHealth
import com.tufein.valkyr.util.SourceKind
import com.tufein.valkyr.util.SourceRanking
import com.tufein.valkyr.util.SourceRecord
import kotlinx.coroutines.launch

/**
 * One console in Sources (5.0): a panel with the console's colour down its edge, its badge, name
 * and manufacturer, pills for how its sources are doing (from the last scan) and how many games they
 * gave, the download folder, a tidy row of icon buttons and, expanded, one inner row per source.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConsoleCard(
    console: Console,
    downloadPath: ResolvedDownloadPath?,
    subtitle: String? = null,
    onAddUrl: () -> Unit,
    onEditConsole: () -> Unit,
    onDeleteConsole: () -> Unit,
    onEditUrl: (Int, UrlEntry) -> Unit,
    onDeleteUrl: (Int, UrlEntry) -> Unit,
    onToggleUrl: (Int, Boolean) -> Unit,
    onSetCustomDownloadPath: () -> Unit,
    onRefreshConsole: () -> Unit,
    onMergeFolders: () -> Unit = {},
    /** How the last scan of a source went, if known. */
    resultFor: (UrlEntry) -> SourceScanResult? = { null },
    /** 7.5: how downloads from a source went lately, if any were made. */
    trackFor: (UrlEntry) -> SourceRecord? = { null }
) {
    var expanded by remember { mutableStateOf(false) }
    // D-pad: when any button inside gains focus, scroll the whole card into view (not just the button),
    // so the download-path line of the last card is never left hidden.
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val reduce = LocalReduceMotion.current
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val color = consoleColor(console.id)

    val results = console.urls.filter { it.enabled }.map { resultFor(it) }
    val health = SourceHealth.of(results.map { r -> r?.let { it.failure == null } })
    val indexed = results.filterNotNull().filter { it.failure == null }.sumOf { it.files ?: 0 }

    Panel(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
            .onFocusChanged { if (it.hasFocus) scope.launch { bringIntoView.bringIntoView() } }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // The console's colour as a 4 dp strip down the left edge (clipped by the panel's corners).
                .drawBehind { drawRect(color, size = Size(4.dp.toPx(), size.height)) }
                .padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 10.dp)
        ) {
            // The expanding source list below is not part of this spaced column: a collapsed (empty)
            // child would still get its gap.
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ConsoleBadge(console.id)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = console.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = scheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (wide) {
                        ConsoleActions(
                            onAddUrl = onAddUrl,
                            onSetCustomDownloadPath = onSetCustomDownloadPath,
                            onRefreshConsole = onRefreshConsole,
                            onEditConsole = onEditConsole,
                            onDeleteConsole = onDeleteConsole
                        )
                    }
                    ExpandButton(expanded) { expanded = !expanded }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HealthPill(health)
                    if (console.urls.isNotEmpty()) {
                        Pill(
                            pluralStringResource(R.plurals.q5_sources_pill, console.urls.size, console.urls.size),
                            tone = PillTone.Neutral,
                            icon = R.drawable.ic_link
                        )
                    }
                    if (health.scanned > 0) {
                        Pill(
                            pluralStringResource(R.plurals.q5_games_pill, indexed, indexed),
                            tone = PillTone.Tint(color),
                            icon = R.drawable.ic_library
                        )
                    }
                }

                if (downloadPath != null) {
                    val (text, textColor) = when (downloadPath.source) {
                        ResolvedDownloadPath.Source.CUSTOM ->
                            stringResource(R.string.sources_path_custom, downloadPath.displayPath) to scheme.primary
                        ResolvedDownloadPath.Source.DETECTED ->
                            stringResource(R.string.sources_path, downloadPath.displayPath) to scheme.onSurfaceVariant
                        ResolvedDownloadPath.Source.WILL_CREATE ->
                            stringResource(R.string.sources_path_will_create, downloadPath.displayPath) to scheme.onSurfaceVariant
                        ResolvedDownloadPath.Source.ROOT ->
                            stringResource(R.string.sources_path, downloadPath.displayPath) to scheme.onSurfaceVariant
                        ResolvedDownloadPath.Source.UNSET ->
                            stringResource(R.string.sources_path_unset) to scheme.error
                    }
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_folder),
                            contentDescription = null,
                            tint = textColor,
                            modifier = Modifier.padding(top = 1.dp).size(14.dp)
                        )
                        Text(text = text, style = MaterialTheme.typography.bodySmall, color = textColor, modifier = Modifier.weight(1f))
                    }
                    if (downloadPath.alternatives.isNotEmpty()) {
                        val all = listOf(downloadPath.subPath) + downloadPath.alternatives
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_merge),
                                contentDescription = null,
                                tint = inkOf(scheme.tertiary),
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = stringResource(R.string.merge_folders_notice, all.size, all.joinToString(", ")),
                                style = MaterialTheme.typography.bodySmall,
                                color = inkOf(scheme.tertiary),
                                modifier = Modifier.weight(1f)
                            )
                            ActionPill(stringResource(R.string.merge_folders_action), onMergeFolders, tone = ActionTone.Accent)
                        }
                    }
                }

                if (!wide) {
                    ConsoleActions(
                        onAddUrl = onAddUrl,
                        onSetCustomDownloadPath = onSetCustomDownloadPath,
                        onRefreshConsole = onRefreshConsole,
                        onEditConsole = onEditConsole,
                        onDeleteConsole = onDeleteConsole
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = if (reduce) EnterTransition.None else expandVertically(tween(Motion.MEDIUM, easing = FastOutSlowInEasing)) + fadeIn(tween(Motion.MEDIUM)),
                exit = if (reduce) ExitTransition.None else shrinkVertically(tween(Motion.MEDIUM, easing = FastOutSlowInEasing)) + fadeOut(tween(Motion.FAST))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    console.urls.forEachIndexed { index, urlEntry ->
                        UrlItem(
                            urlEntry = urlEntry,
                            result = resultFor(urlEntry),
                            track = trackFor(urlEntry),
                            onEdit = { onEditUrl(index, urlEntry) },
                            onDelete = { onDeleteUrl(index, urlEntry) },
                            onToggle = { onToggleUrl(index, it) }
                        )
                    }
                }
            }
        }
    }
}

/** The console's abbreviation on a tile in its family colour. */
@Composable
private fun ConsoleBadge(consoleId: String) {
    val (bg, fg) = pillColors(PillTone.Tint(consoleColor(consoleId)))
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            ConsoleFormatter.getConsoleShortName(consoleId),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.ExtraBold,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}

/** How the console's sources did in their last scan, as one pill. */
@Composable
private fun HealthPill(health: SourceHealth.Health) {
    when (health.state) {
        SourceHealth.State.NO_SOURCES -> Pill(stringResource(R.string.q5_health_no_sources), tone = PillTone.Neutral, icon = R.drawable.ic_remove)
        SourceHealth.State.NOT_SCANNED -> Pill(stringResource(R.string.q5_health_not_scanned), tone = PillTone.Neutral, icon = R.drawable.ic_schedule)
        SourceHealth.State.OK -> Pill(stringResource(R.string.q5_health_ok), tone = PillTone.Success, icon = R.drawable.ic_check_circle)
        SourceHealth.State.PARTIAL -> Pill(stringResource(R.string.q5_health_partial, health.failed), tone = PillTone.Warning, icon = R.drawable.ic_warning)
        SourceHealth.State.FAILED -> Pill(stringResource(R.string.q5_health_failed), tone = PillTone.Danger, icon = R.drawable.ic_error_circle)
    }
}

/** Add a source, pick a download folder, refresh, edit, delete: one even row, each with its own ring. */
@Composable
private fun ConsoleActions(
    onAddUrl: () -> Unit,
    onSetCustomDownloadPath: () -> Unit,
    onRefreshConsole: () -> Unit,
    onEditConsole: () -> Unit,
    onDeleteConsole: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ActionButton(R.drawable.ic_add, stringResource(R.string.sources_add_url), 40.dp, scheme.onSurface, onClick = onAddUrl)
        ActionButton(R.drawable.ic_folder, stringResource(R.string.sources_set_custom_path), 40.dp, scheme.onSurface, onClick = onSetCustomDownloadPath)
        ActionButton(R.drawable.ic_retry, stringResource(R.string.sources_refresh_console), 40.dp, scheme.onSurface, onClick = onRefreshConsole)
        ActionButton(R.drawable.ic_edit, stringResource(R.string.sources_edit_console), 40.dp, scheme.onSurface, onClick = onEditConsole)
        ActionButton(R.drawable.ic_trash, stringResource(R.string.sources_delete_console), 40.dp, scheme.error, onClick = onDeleteConsole)
    }
}

/** Opens and closes the source list; the chevron turns (in the draw phase) when it opens. */
@Composable
private fun ExpandButton(expanded: Boolean, onClick: () -> Unit) {
    val source = rememberFocusSource()
    val angle = animateFloatAsState(if (expanded) 180f else 0f, motionSpec<Float>(Motion.MEDIUM), label = "chevron")
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .focusRing(source, cornerRadius = 10.dp)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painterResource(R.drawable.ic_keyboard_arrow_down),
            contentDescription = stringResource(if (expanded) R.string.sources_collapse else R.string.sources_expand),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = angle.value }
        )
    }
}

/** One source of the console: its address, pills for its kind, type and last scan, a switch, edit and delete. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UrlItem(
    urlEntry: UrlEntry,
    result: SourceScanResult? = null,
    track: SourceRecord? = null,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    // Disabled sources stay listed but visibly muted; the switch is the first control in the row.
    val enabled = urlEntry.enabled
    val kind = SourceKind.of(urlEntry.url)
    val (kindLabel, kindIcon) = when (kind) {
        SourceKind.ROMM -> R.string.q5_kind_romm to R.drawable.ic_server
        SourceKind.TORRENT -> R.string.q5_kind_torrent to R.drawable.ic_hub
        SourceKind.WEB -> R.string.q5_kind_web to R.drawable.ic_globe
        SourceKind.OTHER -> R.string.q5_kind_other to R.drawable.ic_link
    }
    val typeLabel = when (urlEntry.contentType) {
        ContentType.GAME -> R.string.q5_type_game
        ContentType.MISCELLANEOUS -> R.string.q5_type_misc
        ContentType.RETROACHIEVEMENTS -> R.string.q5_type_ra
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(scheme.surfaceContainerHigh)
            .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            val displayText = if (urlEntry.url.length > 200) urlEntry.url.take(197) + "..." else urlEntry.url
            Text(
                text = displayText,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) scheme.onSurface else scheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Pill(stringResource(kindLabel), tone = PillTone.Neutral, icon = kindIcon)
                Pill(stringResource(typeLabel), tone = if (urlEntry.contentType == ContentType.GAME) PillTone.Neutral else PillTone.Info)
                if (!enabled) {
                    Pill(stringResource(R.string.q5_disabled), tone = PillTone.Neutral, icon = R.drawable.ic_visibility)
                } else if (result != null) {
                    if (result.failure != null) {
                        Pill(stringResource(R.string.q5_health_failed), tone = PillTone.Danger, icon = R.drawable.ic_error_circle)
                    } else {
                        Pill(stringResource(R.string.q5_health_ok), tone = PillTone.Success, icon = R.drawable.ic_check_circle)
                    }
                }
                // 7.5: the download track record ("avg 12 MB/s · 98 % ok"); amber while the source is set aside.
                val ok = track?.okPercent
                if (enabled && ok != null) {
                    val speed = SourceRanking.speedText(track.bytesPerSec)
                    val text = if (speed != null) stringResource(R.string.src75_track, speed, ok) else stringResource(R.string.src75_track_ok, ok)
                    val demoted = SourceRanking.isDemoted(track, System.currentTimeMillis())
                    Pill(text, tone = if (demoted) PillTone.Warning else PillTone.Info, icon = R.drawable.ic_arrow_down)
                }
            }
            if (!enabled) {
                Text(
                    text = stringResource(R.string.sources_url_disabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            } else if (result != null) {
                Text(
                    text = sourceResultText(result),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result.failure != null) scheme.error else inkOf(scheme.tertiary)
                )
                sourceTrendText(result)?.let {
                    Text(text = it, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            }
        }

        ThemedSwitch(checked = enabled, onChange = onToggle)
        ActionButton(R.drawable.ic_edit, stringResource(R.string.sources_edit_url), 36.dp, scheme.onSurface, onClick = onEdit)
        ActionButton(R.drawable.ic_trash, stringResource(R.string.sources_delete_url), 36.dp, scheme.error, onClick = onDelete)
    }
}
