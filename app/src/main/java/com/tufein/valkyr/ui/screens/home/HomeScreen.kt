package com.tufein.valkyr.ui.screens.home

import androidx.compose.foundation.layout.heightIn
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
import com.tufein.valkyr.util.LibraryBrowse
import com.tufein.valkyr.util.LibraryDiscovery
import com.tufein.valkyr.ui.screens.home.components.TableCoverWidth
import com.tufein.valkyr.ui.theme.LocalDogmatixTokens
import com.tufein.valkyr.ui.theme.consoleColor
import com.tufein.valkyr.ui.theme.tabular
import com.tufein.valkyr.util.Constants
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import com.tufein.valkyr.ui.screens.home.components.FilterRowSpec
import com.tufein.valkyr.util.CompletedFilter
import com.tufein.valkyr.util.CompletedGames
import com.tufein.valkyr.util.RaStatus
import com.tufein.valkyr.util.RaStatusFilter
import com.tufein.valkyr.util.FilterLayout
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

private const val FAV_ALL = "all"
/** 3.8.0: the search box and the filter button next to it (a comfortable touch target). */
internal val SearchHeight = 52.dp
private const val FAV_ONLY = "only"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel(),
    /**
     * 8.0: opens the full-screen game page (the shell navigates to it). When set, A / tap on a row,
     * "Surprise me" and the Continue playing shelf open the page; X and a long press still show
     * the details card as a quick look. Null keeps the 7.x behaviour (A / tap downloads).
     */
    onOpenGame: ((consoleId: String, fileName: String) -> Unit)? = null
) {
    val listing by viewModel.listing.collectAsStateWithLifecycle()
    val results = listing.rows
    val groupVersions by viewModel.groupVersions.collectAsStateWithLifecycle()
    val hasMoreResults by viewModel.hasMoreResults.collectAsStateWithLifecycle()
    val isLoadingMore by viewModel.isLoadingMore.collectAsStateWithLifecycle()
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
    val selectedConsoles by viewModel.selectedConsoles.collectAsStateWithLifecycle()
    val activeTags by viewModel.activeTags.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val consolesWithFiles by viewModel.consolesWithFiles.collectAsStateWithLifecycle()
    val categorizedTags by viewModel.categorizedTags.collectAsStateWithLifecycle()
    val ownedKeys by viewModel.ownedKeys.collectAsStateWithLifecycle()
    val rommKeys by viewModel.rommKeys.collectAsStateWithLifecycle()
    val rommBase by viewModel.rommBase.collectAsStateWithLifecycle()
    val rommOn by viewModel.rommEnabled.collectAsStateWithLifecycle()
    val favouriteKeys by viewModel.favouriteKeys.collectAsStateWithLifecycle()
    val favouritesOnly by viewModel.favouritesOnly.collectAsStateWithLifecycle()
    val sourceFilter by viewModel.source.collectAsStateWithLifecycle()
    val activeDownloads by viewModel.activeDownloads.collectAsStateWithLifecycle()
    val favoriteLanguages by viewModel.favoriteLanguages.collectAsStateWithLifecycle()
    val detailsState by viewModel.details.collectAsStateWithLifecycle()
    val raMarks by viewModel.raMarks.collectAsStateWithLifecycle()
    // 5.5: completed flag and RetroAchievements status (nothing of RA without an account).
    val completedKeys by viewModel.completedKeys.collectAsStateWithLifecycle()
    val completedFilter by viewModel.completedFilter.collectAsStateWithLifecycle()
    val raFilter by viewModel.raFilter.collectAsStateWithLifecycle()
    val raAwards by viewModel.raAwards.collectAsStateWithLifecycle()
    val raAccount by viewModel.raAccount.collectAsStateWithLifecycle()
    val raOn = raAccount == true
    val isCompletedRow: (DownloadableFileWithTags) -> Boolean = { CompletedGames.key(it.file.consoleId, it.file.fileName) in completedKeys }
    val raStatusRow: (DownloadableFileWithTags) -> RaStatus? = { if (raOn) viewModel.raStatusOf(it.file, raMarks, raAwards) else null }
    val newOnly by viewModel.newOnly.collectAsStateWithLifecycle()
    val collectionId by viewModel.collectionId.collectAsStateWithLifecycle()
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    // 5.0: covers in the rows, the live download ring, placeholder rows and the empty-library state.
    val listCovers by viewModel.listCovers.collectAsStateWithLifecycle()
    val loadMoreSize by viewModel.loadMoreSize.collectAsStateWithLifecycle()
    // 5.1: the remembered view (overview / list / covers) and the downloaded / not downloaded chips.
    val storedViewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    // Without a game page to open there is no overview: the toggle shows List then, so everything else must too.
    val browseViews = LibraryBrowse.availableViews(onOpenGame != null)
    val viewMode = if (storedViewMode in browseViews) storedViewMode else LibraryBrowse.ViewMode.LIST
    val ownership by viewModel.ownership.collectAsStateWithLifecycle()
    val compactLists by viewModel.compactLists.collectAsStateWithLifecycle()
    // 6.0 search by feel: genre / decade from the cached details, and the opt-in fetch.
    val genres by viewModel.genres.collectAsStateWithLifecycle()
    val decades by viewModel.decades.collectAsStateWithLifecycle()
    val discoverIndex by viewModel.discoverIndex.collectAsStateWithLifecycle()
    val fetchState by viewModel.fetchState.collectAsStateWithLifecycle()
    // These three change often (or only matter in one corner): they are read where they are drawn
    // or in ResultList's empty branch, never here, so a change does not recompose this screen.
    val downloadProgressState = viewModel.downloadProgress.collectAsStateWithLifecycle()
    val isSearchingState = viewModel.isSearching.collectAsStateWithLifecycle()
    val libraryEmptyState = viewModel.libraryEmpty.collectAsStateWithLifecycle()
    val progressOf: (String) -> Float = remember(downloadProgressState) { { name: String -> downloadProgressState.value[name] ?: 0f } }
    val isSearching: () -> Boolean = remember(isSearchingState) { { isSearchingState.value } }
    val libraryEmpty: () -> Boolean = remember(libraryEmptyState) { { libraryEmptyState.value } }
    var showBulk by remember { mutableStateOf(false) }
    val views by viewModel.views.collectAsStateWithLifecycle()
    var savingView by remember { mutableStateOf(false) }
    var showCollectionPicker by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var snackbarJob by remember { mutableStateOf<Job?>(null) }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val searchFocus = remember { FocusRequester() }
    val filterFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    var searchActive by remember { mutableStateOf(false) }
    var searchCollapsed by rememberSaveable { mutableStateOf(false) }
    val searchToggleFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val activateSearch: () -> Unit = {
        if (searchCollapsed) {
            searchCollapsed = false
            // Compose the inactive field first; its initial unfocused event must not dismiss
            // the Y shortcut before the field can acquire focus.
            scope.launch {
                withFrameNanos { }
                withFrameNanos { }
                searchActive = true
            }
        } else searchActive = true
    }
    var filtersHaveFocus by remember { mutableStateOf(false) }
    var listHasFocus by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    // Landscape: the filter panel folds into a thin rail (chevron or R3) to give the list more room.
    var filtersCollapsed by rememberSaveable { mutableStateOf(false) }
    var railHasFocus by remember { mutableStateOf(false) }
    // The list is laid out at its wide (rail) width while the panel is folded *and* while it
    // is sliding back in, so the panel covers it progressively; it narrows once the slide ends.
    var listWide by rememberSaveable { mutableStateOf(filtersCollapsed) }
    var expandedFilter by remember { mutableStateOf<String?>(null) }
    // Row under the D-pad cursor: X opens its details card.
    var focusedItem by remember { mutableStateOf<DownloadableFileWithTags?>(null) }
    val focusManager = LocalFocusManager.current
    // 3.8.0 "covers first": while nothing narrows the library, Home shows shelves of big covers
    // (HomeShelves) instead of the list. 5.1: the view switch under the search box picks Overview (the
    // shelves), List or Covers and the pick is remembered; a console shelf's "See all" or any search or
    // filter shows the games themselves. [forceList] is only the one-off "show the whole list" from the
    // shelves' empty state; it never changes the remembered view.
    var forceList by rememberSaveable { mutableStateOf(false) }
    // The hero takes the focus once per visit of Home (not again after a search is cleared).
    var shelvesFocusPending by rememberSaveable { mutableStateOf(true) }
    val shelvesFocus = remember { FocusRequester() }
    // Whether the shelves are on screen, for the lambdas and the gamepad collector defined before it is known.
    val shelvesNow = remember { mutableStateOf(false) }
    // 3.9.0: the game whose page was opened from here (console id, file name). Back on Home the
    // cursor returns to its row or card instead of being lost; saved with the back stack entry.
    var returnGame by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }
    val returnFocus = remember { FocusRequester() }
    // The game under the cursor also goes to a second screen, when there is one.
    LaunchedEffect(focusedItem) { SecondScreenState.focus(focusedItem) }
    // With touch there is no cursor: the game whose details are open goes to the second screen.
    LaunchedEffect(detailsState?.item) { detailsState?.item?.let { SecondScreenState.focus(it) } }
    val setSearchExpanded: (Boolean) -> Unit = { expanded ->
        if (!expanded) {
            keyboard?.hide()
            focusManager.clearFocus()
            searchActive = false
        }
        searchCollapsed = !expanded
        if (Gamepad.connected.value) scope.launch {
            withFrameNanos { }
            withFrameNanos { }
            val target = if (expanded) searchToggleFocus else if (shelvesNow.value) shelvesFocus else listFocus
            if (!target.requestFocusWhenReady(3)) searchToggleFocus.requestFocusWhenReady(2)
        }
    }
    // Folding hides whichever side holds the focus, so it is handed over explicitly
    // (two frames later when expanding: the panel has to be laid out first).
    val collapseFilters = {
        filtersCollapsed = true
        listWide = true
        if (filtersHaveFocus) runCatching { (if (shelvesNow.value) shelvesFocus else listFocus).requestFocus() }
    }
    val expandFilters = {
        filtersCollapsed = false
        // The filter panel belongs to the list: opening it from the shelves shows the list.
        if (shelvesNow.value) forceList = true
        if (railHasFocus) scope.launch { withFrameNanos { }; withFrameNanos { }; runCatching { filterFocus.requestFocus() } }
    }

    val consoleOptions = remember(consolesWithFiles) {
        consolesWithFiles.map {
            FilterOption(
                it.id, ConsoleFormatter.getConsoleFolderName(it.id), ConsoleFormatter.getConsoleShortName(it.id),
                count = it.fileCount, color = consoleColor(it.id)
            )
        }
    }
    fun tagRow(label: String, tags: List<String>, featured: Set<String>? = null) = FilterRowSpec(
        label = label,
        options = tags.map { FilterOption(it, it) },
        selected = activeTags.filter { it in tags }.toSet(),
        featured = featured,
        onSelectionChange = { viewModel.setTagsInCategory(tags, it) }
    )
    // 6.0: only listed when at least one title has cached details; the options are what is really known.
    val genreLabel = stringResource(R.string.disc6_filter_genre)
    val decadeLabel = stringResource(R.string.disc6_filter_decade)
    val discoverRows = if (discoverIndex.knownTitles == 0) emptyList() else listOf(
        FilterRowSpec(
            label = genreLabel,
            options = discoverIndex.genres.take(80).map { (g, n) -> FilterOption(g, g, count = n) },
            selected = genres,
            onSelectionChange = viewModel::setGenres
        ),
        FilterRowSpec(
            label = decadeLabel,
            options = discoverIndex.decades.map { (d, n) -> FilterOption(d.toString(), stringResource(R.string.disc6_decade_label, d), count = n) },
            selected = decades.map { it.toString() }.toSet(),
            onSelectionChange = { sel -> viewModel.setDecades(sel.mapNotNull { it.toIntOrNull() }.toSet()) }
        )
    )
    val filterRows = listOf(
        FilterRowSpec(
            label = stringResource(R.string.filter_console),
            options = consoleOptions,
            selected = selectedConsoles,
            onSelectionChange = viewModel::setConsoleSelection
        ),
        tagRow(stringResource(R.string.filter_region), categorizedTags?.regions?.tags.orEmpty()),
        tagRow(stringResource(R.string.filter_language), categorizedTags?.languages?.tags.orEmpty(), featured = favoriteLanguages),
        tagRow(stringResource(R.string.filter_tag), categorizedTags?.contentTypes?.tags.orEmpty()),
    ) + discoverRows + listOf(
        FilterRowSpec(
            label = stringResource(R.string.filter_favourites),
            options = listOf(
                FilterOption(FAV_ALL, stringResource(R.string.filter_all)),
                FilterOption(FAV_ONLY, stringResource(R.string.favourites_only), stringResource(R.string.favourites_only_short))
            ),
            selected = setOf(if (favouritesOnly) FAV_ONLY else FAV_ALL),
            single = true,
            onSelectionChange = { viewModel.setFavouritesOnly(FAV_ONLY in it) }
        ),
        FilterRowSpec(
            label = stringResource(R.string.done55_filter_completed),
            options = listOf(
                FilterOption(CompletedFilter.ALL.name, stringResource(R.string.filter_all)),
                FilterOption(CompletedFilter.COMPLETED.name, stringResource(R.string.done55_filter_completed_only)),
                FilterOption(CompletedFilter.NOT_COMPLETED.name, stringResource(R.string.done55_filter_not_completed))
            ),
            selected = setOf(completedFilter.name),
            single = true,
            onSelectionChange = { sel -> viewModel.setCompletedFilter(sel.firstOrNull()?.let { CompletedFilter.valueOf(it) } ?: CompletedFilter.ALL) }
        ),
        FilterRowSpec(
            label = stringResource(R.string.lib52_filter_ownership),
            options = listOf(
                FilterOption(LibraryBrowse.Ownership.ALL.name, stringResource(R.string.filter_all)),
                FilterOption(LibraryBrowse.Ownership.DOWNLOADED.name, stringResource(R.string.lib52_chip_downloaded)),
                FilterOption(LibraryBrowse.Ownership.NOT_DOWNLOADED.name, stringResource(R.string.lib52_chip_not_downloaded))
            ),
            selected = setOf(ownership.name),
            single = true,
            onSelectionChange = { sel -> viewModel.setOwnership(sel.firstOrNull()?.let { LibraryBrowse.Ownership.valueOf(it) } ?: LibraryBrowse.Ownership.ALL) }
        ),
    ) + (if (!raOn) emptyList() else listOf(
        // 5.5: only with an RA account; the statuses come from the user's award list, matched on the games RA recognised.
        FilterRowSpec(
            label = stringResource(R.string.done55_filter_ra),
            options = listOf(
                FilterOption(RaStatusFilter.ALL.name, stringResource(R.string.filter_all)),
                FilterOption(RaStatusFilter.BEATEN.name, stringResource(R.string.done55_ra_beaten)),
                FilterOption(RaStatusFilter.MASTERED.name, stringResource(R.string.done55_ra_mastered)),
                FilterOption(RaStatusFilter.IN_PROGRESS.name, stringResource(R.string.done55_ra_in_progress))
            ),
            selected = setOf(raFilter.name),
            single = true,
            onSelectionChange = { sel -> viewModel.setRaFilter(sel.firstOrNull()?.let { RaStatusFilter.valueOf(it) } ?: RaStatusFilter.ALL) }
        )
    )) + listOf(
        FilterRowSpec(
            label = stringResource(R.string.var60_filter_group),
            options = listOf(
                FilterOption(FAV_ALL, stringResource(R.string.var60_group_off)),
                FilterOption(FAV_ONLY, stringResource(R.string.var60_group_on))
            ),
            selected = setOf(if (groupVersions) FAV_ONLY else FAV_ALL),
            single = true,
            onSelectionChange = { viewModel.setGroupVersions(FAV_ONLY in it) }
        ),
        FilterRowSpec(
            label = stringResource(R.string.filter_new),
            options = listOf(
                FilterOption(FAV_ALL, stringResource(R.string.filter_all)),
                FilterOption(FAV_ONLY, stringResource(R.string.filter_new_only), stringResource(R.string.filter_new_only_short))
            ),
            selected = setOf(if (newOnly) FAV_ONLY else FAV_ALL),
            single = true,
            onSelectionChange = { viewModel.setNewOnly(FAV_ONLY in it) }
        ),
        FilterRowSpec(
            label = stringResource(R.string.filter_collection),
            options = listOf(FilterOption("0", stringResource(R.string.filter_all))) + collections.map { FilterOption(it.id.toString(), it.name) },
            selected = setOf(collectionId.toString()),
            single = true,
            onSelectionChange = { sel -> viewModel.setCollection(sel.firstOrNull()?.toLongOrNull() ?: 0L) }
        ),
        FilterRowSpec(
            label = stringResource(R.string.filter_view),
            options = listOf(FilterOption("", "—")) + views.map { FilterOption(it.id, it.name) },
            selected = setOf(viewModel.matchingViewId().orEmpty()),
            single = true,
            onSelectionChange = { sel -> views.firstOrNull { it.id == sel.firstOrNull() }?.let(viewModel::applyView) }
        ),
        FilterRowSpec(
            label = stringResource(R.string.filter_source),
            options = listOf(
                FilterOption(SourceFilter.ALL.name, stringResource(R.string.filter_all)),
                FilterOption(SourceFilter.TORRENT.name, stringResource(R.string.source_torrent)),
                *(if (rommOn) arrayOf(FilterOption(SourceFilter.ROMM.name, stringResource(R.string.source_romm))) else emptyArray()),
                FilterOption(SourceFilter.DIRECT.name, stringResource(R.string.source_direct))
            ),
            selected = setOf(sourceFilter.name),
            single = true,
            onSelectionChange = { sel -> viewModel.setSource(sel.firstOrNull()?.let { SourceFilter.valueOf(it) } ?: SourceFilter.ALL) }
        ),
        FilterRowSpec(
            label = stringResource(R.string.filter_sort),
            options = listOf(
                FilterOption(SortOption.NAME_ASC.name, stringResource(R.string.sort_name_asc)),
                FilterOption(SortOption.NAME_DESC.name, stringResource(R.string.sort_name_desc)),
                FilterOption(SortOption.SIZE_DESC.name, stringResource(R.string.sort_size_desc), stringResource(R.string.sort_size_desc_short)),
                FilterOption(SortOption.SIZE_ASC.name, stringResource(R.string.sort_size_asc), stringResource(R.string.sort_size_asc_short)),
                FilterOption(SortOption.NEWEST.name, stringResource(R.string.sort_newest), stringResource(R.string.sort_newest_short))
            ),
            selected = setOf(sort.name),
            single = true,
            onSelectionChange = { sel -> viewModel.setSort(sel.firstOrNull()?.let { SortOption.valueOf(it) } ?: SortOption.NAME_ASC) }
        )
    )
    val activeFilterCount = selectedConsoles.size + activeTags.size + (if (favouritesOnly) 1 else 0) + (if (sourceFilter != SourceFilter.ALL) 1 else 0) +
        (if (newOnly) 1 else 0) + (if (collectionId != 0L) 1 else 0) + (if (ownership.isActive) 1 else 0) +
        (if (completedFilter.isActive) 1 else 0) + (if (raFilter.isActive && raOn) 1 else 0) +
        LibraryDiscovery.Filter(genres, decades).activeCount
    // 5.0 empty states: the library has nothing yet → Sources (as a tab switch, like the top tabs do);
    // nothing matches → clear what narrows the list.
    val goToSources: () -> Unit = {
        navController.navigate(NavRoutes.Sources.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    // 3.5.0: on a fresh install (nothing indexed, no source at all) the empty library points to the checklist.
    val newHandheldHint: @Composable () -> Unit = {
        com.tufein.valkyr.ui.screens.tools.NewHandheldHint(
            onOpen = { navController.navigate(com.tufein.valkyr.ui.screens.tools.NEW_HANDHELD_ROUTE) { launchSingleTop = true } }
        )
    }
    val discoverBlock: @Composable () -> Unit = {
        DiscoverBlock(
            knownTitles = discoverIndex.knownTitles,
            libraryFiles = consolesWithFiles.sumOf { it.fileCount },
            fetch = fetchState,
            canFetch = results.isNotEmpty(),
            onFetch = viewModel::fetchDetailsForShown
        )
    }
    val clearFilters: (() -> Unit)? =
        if (activeFilterCount > 0 || query.isNotEmpty() || sort != SortOption.NAME_ASC) viewModel::clearAllFilters else null

    val startedMessage = stringResource(R.string.download_started, "%s")
    val favouriteAddedMessage = stringResource(R.string.favourite_added, "%s")
    val favouriteRemovedMessage = stringResource(R.string.favourite_removed, "%s")
    val deletedMessage = stringResource(R.string.owned_deleted, "%s")
    val deleteFailedMessage = stringResource(R.string.owned_delete_failed, "%s")
    val alreadyDownloadingMessage = stringResource(R.string.download_already_active, "%s")
    val showMessage: (String) -> Unit = { message ->
        snackbarJob?.cancel()
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarJob = scope.launch {
            snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
        }
    }
    val download: (DownloadableFileWithTags) -> Unit = { item ->
        scope.launch { viewModel.startDownload(item, context) }
        showMessage(startedMessage.format(item.file.name))
    }
    val toggleFavourite: (DownloadableFileWithTags) -> Unit = { item ->
        scope.launch {
            val starred = viewModel.toggleFavourite(item)
            showMessage((if (starred) favouriteAddedMessage else favouriteRemovedMessage).format(stripExtension(item.file.name)))
        }
    }
    val wishAddedMessage = stringResource(R.string.wishlist_added, "%s")
    val wishExistsMessage = stringResource(R.string.wishlist_exists)
    val addToWishlist: () -> Unit = {
        val title = query.trim()
        scope.launch { showMessage(if (viewModel.addToWishlist(title)) wishAddedMessage.format(title) else wishExistsMessage) }
    }
    // Tapping a game already on disk asks whether to fetch it again or remove it.
    var ownedDialogItem by remember { mutableStateOf<DownloadableFileWithTags?>(null) }
    val onFileClick: (DownloadableFileWithTags) -> Unit = { item ->
        when {
            viewModel.isDownloading(item.file, activeDownloads) -> showMessage(alreadyDownloadingMessage.format(item.file.name))
            viewModel.isOwned(item.file, ownedKeys) -> ownedDialogItem = item
            else -> download(item)
        }
    }

    // 8.0: the game page when the shell provides it, else the details card.
    val openGame: (DownloadableFileWithTags) -> Unit = { item ->
        if (onOpenGame != null) {
            returnGame = item.file.consoleId to item.file.fileName
            onOpenGame(item.file.consoleId, item.file.fileName)
        } else viewModel.openDetails(item)
    }
    val onRowClick: (DownloadableFileWithTags) -> Unit = if (onOpenGame != null) openGame else onFileClick

    // 3.8.0: shelves in the start state (see ShelvesModel.isStartState); an empty library keeps its own empty state.
    val startState = ShelvesModel.isStartState(query, activeFilterCount, sort == SortOption.NAME_ASC)
    val showShelves = LibraryBrowse.showShelves(viewMode, onOpenGame != null, startState, forceList, libraryEmptyState.value)
    SideEffect { shelvesNow.value = showShelves }
    // After a shelf leads to the list (a console's "See all", a chip in landscape) the cursor goes to
    // its first row once the new results are in (gamepad only).
    val focusListAfterChange: () -> Unit = {
        val before = viewModel.results.value
        if (Gamepad.connected.value) scope.launch {
            withTimeoutOrNull(5_000) { viewModel.results.first { it !== before } }
            withFrameNanos { }
            withFrameNanos { }
            runCatching { listFocus.requestFocus() }
        }
    }
    val openConsoleList: (String) -> Unit = { consoleId ->
        viewModel.setConsoleSelection(setOf(consoleId))
        focusListAfterChange()
    }
    // The hero's Play: the game page opens and presses its own Play (the same path as a 3.5 game
    // shortcut), so the emulator choice, missing files and errors are handled in one place.
    val playFromShelf: (String, String) -> Unit = { consoleId, fileName ->
        returnGame = consoleId to fileName
        onOpenGame?.invoke(consoleId, fileName)
        com.tufein.valkyr.ui.shortcut.PendingGameShortcut.submit(consoleId, fileName, play = true)
    }
    // 5.1: the Overview / List / Covers switch. The pick is stored; Overview is the shelves again, which
    // are the library with nothing narrowing it, so search and filters are cleared.
    val onPickView: (LibraryBrowse.ViewMode) -> Unit = { mode ->
        viewModel.setViewMode(mode)
        forceList = false
        if (mode == LibraryBrowse.ViewMode.OVERVIEW && !startState) viewModel.clearAllFilters()
    }
    val quickRow: @Composable (Modifier, Int) -> Unit = { modifier, inset ->
        QuickFilterRow(
            modes = browseViews,
            mode = viewMode,
            onMode = onPickView,
            ownership = ownership,
            onOwnership = viewModel::setOwnership,
            favouritesOnly = favouritesOnly,
            onFavourites = viewModel::setFavouritesOnly,
            sort = sort,
            onSort = viewModel::setSort,
            onClear = clearFilters,
            modifier = modifier,
            horizontalPadding = inset
        )
    }
    // 5.1: in the List and Covers views A / a tap / the row's button does the main thing right there:
    // Play for a game on the device, Download otherwise (a download already running just says so).
    val primaryActionOf: (DownloadableFileWithTags) -> LibraryBrowse.PrimaryAction = { item ->
        LibraryBrowse.primaryAction(viewModel.isOwned(item.file, ownedKeys), viewModel.isDownloading(item.file, activeDownloads))
    }
    val onPrimary: (DownloadableFileWithTags) -> Unit = { item ->
        when (primaryActionOf(item)) {
            LibraryBrowse.PrimaryAction.DOWNLOADING -> showMessage(alreadyDownloadingMessage.format(item.file.name))
            LibraryBrowse.PrimaryAction.PLAY ->
                if (onOpenGame != null) playFromShelf(item.file.consoleId, item.file.fileName) else onFileClick(item)
            LibraryBrowse.PrimaryAction.DOWNLOAD -> download(item)
        }
    }
    // 6.0: a row that stands for several versions opens the game page (its Versions tab picks the one to get or play).
    val onSingleClick: (DownloadableFileWithTags) -> Unit = if (viewMode.isBrowse) onPrimary else onRowClick
    val onItemClick: (DownloadableFileWithTags) -> Unit = { item ->
        if (listing.versionsOf(item) > 1) openGame(item) else onSingleClick(item)
    }
    val shelvesPanel: @Composable (Modifier, (@Composable () -> Unit)?) -> Unit = { modifier, header ->
        HomeShelves(
            onOpenGame = { c, f -> returnGame = c to f; onOpenGame?.invoke(c, f) },
            onPlay = playFromShelf,
            onOpenConsole = openConsoleList,
            onOpenForYou = { navController.navigate(NavRoutes.UpNext.route) },
            onShowList = { forceList = true },
            modifier = modifier,
            initialFocus = shelvesFocus,
            focusOnShow = shelvesFocusPending && !searchActive,
            onFocusTaken = { shelvesFocusPending = false },
            landscape = isLandscape,
            header = header,
            afterHero = { ContinuePlayingShelf(onOpenGame = openGame) },
            returnGame = returnGame,
            returnFocus = returnFocus
        )
    }
    // 3.9.0: back from a game page, the cursor goes back to that game (gamepad only, and not
    // while a ZL / ZR switch keeps it hidden); its row may need a few frames to be laid out.
    LaunchedEffect(Unit) {
        if (returnGame == null) return@LaunchedEffect
        if (Gamepad.connected.value && !Gamepad.pointerHidden.value) {
            if (!returnFocus.requestFocusWhenReady(10)) (if (shelvesNow.value) shelvesFocus else listFocus).requestFocusWhenReady(2)
        }
        returnGame = null
    }
    // The press that brings a hidden cursor back lands on the hero (shelves) or the first row on screen.
    ContentEntry(NavRoutes.Home.route, if (showShelves) shelvesFocus else listFocus)
    // 3.9.0: shelves ⇄ list (B, the List pill, a cleared search) throws away the side that held the
    // cursor; hand it to the side that is now shown instead of leaving it nowhere.
    LaunchedEffect(showShelves) {
        withFrameNanos { }
        withFrameNanos { }
        if (Gamepad.connected.value && !Gamepad.pointerHidden.value && !Gamepad.contentFocused && !searchActive && returnGame == null) {
            (if (showShelves) shelvesFocus else listFocus).requestFocusWhenReady(3)
        }
    }


    ownedDialogItem?.let { item ->
        var removal by remember(item) { mutableStateOf<List<com.tufein.valkyr.data.service.RemovalFile>?>(null) }
        LaunchedEffect(item) { removal = runCatching { viewModel.removalPlan(item) }.getOrNull() }
        AlertDialog(
            modifier = Modifier.closeOnGamepadB { ownedDialogItem = null },
            onDismissRequest = { ownedDialogItem = null },
            title = { Text(stripExtension(item.file.name)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.owned_dialog_message))
                Text(stringResource(R.string.recovery_to_trash))
                removal?.forEach { Text("${it.path} · ${com.tufein.valkyr.ui.components.formatBytes(it.bytes)}") }
            } },
            confirmButton = {
                DialogButton(stringResource(R.string.owned_download_again), onClick = {
                    ownedDialogItem = null
                    download(item)
                }, initialFocus = rememberInitialFocus())
            },
            dismissButton = {
                DialogButton(stringResource(R.string.owned_delete), enabled = !removal.isNullOrEmpty(), destructive = true, onClick = {
                    val plan = removal ?: return@DialogButton
                    ownedDialogItem = null
                    scope.launch {
                        val ok = runCatching { viewModel.removePlan(plan, item.file.name, item.file.consoleId, item.file.fileName) }.getOrDefault(false)
                        showMessage((if (ok) deletedMessage else deleteFailedMessage).format(item.file.name))
                    }
                })
            }
        )
    }

    val viewSavedMessage = stringResource(R.string.view_saved, "%s")
    if (savingView) {
        SaveViewDialog(
            onSave = { name -> savingView = false; scope.launch { if (viewModel.saveView(name)) showMessage(viewSavedMessage.format(name.trim())) } },
            onDismiss = { savingView = false }
        )
    }
    val bulkQueuedMessage = stringResource(R.string.bulk_queued, "%d")
    if (showBulk) {
        BulkDownloadDialog(
            plan = { viewModel.planBulk(it) },
            onConfirm = { plan ->
                showBulk = false
                scope.launch { showMessage(bulkQueuedMessage.replace("%d", viewModel.startBulk(plan, context).toString())) }
            },
            onDismiss = { showBulk = false },
            reclaimable = { viewModel.reclaimableBytes() },
            onFreeUp = { showBulk = false; navController.navigate(NavRoutes.Duplicates.route) }
        )
    }
    val collectionAddedMessage = stringResource(R.string.collection_added, "%s")
    if (showCollectionPicker) detailsState?.let { state ->
        CollectionPickerDialog(
            collections = collections,
            selected = state.collectionIds,
            onToggle = { id -> scope.launch { viewModel.toggleCollection(state.item, id) } },
            onCreate = { name -> scope.launch { if (viewModel.createCollectionWith(state.item, name)) showMessage(collectionAddedMessage.format(name.trim())) } },
            onDismiss = { showCollectionPicker = false }
        )
    }
    val updateQueuedMessage = stringResource(R.string.download_started, "%s")
    detailsState?.let { state ->
        val switch = state.switch
        GameDetailsDialog(
            onCollections = { showCollectionPicker = true },
            onDownloadUpdate = switch?.takeIf { it.updateAvailable }?.newestUpdate?.first?.let { row ->
                { viewModel.downloadRow(row); showMessage(updateQueuedMessage.format(row.name)) }
            },
            onDownloadDlc = switch?.missingDlc?.takeIf { it.isNotEmpty() }?.let { rows ->
                { viewModel.downloadRows(rows); showMessage(bulkQueuedMessage.replace("%d", rows.size.toString())) }
            },
            state = state,
            consoleName = ConsoleFormatter.getConsoleShortName(state.item.file.consoleId),
            favourite = viewModel.isFavourite(state.item.file, favouriteKeys),
            onToggleFavourite = { toggleFavourite(state.item) },
            onDownload = { viewModel.closeDetails(); onFileClick(state.item) },
            onDismiss = viewModel::closeDetails,
            onRomm = viewModel.isOnRomm(state.item.file, rommKeys, rommBase),
            rommOn = rommOn,
            similar = state.similar,
            onOpenSimilar = viewModel::openDetails,
            onOpenPage = onOpenGame?.let { open -> { viewModel.closeDetails(); open(state.item.file.consoleId, state.item.file.fileName) } },
            onShare = {
                val file = state.item.file
                scope.launch { com.tufein.valkyr.data.service.GameShare.share(context, file.consoleId, file.fileName, file.name) }
            },
            onDownloadWhen = { condition ->
                viewModel.closeDetails()
                viewModel.downloadWhen(state.item.file, condition)
                showMessage(updateQueuedMessage.format(state.item.file.name))
            },
            onDownloadBest = state.best?.let { best -> { viewModel.closeDetails(); onFileClick(best) } },
            owned = viewModel.isOwned(state.item.file, ownedKeys),
            downloading = viewModel.isDownloading(state.item.file, activeDownloads),
            extraSections = {
                val file = state.item.file
                RommGameSection(
                    consoleId = file.consoleId,
                    fileName = file.fileName,
                    showSummary = state.details?.description.isNullOrBlank()
                )
                CloudSavesSection(consoleId = file.consoleId, fileName = file.fileName)
                AchievementsSection(
                    consoleId = file.consoleId,
                    fileName = file.fileName,
                    title = file.name,
                    match = state.achievements
                )
            }
        )
    }

    // Button legend follows where the focus is. Kept short: B (back) and LB / RB are left out
    // of the list and filter legends so they fit on one line even on 4:3 screens.
    val filtersKey = if (isLandscape) listOf(LegendEntry("R3", stringResource(R.string.pad_filters))) else emptyList()
    val section = LegendEntry("ZL · ZR", stringResource(R.string.pad_section))
    val legendList = listOf(
        LegendEntry("A", stringResource(if (viewMode.isBrowse) R.string.lib52_pad_primary else if (onOpenGame != null) R.string.pad_open else R.string.pad_download)), LegendEntry("X", stringResource(R.string.pad_details)),
        LegendEntry("Y", stringResource(R.string.pad_search)), selectLegendEntry(R.string.pad_favourite)
    ) + (if (LibraryBrowse.showGrid(viewMode)) emptyList() else listOf(LegendEntry("◀ ▶", stringResource(R.string.pad_letters)))) + filtersKey + section + LegendEntry("START", stringResource(R.string.disc6_pad_surprise))
    val legendFilters = listOf(
        LegendEntry("A", stringResource(R.string.pad_options)), LegendEntry("◀ ▶", stringResource(R.string.pad_change))
    ) + filtersKey + section
    val legendDetails = listOf(
        LegendEntry("A", stringResource(R.string.pad_select)), LegendEntry("B", stringResource(R.string.pad_close)),
        LegendEntry("SELECT", stringResource(R.string.pad_favourite)), LegendEntry("▲ ▼", stringResource(R.string.pad_scroll))
    )
    val legendSearch = listOf(
        LegendEntry("A", stringResource(R.string.pad_keyboard)), LegendEntry("B", stringResource(R.string.pad_close_keyboard))
    )
    // 3.8.0: on the shelves A opens a game (Play on the hero's button).
    val legendShelves = listOf(
        LegendEntry("A", stringResource(R.string.pad_open)), LegendEntry("Y", stringResource(R.string.pad_search)),
        LegendEntry("START", stringResource(R.string.disc6_pad_surprise))
    )
    val legend = when {
        detailsState != null -> legendDetails
        searchActive -> legendSearch
        filtersHaveFocus || showFilterSheet -> legendFilters
        showShelves -> legendShelves
        else -> legendList
    }
    val published = remember(legend) { Legend(legend) }
    LaunchedEffect(published) { Gamepad.legendOverride.value = published }
    // Only clear our own legend: another screen may already have published its own during the transition.
    DisposableEffect(published) { onDispose { if (Gamepad.legendOverride.value === published) Gamepad.legendOverride.value = null } }

    // B / Back undoes one layer at a time and never leaves the app from here (Home is the root).
    BackHandler {
        when {
            showFilterSheet -> showFilterSheet = false
            expandedFilter != null -> expandedFilter = null
            searchActive -> { focusManager.clearFocus(); searchActive = false }
            // 3.9.0: B in the landscape filter panel goes back to the games first (a second B
            // then clears search and filters, as before), like closing the filter sheet upright.
            filtersHaveFocus -> runCatching { (if (shelvesNow.value) shelvesFocus else listFocus).requestFocus() }
            query.isNotEmpty() -> viewModel.setSearch("")
            showCollectionPicker -> showCollectionPicker = false
            activeFilterCount > 0 || sort != SortOption.NAME_ASC -> viewModel.clearAllFilters()
            // 3.8.0: from the whole list back to the shelves.
            forceList && onOpenGame != null -> forceList = false
            else -> runCatching { Gamepad.sectionFocus.requestFocus() }
        }
    }

    // 7.0: the Search shortcut puts the cursor in the search box (the same as the Y button).
    LaunchedEffect(Unit) { viewModel.searchRequests.collect { activateSearch() } }
    // 3.5.0: "Set up a new handheld" asked for the missing favourites / a collection: its preview.
    LaunchedEffect(Unit) { viewModel.bulkRequests.collect { showBulk = true } }

    LaunchedEffect(isLandscape) {
        Gamepad.presses.collect { button ->
            when (button) {
                // X shows the details card of the row under the cursor (LB / RB move between panels).
                GamepadButton.X -> focusedItem?.takeIf { listHasFocus }?.let { viewModel.openDetails(it) }
                // Select stars the row under the cursor, or the game whose details card is open.
                GamepadButton.FAVOURITE -> (viewModel.details.value?.item ?: focusedItem?.takeIf { listHasFocus })?.let(toggleFavourite)
                GamepadButton.Y -> activateSearch()
                // 6.0: Start = surprise me (a random game of the current list opens its details card).
                GamepadButton.START -> if (viewModel.details.value == null && !searchActive) results.randomOrNull()?.let(openGame)
                GamepadButton.PREV_PANEL -> if (isLandscape) {
                    filtersCollapsed = false
                    if (shelvesNow.value) forceList = true
                    scope.launch { withFrameNanos { }; withFrameNanos { }; runCatching { filterFocus.requestFocus() } }
                } else showFilterSheet = true
                GamepadButton.TOGGLE_FILTERS -> if (isLandscape) {
                    if (filtersCollapsed || shelvesNow.value) expandFilters() else collapseFilters()
                } else showFilterSheet = !showFilterSheet
                GamepadButton.NEXT_PANEL -> if (isLandscape) {
                    if (shelvesNow.value) runCatching { shelvesFocus.requestFocus() }
                    else if (!runCatching { listFocus.requestFocus() }.getOrDefault(false)) focusManager.moveFocus(FocusDirection.Right)
                } else showFilterSheet = false
                else -> Unit
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isLandscape) BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // The panel folds into a thin rail. A full recomposition + relayout of this screen
            // costs ~150 ms on the K56 (debug build), so a toggle must trigger exactly one: panel
            // and rail stay composed (the hidden one sits off-screen and cannot take focus), every
            // child is measured once at a fixed size and the animated width is read only while
            // placing. No alpha fade either: an alpha graphicsLayer renders the panel to an
            // offscreen buffer and the GPU wait alone was ~190 ms per frame.
            val panelWidth = FilterLayout.PANEL_WIDTH.dp
            val railWidth = 48.dp
            val dividerWidth = 1.dp
            // 3.8.0: the shelves use the room; the panel stays folded to its rail while they show.
            val panelCollapsed = filtersCollapsed || showShelves
            val targetWidth = if (panelCollapsed) railWidth else panelWidth
            val currentWidth = remember { Animatable(targetWidth, Dp.VectorConverter) }
            // Re-laying out the list at its new width is one heavy frame; let it land before
            // the slide starts so the animation itself runs at full frame rate.
            LaunchedEffect(targetWidth) {
                withFrameNanos { }
                withFrameNanos { }
                currentWidth.animateTo(targetWidth, tween(250))
                listWide = panelCollapsed
            }
            val listWidth = maxWidth - (if (listWide) railWidth else panelWidth) - dividerWidth
            // The list is anchored to the right edge and only uncovered / covered by the panel:
            // tags and sizes are right-aligned so they never move, and the name column is
            // revealed under the sliding edge. Clipping happens in the draw phase (cheap).
            val listStart = maxWidth - listWidth
            // Left-anchored content (names, "Name" header) starts where it was and slides to its
            // final place along with the panel edge; right-anchored tags and sizes never move.
            val density = LocalDensity.current
            val contentShift = { with(density) { (currentWidth.value + dividerWidth - listStart).roundToPx() } }
            val listClip = Modifier.drawWithContent {
                val left = (currentWidth.value + dividerWidth - listStart).toPx().coerceAtLeast(0f)
                clipRect(left = left) { this@drawWithContent.drawContent() }
            }
            // The single-line table needs room for name + tags + size; on 4:3 screens
            // (or with the panel open on narrow ones) rows stack name over tags instead.
            val tableRows = listWidth >= 560.dp

            Layout(
                modifier = Modifier.fillMaxSize().clipToBounds(),
                content = {
                    // Rail underneath, panel sliding over it. Both fade over the last stretch of
                    // the slide. ModulateAlpha applies the opacity per draw call instead of
                    // rendering the panel to an offscreen buffer (that GPU wait cost ~190 ms a
                    // frame on the K56); the lambdas run in the draw phase only. The hidden one
                    // refuses focus: onEnter is evaluated per focus query, no recomposition.
                    val fadeSpan = (panelWidth - railWidth) * 0.4f
                    val panelAlpha = { ((currentWidth.value - railWidth) / fadeSpan).coerceIn(0f, 1f) }
                    // 5.0: panel and rail sit on a translucent panel tone (the glow still shows through);
                    // a plain rect drawn in the draw phase, inside the ModulateAlpha layer.
                    val sideFill = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = if (LocalDogmatixTokens.current.isDark) 0.55f else 0.65f)
                    Box(
                        modifier = Modifier
                            .layoutId("rail")
                            .graphicsLayer { alpha = 1f - panelAlpha(); compositingStrategy = CompositingStrategy.ModulateAlpha }
                            .drawBehind { drawRect(sideFill) }
                            .focusProperties { onEnter = { if (!panelCollapsed) cancelFocusChange() } }
                            .onFocusChanged { railHasFocus = it.hasFocus }
                            .focusGroup()
                    ) {
                        FilterRail(count = activeFilterCount, onExpand = { expandFilters() })
                    }
                    Column(
                        modifier = Modifier
                            .layoutId("panel")
                            .graphicsLayer { alpha = panelAlpha(); compositingStrategy = CompositingStrategy.ModulateAlpha }
                            .drawBehind { drawRect(sideFill) }
                            .focusProperties { onEnter = { if (panelCollapsed) cancelFocusChange() } }
                            .focusGroup()
                    ) {
                    FilterPanel(
                        rows = filterRows,
                        onClear = viewModel::clearAllFilters,
                        compact = true,
                        firstRowFocus = filterFocus,
                        expandedRow = expandedFilter,
                        onExpandedRowChange = { expandedFilter = it },
                        afterRows = discoverBlock,
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { filtersHaveFocus = it.hasFocus }
                            .focusGroup()
                    )
                    // Result count on the left, collapse control at the bottom-right corner (R3 does the same).
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = FilterLayout.labelStart().dp, end = 7.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            resultsLabel(results.size, hasMoreResults),
                            style = MaterialTheme.typography.labelMedium.tabular(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        if (activeFilterCount > 0 || query.isNotBlank()) PanelArrow(R.drawable.ic_star, stringResource(R.string.view_save)) { savingView = true }
                        if (results.isNotEmpty()) PanelArrow(R.drawable.ic_arrow_down, stringResource(R.string.bulk_title)) { showBulk = true }
                        if (results.isNotEmpty()) PanelArrow(R.drawable.ic_shuffle, stringResource(R.string.surprise_me)) { results.randomOrNull()?.let(openGame) }
                        PanelArrow(R.drawable.ic_arrow_left, stringResource(R.string.collapse_filters)) { collapseFilters() }
                    }
                }
                    VerticalDivider(color = LocalDogmatixTokens.current.hairline, modifier = Modifier.layoutId("divider"))
                    Box(modifier = Modifier.layoutId("list").then(listClip)) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 12.dp, end = 12.dp, top = 12.dp)
                    ) {
                        LibrarySearchPanel(
                            expanded = !searchCollapsed,
                            query = query,
                            filterCount = activeFilterCount,
                            onExpandedChange = setSearchExpanded,
                            toggleFocus = searchToggleFocus,
                            search = { toggle ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    SearchField(
                                        value = query,
                                        onValueChange = viewModel::setSearch,
                                        active = searchActive,
                                        onActivate = activateSearch,
                                        onDismiss = { searchActive = false; runCatching { (if (shelvesNow.value) shelvesFocus else listFocus).requestFocus() } },
                                        focusRequester = searchFocus,
                                        trailing = toggle,
                                        contentShift = contentShift,
                                        height = SearchHeight,
                                        cornerRadius = 16.dp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    // The panel is folded while the shelves show: this opens it (and the list).
                                    if (showShelves) FilterButton(count = activeFilterCount) { expandFilters() }
                                }
                            },
                            controls = {
                                if (query.isEmpty() && recentSearches.isNotEmpty()) RecentSearchesRow(
                                    recentSearches, onPick = viewModel::setSearch, onClear = viewModel::clearRecentSearches,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                                quickRow(Modifier.padding(top = 8.dp), FilterLayout.EDGE_LANDSCAPE)
                                if (!showShelves) ConsoleChips(
                                    options = consoleOptions,
                                    selected = selectedConsoles,
                                    onSelect = viewModel::setConsoleSelection,
                                    modifier = Modifier.padding(top = 8.dp),
                                    horizontalPadding = FilterLayout.EDGE_LANDSCAPE
                                )
                            }
                        )
                        if (showShelves) shelvesPanel(Modifier.weight(1f).padding(top = 8.dp)) {
                            if (!searchCollapsed) ConsoleChips(
                                options = consoleOptions,
                                selected = selectedConsoles,
                                onSelect = { viewModel.setConsoleSelection(it); focusListAfterChange() },
                                modifier = Modifier.padding(top = 2.dp),
                                horizontalPadding = FilterLayout.EDGE_LANDSCAPE
                            )
                        } else {
                        if (!searchCollapsed && query.isEmpty() && activeFilterCount == 0 && !viewMode.isBrowse) ContinuePlayingShelf(
                            onOpenGame = openGame,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        if (!searchCollapsed && query.isEmpty() && activeFilterCount == 0 && onOpenGame != null && !viewMode.isBrowse) com.tufein.valkyr.ui.screens.next.UpNextShelf(
                            onOpenGame = onOpenGame,
                            onOpenAll = { navController.navigate(NavRoutes.UpNext.route) },
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        if (tableRows && !viewMode.isBrowse) TableHeader(contentShift, showCover = listCovers) else Text(
                            resultsLabel(results.size, hasMoreResults),
                            style = MaterialTheme.typography.labelMedium.tabular(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 6.dp)
                        )
                        ResultList(
                            results = results,
                            compact = tableRows,
                            hasMore = hasMoreResults,
                            isLoadingMore = isLoadingMore,
                            onLoadMore = { scope.launch { viewModel.loadMore() } },
                            getConsoleName = { ConsoleFormatter.getConsoleShortName(it) },
                            onFileClick = onItemClick,
                            isOwned = { viewModel.isOwned(it.file, ownedKeys) },
                            isOnRomm = { viewModel.isOnRomm(it.file, rommKeys, rommBase) },
                            isFavourite = { viewModel.isFavourite(it.file, favouriteKeys) },
                            isDownloading = { viewModel.isDownloading(it.file, activeDownloads) },
                            isNew = { viewModel.isNew(it.file) },
                            hasAchievements = { raMarks.gameFor(it.file.consoleId, it.file.fileName) != null },
                            isCompleted = isCompletedRow,
                            raStatusOf = raStatusRow,
                            achievementCount = { raMarks.gameFor(it.file.consoleId, it.file.fileName)?.achievements ?: 0 },
                            onRowFocused = { focusedItem = it },
                            onRowLongClick = viewModel::openDetails,
                            query = query,
                            onAddWish = addToWishlist,
                            byName = sort == SortOption.NAME_ASC || sort == SortOption.NAME_DESC,
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { listHasFocus = it.hasFocus }
                                .focusGroup(),
                            firstRowFocus = listFocus,
                            returnGame = returnGame,
                            returnFocus = returnFocus,
                            contentShift = contentShift,
                            showCover = listCovers,
                            dense = compactLists,
                            downloadProgress = progressOf,
                            isSearching = isSearching,
                            libraryEmpty = libraryEmpty,
                            onGoToSources = goToSources,
                            onClearFilters = clearFilters,
                            loadMoreSize = loadMoreSize,
                            illustrated = false,
                            emptyLibraryExtra = newHandheldHint,
                            viewMode = viewMode,
                            primaryAction = primaryActionOf,
                            versionsOf = listing::versionsOf
                        )
                        }
                    }
                    }
                }
            ) { measurables, constraints ->
                val height = constraints.maxHeight
                val widths = mapOf(
                    "panel" to panelWidth.roundToPx(), "rail" to railWidth.roundToPx(),
                    "divider" to dividerWidth.roundToPx(),
                    "list" to listWidth.roundToPx()
                )
                val placed = measurables.associate { val id = it.layoutId as String; id to it.measure(Constraints.fixed(widths.getValue(id), height)) }
                layout(constraints.maxWidth, height) {
                    val edge = currentWidth.value.roundToPx()
                    placed["list"]?.placeRelative(constraints.maxWidth - widths.getValue("list"), 0)
                    placed["rail"]?.placeRelative(0, 0)
                    placed["panel"]?.placeRelative(edge - widths.getValue("panel"), 0)
                    placed["divider"]?.placeRelative(edge, 0)
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                LibrarySearchPanel(
                    expanded = !searchCollapsed,
                    query = query,
                    filterCount = activeFilterCount,
                    onExpandedChange = setSearchExpanded,
                    toggleFocus = searchToggleFocus,
                    modifier = if (searchCollapsed) Modifier.padding(horizontal = 16.dp) else Modifier,
                    search = { toggle ->
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SearchField(
                                value = query,
                                onValueChange = viewModel::setSearch,
                                active = searchActive,
                                onActivate = activateSearch,
                                onDismiss = { searchActive = false; runCatching { (if (shelvesNow.value) shelvesFocus else listFocus).requestFocus() } },
                                focusRequester = searchFocus,
                                trailing = toggle,
                                height = SearchHeight,
                                cornerRadius = 16.dp,
                                modifier = Modifier.weight(1f)
                            )
                            FilterButton(count = activeFilterCount) { showFilterSheet = true }
                        }
                    },
                    controls = {
                        if (query.isEmpty() && recentSearches.isNotEmpty()) RecentSearchesRow(
                            recentSearches, onPick = viewModel::setSearch, onClear = viewModel::clearRecentSearches,
                            modifier = Modifier.padding(start = FilterLayout.EDGE_PORTRAIT.dp, end = FilterLayout.EDGE_PORTRAIT.dp, top = 8.dp)
                        )
                        // The shelves have their own Continue playing and New for you.
                        if (!showShelves && query.isEmpty() && activeFilterCount == 0 && !viewMode.isBrowse) ContinuePlayingShelf(
                            onOpenGame = openGame,
                            modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp)
                        )
                        if (!showShelves && query.isEmpty() && activeFilterCount == 0 && onOpenGame != null && !viewMode.isBrowse) com.tufein.valkyr.ui.screens.next.UpNextShelf(
                            onOpenGame = onOpenGame,
                            onOpenAll = { navController.navigate(NavRoutes.UpNext.route) },
                            modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp)
                        )
                        quickRow(Modifier.padding(top = 8.dp), FilterLayout.EDGE_PORTRAIT)
                        ConsoleChips(
                            options = consoleOptions,
                            selected = selectedConsoles,
                            onSelect = viewModel::setConsoleSelection,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                )
                if (showShelves) shelvesPanel(Modifier.weight(1f), null) else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = FilterLayout.EDGE_PORTRAIT.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        resultsLabel(results.size, hasMoreResults),
                        style = MaterialTheme.typography.labelMedium.tabular(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Spacer(Modifier.width(8.dp))
                    // The links keep their full labels; on a narrow screen they scroll instead of squeezing.
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                            if (activeFilterCount > 0 || query.isNotBlank()) BulkLink(stringResource(R.string.view_save), R.drawable.ic_star) { savingView = true }
                            if (results.isNotEmpty()) BulkLink(stringResource(R.string.bulk_link), R.drawable.ic_download) { showBulk = true }
                            if (results.isNotEmpty()) BulkLink(stringResource(R.string.surprise_me), R.drawable.ic_shuffle) { results.randomOrNull()?.let(openGame) }
                        }
                    }
                }
                ResultList(
                    results = results,
                    compact = false,
                    hasMore = hasMoreResults,
                    isLoadingMore = isLoadingMore,
                    onLoadMore = { scope.launch { viewModel.loadMore() } },
                    getConsoleName = { ConsoleFormatter.getConsoleShortName(it) },
                    onFileClick = onItemClick,
                    isOwned = { viewModel.isOwned(it.file, ownedKeys) },
                    isOnRomm = { viewModel.isOnRomm(it.file, rommKeys, rommBase) },
                    isFavourite = { viewModel.isFavourite(it.file, favouriteKeys) },
                    isDownloading = { viewModel.isDownloading(it.file, activeDownloads) },
                    isNew = { viewModel.isNew(it.file) },
                    hasAchievements = { raMarks.gameFor(it.file.consoleId, it.file.fileName) != null },
                    isCompleted = isCompletedRow,
                    raStatusOf = raStatusRow,
                    achievementCount = { raMarks.gameFor(it.file.consoleId, it.file.fileName)?.achievements ?: 0 },
                    onRowFocused = { focusedItem = it },
                    onRowLongClick = viewModel::openDetails,
                    query = query,
                    onAddWish = addToWishlist,
                    byName = sort == SortOption.NAME_ASC || sort == SortOption.NAME_DESC,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                        .onFocusChanged { listHasFocus = it.hasFocus },
                    firstRowFocus = listFocus,
                    returnGame = returnGame,
                    returnFocus = returnFocus,
                    showCover = listCovers,
                    dense = compactLists,
                    downloadProgress = progressOf,
                    isSearching = isSearching,
                    libraryEmpty = libraryEmpty,
                    onGoToSources = goToSources,
                    onClearFilters = clearFilters,
                    loadMoreSize = loadMoreSize,
                    illustrated = true,
                    emptyLibraryExtra = newHandheldHint,
                    viewMode = viewMode,
                    primaryAction = primaryActionOf,
                    versionsOf = listing::versionsOf
                )
                }
            }

            if (showFilterSheet) {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ModalBottomSheet(
                    onDismissRequest = { showFilterSheet = false },
                    sheetState = sheetState,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    dragHandle = {
                        Box(
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .width(36.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        )
                    }
                ) {
                    // The sheet is its own window: gamepad keys never reach the Activity while it
                    // is open, so the shortcuts that leave the sheet are handled right here.
                    LaunchedEffect(Unit) { filterFocus.requestFocusWhenReady(10) }
                    Box(
                        modifier = Modifier.swapFaceButtons().onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.ButtonR1, Key.ButtonThumbRight -> { showFilterSheet = false; true }
                                // 3.9.0: B closes an open filter row first, then the sheet (not only
                                // through the system's B-to-Back fallback, which some pads lack).
                                Key.ButtonB -> { if (expandedFilter != null) expandedFilter = null else showFilterSheet = false; true }
                                Key.ButtonY -> { showFilterSheet = false; activateSearch(); true }
                                Key.ButtonL2 -> { showFilterSheet = false; Gamepad.presses.tryEmit(GamepadButton.PREV_TAB); true }
                                Key.ButtonR2 -> { showFilterSheet = false; Gamepad.presses.tryEmit(GamepadButton.NEXT_TAB); true }
                                else -> false
                            }
                        }
                    ) {
                    FilterPanel(
                        rows = filterRows,
                        onClear = viewModel::clearAllFilters,
                        compact = false,
                        firstRowFocus = filterFocus,
                        expandedRow = expandedFilter,
                        onExpandedRowChange = { expandedFilter = it },
                        afterRows = discoverBlock,
                        footer = {
                            PrimaryButton(
                                stringResource(R.string.show_results),
                                onClick = { showFilterSheet = false },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp)
                                    .heightIn(min = 48.dp),
                                icon = R.drawable.ic_check
                            )
                        }
                    )
                    }
                }
            }
        }

        SnackbarHost(
            snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
        )
    }
}
