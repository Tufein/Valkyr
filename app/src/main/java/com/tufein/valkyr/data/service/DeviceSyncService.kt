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
import com.tufein.valkyr.data.local.dao.CollectionDao
import com.tufein.valkyr.data.local.dao.FavouriteDao
import com.tufein.valkyr.data.local.dao.WishlistDao
import com.tufein.valkyr.data.local.entity.CollectionEntity
import com.tufein.valkyr.data.local.entity.CollectionItemEntity
import com.tufein.valkyr.data.local.entity.FavouriteEntity
import com.tufein.valkyr.data.local.entity.WishlistEntity
import com.tufein.valkyr.util.CloudErrors
import com.tufein.valkyr.util.CloudNotConfiguredException
import com.tufein.valkyr.util.DeviceSyncEngine
import com.tufein.valkyr.util.DeviceSyncJson
import com.tufein.valkyr.util.DeviceSyncMerge
import com.tufein.valkyr.util.SyncCollection
import com.tufein.valkyr.util.SyncLibrary
import com.tufein.valkyr.util.SyncWish
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import com.tufein.valkyr.util.ActionKind
import com.tufein.valkyr.util.ActionTopic

/**
 * Device sync: favourites, wishlist and collections kept in step between handhelds through
 * `<folder>/sync/library.json` on the user's WebDAV server ([DeviceSyncEngine] does the merge and
 * the careful write). This class is the device's side: it reads the library from the database,
 * applies the merge result in one transaction and keeps the base of the last sync in
 * `files/device_sync_base.json`.
 *
 * When it runs: [syncNow] (the user's "Sync now"), at app start ([Trigger.START], at most every
 * [START_INTERVAL_MS]), a short while after local changes ([Trigger.LOCAL_CHANGE], only sends when
 * something changed here), when the app goes to the background, and from the daily cloud job.
 * Nothing runs unless device sync is switched on and a server is set.
 */
@Singleton
class DeviceSyncService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: CloudSettings,
    private val connection: CloudConnection,
    private val database: DogmatixDatabase,
    private val favouriteDao: FavouriteDao,
    private val wishlistDao: WishlistDao,
    private val collectionDao: CollectionDao,
    private val history: OperationHistoryService,
    private val actionLog: ActionLogService
) {
    /** Why a background sync was asked for. */
    enum class Trigger { START, LOCAL_CHANGE, BACKGROUND, SCHEDULED }

    /** The outcome of one sync, for the screen. */
    sealed class Result {
        data class Synced(val added: Int, val removed: Int, val sent: Boolean) : Result()
        /** Nothing changed: [removals] things would go at once; [syncNow] with `allowMassRemoval` after the user agrees. */
        data class HeldBack(val removals: Int) : Result()
        /** Nothing to do (switched off, nothing changed here, or already running). */
        object Skipped : Result()
        /** [error] is turned into a message by the screen (`CloudMessages.of(context, error)`). */
        data class Failed(val error: Throwable) : Result()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val baseFile = AtomicFile(File(context.filesDir, BASE_FILE))

    private val _running = MutableStateFlow(false)
    /** A sync is talking to the server right now. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    @Volatile private var lastStartSync = 0L
    private var startedActivities = 0

    init {
        watchLocalChanges()
        watchForeground()
    }

    /**
     * The user's "Sync now": waits for a running sync, then syncs whatever changed on either side.
     * [allowMassRemoval] confirms a sync that was held back. Never throws; failures come back as
     * [Result.Failed] and are recorded for the screen.
     */
    suspend fun syncNow(allowMassRemoval: Boolean = false): Result = withContext(Dispatchers.IO) {
        if (!settings.configured.first()) return@withContext Result.Failed(CloudNotConfiguredException())
        lock.withLock { runSync(allowMassRemoval, onlyIfLocalChanges = false, quietTransient = false) }
    }

    /** A background sync for [trigger]; skipped when one is running, device sync is off, or (start) one ran lately. */
    fun requestSync(trigger: Trigger) {
        scope.launch { runInBackground(trigger) }
    }

    /** For the daily cloud job: runs a full sync when device sync is on. */
    suspend fun syncScheduled(): Result = runInBackground(Trigger.SCHEDULED)

    private suspend fun runInBackground(trigger: Trigger): Result {
        if (!settings.deviceSyncActive.first()) return Result.Skipped
        if (trigger == Trigger.START) {
            val now = System.currentTimeMillis()
            val last = maxOf(lastStartSync, settings.records.first().lastSyncAt)
            if (now - last in 0 until START_INTERVAL_MS) return Result.Skipped
            lastStartSync = now
        }
        if (!lock.tryLock()) return Result.Skipped
        return try {
            val onlyLocal = trigger == Trigger.LOCAL_CHANGE || trigger == Trigger.BACKGROUND
            runSync(allowMassRemoval = false, onlyIfLocalChanges = onlyLocal, quietTransient = true)
        } finally {
            lock.unlock()
        }
    }

    /** Must hold [lock]. */
    private suspend fun runSync(allowMassRemoval: Boolean, onlyIfLocalChanges: Boolean, quietTransient: Boolean): Result {
        _running.value = true
        val now = System.currentTimeMillis()
        return try {
            val session = connection.open()
            val engine = DeviceSyncEngine(
                session.store, session.serverUrl, session.rootUrl, local,
                account = DeviceSyncEngine.accountKey(settings.user.first())
            )
            val device = DeviceSyncEngine.Device(settings.deviceId(), settings.deviceName.first())
            when (val outcome = engine.sync(device, allowMassRemoval, onlyIfLocalChanges)) {
                is DeviceSyncEngine.Outcome.Synced -> {
                    settings.recordSync(System.currentTimeMillis(), outcome.added, outcome.removed, outcome.sent)
                    Log.i(TAG, "Device sync: +${outcome.added} -${outcome.removed}${if (outcome.sent) ", sent" else ""}")
                    history.event("device_sync", "${outcome.added} / ${outcome.removed}")
                    actionLog.deviceSync(outcome.added, outcome.removed)
                    Result.Synced(outcome.added, outcome.removed, outcome.sent)
                }
                is DeviceSyncEngine.Outcome.HeldBack -> {
                    settings.recordSyncHeldBack(outcome.removals)
                    Log.i(TAG, "Device sync held back: ${outcome.removals} removals")
                    history.event("device_sync", outcome.removals.toString(), "conflict")
                    actionLog.deviceSyncHeld(outcome.removals)
                    Result.HeldBack(outcome.removals)
                }
                DeviceSyncEngine.Outcome.NothingToSend -> Result.Skipped
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Device sync failed: ${e.javaClass.simpleName}")
            // Offline at app start is not worth a red mark on the screen; a "Sync now" is.
            if (!(quietTransient && CloudErrors.isTransient(e))) {
                settings.recordSyncError(now, CloudErrors.encode(e))
                connection.noteFailure(e)
                actionLog.cloudFailed(ActionKind.SYNC_FAILED, ActionTopic.DEVICE_SYNC, e)
            }
            Result.Failed(e)
        } finally {
            _running.value = false
        }
    }

    /**
     * Forgets the base of the last sync: the next sync is then a union (it adds what is missing on
     * either side and removes nothing). For a change of server or folder this happens by itself.
     */
    suspend fun resetBase() = withContext(Dispatchers.IO) { lock.withLock { runCatching { baseFile.delete() } } }

    // ---- The device's side ---------------------------------------------------------------------

    private val local = object : DeviceSyncEngine.Local {
        override suspend fun snapshot(): SyncLibrary = readLibrary()
        override suspend fun apply(diff: DeviceSyncMerge.Diff) = applyDiff(diff)
        override suspend fun readBase(): DeviceSyncJson.Base? = withContext(Dispatchers.IO) {
            runCatching { DeviceSyncJson.readBase(baseFile.readFully().toString(Charsets.UTF_8)) }.getOrNull()
        }
        override suspend fun writeBase(base: DeviceSyncJson.Base) = withContext(Dispatchers.IO) { writeBaseFile(base) }
    }

    /** The synced part of the library as it is in the database now. */
    private suspend fun readLibrary(): SyncLibrary {
        val favourites = HashMap<String, Long>()
        favouriteDao.getAll().forEach { f ->
            val key = DeviceSyncMerge.itemKey(f.consoleId, f.fileName)
            favourites[key] = minOf(f.addedAt, favourites[key] ?: Long.MAX_VALUE)
        }
        // The same wish twice (title typed twice) is one wish: the older time counts.
        val wishes = HashMap<String, SyncWish>()
        wishlistDao.getAll().forEach { w ->
            val key = DeviceSyncMerge.wishKey(w.title, w.consoleId)
            val known = wishes[key]
            if (known == null || w.addedAt < known.addedAt) wishes[key] = SyncWish(w.title, w.consoleId, w.addedAt)
        }
        // Collections whose names differ only in case are one collection (as the app treats them).
        val itemsById = collectionDao.getAllItems().groupBy { it.collectionId }
        val collections = LinkedHashMap<String, SyncCollection>()
        collectionDao.getAll().forEach { c ->
            val key = DeviceSyncMerge.collectionKey(c.name)
            if (key.isEmpty()) return@forEach
            val items = itemsById[c.id].orEmpty().associate { DeviceSyncMerge.itemKey(it.consoleId, it.fileName) to it.addedAt }
            val known = collections[key]
            collections[key] = if (known == null) SyncCollection(c.name.trim(), c.createdAt, items)
            else known.copy(
                createdAt = minOf(known.createdAt, c.createdAt),
                items = (known.items.keys + items.keys).associateWith { k -> minOf(known.items[k] ?: Long.MAX_VALUE, items[k] ?: Long.MAX_VALUE) }
            )
        }
        return SyncLibrary(favourites, wishes, collections)
    }

    /**
     * Applies a merge result to the database in one transaction: all of it or (on any failure)
     * nothing, so a failed sync never leaves the library half-changed. Works on the rows as they
     * are inside the transaction, so a change the user made a moment ago is not undone.
     */
    private suspend fun applyDiff(diff: DeviceSyncMerge.Diff) {
        database.withTransaction {
            // Favourites
            if (diff.favouritesAdded.isNotEmpty()) {
                favouriteDao.upsertAll(diff.favouritesAdded.mapNotNull { (key, at) ->
                    DeviceSyncMerge.splitItemKey(key)?.let { (console, file) -> FavouriteEntity(console, file, at) }
                })
            }
            diff.favouritesRemoved.forEach { key ->
                DeviceSyncMerge.splitItemKey(key)?.let { (console, file) -> favouriteDao.delete(console, file) }
            }

            // Wishlist (the local id and "found" mark stay as they are)
            if (diff.wishesAdded.isNotEmpty() || diff.wishesRemoved.isNotEmpty()) {
                val current = wishlistDao.getAll()
                val have = current.map { DeviceSyncMerge.wishKey(it.title, it.consoleId) }.toSet()
                val fresh = diff.wishesAdded.filterKeys { it !in have }.values
                    .map { WishlistEntity(title = it.title, consoleId = it.consoleId, addedAt = it.addedAt) }
                if (fresh.isNotEmpty()) wishlistDao.upsertAll(fresh)
                current.filter { DeviceSyncMerge.wishKey(it.title, it.consoleId) in diff.wishesRemoved }
                    .forEach { wishlistDao.delete(it.id) }
            }

            // Collections
            val touched = diff.collectionsAdded.isNotEmpty() || diff.collectionsRemoved.isNotEmpty() ||
                diff.itemsAdded.isNotEmpty() || diff.itemsRemoved.isNotEmpty()
            if (touched) {
                val byKey = collectionDao.getAll().groupBy { DeviceSyncMerge.collectionKey(it.name) }
                diff.collectionsRemoved.forEach { key -> byKey[key].orEmpty().forEach { collectionDao.delete(it.id) } }
                diff.collectionsAdded.forEach { (key, c) ->
                    val id = byKey[key]?.minByOrNull { it.id }?.id
                        ?: collectionDao.insert(CollectionEntity(name = c.name.trim().take(MAX_COLLECTION_NAME), createdAt = c.createdAt))
                    collectionDao.addItems(itemsOf(id, c.items))
                }
                diff.itemsRemoved.forEach { (key, items) ->
                    val targets = byKey[key].orEmpty()
                    items.forEach { item ->
                        DeviceSyncMerge.splitItemKey(item)?.let { (console, file) -> targets.forEach { collectionDao.removeItem(it.id, console, file) } }
                    }
                }
                diff.itemsAdded.forEach { (key, items) ->
                    val target = byKey[key]?.minByOrNull { it.id } ?: return@forEach
                    collectionDao.addItems(itemsOf(target.id, items))
                }
            }
        }
    }

    private fun itemsOf(collectionId: Long, items: Map<String, Long>): List<CollectionItemEntity> =
        items.mapNotNull { (key, at) -> DeviceSyncMerge.splitItemKey(key)?.let { (console, file) -> CollectionItemEntity(collectionId, console, file, at) } }

    /** Written through a temporary file and renamed, so a crash half-way keeps the previous base. */
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

    // ---- Triggers --------------------------------------------------------------------------------

    /** A short while after favourites, wishlist or collections change here, send the change. */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private fun watchLocalChanges() {
        val changes = combine(favouriteDao.observeAll(), wishlistDao.observeAll(), collectionDao.observeAll()) { _, _, _ -> Unit }
        scope.launch {
            settings.deviceSyncActive
                .flatMapLatest { on -> if (on) changes.drop(1) else emptyFlow() }
                .debounce(LOCAL_CHANGE_DELAY_MS)
                .collect { runCatching { runInBackground(Trigger.LOCAL_CHANGE) } }
        }
    }

    /**
     * App start (the first activity starts): sync, at most every [START_INTERVAL_MS]. Going to
     * the background (the last one stops): send what changed here right away.
     */
    private fun watchForeground() {
        val app = context as? Application ?: return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (startedActivities++ == 0) requestSync(Trigger.START)
            }
            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0 && !activity.isChangingConfigurations) requestSync(Trigger.BACKGROUND)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    companion object {
        private const val TAG = "DeviceSync"
        const val BASE_FILE = "device_sync_base.json"
        /** App starts closer together than this do not sync again. */
        const val START_INTERVAL_MS = 10L * 60_000
        /** Quiet time after the last local change before it is sent. */
        const val LOCAL_CHANGE_DELAY_MS = 15_000L
        private const val MAX_COLLECTION_NAME = 60
    }
}
