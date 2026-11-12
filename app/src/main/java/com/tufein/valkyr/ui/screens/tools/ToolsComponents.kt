package com.tufein.valkyr.ui.screens.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tufein.valkyr.ui.common.Gamepad
import com.tufein.valkyr.ui.common.Legend
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.CoverImage
import com.tufein.valkyr.ui.components.IconTile
import com.tufein.valkyr.ui.components.LegendEntry
import com.tufein.valkyr.ui.components.MeterBar
import com.tufein.valkyr.ui.components.NavChevron
import com.tufein.valkyr.ui.components.Panel
import com.tufein.valkyr.ui.components.PanelTone
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.ScreenTitle
import com.tufein.valkyr.ui.components.SectionTitle
import com.tufein.valkyr.ui.components.StatTile
import com.tufein.valkyr.ui.components.TruncatedText
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.rememberFocusSource
import com.tufein.valkyr.ui.theme.LocalDogmatixTokens
import com.tufein.valkyr.ui.theme.tabular
import com.tufein.valkyr.util.GameEntry

/*
 * The shared pieces of the Tools screens (5.0 look): titles, section headers, cards, rows with an
 * icon, status pills, stat grids and the little bar rows of Storage / Statistics / Overview.
 */

private val CardShape = RoundedCornerShape(12.dp)

/** Screen title: the headline style with the tool's own icon on a tile. */
@Composable
internal fun ToolsTitle(text: String, icon: Int? = null, subtitle: String? = null) {
    ScreenTitle(
        text,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        subtitle = subtitle,
        icon = icon
    )
}

/** Heading over a group of rows in the Tools list (not focusable). */
@Composable
internal fun ToolsGroup(text: String, icon: Int? = null) {
    SectionTitle(
        text,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 18.dp, bottom = 6.dp),
        icon = icon
    )
}

/** Small heading between groups of rows, with an optional line of help under it. */
@Composable
internal fun SectionHeader(title: String, subtitle: String? = null, icon: Int? = null) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 4.dp)) {
        SectionTitle(title, icon = icon)
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/**
 * Non-focusable block of summary lines (totals, scan progress, notes) on a panel. [accent] picks
 * the accent tone, [danger] the red one (something needs attention).
 */
@Composable
internal fun InfoCard(
    lines: List<String>,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    icon: Int? = null,
    danger: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    val tone = when {
        danger -> PanelTone.Danger
        accent -> PanelTone.Accent
        else -> PanelTone.Normal
    }
    val first = when (tone) {
        PanelTone.Danger -> scheme.onErrorContainer
        PanelTone.Accent -> scheme.onPrimaryContainer
        else -> scheme.onSurface
    }
    val rest = when (tone) {
        PanelTone.Danger -> scheme.onErrorContainer.copy(alpha = 0.85f)
        PanelTone.Accent -> scheme.onPrimaryContainer.copy(alpha = 0.85f)
        else -> scheme.onSurfaceVariant
    }
    Panel(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        tone = tone,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            icon?.let {
                when (tone) {
                    PanelTone.Danger -> IconTile(it, size = 36.dp, container = scheme.error, tint = scheme.onError)
                    PanelTone.Accent -> IconTile(it, size = 36.dp, container = scheme.primary, tint = scheme.onPrimary)
                    else -> IconTile(it, size = 36.dp)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                lines.forEachIndexed { i, line ->
                    Text(
                        line,
                        style = if (i == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodySmall,
                        color = if (i == 0) first else rest
                    )
                }
            }
        }
    }
}

/**
 * A panel with a small headline (and an optional help line) for a block of content: a chart, a
 * list of bars. [focusable] lets the D-pad stop on it, so a screen without buttons still scrolls.
 */
@Composable
internal fun SectionPanel(
    title: String,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    subtitle: String? = null,
    focusable: Boolean = false,
    content: @Composable () -> Unit
) {
    Panel(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .then(if (focusable) Modifier.focusableCard() else Modifier),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        SectionTitle(title, icon = icon)
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/**
 * Lets a non-clickable card take D-pad focus: an accent ring around it (the ring only, the card
 * keeps its own fill). Put it first in the modifier chain, outside the card's padding.
 */
@Composable
internal fun Modifier.focusableCard(cornerRadius: Dp = 12.dp): Modifier {
    val source = rememberFocusSource()
    return this
        .focusRing(source, cornerRadius = cornerRadius, fill = false)
        .focusable(interactionSource = source)
}

/**
 * Focusable row like Settings' rows, but with several detail lines, an optional icon (or any
 * [leading] content such as a cover), a status [badge] next to the title, a [below] slot under
 * the lines (a bar), actions at the end and an optional arrow. Click / A runs [onClick].
 */
@Composable
internal fun ToolRow(
    title: String,
    lines: List<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: (@Composable () -> Unit)? = null,
    icon: Int? = null,
    leading: (@Composable () -> Unit)? = null,
    chevron: Boolean = false,
    below: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit = {}
) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalDogmatixTokens.current
    val source = rememberFocusSource()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .defaultMinSize(minHeight = 56.dp)
            .background(scheme.surfaceContainer, CardShape)
            .border(1.dp, tokens.hairline, CardShape)
            .focusRing(source, cornerRadius = 12.dp)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (leading != null) leading() else icon?.let { IconTile(it, size = 36.dp) }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false)
                )
                badge?.invoke()
            }
            lines.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            below?.invoke()
        }
        trailing()
        if (chevron) NavChevron()
    }
}

/** A secondary action at the end of a row (or in an [ToolsActions] strip). */
@Composable
internal fun ToolAction(
    label: String,
    icon: Int? = null,
    tone: ActionTone = ActionTone.Neutral,
    onClick: () -> Unit
) {
    ActionPill(label, onClick, icon = icon, tone = tone)
}

/** A strip of actions under a title; wraps onto a second line on a narrow screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToolsActions(modifier: Modifier = Modifier, horizontalPadding: Dp = 12.dp, content: @Composable () -> Unit) {
    FlowRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) { content() }
}

/**
 * A small status label next to a row title. Kept under its old name: [warning] is the red tone,
 * otherwise neutral, unless [tone] says better.
 */
@Composable
internal fun Badge(text: String, warning: Boolean, tone: PillTone? = null, icon: Int? = null) {
    Pill(text, tone = tone ?: if (warning) PillTone.Danger else PillTone.Neutral, icon = icon)
}

/** A console as a small coloured tile with its short name (the cover placeholder, without a cover). */
@Composable
internal fun ConsoleTile(consoleId: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    CoverImage(
        url = null,
        consoleId = consoleId,
        modifier = modifier.size(size),
        shape = RoundedCornerShape(size * 0.25f)
    )
}

/** One number with a label and optional icon: what [StatGrid] lays out. */
@Immutable
internal data class ToolStat(
    val label: String,
    val value: String,
    val icon: Int? = null,
    val detail: String? = null,
    val color: Color? = null
)

/**
 * [StatTile]s in equal columns: two across on a phone held upright, four across in landscape. With
 * [focusable] each tile can take the D-pad focus; [firstFocus] goes to the first one.
 */
@Composable
internal fun StatGrid(
    stats: List<ToolStat>,
    modifier: Modifier = Modifier,
    focusable: Boolean = false,
    firstFocus: FocusRequester? = null
) {
    // The room the grid really has (the landscape rail and the card padding already took theirs), not the
    // screen width: a Thor top screen is ~700 dp wide but only ~570 dp of it is for the content.
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
    val columns = if (maxWidth >= StatGridWideDp.dp) 4 else 2
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        stats.chunked(columns).forEachIndexed { r, rowStats ->
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowStats.forEachIndexed { c, s ->
                    StatTile(
                        label = s.label,
                        value = s.value,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .then(if (firstFocus != null && r == 0 && c == 0) Modifier.focusRequester(firstFocus) else Modifier)
                            .then(if (focusable) Modifier.focusableCard() else Modifier),
                        icon = s.icon,
                        detail = s.detail,
                        valueColor = s.color ?: MaterialTheme.colorScheme.onSurface
                    )
                }
                repeat(columns - rowStats.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    }
}

/** A stat grid this wide (dp) or wider shows four tiles per row, narrower two. */
private const val StatGridWideDp = 480

/** A coloured dot and a label: the key of a segmented bar. */
@Composable
internal fun LegendDot(color: Color, text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(text, style = MaterialTheme.typography.labelSmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/**
 * A label and a value on one line with a full-width bar under them: robust at any text size
 * (top consoles, plays). [color] is usually the console's colour.
 */
@Composable
internal fun BarRow(
    label: String,
    fraction: Float,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                value,
                style = MaterialTheme.typography.bodySmall.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Spacer(Modifier.height(4.dp))
        MeterBar(fraction, height = 6.dp, color = color)
    }
}

/**
 * Shows a button legend that fits what the focused row does. A `null` list leaves the section's
 * default legend; the legend goes away again when the screen does.
 */
@Composable
internal fun PublishLegend(entries: List<LegendEntry>?) {
    val published = remember(entries) { entries?.let { Legend(it) } }
    LaunchedEffect(published) { Gamepad.legendOverride.value = published }
    DisposableEffect(published) { onDispose { if (Gamepad.legendOverride.value === published) Gamepad.legendOverride.value = null } }
}

/**
 * A file name to look the cover of this game up by: one of its own files that carries the title,
 * else the title with a dummy extension (the lookup drops the extension).
 */
internal fun GameEntry.coverFileName(): String = files.firstOrNull { it.name.startsWith(baseName) }?.name ?: "$baseName.rom"

/** The same for a bare title (the wishlist). */
internal fun titleCoverFileName(title: String): String = "$title.rom"
