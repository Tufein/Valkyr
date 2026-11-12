package com.tufein.valkyr.ui.components

import com.tufein.valkyr.ui.theme.accentInk
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.theme.LocalDogmatixTokens
import com.tufein.valkyr.ui.theme.StatusInfo
import com.tufein.valkyr.ui.theme.tabular

/*
 * The 5.0 component kit: panels, icon tiles, pills, titles, empty states and buttons that every
 * screen shares, so the whole app has one look. See also Charts.kt and CoverImage.kt.
 */

/**
 * A raised surface for a group of content: panel colour, hairline border, 12 dp corners and (in
 * dark themes) a faint light along the top edge.
 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /** Accent-tinted variant for a highlighted card (warnings use [PanelTone.Danger]). */
    tone: PanelTone = PanelTone.Normal,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalDogmatixTokens.current
    val fill = when (tone) {
        PanelTone.Normal -> scheme.surfaceContainer
        PanelTone.Raised -> scheme.surfaceContainerHigh
        PanelTone.Accent -> scheme.primaryContainer
        PanelTone.Danger -> scheme.errorContainer
    }
    val border = when (tone) {
        PanelTone.Accent -> scheme.primary.copy(alpha = 0.35f)
        PanelTone.Danger -> scheme.error.copy(alpha = 0.35f)
        else -> tokens.hairline
    }
    val highlight = tokens.highlight
    Column(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .then(
                if (highlight.alpha > 0f) Modifier.drawWithCache {
                    val brush = Brush.verticalGradient(listOf(highlight, Color.Transparent), endY = 48.dp.toPx())
                    onDrawBehind { drawRect(brush) }
                } else Modifier
            )
            .border(1.dp, border, shape)
            .padding(contentPadding),
        verticalArrangement = verticalArrangement,
        content = content
    )
}

enum class PanelTone { Normal, Raised, Accent, Danger }

/** An icon on a soft accent tile: the leading mark of tool rows, setting sections and cloud cards. */
@Composable
fun IconTile(
    icon: Int,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
    /** Tile colour; null = the accent container. */
    container: Color? = null,
    /** Icon colour; null = the text colour that goes with the accent container. */
    tint: Color? = null,
    contentDescription: String? = null
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(container ?: scheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painterResource(icon),
            contentDescription = contentDescription,
            tint = tint ?: scheme.onPrimaryContainer,
            modifier = Modifier.size(size * 0.56f)
        )
    }
}

/** The colour pair of a [Pill]. */
@Immutable
sealed class PillTone {
    object Neutral : PillTone()
    object Accent : PillTone()
    object Strong : PillTone()
    object Success : PillTone()
    object Warning : PillTone()
    object Danger : PillTone()
    object Info : PillTone()
    /** A console's own colour (see ConsoleStyle). */
    data class Tint(val color: Color) : PillTone()
}

/** Background and text colour of a [PillTone] in the current theme. */
@Composable
fun pillColors(tone: PillTone): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    val dark = LocalDogmatixTokens.current.isDark
    fun tinted(c: Color) = lerp(scheme.surfaceContainerHigh, c, if (dark) 0.26f else 0.18f) to
        (if (dark) lerp(c, Color.White, 0.25f) else lerp(c, Color.Black, 0.45f))
    return when (tone) {
        PillTone.Neutral -> scheme.surfaceContainerHigh to scheme.onSurfaceVariant
        PillTone.Accent -> scheme.primaryContainer to scheme.onPrimaryContainer
        PillTone.Strong -> scheme.primary to scheme.onPrimary
        PillTone.Success -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        PillTone.Warning -> tinted(Color(0xFFFFB300))
        PillTone.Danger -> scheme.errorContainer to scheme.onErrorContainer
        PillTone.Info -> tinted(StatusInfo)
        is PillTone.Tint -> tinted(tone.color)
    }
}

/** A small rounded label: status, badge, tag or count. */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    tone: PillTone = PillTone.Neutral,
    icon: Int? = null
) {
    val (bg, fg) = pillColors(tone)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        icon?.let { Icon(painterResource(it), contentDescription = null, tint = fg, modifier = Modifier.size(12.dp)) }
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1)
    }
}

/** The "opens a screen" arrow at the end of a row. */
@Composable
fun NavChevron(modifier: Modifier = Modifier) {
    Icon(
        painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(20.dp)
    )
}

/** Heading over a group: small accent capitals with an optional icon. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, icon: Int? = null) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        icon?.let { Icon(painterResource(it), contentDescription = null, tint = accentInk(), modifier = Modifier.size(16.dp)) }
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = accentInk(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** A screen's title (and optional one-line subtitle), with room for actions at the end. */
@Composable
fun ScreenTitle(
    text: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: Int? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        icon?.let { IconTile(it, size = 38.dp) }
        Column(modifier = Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke()
    }
}

/**
 * Nothing to show yet: an illustration or a big tinted icon, a title, one line of help and an
 * optional primary action.
 */
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    /** A larger picture (for example the Valkyr illustration, R.drawable.brand_illustration) instead of the icon. */
    illustration: Int? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionFocus: FocusRequester? = null
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        when {
            illustration != null -> Image(
                painterResource(illustration),
                contentDescription = null,
                modifier = Modifier.widthIn(max = 180.dp).heightIn(max = 120.dp)
            )
            icon != null -> Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(34.dp))
            }
        }
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp))
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(4.dp))
            PrimaryButton(actionLabel, onAction, modifier = if (actionFocus != null) Modifier.focusRequester(actionFocus) else Modifier)
        }
    }
}

/** A number that matters, with its label (and optionally an icon and a second line). */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    detail: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Panel(modifier = modifier, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            icon?.let { IconTile(it, size = 26.dp) }
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(6.dp))
        Text(value, style = MaterialTheme.typography.headlineMedium.tabular(), color = valueColor, maxLines = 1)
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

/** The main action of a screen or dialog: accent fill, 48 dp, optional leading icon. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    enabled: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .focusRing(source, cornerRadius = 12.dp, onAccent = true, fill = false)
            .clip(shape)
            .background(if (enabled) scheme.primary else scheme.surfaceContainerHigh)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        val fg = if (enabled) scheme.onPrimary else scheme.onSurfaceVariant
        icon?.let { Icon(painterResource(it), contentDescription = null, tint = fg, modifier = Modifier.size(18.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

/** The colour of an [ActionPill]. */
enum class ActionTone { Neutral, Accent, Danger }

/**
 * A secondary action (48 dp touch target, min height grows with the text size): the pill buttons of
 * Settings, Tools and Downloads, now with an optional icon and tone.
 */
@Composable
fun ActionPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    tone: ActionTone = ActionTone.Neutral,
    enabled: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    val (bg, fg) = when (tone) {
        ActionTone.Neutral -> scheme.surfaceContainerHigh to scheme.onSurface
        ActionTone.Accent -> scheme.primaryContainer to scheme.onPrimaryContainer
        ActionTone.Danger -> scheme.errorContainer to scheme.onErrorContainer
    }
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .focusRing(source, cornerRadius = 10.dp)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val color = if (enabled) fg else scheme.onSurfaceVariant.copy(alpha = 0.6f)
        icon?.let { Icon(painterResource(it), contentDescription = null, tint = color, modifier = Modifier.size(16.dp)) }
        Text(label, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/**
 * 3.8: the one big action of a page (the game page's Play / Download): accent fill, at least
 * 56 dp high, 18 dp corners and a larger label that may wrap (never cut). Give it
 * `Modifier.fillMaxWidth()` where the thumb should reach it anywhere.
 */
@Composable
fun LargePrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    enabled: Boolean = true,
    /** A narrow slot (the landscape game page's left column): no icon and less side padding, so the label keeps its room. */
    compact: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 56.dp)
            .focusRing(source, cornerRadius = 18.dp, width = 2.dp, onAccent = true, fill = false)
            .clip(shape)
            .background(if (enabled) scheme.primary else scheme.surfaceContainerHigh)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = if (compact) 10.dp else 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
    ) {
        val fg = if (enabled) scheme.onPrimary else scheme.onSurfaceVariant
        if (!compact) icon?.let { Icon(painterResource(it), contentDescription = null, tint = fg, modifier = Modifier.size(24.dp)) }
        Text(text, style = MaterialTheme.typography.titleMedium, color = fg, textAlign = TextAlign.Center)
    }
}

/**
 * 3.8: a round quick action with its short label under it (the game page's favourite, home
 * screen, share, more). The circle is 52 dp and the whole tile at least 64 dp wide, so it is an
 * easy thumb and D-pad target; [selected] fills the circle with the accent (a starred game).
 * [contentDescription] is read out with [label] when the label alone says too little (a state).
 */
@Composable
fun RoundAction(
    icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    tone: ActionTone = ActionTone.Neutral,
    contentDescription: String? = null,
    enabled: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    val (bg, fg) = when {
        selected -> scheme.primary to scheme.onPrimary
        tone == ActionTone.Accent -> scheme.primaryContainer to scheme.onPrimaryContainer
        tone == ActionTone.Danger -> scheme.errorContainer to scheme.onErrorContainer
        else -> scheme.surfaceContainerHigh to scheme.onSurface
    }
    Column(
        modifier = modifier
            .widthIn(min = 64.dp)
            .focusRing(source, cornerRadius = 16.dp)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(bg)
                .border(1.dp, LocalDogmatixTokens.current.hairline, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painterResource(icon),
                contentDescription = contentDescription,
                tint = if (enabled) fg else scheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) scheme.onSurface else scheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
