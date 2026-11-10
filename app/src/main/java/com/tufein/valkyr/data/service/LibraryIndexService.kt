package com.tufein.valkyr.data.service

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.tufein.valkyr.data.local.dao.DownloadableFileDao
import com.tufein.valkyr.data.local.entity.DownloadableFileEntity
import com.tufein.valkyr.data.model.DownloadStatus
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.util.FileParsingUtils
import com.tufein.valkyr.util.DiskDir
import com.tufein.valkyr.util.DiskScanner
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import com.tufein.valkyr.util.ConsoleFolderAliases
import com.tufein.valkyr.util.GameArtifacts
import com.tufein.valkyr.util.DiskEntry
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.tufein.valkyr.util.LibraryKeys
import com.tufein.valkyr.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import com.tufein.valkyr.util.FreshNames
import com.tufein.valkyr.util.SheetParser
import java.io.ByteArrayOutputStream

/**
 * Knows what is already on disk: the file names under the download folders (including nested archive folders)
 * so the library can mark owned games, plus the free space of the download volume.
 * Keys are scoped by the folder they were found in (see [LibraryKeys]) so a game owned for one
 * console is not marked for another console whose ROM happens to share the file name.
 * Rescans when the folders change in Settings or a download finishes.
 */
@OptIn(FlowPreview::class)
@Singleton
class LibraryIndexService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val downloadService: DownloadService,
    private val downloadableFileDao: DownloadableFileDao,
    private val trash: TrashService,
    private val gamePackages: GamePackageService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshLock = Mutex()
    private data class PackageOwnership(val complete: Map<String, Boolean> = emptyMap(),
        val names: Set<Pair<String, String>> = emptySet(), val roots: Set<String> = emptySet())
    @Volatile private var packageOwnership = PackageOwnership()

    private val _ownedKeys = MutableStateFlow<Set<String>>(emptySet())
    /** `scope|name` keys of the files found on disk; see [LibraryKeys]. */
    val ownedKeys: StateFlow<Set<String>> = _ownedKeys.asStateFlow()

    private val _freeBytes = MutableStateFlow<Long?>(null)
    val freeBytes: StateFlow<Long?> = _freeBytes.asStateFlow()

    private val finishedNames = FreshNames()

    init {
        scope.launch {
            combine(settingsRepository.downloadDirectory, settingsRepository.consoleDownloadDirectories) { root, perConsole ->
                listOf(root) + perConsole.values
            }.distinctUntilChanged().collect { refresh() }
        }
        scope.launch {
            downloadService.downloads
                .map { list -> list.filter { it.status == DownloadStatus.COMPLETED }.map { it.fileName }.toSet() }
                .distinctUntilChanged()
                // Mark finished downloads as owned right away; the full disk walk is slow over SAF.
                // Only the ones that finished since the last change: looking up every finished row
                // again each time was quadratic, and each lookup scanned the whole library table.
                .onEach { completed ->
                    val keys = finishedNames.next(completed).flatMap { keysForCompleted(it) }
                    if (keys.isNotEmpty()) _ownedKeys.update { it + keys }
                }
                .debounce(500)
                .collect { refresh() }
        }
    }

    /** Finished downloads already marked as owned (see [FreshNames]). */

    /** Keys for a download that just finished: scoped to its console (known from the download itself). */
    private suspend fun keysForCompleted(fileName: String): List<String> {
        val consoleId = (downloadService.entityFor(fileName) ?: downloadableFileDao.getFileByFileName(fileName))?.consoleId
            ?: return emptyList()
        val storedName = runCatching { FileParsingUtils.storageFileName(fileName) }.getOrNull() ?: return emptyList()
        return LibraryKeys.keysFor(LibraryKeys.consoleScope(consoleId), storedName)
    }

    fun isOwned(file: DownloadableFileEntity, keys: Set<String> = _ownedKeys.value): Boolean {
        val packages = packageOwnership
        return if (file.consoleId to file.fileName in packages.names || gamePackages.hasRecordedIdentity(file, packages.roots)) packages.complete[gamePackages.identity(file)] == true
        else LibraryKeys.isOwned(file.consoleId, file.fileName, keys)
    }

    suspend fun packageInspection(file: DownloadableFileEntity, verifyHashes: Boolean = false): PackageInspection? {
        val roots = setOf(settingsRepository.downloadDirectory.first(),
            settingsRepository.consoleDownloadDirectories.first()[file.consoleId].orEmpty())
        val manifests = gamePackages.recordsFor(file, roots)
        var incomplete: PackageInspection? = null
        for (manifest in manifests) {
            currentCoroutineContext().ensureActive()
            val inspection = gamePackages.inspect(manifest, verifyHashes)
            if (inspection.complete) return inspection
            if (incomplete == null) incomplete = inspection
        }
        return incomplete
    }

    /** Starts a [refresh] on the service's own scope: it outlives the screen that asked for it. */
    fun requestRefresh() {
        scope.launch { refresh() }
    }

    /**
     * Reads the download folders again. Each folder is listed with one provider query
     * ([DiskScanner]) instead of one per file, and the console folders are read a few at a time.
     */
    suspend fun refresh() = refreshLock.withLock { refreshNow() }

    private suspend fun refreshNow() = withContext(Dispatchers.IO) {
        val root = settingsRepository.downloadDirectory.first()
        val custom = settingsRepository.consoleDownloadDirectories.first()
        val keys = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        val limit = Semaphore(PARALLEL_FOLDERS)
        val knownConsoleFolders = ConsoleFolderAliases.knownFolderScopes()
        val visited = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        val folderCount = AtomicInteger()
        val coroutine = currentCoroutineContext()
        val checkActive = { coroutine.ensureActive() }
        coroutineScope {
            if (root.isNotBlank()) DiskScanner.rootOf(root)?.let { dir ->
                for (child in DiskScanner.list(context, dir)) {
                    if (child.isDirectory) launch { limit.withPermit {
                        val folder = LibraryKeys.folderScope(child.name)
                        val scope = if (folder in knownConsoleFolders) folder else LibraryKeys.ROOT_SCOPE
                        collect(DiskScanner.dirOf(dir, child), scope, keys, depth = 1, visited, folderCount, checkActive)
                    } }
                    else keys += LibraryKeys.keysFor(LibraryKeys.ROOT_SCOPE, child.name)
                }
            }
            custom.forEach { (consoleId, uri) ->
                val dir = uri.takeIf { it.isNotBlank() }?.let { DiskScanner.rootOf(it) } ?: return@forEach
                launch { limit.withPermit { collect(dir, LibraryKeys.customScope(consoleId), keys, depth = 0, visited, folderCount, checkActive) } }
            }
        }
        val packages = gamePackages.records((listOf(root) + custom.values).toSet())
            .filter { it.rootUri == root || it.rootUri == custom[it.consoleId] }
        val ownership = HashMap<String, Boolean>()
        for (inspection in gamePackages.inspectBatch(packages)) {
            checkActive()
            val manifest = inspection.manifest
            val complete = inspection.complete
            ownership[manifest.sourceIdentity] = ownership[manifest.sourceIdentity] == true || complete
            if (complete) keys += LibraryKeys.keysFor(LibraryKeys.consoleScope(manifest.consoleId), FileParsingUtils.storageFileName(manifest.fileName))
        }
        packageOwnership = PackageOwnership(ownership, packages.map { it.consoleId to it.fileName }.toSet(), (listOf(root) + custom.values).toSet())
        _ownedKeys.value = HashSet(keys)
        _freeBytes.value = (listOf(root) + custom.values).firstOrNull { it.isNotBlank() }
            ?.let { StorageHelper.getFreeBytes(context, it) }
    }

    /** Exact files shown before confirmation. Fail closed if any folder cannot be listed. */
    suspend fun removalPlan(file: DownloadableFileEntity): List<RemovalFile> = artifactPlan(file, protectShared = true)

    /** Play also needs tracks/discs shared with another descriptor; these are read grants, never a deletion plan. */
    suspend fun launchPlan(file: DownloadableFileEntity): List<RemovalFile> = artifactPlan(file, protectShared = false)

    private suspend fun artifactPlan(file: DownloadableFileEntity, protectShared: Boolean): List<RemovalFile> = withContext(Dispatchers.IO) {
        if (!protectShared) return@withContext launchArtifacts(file)
        val name = FileParsingUtils.storageFileName(file.fileName)
        val scopes = LibraryKeys.scopesFor(file.consoleId)
        val result = ArrayList<RemovalFile>()
        fun addFiles(dir: DiskDir, entries: List<DiskEntry>, path: String) {
            fun references(entry: DiskEntry): List<String> {
                val ext = entry.name.substringAfterLast('.').lowercase()
                if (ext !in setOf("cue", "m3u", "gdi")) return emptyList()
                val text = context.contentResolver.openInputStream(entry.uri)?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (out.size() <= 1024 * 1024) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                    }
                    val bytes = out.toByteArray()
                    check(bytes.size <= 1024 * 1024) { "Descriptor too large" }
                    bytes.toString(Charsets.UTF_8)
                } ?: error("Descriptor unavailable")
                return when (ext) {
                    "cue" -> SheetParser.cueFiles(text)
                    "gdi" -> SheetParser.gdiFiles(text)
                    else -> SheetParser.m3uFiles(text)
                }
            }
            val files = entries.filter { !it.isDirectory }.associateBy { it.name }
            val selected = GameArtifacts.plan(files.keys.toList(), name, protectShared) { references(files.getValue(it)) }
            selected.map { files.getValue(it) }.forEach { entry -> result += RemovalFile(entry.uri.toString(), DiskScanner.uriOf(dir).toString(), entry.name, entry.size, "$path${entry.name}") }
        }
        fun walk(dir: DiskDir, path: String, depth: Int) {
            val entries = DiskScanner.listOrNull(context, dir, true) ?: error("Folder cannot be read completely")
            addFiles(dir, entries, path)
            if (depth < 2) entries.filter { it.isDirectory && !it.name.startsWith(".dogmatix-") }.forEach { walk(DiskScanner.dirOf(dir, it), "$path${it.name}/", depth + 1) }
        }
        val root = settingsRepository.downloadDirectory.first()
        DiskScanner.rootOf(root)?.let { dir ->
            val entries = DiskScanner.listOrNull(context, dir, true) ?: error("Library unavailable")
            addFiles(dir, entries, "")
            entries.filter { it.isDirectory && LibraryKeys.folderScope(it.name) in scopes }.forEach { walk(DiskScanner.dirOf(dir, it), "${it.name}/", 1) }
        }
        settingsRepository.consoleDownloadDirectories.first()[file.consoleId]?.let { uri -> DiskScanner.rootOf(uri)?.let { walk(it, "", 0) } }
        result.distinctBy { it.uri }
    }

    /** [consoleId] and [fileName] (the library row's) only go to the action history. */
    suspend fun deletePlan(plan: List<RemovalFile>, title: String, consoleId: String? = null, fileName: String? = null): Boolean = withContext(Dispatchers.IO) {
        val removed = trash.move(plan, title, consoleId, fileName)
        if (removed > 0) requestRefresh()
        removed > 0
    }

    suspend fun deleteOwned(file: DownloadableFileEntity): Boolean = deletePlan(removalPlan(file), file.name, file.consoleId, file.fileName)

    /** Reads only selected descriptors, within a recognized console root or flat archive tree. */
    private suspend fun launchArtifacts(file: DownloadableFileEntity): List<RemovalFile> {
        // Receipt paths may differ completely from the archive name. Read grants use only this
        // exact source's recorded files; deletion deliberately keeps its conservative old plan.
        packageInspection(file)?.let { receipt ->
            return receipt.files.filter { GameArtifacts.safeLaunchPath(it.path) }
        }
        val selectedRoots = setOf(settingsRepository.downloadDirectory.first(), settingsRepository.consoleDownloadDirectories.first()[file.consoleId].orEmpty())
        if (gamePackages.hasRecordedIdentity(file, selectedRoots)) return emptyList()
        val requested = FileParsingUtils.storageFileName(file.fileName)
        val scopes = LibraryKeys.scopesFor(file.consoleId)
        val knownConsoles = ConsoleFolderAliases.knownFolderScopes()
        val result = ArrayList<RemovalFile>()
        val coroutine = currentCoroutineContext()
        val checkActive = { coroutine.ensureActive() }
        fun plan(root: DiskDir, filterTopLevel: Boolean) {
            data class Located(val directory: DiskDir, val entry: DiskEntry)
            val files = LinkedHashMap<String, Located>()
            val visited = HashSet<String>()
            var directories = 0
            fun walk(dir: DiskDir, path: String, depth: Int) {
                checkActive()
                if (!visited.add(DiskScanner.canonicalKey(dir))) return
                check(++directories <= MAX_SCANNED_FOLDERS) { "Library has too many folders" }
                val entries = DiskScanner.listOrNull(context, dir, true) ?: error("Folder cannot be read completely")
                for (entry in entries) {
                    checkActive()
                    if (entry.name.startsWith(".dogmatix-")) continue
                    if (entry.isDirectory) {
                        if (depth == 0 && filterTopLevel) {
                            val scope = LibraryKeys.folderScope(entry.name)
                            if (scope in knownConsoles && scope !in scopes) continue
                        }
                        if (depth < MAX_SCAN_DEPTH) walk(DiskScanner.dirOf(dir, entry), "$path${entry.name}/", depth + 1)
                    } else {
                        val relative = "$path${entry.name}"
                        if (GameArtifacts.safeLaunchPath(relative)) {
                            check(relative !in files) { "Ambiguous library file" }
                            files[relative] = Located(dir, entry)
                        }
                    }
                }
            }
            walk(root, "", 0)
            val selected = GameArtifacts.playPaths(files.keys.toList(), requested) { path ->
                val entry = files.getValue(path).entry
                val text = context.contentResolver.openInputStream(entry.uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() <= MAX_DESCRIPTOR_BYTES) {
                        checkActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    check(output.size() <= MAX_DESCRIPTOR_BYTES) { "Descriptor too large" }
                    output.toByteArray().toString(Charsets.UTF_8)
                } ?: error("Descriptor unavailable")
                when (entry.name.substringAfterLast('.').lowercase()) {
                    "cue" -> SheetParser.cueFiles(text)
                    "gdi" -> SheetParser.gdiFiles(text)
                    else -> SheetParser.m3uFiles(text)
                }
            }
            selected.forEach { path ->
                val found = files.getValue(path)
                val entry = found.entry
                result += RemovalFile(entry.uri.toString(), DiskScanner.uriOf(found.directory).toString(), entry.name, entry.size, path)
            }
        }
        DiskScanner.rootOf(settingsRepository.downloadDirectory.first())?.let { plan(it, filterTopLevel = true) }
        settingsRepository.consoleDownloadDirectories.first()[file.consoleId]?.let { uri ->
            DiskScanner.rootOf(uri)?.let { plan(it, filterTopLevel = false) }
        }
        return result.distinctBy { it.uri }
    }

    private fun collect(dir: DiskDir, scope: String, into: MutableSet<String>, depth: Int,
        visited: MutableSet<String>, folderCount: AtomicInteger, checkActive: () -> Unit) {
        checkActive()
        if (!visited.add("$scope|${DiskScanner.canonicalKey(dir)}") || folderCount.incrementAndGet() > MAX_SCANNED_FOLDERS) return
        for (child in DiskScanner.list(context, dir)) {
            checkActive()
            if (child.isDirectory) {
                if (depth < MAX_SCAN_DEPTH) collect(DiskScanner.dirOf(dir, child), scope, into, depth + 1, visited, folderCount, checkActive)
            } else {
                into += LibraryKeys.keysFor(scope, child.name)
            }
        }
    }

    private companion object {
        /** Console folders listed at the same time. */
        const val PARALLEL_FOLDERS = 4
        // Archive paths permit 64 segments; a console folder adds one container level.
        const val MAX_SCAN_DEPTH = 65
        const val MAX_SCANNED_FOLDERS = 10000
        const val MAX_DESCRIPTOR_BYTES = 1024 * 1024
    }
}
