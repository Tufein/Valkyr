package com.tufein.valkyr.ui.screens.home

import com.tufein.valkyr.ui.theme.accentInk
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.tufein.valkyr.ui.screens.cloud.saves.CloudSavesSection
import com.tufein.valkyr.ui.screens.cloud.saves.ContinuePlayingShelf
import com.tufein.valkyr.ui.screens.cloud.sections.AchievementsSection
import com.tufein.valkyr.ui.screens.cloud.sections.RommGameSection
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.EmptyState
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.PrimaryButton
import com.tufein.valkyr.ui.components.pillColors
import com.tufein.valkyr.ui.screens.home.components.CoverGap
import com.tufein.valkyr.ui.screens.home.components.DenseRowGap
import com.tufein.valkyr.ui.screens.home.components.DiscoverBlock
import com.tufein.valkyr.ui.screens.home.components.RowGap
import com.tufein.valkyr.util.LibraryDiscovery
import com.tufein.valkyr.ui.screens.home.components.TableCoverWidth
import com.tufein.valkyr.ui.theme.LocalDogmatixTokens
import com.tufein.valkyr.ui.theme.consoleColor
import com.tufein.valkyr.ui.theme.tabular
import com.tufein.valkyr.util.Constants
import com.tufein.valkyr.util.LibraryBrowse
import java.text.NumberFormat
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.layoutId
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Spacer
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.components.stripExtension
import com.tufein.valkyr.data.model.DownloadableFileWithTags
import com.tufein.valkyr.data.model.SortOption
import com.tufein.valkyr.data.model.SourceFilter
import com.tufein.valkyr.ui.common.Gamepad
import com.tufein.valkyr.ui.common.GamepadButton
import com.tufein.valkyr.ui.common.Legend
import com.tufein.valkyr.ui.components.LegendEntry
import com.tufein.valkyr.ui.components.selectLegendEntry
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.rememberFocusSource
import com.tufein.valkyr.ui.components.swapFaceButtons
import com.tufein.valkyr.ui.components.ContentEntry
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.ui.components.requestFocusWhenReady
import com.tufein.valkyr.ui.navigation.NavRoutes
import com.tufein.valkyr.ui.screens.home.components.BulkDownloadDialog
import com.tufein.valkyr.ui.screens.home.components.CollectionPickerDialog
import com.tufein.valkyr.ui.screens.home.components.FilterOption
import com.tufein.valkyr.ui.screens.home.components.FilterPanel
import com.tufein.valkyr.util.FilterLayout
import com.tufein.valkyr.ui.screens.home.components.FilterRowSpec
import com.tufein.valkyr.ui.screens.home.components.GameDetailsDialog
import com.tufein.valkyr.ui.screens.home.components.RomRow
import com.tufein.valkyr.ui.screens.home.components.SaveViewDialog
import com.tufein.valkyr.ui.screens.home.components.SearchField
import com.tufein.valkyr.ui.secondscreen.SecondScreenState
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.LetterJump
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.tufein.valkyr.ui.navigation.OnTabReselect
import com.tufein.valkyr.util.RaStatus

@Composable
internal fun resultsLabel(count: Int, hasMore: Boolean): String =
    stringResource(if (hasMore) R.string.results_count_more else R.string.results_count, count)

/** Column titles of the landscape table, over a hairline; the name column follows the panel slide. */
@Composable
internal fun TableHeader(contentShift: () -> Int = { 0 }, showCover: Boolean = false) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.labelMedium
    val hairline = LocalDogmatixTokens.current.hairline
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .drawBehind {
                val y = size.height - 0.5.dp.toPx()
                drawLine(hairline, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.weight(1f).clipToBounds().offset { IntOffset(contentShift(), 0) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The cover column of the rows (cover + its gap), so "Name" stands over the names.
            if (showCover) Spacer(Modifier.width(TableCoverWidth + CoverGap))
            Text(stringResource(R.string.column_name), style = style, color = color, maxLines = 1)
        }
        Text(stringResource(R.string.column_tags), style = style, color = color, modifier = Modifier.width(300.dp))
        Text(stringResource(R.string.column_size), style = style, color = color, textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
    }
}

@Composable
internal fun ResultList(
    results: List<DownloadableFileWithTags>,
    compact: Boolean,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    getConsoleName: (String) -> String,
    onFileClick: (DownloadableFileWithTags) -> Unit,
    isOwned: (DownloadableFileWithTags) -> Boolean,
    isFavourite: (DownloadableFileWithTags) -> Boolean,
    isDownloading: (DownloadableFileWithTags) -> Boolean,
    isNew: (DownloadableFileWithTags) -> Boolean,
    onRowFocused: (DownloadableFileWithTags) -> Unit,
    onRowLongClick: (DownloadableFileWithTags) -> Unit,
    modifier: Modifier = Modifier,
    isOnRomm: (DownloadableFileWithTags) -> Boolean = { false },
    hasAchievements: (DownloadableFileWithTags) -> Boolean = { false },
    /** 5.5: the player's completed flag, and the user's RA status (null = none / RA off) of a game. */
    isCompleted: (DownloadableFileWithTags) -> Boolean = { false },
    raStatusOf: (DownloadableFileWithTags) -> RaStatus? = { null },
    /** What the user searched for, offered for the wishlist when nothing is found. */
    query: String = "",
    onAddWish: (() -> Unit)? = null,
    /** The list is sorted by name, so ◀ ▶ jumps by first letter (otherwise by ten rows). */
    byName: Boolean = true,
    firstRowFocus: FocusRequester? = null,
    /** 3.9.0: the row of this game (console id, file name) takes [returnFocus]. */
    returnGame: Pair<String, String>? = null,
    returnFocus: FocusRequester? = null,
    contentShift: () -> Int = { 0 },
    /** 5.0: RA achievement count for the badge (0 = unknown). */
    achievementCount: (DownloadableFileWithTags) -> Int = { 0 },
    /** 5.0: cover thumbnails in front of the names. */
    showCover: Boolean = false,
    /** 5.0: download progress by file name, read while drawing the badge ring only. */
    downloadProgress: (String) -> Float = { 0f },
    /** 5.0: a search is running (placeholder rows instead of "nothing matches"). */
    isSearching: () -> Boolean = { false },
    /** 5.0: nothing is indexed at all (the empty state then points to Sources). */
    libraryEmpty: () -> Boolean = { false },
    onGoToSources: (() -> Unit)? = null,
    /** Clears filters and search; null when nothing narrows the list. */
    onClearFilters: (() -> Unit)? = null,
    /** How many rows "Load more" adds. */
    loadMoreSize: Int = Constants.DEFAULT_MAX_SEARCH_RESULTS,
    /** Room for the Valkyr illustration in the empty library state (portrait). */
    illustrated: Boolean = false,
    /** 6.0: compact lists (tighter rows). */
    dense: Boolean = false,
    /** 3.5.0: shown under the empty-library state (the "Set up a new handheld" hint). */
    emptyLibraryExtra: (@Composable () -> Unit)? = null,
    /** 5.1: the Library tab's view; the overview's rows (and the list at hand) stay as they were. */
    viewMode: LibraryBrowse.ViewMode = LibraryBrowse.ViewMode.OVERVIEW,
    /** 5.1: what a game's button says (Download / Play / Downloading) in the List and Covers views. */
    primaryAction: (DownloadableFileWithTags) -> LibraryBrowse.PrimaryAction = { LibraryBrowse.PrimaryAction.DOWNLOAD },
    /** 6.0: how many versions of a game a row stands for ("Group versions"); 1 = a single game. */
    versionsOf: (DownloadableFileWithTags) -> Int = { 1 }
) {
    if (results.isEmpty()) {
        // The flags are read only here: while the list has rows, a new search or a library change
        // does not recompose it.
        when {
            isSearching() -> SkeletonRows(compact, showCover, modifier.fillMaxWidth())
            libraryEmpty() -> EmptyBox(modifier) {
                EmptyState(
                    title = stringResource(R.string.home_empty_library_title),
                    message = stringResource(R.string.home_empty_library_message),
                    icon = if (illustrated) null else R.drawable.ic_library,
                    illustration = if (illustrated) R.drawable.brand_illustration else null,
                    actionLabel = if (onGoToSources != null) stringResource(R.string.home_go_to_sources) else null,
                    onAction = onGoToSources,
                    actionFocus = firstRowFocus
                )
                emptyLibraryExtra?.invoke()
            }
            else -> EmptyBox(modifier) {
                val wish = onAddWish != null && query.trim().length >= 2
                EmptyState(
                    title = stringResource(R.string.no_results),
                    message = stringResource(R.string.home_no_results_message),
                    icon = R.drawable.ic_search_off,
                    actionLabel = if (wish) stringResource(R.string.wishlist_add_query, query.trim()) else null,
                    onAction = if (wish) onAddWish else null,
                    actionFocus = if (wish) firstRowFocus else null
                )
                if (onClearFilters != null) {
                    ActionPill(
                        stringResource(R.string.home_clear_filters),
                        onClick = onClearFilters,
                        icon = R.drawable.ic_clear_filter,
                        // RB from the filters lands here when there is no wishlist button.
                        modifier = if (!wish && firstRowFocus != null) Modifier.focusRequester(firstRowFocus) else Modifier
                    )
                }
            }
        }
        return
    }
    if (LibraryBrowse.showGrid(viewMode)) {
        ResultGrid(
            results = results,
            hasMore = hasMore,
            isLoadingMore = isLoadingMore,
            onLoadMore = onLoadMore,
            loadMoreSize = loadMoreSize,
            primaryAction = primaryAction,
            onPrimary = onFileClick,
            onDetails = onRowLongClick,
            onRowFocused = onRowFocused,
            modifier = modifier,
            firstRowFocus = firstRowFocus,
            returnGame = returnGame,
            returnFocus = returnFocus,
            isCompleted = isCompleted,
            versionsOf = versionsOf
        )
        return
    }
    val actionRows = LibraryBrowse.showActionRows(viewMode)
    val listState = rememberLazyListState()
    // 5.2: tapping the Library tab again brings the list back to the top.
    OnTabReselect(NavRoutes.Home.route) { listState.scrollToItem(0) }
    val focusIndex = listState.firstVisibleItemIndex
    // ◀ ▶ jump through a long list by first letter: scroll there, then focus that row.
    val jumpScope = rememberCoroutineScope()
    val jumpFocus = remember { FocusRequester() }
    var jumpTarget by remember { mutableStateOf<Int?>(null) }
    var currentIndex by remember { mutableStateOf(0) }
    var jumpJob by remember { mutableStateOf<Job?>(null) }
    // After "Load more" the button is replaced by a spinner and focus is lost:
    // remember where the new page starts and land on its first row once it arrives.
    var pendingFocusIndex by remember { mutableStateOf<Int?>(null) }
    val newPageFocus = remember { FocusRequester() }
    LaunchedEffect(results.size) {
        val target = pendingFocusIndex ?: return@LaunchedEffect
        if (target >= results.size) {
            pendingFocusIndex = null
            return@LaunchedEffect
        }
        listState.scrollToItem(target)
        withFrameNanos { }
        newPageFocus.requestFocusWhenReady(3)
        pendingFocusIndex = null
    }
    LazyColumn(
        state = listState,
        modifier = modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || (event.key != Key.DirectionLeft && event.key != Key.DirectionRight)) return@onPreviewKeyEvent false
            val forward = event.key == Key.DirectionRight
            val target = if (byName) LetterJump.target(results.map { stripExtension(it.file.name) }, currentIndex, forward)
            else LetterJump.step(results.size, currentIndex, forward)
            if (target != currentIndex) {
                // Quick presses chain: the next one starts from where this one is going, and
                // only the last jump is carried out.
                currentIndex = target
                jumpJob?.cancel()
                jumpJob = jumpScope.launch {
                    jumpTarget = target
                    try {
                        listState.scrollToItem(target)
                        withFrameNanos { }
                        jumpFocus.requestFocusWhenReady(3)
                    } finally {
                        if (jumpTarget == target) jumpTarget = null
                    }
                }
            }
            true
        },
        contentPadding = PaddingValues(top = 2.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (dense) DenseRowGap else RowGap)
    ) {
        itemsIndexed(results, key = { _, it -> it.file.id }, contentType = { _, _ -> "rom-row" }) { index, item ->
            // Per-row lambdas are remembered (keyed on the row and the caller's lambda) so a recomposition
            // of the list does not hand every visible row new lambda instances and recompose it.
            val fileName = item.file.fileName
            val rowClick = remember(item, onFileClick) { { onFileClick(item) } }
            val rowLongClick = remember(item, onRowLongClick) { { onRowLongClick(item) } }
            val rowProgress = remember(fileName, downloadProgress) { { downloadProgress(fileName) } }
            // RB from the filters lands on the first row currently on screen.
            val rowModifier = when {
                    index == pendingFocusIndex -> Modifier.focusRequester(newPageFocus)
                    index == jumpTarget -> Modifier.focusRequester(jumpFocus)
                    index == focusIndex && firstRowFocus != null -> Modifier.focusRequester(firstRowFocus)
                    else -> Modifier
                }.then(
                    if (returnFocus != null && returnGame != null && returnGame.first == item.file.consoleId && returnGame.second == item.file.fileName)
                        Modifier.focusRequester(returnFocus) else Modifier
                ).onFocusChanged { if (it.isFocused) { currentIndex = index; onRowFocused(item) } }
            if (actionRows) LibraryListRow(
                item = item,
                action = primaryAction(item),
                favourite = isFavourite(item),
                isNew = isNew(item),
                onPrimary = rowClick,
                onDetails = rowLongClick,
                progress = rowProgress,
                showCover = showCover,
                modifier = rowModifier,
                contentShift = contentShift,
                completed = isCompleted(item),
                raStatus = raStatusOf(item),
                versions = versionsOf(item)
            ) else RomRow(
                item = item,
                consoleName = getConsoleName(item.file.consoleId),
                compact = compact,
                onClick = rowClick,
                onLongClick = rowLongClick,
                owned = isOwned(item),
                onRomm = isOnRomm(item),
                favourite = isFavourite(item),
                downloading = isDownloading(item),
                isNew = isNew(item),
                achievements = hasAchievements(item),
                contentShift = contentShift,
                achievementCount = achievementCount(item),
                showCover = showCover,
                dense = dense,
                downloadProgress = rowProgress,
                modifier = rowModifier,
                completed = isCompleted(item),
                raStatus = raStatusOf(item),
                versions = versionsOf(item)
            )
        }
        if (hasMore) {
            item(key = "load-more", contentType = "load-more") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoadingMore) {
                        CircularProgressIndicator(modifier = Modifier.size(30.dp), strokeWidth = 3.dp)
                    } else {
                        LoadMoreButton(loadMoreSize) { pendingFocusIndex = results.size; onLoadMore() }
                    }
                }
            }
        }
    }
}

/** Centres an empty state in the list's place, scrolling it when the screen is too short for it. */
@Composable
internal fun EmptyBox(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content
        )
    }
}

/** Widths of the name bars of [SkeletonRows]: varied, so the placeholder reads as a list of names. */
internal val SkeletonWidths = listOf(0.62f, 0.44f, 0.71f, 0.52f, 0.58f, 0.38f, 0.66f, 0.49f, 0.6f, 0.42f)

/**
 * Placeholder rows while the first page of a search loads: static bars in the panel tones, laid out
 * like the real rows (no shimmer: nothing animates, nothing recomposes).
 */
@Composable
internal fun SkeletonRows(compact: Boolean, showCover: Boolean, modifier: Modifier) {
    val strong = MaterialTheme.colorScheme.surfaceContainerHigh
    val soft = MaterialTheme.colorScheme.surfaceContainer
    val bar = RoundedCornerShape(6.dp)
    val chip = RoundedCornerShape(4.dp)
    val rowHeight = when {
        compact && showCover -> 48.dp
        compact -> 46.dp
        showCover -> 72.dp
        else -> 64.dp
    }
    Column(modifier = modifier.clipToBounds().padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SkeletonWidths.forEach { w ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .padding(horizontal = if (compact) 14.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (showCover) {
                    Box(
                        Modifier
                            .size(if (compact) TableCoverWidth else 42.dp, if (compact) 40.dp else 56.dp)
                            .background(strong, RoundedCornerShape(if (compact) 5.dp else 7.dp))
                    )
                }
                if (compact) {
                    Box(Modifier.weight(1f)) { Box(Modifier.fillMaxWidth(w).height(12.dp).background(strong, bar)) }
                    Row(Modifier.width(300.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.width(34.dp).height(18.dp).background(strong, chip))
                        Box(Modifier.width(42.dp).height(18.dp).background(soft, chip))
                        Box(Modifier.width(28.dp).height(18.dp).background(soft, chip))
                    }
                    Box(Modifier.width(64.dp), contentAlignment = Alignment.CenterEnd) {
                        Box(Modifier.width(40.dp).height(10.dp).background(soft, bar))
                    }
                } else {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Box(Modifier.fillMaxWidth(w).height(13.dp).background(strong, bar))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.width(34.dp).height(18.dp).background(strong, chip))
                            Box(Modifier.width(42.dp).height(18.dp).background(soft, chip))
                            Box(Modifier.width(28.dp).height(18.dp).background(soft, chip))
                        }
                    }
                    Box(Modifier.width(40.dp).height(10.dp).background(soft, bar))
                }
            }
        }
    }
}

/** "Load N more" at the end of a page: a tonal accent bar, full width. */
@Composable
internal fun LoadMoreButton(count: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .background(scheme.primaryContainer, shape)
            .border(1.dp, scheme.primary.copy(alpha = 0.30f), shape)
            .focusRing(source, 12.dp)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        Icon(painterResource(R.drawable.ic_arrow_down), contentDescription = null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
        Text(
            if (count in 1 until Int.MAX_VALUE) pluralStringResource(R.plurals.home_load_more, count, count) else stringResource(R.string.load_more),
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onPrimaryContainer,
            maxLines = 1
        )
    }
}

/** The last searches under the search field, while nothing is typed: a tap searches again. */
@Composable
internal fun RecentSearchesRow(searches: List<String>, onPick: (String) -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(painterResource(R.drawable.ic_history), contentDescription = null, tint = muted, modifier = Modifier.size(16.dp))
        Text(
            stringResource(R.string.home_recent),
            style = MaterialTheme.typography.labelMedium,
            color = muted,
            modifier = Modifier.padding(end = 2.dp)
        )
        searches.forEach { search -> RecentPill(search) { onPick(search) } }
        PanelArrow(R.drawable.ic_close, stringResource(R.string.recent_searches_clear), onClear)
    }
}

/** One recent search as a neutral pill (focus ring, press flash). */
@Composable
internal fun RecentPill(label: String, onClick: () -> Unit) {
    val source = rememberFocusSource()
    val (bg, fg) = pillColors(PillTone.Neutral)
    val shape = RoundedCornerShape(50)
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = fg,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = 200.dp)
            .background(bg, shape)
            .border(1.dp, LocalDogmatixTokens.current.hairline, shape)
            .focusRing(source, 14.dp)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    )
}

/** An accent text link with an optional icon: "Save these filters", "Download all", "Surprise me" (portrait). */
@Composable
internal fun BulkLink(label: String, icon: Int? = null, onClick: () -> Unit) {
    val source = rememberFocusSource()
    val color = accentInk()
    Row(
        modifier = Modifier
            .focusRing(source, 8.dp)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        icon?.let { Icon(painterResource(it), contentDescription = null, tint = color, modifier = Modifier.size(15.dp)) }
        Text(label, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1)
    }
}

/** A small icon button (same tint and size as the filter dropdown arrows): fold / unfold, save view, bulk, surprise. */
@Composable
internal fun PanelArrow(icon: Int, description: String, onClick: () -> Unit) {
    val source = rememberFocusSource()
    Icon(
        painterResource(icon),
        contentDescription = description,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .focusRing(source, 8.dp)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(7.dp)
            .size(17.dp)
    )
}

/** Collapsed filter panel: a thin rail with the active-filter count and, at the bottom, the button that brings it back. */
@Composable
internal fun FilterRail(count: Int, onExpand: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .requiredWidth(48.dp)
            .padding(top = 16.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painterResource(R.drawable.ic_filter),
            contentDescription = null,
            tint = if (count > 0) scheme.primary else scheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        if (count > 0) {
            Spacer(Modifier.height(8.dp))
            Pill(count.toString(), tone = PillTone.Strong)
        }
        Spacer(Modifier.weight(1f))
        PanelArrow(R.drawable.ic_arrow_right, stringResource(R.string.expand_filters), onExpand)
    }
}

@Composable
internal fun FilterButton(count: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    val active = count > 0
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .height(SearchHeight)
            .background(if (active) scheme.primary else scheme.surfaceContainer, shape)
            .border(1.dp, if (active) Color.Transparent else LocalDogmatixTokens.current.hairline, shape)
            .focusRing(source, 16.dp, onAccent = active)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val fg = if (active) scheme.onPrimary else scheme.onSurface
        Icon(painterResource(R.drawable.ic_filter), contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.filters), style = MaterialTheme.typography.labelLarge, color = fg)
        if (active) {
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelSmall.tabular(),
                color = accentInk(),
                modifier = Modifier
                    .background(scheme.onPrimary, RoundedCornerShape(50))
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            )
        }
    }
}

@Composable
internal fun ConsoleChips(
    options: List<FilterOption>,
    selected: Set<String>,
    onSelect: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    /** The row's start / end inset: the search field's edge (see [FilterLayout]). */
    horizontalPadding: Int = FilterLayout.EDGE_PORTRAIT
) {
    val total = remember(options) { options.sumOf { it.count ?: 0 } }
    // One line that scrolls (there can be dozens of consoles); same start inset, gap and height as the quick chips.
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = horizontalPadding.dp),
        horizontalArrangement = Arrangement.spacedBy(ChipGap)
    ) {
        item { ConsoleChip(stringResource(R.string.filter_all), selected.isEmpty(), color = null, count = total.takeIf { it > 0 }) { onSelect(emptySet()) } }
        items(options, key = { it.id }, contentType = { "console-chip" }) { option ->
            val isOnly = selected.size == 1 && option.id in selected
            ConsoleChip(option.shortLabel, isOnly, color = option.color, count = option.count) { onSelect(if (isOnly) emptySet() else setOf(option.id)) }
        }
    }
}

/** A console chip (portrait): its colour as a dot, the short name and how many games match. */
@Composable
internal fun ConsoleChip(label: String, selected: Boolean, color: Color?, count: Int?, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier = Modifier
            .defaultMinSize(minHeight = ChipHeight)
            .background(if (selected) scheme.primary else scheme.surfaceContainer, shape)
            .border(1.dp, if (selected) Color.Transparent else LocalDogmatixTokens.current.hairline, shape)
            .focusRing(source, 24.dp, onAccent = selected)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (color != null) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(if (selected) scheme.onPrimary else color, CircleShape)
            )
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) scheme.onPrimary else scheme.onSurface, maxLines = 1)
        if (count != null) {
            val shown = remember(count) { NumberFormat.getIntegerInstance().format(count) }
            Text(
                shown,
                style = MaterialTheme.typography.labelSmall.tabular(),
                color = if (selected) scheme.onPrimary.copy(alpha = 0.75f) else scheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}
