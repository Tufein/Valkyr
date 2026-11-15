package com.tufein.valkyr.data.service

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.tufein.valkyr.data.local.dao.DownloadableFileDao
import com.tufein.valkyr.data.model.DownloadableFileWithTags
import com.tufein.valkyr.util.UpNext
import com.tufein.valkyr.util.UpNextEntry
import com.tufein.valkyr.util.UpNextState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "UpNext"

/**
 * The "Up next" list (3.0.0): the games the player means to play next, per profile, in their own
 * order. Each profile's list is a small JSON file under `files/upnext/` written atomically
 * ([AtomicFile]); [entries] always holds the active profile's list, read again on a profile switch.
 * Deleting a profile forgets its list. Edits go through one lock and always land in the list of
 * the profile that is active when they run, never in the one shown a moment before a switch.
 */
@Singleton
class UpNextService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val profiles: ProfileService,
    private val fileDao: DownloadableFileDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val dir = File(context.filesDir, "upnext")

    private data class Loaded(val profileId: String, val list: List<UpNextEntry>)
    @Volatile private var loaded: Loaded? = null

    private val _entries = MutableStateFlow<List<UpNextEntry>?>(null)
    /** The active profile's list as shown (open games first); null until it was read. */
    val entries: StateFlow<List<UpNextEntry>?> = _entries.asStateFlow()

    private val _keys = MutableStateFlow<Set<String>>(emptySet())
    /** `console|file` of every game on the active profile's list, finished ones included. */
    val keys: StateFlow<Set<String>> = _keys.asStateFlow()

    init {
        scope.launch { profiles.activeId.collect { id -> lock.withLock { ensure(id) } } }
        scope.launch {
            // A deleted profile takes its list with it. Only ids seen and then gone count, never a first read.
            var before: Set<String>? = null
            profiles.profiles.collect { list ->
                val now = list.mapTo(HashSet()) { it.id }
                before?.minus(now)?.forEach { gone -> lock.withLock { forget(gone) } }
                before = now
            }
        }
    }

    private fun file(profileId: String) = AtomicFile(File(dir, UpNext.fileNameFor(profileId)))

    /** Must hold [lock]. Reads [profileId]'s list unless it is the one in memory. */
    private fun ensure(profileId: String): List<UpNextEntry> {
        loaded?.takeIf { it.profileId == profileId }?.let { return it.list }
        val f = file(profileId)
        val list = if (!f.baseFile.exists()) emptyList() else runCatching { UpNext.fromJson(f.readFully().toString(Charsets.UTF_8)) }
            .onFailure { Log.w(TAG, "List unreadable: ${it.javaClass.simpleName}") }
            .getOrDefault(emptyList())
        publish(Loaded(profileId, list))
        return list
    }

    private fun publish(state: Loaded) {
        loaded = state
        _entries.value = state.list
        _keys.value = state.list.mapTo(HashSet()) { it.key }
    }

    private fun forget(profileId: String) {
        if (profileId.isEmpty()) return
        runCatching { file(profileId).delete() }
        if (loaded?.profileId == profileId) loaded = null
    }

    /** Applies [change] to the active profile's list and saves it; false when it could not be saved (the list then stays as it was). */
    private suspend fun edit(change: (List<UpNextEntry>) -> List<UpNextEntry>): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val profileId = profiles.currentIdNow()
            val before = ensure(profileId)
            val after = change(before)
            if (after == before) return@withLock true
            val f = file(profileId)
            try {
                dir.mkdirs()
                val stream = f.startWrite()
                try { stream.write(UpNext.toJson(after).toByteArray(Charsets.UTF_8)); f.finishWrite(stream) }
                catch (e: Exception) { f.failWrite(stream); throw e }
                publish(Loaded(profileId, UpNext.normalized(after)))
                true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.w(TAG, "List not saved: ${e.javaClass.simpleName}")
                false
            }
        }
    }

    /** 8.0: the active profile's list and its name, for the device sync; null before it was read. */
    suspend fun forSync(): Pair<String, List<UpNextEntry>> = withContext(Dispatchers.IO) {
        lock.withLock {
            val id = profiles.currentIdNow()
            val name = profiles.profiles.value.firstOrNull { it.id == id }?.name.orEmpty()
            name to ensure(id)
        }
    }

    /** 8.0: replaces the active profile's list by what a sync merged (saved like any other change). */
    suspend fun replaceFromSync(merged: List<UpNextEntry>): Boolean = edit { merged }

    /** Puts a game on the list (after the open ones); true when it is on the list afterwards. */
    suspend fun add(consoleId: String, fileName: String, title: String): Boolean {
        val ok = edit { UpNext.add(it, UpNextEntry(consoleId, fileName, title.ifBlank { fileName.substringBeforeLast('.') }, System.currentTimeMillis())) }
        return ok && UpNext.key(consoleId, fileName) in _keys.value
    }

    /** [add] for a library row; its readable name is the title. */
    suspend fun add(item: DownloadableFileWithTags): Boolean =
        add(item.file.consoleId, item.file.fileName, item.file.name.substringBeforeLast('.').ifBlank { item.file.name })

    suspend fun remove(key: String): Boolean = edit { UpNext.remove(it, key) }
    suspend fun move(key: String, delta: Int): Boolean = edit { UpNext.move(it, key, delta) }
    suspend fun setState(key: String, state: UpNextState): Boolean = edit { UpNext.setState(it, key, state, System.currentTimeMillis()) }
    suspend fun clearFinished(): Boolean = edit { UpNext.clearFinished(it) }

    /**
     * Adds a game the caller only knows by console and file name (the game page's pill): its title
     * comes from the library row when there is one.
     */
    suspend fun addByName(consoleId: String, fileName: String): Boolean {
        val row = withContext(Dispatchers.IO) {
            runCatching { fileDao.filesByFileNames(listOf(fileName)).firstOrNull { it.consoleId == consoleId } }
                .onFailure { if (it is CancellationException) throw it }.getOrNull()
        }
        return add(consoleId, fileName, row?.name?.substringBeforeLast('.').orEmpty())
    }

    suspend fun toggle(consoleId: String, fileName: String): Boolean {
        val key = UpNext.key(consoleId, fileName)
        return if (key in _keys.value) { remove(key); false } else addByName(consoleId, fileName)
    }
}
