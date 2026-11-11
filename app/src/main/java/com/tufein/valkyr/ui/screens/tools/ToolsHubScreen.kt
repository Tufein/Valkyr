package com.tufein.valkyr.ui.screens.tools

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tufein.valkyr.ui.components.requestFocusWhenReady
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.tufein.valkyr.R
import com.tufein.valkyr.data.repository.CollectionsRepository
import com.tufein.valkyr.data.repository.WishlistRepository
import com.tufein.valkyr.data.service.EsdeFavouritesService
import com.tufein.valkyr.data.service.LibraryToolsService
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.ScreenTitle
import com.tufein.valkyr.ui.screens.search.SEARCH_ALL_ROUTE
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.navigation.NavRoutes
import com.tufein.valkyr.util.CollectionExport
import com.tufein.valkyr.util.ExportLabels
import com.tufein.valkyr.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class ToolsHubViewModel @Inject constructor(
    private val tools: LibraryToolsService,
    private val esdeFavourites: EsdeFavouritesService,
    wishlist: WishlistRepository,
    collections: CollectionsRepository
) : ViewModel() {

    /** How many games are wished for / how many collections exist: small numbers next to those tools. */
    val wishCount: StateFlow<Int> = wishlist.items.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val collectionCount: StateFlow<Int> = collections.collections.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Stars the games ES-DE has as favourites; says how many. */
    fun importEsdeFavourites(context: Context) {
        val app = context.applicationContext
        viewModelScope.launch {
            val r = runCatching { esdeFavourites.import() }.getOrNull()
            when {
                r == null -> ToastUtil.showError(app, app.getString(R.string.esde_favs_no_folder))
                else -> ToastUtil.showSuccess(app, app.getString(R.string.esde_favs_done, r.found, r.starred, r.unmatched))
            }
        }
    }


    /** Writes the whole collection to [uri] as a CSV file or a web page. */
    fun export(context: Context, uri: String, html: Boolean) {
        val app = context.applicationContext
        val labels = ExportLabels(
            title = context.getString(R.string.export_title), console = context.getString(R.string.export_console),
            game = context.getString(R.string.export_game), files = context.getString(R.string.export_files),
            size = context.getString(R.string.export_size), folder = context.getString(R.string.export_folder),
            total = context.getString(R.string.export_total), generated = context.getString(R.string.export_generated)
        )
        val unknown = { name: String -> context.getString(R.string.tools_unknown_folder, name) }
        val root = context.getString(R.string.tools_root_folder)
        ToastUtil.showInfo(app, context.getString(R.string.export_working))
        viewModelScope.launch {
            val result = runCatching {
                val games = tools.exportGames(unknown, root)
                val text = if (html) CollectionExport.html(games, labels, LocalDate.now().toString()) else CollectionExport.csv(games, labels)
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri.toUri(), "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: error("cannot write")
                }
                games.size
            }
            result.onSuccess { ToastUtil.showSuccess(app, app.getString(R.string.export_done, it)) }
                .onFailure { ToastUtil.showError(app, app.getString(R.string.export_failed)) }
        }
    }
}

/** The library tools in one place: overview, duplicates, set check, storage, wishlist and the export. */
@Composable
fun ToolsHubScreen(navController: NavController, viewModel: ToolsHubViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { viewModel.export(context, it.toString(), html = false) }
    }
    val htmlLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        uri?.let { viewModel.export(context, it.toString(), html = true) }
    }
    val wishCount by viewModel.wishCount.collectAsStateWithLifecycle()
    val collectionCount by viewModel.collectionCount.collectAsStateWithLifecycle()
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocusWhenReady() }
    val go: (NavRoutes) -> Unit = { route -> navController.navigate(route.route) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        // 8.0: the way into "search everything", next to the title.
        ScreenTitle(
            stringResource(R.string.settings_tools),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            icon = NavRoutes.Tools.icon,
            trailing = {
                ActionPill(
                    stringResource(R.string.find8_open),
                    { navController.navigate(SEARCH_ALL_ROUTE) { launchSingleTop = true } },
                    icon = R.drawable.ic_search
                )
            }
        )
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item { ToolsGroup(stringResource(R.string.tools_group_library), icon = R.drawable.ic_library) }
            item { Tool(go, NavRoutes.Overview, R.string.settings_overview, R.string.settings_overview_hint, Modifier.focusRequester(firstFocus)) }
            item { Tool(go, NavRoutes.Duplicates, R.string.settings_duplicates, R.string.settings_duplicates_hint) }
            item { Tool(go, NavRoutes.Sets, R.string.nav_sets, R.string.tools_sets_hint) }
            item { Tool(go, NavRoutes.Collections, R.string.nav_collections, R.string.tools_collections_hint, count = collectionCount) }
            item { Tool(go, NavRoutes.Files, R.string.nav_files, R.string.tools_files_hint) }
            item { Tool(go, NavRoutes.Storage, R.string.nav_storage, R.string.tools_storage_hint) }
            item { Tool(go, NavRoutes.UpNext, R.string.next30_title, R.string.next30_hint) }
            item { Tool(go, NavRoutes.LibraryRepair, R.string.repair30_title, R.string.repair30_hint) }
            item { Tool(go, NavRoutes.Recovery, R.string.recovery_title, R.string.recovery_hint) }
            item { Tool(go, NavRoutes.NewHandheld, R.string.wizard35_title, R.string.wizard35_hint) }
            item { Tool(go, NavRoutes.PackageTransfer, R.string.package_transfer_title, R.string.package_transfer_hint) }
            item { Tool(go, NavRoutes.GameTransfer, R.string.transfer31_title, R.string.transfer31_hint) }
            item { Tool(go, NavRoutes.VersionPreference, R.string.compare24_title, R.string.compare24_row_hint) }
            item { Tool(go, NavRoutes.ActionHistory, R.string.hist24_title, R.string.hist24_hint) }
            item { Tool(go, NavRoutes.FreeSpace, R.string.space7_title, R.string.space7_hint) }
            item { Tool(go, NavRoutes.Stats, R.string.nav_stats, R.string.tools_stats_hint) }
            item { Tool(go, NavRoutes.CollectionGoals, R.string.coll6_goals_title, R.string.lead6_goals_hint) }
            item { Tool(go, NavRoutes.History, R.string.coll6_history_title, R.string.lead6_history_hint) }
            item { Tool(go, NavRoutes.Health, R.string.health6_title, R.string.lead6_health_hint) }
            item { Tool(go, NavRoutes.Recap, R.string.quick7_title, R.string.quick7_hint) }
            item { Tool(go, NavRoutes.ReportProblem, R.string.report30_title, R.string.report30_hint) }
            item { Tool(go, NavRoutes.WifiReceive, R.string.wifi66_title, R.string.wifi66_hint) }
            item { Tool(go, NavRoutes.PerfCheck, R.string.measure47_title, R.string.measure47_hint) }
            item { ToolsGroup(stringResource(R.string.tools_group_games), icon = R.drawable.ic_gamepad) }
            item { Tool(go, NavRoutes.ImportList, R.string.nav_import_list, R.string.tools_import_list_hint) }
            item { Tool(go, NavRoutes.Wishlist, R.string.nav_wishlist, R.string.tools_wishlist_hint, count = wishCount) }
            item { Tool(go, NavRoutes.Dat, R.string.nav_dat, R.string.tools_dat_hint) }
            item { Tool(go, NavRoutes.Bios, R.string.nav_bios, R.string.tools_bios_hint) }
            item { Tool(go, NavRoutes.Switch, R.string.nav_switch, R.string.tools_switch_hint) }
            item { Tool(go, NavRoutes.RetroAchievements, R.string.nav_ra, R.string.tools_ra_hint) }
            item { Tool(go, NavRoutes.BestGames, R.string.top7_title, R.string.top7_hint) }
            item { Tool(go, NavRoutes.BetterVersions, R.string.upg7_better_title, R.string.upg7_better_hint) }
            item { ToolsGroup(stringResource(R.string.settings_section_frontends), icon = R.drawable.ic_frontends) }
            item { Tool(go, NavRoutes.Frontends, R.string.nav_frontends, R.string.tools_frontends_hint) }
            item { Tool(go, NavRoutes.FrontendMetadata, R.string.meta7_title, R.string.meta7_hint) }
            item { Tool(go, NavRoutes.FrontendCovers, R.string.covers32_title, R.string.covers32_hint) }
            item { Tool(go, NavRoutes.RetroArchPlaylists, R.string.playlists35_title, R.string.playlists35_hint) }
            item {
                val run = { viewModel.importEsdeFavourites(context) }
                ToolRow(
                    stringResource(R.string.esde_favs), listOf(stringResource(R.string.esde_favs_hint)), run,
                    icon = R.drawable.ic_star
                ) { ToolAction(stringResource(R.string.esde_favs_action), onClick = run) }
            }
            item { ToolsGroup(stringResource(R.string.tools_group_export), icon = R.drawable.ic_share) }
            item {
                val launch = { csvLauncher.launch("valkyr-collection-${LocalDate.now()}.csv") }
                ToolRow(
                    stringResource(R.string.tools_export_csv), listOf(stringResource(R.string.tools_export_csv_hint)), launch,
                    icon = R.drawable.ic_table_view
                ) { ToolAction(stringResource(R.string.tools_export_action), onClick = launch) }
            }
            item {
                val launch = { htmlLauncher.launch("valkyr-collection-${LocalDate.now()}.html") }
                ToolRow(
                    stringResource(R.string.tools_export_html), listOf(stringResource(R.string.tools_export_html_hint)), launch,
                    icon = R.drawable.ic_web
                ) { ToolAction(stringResource(R.string.tools_export_action), onClick = launch) }
            }
        }
    }
}

/** One tool: its route's icon, name, hint and the arrow; [count] shows as a pill. */
@Composable
private fun Tool(go: (NavRoutes) -> Unit, route: NavRoutes, title: Int, hint: Int, modifier: Modifier = Modifier, count: Int = 0) {
    ToolRow(
        stringResource(title), listOf(stringResource(hint)), { go(route) }, modifier,
        badge = if (count > 0) ({ Pill(count.toString(), tone = PillTone.Accent) }) else null,
        icon = route.icon, chevron = true
    )
}
