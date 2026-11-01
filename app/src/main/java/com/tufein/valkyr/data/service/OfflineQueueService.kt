package com.tufein.valkyr.data.service

import android.content.Context
import android.util.Log
import com.tufein.valkyr.util.OfflineMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "OfflineQueue"

/**
 * User actions that need the network and were made without it (6.0): today the RomM play status and
 * rating writes. They are kept in a small file and sent when the connection is back, newest value per
 * field. Wishlist entries and the "mark" flags are local and never need to wait.
 */
@Singleton
class OfflineQueueService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val network: NetworkMonitor,
    private val rommClient: RommClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val file = File(context.filesDir, "offline_queue.json")

    private val _pending = MutableStateFlow(0)
    /** How many games have changes waiting. */
    val pending: StateFlow<Int> = _pending.asStateFlow()

    init {
        scope.launch { lock.withLock { _pending.value = read().size } }
        scope.launch { network.online.filter { it }.collect { flush() } }
        // A LAN-only network is never "validated": any network that claims internet access retries too.
        scope.launch { network.reachable.collect { flush() } }
    }

    private fun read(): List<OfflineMode.Pending> =
        runCatching { if (file.exists()) OfflineMode.decode(file.readText()) else emptyList() }.getOrDefault(emptyList())

    private fun write(list: List<OfflineMode.Pending>) {
        runCatching {
            if (list.isEmpty()) { file.delete(); return@runCatching }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(OfflineMode.encode(list))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }.onFailure { Log.w(TAG, "Could not save the waiting changes: ${it.javaClass.simpleName}") }
        _pending.value = list.size
    }

    /** Keeps a RomM play-status write until the server can be reached. */
    fun enqueueRommProps(romId: Int, changes: Map<String, Any?>) {
        if (changes.isEmpty()) return
        scope.launch {
            lock.withLock {
                write(OfflineMode.merge(read(), OfflineMode.Pending(OfflineMode.Pending.KIND_ROMM_PROPS, romId, changes, System.currentTimeMillis())))
            }
        }
    }

    /**
     * Runs [block] (an online write of [keys] for [romId]) under the queue lock and, when it succeeds, takes
     * those fields out of the waiting list so an older queued value cannot overwrite the newer one later.
     */
    suspend fun <T> writeThrough(romId: Int, keys: Set<String>, block: suspend () -> T): T = lock.withLock {
        val result = block()
        val before = read()
        val after = OfflineMode.discard(before, romId, keys)
        if (after != before) write(after)
        result
    }

    /** Sends what waits, in order. A run stops at the first failure that may pass (no network, 5xx, wrong token, certificate); only an answer that can never succeed drops that entry. */
    suspend fun flush() = lock.withLock {
        var list = read()
        if (list.isEmpty()) return@withLock
        for (p in list.toList()) {
            val done = try {
                rommClient.updateRomProps(p.romId, p.changes); true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val code = generateSequence<Throwable>(e) { it.cause }.take(6).firstNotNullOfOrNull { it as? JsonHttp.HttpException }?.code
                if (e is RommSwitchedOffException || !OfflineMode.dropsEntry(code)) { Log.i(TAG, "Kept the waiting changes: ${rommErrorKind(e)}"); break }
                Log.w(TAG, "Dropped a waiting change for ROM ${p.romId}: HTTP $code")
                true
            }
            if (done) list = list - p
        }
        write(list)
    }
}
