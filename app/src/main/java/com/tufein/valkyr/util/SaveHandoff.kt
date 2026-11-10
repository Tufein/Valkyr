package com.tufein.valkyr.util

import java.security.MessageDigest
import java.io.IOException

enum class SaveHandoffDirection { UPLOAD, DOWNLOAD, IDENTICAL, CONFLICT, BLOCKED }
data class SaveHandoffFile(
    val path: String,
    val direction: SaveHandoffDirection,
    val local: LocalSaveFile? = null,
    val remote: RemoteSaveFile? = null,
    val localSha256: String? = null,
    val remoteSha256: String? = null
)
data class SaveHandoffPreview(
    val key: JournalKey,
    val romId: Int,
    val configuration: String,
    val createdAt: Long,
    val files: List<SaveHandoffFile>,
    val omittedStates: Int = 0,
    val backupVerified: Boolean = false
) {
    val canTransfer: Boolean get() = files.isNotEmpty() && files.none { it.direction == SaveHandoffDirection.CONFLICT || it.direction == SaveHandoffDirection.BLOCKED }
    val ready: Boolean get() = backupVerified && canTransfer && files.all { it.direction == SaveHandoffDirection.IDENTICAL }
}

/** Every ordinary save is compared by actual bytes, even if timestamps claim it is synchronized. */
object SaveHandoff {
    const val MAX_FILES = 32
    /** Multipart uploads and verified backups coexist in memory on smaller handheld heaps. */
    const val MAX_SAVE_BYTES = 16L * 1024 * 1024
    const val PREVIEW_TTL_MS = 5 * 60 * 1000L
    private val ordinaryExtensions = setOf("srm", "sav", "dsv", "sra", "eep", "fla", "mcr", "mcd", "gci", "mem", "rtc", "sram")
    fun isInGameSave(fileName: String): Boolean =
        (fileName.substringAfterLast('.', "").lowercase() in ordinaryExtensions || EmulatorSaveLayouts.isPackName(fileName)) &&
            // A shared memory card holds every game's saves; it is never handed over with one game.
            !EmulatorSaveLayouts.isSharedCard(fileName)
    class SaveTooLargeException : IOException("Device handoff supports in-game saves up to 16 MiB. Use ordinary save synchronization for larger files.")
    fun requireBoundedSave(bytes: Long) { if (bytes > MAX_SAVE_BYTES) throw SaveTooLargeException() }
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun plan(listing: SaveStore.Listing, remotes: List<RemoteSaveFile>, records: Collection<SaveSyncRecord>,
        localHashes: Map<String, String>, remoteHashes: Map<Int, String>): List<SaveHandoffFile> {
        require(listing.files.all { it.kind == SaveKind.SAVE && isInGameSave(it.name) } && remotes.all { it.kind == SaveKind.SAVE && isInGameSave(it.fileName) }) { "Only recognized in-game saves can be handed off" }
        listing.files.forEach { requireBoundedSave(it.size) }
        remotes.forEach { requireBoundedSave(it.size) }
        require(listing.files.size + remotes.size <= MAX_FILES * 2)
        val actions = SaveSyncPlanner.plan(listing.files, remotes, records.toList(), listing.topFolders, false, listing.noRootFolder)
        require(actions.size <= MAX_FILES) { "Too many save files for one game" }
        return actions.map { action ->
            val local = when (action) {
                is SaveSyncAction.Upload -> action.local
                is SaveSyncAction.Download -> action.replacing
                is SaveSyncAction.Compare -> action.local
                is SaveSyncAction.Conflict -> action.local
                is SaveSyncAction.InSync -> action.local
                is SaveSyncAction.Ambiguous -> action.local
                else -> null
            }
            val remote = when (action) {
                is SaveSyncAction.Upload -> action.replacing
                is SaveSyncAction.Download -> action.remote
                is SaveSyncAction.Compare -> action.remote
                is SaveSyncAction.Conflict -> action.remote
                is SaveSyncAction.InSync -> action.remote
                is SaveSyncAction.Blocked -> action.remote
                else -> null
            }
            val path = when (action) { is SaveSyncAction.Download -> action.path; is SaveSyncAction.Blocked -> action.path; else -> local?.path ?: remote?.fileName.orEmpty() }
            val localHash = local?.let { requireNotNull(localHashes[it.path]) }
            val remoteHash = remote?.let { requireNotNull(remoteHashes[it.id]) }
            val direction = when {
                action is SaveSyncAction.Blocked || action is SaveSyncAction.Ambiguous -> SaveHandoffDirection.BLOCKED
                localHash != null && localHash == remoteHash -> SaveHandoffDirection.IDENTICAL
                action is SaveSyncAction.Upload -> SaveHandoffDirection.UPLOAD
                action is SaveSyncAction.Download -> SaveHandoffDirection.DOWNLOAD
                else -> SaveHandoffDirection.CONFLICT
            }
            SaveHandoffFile(path, direction, local, remote, localHash, remoteHash)
        }.sortedBy { it.path }
    }

    fun requireFresh(expected: SaveHandoffPreview, actual: SaveHandoffPreview, now: Long) {
        require(now >= expected.createdAt && now - expected.createdAt <= PREVIEW_TTL_MS) { "Check the saves again before transferring" }
        require(fingerprint(expected) == fingerprint(actual)) { "Saves, profile or folders changed; check them again" }
        require(actual.canTransfer) { "Resolve save conflicts before transferring" }
    }

    /** Length-prefixed fields keep arbitrary game/folder names unambiguous without reflection. */
    fun fingerprint(preview: SaveHandoffPreview): String {
        val fields = mutableListOf(preview.key.profileId, preview.key.consoleId, preview.key.fileName, preview.romId.toString(), preview.configuration)
        preview.files.sortedBy { it.path }.forEach { row ->
            fields += listOf(row.path, row.direction.name, row.localSha256.orEmpty(), row.remoteSha256.orEmpty(),
                row.local?.size.toString(), row.local?.modified.toString(), row.remote?.id.toString(), row.remote?.updatedAt.orEmpty(), row.remote?.size.toString())
        }
        return sha256(fields.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))
    }
}
