package com.tufein.valkyr.ui.screens.search

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.tufein.valkyr.R
import com.tufein.valkyr.data.service.SearchAllService
import com.tufein.valkyr.data.state.LibraryFilterRequest
import com.tufein.valkyr.data.state.PendingLibraryFilters
import com.tufein.valkyr.ui.common.Gamepad
import com.tufein.valkyr.ui.common.GamepadButton
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.EmptyState
import com.tufein.valkyr.ui.components.LegendEntry
import com.tufein.valkyr.ui.navigation.NavRoutes
import com.tufein.valkyr.ui.navigation.switchToTab
import com.tufein.valkyr.ui.screens.home.components.SearchField
import com.tufein.valkyr.ui.screens.settings.SettingsJump
import com.tufein.valkyr.ui.screens.tools.ConsoleTile
import com.tufein.valkyr.ui.screens.tools.PublishLegend
import com.tufein.valkyr.ui.screens.tools.ToolRow
import com.tufein.valkyr.ui.screens.tools.ToolsGroup
import com.tufein.valkyr.ui.screens.tools.ToolsTitle
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.GameHit
import com.tufein.valkyr.util.SearchDoc
import com.tufein.valkyr.util.SearchEntry
import com.tufein.valkyr.util.SearchGroup
import com.tufein.valkyr.util.SearchIndex
import com.tufein.valkyr.util.SearchMatch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import androidx.compose.ui.Alignment

/** Route of the "search everything" screen (8.0). */
const val SEARCH_ALL_ROUTE = "search_all"

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchAllViewModel @Inject constructor(
    private val service: SearchAllService,
    private val pendingFilters: PendingLibraryFilters
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** A few library games for the query, looked up once typing pauses. */
    val games: StateFlow<List<GameHit>> = _query
        .debounce { if (it.isBlank()) 0L else GAMES_DEBOUNCE_MS }
        .mapLatest { q -> if (q.isBlank()) emptyList() else runCatching { service.games(q) }.getOrDefault(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recent: StateFlow<List<String>> = service.recent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setQuery(text: String) { _query.value = text }

    /** Remembers the current query once a result of it was opened. */
    fun rememberQuery() {
        val q = _query.value
        if (q.isNotBlank()) viewModelScope.launch { service.remember(q) }
    }

    fun clearRecent() { viewModelScope.launch { service.clearRecent() } }

    /** Opens the library on this game (the shell switches to the Library tab). */
    fun openGame(hit: GameHit) {
        rememberQuery()
        pendingFilters.submit(LibraryFilterRequest(consoles = setOf(hit.consoleId), query = hit.title))
    }

    /** Opens the library with the query typed here. */
    fun showAllGames() {
        rememberQuery()
        pendingFilters.submit(LibraryFilterRequest(query = _query.value.trim()))
    }

    private companion object {
        const val GAMES_DEBOUNCE_MS = 250L
    }
}

/** Results per group; the rest is one more word away. */
private const val PER_GROUP = 8

/**
 * One search for everything (8.0): Settings rows, tools and screens, the online services and
 * games of the library. The field has focus when the screen opens (with a gamepad the on-screen
 * keyboard comes up); D-pad down goes into the results, up from the first result (or Y) back to
 * the field, A opens, B goes back.
 */
@Composable
fun SearchAllScreen(navController: NavController, viewModel: SearchAllViewModel = hiltViewModel()) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val games by viewModel.games.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val index = rememberSearchDocs(context)
    val byId = remember { SearchIndex.entries.associateBy { it.id } }
    val hits = remember(query, index) { SearchMatch.rank(query, index).mapNotNull { byId[it.doc.id] } }
    val grouped = remember(hits) { SearchGroup.entries.associateWith { g -> hits.filter { it.group == g }.take(PER_GROUP) } }

    var active by rememberSaveable { mutableStateOf(true) }
    val fieldFocus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }
    fun focusResults() { runCatching { firstFocus.requestFocus() } }

    LaunchedEffect(Unit) {
        Gamepad.presses.collect { if (it == GamepadButton.Y) active = true }
    }
    PublishLegend(
        listOf(
            LegendEntry("A", stringResource(R.string.pad_open)),
            LegendEntry("Y", stringResource(R.string.find8_pad_type)),
            LegendEntry("B", stringResource(R.string.pad_back))
        )
    )

    fun open(entry: SearchEntry) {
        viewModel.rememberQuery()
        val target = entry.target
        val tab = NavRoutes.tabs.firstOrNull { it.route == target.route }
        target.rowKey?.let(SettingsJump::request)
        if (tab != null) {
            navController.switchToTab(tab.route)
        } else {
            // An existing entry (Settings already open under the search): go back to it instead of stacking a second one.
            val existing = runCatching { navController.getBackStackEntry(target.route) }.isSuccess
            if (!existing || !navController.popBackStack(target.route, false)) navController.navigate(target.route) { launchSingleTop = true }
        }
    }

    // The first focusable row takes [firstFocus]; up from it goes back to the field.
    val toField = Modifier.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) { active = true; true } else false
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.find8_title), icon = R.drawable.ic_search)
        SearchField(
            value = query,
            onValueChange = viewModel::setQuery,
            active = active,
            onActivate = { active = true },
            // 3.9.0: B in the field hands the cursor to the results instead of leaving it nowhere.
            onDismiss = { active = false; focusResults() },
            placeholder = stringResource(R.string.find8_field_hint),
            focusRequester = fieldFocus,
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) { focusResults(); true } else false
                }
        )
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // Counted afresh on every build of the list, so the first row shown always gets it.
            var firstTaken = false
            fun firstModifier(): Modifier = if (firstTaken) Modifier else {
                firstTaken = true
                Modifier.focusRequester(firstFocus).then(toField)
            }
            when {
                query.isBlank() -> {
                    item(key = "empty") {
                        EmptyState(
                            title = stringResource(R.string.find8_empty_title),
                            message = stringResource(R.string.find8_empty_body),
                            icon = R.drawable.ic_search,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item(key = "examples") {
                        Examples(onPick = { viewModel.setQuery(it); active = true }, first = firstModifier())
                    }
                    if (recent.isNotEmpty()) {
                        item(key = "recent-head") { ToolsGroup(stringResource(R.string.find8_recent), icon = R.drawable.ic_history) }
                        recent.forEach { text ->
                            item(key = "recent-$text") {
                                ToolRow(text, emptyList(), { viewModel.setQuery(text) }, icon = R.drawable.ic_history, chevron = true)
                            }
                        }
                        item(key = "recent-clear") {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                                ActionPill(stringResource(R.string.find8_clear_recent), viewModel::clearRecent, icon = R.drawable.ic_close)
                            }
                        }
                    }
                }
                hits.isEmpty() && games.isEmpty() -> item(key = "none") {
                    EmptyState(
                        title = stringResource(R.string.find8_none_title),
                        message = stringResource(R.string.find8_none_body, query.trim()),
                        icon = R.drawable.ic_search_off,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                else -> {
                    entryGroup(SearchGroup.SETTINGS, R.string.find8_group_settings, R.drawable.ic_settings, grouped, ::firstModifier, ::open)
                    entryGroup(SearchGroup.TOOLS, R.string.find8_group_tools, R.drawable.ic_build, grouped, ::firstModifier, ::open)
                    entryGroup(SearchGroup.CLOUD, R.string.find8_group_cloud, R.drawable.ic_cloud, grouped, ::firstModifier, ::open)
                    if (games.isNotEmpty()) {
                        item(key = "games-head") { ToolsGroup(stringResource(R.string.find8_group_games), icon = R.drawable.ic_controller) }
                        games.forEach { hit ->
                            val mod = firstModifier()
                            item(key = "game-${hit.consoleId}-${hit.title}") {
                                ToolRow(
                                    hit.title, listOf(ConsoleFormatter.getConsoleDisplayName(hit.consoleId)),
                                    { viewModel.openGame(hit) }, mod,
                                    leading = { ConsoleTile(hit.consoleId, size = 36.dp) }, chevron = true
                                )
                            }
                        }
                        item(key = "games-all") {
                            ToolRow(
                                stringResource(R.string.find8_show_all), listOf(stringResource(R.string.find8_show_all_hint, query.trim())),
                                viewModel::showAllGames, icon = R.drawable.ic_library, chevron = true
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A group heading and its results; does nothing for an empty group. */
private fun LazyListScope.entryGroup(
    group: SearchGroup,
    title: Int,
    icon: Int,
    grouped: Map<SearchGroup, List<SearchEntry>>,
    first: () -> Modifier,
    open: (SearchEntry) -> Unit
) {
    val entries = grouped[group].orEmpty()
    if (entries.isEmpty()) return
    item(key = "head-${group.name}") { ToolsGroup(stringResource(title), icon = icon) }
    entries.forEach { entry ->
        val mod = first()
        item(key = "entry-${entry.id}") {
            val hint = entry.hint?.let { stringResource(it) }
            val where = entry.section?.let {
                stringResource(if (entry.advanced) R.string.settings51_in_section_advanced else R.string.find8_in_section, stringResource(it))
            }
            ToolRow(stringResource(entry.title), listOfNotNull(hint, where), { open(entry) }, mod, icon = entry.icon, chevron = true)
        }
    }
}

/** A few words to try, as buttons that fill in the field. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Examples(onPick: (String) -> Unit, first: Modifier) {
    val words = listOf(R.string.find8_example_1, R.string.find8_example_2, R.string.find8_example_3, R.string.find8_example_4)
        .map { stringResource(it) }
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        words.forEachIndexed { i, word ->
            ActionPill(word, { onPick(word) }, modifier = if (i == 0) first else Modifier, icon = R.drawable.ic_search)
        }
    }
}

/**
 * The index in the language the app shows. Titles and synonyms in English are added as keywords
 * (and the Settings card's name), so an English word still finds the row in another language.
 */
@Composable
private fun rememberSearchDocs(context: Context): List<SearchDoc> {
    val configuration = context.resources.configuration
    return remember(configuration) {
        val english = englishContext(context)
        SearchIndex.entries.map { e ->
            val keywords = buildList {
                e.keywords?.let { addAll(SearchMatch.keywordList(context.getString(it))) }
                e.section?.let { add(context.getString(it)) }
                if (english != null) {
                    add(english.getString(e.title))
                    e.keywords?.let { addAll(SearchMatch.keywordList(english.getString(it))) }
                }
            }
            SearchDoc(e.id, context.getString(e.title), e.hint?.let { context.getString(it) }.orEmpty(), keywords)
        }
    }
}

/** The app's strings in English, or null when the app already shows English. */
private fun englishContext(context: Context): Context? {
    val config = context.resources.configuration
    if (config.locales[0]?.language == Locale.ENGLISH.language) return null
    return runCatching {
        context.createConfigurationContext(Configuration(config).apply { setLocale(Locale.ENGLISH) })
    }.getOrNull()
}
