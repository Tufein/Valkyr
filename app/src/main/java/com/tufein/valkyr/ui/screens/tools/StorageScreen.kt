package com.tufein.valkyr.ui.screens.tools

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tufein.valkyr.ui.components.requestFocusWhenReady
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.R
import com.tufein.valkyr.data.model.DownloadStatus
import com.tufein.valkyr.data.service.DownloadService
import com.tufein.valkyr.data.service.LibraryMoveService
import com.tufein.valkyr.data.service.LibraryScanService
import com.tufein.valkyr.data.service.LibraryToolsService
import com.tufein.valkyr.data.service.MoveProblem
import com.tufein.valkyr.data.service.MoveState
import com.tufein.valkyr.data.state.LibraryFilterRequest
import com.tufein.valkyr.data.state.PendingLibraryFilters
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.BarSegment
import com.tufein.valkyr.ui.components.GameCover
import com.tufein.valkyr.ui.components.IconTile
import com.tufein.valkyr.ui.components.LegendEntry
import com.tufein.valkyr.ui.components.MeterBar
import com.tufein.valkyr.ui.components.SegmentedBar
import com.tufein.valkyr.ui.components.formatBytes
import com.tufein.valkyr.ui.navigation.NavRoutes
import com.tufein.valkyr.ui.theme.StatusInfo
import com.tufein.valkyr.ui.theme.consoleColor
import com.tufein.valkyr.ui.screens.sources.components.ConfirmDialog
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.GameEntry
import com.tufein.valkyr.util.StorageInsights
import com.tufein.valkyr.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StorageUiState(
    val loading: Boolean = true,
    val folderSet: Boolean = true,
    val freeBytes: Long? = null,
    val entries: List<GameEntry> = emptyList(),
    val usage: List<StorageInsights.ConsoleUsage> = emptyList(),
    val biggest: List<GameEntry> = emptyList(),
    val queue: StorageInsights.QueueNeed = StorageInsights.QueueNeed(0, 0)
) {
    val totalBytes: Long get() = entries.sumOf { it.size }
}

@HiltViewModel
class StorageViewModel @Inject constructor(
    private val tools: LibraryToolsService,
    private val scanService: LibraryScanService,
    private val downloadService: DownloadService,
    private val mover: LibraryMoveService,
    private val pendingFilters: PendingLibraryFilters
) : ViewModel() {
    /** Shows one console's games in the library (the shell switches to the Library tab). */
    fun showInLibrary(consoleId: String) = pendingFilters.submit(LibraryFilterRequest(consoles = setOf(consoleId)))

    val move: StateFlow<MoveState> = mover.state
    fun startMove(destination: String) = mover.start(destination)
    fun cancelMove() = mover.cancel()
    fun dismissMove() { mover.dismiss(); refresh() }

    private val _ui = MutableStateFlow(StorageUiState())
    val ui: StateFlow<StorageUiState> = _ui.asStateFlow()
    private var job: Job? = null

    init { refresh(force = false) }

    fun refresh(force: Boolean = true) {
        job?.cancel()
        job = viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            val disk = try { tools.disk(force) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            val queue = StorageInsights.queueNeed(
                downloadService.getDownloads()
                    .filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED }
                    .map { StorageInsights.QueueItem((it.fileSize - it.downloadedBytes).coerceAtLeast(0), StorageInsights.isExtractable(it.fileName.substringAfterLast('.', ""))) }
            )
            val entries = disk?.entries.orEmpty()
            _ui.value = StorageUiState(
                loading = false, folderSet = disk?.folderSet ?: true, freeBytes = disk?.freeBytes, entries = entries,
                usage = StorageInsights.usageByConsole(entries), biggest = StorageInsights.biggest(entries, 15), queue = queue
            )
        }
    }

    fun delete(context: Context, entry: GameEntry) {
        val app = context.applicationContext
        val failed = context.getString(R.string.duplicates_delete_failed, entry.baseName)
        val deleted = context.getString(R.string.duplicates_deleted, entry.baseName)
        viewModelScope.launch {
            val removed = try { scanService.delete(entry) } catch (e: CancellationException) { throw e } catch (e: Exception) { 0 }
            if (removed == 0) ToastUtil.showError(app, failed) else ToastUtil.showSuccess(app, deleted)
            refresh()
        }
    }
}

private enum class StorageFocus { REFRESH, MOVE, SMART, CONSOLE, GAME, INFO }

/** Where the space goes: per console, the biggest games, and whether the queued downloads still fit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StorageScreen(viewModel: StorageViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var pendingDelete by remember { mutableStateOf<GameEntry?>(null) }
    val move by viewModel.move.collectAsStateWithLifecycle()
    val smart = rememberSmartStorage()
    var pendingMove by remember { mutableStateOf<String?>(null) }
    val moveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            pendingMove = it.toString()
        }
    }
    pendingMove?.let { destination ->
        ConfirmDialog(
            title = stringResource(R.string.storage_move_title),
            message = stringResource(R.string.storage_move_message, formatBytes(ui.totalBytes)),
            confirmText = stringResource(R.string.storage_move_confirm),
            onConfirm = { viewModel.startMove(destination) },
            onDismiss = { pendingMove = null }
        )
    }
    pendingDelete?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.duplicates_delete_title),
            message = pluralStringResource(
                R.plurals.duplicates_delete_message, entry.files.size, entry.baseName, entry.folder,
                pluralStringResource(R.plurals.tools_files, entry.files.size, entry.files.size), formatBytes(entry.size)
            ) + "\n\n" + entry.files.take(8).joinToString("\n") { "• " + it.name } + if (entry.files.size > 8) "\n• … (+${entry.files.size - 8})" else "",
            confirmText = stringResource(R.string.duplicates_delete),
            onConfirm = { viewModel.delete(context, entry) },
            onDismiss = { pendingDelete = null },
            destructive = true
        )
    }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocusWhenReady() }
    // What A does depends on the row under the cursor, so the legend follows it.
    var focusKind by remember { mutableStateOf(StorageFocus.REFRESH) }
    val back = LegendEntry("B", stringResource(R.string.pad_back))
    val section = LegendEntry("ZL · ZR", stringResource(R.string.pad_section))
    PublishLegend(
        when (focusKind) {
            StorageFocus.REFRESH -> listOf(LegendEntry("A", stringResource(R.string.tools_refresh)), back, section)
            StorageFocus.MOVE -> listOf(LegendEntry("A", stringResource(R.string.storage_move_action)), back, section)
            StorageFocus.SMART -> listOf(LegendEntry("A", stringResource(R.string.pad_select)), back, section)
            StorageFocus.CONSOLE -> listOf(LegendEntry("A", stringResource(R.string.pad_open)), back, section)
            StorageFocus.GAME -> listOf(LegendEntry("A", stringResource(R.string.pad_delete)), back, section)
            StorageFocus.INFO -> listOf(back, section)
        }
    )
    val summary = when {
        ui.loading -> stringResource(R.string.tools_scanning)
        !ui.folderSet -> stringResource(R.string.tools_no_folder)
        else -> pluralStringResource(R.plurals.storage_summary, ui.entries.size, ui.entries.size, formatBytes(ui.totalBytes))
    }
    val shortfall = StorageInsights.shortfall(ui.queue, ui.freeBytes)
    val short = shortfall != null && shortfall > 0
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.nav_storage), icon = NavRoutes.Storage.icon)
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "summary") {
                ToolRow(
                    summary,
                    listOfNotNull(ui.freeBytes?.let { stringResource(R.string.storage_free, formatBytes(it)) }),
                    viewModel::refresh,
                    Modifier.focusRequester(firstFocus).onFocusChanged { if (it.isFocused) focusKind = StorageFocus.REFRESH },
                    icon = R.drawable.ic_storage
                ) { ToolAction(stringResource(R.string.tools_refresh), icon = R.drawable.ic_retry, onClick = viewModel::refresh) }
            }
            if (!ui.loading && ui.folderSet && (ui.freeBytes != null || ui.totalBytes > 0)) {
                item(key = "space") {
                    val free = ui.freeBytes
                    val games = ui.totalBytes
                    val queueColor = if (short) scheme.error else StatusInfo
                    SectionPanel(stringResource(R.string.tools5_space_title), icon = R.drawable.ic_storage) {
                        if (free != null) {
                            // The bar covers what the library can use: its games and the free space.
                            val total = (games + free).coerceAtLeast(1L).toFloat()
                            SegmentedBar(
                                listOf(
                                    BarSegment(games / total, scheme.primary),
                                    BarSegment(ui.queue.total.coerceAtMost(free) / total, queueColor)
                                )
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LegendDot(scheme.primary, stringResource(R.string.tools5_games) + " " + formatBytes(games))
                            if (ui.queue.total > 0) LegendDot(queueColor, stringResource(R.string.tools5_queue) + " " + formatBytes(ui.queue.total))
                            if (free != null) LegendDot(scheme.surfaceContainerHighest, stringResource(R.string.tools5_free) + " " + formatBytes(free))
                        }
                    }
                }
                item(key = "tiles") {
                    val free = ui.freeBytes
                    StatGrid(
                        listOfNotNull(
                            free?.let { ToolStat(stringResource(R.string.tools5_free), formatBytes(it), R.drawable.ic_storage) },
                            ToolStat(
                                stringResource(R.string.tools5_games), formatBytes(ui.totalBytes), R.drawable.ic_gamepad,
                                pluralStringResource(R.plurals.storage_games, ui.entries.size, ui.entries.size)
                            ),
                            if (ui.queue.total > 0) ToolStat(
                                stringResource(R.string.tools5_queue_needs), formatBytes(ui.queue.total), R.drawable.ic_download,
                                when {
                                    shortfall == null -> null
                                    shortfall > 0 -> stringResource(R.string.storage_queue_short, formatBytes(shortfall))
                                    else -> stringResource(R.string.storage_queue_fits)
                                },
                                if (short) scheme.error else null
                            ) else null
                        )
                    )
                }
            }
            if (!ui.loading && ui.folderSet) item(key = "move") {
                val problem = when (move.problem) {
                    MoveProblem.NO_SOURCE -> stringResource(R.string.storage_move_no_source)
                    MoveProblem.CANNOT_OPEN -> stringResource(R.string.storage_move_cannot_open)
                    MoveProblem.OVERLAP -> stringResource(R.string.storage_move_overlap)
                    MoveProblem.DOWNLOADS_ACTIVE -> stringResource(R.string.storage_move_downloads_active)
                    MoveProblem.NO_ROOM -> stringResource(R.string.storage_move_no_room, formatBytes(move.needBytes), formatBytes(move.freeBytes))
                    MoveProblem.ANOTHER_MOVE -> stringResource(R.string.store8_problem_busy)
                    null -> null
                }
                val lines = when {
                    move.running && move.scanning -> listOf(stringResource(R.string.tools_scanning))
                    move.running -> listOf(
                        stringResource(R.string.storage_move_running, move.filesDone, move.filesTotal, formatBytes(move.bytesDone), formatBytes(move.bytesTotal)),
                        move.current
                    )
                    move.finished && move.failed == 0 -> listOf(stringResource(R.string.storage_move_done, move.filesTotal))
                    move.finished -> listOf(stringResource(R.string.storage_move_done_failed, move.failed))
                    problem != null -> listOf(problem)
                    else -> listOf(stringResource(R.string.storage_move_hint))
                }
                val idle = !move.running
                ToolRow(
                    stringResource(R.string.storage_move), lines,
                    { if (idle) { if (move.finished || move.problem != null) viewModel.dismissMove() else moveLauncher.launch(null) } else viewModel.cancelMove() },
                    Modifier.onFocusChanged { if (it.isFocused) focusKind = StorageFocus.MOVE },
                    icon = R.drawable.ic_drive_file_move,
                    below = if (move.running && !move.scanning && move.bytesTotal > 0) ({
                        MeterBar(move.bytesDone.toFloat() / move.bytesTotal, modifier = Modifier.padding(top = 6.dp))
                    }) else null
                ) {
                    if (move.running) ToolAction(stringResource(R.string.storage_move_stop), tone = ActionTone.Danger) { viewModel.cancelMove() }
                    else if (move.finished || move.problem != null) ToolAction(stringResource(R.string.storage_move_ok)) { viewModel.dismissMove() }
                    else ToolAction(stringResource(R.string.storage_move_action)) { moveLauncher.launch(null) }
                }
            }
            // 8.0: smart storage (cold consoles to the SD card, played ones back).
            if (!ui.loading && ui.folderSet) smartStorageItems(smart) { focusKind = StorageFocus.SMART }
            if (!ui.loading && ui.usage.isNotEmpty()) {
                item(key = "usageHeader") { SectionHeader(stringResource(R.string.storage_by_console), icon = R.drawable.ic_controller) }
                val maxBytes = ui.usage.maxOf { it.bytes }.coerceAtLeast(1L)
                items(ui.usage, key = { "u:" + (it.consoleId ?: it.scope) }) { usage ->
                    val id = usage.consoleId
                    val name = id?.let { ConsoleFormatter.getConsoleDisplayName(it) }
                        ?: if (usage.scope.isEmpty()) stringResource(R.string.tools_root_folder)
                        else stringResource(R.string.tools_unknown_folder, usage.scope.removePrefix("folder:"))
                    val share = if (ui.totalBytes == 0L) 0 else (usage.bytes * 100 / ui.totalBytes).toInt()
                    ToolRow(
                        name,
                        listOf("${pluralStringResource(R.plurals.storage_games, usage.games, usage.games)} · ${formatBytes(usage.bytes)} · $share%"),
                        onClick = { if (id != null) viewModel.showInLibrary(id) },
                        modifier = Modifier.onFocusChanged { if (it.isFocused) focusKind = if (id != null) StorageFocus.CONSOLE else StorageFocus.INFO },
                        leading = { if (id != null) ConsoleTile(id) else IconTile(R.drawable.ic_folder, size = 40.dp) },
                        chevron = id != null,
                        below = {
                            MeterBar(
                                usage.bytes.toFloat() / maxBytes,
                                modifier = Modifier.padding(top = 6.dp),
                                height = 6.dp,
                                color = id?.let { consoleColor(it) } ?: scheme.outline
                            )
                        }
                    )
                }
                item(key = "biggestHeader") { SectionHeader(stringResource(R.string.storage_biggest), stringResource(R.string.storage_biggest_hint), icon = R.drawable.ic_bar_chart) }
                items(ui.biggest, key = { "b:" + it.id }) { entry ->
                    ToolRow(
                        entry.baseName,
                        listOf(entry.folder, formatBytes(entry.size) + " · " + pluralStringResource(R.plurals.tools_files, entry.files.size, entry.files.size)),
                        { pendingDelete = entry },
                        Modifier.onFocusChanged { if (it.isFocused) focusKind = StorageFocus.GAME },
                        leading = {
                            val id = entry.consoleId
                            if (id != null) GameCover(id, entry.coverFileName(), entry.baseName, Modifier.size(width = 42.dp, height = 56.dp))
                            else IconTile(R.drawable.ic_folder, size = 40.dp)
                        }
                    ) { ToolAction(stringResource(R.string.duplicates_delete), icon = R.drawable.ic_trash, tone = ActionTone.Danger) { pendingDelete = entry } }
                }
            }
        }
    }
}
