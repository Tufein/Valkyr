package com.tufein.valkyr.util

import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Both sides changed since the last sync; the user picks which one stays. */
data class SaveConflict(val local: LocalSaveFile, val remote: RemoteSaveFile)

/** One choice for a whole batch of conflicts ([SaveSyncEngine.resolveAll]). */
enum class BatchChoice { DEVICE, SERVER, NEWER, KEEP_BOTH }

/** What a batch did: the conflicts settled, the ones left (failed, or no newer side known) and the first few errors. */
data class BatchOutcome(val resolved: List<SaveConflict>, val skipped: List<SaveConflict>, val errors: List<String>)

data class SaveSyncResult(
    val finishedAt: Long,
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val unchanged: Int = 0,
    val conflicts: Int = 0,
    /** Device files RomM has no game for (or several games, and none could be picked). */
    val notMatched: Int = 0,
    val failed: Int = 0,
    /** The first few failures, as "file: reason". */
    val errors: List<String> = emptyList(),
    /** Saves removed on the device / on the server because they were deleted on the other side. */
    val deletedOnDevice: Int = 0,
    val deletedOnServer: Int = 0,
    /** Deletions that were not carried out: there were more than expected, the user has to confirm. */
    val deletionsHeld: Int = 0,
    /** The first few device saves no single game was found for (memory cards, save folders…), as paths; they stay as they are. */
    val unmatched: List<String> = emptyList(),
    /** Changed saves left as they are because "never overwrite" is on (a device save not sent, a server save not applied). */
    val keptAsIs: Int = 0,
    /** Server versions set aside as safety copies on the device instead of replacing the device save. */
    val serverCopies: Int = 0
)

/** The RomM side of a save sync (implemented over [com.tufein.valkyr.data.service.RommClient]). */
interface SaveServer {
    suspend fun list(kind: SaveKind): List<RemoteSaveFile>
    suspend fun download(save: RemoteSaveFile): ByteArray
    /** Stores [bytes] as [fileName] for ROM [romId], replacing a save of that name; returns the stored save. */
    suspend fun upload(kind: SaveKind, romId: Int, fileName: String, emulator: String?, bytes: ByteArray): RemoteSaveFile?
    suspend fun searchRoms(term: String): List<SaveSyncPlanner.RomCandidate>
    /** Removes [save] from the server. */
    suspend fun delete(save: RemoteSaveFile)
}

/** The device side: the picked saves / states folders. */
interface SaveStore {
    data class Listing(
        val files: List<LocalSaveFile>,
        /** Folders directly inside each picked folder (emulator / core folders). */
        val topFolders: Map<SaveKind, Set<String>>,
        /** Files skipped for their size. */
        val tooLarge: Int = 0,
        /** Kinds listed only through emulators' own folders: server files of other emulators have no place here. */
        val noRootFolder: Set<SaveKind> = emptySet(),
        /** Emulator folders (labels) whose shared memory cards the user has not chosen to sync. */
        val holdSharedCards: Set<String> = emptySet(),
        /**
         * Sync paths (`Dolphin (standalone)/GCI-USA-A.gcifolder.zip`) of the Dolphin bundles this
         * device takes ([DolphinSaveBundles]): server bundles of any other path stay on the server.
         */
        val syncedBundles: Set<String> = emptySet(),
        /** Why parts of the device were left out (a bundle too large, a folder out of reach), shown with the result. */
        val problems: List<String> = emptyList()
    )

    /** Kinds with a picked folder are the keys of [Listing.topFolders]. */
    suspend fun list(): Listing
    suspend fun read(file: LocalSaveFile): ByteArray
    /** Writes [bytes] to [path] (creating its folder) and returns the file as now on disk. */
    suspend fun write(kind: SaveKind, path: String, bytes: ByteArray): LocalSaveFile
    /** Keeps a verified copy of [file] before replacement/deletion; must throw when the copy cannot be kept. */
    suspend fun backup(file: LocalSaveFile)
    /** Removes [file] from the device (the caller has made a backup). */
    suspend fun delete(file: LocalSaveFile)
    /**
     * Keeps a verified safety copy of [bytes] under [path]: a version about to be replaced somewhere
     * else (the server's save an upload replaces) or set aside instead of replacing the device file.
     * Must throw when the copy cannot be kept; a store that cannot keep copies must not pretend to.
     */
    suspend fun keepCopy(kind: SaveKind, path: String, bytes: ByteArray): Unit = throw IOException("This store cannot keep safety copies")
}

/**
 * Carries a save sync out: lists both sides, lets [SaveSyncPlanner] decide, uploads,
 * downloads and compares, and keeps [records] (what both sides looked like after the last
 * sync of each file) up to date. Pure Kotlin, so it is tested against fakes and a real server.
 */
class SaveSyncEngine(
    private val server: SaveServer,
    private val store: SaveStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    /** Whether deletions are mirrored (Settings → Save sync); read at the start of every sync. */
    private val syncDeletions: suspend () -> Boolean = { false },
    /** Prefer-newer-server and never-overwrite (Settings → Save sync); read at the start of every sync. */
    private val options: suspend () -> SaveSyncOptions = { SaveSyncOptions() }
) {
    /** Save files RomM had no (single) game for, with when it was asked: not asked again for a day. */
    private val notFoundCache = mutableMapOf<String, Long>()

    suspend fun sync(records: MutableMap<String, SaveSyncRecord>, confirmDeletions: Boolean = false): Pair<SaveSyncResult, List<SaveConflict>> {
        val opts = options()
        val listing = store.list()
        val remotes = SaveSyncPlanner.latestPerName(listing.topFolders.keys.flatMap { server.list(it) })
        // Files whose size or time moved are read once to see whether their bytes did (the manifest's base hash).
        val localHashes = hashChanged(listing.files, records)
        val planned = SaveSyncPlanner.plan(
            listing.files, remotes, records.values.toList(), listing.topFolders, SaveMerge.mirrorsDeletions(syncDeletions(), opts),
            listing.noRootFolder, listing.holdSharedCards, listing.syncedBundles, localHashes
        )
        // A lot of deletions at once usually means something is wrong (a folder emptied, a wrong
        // server), not that the user cleaned up: hold them back until the user says yes.
        val deletions = planned.count { it is SaveSyncAction.DeleteRemote || it is SaveSyncAction.DeleteLocal }
        val held = !confirmDeletions && deletions > maxOf(MIN_DELETIONS_WITHOUT_ASKING, records.size / 4)
        val actions = if (held) planned.filterNot { it is SaveSyncAction.DeleteRemote || it is SaveSyncAction.DeleteLocal } else planned

        var uploaded = 0; var downloaded = 0; var unchanged = 0; var notMatched = listing.tooLarge; var failed = 0
        var deletedOnDevice = 0; var deletedOnServer = 0; var keptAsIs = 0; var serverCopies = 0
        val conflicts = mutableListOf<SaveConflict>()
        val errors = listing.problems.take(5).toMutableList()
        val unmatched = mutableListOf<String>()
        val transfers = actions.count { it.isTransfer() }
        var done = 0

        for (action in actions) {
            currentCoroutineContext().ensureActive()
            if (action.isTransfer()) onProgress(++done, transfers)
            try {
                when (action) {
                    is SaveSyncAction.InSync -> {
                        unchanged++
                        // A time that moved with the same bytes: the snapshot catches up, so the file is not read again.
                        records[key(action.local)]?.takeIf { SaveMerge.needsRefresh(it, action.local, action.remote) }
                            ?.let { records[key(action.local)] = SaveMerge.refreshed(it, action.local, action.remote) }
                    }
                    is SaveSyncAction.Conflict -> when (SaveMerge.onDiffer(action.local, action.remote, opts)) {
                        SaveMerge.OnDiffer.ASK -> conflicts += SaveConflict(action.local, action.remote)
                        SaveMerge.OnDiffer.TAKE_SERVER -> { download(action.remote, action.local.path, action.local, records); downloaded++ }
                        SaveMerge.OnDiffer.KEEP_BOTH -> { keepBoth(action.local, action.remote, records); serverCopies++ }
                    }
                    is SaveSyncAction.Ambiguous -> { notMatched++; if (action.local.layout != SaveFolderLayout.FILES && unmatched.size < MAX_UNMATCHED_LISTED) unmatched += action.local.path }
                    is SaveSyncAction.Blocked -> notMatched++
                    is SaveSyncAction.DeleteRemote -> {
                        server.delete(action.remote)
                        records.remove(SaveSyncPlanner.key(action.record.kind, action.record.path))
                        deletedOnServer++
                    }
                    is SaveSyncAction.DeleteLocal -> {
                        store.backup(action.local)
                        currentCoroutineContext().ensureActive()
                        store.delete(action.local)
                        records.remove(SaveSyncPlanner.key(action.record.kind, action.record.path))
                        deletedOnDevice++
                    }
                    is SaveSyncAction.Download -> when {
                        action.replacing == null || SaveMerge.replacesDevice(opts) -> { download(action.remote, action.path, action.replacing, records); downloaded++ }
                        else -> { if (keepBoth(action.replacing, action.remote, records, keepBase = true)) serverCopies++ else keptAsIs++ }
                    }
                    is SaveSyncAction.Upload -> {
                        val romId = action.romId ?: findRom(action.local)
                        if (romId == null) {
                            notMatched++
                            if (action.local.layout != SaveFolderLayout.FILES && unmatched.size < MAX_UNMATCHED_LISTED) unmatched += action.local.path
                        } else if (action.replacing != null && !SaveMerge.replacesServer(opts)) {
                            keptAsIs++
                        } else { upload(action.local, romId, records, action.replacing); uploaded++ }
                    }
                    is SaveSyncAction.Compare -> {
                        val localBytes = store.read(action.local)
                        val localHash = md5(localBytes)
                        val same = action.remote.contentHash?.let { it.equals(localHash, ignoreCase = true) }
                            ?: server.download(action.remote).contentEquals(localBytes)
                        if (same) {
                            records[key(action.local)] = record(action.local, action.remote, localHash)
                            unchanged++
                        } else when (SaveMerge.onDiffer(action.local, action.remote, opts)) {
                            SaveMerge.OnDiffer.ASK -> conflicts += SaveConflict(action.local, action.remote)
                            SaveMerge.OnDiffer.TAKE_SERVER -> { download(action.remote, action.local.path, action.local, records); downloaded++ }
                            SaveMerge.OnDiffer.KEEP_BOTH -> { keepBoth(action.local, action.remote, records); serverCopies++ }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failed++
                if (errors.size < 5) errors += "${action.fileName()}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        // Records of files gone from both sides would only grow the file. Files this sync
        // downloaded are not in the listing, but their server copy is.
        val livePaths = listing.files.map(::key).toSet()
        val liveRemotes = remotes.map { it.kind to it.id }.toSet()
        records.entries.retainAll { (k, r) -> k in livePaths || (r.kind to r.remoteId) in liveRemotes }

        val result = SaveSyncResult(
            clock(), uploaded, downloaded, unchanged, conflicts.size, notMatched, failed, errors,
            deletedOnDevice, deletedOnServer, if (held) deletions else 0, unmatched, keptAsIs, serverCopies
        )
        return result to conflicts
    }

    /** Keeps the device copy (uploads it) or the server copy (downloads it) of [conflict]; the copy that is replaced is backed up first. */
    suspend fun resolve(conflict: SaveConflict, keepDevice: Boolean, records: MutableMap<String, SaveSyncRecord>) {
        val local = freshLocal(conflict)
        // The conflict was found earlier: the server save may have changed since, so the copy kept first is of what it holds now.
        val current = server.list(conflict.remote.kind).firstOrNull { it.id == conflict.remote.id } ?: conflict.remote
        if (keepDevice) upload(local, current.romId, records, current)
        else download(conflict.remote, local.path, local, records)
    }

    /**
     * Keeps both copies of [conflict]: nothing is replaced. The server version is set aside as a safety
     * copy on the device and both sides are noted as seen (see [SaveMerge.keepBothRecord]).
     */
    suspend fun resolveKeepBoth(conflict: SaveConflict, records: MutableMap<String, SaveSyncRecord>) {
        keepBoth(freshLocal(conflict), conflict.remote, records)
    }

    /**
     * Settles [conflicts] with one [choice] for all of them: the device and the server are each listed
     * once for the whole batch (a conflict resolved alone lists both again), then every file gets the
     * same safety as [resolve] (the copy that is replaced is kept first). A file that fails stays a
     * conflict and does not stop the others. With [BatchChoice.NEWER] a conflict whose newer side is
     * not known from the times is left for the user.
     */
    suspend fun resolveAll(conflicts: List<SaveConflict>, choice: BatchChoice, records: MutableMap<String, SaveSyncRecord>): BatchOutcome {
        if (conflicts.isEmpty()) return BatchOutcome(emptyList(), emptyList(), emptyList())
        val locals = store.list().files.associateBy { key(it) }
        val remotes = conflicts.map { it.remote.kind }.distinct().flatMap { server.list(it) }.associateBy { it.kind to it.id }
        val resolved = mutableListOf<SaveConflict>(); val skipped = mutableListOf<SaveConflict>(); val errors = mutableListOf<String>()
        for (conflict in conflicts) {
            currentCoroutineContext().ensureActive()
            val keepDevice = when (choice) {
                BatchChoice.DEVICE -> true
                BatchChoice.SERVER -> false
                BatchChoice.KEEP_BOTH -> null
                BatchChoice.NEWER -> when (SaveConflictInfo.of(conflict).newer) {
                    NewerSide.DEVICE -> true
                    NewerSide.SERVER -> false
                    NewerSide.SAME, NewerSide.UNKNOWN -> { skipped += conflict; continue }
                }
            }
            try {
                val local = locals[key(conflict.local)] ?: throw IOException("${conflict.local.name} is no longer on the device")
                when (keepDevice) {
                    null -> keepBoth(local, conflict.remote, records)
                    true -> (remotes[conflict.remote.kind to conflict.remote.id] ?: conflict.remote).let { upload(local, it.romId, records, it) }
                    false -> download(conflict.remote, local.path, local, records)
                }
                resolved += conflict
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                skipped += conflict
                if (errors.size < 5) errors += "${conflict.local.name}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        return BatchOutcome(resolved, skipped, errors)
    }

    private suspend fun freshLocal(conflict: SaveConflict): LocalSaveFile =
        store.list().files.firstOrNull { it.kind == conflict.local.kind && it.path == conflict.local.path }
            ?: throw IOException("${conflict.local.name} is no longer on the device")

    /** The files whose size or time differ from their record, hashed (a read that fails leaves the file to the size-and-time rules). */
    private suspend fun hashChanged(files: List<LocalSaveFile>, records: Map<String, SaveSyncRecord>): Map<String, String> {
        val out = HashMap<String, String>()
        for (file in files) {
            if (!SaveMerge.needsHash(file, records[key(file)])) continue
            currentCoroutineContext().ensureActive()
            try { out[key(file)] = md5(store.read(file)) }
            catch (e: Exception) { if (e is CancellationException) throw e }
        }
        return out
    }

    private suspend fun upload(local: LocalSaveFile, romId: Int, records: MutableMap<String, SaveSyncRecord>, replacing: RemoteSaveFile? = null) {
        val bytes = store.read(local)
        val hash = md5(bytes)
        // The server's save is about to be replaced: what it holds now is kept on the device first (if that fails, nothing is sent).
        if (replacing != null && !(replacing.contentHash?.equals(hash, ignoreCase = true) ?: false)) {
            val old = server.download(replacing)
            if (!old.contentEquals(bytes)) store.keepCopy(replacing.kind, local.path, old)
        }
        currentCoroutineContext().ensureActive()
        val stored = server.upload(local.kind, romId, local.name, SaveSyncPlanner.emulatorFor(local), bytes)
            ?: server.list(local.kind).filter { it.romId == romId && it.fileName.equals(local.name, ignoreCase = true) }
                .maxByOrNull { SaveSyncPlanner.epochMillis(it.updatedAt) ?: Long.MIN_VALUE }
            ?: throw IOException("RomM did not confirm the upload")
        records[key(local)] = record(local, stored, hash)
    }

    private suspend fun download(remote: RemoteSaveFile, path: String, replacing: LocalSaveFile?, records: MutableMap<String, SaveSyncRecord>) {
        val bytes = server.download(remote)
        if (replacing != null) store.backup(replacing)
        currentCoroutineContext().ensureActive()
        val written = store.write(remote.kind, path, bytes)
        records[key(written)] = record(written, remote, md5(bytes))
    }

    /**
     * Sets the server version aside as a safety copy and notes both sides as seen; nothing is replaced.
     * With [keepBase] (a never-overwrite download of an unchanged device file) the record keeps the old
     * server snapshot, so the newer server save stays a pending download for when replacing is allowed again,
     * and a version already set aside is not written again. Returns whether a copy was written.
     */
    private suspend fun keepBoth(local: LocalSaveFile, remote: RemoteSaveFile, records: MutableMap<String, SaveSyncRecord>, keepBase: Boolean = false): Boolean {
        val old = if (keepBase) records[key(local)] else null
        val signature = "${key(local)}|${remote.id}|${remote.updatedAt}|${remote.size}|${remote.contentHash}"
        var copied = false
        if (old == null || signature !in keptCopies) {
            val bytes = server.download(remote)
            store.keepCopy(remote.kind, local.path, bytes)
            copied = true
        }
        currentCoroutineContext().ensureActive()
        if (old != null) {
            keptCopies += signature
            records[key(local)] = old.copy(localSize = local.size, localModified = local.modified)
        } else {
            records[key(local)] = SaveMerge.keepBothRecord(local, remote)
        }
        return copied
    }

    /** Server versions already set aside this session by a never-overwrite sync (so each is copied once). */
    private val keptCopies = HashSet<String>()

    private suspend fun findRom(local: LocalSaveFile): Int? {
        // A shared memory card or a Dolphin bundle goes with the game the user chose for it, or nowhere.
        if (EmulatorSaveLayouts.isChosenGameOnly(local)) return local.fixedRomId
        val cacheKey = key(local)
        val askedAt = notFoundCache[cacheKey]
        if (askedAt != null && clock() - askedAt < NOT_FOUND_RETRY_MS) return null
        for (term in EmulatorSaveLayouts.searchTerms(local)) {
            when (val match = EmulatorSaveLayouts.matchRom(local, server.searchRoms(term))) {
                is SaveSyncPlanner.RomMatch.Found -> return match.romId
                // The game is there more than once: another search will not make it single.
                SaveSyncPlanner.RomMatch.Ambiguous -> break
                SaveSyncPlanner.RomMatch.NotFound -> Unit
            }
        }
        notFoundCache[cacheKey] = clock()
        return null
    }

    private fun SaveSyncAction.isTransfer() =
        this is SaveSyncAction.Upload || this is SaveSyncAction.Download || this is SaveSyncAction.Compare

    private fun SaveSyncAction.fileName(): String = when (this) {
        is SaveSyncAction.Download -> remote.fileName
        is SaveSyncAction.Upload -> local.name
        is SaveSyncAction.Compare -> local.name
        is SaveSyncAction.Conflict -> local.name
        is SaveSyncAction.InSync -> local.name
        is SaveSyncAction.Ambiguous -> local.name
        is SaveSyncAction.Blocked -> remote.fileName
        is SaveSyncAction.DeleteRemote -> remote.fileName
        is SaveSyncAction.DeleteLocal -> local.name
    }

    companion object {
        private const val NOT_FOUND_RETRY_MS = 24 * 60 * 60 * 1000L
        /** Up to this many deletions (or a quarter of all synced files, if more) go through without asking. */
        private const val MIN_DELETIONS_WITHOUT_ASKING = 3
        /** Unmatched memory cards / save folders named in the result (the count covers all). */
        private const val MAX_UNMATCHED_LISTED = 10

        fun key(local: LocalSaveFile) = SaveSyncPlanner.key(local.kind, local.path)

        fun record(local: LocalSaveFile, remote: RemoteSaveFile, baseHash: String? = null) =
            SaveSyncRecord(local.kind, local.path, remote.romId, remote.id, remote.updatedAt, local.size, local.modified, remote.size, remote.contentHash, baseHash)

        /** RomM's `content_hash` of a save: the MD5 of its bytes, in hex. */
        fun md5(bytes: ByteArray): String =
            MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
