package com.tufein.valkyr.ui.screens.tools

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.verticalScroll
import com.tufein.valkyr.ui.components.requestFocusWhenReady
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.Alignment
import android.provider.DocumentsContract
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.R
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.data.service.ArchiveExtractorService
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.IconTile
import com.tufein.valkyr.ui.components.MeterBar
import com.tufein.valkyr.ui.components.Panel
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.formatBytes
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.ui.navigation.NavRoutes
import com.tufein.valkyr.ui.screens.sources.components.ConfirmDialog
import com.tufein.valkyr.ui.theme.tabular
import com.tufein.valkyr.util.ArchiveUtils
import com.tufein.valkyr.util.StorageHelper
import com.tufein.valkyr.util.ArchiveSafetyException
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.DatMatcher
import com.tufein.valkyr.util.DiskDir
import com.tufein.valkyr.util.DiskEntry
import com.tufein.valkyr.util.DiskFile
import com.tufein.valkyr.util.DiskScanner
import com.tufein.valkyr.util.DuplicateFinder
import com.tufein.valkyr.util.RomPatcher
import com.tufein.valkyr.util.SetChecker
import com.tufein.valkyr.util.SetProblem
import com.tufein.valkyr.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import androidx.compose.material3.Checkbox
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.service.LibraryIndexService
import com.tufein.valkyr.data.service.OperationDirectory
import com.tufein.valkyr.data.service.RemovalFile
import com.tufein.valkyr.data.service.StorageAvailabilityService
import com.tufein.valkyr.data.service.StorageMoveGate
import com.tufein.valkyr.data.service.TrashService
import com.tufein.valkyr.data.service.VerifiedDocumentCopy
import com.tufein.valkyr.util.BoundedStreams
import com.tufein.valkyr.util.Profiles
import com.tufein.valkyr.util.StorageAccessStatus
import com.tufein.valkyr.util.StorageAvailability
import kotlinx.coroutines.CancellationException

/** A starting point of the file explorer: one of the folders the app was given access to. */
data class ExplorerRoot(val label: String, val dir: DiskDir)

data class ExplorerState(
    val roots: List<ExplorerRoot> = emptyList(),
    /** Folders opened, root first; empty = the list of roots. */
    val path: List<Pair<String, DiskDir>> = emptyList(),
    val entries: List<DiskEntry> = emptyList(),
    val loading: Boolean = false,
    val bySize: Boolean = false,
    /** Result of "Size of this folder": bytes and files, for the folder it was asked for. */
    val folderSize: Pair<Long, Int>? = null,
    val sizing: Boolean = false,
    val problems: List<SetProblem>? = null,
    val recoveries: List<StorageHelper.Recovery> = emptyList(),
    val storageStatus: StorageAccessStatus? = null,
    /** A file or folder picked up with "Move": it goes into the folder that is open when "Move here" is pressed. */
    val moving: Pair<DiskEntry, DiskDir>? = null,
    /** Something slow is running (moving, extracting); its description. */
    val busy: String? = null
) {
    val atRoots: Boolean get() = path.isEmpty()
    val sorted: List<DiskEntry> get() = entries.sortedWith(
        compareByDescending<DiskEntry> { it.isDirectory }.then(
            if (bySize) compareByDescending { it.size } else compareBy { it.name.lowercase() }
        )
    )
}

/**
 * Looks inside the folders the app may use (download folder, per-console folders, the save sync and
 * ES-DE folders): what is really there, how big it is, whether a file counts as a game, and whether
 * the disc sets of a folder are complete. Files can be opened in another app or deleted.
 */
@HiltViewModel
class FileExplorerViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val extractor: ArchiveExtractorService,
    private val verifiedCopy: VerifiedDocumentCopy,
    private val trash: TrashService,
    private val library: LibraryIndexService,
    private val moveGate: StorageMoveGate,
    private val appSettings: AppSettings
) : ViewModel() {
    private val _state = MutableStateFlow(ExplorerState())
    val state: StateFlow<ExplorerState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(appSettings.activeProfile, appSettings.profiles) { id, json ->
                id to Profiles.restrictionsOf(Profiles.fromJson(json), id)
            }.collect { (profile, restrictions) ->
            _state.value = ExplorerState()
            val roots = buildList {
                settings.downloadDirectory.first().takeIf { !restrictions.active && it.isNotBlank() }?.let { DiskScanner.rootOf(it) }
                    ?.let { add(ExplorerRoot(context.getString(R.string.files_root_downloads), it)) }
                settings.consoleDownloadDirectories.first().forEach { (consoleId, uri) ->
                    if (consoleId !in restrictions.hiddenConsoles && restrictions.hiddenTags.isEmpty())
                        DiskScanner.rootOf(uri)?.let { add(ExplorerRoot(ConsoleFormatter.getConsoleDisplayName(consoleId), it)) }
                }
                settings.saveSyncSavesDir.first().takeIf { !restrictions.active && it.isNotBlank() }?.let { DiskScanner.rootOf(it) }
                    ?.let { add(ExplorerRoot(context.getString(R.string.save_sync_saves_folder), it)) }
                settings.saveSyncStatesDir.first().takeIf { !restrictions.active && it.isNotBlank() && it != settings.saveSyncSavesDir.first() }?.let { DiskScanner.rootOf(it) }
                    ?.let { add(ExplorerRoot(context.getString(R.string.save_sync_states_folder), it)) }
                settings.esdeDirectory.first().takeIf { !restrictions.active && it.isNotBlank() }?.let { DiskScanner.rootOf(it) }
                    ?.let { add(ExplorerRoot(context.getString(R.string.files_root_esde), it)) }
            }.distinctBy { StorageAvailabilityService.key(DiskScanner.uriOf(it.dir).toString()) }
            if (appSettings.activeProfile.first() == profile) _state.update { it.copy(roots = roots) }
            }
        }
    }

    fun openRoot(root: ExplorerRoot) = open(listOf(root.label to root.dir))

    fun openFolder(entry: DiskEntry) {
        val current = _state.value.path.lastOrNull() ?: return
        open(_state.value.path + (entry.name to DiskScanner.dirOf(current.second, entry)))
    }

    /** One level up; false when already at the list of roots. */
    fun up(): Boolean {
        val path = _state.value.path
        if (path.isEmpty()) return false
        if (path.size == 1) _state.update { it.copy(path = emptyList(), entries = emptyList(), folderSize = null, problems = null, recoveries = emptyList()) }
        else open(path.dropLast(1))
        return true
    }

    fun refresh() { _state.value.path.takeIf { it.isNotEmpty() }?.let { open(it) } }

    fun toggleSort() = _state.update { it.copy(bySize = !it.bySize) }

    private fun open(path: List<Pair<String, DiskDir>>) {
        val root = path.first().second
        if (_state.value.roots.none { StorageAvailabilityService.key(DiskScanner.uriOf(it.dir).toString()) == StorageAvailabilityService.key(DiskScanner.uriOf(root).toString()) }) return
        _state.update { it.copy(path = path, entries = if (it.path == path) it.entries else emptyList(), loading = true, folderSize = null, problems = null, recoveries = emptyList(), storageStatus = null) }
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) { StorageAvailabilityService.probe(context, DiskScanner.uriOf(path.last().second).toString()) }
            val entries = withContext(Dispatchers.IO) {
                if (StorageAvailability.readable(status)) DiskScanner.listOrNull(context, path.last().second, true)?.filterNot { it.name.startsWith(".dogmatix-") || it.name.endsWith(".tmp") && it.name.startsWith(".") }
                else null
            }
            val recoveries = withContext(Dispatchers.IO) {
                if (appSettings.activeProfile.first().isNotBlank()) emptyList() else StorageHelper.getDocumentFile(context, DiskScanner.uriOf(path.last().second).toString())
                    ?.let { runCatching { StorageHelper.pendingRecoveries(context, it) }.getOrDefault(emptyList()) }.orEmpty()
            }
            if (_state.value.path == path) _state.update { it.copy(entries = entries ?: it.entries, loading = false, recoveries = recoveries,
                storageStatus = if (entries == null && StorageAvailability.readable(status)) StorageAccessStatus.UNAVAILABLE else status) }
        }
    }

    /** Adds up every file below the current folder (up to six levels deep). */
    fun measure() {
        val dir = _state.value.path.lastOrNull()?.second ?: return
        _state.update { it.copy(sizing = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                var bytes = 0L; var files = 0
                fun walk(d: DiskDir, depth: Int) {
                    for (e in DiskScanner.list(context, d)) {
                        if (e.isDirectory) { if (depth < 6) walk(DiskScanner.dirOf(d, e), depth + 1) } else { bytes += e.size; files++ }
                    }
                }
                walk(dir, 0)
                bytes to files
            }
            _state.update { it.copy(folderSize = result, sizing = false) }
        }
    }

    /** Runs the disc-set check on the files of this folder. */
    fun checkSets() {
        val (label, dir) = _state.value.path.lastOrNull() ?: return
        val files = _state.value.entries.filter { !it.isDirectory }.map {
            DiskFile(scope = "", consoleId = null, folder = label, name = it.name, size = it.size, uri = it.uri.toString(), dirId = "here", fileId = it.uri.toString())
        }
        viewModelScope.launch {
            val problems = withContext(Dispatchers.IO) {
                SetChecker.check(files) { f ->
                    runCatching { context.contentResolver.openInputStream(Uri.parse(f.uri))?.use { BoundedStreams.read(it, SetChecker.MAX_SHEET_BYTES.toInt()) }?.toString(Charsets.UTF_8) }.getOrNull()
                }
            }
            _state.update { it.copy(problems = problems) }
        }
    }

    fun openFile(context: Context, entry: DiskEntry) {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(entry.name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(entry.uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (runCatching { context.startActivity(Intent.createChooser(intent, entry.name)) }.isFailure) {
            ToastUtil.showInfo(context, context.getString(R.string.download_open_none))
        }
    }

    /** Gives [entry] a new name in the same folder. */
    fun rename(context: Context, entry: DiskEntry, newName: String) {
        val name = DatMatcher.safeFileName(newName)
        if (name.isEmpty() || name == entry.name) return
        val app = context.applicationContext
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { DocumentsContract.renameDocument(app.contentResolver, entry.uri, name) }.getOrNull() != null }
            if (!ok) ToastUtil.showError(app, app.getString(R.string.files_rename_failed, entry.name))
            refresh()
        }
    }

    /** Picks [entry] up; "Move here" in another folder puts it there. */
    fun startMove(entry: DiskEntry) {
        val from = _state.value.path.lastOrNull()?.second ?: return
        _state.update { it.copy(moving = entry to from) }
    }

    fun cancelMove() = _state.update { it.copy(moving = null) }

    /**
     * Moves the picked-up entry into the open folder: the storage provider's own move when it can
     * (instant on the same storage), else a copy followed by deleting the original (files only).
     */
    fun moveHere(context: Context) {
        val (entry, from) = _state.value.moving ?: return
        val to = _state.value.path.lastOrNull()?.second ?: return
        val app = context.applicationContext
        if (DiskScanner.canonicalKey(from) == DiskScanner.canonicalKey(to)) { cancelMove(); return }
        _state.update { it.copy(moving = null, busy = app.getString(R.string.files_moving, entry.name)) }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val resolver = app.contentResolver
                val target = DiskScanner.uriOf(to)
                runCatching {
                    val parent = StorageHelper.getDocumentFile(app, target.toString()) ?: error("Destination unavailable")
                    check(parent.findFile(entry.name) == null) { "Destination already exists" }
                    if (entry.isDirectory) {
                        // A provider-native move preserves directory structure without a recursive copy/delete fallback.
                        DocumentsContract.moveDocument(resolver, entry.uri, DiskScanner.uriOf(from), target) != null
                    } else {
                        val hash = verifiedCopy.hash(entry.uri)
                        val copy = verifiedCopy.copy(entry.uri, parent, entry.name)
                        check(verifiedCopy.mayRemove(entry.uri, copy.uri, hash))
                        DiskScanner.delete(app, entry.uri)
                    }
                }.getOrDefault(false)
            }
            _state.update { it.copy(busy = null) }
            if (ok) ToastUtil.showSuccess(app, app.getString(R.string.files_moved, entry.name)) else ToastUtil.showError(app, app.getString(R.string.files_move_failed, entry.name))
            refresh()
        }
    }

    /**
     * Applies an IPS / UPS / BPS patch to [entry] and writes the result next to it as a new file
     * (`Game [Patch name].ext`); the original stays as it is.
     */
    fun applyPatch(context: Context, entry: DiskEntry, patchUri: Uri) {
        val dir = _state.value.path.lastOrNull()?.second ?: return
        val app = context.applicationContext
        if (entry.size > RomPatcher.MAX_SIZE) {
            ToastUtil.showError(app, app.getString(R.string.patch_too_large)); return
        }
        _state.update { it.copy(busy = app.getString(R.string.patch_working, entry.name)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = app.contentResolver
                    val patchName = DocumentFile.fromSingleUri(app, patchUri)?.name ?: "patch"
                    val patch = resolver.openInputStream(patchUri)!!.use { it.readBytes() }
                    val rom = resolver.openInputStream(entry.uri)!!.use { it.readBytes() }
                    val out = RomPatcher.apply(rom, patch)
                    val name = RomPatcher.outputName(entry.name, patchName)
                    val target = DocumentsContract.createDocument(resolver, DiskScanner.uriOf(dir), "application/octet-stream", name) ?: error("cannot create $name")
                    resolver.openOutputStream(target)!!.use { it.write(out) }
                    name
                }
            }
            _state.update { it.copy(busy = null) }
            result.onSuccess { ToastUtil.showSuccess(app, app.getString(R.string.patch_done, it)) }
                .onFailure { ToastUtil.showError(app, app.getString(R.string.patch_failed, it.message ?: "")) }
            refresh()
        }
    }

    /** Unpacks an archive into the folder it is in (the archive stays). */
    fun extract(context: Context, entry: DiskEntry) {
        if (_state.value.busy != null) return
        val dir = _state.value.path.lastOrNull()?.second ?: return
        val app = context.applicationContext
        _state.update { it.copy(busy = app.getString(R.string.files_extracting, entry.name)) }
        viewModelScope.launch {
            try {
                val files = extractor.extractArchive(app, entry.uri, DiskScanner.uriOf(dir), failOnError = true)
                if (files.isNotEmpty()) ToastUtil.showSuccess(app, app.getString(R.string.files_extracted, files.size))
                else ToastUtil.showError(app, app.getString(R.string.files_extract_failed, entry.name))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val safety = generateSequence<Throwable>(e) { it.cause }.take(16).filterIsInstance<ArchiveSafetyException>().firstOrNull()
                val message = when (safety?.reason) {
                    ArchiveSafetyException.Reason.UNSAFE_PATH, ArchiveSafetyException.Reason.NAME_COLLISION,
                    ArchiveSafetyException.Reason.TOO_MANY_ENTRIES -> R.string.archive27_unsafe
                    ArchiveSafetyException.Reason.DESTINATION_CONFLICT -> R.string.archive27_conflict
                    ArchiveSafetyException.Reason.INSUFFICIENT_SPACE -> R.string.archive27_space
                    else -> R.string.files_extract_failed
                }
                ToastUtil.showError(app, app.getString(message, entry.name))
            } finally { _state.update { it.copy(busy = null) }; library.requestRefresh(); refresh() }
        }
    }

    /** Restore only a verified copy in this folder; a different current file is protected. */
    fun recover(recovery: StorageHelper.Recovery) {
        if (_state.value.busy != null) return
        val dir = _state.value.path.lastOrNull()?.second ?: return
        if (recovery !in _state.value.recoveries) return
        _state.update { it.copy(busy = context.getString(R.string.recovery27_title)) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    appSettings.withActiveProfile("") { moveGate.lock.withLock {
                        check(appSettings.activeProfile.first().isBlank()) { "Unowned receipt unavailable in this profile" }
                        check(_state.value.path.lastOrNull()?.second == dir && recovery in _state.value.recoveries)
                        check(StorageAvailability.writable(StorageAvailabilityService.probe(context, DiskScanner.uriOf(dir).toString())))
                        val folder = StorageHelper.getDocumentFile(context, DiskScanner.uriOf(dir).toString()) ?: error("Folder unavailable")
                        StorageHelper.restoreRecovery(context, folder, recovery)
                    } }
                }
                ToastUtil.showSuccess(context, context.getString(R.string.recovery27_done, recovery.fileName))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { ToastUtil.showError(context, context.getString(R.string.recovery27_failed)) }
            finally { _state.update { it.copy(busy = null) }; library.requestRefresh(); refresh() }
        }
    }

    fun delete(context: Context, entry: DiskEntry, permanent: Boolean = false) {
        if (_state.value.busy != null) return
        val parent = _state.value.path.lastOrNull()?.second ?: return
        val app = context.applicationContext
        _state.update { it.copy(busy = app.getString(R.string.files25_trash)) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (permanent) moveGate.lock.withLock { check(DiskScanner.delete(app, entry.uri)) }
                    else {
                        val root = DiskScanner.uriOf(parent).toString()
                        val files = ArrayList<RemovalFile>()
                        val dirs = ArrayList<OperationDirectory>()
                        fun gather(current: DiskEntry, at: DiskDir, path: String, depth: Int) {
                            check(depth <= 20 && files.size <= 10000) { "Folder too large" }
                            if (current.isDirectory) {
                                check(current.name != ".dogmatix-trash") { "Recovery directory" }
                                dirs += OperationDirectory(root, path)
                                val sub = DiskScanner.dirOf(at, current)
                                (DiskScanner.listOrNull(app, sub, true) ?: error("Folder unavailable")).forEach { gather(it, sub, "$path/${it.name}", depth + 1) }
                            } else files += RemovalFile(current.uri.toString(), root, current.name, current.size, path, path)
                        }
                        gather(entry, parent, entry.name, 0)
                        trash.move(files, entry.name, directories = dirs)
                    }
                }
                ToastUtil.showSuccess(app, app.getString(if (permanent) R.string.duplicates_deleted else if (entry.isDirectory) R.string.files25_folder_stored else R.string.files25_stored, entry.name))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { ToastUtil.showError(app, app.getString(R.string.duplicates_delete_failed, entry.name)) }
            finally { _state.update { it.copy(busy = null) }; library.requestRefresh(); refresh() }
        }
    }
}

@Composable
fun FileExplorerScreen(viewModel: FileExplorerViewModel = hiltViewModel(), onNavigate: (String) -> Unit = {}) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selected by remember { mutableStateOf<DiskEntry?>(null) }
    var recovering by remember { mutableStateOf<StorageHelper.Recovery?>(null) }
    var confirmDelete by remember { mutableStateOf<DiskEntry?>(null) }
    var permanently by remember { mutableStateOf(false) }
    recovering?.let { recovery ->
        ConfirmDialog(
            title = stringResource(R.string.recovery27_title),
            message = stringResource(R.string.recovery27_confirm, recovery.fileName),
            confirmText = stringResource(R.string.recovery27_title),
            onConfirm = { recovering = null; viewModel.recover(recovery) },
            onDismiss = { recovering = null }
        )
    }
    BackHandler(enabled = !ui.atRoots) { viewModel.up() }

    var renaming by remember { mutableStateOf<DiskEntry?>(null) }
    var patching by remember { mutableStateOf<DiskEntry?>(null) }
    val patchPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val entry = patching
        if (uri != null && entry != null) viewModel.applyPatch(context, entry, uri)
        patching = null
    }
    selected?.let { entry ->
        FileDetailsDialog(
            entry = entry,
            onOpen = { selected = null; viewModel.openFile(context, entry) },
            onDelete = { selected = null; permanently = false; confirmDelete = entry },
            onRename = { selected = null; renaming = entry },
            onMove = { selected = null; viewModel.startMove(entry) },
            onPatch = { selected = null; patching = entry; patchPicker.launch(arrayOf("*/*")) },
            onExtract = if (ArchiveUtils.isExtractable(entry.name.substringAfterLast('.', ""))) ({ selected = null; viewModel.extract(context, entry) }) else null,
            onDismiss = { selected = null }
        )
    }
    renaming?.let { entry ->
        RenameDialog(entry.name, onSave = { viewModel.rename(context, entry, it); renaming = null }, onDismiss = { renaming = null })
    }
    confirmDelete?.let { entry ->
        val cancel = rememberInitialFocus()
        AlertDialog(modifier = Modifier.closeOnGamepadB { confirmDelete = null },
            onDismissRequest = { confirmDelete = null }, title = { Text(entry.name) },
            text = { Column {
                Text(stringResource(if (permanently) R.string.files25_permanent_hint else R.string.files25_trash_hint))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(permanently, { permanently = it })
                    Text(stringResource(R.string.files25_permanent))
                }
            } },
            confirmButton = { DialogButton(stringResource(if (permanently) R.string.dialog_delete else R.string.files25_trash),
                { viewModel.delete(context, entry, permanently); confirmDelete = null }, destructive = permanently) },
            dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), { confirmDelete = null }, initialFocus = cancel) })
    }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(ui.path.size, ui.loading) { firstFocus.requestFocusWhenReady() }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        if (ui.atRoots) ToolsTitle(stringResource(R.string.nav_files), icon = NavRoutes.Files.icon)
        else {
            ToolsTitle(ui.path.last().first, icon = R.drawable.ic_folder_open)
            Breadcrumb(ui.path.map { it.first })
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "recovery-hub") {
                ToolRow(stringResource(R.string.road28_recovery_title), listOf(stringResource(R.string.road28_recovery_hint)), { onNavigate("recovery") }, icon = R.drawable.ic_history, chevron = true)
            }
            if (ui.atRoots) {
                if (ui.roots.isEmpty()) item { InfoCard(listOf(stringResource(R.string.tools_no_folder)), icon = R.drawable.ic_folder_open) }
                items(ui.roots, key = { "root:" + it.label }) { root ->
                    val index = ui.roots.indexOf(root)
                    ToolRow(root.label, listOf(stringResource(R.string.files_root_hint)), { viewModel.openRoot(root) },
                        modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                        icon = R.drawable.ic_folder, chevron = true)
                }
            } else {
                if (ui.storageStatus != null && !StorageAvailability.writable(ui.storageStatus!!)) item(key = "storage-status") {
                    ToolRow(stringResource(if (ui.storageStatus == StorageAccessStatus.READ_ONLY) R.string.road28_storage_read_only else R.string.road28_storage_unavailable),
                        listOf(stringResource(R.string.road28_storage_cached)), { viewModel.refresh() }, icon = R.drawable.ic_folder_open) {
                        ToolAction(stringResource(R.string.road28_storage_check)) { viewModel.refresh() }
                    }
                }
                ui.moving?.let { (entry, _) ->
                    item(key = "moving") {
                        ToolRow(
                            stringResource(R.string.files_move_pending, entry.name), listOf(stringResource(R.string.files_move_hint)), { viewModel.moveHere(context) },
                            icon = R.drawable.ic_drive_file_move
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ToolAction(stringResource(R.string.files_move_here), tone = ActionTone.Accent) { viewModel.moveHere(context) }
                                ToolAction(stringResource(R.string.dialog_cancel)) { viewModel.cancelMove() }
                            }
                        }
                    }
                }
                ui.busy?.let { item(key = "busy") { InfoCard(listOf(it), accent = true, icon = R.drawable.ic_hourglass) } }
                if (ui.recoveries.isNotEmpty()) {
                    item(key = "recovery-info") { InfoCard(listOf(
                        pluralStringResource(R.plurals.recovery27_available, ui.recoveries.size, ui.recoveries.size),
                        stringResource(R.string.recovery27_hint)), icon = R.drawable.ic_retry) }
                    items(ui.recoveries, key = { "recovery:" + it.id }) { recovery ->
                        ToolRow(recovery.fileName, emptyList(), { if (ui.busy == null) recovering = recovery }, icon = R.drawable.ic_retry) {
                            ToolAction(stringResource(R.string.recovery27_title)) { if (ui.busy == null) recovering = recovery }
                        }
                    }
                }
                item(key = "tools") {
                    val folders = ui.entries.count { it.isDirectory }
                    val files = ui.entries.size - folders
                    val summary = if (ui.loading) stringResource(R.string.tools_scanning)
                    else stringResource(R.string.files_summary, folders, files, formatBytes(ui.entries.filter { !it.isDirectory }.sumOf { it.size }))
                    ToolRow(summary, emptyList(), viewModel::up, Modifier.focusRequester(firstFocus), icon = R.drawable.ic_arrow_up)
                }
                item(key = "actions") {
                    ToolsActions {
                        ToolAction(stringResource(R.string.files_up), icon = R.drawable.ic_arrow_up, onClick = viewModel::up)
                        ToolAction(stringResource(if (ui.bySize) R.string.files_sort_name else R.string.files_sort_size), icon = R.drawable.ic_sort, onClick = viewModel::toggleSort)
                        ToolAction(stringResource(R.string.files_measure), icon = R.drawable.ic_storage, onClick = viewModel::measure)
                        ToolAction(stringResource(R.string.files_check), icon = R.drawable.ic_check_circle, onClick = viewModel::checkSets)
                    }
                }
                if (ui.sizing) item(key = "sizing") { InfoCard(listOf(stringResource(R.string.files_sizing)), accent = true, icon = R.drawable.ic_hourglass) }
                ui.folderSize?.let { (bytes, count) ->
                    item(key = "size") {
                        val here = ui.entries.filter { !it.isDirectory }.sumOf { it.size }
                        SizeCard(bytes, count, here)
                    }
                }
                ui.problems?.let { problems ->
                    item(key = "problems") {
                        InfoCard(
                            if (problems.isEmpty()) listOf(stringResource(R.string.files_check_ok))
                            else listOf(pluralStringResource(R.plurals.files_check_problems, problems.size, problems.size)) +
                                problems.map { p -> p.sheet.name + ": " + when (p.kind) {
                                    SetProblem.Kind.EMPTY_SHEET -> context.getString(R.string.sets_empty_sheet)
                                    SetProblem.Kind.MISSING_DISCS -> context.getString(R.string.sets_missing_discs, p.missing.joinToString(", "))
                                    SetProblem.Kind.MISSING_TRACKS -> context.getString(R.string.sets_missing_tracks, p.missing.joinToString(", "))
                                } },
                            icon = if (problems.isEmpty()) R.drawable.ic_check_circle else R.drawable.ic_warning,
                            danger = problems.isNotEmpty()
                        )
                    }
                }
                if (!ui.loading && ui.entries.isEmpty()) item(key = "empty") { InfoCard(listOf(stringResource(R.string.files_empty)), icon = R.drawable.ic_folder_open) }
                items(ui.sorted, key = { it.documentId }) { entry ->
                    if (entry.isDirectory) {
                        ToolRow(entry.name, emptyList(), { viewModel.openFolder(entry) }, icon = R.drawable.ic_folder, chevron = true) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                ToolAction(stringResource(R.string.files_rename)) { renaming = entry }
                                ToolAction(stringResource(R.string.files_move)) { viewModel.startMove(entry) }
                            }
                        }
                    } else {
                        val game = DuplicateFinder.isGameFile(entry.name)
                        ToolRow(
                            entry.name,
                            listOf(formatBytes(entry.size)),
                            { selected = entry },
                            badge = if (game) ({ Badge(stringResource(R.string.files_badge_game), warning = false, tone = PillTone.Accent) }) else null,
                            icon = fileIcon(entry.name, game)
                        )
                    }
                }
            }
        }
    }
}

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

/** The icon of a file by what it is: archive, picture, game or anything else. */
private fun fileIcon(name: String, isGame: Boolean): Int {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        ArchiveUtils.isExtractable(ext) -> R.drawable.ic_archive
        ext in IMAGE_EXTENSIONS -> R.drawable.ic_image
        isGame -> R.drawable.ic_gamepad
        else -> R.drawable.ic_description
    }
}

/** The folders opened so far as pills, the last one highlighted; scrolls sideways when long. */
@Composable
private fun Breadcrumb(parts: List<String>) {
    val scroll = rememberScrollState()
    LaunchedEffect(parts) {
        withFrameNanos { }
        scroll.scrollTo(scroll.maxValue)
    }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        parts.forEachIndexed { i, name ->
            if (i > 0) Icon(
                painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp)
            )
            Pill(name, tone = if (i == parts.lastIndex) PillTone.Accent else PillTone.Neutral)
        }
    }
}

/** The result of "Folder size": the total, and how much of it lies directly in the open folder. */
@Composable
private fun SizeCard(bytes: Long, files: Int, directBytes: Long) {
    val scheme = MaterialTheme.colorScheme
    Panel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(R.drawable.ic_storage, size = 36.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(formatBytes(bytes), style = MaterialTheme.typography.headlineSmall.tabular(), color = scheme.onSurface, maxLines = 1)
                Text(
                    stringResource(R.string.files_size_result, formatBytes(bytes), files),
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant
                )
            }
        }
        if (bytes > 0) {
            Spacer(Modifier.height(10.dp))
            MeterBar(directBytes.toFloat() / bytes, height = 6.dp)
            Text(
                stringResource(R.string.tools5_files_direct, formatBytes(directBytes)),
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun RenameDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    val cancelFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.files_rename)) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it.take(250) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { DialogButton(text = stringResource(R.string.dialog_save), onClick = { onSave(name) }, enabled = name.isNotBlank() && name != current) },
        dismissButton = { DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss, initialFocus = cancelFocus) }
    )
}

@Composable
private fun FileDetailsDialog(
    entry: DiskEntry,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onPatch: () -> Unit,
    onExtract: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val openFocus = rememberInitialFocus()
    val ext = entry.name.substringAfterLast('.', "").lowercase()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(entry.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.files_info_size, formatBytes(entry.size), entry.size))
                Text(stringResource(R.string.files_info_type, ext.ifEmpty { "—" }))
                Text(stringResource(if (DuplicateFinder.isGameFile(entry.name)) R.string.files_info_game else R.string.files_info_not_game),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ToolsActions(modifier = Modifier.padding(top = 8.dp), horizontalPadding = 0.dp) {
                    ToolAction(stringResource(R.string.files_rename), onClick = onRename)
                    ToolAction(stringResource(R.string.files_move), onClick = onMove)
                    onExtract?.let { ToolAction(stringResource(R.string.files_extract), onClick = it) }
                    if (DuplicateFinder.isGameFile(entry.name) && onExtract == null) ToolAction(stringResource(R.string.patch_apply), onClick = onPatch)
                }
            }
        },
        confirmButton = { DialogButton(text = stringResource(R.string.download_open), onClick = onOpen, initialFocus = openFocus) },
        dismissButton = {
            DialogButton(text = stringResource(R.string.duplicates_delete), onClick = onDelete, destructive = true)
            DialogButton(text = stringResource(R.string.dialog_close), onClick = onDismiss)
        }
    )
}
