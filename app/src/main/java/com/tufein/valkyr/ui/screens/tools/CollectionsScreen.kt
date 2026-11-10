package com.tufein.valkyr.ui.screens.tools

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tufein.valkyr.ui.components.requestFocusWhenReady
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.tufein.valkyr.R
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.local.dao.CollectionWithCount
import com.tufein.valkyr.data.repository.CollectionsRepository
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.data.service.FrontendShortcutService
import com.tufein.valkyr.data.service.RommCollectionsService
import com.tufein.valkyr.data.state.LibraryFilterRequest
import com.tufein.valkyr.data.state.PendingLibraryFilters
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.EmptyState
import com.tufein.valkyr.ui.navigation.NavRoutes
import com.tufein.valkyr.ui.screens.sources.components.ConfirmDialog
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.DeepLinkParser
import com.tufein.valkyr.util.LibraryView
import com.tufein.valkyr.util.LibraryViews
import com.tufein.valkyr.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.tufein.valkyr.data.local.dao.DownloadableFileDao
import com.tufein.valkyr.data.service.SmartCollectionsService
import com.tufein.valkyr.util.SmartCollectionRule
import kotlinx.coroutines.CancellationException

@HiltViewModel
class CollectionsViewModel @Inject constructor(
    private val repository: CollectionsRepository,
    private val pendingFilters: PendingLibraryFilters,
    private val appSettings: AppSettings,
    private val shortcuts: FrontendShortcutService,
    private val rommCollections: RommCollectionsService,
    settings: SettingsRepository,
    private val smart: SmartCollectionsService,
    files: DownloadableFileDao
) : ViewModel() {
    val views: StateFlow<List<LibraryView>> = appSettings.libraryViews
        .map { LibraryViews.fromJson(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** RomM is set up, so collections can be synced with it. */
    val rommReady: StateFlow<Boolean> = settings.rommUrl.map { it.isNotBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Opens a saved view in the library (through the same hand-off as a deep link). */
    fun openView(view: LibraryView) {
        DeepLinkParser.parse(view.deepLink())?.let(pendingFilters::submit)
    }

    fun deleteView(id: String) {
        viewModelScope.launch {
            appSettings.setLibraryViews(LibraryViews.toJson(views.value.filterNot { it.id == id }))
        }
    }

    fun shortcut(context: Context, view: LibraryView) {
        val app = context.applicationContext
        viewModelScope.launch {
            if (view.consoles.isEmpty()) { ToastUtil.showError(app, app.getString(R.string.view_shortcut_needs_console)); return@launch }
            val n = runCatching { shortcuts.deployView(view) }.getOrDefault(0)
            if (n > 0) ToastUtil.showSuccess(app, app.resources.getQuantityString(R.plurals.view_shortcut_done, n, n))
            else ToastUtil.showError(app, app.getString(R.string.view_shortcut_failed))
        }
    }

    fun syncRomm(context: Context, push: Boolean) {
        val app = context.applicationContext
        viewModelScope.launch {
            runCatching { if (push) rommCollections.push() else rommCollections.pull() }
                .onSuccess { ToastUtil.showSuccess(app, app.getString(if (push) R.string.romm_collections_pushed else R.string.romm_collections_pulled, it.collections, it.games, it.skipped)) }
                .onFailure { ToastUtil.showError(app, app.getString(R.string.romm_collections_failed, it.message ?: "")) }
        }
    }
    val collections: StateFlow<List<CollectionWithCount>?> = repository.collections
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val rules = smart.rules
    val ruleFailed = smart.failed
    val consoles = files.observeLibraryChanges().map { files.indexedSources().map { it.consoleId }.distinct().sorted() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun saveRule(context: Context, id: Long?, name: String, rule: SmartCollectionRule?) {
        viewModelScope.launch {
            try {
                if (id == null && collections.value.orEmpty().any { it.name.equals(name.trim(), true) }) {
                    ToastUtil.showError(context, context.getString(R.string.smart25_name_exists))
                    return@launch
                }
                val target = id ?: repository.create(name) ?: return@launch
                smart.save(target, rule)
                smart.refresh()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { ToastUtil.showError(context, context.getString(R.string.smart25_failed)) }
        }
    }
    fun create(name: String) { viewModelScope.launch { repository.create(name) } }
    fun rename(id: Long, name: String) { viewModelScope.launch { repository.rename(id, name) } }
    fun delete(id: Long) { viewModelScope.launch { smart.save(id, null); repository.delete(id) } }

    /** Shows the collection in the library (the shell switches to the Library tab). */
    fun open(id: Long) = pendingFilters.submit(LibraryFilterRequest(collectionId = id))
}

/** Own collections: open one in the library, rename or delete it, or start a new one. */
@Composable
fun CollectionsScreen(navController: NavController, viewModel: CollectionsViewModel = hiltViewModel()) {
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    val views by viewModel.views.collectAsStateWithLifecycle()
    val rommReady by viewModel.rommReady.collectAsStateWithLifecycle()
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val ruleFailed by viewModel.ruleFailed.collectAsStateWithLifecycle()
    val consoles by viewModel.consoles.collectAsStateWithLifecycle()
    var smartCreating by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<CollectionWithCount?>(null) }
    val offline = rememberOfflineCollections { navController.navigate(it) }
    val context = LocalContext.current
    var naming by remember { mutableStateOf<CollectionWithCount?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CollectionWithCount?>(null) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(collections?.size) { firstFocus.requestFocusWhenReady() }

    if (smartCreating || editingRule != null) {
        SmartRuleDialog(editingRule?.name.orEmpty(), editingRule?.id?.let { rules[it] }, consoles,
            onSave = { name, rule -> viewModel.saveRule(context, editingRule?.id, name, rule); smartCreating = false; editingRule = null },
            onDismiss = { smartCreating = false; editingRule = null })
    }
    if (creating || naming != null) {
        NameDialog(
            title = stringResource(if (creating) R.string.collections_new else R.string.collections_rename),
            initial = naming?.name.orEmpty(),
            onSave = { name -> naming?.let { viewModel.rename(it.id, name) } ?: viewModel.create(name); creating = false; naming = null },
            onDismiss = { creating = false; naming = null }
        )
    }
    deleting?.let { c ->
        ConfirmDialog(
            title = stringResource(R.string.collections_delete_title, c.name),
            message = stringResource(R.string.collections_delete_message),
            confirmText = stringResource(R.string.dialog_delete),
            onConfirm = { viewModel.delete(c.id); deleting = null },
            onDismiss = { deleting = null },
            destructive = true
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.nav_collections), icon = NavRoutes.Collections.icon)
        ToolsActions {
            if (rommReady) {
                ToolAction(stringResource(R.string.romm_collections_pull), icon = R.drawable.ic_cloud_download) { viewModel.syncRomm(context, push = false) }
                ToolAction(stringResource(R.string.romm_collections_push), icon = R.drawable.ic_cloud_upload) { viewModel.syncRomm(context, push = true) }
            }
            offline.FetchNowAction()
            ToolAction(stringResource(R.string.smart25_new), icon = R.drawable.ic_filter) { smartCreating = true }
            ToolAction(stringResource(R.string.collections_new), icon = R.drawable.ic_plus, tone = ActionTone.Accent) { creating = true }
        }
        val list = collections
        when {
            list == null -> Unit
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (list.isEmpty()) item {
                    EmptyState(
                        title = stringResource(R.string.tools5_collections_empty_title),
                        message = stringResource(R.string.tools5_collections_empty_message),
                        modifier = Modifier.fillMaxWidth(),
                        illustration = R.drawable.brand_illustration,
                        actionLabel = stringResource(R.string.collections_new),
                        onAction = { creating = true },
                        actionFocus = firstFocus
                    )
                }
                if (ruleFailed) item { InfoCard(listOf(stringResource(R.string.smart25_failed)), icon = R.drawable.ic_warning, danger = true) }
                items(list, key = { it.id }) { c -> Column {
                    ToolRow(
                        title = c.name,
                        lines = listOfNotNull(pluralStringResource(R.plurals.collections_games, c.count, c.count), if (c.id in rules) stringResource(R.string.smart25_dynamic) else null),
                        onClick = { viewModel.open(c.id) },
                        modifier = if (c == list.first()) Modifier.focusRequester(firstFocus) else Modifier,
                        icon = R.drawable.ic_collections,
                        chevron = true
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ToolAction(stringResource(R.string.smart25_rules)) { editingRule = c }
                            ToolAction(stringResource(R.string.collections_rename)) { naming = c }
                            ToolAction(stringResource(R.string.dialog_delete), tone = ActionTone.Danger) { deleting = c }
                        }
                    }
                    offline.SwitchRow(c.id)
                } }
                offlineCollectionsItems(offline)
                item { SectionHeader(stringResource(R.string.views_title), stringResource(R.string.views_hint), icon = R.drawable.ic_bookmark) }
                if (views.isEmpty()) item { InfoCard(listOf(stringResource(R.string.views_empty)), icon = R.drawable.ic_filter) }
                items(views, key = { "v" + it.id }) { v ->
                    ToolRow(
                        v.name,
                        listOf((v.consoles.map { ConsoleFormatter.getConsoleShortName(it) } + v.tags.sorted() + listOfNotNull(v.query.takeIf { it.isNotBlank() }?.let { "“$it”" })).joinToString(" · ").ifEmpty { "—" }),
                        onClick = { viewModel.openView(v) },
                        icon = R.drawable.ic_filter
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ToolAction(stringResource(R.string.view_shortcut)) { viewModel.shortcut(context, v) }
                            ToolAction(stringResource(R.string.dialog_delete), tone = ActionTone.Danger) { viewModel.deleteView(v.id) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val cancelFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { DialogButton(stringResource(R.string.dialog_save), onClick = { onSave(name) }, enabled = name.isNotBlank()) },
        dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), onClick = onDismiss, initialFocus = cancelFocus) }
    )
}
