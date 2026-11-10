package com.tufein.valkyr.ui.screens.home

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tufein.valkyr.R
import com.tufein.valkyr.data.model.DownloadableFileWithTags
import com.tufein.valkyr.data.model.SortOption
import com.tufein.valkyr.ui.components.ConsoleTag
import com.tufein.valkyr.ui.components.CoverCard
import com.tufein.valkyr.ui.components.GameCover
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.formatBytes
import com.tufein.valkyr.ui.components.rememberFocusSource
import com.tufein.valkyr.ui.components.stripExtension
import com.tufein.valkyr.ui.components.tvSized
import com.tufein.valkyr.ui.screens.home.components.slidingCell
import com.tufein.valkyr.ui.theme.LocalDogmatixTokens
import com.tufein.valkyr.ui.theme.tabular
import com.tufein.valkyr.util.FilterLayout
import com.tufein.valkyr.util.LibraryBrowse
import com.tufein.valkyr.util.LibraryBrowse.PrimaryAction
import com.tufein.valkyr.util.LibraryBrowse.ViewMode
import com.tufein.valkyr.ui.navigation.NavRoutes
import com.tufein.valkyr.ui.navigation.OnTabReselect
import com.tufein.valkyr.util.RaStatus

/** Height of every chip and segment of the filter rows (quick chips, console chips, view switch): the 48 dp touch target. */
internal val ChipHeight = FilterLayout.CHIP_HEIGHT.dp

/** Gap between chips, sideways and between wrapped rows; the console chip row uses it too. */
internal val ChipGap = FilterLayout.GAP.dp

@StringRes
internal fun ViewMode.labelRes(): Int = when (this) {
    ViewMode.OVERVIEW -> R.string.lib52_view_overview
    ViewMode.LIST -> R.string.lib52_view_list
    ViewMode.COVERS -> R.string.lib52_view_covers
    ViewMode.CONSOLES -> R.string.home60_view_consoles
}

internal fun ViewMode.iconRes(): Int = when (this) {
    ViewMode.OVERVIEW -> R.drawable.ic_dashboard
    ViewMode.LIST -> R.drawable.ic_list
    ViewMode.COVERS -> R.drawable.ic_grid
    ViewMode.CONSOLES -> R.drawable.ic_gamepad
}

/** The short label of a sort order, as the sort chip shows it. */
@Composable
internal fun sortChipLabel(sort: SortOption): String = stringResource(
    when (sort) {
        SortOption.NAME_ASC -> R.string.sort_name_asc
        SortOption.NAME_DESC -> R.string.sort_name_desc
        SortOption.SIZE_DESC -> R.string.sort_size_desc_short
        SortOption.SIZE_ASC -> R.string.sort_size_asc_short
        SortOption.NEWEST -> R.string.sort_newest_short
    }
)

/**
 * 5.1: the row under the search box. First the view switch (Overview / List / Covers, the last pick is
 * remembered), then one-press chips: Downloaded, Not downloaded, Favourites, the sort order (each press
 * goes to the next one) and, while anything narrows the list, "Clear all". Labels are never cut.
 * 5.2.1: the chips wrap into rows that all start at [horizontalPadding] (the search field's edge) with the same
 * gap sideways and down, instead of scrolling sideways with the last chips out of sight; "Clear all" comes last
 * so the others do not move when it appears. D-pad order is left to right, then down.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickFilterRow(
    modes: List<ViewMode>,
    mode: ViewMode,
    onMode: (ViewMode) -> Unit,
    ownership: LibraryBrowse.Ownership,
    onOwnership: (LibraryBrowse.Ownership) -> Unit,
    favouritesOnly: Boolean,
    onFavourites: (Boolean) -> Unit,
    sort: SortOption,
    onSort: (SortOption) -> Unit,
    onClear: (() -> Unit)?,
    modifier: Modifier = Modifier,
    horizontalPadding: Int = FilterLayout.EDGE_PORTRAIT
) {
    val bigText = FilterLayout.stackViewSwitch(LocalDensity.current.fontScale)
    FlowRow(
        modifier = modifier.focusGroup().padding(horizontal = horizontalPadding.dp),
        horizontalArrangement = Arrangement.spacedBy(ChipGap),
        verticalArrangement = Arrangement.spacedBy(ChipGap),
        itemVerticalAlignment = Alignment.CenterVertically
    ) {
        ViewModeToggle(modes, mode, onMode, modifier = if (bigText) Modifier.fillMaxWidth() else Modifier, fill = bigText)
        QuickChip(
            stringResource(R.string.lib52_chip_downloaded), ownership == LibraryBrowse.Ownership.DOWNLOADED, icon = R.drawable.ic_check,
            onClick = { onOwnership(ownership.toggled(LibraryBrowse.Ownership.DOWNLOADED)) }
        )
        QuickChip(
            stringResource(R.string.lib52_chip_not_downloaded), ownership == LibraryBrowse.Ownership.NOT_DOWNLOADED, icon = R.drawable.ic_arrow_down,
            onClick = { onOwnership(ownership.toggled(LibraryBrowse.Ownership.NOT_DOWNLOADED)) }
        )
        QuickChip(
            stringResource(R.string.lib52_chip_favourites), favouritesOnly, icon = R.drawable.ic_star,
            onClick = { onFavourites(!favouritesOnly) }
        )
        QuickChip(
            stringResource(R.string.lib52_chip_sort, sortChipLabel(sort)), selected = sort != SortOption.NAME_ASC, icon = R.drawable.ic_sort,
            onClick = { onSort(LibraryBrowse.nextSort(sort)) }
        )
        if (onClear != null) QuickChip(stringResource(R.string.lib52_chip_clear), selected = false, icon = R.drawable.ic_clear_filter, onClick = onClear)
    }
}

/** One toggle chip (48 dp): accent-filled while on, a panel tone while off. */
@Composable
internal fun QuickChip(label: String, selected: Boolean, icon: Int?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    val shape = RoundedCornerShape(24.dp)
    val fg = if (selected) scheme.onPrimary else scheme.onSurface
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = ChipHeight)
            .background(if (selected) scheme.primary else scheme.surfaceContainer, shape)
            .border(1.dp, if (selected) Color.Transparent else LocalDogmatixTokens.current.hairline, shape)
            .focusRing(source, 24.dp, onAccent = selected)
            .semantics { role = Role.Checkbox; this.selected = selected }
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/**
 * The Overview / List / Covers switch: one rounded track with a segment per view, the current one filled.
 * [fill] (largest text sizes): the track takes the whole line, the segments share it equally and their labels wrap.
 */
@Composable
internal fun ViewModeToggle(modes: List<ViewMode>, mode: ViewMode, onMode: (ViewMode) -> Unit, modifier: Modifier = Modifier, fill: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier = modifier
            .background(scheme.surfaceContainerHigh, shape)
            .border(1.dp, LocalDogmatixTokens.current.hairline, shape)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        modes.forEach { entry ->
            val selected = entry == mode
            val source = rememberFocusSource()
            val fg = if (selected) scheme.onPrimary else scheme.onSurface
            Row(
                modifier = (if (fill) Modifier.weight(1f) else Modifier)
                    .defaultMinSize(minHeight = ChipHeight - 4.dp)
                    .focusRing(source, 22.dp, onAccent = selected)
                    .background(if (selected) scheme.primary else Color.Transparent, RoundedCornerShape(22.dp))
                    .semantics { role = Role.RadioButton; this.selected = selected }
                    .clickable(interactionSource = source, indication = null, onClick = { onMode(entry) })
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, if (fill) Alignment.CenterHorizontally else Alignment.Start)
            ) {
                if (!fill) Icon(painterResource(entry.iconRes()), contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
                Text(stringResource(entry.labelRes()), style = MaterialTheme.typography.labelLarge, color = fg, textAlign = if (fill) TextAlign.Center else null)
            }
        }
    }
}

/**
 * The big button on a list row: Download (tonal), Play (filled) or Downloading (a progress ring).
 * A tap runs [onClick]; it is not a D-pad stop of its own, because A on the row does the same.
 */
@Composable
internal fun ActionButton(action: PrimaryAction, title: String, progress: () -> Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    val (bg, fg) = when (action) {
        PrimaryAction.PLAY -> scheme.primary to scheme.onPrimary
        PrimaryAction.DOWNLOAD -> scheme.primaryContainer to scheme.onPrimaryContainer
        PrimaryAction.DOWNLOADING -> scheme.surfaceContainerHigh to scheme.onSurface
    }
    val label = stringResource(
        when (action) {
            PrimaryAction.PLAY -> R.string.lib52_action_play
            PrimaryAction.DOWNLOAD -> R.string.lib52_action_download
            PrimaryAction.DOWNLOADING -> R.string.lib52_action_downloading
        }
    )
    val describe = stringResource(
        when (action) {
            PrimaryAction.PLAY -> R.string.lib52_action_play_cd
            PrimaryAction.DOWNLOAD -> R.string.lib52_action_download_cd
            PrimaryAction.DOWNLOADING -> R.string.lib52_action_downloading_cd
        },
        title
    )
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = ChipHeight, minWidth = ChipHeight)
            .background(bg, shape)
            .semantics { contentDescription = describe; role = Role.Button }
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        if (action == PrimaryAction.DOWNLOADING) {
            val track = scheme.primary.copy(alpha = 0.25f)
            val arc = scheme.primary
            Box(
                Modifier.size(20.dp).drawBehind {
                    val stroke = 2.dp.toPx()
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    val inset = Offset(stroke / 2, stroke / 2)
                    drawArc(track, 0f, 360f, false, inset, arcSize, style = Stroke(stroke))
                    val sweep = 360f * progress().coerceIn(0f, 1f)
                    if (sweep > 0f) drawArc(arc, -90f, sweep, false, inset, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            )
        } else {
            Icon(
                painterResource(if (action == PrimaryAction.PLAY) R.drawable.ic_play_arrow else R.drawable.ic_arrow_down),
                contentDescription = null, tint = fg, modifier = Modifier.size(22.dp)
            )
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/**
 * The List view's row: a small cover, the title (two lines at most), the console tag, the size and
 * the marks, and the [ActionButton] at the end. A / tap runs [onPrimary]; long press / X open the details card.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryListRow(
    item: DownloadableFileWithTags,
    action: PrimaryAction,
    favourite: Boolean,
    isNew: Boolean,
    onPrimary: () -> Unit,
    onDetails: () -> Unit,
    progress: () -> Float,
    showCover: Boolean,
    modifier: Modifier = Modifier,
    contentShift: () -> Int = { 0 },
    /** 5.5: marked as completed by the player. */
    completed: Boolean = false,
    /** 5.5: the user's RetroAchievements status in the game (null = nothing to show). */
    raStatus: RaStatus? = null,
    /** 6.0: how many versions of the game this row stands for ("Group versions"); 1 shows nothing. */
    versions: Int = 1
) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    val rom = item.file
    val title = remember(rom.name) { stripExtension(rom.name) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusRing(source, cornerRadius = 12.dp, startShift = contentShift)
            .combinedClickable(interactionSource = source, indication = null, onClick = onPrimary, onLongClick = onDetails)
            .defaultMinSize(minHeight = tvSized(64.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.weight(1f).slidingCell(contentShift),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (showCover) GameCover(
                consoleId = rom.consoleId, fileName = rom.fileName, title = rom.name,
                modifier = Modifier.size(tvSized(40.dp), tvSized(54.dp)), shape = RoundedCornerShape(6.dp), showLabel = false
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConsoleTag(rom.consoleId)
                    if (versions > 1) Pill(pluralStringResource(R.plurals.var60_versions, versions, versions), tone = PillTone.Accent)
                    if (rom.fileSize > 0 && versions <= 1) Text(
                        formatBytes(rom.fileSize),
                        style = MaterialTheme.typography.bodySmall.tabular(),
                        color = scheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    if (favourite) Icon(
                        painterResource(R.drawable.ic_star), contentDescription = stringResource(R.string.favourite),
                        tint = scheme.primary, modifier = Modifier.size(16.dp)
                    )
                    if (completed) Icon(
                        painterResource(R.drawable.ic_verified), contentDescription = stringResource(R.string.done55_completed),
                        tint = scheme.secondary, modifier = Modifier.size(16.dp)
                    )
                    when (raStatus) {
                        RaStatus.MASTERED -> Pill(stringResource(R.string.done55_ra_mastered), tone = PillTone.Warning, icon = R.drawable.ic_trophy)
                        RaStatus.BEATEN -> Pill(stringResource(R.string.done55_ra_beaten), tone = PillTone.Info, icon = R.drawable.ic_trophy)
                        else -> Unit
                    }
                    if (isNew) Pill(stringResource(R.string.new_badge), tone = PillTone.Accent)
                }
            }
        }
        ActionButton(action, title, progress, onPrimary)
    }
}

/**
 * The Covers view: the found games as a grid of cover cards, each with what A / a tap does. Empty and
 * loading states are drawn by [ResultList], which calls this for the rows only.
 */
@Composable
internal fun ResultGrid(
    results: List<DownloadableFileWithTags>,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    loadMoreSize: Int,
    primaryAction: (DownloadableFileWithTags) -> PrimaryAction,
    onPrimary: (DownloadableFileWithTags) -> Unit,
    onDetails: (DownloadableFileWithTags) -> Unit,
    onRowFocused: (DownloadableFileWithTags) -> Unit,
    modifier: Modifier = Modifier,
    firstRowFocus: FocusRequester? = null,
    returnGame: Pair<String, String>? = null,
    returnFocus: FocusRequester? = null,
    isCompleted: (DownloadableFileWithTags) -> Boolean = { false },
    /** 6.0: how many versions a card stands for ("Group versions"). */
    versionsOf: (DownloadableFileWithTags) -> Int = { 1 }
) {
    val state = rememberLazyGridState()
    OnTabReselect(NavRoutes.Home.route) { state.scrollToItem(0) }
    val focusIndex = state.firstVisibleItemIndex
    // After "Load more" focus is lost with the button; land on the first new card.
    val newPageFocus = remember { FocusRequester() }
    var pendingFocusIndex by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(results.size) {
        val target = pendingFocusIndex ?: return@LaunchedEffect
        pendingFocusIndex = null
        if (target >= results.size) return@LaunchedEffect
        state.scrollToItem(target)
        withFrameNanos { }
        runCatching { newPageFocus.requestFocus() }
    }
    val download = stringResource(R.string.lib52_action_download)
    val play = stringResource(R.string.lib52_action_play)
    val busy = stringResource(R.string.lib52_action_downloading)
    LazyVerticalGrid(
        state = state,
        columns = GridCells.Adaptive(minSize = 140.dp),
        modifier = modifier,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(results, key = { _, it -> it.file.id }, contentType = { _, _ -> "cover" }) { index, item ->
            val rom = item.file
            val action = primaryAction(item)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                CoverCard(
                    consoleId = rom.consoleId,
                    fileName = rom.fileName,
                    title = remember(rom.name) { stripExtension(rom.name) },
                    onClick = { onPrimary(item) },
                    onLongClick = { onDetails(item) },
                    owned = action == PrimaryAction.PLAY,
                    completed = isCompleted(item),
                    versions = versionsOf(item),
                    width = 128.dp,
                    coverHeight = 174.dp,
                    actionLabel = when (action) {
                        PrimaryAction.PLAY -> play
                        PrimaryAction.DOWNLOAD -> download
                        PrimaryAction.DOWNLOADING -> busy
                    },
                    modifier = when {
                        index == pendingFocusIndex -> Modifier.focusRequester(newPageFocus)
                        index == focusIndex && firstRowFocus != null -> Modifier.focusRequester(firstRowFocus)
                        else -> Modifier
                    }.then(
                        if (returnFocus != null && returnGame != null && returnGame.first == rom.consoleId && returnGame.second == rom.fileName)
                            Modifier.focusRequester(returnFocus) else Modifier
                    ).onFocusChanged { if (it.isFocused) onRowFocused(item) }
                )
            }
        }
        if (hasMore) item(key = "load-more", span = { GridItemSpan(maxLineSpan) }, contentType = "load-more") {
            Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                if (isLoadingMore) CircularProgressIndicator(modifier = Modifier.size(30.dp), strokeWidth = 3.dp)
                else LoadMoreButton(loadMoreSize) { pendingFocusIndex = results.size; onLoadMore() }
            }
        }
    }
}
