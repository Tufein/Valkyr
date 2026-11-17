package com.tufein.valkyr.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 9.0: a per-game override for title, year, genre and a pinned cover, keyed `consoleId|stem` like
 * covers so it survives rescans. Null fields mean "left automatic".
 */
@Entity(tableName = "game_overrides")
data class GameOverrideEntity(
    @PrimaryKey val key: String,
    val title: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val pinnedCoverUrl: String? = null,
    val updatedAt: Long = 0L
)