package com.tufein.valkyr.util

import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException

/**
 * 6.0 Folder mode (Syncthing): the saves and states of this device are mirrored to and from a folder
 * the user picked and Syncthing syncs. There is no network code: Syncthing moves the folder between
 * devices, this only reads and writes plain files in it.
 *
 * Inside the folder the files sit at `saves/<path>` and `states/<path>` (the same path a RomM sync
 * uses below the picked folders). The device side is the same [SaveStore] the RomM sync uses, and the
 * three-way merge is [SaveMerge.verdict] with the safety rules of the RomM sync:
 *  - a file that exists on both sides is only replaced after a verified safety copy of what is replaced
 *    (the device file through [SaveStore.backup], the folder file through [SaveStore.keepCopy]);
 *  - a file changed on both sides is a conflict that nothing touches until the user chooses;
 *  - "never overwrite" ([SaveSyncOptions.nonDestructive]) only adds files that are missing;
 *  - nothing is ever deleted, on either side, by a sync.
 *
 * Syncthing's own conflict copies (`name.sync-conflict-20190101-120000-ABCDEFG.ext`, per
 * https://docs.syncthing.net/users/syncing.html, read 2026-11-01) are listed as conflicts and left
 * in place until the user chooses; even then they are not deleted. Nothing here was tried on a device.
 */
object SyncthingNames {
    private val conflict = Regex("""^(.*)\.sync-conflict-(\d{8})-(\d{6})(?:-([A-Za-z0-9]+))?(\..*)?$""")

    /** A Syncthing conflict copy's name taken apart; the original name has the marker removed. */
    data class ConflictName(val original: String, val date: String, val time: String, val device: String?)

    fun conflictOf(name: String): ConflictName? {
        val m = conflict.matchEntire(name) ?: return null
        val base = m.groupValues[1]
        if (base.isEmpty()) return null
        return ConflictName(base + m.groupValues[5], m.groupValues[2], m.groupValues[3], m.groupValues[4].ifEmpty { null })
    }

    fun isConflict(name: String) = conflictOf(name) != null

    /** Syncthing's temporary files (`.syncthing.<name>.tmp`, `~syncthing~<name>.tmp`) and this app's staging files. */
    fun isTemporary(name: String): Boolean =
        name.startsWith(".syncthing.") || name.startsWith("~syncthing~") || name.startsWith(".dogmatix-")

    /** Syncthing's own folders and files at the root of a synced folder. */
    fun isMarker(name: String): Boolean = name == ".stfolder" || name == ".stversions" || name == ".stignore" || name == ".stglobalignore"

    /** `2019-01-01 12:00:00` style stamp of a conflict name, for the list. */
    fun stamp(c: ConflictName): String =
        "${c.date.substring(0, 4)}-${c.date.substring(4, 6)}-${c.date.substring(6, 8)} ${c.time.substring(0, 2)}:${c.time.substring(2, 4)}:${c.time.substring(4, 6)}"

    /** The folder inside the Syncthing folder for [kind]. */
    fun folderOf(kind: SaveKind): String = if (kind == SaveKind.STATE) "states" else "saves"

    fun kindOfFolder(name: String): SaveKind? = when (name.lowercase(Locale.ROOT)) {
        "saves" -> SaveKind.SAVE
        "states" -> SaveKind.STATE
        else -> null
    }
}

/** A file in the Syncthing folder, path below it (`saves/mGBA/Game.srm`). */
data class FolderFile(val path: String, val size: Long, val modified: Long)

/** The Syncthing folder: strict listing (a listing that cannot be read throws), atomic writes. */
interface SyncFolder {
    /** Every file below the folder; must throw when any part cannot be listed, never return a partial list. */
    suspend fun list(): List<FolderFile>
    suspend fun read(file: FolderFile): ByteArray
    /** Writes [bytes] to [path] (creating folders) atomically and returns the file as now on disk. */
    suspend fun write(path: String, bytes: ByteArray): FolderFile
}

/** What the two sides held for one save when it was last synced (reuses [SaveSyncRecord]: the folder is the "remote"). */
typealias FolderRecords = MutableMap<String, SaveSyncRecord>

/** A save that needs the user: [this] and [other] are the two versions, named in the list. */
data class FolderConflict(
    val kind: SaveKind,
    /** The save's path below the saves / states folder. */
    val path: String,
    val type: Type,
    val local: LocalSaveFile?,
    val folderFile: FolderFile,
    /** For [Type.SYNCTHING_COPY]: Syncthing's conflict copy. */
    val copy: FolderFile? = null,
    val copyStamp: String? = null
) {
    /** DEVICE_VS_FOLDER: this device's file and the folder's file both changed. SYNCTHING_COPY: Syncthing kept two versions in the folder. */
    enum class Type { DEVICE_VS_FOLDER, SYNCTHING_COPY }

    val id: String get() = type.name + "|" + (copy?.path ?: folderFile.path) + "|" + (copy?.size ?: folderFile.size) + "|" + (copy?.modified ?: folderFile.modified)
}

enum class FolderChoice { KEEP_THIS, KEEP_OTHER, KEEP_BOTH }

data class FolderSyncResult(
    val finishedAt: Long,
    val toFolder: Int = 0,
    val toDevice: Int = 0,
    val unchanged: Int = 0,
    val conflicts: Int = 0,
    /** Changed saves left as they are because "never overwrite" is on. */
    val keptAsIs: Int = 0,
    val failed: Int = 0,
    val errors: List<String> = emptyList()
)

class FolderSaveSync(
    private val store: SaveStore,
    private val folder: SyncFolder,
    private val clock: () -> Long = System::currentTimeMillis,
    private val options: suspend () -> SaveSyncOptions = { SaveSyncOptions() }
) {
    private fun key(kind: SaveKind, path: String) = SaveSyncPlanner.key(kind, path)

    private class Listed(val files: Map<String, Pair<SaveKind, FolderFile>>, val conflictCopies: List<Triple<SaveKind, FolderFile, SyncthingNames.ConflictName>>)

    private suspend fun listFolder(): Listed {
        val files = LinkedHashMap<String, Pair<SaveKind, FolderFile>>()
        val copies = mutableListOf<Triple<SaveKind, FolderFile, SyncthingNames.ConflictName>>()
        for (f in folder.list()) {
            val parts = f.path.split('/')
            if (parts.size < 2 || parts.any { it.isEmpty() || it == ".." || it == "." }) continue
            val kind = SyncthingNames.kindOfFolder(parts.first()) ?: continue
            if (parts.drop(1).any { SyncthingNames.isTemporary(it) || SyncthingNames.isMarker(it) }) continue
            val inner = parts.drop(1).joinToString("/")
            val name = parts.last()
            val conflictName = SyncthingNames.conflictOf(name)
            if (conflictName != null) { copies += Triple(kind, f, conflictName); continue }
            if (!SaveSyncPlanner.isSyncable(name)) continue
            files[key(kind, inner)] = kind to f.copy(path = f.path)
        }
        return Listed(files, copies)
    }

    /** One pass. [handled] holds the ids ([FolderConflict.id]) of Syncthing copies the user has already decided on. */
    suspend fun sync(records: FolderRecords, handled: Set<String> = emptySet()): Pair<FolderSyncResult, List<FolderConflict>> {
        val opts = options()
        val listing = store.list()
        val listed = listFolder()
        val locals = listing.files.filter { SaveSyncPlanner.isSyncable(it.name) && it.layout == SaveFolderLayout.FILES }.associateBy { key(it.kind, it.path) }

        var toFolder = 0; var toDevice = 0; var unchanged = 0; var keptAsIs = 0; var failed = 0
        val errors = mutableListOf<String>()
        val conflicts = mutableListOf<FolderConflict>()

        for (k in (locals.keys + listed.files.keys).toSortedSet()) {
            val local = locals[k]
            val remoteEntry = listed.files[k]
            val record = records[k]
            val name = local?.path ?: remoteEntry!!.second.path.substringAfter('/')
            try {
                when {
                    local != null && remoteEntry == null -> {
                        // Missing in the folder (never synced, or deleted there): the device's file is copied in, nothing is removed.
                        val bytes = store.read(local)
                        val written = folder.write(SyncthingNames.folderOf(local.kind) + "/" + local.path, bytes)
                        records[k] = recordOf(local, written, SaveSyncEngine.md5(bytes))
                        toFolder++
                    }
                    local == null && remoteEntry != null -> {
                        val (kind, file) = remoteEntry
                        val bytes = folder.read(file)
                        val written = store.write(kind, name, bytes)
                        records[k] = recordOf(written, file, SaveSyncEngine.md5(bytes))
                        toDevice++
                    }
                    local != null && remoteEntry != null -> {
                        val file = remoteEntry.second
                        val localHash = if (record == null || SaveMerge.needsHash(local, record)) SaveSyncEngine.md5(store.read(local)) else record.baseHash
                        val folderTouched = record == null || record.remoteSize != file.size || record.remoteUpdatedAt != file.modified.toString()
                        val folderBytes = if (record?.baseHash == null || folderTouched) folder.read(file) else null
                        val folderHash = folderBytes?.let(SaveSyncEngine::md5) ?: record?.baseHash
                        val verdict = if (record == null) {
                            if (SaveMerge.sameHash(localHash, folderHash)) SaveMerge.Verdict.CONVERGED else SaveMerge.Verdict.CONFLICT
                        } else SaveMerge.verdict(record.baseHash, localHash, folderHash, SaveSyncPlanner.localChanged(local, record), folderTouched)
                        when (verdict) {
                            SaveMerge.Verdict.UNCHANGED -> {
                                unchanged++
                                if (record != null && (record.remoteSize != file.size || record.remoteUpdatedAt != file.modified.toString() || record.localSize != local.size || record.localModified != local.modified))
                                    records[k] = record.copy(remoteSize = file.size, remoteUpdatedAt = file.modified.toString(), localSize = local.size, localModified = local.modified)
                            }
                            SaveMerge.Verdict.CONVERGED -> { records[k] = recordOf(local, file, localHash); unchanged++ }
                            SaveMerge.Verdict.PUSH -> if (!SaveMerge.replacesServer(opts)) keptAsIs++ else {
                                pushReplacing(local, file, folderBytes ?: folder.read(file), records)
                                toFolder++
                            }
                            SaveMerge.Verdict.PULL -> if (!SaveMerge.replacesDevice(opts)) keptAsIs++ else {
                                pullReplacing(local, remoteEntry.first, file, folderBytes ?: folder.read(file), records)
                                toDevice++
                            }
                            SaveMerge.Verdict.CONFLICT -> conflicts += FolderConflict(local.kind, local.path, FolderConflict.Type.DEVICE_VS_FOLDER, local, file)
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failed++
                if (errors.size < 5) errors += "$name: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        // Syncthing's own conflict copies: listed until the user decides; the decided ones are not listed again.
        for ((kind, copy, c) in listed.conflictCopies) {
            val inner = copy.path.substringAfter('/').let { it.substringBeforeLast('/', "").let { dir -> if (dir.isEmpty()) c.original else "$dir/${c.original}" } }
            val original = listed.files[key(kind, inner)]?.second ?: continue
            val item = FolderConflict(kind, inner, FolderConflict.Type.SYNCTHING_COPY, locals[key(kind, inner)], original, copy, SyncthingNames.stamp(c))
            if (item.id !in handled) conflicts += item
        }
        records.entries.retainAll { (k, _) -> k in locals.keys || k in listed.files.keys }
        return FolderSyncResult(clock(), toFolder, toDevice, unchanged, conflicts.size, keptAsIs, failed, errors) to conflicts
    }

    /** The device file goes into the folder; what the folder held is kept as a verified safety copy first. */
    private suspend fun pushReplacing(local: LocalSaveFile, file: FolderFile, folderBytes: ByteArray, records: FolderRecords) {
        val bytes = store.read(local)
        store.keepCopy(local.kind, local.path, folderBytes)
        val written = folder.write(file.path, bytes)
        records[key(local.kind, local.path)] = recordOf(local, written, SaveSyncEngine.md5(bytes))
    }

    /** The folder's file goes onto the device; the device file is backed up first (a failed backup changes nothing). */
    private suspend fun pullReplacing(local: LocalSaveFile, kind: SaveKind, file: FolderFile, folderBytes: ByteArray, records: FolderRecords) {
        store.backup(local)
        val written = store.write(kind, local.path, folderBytes)
        records[key(kind, local.path)] = recordOf(written, file, SaveSyncEngine.md5(folderBytes))
    }

    /**
     * Applies the user's choice. KEEP_THIS makes [FolderConflict]'s first version the one both sides hold, KEEP_OTHER the
     * second, KEEP_BOTH replaces nothing and keeps the other version as a safety copy. Syncthing's
     * conflict copies are never deleted; the caller remembers [FolderConflict.id] as decided.
     *
     * DEVICE_VS_FOLDER: this = the device's file, other = the folder's. SYNCTHING_COPY: this = Syncthing's
     * conflict copy, other = the file in place.
     */
    suspend fun resolve(conflict: FolderConflict, choice: FolderChoice, records: FolderRecords) {
        val k = key(conflict.kind, conflict.path)
        when (conflict.type) {
            FolderConflict.Type.DEVICE_VS_FOLDER -> {
                val local = store.list().files.firstOrNull { it.kind == conflict.kind && it.path == conflict.path }
                    ?: throw IOException("${conflict.path} is no longer on the device")
                val current = listFolder().files[k]?.second ?: throw IOException("${conflict.path} is no longer in the folder")
                val folderBytes = folder.read(current)
                when (choice) {
                    FolderChoice.KEEP_THIS -> pushReplacing(local, current, folderBytes, records)
                    FolderChoice.KEEP_OTHER -> pullReplacing(local, conflict.kind, current, folderBytes, records)
                    FolderChoice.KEEP_BOTH -> {
                        store.keepCopy(conflict.kind, conflict.path, folderBytes)
                        records[k] = recordOf(local, current, null)
                    }
                }
            }
            FolderConflict.Type.SYNCTHING_COPY -> {
                val copy = conflict.copy ?: throw IOException("No conflict copy")
                val listed = listFolder()
                if (listed.conflictCopies.none { it.second.path == copy.path }) throw IOException("The conflict copy is gone")
                val current = listed.files[k]?.second ?: throw IOException("${conflict.path} is no longer in the folder")
                val copyBytes = folder.read(copy)
                val currentBytes = folder.read(current)
                when (choice) {
                    FolderChoice.KEEP_THIS -> {
                        store.keepCopy(conflict.kind, conflict.path, currentBytes)
                        // The record stays: the device still holds the old base, so the next sync brings the new content to it (backed up first).
                        folder.write(current.path, copyBytes)
                    }
                    FolderChoice.KEEP_OTHER, FolderChoice.KEEP_BOTH -> store.keepCopy(conflict.kind, conflict.path, copyBytes)
                }
            }
        }
    }

    private fun recordOf(local: LocalSaveFile, file: FolderFile, baseHash: String?) = SaveSyncRecord(
        kind = local.kind, path = local.path, romId = 0, remoteId = 0, remoteUpdatedAt = file.modified.toString(),
        localSize = local.size, localModified = local.modified, remoteSize = file.size, remoteHash = baseHash, baseHash = baseHash
    )
}

/** Settings keys of Folder mode, for the settings backup ([SettingSchema]). */
object SyncthingFolderSettings {
    const val KEY_MODE = "syncthing_mode"
    const val KEY_DIR = "syncthing_dir"
    val TYPES: Map<String, String> = mapOf(KEY_MODE to "b", KEY_DIR to "s")
}
