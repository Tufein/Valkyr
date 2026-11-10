package com.tufein.valkyr.data.service

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.AtomicFile
import android.util.Log
import androidx.room.withTransaction
import com.tufein.valkyr.data.local.CloudSettings
import com.tufein.valkyr.data.local.DogmatixDatabase
import com.tufein.valkyr.data.local.dao.WishlistDao
import com.tufein.valkyr.data.local.entity.WishlistEntity
import com.tufein.valkyr.util.CloudErrors
import com.tufein.valkyr.util.CloudNotConfiguredException
import com.tufein.valkyr.util.DeviceSyncJson
import com.tufein.valkyr.util.DeviceSyncMerge
import com.tufein.valkyr.util.SharedMeta
import com.tufein.valkyr.util.SharedWish
import com.tufein.valkyr.util.SharedWishlistEngine
import com.tufein.valkyr.util.SharedWishlistJson
import com.tufein.valkyr.util.SharedWishlistMerge
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import com.tufein.valkyr.util.DeviceSyncEngine

/**
 * 6.0 shared family wishlist: several people, each with their own app, keep one wish list in
 * `<folder>/shared/<list name>/wishlist.json` on the same WebDAV server ([SharedWishlistEngine] does
 * the merge and the careful write). This class is the device's side: it reads the wishes from the
 * database, adds and removes the ones the merge says (in one transaction, the Room schema is
 * untouched), keeps the base of the last sync in `files/shared_wishlist_base.json` and who added /
 * found which wish in `files/shared_wishlist_meta.json`.
 *
 * Runs when the user asks ([syncNow]), at app start (at most every [START_INTERVAL_MS]), a short
 * while after the local wishlist changes, and from the daily cloud job ([syncScheduled]); only while
 * a list name is set and a server is configured.
 */
@Singleton
class SharedWishlistService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: CloudSettings,
    private val connection: CloudConnection,
    private val database: DogmatixDatabase,
    private val wishlistDao: WishlistDao
) {
    sealed class Result {
        data class Synced(val added: Int, val removed: Int, val sent: Boolean) : Result()
        data class HeldBack(val removals: Int) : Result()
        /** Nothing to do (switched off, or already running). */
        object Skipped : Result()
        data class Failed(val error: Throwable) : Result()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val baseFile = AtomicFile(File(context.filesDir, BASE_FILE))
    private val metaFile = AtomicFile(File(context.filesDir, META_FILE))
    private val meta = MutableStateFlow(SharedMeta.EMPTY)

    private val _running = MutableStateFlow(false)
    /** A shared wishlist sync is talking to the server right now. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /**
     * Wish key ([DeviceSyncMerge.wishKey] of title and console) → name of the person who added it, for
     * wishes added by somebody else; the Wishlist screen shows it as a small pill.
     */
    val authors: Flow<Map<String, String>> = meta.map { it.authors }.distinctUntilChanged()

    /** Wish key → name of the person who found the game (the shared entry's `doneBy` note). */
    val doneBy: Flow<Map<String, String>> = meta.map { it.doneBy }.distinctUntilChanged()

    @Volatile private var lastStartSync = 0L
    private var startedActivities = 0

    init {
        scope.launch { meta.value = readMeta() }
        watchSettings()
        watchLocalChanges()
        watchForeground()
    }

    /** The user's "Sync now": waits for a running sync; removals are applied (the user asked). Never throws. */
    suspend fun syncNow(): Result = withContext(Dispatchers.IO) {
        if (!settings.configured.first()) return@withContext Result.Failed(CloudNotConfiguredException())
        if (SharedWishlistEngine.folderName(settings.sharedList.first()) == null) return@withContext Result.Skipped
        lock.withLock { runSync(allowMassRemoval = true, quietTransient = false) }
    }

    /** For the daily cloud job. */
    suspend fun syncScheduled(): Result = runInBackground(start = false)

    private suspend fun runInBackground(start: Boolean): Result {
        if (!settings.sharedActive.first()) return Result.Skipped
        if (start) {
            val now = System.currentTimeMillis()
            val last = maxOf(lastStartSync, settings.records.first().lastSharedAt)
            if (now - last in 0 until START_INTERVAL_MS) return Result.Skipped
            lastStartSync = now
        }
        if (!lock.tryLock()) return Result.Skipped
        return try {
            runSync(allowMassRemoval = false, quietTransient = true)
        } finally {
            lock.unlock()
        }
    }

    /** Must hold [lock]. */
    private suspend fun runSync(allowMassRemoval: Boolean, quietTransient: Boolean): Result {
        _running.value = true
        val now = System.currentTimeMillis()
        return try {
            val session = connection.open()
            val me = myName()
            val engine = SharedWishlistEngine(
                session.store, session.serverUrl, session.rootUrl, settings.sharedList.first(), local(me),
                account = DeviceSyncEngine.accountKey(settings.user.first())
            )
            when (val outcome = engine.sync(me, allowMassRemoval)) {
                is SharedWishlistEngine.Outcome.Synced -> {
                    settings.recordShared(System.currentTimeMillis(), outcome.added, outcome.removed, outcome.sent)
                    Log.i(TAG, "Shared wishlist: +${outcome.added} -${outcome.removed}${if (outcome.sent) ", sent" else ""}")
                    Result.Synced(outcome.added, outcome.removed, outcome.sent)
                }
                is SharedWishlistEngine.Outcome.HeldBack -> {
                    Log.i(TAG, "Shared wishlist held back: ${outcome.removals} removals")
                    Result.HeldBack(outcome.removals)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Shared wishlist failed: ${e.javaClass.simpleName}")
            if (!(quietTransient && CloudErrors.isTransient(e))) {
                settings.recordSharedError(now, CloudErrors.encode(e))
                connection.noteFailure(e)
            }
            Result.Failed(e)
        } finally {
            _running.value = false
        }
    }

    /** Forgets the base of the last sync: the next sync is a union (nothing is removed). */
    suspend fun resetBase() = withContext(Dispatchers.IO) { lock.withLock { runCatching { baseFile.delete() } } }

    /** The name this person goes by: the one set for the list, else this device's name. */
    private suspend fun myName(): String =
        settings.sharedName.first().trim().ifEmpty { settings.deviceName.first().trim() }.ifEmpty { "?" }
            .take(SharedWishlistJson.MAX_NAME)

    // ---- The device's side ---------------------------------------------------------------------

    private fun local(me: String) = object : SharedWishlistEngine.Local {
        override suspend fun snapshot(): Map<String, SharedWish> = readWishes(me)
        override suspend fun apply(added: Collection<SharedWish>, removed: Set<String>, merged: Map<String, SharedWish>) {
            applyChanges(added, removed)
            saveMeta(SharedWishlistMerge.metaOf(merged, me))
        }
        override suspend fun readBase(): DeviceSyncJson.Base? = withContext(Dispatchers.IO) {
            runCatching { DeviceSyncJson.readBase(baseFile.readFully().toString(Charsets.UTF_8)) }.getOrNull()
        }
        override suspend fun writeBase(base: DeviceSyncJson.Base) = withContext(Dispatchers.IO) { writeBaseFile(base) }
    }

    /** The wishes in the database: added by [me] unless the last sync knew another author; found = [me]. */
    private suspend fun readWishes(me: String): Map<String, SharedWish> {
        val authors = meta.value.authors
        val out = HashMap<String, SharedWish>()
        wishlistDao.getAll().forEach { w ->
            val key = DeviceSyncMerge.wishKey(w.title, w.consoleId)
            val wish = SharedWish(
                title = w.title, consoleId = w.consoleId, addedAt = w.addedAt,
                addedBy = authors[key] ?: me,
                doneBy = if (w.notifiedAt != null) me else "",
                doneAt = w.notifiedAt ?: 0L
            )
            val known = out[key]
            // The same wish twice (title typed twice) is one wish: the older time counts.
            if (known == null || wish.addedAt < known.addedAt) out[key] = wish
        }
        return out
    }

    /** All or nothing; works on the rows as they are inside the transaction. */
    private suspend fun applyChanges(added: Collection<SharedWish>, removed: Set<String>) {
        if (added.isEmpty() && removed.isEmpty()) return
        database.withTransaction {
            val current = wishlistDao.getAll()
            val have = current.map { DeviceSyncMerge.wishKey(it.title, it.consoleId) }.toSet()
            val fresh = added.filter { it.key !in have }.map { WishlistEntity(title = it.title, consoleId = it.consoleId, addedAt = it.addedAt) }
            if (fresh.isNotEmpty()) wishlistDao.upsertAll(fresh)
            current.filter { DeviceSyncMerge.wishKey(it.title, it.consoleId) in removed }.forEach { wishlistDao.delete(it.id) }
        }
    }

    private fun writeBaseFile(base: DeviceSyncJson.Base) {
        val out = baseFile.startWrite()
        try {
            out.write(DeviceSyncJson.writeBase(base).toByteArray(Charsets.UTF_8))
            baseFile.finishWrite(out)
        } catch (e: Exception) {
            baseFile.failWrite(out)
            throw e
        }
    }

    private fun readMeta(): SharedMeta =
        runCatching { SharedWishlistJson.readMeta(metaFile.readFully().toString(Charsets.UTF_8)) }.getOrDefault(SharedMeta.EMPTY)

    private suspend fun saveMeta(value: SharedMeta) = withContext(Dispatchers.IO) {
        val out = metaFile.startWrite()
        try {
            out.write(SharedWishlistJson.writeMeta(value).toByteArray(Charsets.UTF_8))
            metaFile.finishWrite(out)
        } catch (e: Exception) {
            metaFile.failWrite(out)
            throw e
        }
        meta.value = value
    }

    // ---- Triggers ------------------------------------------------------------------------------

    /** Switched off (or another list): what was learnt about the old list's people goes. */
    private fun watchSettings() {
        scope.launch {
            settings.sharedList.map { SharedWishlistEngine.folderName(it) }.distinctUntilChanged().drop(1).collect {
                runCatching { saveMeta(SharedMeta.EMPTY) }
            }
        }
    }

    /** A short while after the wishlist changes here, send the change. */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private fun watchLocalChanges() {
        scope.launch {
            settings.sharedActive
                .flatMapLatest { on -> if (on) wishlistDao.observeAll().drop(1) else emptyFlow() }
                .debounce(LOCAL_CHANGE_DELAY_MS)
                .collect { runCatching { runInBackground(start = false) } }
        }
    }

    /** App start: sync, at most every [START_INTERVAL_MS]. */
    private fun watchForeground() {
        val app = context as? Application ?: return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (startedActivities++ == 0) scope.launch { runCatching { runInBackground(start = true) } }
            }
            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    companion object {
        private const val TAG = "SharedWishlist"
        const val BASE_FILE = "shared_wishlist_base.json"
        const val META_FILE = "shared_wishlist_meta.json"
        const val START_INTERVAL_MS = 10L * 60_000
        const val LOCAL_CHANGE_DELAY_MS = 15_000L
    }
}
