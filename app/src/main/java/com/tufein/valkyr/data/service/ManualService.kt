package com.tufein.valkyr.data.service

import android.content.Context
import android.util.Log
import com.tufein.valkyr.data.local.ManualSettings
import com.tufein.valkyr.util.Manuals
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Manuals"

/** How a manual download ended. */
enum class ManualResult { SAVED, EXISTS, NO_MANUAL, TOO_BIG, OFFLINE, FAILED }

/** The manual RomM stores for a game: its RomM id and the file on the server. */
data class RemoteManual(val romId: Int, val url: String)

/**
 * Game manuals in the app's own storage (`files/manuals/<console>/<game>/`, 6.0). A manual comes only
 * from the user's RomM server (its `path_manual` file) and only while "Use RomM" is on; nothing is
 * fetched from anywhere else. Writes go through a temporary file, an existing file is never replaced,
 * and the total is kept under the size cap (oldest manuals of other games go first).
 */
@Singleton
class ManualService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: ManualSettings,
    private val rommClient: RommClient,
    private val rommGames: RommGameService,
    private val network: NetworkMonitor,
    downloads: DownloadService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val root get() = File(context.filesDir, Manuals.ROOT)

    private val _changes = MutableStateFlow(0)
    /** Moves whenever a manual is added or removed (screens list again). */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    init {
        // With "Download manual with the game" on, a finished RomM download fetches its manual too.
        scope.launch {
            downloads.finished.collect { name ->
                if (!settings.withGame.first()) return@collect
                val file = downloads.entityFor(name) ?: return@collect
                launch {
                    val r = download(file.consoleId, file.fileName)
                    Log.i(TAG, "Manual with the game: $r")
                }
            }
        }
    }

    private fun dirOf(consoleId: String, fileName: String) = File(context.filesDir, Manuals.relativeDir(consoleId, fileName))

    /** The readable manual files of a game, by name. */
    suspend fun files(consoleId: String, fileName: String): List<String> = withContext(Dispatchers.IO) {
        dirOf(consoleId, fileName).listFiles()?.filter { it.isFile && Manuals.kindOf(it.name) != Manuals.Kind.OTHER }?.map { it.name }.orEmpty()
    }

    suspend fun docs(consoleId: String, fileName: String): List<Manuals.Doc> = Manuals.docsIn(files(consoleId, fileName))

    fun fileOf(consoleId: String, fileName: String, name: String): File = File(dirOf(consoleId, fileName), Manuals.safeSegment(name, "manual"))

    /** The manual RomM has for the game; null when RomM is off, the game is not on it, or it has no manual. */
    suspend fun remote(consoleId: String, fileName: String): RemoteManual? = withContext(Dispatchers.IO) {
        runCatching {
            val id = rommGames.romIdFor(consoleId, fileName) ?: return@runCatching null
            val url = rommGames.details(id).manualUrl ?: return@runCatching null
            RemoteManual(id, url)
        }.getOrNull()
    }

    /** Downloads the game's manual from RomM. [known] skips the lookup when the caller already has it. */
    suspend fun download(consoleId: String, fileName: String, known: RemoteManual? = null): ManualResult = withContext(Dispatchers.IO) {
        if (!network.online.value) return@withContext ManualResult.OFFLINE
        val remote = known ?: remote(consoleId, fileName) ?: return@withContext ManualResult.NO_MANUAL
        val name = Manuals.fileNameFor(remote.url)
        val target = fileOf(consoleId, fileName, name)
        if (target.exists()) return@withContext ManualResult.EXISTS
        val cap = Manuals.capBytes(settings.capMb.first())
        try {
            val bytes = rommClient.downloadServerFile(remote.url, Manuals.maxFileBytes(cap))
            lock.withLock {
                if (target.exists()) return@withLock ManualResult.EXISTS
                val existing = entries()
                when (val fit = Manuals.fit(existing, bytes.size.toLong(), cap)) {
                    Manuals.Fit.TooBig -> ManualResult.TOO_BIG
                    is Manuals.Fit.Ok -> {
                        fit.remove.forEach { File(root, it.path).delete() }
                        target.parentFile?.mkdirs()
                        val tmp = File(target.parentFile, target.name + ".part")
                        tmp.writeBytes(bytes)
                        if (!tmp.renameTo(target)) { tmp.delete(); ManualResult.FAILED } else {
                            prune()
                            _changes.value++
                            ManualResult.SAVED
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Manual download failed: ${e.javaClass.simpleName}")
            if (rommErrorKind(e) == RommErrorKind.UNREACHABLE) ManualResult.OFFLINE else ManualResult.FAILED
        }
    }

    /** Removes every manual of the game. */
    suspend fun delete(consoleId: String, fileName: String) = withContext(Dispatchers.IO) {
        lock.withLock { dirOf(consoleId, fileName).deleteRecursively(); prune() }
        _changes.value++
    }

    private fun entries(): List<Manuals.Entry> =
        root.walkTopDown().filter { it.isFile }.map { Manuals.Entry(it.relativeTo(root).path, it.length(), it.lastModified()) }.toList()

    /** Removes empty game and console folders. */
    private fun prune() {
        root.listFiles()?.forEach { console ->
            console.listFiles()?.forEach { game -> if (game.isDirectory && game.list().isNullOrEmpty()) game.delete() }
            if (console.isDirectory && console.list().isNullOrEmpty()) console.delete()
        }
    }

    /** Space all manuals take, for "Free up space". */
    suspend fun usedBytes(): Long = withContext(Dispatchers.IO) { entries().sumOf { it.size } }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        lock.withLock { root.deleteRecursively() }
        _changes.value++
    }
}
