package com.tufein.valkyr.util

/** Which side of a save conflict looks newer, judged from the file times (clocks may differ). */
enum class NewerSide { DEVICE, SERVER, SAME, UNKNOWN }

/** What the conflict screen shows so the user can choose: both times, both sizes and a hint. */
data class ConflictInfo(
    val deviceModified: Long?,
    val serverModified: Long?,
    val deviceSize: Long,
    val serverSize: Long,
    val newer: NewerSide,
    /** Server size minus device size; a much smaller file than before often means progress was lost. */
    val sizeDelta: Long
) {
    val sameSize: Boolean get() = sizeDelta == 0L
}

object SaveConflictInfo {
    /** Times closer than this count as the same moment. */
    private const val SAME_WINDOW_MS = 2_000L

    fun of(conflict: SaveConflict): ConflictInfo = of(conflict.local, conflict.remote)

    fun of(local: LocalSaveFile, remote: RemoteSaveFile): ConflictInfo {
        val device = local.modified.takeIf { it > 0 }
        val server = SaveSyncPlanner.epochMillis(remote.updatedAt)
        val newer = when {
            device == null || server == null -> NewerSide.UNKNOWN
            kotlin.math.abs(device - server) <= SAME_WINDOW_MS -> NewerSide.SAME
            device > server -> NewerSide.DEVICE
            else -> NewerSide.SERVER
        }
        return ConflictInfo(device, server, local.size, remote.size, newer, remote.size - local.size)
    }

    /** How a batch of conflicts splits: newer on the device, newer on the server, and those the times do not settle. */
    data class Summary(val deviceNewer: Int, val serverNewer: Int, val unclear: Int)

    fun summary(conflicts: List<SaveConflict>): Summary {
        val sides = conflicts.map { of(it).newer }
        return Summary(sides.count { it == NewerSide.DEVICE }, sides.count { it == NewerSide.SERVER }, sides.count { it == NewerSide.SAME || it == NewerSide.UNKNOWN })
    }
}
