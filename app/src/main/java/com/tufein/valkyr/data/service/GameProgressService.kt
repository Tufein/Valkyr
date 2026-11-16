package com.tufein.valkyr.data.service

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.tufein.valkyr.util.PlayStatus
import com.tufein.valkyr.util.ProgressConflict
import com.tufein.valkyr.util.ProgressEntry
import com.tufein.valkyr.util.ProgressState
import com.tufein.valkyr.util.ProgressSync
import com.tufein.valkyr.util.UpNext
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GameProgress"

/**
 * 8.3: a game's own play status and star rating, per profile, on this device (`files/progress/`, one
 * small file per profile written atomically), and the bookkeeping the device sync needs: the state
 * both handhelds last agreed on and the conflicts waiting for a decision. No server is involved here;
 * [DeviceSyncService] moves the document.
 */
@Singleton
class GameProgressService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val profiles: ProfileService
) {
    private val lock = Mutex()
    private val dir = File(context.filesDir, "progress")
    private val _revision = MutableStateFlow(0L)

    /** Bumped after every saved change (also one that came from a sync). */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private fun file(profileId: String) = AtomicFile(File(dir, ProgressState().fileName(profileId)))

    /** Must hold [lock]. */
    private fun read(profileId: String): ProgressState {
        val f = file(profileId)
        if (!f.baseFile.exists()) return ProgressState()
        return runCatching { ProgressState.fromJson(f.readFully().toString(Charsets.UTF_8)) }
            .onFailure { Log.w(TAG, "Progress unreadable: ${it.javaClass.simpleName}") }.getOrDefault(ProgressState())
    }

    /** Must hold [lock]. False when it could not be saved. */
    private fun write(profileId: String, state: ProgressState): Boolean {
        val f = file(profileId)
        return try {
            dir.mkdirs()
            val stream = f.startWrite()
            try { stream.write(ProgressState.toJson(state).toByteArray(Charsets.UTF_8)); f.finishWrite(stream) }
            catch (e: Exception) { f.failWrite(stream); throw e }
            _revision.value++
            true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Log.w(TAG, "Progress not saved: ${e.javaClass.simpleName}"); false }
    }

    /** The active profile's entry for a game; null when nothing was ever set. */
    suspend fun entry(consoleId: String, fileName: String): ProgressEntry? = withContext(Dispatchers.IO) {
        lock.withLock { read(profiles.currentIdNow()).entries[UpNext.key(consoleId, fileName)] }
    }

    /** Sets the active profile's status and rating (0 = none) for a game; false when it could not be saved. */
    suspend fun set(consoleId: String, fileName: String, status: PlayStatus?, rating: Int): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val id = profiles.currentIdNow()
            write(id, read(id).with(UpNext.key(consoleId, fileName), status, rating, System.currentTimeMillis()))
        }
    }

    /** The active profile's conflicts, newest list first; changes with every saved change and profile switch. */
    val conflicts: Flow<List<ProgressConflict>> = combine(_revision, profiles.activeId) { _, id -> id }
        .map { id -> withContext(Dispatchers.IO) { lock.withLock { read(id.ifEmpty { profiles.currentIdNow() }).conflicts } } }
        .distinctUntilChanged()

    /** Decides one conflict: the chosen value becomes a new change that travels to the other handheld at the next sync. */
    suspend fun resolve(key: String, keepMine: Boolean): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val id = profiles.currentIdNow()
            val state = read(id)
            val conflict = state.conflicts.firstOrNull { it.key == key } ?: return@withLock false
            write(id, state.copy(entries = ProgressSync.resolve(state.entries, conflict, keepMine, System.currentTimeMillis()), conflicts = state.conflicts - conflict))
        }
    }

    /** For the device sync: the active profile's id, its name (what both handhelds share) and its state. */
    suspend fun forSync(): Triple<String, String, ProgressState> = withContext(Dispatchers.IO) {
        lock.withLock {
            val id = profiles.currentIdNow()
            Triple(id, profiles.profiles.value.firstOrNull { it.id == id }?.name.orEmpty(), read(id))
        }
    }

    /**
     * Saves what a sync decided for [profileId]: [merged] replaces [mine], except for a game that was
     * changed here while the sync ran (that change stays and goes out next time). New conflicts are
     * added to the waiting ones; a game decided in the meantime is not listed twice. True when something changed here.
     */
    suspend fun commitSync(profileId: String, mine: Map<String, ProgressEntry>, merged: ProgressSync.Merged): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val now = read(profileId)
            val entries = LinkedHashMap(merged.entries)
            for ((k, v) in now.entries) if (mine[k] != v) entries[k] = v
            val fresh = merged.conflicts.filter { entries[it.key]?.sameValue(it.mine) == true || entries[it.key]?.sameValue(it.theirs) == true }
            val conflicts = now.conflicts.filter { old -> fresh.none { it.key == old.key } } + fresh
            val next = ProgressState(entries, merged.entries, conflicts.takeLast(ProgressState.MAX_CONFLICTS))
            if (next == now) false else write(profileId, next) && entries != now.entries
        }
    }
}
