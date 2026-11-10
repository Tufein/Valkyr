package com.tufein.valkyr.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.ColumnInfo

/**
 * A game the user starred. Keyed by console + file name (not by `downloadable_files.id`)
 * so favourites survive the wholesale re-index that every rescan performs.
 */
@Entity(tableName = "favourites", primaryKeys = ["consoleId", "fileName", "profileId"], indices = [Index("profileId")])
data class FavouriteEntity(
    val consoleId: String,
    val fileName: String,
    val addedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val profileId: String = ""
) {
    val key: String get() = key(consoleId, fileName)

    companion object {
        fun key(consoleId: String, fileName: String): String = "$consoleId|$fileName"
    }
}
