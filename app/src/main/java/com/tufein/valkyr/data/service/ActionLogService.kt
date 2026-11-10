package com.tufein.valkyr.data.service

import android.content.Context
import android.util.Log
import com.tufein.valkyr.util.ActionCount
import com.tufein.valkyr.util.ActionEntry
import com.tufein.valkyr.util.ActionKind
import com.tufein.valkyr.util.ActionLogFile
import com.tufein.valkyr.util.ActionLogFormat
import com.tufein.valkyr.util.ActionReason
import com.tufein.valkyr.util.ActionTopic
import com.tufein.valkyr.util.CloudErrors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import com.tufein.valkyr.data.local.AppSettings

private const val TAG = "ActionLog"

/**
 * The action history (2.4.0): what the app did for the user, one line each in
 * `files/action_log.jsonl` (see [ActionEntry]). It only tells; the recovery journal
 * ([OperationHistoryService]) stays the source of truth for files, and a removal line points at
 * its operation so Restore goes through [TrashService].
 *
 * Every feature calls [record] (or a helper below); it never blocks and never throws. One
 * coroutine is the only writer: lines reach the file in the order they were recorded, with times
 * that never go backwards, and a rewrite (trim, clear, undo mark) is atomic ([ActionLogFile]).
 */
@Singleton
class ActionLogService @Inject constructor(
    @param:ApplicationContext context: Context,
    private val settings: AppSettings? = null
) {
    private sealed interface Op {
        class Append(val entry: ActionEntry, val profileId: String?) : Op
        class MarkUndone(val id: String, val done: CompletableDeferred<Unit>) : Op
        class Clear(val profileId: String, val done: CompletableDeferred<Unit>) : Op
        class FindRemoval(val id: String, val done: CompletableDeferred<ActionEntry?>) : Op
    }

    private val store = ActionLogFile(File(context.filesDir, "action_log.jsonl"))
    private val ops = Channel<Op>(Channel.UNLIMITED)
    private val sendLock = Any()
    private var lastAt = 0L

    private val _entries = MutableStateFlow<List<ActionEntry>?>(null)

    /** Every line, oldest first; null until the file was read. */
    val entries: StateFlow<List<ActionEntry>?> = _entries.asStateFlow()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val now = System.currentTimeMillis()
            var all = runCatching { store.load() }.onFailure { Log.w(TAG, "History unreadable: ${it.javaClass.simpleName}") }.getOrDefault(emptyList())
            val trimmed = ActionLogFormat.trim(all, now)
            if (trimmed.size != all.size || store.lines > ActionLogFormat.COMPACT_AT) {
                all = trimmed
                runCatching { store.rewrite(all) }
            }
            _entries.value = all
            // A clock set back since the last run must not put new lines before the old ones.
            all.maxOfOrNull { it.at }?.let { newest -> synchronized(sendLock) { lastAt = maxOf(lastAt, newest) } }
            for (op in ops) {
                when (op) {
                    is Op.Append -> {
                        // Also keep ordering after a restart while the device clock moved back. Here the log is
                        // loaded for sure: a restore or purge recorded at app start still gets its game.
                        val entry = ActionLogFormat.withRemovalInfo(op.entry, all).copy(at = maxOf(op.entry.at, all.lastOrNull()?.at ?: 0L),
                            profileId = op.profileId ?: settings?.activeProfile?.first() ?: op.entry.profileId)
                        all = all + entry
                        runCatching { store.append(entry) }.onFailure { Log.w(TAG, "Could not write history: ${it.javaClass.simpleName}") }
                        if (store.lines > ActionLogFormat.COMPACT_AT || all.size > ActionLogFormat.COMPACT_AT) {
                            all = ActionLogFormat.trim(all, System.currentTimeMillis())
                            runCatching { store.rewrite(all) }
                        }
                    }
                    is Op.MarkUndone -> {
                        val next = all.map { if (it.id == op.id) it.copy(undone = true) else it }
                        try {
                            store.rewrite(next)
                            all = next
                            op.done.complete(Unit)
                        } catch (e: Exception) { op.done.completeExceptionally(e) }
                    }
                    is Op.Clear -> {
                        // An explicit privacy action only succeeds once the on-disk history is gone.
                        try {
                            val next = all.filter { it.profileId != op.profileId }
                            store.rewrite(next)
                            all = next
                            op.done.complete(Unit)
                        } catch (e: Exception) { op.done.completeExceptionally(e) }
                    }
                    is Op.FindRemoval -> op.done.complete(all.lastOrNull { it.opId == op.id && it.kind == ActionKind.REMOVED })
                }
                _entries.value = all
            }
        }
    }

    fun newId(): String = UUID.randomUUID().toString().take(13)

    /** Notes one action. Fire and forget; [ActionEntry.id] and [ActionEntry.at] are set here. */
    fun record(
        kind: ActionKind,
        title: String = "",
        topic: String? = null,
        consoleId: String? = null,
        fileName: String? = null,
        opId: String? = null,
        reason: String? = null,
        counts: Map<String, Int> = emptyMap(),
        count: Int = 0,
        bytes: Long = 0L
    ) {
        runCatching {
            synchronized(sendLock) {
                // Never earlier than the line before: the file's order is the time order.
                lastAt = maxOf(System.currentTimeMillis(), lastAt)
                val entry = ActionEntry(
                    newId(), lastAt, kind, title.take(300), topic, consoleId?.take(64), fileName?.take(300), opId, reason?.take(80),
                    counts.filterValues { it > 0 }, count, bytes.coerceAtLeast(0L), profileId = settings?.activeProfileSnapshot.orEmpty()
                )
                ops.trySend(Op.Append(entry, settings?.activeProfileSnapshot))
            }
        }
    }

    /** Writes an entry built elsewhere (a download summary), with a fresh time. */
    fun record(entry: ActionEntry) = record(
        entry.kind, entry.title, entry.topic, entry.consoleId, entry.fileName, entry.opId, entry.reason, entry.counts, entry.count, entry.bytes
    )

    /** The way back of line [id] was used: the line stays, its button goes. */
    suspend fun markUndone(id: String) {
        val done = CompletableDeferred<Unit>()
        ops.trySend(Op.MarkUndone(id, done)).getOrThrow()
        done.await()
    }

    /** Empties the history. Games, the trash and the recovery journal are not touched. */
    suspend fun clear() {
        val done = CompletableDeferred<Unit>()
        ops.trySend(Op.Clear(settings?.activeProfile?.first() ?: "", done)).getOrThrow()
        done.await()
    }

    /** Waits for the initial read and previously queued writes, including a just-completed removal. */
    suspend fun removal(opId: String): ActionEntry? {
        val done = CompletableDeferred<ActionEntry?>()
        ops.trySend(Op.FindRemoval(opId, done)).getOrThrow()
        return done.await()
    }

    // ---- Helpers for the features -------------------------------------------------------------

    /** A sync of saves with RomM ([SaveSyncService]). */
    fun saveSync(uploaded: Int, downloaded: Int, conflicts: Int, failed: Int, deletedOnDevice: Int, deletedOnServer: Int) {
        val counts = mapOf(
            ActionCount.UPLOADED to uploaded, ActionCount.DOWNLOADED to downloaded, ActionCount.DELETED_DEVICE to deletedOnDevice,
            ActionCount.DELETED_SERVER to deletedOnServer, ActionCount.CONFLICTS to conflicts, ActionCount.FAILED to failed
        )
        // A run that changed nothing is not news (the background job runs every few hours).
        if (counts.values.all { it == 0 }) return
        record(if (failed > 0 && uploaded + downloaded == 0) ActionKind.SYNC_FAILED else ActionKind.SYNCED, topic = ActionTopic.SAVE_SYNC, counts = counts)
    }

    fun saveSyncFailed() = record(ActionKind.SYNC_FAILED, topic = ActionTopic.SAVE_SYNC)

    /** A device sync over WebDAV ([DeviceSyncService]); a run that only sent this device's own change is not written. */
    fun deviceSync(added: Int, removed: Int) {
        if (added + removed == 0) return
        record(ActionKind.SYNCED, topic = ActionTopic.DEVICE_SYNC, counts = mapOf(ActionCount.ADDED to added, ActionCount.REMOVED to removed))
    }

    fun deviceSyncHeld(removals: Int) =
        record(ActionKind.SYNC_FAILED, topic = ActionTopic.DEVICE_SYNC, reason = ActionReason.HELD_BACK, counts = mapOf(ActionCount.HELD to removals))

    /** A failed cloud call: only the error's code is kept, never its detail (a server address). */
    fun cloudFailed(kind: ActionKind, topic: String, error: Throwable) =
        record(kind, topic = topic, reason = ActionReason.cloud(runCatching { CloudErrors.encode(error) }.getOrDefault("")))

    /**
     * For [VersionPreferenceService.set] (wired by the lead): a version of a game pinned, or the pin
     * removed. [name] is the key the pin is stored under (the game's file name).
     */
    fun versionPin(consoleId: String, name: String, fileName: String?) {
        val shown = fileName ?: name
        record(if (fileName != null) ActionKind.PINNED else ActionKind.UNPINNED, title = shown.substringBeforeLast('.'), consoleId = consoleId, fileName = shown)
    }

    /** For [GameLaunchService.launch] (wired by the lead): a game opened in an emulator. */
    fun played(consoleId: String, name: String) =
        record(ActionKind.PLAYED, title = name.substringBeforeLast('.'), consoleId = consoleId, fileName = name)
}
