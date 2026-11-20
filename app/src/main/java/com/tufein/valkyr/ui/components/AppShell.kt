package com.tufein.valkyr.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.semantics.Role
import com.tufein.valkyr.ui.navigation.tabRouteFor
import com.tufein.valkyr.ui.theme.Dimens
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.tufein.valkyr.ui.theme.LocalReduceMotion
import com.tufein.valkyr.ui.theme.Motion
import com.tufein.valkyr.ui.theme.tabular
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import com.tufein.valkyr.ui.common.Gamepad
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tufein.valkyr.BuildConfig
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.navigation.NavRoutes
import com.tufein.valkyr.ui.screens.cloud.saves.CloudStatusIndicator

/** App title with the version tucked under it: tiny, faint, right-aligned to the wordmark. */
@Composable
private fun Wordmark(modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        val title = stringResource(R.string.topbar_title)
        val accent = MaterialTheme.colorScheme.primary
        // "Valkyr": the initial V in the accent colour.
        Text(
            buildAnnotatedString {
                if (title.isNotEmpty()) {
                    withStyle(SpanStyle(color = accent)) { append(title.take(1)) }
                    append(title.drop(1))
                }
            },
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold)
        )
        // Zero layout height + unbounded measure: the wordmark keeps its original centering in
        // the row (as if this line did not exist) and the version overflows below it, pulled up
        // 5dp so it hugs the logo.
        Text(
            BuildConfig.VERSION_NAME,
            fontSize = 9.sp,
            lineHeight = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier
                .height(0.dp)
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .offset(y = (-5).dp)
        )
    }
}

/**
 * 3.8.0 header, in both orientations: the wordmark, then cloud and rescan status on the right.
 * Slim in landscape (the rail on the left already carries the sections), a little taller upright.
 */
@Composable
fun ShellHeader(
    slim: Boolean,
    trailing: @Composable (() -> Unit)? = null,
    onOpenCloud: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = tvSized(if (slim) 44.dp else 56.dp))
            .padding(start = 20.dp, end = Dimens.gutter),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Wordmark()
        Spacer(Modifier.weight(1f))
        CloudStatusIndicator(onClick = onOpenCloud, modifier = Modifier.padding(end = 8.dp))
        RescanIndicator(modifier = Modifier.widthIn(max = if (slim) 260.dp else 200.dp))
        trailing?.let { Spacer(Modifier.padding(start = 12.dp)); it() }
    }
}

/**
 * Landscape (3.8.0): a slim rail on the left with the four sections, so no vertical space goes to
 * a bar. It grows with the longest label (big text, long languages) up to [Dimens.railMaxWidth];
 * longer labels wrap, they are never cut off.
 */
@Composable
fun SideRail(currentRoute: String, activeDownloads: Int, onSelect: (NavRoutes) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val litRoute = tabRouteFor(currentRoute)
    Row(modifier = Modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(IntrinsicSize.Max)
                .widthIn(min = tvSized(Dimens.railMinWidth), max = tvSized(Dimens.railMaxWidth))
                .selectableGroup()
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            NavRoutes.tabs.forEach { route ->
                NavEntry(
                    route = route,
                    selected = route.route == litRoute,
                    badge = if (route == NavRoutes.Downloads) activeDownloads else 0,
                    onSelect = onSelect,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        VerticalDivider(color = scheme.outlineVariant, thickness = 1.dp)
    }
}

/** 0 → 1 when [selected] turns on (and back), animated and read only while drawing (3.9.0: [Motion.FAST]). */
@Composable
private fun rememberSelectionProgress(selected: Boolean): () -> Float {
    val reduce = LocalReduceMotion.current
    val progress = remember { Animatable(if (selected) 1f else 0f) }
    LaunchedEffect(selected, reduce) {
        val target = if (selected) 1f else 0f
        if (reduce) progress.snapTo(target) else progress.animateTo(target, Motion.spec(false, Motion.FAST))
    }
    return { progress.value }
}

/** The number of running downloads, on the Downloads entry. */
@Composable
private fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Text(
        count.toString(),
        style = MaterialTheme.typography.labelSmall.tabular(),
        color = scheme.onPrimary,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(scheme.primary)
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

/**
 * One section in the bottom bar or the rail: icon on a pill that widens in when the section is
 * the current one, the label always visible under it (it wraps, never ellipsized), at least
 * [Dimens.navItem] high. The whole entry is the touch and D-pad target; it takes the section's
 * [Gamepad.tabFocus] requester so B and ◀ from a hidden cursor in landscape land on it (3.9.0:
 * other wake-up presses enter the screen instead, see [Gamepad.contentEntry]).
 */
@Composable
private fun NavEntry(
    route: NavRoutes,
    selected: Boolean,
    badge: Int,
    onSelect: (NavRoutes) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    val pill = rememberSelectionProgress(selected)
    val pillColor = scheme.primaryContainer
    val tint = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant
    val shape = RoundedCornerShape(Dimens.tile)
    Column(
        modifier = modifier
            .heightIn(min = tvSized(Dimens.navItem))
            .focusRequester(Gamepad.tabFocus.getValue(route.route))
            .clip(shape)
            .focusRing(source, Dimens.tile)
            .selectable(
                selected = selected,
                interactionSource = source,
                indication = null,
                role = Role.Tab,
                onClick = { onSelect(route) }
            )
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(tvSized(Dimens.indicatorWidth), tvSized(Dimens.indicatorHeight))
                .drawBehind {
                    val p = pill()
                    if (p > 0f) {
                        val h = size.height
                        val w = size.width * (0.5f + 0.5f * p)
                        drawRoundRect(
                            pillColor.copy(alpha = pillColor.alpha * p),
                            topLeft = Offset((size.width - w) / 2, 0f),
                            size = Size(w, h),
                            cornerRadius = CornerRadius(h / 2)
                        )
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(painterResource(route.icon), contentDescription = null, tint = tint, modifier = Modifier.size(tvSized(24.dp)))
            if (badge > 0) {
                CountBadge(
                    badge,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 2.dp, y = (-4).dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(route.labelRes),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
        )
    }
}

/** Portrait bottom bar with the four sections (3.8.0: Games, Downloads, Sources, More). */
@Composable
fun BottomTabs(currentRoute: String, activeDownloads: Int, onSelect: (NavRoutes) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val litRoute = tabRouteFor(currentRoute)
    Column {
        HorizontalDivider(color = scheme.outlineVariant, thickness = 1.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.background)
                .selectableGroup()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            NavRoutes.tabs.forEach { route ->
                NavEntry(
                    route = route,
                    selected = route.route == litRoute,
                    badge = if (route == NavRoutes.Downloads) activeDownloads else 0,
                    onSelect = onSelect,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun legendFor(route: String): List<LegendEntry> {
    val section = LegendEntry("ZL · ZR", stringResource(R.string.pad_section))
    val back = LegendEntry("B", stringResource(R.string.pad_back))
    // 3.9.0: on these screens L1 / R1 page through the list (see PadFocus.shouldersPage).
    val page = LegendEntry("LB · RB", stringResource(R.string.controls39_pad_page))
    return when (route) {
        NavRoutes.Home.route -> listOf(
            LegendEntry("A", stringResource(R.string.pad_download)), back,
            LegendEntry("X", stringResource(R.string.pad_filters)),
            LegendEntry("Y", stringResource(R.string.pad_search)), section
        )
        // Downloads fits four entries on one line in 4:3; SELECT (multi-selection) earns B's slot.
        NavRoutes.Downloads.route -> listOf(
            LegendEntry("A", stringResource(R.string.pad_retry)),
            LegendEntry("X", stringResource(R.string.pad_delete)),
            selectLegendEntry(R.string.pad_tick), section
        )
        NavRoutes.Sources.route, NavRoutes.SourcesTracker.route -> listOf(LegendEntry("A", stringResource(R.string.pad_open)), back, page, section)
        NavRoutes.TorrentTrackers.route -> listOf(LegendEntry("A", stringResource(R.string.pad_change)), back, section)
        NavRoutes.Duplicates.route -> listOf(LegendEntry("A", stringResource(R.string.pad_delete)), back, page, section)
        NavRoutes.Overview.route -> listOf(LegendEntry("A", stringResource(R.string.pad_rescan)), back, section)
        NavRoutes.Tools.route, NavRoutes.More.route -> listOf(LegendEntry("A", stringResource(R.string.pad_open)), back, page, section)
        NavRoutes.Sets.route -> listOf(LegendEntry("A", stringResource(R.string.pad_apply)), back, section)
        NavRoutes.Storage.route -> listOf(LegendEntry("A", stringResource(R.string.pad_delete)), back, page, section)
        NavRoutes.Wishlist.route -> listOf(LegendEntry("A", stringResource(R.string.pad_open)), back, page, section)
        NavRoutes.Collections.route, NavRoutes.Switch.route, NavRoutes.Dat.route, NavRoutes.Bios.route, NavRoutes.Stats.route, NavRoutes.RetroAchievements.route, NavRoutes.Profiles.route, NavRoutes.Frontends.route, NavRoutes.Cloud.route, NavRoutes.CollectionGoals.route, NavRoutes.History.route, NavRoutes.Health.route, NavRoutes.Recap.route, NavRoutes.BestGames.route, NavRoutes.FrontendMetadata.route, NavRoutes.BetterVersions.route, NavRoutes.FreeSpace.route -> listOf(LegendEntry("A", stringResource(R.string.pad_open)), back, page, section)
        NavRoutes.Files.route -> listOf(LegendEntry("A", stringResource(R.string.pad_open)), LegendEntry("B", stringResource(R.string.files_up)), page, section)
        NavRoutes.Settings.route, NavRoutes.Romm.route, NavRoutes.SaveSync.route, NavRoutes.CloudBackup.route -> listOf(
            LegendEntry("A", stringResource(R.string.pad_change)),
            LegendEntry("◀ ▶", stringResource(R.string.pad_adjust)), back, section
        )
        else -> listOf(back)
    }
}

@Composable
fun NoGamepadHint(trailing: @Composable (() -> Unit)? = null) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 30.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.pad_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}
